# FE-149 · Unify the package dialect, and repair the branch baseline — Report

First task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §14).
Planned as one change; executed as three, because the branch was not in the state the plan assumed.

> **Numbering.** `FE-149` is a task number. The `#149` in `task-list.md` line 121 is a *GitHub issue* (it tracks
> `FE-119`) — issue numbers run 30 ahead of task numbers in that table. There is no collision, but the two will
> sit side by side, so this note is here.

---

## 1 · What was wrong

**The planned defect — the bundled path skipped the normalizer.** The frontend engine reads a hybrid shape:
`label` / `helperText` / `advanced` flat on the field, `section` / `order` / `readOnly` nested under `ui`
(`frontend/contracts/view-model/project.ts`). `toFrontendPackage()` converts a canonical package into exactly
that, and the dev-upload path calls it (`app/dev/parsePackage.ts`). The bundled path
(`shared/testdoubles/data/packages.ts`) cast the JSON straight through, typed `as SolutionPackage`. A cast satisfies
the compiler and silently drops every authored label — no error, just raw keys on screen.

**Two defects the plan did not anticipate**, both found only by running the gates:

1. **`dev/frontend` was already red** — 4 failing tests and 1 lint error at `HEAD`, before any change here.
2. **A regression in `cache`, introduced by `b13c241`.** That commit ("flatten db-designer/cache fields") also
   rewrote `cache/package.json`'s version scheme. Compared against the last good state (`c2538ba`), it made
   exactly four collateral changes and nothing else:

   | | `c2538ba` (good) | `b13c241` (regressed) |
   |---|---|---|
   | root `packageVersion` | `[6, 7]` | `[2.3, 2.4]` |
   | per-field `version` scope | on all 27 fields | on none |
   | `max-size-bytes` (v6-only) | present, default `67108864` | **deleted** |
   | `max-weight.default` (v7-only) | `536870912` | **deleted** |

   That is what the four red tests (`mergeReset` / `useDesignerDraft`, `FE-121`/`FE-D37` version filtering)
   were reporting. It was found by `git log --follow` plus a per-commit compare — not by guessing which version
   scheme was "right". The history decided it.

The one lint error (`sidebarSolutions.test.ts`, `color: '#000000'`) came from an unrelated earlier commit.

## 2 · What changed

| Change | Files |
|---|---|
| Bundled packages go through `toFrontendPackage()`, never a raw cast | `shared/testdoubles/data/packages.ts` |
| `cache` and `db-designer` converted to the canonical dialect (`label`/`helperText`/`advanced` → `ui`) | `contracts/packages/{cache,db-designer}/package.json` |
| `file-mesh` committed in the canonical dialect (see §5 — this was an uncommitted edit already in the working tree) | `contracts/packages/file-mesh/package.json` |
| `cache`'s four `b13c241` regressions restored | `contracts/packages/cache/package.json` |
| `cache` root `id` removed — not in the schema, read by nothing | same |
| `db-designer` `role: "primary"` → `"none"`; `columns[].required`/`default` dropped (not canonical) | `contracts/packages/db-designer/package.json` |
| Lint fix: the test's unused colour comes from the fixture layer, which the rule exempts | `features/inventory/solutions/sidebarSolutions.test.ts` |
| New test pinning the dialect boundary | `shared/testdoubles/data/packages.test.ts` |

## 3 · What deliberately did not change

- **`contracts/solution-package.schema.json`** — untouched, as plan 129's first global constraint requires.
- **Both `project()` implementations** — untouched. They still diverge (open item 36 stays open); this task
  makes the *data* agree with the schema and converts at one boundary. It does not reconcile the two engines.
- **The runtime-version strings** (`2.3`, `2.4`, …) in a dozen test files are `declaredVersion` /
  `observedVersion` — a different concept from the package's schema `packageVersion`. Confirmed by the full
  suite staying green; not touched.
- **Two test files still raw-cast `cache/package.json`** (`mergeReset.test.ts`, `useDesignerDraft.test.ts`).
  They exercise defaults and version filtering, not labels, and pass. They should eventually route through
  `packageForSolution`, since a raw cast of canonical data is the same trap this task closes. Left alone rather
  than widening scope.

## 4 · Evidence

| Gate | Before | After |
|---|---|---|
| `vitest run` (full) | 338 files pass, **2 fail** · 2410 pass, **4 fail** | **340 / 340** files · **2414 / 2414** tests |
| `npm run typecheck` | clean | clean |
| `npm run lint` | **1 error** | clean, `--max-warnings=0` |
| `validate-package.py` over `frontend/contracts/packages/*` | cache **29** violations, db-designer **13** | **9 / 9 PASS** |
| Stash-and-rerun at `HEAD` | the same 4 tests fail | — proves they pre-date this task |

The "before" red state was established by stashing this work and re-running the four failing tests at `HEAD`
(identical failures), so none of it is attributable to these changes.

## 5 · Deviations from the plan, stated plainly

1. **The plan said `file-mesh` was being rendered with raw-key labels "right now". That was true of the working
   tree, not of `HEAD`.** At `HEAD` file-mesh is authored flat and renders correctly. The canonical copy was an
   uncommitted edit already present when the session started (it also swaps the fake signature for
   `example-not-signed`). I mistook the working tree for the committed state. It is committed here because it is
   *required*: `normalizeField` reads only `ui.label`, so a flat file routed through the normalizer would lose
   every label. Spec §14.1 and plan Task 149 are corrected.
2. **Plan Step 4 predicted "all nine PASS".** It was wrong — I attributed all 29 cache violations to the dialect
   when three were something else (the dead `id` key, and the `[2.3, 2.4]` scheme, which the schema's
   integer-only `packageVersion` rejects). Corrected in the plan.
3. **Scope grew** from "normalize the boundary" to "and repair the baseline". The plan assumed a green branch
   and never checked.
4. **This reverses an interim rule.** [L19](../../../design/common/lessons.md) and open item 36 said: until the
   two `project()` implementations are reconciled, author `cache`/`file-mesh`/`db-designer` **flat**. This task
   does the opposite — canonical data, normalized at the boundary. That is a legitimate way to discharge L19's
   "until", but it is a change to a recorded position, and it was made without first reading L19. Both L19 and
   item 36 now carry an update pointing here; the rule was not deleted. The remaining question — which
   `project()` is authoritative — is still the architect group's.

## 6 · Open

- **Which `project()` is the contract** — open item 36, unchanged.
- **A CI gate over `frontend/contracts/packages/`.** Nothing in `.github/workflows/frontend.yml` runs
  `validate-package.py` over these files, which is how two of them drifted and how `b13c241` shed 4 fields'
  worth of content unnoticed. Proposed as a new open item in `FE-155`.
- **`b13c241` landed with 4 red tests on the branch.** Whether CI caught it and was overridden, or did not run,
  is not something the repository can answer.
