# Plan 011 — the `sm_control` `V013` gap: strategy and evidence

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

| | |
|---|---|
| **Date** | 2026-10-07 |
| **Question** | Plan 010's task T2.0 reserved `sm_control/V013__dp_remote_governance.sql`. Plan 011 shipped `V014` (agent registration) and `V015` (enrollment replay) around it, so `V013` has never existed in this tree and databases already at `V015` do. How does the next `sm_control` migration land safely on **both** a fresh database and a persistent one already at `V015`? |
| **Constraints** | `OR-22`: every applied migration is immutable (checksums pinned, anchored); `flyway repair` is not an accepted remedy; **no global `outOfOrder`**. |

## What was checked (actual history, not the plan)

| Source | Finding |
|---|---|
| `backend/cp-core/src/main/resources/db/migration/sm_control/` | `V001`–`V012`, `V014`, `V015`; **no `V013`**. `CHECKSUMS.sha256` pins those 14 files. |
| `docs/plans/backend/010-…md` allocation table (line ~351) and Task T2.0 | Reserves `sm_control/V013`; also reserves `sm_data/V007`–`V014` and (plan 008) `V015`–`V017`. Nothing of plan 010's migrations has been executed in this tree. |
| `sm_data/` | `V001`–`V006`, then `V018`–`V026`. The reserved `V007`–`V017` are **also** below the head and have the same problem. |
| `contracts/run-checks.sh` step 14 (OR-22) | Passes: 12 migrations byte-identical at anchor `245d763`; `V014`/`V015` noted as unanchored until published. |
| Flyway behaviour | A database whose history already contains version 15 refuses a resolved migration numbered below it ("detected resolved migration not applied") unless `outOfOrder` is on. Proven below, not asserted. |

## Strategy chosen

**Never fill the gap.** `V013` is retired; Flyway has no objection to a numbering gap. The next `sm_control` migration — including what plan 010 calls T2.0 — takes **`V016`**, and
`sm_data`'s plan-010 migrations must be renumbered to `V027`+ in merge order by whoever lands them. `outOfOrder(false)` is now written into both migrators as policy
(`SmControlMigrator.flyway`, `SmDataMigrator.migrate`), so it cannot silently change with a Flyway default. Recorded in: the `sm_control` migration `README.md` (`V013` is retired), plan 010's allocation table
(an amendment block, and the T2.0 row struck through and renumbered), this file.

**Rejected:** (a) enabling `outOfOrder` globally — a persistent database and a fresh one would run the same migrations in different orders and share a version history with different schemas; (b) a "backfill" `V013` that
no-ops on databases past it — it would still be refused by Flyway on a persistent database without `outOfOrder`, and a per-database conditional migration is exactly the drift `OR-22` forbids; (c) editing `V014`/`V015`
to be `V013`/`V014` — rewrites applied checksums, forbidden by `OR-22` and by the anchors.

## Evidence — `SmControlMigrationUpgradePathIT` (cp-core, real PostgreSQL 14, real `sm_cp_migrator` role, real Phase 0), 5/5

Command: `cd backend && mvn -o verify -pl cp-core -Dtest='CredentialHashMainTest,MigrationChecksumsTest,ControlPlaneAgentGatewayServiceTest' -Dit.test=SmControlMigrationUpgradePathIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`

| Test | What it proves |
|---|---|
| `aFreshDatabaseReachesV015WithTheGapAndNoV013` | A fresh database applies exactly `001`–`012`, `014`, `015`, in that order, with no `013` row. |
| `aPersistentDatabaseAtV015UpgradesToTheNextMigrationWithoutTouchingWhatIsApplied` | A database already at `V015` takes a stand-in `V016` (a test-only location) with **every previously applied version and checksum byte-identical**; and a fresh database given the same migration set ends with a history **identical** to the upgraded one. |
| `aMigrationNumberedBelowTheHeadIsRefusedAndAppliesNothing` | A stand-in `V013` appearing after `V015` is **refused** (`FlywayValidateException`, naming `013`) and **applies nothing** — the reason the strategy exists. |
| `outOfOrderIsOffAsPolicyNotAsAnAccident` | The migrator's Flyway configuration has `outOfOrder == false`. |
| `v013IsRetiredAndSaidSoWhereTheNextAuthorWillLook` | No `V013*.sql` exists; the README says `V013` is retired and names `V016`; `CHECKSUMS.sha256` has no `V013`. |

Also green in the same pass: `MigrationChecksumsTest` (the frozen manifest), `./contracts/run-checks.sh` step 14 (anchor + base mode, and its hermetic self-test), `SmControlMigrationMainTest` (fresh migrate: 14 migrations, then 0).

## Not done, said plainly

- The `sm_data` renumbering of plan 010's own migrations is a **plan amendment**, not code: nothing from plan 010 exists to renumber. The amendment is recorded; whoever executes those tasks must use `V027`+.
- No customer database was upgraded; "persistent at V015" is the integration database created by the real migrator, then upgraded.
- `V014`/`V015` are still unanchored in `frozen-migration-anchors.txt` (an anchor must be a published commit's SHA; appending it is the follow-up the README describes and is **not** done here because nothing has been pushed).
