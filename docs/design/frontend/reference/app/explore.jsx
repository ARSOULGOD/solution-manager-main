// P8 · Explore — ad-hoc metric exploration. Controls gate in a fixed order:
// metric → instances (to a cap) → seriesBy → aggregation → groupBy. The cap counts
// RENDERED SERIES, so per-replica mode visibly reduces how many instances remain
// selectable; at the cap, unselected instances are disabled with two routes forward.
// 422s are never retried and render inline. The error body shape is INVENTED (flagged).
const { useState: uSe, useMemo: uMe } = React;
const SERIES_CAP = 20;

const CAT_STATE = {
  plottable: { tone: "ok", label: "plottable" },
  "needs-typing": { tone: "warn", label: "needs typing" },
  "type-suspect": { tone: "warn", label: "type-suspect" },
};

const TypeMetricModal = ({ metric, onClose, onType }) => {
  const [mt, setMt] = uSe(""); const [unit, setUnit] = uSe("");
  return (
    <Modal title={`Type ${metric.name}`} onClose={onClose}
      foot={<><Btn variant="text" onClick={onClose}>Cancel</Btn><Btn disabled={!mt} onClick={() => onType(mt, unit)}>Save type</Btn></>}>
      <Alert severity="info">This metric arrived unclassified. Inferring a type is forbidden — you must declare it. Until then it can’t be plotted.</Alert>
      <div className="mt16"><div className="t-over" style={{ marginBottom: 8 }}>Metric type</div>
        <div className="row gap8 wrap">{["counter", "gauge", "histogram"].map(t => <button key={t} className={`strat ${mt === t ? "on" : ""}`} style={{ padding: "10px 14px", flex: "0 0 auto" }} onClick={() => setMt(t)}><span className="radio" /><span className="strong">{t}</span></button>)}</div>
      </div>
      <div className="mt16"><TextField label="Unit" value={unit} onChange={e => setUnit(e.target.value)} help="Free text — there is no unit vocabulary anywhere in the contract, so this control has no list to offer." /></div>
      <div className="finding mt16"><Ic name="alert" size={17} style={{ flex: "none", color: "var(--warning-w100)" }} /><div><div className="strong t-sm">Finding — required contract addition</div><div className="t-cap">The catalog entry has no <span className="mono">firstSeen</span> and no suggested-type field. A “suggest counter from observed monotonicity” capability is described in prose with nothing to carry it — so no suggestion can be offered here.</div></div></div>
    </Modal>
  );
};

const ERR_TEXT = {
  SERIES_CAP_EXCEEDED: (m) => `Projected ${m.projected} series exceeds the cap of ${m.cap} (seriesBy: ${m.seriesBy}). Narrow the selection, or aggregate into fewer lines.`,
  AGGREGATION_NOT_APPLICABLE: () => "Aggregation across instances needs at least two instances to combine.",
  GROUP_BY_REQUIRES_AGGREGATION: () => "Group-by only applies once you aggregate across instances.",
  STAT_REQUIRES_AGGREGATION: () => "A single-stat or gauge shows one number, so it requires aggregation to a single value.",
  METRIC_UNTYPED: () => "This metric has no declared type and cannot be queried until it is typed.",
};

