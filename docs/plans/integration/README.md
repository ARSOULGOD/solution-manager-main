# Frontend ↔ backend integration plans

One folder for everything that connects the React frontend to the real backend (`backend/launcher` → `console-gateway`). **Own numbering (001, 002, …)** — deliberately not the frontend's `FE-NNN` series, whose task and report numbers collided with plan numbers 132/133.

Normative contract: [`contracts/openapi/console.yaml`](../../../contracts/openapi/console.yaml) (**65 operations** as of `getSolutionPackage`, 2026-10-09; all served by the production gateway — re-verified 2026-10-09, see the regeneration command below). Decisions: `docs/design/DECISIONS.md` (owner rulings `OR-*`). Spec for the first slice: [`docs/design/frontend/backend-integration-auth-hierarchy.md`](../../design/frontend/backend-integration-auth-hierarchy.md).

## Plans

| # | Plan | Status |
|---|---|---|
| 001 | [Auth + hierarchy](001-auth-hierarchy.md) (formerly "plan 132") | ✅ **closed 2026-10-01** — all 10 tasks done, browser-verified; 6 follow-ups listed in the plan's review section |
| 002 | [Remaining backend integration](002-remaining-backend.md) | ⛔ **superseded 2026-10-08** by 004–012 (drafted when only 7/32 ops were served) |
| 003 | [Served-API follow-ups](003-served-api-followups.md) | ✅ **closed 2026-10-02** — banner bug fixed, runbook added; 2 follow-ups blocked on backend/owner |
| 004 | [Home](004-home.md) | ✅ **closed 2026-10-08** — all tasks done, browser-verified (Playwright) |
| 005 | [Solutions & Targets catalog](005-solutions-and-targets-catalog.md) | ✅ **closed 2026-10-08** — all tasks done; Playwright not run (no backend in that sandboxed run — component coverage only) |
| 006 | [Target registration & connections](006-target-registration-and-connections.md) | 📝 draft — awaiting approval (new UI, needs a design pass first) |
| 007 | [Instances & placements](007-instances-and-placements.md) | ⚠️ **partially closed 2026-10-09** — Task 1 (declareInstance drift) and Task 2's detail-header half done, full suite green except the known-deferred `devPackageFlow.test.ts` gap; list grid's 7 orphaned columns and Task 3 (placement add/retire) still open |
| 008 | [Designer & config](008-designer-and-config.md) | ⚠️ **partially closed 2026-10-09** — Tasks 3 and 4 (history/secrets, accept/restore-runtime, with `AcceptRuntimeDialog` redesigned against the real contract) done; Tasks 1/2 (`getDesigner`/`stageConfig`) still fixture-era — each needs its own reverse/forward-mapping design work beyond D-4's resolution (see the plan's own notes) |
| 009 | [Apply, rollout, schedules, fleet](009-apply-rollout-schedules-fleet.md) | 📝 draft — awaiting approval |
| 010 | [Governance](010-governance.md) | ✅ **closed 2026-10-08** — all tasks done, 79/79 tests, browser-verified where applicable |
| 011 | [Monitoring](011-monitoring.md) | ✅ **closed 2026-10-08** — all tasks done, 497/497 tests, browser-verified (Playwright) |
| 012 | [Hierarchy writes & entitlements](012-hierarchy-writes-and-entitlements.md) | 📝 draft — awaiting approval (new UI, needs a design pass first) |
| 013 | [Connections screen (real backend) & Targets screen removal](013-connections-screen-and-targets-removal.md) | 📝 draft — design approved 2026-10-09 (`BE-D66`/`OR-130`), awaiting execution |
| 014 | [Connection-backed instance placement](014-connection-backed-instance-placement.md) | 📝 approved — supersedes the target-binding portion of 007 for new declarations; steps 1, 2 and 4 implemented (uncommitted, see [report](reports/task-014-report.md)); step 3 and the gateway→DP projection seam pending |
| 016 | [Deployment targets and runtime adapters](016-deployment-targets-runtime-adapters.md) | ⚠️ implemented — linked target metadata, Docker/systemd adapter routing, declaration UI, target administration UI, migrations and focused tests are in place; live remote E2E remains environment-dependent |
| 017 | [Designer YAML editor](017-designer-yaml-editor.md) | ✅ implemented — package-driven Form/YAML views share one draft; syntax/type validation and secret omission are covered by focused tests |
| — | [API inventory](API-INVENTORY.md) | 📖 reference — every backend API (contracted, planned, 501, demo-only) |
| — | [API → screen map](API-SCREEN-MAP.md) | 📖 reference — operation → parameters → feature → screen |

New plan = next number, one file, linked from this table. Reports go in [`reports/`](reports/) as `NNN-task-MM-report.md`.

## Operation status

*Source of truth for "what is integrated" is now split by plan (one table per screen, in each plan file) because one 64-row table stopped being readable. This section states the one fact that's true for all of them, and regeneration commands to re-check it.*

**All 65 `console.yaml` operations are served by the production gateway.** Re-check with:

```bash
grep -rhoE 'ConsoleOperation\([^)]*' backend --include='*.java' | grep -v /test/ | sed -E 's/.*"([a-zA-Z]+)".*/\1/' | sort -u > /tmp/served.txt
grep -oE 'operationId: [a-zA-Z]+' contracts/openapi/console.yaml | awk '{print $2}' | sort -u > /tmp/contracted.txt
comm -23 /tmp/contracted.txt /tmp/served.txt   # empty = everything contracted is served
```

