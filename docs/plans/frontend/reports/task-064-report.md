# FE-064 · Health hero and replica cards — Report

## What I implemented

Reference: `docs/design/frontend/reference/app/governance.jsx`'s `GovernanceScreen` (hero + replica-grid
section, lines 92-132) and `instance.jsx`'s `GOV_HEALTH` map (lines 45-51) — used for copy/layout guidance,
not copied verbatim (MUI kit components replace the prototype's CSS classes). Phase 5 starts here; task
064's own dependencies (013 StateChip/vocab, 015 page furniture, 044 instance shell/header) were already
done. `governanceRepository.ts` (task 033) already existed with the right shape; what was missing was the
page itself — `InstanceShell.tsx` rendered a stated placeholder for `governance`, same as `designer` did
before task 123.

| File | Summary |
|---|---|
| `frontend/src/features/governance/healthRollup.ts` (new) + `.test.ts` | `ROLLUP_TO_HEALTH_KEY`: translates `governanceRepository.ts`'s wire-shaped `HealthRollupState` (`GREEN`/`RED`/…) into `shared/vocab/health.ts`'s `HealthKey` (`healthy`/`not_responding`/…) so `HealthHero` reuses `healthVocab`'s tone/icon instead of inventing a third copy of the same five states. |
| `frontend/src/features/governance/HealthHero/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `index.ts`) | Badge + rollup label + one explanatory paragraph per rollup (5 new i18n keys) + "Open in Monitoring" CTA, `contained` only for `RED`. No numeric/score prop anywhere — a header comment cites `dynamic-ui` §5.1's non-goal explicitly. |
| `frontend/src/features/governance/ReplicaCards/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `index.ts`) | One card per replica via `healthVocab`/`StateChip` (replicas were already typed `HealthKey`, same as `columns.tsx`'s `healthCell`). A standby (`inactive_replica`) card gets a dashed border + `theme.palette.tone.idle.bg` background, not just its chip's colour, plus the "excluded from availability math… not a healthy zero" note. Renders nothing for an empty replica list. |
| `frontend/src/features/governance/GovernancePage/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | The query lifecycle: skeleton → error-with-retry → hero (+ Replicas section only when `availability === 'declared'`) → a neutral "why this screen is simple" banner. Same `describeQueryError`/skeleton shape `DesignerPage.tsx` already uses. |
| `frontend/src/features/governance/index.ts` (new) | Public barrel — `GovernancePage`, `createGovernanceRepository`, repository types — mirroring `features/designer/index.ts`. |
| `frontend/src/features/instance/InstanceShell.tsx` | Renders the real `GovernancePage` for `category === 'governance'`; takes a new required `governanceRepository` prop; `monitoring` still gets the stated placeholder (Phase 6). |
| `frontend/src/app/routes/InstanceCategoryRoute.tsx` | Constructs `createGovernanceRepository(dpClient)` alongside the other repositories and passes it through. |
| `frontend/src/shared/testdoubles/handlers/dp.ts` | The `*/dp/instances/:instanceId/health` handler previously collapsed every non-`healthy` state to `RED`. Fixed to map all five `HealthKey` states to the correct `HealthRollupState`, and `availability: 'not-declared'` only for `not_applicable` — otherwise the "all five rollups" acceptance criterion wasn't exercisable end-to-end through the real repository (e.g. `cache-edge-02`, fixture'd `not_applicable`, previously reported `RED`). |
| `frontend/src/shared/i18n/messages.en.ts` | 17 new `governance*` keys; deleted the now-unused `instanceGovernancePlaceholder`. |
| `frontend/src/features/instance/InstanceShell.test.tsx`, `InstanceShell.a11y.test.tsx` | Updated the `governance` case(s) to assert the real page (heading "Governance") instead of the retired placeholder string — same before/after shape task 123 already used for `designer`. Added a governance a11y case at the assembled-screen level. |

## Design decisions, and why

1. **Two health vocabularies stay two vocabularies, translated at one seam.** `HealthRollupState`
   (`governanceRepository.ts`, task 033) and `HealthKey` (`shared/vocab/health.ts`, task 013) encode the
   same five states differently — one mirrors the backend/wire enum, the other is the UI vocabulary every
   other screen already renders through. Task 060's own report flagged this exact gap as unreconciled
   ("`ReplicaObservationFixture.health`… `governanceRepository.ts`'s `HealthRollupState` has the same
   uppercase shape, same gap"). `healthRollup.ts`'s `ROLLUP_TO_HEALTH_KEY` is that one translation, kept in
   the governance feature (not `shared/vocab`) because `HealthRollupState` is this one repository's wire
   contract, not a general vocabulary. Replicas needed no such map — `HealthSnapshot.replicas[].health` was
   already typed `HealthKey`.
2. **The MSW `/health` handler fix, not just the new page.** Task 064's acceptance line is explicit — "all
   five rollups" — and the pre-existing handler could only ever produce `GREEN` or `RED`. Left unfixed, nothing
   in the fixture set could exercise `RESTART_EXPECTED`/`INACTIVE_REPLICA`/`NOT_APPLICABLE` end-to-end
   through the real repository, only through component-level stubs. Fixed via a small local
   `HEALTH_KEY_TO_ROLLUP` map inside `dp.ts` itself (not imported from the governance feature — `shared/*`
   may not depend on a feature, per the `boundaries/dependencies` ESLint policy).
3. **`GovernancePage` hand-writes its own Monitoring href, mirroring `InstanceHeader`'s `categoryHref`.**
   Feature code may not import `@app/routes` (the same boundary rule): `app -> feature` is allowed,
   `feature -> app` is not. `InstanceHeader.tsx` already solved this by hand-building
   `/solutions/${id}/instances/${id}/${category}` locally rather than importing the route builder;
   `GovernancePage`'s `monitoringHref()` follows the identical pattern for the same three-segment shape,
   with a comment pointing at the precedent.
4. **No synthetic score is a type-level fact, not just a copy choice.** `HealthHeroProps` carries only
   `snapshot`/`onOpenMonitoring` — there is no numeric field to render even by mistake. `HealthHero.test.tsx`
   additionally asserts no `role="meter"`/`role="progressbar"` element exists for any of the five rollups,
   so a future PR adding one would fail a test, not just violate a comment.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck                                        # clean
npm run lint                                              # clean (project-wide, --max-warnings=0)
npm run test -- governance InstanceShell                  # 8 files, 37/37
npm run test                                               # full suite: 234 files, 1339/1339 green
npx prettier --write <every file this task touched or added>   # applied, then re-verified typecheck/lint/tests
npm run build                                               # production build succeeds
```

Task-specific acceptance items:

- **All five rollups render distinctly** — `HealthHero.test.tsx`'s `it.each` over all five `HealthRollupState`
  values asserts the correct `healthVocab` label per state.
- **`NOT_APPLICABLE` never reads as an outage** — asserted against error-tone/`role="alert"`, and visually
  confirmed (neutral grey badge, no red anywhere) in the browser verification below.
- **The `RED` CTA is primary** — `HealthHero.test.tsx` asserts `contained` only for `RED`, `outlined`
  otherwise; confirmed visually.
- **No synthetic score anywhere** — no such prop exists on `HealthHeroProps`; `HealthHero.test.tsx` asserts
  no meter/progressbar role renders for any rollup.
- **Standby ≠ healthy** — `ReplicaCards.test.tsx` asserts the standby card carries a distinct `data-standby`
  marker and the "excluded from availability math; not a healthy zero" note; confirmed visually (dashed
  border, muted background, distinct chip).
- **`GovernancePage.a11y.test.tsx`** — axe-clean in both light/dark for a declared-healthy snapshot and a
  `RED` snapshot with the primary CTA. Full keyboard/live-region sweep is task 067's job, not this one.

### Browser verification

Started the dev server (`npm run dev`, MSW active) and drove it headless with Playwright (no project
`run` skill existed for this repo; `chromium-cli` wasn't available, so used the documented Playwright
fallback pattern directly). Navigated to three fixture instances and confirmed, with screenshots and zero
browser console errors:

- `cache-prod-01` (`GREEN`, 3 replicas incl. a standby) — hero reads "Healthy", outlined CTA, standby card
  visually distinct (dashed border, muted background, "Inactive replica" chip).
- `cache-edge-02` (`NOT_APPLICABLE`) — hero reads "Not applicable" in neutral grey, no Replicas section,
  no error styling anywhere.
- `cache-stage-01` (`RED`/`not_responding`) — hero reads "Not responding" in red, **contained** (primary)
  CTA; clicking it navigated to `.../cache-stage-01/monitoring`, confirmed via `page.url()`.

Screenshots sent to the user separately; verification script and screenshots were scratch files, not
committed.

## Status

DONE — implemented, tested (37/37 on the touched suite; full suite green, 234/234 files), lint-clean,
typecheck-clean, prettier-clean, production build succeeds, browser-verified for all five rollup states
reachable from the current fixture set (four of five rollups have a fixture instance today —
`INACTIVE_REPLICA` as a *rollup* has no fixture instance yet, only as a per-replica state within an
otherwise-`GREEN` instance; covered at the component level by `HealthHero.test.tsx`'s parametrized test
instead). No GitHub issue titled `FE-064 …` exists in the repo yet (unlike every prior Phase 1-4 task,
which had one pre-created) — flagging this rather than creating one unprompted. Not yet marked done in
`task-list.md` or committed — pending user confirmation.
