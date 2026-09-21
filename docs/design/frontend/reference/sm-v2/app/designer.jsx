// P5 · Designer — ONE component renders every solution's config editor from the
// view model. Single-component solutions (Cache) skip the canvas; multi-component
// ones (File Mesh) get the composer. Scenario switch surfaces P5/b/c/d/e.
const { useState: uS, useMemo: uM, useEffect: uE, useRef: uR } = React;

const u = (section, key) => `i1::${section}::${key}`;
function fromKind(v) {
  if (!v) return undefined;
  if (v.kind === "value") return v.value;
  if (v.kind === "secretUnset") return { secret: false };
  if (v.kind === "secretRef") return { secret: true, ref: v.ref };
  return undefined;
}
function initialValues(model) {
  const out = {};
  model.components.forEach(c => c.fields.forEach(f => { out[f.uid] = fromKind(f.value); }));
  return out;
}
function compGetter(comp, values) { const m = {}; comp.fields.forEach(f => { if (!(f.key in m)) m[f.key] = f.uid; }); return (k) => values[m[k]]; }
function fieldVisible(field, g) { if (field.visible === false) return false; if (field.when) return !!field.when(g); return true; }

const SCENARIOS = [
  { v: "editable", label: "Editable" }, { v: "validation", label: "Validation" }, { v: "blocked", label: "Blocked" },
  { v: "restart", label: "Restart" }, { v: "diverged", label: "Diverged" }, { v: "composer", label: "Composer · File Mesh" },
];

function buildScenario(scenario) {
  if (scenario === "composer") {
    const model = fileMeshModel();
    return { model, initialValues: initialValues(model), saved: false, replicas: [], reached: null, blocked: false };
  }
  const model = cacheModel();
  const iv = initialValues(model);
  // sensible saved baseline so the form is legible + savable
  Object.assign(iv, {
    [u("General", "namespace")]: "ledger", [u("Keying", "key-source")]: "TOPIC",
    [u("Keying", "topic-key-levels")]: "2", [u("Sizing", "max-size")]: 100000,
    [u("Broker", "solace.host")]: "broker-east.acme.example", [u("Broker", "solace.username")]: "svc-ledger",
    [u("Broker", "solace.password")]: { secret: true, ref: "secretRef://kv/acme/ledger-pw" },
  });
  let saved = true, replicas = [{ id: "ledger-0", gen: "gen-41", restartObserved: true, health: "GREEN" }], reached = "restart-observed", blocked = false;

  if (scenario === "validation") {
    model.components[0].fields.find(f => f.key === "namespace").value = Vv.unset();
    iv[u("General", "namespace")] = undefined;                                   // REQUIRED block
    const ms = model.components[0].fields.find(f => f.key === "max-size");
    ms.validations = [{ severity: "warn", code: "ABOVE_RECOMMENDED", message: "Above the range Solace recommends (1–1000000)." }];
    iv[u("Sizing", "max-size")] = 5000000;                                       // warn
    saved = false;
  }
  if (scenario === "blocked") {
    model.components.forEach(c => c.fields.forEach(f => { f.enabled = false; }));
    model.status.version = { state: "VERSION_DRIFT", declared: "2.4", observed: "2.3", editingBlocked: true, reason: "Declared 2.4 but observed 2.3." };
    model.status.healthRollup = "RED"; blocked = true; saved = true; replicas = [];
  }
  if (scenario === "restart") { replicas = ROLLOUT_REPLICAS; reached = "restart-observed"; saved = true; }
  if (scenario === "diverged") { replicas = DIVERGED_REPLICAS; reached = "restart-observed"; model.diverged = true; saved = true; }
  return { model, initialValues: iv, saved, replicas, reached, blocked };
}

