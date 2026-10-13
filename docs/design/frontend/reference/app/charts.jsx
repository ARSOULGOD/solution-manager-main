// Charts for P7–P9. The "must not" rules are enforced here: a missing bucket
// produces NO tuple (line breaks, never zero-filled); low-coverage buckets render
// distinct; retention truncation shows a band + boundary, never a pretend range;
// the shared tooltip carries each series' STATE, not just its value; NA/empty
// shows "no samples", never a zero line. Colours are sequential, then dash.
const { useState: uSc, useRef: uRc, useEffect: uEc, useMemo: uMc } = React;

const HOUR = 3600e3, DAY = 86400e3;
const PALETTE = ["#4E79A7", "#59A14F", "#B07AA1", "#9C755F", "#EDC948", "#76B7B2", "#FF9DA7", "#8CD17D"];
function mulberry32(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
function fmtTime(ms, withDate) { const d = new Date(ms); const M = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getUTCMonth()]; const hh = String(d.getUTCHours()).padStart(2, "0"), mm = String(d.getUTCMinutes()).padStart(2, "0"); return withDate ? `${M} ${d.getUTCDate()} ${hh}:${mm}` : `${hh}:${mm}`; }
function fmtVal(v, unit) {
  if (v == null) return "—";
  if (unit === "ratio") return v.toFixed(3);
  if (unit === "ms") return v.toFixed(0) + "ms";
  const a = Math.abs(v);
  if (a >= 1e6) return (v / 1e6).toFixed(1) + "M";
  if (a >= 1e3) return (v / 1e3).toFixed(1) + "k";
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}

function useMeasure() {
  const ref = uRc(null); const [w, setW] = uSc(720);
  uEc(() => { if (!ref.current) return; const ro = new ResizeObserver(es => { for (const e of es) setW(e.contentRect.width); }); ro.observe(ref.current); setW(ref.current.clientWidth); return () => ro.disconnect(); }, []);
  return [ref, w];
}

// ---- metric catalog (P8 picker) — entries are EXACTLY the contract fields ----
const NOW = Date.parse("2026-08-14T10:00:00Z");
const METRIC_CATALOG = [
  { name: "cache.hit_rate", metricType: "gauge", unit: "ratio", layer: "package-baseline", state: "plottable", lastSeen: NOW - 5 * 60e3, labelKeys: ["region"], durability: "durable" },
  { name: "cache.evictions", metricType: "counter", unit: "count", layer: "package-baseline", state: "plottable", lastSeen: NOW - 5 * 60e3, labelKeys: ["region"], durability: "best-effort" },
  { name: "cache.hit_count", metricType: "counter", unit: "events", layer: "package-baseline", state: "plottable", lastSeen: NOW - 5 * 60e3, labelKeys: [], durability: "best-effort" },
  { name: "cache.miss_count", metricType: "counter", unit: "events", layer: "package-baseline", state: "plottable", lastSeen: NOW - 5 * 60e3, labelKeys: [], durability: "best-effort" },
  { name: "cache.size", metricType: "gauge", unit: "entries", layer: "package-baseline", state: "plottable", lastSeen: NOW - 5 * 60e3, labelKeys: [], durability: "durable" },
  { name: "cache.p99_get_latency", metricType: "gauge", unit: "ms", layer: "user-extension", state: "plottable", lastSeen: NOW - 8 * 60e3, labelKeys: [], durability: "best-effort" },
  { name: "cache.custom_lag", metricType: null, unit: null, layer: "user-extension", state: "needs-typing", lastSeen: NOW - 30 * 60e3, labelKeys: [] },
  { name: "cache.queue_depth", metricType: "gauge", unit: "items", layer: "user-extension", state: "type-suspect", lastSeen: NOW - 12 * 60e3, labelKeys: [], durability: "best-effort", suspectReason: "Observed values climb then drop to zero like a counter reset, but this is typed as a gauge — deltas may mislead." },
];
const RANGE_PRESETS = [
  { k: "15m", label: "15m", ms: 15 * 60e3 }, { k: "1h", label: "1h", ms: HOUR }, { k: "6h", label: "6h", ms: 6 * HOUR },
  { k: "24h", label: "24h", ms: DAY }, { k: "7d", label: "7d", ms: 7 * DAY }, { k: "30d", label: "30d", ms: 30 * DAY },
  { k: "90d", label: "90d", ms: 90 * DAY }, { k: "6mo", label: "6mo", ms: 182 * DAY },
];

