// P13 (a) Roles + P14 Users, and the Admin shell tying the RBAC surfaces together.
// The verb catalog is UNRATIFIED placeholder (flagged). P14 is entirely invented;
// the first-admin bootstrap is the load-bearing hole. Identity (P14) and assignment
// (P13) are separate screens so the IdP migration removes affordances, not structure.
const { useState: uSa } = React;

const permLabel = (id) => id.split(".").slice(1).join(".");

const RoleEditor = ({ role, mode, onClose }) => {
  const initial = role ? (role.perms === "*" ? ALL_PERMS.slice() : role.perms.slice()) : [];
  const [sel, setSel] = uSa(new Set(initial));
  const [name, setName] = uSa(role && mode !== "clone" ? role.name : role ? role.name + " (copy)" : "");
  const readOnly = mode === "inspect";
  const toggle = (pid) => { if (readOnly) return; setSel(s => { const n = new Set(s); n.has(pid) ? n.delete(pid) : n.add(pid); return n; }); };
  const title = mode === "inspect" ? role.name : mode === "clone" ? "Clone role" : mode === "create" ? "New custom role" : "Edit role";

  return (
    <Modal title={title} max={720} onClose={onClose}
      foot={readOnly
        ? <><Btn variant="text" onClick={onClose}>Close</Btn><Btn icon="copy" onClick={() => onClose("clone")}>Clone…</Btn></>
        : <><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn disabled={!name.trim()} onClick={() => { pushOutcome(null, { sev: "ok", title: `Role “${name}” saved`, detail: `${sel.size} permission${sel.size === 1 ? "" : "s"}.` }); onClose(); }}>Save role</Btn></>}>
      {readOnly ? <Alert severity="info" icon="lock">Built-in roles can’t be edited — but they’re fully inspectable, and cloneable. “The built-in one but without restart” is the common real request.</Alert>
        : <TextField label="Role name" req value={name} onChange={e => setName(e.target.value)} />}
      {role && role.clonedFrom && <div className="t-cap muted" style={{ marginTop: 8 }}>Cloned from <strong>{role.clonedFrom}</strong>.</div>}
      <div className="row between" style={{ margin: "16px 0 4px" }}><div className="t-over">Permissions · grouped by resource</div><div className="t-cap muted">{sel.size} selected</div></div>
      {PERM_GROUPS.map(g => {
        const allOn = g.perms.length && g.perms.every(p => sel.has(p.id));
        return (
          <div className="perm-grp" key={g.res}>
            <div className="perm-grp-h"><span className="grow">{g.label}</span>{!readOnly && <button className="btn btn-text btn-sm" onClick={() => setSel(s => { const n = new Set(s); g.perms.forEach(p => allOn ? n.delete(p.id) : n.add(p.id)); return n; })}>{allOn ? "Clear" : "All"}</button>}</div>
            {g.perms.map(p => { const meta = STATUS_META[p.status]; const on = sel.has(p.id); const selectable = !readOnly; return (
              <div className={`perm-row ${selectable ? "selectable" : ""}`} key={p.id} onClick={() => toggle(p.id)}>
                <Cbx on={on} indeterminate={false} onClick={() => toggle(p.id)} label={p.id} />
                <span className="perm-id grow">{p.id}</span>
                {p.hc && <span className="hc"><Ic name="alert" size={11} /> high-consequence</span>}
                {p.twoSided && <Chip tone="info" sm icon="link">two-sided</Chip>}
                <Tip tip={meta.tip}><Chip tone={meta.tone} sm>{meta.label}</Chip></Tip>
              </div>
            ); })}
          </div>
        );
      })}
      <div className="finding" style={{ marginTop: 14 }}><Ic name="alert" size={16} style={{ flex: "none", color: "var(--warning-w100)" }} /><div className="t-cap"><strong>Catalog not ratified.</strong> Only <span className="mono">target.use</span> and <span className="mono">instance.bindTarget</span> are grantable today; six metrics/dashboard verbs are proposed (◇) but can’t be granted; the rest are candidate placeholders. Adding a verb is a data change — this layout doesn’t move.</div></div>
    </Modal>
  );
};

