# PG 14 evidence run — Agent Gateway design set

* commit under test: `8551e5c`
* engine: `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit`
* container: `agw-pg14-s12-full`  image `postgres:14.24`  host port 55442
* started: 2026-09-11T06:23:36.043019+00:00   finished: 2026-09-11T06:24:02.816099+00:00

## Bootstrap line ranges and md5

| file and range | md5 |
|---|---|
| phase0 (docs/design/backend/eda-walking-skeleton.md L216-253) | `768e195b7c17861d97e402c5318be51b` |
| telemetry (backend/src/main/resources/db/migration/V001__telemetry_schema.sql whole file) | `d8e3948552258e17d2c51e440afcbe55` |
| phase1 (docs/design/backend/eda-walking-skeleton.md L254-533) | `5c28fa14868fbd72cbec158193e6d86f` |

## Extracted blocks under test

| block | md5 |
|---|---|
| design §5.2#1 L389-544 | `e5be6e887dc2090614c1f237b1b3e499` |
| design §5.2#2 L557-598 | `4c4dc9c27fd2d6f59b2f563f28be2b56` |
| design §5.2#3 L604-637 | `df02e4f394539a314f5315f2b69860f7` |
| design §5.2#4 L644-675 | `708156fec62a5390f7f46e3646ced715` |
| design §7.4#1 L847-864 | `6d4c03f8dbafedd137b0dcfc73e075b6` |
| design §7.4#2 L870-886 | `dc0221a787d22cf828e9625c8761bc68` |
| sketch §2.1#1 L42-186 | `70cc50481add4dc0dca72ad1cdb73364` |
| sketch §2.2#1 L230-267 | `bcb812681a35023750b1a50eec01df0e` |
| sketch §2.3#1 L275-311 | `58abfb34c9f1bfd604deaffe82fc7473` |
| sketch §2.4#1 L317-328 | `239668f9af3f3b26c1af3a8a8f89b269` |
| sketch §2.5#1 L334-409 | `62262ed74ae0846b89662d6b558741f8` |
| sketch §3.1#1 L425-532 | `b88620f57aa451b40157886ee343c2d5` |
| sketch §3.3#1 L552-595 | `52511d41647e14ed9a2a5268bb5ba4b6` |
| sketch §3.3#2 L599-651 | `19edf27508059b70284579d4238c8918` |
| sketch §3.4#1 L665-704 | `e59510e3ab28c0f83f4eda9949083a64` |
| sketch §4.1#1 L748-833 | `35ec346eee9b00c94ed306f1a2c3a35f` |
| sketch §4.1#2 L849-891 | `964f963382d87cc58953b019934bfbf8` |
| sketch §4.2#1 L909-984 | `bcc35d922e04e07a79d9ba947a3477b8` |
| sketch §5.0#1 L1006-1017 | `db33284fcf37d605436129f401d64b8a` |
| sketch §5.1#1 L1039-1065 | `3130f2cd842a260fb90c622181e1c6a4` |
| sketch §5.2#1 L1075-1088 | `191ae315d21818b9c5b0fb937b80a1b6` |
| sketch §5.3#1 L1104-1134 | `2df16308e04aeb49f6f2fa42d6db2ff3` |
| sketch §5.4#1 L1162-1183 | `67eab03e3d227246f9f526b0e1a2c3d1` |
| sketch §6#1 L1204-1204 | `209dcb8c75419df5894ed483a0c1325d` |
| sketch §7#1 L1229-1254 | `b0111391ed19434ef8c5e73550d01863` |

## Verdict counts

| verdict | legs |
|---|---|
| OK | 162 |
| REFUSED | 60 |
| N/A-engine | 2 |
| **total** | **224** |

## Legs

