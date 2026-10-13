// P5 supporting panels. Save · Apply · Restart are three distinct things and
// never collapse. restart-observed is NOT convergence — the stepper stops short of
// effective-verified. Per-replica table, never a single spinner.
const { useState: useStateP } = React;

// ---- config-state stepper (5 states; effective-verified stays pending) ----
const ConfigStepper = ({ reached }) => {
  const idx = CONFIG_STATES.findIndex(s => s.k === reached);
  return (
    <div>
      <div className="cfg-steps">
        {CONFIG_STATES.map((s, i) => (
          <React.Fragment key={s.k}>
            {i > 0 && <span className="cbar" />}
            <Tip tip={s.tip}>
              <span className={`cnode ${i < idx ? "done" : i === idx ? "cur" : "pend"}`}>
                {i < idx ? <Ic name="check" size={13} /> : i === idx ? <Ic name="dot" size={12} /> : <Ic name="dot" size={12} />}
                {s.label}
              </span>
            </Tip>
          </React.Fragment>
        ))}
      </div>
      <div className="row gap6 mt8" style={{ color: "var(--warning-w100,#a85a10)" }}>
        <Ic name="info" size={14} /><span className="t-cap">A completed restart reaches <strong>restart-observed</strong>, not effective-verified: the platform knows the process came back with the file, not that it is using it. Outcome is <span className="mono">APPLY_INDETERMINATE</span>.</span>
      </div>
    </div>
  );
};