// ---- one section (accordion + advanced disclosure) ----
const SectionCard = ({ comp, section, values, initial, onChange, disabled, expanded, onToggle, showComp, showPath, registerRef, forceAll }) => {
  const [advOpen, setAdvOpen] = uS(false);
  const g = compGetter(comp, values);
  const fields = comp.fields.filter(f => f.section === section.title && fieldVisible(f, g));
  const normal = section.allAdvanced ? fields : fields.filter(f => !f.advanced);
  const adv = section.allAdvanced ? [] : fields.filter(f => f.advanced);
  let block = 0, warn = 0;
  fields.forEach(f => { if (f.enabled === false) return; const vs = activeValidations(f, valForVal(f, values[f.uid])); if (vs.some(v => v.severity === "block")) block++; else if (vs.some(v => v.severity === "warn")) warn++; });
  const renderF = (f) => <FieldRow key={f.uid} field={f} val={values[f.uid]} initial={initial[f.uid]} disabled={disabled || f.enabled === false} showPath={showPath} rowRef={registerRef ? registerRef(f.uid) : null} onChange={(nv) => onChange(f.uid, nv)} />;
  return (
    <div className="sect">
      <div className="sect-h" onClick={onToggle} role="button" aria-expanded={expanded}>
        <span className={`expander ${expanded ? "open" : ""}`}><Ic name="chevRight" size={17} /></span>
        <span className="st">{section.title}</span>
        <span className="snum">{fields.length}</span>
        {section.allAdvanced && <Chip tone="neutral" sm>Advanced</Chip>}
        <span className="grow" />
        {block > 0 && <Chip tone="err" sm icon="xCircle">{block}</Chip>}
        {warn > 0 && block === 0 && <Chip tone="warn" sm icon="alert">{warn}</Chip>}
      </div>
      {expanded && (
        <div className="sect-b">
          {normal.length === 0 && adv.length === 0 && <div className="fld"><span className="muted t-sm">No fields apply here with the current selections.</span></div>}
          {normal.map(renderF)}
          {adv.length > 0 && (
            <>
              <button className="adv-toggle" onClick={() => setAdvOpen(o => !o)}><Ic name={advOpen ? "minus" : "plus"} size={14} />{advOpen ? "Hide" : "Show"} {adv.length} advanced field{adv.length === 1 ? "" : "s"}</button>
              {advOpen && adv.map(renderF)}
            </>
          )}
        </div>
      )}
    </div>
  );
};

// ---- flat search results across component(s) ----
const SearchResults = ({ components, values, initial, onChange, disabled, query, registerRef }) => {
  const q = query.toLowerCase();
  const hits = [];
  components.forEach(c => { const g = compGetter(c, values); c.fields.forEach(f => { if (!fieldVisible(f, g)) return; if (`${f.label} ${f.key} ${f.renderedPath || ""} ${f.section}`.toLowerCase().includes(q)) hits.push({ c, f }); }); });
  if (hits.length === 0) return <div className="sect"><div className="sect-b"><div className="fld"><span className="muted">No fields match “{query}”.</span></div></div></div>;
  return (
    <div className="sect mt16"><div className="sect-b">
      <div className="t-cap muted" style={{ padding: "10px 0 2px" }}>{hits.length} field{hits.length === 1 ? "" : "s"} match “{query}”</div>
      {hits.map(({ c, f }) => <FieldRow key={f.uid} field={f} val={values[f.uid]} initial={initial[f.uid]} disabled={disabled || f.enabled === false} showPath crumb={<span className="row gap6" style={{ display: "inline-flex" }}><span className="mono">{c.type.split("@")[0]}</span> · {f.section}</span>} rowRef={registerRef ? registerRef(f.uid) : null} onChange={(nv) => onChange(f.uid, nv)} />)}
    </div></div>
  );
};

const NavList = ({ components, values, onJump, single }) => (
  <>
    {components.map(c => {
      const g = compGetter(c, values);
      return (
        <div key={c.instanceKey}>
          {!single && <div className="comp-h"><Ic name={c.role === "sink" ? "mesh" : "folder"} size={14} />{c.type.split("@")[0]}</div>}
          {c.sections.map(s => {
            const fields = c.fields.filter(f => f.section === s.title && fieldVisible(f, g));
            let block = 0, warn = 0;
            fields.forEach(f => { if (f.enabled === false) return; const vs = activeValidations(f, valForVal(f, values[f.uid])); if (vs.some(v => v.severity === "block")) block++; else if (vs.some(v => v.severity === "warn")) warn++; });
            return (
              <button key={s.title} className="navf" onClick={() => onJump(c.instanceKey, s.title)}>
                <span className="grow" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{s.title}</span>
                {block > 0 ? <span className="nfdot" style={{ background: "var(--error-wMain)" }} /> : warn > 0 ? <span className="nfdot" style={{ background: "var(--warning-wMain)" }} /> : <span className="nfc">{fields.length}</span>}
              </button>
            );
          })}
        </div>
      );
    })}
  </>
);