const RolesView = () => {
  const [editor, setEditor] = uSa(null); // {role, mode}
  const builtins = RBAC_ROLES.filter(r => r.kind === "built-in");
  const customs = RBAC_ROLES.filter(r => r.kind === "custom");
  const openInspect = (r) => setEditor({ role: r, mode: r.kind === "built-in" ? "inspect" : "edit" });
  const card = (r) => (
    <div className="role-card" key={r.id} onClick={() => openInspect(r)}>
      <div className="row between"><span className="row gap8"><Ic name="shield" size={17} style={{ color: r.kind === "built-in" ? "var(--info-wMain,#0591d3)" : "var(--primary)" }} /><span className="t-sub">{r.name}</span></span><Chip tone={r.kind === "built-in" ? "info" : "neutral"} sm>{r.kind}</Chip></div>
      <div className="t-cap muted" style={{ margin: "8px 0 10px", lineHeight: 1.5 }}>{r.desc}</div>
      <div className="row gap6 wrap">
        <Chip tone="neutral" sm>{r.perms === "*" ? "all" : r.perms.length} perms</Chip>
        {(r.perms === "*" ? ALL_PERMS : r.perms).some(p => PERM_BY_ID[p] && PERM_BY_ID[p].hc) && <span className="hc"><Ic name="alert" size={11} /> incl. high-consequence</span>}
      </div>
    </div>
  );
  return (
    <div>
      <div className="row between wrap gap12" style={{ marginBottom: 6 }}>
        <div><div className="t-h6">Role definitions</div><div className="t-cap muted" style={{ marginTop: 2 }}>A role is a permission set with a name. Assignment is separate — roles never reference a principal.</div></div>
        <Btn size="sm" icon="plus" onClick={() => setEditor({ role: null, mode: "create" })}>New custom role</Btn>
      </div>
      <div className="t-over" style={{ margin: "18px 0 10px" }}>Built-in <span className="muted" style={{ textTransform: "none", letterSpacing: 0 }}>· read-only, cloneable</span></div>
      <div className="sol-grid" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(300px,1fr))" }}>{builtins.map(card)}</div>
      <div className="t-over" style={{ margin: "22px 0 10px" }}>Custom</div>
      <div className="sol-grid" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(300px,1fr))" }}>{customs.map(card)}</div>
      {editor && <RoleEditor role={editor.role} mode={editor.mode} onClose={(next) => { if (next === "clone") { setEditor({ role: editor.role, mode: "clone" }); } else setEditor(null); }} />}
    </div>
  );
};

// ---- P14 users ----
const AddUserModal = ({ onClose }) => {
  const [cred, setCred] = uSa("temp");
  return (
    <Modal title="Add a user" max={560} onClose={onClose}
      foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn onClick={() => { pushOutcome(null, { sev: "ok", title: "User created", detail: "Assign a role separately on the Assignments screen." }); onClose(); }}>Create user</Btn></>}>
      <div className="row gap12"><TextField label="Full name" req /><TextField label="Email" req type="email" /></div>
      <Alert severity="neutral" icon="info" style={{ marginTop: 14 }}>Creating a user does <strong>not</strong> assign a role — identity and authorization are separate screens, on purpose, so identity can become IdP-backed later without touching assignments.</Alert>
      <div className="t-over" style={{ margin: "18px 0 8px" }}>Credential</div>
      {[["temp", "Admin-set temporary credential", "Forced change on first sign-in. No external dependency — the chosen option."], ["invite", "Emailed invitation link", "Cleanest UX, but introduces an email capability this console does not have — a dependency that does not exist."], ["oob", "Out-of-band handoff", "Communicate the credential yourself. Honest, low-tech, no dependency."]].map(([v, t, d]) => (
        <div key={v} className={`strat ${cred === v ? "on" : ""}`} style={{ marginBottom: 8 }} onClick={() => setCred(v)}><span className="radio" /><div><div className="strong t-sm">{t}{v === "temp" && <Chip tone="ok" sm style={{ marginLeft: 8 }}>chosen</Chip>}{v === "invite" && <Chip tone="warn" sm style={{ marginLeft: 8 }}>needs email</Chip>}</div><div className="t-cap muted" style={{ marginTop: 3 }}>{d}</div></div></div>
      ))}
    </Modal>
  );
};

const BootstrapView = ({ onExit }) => (
  <div className="empty" style={{ maxWidth: 560 }}>
    <div className="eic"><Ic name="rocket" size={26} /></div>
    <h3>Welcome — set up the first administrator</h3>
    <p className="t-body">This deployment has no users yet, so no one can sign in. This screen is reachable <strong>only while the user table is empty</strong>. Create the first admin with an installer-provided initial secret.</p>
    <div className="col gap12" style={{ maxWidth: 380, margin: "0 auto 18px", textAlign: "left" }}>
      <TextField label="Admin email" req />
      <TextField label="Initial secret (from installer)" req reveal />
      <TextField label="New password" req reveal />
    </div>
    <Btn onClick={onExit}>Create first admin &amp; sign in</Btn>
    <div className="finding" style={{ marginTop: 22, textAlign: "left" }}><Ic name="alert" size={16} style={{ flex: "none", color: "var(--warning-w100)" }} /><div className="t-cap"><strong>Load-bearing invention.</strong> The first-admin bootstrap is the single largest hole — it is the first thing that happens to a new deployment and is specified nowhere. The installer-secret approach shown here needs a decision before anyone builds it.</div></div>
  </div>
);

