// MUI-structured primitives painted with Solace tokens. Shared via window.*.
const { useState, useRef, useEffect } = React;

const Btn = ({ variant = "contained", size, icon, iconRight, children, className = "", ...p }) => (
  <button className={`btn btn-${variant} ${size ? "btn-" + size : ""} ${className}`} {...p}>
    {icon && <Ic name={icon} size={size === "sm" ? 15 : 17} />}
    {children}
    {iconRight && <Ic name={iconRight} size={size === "sm" ? 15 : 17} />}
  </button>
);
const IconBtn = ({ icon, size = 18, small, className = "", ...p }) => (
  <button className={`iconbtn ${small ? "sm" : ""} ${className}`} {...p}><Ic name={icon} size={size} /></button>
);

// Outlined text field with floating label (MUI). placeholder=" " drives the float.
const TextField = ({ label, req, help, error, reveal, dense, id, ...p }) => {
  const [show, setShow] = useState(false);
  const t = reveal ? (show ? "text" : "password") : (p.type || "text");
  const fid = id || "tf-" + Math.random().toString(36).slice(2, 8);
  return (
    <div className="col" style={{ gap: 0 }}>
      <div className={`tf ${dense ? "dense" : ""} ${error ? "err" : ""}`}>
        <input id={fid} {...p} type={t} placeholder=" " aria-invalid={!!error} aria-describedby={help || error ? fid + "-h" : undefined} />
        <label className="tfl" htmlFor={fid}>{label}{req && <span className="tf-req"> *</span>}</label>
        {reveal && <button type="button" className="iconbtn sm tfaffix" onClick={() => setShow(s => !s)} tabIndex={-1} aria-label={show ? "Hide password" : "Show password"}><Ic name={show ? "eyeOff" : "eye"} size={16} /></button>}
      </div>
      {(help || error) && <div id={fid + "-h"} className={`tf-help ${error ? "err" : ""}`}>{error || help}</div>}
    </div>
  );
};

const Tabs = ({ tabs, value, onChange }) => (
  <div className="tabs" role="tablist">
    {tabs.map(t => (
      <button key={t.id} role="tab" aria-selected={value === t.id} className={`tab ${value === t.id ? "on" : ""}`} onClick={() => onChange(t.id)}>
        {t.icon && <Ic name={t.icon} size={16} />}{t.label}{t.count != null && <span className="chip chip-sm chip-neutral" style={{ marginLeft: 2 }}>{t.count}</span>}
      </button>
    ))}
  </div>
);

const Chip = ({ tone = "neutral", dot, icon, sm, children, style }) => (
  <span className={`chip chip-${tone} ${sm ? "chip-sm" : ""}`} style={style}>
    {dot && <span className="cd" />}{icon && <Ic name={icon} size={sm ? 12 : 13} />}{children}
  </span>
);

// Renders a state-vocabulary entry as chip + optional info tooltip (icon+text+colour).
const StateChip = ({ s, sm, tip = true, suffix }) => {
  if (!s) return <span className="dash">—</span>;
  return (
    <span className="row" style={{ gap: 6 }}>
      <Chip tone={s.tone} icon={s.icon} sm={sm}>{s.label}{suffix ? ` ${suffix}` : ""}</Chip>
      {tip && s.tip && <InfoI tip={s.tip} />}
    </span>
  );
};

const Sw = ({ on, onChange, label }) => (
  <button type="button" className={`sw ${on ? "on" : ""}`} onClick={() => onChange(!on)} aria-pressed={on} aria-label={label} />
);
const Cbx = ({ on, indeterminate, onClick, label }) => (
  <button type="button" role="checkbox" aria-checked={indeterminate ? "mixed" : on} aria-label={label} className={`cbx ${on || indeterminate ? "on" : ""}`} onClick={onClick}>
    {indeterminate ? <Ic name="minus" size={13} /> : on ? <Ic name="check" size={13} /> : null}
  </button>
);

const Menu = ({ trigger, children, align = "left", width = 240 }) => {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    const h = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", h);
    const k = (e) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", k);
    return () => { document.removeEventListener("mousedown", h); document.removeEventListener("keydown", k); };
  }, []);
  return (
    <div ref={ref} style={{ position: "relative" }}>
      {trigger(open, () => setOpen(o => !o))}
      {open && <div className="pop" style={{ [align]: 0, top: "calc(100% + 6px)", width }} role="menu">
        {typeof children === "function" ? children(() => setOpen(false)) : children}
      </div>}
    </div>
  );
};

const Alert = ({ severity = "info", title, icon, children, style, action }) => {
  const ic = icon || { info: "info", warn: "alert", err: "xCircle", ok: "checkCircle", neutral: "info" }[severity];
  return (
    <div className={`alert alert-${severity}`} style={style} role={severity === "err" ? "alert" : undefined}>
      <span className="ai"><Ic name={ic} size={19} /></span>
      <div className="grow">{title && <div className="at">{title}</div>}<div>{children}</div></div>
      {action}
    </div>
  );
};

const Tip = ({ tip, children }) => (
  <span className="tip" tabIndex={0}>{children}<span className="tipbox" role="tooltip">{tip}</span></span>
);
const InfoI = ({ tip }) => <Tip tip={tip}><span className="info-i">i</span></Tip>;

const Paper = ({ pad, hover, className = "", style, children, ...p }) => (
  <div className={`paper ${pad ? "paper-pad" : ""} ${hover ? "hoverable" : ""} ${className}`} style={style} {...p}>{children}</div>
);

const EmptyState = ({ icon = "box", title, children, action }) => (
  <div className="empty">
    <div className="eic"><Ic name={icon} size={28} /></div>
    <h3>{title}</h3>
    <p className="t-body">{children}</p>
    {action}
  </div>
);

const Sk = ({ w = "100%", h = 14, r = 6, style }) => <span className="sk" style={{ display: "inline-block", width: w, height: h, borderRadius: r, ...style }} />;

const Modal = ({ title, children, foot, onClose, max = 560 }) => {
  useEffect(() => { const k = (e) => e.key === "Escape" && onClose(); document.addEventListener("keydown", k); return () => document.removeEventListener("keydown", k); }, [onClose]);
  return (
    <div className="scrim" onMouseDown={onClose}>
      <div className="modal" style={{ maxWidth: max }} onMouseDown={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-h"><span className="t-h6">{title}</span><IconBtn icon="x" onClick={onClose} aria-label="Close" /></div>
        <div className="modal-b">{children}</div>
        {foot && <div className="modal-f">{foot}</div>}
      </div>
    </div>
  );
};

const Tbg = ({ options, value, onChange }) => (
  <div className="tbg" role="group">
    {options.map(o => (
      <button key={o.v} className={value === o.v ? "on" : ""} aria-pressed={value === o.v} onClick={() => onChange(o.v)}>
        {o.icon && <Ic name={o.icon} size={15} />}{o.label}
      </button>
    ))}
  </div>
);

const EnvChip = ({ env }) => <Chip tone={ENV_TONE[env] || "neutral"} sm>{env}</Chip>;

const PageHead = ({ title, sub, right }) => (
  <div className="pagehead">
    <div className="grow"><h1 className="t-h4">{title}</h1>{sub && <p className="t-body muted" style={{ margin: "7px 0 0", maxWidth: 780 }}>{sub}</p>}</div>
    {right}
  </div>
);

Object.assign(window, { Btn, IconBtn, TextField, Tabs, Chip, StateChip, Sw, Cbx, Menu, Alert, Tip, InfoI, Paper, EmptyState, Sk, Modal, Tbg, EnvChip, PageHead });
