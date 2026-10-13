# PostgreSQL-14 execution evidence — the Agent Gateway design set (`AGW-D*`)

**Verdict: executable evidence clean, obligation set partial — every leg is `OK`, `REFUSED` or
`N/A-engine`; the run exits 0; nothing is ratified.** The six tables of [`design.md`](../design.md)
§5.2, the five of
[`lease-and-registry-sketch.md`](../lease-and-registry-sketch.md) §2, both triggers and both `GRANT`
blocks execute verbatim from the extracted bytes on PostgreSQL 14.24, and **224 legs** ran: **162
`OK`, 60 `REFUSED`, 2 `N/A-engine`, 0 `FAIL`, 0 `BLOCKED-dependency`**. The one leg the previous run
recorded as blocked — sketch §5.2's stable-replica `DELETE` — **was never blocked**: its dependency
`sm_data.series` is created by the repo's telemetry migration, which S10 missed because it searched
`docs/` only. That claim is retracted, the migration is now a pinned bootstrap input, and the
statement is measured by three legs instead of one excuse. **Nothing here ratifies anything.**
`AGW-D1`–`AGW-D16` stay `PROPOSED`, AGW-17 through AGW-21 stay open, no product code exists, and §6
says what this evidence does not establish.

---

## 1. Header

| Item | Value |
|---|---|
| Commit under test | **`8551e5c`** (`8551e5cb6acf578d83d83fc13714844596aeeefa`) — "docs(agent-gateway): correct S12 review findings" |
| Documents | [`docs/design/backend/agent-gateway/design.md`](../design.md) §5.2 and §7.4 · [`docs/design/backend/agent-gateway/lease-and-registry-sketch.md`](../lease-and-registry-sketch.md) §2–§7 |
| Bootstrap, **in the order it is applied** | **1.** [`docs/design/backend/eda-walking-skeleton.md`](../../eda-walking-skeleton.md) lines **216–253** (Phase 0), md5 **`768e195b7c17861d97e402c5318be51b`** ✅ · **2.** [`backend/src/main/resources/db/migration/V001__telemetry_schema.sql`](../../../../../backend/src/main/resources/db/migration/V001__telemetry_schema.sql), **whole file**, md5 **`d8e3948552258e17d2c51e440afcbe55`** ✅ · **3.** the same skeleton, lines **254–533** (Phase 1), md5 **`5c28fa14868fbd72cbec158193e6d86f`** ✅ — all three md5-pinned in `extract.py`, which aborts on a mismatch; all three matched on first extraction |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit` (recorded verbatim by `select version()`) |
| Container | `agw-pg14-s12-full`, image `postgres:14.24`, host port 55442, created `--fresh` (an empty cluster); removed after the run |
| Harness | [`pg14-harness/`](pg14-harness/) — `extract.py`, `run.py`, `requirements.txt`, `README.md` |
| Run output | [`runs/20260911T062308Z-8551e5c/`](runs/20260911T062308Z-8551e5c/) — `results.md`, `results.json`, `log.txt` |
| Started / finished | `2026-09-11T06:23:36.043019+00:00` → `2026-09-11T06:24:02.816099+00:00` (27 s) |
| Python | `psycopg[binary]==3.3.5` (`requirements.txt`), which declares `Requires-Python: >=3.10`; run on a CPython 3.14 venv |
| Exit status | **0** |

**Why the telemetry migration is a bootstrap input, and why it sits between Phase 0 and Phase 1.**
Sketch §5.2's stable-replica `DELETE` reads `sm_data.series`, and that relation is created by a
**migration**, not by a design document: `V001__telemetry_schema.sql`, whose own lines 3–5 declare it
*"THE SOURCE OF TRUTH FOR THE TELEMETRY SCHEMA"*. Its position is forced, not chosen:

* Phase 0's `CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner` carries **no** `IF NOT EXISTS`, so
  anything that creates the schema before it makes Phase 0 fail. V001's own
  `CREATE SCHEMA IF NOT EXISTS sm_data` is therefore a notice-only no-op here — *"Do nothing (except
  issuing a notice) if a schema with the same name already exists"* (PG 14 docs, `sql-createschema`;
  verified 2026-09-11) — and the schema keeps Phase 0's `sm_dp_owner` ownership.
* V001 is the repo's **only** Flyway `V`-file, so Flyway runs it before any later migration; the
  walking skeleton's Phase 1 is still prose and would become a later one.
* V001 sets `search_path` to `sm_data` and creates unqualified, so its tables land there — *"it is
  also the schema in which new tables will be created if the `CREATE TABLE` command does not specify
  a schema name"* (PG 14 docs, `ddl-schemas`; verified 2026-09-11) — and under the harness-added
  `SET ROLE sm_dp_owner` they are born owned by `sm_dp_owner`.
* **Consequence, recorded not hidden:** Phase 1's `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES
  TO sm_dp_runtime` runs afterwards and *"does not affect privileges assigned to already-existing
  objects"* (PG 14 docs, `sql-alterdefaultprivileges`; verified 2026-09-11), so `sm_dp_runtime` holds
  **no** privilege on `placement` or `series`. Correct and deliberate: those grants belong to the
  `DM-D*`/`BE-D*` owners, and §5.2's `DELETE` is the migrator's.

### Commands

```sh
cd docs/design/backend/agent-gateway/evidence/pg14-harness
python3 extract.py --commit 8551e5c                  # slices 8551e5c into extracted/ + extract.json
<venv>/bin/python run.py --fresh --container agw-pg14-s12-full --port 55442 \
  --out ../runs/20260911T062308Z-8551e5c
```

`extract.py` has **no default commit** since S11: `--commit <sha>` is required unless `--worktree` is
given, because a default silently slices the wrong line ranges the moment the design is edited.
`run.py` exits non-zero if any leg is `FAIL` **or** `BLOCKED-dependency`. This run exited **0**.

### Roles, by phase

| Phase | Role | Note |
|---|---|---|
| Bootstrap Phase 0 | `postgres` | the cluster's bootstrap superuser; Phase 0's last statement (`REVOKE CREATE ON SCHEMA public FROM PUBLIC`) can be executed by nobody else |
| Harness-only credentials | `postgres` | ⚠️ **not part of the design:** `ALTER ROLE sm_dp_runtime PASSWORD …` and `ALTER ROLE sm_dp_migrator PASSWORD …`, because Phase 0 deliberately carries no credentials and the image's `pg_hba.conf` requires scram over TCP. Logged as `HARNESS-ONLY` (`log.txt` lines 6–10). |
| Harness-only database grant | `postgres` | ⚠️ **not part of the design, and it records a real gap:** `GRANT CREATE ON DATABASE postgres TO sm_dp_owner`, because V001's `CREATE SCHEMA IF NOT EXISTS` needs `CREATE` on the database and Phase 0 grants it to no `sm_dp_*` role. Logged as `HARNESS-ONLY` with the reason (`log.txt` lines 11–21). See §5.1. |
| Telemetry migration (V001) | `sm_dp_migrator` with a **harness-added** leading `SET ROLE sm_dp_owner;` | which is Flyway's `initSql`, the convention the walking skeleton's Phase 1 states for itself; everything after that line is verbatim |
| Bootstrap Phase 1 | `sm_dp_migrator` | as Flyway would run it |
| The four design DDL/DCL groups | `sm_dp_migrator` with a **harness-added** leading `SET ROLE sm_dp_owner;` | which is what §2, §5.2 and §7 say the migrator does; logged as `HARNESS-ADDED first line`, everything after it verbatim |
| Runtime legs | `sm_dp_runtime` over TCP | the application datasource |
| Migrator legs, and fixtures | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | fixtures are harness rows, never design material |
| Catalog inspection | `postgres` | reads only |

### Extracted blocks — line range and md5

Every block is sliced out of `git show 8551e5c:<path>` by line range. `extract.py` never edits,
reformats or repairs a block; `run.py` supplies fixture **values** for the documents' own
`$placeholders` and nothing else.

| Block | md5 |
|---|---|
| `design §5.2#1 L389-544` | `e5be6e887dc2090614c1f237b1b3e499` |
| `design §5.2#2 L557-598` | `4c4dc9c27fd2d6f59b2f563f28be2b56` |
| `design §5.2#3 L604-637` | `df02e4f394539a314f5315f2b69860f7` |
| `design §5.2#4 L644-675` | `708156fec62a5390f7f46e3646ced715` |
| `design §7.4#1 L847-864` | `6d4c03f8dbafedd137b0dcfc73e075b6` |
| `design §7.4#2 L870-886` | `dc0221a787d22cf828e9625c8761bc68` |
| `sketch §2.1#1 L42-186` | `70cc50481add4dc0dca72ad1cdb73364` |
| `sketch §2.2#1 L230-267` | `bcb812681a35023750b1a50eec01df0e` |
| `sketch §2.3#1 L275-311` | `58abfb34c9f1bfd604deaffe82fc7473` |
| `sketch §2.4#1 L317-328` | `239668f9af3f3b26c1af3a8a8f89b269` |
| `sketch §2.5#1 L334-409` | `62262ed74ae0846b89662d6b558741f8` |
| `sketch §3.1#1 L425-532` | `b88620f57aa451b40157886ee343c2d5` |
| `sketch §3.3#1 L552-595` | `52511d41647e14ed9a2a5268bb5ba4b6` |
| `sketch §3.3#2 L599-651` | `19edf27508059b70284579d4238c8918` |
| `sketch §3.4#1 L665-704` | `e59510e3ab28c0f83f4eda9949083a64` |
| `sketch §4.1#1 L748-833` | `35ec346eee9b00c94ed306f1a2c3a35f` |
| `sketch §4.1#2 L849-891` | `964f963382d87cc58953b019934bfbf8` |
| `sketch §4.2#1 L909-984` | `bcc35d922e04e07a79d9ba947a3477b8` |
| `sketch §5.0#1 L1006-1017` | `db33284fcf37d605436129f401d64b8a` |
| `sketch §5.1#1 L1039-1065` | `3130f2cd842a260fb90c622181e1c6a4` |
| `sketch §5.2#1 L1075-1088` | `191ae315d21818b9c5b0fb937b80a1b6` |
| `sketch §5.3#1 L1104-1134` | `2df16308e04aeb49f6f2fa42d6db2ff3` |
| `sketch §5.4#1 L1162-1183` | `67eab03e3d227246f9f526b0e1a2c3d1` |
| `sketch §6#1 L1204-1204` | `209dcb8c75419df5894ed483a0c1325d` |
| `sketch §7#1 L1229-1254` | `b0111391ed19434ef8c5e73550d01863` |