// ---- synthetic query result honoring a demo "state" ----
function genResult(metric, insts, seriesBy, state, range) {
  const step = HOUR; const from = range.from, to = range.to;
  const cat = METRIC_CATALOG.find(m => m.name === metric) || METRIC_CATALOG[0];
  const isRate = cat.metricType === "counter";
  const units = [];
  insts.forEach(inst => {
    const reps = seriesBy === "replica" ? inst.replicas : [{ n: inst.name, h: inst.roll }];
    reps.forEach((rep, ri) => {
      const seed = [...(inst.id + rep.n)].reduce((a, c) => a + c.charCodeAt(0), 0);
      const rnd = mulberry32(seed);
      const inactive = rep.h === "inactive_replica";
      const base = cat.unit === "ratio" ? 0.6 + rnd() * 0.3 : cat.unit === "ms" ? 40 + rnd() * 60 : cat.unit === "entries" ? 40000 + rnd() * 40000 : 8 + rnd() * 20;
      const points = []; const gaps = [];
      let gapFrom = null, gapTo = null;
      if (state === "gaps") { gapFrom = from + (to - from) * 0.45; gapTo = from + (to - from) * 0.58; }
      const servedFrom = state === "retention" ? from + (to - from) * 0.35 : from;
      for (let t = from; t <= to; t += step) {
        if (t < servedFrom) continue;                                   // retention: no tuples before servedFrom
        if (gapFrom && t >= gapFrom && t <= gapTo) continue;            // restart gap: no tuples
        if (state === "nodata") continue;                               // no samples at all
        const expected = 60; let sample = 60;
        const lowSpanA = from + (to - from) * 0.68, lowSpanB = from + (to - from) * 0.78;
        if (state === "lowcov" && t >= lowSpanA && t <= lowSpanB) sample = 14 + Math.floor(rnd() * 10);
        let v;
        if (inactive) v = cat.unit === "ratio" ? 0 : 0;                 // standby: legitimately zero
        else { const wobble = Math.sin(t / (DAY / 2) + seed) * 0.5 + (rnd() - 0.5) * 0.4; v = Math.max(0, base * (1 + wobble * 0.35)); if (cat.unit === "ratio") v = Math.min(1, base + wobble * 0.12); }
        points.push([t, isRate && !inactive ? Math.max(0, v) : v, sample, expected]);
      }
      if (gapFrom) gaps.push({ from: gapFrom, to: gapTo, reason: "no-samples" });
      units.push({
        seriesRef: { instanceId: inst.id, placementId: rep.n }, label: seriesBy === "replica" ? `${inst.name}·${rep.n}` : inst.name,
        color: PALETTE[units.length % PALETTE.length], points, gaps,
        replicaComposition: { total: inst.replicas.length, inactive: inst.replicas.filter(r => r.h === "inactive_replica").length },
        inactive, state: state === "pending" ? "pending-config-change" : inactive ? "inactive-replica" : (cat.state === "type-suspect" ? "type-suspect" : cat.durability === "best-effort" ? "unverified-durability" : "ok"),
      });
    });
  });
  const annotations = state === "normal" || state === "gaps" ? [{ ts: from + (to - from) * 0.6, label: "replica added (+1) — summed series steps; not a workload change" }] : [];
  return {
    metric: { name: cat.name, metricType: cat.metricType, unit: cat.unit }, render: { transform: isRate ? "rate" : "none", rateSeconds: 60 },
    resolution: { requested: "auto", served: "hourly", bucketSeconds: 3600 },
    coverage: { requestedFrom: from, servedFrom: state === "retention" ? from + (to - from) * 0.35 : from, truncatedByRetention: state === "retention" },
    gapThresholdSeconds: 7200, series: units, annotations,
    partial: state === "partial" ? { degraded: true, unavailable: [{ instanceId: "cache-edge-01", label: "cache-edge-01", reason: "host unreachable · no telemetry for 8m" }] } : { degraded: false, unavailable: [] },
  };
}