**"Served" is not "integrated."** Each plan 004–012 still has to verify, per operation: the frontend repository calls the right route with the right body, the response-shape adapter matches the contract schema (not the old fixture shape), error codes route to the right UI state, and a real browser run proves it end to end. Plan 002's Task 1 audit found real drift between the frontend's pre-existing fixture-era calls and the contract (wrong URL for `declareInstance`, wrong body shapes throughout) — expect more of that, not less, now that every operation answers for real instead of 404.

## Not in the contract

**Complete catalogue: [`API-INVENTORY.md`](API-INVENTORY.md)** — planned-but-uncontracted (admin RBAC, connections, package fetch), 501-by-design operations, demo-launcher-only routes.

The frontend calls `/cp/admin/*`, `/cp/connections*`, `/cp/connection-types` and `POST /dp/instances/{}/actions/{}`. `console.yaml` has no operation for them, so they stay fixture-backed until a ruling adds contract operations — not scheduled by any plan in this folder. (`GET /cp/solutions/{}/package` is **resolved** — `getSolutionPackage`, commit `592bff02` — and no longer belongs on this list.)

## Decisions waiting on the owner / designer

Carried forward from plan 002 (D-1…D-5, written 2026-10-01) into the plan that now owns each area — check that plan's "Decisions" section for current status before assuming D-1…D-5 are still open as originally written:

- D-1 (home attention feed fields) → [004](004-home.md) — ✅ resolved as option B (resolve via `getInstance`)
- D-2 (home summary derivations) → [004](004-home.md) — stays open, renders `null`
- D-3 (declare-instance target/placement UX) → [007](007-instances-and-placements.md). ⚠️ **Widened 2026-10-08:** the Instances **list grid** has the same root cause from the other side — 7 of 9 columns (`env`/`org`/`subOrg`, `driver`, `locus`, health rollup, `version` state, `configState`, `lastSeen`) render fields `InstanceRow` invented, with no real source on `Instance`. Resolve D-3 and the grid-column question **together, in one session** — they both need one simultaneous field-set decision for the Instances feature, and solving them separately risks disagreeing answers.
- D-4 (surfaces with no contract — admin/connections/package-fetch) → ✅ **the package-fetch half resolved 2026-10-09** (commit `592bff02`): `GET /cp/solutions/{solutionId}/package` (operationId `getSolutionPackage`) is now in `console.yaml`, served by `SolutionController`, and `solutionsRepository.ts`'s `getPackage()` is wired against it. Plan 008's Designer Task 1 (the browser's local `project()` had nothing to run against — see the paragraph this replaces for the full reasoning, still correct) and the Solutions screen's package preview can now proceed. **Connections half resolved 2026-10-09** (plan 013, `BE-D66`/`OR-130`): `/cp/connections*` and `/cp/connection-types` are now contracted and scheduled. **Still open:** `/cp/admin/*` — untouched, stays fixture-backed, no plan schedules it.
- D-5 (contract operations nothing called — fleet, stop) → [009](009-apply-rollout-schedules-fleet.md)

## ✅ Real end-to-end verification (2026-10-08) found, and fixed, a high-severity defect

[`reports/real-e2e-2026-10-08.md`](reports/real-e2e-2026-10-08.md) — first run of the console
against the **real** production backend (not MSW, not a backend-less Playwright project).
`localSessionAdapter.ts`'s `WireIdentity` shape (plan 001, closed 2026-10-01) assumed
`role`/`superAdmin`/`organizationId` fields and an uppercase `LOCAL`/`IDP` `source` enum that do
not exist on the real wire — confirmed impact was "Load Package" permanently unreachable for
everyone, including true super-admins. **Fixed 2026-10-09** (commit `1a30e9e5`): `superAdmin` is
now derived from holding any unscoped (`{}`) permission grant (OR-12), the role chip was replaced
with a permission count (the real wire has no role-name concept), and `organizationId` was replaced
with `organizationNames` (the real `organizations[]` hierarchy, which can hold more than one
top-level org for an unscoped admin) — a UX upgrade too, since the app bar used to show a raw UUID.
Full suite green (359/359 files, 2650/2652 tests) after the fix.

## Needs one consolidated design session

Three items, found across plans 006/007/008/009/012, all come down to the same shape of question —
"the fixture era invented UI for data the real contract doesn't carry" — and some share root cause.
Resolving them one at a time risks inconsistent answers; working through them together before any
of plans 006/007(remainder)/008(remainder)/009(remainder)/012 continue:

1. **D-3 + the Instances list grid** (plan 007) — ✅ **declare-dialog target/placement UX resolved
   2026-10-09**: the dialog now collects `targetId` + the real `placement`/`acquisition` fields, and
   the invented `driver`/`locus`/`replicas`/`componentTypes` fields are gone. **Still open:** the list
   grid's 7 orphaned columns (`env`/`org`/`subOrg`, `driver`, `locus`, health rollup, version/config
   state, `lastSeen`) — still need the N+1-fetch-vs-drop-vs-fixture-split call.
2. **D-4 / Designer's package-fetch gap** (plan 008) — ✅ **resolved 2026-10-09**: `GET
   /cp/solutions/{solutionId}/package` (`getSolutionPackage`) is now contracted and served. Plan
   008 Tasks 1/2/4 (previously blocked on this) and the Solutions screen's package preview can now
   be executed as their own work — not done here; this item was the contract gap only.
3. **Target registration UI** (plan 006) and **scheduled-apply / fleet-apply / hierarchy-write UI**
   (plans 009, 012) — no reference-design screens exist for any of these four new flows.

## Out-of-band blockers

- Backend `mvn verify` fails in `mesh-events` (`tenant` vs `organization` in `contracts/events/`, unreconciled vs `D20`/`FE-D51`/`FE-D53`). Unrelated to the frontend integration work in this folder; re-check before relying on a clean `mvn verify` run.