// ---- per-replica table (renderedConfigVersion + restartObserved are invented column names) ----
const ReplicaTable = ({ replicas }) => (
  <div>
    <div className="dg-scroll">
      <table className="rep-tbl">
        <thead><tr>
          <th>Replica</th>
          <th>Rendered config version <InfoI tip="Invented column name — the contract names this in prose but defines no row schema for it." /></th>
          <th>Restart observed <InfoI tip="Invented column name — same caveat. 'Observed' means the process came back with the file, not that it is using it." /></th>
          <th>Health</th>
        </tr></thead>
        <tbody>
          {replicas.map(r => {
            const h = REPLICA_HEALTH[r.health];
            const gens = new Set(replicas.map(x => x.gen));
            const odd = gens.size > 1 && r.gen !== mode(replicas.map(x => x.gen));
            return (
              <tr key={r.id}>
                <td className="mono strong">{r.id}</td>
                <td><span className={"mono " + (odd ? "" : "")} style={odd ? { color: "var(--error-w100)", fontWeight: 600 } : null}>{r.gen}</span></td>
                <td>{r.restartObserved ? <Chip tone="ok" sm icon="check">observed</Chip> : <Chip tone="idle" sm icon="hourglass">pending</Chip>}</td>
                <td><span className="row gap6"><Chip tone={h.tone} sm icon={h.icon}>{h.label}</Chip>{h.tip && <InfoI tip={h.tip} />}</span></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  </div>
);
function mode(arr) { const c = {}; let best = arr[0], n = 0; arr.forEach(x => { c[x] = (c[x] || 0) + 1; if (c[x] > n) { n = c[x]; best = x; } }); return best; }

// ---- collapsible Rollout card ----
const RolloutCard = ({ replicas, reached, defaultOpen, diverged }) => {
  const [open, setOpen] = useStateP(!!defaultOpen);
  return (
    <div className="card2 mt16">
      <div className="card2-h" onClick={() => setOpen(o => !o)}>
        <Ic name="layers" size={18} style={{ color: "var(--primary)" }} />
        <span className="t-h6 grow">Rollout &amp; replicas</span>
        {diverged ? <Chip tone="err" sm icon="alert">Diverged</Chip> : <Chip tone="neutral" sm>{replicas.length} replicas</Chip>}
        <span className={`expander ${open ? "open" : ""}`}><Ic name="chevRight" size={18} /></span>
      </div>
      {open && <div className="card2-b">
        <div className="t-over" style={{ margin: "12px 0 8px" }}>Config state</div>
        <ConfigStepper reached={reached} />
        <div className="t-over" style={{ margin: "18px 0 8px" }}>Per replica</div>
        <ReplicaTable replicas={replicas} />
      </div>}
    </div>
  );
};

// ---- field-level diff (secrets masked) ----
const DiffView = ({ rows, labels }) => (
  <div className="diff">
    {rows.map((r, i) => (
      <div className="diff-blk" key={i}>
        <div className="diff-path">{r.path}{r.secret && <span className="diff-tag chip-neutral" style={{ background: "var(--secondary-w20)" }}>secret · masked</span>}</div>
        <div className="diff-cells">
          <div className={`diff-cell ${r.kind === "add" ? "add-l" : "old"}`}><span className="diff-k">{(labels && labels[0]) || "Deployed"}</span><span className="diff-v">{r.kind === "add" ? "—" : r.old}</span></div>
          <div className="diff-cell new"><span className="diff-k">{(labels && labels[1]) || "Draft"}</span><span className="diff-v">{r.new}</span></div>
        </div>
      </div>
    ))}
  </div>
);

// ---- 409 conflict. Resolution controls are INVENTED — flagged in-panel. ----
const ConflictModal = ({ onClose, onRefetch }) => (
  <Modal title="Someone saved first" max={640} onClose={onClose}
    foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn icon="refresh" onClick={onRefetch}>Refetch &amp; review</Btn></>}>
    <Alert severity="warn" title="Your draft is based on an older generation (HTTP 409)">
      The platform rejected the save because another edit landed after you loaded this form. Your optimistic changes were discarded; below is the difference between your draft and the current record.
    </Alert>
    <div className="t-over" style={{ margin: "18px 0 8px" }}>Your draft → current record</div>
    <DiffView rows={CONFLICT_THEIRS} labels={["Your draft", "Current record"]} />
    <Alert severity="neutral" icon="alert" style={{ marginTop: 16 }} title="Invented — needs a product decision">
      The contract does not specify conflict resolution. There is <strong>no “overwrite anyway”, “take theirs”, or per-field merge</strong> defined, and <strong>no actor or timestamp</strong> field to say who conflicted or when. The safe path shown here is refetch-and-reapply; the rest are proposals to confirm.
    </Alert>
    <div className="row gap8 mt16 wrap">
      <Btn variant="outlined" size="sm" disabled>Keep mine — overwrite</Btn>
      <Btn variant="outlined" size="sm" disabled>Take theirs — discard mine</Btn>
      <Btn variant="outlined" size="sm" disabled>Per-field merge…</Btn>
      <span className="t-cap muted row gap6"><Ic name="info" size={13} /> disabled — not specified</span>
    </div>
  </Modal>
);

// ---- warning override: capture reason → recorded in configuration history ----
const WarnReasonModal = ({ warns, onCancel, onConfirm }) => {
  const [reason, setReason] = useStateP("");
  return (
    <Modal title="Save with recommendations overridden" onClose={onCancel}
      foot={<><Btn variant="text" onClick={onCancel}>Cancel</Btn><Btn disabled={reason.trim().length < 4} onClick={() => onConfirm(reason)}>Save &amp; record reason</Btn></>}>
      <Alert severity="warn" title={`${warns.length} field${warns.length === 1 ? "" : "s"} outside Solace recommendations`}>
        These are recommendations, not hard limits — the MI will accept the config. Your reason is recorded in configuration history alongside the change.
      </Alert>
      <ul className="t-sm" style={{ lineHeight: 1.7, margin: "14px 0", paddingLeft: 18 }}>
        {warns.map((w, i) => <li key={i}><span className="strong">{w.label}</span> — {w.message}</li>)}
      </ul>
      <div className="tf"><textarea rows={3} value={reason} placeholder=" " onChange={e => setReason(e.target.value)} aria-label="Reason" /><label className="tfl">Reason for override</label></div>
      <div className="tf-help">Recorded in history. Minimum a few words.</div>
    </Modal>
  );
};

// ---- blocked (P5b): version drift, read-only-not-unavailable, two routes out ----
const BlockedBanner = ({ status, onAccept, onRestore }) => (
  <div className="alert alert-err" style={{ alignItems: "flex-start" }}>
    <span className="ai"><Ic name="lock" size={19} /></span>
    <div className="grow">
      <div className="at">Editing blocked · version drift</div>
      <div className="t-body" style={{ marginTop: 2 }}>{status.version.reason || `Declared ${status.version.declared} but observed ${status.version.observed}.`} The configuration below stays fully legible — this is <strong>read-only, not unavailable</strong> — but it can’t be edited until the declared and deployed versions agree.</div>
      <div className="row gap8 mt16 wrap">
        <Btn size="sm" onClick={onAccept}>Accept the runtime…</Btn>
        <Btn size="sm" variant="outlined" onClick={onRestore}>Restore the runtime…</Btn>
      </div>
    </div>
  </div>
);

// accept-the-runtime: re-render against deployed schema, diff classified 4 ways
const AcceptRuntimeModal = ({ onClose }) => (
  <Modal title="Accept the runtime (2.3.0)" max={640} onClose={onClose}
    foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn onClick={onClose}>Re-render against 2.3.0</Btn></>}>
    <p className="t-body muted" style={{ marginTop: 0 }}>The declaration will be re-rendered against the deployed version’s schema. Changes are classified so you can see exactly what moves:</p>
    <div className="col gap8 mt8">
      {[["add", "Added / defaulted", "2 fields", "success"], ["remove", "Removed", "1 field", "error"], ["change", "Type or constraint changed", "1 field", "warning"], ["rename", "Renamed · needs mapping", "1 field", "info"]].map(([k, l, n, t]) => (
        <div key={k} className="row between paper-flat" style={{ padding: "11px 14px" }}>
          <span className="row gap8"><span className={`sdot sd-${t === "success" ? "ok" : t === "error" ? "err" : t === "warning" ? "warn" : "info"}`} /><span className="strong t-sm">{l}</span></span>
          <span className="t-sm muted">{n}</span>
        </div>
      ))}
    </div>
    <Alert severity="info" style={{ marginTop: 14 }} icon="link"><strong>renamed · needs mapping</strong> requires you to point the old field at its new name before this completes — an unmapped rename would silently drop the value.</Alert>
  </Modal>
);

// ---- form-level validation summary ----
const ValidationSummary = ({ blocks, warns, onJump }) => {
  if (blocks.length === 0 && warns.length === 0) return null;
  const sev = blocks.length ? "err" : "warn";
  return (
    <div className={`alert alert-${sev}`} style={{ alignItems: "flex-start" }}>
      <span className="ai"><Ic name={blocks.length ? "xCircle" : "alert"} size={19} /></span>
      <div className="grow">
        <div className="at">{blocks.length > 0 ? `${blocks.length} field${blocks.length === 1 ? "" : "s"} must be fixed before saving` : `${warns.length} recommendation${warns.length === 1 ? "" : "s"} to review`}{blocks.length > 0 && warns.length > 0 ? ` · ${warns.length} recommendation${warns.length === 1 ? "" : "s"}` : ""}</div>
        <div className="row wrap gap8 mt8">
          {blocks.map(b => <button key={b.uid} className="chip chip-err chip-sm" onClick={() => onJump(b.uid)} style={{ cursor: "pointer", border: "none" }}><Ic name="arrowRight" size={12} />{b.label}</button>)}
          {warns.map(w => <button key={w.uid} className="chip chip-warn chip-sm" onClick={() => onJump(w.uid)} style={{ cursor: "pointer", border: "none" }}><Ic name="arrowRight" size={12} />{w.label}</button>)}
        </div>
      </div>
    </div>
  );
};

// ---- restart strategy picker (P5d) ----
const StrategyPicker = ({ model, value, onChange }) => {
  const strategies = RESTART_STRATEGIES.filter(s => {
    if (model.requiresUniformConfig && (s.k === "rolling-health-gated" || s.k === "rolling-no-validation")) return false; // suppressed
    return true;
  });
  return (
    <div className="col gap12">
      {model.requiresUniformConfig && <Alert severity="neutral" icon="info">This package requires all replicas to hold identical config, so both rolling strategies are suppressed.</Alert>}
      {strategies.map(s => {
        let disabled = false, reason = null;
        if (s.outOfRelease) { disabled = true; reason = "Out of this release."; }
        if (s.k === "rolling-health-gated") {
          if (!model.healthMappingDeclared) { disabled = true; reason = "The package declares no health mapping, so a health gate has nothing to read."; }
          else if (model.isFlagship) { disabled = true; reason = "Unavailable for this MI: its standby reports healthy while processing nothing, so a health gate would advance past a replica that took no traffic."; }
        }
        const on = value === s.k && !disabled;
        return (
          <div key={s.k} className={`strat ${on ? "on" : ""} ${disabled ? "disabled" : ""}`} onClick={() => !disabled && onChange(s.k)} role="radio" aria-checked={on} tabIndex={disabled ? -1 : 0}
               onKeyDown={e => { if (!disabled && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); onChange(s.k); } }}>
            <span className="radio" />
            <div className="grow">
              <div className="row gap8" style={{ flexWrap: "wrap" }}>
                <span className="strong">{s.label}</span>
                {s.def && <Chip tone="ok" sm>Default</Chip>}
                {s.consequence === "high" && <Chip tone="warn" sm icon="alert">High-consequence · confirm even when authorized</Chip>}
                {s.consequence === "outage" && <Chip tone="err" sm icon="alert">Full outage · confirm even when authorized</Chip>}
                {disabled && <Chip tone="idle" sm icon="ban">Unavailable</Chip>}
              </div>
              <div className="t-sm muted" style={{ marginTop: 4 }}>{s.desc}</div>
              {disabled && reason && <div className="t-cap" style={{ marginTop: 6, color: "var(--warning-w100,#a85a10)" }}>{reason}</div>}
            </div>
          </div>
        );
      })}
      <Alert severity="neutral" icon="info">Default multi-replica ordering is <strong>stage everywhere, then restart</strong> — a halt leaves config uniform but unapplied, never half-applied.</Alert>
    </div>
  );
};

Object.assign(window, { ConfigStepper, ReplicaTable, RolloutCard, DiffView, ConflictModal, WarnReasonModal, BlockedBanner, AcceptRuntimeModal, ValidationSummary, StrategyPicker });
