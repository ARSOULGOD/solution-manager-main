# Connections screen (real backend) & Targets screen removal — Integration Plan 013

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the Connections half of D-4 — give `/cp/connections*` and `/cp/connection-types` real
contract operations, backend persistence, secret handling and connectivity testing — and remove the
`/targets` preview screen (nav/UI only; the Target domain, its API and `Placement`'s dependency on
`targetId` are untouched, per the owner's explicit scope ruling in this plan's brainstorming session,
2026-10-09).

**Design basis:** [`BE-D66`](../../design/backend/decisions.md#be-d66--connections-get-the-cpdp-split-targets-already-have-and-the-connection-verbs)
(cp/dp split, secret reuse, test semantics) and [`OR-130`](../../design/DECISIONS.md) (the four new
`connection.*` verbs). Read both before starting Task 2 — the split is not optional restructuring, it is
how `I6`/`I7` stay true for this feature, mirroring `Target`'s existing split (`BE-D54`).

**Connections is not Targets.** Do not let any task reach for Target's host-key pinning/scanning
machinery for a Remote Server connection's "Test & Save" — that mechanism is deliberately excluded
(`BE-D66`). A connection's connectivity test authenticates and disconnects; it never pins a host key,
never writes to `target`/`target_connection`, and never participates in Placement's target picker.

## Operations (new)

| Operation | Route | Verb |
|---|---|---|
| `listConnections` | `GET /cp/connections` | `connection.view`, `x-sm-scope: reach` |
| `getConnectionTypes` | `GET /cp/connection-types` | authenticated only (static catalog, mirrors `contracts/connection-types/*.json`) |
| `createConnection` | `POST /cp/connections` | `connection.create`, `x-sm-scope: named` |
| `updateConnection` | `PATCH /cp/connections/{id}` | `connection.edit` |
| `deactivateConnection` | `POST /cp/connections/{id}/deactivate` | `connection.edit` |
| `deleteConnection` | `DELETE /cp/connections/{id}` | `connection.delete`; `409` while `usedByCount > 0` |

`createConnection`/`updateConnection` take a body `testBeforeSave: boolean` — this is the single flag
backing both the "Save" and "Test & Save" buttons; there is no separate test-then-save round trip.
`testBeforeSave: true` and the test fails → `422 CONNECTION_TEST_FAILED` with the rejection reason,
**nothing written**, non-secret fields echoed back so the form can be left filled in (secret fields
stripped from the echo, `I6`). Connectivity tests share a new rate budget (10/min, `429 RATE_LIMITED`
+ `Retry-After`) — same mechanism as the Target scan budget, separate counter.

## Task 1: Contract

- [ ] Add the six operations above to `contracts/openapi/console.yaml` under a `connections` tag, request/response schemas (`Connection`, `ConnectionList`, `CreateConnectionRequest`, `UpdateConnectionRequest`, `ConnectionType`), and the four `connection.*` verbs to the catalog (`OR-130`) wherever the ratified verb table is enforced (check 11).
- [ ] Regenerate the Postman collection (`contracts/openapi/postman/`) and run `./contracts/run-checks.sh --strict` — must pass checks 11 and 15 before any backend code.

## Task 2: `cp-core` — the `connection` row

- [ ] Migration `backend/cp-core/src/main/resources/db/migration/sm_control/V021__connections.sql` — `connection` table per `BE-D66` (id, org/sub-org/env scope columns, name, connection_type CHECK, descriptor_version, attributes JSONB, secret_status JSONB, status, revision, used_by_count, audit columns); scoped `UNIQUE (org_id, sub_org_id, env_id, name)`.
- [ ] `ConnectionService` (authorization, scope enforcement `I5`, optimistic concurrency on `revision`, `usedByCount`-guarded delete) — tests first, modeled on `TargetService`'s registration/retire race tests.
- [ ] Bind-parameterized queries throughout — no string interpolation (`I5`).

## Task 3: `dp-core` — secrets and connectivity tests

- [ ] New `SecretPurpose.CONNECTION_CREDENTIAL`, keyed by `connectionId`, sealed/resolved through the existing `EnvelopeSecretStore` (`sm_data.secret`) — no new secret table.
- [ ] `SolaceBrokerConnectionTester`: opens a JCSMP session with the submitted host/port/vpn/username/password, confirms connect, disconnects. Surfaces the broker's own rejection reason on failure.
- [ ] `RemoteServerConnectionTester`: SSH connect + authenticate (basic or private-key per `authMethod`), then disconnect. **No host-key scanning or pinning** — accept whatever key the host offers, the same trust posture a `ssh` client without `StrictHostKeyChecking` would have, since this is not a deployment-trust decision.
- [ ] `I6`: secret values never logged, never returned, never cached past the single call that needs them.

## Task 4: `console-gateway` — composition

- [ ] `ConnectionController`/`ConnectionManagementController` (mirroring `TargetController`/`TargetManagementController`): authorize at `cp`, run the `dp` secret seal + optional test, write the `cp` row, in that order — a refused pre-check leaves nothing written in either plane, same invariant as `registerTarget`.
- [ ] Shared rate-limit handling for the connectivity-test budget (`429` + `Retry-After`), one code path, not one per operation.

## Task 5: Frontend — wire Connections to the real API

- [ ] `connectionsRepository.ts`: replace fixture calls with the six real operations via `cpClient`.
- [ ] `ConnectionDrawer`: add the two explicit actions "Save" (`testBeforeSave: false`) and "Test & Save" (`testBeforeSave: true`); on `422 CONNECTION_TEST_FAILED` keep the drawer open, show the rejection reason inline, preserve every entered non-secret value and each secret's "entered" state (never the value) so the operator can hit "Save" without retyping anything.
- [ ] `getConnectionTypes` replaces the static `DESCRIPTORS` import for runtime data; keep the TS types.
- [ ] Migrate component tests off the fixture array to MSW handlers backed by the real contract shapes (same pattern used elsewhere in this integration effort).

## Task 6: Frontend — remove the Targets screen

- [ ] Before deleting anything: confirm what the Declare-instance target picker (plan 007) imports. If it reaches through `features/targets`, extract just its `list()` call into a small shared module first — the picker must keep working; only the standalone preview screen goes away.
- [ ] Delete `frontend/src/app/routes/TargetsRoute.tsx`, `frontend/src/features/targets/` (preview component, repository, tests), the route registration, and the `AccountMenu` DEV-only `/targets` preview link.
- [ ] Confirm `listTargets`/`TargetDetail` consumers elsewhere (placement flows, plan 007) are unaffected — run their existing tests.

## Task 7: Tests + browser verification

- [ ] Component tests per repository method and per Drawer action, including the rate-limit and test-failure paths.
- [ ] Backend ITs: create/update both types with `testBeforeSave` true and false (including a forced test failure — wrong broker/SSH credentials — proving nothing is written); delete blocked by `usedByCount`; delete succeeds at zero; secret rotation on update leaves untouched fields alone.
- [ ] Playwright journey: create Solace Broker (Save), create Solace Broker (Test & Save, failure then success), create Remote Server both auth methods, edit + rotate a secret, deactivate, delete blocked then unblocked. Record in `reports/013-task-07-report.md`.
- [ ] `mvn verify` (arch-tests — `I7` must hold: no `cp-core` import of `dp-core`'s secret/SSH/JCSMP classes) and `./contracts/run-checks.sh --strict`.

## Task 8: Documentation

- [x] `docs/plans/integration/README.md`: mark the Connections half of D-4 resolved; add plan 013's row to the plans table. Done when this plan was written (2026-10-09).
- [x] `docs/plans/integration/006-target-registration-and-connections.md`: D-4 carried-forward line points at this plan. Done when this plan was written.
- [x] `docs/plans/integration/API-INVENTORY.md`: "owner: contract or defer" tag dropped from the Connections row. Done when this plan was written.

## Decisions carried forward

- `BE-D66`, `OR-130` — this plan is their implementation.
- D-4 (Connections half) — closed by this plan; Task 8 updates the three documents above.
- Plan 006's "Decisions carried forward" line (D-4 not resolved by that plan) — points at this plan.

## Global constraints

Same as plan 004 — see that plan's closing section.
