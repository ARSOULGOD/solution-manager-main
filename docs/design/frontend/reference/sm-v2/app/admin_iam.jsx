// P13 (b)(c) · Assignments + Effective permissions. Principal is discriminated:
// users and groups list side-by-side with no new screen. Inheritance is shown as
// inherited, never as if granted directly. Deny-by-default is made debuggable by
// naming the conferring assignment.
const { useState: uSia } = React;

const PrincipalTag = ({ p, sub = true }) => (
  <span className="prin">
    <span className={`prin-ic ${p.kind}`}><Ic name={p.kind === "group" ? "users" : "user"} size={16} /></span>
    <span className="col" style={{ gap: 0, minWidth: 0 }}>
      <span className="strong t-sm" style={{ display: "flex", alignItems: "center", gap: 6 }}>{p.name}{p.source === "idp" && <Chip tone="purple" sm>IdP group</Chip>}</span>
      {sub && <span className="t-cap muted" style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{p.sub}</span>}
    </span>
  </span>
);

const ScopeTree = ({ node, path = [], selected, onSelect }) => {
  const [open, setOpen] = uSia(true);
  const here = [...path, node.name];
  const isSel = selected && pathStr(selected) === pathStr(here);
  const hasKids = node.children && node.children.length;
  return (
    <div>
      <div className={`scope-node ${isSel ? "on" : ""}`} onClick={() => onSelect(here)}>
        {hasKids ? <button className={`expander ${open ? "open" : ""}`} onClick={e => { e.stopPropagation(); setOpen(o => !o); }} aria-label="toggle"><Ic name="chevRight" size={14} /></button> : <span style={{ width: 14 }} />}
        <Ic name={node.level === "Instance" ? "server" : node.level === "Environment" ? "layers" : node.level === "Tenant" ? "building" : "folder"} size={15} style={{ color: "var(--secondary-text-wMain)", flex: "none" }} />
        <span className="grow">{node.name}</span>
        <span className="t-cap muted">{node.level}</span>
      </div>
      {hasKids && open && <div className="scope-kids">{node.children.map(c => <ScopeTree key={c.name} node={c} path={here} selected={selected} onSelect={onSelect} />)}</div>}
    </div>
  );
};

