# FE-099 · End-to-end journeys — Report

## What I implemented

The seven Playwright journeys `FE-099` names, over the fixture backend (task spec:
`docs/plans/frontend/implementation.md` §FE-099; refs `FE-D12`, design §19; deps all features).
Journey 1 already existed from `FE-125` and was **missing two of its own stated must-nots**; journeys
2–7 are new. Execution-time decisions are recorded as **`FE-D40`** (`I3` — new ID, nothing edited).

| File | Summary |
|---|---|
| `frontend/e2e/journeys.spec.ts` | **New.** Journeys 2–7, each asserting a must-not. |
| `frontend/e2e/designer.spec.ts` | Journey 1 gains its two missing must-nots (generation on the *request*; inactive branch absent from the payload). |
| `frontend/src/shared/testdoubles/e2eOverrides.ts` | **New.** DEV-only `?e2e_grants=` / `?e2e_idle_seconds=` URL overrides. |
| `frontend/src/shared/testdoubles/handlers/dp.ts` | Save now models real optimistic concurrency: a per-instance generation, and a `409` when the submitted one is stale. |
| `frontend/src/shared/testdoubles/handlers/console.ts` | An instance whose health says `not_responding` now defaults to the `gaps` series scenario. |
| `frontend/src/shared/auth/devMockAdapter.ts` | Honours the grant profile. |
| `frontend/src/app/routes/AuthenticatedLayout.tsx` | Honours the idle override, guarded at the **call site**. |
| `frontend/src/test/setup.ts` | Resets the generation map between tests. |
| `frontend/playwright.config.ts` | Registers `journeys.spec.ts`; sets a considered `expect` timeout. |
| `docs/design/frontend/decisions.md` | `FE-D40` recorded, indexed, changelogged. |

## The seven journeys

| # | Journey | The must-not it asserts |
|---|---|---|
| 1 | Walking skeleton | The generation is on the **request**, not merely in the response; the **inactive** `payload-key-json-pointer` branch is absent from the payload; apply renders `APPLY_INDETERMINATE`, never a success verdict |
| 2 | Concurrent save | All three unspecified resolutions are **disabled** — no working path overwrites; the operator's own edit survives the conflict |
| 3 | Incident path | **No rendered point is a zero** — the gap is missing hours, not hours at a filler value |
| 4 | Explore → save widget | The dialog must not imply scopes v1 cannot save to |
| 5 | Deep link | `?focus=` is **not** a selection — the receiver must not act on an instance the sender never selected |
| 6 | Permission | A v1 control must not disappear for a verbless principal; an unavailable control is **disabled with a reason**, never silently missing |
| 7 | Idle lock | An idle lock must not render as *"Your session ended"* — that sends the operator down the wrong support path |

## How the journeys reach states no fixture row can express

Three mechanisms, in a deliberate order of preference:

1. **Real interleaving** — journey 2's `409` is a genuine second actor. The save handler now holds a
   per-instance generation and rejects a stale one. A conflict is not a property of a record; it is
   what happens when two actors interleave, so it is reproduced rather than flagged. This replaced
   my first instinct — a dedicated "conflicting instance" fixture — which would have modelled the
   wrong thing.
2. **Fixture coherence** — journey 3's gap comes from the fixture agreeing with itself. An instance
   whose health rollup says `not_responding` now defaults to the `gaps` scenario. **It previously
   reported a dead endpoint alongside a perfectly healthy line**, which makes §A.13.4's own stated
   workflow — *"red badge → open the graphs → no data confirms it is genuinely down"* — prove the
   opposite of what it says. Fixing that is a fixture-correctness win independent of this task.
3. **A DEV-only URL override**, last resort — `?e2e_grants=`, `?e2e_idle_seconds=`. A URL states the
   journey's premise in a line a human can paste into a browser; an `addInitScript` global hides it
   inside the test file. Driving `config.json` from Playwright does not work at all — MSW's service
   worker sits between `page.route` and that fetch.

### The production-safety catch

`readE2EOverrides` guards itself with `import.meta.env.DEV`. **That is not sufficient, and I only
found it because I checked the built bundle.** A build with the guard in the callee alone still
shipped the reader, dead-code-eliminated down to:

