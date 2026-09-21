/**
 * Pure `DM-D10` rule for `TimeRangePicker` (task 081, Dec `DM-D10` `B/D15`
 * 🔒, `dynamic-metrics` §5.4). The candidate preset set —
 * `15m · 1h · 6h · 24h · 7d · 30d · 90d · 6mo` — is fixed by the design
 * text; which of them are OFFERED is derived from `capabilities.tiers`,
 * never hardcoded. `'custom'` is not tier-gated — an operator can always
 * request an absolute window; the server truncates or 422s beyond what a
 * tier can serve (task 082's territory, not this one's).
 *
 * `permittedPresets` takes the LONGEST retention any tier advertises (a
 * `null` retention — e.g. the fixture's `daily` tier — means unlimited) and
 * keeps every preset at or under it. Against `FAIL_CLOSED_CAPABILITIES`
 * (`capabilityDefaults.ts`, tiers: `[{ retention: 'P1D' }]`) this yields
 * exactly `15m/1h/6h/24h` — matching §17's own words, "presets to 24h",
 * without a second hardcoded cutoff anywhere in this file.
 */
import type { CapabilityTier } from '../metricsRepository';

export type TimeRangePresetKey = '15m' | '1h' | '6h' | '24h' | '7d' | '30d' | '90d' | '6mo';

export type TimeRangeValue =
  | { kind: 'preset'; preset: TimeRangePresetKey }
  | { kind: 'custom'; from: string; to: string };

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

// 6mo approximated as 182 days — same figure the visual reference's own
// `RANGE_PRESETS` uses (`docs/design/frontend/reference/app/charts.jsx`).
const PRESET_DURATIONS_MS: Readonly<Record<TimeRangePresetKey, number>> = {
  '15m': 15 * MINUTE_MS,
  '1h': HOUR_MS,
  '6h': 6 * HOUR_MS,
  '24h': DAY_MS,
  '7d': 7 * DAY_MS,
  '30d': 30 * DAY_MS,
  '90d': 90 * DAY_MS,
  '6mo': 182 * DAY_MS,
};

const PRESET_ORDER: readonly TimeRangePresetKey[] = [
  '15m',
  '1h',
  '6h',
  '24h',
  '7d',
  '30d',
  '90d',
  '6mo',
];

export function presetDurationMs(preset: TimeRangePresetKey): number {
  return PRESET_DURATIONS_MS[preset];
}

/**
 * Approximate — Y=365d, M=30d — adequate for a UI-level "is this preset
 * within the advertised retention" filter, not for calendar-accurate math.
 * Only `Y`/`M`/`D` appear in any tier `retention` this app has seen
 * (`P6M`, `P2Y`, `P1D` — the fixture and `capabilityDefaults.ts` alike).
 */
function parseIsoDurationMs(duration: string): number {
  const match = /^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?$/.exec(duration);
  if (match === null) {
    return 0;
  }
  const [, years, months, days] = match;
  return (
    Number(years ?? 0) * 365 * DAY_MS +
    Number(months ?? 0) * 30 * DAY_MS +
    Number(days ?? 0) * DAY_MS
  );
}

const PRESET_KEY_SET: ReadonlySet<string> = new Set(PRESET_ORDER);

/** Type guard for a value read off the URL (task 083) — untrusted, unlike an in-app `TimeRangePresetKey`. */
export function isTimeRangePresetKey(value: string): value is TimeRangePresetKey {
  return PRESET_KEY_SET.has(value);
}

export function permittedPresets(tiers: readonly CapabilityTier[]): readonly TimeRangePresetKey[] {
  const maxServableMs = tiers.reduce((max, tier) => {
    if (tier.retention === null) {
      return Infinity;
    }
    return Math.max(max, parseIsoDurationMs(tier.retention));
  }, 0);

  return PRESET_ORDER.filter((preset) => PRESET_DURATIONS_MS[preset] <= maxServableMs);
}

/** Resolves a picker value to the absolute UTC window a query would use. `now` is injectable for tests. */
export function resolveTimeRange(value: TimeRangeValue, now: Date = new Date()): { from: string; to: string } {
  if (value.kind === 'custom') {
    return { from: value.from, to: value.to };
  }
  const from = new Date(now.getTime() - presetDurationMs(value.preset));
  return { from: from.toISOString(), to: now.toISOString() };
}
