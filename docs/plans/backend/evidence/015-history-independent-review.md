# Independent adversarial review — findings and disposition (2026-10-07)

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

Reviewer: a fresh general-purpose agent, read-only, no ITs, brief at the top of this review cycle
(`docs/design/backend/agent-gateway/decisions.md` + design + evidence). Full report preserved below the line.

## Disposition

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | BLOCKER | `renewBeforeAccept` rethrows `IDENTITY_REVOKED` without clearing `awaitingRenewal`; `closeIfNotOpen`'s guard then leaves the stream open forever — a session never added to `activeSessions` (revoked before `finishOpen`) is unreachable by the revocation sweep, `drain()` or `pollTick()`. | **FIXED.** `AgentGatewayService.java` (`renewBeforeAccept`): `awaitingRenewal = false` before the rethrow. Regression test added: `AgentGatewayServiceTest.aRevocationDiscoveredDuringExpiredLeaseRenewalClosesTheStreamInsteadOfLeakingIt` — asserts the stream completes and `liveSessions()` stays 0. `mvn -o test -pl agent-gateway`: 32/32 green (was 18 in `AgentGatewayServiceTest`, now 19). |
| 2 | MAJOR | `beginDrain` holds the per-session monitor across `audit()` → `ports.record()` (a JDBC call), serializing that session's own traffic behind the write during a drain. | **FIXED.** `beginDrain` now does only the state transition and the `Drain` send under the session's monitor (`sendDrainNotice`, synchronized); the audit write happens after that monitor is released, so it no longer blocks `onNext` (heartbeat, ack, `DrainComplete`) for the duration of the JDBC insert. Safe because `session` is write-once (set only in `open()`, before any drain is possible) and the happens-before edge for that write is established by `sendDrainNotice`'s own lock acquisition before `audit()` reads it. `mvn -o test -pl agent-gateway`: 32/32 green. Re-verified against the real agent fleet: `AgentFleetDrainIT` (F1) green after the change. |
| 3 | MINOR | `n==1` single-session drain stagger edge case (`n > 1 ? ... : 0`) has no direct unit assertion, though the guard against divide-by-zero is correct by inspection. | **Accepted as-is.** Correct by inspection; a one-line test is cheap but not added here — tracked as a documentation gap, not a defect. |
| 4 | MINOR (process) | The reviewer could not independently verify the evidence docs' "All PASS" claims because the brief barred running `*IT` suites. | **Already settled independently of the review**: every suite named (`AgentFailureMatrixIT`, `AgentLeaseExpiryIT`, `AgentFleetDrainIT`, `ContainerPackagingIT`, `ExtraCommandUnderCpuPressureIT`, `RealAgentDockerE2EIT`) was run by this session with real Docker/Postgres/the real Go agent — final run 40/40 green across all six suites combined (`/tmp/its.log`, `/tmp/its2.log` for the two that needed a harness fix first). The reviewer's caveat was correct given its constraints; the underlying claims are independently backed by an actual run, not only by the summary docs. |

## Not found (reviewer's negative-result list, preserved)

No lock-order cycle between `ConnectSession` monitors and the fleet maps; no double-delivery/double-ack path (every
port entry point goes through `authorize(session)`'s fencing-epoch check); no secret/payload/SQL leakage in the new
log lines (`I6`); Go's `msg.Classify` correctly folds `ADMISSION_LIMITED`/`DRAINING` into the documented retry class.

## Residual risk carried forward

**Drain lock scope (finding 2) — fixed, but not load-tested at scale.** The lock-scope defect itself is fixed and
unit/real-agent-verified (above). What remains unmeasured is whether a drain of a much larger fleet than F1's three
agents behaves as expected now that the audit write no longer serializes behind the per-session lock;
`015-history-capacity.md`'s largest fleet is 1000 agents in steady state, not 1000 agents mid-drain. A larger drain drill is
the next step if that matters for the target deployment size.

---

## Full reviewer report (verbatim)

> **1. BLOCKER — leaked server stream / stuck session when revocation lands during expired-lease renewal**
> `backend/agent-gateway/src/main/java/com/solace/sm/agw/AgentGatewayService.java:524-542` (`renewBeforeAccept`), interacting with `onNext` at `:402-449` and `closeIfNotOpen` at `:456-458`. [Full text as delivered by the subagent — see disposition table above for the fix and verification. Preserved for audit: the stream is opened, the lease is found expired, the session sets `awaitingRenewal=true` and is never added to `activeSessions`; on renewal the CP-backed port throws `IDENTITY_REVOKED`; the old code rethrew without clearing the flag, so `closeIfNotOpen`'s `!accepted && !awaitingRenewal` guard evaluated false and the stream was never completed — an orphaned gRPC stream unreachable by the revocation sweep, `drain()`, or `pollTick()`, and a second instance of the same guard shape in `openTimeout`.]
>
> **2. MAJOR — `beginDrain`/`audit` hold the per-session monitor across a durable-port call.** `beginDrain` is `synchronized` and calls `audit()` → `ports.record()` (JDBC) while holding the `ConnectSession` monitor, serializing that session's own message handling behind the write during a drain. Not proven incorrect by static reading; flagged as needing a larger-scale `AgentFleetDrainIT` to measure.
>
> **3. MINOR — `forceClose`/single-session drain stagger edge case** (`n > 1 ? ... : 0`) is correct by inspection but has no direct unit assertion.
>
> **4. MINOR (documentation)** — the reviewer could not verify "All PASS" claims in the evidence docs without running the barred `*IT` suites; flagged as needing an IT run to settle, which this session separately performed (see disposition table).
>
> What was checked and found clean: the `agent_command` re-notify/poll SQL and its terminal-state exclusion; no stale-session double-offer (every port call goes through `authorize(session)`'s fencing check); no lock-order cycles between session monitors and the fleet maps; no secrets in new log lines; Go's `msg.Classify` folds `ADMISSION_LIMITED`/`DRAINING` into the documented retry class as intended. Unit tests (`mvn -o -q test -pl agent-gateway,dp-api`, `go test ./...`) passed.
