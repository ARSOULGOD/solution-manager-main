/**
 * Formats a metric value from its package-declared canonical `unit` string
 * — never inferred by parsing the metric name (`dynamic-metrics` design
 * §12: "Units formatted from the canonical unit, never parsed from the
 * metric name", `DM-D13`). The unit vocabulary is open — packages declare
 * their own (`SM-D30`) — so only `ratio` gets special treatment; every
 * other declared unit is a formatted number plus the label, unchanged.
 *
 * **Task 128 adds `formatAxisValue`** for chart tick labels, which have
 * different constraints from a tooltip: a y axis repeats its label every
 * few pixels, so `formatUnitValue`'s output ("47,900 count") is far too
 * wide and repeats the unit on every tick. See that function's own doc for
 * where the two deliberately diverge.
 */

const numberFormat = new Intl.NumberFormat('en-US');
const percentFormat = new Intl.NumberFormat('en-US', {
  style: 'percent',
  maximumFractionDigits: 1,
});

export function formatUnitValue(value: number, unit: string | null): string {
  if (unit === null) {
    return numberFormat.format(value);
  }
  if (unit === 'ratio') {
    return percentFormat.format(value);
  }
  return `${numberFormat.format(value)} ${unit}`;
}

const axisNumberFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
const axisCompactFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 });

/** Thresholds descend, so the first match is the largest applicable magnitude. */
const AXIS_MAGNITUDES: readonly [threshold: number, divisor: number, suffix: string][] = [
  [1e9, 1e9, 'B'],
  [1e6, 1e6, 'M'],
  [1e3, 1e3, 'k'],
];

/**
 * A chart tick label — compact, and deliberately carrying NO unit suffix.
 * `reference/app/charts.jsx`'s `fmtVal` is the shape being matched: a
 * counter axis reads `47.9k`, not `47,900 count`, because the unit belongs
 * to the widget title and axis name, not to every tick.
 *
 * `ratio` reads as a plain decimal (`0.7`), matching the reference, which
 * renders ratios as decimals throughout. This is the one place the console
 * diverges from `formatUnitValue`'s percent (`70.2%`) — that is a
 * deliberate, confirmed choice for tick labels only. The tooltip, the data
 * table and the gauge/single-stat marks all still read `70.2%`; nothing
 * else in the app moved.
 */
export function formatAxisValue(value: number, unit: string | null): string {
  if (unit === 'ratio') {
    return axisNumberFormat.format(value);
  }
  const magnitude = Math.abs(value);
  for (const [threshold, divisor, suffix] of AXIS_MAGNITUDES) {
    if (magnitude >= threshold) {
      return `${axisCompactFormat.format(value / divisor)}${suffix}`;
    }
  }
  return axisNumberFormat.format(value);
}
