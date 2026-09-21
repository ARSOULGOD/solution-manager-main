# PG 14 evidence harness — Agent Gateway design set (`AGW-D*`)

This directory is **tooling**. It contains no design material and no product code.

What is under test is the SQL the **committed** documents carry:

| Source | What is taken |
|---|---|
| `docs/design/backend/agent-gateway/design.md` | every ` ```sql ` block of **§5.2** and **§7.4** |
| `docs/design/backend/agent-gateway/lease-and-registry-sketch.md` | every ` ```sql ` block of **§2 … §7** |
| `docs/design/backend/eda-walking-skeleton.md` | lines **216–253** (Phase 0) and **254–533** (Phase 1), md5-pinned |
| `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` | the **whole file**, md5-pinned |

**There is no default commit.** `extract.py` requires `--commit <sha>` (or `--worktree` for a development
run): a default goes stale the moment the design is edited, and would silently slice the wrong line
ranges. The report of the recorded run is [`../pg14-execution.md`](../pg14-execution.md), which names the
commit it was made against.

`extract.py` slices those blocks **by line range out of `git show <commit>:<path>`** — never out of the
working tree — and records every range and md5 in `extracted/extract.json`. `run.py` executes them.
Neither script edits, reformats or repairs a block.

### Why the telemetry migration is a bootstrap input, and where it sits (S11/F1)

Sketch §5.2's stable-replica `DELETE` reads `sm_data.series`. S10 concluded that no document in this
design set defines that relation and recorded the leg `BLOCKED-dependency`. **That was wrong**: the
relation is defined by a **migration**, not by a document — `V001__telemetry_schema.sql`, whose own lines
3–5 declare it the source of truth for the telemetry schema. It is taken from the committed file **bytes**
(`git show <commit>:<path>`), md5-pinned in `extract.py` exactly like the other two inputs, never
hand-copied.

Its position — **Phase 0 → V001 → Phase 1** — is forced by the inputs' own text, not chosen:

* Phase 0's `CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner` has **no** `IF NOT EXISTS`, so anything that
  creates the schema before it makes Phase 0 fail; V001's own `CREATE SCHEMA IF NOT EXISTS` is then a
  notice-only no-op and the schema keeps Phase 0's ownership.
* V001 is the repo's **only** Flyway `V`-file, so Flyway runs it before any later migration; the walking
  skeleton's Phase 1 is still prose and would become a later one.
