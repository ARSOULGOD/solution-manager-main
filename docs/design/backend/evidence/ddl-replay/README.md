# DDL replay harness — re-executable evidence for `eda-walking-skeleton.md` §3

The PostgreSQL-14 execution evidence in [`../ddl-execution-pg14.md`](../ddl-execution-pg14.md) is
**re-runnable from this directory**. Every leg of that report — the two phase files, 254 marked SQL
legs across the obligations (a)–(u), the (s) negative controls in a second cluster, the hostile
probes, and the two live-session (d) legs — is recorded in `legs.json` with the role, container and
database it ran as, the SQL it ran, its expected status, and its expected **signature**: the sorted set
of `ERROR: <SQLSTATE>`, `CONSTRAINT NAME: …` and `WARNING: …` lines PostgreSQL emitted. Inspection legs
carry `expect_contains` fragments (the six-column UPDATE grant, the sixteen-column INSERT grant, the
`has_schema_privilege … f`, the untouched `APPLY_INDETERMINATE` row).

```bash
# from the repo root; Docker required (~90 s). --out must resolve OUTSIDE the repository.
python3 docs/design/backend/evidence/ddl-replay/replay.py --out /tmp/ddl-replay
# the harness's own write-path refusals, no Docker (11 tests, <1 s)
python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py
```

`replay.py` extracts Phase 0 and Phase 1 from the design's ```` ```sql ```` block **by its
`-- PHASE 0` banner** (never by line numbers), compares both md5s with `design-block.json`, starts two
fresh `postgres:14` containers (`ddl-replay-main-<epoch>`, `ddl-replay-negctl-<epoch>`), runs the
manifest in order, writes one log per group into `--out`, removes the containers, and exits **1 on any
drift** — a changed md5, a leg whose status, signature or expected fragment differs from the manifest, a
phase file whose error/warning counts or command-tag multiset changed — after printing every
difference. Exit 0 prints `NO DRIFT`.

A signature is the sorted set of `ERROR: <SQLSTATE>` tokens, `CONSTRAINT NAME: …` lines and
`WARNING: …` lines in a leg's result. Every one of the 162 refusals carries a SQLSTATE
(93× `23514`, 37× `42501`, 13× `P0001`, 10× `23505`, 7× `23503`, 1× `42710`, 1× `23502`); the
constraint-backed ones carry the name too. The first version of the pattern anchored `$` after the
SQLSTATE and so never matched an `ERROR:` line — 52 refusals were checked as "some error happened" and
a wrong-SQLSTATE mutation passed. An independent reproduction found it; mutation B below pins the fix.

## Why this exists

The first three evidence runs were driven interactively; their legs survived only as logs in a session
scratchpad. When Plan 004's fifth review pass forced a DDL change (the `resolution_note` grants), the
legs had to be reconstructed from those logs — the workflow that let structural defects survive several
passes, because re-running the evidence felt expensive enough to avoid. It is now one command, and a
DDL change that is *not* accompanied by a green run of this harness is not evidenced.

## When the design's SQL changes

1. Run the harness. Read **every** reported difference. Each must be the intended consequence of the
   change — a new refusal where a grant was narrowed, a changed column list — and nothing else.
2. Add legs for the change: a refusal **by name** for the new rule and a positive control proving the
   refusal is the rule and not a broken object (the `(j/I6)` legs are the template). Append them to
   `legs.json` in the order they must run.
3. Run with `--record-candidate <dir>` (a directory **outside** this one — the harness refuses one
   inside it, and refuses to run at all while the design file has uncommitted changes, so a candidate
   always names a commit; the directory must **not exist yet** — the harness creates it, so nothing can
   stand in for it). It writes a **candidate** `legs.json` and `design-block.json` there with the
   statuses, signatures, phase error/warning counts and command tags of the run, and **exits 2 (review
   required)** listing every value that would change. **Nothing the harness does can modify tracked
   files** — and that is enforced, not advised: both `--out` and `--record-candidate` are resolved with
   `Path.resolve()` (symlinks followed) and refused if they land anywhere inside the repository, before
   any directory is created, any file written or any container started. `test_paths.py` pins the cases
   an external review found open in earlier versions: `--out reference-run`, an in-repo candidate
   directory, an external symlink pointing into the harness, a pre-existing candidate directory, and a
   symlink alias of the repository itself. The previous in-place `--record` could turn an unintended DDL
   change into a green baseline in one command; the version before that would overwrite the tracked
   reference logs when pointed at them.