---

## 2. Bootstrap and the four design groups

All three bootstrap slices matched their pinned md5 before a byte was executed; no range was
adjusted. Each group ran in **one transaction** (`psql -1 -v ON_ERROR_STOP=1`), and every one
executed clean — no statement was retried, reordered or amended.

| # | Group | Blocks | Role | Result |
|---|---|---|---|---|
| 0 | bootstrap Phase 0 | skeleton L216–253 | `postgres` | clean |
| — | harness-only role passwords | — | `postgres` | clean (**not design material**) |
| — | harness-only `GRANT CREATE ON DATABASE` | — | `postgres` | clean (**not design material**; §5.1) |
| 1 | telemetry migration V001 — `placement`, `binding_policy`, `series`, `metrics_raw` (+2 partitions), `metrics_rollup_1h` (+1), `events_raw` (+1) | V001, whole file | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | clean |
| 2 | bootstrap Phase 1 | skeleton L254–533 | `sm_dp_migrator` | clean |
| 3 | design §5.2 DDL — six tables + `agent_session_epoch_only_rises` | `design §5.2#1 L389-544` | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | clean |
| 4 | sketch §2 DDL — five tables + `agent_lease_grant_terminal_once` | `sketch §2.1#1 L42-186`, `§2.2#1 L230-267`, `§2.3#1 L275-311`, `§2.4#1 L317-328`, `§2.5#1 L334-409` | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | clean |
| 5 | design §7.4 DCL — six tables | `design §7.4#1 L847-864` | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | clean |
| 6 | sketch §7 DCL — five tables + the sequence | `sketch §7#1 L1229-1254` | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | clean |

### Objects, as the catalog reports them

`log.txt` lines 38–40, read back as `postgres`:

* **26 tables in `sm_data`**, in three groups:
  * the design set's **eleven** — `agent_claim`, `agent_command`, `agent_fleet_epoch`,
    `agent_identity`, `agent_lease`, `agent_lease_grant`, `agent_replica`, `agent_replica_cap`,
    `agent_replica_incarnation`, `agent_request_tombstone`, `agent_session`;
  * the telemetry migration's **ten** — `placement`, `binding_policy`, `series`, `metrics_raw`,
    `metrics_raw_2026_08`, `metrics_raw_2026_09`, `metrics_rollup_1h`, `metrics_rollup_1h_2026_08`,
    `events_raw`, `events_raw_2026_08`;
  * the backend Phase 1's **five** — `apply_attempt`, `apply_request`, `apply_scope`,
    `apply_step_intent`, `placement_watermark`.
* **5 non-internal triggers**, of which **two are this design set's**:
  `agent_lease_grant.agent_lease_grant_terminal_once` and
  `agent_session.agent_session_epoch_only_rises`. The other three are the backend's
  (`apply_request_terminal_guard`, `apply_scope_monotonic`, `placement_watermark_monotonic`). V001
  carries none.
* **34 indexes across the eleven `agent_*` tables** — `agent_claim` 3, `agent_command` 5,
  `agent_fleet_epoch` 1, `agent_identity` 3, `agent_lease` 2, `agent_lease_grant` 6,
  `agent_replica` 5, `agent_replica_cap` 1, `agent_replica_incarnation` 5,
  `agent_request_tombstone` 2, `agent_session` 1 — plus **17 across V001's ten** and 16 across the
  backend's five.
* **Grants**, measured rather than read: every statement of §3 and §4 executes as `sm_dp_runtime`
  ((L14), 23 statements, all succeeded), and the grants refuse exactly nine things across the run —
  six identity-column writes and `DELETE`s in §14.3(c), the ungranted `nextval` in (L10), and
  §5.2's `DELETE` and §5.3 step 2's `DELETE` + tombstone `INSERT` in (L14) — each with `42501`.
  `sm_dp_runtime` holds **no** grant on `placement` or `series`, by construction (§1).

### The restore path

Eighteen recorded legs run in the `restored` phase, behind five full rebuilds. Restoring is not an
edit: `rebuild()` drops `sm_data`, re-creates it from **Phase 0's own two schema lines** (sliced
verbatim), and re-runs **V001**, Phase 1 and all four design groups — `DROP SCHEMA … CASCADE` takes
`placement` and `series` with it, so the migration is part of the restore in the same position it
holds in the bootstrap. Every restore in this run succeeded, and each restored leg re-asserts the
production behaviour afterwards.

---

## 3. Obligations

Each subsection is generated from `results.json`. A **planted** leg is a real red/green triple:
the production form passes, a **stated** mutation — printed into `log.txt` as
`-- PLANTED VARIANT: removed …` or as a named DDL/DCL change — produces the specified observable
failure, and the extracted form is restored and passes again. No pass criterion was weakened to
match an observation.

### §14.3(a) — claim is monotonic and exclusive under concurrency

**PASS** · 3 recorded legs over 1 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| two-concurrent-claims | production | **OK** | A=commit B=commit ledger=[1, 2] session=2 owner=pod-b | `-` | 10 |
| two-concurrent-claims | planted | **OK** | duplicate epoch accepted | `-` | 0 |
| two-concurrent-claims | restored | **OK** | 1 row(s) | `-` | 0 |

### §14.3(b) — `fencing_epoch` decrease refused, and an owner change without an epoch rise refused

**PASS** · 5 recorded legs over 3 named legs · OK 2 · REFUSED 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| positive-controls | production | **OK** | raise=1 row, heartbeat=1 row | `-` | 0 |
| AGW01-epoch-decrease | production | **REFUSED** | SQLSTATE AGW01: agent_session 2bbe4c99-aef1-4b13-b56a-f774449b955c fencing epoch may not decrease (6 -> 1) | `AGW01` | 6 |
| AGW02-owner-without-epoch | production | **REFUSED** | SQLSTATE AGW02: agent_session 2bbe4c99-aef1-4b13-b56a-f774449b955c owner may not change without raising the fencing epoch (pod-b -> pod-z at epoch 6 -> 6) | `AGW02` | 8 |
| AGW01-epoch-decrease | planted | **OK** | 1 row updated, epoch now 1 | `-` | 0 |
| AGW01-epoch-decrease | restored | **REFUSED** | SQLSTATE AGW01: agent_session 9128737a-b0ae-41ae-b3f2-67540e8d7c24 fencing epoch may not decrease (5 -> 1) | `AGW01` | 5 |

### §14.3(c) — column-scoped grants refuse identity mutation

**PASS** · 7 recorded legs over 7 named legs · OK 1 · REFUSED 6

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 42501-agent_session.tenant_id | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_session | `42501` | 6 |
| 42501-agent_identity.agent_id | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_identity | `42501` | 4 |
| 42501-agent_command.command_id | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_command | `42501` | 4 |
| 42501-agent_claim DELETE | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_claim | `42501` | 4 |
| 42501-agent_lease_grant DELETE | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_lease_grant | `42501` | 4 |
| 42501-agent_request_tombstone INSERT | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_request_tombstone | `42501` | 4 |
| positive-control-granted-column | production | **OK** | 1 row(s) | `-` | 0 |

