// P5 field rendering. FieldControl switches on controlKind only — the same code
// renders every solution. `virtual` fields (renderedPath: null) render IDENTICALLY
// to real ones by design: no badge, no visual tell. `advanced` affects disclosure
// only. Validations are computed live so REQUIRED clears as you type.
const { useState: useStateF } = React;

function isEmpty(v) { return v === undefined || v === null || v === "" || (Array.isArray(v) && v.length === 0); }

// Live validations: REQUIRED is dynamic (clears when filled); ABOVE_RECOMMENDED tracks the value.
function activeValidations(field, val) {
  const out = [];
  (field.validations || []).forEach(v => {
    if (v.severity === "block" && v.code === "REQUIRED") { if (isEmpty(val)) out.push(v); return; }
    if (v.severity === "warn" && v.code === "ABOVE_RECOMMENDED") { if (Number(val) > 1000000) out.push(v); return; }
    out.push(v);
  });
  return out;
}

const SecretControl = ({ val, onChange, disabled }) => {
  const set = val && val.secret;
  const [entering, setEntering] = useStateF(false);
  const [tmp, setTmp] = useStateF("");
  if (entering) {
    return (
      <div className="secret">
        <div className="t-cap strong" style={{ marginBottom: 8 }}>{set ? "Replace credential" : "Set credential"}</div>
        <div className="row gap8">
          <input className="ctl-in" type="password" value={tmp} autoFocus placeholder="Enter secret value" onChange={e => setTmp(e.target.value)} aria-label="Secret value" />
          <Btn size="sm" disabled={!tmp} onClick={() => { onChange({ secret: true, ref: "secretRef://kv/acme/" + (field_ref()) }); setEntering(false); setTmp(""); }}>Save</Btn>
          <Btn size="sm" variant="text" onClick={() => { setEntering(false); setTmp(""); }}>Cancel</Btn>
        </div>
        <div className="t-cap muted" style={{ marginTop: 8 }}>The value is write-only — it is stored as a reference and never displayed again, here or in diffs, exports and history.</div>
      </div>
    );
  }
  return (
    <div className="secret">
      <div className="row between">
        <div className="col" style={{ gap: 0 }}>
          <div className="sst">{set ? <><span className="sdot sd-ok" /><span>Credential set</span></> : <><Ic name="key" size={16} style={{ color: "var(--secondary-text-wMain)" }} /><span style={{ fontWeight: 600, color: "var(--secondary-text-wMain)" }}>No credential set</span></>}</div>
          {set && <div className="sref">{val.ref || "secretRef://kv/acme/•••"}</div>}
        </div>
        <div className="row gap8">
          <Btn size="sm" variant="outlined" disabled={disabled} onClick={() => setEntering(true)}>{set ? "Replace" : "Set"}</Btn>
          {set && <Btn size="sm" variant="text" disabled={disabled} onClick={() => onChange({ secret: false })}>Clear</Btn>}
        </div>
      </div>
    </div>
  );
};
let _rc = 0; function field_ref() { _rc = (_rc + 1) % 9000; return "s" + (1000 + _rc); }

const StringList = ({ val, onChange, disabled }) => {
  const list = Array.isArray(val) ? val : [];
  const upd = (i, v) => { const n = [...list]; n[i] = v; onChange(n); };
  return (
    <div className="kvlist">
      {list.map((item, i) => (
        <div className="kvrow" key={i}>
          <input className="ctl-in" value={item} disabled={disabled} onChange={e => upd(i, e.target.value)} aria-label={`Item ${i + 1}`} />
          <IconBtn icon="trash" small disabled={disabled} onClick={() => onChange(list.filter((_, j) => j !== i))} aria-label="Remove" />
        </div>
      ))}
      <div><Btn size="sm" variant="text" icon="plus" disabled={disabled} onClick={() => onChange([...list, ""])}>Add item</Btn></div>
    </div>
  );
};

const KeyValue = ({ val, onChange, disabled }) => {
  const rows = Array.isArray(val) ? val : [];
  const upd = (i, k, v) => { const n = rows.map((r, j) => j === i ? { k, v } : r); onChange(n); };
  return (
    <div className="kvlist">
      {rows.map((r, i) => (
        <div className="kvrow" key={i}>
          <input className="ctl-in" value={r.k} placeholder="key" disabled={disabled} onChange={e => upd(i, e.target.value, r.v)} aria-label="Key" />
          <span className="muted">→</span>
          <input className="ctl-in" value={r.v} placeholder="value" disabled={disabled} onChange={e => upd(i, r.k, e.target.value)} aria-label="Value" />
          <IconBtn icon="trash" small disabled={disabled} onClick={() => onChange(rows.filter((_, j) => j !== i))} aria-label="Remove" />
        </div>
      ))}
      <div><Btn size="sm" variant="text" icon="plus" disabled={disabled} onClick={() => onChange([...rows, { k: "", v: "" }])}>Add mapping</Btn></div>
    </div>
  );
};