4. Review: `diff -u docs/design/backend/evidence/ddl-replay/legs.json <dir>/legs.json`. Every changed
   value must be one you expected from step 1. `expect_contains` fragments are never regenerated —
   update them by hand where a column list or a message changed.
5. Copy the two candidate files over the tracked ones and the run's logs over `reference-run/`, update
   `../ddl-execution-pg14.md`'s header (commit, ranges, md5s, leg counts), update **this README**
   (leg/entry counts, the SQLSTATE distribution recomputed from the new `legs.json` — never adjusted
   by hand — and the `reference-run/` commit and date), and add the changelog row in
   `../../decisions.md` — all in one ordinary, reviewed commit. The baseline changes only by a diff a
   human read.

   **This still applies when the re-record is deliberately deferred to a later task.** A task that
   edits `V002__apply_request.sql`/the design's SQL block but explicitly defers steps 3–5 to a
   separate commit does not thereby defer *this* obligation: it still owes its own `../../decisions.md`
   changelog row recording what changed, on landing, not once the deferred re-record eventually
   happens — the two are independent records of two different events. Skipped once for `BE-D18`'s own
   landing (`56119a4`) and its fix round (`22ebd61`), caught only in a later repair pass (`3a5d423`)
   that had to reconstruct both rows after the fact from a gitignored session report.

## Prove-it-fires (three mutations, measured 2026-09-08)

**Provenance:** measured against the pre-amendment, 184-entry manifest (179 SQL legs) — the one that
predates `BE-D16`/`BE-D17` and their 2026-09-09 re-record. Not re-measured against the current
239-entry manifest (`BE-D18`'s own re-record grew it further, 202 → 239), and re-measuring would mean
mutating the design's own SQL block, which no record-accuracy or re-record task may do. The
difference counts below are historically accurate for the manifest they were run against; a fresh
prove-it-fires pass against the current manifest is future work, not represented here as done.

- **A — `resolution_note` back into the design's UPDATE grant:** **9 differences**, exit 1 — the
  Phase 1 md5, the two catalog legs (column lists), both `(j/I6)` UPDATE refusals flipping
  `REFUSED → OK` (the grant admits them), the owner control and the row-unchanged leg, the `(j) POS`
  resolution (the row is now resolved with a note), and the `(u/I6)` fold-plus-note (now `P0001`, the
  trigger, instead of `42501`, the grant).
- **B — the terminal guard raises `SQLSTATE '23514'` with a different message:** **9 differences**,
  exit 1 — the md5 and the eight legs whose signature was `P0001` (the two `(o)` guards, three `(p)`
  walks, two `(u)` folds, probe `P14`). Under the first signature pattern this mutation passed.
- **C — `CREATE TRIGGER apply_scope_monotonic` renamed `_v2`:** **2 differences**, exit 1 — the md5
  and the topology leg, which pins the three function names, the three trigger names and their
  table/function wiring by `expect_contains`.

## Layout

| Path | Contents |
|---|---|
| `replay.py` | the harness (Python 3 standard library + `docker` on PATH); `validate_paths` is the write-path gate |
| `test_paths.py` | 11 no-Docker tests of the write-path gate (`python3 -m unittest …/test_paths.py`) |
| `legs.json` | the manifest — 259 entries: 254 SQL legs (status + signature, `expect_contains` on the inspection and owner-control legs), 3 phase-file legs (error/warning counts, command-tag multiset — 7 for Phase 0, 25 for Phase 1 — and signature), 2 live-session legs (B blocks ≥ 2 s, reads the in-flight count, a third session observes the `transactionid` lock; both directions) |
| `design-block.json` | the expected line ranges and md5s of the two extracted phases |
| `sessions/sessA.sql`, `sessB.sql`, `sessA2.sql`, `sessB2.sql` | the (d) concurrency scripts: A holds the scope row for 6 s, B blocks on it and then counts in-flight requests; both directions |
| `reference-run/` | the logs of the harness's own clean run against the design at `0fd35c8` (2026-09-09), re-recorded against `BE-D19`/`BE-D20` — `log-<group>.txt` in the `### [STATUS] label (container role db)` / `--- SQL ---` / `--- RESULT ---` shape; `log-d.txt` holds both live-session legs with their third-session observation. The extracted `phase0.sql` / `phase1.sql` are written to `--out` on every run |

Roles and databases are created by the manifest's own SETUP legs (`smdb`, `smdb2`, `sm_installer`);
the containers use PostgreSQL's trust authentication over the container-local socket only.