const DesignerScreen = ({ route, nav }) => {
  const { demo } = window.useDemo();
  const solId = route.parts[1], instId = route.parts[3] || "cache-prod-01";
  const [scenario, setScenario] = uS("editable");
  const built = uM(() => buildScenario(scenario), [scenario]);
  const model = built.model;
  const [values, setValues] = uS(built.initialValues);
  const [initial, setInitial] = uS(built.initialValues);
  const [saved, setSaved] = uS(built.saved);
  const [strategy, setStrategy] = uS("manual");
  const [search, setSearch] = uS("");
  const [expanded, setExpanded] = uS(() => new Set());
  const [flash, setFlash] = uS(null);
  const [modal, setModal] = uS(null); // conflict|reason|apply|restart|accept|restore|rollback
  const [view, setView] = uS(model.single ? "form" : "canvas");
  const [selNode, setSelNode] = uS(model.nodes ? model.nodes[0].instanceKey : null);
  const [nodePos, setNodePos] = uS(() => Object.fromEntries((model.nodes || []).map(n => [n.instanceKey, { x: n.x, y: n.y }])));
  const formRef = uR(null); const rowRefs = uR({});
  const registerRef = (uid) => (el) => { if (el) rowRefs.current[uid] = el; };

  uE(() => {
    setValues(built.initialValues); setInitial(built.initialValues); setSaved(built.saved);
    setStrategy("manual"); setSearch(""); setModal(null); setFlash(null);
    setView(model.single ? "form" : "canvas");
    setSelNode(model.nodes ? model.nodes[0].instanceKey : null);
    setNodePos(Object.fromEntries((model.nodes || []).map(n => [n.instanceKey, { x: n.x, y: n.y }])));
    const ex = new Set();
    model.components.forEach(c => c.sections.forEach(s => { if (!s.allAdvanced) ex.add(c.instanceKey + ":" + s.title); }));
    setExpanded(ex);
  }, [scenario]);
  uE(() => { if (!flash) return; const t = setTimeout(() => setFlash(null), 4000); return () => clearTimeout(t); }, [flash]);

  const onChange = (uid, nv) => { setValues(v => ({ ...v, [uid]: nv })); setSaved(false); };
  const toggle = (key) => setExpanded(s => { const n = new Set(s); n.has(key) ? n.delete(key) : n.add(key); return n; });

  // validation rollup
  const { blocks, warns } = uM(() => {
    const b = [], w = [];
    model.components.forEach(c => { const g = compGetter(c, values); c.fields.forEach(f => { if (f.enabled === false || !fieldVisible(f, g)) return; const vs = activeValidations(f, valForVal(f, values[f.uid])); if (vs.some(v => v.severity === "block")) b.push({ uid: f.uid, label: f.label, message: vs.find(v => v.severity === "block").message }); vs.filter(v => v.severity === "warn").forEach(v => w.push({ uid: f.uid, label: f.label, message: v.message })); }); });
    return { blocks: b, warns: w };
  }, [model, values]);
  const dirty = uM(() => Object.keys(values).filter(k => JSON.stringify(values[k]) !== JSON.stringify(initial[k])).length, [values, initial]);
  const editingBlocked = built.blocked;

  const jump = (ik, sectionTitle) => {
    const key = ik + ":" + sectionTitle; setSearch("");
    setExpanded(s => new Set(s).add(key));
    setTimeout(() => { const first = model.components.find(c => c.instanceKey === ik).fields.find(f => f.section === sectionTitle); if (first && rowRefs.current[first.uid] && formRef.current) formRef.current.scrollTo({ top: rowRefs.current[first.uid].offsetTop - 90, behavior: "smooth" }); }, 40);
  };
  const jumpUid = (uid) => { const c = model.components.find(cc => cc.fields.some(f => f.uid === uid)); const f = c.fields.find(x => x.uid === uid); setSearch(""); setExpanded(s => new Set(s).add(c.instanceKey + ":" + f.section)); setTimeout(() => { if (rowRefs.current[uid] && formRef.current) formRef.current.scrollTo({ top: rowRefs.current[uid].offsetTop - 90, behavior: "smooth" }); }, 40); };

  const doSave = () => {
    if (blocks.length > 0 || editingBlocked || dirty === 0) return;
    if (warns.length > 0) { setModal("reason"); return; }
    setInitial(values); setSaved(true); setFlash({ sev: "ok", msg: "Draft saved to the platform record. This wrote nothing to the MI — use Apply for that." });
  };
  const confirmReason = (reason) => { setInitial(values); setSaved(true); setModal(null); setFlash({ sev: "ok", msg: `Saved with ${warns.length} recommendation${warns.length === 1 ? "" : "s"} overridden — reason recorded in history.` }); };
  const doApply = () => { if (dragReplicas().length > 1) setModal("apply"); else setModal("restart"); };
  const doRestart = () => { setModal(null); setFlash({ sev: "info", msg: "Config staged and a restart was observed on the target — effective-verified is not confirmed (APPLY_INDETERMINATE)." }); };
  const dragReplicas = () => built.replicas;

  if (demo === "loading") return (
    <div className="page page-wide"><Sk w={220} h={28} /><div className="mt24 dg"><div className="dg-toolbar"><Sk w={260} h={36} /></div><div style={{ padding: 20 }}>{[0, 1, 2, 3].map(i => <div key={i} style={{ marginBottom: 18 }}><Sk w="30%" h={14} /><div className="mt8"><Sk w="55%" h={40} /></div></div>)}</div></div></div>
  );
  if (demo === "error") return (
    <div className="page page-wide"><PageHead title="Designer" /><Alert severity="err" title="Couldn’t load the configuration model" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>The console reached the server but the view model failed to load. Retry, or check the control plane.</Alert></div>
  );

  const inst = (CACHE_INSTANCES.find(i => i.id === instId) || {}).name || instId;
  const sol = SOLUTIONS.find(s => s.id === solId) || SOLUTIONS[0];

  return (
    <div className="inst-shell">
      <InstanceHeader solId={solId} instId={instId} active="designer" nav={nav} roll="GREEN" availability="declared" versionState={model.status.version.state} />
    <div className="dsgn">
      <div className="dsgn-top">
        <div className="row between wrap gap12">
          <div>
            <h1 className="t-h5 row gap8" style={{ alignItems: "center" }}>{scenario === "composer" ? "File Mesh" : sol.name} · Designer</h1>
            <div className="row gap8 mt4"><span className="t-cap muted mono">{scenario === "composer" ? "file-mesh" : instId}</span><Chip tone="neutral" sm><span className="mono">pkg v{model.packageVersion}</span></Chip>{scenario !== "composer" && <VersionPill status={model.status} />}</div>
          </div>
          <div className="col" style={{ gap: 6, alignItems: "flex-end" }}>
            <span className="t-cap muted">Scenario (prototype)</span>
            <Tbg value={scenario} onChange={setScenario} options={SCENARIOS.map(s => ({ v: s.v, label: s.label }))} />
          </div>
        </div>
        <div className="dsgn-status">
          {scenario === "composer" && <div className="comp-toggle"><Tbg value={view} onChange={setView} options={[{ v: "canvas", label: "Composer", icon: "network" }, { v: "form", label: "All fields", icon: "list" }]} /></div>}
        </div>
      </div>

      {scenario === "composer" && view === "canvas"
        ? <Composer model={model} nodePos={nodePos} setNodePos={setNodePos} selNode={selNode} setSelNode={setSelNode} values={values} initial={initial} onChange={onChange} />
        : (
          <div className="dsgn-body">
            <nav className="dsgn-nav" aria-label="Sections">
              {model.single && <div className="comp-h"><Ic name="sliders" size={14} /> Sections</div>}
              <NavList components={model.components} values={values} onJump={jump} single={model.single} />
            </nav>
            <div className="dsgn-form" ref={formRef}>
              <div className="dsgn-form-in">
                {flash && <div style={{ marginBottom: 12 }}><Alert severity={flash.sev}>{flash.msg}</Alert></div>}
                {editingBlocked && <div style={{ marginBottom: 12 }}><BlockedBanner status={model.status} onAccept={() => setModal("accept")} onRestore={() => setModal("restore")} /></div>}
                {scenario === "diverged" && <div style={{ marginBottom: 12 }}><DivergedBanner onRestage={() => setFlash({ sev: "info", msg: "Re-staging the last uniform generation to every replica…" })} onRestart={() => setModal("restart")} onRollback={() => setModal("rollback")} /></div>}
                {!editingBlocked && (blocks.length > 0 || warns.length > 0) && <div style={{ marginBottom: 4 }}><ValidationSummary blocks={blocks} warns={warns} onJump={jumpUid} /></div>}

                <div className="dsgn-form-head mt16">
                  <div className="searchbox" style={{ maxWidth: 340 }}>
                    <span className="si"><Ic name="search" size={16} /></span>
                    <input className="ctl-in" value={search} onChange={e => setSearch(e.target.value)} placeholder={`Search ${model.components.reduce((n, c) => n + c.fields.length, 0)} fields…`} aria-label="Search fields" />
                  </div>
                  <span className="grow" />
                  {model.status.version.state === "VERSION_UNVERIFIED" && <span className="row gap6"><Chip tone="idle" sm icon="info">Version unverified</Chip><InfoI tip="Nothing corroborates the declared version — normal for an MI on a plain VM. This does NOT block editing." /></span>}
                </div>

                {search
                  ? <SearchResults components={model.components} values={values} initial={initial} onChange={onChange} disabled={editingBlocked} query={search} registerRef={registerRef} />
                  : model.components.map(c => (
                    <div key={c.instanceKey}>
                      {!model.single && <div className="comp-h" style={{ paddingLeft: 2, marginTop: 22 }}><Ic name={c.role === "sink" ? "mesh" : "folder"} size={15} /> {c.type.split("@")[0]} <Chip tone="neutral" sm>{c.role}</Chip></div>}
                      {c.sections.map(s => <SectionCard key={s.title} comp={c} section={s} values={values} initial={initial} onChange={onChange} disabled={editingBlocked} showPath={model.single} expanded={expanded.has(c.instanceKey + ":" + s.title)} onToggle={() => toggle(c.instanceKey + ":" + s.title)} registerRef={registerRef} />)}
                    </div>
                  ))}

                {model.components[0].actions && model.components[0].actions.length > 0 && !search && (
                  <div className="card2 mt16"><div className="card2-b" style={{ borderTop: "none", paddingTop: 16 }}>
                    <div className="t-over" style={{ marginBottom: 10 }}>Actions</div>
                    {model.components[0].actions.map(a => (
                      <div key={a.key} className="row between wrap gap12">
                        <div><div className="strong t-sm">{a.label}</div><div className="t-cap muted">Runs server-side against {a.inputs.join(", ")} and returns a verdict — never a credential.</div></div>
                        <Btn size="sm" variant="outlined" icon="bolt" disabled={editingBlocked} onClick={() => setFlash({ sev: "ok", msg: "Connection test passed (server-side). No credential was returned to the browser." })}>{a.label}</Btn>
                      </div>
                    ))}
                  </div></div>
                )}

                {!model.single ? null : <RolloutCard replicas={built.replicas} reached={built.reached} defaultOpen={scenario === "restart" || scenario === "diverged"} diverged={scenario === "diverged"} />}
                <div style={{ height: 8 }} />
              </div>

              {!editingBlocked && (
                <div className="dsgn-bar">
                  <div className="grow row gap12 wrap">
                    <span className="barnote">{dirty === 0 ? "No unsaved changes" : <strong>{dirty} unsaved change{dirty === 1 ? "" : "s"}</strong>}</span>
                    {blocks.length > 0 && <Chip tone="err" sm icon="xCircle">{blocks.length} blocking</Chip>}
                    {warns.length > 0 && <Chip tone="warn" sm icon="alert">{warns.length} recommendation{warns.length === 1 ? "" : "s"}</Chip>}
                    <Menu align="left" width={220} trigger={(o, t) => <button className="btn btn-text btn-sm" onClick={t}><Ic name="more" size={16} /> Prototype</button>}>
                      {(close) => <><div className="mi-cap">Simulate</div><div className="mi" onClick={() => { close(); setModal("conflict"); }}><Ic name="gitBranch" size={15} /> Concurrent save (409)</div></>}
                    </Menu>
                  </div>
                  <Tip tip="Writes the draft to the platform's own record and sends the last-seen generation. Writes nothing to the MI.">
                    <span><Btn variant="outlined" icon="download" disabled={blocks.length > 0 || dirty === 0} onClick={doSave}>Save draft</Btn></span>
                  </Tip>
                  <Tip tip="Writes the saved config to the MI. Usually needs a restart to take effect.">
                    <span><Btn icon="upload" disabled={!saved} onClick={doApply}>Apply…</Btn></span>
                  </Tip>
                </div>
              )}
            </div>
          </div>
        )}

      {modal === "conflict" && <ConflictModal onClose={() => setModal(null)} onRefetch={() => { setModal(null); setValues(initial); setSaved(true); setFlash({ sev: "info", msg: "Refetched the current record. Re-apply your changes on top of it." }); }} />}
      {modal === "reason" && <WarnReasonModal warns={warns} onCancel={() => setModal(null)} onConfirm={confirmReason} />}
      {modal === "apply" && <Modal title="Choose a restart strategy" max={620} onClose={() => setModal(null)} foot={<><Btn variant="text" onClick={() => setModal(null)}>Cancel</Btn><Btn onClick={() => setModal("restart")}>Continue</Btn></>}><StrategyPicker model={model} value={strategy} onChange={setStrategy} /></Modal>}
      {modal === "restart" && <RestartConfirm replicas={dragReplicas()} strategy={strategy} onCancel={() => setModal(null)} onConfirm={doRestart} />}
      {modal === "accept" && <AcceptRuntimeModal onClose={() => setModal(null)} />}
      {modal === "restore" && <Modal title="Restore the runtime" onClose={() => setModal(null)} foot={<><Btn variant="text" onClick={() => setModal(null)}>Cancel</Btn><Btn className="btn-danger-c" onClick={() => setModal(null)}>Restore 2.4 &amp; restart</Btn></>}><Alert severity="warn" title="This redeploys the declared version">Restoring re-applies the declared version (2.4) to the target and restarts it — an outage. Use this when the deployed 2.3 was the accident.</Alert></Modal>}
      {modal === "rollback" && <Modal title="Roll back to the last uniform generation" max={620} onClose={() => setModal(null)} foot={<><Btn variant="text" onClick={() => setModal(null)}>Cancel</Btn><Btn className="btn-danger-c" onClick={() => { setModal(null); setFlash({ sev: "info", msg: "Rolling back every replica to gen-40 and restarting." }); }}>Roll back &amp; restart all</Btn></>}>
        <Alert severity="warn" title="A rollback is a full change — reviewed like any other">This reverts every replica to <span className="mono">gen-40</span>. It gets no lighter confirmation for being a revert; review the diff first.</Alert>
        <div className="t-over" style={{ margin: "16px 0 8px" }}>gen-41 (current) → gen-40 (target)</div>
        <DiffView rows={DIFF_DRAFT} labels={["Current · gen-41", "Target · gen-40"]} />
      </Modal>}
    </div>
    </div>
  );
};