### §14.3(e) — `agent_command` UNIQUE refuses a second row per intent/agent

**PASS** · 1 recorded legs over 1 named legs · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 23505-second-row-per-intent | production | **REFUSED** | SQLSTATE 23505: duplicate key value violates unique constraint "agent_command_tenant_id_apply_id_intent_id_agent_id_key" | `23505` | 4 |

### §14.3(f) — `QUEUED → SUPERSEDED` admitted with `offered_epoch` NULL

**PASS** · 2 recorded legs over 2 named legs · OK 1 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| never-offered-may-be-superseded | production | **OK** | 1 row, offered_epoch=None | `-` | 0 |
| 23514-offered_has_epoch | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "offered_has_epoch" | `23514` | 4 |

### §14.3(h) — `UNIQUE (agent_id)` / `UNIQUE (command_id)` refuse cross-tenant id reuse

**PASS** · 2 recorded legs over 2 named legs · REFUSED 2

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 23505-UNIQUE(agent_id) | production | **REFUSED** | SQLSTATE 23505: duplicate key value violates unique constraint "agent_identity_agent_id_key" | `23505` | 6 |
| 23505-UNIQUE(command_id) | production | **REFUSED** | SQLSTATE 23505: duplicate key value violates unique constraint "agent_command_command_id_key" | `23505` | 9 |

### §14.3(j) — `cap_matches_op` fires

**PASS** · 2 recorded legs over 2 named legs · OK 1 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 23514-restart-tagged-apply-config | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "cap_matches_op" | `23514` | 5 |
| positive-control | production | **OK** | command 5afa52ee inserted | `-` | 0 |

### §14.3(d) — a stale-epoch write returns 0 rows for every fenced write

**PASS** · 8 recorded legs over 8 named legs · OK 8

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| stale-poll (CommandFeedPort) | production | **OK** | 0 row(s) | `-` | 0 |
| stale-terminal ack (CommandAckPort.ack) | production | **OK** | 0 row(s) | `-` | 0 |
| stale-lease reservation, step 1 (LeasePort) | production | **OK** | 0 row(s) | `-` | 0 |
| stale-lease adoption, step 1 re-arm | production | **OK** | 0 row(s) | `-` | 0 |
| stale-lease takeover (§3.4) | production | **OK** | 0 row(s) | `-` | 0 |
| stale-registry A1 fence (§4 Phase A) | production | **OK** | 0 row(s) | `-` | 0 |
| stale-delivery ack (CommandAckPort.received) | production | **OK** | 0 row(s) | `-` | 0 |
| registry-and-ledger-unchanged | production | **OK** | terminal ledger rows=0, command state=OFFERED | `-` | 0 |

### §14.3(g) — claim refuses a revoked thumbprint under concurrency with the revoke

**PASS** · 3 recorded legs over 3 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| revoked-then-claim | production | **OK** | 0 row(s) | `-` | 0 |
| revoke-serialises-behind-the-claim | production | **OK** | order=['claim-wrote', 'claim-commits', 'revoke-wrote'] A=commit B=commit | `-` | 418 |
| claim-after-revoke | production | **OK** | 0 row(s) | `-` | 0 |

### §14.3(i) — `received` cannot produce a terminal row; `ack` succeeds from `OFFERED` and `DELIVERED`

**PASS** · 5 recorded legs over 5 named legs · OK 4 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| ack-from-OFFERED | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| ack-from-DELIVERED | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| received-never-terminal | production | **OK** | 1 row(s), state=DELIVERED, outcome=None, delivered_at set=True | `-` | 0 |
| received-is-idempotent | production | **OK** | 0 row(s) | `-` | 0 |
| 23514-DELIVERED-with-an-outcome | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "outcome_iff_terminal" | `23514` | 4 |

### §14.3(k) — `state_matches_outcome` refuses every mismatched pair; the terminal statement lands all eight outcomes

**PASS** · 12 recorded legs over 12 named legs · OK 8 · REFUSED 4

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| outcome-APPLIED | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-FAILED_NO_EFFECT | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-FAILED_ROLLED_BACK | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-APPLY_INDETERMINATE | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-PREFLIGHT_OK | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-PREFLIGHT_REFUSED | production | **OK** | 1 row(s), state=ACKED | `-` | 0 |
| outcome-REFUSED | production | **OK** | 1 row(s), state=REFUSED | `-` | 0 |
| outcome-SUPERSEDED | production | **OK** | 1 row(s), state=SUPERSEDED | `-` | 0 |
| 23514-ACKED+REFUSED | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| 23514-REFUSED+APPLIED | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| 23514-SUPERSEDED+APPLIED | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |
| 23514-ACKED+SUPERSEDED | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_command" violates check constraint "state_matches_outcome" | `23514` | 4 |

### §14.3(p) — a report the lease does not scope is `UNTRUSTED` — engine-side

**N/A-engine** · 1 recorded legs over 1 named legs · N/A-engine 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| no-report-store-exists | n-a | **N/A-engine** | matching tables in sm_data: none | `-` | 0 |

### §14.3(l) — the `pollDeliverable` reservation

**PASS** · 16 recorded legs over 16 named legs · OK 16

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| returns-nothing-stale epoch | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-non-owning replica | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-lease bound to another certificate | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-expired lease | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-insufficient remaining time | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-missing capability | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-binding not in the lease | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-foreign target | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-revocation epoch moved | production | **OK** | 0 row(s) | `-` | 0 |
| returns-nothing-no-lease-row | production | **OK** | 0 row(s) | `-` | 0 |
| returns-and-stamps | production | **OK** | 1 row(s) state=OFFERED offered_epoch=1 next_notice_future=True | `-` | 0 |
| no-renotice-inside-the-interval | production | **OK** | 0 row(s) | `-` | 0 |
| renotices-after-the-interval | production | **OK** | 1 row(s) | `-` | 0 |
| never-again-once-acknowledged | production | **OK** | 0 row(s) | `-` | 0 |
| two-pollers-one-row | production | **OK** | live=1 stale=0 A=commit B=commit | `-` | 8 |
| no-process-state | production | **OK** | first=1 second=0 | `-` | 0 |

### §14.3(n) — claim idempotency under a retried `requestId`

**PASS** · 5 recorded legs over 5 named legs · OK 4 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| retry-returns-its-own-epoch | production | **OK** | epoch=1 current=False | `-` | 0 |
| retry-writes-no-row | production | **OK** | ledger rows=2 session epoch=2 | `-` | 0 |
| 23505-second-step-2-under-one-id | production | **REFUSED** | SQLSTATE 23505: duplicate key value violates unique constraint "agent_claim_pkey" | `23505` | 6 |
| aborted-replay-mints-no-epoch | production | **OK** | session epoch=2 | `-` | 0 |
| two-concurrent-identical-claims-mint-one-epoch | production | **OK** | commits=1 ledger rows=1 session epoch=1 outcomes={'A': 'ok', 'B': '23505'} | `-` | 7 |

### §14.3(r) — retention runs, tombstones and the advisory-key interleaving

**PASS** · 3 recorded legs over 3 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| bounded-batch-and-one-tombstone-per-deleted-row | production | **OK** | batch=2 deleted=2 remaining=3 tombstones=2 | `-` | 0 |
| tombstone-refuses-the-replay | production | **OK** | 1 tombstone row(s) found | `-` | 0 |
| advisory-key-serialises-retry-and-job | production | **OK** | order=['job-holds-key', 'job-commits', 'retry-got-key'] A=commit B=commit | `-` | 419 |

### (L1) — lease ledger, the reservation

**PASS** · 4 recorded legs over 4 named legs · OK 4

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| reserve-before-any-cp-call | production | **OK** | 1 row(s); state=PENDING | `-` | 0 |
| duplicate-renew-is-one-row | production | **OK** | 0 row(s); ledger rows=1 | `-` | 0 |
| tombstoned-id-is-REQUEST_EXPIRED | production | **OK** | 1 row(s) | `-` | 0 |
| reservation-is-fenced | planted | **OK** | 0 row(s) | `-` | 0 |

### (L2) — epoch transfer

**PASS** · 3 recorded legs over 3 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| current-owner-adopts | production | **OK** | 1 row(s) epoch=2 owner=pod-b attempt_no=1 | `-` | 0 |
| old-owner-adopts-nothing | production | **OK** | 0 row(s) | `-` | 0 |
| adoption-is-monotonic | production | **OK** | 0 row(s) | `-` | 0 |

### (L3) — takeover