| obligation | leg | phase | verdict | expected | observed | SQLSTATE | ms |
|---|---|---|---|---|---|---|---|
| §14.3(a) | two-concurrent-claims | production | **OK** | both commit; ledger epochs [1,2]; session at 2 owned by the later claimant | A=commit B=commit ledger=[1, 2] session=2 owner=pod-b | `-` | 8 |
| §14.3(a) | two-concurrent-claims | planted | **OK** | with the UNIQUE dropped a second ledger row may claim epoch 2 (the guard is real) | duplicate epoch accepted | `-` | 0 |
| §14.3(a) | two-concurrent-claims | restored | **OK** | claim writes exactly one row again | 1 row(s) | `-` | 0 |
| §14.3(b) | positive-controls | production | **OK** | a legal raise (epoch 5→6 with a new owner) and a last_seen_at-only heartbeat both succeed | raise=1 row, heartbeat=1 row | `-` | 0 |
| §14.3(b) | AGW01-epoch-decrease | production | **REFUSED** | SQLSTATE AGW01: a fencing_epoch decrease is refused | SQLSTATE AGW01: agent_session 51603454-24b1-4840-b3a5-0e941a08d28e fencing epoch may not decrease (6 -> 1) | `AGW01` | 4 |
| §14.3(b) | AGW02-owner-without-epoch | production | **REFUSED** | SQLSTATE AGW02: an owner change that does not raise the epoch is refused | SQLSTATE AGW02: agent_session 51603454-24b1-4840-b3a5-0e941a08d28e owner may not change without raising the fencing epoch (pod-b -> pod-z at epoch 6 -> 6) | `AGW02` | 4 |
| §14.3(b) | AGW01-epoch-decrease | planted | **OK** | without the trigger the epoch goes backwards | 1 row updated, epoch now 1 | `-` | 0 |
| §14.3(b) | AGW01-epoch-decrease | restored | **REFUSED** | SQLSTATE AGW01: the re-created trigger refuses the decrease again | SQLSTATE AGW01: agent_session cda61e42-bcba-43ad-99e5-1915ffabd317 fencing epoch may not decrease (5 -> 1) | `AGW01` | 5 |
| §14.3(c) | 42501-agent_session.tenant_id | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_session | `42501` | 4 |
| §14.3(c) | 42501-agent_identity.agent_id | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_identity | `42501` | 4 |
| §14.3(c) | 42501-agent_command.command_id | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_command | `42501` | 4 |
| §14.3(c) | 42501-agent_claim DELETE | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_claim | `42501` | 4 |
| §14.3(c) | 42501-agent_lease_grant DELETE | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_lease_grant | `42501` | 4 |
| §14.3(c) | 42501-agent_request_tombstone INSERT | production | **REFUSED** | SQLSTATE 42501: the runtime role's column-scoped grant refuses it | SQLSTATE 42501: permission denied for table agent_request_tombstone | `42501` | 6 |
| §14.3(c) | positive-control-granted-column | production | **OK** | the same role may write a granted column | 1 row(s) | `-` | 0 |
| §14.3(d) | stale-poll (CommandFeedPort) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-terminal ack (CommandAckPort.ack) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-lease reservation, step 1 (LeasePort) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-lease adoption, step 1 re-arm | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-lease takeover (§3.4) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-registry A1 fence (§4 Phase A) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | stale-delivery ack (CommandAckPort.received) | production | **OK** | 0 rows written at a superseded epoch | 0 row(s) | `-` | 0 |
| §14.3(d) | registry-and-ledger-unchanged | production | **OK** | no ledger row terminalised and the command still OFFERED | terminal ledger rows=0, command state=OFFERED | `-` | 0 |
| §14.3(e) | 23505-second-row-per-intent | production | **REFUSED** | SQLSTATE 23505: UNIQUE (tenant_id, apply_id, intent_id, agent_id) refuses a second command for one intent/agent | SQLSTATE 23505: duplicate key value violates unique constraint "agent_command_tenant_id_apply_id_intent_id_agent_id_key" | `23505` | 4 |
| §14.3(f) | never-offered-may-be-superseded | production | **OK** | a QUEUED row with offered_epoch NULL moves to SUPERSEDED | 1 row, offered_epoch=None | `-` | 0 |
| §14.3(f) | 23514-offered_has_epoch | production | **REFUSED** | SQLSTATE 23514: OFFERED with a NULL offered_epoch is refused | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "offered_has_epoch" | `23514` | 5 |
| §14.3(g) | revoked-then-claim | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(g) | revoke-serialises-behind-the-claim | production | **OK** | the claim's FOR SHARE makes a concurrent revoke wait for its commit; both then commit | order=['claim-wrote', 'claim-commits', 'revoke-wrote'] A=commit B=commit | `-` | 414 |
| §14.3(g) | claim-after-revoke | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(h) | 23505-UNIQUE(agent_id) | production | **REFUSED** | SQLSTATE 23505: the same agent_id under another tenant is refused | SQLSTATE 23505: duplicate key value violates unique constraint "agent_identity_agent_id_key" | `23505` | 5 |
| §14.3(h) | 23505-UNIQUE(command_id) | production | **REFUSED** | SQLSTATE 23505: the same command_id under another tenant is refused | SQLSTATE 23505: duplicate key value violates unique constraint "agent_command_command_id_key" | `23505` | 9 |
| §14.3(i) | ack-from-OFFERED | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(i) | ack-from-DELIVERED | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(i) | received-never-terminal | production | **OK** | 1 row; state DELIVERED with delivered_at set and NO outcome — the row is not terminal | 1 row(s), state=DELIVERED, outcome=None, delivered_at set=True | `-` | 0 |
| §14.3(i) | received-is-idempotent | production | **OK** | 0 rows the second time — DUPLICATE, not a second transition | 0 row(s) | `-` | 0 |
| §14.3(i) | 23514-DELIVERED-with-an-outcome | production | **REFUSED** | SQLSTATE 23514: outcome_iff_terminal refuses a DELIVERED row that carries an outcome | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "outcome_iff_terminal" | `23514` | 5 |
| §14.3(j) | 23514-restart-tagged-apply-config | production | **REFUSED** | SQLSTATE 23514: cap_matches_op refuses a restart tagged apply-config | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "cap_matches_op" | `23514` | 6 |
| §14.3(j) | positive-control | production | **OK** | restart + lifecycle is accepted | command fd33904d inserted | `-` | 0 |
| §14.3(k) | outcome-APPLIED | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-FAILED_NO_EFFECT | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-FAILED_ROLLED_BACK | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-APPLY_INDETERMINATE | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-PREFLIGHT_OK | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-PREFLIGHT_REFUSED | production | **OK** | 1 row, state ACKED | 1 row(s), state=ACKED | `-` | 0 |
| §14.3(k) | outcome-REFUSED | production | **OK** | 1 row, state REFUSED | 1 row(s), state=REFUSED | `-` | 0 |
| §14.3(k) | outcome-SUPERSEDED | production | **OK** | 1 row, state SUPERSEDED | 1 row(s), state=SUPERSEDED | `-` | 0 |
| §14.3(k) | 23514-ACKED+REFUSED | production | **REFUSED** | SQLSTATE 23514: state_matches_outcome refuses the pair | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| §14.3(k) | 23514-REFUSED+APPLIED | production | **REFUSED** | SQLSTATE 23514: state_matches_outcome refuses the pair | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| §14.3(k) | 23514-SUPERSEDED+APPLIED | production | **REFUSED** | SQLSTATE 23514: state_matches_outcome refuses the pair | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| §14.3(k) | 23514-ACKED+SUPERSEDED | production | **REFUSED** | SQLSTATE 23514: state_matches_outcome refuses the pair | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| §14.3(l) | returns-nothing-stale epoch | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-non-owning replica | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-lease bound to another certificate | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-expired lease | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-insufficient remaining time | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-missing capability | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-binding not in the lease | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-foreign target | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-revocation epoch moved | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-nothing-no-lease-row | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | returns-and-stamps | production | **OK** | 1 row returned; the command is OFFERED at the caller's epoch with next_notice_at in the future | 1 row(s) state=OFFERED offered_epoch=1 next_notice_future=True | `-` | 0 |
| §14.3(l) | no-renotice-inside-the-interval | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | renotices-after-the-interval | production | **OK** | 1 row | 1 row(s) | `-` | 0 |
| §14.3(l) | never-again-once-acknowledged | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| §14.3(l) | two-pollers-one-row | production | **OK** | exactly one poller receives the command — the one at the live epoch | live=1 stale=0 A=commit B=commit | `-` | 9 |
| §14.3(l) | no-process-state | production | **OK** | connection 1 gets the row; a brand-new connection then gets nothing, from the row alone | first=1 second=0 | `-` | 0 |
| §14.3(n) | retry-returns-its-own-epoch | production | **OK** | the delayed retry reads epoch 1 with current = false, never epoch 2 | epoch=1 current=False | `-` | 0 |
| §14.3(n) | retry-writes-no-row | production | **OK** | still two ledger rows and the session at epoch 2 | ledger rows=2 session epoch=2 | `-` | 0 |
| §14.3(n) | 23505-second-step-2-under-one-id | production | **REFUSED** | SQLSTATE 23505: the PRIMARY KEY refuses a second ledger row for one request id, so no second epoch is minted | SQLSTATE 23505: duplicate key value violates unique constraint "agent_claim_pkey" | `23505` | 5 |
| §14.3(n) | aborted-replay-mints-no-epoch | production | **OK** | the session is still at epoch 2 | session epoch=2 | `-` | 0 |
| §14.3(n) | two-concurrent-identical-claims-mint-one-epoch | production | **OK** | exactly one commits, one ledger row, one epoch | commits=1 ledger rows=1 session epoch=1 outcomes={'B': 'ok', 'A': '23505'} | `-` | 7 |
| §14.3(p) | no-report-store-exists | n-a | **N/A-engine** | the design set defines NO table that could persist a report or its refusal (durable persistence is §13 AGW-20, open) | matching tables in sm_data: none | `-` | 0 |
| §14.3(r) | bounded-batch-and-one-tombstone-per-deleted-row | production | **OK** | retention_batch = 2 deletes exactly two rows and writes exactly two tombstones | batch=2 deleted=2 remaining=3 tombstones=2 | `-` | 0 |
| §14.3(r) | tombstone-refuses-the-replay | production | **OK** | step 0b finds the tombstone row, so the retry is REQUEST_EXPIRED rather than a new claim | 1 tombstone row(s) found | `-` | 0 |
| §14.3(r) | advisory-key-serialises-retry-and-job | production | **OK** | the retry waits for the job's transaction to end before it holds the same request key | order=['job-holds-key', 'job-commits', 'retry-got-key'] A=commit B=commit | `-` | 414 |
| (L1) | reserve-before-any-cp-call | production | **OK** | one PENDING row reserved | 1 row(s); state=PENDING | `-` | 0 |
| (L1) | duplicate-renew-is-one-row | production | **OK** | the second identical renew inserts nothing (ON CONFLICT DO NOTHING) → IN_PROGRESS | 0 row(s); ledger rows=1 | `-` | 0 |
| (L1) | tombstoned-id-is-REQUEST_EXPIRED | production | **OK** | step 0b finds the tombstone before anything is written | 1 row(s) | `-` | 0 |
| (L1) | reservation-is-fenced | planted | **OK** | a reservation at a superseded epoch reserves nothing | 0 row(s) | `-` | 0 |
| (L2) | current-owner-adopts | production | **OK** | the row moves to the new epoch with the new owner and the SAME attempt_no | 1 row(s) epoch=2 owner=pod-b attempt_no=1 | `-` | 0 |
| (L2) | old-owner-adopts-nothing | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L2) | adoption-is-monotonic | production | **OK** | a second adoption at the same epoch writes nothing (lg.fencing_epoch < s.fencing_epoch) | 0 row(s) | `-` | 0 |
| (L3) | ten-retries-exactly-one-takeover | production | **OK** | exactly one retry moves the attempt to 2; the other nine write nothing; still one ledger row | winners=[('pod-1', 1)] attempt_no=2 ledger rows=1 | `-` | 0 |
| (L3) | not-takeable-before-the-new-deadline | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L3) | attempt-cap-bounds-the-takeovers | production | **OK** | lg.attempt_no < $takeover_attempts refuses the takeover once the cap is reached | 0 row(s), attempt_no still 3 | `-` | 0 |
| (L4) | stale-completion-writes-nothing | production | **OK** | 3a (the fence) returns 0 rows and 3c writes NO projection and NO ledger change: the row is still PENDING at its original attempt_no with completed_at NULL, and agent_lease is empty | 3a=0 3b=1 (3b is the ownership lock on the ledger row, not the fence) 3c=0 state=PENDING attempt_no=1 completed_at=None agent_lease rows=0 | `-` | 0 |
| (L4) | cross-agent-completion-equal epochs | production | **OK** | 3b returns 0 rows; nothing is projected and B's ledger row is untouched | 3b=0 3c=0 state=PENDING agent_lease rows=0 | `-` | 0 |
| (L4) | cross-agent-completion-different epochs | production | **OK** | 3b returns 0 rows; nothing is projected and B's ledger row is untouched | 3b=0 3c=0 state=PENDING agent_lease rows=0 | `-` | 0 |
| (L4) | cross-agent-completion-equal-epochs-3b | production | **OK** | the extracted 3b returns 0 rows | 0 row(s) | `-` | 0 |
| (L4) | cross-agent-completion-equal-epochs-3b | planted | **OK** | without 3b's agent predicate A's session locks B's request row (the fence is real) | 1 row(s) returned | `-` | 0 |
| (L4) | cross-agent-completion-equal-epochs-3b | restored | **OK** | the extracted 3b returns 0 rows again | 0 row(s) | `-` | 0 |
| (L4) | completion-from-a-non-owner | production | **OK** | 3b returns 0 rows for a caller that is not attempt_owner | 3b=0 | `-` | 0 |
| (L5) | older-grant-arriving-second-is-SUPERSEDED_ON_ARRIVAL | production | **OK** | the newer grant stays projected; the older completes SUPERSEDED_ON_ARRIVAL | newer=PROJECTED older=SUPERSEDED_ON_ARRIVAL projected=newer | `-` | 0 |
| (L5) | equal-issued_at-resolved-by-grant_seq | production | **OK** | exactly one PROJECTED and one SUPERSEDED_ON_ARRIVAL | r1(seq low)=SUPERSEDED_ON_ARRIVAL r2(seq high)=PROJECTED | `-` | 0 |
| (L5) | invalid-grant-lands-FAILED-with-nothing-projected | production | **OK** | FAILED/GRANT_INVALID, no projection, and failed_has_no_grant leaves no grant material | FAILED/GRANT_INVALID; agent_lease rows=0; lease_jti=None | `-` | 0 |
| (L6) | positive-completion-PENDING-to-COMPLETED | production | **OK** | the completion branch is admitted | COMPLETED | `-` | 0 |
| (L6) | positive-completion-PENDING-to-FAILED | production | **OK** | the completion branch is admitted | FAILED | `-` | 0 |
| (L6) | positive-takeover-branch | production | **OK** | attempt_no + 1 past the deadline is admitted | 1 row(s) | `-` | 0 |
| (L6) | positive-epoch-transfer-branch | production | **OK** | a strictly higher epoch at the same attempt_no is admitted | 1 row(s) | `-` | 0 |
| (L6) | AGW11-write-to-a-COMPLETED-row | production | **REFUSED** | SQLSTATE AGW11: the named branch raises | SQLSTATE AGW11: lease request 0b473f91-66a0-424b-b5a8-fb458ecda2de is terminal (COMPLETED) and immutable | `AGW11` | 5 |
| (L6) | AGW10-fingerprint-rewrite | production | **REFUSED** | SQLSTATE AGW10: the named branch raises | SQLSTATE AGW10: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 identity columns are immutable | `AGW10` | 5 |
| (L6) | AGW10-identity-column-write | production | **REFUSED** | SQLSTATE AGW10: the named branch raises | SQLSTATE AGW10: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 identity columns are immutable | `AGW10` | 5 |
| (L6) | AGW12-completion-moves-attempt_no | production | **REFUSED** | SQLSTATE AGW12: the named branch raises | SQLSTATE AGW12: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 completion may not move the attempt controls (attempt 1->2, owner pod-a->pod-a, deadline 2026-09-11 06:24:45. | `AGW12` | 5 |
| (L6) | AGW12-completion-moves-attempt_owner | production | **REFUSED** | SQLSTATE AGW12: the named branch raises | SQLSTATE AGW12: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 completion may not move the attempt controls (attempt 1->1, owner pod-a->pod-z, deadline 2026-09-11 06:24:45. | `AGW12` | 5 |
| (L6) | AGW12-completion-moves-fencing_epoch | production | **REFUSED** | SQLSTATE AGW12: the named branch raises | SQLSTATE AGW12: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 completion may not move the attempt controls (attempt 1->1, owner pod-a->pod-a, deadline 2026-09-11 06:24:45. | `AGW12` | 6 |
| (L6) | AGW13-takeover-before-the-deadline | production | **REFUSED** | SQLSTATE AGW13: the named branch raises | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns unchanged) | `AGW13` | 7 |
| (L6) | AGW13-takeover-lowers-the-epoch | production | **REFUSED** | SQLSTATE AGW13: the named branch raises | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->0, result columns unchanged) | `AGW13` | 5 |
| (L6) | AGW14-transfer-that-preserves-the-epoch | production | **REFUSED** | SQLSTATE AGW14: the named branch raises | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->1, result columns unchanged) | `AGW14` | 5 |
| (L6) | AGW14-transfer-that-lowers-the-epoch | production | **REFUSED** | SQLSTATE AGW14: the named branch raises | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->0, result columns unchanged) | `AGW14` | 7 |
| (L6) | AGW15-attempt_no-moves-by-two | production | **REFUSED** | SQLSTATE AGW15: the named branch raises | SQLSTATE AGW15: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 admits no such transition (state PENDING->PENDING, attempt 1->3) | `AGW15` | 5 |
| (L6) | AGW13-takeover-writes-outcome | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| (L6) | AGW14-transfer-writes-outcome | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| (L6) | AGW13-takeover-writes-lease_jti | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 6 |
| (L6) | AGW14-transfer-writes-lease_jti | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| (L6) | AGW13-takeover-writes-issued_at | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 4 |
| (L6) | AGW14-transfer-writes-issued_at | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 6 |
| (L6) | AGW13-takeover-writes-expires_at | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| (L6) | AGW14-transfer-writes-expires_at | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 4 |
| (L6) | AGW13-takeover-writes-grant_payload | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| (L6) | AGW14-transfer-writes-grant_payload | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 4 |
| (L6) | AGW13-takeover-writes-completed_at | production | **REFUSED** | SQLSTATE AGW13: a takeover may write no result column | SQLSTATE AGW13: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 takeover refused (deadline 2026-09-11 06:24:45.575565+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| (L6) | AGW14-transfer-writes-completed_at | production | **REFUSED** | SQLSTATE AGW14: a transfer may write no result column | SQLSTATE AGW14: lease request 6683cb75-a0b8-41c2-8791-b86eb3dadff1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| (L6) | 23514-pending_has_no_result-lease_jti | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses a result on a PENDING row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 6 |
| (L6) | 23514-pending_has_no_result-issued_at | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses a result on a PENDING row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 7 |
| (L6) | 23514-pending_has_no_result-expires_at | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses a result on a PENDING row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 7 |
| (L6) | 23514-pending_has_no_result-grant_payload | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses a result on a PENDING row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 8 |
| (L6) | 23514-pending_has_no_result-outcome | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses a result on a PENDING row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 6 |
| (L6) | 23514-failed_has_no_grant-lease_jti | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses grant material on a FAILED row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| (L6) | 23514-failed_has_no_grant-issued_at | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses grant material on a FAILED row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| (L6) | 23514-failed_has_no_grant-expires_at | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses grant material on a FAILED row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| (L6) | 23514-failed_has_no_grant-grant_payload | planted | **REFUSED** | SQLSTATE 23514: with the trigger disabled the CHECK still refuses grant material on a FAILED row | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 4 |
| (L6) | AGW11-write-to-a-COMPLETED-row | planted | **OK** | with the trigger disabled a terminal row is writable (the trigger is the artifact) | 1 row(s) updated | `-` | 0 |
| (L6) | AGW11-write-to-a-COMPLETED-row | restored | **REFUSED** | SQLSTATE AGW11: the re-enabled trigger refuses it again | SQLSTATE AGW11: lease request 0b473f91-66a0-424b-b5a8-fb458ecda2de is terminal (COMPLETED) and immutable | `AGW11` | 5 |
| (L7) | issue-refused-when-a-lease-row-exists | production | **OK** | step 1's NOT EXISTS clause reserves nothing for a kind='issue' request | 0 row(s) | `-` | 0 |
| (L7) | issue-refused-even-when-the-lease-has-EXPIRED | production | **OK** | the row's EXISTENCE is the arbiter, not its window | 0 row(s) | `-` | 0 |
| (L7) | two-concurrent-first-issues-with-different-ids | production | **OK** | agent_lease_grant_issue_once admits exactly one; the other is refused 23505 | commits=1 issue rows=1 outcomes={'I1': 'ok', 'I2': '23505'} sqlstates=['', '23505'] | `-` | 16 |
| (L7) | post-retention-a-second-issue-IS-admitted | production | **OK** | MEASURED, and the design says so (§12 limit 19a): once retention has deleted the FAILED issue row and no agent_lease row was ever projected, a NEW request id reserves a second nonce-free issue — the tombstone is keyed by the OLD id and does not match | 1 row(s) reserved | `-` | 0 |
| (L8) | current-adds-no-ledger-row | production | **OK** | `current` returns the projection and the ledger is byte-identical | rows=1 ledger before=0 after=0 | `-` | 0 |
| (L8) | current-on-a-missing-projection | production | **OK** | 0 rows and nothing written | 0 row(s), ledger rows=0 | `-` | 0 |
| (L9) | retry-waits-and-then-sees-REQUEST_EXPIRED | production | **OK** | the retry blocks on the same request key until the job commits, then finds no ledger row and exactly one tombstone — REQUEST_EXPIRED, never a second ledger row | order=['job-locked-1', 'job-commits', 'retry-got-key', 'retry-sees-0', 'retry-tombstone-1'] | `-` | 416 |
| (L9) | never-a-second-ledger-row | production | **OK** | the ledger holds no row for that id | 0 row(s) in agent_lease_grant | `-` | 0 |
| (L10) | nextval-succeeds-with-the-grant | production | **OK** | a value is returned | nextval = 1 | `-` | 0 |
| (L10) | nextval-without-the-grant | planted | **REFUSED** | SQLSTATE 42501: an INSERT grant alone cannot evaluate nextval() — the F9/R7 finding | SQLSTATE 42501: permission denied for sequence agent_lease_grant_seq | `42501` | 3 |
| (L10) | nextval-succeeds-with-the-grant | restored | **OK** | a value is returned | nextval = 1 | `-` | 0 |
| (L10) | OWNED-BY-drops-the-sequence | planted | **OK** | dropping the table drops the sequence | 0 sequence(s) left | `-` | 0 |
| (L10) | OWNED-BY-drops-the-sequence | restored | **OK** | the re-applied DDL re-creates it | 1 sequence(s) | `-` | 0 |
| (L11) | two-concurrent-registrations-land-AT-the-cap | production | **OK** | A2's binding-wide cap-row FOR UPDATE serialises them: exactly one lands, the other is REPLICA_CAP_EXCEEDED, and the counter stops AT the cap | landed=['pod-y'] refused=[('pod-x', 2)] active_replicas=2 live replicas=2 A=commit B=commit | `-` | 12 |
| (L11) | two-concurrent-registrations-land-AT-the-cap | planted | **OK** | without A2's FOR UPDATE both readers see CAP − 1 and both register: the counter passes the cap | landed=['pod-x', 'pod-y'] active_replicas=3 (cap=2) | `-` | 14 |
| (L11) | two-concurrent-registrations-land-AT-the-cap | restored | **OK** | the extracted A2 stops the counter at the cap again | active_replicas=2 | `-` | 12 |
| (L12) | 23505-agent_replica_one_active | production | **REFUSED** | SQLSTATE 23505: the partial unique index refuses a second ACTIVE incarnation of one replica | SQLSTATE 23505: duplicate key value violates unique constraint "agent_replica_one_active" | `23505` | 4 |
| (L12) | restart-reuses-replica_id-and-mints-one-incarnation | production | **OK** | exactly one active incarnation, two in total, and the stable id is unchanged | active=1 total=2 replica rows=1 | `-` | 0 |
| (L12) | re-report-of-the-same-incarnation-is-a-watermark-move-only | production | **OK** | 1 row, the watermark advances, no row is minted | 1 row(s), last_seen_report=4, total incarnations=2 | `-` | 0 |
| (L12) | out-of-order-report-changes-nothing | production | **OK** | 0 rows and the watermark does not move backwards | 0 row(s), last_seen_report=4 | `-` | 0 |
| (L12) | out-of-order-report-changes-nothing | planted | **OK** | without the predicate the stale report drags the watermark backwards | 1 row(s), last_seen_report=2 | `-` | 0 |
| (L12) | out-of-order-report-changes-nothing | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L13) | idle-replica-retired-and-the-cap-released | production | **OK** | the replica is retired and active_replicas is decremented in the SAME transaction | retired=True active_replicas=0 | `-` | 0 |
| (L13) | churn-beyond-the-cap-succeeds | production | **OK** | 4 distinct replica_refs register serially with retirement in between | 4 registrations landed | `-` | 0 |
| (L13) | retired-incarnations-pruned | production | **OK** | the retired incarnation past registry_retention is deleted | 1 row(s) deleted | `-` | 0 |
| (L13) | series-relation-is-present | production | **OK** | sm_data.series exists, bootstrapped from V001__telemetry_schema.sql | present: 1 | `-` | 0 |
| (L13) | stable-delete-succeeds-with-no-referencing-series | production | **OK** | with no incarnation row and no series row naming the replica, the DELETE removes it | 1 row(s) deleted | `-` | 0 |
| (L13) | stable-delete-withheld-by-a-same-tenant-series | production | **OK** | a series row in the replica's OWN tenant with source_key = replica_id::text withholds the delete — a no-op, not an error | 0 row(s) deleted, replica rows remaining=1 | `-` | 0 |
| (L13) | stable-delete-ignores-a-foreign-tenant-series | production | **OK** | a series row carrying the same source_key in ANOTHER tenant does not withhold the delete — the predicate is tenant-scoped | foreign series rows=1, 1 row(s) deleted, replica rows remaining=0 | `-` | 0 |
| (L13) | registration-and-the-sweep-both-commit | production | **OK** | both commit and neither raises 40P01 — both take the binding's cap row first (§7 clause 2) | REG=commit/ SWEEP=commit/ | `-` | 431 |
| (L13) | registration-and-the-sweep-both-commit | planted | **OK** | without the cap-row lock the two take agent_replica_cap and agent_replica in opposite orders and one aborts with 40P01 | REG=commit/ SWEEP=error/40P01 | `40P01` | 1028 |
| (L13) | registration-and-the-sweep-both-commit | restored | **OK** | with step 1 back, both commit again | REG=commit SWEEP=commit | `-` | 432 |
| (L13) | two-concurrent-§5.1-sweeps-are-safe | production | **OK** | both commit with no 40P01; the idle replica is retired exactly once; the LIVE replica is not retired; active_replicas is decremented exactly once and still reconciles with the non-retired row count | commit/- commit/- idle_retired=True live_retired=False active_replicas=1 non_retired_rows=1 | `-` | 429 |
| (L14) | every-§3-and-§4-statement-runs-as-sm_dp_runtime | production | **OK** | all 23 statements execute under the §7.4/§7 grants | all succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.0 silence sweep | production | **OK** | succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the migrator's exclusivity here is scheduling, not privilege | executed, 0 row(s) affected | `-` | 0 |
| (L14) | migrator-may-run-§5.0 silence sweep | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.1 step 1 cap lock | production | **OK** | succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the migrator's exclusivity here is scheduling, not privilege | executed, 1 row(s) affected | `-` | 0 |
| (L14) | migrator-may-run-§5.1 step 1 cap lock | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.1 step 3 idle sweep | production | **OK** | succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the migrator's exclusivity here is scheduling, not privilege | executed, 0 row(s) affected | `-` | 0 |
| (L14) | migrator-may-run-§5.1 step 3 idle sweep | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | 42501-as-runtime-§5.2 prune incarnations (a DELETE — genuinely refused) | production | **REFUSED** | SQLSTATE 42501: the DCL refuses it: no DELETE on any of the eleven tables, no tombstone INSERT | SQLSTATE 42501: permission denied for table agent_replica_incarnation | `42501` | 4 |
| (L14) | migrator-may-run-§5.2 prune incarnations (a DELETE — genuinely refused) | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.3 step 1 batch | production | **OK** | (L14) states this one SUCCEEDS as sm_dp_runtime | succeeded | `-` | 0 |
| (L14) | migrator-may-run-§5.3 step 1 batch | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | 42501-as-runtime-§5.3 step 2 delete + tombstone (DELETE + tombstone INSERT — genuinely refused) | production | **REFUSED** | SQLSTATE 42501: the DCL refuses it: no DELETE on any of the eleven tables, no tombstone INSERT | SQLSTATE 42501: permission denied for table agent_request_tombstone | `42501` | 5 |
| (L14) | migrator-may-run-§5.3 step 2 delete + tombstone (DELETE + tombstone INSERT — genuinely refused) | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.4 step 1 batch | production | **OK** | succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the migrator's exclusivity here is scheduling, not privilege | executed, 0 row(s) affected | `-` | 0 |
| (L14) | migrator-may-run-§5.4 step 1 batch | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L14) | runtime-may-run-§5.4 step 2 terminalise | production | **OK** | succeeds as sm_dp_runtime — the DCL cannot refuse it, per corrected (L14): the migrator's exclusivity here is scheduling, not privilege | executed, 0 row(s) affected | `-` | 0 |
| (L14) | migrator-may-run-§5.4 step 2 terminalise | production | **OK** | the migrator executes it | succeeded | `-` | 0 |
| (L15) | 23514-incarnation_window | production | **REFUSED** | SQLSTATE 23514: retired_at must be strictly after registered_at, so the acceptance interval is never empty | SQLSTATE 23514: new row for relation "agent_replica_incarnation" violates check constraint "incarnation_window" | `23514` | 4 |
| (L15) | sample-admission-interval | n-a | **N/A-engine** | a sample labelled with a retired incarnation_id commits iff ts ∈ [registered_at, retired_at) — design.md §6.1's rule, tested by §8 row 44 | no admission statement exists in this design set to execute (sm_data.metrics_raw present from V001: 1; the rule that reads it is the DM-D*/BE-D* families', and no document here carries it as SQL) | `-` | 0 |
| (L16) | abandoned-request-terminalised-live-one-skipped | production | **OK** | the abandoned row becomes FAILED/TIMEOUT through the trigger's completion branch with its attempt controls unmoved; the live row is untouched | batch=1 terminalised=1 dead=FAILED/TIMEOUT attempt_no=1 live=PENDING | `-` | 0 |
| (L16) | no-grant-material-on-the-swept-row | production | **OK** | failed_has_no_grant leaves the terminal row with a code and nothing else | lease_jti=None | `-` | 0 |
| (L16) | sweep-concurrent-with-a-step-3-completion-same-request-id | production | **OK** | both commit with no 40P01 and the request has EXACTLY ONE outcome: step 3 holds the ledger row, so the sweep's step 2 waits and then re-evaluates state = 'PENDING', which no longer holds — it writes 0 rows and the completion stands | STEP3=commit/- SWEEP=commit/- 3c wrote 1 row(s), sweep step 2 wrote 0 row(s), state=COMPLETED outcome=PROJECTED agent_lease rows=1 | `-` | 524 |
| (L16) | sweep-wins-before-step-3-completion | production | **OK** | both commit with no 40P01 and exactly one outcome: the sweep terminalises first; step 3b then returns 0 rows, step 3c is not reached and no lease is projected | SWEEP=commit/- STEP3=commit/- sweep step 2 wrote 1 row(s), 3b returned 0 row(s), 3c wrote 0 row(s), state=FAILED outcome=TIMEOUT agent_lease rows=0 | `-` | 519 |
| (L17) | stale-B1 handoff ownership | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B1 handoff ownership | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B1 handoff ownership | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B2 mint / un-retire | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B2 mint / un-retire | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B2 mint / un-retire | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B2 cap counter | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B2 cap counter | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B2 cap counter | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, incumbent form | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, incumbent form | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, incumbent form | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, handoff form | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, handoff form | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B3 liveness refresh, handoff form | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B4 retire the activation | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B4 retire the activation | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B4 retire the activation | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | stale-B5 insert the new activation | production | **OK** | 0 rows | 0 row(s) | `-` | 0 |
| (L17) | stale-B5 insert the new activation | planted | **OK** | without the fence the superseded report's write lands | 1 row(s) | `-` | 0 |
| (L17) | stale-B5 insert the new activation | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L17) | registry-byte-identical-after-a-stale-report | production | **OK** | all seven fenced writes together move nothing: agent_replica and agent_replica_incarnation hash the same and the cap counter is unchanged | replica md5 3794a592→3794a592, incarnation md5 c9fb20d6→c9fb20d6, active_replicas=1 | `-` | 0 |
| (L17) | A0-refuses-a-revoked-identity | production | **OK** | A0 returns 0 rows, so nothing below it runs | 0 row(s) | `-` | 0 |
| (L17) | A0-refuses-a-revoked-identity | planted | **OK** | without it a revoked agent's report proceeds past A0 | 1 row(s) | `-` | 0 |
| (L17) | A0-refuses-a-revoked-identity | restored | **OK** | 0 rows again | 0 row(s) | `-` | 0 |
| (L18) | L18-P1-claim-vs-registration | production | **OK** | both commit; neither raises 40P01; each takes agent_identity before agent_session | CLAIM=commit/- REGISTRATION=commit/- | `-` | 8 |
| (L18) | L18-N1-claim-vs-registration | planted | **OK** | 40P01 in EXACTLY ONE of the two transactions inside a 5 s lock_timeout, the other commits | FIXTURE=commit/- PRODUCTION=error/40P01 in 1013 ms | `40P01` | 1013 |
| (L18) | L18-P2-claim-vs-lease-reservation | production | **OK** | both commit, no 40P01; a reservation whose epoch was superseded reserves nothing | CLAIM=commit/- RESERVATION=commit/- ledger rows 0→0 session epoch=2 | `-` | 12 |
| (L18) | L18-N2-claim-vs-lease-reservation | planted | **OK** | 40P01 in EXACTLY ONE of the two transactions inside a 5 s lock_timeout, the other commits | FIXTURE=commit/- PRODUCTION=error/40P01 in 1013 ms | `40P01` | 1013 |
| (L18) | L18-P3-step1-retry-vs-step3 | production | **OK** | (L18-P3) asserts BOTH COMMIT and neither raises 40P01, in every one of 5 concurrent repetitions, with the retry writing no second ledger row | #1 RETRY=commit/- STEP3=commit/-; #2 RETRY=commit/- STEP3=commit/-; #3 RETRY=commit/- STEP3=commit/-; #4 RETRY=commit/- STEP3=commit/-; #5 RETRY=commit/- STEP3=commit/- \| ledger rows for the last id=1 | `-` | 14 |
| (L18) | L18-P3-mechanism-probe-1 | production | **REFUSED** | SQLSTATE 55P03: step 3c WAITS on a FOR UPDATE held on agent_identity — the pre-S10/F4 mode | SQLSTATE 55P03: canceling statement due to lock timeout | `55P03` | 2013 |
| (L18) | L18-P3-mechanism-probe-3 | production | **OK** | step 3c does NOT wait on an identity row held FOR NO KEY UPDATE — KEY SHARE does not conflict with NO KEY UPDATE (PG 9.3 release notes; PG 14 Table 13.3) | completed without waiting, 1 row(s) | `-` | 0 |
| (L18) | L18-P3-mechanism-probe-2 | production | **REFUSED** | SQLSTATE 55P03: step 1's ON CONFLICT DO NOTHING WAITS on a ledger row an in-flight transaction updated | SQLSTATE 55P03: canceling statement due to lock timeout | `55P03` | 2011 |
| (L18) | L18-N3-step1-retry-vs-step3 | planted | **OK** | 40P01 in EXACTLY ONE of the two transactions inside a 5 s lock_timeout, the other commits | FIXTURE=commit/- PRODUCTION=error/40P01 in 1018 ms | `40P01` | 1018 |
| (L18) | L18-P1-claim-vs-registration | restored | **OK** | the production pair still commits after the planted fixtures ran | CLAIM=commit REGISTRATION=commit | `-` | 10 |