// ---- LINE CHART ----
const TimeChart = ({ result, range, height = 260, thresholds = [], logScale = false, yLabel }) => {
  const [ref, w] = useMeasure();
  const [hover, setHover] = uSc(null);
  const series = result.series || [];
  const unit = result.metric.unit;
  const padL = 54, padR = 16, padT = 12, padB = 30;
  const iw = Math.max(120, w - padL - padR), ih = height - padT - padB;
  const active = series.filter(s => !s.inactive);
  const allPts = series.flatMap(s => s.points.map(p => p[1]));
  let ymin = 0, ymax = Math.max(1, ...allPts) * 1.14;
  if (logScale) ymin = Math.max(0.0001, Math.min(...allPts.filter(v => v > 0), 1));
  const xf = (t) => padL + ((t - range.from) / (range.to - range.from)) * iw;
  const yf = (v) => { if (logScale) { const lo = Math.log10(ymin), hi = Math.log10(ymax); return padT + ih - ((Math.log10(Math.max(v, ymin)) - lo) / (hi - lo)) * ih; } return padT + ih - ((v - ymin) / (ymax - ymin)) * ih; };
  const hasData = allPts.length > 0;

  // unified ts grid for tooltip
  const grid = uMc(() => { const s = new Set(); series.forEach(se => se.points.forEach(p => s.add(p[0]))); return [...s].sort((a, b) => a - b); }, [result]);
  const onMove = (e) => {
    if (!grid.length) return; const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left; const t = range.from + ((x - padL) / iw) * (range.to - range.from);
    let best = grid[0]; for (const g of grid) if (Math.abs(g - t) < Math.abs(best - t)) best = g;
    setHover({ ts: best, x: xf(best) });
  };

  const yticks = 4; const xticks = 6;
  const covFrom = result.coverage && result.coverage.servedFrom > range.from ? result.coverage.servedFrom : null;

  return (
    <div className="chart-wrap" ref={ref}>
      {!hasData ? (
        <div className="chart-empty" style={{ height }}>
          <Ic name="chart" size={26} style={{ opacity: .4 }} />
          <div className="strong">No samples in this range</div>
          <div className="t-cap muted">Served tier: {result.resolution.served} · bucket {result.resolution.bucketSeconds}s. Nearest data is outside the selected window.</div>
        </div>
      ) : (
        <svg className="chart-svg" viewBox={`0 0 ${w} ${height}`} height={height} onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
          {covFrom && <><rect className="retband" x={padL} y={padT} width={xf(covFrom) - padL} height={ih} /><line x1={xf(covFrom)} y1={padT} x2={xf(covFrom)} y2={padT + ih} stroke="var(--warning-wMain,#ff8e2b)" strokeDasharray="4 3" /></>}
          {Array.from({ length: yticks + 1 }, (_, i) => { const v = ymin + (ymax - ymin) * (i / yticks); const y = yf(v); return <g key={i}><line className="gridline" x1={padL} y1={y} x2={w - padR} y2={y} /><text x={padL - 8} y={y + 3} textAnchor="end" fontSize="10">{fmtVal(v, unit)}</text></g>; })}
          {Array.from({ length: xticks + 1 }, (_, i) => { const t = range.from + (range.to - range.from) * (i / xticks); const x = xf(t); return <text key={i} x={x} y={height - 10} textAnchor="middle" fontSize="10">{fmtTime(t, (range.to - range.from) > 2 * DAY)}</text>; })}
          {thresholds.map((th, i) => <g key={i}><line x1={padL} y1={yf(th.value)} x2={w - padR} y2={yf(th.value)} stroke="var(--warning-wMain,#ff8e2b)" strokeDasharray={th.style === "dashed" ? "6 4" : "0"} /><text x={w - padR} y={yf(th.value) - 4} textAnchor="end" fontSize="10" fill="var(--warning-w100,#a85a10)">{th.label}</text></g>)}
          {(result.annotations || []).map((a, i) => <g key={i}><line x1={xf(a.ts)} y1={padT} x2={xf(a.ts)} y2={padT + ih} stroke="var(--secondary-w40,#cfd3d9)" strokeDasharray="3 3" /><polygon points={`${xf(a.ts) - 4},${padT} ${xf(a.ts) + 4},${padT} ${xf(a.ts)},${padT + 6}`} fill="var(--secondary-w60,#8a94a0)" /></g>)}
          {series.map((s, si) => <SeriesPath key={si} s={s} xf={xf} yf={yf} gapThreshold={result.gapThresholdSeconds * 1000} />)}
          {hover && <line x1={hover.x} y1={padT} x2={hover.x} y2={padT + ih} stroke="var(--navy)" strokeOpacity=".3" />}
          {hover && series.map((s, si) => { const p = s.points.find(pp => pp[0] === hover.ts); if (!p) return null; return <circle key={si} cx={xf(hover.ts)} cy={yf(p[1])} r="3.5" fill={s.color} stroke="#fff" strokeWidth="1.5" />; })}
        </svg>
      )}
      {hover && hasData && <ChartTip result={result} hover={hover} chartW={w} />}
    </div>
  );
};

