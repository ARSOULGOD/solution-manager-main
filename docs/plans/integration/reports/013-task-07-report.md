# Plan 013 Task 7 — Tests + browser verification — Results

## Playwright journey (fixture project)

New file `frontend/e2e/connections.spec.ts`, added to the `fixture` project's `testMatch` in
`frontend/playwright.config.ts` (it previously matched `designer|journeys|reflow|...` only). Run:

```
npx playwright test --project=fixture connections.spec.ts --reporter=list
```

All 4 pass:

1. **Create a Solace broker with a plain Save** — `testBeforeSave: false`, row appears on the list
   immediately, no test probe involved.
2. **Test & Save fails against an "unreachable" host, then succeeds** — the MSW fixture answers
   `503 SERVICE_UNAVAILABLE` for a host attribute containing `unreachable`; the journey asserts the
   drawer stays open, the rejection ("Service unavailable") renders inline, and the typed host value
   is preserved (must-not: a failed test never writes a row — the first host never appears on the
   list). The same drawer is then corrected and a second Test & Save succeeds.
3. **Create a Remote server, both auth methods** — `basic` (Password) and `private-key` (Private key)
   — including the discriminator quirk: the Authentication select's visual default is not in
   component state until explicitly chosen, so the journey selects `basic` before filling Password
   (this is existing drawer behavior, not something the journey works around incorrectly — the same
   explicit-selection step the private-key case already needed).
4. **Edit + rotate a secret, deactivate, delete blocked then unblocked** — edits the seeded in-use
   row (`usedByCount: 3`), confirms the impact-warning gate, rotates its password, deactivates it
   (one-directional, must-not: no reactivate control), and confirms delete is blocked with the
   "Deactivate it instead" reason while in use. Separately, the seeded unused row (`usedByCount: 0`)
   deletes cleanly after a confirm dialog.

Full `fixture` project run (`npx playwright test --project=fixture`): **159 passed, 7 failed**. The 7
failures are **pre-existing and unrelated to Connections** — `designer.spec.ts`'s main journey and
`journeys.spec.ts` journeys 2/4/5/6/7, all failing at `instance.declare`/Designer-load call sites that
were already broken before this task touched anything (confirmed independently via
`devPackageFlow.test.ts`'s unrelated `DeclareInstanceInput` shape-mismatch failures, present in the
same already-modified files per this worktree's starting `git status`). None of the 7 names
Connections.

## MSW fixture handlers — real contract shape, Designer dropdown preserved

`frontend/src/shared/testdoubles/handlers/cp.ts` previously served the OLD, pre-Task-5 Connections
fixture shape (`id`/`revision`/`scope`/`secretStatus`) over `/cp/connections` — the real screen's
repository (`connectionId`/`ownershipScope`/`active`/`usedByCount`, `{set:true}` secret redaction, the
`{items:[...]}` envelope) could not run against it.

Fixed by adding a **second, separate** mutable store (`realConnectionRows`, seeded from
`SEED_REAL_CONNECTIONS`) and real handlers for the six operations (`GET`/`POST /connections`,
`PATCH /connections/:id`, `POST /connections/:id/deactivate`, `DELETE /connections/:id`,
`GET /connection-types`), while leaving the OLD store (`connectionRows`/`DEV_CONNECTIONS`) and its
`getConnectionRows()` export **untouched** — `devPackageHandlers.ts`'s Designer connection-binding
dropdown still reads that export directly (a function call, not HTTP) and is unaffected.

One conflict the addendum didn't fully anticipate: `devPackageFlow.test.ts` (Designer/drift-detection
suite, `UI-D27/28/29`) talks to the OLD shape **over real HTTP**, not just via the function export —
`GET /connections?type=...` and `PUT /connections/:id`. Resolved by disambiguating on exactly what the
real client never sends: a GET with `type`/`status` query params is answered from the OLD store (the
real repository is asserted, in `connectionsRepository.test.ts`, to never send query params at all),
and the OLD `PUT` handler is restored unchanged, co-existing with the new `PATCH` handler by HTTP
method rather than path. Verified: `devPackageFlow.test.ts`'s two connection-revision tests both
reach their assertions correctly now; one of the two then fails for the same pre-existing, unrelated
`instance.declare` 422 the rest of that file already has.

## Component tests — gaps filled

