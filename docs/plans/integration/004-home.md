# Home — Integration Plan 004

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the Home screen's counters and attention feed show real data from the production gateway instead of fixtures, closing out plan 002's Task 2 and decisions D-1/D-2.

**Supersedes:** plan 002 Task 2 and D-1/D-2 (written when these operations were unserved; they are now served — re-verify the decisions below before assuming they're still open).

## Operations

| Operation | Route | Served | Frontend repository |
|---|---|---|---|
| `getHomeSummary` | `GET /cp/home/summary` | ✅ | `frontend/src/features/home/homeRepository.ts` |
| `getHomeAttention` | `GET /cp/home/attention` | ✅ | same |

Both routes are already called by `homeRepository.ts` through the shared `cpClient` (confirmed present pre-integration; this is not new wiring). What's unverified is whether the response adapter matches the real wire shape and whether the two open decisions still need a ruling now that the backend exists to check against.

## Task 0: Re-check the decisions against the real response

- [ ] **Step 1:** Call `GET /cp/home/attention` against a running production gateway (or read the backend's response-schema test/fixture for `HomeAttention`) and check whether `instanceName`/`solutionId` were added to the item (D-1 option A) or the frontend still has to resolve them via `getInstance` (option B).
- [ ] **Step 2:** Same for `GET /cp/home/summary` — check whether `configStagedNotApplied` has a source now, or still renders as `null`/"—" (D-2).
- [ ] **Step 3:** Record the actual current shape in this plan (replace this step with the finding) before writing the adapter — do not guess from the 2026-10-01 write-up.

## Task 1: Response-shape adapter

- [ ] Write a failing test asserting `homeRepository.getSummary()`/`getAttention()` map the real `HomeSummary { solutions: [{solutionId, name, instanceCount, byHealth, stateVersion}] }` / `HomeAttention { items: [...] }` envelopes (not the fixture-era card shape) into the view model the `Home` screen renders.
- [ ] Implement the adapter. Derive `solutionsManaged = solutions.length`, `instancesDeclared = Σ instanceCount`, `needAttentionNow = Σ byHealth` over the err-tone states (`RED`, `unreachable` — `STATE-VOCABULARY.md` §2); do not invent fields not present on the wire.
- [ ] No top-level `stateVersion` requirement for `HomeSummary`/`HomeAttention` (OR-55 exemption) — don't add a check that fails on its absence.
- [ ] Remove the `@shared/testdoubles` import from `homeRepository.ts` if one remains as a type-only leftover; confirm with `verify:production`'s `no-fixture-dataset` assertion.

## Task 2: Component tests

- [ ] `HomePage` / counter / attention-feed component tests against the real shape (MSW handlers updated to answer the contract envelope, not the old fixture one).

## Task 3: Browser verification

- [ ] Playwright: login → Home renders counters and attention feed from the real backend → clicking an attention row navigates to the right instance (requires D-1's resolution to be wired, whichever option was taken).
- [ ] Record the result in `reports/004-task-03-report.md`.

## Out of scope

- Admin/connections screens (no contract operation — `README.md` D-4).
- Anything in Instances/Designer/Governance (plans 007/008/010) — Home only resolves attention-feed *links* into those screens, it doesn't implement them.

## Global constraints (same as every plan in this folder)

`I5` org scope never client-supplied · `I6` no secret values logged/stored · bind parameters only · no CORS/no second feature flag · real adapter selected by the existing `VITE_USE_REAL_AUTH`-style flag · no silent fixture fallback on a served operation's error response.