const SeriesPath = ({ s, xf, yf, gapThreshold }) => {
  const segs = []; let cur = [];
  for (let i = 0; i < s.points.length; i++) {
    const p = s.points[i];
    const inGap = (s.gaps || []).some(g => p[0] > g.from && p[0] < g.to);
    if (inGap) { if (cur.length) segs.push(cur); cur = []; continue; }
    if (cur.length) { const prev = cur[cur.length - 1]; if (p[0] - prev[0] > gapThreshold * 1.5) { segs.push(cur); cur = []; } }
    cur.push(p);
  }
  if (cur.length) segs.push(cur);
  const dash = s.inactive || s.state === "pending-config-change" ? "5 4" : "0";
  const op = s.inactive ? 0.55 : 1;
  return (
    <g style={{ opacity: op }}>
      {segs.map((seg, i) => <polyline key={i} fill="none" stroke={s.color} strokeWidth="1.8" strokeDasharray={dash} strokeLinejoin="round" points={seg.map(p => `${xf(p[0])},${yf(p[1])}`).join(" ")} />)}
      {s.points.filter(p => p[2] < p[3]).map((p, i) => <circle key={"lc" + i} cx={xf(p[0])} cy={yf(p[1])} r="2.6" fill="#fff" stroke={s.color} strokeWidth="1.4" />)}
    </g>
  );
};

const STATE_LABEL = { "ok": null, "gap": "gap", "inactive-replica": "inactive replica", "pending-config-change": "pending", "type-suspect": "type-suspect", "unverified-durability": "best-effort" };
const ChartTip = ({ result, hover, chartW }) => {
  const rows = result.series.map(s => { const p = s.points.find(pp => pp[0] === hover.ts); return { s, p }; }).sort((a, b) => (b.p ? b.p[1] : -1) - (a.p ? a.p[1] : -1));
  const left = Math.min(hover.x + 14, chartW - 230);
  return (
    <div className="ctip" style={{ left: Math.max(4, left), top: 8 }}>
      <div className="ctip-h">{fmtTime(hover.ts, true)} · served {result.resolution.served}</div>
      {rows.map(({ s, p }, i) => (
        <div className="ctip-row" key={i}>
          <span className="ctip-sw" style={{ background: s.color }} />
          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 120 }}>{s.label}</span>
          {STATE_LABEL[s.state] && <span className="ctip-st">{STATE_LABEL[s.state]}</span>}
          <span className="ctip-v">{p ? fmtVal(p[1], result.metric.unit) : "gap"}</span>
        </div>
      ))}
    </div>
  );
};

const Legend = ({ series, maxRows = 4 }) => (
  <div className="legend" style={{ maxHeight: maxRows * 24 }}>
    {series.map((s, i) => (
      <div className={`leg-item ${s.inactive ? "muted-leg" : ""}`} key={i}>
        <span className="leg-sw" style={{ background: s.color, borderBottom: s.inactive || s.state === "pending-config-change" ? "2px dashed " + s.color : "none" }} />
        <span className="grow" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{s.label}</span>
        {STATE_LABEL[s.state] && <Chip tone={s.state === "type-suspect" ? "warn" : "idle"} sm>{STATE_LABEL[s.state]}</Chip>}
      </div>
    ))}
  </div>
);

const GaugeMark = ({ value, unit, min = 0, max = 100, thresholds = [] }) => {
  const pct = Math.max(0, Math.min(1, (value - min) / (max - min)));
  const R = 62, C = Math.PI * R;
  const th = thresholds.find(t => value >= t.value);
  return (
    <div style={{ textAlign: "center", padding: "8px 0" }}>
      <svg width="160" height="96" viewBox="0 0 160 96">
        <path d="M16 88 A64 64 0 0 1 144 88" fill="none" stroke="var(--secondary-w20,#e7e9ec)" strokeWidth="12" strokeLinecap="round" />
        <path d="M16 88 A64 64 0 0 1 144 88" fill="none" stroke={th ? "var(--warning-wMain,#ff8e2b)" : "var(--primary)"} strokeWidth="12" strokeLinecap="round" strokeDasharray={`${pct * C} ${C}`} />
      </svg>
      <div style={{ marginTop: -30 }}><div className="t-h4">{fmtVal(value, unit)}</div><div className="t-cap muted">{unit}</div></div>
    </div>
  );
};
const SingleStatMark = ({ value, unit, delta }) => (
  <div style={{ textAlign: "center", padding: "18px 0" }}>
    <div style={{ fontSize: 40, fontWeight: 600, letterSpacing: "-.02em" }}>{fmtVal(value, unit)}</div>
    <div className="t-cap muted" style={{ marginTop: 4 }}>{unit}{delta != null && <span className={delta >= 0 ? "" : ""}> · {delta >= 0 ? "▲" : "▼"} {fmtVal(Math.abs(delta), unit)}</span>}</div>
  </div>
);

Object.assign(window, { TimeChart, GaugeMark, SingleStatMark, Legend, useMeasure, fmtVal, fmtTime, METRIC_CATALOG, RANGE_PRESETS, genResult, HOUR, DAY, NOW });
