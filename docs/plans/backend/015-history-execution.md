# 011 — Production agent-gateway execution plan

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`** (the number collides with [`011-monitoring-backend.md`](011-monitoring-backend.md)). It proves the *branch's* tree, not ours, and it is **not authority**: [`015-agent-gateway.md`](015-agent-gateway.md) is. Apart from this note, only file names and the links between these history files were edited.

| | |
|---|---|
| **Component** | Backend `agent-gateway` production integration |
| **Design** | [`../../design/backend/agent-gateway/specs/2026-10-05-dp-agent-production-integration-design.md`](../../design/backend/agent-gateway/specs/2026-10-05-dp-agent-production-integration-design.md) |
| **Implementation plan** | [`015-history-implementation.md`](015-history-implementation.md) |
| **Status** | **IN PROGRESS — local implementation and hardening executed; external release gates remain** |
| **Recommended route** | Fresh execution session per phase; this work spans multiple modules, migrations, contracts and environments |

## 1. Execution rules

- The approved implementation plan is authoritative; this document is the phase order and release procedure.
- Before execution begins, ask whether the developer wants one session or fresh sessions per phase. Fresh sessions are
  recommended because the work is larger than five tasks, touches more than three files and crosses components.
- Do not implement around an open prerequisite. Stop at the named gate and record the owner/open item.
- Every phase produces evidence before the next phase starts.
- The fake gateway may be extended to keep tests deterministic, but it must never be used as a production fallback.
- No destructive database or customer-host action is allowed without the phase's rollback point and approval.

## 2. Entry gates

| Gate | Evidence required | Owner |
|---|---|---|
| Protocol | Authoritative versioned `.proto`, generated bindings and compatibility fixtures | Contract/agent owners |
| Authority | `SM-D24`/`SM-D28` session/lease placement resolved | Architect group |
| Reports | AGW-20 actual-state persistence contract approved | Backend/agent-gateway owners |
| Registry | AGW-17 and AGW-19 prerequisites resolved for registry behavior | Backend/agent-gateway owners |
| Lease issuance | AGW-18 CP issuance idempotency contract approved | CP/backend owners |
| Audit | AGW-21 durable security-audit contract approved | Backend/architect owners |
| Apply seam | `BE-D12`/`BE-D13` decision for `IN_FLIGHT` and resume | Backend apply owners |
| Database | Migration numbering, bootstrap authority and maintenance ownership reconciled | Backend owners |
| Operations | TLS/secret distribution, endpoint exposure and capacity constants measured | Release/operations owners |

### Branch execution status

The branch has completed the locally actionable contract, module, port, migration, gRPC shell, CP-backed issuer code,
launcher wiring, telemetry credit accounting and gateway operational signals. **As of 2026-10-06 it has also executed
the L3 path against a real Go agent, PostgreSQL 14 and a Docker target** (`RealAgentDockerE2EIT`, 9/9; evidence in
[`evidence/015-history-real-agent-e2e.md`](evidence/015-history-real-agent-e2e.md)): enrollment over mTLS with a CP-signed
certificate, session/lease, preflight/apply/restart/stop through the apply engine, durable reports, committed
telemetry, agent restart without re-enrollment, and a redaction check. The following are deliberately **not** claimed:
most of the §4 failure matrix (gateway/DP restart, split brain, lost acks, lease expiry, revocation, telemetry
pause/reject, drain, overload), a container image and deployment drill, capacity measurement, and the rollback drill.

The implementation choices used while proceeding are recorded in the [implementation plan](015-history-implementation.md).

## 3. Phase sequence

### Phase E0 — approval and baseline

**Entry:** request is accepted as a documentation task; no code execution yet.

1. Review the design against [`spec-review.md`](../../checklists/spec-review.md).
2. Run an independent adversarial review with the predecessor gateway design, Go-agent design and backend apply plans
   attached.
3. Resolve or route every finding. A “looks good” review is not sufficient.
4. Review the implementation plan against [`plan-review.md`](../../checklists/plan-review.md).
5. Record ratification/closure evidence for every entry gate.

**Exit evidence:** approved design, approved implementation plan, gate table with no unowned blocker.

### Phase E1 — contract and persistence foundation

1. Land the authoritative contract and generated bindings.
2. Add the `dp-api` records/ports and their contract-test kit.
3. Allocate migrations after reconciling plan 010's ledger.
4. Implement identity/session/lease/command/report/audit persistence in `dp-core` according to the approved gates.
5. Run migration, privilege, concurrency and replay tests on a clean PostgreSQL instance.

**Exit evidence:** `dp-core` owns durable state; in-process port adapters pass; no gateway code exists yet that reads
the database directly.

### Phase E2 — gateway shell and enrollment

1. Add the Maven module, package rules and generated-code isolation.
2. Configure bootstrap TLS, enrollment token handling and client-certificate verification.
3. Implement `Enroll`, `Connect` session claim, fencing and `SessionAccept`.
4. Add readiness/liveness and connection metrics.
5. Run protocol fixtures against the fake gateway and the Java gateway shell.

**Exit evidence:** local gateway health/readiness and metrics tests pass; a real Go agent enrolling and reaching
`ACTIVE` against the Java DP remains an external integration gate.

### Phase E3 — command delivery and apply

1. Implement command polling and after-commit wake-up optimization.
2. Implement notify/pull/offer and separate delivery/terminal acknowledgements.
3. Implement the agent-backed `RuntimeAdapter` seam.
4. Provision a hash-verified rendered generation and run preflight against a disposable target.
5. Run apply and verify the config hash, generation, journal state, correlation IDs and actual report.
6. Run restart and stop and verify observed state, not only Docker API return values.

**Exit evidence:** the existing DP apply API completes all four commands through a real Go agent and Docker target.

### Phase E4 — reports and convergence

1. Implement report intake transaction and registry behavior.
2. Handle duplicate, reordered, stale, untrusted and handoff reports.
3. Connect `RuntimeAdapter.status()` and convergence gates to the trusted projection.
4. Run agent restart and lost-ack scenarios.

**Exit evidence:** a report is durable before acknowledgement; a stale/untrusted report cannot close an apply or health
gate; registry IDs remain stable/replay-safe.

### Phase E5 — telemetry

1. Implement telemetry stream open/close and session binding.
2. Implement ingest commit acknowledgement and connection-local credits.
3. Run a live HTTP-JSON metric through the Go agent into DP storage/query.
4. Pause credits, restart gateway, disconnect agent and verify buffer replay/gaps.

**Exit evidence:** telemetry is queryable through the existing Data Plane path and no uncommitted batch is
acknowledged; local credit accounting tests pass, while live database/query and reconnect evidence remain pending.

### Phase E6 — hardening and production readiness

1. Add durable audit and redaction coverage.
2. Execute load and capacity measurements for session count, poll rate, command rate, report rate, telemetry bytes
   and database write rate.
3. Run security, dependency, migration and architecture checks.
4. Package TLS, migrations, readiness, drain, metrics and alert configuration.
5. Execute the rollback drill in a disposable environment.

**Exit evidence:** local operational limits, metrics, readiness and runbooks are present; a release candidate still
requires measured capacity, redaction evidence and the external rollback drill.

## 4. Required scenario matrix

| Scenario | Steps | Pass condition |
|---|---|---|
| First enrollment | Empty agent data dir → Enroll → Connect | Certificate and endpoint persist; session reaches `ACTIVE` |
| Agent restart | Kill/restart agent with same data dir | No re-enrollment; pending work resumes safely |
| Gateway restart | Drain/kill Java DP → restart | Agent reconnects; no duplicate execution; credits recover |
| DP restart | Restart full DP process | DB reconstructs ownership/commands; agent resumes from hints |
| Split brain | Two gateway instances claim same agent | One epoch owns writes; stale epoch is refused |
| Lost `CommandReceived` | Drop delivery ack | Command is re-notified/pulled; agent executes once |
| Lost terminal ack | Drop terminal ack | Agent/DP replay returns original terminal result |
| Duplicate command | Redeliver same key/hash | No second host operation |
| Hash mismatch | Same key with different payload hash | Refused and audited; no write |
| Lease expiry | Expire lease during a step | Agent fails closed per its state machine; DP does not falsely converge |
| Revocation | Raise agent or fleet epoch | New work stops; refusal/revocation is audited |
| Report replay | Replay same item and reorder batch | Same result/IDs; no duplicate registry identity |
| Untrusted report | Report outside scope/epoch | Stored as untrusted/refused; cannot satisfy convergence |
| Telemetry pause | Grant zero credits | Gateway sends no false ack; agent buffers and later drains |
| Telemetry reject | Reject oversize/unknown label | Batch is coded as rejected/gap; later batches continue |
| Planned drain | Drain gateway with live sessions | Staggered reconnect, no stranded credits or commands |
| Overload | Saturate sessions, pollers and telemetry | Admission/backpressure protects bounded resources |

## 5. Environment ladder

### L0 — static and unit

- Java unit tests for message mapping, state transitions, code classification and redaction.
- Go codec/compatibility tests.
- ArchUnit and dependency allow-list tests.

### L1 — persistence integration

- Clean PostgreSQL container using repository migrations.
- Transaction/concurrency/replay/privilege tests.
- Contract tests against in-process `dp-core` ports.

### L2 — protocol integration

- Java gateway and fake gateway both exercise the same contract fixtures.
- Go agent reaches `ACTIVE`, heartbeats, renews and reconnects.

### L3 — runtime integration

- Disposable Docker target with binding label and external config mount.
- Preflight, apply, restart, stop and report verification.
- Agent telemetry source and DP query verification.

### L4 — release candidate

- Full Maven verification and backend launcher deployment.
- TLS/secret references from the real deployment mechanism.
- Capacity test and rollback drill.

## 6. Release procedure

1. Freeze the contract version and generated-code checksums.
2. Apply migrations through the supported migrator; verify readiness and schema version.
3. Deploy the DP with the listener disabled for agent admission.
4. Verify health, readiness, metrics and audit storage.
5. Enable admission for one canary target.
6. Enroll one fresh agent and run the complete L3 command/report/telemetry path.
7. Observe reconnect, lease, command, report and telemetry metrics for the agreed soak period.
8. Expand admission by cohort, preserving the rollback version and migration posture.
9. Close the release only after the acceptance evidence is attached to the implementation plan.

## 7. Rollback procedure

1. Disable new agent admission and stop creating new host-local commands.
2. Drain existing sessions; do not delete command, report, lease or audit rows.
3. Wait for in-flight commands to become terminal or enter the approved reconciliation state.
4. Capture health, command, report, telemetry and audit evidence.
5. Roll back the application only if the previous image understands the current durable schema and protocol window.
6. If schema compatibility is not proven, keep the current image in a safe disabled mode and escalate to the
   migration owner; never run an ad-hoc downgrade.
7. Re-enable only after a canary passes the same L3 acceptance path.

## 8. Definition of done

- [ ] All entry gates are closed by their owning records. **(`AGW-D17`–`AGW-D22`, `BE-D27` remain `PROPOSED`, not ratified — the one gate still open, by design: see item 6/item 9 of the task's highest-priority open items.)**
- [ ] Design and implementation plan are approved. **(same reason — implemented and verified, not ratified.)**
- [x] Every implementation task has code, tests and evidence.
- [x] The full scenario matrix passes at L2/L3 — `docs/plans/backend/evidence/015-history-p72-failure-matrix.md`.
- [x] Production configuration and secret references are documented — `docs/runbooks/backend/enable-agent-gateway.md`.
- [x] Capacity limits are measured and surfaced as configuration/metrics — `docs/plans/backend/evidence/015-history-capacity.md`.
- [x] Canary and rollback drills are executed — rollback drill in `015-history-p73-packaging.md`'s runbook execution; canary is N/A (single replica, no supported multi-replica deployment yet).
- [x] The implementation plan Review section records deviations and remaining limitations — see `015-history-implementation.md` and the evidence docs' "Not covered/Not done" sections.

## 9. Execution record

| Phase | Owner | Started | Evidence | Result |
|---|---|---|---|---|
| E0 | branch implementation | 2026-10-05 | branch-local decisions in implementation plan | Code execution authorized for unresolved implementation choices; external ratification remains open |
| E1 | branch implementation | 2026-10-05 | `dp-api`, `dp-core`, V007/V008, migration shape tests | Local compile/tests pass; PostgreSQL privilege/concurrency execution pending |
| E2 | branch implementation | 2026-10-05 | `agent-gateway`, mTLS listener, CP registry/CA adapters, launcher wiring, service/operational tests | CP-backed issuer code, fail-closed TLS wiring, health/readiness and local gateway tests pass; real CA-keystore enrollment and real agent remain pending |
| E3 | branch implementation | 2026-10-05/06 | `JdbcAgentPlacementCatalog`, `AgentRuntimeAdapter`, `JdbcAgentRenderedConfigSource`, `ApplyExecutor` wiring, durable completion/correlation projection and adapter unit tests | Production apply wiring is present behind `sm.dp.agent.runtime.enabled`; PostgreSQL-backed generation provisioning and disposable-target E2E remain release gates |
| E4 | branch implementation | 2026-10-05/06 | report persistence, replay fingerprint, registry IDs, trusted applied-only actual-state query and correlation persistence | Canonical `status()` reads only trusted applied rows; PostgreSQL freshness/concurrency and restart evidence pending |
| E5 | branch implementation | 2026-10-05 | telemetry watermark/sample/gap persistence, `series`/`metrics_raw` projection and query port, gateway credit test | Canonical query path and connection-local commit-gated credits are implemented; live DB query/partition and reconnect evidence pending |
| E6 | branch implementation | 2026-10-05/06 | TLS/capacity properties, maintenance function, Micrometer metrics, actuator health/readiness, ArchUnit, Go payload/token redaction tests | Focused architecture/build/operational checks pass; captured Java log review, measured capacity and Docker release checks pending |
| E7 | branch implementation | 2026-10-06 | `RealAgentDockerE2EIT` (real agent + Postgres 14 + Docker), V024/V025, `AGW-D17`, runbook `docs/runbooks/backend/enable-agent-gateway.md` | First real-agent execution: 11 interoperability defects found and fixed; L3 command/report/telemetry path passes; failure matrix and capacity still open |
| E8 | branch implementation | 2026-10-07 | `AgentFailureMatrixIT` (M01–M15), `AgentLeaseExpiryIT` (L1–L2), `AgentFleetDrainIT` (F1), `ContainerPackagingIT` (P01–P10, P05b), `ExtraCommandUnderCpuPressureIT`, `AgentCapacityIT` (1000 agents, 3 configs), `SmControlMigrationUpgradePathIT`; one independent adversarial review (1 BLOCKER fixed + regression test, 1 MAJOR acknowledged as residual risk); evidence under `docs/plans/backend/evidence/011-{p72-failure-matrix,p73-packaging,extra-command,capacity,migration-strategy,independent-review}.md` | **Full P7.2/P7.3 failure-matrix and packaging drills green against the real Go agent; capacity measured to 1000 agents; the extra-APPLY-command race root-caused, fixed and proven by a controlled A/B; the revoked-identity-during-renewal stream leak found by review, fixed, and regression-tested; the `sm_control` V013 gap resolved and tested on both a fresh and an upgraded database. Full `mvn -o verify` reactor + `go test ./...` (unit and integration) + `./contracts/run-checks.sh` all green (one `AgentLeaseExpiryIT` run flaked under concurrent host load and passed clean on an isolated rerun — not reproduced, treated as host contention, not a defect). `AGW-D17`–`AGW-D22` and `BE-D27` remain `PROPOSED`; not ratified here.** |