* V001 sets `search_path` to `sm_data` and creates unqualified, so under the `SET ROLE sm_dp_owner` the
  harness prefixes (Flyway's `initSql`, the convention Phase 1 states for itself) its tables land in
  `sm_data` owned by `sm_dp_owner`.
* Consequence, recorded and not hidden: Phase 1's `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES` runs
  afterwards and does not reach already-existing objects, so `sm_dp_runtime` holds no privilege on
  `placement`/`series` — correct, since those grants are the `DM-D*`/`BE-D*` owners' and §5.2's `DELETE` is
  the migrator's.

🔴 **One HARNESS-ONLY step exists because of a gap between two committed documents**, and it is labelled in
the log: V001's `CREATE SCHEMA IF NOT EXISTS` needs `CREATE` on the **database**, which Phase 0 grants to
no `sm_dp_*` role, so as `sm_dp_migrator` it raises `42501: permission denied for database` — measured.
The harness issues the missing grant as the superuser, the way it issues the role passwords Phase 0 also
deliberately omits. **V001 is not edited.** The gap is routed to the `BE-D*` owners (sketch §5.2's
bootstrap note).

## The recorded run is the coordinator's

Anything the author of this harness runs is **development**, not evidence. The recorded run is:

```sh
cd docs/design/backend/agent-gateway/evidence/pg14-harness
python3 extract.py --commit <sha>                    # REQUIRED: there is no default commit
<venv>/bin/python run.py --fresh \
    --out ../runs/$(date -u +%Y%m%dT%H%M%SZ)
```

with `<venv>` a Python 3 environment holding `psycopg[binary]` (see `requirements.txt`):

```sh
python3 -m venv /tmp/pgvenv && /tmp/pgvenv/bin/pip install -r requirements.txt
```

**Python version:** `psycopg[binary]==3.3.5` declares `Requires-Python: >=3.10` and ships classifiers for
CPython **3.10, 3.11, 3.12, 3.13 and 3.14** (PyPI metadata for `psycopg` 3.3.5; verified 2026-09-11). Any
of those runs the harness; it uses no syntax newer than 3.10.

`extract.py --worktree` reads the design files from the **working tree** instead of a commit. That is
for **development only** — debugging the harness against uncommitted edits — and `extract.json`
records `"commit": "WORKTREE"`, which `run.py` prints as a banner, so a run made that way can never be
mistaken for evidence. A recorded run passes `--commit <sha>`.

`--fresh` removes and re-creates the container (`postgres:14.24`, host port 55432 by default) so the
run starts from an empty cluster; `--container NAME` and `--port N` override those. **`run.py` exits
non-zero if any leg is `FAIL` or `BLOCKED-dependency`** — that is the exact condition in the code.

`BLOCKED-dependency` is the verdict for a leg whose statement depends on a relation **nothing in the
bootstrap provides**: it names the missing relation, is never `OK` and never `FAIL`, and keeps the run
non-zero, because blocked is not green. **No leg produces it since S11** — the one that did, sketch
§5.2's stable-replica `DELETE`, was blocked only because `sm_data.series` was missing from the
bootstrap, and V001 now supplies it. The verdict and its non-zero exit are kept for the next such
dependency rather than deleted.

Output in `--out DIR`:

| file | contents |
|---|---|
| `log.txt` | every statement executed, its fixture values, and one `### [OK] / [REFUSED] / [FAIL] / [N/A-engine]` marker line per leg |
| `results.json` | one record per leg: obligation, leg, phase, expected, observed, sqlstate, elapsed_ms, verdict |
| `results.md` | the legs table grouped by obligation, verdict counts, `select version()`, the bootstrap md5s, the block md5s, the container name |

## What the harness adds, and what it never adds

**Added, and labelled HARNESS-ONLY in the log:**

* `ALTER ROLE sm_dp_runtime|sm_dp_migrator PASSWORD …` — Phase 0 deliberately carries no credentials
  and the image's `pg_hba.conf` wants scram over TCP. `postgres` is used for catalog inspection only.
* `GRANT CREATE ON DATABASE … TO sm_dp_owner` — the install-time grant V001's `CREATE SCHEMA IF NOT
  EXISTS` needs and Phase 0 does not carry (the 🔴 note above). A gap between two committed documents,
  routed, not repaired.
* `SET ROLE sm_dp_owner;` as the first line of each design DDL/DCL group **and of V001** — which is
  what §2, §5.2 and §7 say the migrator does, and what Flyway's `initSql` does for a migration.
* Fixture rows (identities, sessions, commands, leases, replicas, the backend `apply_*` chain that
  `agent_command`'s foreign key needs, and the `placement`/`series` pair the `(L13)` series legs
  require) and fixture **values** for the documents' own `$placeholders`.
* Threading barriers, and `lock_timeout`/`statement_timeout` of 5 s on both sides of a deadlock leg.

**Never added:** a rewritten, re-indented, composed or "fixed" version of a document's statement. A
planted-violation variant is derived from the extracted statement by a **stated textual removal**
that is printed into the log (`-- PLANTED VARIANT: removed …`), or by a DDL change named in the log
(`DROP TRIGGER …`, `ALTER TABLE … DROP CONSTRAINT …`, `REVOKE …`). Restoring means re-applying the
extracted DDL: `rebuild()` drops `sm_data`, re-creates it from Phase 0's own two schema lines, and
re-runs V001, Phase 1 and all four design groups — `DROP SCHEMA … CASCADE` takes `placement` and
`series` with it, so V001 is part of the restore in the same position it holds in the bootstrap.

If a committed block is not executable as extracted, the harness does **not** compose or repair it:
the leg is recorded `FAIL` with the exact citation and error, and it is reported as a finding.

## Development runs

```sh
<venv>/bin/python run.py --fresh --only "(L18" --out /tmp/dev
```

`--only` filters on `"<obligation>/<leg>"`. Runs without `--fresh` fail in bootstrap, because Phase 0
creates roles that already exist — that is deliberate: every run starts from a clean cluster.