const ExploreScreen = ({ route, nav }) => {
  const { demo } = window.useDemo();
  const solId = route.parts[1], instId = route.parts[3];
  const insts = (window.CACHE_INSTANCES || []).filter(i => i.env === "PROD").slice(0, 8);
  const [metricName, setMetricName] = uSe("cache.hit_rate");
  const [sel, setSel] = uSe([insts[0].id]);
  const [seriesBy, setSeriesBy] = uSe("instance");
  const [aggInst, setAggInst] = uSe("none");
  const [groupBy, setGroupBy] = uSe([]);
  const [mark, setMark] = uSe("line");
  const [dataState, setDataState] = uSe("normal");
  const [rangeK, setRangeK] = uSe("7d");
  const [typing, setTyping] = uSe(null);
  const [typed, setTyped] = uSe({});

  const metric = uMe(() => { const m = METRIC_CATALOG.find(x => x.name === metricName); return typed[metricName] ? { ...m, ...typed[metricName], state: "plottable" } : m; }, [metricName, typed]);
  const rangePreset = RANGE_PRESETS.find(r => r.k === rangeK);
  const range = { from: NOW - rangePreset.ms, to: NOW };
  const aggregated = aggInst !== "none";
  const forceAgg = mark === "gauge" || mark === "single-stat";

  const perInst = (inst) => seriesBy === "replica" ? inst.replicas.length : 1;
  const selInsts = insts.filter(i => sel.includes(i.id));
  const projected = aggregated ? (groupBy.length ? Math.max(1, new Set(selInsts.map(i => i.org)).size) : 1) : selInsts.reduce((n, i) => n + perInst(i), 0);
  const atCap = !aggregated && projected >= SERIES_CAP;

  const errors = uMe(() => {
    const e = [];
    if (metric.state === "needs-typing") e.push({ code: "METRIC_UNTYPED" });
    if (forceAgg && !aggregated) e.push({ code: "STAT_REQUIRES_AGGREGATION" });
    if (groupBy.length && !aggregated) e.push({ code: "GROUP_BY_REQUIRES_AGGREGATION" });
    if (aggregated && selInsts.length < 2) e.push({ code: "AGGREGATION_NOT_APPLICABLE" });
    if (projected > SERIES_CAP) e.push({ code: "SERIES_CAP_EXCEEDED", meta: { cap: SERIES_CAP, projected, seriesBy } });
    return e;
  }, [metric, forceAgg, aggregated, groupBy, selInsts, projected, seriesBy]);
  const blocking = errors.length > 0;

  const toggleInst = (inst) => { const on = sel.includes(inst.id); if (on) setSel(sel.filter(x => x !== inst.id)); else { if (atCap) return; setSel([...sel, inst.id]); } };
  const changeMetric = (name) => { setMetricName(name); };  // single-select replaces

  const result = uMe(() => {
    if (blocking || selInsts.length === 0 || metric.state === "needs-typing") return null;
    const st = metric.state === "type-suspect" ? "normal" : dataState;
    let r = genResult(metric.name, aggregated ? selInsts : selInsts, aggregated ? "instance" : seriesBy, st, range);
    if (aggregated) { const merged = r.series[0]; r = { ...r, series: [{ ...merged, label: groupBy.length ? "by " + groupBy.join(",") : `aggregate · ${selInsts.length} instances`, state: merged.state }] }; }
    return r;
  }, [metric, selInsts, seriesBy, aggregated, groupBy, dataState, rangeK, blocking]);

  if (demo === "error") return <Wrap solId={solId} instId={instId} nav={nav}><div className="page"><Alert severity="err" title="Explore failed to load" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>The console reached the server but the metric catalog failed to load.</Alert></div></Wrap>;

  return (
    <Wrap solId={solId} instId={instId} nav={nav}>
      <div className="explore">
        <div className="exp-controls">
          <div className="row between" style={{ marginBottom: 14 }}><span className="t-h6">Explore</span><a href={`#/solutions/${solId}/instances/${instId}/monitoring`} className="t-cap">← Monitoring</a></div>

          <div className="ctl-step">
            <div className="cs-h"><span className="num">1</span> Metric <span className="muted" style={{ textTransform: "none", letterSpacing: 0, fontWeight: 400 }}>· single-select</span></div>
            <div className="catalog">
              {METRIC_CATALOG.map(m => { const eff = typed[m.name] ? { ...m, state: "plottable" } : m; const dis = eff.state === "needs-typing"; return (
                <div key={m.name} className={`cat-item ${metricName === m.name ? "on" : ""} ${dis ? "disabled" : ""}`} onClick={() => !dis && changeMetric(m.name)}>
                  <span className="cat-n grow">{m.name}</span>
                  <span className={`layer-tag ${m.layer === "package-baseline" ? "layer-pkg" : "layer-op"}`}>{m.layer === "package-baseline" ? "pkg" : "ext"}</span>
                  <Chip tone={CAT_STATE[eff.state].tone} sm>{CAT_STATE[eff.state].label}</Chip>
                  {dis && <button className="btn btn-text btn-sm" onClick={(e) => { e.stopPropagation(); setTyping(m); }}>Type…</button>}
                </div>
              ); })}
            </div>
            <div className="t-cap muted" style={{ marginTop: 6 }}>{metric.unit ? <>type <span className="mono">{metric.metricType}</span> · unit <span className="mono">{metric.unit}</span></> : "untyped"}</div>
            {metric.state === "type-suspect" && <Alert severity="warn" icon="alert" style={{ marginTop: 8 }} title="Type-suspect — plotted, never auto-corrected">{metric.suspectReason}</Alert>}
          </div>

          <div className={`ctl-step ${metric.state === "needs-typing" ? "locked" : ""}`}>
            <div className="cs-h"><span className="num">2</span> Instances <span className="muted" style={{ textTransform: "none", letterSpacing: 0, fontWeight: 400 }}>· to a cap</span></div>
            <div className="row between" style={{ marginBottom: 8 }}>
              <span className="t-cap"><strong>{projected}</strong> / {SERIES_CAP} series projected</span>
              <span className="t-cap muted">{seriesBy === "replica" ? "per replica" : "per instance"}</span>
            </div>
            <div className="capmeter" style={{ marginBottom: 10 }}><i className={projected > SERIES_CAP ? "over" : ""} style={{ width: Math.min(100, projected / SERIES_CAP * 100) + "%" }} /></div>
            <div className="inst-list">
              {insts.map(i => { const on = sel.includes(i.id); const wouldAdd = perInst(i); const dis = !on && atCap; return (
                <div key={i.id} className={`inst-row ${dis ? "disabled" : ""}`} onClick={() => toggleInst(i)}>
                  <Cbx on={on} onClick={() => {}} label={i.name} />
                  <span className="grow"><span className="t-sm strong">{i.name}</span> <span className="t-cap muted">{i.org}</span></span>
                  <span className="t-cap muted mono">{wouldAdd} sr</span>
                </div>
              ); })}
            </div>
            {atCap && <div className="finding" style={{ marginTop: 10, background: "var(--info-w10)", borderColor: "var(--info-w30,#bfe0f2)" }}><Ic name="info" size={16} style={{ flex: "none", color: "var(--info-w100)" }} /><div><div className="strong t-sm">Series cap reached — two ways forward</div><div className="row gap8 mt8"><Btn size="sm" variant="outlined" onClick={() => alert("Label filter (prototype)")}>Narrow by label</Btn><Btn size="sm" variant="outlined" onClick={() => setAggInst("avg")}>Aggregate into one line</Btn></div></div></div>}
          </div>

          <div className={`ctl-step ${metric.state === "needs-typing" ? "locked" : ""}`}>
            <div className="cs-h"><span className="num">3</span> Series by</div>
            <Tbg value={seriesBy} onChange={setSeriesBy} options={[{ v: "instance", label: "Instance" }, { v: "replica", label: "Replica" }]} />
          </div>

          <div className={`ctl-step ${metric.state === "needs-typing" ? "locked" : ""}`}>
            <div className="cs-h"><span className="num">4</span> Aggregation</div>
            <select className="bare" style={{ width: "100%" }} value={aggInst} onChange={e => setAggInst(e.target.value)} disabled={forceAgg}>
              <option value="none">Across instances: none (one line each)</option>
              <option value="sum">Across instances: sum</option>
              <option value="avg">Across instances: average</option>
            </select>
            {forceAgg && <div className="t-cap muted" style={{ marginTop: 6 }}>A {mark} shows one number — aggregation is forced to a single value.</div>}
          </div>

          <div className={`ctl-step ${aggregated ? "" : "locked"}`}>
            <div className="cs-h"><span className="num">5</span> Group by <span className="muted" style={{ textTransform: "none", letterSpacing: 0, fontWeight: 400 }}>· needs aggregation</span></div>
            <div className="row gap8 wrap">{["region", "org"].map(k => <button key={k} className={`chip ${groupBy.includes(k) ? "chip-info" : "chip-out"}`} style={{ cursor: "pointer" }} onClick={() => setGroupBy(g => g.includes(k) ? g.filter(x => x !== k) : [...g, k])}>{k}</button>)}</div>
          </div>
        </div>

        <div className="exp-main">
          <div className="row between wrap gap12" style={{ marginBottom: 14 }}>
            <div className="row gap8">
              <Tbg value={mark} onChange={setMark} options={[{ v: "line", label: "Line", icon: "trend" }, { v: "gauge", label: "Gauge", icon: "gauge" }, { v: "single-stat", label: "Stat", icon: "dot" }]} />
              <select className="bare" value={rangeK} onChange={e => setRangeK(e.target.value)} aria-label="Range">{RANGE_PRESETS.map(r => <option key={r.k} value={r.k}>Last {r.label}</option>)}</select>
              <span className="t-cap muted">absolute · resolution auto</span>
            </div>
            <div className="row gap8">
              <select className="bare" value={dataState} onChange={e => setDataState(e.target.value)} aria-label="Data state (prototype)">
                {[["normal", "Normal"], ["gaps", "Gaps (restart)"], ["lowcov", "Low coverage"], ["retention", "Retention truncation"], ["nodata", "No data in range"], ["partial", "Partial fleet"], ["inactive", "Inactive replica"], ["pending", "Pending config"]].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
              <Btn size="sm" icon="plus" disabled={blocking || !result} onClick={() => pushOutcome(instId, { sev: "ok", title: "Saved as widget", detail: "Added to the operator dashboard layer." })}>Save as widget</Btn>
            </div>
          </div>

          {errors.map((e, i) => (
            <Alert key={i} severity="err" icon="xCircle" style={{ marginBottom: 10 }} title={<span className="row gap8">{e.code} <span className="t-cap" style={{ fontWeight: 400 }}>· 422 · not retried</span></span>}>
              {ERR_TEXT[e.code](e.meta || {})}
              <div className="t-cap" style={{ marginTop: 6, opacity: .8 }}>⚠ The 422 body shape is unspecified — the field carrying this stated reason is invented.</div>
            </Alert>
          ))}

          {result && result.partial && result.partial.degraded && (
            <Alert severity="warn" icon="wifiOff" style={{ marginBottom: 10 }} title="Partial data — some instances did not resolve">
              {result.partial.unavailable.map(u => <div key={u.instanceId}><span className="mono">{u.label}</span> — {u.reason}</div>)}
              <div className="t-cap" style={{ marginTop: 4, opacity: .8 }}>⚠ Invented: <span className="mono">partial.unavailable[]</span> item shape and its reason vocabulary aren’t in the contract.</div>
            </Alert>
          )}
          {result && result.coverage.truncatedByRetention && <Alert severity="info" icon="clock" style={{ marginBottom: 10 }}>Data before {fmtTime(result.coverage.servedFrom, true)} is outside the retention window; the chart starts at the earliest retained point.</Alert>}

          <Paper pad>
            {selInsts.length === 0 ? <div className="chart-empty" style={{ height: 260 }}><Ic name="server" size={24} style={{ opacity: .4 }} /><div className="strong">Select at least one instance</div><div className="t-cap muted">Nothing is plotted until you choose what to compare.</div></div>
              : metric.state === "needs-typing" ? <div className="chart-empty" style={{ height: 260 }}><Ic name="help" size={24} style={{ opacity: .4 }} /><div className="strong">This metric needs a type</div><Btn size="sm" variant="outlined" onClick={() => setTyping(METRIC_CATALOG.find(m => m.name === metricName))}>Type this metric</Btn></div>
                : blocking ? <div className="chart-empty" style={{ height: 260 }}><Ic name="ban" size={24} style={{ opacity: .4 }} /><div className="strong">Resolve the request above to plot</div></div>
                  : mark === "gauge" ? <GaugeMark value={result.series[0].points.slice(-1)[0][1]} unit={metric.unit} min={0} max={metric.unit === "ratio" ? 1 : Math.max(...result.series[0].points.map(p => p[1])) * 1.2} />
                    : mark === "single-stat" ? <SingleStatMark value={result.series[0].points.slice(-1)[0][1]} unit={metric.unit} />
                      : <><TimeChart result={result} range={range} height={300} thresholds={metric.name === "cache.evictions" ? [{ value: Math.max(...result.series.flatMap(s => s.points.map(p => p[1]))) * 0.8, label: "warn", style: "dashed" }] : []} /><div className="mt12"><Legend series={result.series} /></div></>}
          </Paper>
          {result && mark === "line" && <div className="t-cap muted mt12 row gap8" style={{ flexWrap: "wrap" }}><Ic name="info" size={13} /> Hollow points = low coverage (fewer samples than expected). Line breaks are gaps — never zero-filled. Dashed = inactive/pending. Counter rates can’t use a log axis (zero is legitimate when idle).</div>}
        </div>
      </div>
      {typing && <TypeMetricModal metric={typing} onClose={() => setTyping(null)} onType={(mt, unit) => { setTyped(t => ({ ...t, [typing.name]: { metricType: mt, unit } })); setMetricName(typing.name); setTyping(null); pushOutcome(instId, { sev: "ok", title: `Typed ${typing.name}`, detail: `Declared as ${mt}${unit ? " · " + unit : ""}.` }); }} />}
    </Wrap>
  );
};

const Wrap = ({ solId, instId, nav, children }) => (
  <div className="inst-shell">
    <InstanceHeader solId={solId} instId={instId} active="monitoring" nav={nav} roll="GREEN" availability="declared" />
    {children}
  </div>
);
window.ExploreScreen = ExploreScreen;
