# 011 — Real-agent end-to-end evidence

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

| | |
|---|---|
| **Date** | 2026-10-06 |
| **Test** | `backend/launcher/src/test/java/com/solace/sm/launcher/RealAgentDockerE2EIT.java` |
| **Command** | `cd backend && mvn -o verify -pl launcher -am -Dtest='NoSuch*' -Dit.test=RealAgentDockerE2EIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` |
| **Environment** | macOS (Darwin 25.6, arm64) · Maven 3.9.16 · JDK 21.0.11 · Go 1.27.1 · Docker Engine 29.5.3 (Desktop) · PostgreSQL 14 (Testcontainers, both schemas, real Phase 0 + both migration mains) · managed target `alpine:latest` |
| **Result** | **9 / 9 pass** (also 32 / 32 across all `launcher` ITs run together). The test is **skipped, not failed**, when Docker, `go` or the alpine image is missing. |
| **Status** | Local, single-process, loopback. Not a canary, not a capacity measurement, not an independent review. |
| **Superseded in part (2026-10-07)** | The "not executed" list at the foot of this file was true on 2026-10-06 and is closed by [`015-history-p72-failure-matrix.md`](015-history-p72-failure-matrix.md), [`015-history-p73-packaging.md`](015-history-p73-packaging.md), [`015-history-capacity.md`](015-history-capacity.md) and [`015-history-independent-review.md`](015-history-independent-review.md). This file is kept as the record of the first run and is not edited further. The test now uses a per-run binding id (two overlapping runs sharing one Docker daemon had collided on the container label) and `--stop-timeout 1` on its target. |

## What runs

The test boots the **real** `SolutionManagerApplication` (CP + DP) with the gateway listener on, a PKCS12 CA the CP
signs with, and a CA-signed server certificate; builds the **real** `go-agent/cmd/sm-agent` with `go build`; starts
it as a subprocess against a **real** Docker container carrying the binding label and a bind-mounted config
directory; and drives everything through the existing apply engine. Nothing between the agent and PostgreSQL is a
test double.

| # | Scenario | What is asserted |
|---|---|---|
| 1 | First enrollment | Certless `Enroll` over the bootstrap TLS surface; CP consumes the one-time token and signs the CSR; mTLS `Connect`; one `agent_session` (epoch 1) and one `agent_lease`; one identity |
| 2 | `preflight` + `apply`, `manual` | `STAGED`; the host file equals the rendered bytes; `PREFLIGHT:ACKED:PREFLIGHT_OK`, `APPLY:ACKED:APPLIED`; dense strictly ascending per-binding command numbers |
| 3 | `apply` with a restart strategy | `APPLIED`; the container **really restarted** (`StartedAt` changed) and is running; a `TRUSTED`/`APPLIED` actual-state report with `restart_observed_at`; one DP-minted replica id |
| 4 | Telemetry | A value scraped from the container's HTTP-JSON endpoint is committed to `metrics_raw` through the canonical projection and is queryable; the acknowledged watermark advanced |
| 5 | Agent restart | Same data dir, no token: reconnects, **no second identity, no second enrollment**, fencing epoch ≥ 2 |
| 6 | Re-apply of an applied generation | Refused at admission (`NotAdmitted`); zero new commands; container untouched; file unchanged |
| 6b | Hash mismatch drill | A command whose payload does not match its `config_sha256` is refused by the agent with `KEY_HASH_MISMATCH`, recorded `REFUSED`, nothing written to the host |
| 7 | `stop` | `STOPPED`; the container is **observed** stopped, not merely issued |
| 8 | Log redaction (P6.3) | Neither the whole Java JVM output nor the agent log contains the enrollment token, the CA keystore password, the gateway private key (or any `PRIVATE KEY`/`CERTIFICATE` PEM block), or a marker embedded in the rendered payload; the agent log is non-empty so the check is meaningful |

## Defects the run found and fixed

None of these was visible to any existing unit or in-process test, because each side agreed with itself. Each was found
by the real agent refusing, stalling or hiding the reason, and each was diagnosed from evidence, not guessed.

