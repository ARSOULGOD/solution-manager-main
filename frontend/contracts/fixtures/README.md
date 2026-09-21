# Shared fixture suite

Two suites, each pinning a pure engine that both a TypeScript and a future Java implementation must pass:

| Suite | Engine | Divergence guard |
|---|---|---|
| `view-model/*.json` | `project()` (`contracts/view-model/project.ts`, task 046) | `UI-D15` (`docs/design/dynamic-ui/design.md` §6.5) |
| `metrics/*.json` | `projectSeries()` (`contracts/metrics/projectSeries.ts`, task 071) | `DM-D20` (`docs/design/dynamic-metrics/design.md` §9.4) |

## `view-model` fixture file shape

`(package, componentSelection, draftValues, observedState, packageVersion?) → expected viewModel` cases. One JSON
file per case, under `view-model/*.json`:

```json
{
  "name": "human-readable case name — matches the file name without .json",
  "package": { "...": "a SolutionPackage (contracts/view-model/types.ts)" },
  "componentSelection": [{ "type": "...", "instanceId": "..." }],
  "draftValues": { "<instanceId>": { "<fieldKey>": "..." } },
  "observedState": { "health": "healthy", "versionState": "ok", "configState": "in_sync" },
  "packageVersion": 1,
  "expected": { "...": "a ViewModel" }
}
```

`view-model/run.test.ts` reads every `*.json` file in this directory, calls `project(fixture.package,
fixture.componentSelection, fixture.draftValues, fixture.observedState, fixture.packageVersion)`, and asserts
the result deep-equals `fixture.expected`.

`package.packageVersion` is an **array** — every version the one package file serves (`PackageVersionId[]`,
FE-118). The top-level `packageVersion` is the single version to project **at**, and is **optional**: omitted
means no version filtering at all, so a fixture that says nothing about versions projects exactly as it did
before the dimension existed. Only the three `version-scoped-*` fixtures set it.

## `metrics` fixture file shape

`(queryResponse, widgetConfig, theme) → expected ChartModel` cases. One JSON file per case, under `metrics/*.json`:

```json
{
  "name": "human-readable case name — matches the file name without .json",
  "queryResponse": { "...": "a QueryResponse (contracts/metrics/types.ts) — the dynamic-metrics §5.6 wire shape" },
  "widgetConfig": { "...": "a ChartWidgetConfig (contracts/metrics/types.ts) — often {}" },
  "theme": { "categoricalColors": ["#4E79A7", "..."] },
  "expected": { "...": "a ChartModel" }
}
```

`metrics/run.test.ts` reads every `*.json` file in this directory, calls `projectSeries(fixture.queryResponse,
fixture.widgetConfig, fixture.theme)`, and asserts the result deep-equals `fixture.expected`.

## Pinning conventions

These exist so **the comparison itself never becomes the divergence** (`UI-D15`'s own warning, mirrored for
metrics by `DM-D20`) once a Java runner reads the same files:

- **Key ordering.** Object keys are written in the order the corresponding TypeScript interface declares them —
  `../view-model/types.ts` for `view-model/*.json`, `../metrics/types.ts` for `metrics/*.json`. That file is the
  canonical order, not this one. This is a *readability* convention, not a correctness requirement: the runner
  compares parsed structures (`expect(actual).toEqual(expected)`, key-order-insensitive), never raw JSON
  strings, so a Java `Map`'s different iteration order cannot fail the comparison.
- **Number formatting.** Plain JSON numbers — no trailing `.0`, no scientific notation, no numeric strings
  where the type is numeric (`"order": 1`, not `"order": 1.0` or `"order": "1"`). This is what actually needs
  pinning: `JSON.parse` and a default Jackson `ObjectMapper` both read `1` as an integral number without
  extra configuration, but a Java-side `1.0` would parse to a `Double` that fails a naive
  `Objects.equals` against JavaScript's single numeric type.
- **Absent vs. empty, for `version`.** A component's or field's `version` array is *optional*, and its absence
  means "every version the package supports" — not "no version". An empty array (`"version": []`) belongs to no
  version and never renders; `src/app/dev/parsePackage.ts` rejects it at load time so it cannot happen by
  accident. Never write `"version": []` in a fixture to mean "unversioned"; omit the key.
- **`null` vs. absent.** A field's `value` is a `ViewFieldValue` (task 048): `{ kind: 'value', value, source }`
  when there is one, `{ kind: 'unset' }` when there is none, and `{ kind: 'secretUnset' }` for every
  `secret: true` field (obligation 7, `I6` — `project()` never yet produces `secretRef`, see
  `docs/plans/frontend/reports/task-048-report.md`). The `kind` discriminant is what keeps "no value" and "the
  key is missing from a stale fixture" unconfusable in a diff — never rely on a bare `null`.
- **Absent, for `metrics/*.json`'s `gaps`, `points` and `truncatedBand`.** A bucket with no samples is a missing
  tuple in `points`, never `null` or `0` (`UI-D8`, `DM-D16`) — a series with none at all still gets `"points": []`,
  not an omitted key, since the series itself must survive for the legend (§12). `gaps: []` means no gap, not an
  omitted key either. `ChartCoverage.truncatedBand` is the one field in this suite that follows the `view-model`
  convention above: present only when `truncatedByRetention` is `true`, omitted otherwise — never `null`.
  `ChartSeries.replicaCountChanges` (task 073) follows the same "present only when non-empty" rule as
  `truncatedBand`, not the "always `[]`" rule `gaps` and `points` follow — most series never step, and an
  always-present empty array on every one of the seventeen pre-existing fixtures would be pure noise.

## Adding a fixture

Name the file after the obligation or scenario it proves (see `docs/plans/frontend/reports/task-046-report.md`
for `view-model`'s numbered obligations and `docs/plans/frontend/reports/task-071-report.md` for `metrics`'s). A
fixture proves exactly one thing; resist folding two obligations into one file even when the same input would
technically support both — a failing assertion should point at a single sentence in the design doc, not require
re-deriving which of two rules broke.