const VersionPill = ({ status }) => {
  const v = status.version;
  const map = { OK: { t: "ok", i: "check", l: "Version OK" }, VERSION_DRIFT: { t: "warn", i: "alert", l: "Version drift" }, VERSION_UNVERIFIED: { t: "idle", i: "info", l: "Version unverified" } };
  const m = map[v.state] || map.OK;
  return <span className="row gap6"><Chip tone={m.t} sm icon={m.i}>{m.l}</Chip><span className="t-cap muted mono">decl {v.declared}{v.observed ? " · obs " + v.observed : ""}</span></span>;
};

const RestartConfirm = ({ replicas, strategy, onCancel, onConfirm }) => {
  const n = replicas.length || 1;
  const strat = RESTART_STRATEGIES.find(s => s.k === strategy);
  return (
    <Modal title="Apply requires a restart" onClose={onCancel} foot={<><Btn variant="text" onClick={onCancel}>Cancel</Btn><Btn className="btn-danger-c" onClick={onConfirm}>Apply &amp; restart {n} replica{n === 1 ? "" : "s"}</Btn></>}>
      <Alert severity="warn" title={`Requires restart · ${n} replica${n === 1 ? "" : "s"} · expect unavailability · continue?`}>
        Writing configuration is not the same as applying it, and applying needs a restart{strat && strat.k !== "manual" ? ` (${strat.label.toLowerCase()})` : ""}. Each replica is briefly unavailable while it restarts.
      </Alert>
      <div className="t-cap muted" style={{ marginTop: 14 }}>The console confirms only that it wrote config and observed a restart — never that the process is using it (restart-observed ≠ effective-verified).</div>
    </Modal>
  );
};