| # | Defect | Fix | Where recorded |
|---|---|---|---|
| 1 | The launcher did not start: `agentGatewayClock` was `@ConditionalOnMissingBean`, suppressed by the existing clocks, and the launcher's actuator starter registered web `HandlerMapping`s the console refuses | Unconditional clock; starter removed | plan 011 verification update |
| 2 | **Enrollment could never succeed**: `sm_control.agent_registration.agent_id` is `uuid`, the registry bound a string (`operator does not exist: uuid = character varying`), and the CP issuers swallowed the cause | Explicit `cast(... as uuid)`; the issuers now log the exception class and root cause | `JdbcAgentRegistry` |
| 3 | **Every lease refused** (`ISSUE_NOT_ALLOWED`): the DP created `agent_identity` with revocation epoch 0, the CP registration starts at 1 | `EnrollmentResult` carries the CP's epochs; the DP stores them | `AGW-D17` §4 |
| 4 | Agent refused every command `KEY_HASH_MISMATCH`: Java sent `sha256:<hex>`, the agent computes bare hex | Gateway translates both ways | `AGW-D17` §2 |
| 5 | Every step deadline was "now" (`step deadline exceeded`): the lease never carried `verb_ceilings` | Gateway sends them; CP ceilings fit the 5-minute command deadline (they could not satisfy the DP's own admission rule at 10 min vs a 2-min deadline) | `AGW-D17` §3 |
| 6 | Gateway→agent messages **lost**: the poll loop and the stream handler called `onNext` on one stream from two threads | Serialized on the session monitor; an uncaught poll exception no longer cancels the schedule | `AGW-D17` §5 |
| 7 | **Preflight and apply of one rollout shared a generation**; the agent answered the `apply` notice by replaying the finished `preflight` | The DP allocates a strictly ascending per-(agent, binding) command number under an advisory lock | `AGW-D17` §1 |
| 8 | **No telemetry could ever commit**: V020 granted the runtime role `SELECT` only on `series`/`metrics_raw`, which ingest inserts into | V024 grants `INSERT` | `AGW-D17` §6 |
| 9 | Telemetry would stop at **2026-11-01**: `metrics_raw` had partitions only through 2026-10 and the "maintenance" function never created any | V025 adds an idempotent horizon function, run for 12 months at migration | `AGW-D17` §6 |
| 10 | A retried `enqueue` could be refused `KEY_HASH_MISMATCH` (the deadline and generation were part of command identity) | Identity is key + content | `AGW-D17` §1 |
| 11 | The agent hid enrollment failures; the gateway dropped the specific refusal code | Agent logs `enroll failed; will retry`; the gateway stores `ACK_DETAIL_CODE_ERROR:<ERROR_CODE>` | agent `loop.go`, `AgentGatewayService` |

Also corrected: the `Identity` contract split (fields restored from the pre-revert implementation), the Java designer
projector (`edge-catalog-supplied-options`: it returned catalog entry objects where the normative TS engine returns
ids, filtered by `connectionType`), and the `tenant` → `organization` rename in `mesh-events` (`BE-D27`).

## Scenario matrix (execution plan §4): what is and is not evidenced

| Scenario | Status |
|---|---|
| First enrollment | **Executed** (E2E 1) |
| Agent restart | **Executed** (E2E 5) |
| Duplicate command | **Executed** at admission (E2E 6); the agent-side replay of a finished generation is covered by Go tests |
| Hash mismatch | **Executed** (E2E 6b) |
| Stale epoch | **Executed by accident, then fixed**: defect 3 was exactly a stale-epoch lease refusal; no deliberate drill after the fix |
| Telemetry reject / pause | Gateway credit accounting covered by `AgentGatewayServiceTest`/`AgentGatewayOperationalTest`; **not executed** against the real agent |
| Report replay | The agent re-reports every 30 s and the DP replays each idempotently (observed as repeated `TRUSTED APPLIED` rows); **no deliberate reorder/duplicate drill** |
| Gateway restart, DP restart | **Not executed** |
| Split brain (two owners) | **Not executed** (single replica) |
| Lost `CommandReceived` / lost terminal ack | **Not executed** |
| Lease expiry during a step | **Not executed** |
| Revocation | **Not executed**; code reading suggests it blocks the next lease issuance but does not update the DP identity (runbook, known limits) |
| Untrusted report | **Not executed** |
| Planned drain | **Not implemented** |
| Overload | **Not executed**; bounded-queue behaviour covered by unit tests only |

## One unexplained observation

In one full-reactor run executed concurrently with other CPU-heavy commands, test 2 observed a **third** command
after the `STAGED` landing; it did not reproduce in the later runs (the complete `launcher` IT set, 32/32, and the E2E alone).
A transient step retry under contention is plausible and legitimate (a retry carries a new attempt id and a new
number), and the assertion was relaxed to the actual contract (preflight then apply, each acknowledged, numbers dense
and ascending). The cause was not established.

## Not claimed

Capacity, soak, a canary on a customer host, Kubernetes, multi-replica, live CA rotation, the independent adversarial
review of the design (§16), and `AGW-D17`'s ratification.
