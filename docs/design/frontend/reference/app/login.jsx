// P1 · Login — one centred card, local session (username + password). No SSO in
// this release (layout leaves room to add one "Sign in with your organisation"
// button later without redesign). No signup, no password reset.
// Two arrivals are distinguished: session-expired vs idle-timeout. Assumption
// (flagged in-screen): idle-timeout preserves the in-progress draft locally.
const { useState, useEffect } = React;

const LoginScreen = ({ onAuthed }) => {
  const [user, setUser] = useState("devon.ramirez");
  const [pw, setPw] = useState("••••••••");
  const [status, setStatus] = useState("idle");      // idle · submitting · rejected · unreachable
  const [arrival, setArrival] = useState("none");    // none · session · idle
  const [preview, setPreview] = useState("idle");

  useEffect(() => {
    if (preview === "session") { setArrival("session"); setStatus("idle"); }
    else if (preview === "idle_to") { setArrival("idle"); setStatus("idle"); }
    else { setArrival("none"); setStatus(preview); }
  }, [preview]);

  const submit = (e) => { e && e.preventDefault(); if (status === "submitting") return; setStatus("submitting"); setTimeout(() => onAuthed(), 750); };

  return (
    <div className="login-wrap">
      <svg className="login-mesh" viewBox="0 0 1200 800" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        {(() => {
          const nodes = [[140,150],[360,90],[560,180],[760,110],[980,200],[1080,380],[860,360],[640,380],[420,340],[220,320],[160,540],[380,560],[600,560],[820,560],[1010,600],[300,720],[560,730],[820,730]];
          return <g>
            {nodes.map((a,i)=>nodes.slice(i+1).map((b,j)=>{const d=Math.hypot(a[0]-b[0],a[1]-b[1]); if(d>230)return null; return <line key={i+"-"+j} x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke="#00C895" strokeOpacity="0.13" strokeWidth="1"/>;}))}
            {nodes.map((n,i)=><circle key={i} cx={n[0]} cy={n[1]} r={i%3===0?6:3.5} fill="#00C895" fillOpacity={i%3===0?0.85:0.45}/>)}
          </g>;
        })()}
      </svg>

      <div className="login-state">
        <span className="lsl">Preview</span>
        <select value={preview} onChange={e => setPreview(e.target.value)} aria-label="Preview login state">
          <option value="idle">Idle</option>
          <option value="submitting">Submitting</option>
          <option value="rejected">Rejected credentials</option>
          <option value="unreachable">Backend unreachable</option>
          <option value="session">Arrival · session expired</option>
          <option value="idle_to">Arrival · idle timeout</option>
        </select>
      </div>

      <form className="login-card" onSubmit={submit}>
        <img className="login-logo" src="../assets/solace-logo-full.svg" alt="Solace" />
        <div className="t-h5" style={{ marginTop: 14 }}>Sign in</div>
        <p className="login-sub">Solace Solution Manager</p>

        {arrival === "session" && (
          <Alert severity="warn" icon="lock" title="Your session ended" style={{ marginBottom: 18 }}>
            The server no longer recognised your session, so you were signed out. Sign in to continue.
          </Alert>
        )}
        {arrival === "idle" && (
          <Alert severity="info" icon="clock" title="Signed out after 30 minutes idle" style={{ marginBottom: 18 }}>
            For security, the console dropped your session after inactivity. <strong>Your in-progress configuration edits were saved on this device</strong> and will reload after you sign in.
          </Alert>
        )}
        {status === "unreachable" && (
          <Alert severity="err" icon="wifiOff" title="Can’t reach Solution Manager" style={{ marginBottom: 18 }}>
            The console couldn’t reach the server. Check your connection, then try again.
          </Alert>
        )}

        <div className="col" style={{ gap: 18 }}>
          <TextField label="Username" req value={user} onChange={e => setUser(e.target.value)} autoComplete="username"
            error={status === "rejected" ? " " : null} />
          <TextField label="Password" req reveal value={pw} onChange={e => setPw(e.target.value)} autoComplete="current-password"
            error={status === "rejected" ? "Username or password is incorrect." : null} />
          <Btn type="submit" size="lg" className="btn-block" disabled={status === "submitting"}>
            {status === "submitting" ? "Signing in…" : "Sign in"}
          </Btn>
          {/* Future: a single "Sign in with your organisation" button drops in here, below the divider, without redesign. SSO is deferred in v1. */}
        </div>

        <p className="t-cap muted" style={{ marginTop: 22, lineHeight: 1.6 }}>
          Accounts are created by your administrator. There’s no self-service sign-up or password reset in this release. Sessions end after 30 minutes of inactivity.
        </p>
      </form>
    </div>
  );
};
window.LoginScreen = LoginScreen;