```js
function pe(e=window.location.search){return{grants:void 0,idleSeconds:void 0}}
```

Correct at runtime, present in the bundle. Every call site now carries its own literal
`if (import.meta.env.DEV)`, which Vite folds to `false` and drops the import with it — the rule
`devPackageHandlers.ts` already documents. Verified against a real `npm run build`:

| Marker | In `dist` |
|---|---|
| `e2e_grants`, `e2e_idle_seconds`, `e2eOverrides`, `readE2EOverrides`, `idleSeconds`, `devMockAdapter` | **all absent** |

## Two journeys are narrower than the plan's line for them

Both because the console deliberately lacks the behaviour, not because the test was hard.

**Journey 6 — "with the verb absent, the control is omitted" is not testable, by design.**
`overlayRbac` (task 059, `FE-D22`) is a documented no-op, and `permissions.ts`'s **C12** forbids
gating a v1 feature on a non-`grantable` verb: only `target.use` and `instance.bindTarget` are
grantable, so such a gate would deny *every* principal. Verified empirically — a session with no
permissions and `superAdmin: false` changes nothing anywhere, including `/admin`. Rather than invent
a gate to have something to assert, the journey **pins C12 itself**: a verbless session must still
reach every v1 control. That is the regression this codebase can actually suffer, and it fails the
day someone gates a v1 screen on a verb nobody can hold. The "disabled with a reason" half is
asserted against Explore's gated steps, which are real.

**Journey 4 stops at the save.** Widget persistence does not exist — §16 **Q15** — and the save
dialog says so itself: *"the route this saves to, and which dashboard it targets, are invented."*
Verified: after a successful save the dashboard still shows only its `package`-layer widgets, by
reload **and** by in-app navigation. The journey asserts the path that exists (Explore → a save that
states its layer → a confirmation) and pins the layer vocabulary on the dashboard that renders it
today.

⚠️ **A UI over-claim found and left standing.** The confirmation reads *"«title» was added to this
instance's dashboard"* when nothing was added. Changing that copy belongs with Q15, not with a test
task — but it should not have to be discovered twice.

## Verification

| Check | Result |
|---|---|
| `npm run e2e` | **149 passed**, exit 0 (all seven journeys green) |
| `npm test` | **2145 passed**, exit 0 |
| `npm run test:a11y -- --all` | 351 passed |
| `npm run lint` · `typecheck` | clean |
| `npm run build` · `verify:bundle` | clean; dev seams absent from `dist` |
| `npm run verify:palette` · `verify:reflow` | 169 passed · 137 passed |
| `check:forbidden-deps` · `lint:fixtures:assert` | clean |

**One assertion caught itself being wrong.** Journey 1's "inactive branch absent" check first read
the payload's *top-level* keys — but `fields` is keyed by component instance id, with field keys
nested one level in, so the exclusion passed against **any** payload. The paired positive assertion
is what exposed it. Both now read `component.field`, and the positive anchor is the branch
discriminator (`key-source: TOPIC`) rather than the active branch's own field, which is an empty
optional `renderedFields()` legitimately omits.

## Deviations and fixes made along the way

- **`playwright.config.ts` now sets `expect: { timeout: 15_000 }`.** Playwright's 5 s default is a
  browser default, not a considered value for a project that drives the **dev server**: a first
  visit to a heavy route waits on Vite compiling that route's chunk. Several journeys failed on cold
  compiles before this.
- **Journey 7 warms its route before timing it.** With a 2 s idle timer, a cold compile under a
  loaded worker pool logged the session out before the page rendered — the test was racing the
  wrong thing.
- **Journey 2 allows exactly one console error.** The browser logs the deliberate `409` as a
  resource error. `watchConsole` takes a narrow per-journey allowlist rather than dropping the check,
  so a React warning or unhandled rejection still fails the journey.

## Follow-ups (not done here)

- **§16 Q15** — widget persistence, and the confirmation copy that currently over-claims.
- **The axe saturation flake.** One a11y file times out intermittently when the full suite saturates
  the worker pool — a *different* file each run, which is the signature `vitest.config.ts` already
  documents for `testTimeout`. It did not reproduce on a clean re-run (2145/2145). Raising the
  `a11y` project's 20 s timeout is the obvious fix but belongs to `FE-098`, not here.
