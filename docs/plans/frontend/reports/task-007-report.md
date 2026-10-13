# FE-007 · Message catalogue and formatting — Report

## What I implemented

The message-catalogue and formatting infrastructure Phase 1+ components will consume — no
literal user-facing strings, no sentence built by concatenating fragments, UTC always converted
through one place, and a metric's declared `unit` string formatted without ever being guessed
from the metric name.

### Files created

- `frontend/src/shared/i18n/messages.en.ts` — the English-only v1 catalogue. `PluralMessage`
  (`{ one, other }`) and plain `string` entries, typed as `Record<string, MessageValue>` via
  `satisfies` so `MessageKey` is a literal union. Seeded with `retry`, `errorGeneric`, and three
  real plural strings the prototype hand-rolls per-call-site (`docs/design/frontend/reference/app/
  designer.jsx`): `replicaCount` (the restart-confirmation banner's `N replica(s)`),
  `advancedFieldCount` (`N advanced field(s)`), and `fieldMatchCount` (`N field(s) match "…"`,
  interpolating `query` alongside `count`). Not an exhaustive catalogue — no component consumes
  it yet (`Deps: 001` only) — but real strings traced to real screens rather than invented ones.
- `frontend/src/shared/i18n/t.ts` — `t(key, params?)`. Plural keys are typed to *require* a
  numeric `count` param at the call site (`Messages[K] extends string ? [params?] :
  [params: {count:number}&Params]`); `Intl.PluralRules('en-US')` picks `one`/`other`, and
  `{token}` placeholders are filled by simple string replace. A plural lookup called without a
  numeric `count` throws at runtime too (belt-and-braces under the type-level guard).
- `frontend/src/shared/i18n/index.ts` — barrel, same pattern as `shared/theme/index.ts`.
- `frontend/src/shared/format/time.ts` — UTC is canonical on the wire (`B/D15`🔒); this is the
  only place display localisation happens. `displayTimeZone` resolves the browser's zone once
  (`Intl.DateTimeFormat().resolvedOptions().timeZone`) — no display-timezone toggle exists in v1,
  per the task's own note. `formatDateTime`/`formatTime` default to it but accept an explicit
  `timeZone` (this is what makes the UTC→local conversion testable without depending on the host
  machine's zone). `timeZoneLabel` returns the short abbreviation (`EDT`, `UTC`, …) via
  `formatToParts`. `formatAxisTimestamp` composes the two — the function a chart axis calls so the
  zone is never omitted (`dynamic-metrics` design §18: "an unlabelled time axis is how an operator
  misreads an incident window").
- `frontend/src/shared/format/units.ts` — `formatUnitValue(value, unit)` formats strictly from the
  declared `unit` string (`dynamic-metrics` design §12, under `DM-D13`: "never parsed from the
  metric name"). The vocabulary is open — packages declare their own (`SM-D30` REQUIRED) — so only
  `ratio` gets special treatment (percentage); every other unit (`ms`, `count`, `entries`, `items`,
  …) is a grouped number plus the label, unchanged. A `null` unit (the `needs-typing` state) is a
  plain grouped number.
- `frontend/src/shared/format/index.ts` — barrel, same pattern.

### A citation worth flagging, not resolving

The task's own **Refs** line cites `DM-D13` for the "never parsed from the metric name" rule.
`DM-D13` is actually *"Apache ECharts is the charting library"* (`docs/design/dynamic-metrics/
decisions.md` §`DM-D13`) — the units rule sits in prose under that decision's heading (design.md
§12) but isn't itself `DM-D13`'s content, and `DM-D13` is `PROPOSED`, not ratified (`I4`). The
rule itself is real and correctly quoted; only the decision-ID attribution in the task text is
loose. Not mine to fix in-place (`I3`) — flagging it here rather than silently writing a different
citation into source comments than the one the task gave me.

## Testing

- `t.test.ts` — plain-string passthrough; singular (`count: 1`) vs plural (`count: 0`, `count: 3`)
  selection; `{token}` interpolation alongside `count`; throws when a plural key is called without
  a numeric `count`.
- `time.test.ts` — `formatDateTime`/`formatTime` convert a fixed UTC instant
  (`2026-08-20T14:30:00Z`) to `America/New_York` wall-clock time (verified against `node -e` Intl
  output before writing the assertion, not assumed); `timeZoneLabel` returns `EDT` for that zone/
  date and `UTC` for `UTC`; `formatAxisTimestamp` carries both.
- `units.test.ts` — `ratio` → `85.5%`; `ms`/`count`/`entries` → grouped number + label unchanged
  (including `1,234` grouping); `null` unit → plain grouped number.

```
$ npm run lint && npm run typecheck && npm test -- --run
```
All green. 116 tests passing across 18 files (10 new in this task: 3 spec files × the counts
above). `npm run format` flags only four pre-existing files under `shared/theme/` that this task
did not touch — the new files are Prettier-clean.

## Status

DONE

## Commit

`62981d1..8cf5121` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `007`. Issue
[#27](https://github.com/parikshit-coe/solution-manager/issues/27).