const DivergedBanner = ({ onRestage, onRestart, onRollback }) => (
  <div className="alert alert-err" style={{ alignItems: "flex-start" }}>
    <span className="ai"><Ic name="alert" size={19} /></span>
    <div className="grow">
      <div className="at">Configuration diverged</div>
      <div className="t-body" style={{ marginTop: 2 }}>Replicas are holding different config generations after a halted rollout. Pick one remediation — there is no “force healthy”.</div>
      <div className="row gap8 mt16 wrap">
        <Btn size="sm" variant="outlined" onClick={onRestage}>Re-stage everywhere</Btn>
        <Btn size="sm" variant="outlined" onClick={onRestart}>Restart the remaining</Btn>
        <Btn size="sm" variant="outlined" onClick={onRollback}>Roll back to last uniform generation…</Btn>
      </div>
    </div>
  </div>
);

// ---- P5e composer canvas ----
const Composer = ({ model, nodePos, setNodePos, selNode, setSelNode, values, initial, onChange }) => {
  const startDrag = (e, ik) => {
    e.stopPropagation();
    const start = { x: e.clientX, y: e.clientY }; const orig = { ...(nodePos[ik]) };
    const move = (ev) => setNodePos(p => ({ ...p, [ik]: { x: Math.max(8, orig.x + (ev.clientX - start.x)), y: Math.max(8, orig.y + (ev.clientY - start.y)) } }));
    const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
    window.addEventListener("pointermove", move); window.addEventListener("pointerup", up);
  };
  const comp = model.components.find(c => c.instanceKey === selNode);
  const src = model.nodes.find(n => n.role === "source"), snk = model.nodes.find(n => n.role === "sink");
  const p1 = nodePos[src.instanceKey], p2 = nodePos[snk.instanceKey];
  const x1 = p1.x + 220, y1 = p1.y + 34, x2 = p2.x, y2 = p2.y + 34;
  return (
    <div className="comp-body">
      <div className="palette">
        <div className="pal-cap">Component types</div>
        {model.palette.map(p => (
          <div key={p.type} className="pal-item" title="Drag onto the canvas (prototype: static)"><span className="pi-ic" style={{ background: p.color }}><Ic name={p.icon} size={16} /></span><span className="grow">{p.label}</span></div>
        ))}
        <div className="t-cap muted" style={{ padding: "8px 6px", lineHeight: 1.5 }}>Generated from the package’s declared component types — never a hard-coded FTP/SFTP/S3 list.</div>
      </div>
      <div className="canvas">
        <div className="canvas-hint"><span className="row gap6"><Ic name="info" size={13} /> Node positions are presentation only — moving a node isn’t a config change and won’t prompt a restart.</span></div>
        <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}>
          <path d={`M ${x1} ${y1} C ${x1 + 70} ${y1}, ${x2 - 70} ${y2}, ${x2} ${y2}`} fill="none" stroke="var(--primary)" strokeWidth="2" />
          <circle cx={x2} cy={y2} r="4" fill="var(--primary)" />
        </svg>
        {model.nodes.map(nd => {
          const pos = nodePos[nd.instanceKey]; const c = model.components.find(cc => cc.instanceKey === nd.instanceKey);
          const req = c.fields.filter(f => (f.validations || []).some(v => v.code === "REQUIRED") && isEmpty(values[f.uid])).length;
          return (
            <div key={nd.instanceKey} className={`node ${selNode === nd.instanceKey ? "sel" : ""}`} style={{ left: pos.x, top: pos.y }} onClick={() => setSelNode(nd.instanceKey)}>
              <div className="node-h" onPointerDown={e => startDrag(e, nd.instanceKey)}>
                <span className="node-ic" style={{ background: nd.color }}><Ic name={nd.icon} size={17} /></span>
                <div className="col" style={{ gap: 1 }}><span className="node-role" style={{ color: nd.color }}>{nd.role}</span><span className="strong t-sm">{nd.label}</span></div>
              </div>
              <div className="node-b row between"><span className="mono t-cap">{c.fields.length} fields</span>{req > 0 ? <Chip tone="err" sm icon="xCircle">{req}</Chip> : <Chip tone="ok" sm icon="check">ok</Chip>}</div>
              {nd.role === "source" && <span className="node-port out" />}
              {nd.role === "sink" && <span className="node-port in" />}
            </div>
          );
        })}
      </div>
      <div className="inspector">
        <div className="insp-h"><div className="row gap8"><span className="node-ic" style={{ background: comp && model.nodes.find(n => n.instanceKey === comp.instanceKey).color, width: 28, height: 28 }}><Ic name={model.nodes.find(n => n.instanceKey === selNode).icon} size={15} /></span><div className="col" style={{ gap: 0 }}><span className="strong">{model.nodes.find(n => n.instanceKey === selNode).label}</span><span className="t-cap muted mono">{comp.type}</span></div></div></div>
        <div className="insp-b"><InspectorForm comp={comp} values={values} initial={initial} onChange={onChange} /></div>
      </div>
    </div>
  );
};