**PASS** · 3 recorded legs over 3 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| ten-retries-exactly-one-takeover | production | **OK** | winners=[('pod-1', 1)] attempt_no=2 ledger rows=1 | `-` | 0 |
| not-takeable-before-the-new-deadline | production | **OK** | 0 row(s) | `-` | 0 |
| attempt-cap-bounds-the-takeovers | production | **OK** | 0 row(s), attempt_no still 3 | `-` | 0 |

### (L4) — a fenced completion writes nothing

**PASS** · 7 recorded legs over 5 named legs · OK 7

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| stale-completion-writes-nothing | production | **OK** | 3a=0 3b=1 (3b is the ownership lock on the ledger row, not the fence) 3c=0 state=PENDING attempt_no=1 completed_at=None agent_lease rows=0 | `-` | 0 |
| cross-agent-completion-equal epochs | production | **OK** | 3b=0 3c=0 state=PENDING agent_lease rows=0 | `-` | 0 |
| cross-agent-completion-different epochs | production | **OK** | 3b=0 3c=0 state=PENDING agent_lease rows=0 | `-` | 0 |
| cross-agent-completion-equal-epochs-3b | production | **OK** | 0 row(s) | `-` | 0 |
| cross-agent-completion-equal-epochs-3b | planted | **OK** | 1 row(s) returned | `-` | 0 |
| cross-agent-completion-equal-epochs-3b | restored | **OK** | 0 row(s) | `-` | 0 |
| completion-from-a-non-owner | production | **OK** | 3b=0 | `-` | 0 |

### (L5) — projection order

**PASS** · 3 recorded legs over 3 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| older-grant-arriving-second-is-SUPERSEDED_ON_ARRIVAL | production | **OK** | newer=PROJECTED older=SUPERSEDED_ON_ARRIVAL projected=newer | `-` | 0 |
| equal-issued_at-resolved-by-grant_seq | production | **OK** | r1(seq low)=SUPERSEDED_ON_ARRIVAL r2(seq high)=PROJECTED | `-` | 0 |
| invalid-grant-lands-FAILED-with-nothing-projected | production | **OK** | FAILED/GRANT_INVALID; agent_lease rows=0; lease_jti=None | `-` | 0 |

### (L6) — the `agent_lease_grant_terminal_once` trigger, per branch by SQLSTATE

**PASS** · 38 recorded legs over 36 named legs · OK 5 · REFUSED 33

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| positive-completion-PENDING-to-COMPLETED | production | **OK** | COMPLETED | `-` | 0 |
| positive-completion-PENDING-to-FAILED | production | **OK** | FAILED | `-` | 0 |
| positive-takeover-branch | production | **OK** | 1 row(s) | `-` | 0 |
| positive-epoch-transfer-branch | production | **OK** | 1 row(s) | `-` | 0 |
| AGW11-write-to-a-COMPLETED-row | production | **REFUSED** | SQLSTATE AGW11: lease request 6288e62f-d691-4fab-a61f-b23f41529ffd is terminal (COMPLETED) and immutable | `AGW11` | 5 |
| AGW10-fingerprint-rewrite | production | **REFUSED** | SQLSTATE AGW10: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 identity columns are immutable | `AGW10` | 5 |
| AGW10-identity-column-write | production | **REFUSED** | SQLSTATE AGW10: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 identity columns are immutable | `AGW10` | 5 |
| AGW12-completion-moves-attempt_no | production | **REFUSED** | SQLSTATE AGW12: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 completion may not move the attempt controls (attempt 1->2, owner pod-a->pod-a, deadline 2026-09-11 05:46:57. | `AGW12` | 5 |
| AGW12-completion-moves-attempt_owner | production | **REFUSED** | SQLSTATE AGW12: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 completion may not move the attempt controls (attempt 1->1, owner pod-a->pod-z, deadline 2026-09-11 05:46:57. | `AGW12` | 6 |
| AGW12-completion-moves-fencing_epoch | production | **REFUSED** | SQLSTATE AGW12: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 completion may not move the attempt controls (attempt 1->1, owner pod-a->pod-a, deadline 2026-09-11 05:46:57. | `AGW12` | 5 |
| AGW13-takeover-before-the-deadline | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns unchanged) | `AGW13` | 5 |
| AGW13-takeover-lowers-the-epoch | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->0, result columns unchanged) | `AGW13` | 5 |
| AGW14-transfer-that-preserves-the-epoch | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->1, result columns unchanged) | `AGW14` | 4 |
| AGW14-transfer-that-lowers-the-epoch | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->0, result columns unchanged) | `AGW14` | 5 |
| AGW15-attempt_no-moves-by-two | production | **REFUSED** | SQLSTATE AGW15: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 admits no such transition (state PENDING->PENDING, attempt 1->3) | `AGW15` | 5 |
| AGW13-takeover-writes-outcome | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| AGW14-transfer-writes-outcome | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| AGW13-takeover-writes-lease_jti | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| AGW14-transfer-writes-lease_jti | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| AGW13-takeover-writes-issued_at | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| AGW14-transfer-writes-issued_at | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 4 |
| AGW13-takeover-writes-expires_at | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 7 |
| AGW14-transfer-writes-expires_at | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| AGW13-takeover-writes-grant_payload | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 5 |
| AGW14-transfer-writes-grant_payload | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 5 |
| AGW13-takeover-writes-completed_at | production | **REFUSED** | SQLSTATE AGW13: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 takeover refused (deadline 2026-09-11 05:46:57.008597+00 vs now, epoch 1->1, result columns written) | `AGW13` | 6 |
| AGW14-transfer-writes-completed_at | production | **REFUSED** | SQLSTATE AGW14: lease request 3b4c2fb4-463a-4873-a431-c4e5ba8157d1 epoch transfer refused (epoch 1->2, result columns written) | `AGW14` | 7 |
| 23514-pending_has_no_result-lease_jti | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 5 |
| 23514-pending_has_no_result-issued_at | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 5 |
| 23514-pending_has_no_result-expires_at | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 5 |
| 23514-pending_has_no_result-grant_payload | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 5 |
| 23514-pending_has_no_result-outcome | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "pending_has_no_result" | `23514` | 4 |
| 23514-failed_has_no_grant-lease_jti | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 7 |
| 23514-failed_has_no_grant-issued_at | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| 23514-failed_has_no_grant-expires_at | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| 23514-failed_has_no_grant-grant_payload | planted | **REFUSED** | SQLSTATE 23514: new row for relation "agent_lease_grant" violates check constraint "failed_has_no_grant" | `23514` | 5 |
| AGW11-write-to-a-COMPLETED-row | planted | **OK** | 1 row(s) updated | `-` | 0 |
| AGW11-write-to-a-COMPLETED-row | restored | **REFUSED** | SQLSTATE AGW11: lease request 6288e62f-d691-4fab-a61f-b23f41529ffd is terminal (COMPLETED) and immutable | `AGW11` | 5 |

### (L7) — issue once per identity, and the retention horizon

**PASS** · 4 recorded legs over 4 named legs · OK 4

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| issue-refused-when-a-lease-row-exists | production | **OK** | 0 row(s) | `-` | 0 |
| issue-refused-even-when-the-lease-has-EXPIRED | production | **OK** | 0 row(s) | `-` | 0 |
| two-concurrent-first-issues-with-different-ids | production | **OK** | commits=1 issue rows=1 outcomes={'I1': 'ok', 'I2': '23505'} sqlstates=['', '23505'] | `-` | 15 |
| post-retention-a-second-issue-IS-admitted | production | **OK** | 1 row(s) reserved | `-` | 0 |

### (L8) — `current` writes nothing

**PASS** · 2 recorded legs over 2 named legs · OK 2

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| current-adds-no-ledger-row | production | **OK** | rows=1 ledger before=0 after=0 | `-` | 0 |
| current-on-a-missing-projection | production | **OK** | 0 row(s), ledger rows=0 | `-` | 0 |

### (L9) — the advisory lock on the ledger

**PASS** · 2 recorded legs over 2 named legs · OK 2

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| retry-waits-and-then-sees-REQUEST_EXPIRED | production | **OK** | order=['job-locked-1', 'job-commits', 'retry-got-key', 'retry-sees-0', 'retry-tombstone-1'] | `-` | 419 |
| never-a-second-ledger-row | production | **OK** | 0 row(s) in agent_lease_grant | `-` | 0 |

### (L10) — the sequence

**PASS** · 5 recorded legs over 3 named legs · OK 4 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| nextval-succeeds-with-the-grant | production | **OK** | nextval = 1 | `-` | 0 |
| nextval-without-the-grant | planted | **REFUSED** | SQLSTATE 42501: permission denied for sequence agent_lease_grant_seq | `42501` | 4 |
| nextval-succeeds-with-the-grant | restored | **OK** | nextval = 1 | `-` | 0 |
| OWNED-BY-drops-the-sequence | planted | **OK** | 0 sequence(s) left | `-` | 0 |
| OWNED-BY-drops-the-sequence | restored | **OK** | 1 sequence(s) | `-` | 0 |