const UsersView = () => {
  const [modal, setModal] = uSa(false);
  const [bootstrap, setBootstrap] = uSa(false);
  const [users, setUsers] = uSa(LOCAL_USERS);
  if (bootstrap) return <BootstrapView onExit={() => setBootstrap(false)} />;
  return (
    <div>
      <div className="row between wrap gap12" style={{ marginBottom: 6 }}>
        <div><div className="t-h6">Users</div><div className="t-cap muted" style={{ marginTop: 2 }}>Local accounts — a stopgap that an identity provider will replace.</div></div>
        <div className="row gap8"><Btn size="sm" variant="text" icon="rocket" onClick={() => setBootstrap(true)}>Simulate first run</Btn><Btn size="sm" icon="plus" onClick={() => setModal(true)}>Add user</Btn></div>
      </div>
      <Alert severity="warn" icon="alert" style={{ margin: "12px 0" }} title="Everything on this screen is invention — it will be replaced">
        When identities come from each customer’s IdP, this becomes a <strong>read-only list</strong> the provider supplies: <span className="strong">Add user</span> and <span className="strong">Deactivate</span> disappear (they move to the provider); the <span className="strong">list itself survives</span>. Assignments (a separate screen) are untouched.
      </Alert>
      <div className="dg"><div className="dg-scroll"><table className="matrix" style={{ minWidth: 760 }}>
        <thead><tr><th>User</th><th>Status</th><th>Last sign-in</th><th>Created by</th><th></th></tr></thead>
        <tbody>
          {users.map(u => (
            <tr key={u.id}>
              <td><PrincipalTag p={{ kind: "user", name: u.name, sub: u.email }} /></td>
              <td>{u.status === "active" ? <Chip tone="ok" sm dot>Active</Chip> : <Chip tone="idle" sm icon="ban">Deactivated</Chip>}</td>
              <td className="t-sm muted">{u.lastSignIn}</td>
              <td className="t-sm muted">{u.created}</td>
              <td style={{ textAlign: "right" }}>{u.status === "active" ? <Btn size="sm" variant="text" onClick={() => setUsers(us => us.map(x => x.id === u.id ? { ...x, status: "deactivated" } : x))}>Deactivate</Btn> : <Btn size="sm" variant="text" onClick={() => setUsers(us => us.map(x => x.id === u.id ? { ...x, status: "active" } : x))}>Reactivate</Btn>}</td>
            </tr>
          ))}
        </tbody>
      </table></div></div>
      <div className="t-cap muted mt12 row gap6"><Ic name="info" size={13} /> Deactivate, never delete — an account that authored configuration history stays attributable.</div>
      {modal && <AddUserModal onClose={() => setModal(false)} />}
    </div>
  );
};

const ADMIN_TABS = [{ k: "roles", label: "Roles", icon: "shield" }, { k: "assignments", label: "Assignments", icon: "link" }, { k: "access", label: "Access check", icon: "search" }, { k: "users", label: "Users", icon: "users" }];

const AdminScreen = ({ route, nav }) => {
  const sub = route.parts[1] || "roles";
  const [findings, setFindings] = uSa(false);
  return (
    <div className="inst-shell">
      <div className="ihead">
        <div className="ihead-row"><span className="iname">Admin</span><Chip tone="neutral" sm icon="clock">Later increment</Chip><Chip tone="idle" sm>Not in v1 nav</Chip><span className="grow" /><button className="finding" style={{ cursor: "pointer", alignItems: "center", padding: "8px 12px" }} onClick={() => setFindings(f => !f)}><Ic name="alert" size={15} style={{ color: "var(--warning-w100)" }} /><span className="strong t-sm">Findings</span><Ic name={findings ? "chevUp" : "chevDown"} size={14} /></button></div>
        {findings && <div style={{ padding: "4px 0 12px" }}><div className="finding"><Ic name="doc" size={16} style={{ flex: "none", color: "var(--warning-w100)" }} /><div className="t-cap" style={{ lineHeight: 1.7 }}><strong>Model locked, catalog &amp; identity not.</strong> The verb catalog is unratified — 2 grantable, 6 proposed (◇), the rest placeholder. P14 (users) is entirely invented; the <strong>first-admin bootstrap</strong> is the load-bearing hole. <strong>principal</strong> is modelled as a discriminated user|group reference so the IdP migration is a data change, not a rewrite — which is why identity and assignment are separate screens.</div></div></div>}
      </div>
      <div className="itabs" role="tablist" aria-label="Admin sections">
        {ADMIN_TABS.map(t => <button key={t.k} role="tab" aria-selected={sub === t.k} className={`itab ${sub === t.k ? "on" : ""}`} onClick={() => nav(`/admin/${t.k}`)}><Ic name={t.icon} size={16} />{t.label}</button>)}
      </div>
      <div className="inst-scroll"><div className="page page-wide">
        {sub === "roles" ? <RolesView />
          : sub === "assignments" ? <AssignmentsView />
            : sub === "access" ? <AccessCheckView />
              : <UsersView />}
      </div></div>
    </div>
  );
};

Object.assign(window, { AdminScreen, RolesView, RoleEditor, UsersView, AddUserModal, BootstrapView });