const InspectorForm = ({ comp, values, initial, onChange }) => {
  const [expanded, setExpanded] = uS(() => new Set(comp.sections.filter(s => !s.allAdvanced).map(s => s.title)));
  const [q, setQ] = uS("");
  uE(() => { setExpanded(new Set(comp.sections.filter(s => !s.allAdvanced).map(s => s.title))); setQ(""); }, [comp.instanceKey]);
  const g = compGetter(comp, values);
  if (q) {
    const hits = comp.fields.filter(f => fieldVisible(f, g) && `${f.label} ${f.key} ${f.section}`.toLowerCase().includes(q.toLowerCase()));
    return (<><div className="searchbox" style={{ margin: "10px 0" }}><span className="si"><Ic name="search" size={15} /></span><input className="ctl-in" value={q} onChange={e => setQ(e.target.value)} placeholder="Search fields…" /></div>
      <div className="sect"><div className="sect-b">{hits.length === 0 ? <div className="fld muted">No matches.</div> : hits.map(f => <FieldRow key={f.uid} field={f} val={values[f.uid]} initial={initial[f.uid]} disabled={f.enabled === false} crumb={f.section} onChange={nv => onChange(f.uid, nv)} />)}</div></div></>);
  }
  return (
    <>
      <div className="searchbox" style={{ margin: "10px 0" }}><span className="si"><Ic name="search" size={15} /></span><input className="ctl-in" value={q} onChange={e => setQ(e.target.value)} placeholder={`Search ${comp.fields.length} fields…`} /></div>
      {comp.sections.map(s => <SectionCard key={s.title} comp={comp} section={s} values={values} initial={initial} onChange={onChange} disabled={false} expanded={expanded.has(s.title)} onToggle={() => setExpanded(e => { const n = new Set(e); n.has(s.title) ? n.delete(s.title) : n.add(s.title); return n; })} />)}
    </>
  );
};

window.DesignerScreen = DesignerScreen;