### (L11) — registration cardinality and A2's binding-wide lock

⚠️ **One label in this obligation's recorded text is wrong, and only the label** (coordinator
check, S11). The leg reads "A2/A3's binding-wide FOR UPDATE" and "without A3's FOR UPDATE"; per
sketch §4.1 the binding's cap row is materialized **and locked** at **A2** — its `-- A2.` comment
covers two statements, the `INSERT … ON CONFLICT` and the `SELECT … FOR UPDATE` — while **A3** is
the *stable read* and takes no lock at all. Everything the leg actually did is right: it removed the
`FOR UPDATE` from the cap-row lock statement, which is A2's. This is the same mislabel S10's
correction pass fixed in §7 clause 5 of the sketch and did not sweep into the harness. `run.py`'s
label strings are corrected for the next run; `results.json` and `results.md` are the record of what
ran and are **not** edited, so the table below still prints "A3".

**PASS** · 3 recorded legs over 1 named legs · OK 3

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| two-concurrent-registrations-land-AT-the-cap | production | **OK** | landed=['pod-y'] refused=[('pod-x', 2)] active_replicas=2 live replicas=2 A=commit B=commit | `-` | 13 |
| two-concurrent-registrations-land-AT-the-cap | planted | **OK** | landed=['pod-x', 'pod-y'] active_replicas=3 (cap=2) | `-` | 12 |
| two-concurrent-registrations-land-AT-the-cap | restored | **OK** | active_replicas=2 | `-` | 12 |

### (L12) — one active incarnation, and order

**PASS** · 6 recorded legs over 4 named legs · OK 5 · REFUSED 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 23505-agent_replica_one_active | production | **REFUSED** | SQLSTATE 23505: duplicate key value violates unique constraint "agent_replica_one_active" | `23505` | 5 |
| restart-reuses-replica_id-and-mints-one-incarnation | production | **OK** | active=1 total=2 replica rows=1 | `-` | 0 |
| re-report-of-the-same-incarnation-is-a-watermark-move-only | production | **OK** | 1 row(s), last_seen_report=4, total incarnations=2 | `-` | 0 |
| out-of-order-report-changes-nothing | production | **OK** | 0 row(s), last_seen_report=4 | `-` | 0 |
| out-of-order-report-changes-nothing | planted | **OK** | 1 row(s), last_seen_report=2 | `-` | 0 |
| out-of-order-report-changes-nothing | restored | **OK** | 0 row(s) | `-` | 0 |

### (L13) — retirement, churn and retention

Four of these eleven legs are new in S11, and three of them exist because a claim was
**retracted**. The `series` legs: the relation is defined by
[`V001__telemetry_schema.sql`](../../../../../backend/src/main/resources/db/migration/V001__telemetry_schema.sql),
bootstrapped from the committed file bytes, so sketch §5.2's stable-replica `DELETE` **runs** — it
removes the row when nothing references it, is **withheld** (0 rows, no error) by a `series` row in
the replica's **own** tenant naming it, and is **not** withheld by the same `source_key` string
under a **different** tenant. That third leg is what proves the `s.tenant_id = r.tenant_id`
predicate S11 added; without it the foreign-tenant row would stall retention for ever.

The fourth new leg is **two concurrent §5.1 sweeps on one binding** — one as `sm_dp_migrator`, the
other deliberately as **`sm_dp_runtime`**, the role the DCL cannot refuse the sweep to. Both commit,
neither raises `40P01`, the idle replica is retired exactly once, a **live** replica on the same
binding is untouched, and `active_replicas` is decremented exactly once and still reconciles with
`count(*) … WHERE retired_at IS NULL`. It is the enforcing test for S11/F4's replacement of the
unenforceable "the migrator's alone" guarantee.

The barrier leg and its planted half remain §7 clause 2's enforcing test: registration and the
sweep run concurrently on **one binding**, both take that binding's `agent_replica_cap` row before
either enters a tail, and **both commit**. With §5.1 **step 1** — the cap-row lock — removed from
the sweep, the two take `agent_replica_cap` and `agent_replica` in opposite orders and **one aborts
with `40P01`**; with step 1 back, both commit again.

**PASS** · 11 recorded legs over 9 named legs · OK 11

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| idle-replica-retired-and-the-cap-released | production | **OK** | retired=True active_replicas=0 | `-` | 0 |
| churn-beyond-the-cap-succeeds | production | **OK** | 4 registrations landed | `-` | 0 |
| retired-incarnations-pruned | production | **OK** | 1 row(s) deleted | `-` | 0 |
| series-relation-is-present | production | **OK** | present: 1 | `-` | 0 |
| stable-delete-succeeds-with-no-referencing-series | production | **OK** | 1 row(s) deleted | `-` | 0 |
| stable-delete-withheld-by-a-same-tenant-series | production | **OK** | 0 row(s) deleted, replica rows remaining=1 | `-` | 0 |
| stable-delete-ignores-a-foreign-tenant-series | production | **OK** | foreign series rows=1, 1 row(s) deleted, replica rows remaining=0 | `-` | 0 |
| registration-and-the-sweep-both-commit | production | **OK** | REG=commit/ SWEEP=commit/ | `-` | 433 |
| registration-and-the-sweep-both-commit | planted | **OK** | REG=commit/ SWEEP=error/40P01 | `40P01` | 1036 |
| registration-and-the-sweep-both-commit | restored | **OK** | REG=commit SWEEP=commit | `-` | 439 |
| two-concurrent-§5.1-sweeps-are-safe | production | **OK** | commit/- commit/- idle_retired=True live_retired=False active_replicas=1 non_retired_rows=1 | `-` | 423 |

### (L14) — least privilege, as the DCL actually draws it

Read this subsection as two halves, which is what S10/F3 corrected and S11/F3 finished
sweeping out of the prose.