const FieldControl = ({ field, val, onChange, disabled, err }) => {
  const ck = field.controlKind;
  const ec = err ? " err" : "";
  if (ck === "switch") return (
    <div className="switch-row"><Sw on={!!val} onChange={v => onChange(v)} label={field.label} /><span className="sv">{val ? "Enabled" : "Disabled"}</span></div>
  );
  if (ck === "select") return (
    <select className={"ctl-sel" + ec} value={val ?? ""} disabled={disabled} onChange={e => onChange(e.target.value)} aria-label={field.label}>
      <option value="" disabled>Select…</option>
      {(field.options || []).map(o => <option key={o} value={o}>{o}</option>)}
    </select>
  );
  if (ck === "number") return <input className={"ctl-num" + ec} type="number" value={val ?? ""} disabled={disabled} placeholder="—" onChange={e => onChange(e.target.value === "" ? undefined : Number(e.target.value))} aria-label={field.label} />;
  if (ck === "duration") return (
    <div className="dur-wrap"><input className={"ctl-in" + ec} value={val ?? ""} disabled={disabled} placeholder="e.g. 120m" onChange={e => onChange(e.target.value)} style={{ maxWidth: 200 }} aria-label={field.label} /><span className="dur-hint">Spring Duration · 120m · PT2H</span></div>
  );
  if (ck === "secret") return <SecretControl val={val} onChange={onChange} disabled={disabled} />;
  if (ck === "string-list") return <StringList val={val} onChange={onChange} disabled={disabled} />;
  if (ck === "key-value") return <KeyValue val={val} onChange={onChange} disabled={disabled} />;
  if (ck === "table") return <div className="gated"><Ic name="table" size={18} /><div className="t-sm">Tabular editor — columns declared by the package. (No table field in this fixture.)</div></div>;
  if (ck === "yaml-editor") return <div className="gated"><Ic name="lock" size={18} style={{ color: "var(--secondary-text-wMain)" }} /><div><div className="strong t-sm">Raw YAML editing is gated</div><div className="t-cap muted">This field is edited through the structured form. The raw editor is disabled in this release.</div></div></div>;
  // text
  return <input className={"ctl-in" + ec} value={val ?? ""} disabled={disabled} placeholder="—" onChange={e => onChange(e.target.value)} aria-label={field.label} />;
};

// One field: label row (required *, discriminator hint, default/modified), control, help, validations.
const FieldRow = ({ field, val, initial, onChange, disabled, showPath, crumb, rowRef }) => {
  const vs = activeValidations(field, valForVal(field, val));
  const hasBlock = vs.some(v => v.severity === "block");
  const isRequired = (field.validations || []).some(v => v.code === "REQUIRED");
  const changed = JSON.stringify(val) !== JSON.stringify(initial);
  const isDefault = field.value.kind === "value" && field.value.source === "default" && !changed;
  return (
    <div className={`fld ${hasBlock ? "blocked-f" : ""}`} ref={rowRef}>
      {crumb && <div className="fld-crumb">{crumb}</div>}
      <div className="fld-top">
        <span className="fld-lbl">{field.label}</span>
        {isRequired && <span className="tf-req" aria-label="required">*</span>}
        {field.discriminator && <Tip tip="Choosing this changes which fields below apply. Values in a hidden branch are kept — switch back and they return."><span className="fld-hint row gap6" style={{ color: "var(--primary)" }}><Ic name="gitBranch" size={13} />controls fields below</span></Tip>}
        <span className="grow" />
        {showPath && field.renderedPath && <span className="fld-path">{field.renderedPath}</span>}
        {isDefault && <span className="fld-hint">Default</span>}
        {changed && <Chip tone="info" sm>Modified</Chip>}
      </div>
      <div className={`fld-ctl ${["key-value", "string-list", "table", "yaml-editor"].includes(field.controlKind) ? "full" : ""}`}>
        <FieldControl field={field} val={val} onChange={onChange} disabled={disabled} err={hasBlock} />
      </div>
      {field.helperText && <div className="fld-help">{field.helperText}</div>}
      {vs.map((v, i) => (
        <div key={i} className={`vmsg ${v.severity === "block" ? "vmsg-block" : "vmsg-warn"}`}>
          <Ic name={v.severity === "block" ? "xCircle" : "alert"} size={15} />
          <span>{v.message}{v.severity === "warn" && <span className="muted"> · a Solace recommendation, not a hard limit — you can proceed</span>}</span>
        </div>
      ))}
    </div>
  );
};
// secret vals aren't validated by value; pass through for others
function valForVal(field, val) { return field.controlKind === "secret" ? (val && val.secret ? "set" : "") : val; }

Object.assign(window, { FieldControl, FieldRow, SecretControl, StringList, KeyValue, activeValidations, isEmpty });
