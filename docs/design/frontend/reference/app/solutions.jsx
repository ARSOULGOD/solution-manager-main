// P3 · Solutions catalog — the MI product types this deployment can manage.
// Real package-schema fields: solution, packageVersion, appliesTo, provenance,
// authoring (reviewedRows/totalRows), components. Two carry extra weight:
// provenance.author (solace-signed vs dev-mode/unsigned — a conspicuous trust
// boundary) and authoring review coverage (partial is normal, not alarming;
// fully-unreviewed must not look identical to curated). "Load a package" upload
// is visible to Solace super-admins only.
const { useState, useRef } = React;

const ReviewMeter = ({ authoring }) => {
  const { reviewedRows, totalRows } = authoring;
  const pct = totalRows ? Math.round((reviewedRows / totalRows) * 100) : 0;
  return (
    <div className="review">
      <div className="row between" style={{ marginBottom: 6 }}>
        <span className="t-cap strong">Authoring review</span>
        {pct === 100 ? <Chip tone="ok" sm icon="check">Fully reviewed</Chip>
          : pct === 0 ? <Chip tone="err" sm icon="alert">Not reviewed</Chip>
            : <Chip tone="warn" sm>{pct}% reviewed</Chip>}
      </div>
      <div className="review-bar">
        <i style={{ width: pct + "%", background: pct === 0 ? "var(--error-wMain)" : "var(--success-wMain)" }} />
        <i style={{ width: (100 - pct) + "%", background: "var(--warning-w30,#f6c98a)" }} />
      </div>
      <div className="t-cap muted" style={{ marginTop: 6 }}>
        {reviewedRows.toLocaleString()} / {totalRows.toLocaleString()} rows reviewed
        {pct > 0 && pct < 100 ? " · the remainder auto-extracted — a normal state" : pct === 0 ? " · assembled by automated extraction, unreviewed" : ""}
      </div>
    </div>
  );
};

const SolCard = ({ s, nav }) => {
  const dev = s.devMode;
  return (
    <div className={`sol-card ${dev ? "dev" : ""}`}>
      {dev && <div className="devbar"><Ic name="alert" size={15} /> Unsigned · development mode — not trusted like a signed package</div>}
      <div className="sc-top">
        <div className="sol-ic" style={{ background: s.color }}><Ic name={s.icon} size={24} /></div>
        <div className="grow">
          <div className="row between"><div className="t-h6">{s.name}</div>{s.entitled ? <Chip tone="ok" sm dot>Entitled</Chip> : <Chip tone="neutral" sm>Not entitled</Chip>}</div>
          <div className="t-cap muted" style={{ marginTop: 2 }}>{s.kind}</div>
          <div style={{ marginTop: 11 }}>
            {dev
              ? <span className="row gap6"><Chip tone="err" icon="beaker">Unsigned · dev-mode</Chip><InfoI tip="No Solace signature. Loadable only in an explicit development mode, and never trusted like a signed package." /></span>
              : <span className="row gap6"><Chip tone="ok" icon="badgeCheck">Signed · Solace</Chip><InfoI tip={`Solace signature ${s.provenance.signature} — verified.`} /></span>}
          </div>
        </div>
      </div>
      <div className="sol-body">
        <dl className="kv">
          <dt>Package version</dt><dd className="mono">{s.packageVersion}</dd>
          <dt>Applies to</dt><dd><span className="mono">{s.appliesTo}</span> <InfoI tip="Semver range of MI versions this package serves — the package's own revision, not the MI's." /></dd>
          <dt>Source</dt><dd>{s.authoring.source}</dd>
        </dl>
        <ReviewMeter authoring={s.authoring} />
        <div className="row wrap gap6" style={{ marginTop: 14 }}>{s.components.map(c => <span key={c} className="pill mono">{c}</span>)}</div>
      </div>
      <div className="sol-foot">
        <span className="row gap6 t-sm muted"><Ic name="server" size={15} /> {s.instances} instance{s.instances === 1 ? "" : "s"}</span>
        <span className="row gap6 t-sm muted"><Ic name="activity" size={15} /> {s.telemetry}</span>
        <div className="grow" />
        {dev
          ? <Tip tip="Unsigned — loadable and manageable only in development mode."><span><Btn size="sm" variant="outlined" disabled>Open</Btn></span></Tip>
          : <Btn size="sm" variant="outlined" iconRight="arrowRight" onClick={() => nav(`/solutions/${s.id}/instances`)}>Open</Btn>}
      </div>
    </div>
  );
};