const AssignmentModal = ({ onClose }) => {
  const [pid, setPid] = uSia("");
  const [rid, setRid] = uSia("");
  const [scope, setScope] = uSia(["Acme Financial"]);
  const role = roleById(rid);
  const twoSided = role && role.perms !== "*" && role.perms.some(p => PERM_BY_ID[p] && PERM_BY_ID[p].twoSided);
  return (
    <Modal title="New assignment" max={620} onClose={onClose}
      foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn disabled={!pid || !rid} onClick={() => { const p = principalById(pid); pushOutcome(null, { sev: "ok", title: "Assignment created", detail: `${roleById(rid).name} → ${p.name} at ${pathStr(scope)}.` }); onClose(); }}>Create assignment</Btn></>}>
      <div className="t-over" style={{ marginBottom: 8 }}>Principal <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>· user or group — same picker</span></div>
      <select className="bare" style={{ width: "100%" }} value={pid} onChange={e => setPid(e.target.value)}>
        <option value="" disabled>Select a principal…</option>
        <optgroup label="Users (local)">{RBAC_PRINCIPALS.filter(p => p.kind === "user").map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</optgroup>
        <optgroup label="Groups (IdP)">{RBAC_PRINCIPALS.filter(p => p.kind === "group").map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</optgroup>
      </select>
      <div className="t-over" style={{ margin: "16px 0 8px" }}>Role</div>
      <select className="bare" style={{ width: "100%" }} value={rid} onChange={e => setRid(e.target.value)}>
        <option value="" disabled>Select a role…</option>
        {RBAC_ROLES.map(r => <option key={r.id} value={r.id}>{r.name}{r.kind === "built-in" ? " (built-in)" : ""}</option>)}
      </select>
      <div className="t-over" style={{ margin: "16px 0 8px" }}>Scope <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>· grants here and everything below</span></div>
      <div className="paper-flat" style={{ padding: 10, maxHeight: 220, overflow: "auto" }}><ScopeTree node={SCOPE_TREE} selected={scope} onSelect={setScope} /></div>
      <div className="t-cap muted mt8">Selected: <span className="mono">{pathStr(scope)}</span></div>
      {twoSided && <Alert severity="info" icon="link" style={{ marginTop: 12 }}>This role grants <span className="mono">target.use</span> / <span className="mono">instance.bindTarget</span>. Placing an instance on a target needs a permission on <strong>both</strong> the instance's scope and the target's <span className="mono">ownership_scope</span> — the one two-sided rule.</Alert>}
    </Modal>
  );
};

const AssignmentsView = ({ onOutcome }) => {
  const [dir, setDir] = uSia("principal");
  const [scope, setScope] = uSia(["Acme Financial", "Payments", "Ledger", "PROD"]);
  const [modal, setModal] = uSia(false);

  return (
    <div>
      <div className="row between wrap gap12" style={{ marginBottom: 14 }}>
        <div><div className="t-h6">Assignments</div><div className="t-cap muted" style={{ marginTop: 2 }}>Who can do what, where — the triple (principal, role, scope).</div></div>
        <div className="row gap10"><Tbg value={dir} onChange={setDir} options={[{ v: "principal", label: "By principal" }, { v: "scope", label: "By scope" }]} /><Btn size="sm" icon="plus" onClick={() => setModal(true)}>New assignment</Btn></div>
      </div>

      {dir === "principal" ? (
        <div className="dg"><div className="dg-scroll"><table className="matrix" style={{ minWidth: 720 }}>
          <thead><tr><th>Principal</th><th>Role</th><th>Scope</th><th></th></tr></thead>
          <tbody>
            {RBAC_ASSIGNMENTS.map(a => { const p = principalById(a.principal), r = roleById(a.role); return (
              <tr key={a.id}>
                <td><PrincipalTag p={p} /></td>
                <td><Chip tone={r.kind === "built-in" ? "info" : "neutral"} sm>{r.name}</Chip></td>
                <td className="mono t-sm">{pathStr(a.scope)}</td>
                <td style={{ textAlign: "right" }}><Menu align="right" width={180} trigger={(o, t) => <IconBtn icon="more" small onClick={t} aria-label="menu" />}>{(c) => <><div className="mi" onClick={c}><Ic name="eye" size={15} /> Effective perms</div><div className="mi danger" onClick={c}><Ic name="trash" size={15} /> Remove</div></>}</Menu></td>
              </tr>
            ); })}
          </tbody>
        </table></div></div>
      ) : (
        <div className="row gap16 wrap" style={{ alignItems: "flex-start" }}>
          <Paper pad style={{ flex: "0 0 300px" }}><div className="t-over" style={{ marginBottom: 8 }}>Pick a scope</div><ScopeTree node={SCOPE_TREE} selected={scope} onSelect={setScope} /></Paper>
          <Paper pad className="grow" style={{ minWidth: 340 }}>
            <div className="t-sm muted" style={{ marginBottom: 10 }}>Who can touch <span className="mono strong">{pathStr(scope)}</span></div>
            {RBAC_ASSIGNMENTS.filter(a => isPrefix(a.scope, scope)).map(a => { const p = principalById(a.principal), r = roleById(a.role); const direct = pathStr(a.scope) === pathStr(scope); return (
              <div className="confer" key={a.id}>
                <div className="row between"><PrincipalTag p={p} sub={false} /><Chip tone={direct ? "ok" : "idle"} sm icon={direct ? "check" : "arrowDown"}>{direct ? "direct" : "inherited"}</Chip></div>
                <div className="t-cap muted" style={{ marginTop: 6 }}>{r.name} · granted at <span className="mono">{pathStr(a.scope)}</span></div>
              </div>
            ); })}
            {RBAC_ASSIGNMENTS.filter(a => isPrefix(a.scope, scope)).length === 0 && <div className="muted t-sm">No assignments reach this scope.</div>}
            <Alert severity="neutral" icon="info" style={{ marginTop: 12 }}>Inherited rows come from an assignment higher in the tree. Removing one changes access here — showing it as “direct” is how someone deletes an assignment and is surprised nothing changed.</Alert>
          </Paper>
        </div>
      )}
      {modal && <AssignmentModal onClose={() => setModal(false)} />}
    </div>
  );
};

const AccessCheckView = () => {
  const [pid, setPid] = uSia("u-mira");
  const [scope, setScope] = uSia(["Acme Financial", "Payments", "Ledger", "PROD"]);
  const applicable = RBAC_ASSIGNMENTS.filter(a => a.principal === pid && isPrefix(a.scope, scope));
  const grants = {};
  applicable.forEach(a => { const r = roleById(a.role); expandPerms(r).forEach(pm => { if (!grants[pm]) grants[pm] = []; grants[pm].push({ a, r }); }); });
  const granted = Object.keys(grants).sort();
  const p = principalById(pid);
  const sampleDenied = ALL_PERMS.filter(x => !grants[x]).slice(0, 1)[0];

  return (
    <div>
      <div className="t-h6">Effective permissions</div>
      <div className="t-cap muted" style={{ marginTop: 2, marginBottom: 14 }}>Given a principal and a scope: exactly what they have — and which assignment conferred it. The screen for “why was I denied?”</div>
      <div className="row gap16 wrap" style={{ alignItems: "flex-start" }}>
        <Paper pad style={{ flex: "0 0 300px" }}>
          <div className="t-over" style={{ marginBottom: 8 }}>Principal</div>
          <select className="bare" style={{ width: "100%" }} value={pid} onChange={e => setPid(e.target.value)}>
            <optgroup label="Users">{RBAC_PRINCIPALS.filter(x => x.kind === "user").map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</optgroup>
            <optgroup label="Groups">{RBAC_PRINCIPALS.filter(x => x.kind === "group").map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</optgroup>
          </select>
          <div className="t-over" style={{ margin: "16px 0 8px" }}>At scope</div>
          <div style={{ maxHeight: 240, overflow: "auto" }}><ScopeTree node={SCOPE_TREE} selected={scope} onSelect={setScope} /></div>
        </Paper>
        <Paper pad className="grow" style={{ minWidth: 360 }}>
          <div className="row gap8" style={{ marginBottom: 10 }}><PrincipalTag p={p} sub={false} /><span className="muted t-sm">at</span><span className="mono t-sm strong">{pathStr(scope)}</span></div>
          {granted.length === 0 ? <Alert severity="warn" icon="ban" title="No permissions here">Deny-by-default: with no assignment reaching this scope, every action is denied.</Alert>
            : <div className="dg" style={{ boxShadow: "none" }}><div className="dg-scroll"><table className="matrix">
              <thead><tr><th>Permission</th><th>Conferred by</th></tr></thead>
              <tbody>{granted.map(pm => { const src = grants[pm][0]; const direct = pathStr(src.a.scope) === pathStr(scope); const meta = PERM_BY_ID[pm]; return (
                <tr key={pm}><td><span className="row gap6"><span className="perm-id">{pm}</span>{meta && meta.hc && <span className="hc"><Ic name="alert" size={11} />HC</span>}</span></td>
                  <td><span className="t-sm">{src.r.name}</span> <Chip tone={direct ? "ok" : "idle"} sm>{direct ? "direct" : "inherited"}</Chip><div className="t-cap muted mono">at {pathStr(src.a.scope)}</div></td></tr>
              ); })}</tbody>
            </table></div></div>}
          {sampleDenied && <Alert severity="neutral" icon="ban" style={{ marginTop: 12 }}>Example denial: <span className="mono">{sampleDenied}</span> is <strong>denied</strong> here — no assignment for this principal confers it. A permission that isn’t granted is denied; one that doesn’t exist can’t be granted at all.</Alert>}
        </Paper>
      </div>
    </div>
  );
};

Object.assign(window, { PrincipalTag, ScopeTree, AssignmentsView, AccessCheckView, AssignmentModal });