* **What the DCL refuses — 2 legs here, `42501` both:** §5.2's `DELETE FROM
  agent_replica_incarnation` (`permission denied for table agent_replica_incarnation`) and §5.3
  step 2's `DELETE` + tombstone `INSERT` (`permission denied for table agent_request_tombstone`).
  Across the whole run the refusal set is **nine legs**: these two, the six identity-column writes
  and `DELETE`s of §14.3(c), and (L10)'s ungranted `nextval`.
* **What the DCL cannot refuse — 5 legs, each "succeeds as runtime, per corrected (L14)":** §5.0's
  silence sweep, §5.1 steps 1 and 3, and §5.4 steps 1 and 2. Their columns are the ones **B4**,
  **B2**'s un-retire, **A7/B2**'s counter and **step 3c** write on the runtime path. §5.3 **step 1**
  is a sixth statement in that class and was already carved out in the text before the first run.
* **The remaining legs:** one aggregate (all 23 statements of §3 and §4 execute as `sm_dp_runtime`)
  and eight `migrator-may-run-…` controls, so that "refused to the runtime role" is never confused
  with "refused to everyone".

⚠️ **S11/F3 removed the last two places the prose still contradicted this measurement:** `(L16)`
required §5.4 to raise `42501` as `sm_dp_runtime`, and §7's `(L14)` pointer said the test "expects
`42501`" for §5.0 and §5.1. Neither had a harness leg behind it — the legs below always measured
the opposite — so both were claims without artifacts (`L19`) and both are withdrawn.

**PASS** · 17 recorded legs over 17 named legs · OK 15 · REFUSED 2

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| every-§3-and-§4-statement-runs-as-sm_dp_runtime | production | **OK** | all succeeded | `-` | 0 |
| runtime-may-run-§5.0 silence sweep | production | **OK** | executed, 0 row(s) affected | `-` | 0 |
| migrator-may-run-§5.0 silence sweep | production | **OK** | succeeded | `-` | 0 |
| runtime-may-run-§5.1 step 1 cap lock | production | **OK** | executed, 1 row(s) affected | `-` | 0 |
| migrator-may-run-§5.1 step 1 cap lock | production | **OK** | succeeded | `-` | 0 |
| runtime-may-run-§5.1 step 3 idle sweep | production | **OK** | executed, 0 row(s) affected | `-` | 0 |
| migrator-may-run-§5.1 step 3 idle sweep | production | **OK** | succeeded | `-` | 0 |
| 42501-as-runtime-§5.2 prune incarnations (a DELETE — genuinely refused) | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_replica_incarnation | `42501` | 5 |
| migrator-may-run-§5.2 prune incarnations (a DELETE — genuinely refused) | production | **OK** | succeeded | `-` | 0 |
| runtime-may-run-§5.3 step 1 batch | production | **OK** | succeeded | `-` | 0 |
| migrator-may-run-§5.3 step 1 batch | production | **OK** | succeeded | `-` | 0 |
| 42501-as-runtime-§5.3 step 2 delete + tombstone (DELETE + tombstone INSERT — genuinely refused) | production | **REFUSED** | SQLSTATE 42501: permission denied for table agent_request_tombstone | `42501` | 5 |
| migrator-may-run-§5.3 step 2 delete + tombstone (DELETE + tombstone INSERT — genuinely refused) | production | **OK** | succeeded | `-` | 0 |
| runtime-may-run-§5.4 step 1 batch | production | **OK** | executed, 0 row(s) affected | `-` | 0 |
| migrator-may-run-§5.4 step 1 batch | production | **OK** | succeeded | `-` | 0 |
| runtime-may-run-§5.4 step 2 terminalise | production | **OK** | executed, 0 row(s) affected | `-` | 0 |
| migrator-may-run-§5.4 step 2 terminalise | production | **OK** | succeeded | `-` | 0 |

### (L15) — the retired-incarnation acceptance window

**PARTIAL** · 2 recorded legs over 2 named legs · REFUSED 1 · N/A-engine 1

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| 23514-incarnation_window | production | **REFUSED** | SQLSTATE 23514: new row for relation "agent_replica_incarnation" violates check constraint "incarnation_window" | `23514` | 5 |
| sample-admission-interval | n-a | **N/A-engine** | no admission statement exists in this design set to execute (sm_data.metrics_raw present from V001: 1; the rule that reads it is the DM-D*/BE-D* families', and no document here carries it as SQL) | `-` | 0 |

### (L16) — the orphan sweep

The two concurrency legs run the orphan sweep against step-3 completion on the same `request_id`
in **both ledger-lock orders**. Completion first leaves the sweep's re-checked
`state = 'PENDING'` false, so it writes zero rows and one lease is projected. Sweep first leaves
step 3b's ownership read with zero rows, so step 3c is unreachable and no lease is projected.
Both transactions commit without `40P01` in each order, and the request has exactly one outcome.

⚠️ **What serializes that pair is the ledger row lock, not the request-key advisory lock**, and the
obligation says so: step 3 runs in its own transaction and takes no advisory key, because step 0a
belongs to the reserve/adopt transaction that has already committed (§5.4's boundary note). The
advisory key is what serializes the sweep against an **adoption or takeover**, which do run step 0a;
those are a different leg.

**PASS** · 4 recorded legs over 4 named legs · OK 4

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| abandoned-request-terminalised-live-one-skipped | production | **OK** | batch=1 terminalised=1 dead=FAILED/TIMEOUT attempt_no=1 live=PENDING | `-` | 0 |
| no-grant-material-on-the-swept-row | production | **OK** | lease_jti=None | `-` | 0 |
| sweep-concurrent-with-a-step-3-completion-same-request-id | production | **OK** | STEP3=commit/- SWEEP=commit/- 3c wrote 1 row(s), sweep step 2 wrote 0 row(s), state=COMPLETED outcome=PROJECTED agent_lease rows=1 | `-` | 524 |
| sweep-wins-before-step-3-completion | production | **OK** | SWEEP=commit/- STEP3=commit/- sweep step 2 wrote 1 row(s), 3b returned 0 row(s), 3c wrote 0 row(s), state=FAILED outcome=TIMEOUT agent_lease rows=0 | `-` | 519 |

### (L17) — a stale-epoch report writes nothing

Seven fenced writes, each planted **separately**, each a full red/green triple —
production 0 rows → fence removed by a stated textual removal, 1 row → extracted statement restored,
0 rows again: **B1** handoff ownership · **B2** mint / un-retire · **B2** cap counter · **B3**
liveness refresh (incumbent form) · **B3** liveness refresh (handoff form) · **B4** retire the
activation · **B5** insert the new activation. Beside them: the whole-registry leg (after all seven
stale writes, `agent_replica` and `agent_replica_incarnation` hash identically and the cap counter is
unmoved) and **A0**'s revocation predicate, planted the same way.

**PASS** · 25 recorded legs over 9 named legs · OK 25

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| stale-B1 handoff ownership | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B1 handoff ownership | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B1 handoff ownership | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B2 mint / un-retire | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B2 mint / un-retire | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B2 mint / un-retire | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B2 cap counter | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B2 cap counter | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B2 cap counter | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B3 liveness refresh, incumbent form | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B3 liveness refresh, incumbent form | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B3 liveness refresh, incumbent form | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B3 liveness refresh, handoff form | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B3 liveness refresh, handoff form | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B3 liveness refresh, handoff form | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B4 retire the activation | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B4 retire the activation | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B4 retire the activation | restored | **OK** | 0 row(s) | `-` | 0 |
| stale-B5 insert the new activation | production | **OK** | 0 row(s) | `-` | 0 |
| stale-B5 insert the new activation | planted | **OK** | 1 row(s) | `-` | 0 |
| stale-B5 insert the new activation | restored | **OK** | 0 row(s) | `-` | 0 |
| registry-byte-identical-after-a-stale-report | production | **OK** | replica md5 4547f773→4547f773, incarnation md5 4e91e19b→4e91e19b, active_replicas=1 | `-` | 0 |
| A0-refuses-a-revoked-identity | production | **OK** | 0 row(s) | `-` | 0 |
| A0-refuses-a-revoked-identity | planted | **OK** | 1 row(s) | `-` | 0 |
| A0-refuses-a-revoked-identity | restored | **OK** | 0 row(s) | `-` | 0 |

### (L18) — lock order under contention

Six named legs and three mechanism probes. The three probes are what make (L18-P3)'s
green a measurement: probe 1 shows the wait that used to cycle, probe 3 shows the same statement not
waiting under the corrected mode, and probe 2 shows why (L18-N3) must be run against step 3.

**PASS** · 10 recorded legs over 9 named legs · OK 8 · REFUSED 2

| leg | phase | verdict | observed | SQLSTATE | ms |
|---|---|---|---|---|---|
| L18-P1-claim-vs-registration | production | **OK** | CLAIM=commit/- REGISTRATION=commit/- | `-` | 9 |
| L18-N1-claim-vs-registration | planted | **OK** | FIXTURE=commit/- PRODUCTION=error/40P01 in 1016 ms | `40P01` | 1016 |
| L18-P2-claim-vs-lease-reservation | production | **OK** | CLAIM=commit/- RESERVATION=commit/- ledger rows 0→0 session epoch=2 | `-` | 13 |
| L18-N2-claim-vs-lease-reservation | planted | **OK** | FIXTURE=commit/- PRODUCTION=error/40P01 in 1014 ms | `40P01` | 1014 |
| L18-P3-step1-retry-vs-step3 | production | **OK** | #1 RETRY=commit/- STEP3=commit/-; #2 RETRY=commit/- STEP3=commit/-; #3 RETRY=commit/- STEP3=commit/-; #4 RETRY=commit/- STEP3=commit/-; #5 RETRY=commit/- STEP3=commit/- \| ledger rows for the last id=1 | `-` | 13 |
| L18-P3-mechanism-probe-1 | production | **REFUSED** | SQLSTATE 55P03: canceling statement due to lock timeout | `55P03` | 2011 |
| L18-P3-mechanism-probe-3 | production | **OK** | completed without waiting, 1 row(s) | `-` | 0 |
| L18-P3-mechanism-probe-2 | production | **REFUSED** | SQLSTATE 55P03: canceling statement due to lock timeout | `55P03` | 2016 |
| L18-N3-step1-retry-vs-step3 | planted | **OK** | FIXTURE=commit/- PRODUCTION=error/40P01 in 1023 ms | `40P01` | 1023 |
| L18-P1-claim-vs-registration | restored | **OK** | CLAIM=commit REGISTRATION=commit | `-` | 10 |

---

## 4. Four runs, and what each one changed

| Run | Commit | Legs | Result | What it forced |
|---|---|---|---|---|
| discovery | **`7558dba`** (S9) — [`runs/20260910T094104Z/`](runs/20260910T094104Z/) | 216 | OK 144 · REFUSED 60 · N/A-engine 2 · **FAIL 10** | every one of the ten was a design defect; all ten corrected in S10 |
| first recorded | **`dce059d`** (S10) — [`runs/20260910T100024Z-dce059d/`](runs/20260910T100024Z-dce059d/) | 218 | OK 155 · REFUSED 60 · N/A-engine 2 · **BLOCKED 1** · FAIL 0, exit **1** | the blocked leg was recorded as an unownable dependency — **which was itself a false claim**, retracted in S11 |
| second recorded | **`8ab6880`** (S11) — [`runs/20260911T054546Z-8ab6880/`](runs/20260911T054546Z-8ab6880/) | 223 | OK 161 · REFUSED 60 · N/A-engine 2 · BLOCKED 0 · FAIL 0, exit **0** | five new legs; three prose retractions; one SQL predicate change |
| this run | **`8551e5c`** (S12) — [`runs/20260911T062308Z-8551e5c/`](runs/20260911T062308Z-8551e5c/) | 224 | OK 162 · REFUSED 60 · N/A-engine 2 · BLOCKED 0 · FAIL 0, exit **0** | one reverse-order concurrency leg; execution and planning claims narrowed; two central open items registered |

### The discovery run's ten failures, and what fixed each

None was fixed by changing a test.

| # | Failing leg (`7558dba`) | Observed then | Correction | Where |
|---|---|---|---|---|
| 1 | `§14.3(d) / stale-received` | `FINDING: not executable as extracted` | **S10/F1** — design §5.2's `received` statement read `FROM s` with no `WITH` of its own; it now carries its own `WITH s AS (… FOR SHARE OF se)` | `dce059d`, design.md §5.2 |
| 2 | `§14.3(i) / received-never-terminal` | `FINDING: not executable as extracted` | **S10/F1**, same statement | `dce059d`, design.md §5.2 |
| 3 | `(L13) / retired-replica-pruned` | `42P01: relation "sm_data.series" does not exist` | **S10/F2** recorded it `BLOCKED-dependency` on the grounds that no document defines the relation — **since retracted, see below** | `dce059d`, then `8ab6880` |
| 4 | `(L14) / 42501-as-runtime-§5.0 silence sweep` | `no error raised` | **S10/F3** — the *claim* was wrong, not the DCL | `dce059d`, (L14) + §5.0/§5.1/§5.4 intros + §7 audit + design §7.4 + §14.3(c) + `AGW-D13` |
| 5 | `(L14) / 42501-as-runtime-§5.1 step 1 cap lock` | `no error raised` | **S10/F3** | as above |
| 6 | `(L14) / 42501-as-runtime-§5.1 step 3 idle sweep` | `no error raised` | **S10/F3** | as above |
| 7 | `(L14) / 42501-as-runtime-§5.4 step 1 batch` | `no error raised` | **S10/F3** | as above |
| 8 | `(L14) / 42501-as-runtime-§5.4 step 2 terminalise` | `no error raised` | **S10/F3** | as above |
| 9 | `(L18) / L18-P3-step1-retry-vs-step3` | `#3 RETRY=commit/- STEP3=error/40P01` — one of five concurrent repetitions deadlocked | **S10/F4** — every identity lock in §7's prefix (lease step 0c, step 1's `id` re-lock, registration A0) became **`FOR NO KEY UPDATE`**, and §7 gained **clause 5** naming the FK lock edges | `dce059d`, sketch §3.1, §4.1, §7 |
| 10 | `(L18) / L18-N3-step1-retry-vs-step3` | `FIXTURE=commit/- PRODUCTION=commit/- in 620 ms` — no cycle possible | **S10/F5** — `FOR SHARE` does not conflict with `FOR SHARE`, so the S8/C1 claim that the pre-swap step-3 order "cycles against step 1" was wrong; (L18-N3) is redefined as an explicitly **synthetic** cycle run against production step 3 | `dce059d`, sketch §3.3, §7, (L18-N3) |