const LoadModal = ({ onClose, onLoaded }) => {
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef(null);
  const pick = (f) => { if (f) setFile({ name: f.name, size: f.size }); };
  const doLoad = () => { setBusy(true); setTimeout(() => { setBusy(false); onLoaded(file ? file.name : "cache-engine-7.sompkg"); }, 700); };
  return (
    <Modal title="Load a Solution Package" onClose={onClose} foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn disabled={!file || busy} onClick={doLoad}>{busy ? "Loading…" : "Load package"}</Btn></>}>
      <input ref={inputRef} type="file" accept=".sompkg,.json,.zip" style={{ display: "none" }} onChange={e => pick(e.target.files[0])} />
      {file ? (
        <div className="dropzone" style={{ borderStyle: "solid", borderColor: "var(--primary)", background: "var(--primary-050)" }}>
          <div style={{ width: 52, height: 52, margin: "0 auto 12px", borderRadius: 12, background: "var(--success-w10,#e5f5f0)", color: "var(--success-w100,#0a7a5e)", display: "flex", alignItems: "center", justifyContent: "center" }}><Ic name="badgeCheck" size={24} /></div>
          <div className="strong mono">{file.name}</div>
          <div className="t-cap muted" style={{ marginTop: 4 }}>Signature verified · Solace-signed · <button className="btn btn-text btn-sm" onClick={() => setFile(null)}>Choose another</button></div>
        </div>
      ) : (
        <div className="dropzone" role="button" tabIndex={0} onClick={() => inputRef.current.click()} onKeyDown={e => e.key === "Enter" && inputRef.current.click()}
          onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); pick(e.dataTransfer.files[0]); }}>
          <div style={{ width: 52, height: 52, margin: "0 auto 12px", borderRadius: 12, background: "var(--primary-100)", color: "var(--primary)", display: "flex", alignItems: "center", justifyContent: "center" }}><Ic name="upload" size={24} /></div>
          <div className="strong">Drop a package file here</div>
          <div className="t-cap muted" style={{ marginTop: 4 }}>or click to browse · signed <span className="mono">.sompkg</span></div>
        </div>
      )}
      <div className="row gap8" style={{ marginTop: 12, alignItems: "center" }}>
        <span className="t-cap muted">No file handy?</span>
        <Btn size="sm" variant="outlined" icon="package" onClick={() => setFile({ name: "cache-engine-7.sompkg", size: 0 })}>Use the sample package</Btn>
      </div>
      <Alert severity="info" style={{ marginTop: 16 }}>Solace-signed packages load normally. An <strong>unsigned (dev-mode)</strong> package is admitted only when the console runs in development mode, and is marked conspicuously wherever it appears.</Alert>
      <p className="t-cap muted" style={{ marginTop: 12 }}>Visible to Solace super-admins only.</p>
    </Modal>
  );
};

const SolutionsScreen = ({ nav }) => {
  const { demo, setDemo } = window.useDemo();
  const [load, setLoad] = useState(false);
  const right = CURRENT_USER.superAdmin ? <Btn icon="upload" onClick={() => setLoad(true)}>Load a package</Btn> : null;

  let body;
  if (demo === "loading") {
    body = <div className="sol-grid mt16">{[0, 1, 2].map(i => <Paper key={i} pad style={{ height: 320 }}><div className="row gap12"><Sk w={50} h={50} r={12} /><div className="grow"><Sk w="60%" h={16} /><div className="mt8"><Sk w="40%" h={12} /></div></div></div><div className="mt24"><Sk w="100%" h={12} /><div className="mt8"><Sk w="90%" h={12} /></div><div className="mt16"><Sk w="100%" h={7} r={99} /></div></div></Paper>)}</div>;
  } else if (demo === "error") {
    body = <Alert severity="err" title="Couldn’t load the catalog" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>The console reached the server but the package list failed to load. Retry, or check the control-plane status.</Alert>;
  } else if (demo === "empty") {
    body = <Paper style={{ marginTop: 8 }}><EmptyState icon="package" title="No solution packages loaded"
      action={CURRENT_USER.superAdmin ? <Btn icon="upload" onClick={() => setLoad(true)}>Load a package</Btn> : <Alert severity="neutral" icon="lock">Ask a Solace super-admin to load one.</Alert>}>
      A Solution Package — published by Solace, loaded here — defines every solution this deployment can manage. Load one to populate the catalog.
    </EmptyState></Paper>;
  } else {
    body = <div className="sol-grid mt16">{SOLUTIONS.map(s => <SolCard key={s.id} s={s} nav={nav} />)}</div>;
  }

  return (
    <div className="page page-wide">
      <PageHead title="Solutions" sub="The solution types this deployment can manage. Each renders entirely from its Solution Package — the console holds no per-solution code." right={right} />
      {body}
      {load && <LoadModal onClose={() => setLoad(false)} onLoaded={(name) => { setLoad(false); setDemo("populated"); window.pushOutcome && window.pushOutcome(null, { sev: "ok", title: "Package loaded", detail: `${name} — signature verified. The catalog now renders from it.` }); }} />}
    </div>
  );
};
window.SolutionsScreen = SolutionsScreen;
