// MUI X Data Grid (Community) — sortable columns, checkbox selection, density,
// column visibility, quick filter, CSV export, expandable rows. No Pro/Premium
// features (no pinning, no row grouping, no built-in export beyond CSV).
const { useState, useMemo } = React;

function toCSV(cols, rows, getId) {
  const head = cols.map(c => `"${(c.header || c.field).replace(/"/g, '""')}"`).join(",");
  const body = rows.map(r => cols.map(c => {
    const v = c.csv ? c.csv(r) : (c.sortVal ? c.sortVal(r) : r[c.field]);
    return `"${String(v ?? "").replace(/"/g, '""')}"`;
  }).join(",")).join("\n");
  return head + "\n" + body;
}
function downloadCSV(name, text) {
  const blob = new Blob([text], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a"); a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const DataGrid = ({ columns, rows, getId, selection = [], onSelectionChange, renderExpand,
                    search, toolbarExtras, rightToolbar, exportName = "export.csv", empty, focusId }) => {
  const [sort, setSort] = useState({ field: null, dir: 1 });
  const [compact, setCompact] = useState(false);
  const [hidden, setHidden] = useState(() => new Set());
  const [expanded, setExpanded] = useState(() => new Set(focusId ? [focusId] : []));
  const [q, setQ] = useState("");

  const cols = columns.filter(c => !hidden.has(c.field));
  const filtered = useMemo(() => {
    let r = rows;
    if (q && search) { const t = q.toLowerCase(); r = r.filter(x => search(x).toLowerCase().includes(t)); }
    if (sort.field) {
      const c = columns.find(x => x.field === sort.field);
      const val = c && c.sortVal ? c.sortVal : (x) => x[sort.field];
      r = [...r].sort((a, b) => { const av = val(a), bv = val(b); return (av > bv ? 1 : av < bv ? -1 : 0) * sort.dir; });
    }
    return r;
  }, [rows, q, sort, columns, hidden]);

  const ids = filtered.map(getId);
  const allSel = ids.length > 0 && ids.every(i => selection.includes(i));
  const someSel = ids.some(i => selection.includes(i)) && !allSel;
  const toggleAll = () => onSelectionChange && onSelectionChange(allSel ? selection.filter(i => !ids.includes(i)) : [...new Set([...selection, ...ids])]);
  const toggleOne = (id) => onSelectionChange && onSelectionChange(selection.includes(id) ? selection.filter(i => i !== id) : [...selection, id]);
  const sortBy = (f) => setSort(s => s.field === f ? { field: f, dir: -s.dir } : { field: f, dir: 1 });
  const toggleExp = (id) => setExpanded(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  return (
    <div className="dg">
      <div className="dg-toolbar">
        {search && (
          <div className="tf dense" style={{ width: 260 }}>
            <input value={q} onChange={e => setQ(e.target.value)} placeholder=" " aria-label="Search rows" style={{ paddingLeft: 34 }} />
            <label className="tfl" style={{ left: 30 }}>Search</label>
            <span style={{ position: "absolute", left: 11, top: "50%", transform: "translateY(-50%)", color: "var(--secondary-text-wMain)" }}><Ic name="search" size={16} /></span>
          </div>
        )}
        {toolbarExtras}
        <div className="grow" />
        {rightToolbar}
        <Menu align="right" width={230} trigger={(o, t) => <button className="tbg" onClick={t} style={{ height: 36 }}><span style={{ padding: "0 12px", display: "flex", alignItems: "center", gap: 7, fontSize: 12.5, fontWeight: 600, color: "var(--secondary-text-wMain)" }}><Ic name="columns" size={15} /> Columns</span></button>}>
          {() => <>
            <div className="mi-cap">Show columns</div>
            {columns.filter(c => c.hideable !== false && c.field).map(c => (
              <div key={c.field} className="mi" onClick={() => setHidden(h => { const n = new Set(h); n.has(c.field) ? n.delete(c.field) : n.add(c.field); return n; })}>
                <Cbx on={!hidden.has(c.field)} onClick={() => {}} label={c.header} /> <span>{c.header}</span>
              </div>
            ))}
          </>}
        </Menu>
        <Tbg value={compact ? "c" : "s"} onChange={v => setCompact(v === "c")} options={[{ v: "s", label: "Standard" }, { v: "c", label: "Compact" }]} />
        <Btn variant="outlined" size="sm" icon="download" onClick={() => downloadCSV(exportName, toCSV(cols, filtered, getId))}>CSV</Btn>
      </div>

      {filtered.length === 0 ? (empty || <div style={{ padding: 40, textAlign: "center" }} className="muted">No rows match.</div>) : (
        <div className="dg-scroll">
          <table className={`dgt ${compact ? "compact" : ""}`}>
            <thead>
              <tr>
                {renderExpand && <th style={{ width: 40 }} aria-label="Expand" />}
                {onSelectionChange && <th className="col-check"><Cbx on={allSel} indeterminate={someSel} onClick={toggleAll} label="Select all rows" /></th>}
                {cols.map(c => (
                  <th key={c.field} className={c.sortable ? "sortable" : ""} style={{ width: c.width, minWidth: c.minWidth, textAlign: c.align }}
                      onClick={c.sortable ? () => sortBy(c.field) : undefined} aria-sort={sort.field === c.field ? (sort.dir === 1 ? "ascending" : "descending") : undefined}>
                    <span className="th-in">{c.header}{c.info && <InfoI tip={c.info} />}{c.sortable && sort.field === c.field && <Ic name={sort.dir === 1 ? "chevUp" : "chevDown"} size={13} />}</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map(r => {
                const id = getId(r); const isSel = selection.includes(id); const isExp = expanded.has(id);
                return (
                  <React.Fragment key={id}>
                    <tr className={`${isSel ? "sel" : ""} ${focusId === id ? "focus-row" : ""}`}>
                      {renderExpand && <td style={{ width: 40, paddingLeft: 12 }}><button className={`expander ${isExp ? "open" : ""}`} onClick={() => toggleExp(id)} aria-label={isExp ? "Collapse" : "Expand"} aria-expanded={isExp}><Ic name="chevRight" size={16} /></button></td>}
                      {onSelectionChange && <td className="col-check"><Cbx on={isSel} onClick={() => toggleOne(id)} label={`Select ${id}`} /></td>}
                      {cols.map(c => <td key={c.field} style={{ textAlign: c.align }}>{c.render ? c.render(r) : r[c.field]}</td>)}
                    </tr>
                    {isExp && renderExpand && (
                      <tr className="subrow"><td colSpan={cols.length + (onSelectionChange ? 1 : 0) + 1}>{renderExpand(r)}</td></tr>
                    )}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <div className="dg-foot">
        {selection.length > 0 && <span className="strong" style={{ color: "var(--primary)" }}>{selection.length} selected</span>}
        <span>{filtered.length} of {rows.length} {rows.length === 1 ? "row" : "rows"}</span>
      </div>
    </div>
  );
};

Object.assign(window, { DataGrid, downloadCSV, toCSV });