**S10/F3 in one line, because it is the most quotable:** the five statements that "must raise
`42501`" write `agent_replica_incarnation.retired_at`, `agent_replica.retired_at`,
`agent_replica_cap.active_replicas` and `agent_lease_grant.state/outcome/completed_at` — exactly the
columns **B4**, **B2**'s un-retire, **A7/B2**'s counter and **step 3c** must write on the runtime
path. No column-scoped grant can refuse them without breaking the report and lease paths.

**S10/F4 in one line:** `agent_lease` carries a `FOREIGN KEY` to `agent_identity`, so step 3c's
projection takes a `KEY SHARE` lock on the identity row; `FOR KEY SHARE` conflicts with `FOR UPDATE`
and with nothing else (PG 14 `explicit-locking` Table 13.3), and "foreign key checks use the new
`KEY SHARE` lock mode, which does not conflict with `NO KEY UPDATE`" (PG 9.3 release notes) — both
verified 2026-09-10.

### What S11 changed, and why this run has five more legs

* **S11/F1 — the retraction.** `sm_data.series` is defined; the S10/F2 "blocked by dependency"
  claim is withdrawn, the migration is a pinned bootstrap input, and **four** new (L13) legs replace
  the single blocked one (the presence check plus the three delete legs).
* **S11/F1d — one SQL change**, the tenant-scoped `NOT EXISTS` predicate (§5.2 below).
* **S11/F3** — `(L16)`'s `42501` requirement removed; no leg count change (the claim never had one).
* **S11/F4 — the fifth new leg** and one more in (L16): the two concurrency legs that replace the
  unenforceable "the migrator's alone" guarantee.
* **S11/F2 and S11/F5** — documentation only: §14 restructured out of its circularity, and nine
  stale "UNEXECUTED" statements replaced by this run's status.

### What S12 changed, and why this run has one more leg

* **S12/F1** distinguishes an implementation plan being drafted from being approved; open Group A
  inputs block approval, not drafting.
* **S12/F2** narrows execution claims to the SQL-backed executable subset. The two `N/A-engine`
  legs and (L9)'s defensive, unexercised projection re-check are not described as green settlement.
* **S12/F3** adds `(L16) / sweep-wins-before-step-3-completion`; together with the existing
  completion-first leg it measures both ledger-lock orders. Broader arbitrary maintenance-job
  concurrency is not claimed.
* **S12/F4** registers central open items 38 and 39 for V001 bootstrap authority and maintenance
  scheduler ownership/multiplicity. Neither is closed by this run.

---

## 5. Findings and observations

### 5.1 🔴 A gap between two committed documents — V001 cannot be run by the Flyway migrator role

**Routed to the `BE-D*` owners; not repaired here.** V001's first statement is
`CREATE SCHEMA IF NOT EXISTS sm_data`, and *"to create a schema, the invoking user must have the
`CREATE` privilege for the current database"* (PG 14 docs, `sql-createschema`, Notes; verified
2026-09-11). **That privilege check precedes the `IF NOT EXISTS` test**, so it fires even though
Phase 0 has already created the schema. The walking skeleton's Phase 0 grants **no database-level
privilege to any `sm_dp_*` role** — it creates the schema as the cluster administrator and grants
only `USAGE ON SCHEMA` — so as `sm_dp_migrator` the migration raises
`42501: permission denied for database`.

`log.txt` line 16 carries the measurement in the harness's own note:

```
=== created the schema — measured here as 42501 'permission denied for database'. The
```

⚠️ **Read that line precisely.** The failure was measured during harness development, **before** the
grant was added; this recorded run issues the grant first, so it does not re-raise the error. The
note is the record of the measurement, not a leg, and there is deliberately no leg for it: the
harness's job is to execute the design set's SQL, not to assert a defect in a bootstrap document it
does not own.

Three closes exist and **none is this design set's to pick**: add `GRANT CREATE ON DATABASE … TO
sm_dp_owner` to Phase 0; have the installer rather than Flyway run V001; or drop V001's
`CREATE SCHEMA` line so it relies on Phase 0 having run. It is install-runbook material and belongs
beside Phase 0's own "only the bootstrap superuser can run this `REVOKE`" note. **V001's bytes are
not edited** — a harness that repairs its input measures nothing.

### 5.2 The stable-replica delete is tenant-scoped, and the third series leg is why

`lease-and-registry-sketch.md` §5.2's second statement now ends:

```sql
AND NOT EXISTS (SELECT 1 FROM sm_data.series s
                 WHERE s.tenant_id = r.tenant_id
                   AND s.source_key = r.replica_id::text);
