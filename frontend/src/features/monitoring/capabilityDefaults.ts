/**
 * Fail-closed capabilities (task 068, `DM-D10`, `dynamic-metrics` §17: "Capabilities
 * fetch fails → fail closed — conservative defaults (series cap 10, presets to 24h,
 * no forced-Raw), banner stating the UI is running on defaults"). The one place a
 * preset, cap or tier literal is allowed to live — every other module reads
 * `capabilities` through `useCapabilities`/`MetricsProvider`, never a hardcoded value.
 *
 * Fields §17 does not name (`maxPointsPerSeries`, `marks`, `aggregations`,
 * `features.streamingExport`/`pngExport`) are conservative choices consistent with
 * it, not spec text: `minBucketSeconds` is picked so the single 24h tier never
 * exceeds `maxPointsPerSeries`; chart types and aggregations stay available since
 * §17 does not restrict them; both export paths are withheld because an
 * unreachable capabilities endpoint means the backend's support for either is
 * itself unverified.
 */
import type { Capabilities } from './metricsRepository';

export const FAIL_CLOSED_CAPABILITIES: Capabilities = {
  queryVersion: 1,
  // Presets to 24h (§17) — the sole tier tops out at one day.
  tiers: [{ name: 'raw', retention: 'P1D', minBucketSeconds: 300 }],
  maxRange: 'P1D',
  // Series cap 10 (§17).
  maxSeriesPerWidget: 10,
  // 24h / 300s = 288 points — comfortably under the cap.
  maxPointsPerSeries: 1000,
  marks: ['line', 'gauge', 'single-stat'],
  aggregations: ['sum', 'avg', 'min', 'max'],
  features: {
    // No forced-Raw (§17).
    forceRaw: false,
    streamingExport: false,
    pngExport: false,
  },
  // `unknown` (never a fabricated `live`) — this is the fail-closed
  // default used when `getCapabilities` itself could not be read, so no
  // collector health was ever measured either (`console.yaml`'s own
  // `collector.state` doc: "shown as 'not measured yet', never healthy").
  collector: { state: 'unknown', unfrozenClosedPartitions: 0, reasons: [] },
};
