# Plan 011 P7.2 — fault-injection drills against the real Go agent (verified 2026-10-07)

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

All scenarios run with a **real DP** (`backend/launcher`) and a **real `sm-agent`** binary against a real managed
host container, via `AgentFailureMatrixIT`, `AgentLeaseExpiryIT` and `AgentFleetDrainIT`. Raw harness output:
`015-history-raw-p72-failure-matrix.md` (M01–M15), `015-history-raw-p72-lease-expiry.md` (L1–L2), `015-history-raw-p72-fleet-drain.md` (F1). All PASS on
the final run (`mvn -o verify -pl launcher`, all three suites green).

| # | Scenario | Result |
|---|---|---|
| M01 | First enrollment | one identity, one epoch, token consumed |
| M02 | Agent restart (SIGKILL) | epoch advances 1→2; still one identity |
| M03 | Duplicate command (same request id) | exactly one row, one host effect |
| M04 | Hash mismatch (tampered payload) | DP refuses `KEY_HASH_MISMATCH`; agent refuses the tampered payload; host file unchanged; refusal audited |
| M05 | Lost `CommandReceived` (ack dropped) | command still reaches `ACKED:APPLIED`; renewal correctly refused during the gap; one `docker start` |
| M06 | Lost terminal ack | host executed once; DP's own close/reconcile closes the command 1.16 s after the host operation is observed; one `docker start` |
| M07 | Report lands after the apply ack — the "extra command" scenario | **one** PREFLIGHT + one APPLY, both ACKED; no retry — see `015-history-extra-command.md` for the controlled A/B proving the mechanism and its fix |
| M08 | **Gateway restart — planned drain with work in flight** | drained before the DP went down (2.56 s); in-flight command stayed `ACKED:APPLIED`; reconnected 482 ms after the DP returned; one `docker start`; telemetry resumed (watermark 14→18) |
| M09 | DP restart (full process state rebuilt from the database) | queued work survives the restart and completes after reconnect; one `docker start` |
| M10 | Split brain — a second gateway claims the agent | rival claims a higher epoch (6 over incumbent 5); incumbent reclaims at epoch 7; the superseded claimant's heartbeat is refused `STALE_EPOCH` |
| M11 | Split brain, hard mode — two live agent processes sharing one identity | 5 claims trade back and forth in 15 s; never both accepted at once (the backoff from `AGW-D20` is what bounds this) |
| M12 | Permission change under a live session | lease narrows 126 ms after the CP-side change; after the capability is restored the held command completes `ACKED:PREFLIGHT_OK` |
| M13 | Telemetry reject (unknown series) | rejected `UNDECLARED_LABEL`, audited, watermark held; once the mapping is provisioned the batch lands and the gap is recorded |
| M14 | Telemetry pause (zero credits) | watermark frozen for the full pause (no ack without a commit — `AGW-D20`'s credit model holds); resumes cleanly once credit is re-granted |
| M15 | **Revocation under load** | agent halted 113 ms after the CP-side revocation (`authority_phase":"REVOKED"`, `halted_reason":"IDENTITY_REVOKED"`); telemetry samples stop advancing at the moment of revocation; a restarted agent is refused at claim with the same halted reason — **the stale-identity gap this item set out to close is closed**: nothing revoked can reconnect or keep writing |
| L1 | Lease expiry with a healthy DP (renewals refused) | fails closed at 149 s (configured 150 s TTL); held command never ran while expired; recovers via the DP's renewal prompt; ran exactly once |
| L2 | Lease expiry across a DP outage longer than the lease | fails closed for the full 147 s outage; never halted with `ISSUE_NOT_ALLOWED`; renews with its last nonce and resumes without operator action |
| F1 | **Planned drain of a live fleet, one agent mid-command** | 3/3 agents answered `DrainComplete` (none forced); the in-flight command's outcome was durable before the DP went down; `reconnect_after` was staggered (1000/7000/13000 ms, spread 12000 ms against a 30000 ms deadline); all three back `ACTIVE` 8.86 s after the DP returned; one `docker start`; telemetry resumed for all three |

## What this settles

- **Revocation (item 4)**: a revoked identity cannot act on an active session (M15) and cannot reconnect (M15's
  restarted-agent check). Permission *narrowing* short of revocation also takes effect live (M12).
- **Planned drain (item 2)**: proven under both a single busy agent (M08) and a three-agent fleet with one mid-command
  (F1) — staggered reconnect, no forced closes, no lost or duplicated work.
- **Split brain / fencing**: M10–M11 show the epoch fence holds under both a clean second claimant and two live
  processes sharing one identity.
- **Lease liveness**: L1–L2 show fail-closed behaviour and clean recovery at and beyond the lease TTL, including a DP
  outage that outlasts the lease.

## Not covered here

Multi-replica gateway deployments (unsupported in this build — see `015-history-capacity.md`); WAN-latency conditions; a
managed host other than the single Docker container used throughout. These scenarios are single-run, not
statistically repeated — M07/M11's timing-dependent outcomes could in principle vary run to run; `015-history-extra-command.md`
is the one case this was checked with a repeated A/B rather than a single observation.