```

V001 makes every `series` key tenant-scoped — `PRIMARY KEY (tenant_id, series_id)` and
`UNIQUE (tenant_id, instance_id, placement_id, metric_name, package_version, source_key,
labels_fingerprint)` — so a `source_key` **value** is unique only *within* a tenant and two tenants
may legitimately carry the same string; `agent_replica` likewise carries `tenant_id` in its own
`PRIMARY KEY`. Without the predicate a foreign tenant's row **withholds** the delete. The failure
direction is a **permanently stalled retention**, not an orphaned dimension — nothing was ever at
risk of being deleted wrongly — but the statement's correctness then rested on the *unstated* fact
that `replica_id` happens to be globally unique (`agent_replica`'s global `UNIQUE (replica_id)`)
rather than on anything the statement said. That is `L19`'s shape, and
`(L13) / stable-delete-ignores-a-foreign-tenant-series` is the leg that now measures it.

### 5.3 Two trigger behaviours that shaped the fixtures — both correct

Neither is a defect; both are recorded because they constrain how any future test may build state.

* **`agent_lease_grant_terminal_once` makes `attempt_deadline` and `requested_at` unmovable out of
  band**, even for the table owner: an `UPDATE` that only moves `attempt_deadline` raises `AGW14`
  (no admitted branch), and one that touches `requested_at` raises `AGW10` (identity columns are
  immutable). So a ledger row that must already be past its deadline is created that way — through
  the document's own `$pending_ttl` with a negative fixture value — and a row that must be past the
  retention horizon is *inserted* old rather than aged.
* **`agent_session_epoch_only_rises` refuses a fixture that lowers the epoch (`AGW01`) or moves the
  owner without raising it (`AGW02`).** The session fixture therefore deletes and re-inserts the
  row rather than updating it, so the trigger under test is never worked around by the harness.

### 5.4 Other things the log shows

* **`INSERT … ON CONFLICT DO NOTHING` waits only on a row an in-flight transaction has *updated*,
  never on one merely locked.** Measured as `(L18)/L18-P3-mechanism-probe-2` (`55P03` under a 2 s
  `lock_timeout`). This is why (L18-N3)'s synthetic cycle is run against production **step 3** and
  not against a step-1 retry.
* **The corrected lock mode is proven directly**, not only by the absence of a deadlock:
  `probe-1` shows step 3c waiting (`55P03`) on an identity row held `FOR UPDATE` — the pre-S10/F4
  mode, held synthetically — and `probe-3` shows the same statement **completing without waiting**
  against a hold in the current `FOR NO KEY UPDATE` mode.
* **(L18-P3) ran five concurrent repetitions and all five committed**, where the same leg on
  `7558dba` deadlocked in one of five. A defect that appears intermittently is still a defect; the
  fix is asserted over repetitions rather than over a single pass.
* **One leg text in this run carries a wrong step label** — (L11)'s "A2/A3's binding-wide FOR
  UPDATE" and "without A3's FOR UPDATE", where sketch §4.1 puts the cap-row `FOR UPDATE` at **A2**
  and makes **A3** the lock-free stable read. Only the label is wrong; the leg removed the
  `FOR UPDATE` from the cap-row statement, which is A2's. `run.py`'s label strings are corrected for
  the next run, and `results.json`/`results.md` are left as the record of what actually ran.

---

## 6. What this evidence does **not** establish

1. **It ratifies nothing.** `AGW-D1`–`AGW-D16` remain `PROPOSED`;
   [`docs/design/DECISIONS.md`](../../../DECISIONS.md) stays the status authority and was not touched
   by this work. Execution is **one** of the sketch's §10 gates — now met — and the other three are
   decisions and ratification order that no amount of green SQL can close.
2. **AGW-17, AGW-18, AGW-19, AGW-20 and AGW-21 remain open**, and design §12's limits 19a, 20 and 21
   with them. `AGW-D15` still gates on AGW-18; `AGW-D16` still gates on AGW-17 **and** AGW-20, with
   its retirement clauses additionally conditional on AGW-19.
3. **It is not an implementation.** No product code carries any of this DDL. Design §14's Group A
   gates decide when a plan may even be approved, and Group B is where these obligations must be
   re-established against the implementation's own migrations, in CI.
4. **Two obligations have no database artifact and are recorded `N/A-engine`, not passed.**
   §14.3(p) — a report the current lease does not scope is `UNTRUSTED` — is engine-side
   classification, and the run measured only that **no table in `sm_data` could persist a report or
   its refusal** (`matching tables in sm_data: none`); durable persistence is AGW-20. (L15)'s
   sample-admission interval is the metrics family's rule over rows this sketch owns: V001 supplies
   the fact tables, but the **admission statement itself is written in no document of this design
   set**, so there is nothing to extract and run.
5. **(L9)'s projection re-check is recorded defensive and unexercised**, not passed: the
   interleaving it guards is unreachable, and S1/F6 says so rather than dressing it as a race test.
6. **The ownership and multiplicity of the §5 maintenance schedule are routed, not decided.** The
   run establishes only the named pairings: two §5.1 runners on one binding and §5.4 versus step 3
   in both ledger-lock orders. It does **not** establish arbitrary pairwise concurrency or an
   unlimited runner count. Central open item 39 assigns the scheduler contract to the `BE-D*`
   owners; central open item 38 assigns the V001 bootstrap choice to them as well.
7. **The statements are the design's; the fixture values are the harness's.** Identities, sessions,
   commands, leases, replicas, the backend `apply_*` chain `agent_command`'s foreign key needs, the
   `placement`/`series` pair the (L13) series legs require, the two role passwords, the database
   `CREATE` grant, the threading barriers and the 5 s `lock_timeout`/`statement_timeout` are all
   harness material, labelled as such in `log.txt`. What is under test is the extracted SQL.
8. **Ceilings, cadences and capacity are untested here.** Every value the design expresses as
   configuration (`pending_ttl`, `ledger_retention`, `orphan_grace`, `registry_retention`, the two
   caps, `report_interval`) was supplied as a fixture value chosen to reach a branch — not measured,
   and not validated against the capacity model.
9. **This is one engine build on one architecture.** `aarch64`, Debian, 14.24, default
   `deadlock_timeout`. Nothing here characterises behaviour under load, on another minor version, or
   with non-default lock or timeout settings.

---

## 7. Legs by verdict, per obligation

Every number is `collections.Counter` over `results.json`; nothing is rounded and nothing is added.

| obligation | legs | OK | REFUSED | N/A-engine | BLOCKED-dependency | FAIL |
|---|---|---|---|---|---|---|
| §14.3(a) | 3 | 3 | 0 | 0 | 0 | 0 |
| §14.3(b) | 5 | 2 | 3 | 0 | 0 | 0 |
| §14.3(c) | 7 | 1 | 6 | 0 | 0 | 0 |
| §14.3(d) | 8 | 8 | 0 | 0 | 0 | 0 |
| §14.3(e) | 1 | 0 | 1 | 0 | 0 | 0 |
| §14.3(f) | 2 | 1 | 1 | 0 | 0 | 0 |
| §14.3(g) | 3 | 3 | 0 | 0 | 0 | 0 |
| §14.3(h) | 2 | 0 | 2 | 0 | 0 | 0 |
| §14.3(i) | 5 | 4 | 1 | 0 | 0 | 0 |
| §14.3(j) | 2 | 1 | 1 | 0 | 0 | 0 |
| §14.3(k) | 12 | 8 | 4 | 0 | 0 | 0 |
| §14.3(l) | 16 | 16 | 0 | 0 | 0 | 0 |
| §14.3(n) | 5 | 4 | 1 | 0 | 0 | 0 |
| §14.3(p) | 1 | 0 | 0 | 1 | 0 | 0 |
| §14.3(r) | 3 | 3 | 0 | 0 | 0 | 0 |
| (L1) | 4 | 4 | 0 | 0 | 0 | 0 |
| (L2) | 3 | 3 | 0 | 0 | 0 | 0 |
| (L3) | 3 | 3 | 0 | 0 | 0 | 0 |
| (L4) | 7 | 7 | 0 | 0 | 0 | 0 |
| (L5) | 3 | 3 | 0 | 0 | 0 | 0 |
| (L6) | 38 | 5 | 33 | 0 | 0 | 0 |
| (L7) | 4 | 4 | 0 | 0 | 0 | 0 |
| (L8) | 2 | 2 | 0 | 0 | 0 | 0 |
| (L9) | 2 | 2 | 0 | 0 | 0 | 0 |
| (L10) | 5 | 4 | 1 | 0 | 0 | 0 |
| (L11) | 3 | 3 | 0 | 0 | 0 | 0 |
| (L12) | 6 | 5 | 1 | 0 | 0 | 0 |
| (L13) | 11 | 11 | 0 | 0 | 0 | 0 |
| (L14) | 17 | 15 | 2 | 0 | 0 | 0 |
| (L15) | 2 | 0 | 1 | 1 | 0 | 0 |
| (L16) | 4 | 4 | 0 | 0 | 0 | 0 |
| (L17) | 25 | 25 | 0 | 0 | 0 | 0 |
| (L18) | 10 | 8 | 2 | 0 | 0 | 0 |
| **total** | **224** | **162** | **60** | **2** | **0** | **0** |

---

## Forward pointer, open items (plan 015 T0.1, 2026-10-08)

Every citation of "open item 34"–"39" in this document is the **Agent Gateway's** numbering. Since 2026-10-08 those are central open items **42–47**: 34 → 42, 35 → 43, 36 → 44, 37 → 45, 38 → 46 (closed, moot), 39 → 47. The central items 34–39 are different questions and are unaffected; the map is in [`DECISIONS.md`](../../../DECISIONS.md) after the open-items table. The text above is unedited.