`ConnectionDrawer.test.tsx` gained 5 tests (16 total, all passing): the 429 rate-limit inline message,
the 503 service-unavailable inline message, secret-rotation leaving an untouched attribute unsent
(`I6`), and sending a newly-typed secret when rotated. `connectionsRepository.test.ts` already covered
422/429/503 and all six repository methods (Task 5); no gap there.

## Backend ITs — `ConnectionManagementEndpointIT` (11 tests, all passing)

New file, modeled on `TargetManagementEndpointIT`'s harness (`DeclareFixtures`,
`TwoSchemaTestDatabase`, real HTTP against the real `SolutionManagerApplication` + `postgres:14`).

Covers: create (solace-broker, remote-server/private-key) with `testBeforeSave: false`; a forced test
failure via a genuinely unreachable `127.0.0.1:<closed port>` for both connection types (real
production `SolaceBrokerConnectionTester`/`RemoteServerConnectionTester`, not doubled — real JCSMP/
sshj connection attempts, real refusals, confirmed `503` and nothing written); a scripted test success
and a scripted `422` credential rejection (the one thing this sandbox cannot produce for real, per the
addendum); update leaving an untouched secret's reference unchanged vs. rotating it to a new
reference; update with a forced-unreachable test leaving the row unchanged; delete blocked by
`usedByCount` then succeeding at zero; deactivate.

**Known gap, as the addendum predicted and explicitly permitted:** there is no live Solace broker or
SSH daemon in this sandbox, so `testBeforeSave: true` **success**, and the specific "peer replies with
a credential rejection" `422` path, are proven only via a scripted override of the
`ConnectionCredentialService` bean (`SCRIPTED`), not against a real peer. The **real** `503`
write-nothing path is proven for real (genuine TCP refusal through the actual production testers).
This is consistent with, not a new instance of, Task 3's already-parked finding.

### Two real bugs found and fixed in the process

Running this IT inside the full `SolutionManagerApplication` context (not a unit test with mocks)
surfaced that **the whole application context could never start** with the Connections feature
wired in — confirmed by the pre-existing `TargetManagementEndpointIT` failing identically before any
fix, i.e. a regression already present on this branch, not introduced by this task:

1. `ConnectionOrchestrator` (`backend/console-gateway/.../ConnectionOrchestrator.java`) has two
   nine-parameter constructors (production + package-private test seam), the same shape
   `TargetRegistrationOrchestrator` uses — but unlike that class, its production constructor was
   missing `@Autowired`, so Spring could not disambiguate and failed with "No default constructor
   found." Fixed by adding `@Autowired`.
2. **No Spring bean ever existed for `ConnectionService`** (`ConnectionDirectory`/
   `ConnectionRegistry`) — `ConnectionPersistenceConfiguration` supplied only the `ConnectionRepository`
   and was imported by nothing. Added `backend/cp-core/.../connection/ConnectionConfiguration.java`
   (mirroring `TargetConfiguration`) and imported it from `ControlPlaneConfiguration`.

Without these two fixes, `ConnectionController`/`ConnectionManagementController` could never be
instantiated in any real deployment — the Connections screen's backend was untested end-to-end until
this task and would not have booted.

## `mvn verify` / `run-checks.sh --strict`

- `mvn verify` (backend reactor, arch-tests included): see the companion
  `.superpowers/sdd/.../task-7-report.md` for the exact pass/fail per module — launcher's full suite
  is green except two unrelated, pre-existing `GatewayHttpBoundaryTest` failures (login response
  missing `role`/`superAdmin` fields; a hand-wired `MockMvc` harness untouched by anything in this
  task, fails in isolation too).
- `./contracts/run-checks.sh --strict`: mostly green. Pre-existing environmental failures (offline
  Maven plugin resolution for the licence gate, a missing Ajv2020 options string in check 14) match
  Task 1's already-documented set. **One new, real gap found**: check 15 (generated Postman
  collection vs. `console.yaml`/`ENDPOINT-INVENTORY.md`) reports drift for all six `connections`
  operations — `ENDPOINT-INVENTORY.md` never got rows for them (Task 4/5 gap, not this task's), so the
  generator's "served" marker disagrees with the (absent) inventory entry. Not fixed here — it is a
  contracts-documentation task, out of Task 7's "tests + browser verification" scope, and is reported
  rather than silently left out.
