# PostgreSQL-14 execution evidence — `eda-walking-skeleton.md` §3 DDL/DCL

**Verdict: YES — the sketch stands green for (a)–(u).** Both phases execute verbatim from the
extracted bytes, all 19 database-enforced obligations pass, and the two engine-only obligations are
labelled (one measured to confirm no DB artifact exists). **Run 7 (2026-09-09, against
`0fd35c8` — `BE-D19`'s `ReplicaRef`/`resolution_is_evidence` grammar predicate and `BE-D20`'s
`attempt_open_fence` partial unique index, landed in `V002__apply_request.sql`/`V005__apply_attempt.sql`
and mirrored into the design's §3.1 SQL block): 254 SQL legs** — the 234 of run 6 replayed in order
(six pre-existing `(i)`/`(m)` legs repaired by inserting two SETUP "close the open attempt" legs around
them, not by editing their own SQL or expectations — see below), plus 20 new legs: 15 for `BE-D19`'s
`ref`-content grammar (both the apply and restart branches of `resolution_is_evidence`) and 5 for
`BE-D20`'s `attempt_open_fence` (two SETUP closes reused by the repair above, plus a dedicated
fresh-intent fence-by-name leg and its retry-after-close positive control) — every one emitting an
`[OK]`/`[REFUSED]` marker into a named log file. Zero defects; three pre-emption facts measured and
disposed of, per `L20`. **Run 6 (2026-09-09, against
`22ebd61` + `BE-D18`'s ten predicates landed in `V002__apply_request.sql`): 234 SQL legs** — the 197
of run 5 replayed in order (one exemplar row retargeted, four fixture values element-completed —
see below), plus 37 new legs for `resolution_is_evidence`'s per-element and key-exactness
predicates — every one emitting an `[OK]`/`[REFUSED]` marker into a named log file. Zero defects.
**Run 5 (2026-09-09, against
`90cce61` + the `BE-D16`/`BE-D17` amendments landed in `V002__apply_request.sql`): 197 SQL legs** —
the 179 of run 4 replayed in order (two exemplar rows deliberately retargeted, one fixture value
corrected — see below), plus 18 new legs for `staged_requires_manual` and `resolution_is_evidence`'s
operation-discriminated CASE — every one emitting an `[OK]`/`[REFUSED]` marker into a named log file.
Zero defects. **Run 4 (2026-09-08, against `d87c2e7`): 179
legs** — the 168 of run 3 replayed in order with every refusal's SQLSTATE and constraint name
unchanged, plus 11 new legs for the `resolution_note` grant change — every one emitting an
`[OK]`/`[REFUSED]` marker into a named log file. Zero defects; three informational notes.

---

## 0. Run 7 — `BE-D19`/`BE-D20`, replayed leg by leg

**Why a seventh run.** `BE-D19` (a dedicated `ValueGrammar.REPLICA_REF` grammar, replacing `resolution_is_evidence`'s bare `like_regex "\S"` on every `ref`) and `BE-D20` (a second, independent partial unique index, `attempt_open_fence ON sm_data.apply_attempt (intent_id) WHERE outcome IS NULL`) landed in `V002__apply_request.sql`/`V005__apply_attempt.sql` and the design's own §3.1 SQL block (commits `4ecf5e7`, `e1b8490`, `0fd35c8`), each leaving this harness's tracked baseline deliberately red per its own stated instruction (`evidence/ddl-replay/README.md` §"When the design's SQL changes"). This run — S3, the dedicated re-record task for this repair wave — does the re-recording for both amendments together, in one pass, since both changed `V002`/Phase 1 in the same window.

| Item | Value |
|---|---|
| Commit under test | **`0fd35c8`** — "Mirror `BE-D19` and `BE-D20` into the design's own §3.1 SQL block" — the state `V002__apply_request.sql`/`V005__apply_attempt.sql`/the design's §3.1 SQL block have carried since |
| Phase 0 extract | lines **228–265**, md5 **`768e195b7c17861d97e402c5318be51b`** — unchanged |
| Phase 1 extract | lines **266–615** (was 266–610), md5 **`366c7d84c16425f967a2464a348d007e`** (was `a4d51cf21c30135093e94b484f07b53e`) — `+5` lines: the `BE-D19` predicate-text substitution (no line-count change, same statement) plus `BE-D20`'s one new `CREATE UNIQUE INDEX attempt_open_fence` statement (+5 lines including its comment) |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu` — fresh `postgres:14` containers, removed by the harness |
| Harness | unchanged: [`ddl-replay/`](ddl-replay/) — `replay.py`, `legs.json` (now 259 entries: 254 SQL legs, 3 phase legs, 2 live-session legs), `sessions/`, `reference-run/` |
| Method | `python3 docs/design/backend/evidence/ddl-replay/replay.py --out <dir>` first, reading every reported difference (README §"When the design's SQL changes" step 1); six pre-existing legs repaired **by inserting SETUP legs around them**, never by editing their own SQL/expectations (step 2, this run's own variant — see below); 20 new legs added for both amendments' own obligations (step 2); `--record-candidate <dir outside the repo>` (step 3, exits 2); `diff -u` against the tracked files (step 4 — one line, the phase-1 file leg's command-tag count); tracked files and this document updated together (step 5); re-run from the checked-in artefacts for `NO DRIFT` (step 6) |

**Step 1 — every difference the first plain run reported, before any manifest edit** (eight
differences, every one explained by `BE-D19`/`BE-D20` and nothing else):

| Difference | Judgement |
|---|---|
| `phase1` md5 (`a4d51cf2…` → `366c7d84…`, lines 266–610 → 266–615) | **Expected.** The design's SQL block changed exactly as `BE-D19`/`BE-D20` describe; re-recorded in `design-block.json` |
| `PHASE 1` file leg: command-tag multiset gains one more `CREATE INDEX` | **Expected.** `BE-D20` is a new statement, not an in-place text edit (unlike `BE-D19`'s substring substitution) — the tag count moves 24 → 25, exactly as `BE-D20`'s own amendment text predicts |
| `(s) Phase 1 topology: every table+index owned by sm_dp_owner`: `expect_contains` fragment stale (`21 \| 21`) | **Expected.** One new index (`attempt_open_fence`), owned by `sm_dp_owner` like every other object born under `SET ROLE sm_dp_owner` — measured `22 \| 22`; fixed by hand (never regenerated) |
| `(i) REFUSE isolated (unused attempt_no=9): same tenant, WRONG apply_id`: signature `apply_attempt_tenant_id_apply_id_intent_id_fkey`/`23503` → `attempt_open_fence`/`23505` | **Expected pre-emption, not accepted as the new baseline — repaired, see below.** PostgreSQL evaluates a unique-index insertion before an `AFTER ROW` FK trigger fires; since `attempt_no=1` (from the leg immediately above) is still open for this intent, the fence refuses the row before the FK ever gets a chance to |
| `(i) REFUSE: DIFFERENT tenant, right apply/intent -> triple FK`: same pre-emption | **Same cause, same repair** |
| `(i) REFUSE: attempt step DISAGREES with its intent -> (intent_id, step) FK`: same pre-emption | **Same cause, same repair** |
| `(m) POS: attempt_no = 2 (a legal retry)`: status `OK` → `REFUSED` (`attempt_open_fence`) | **Expected pre-emption, not accepted — repaired.** The recorded sequence never closed `attempt_no = 1` first, so this "legal retry" was never actually legal under the new fence; `BE-D20`'s own obligation 2 is precisely that a retry-after-close must stay legal, so the fix must make this leg genuinely be one, not relabel the refusal |
| `(m) REFUSE: duplicate (intent_id, attempt_no)`: signature `apply_attempt_intent_id_attempt_no_key`/`23505` → `attempt_open_fence`/`23505` | **Expected pre-emption, not accepted — repaired.** Same SQLSTATE both ways, but the wrong constraint: because the preceding leg's `attempt_no=2` row never landed (previous row), this leg's own `attempt_no=2` collided with nothing on `(intent_id, attempt_no)` and instead tripped the still-open `attempt_no=1` fence — a leg meant to isolate `ATTEMPT_NO_UNIQUE` was pre-empted by an unrelated mechanism |

**The known-drift decision, stated (README step 3): repaired by insertion, not by editing the six
legs' own SQL or expectations.** All six pre-empted legs are restored to their *original* recorded
values — verbatim, byte-for-byte — by inserting two new SETUP legs around them that close the
attempt each subsequent leg must not find open:

- **`(m) SETUP: close attempt_no=1`**, inserted between `(i) POS: attempt with the CORRECT triple`
  and the three FK-refusal legs. Measured (live PG 14.24, this session): with `attempt_no=1` closed
  first, all three FK legs report their own named FK (`23503`) again, cleanly, and the fence never
  fires — confirmed on an isolated fresh-intent reproduction before touching `legs.json`, then
  re-confirmed by the full harness run reporting the *original* recorded FK signatures with zero
  drift.
- **`(m) SETUP: close attempt_no=2 / a0000006`**, inserted between `(m) POS: attempt_no = 2 (a legal
  retry)` — now genuinely legal, since `attempt_no=1` is closed — and `(m) REFUSE: duplicate
  (intent_id, attempt_no)`. With `attempt_no=2`'s row closed before the duplicate-number leg runs,
  the duplicate insert collides with `apply_attempt_intent_id_attempt_no_key` alone (a plain,
  non-partial unique index that fires regardless of `outcome`) and never touches the fence.

**Why "close the open attempt first," not "route through a fresh intent with no open attempt."**
Both options were live per the implementing task's own instructions; "close first" was chosen
because every one of the six affected legs already shares the *same* intent (`e0000001`) and the
*same* deliberate numbering scheme (attempt_no 1, 2, 9, 10, 11, 12–14 — chosen specifically to avoid
colliding with `ATTEMPT_NO_UNIQUE`); routing through a fresh intent would have meant re-numbering
every one of the six legs' fixtures and renaming every cross-reference to `e0000001` in their own
labels, for no isolation benefit the close-first approach doesn't already give — a SETUP leg is the
established idiom this manifest already uses everywhere a fixture needs a specific pre-state (the
`(j)`/`(j/I6)` groups' own SETUP legs are the same pattern). No existing leg's SQL text or recorded
`status`/`signature` changed; only two legs were added.

**The three pre-emption claims, measured directly (not assumed) before acting on them, against the
actual `V002`/`V005` DDL on a live PostgreSQL 14.24 container — first on an isolated fresh-intent
reproduction, then re-confirmed by the full harness run:**

1. **`(m) POS: attempt_no = 2 (a legal retry)` flips `OK` → `REFUSED` (`attempt_open_fence`, `23505`)
   under the unrepaired sequence** — confirmed: the recorded sequence never closes `attempt_no = 1`
   first, so the second open collides with the still-open first. Repaired by construction (the SETUP
   leg above), not by relabelling.
2. **The three FK legs for intent `e0000001` report `attempt_open_fence` instead of their intended FK
   constraint name, under the unrepaired sequence** — confirmed on both the full-manifest run and an
   isolated reproduction (fresh tenant/apply/intent, identical shape): PostgreSQL evaluates a
   unique-index insertion (which fires synchronously as part of the row's own insert) before the
   `AFTER ROW` trigger backing a `FOREIGN KEY` constraint (which fires after all per-row index work
   completes) — so when both would refuse the same row, the unique index wins the race every time,
   deterministically, not as a scheduling accident.
3. **The three CHECK legs (`baseline_only_for_lifecycle`, `attempt_time_ordered`, `outcome_known`, at
   attempt_no 12/13/14) are unaffected** — confirmed: no drift was reported for any of the three in
   either the unrepaired or repaired full-manifest run, and the isolated reproduction shows why:
   `CHECK` constraints are evaluated as part of `ExecConstraints`, before the row is written to the
   heap and before any index insertion, so a `CHECK` violation aborts the statement before the unique
   index (and therefore the fence) is ever reached — regardless of whether `attempt_no=1` is open or
   closed at the time.

**The `L20` sweep — every apply_attempt-touching leg in the manifest, examined for the same
pre-emption class, not only the six named above.** `attempt_open_fence` can only be tripped by an
`INSERT`/`UPDATE` on `sm_data.apply_attempt` that leaves a row with `outcome IS NULL` (open) for an
`intent_id` that already has one. A full-text search of the pre-existing 234-leg manifest for every
`INSERT INTO sm_data.apply_attempt` and `UPDATE ... apply_attempt` statement found exactly twelve
matches, all examined:

| Leg | Disposition |
|---|---|
| `(i) POS: attempt with the CORRECT triple` (opens `attempt_no=1` for `e0000001`) | unaffected — the opener, not a victim; unchanged |
| `(i) REFUSE isolated (unused attempt_no=9)`, `(i) REFUSE: DIFFERENT tenant`, `(i) REFUSE: step DISAGREES` (three FK legs) | **pre-empted — repaired** by the `close attempt_no=1` SETUP leg (above) |
| `(i) extra: baseline_only_for_lifecycle`, `(i) extra: attempt_time_ordered`, `(i) extra: outcome_known` | unaffected, measured (pre-emption claim 3 above) — `CHECK` fires before the fence is ever reached; unchanged |
| `(m) REFUSE: attempt_no = 0`, `(m) REFUSE: attempt_no = -1` | unaffected — `attempt_no_positive` is also a plain `CHECK`, same reason; confirmed by the harness reporting zero drift for either leg; unchanged |
| `(m) POS: attempt_no = 2 (a legal retry)` | **pre-empted — repaired**, now genuinely legal (`BE-D20` obligation 2) |
| `(m) REFUSE: duplicate (intent_id, attempt_no)` | **pre-empted — repaired** by the `close attempt_no=2` SETUP leg (above), restoring `ATTEMPT_NO_UNIQUE` isolation |
| `P26 SETUP for P27: create an attempt row that definitely exists` (`a0000030`, `attempt_no=30`, outcome unset, same intent `e0000001`) | **traced, found unaffected — not by coincidence.** `(u) POS: the attempt-pruning DELETE half works under the runtime grant` (leg 200, `DELETE FROM sm_data.apply_attempt WHERE tenant_id='1111…' AND started_at < now()+interval '1 day'`) runs **before** P26 and deletes every `apply_attempt` row for tenant `1111…` so that by the time P26 opens `a0000030` for `e0000001`, no other open row exists for that intent regardless of the fence. **Correction (independent re-review, state simulation over the manifest):** this justification as first written said the DELETE clears "`a0000001`, still open at that point". It is not open — leg 98, the `(m) SETUP: close attempt_no=1 (BE-D20)` leg *this repair inserted*, closes it, and leg 108 closes `a0000006`. The conclusion stands and is in fact stronger than the reason given: after leg 108 nothing is ever open on intent `e0000001` again, so P26 is unaffected independently of the DELETE. The original wording described the pre-repair world. Confirmed by direct query against a live container carrying the full, unmodified manifest's data: after leg 200 runs, `sm_data.apply_attempt` holds zero rows for tenant `1111…` until P26 re-populates it. No repair needed; the harness's own "no drift" for this leg, both before and after, is correct as recorded |
| `P27 re-point that apply_attempt to a DIFFERENT tenant`, `P28 re-point ... DIFFERENT apply_id` (both `UPDATE`s on `a0000030`, not `INSERT`s) | unaffected — an `UPDATE` that changes `tenant_id`/`apply_id` but not `outcome` or `intent_id` never creates a second open-row entry in the fence's partial index; both already refuse on their own named FK, unrelated to the fence |

No other `INSERT`/`UPDATE` against `sm_data.apply_attempt` exists anywhere else in the 234-leg
manifest (confirmed by exhaustive search, not sampling). Six legs needed repair; one (`P26`) was
traced and found safe for a reason worth recording (the blanket prune at leg 200), not assumed safe
because nothing was reported.

**The equivalent sweep for `BE-D19`'s `ref`-grammar tightening: every `"ref":` literal in the
pre-existing manifest, checked against both the old `\S` pattern and the new `REPLICA_REF` class.**
An exhaustive search of every `resolution_observed`/`replicas`/`restartsObserved` payload in the
234-leg manifest for a `"ref":` key found exactly five distinct literal values in use, all four
already accounted for by clauses that never reach the grammar check at all:

| Literal(s) | Legs | Why unaffected |
|---|---|---|
| `"   "` (three spaces), `"  "` (two spaces) | the `(j)` "BLANK ref" legs, apply and restart | blank under **both** the old `\S` pattern (zero non-whitespace characters) and the new `REPLICA_REF` class (no whitespace character is a class member) — same refusal, same underlying defect, no confound |
| `7` (a JSON number, not a string) | the `(j)` "NON-STRING ref" legs, apply and restart | refused by `@.ref.type()=="string"` — a type check that short-circuits the `AND`-chain before `like_regex` (old or new) is ever evaluated |
| `"r0"`, `"r1"`, `"r2"` | every other `(j)`/`(j/I6)`/`(j) POS` leg (46 occurrences) | within **both** the old `\S` class and the new `REPLICA_REF` class (`^[A-Za-z0-9._:/@+\\-]{1,255}$`) — accepted identically either way |

No existing leg's `ref` value sits in the gap between the two grammars (a value `\S` would accept but
`REPLICA_REF` refuses, or vice versa) — confirmed both by this literal-by-literal search and by the
full harness run reporting zero status/signature drift for every `(j)`/`(j/I6)` leg outside the eight
named above. This is the pre-existing manifest only; the 15 new `BE-D19` legs below are additions
that exercise the grammar boundary deliberately.

**Step 2 — 20 new legs, in the order they run, each with its positive control where applicable:**

| Leg(s) | Result |
|---|---|
| `BE-D20` `(m) SETUP`: a fresh intent (`e0000020`, under `d0000001`) with `attempt_no=1` opened, dedicated to the fence-by-name demonstration (not the entangled `e0000001` sequence) | `[OK]` |
| `BE-D20` `(m) REFUSE, NO RACE`: a second open (`attempt_no=2`, same intent, `outcome` `NULL`) while `attempt_no=1` is still open | `attempt_open_fence`, `23505` — **by name**, not `apply_attempt_intent_id_attempt_no_key` (the two numbers differ) |
| `BE-D20` `(m) POS control`: close `attempt_no=1`, then a fresh `attempt_no=2` for the same intent | `[OK]` — the fence forbids a concurrent second open, not a legitimate sequential retry |
| `BE-D19` `(j) SETUP`: fresh `APPLY_INDETERMINATE` apply/restart fixtures (`c0000051` gen=6, `c0000052`), dedicated to the `ref`-grammar legs | `[OK]` |
| `BE-D19` `(j) REFUSE` ×7 (apply, `stagedGeneration=6` matching throughout — the equality clause never fires): `ref` = a lone U+001C control character; a lone U+001F control character; the hostile literal carrying quotes, a backslash, a space, `=` and an accented letter; a newline-carrying literal; the empty string; a CJK string; 256 characters (one past the boundary) | `resolution_is_evidence` ×7, each `23514` |
| `BE-D19` `(j) POS`: the same apply fixture, made well-formed, closing the `REFUSE` chain | `[OK]` — proves the seven refusals test the `ref` content, not a broken row |
| `BE-D19` `(j) POS` ×3 (fresh standalone rows, `f0000110`–`f0000112`, born `INDETERMINATE_RESOLVED`, the `(j/I6)` template): a 200-character Kubernetes-shaped `ref` (past the withdrawn `VALUE` grammar's 128 cap); a systemd-escaped-name fragment carrying one literal backslash; a `ref` of exactly 255 characters (the boundary) | `[OK]` ×3 — the two disqualifying cases that ruled out `VALUE`, plus the length boundary, all measured accepted |
| `BE-D19` `(j) REFUSE` ×2 (restart): `ref` = a lone U+001C; a lone U+001F | `resolution_is_evidence` ×2, each `23514` |
| `BE-D19` `(j) POS`: the same restart fixture, made well-formed, closing the `REFUSE` chain | `[OK]` |

**Result: 254 SQL legs (162 refused, 92 ok) + 3 phase legs + 2 session legs — `NO DRIFT` on the
final run from the checked-in artefacts.** Every new/repaired leg matched its expected value on the
first `--record-candidate` pass — `diff -u` against the tracked `legs.json` reported a single line
(the phase-1 file leg's command-tag count, expected and described above); `design-block.json`'s
`phase1` entry is the only other tracked value that changed. Totals: run 6's 234 (152 refused, 82 ok)
+ 9 new `BE-D19` refusals (7 apply + 2 restart) + 1 new `BE-D20` refusal (the dedicated fence-by-name
leg) + 6 new `BE-D19` positive controls (1 SETUP + 1 chain-closing POS + 3 standalone POS + 1
restart chain-closing POS) + 4 new `BE-D20` positive controls (2 SETUP-close legs reused by the
repair above + 1 dedicated SETUP + 1 retry-after-close POS control) = 254 (162, 92). The SQLSTATE
distribution (recomputed from `legs.json`, never adjusted by hand): 93× `23514` (was 84, **+9** —
exactly `BE-D19`'s new refusals, all on `resolution_is_evidence`), 37× `42501`, 13× `P0001`, 10×
`23505` (was 9, **+1** — `BE-D20`'s dedicated fence-by-name leg; the six repaired legs restore their
*original* constraint names and contribute no net change), 7× `23503`, 1× `42710`, 1× `23502` —
every other SQLSTATE unchanged, as expected: neither amendment touches a privilege, trigger, or
type-check refusal.

---

## 1. Run 6 — `BE-D18`, replayed leg by leg

**Why a sixth run.** `BE-D18` (this repair wave's R6, executed in `56119a4`, with a fix round in
`22ebd61`) appended ten `jsonb_path_exists(…, silent => true)` predicates to `resolution_is_evidence`
— six for `apply`, four for `restart` — closing the container-only escape a fourth review found
(`{"replicas":[{}]}`, `{"restartsObserved":[null]}`) and the `lax`-mode array-unwrap escape the fix
round closed on top of it. Both landing commits left this harness's tracked baseline deliberately
red, per their own stated instruction (`evidence/ddl-replay/README.md` §"When the design's SQL
changes"); this run (R7 of the repair wave) does the re-recording. R7's own residual-fix commit
(the four small documentation/fixture corrections that precede this one) made no SQL change and is
not itself evidenced here.

| Item | Value |
|---|---|
| Commit under test | **`22ebd61`** (`22ebd61bfbb50eac086e5095a3058b5ffab7dd77`) — "Fix R6 fix round 1: restore four pre-existing clauses BE-D18's own landing had silently unpinned" — the state `V002__apply_request.sql`/the design's §3.1 SQL block have carried since |
| Phase 0 extract | lines **228–265**, md5 **`768e195b7c17861d97e402c5318be51b`** — unchanged |
| Phase 1 extract | lines **266–610** (was 266–572), md5 **`a4d51cf21c30135093e94b484f07b53e`** (was `4ecc44700ad165b80c2480fc6bbb7a46`) — `+38` lines from the ten new predicates |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu` — fresh `postgres:14` containers, removed by the harness |
| Harness | unchanged: [`ddl-replay/`](ddl-replay/) — `replay.py`, `legs.json` (now 239 entries: 234 SQL legs, 3 phase legs, 2 live-session legs), `sessions/`, `reference-run/` |
| Method | `python3 docs/design/backend/evidence/ddl-replay/replay.py --out <dir>` first, reading every reported difference (README §"When the design's SQL changes" step 1); legs added for the ten new predicates' refusals-by-name and positive controls (step 2); `--record-candidate <dir outside the repo>` (step 3, exits 2); `diff -u` against the tracked files (step 4, empty for `legs.json` — every added/retargeted leg's status and signature was already correct on the first `--record-candidate` pass; `design-block.json`'s `phase1` entry was the only tracked value that changed); tracked files and this document updated together (step 5); re-run from the checked-in artefacts for `NO DRIFT` (step 6) |

**Step 1 — every difference the first plain run reported, before any manifest edit, and its
judgement** (twelve differences, every one explained by `BE-D18` and nothing else):

| Difference | Judgement |
|---|---|
| `phase1` md5 (`4ecc4470…` → `a4d51cf2…`, lines shift 266–572 → 266–610) | **Expected.** The design's SQL block changed exactly as `BE-D18` describes; re-recorded in `design-block.json` |
| `(j) REFUSE: bogus resolution_outcome -> resolution_known`: signature `resolution_known` → `resolution_is_evidence` | **Expected, and a fixture bug, not a rule change to accept — the sixth instance of this same repair wave's own `L20` pattern.** This leg's shared exemplar carries `replicas: ["r0","r1"]` — bare strings, element-incomplete — which `BE-D18`'s new well-formedness predicate now refuses on its own; the row now violates both `resolution_known` (the leg's intended target: `resolution_outcome='maybe'` is out of enum) and `resolution_is_evidence`, and PostgreSQL reports whichever sorts first lexically (`resolution_is_evidence` before `resolution_known`). **Decision: retarget**, not accept — see below |
| `(j/I6) POS control: the OWNER may insert the row naming resolution_note`: status `OK` → `REFUSED` | **Expected, the same fixture bug.** `"replicas": ["r0"]` is the identical bare-string escape; a positive control silently becoming a refusal is not a new baseline to accept — fixed by element-completing |
| `(j/I6) POS control: the runtime's OWN insert of a resolved row succeeds WITHOUT naming the column`: status `OK` → `REFUSED` | **Expected, the same fixture bug**, fixed the same way |
| `(j) POS: a FULLY-EVIDENCED INDETERMINATE_RESOLVED succeeds`: status `OK` → `REFUSED` | **Expected, the same fixture bug** (`replicas: ["r0","r1"]`) — and, left unfixed, the root cause of the next six differences: this leg is what finally resolves `c0000001` out of `apply_inflight_fence`, and while it fails, `c0000001` stays in-flight |
| `(j) SETUP: a RESTART request and a second APPLY request, both APPLY_INDETERMINATE`: status `OK` → `REFUSED` (`apply_inflight_fence`, `23505`) | **Expected knock-on, not an independent defect.** With `c0000001` never resolved out (the difference above), `(tenant, instance, placement=NIL)` is still occupied when this leg's first `INSERT` (`c0000031`, restart, the same triple) runs — collision. Fixing the difference above removes this one too |
| `(j) REFUSE: restart resolution with an INVENTED stagedGeneration` and the four restart-branch legs immediately after it (the three rejection-class-iii legs plus the rejection-class-i, direction-1 apply-shaped-evidence-on-a-restart leg — five legs total): status `REFUSED` → `OK`, signature emptied | **Expected knock-on of the SETUP failure**: `c0000031` was never inserted, so each `UPDATE … WHERE apply_id='c0000031…'` affects zero rows and returns `OK` with no error. Not a rule regression — there is no row left to test |
| `(n) RESOLVE 'applied' AND RAISE the watermark in ONE transaction, then compare in-txn`: status `OK` → `REFUSED` (`resolution_is_evidence` **and** `25P02`) | **Expected, the same fixture bug** (`replicas: ["r0"]`), inside an explicit `BEGIN…COMMIT`: the failed `UPDATE` aborts the transaction, so every statement after it in the same block reports `25P02` ("current transaction is aborted") on top of the `23514` |

**The known-drift decision, stated (README step 3): retargeted, not accepted.** Only one leg's
*violated constraint name* actually changed (`resolution_known` → `resolution_is_evidence`) — the
`(j) REFUSE: bogus resolution_outcome` leg above. Retargeted by element-completing its `replicas`
array (`["r0","r1"]` → two well-formed objects, `stagedGeneration` unchanged at `1`, matching
`c0000001`'s own `generation`), restoring `resolution_known`'s isolation exactly as `BE-D17`'s R4
retarget restored `(h)`'s `state_fits_operation` isolation. The other four fixture bugs
(`(j/I6)` ×2, `(j) POS`, `(n)`) do not change which constraint fires when fixed — they were already,
correctly, targeting `resolution_is_evidence` (as a container-shape leg) or nothing at all (the two
positive controls) — so element-completing their `replicas` arrays the same way is a fixture repair,
not a retarget decision in the README's sense. All five are the identical failure mode this repair
wave's `L20` lesson names: a legacy, element-incomplete fixture (`["r0","r1"]`, `["r0"]`) sitting
unchanged beside a new predicate whose whole job is to complete exactly that shape — found here in
the DDL-replay harness's own independently-tracked manifest, not only in the Java suite `BE-D18`'s
own landing commit already fixed six instances of.

**Step 2 — 37 new legs, in the order they run, each with its positive control:**

| Leg(s) | Result |
|---|---|
| `SETUP`: two fresh `APPLY_INDETERMINATE` fixtures dedicated to `BE-D18`'s own legs (`apply` `c0000041`, `generation=5`; `restart` `c0000042`) | `[OK]` |
| `REFUSE` ×19 (apply, `stagedGeneration=5` matching throughout — the equality clause never fires, isolating each new predicate alone): element type-check (`replicas:[[]]`, `[[{ok}]]`); element well-formedness (`[{}]`, `[null]`, missing/blank/non-string `ref`, missing/non-boolean `restarted`, `observedHash` absent entirely); element key-set (an extra element key); top-level key-set (an extra top-level key); hash format (no colon-hex shape, non-string, 15-hex, 129-hex, uppercase); `observedHash` array-valued (empty, and holding an otherwise-valid hash string) | `resolution_is_evidence` ×19 — each by name |
| `POS` (apply): the same fixture, made well-formed | `[OK]` — proves the 19 refusals test the malformation, not a broken row |
| `REFUSE` ×11 (restart): element type-check (`restartsObserved:[[]]`, `[[{ok}]]`); element well-formedness (`[{}]`, `[null]` — the exact escape `BE-D18` exists to close — missing/blank/non-string `ref`, missing/non-boolean `restarted`); element key-set (`observedHash` present at all, even `null`); top-level key-set (an extra top-level key) | `resolution_is_evidence` ×11 |
| `POS` (restart): the same fixture, made well-formed | `[OK]` — proves the 11 refusals test the malformation, not a broken row |
| `POS` ×4 (fresh rows, `55555555…`/`b3b3b3b3…`, mirroring the `(j/I6)` template): a valid 16-hex `observedHash` (the `{16,128}` floor); a valid 128-hex `observedHash` (the cap); several `replicas` mixing a present-`null` and a present-hash `observedHash`; several `restartsObserved` elements | `[OK]` ×4 |

**Result: 234 SQL legs (152 refused, 82 ok) + 3 phase legs + 2 session legs — `NO DRIFT` on the
final run from the checked-in artefacts.** Every one of the 37 new/retargeted legs matched the
expected value on the first `--record-candidate` pass — `diff -u` against the tracked `legs.json`
was empty; only `design-block.json`'s `phase1` entry changed. Totals: run 5's 197 (122 refusals, 75
positive controls) + 30 new refusals (19 apply + 11 restart) + 7 new positive controls (the `SETUP`,
2 fixture `POS` legs, and the 4 extra `POS` legs) = 234 (152, 82). The SQLSTATE distribution
(recomputed from `legs.json`, never adjusted by hand): 84× `23514` (was 54, **+30** — exactly the new
refusals, all on `resolution_is_evidence`), 37× `42501`, 13× `P0001`, 9× `23505`, 7× `23503`, 1×
`42710`, 1× `23502` — unchanged apart from the `23514` count, as expected: none of `BE-D18`'s new
predicates touch a privilege, trigger, FK, or role-topology refusal.

### Addendum — two exemplars that stopped isolating their named clause, and four checked alongside them (2026-09-09)

**Why this wasn't caught above.** Step 1's twelve differences were exactly the legs whose recorded
`status`/`signature` *moved*. Two legs elsewhere in the same 197-leg carry-forward have a bare-string
`replicas` element too, but happen to refuse via `resolution_is_evidence` either way — before
`BE-D18` (for the clause their own label names) and after it (for the new well-formedness predicate)
— so their recorded value never moved and `--record-candidate` had nothing to flag. An independent
review measured this directly against the shipped CHECK on a live PostgreSQL 14.24 container:

- **`(j) REFUSE: MISSING resolved_by`** carried `{"stagedGeneration": 7, "replicas": ["r0","r1"]}`
  against `c0000001` (`generation = 1`) — **triple-cause**: supplying `resolved_by` alone still
  refuses (the generation mismatch and the bare-string elements remain); additionally correcting
  `stagedGeneration` to `1` alone still refuses (the bare-string elements remain). Fixed by
  element-completing `replicas` to two well-formed objects and correcting `stagedGeneration` to `1`,
  leaving `resolved_by` the only remaining defect — measured to refuse solely for that, and to
  succeed the moment `resolved_by` is supplied on top.
- **`(j) REFUSE: apply resolution with a MISMATCHED stagedGeneration, otherwise fully evidenced`**
  carried `{"stagedGeneration": 99, "replicas": ["r0","r1"]}` — **double-cause**: removing the
  mismatch alone (setting `stagedGeneration` to `1`) still refuses, because the bare-string elements
  remain. Fixed by element-completing `replicas`, keeping the `99` mismatch the leg names — measured
  to refuse solely for that, and to succeed the moment the generation is corrected on top. Its label
  is accurate once fixed and needed no change.
- **`(j) REFUSE: stagedGeneration as a STRING`** (`{"stagedGeneration": "7", "replicas": ["r0"]}`)
  was checked for the same pattern and found to have it: fixing only the bare-string `replicas`
  element still refuses (the string-typed `stagedGeneration` remains, correctly). Fixed the same way,
  keeping `stagedGeneration` as the string `"7"` the leg names.
- The three `resolution_note` privilege legs sharing this row (`(j/I6) REFUSE:` the legal update /
  the born-`INDETERMINATE_RESOLVED` insert / the same insert with a `NULL` note) were checked too:
  all three raise `42501` — a column-privilege check Postgres runs before evaluating any `CHECK` —
  regardless of `resolution_observed`'s shape, measured by running each with every other defect
  fixed and confirming the identical `42501`. Their recorded behaviour is unaffected; their fixtures'
  `replicas` were element-completed anyway, since the first of the three labels its row "the LEGAL
  resolution UPDATE," which the mismatched/bare-string row it carried did not actually satisfy.

All six legs' recorded `status` and `signature` are unchanged — reconfirmed by a `--record-candidate`
pass reporting **0 differences** against the tracked `legs.json`/`design-block.json`; only their
`sql` text moved. `legs.json` stays at 239 entries; the SQLSTATE distribution above is unaffected.

### Addendum 2 — the class swept across the whole manifest, per L20 (2026-09-09)

**Why.** The addendum above closed the six legs the R18 review named, and its own report flagged two
more it found by the same method but left unfixed as out of scope: `(j) REFUSE: replicas as a NUMBER
(not an array)` and `(j) REFUSE: replicas EMPTY array`, both carrying the same `stagedGeneration: 7`
filler against `c0000001` (`generation = 1`). `docs/design/common/lessons.md` L20 — recorded by this
same repair wave — says a class like this is swept once identified, not chased instance by instance.
This task closes both, then sweeps the rest of `legs.json` for the same failure mode: **every leg
whose label claims to isolate one named clause, measured — not read — against a live PostgreSQL
14.24 container for whether more than one clause actually refuses its payload.**

**Method.** Two measurement techniques, chosen per leg: (1) for `resolution_is_evidence`'s nine
`apply`/seven `restart` sub-clauses (a single monolithic `CHECK`, so PostgreSQL's own error only ever
names `resolution_is_evidence` regardless of which AND-ed clause failed), each candidate's
`resolution_observed` literal and its target row's `operation`/`generation` were run through the
clause bodies directly as a batch of `SELECT` boolean expressions against a live `postgres:14`
container — cheaper and more precise than mutating rows, since it reads every clause's truth value in
one pass instead of one row-state experiment per clause; (2) for the plain scalar `CHECK`s on
`apply_request`/`apply_scope`/`placement_watermark`/`apply_step_intent`/`apply_attempt` (`op_known`,
`state_known`, `state_fits_operation`, `apply_has_generation`, `lifecycle_has_strategy`,
`restart_not_manual`, `staged_requires_manual`, `terminal_has_timestamp`, `full_outage_confirmed`,
`generation_positive`, `strategy_known`, `hash_on_config_steps`, `placement_scoped_steps`,
`attempt_no_positive`, `baseline_only_for_lifecycle`, `attempt_time_ordered`, `outcome_known`), every
`REFUSE` leg's column values were run as one batch of `VALUES`-driven boolean expressions mirroring
each CHECK's exact body, verified against the design's own text first.

**Full sweep table — every leg examined, its refusing clause(s), and its verdict.** For a row whose
Verdict says a leg was **fixed**, the middle column is frozen at the clause set as **measured before
that fix** — the evidence that found the confound and motivated the repair, not the leg's current,
post-repair behaviour. The current state of each fixed leg is stated in the Findings below and the
fix log at the end of this section; it is not restated per row here.

| Leg (label, abridged) | Clauses that actually refuse it (measured, pre-repair where later fixed) | Verdict |
|---|---|---|
| `(j)` MISSING `resolved_by` | `resolved_by IS NOT NULL` only (already fixed, R8) | single-cause |
| `(j)` `resolution_observed = '{}'` | equality, array-type, non-empty (inherent to a totally empty object; label makes no single-clause claim) | multi-cause, not mislabelled |
| `(j)` `resolution_observed = JSON null` | top-level object-type, equality, array-type, non-empty (same reason) | multi-cause, not mislabelled |
| `(j)` `{stagedGeneration:null, replicas:[]}` | equality, non-empty — label itself names both "worthless values" | multi-cause, matches its own claim |
| `(j)` `stagedGeneration` as a STRING | equality only (replicas already well-formed) | single-cause |
| `(j)` **replicas as a NUMBER (not an array)** | equality (filler `stagedGeneration:7`≠1) **+** array-type **+** element-type **+** well-formedness | **confounded — fixed + relabelled** |
| `(j)` **replicas EMPTY array** | equality (filler `stagedGeneration:7`≠1) **+** non-empty | **confounded — fixed, now clean** |
| `(j)` bogus `resolution_outcome` → `resolution_known` | `resolution_known` only (`resolution_observed` already well-formed) | single-cause |
| `(j/I6)` the four `resolution_note` grant legs (117, 118, 122, 123) | `42501` only, a privilege check Postgres evaluates before any `CHECK` | single-cause (different mechanism) |
| `(j)` mismatched `stagedGeneration`, otherwise evidenced | equality only (already fixed, R8) | single-cause |
| `(j)` replicas EMPTY array, generation MATCHING | non-empty only | single-cause |
| `(j)` **replicas NOT an array, generation MATCHING** | array-type **+** element-type **+** well-formedness (bare STRING `"r1"` auto-wraps under `lax`) | **confounded — fixed, now clean** |
| `(j)` restart INVENTED `stagedGeneration` | `NOT jsonb_exists(stagedGeneration)` **+** top-level key-set — **permanently entangled**: any `stagedGeneration` key is itself a disallowed top-level key, so the two can never be isolated from each other, the restart mirror of the already-documented `jsonb_exists` dead clause | **structurally confounded — relabelled, not fixable** |
| `(j)` restart NO `restartsObserved` key at all | array-type, non-empty (both are symptoms of the one "key absent" defect the label names) | multi-cause, not mislabelled |
| `(j)` restart `restartsObserved` EMPTY array | non-empty only | single-cause |
| `(j)` **restart `restartsObserved` NOT an array** | array-type **+** element-type **+** well-formedness (bare STRING, same as the apply mirror) | **confounded — fixed, now clean** |
| `(j)` apply-shaped evidence on a restart request (direction 1) | four clauses at once — the label names "rejection class i", a whole-shape mismatch, not one clause | multi-cause, not mislabelled |
| `(j)` restart-shaped evidence on an apply request (direction 2) | four clauses at once, same reason | multi-cause, not mislabelled |
| `(j)` `BE-D18` element/hash legs, apply ×19 + restart ×11 (139–169 except 142/152/162) | exactly the one predicate each names | single-cause (19 of 19 minus 3 below, each measured) |
| `(j)` `BE-D18` replicas/restartsObserved element = JSON `null` (142, 162) | element-type **+** well-formedness — the design's own §3.4 text calls these "three predicates [that] close both escapes" together, i.e. deliberate overlapping defence-in-depth, not an accidental filler; neither label claims single-clause isolation | multi-cause, not mislabelled |
| `(j)` `observedHash` NON-STRING numeric (152) | hash-type **+** hash-format — same deliberate-overlap reason (any non-null/non-string value fails the format predicate's own type disjunct too) | multi-cause, not mislabelled |
| `(j)` `BE-D18` positive controls (129, 137, 158, 170–174) | none | clean, as required |
| `(h)` **state outside the enum → `state_known`** | `state_known` **+** `terminal_has_timestamp` (`'TOTALLY_BOGUS'` is outside the three-state exemption set, so the CHECK also demands a `terminal_at`, which the fixture omitted) | **confounded — fixed** |
| `(h)` the 10 other named single-clause legs (77–87) | exactly the one clause each names | single-cause (measured as a batch) |
| `(h)` `staged_requires_manual` legs 90–93 | `staged_requires_manual` only | single-cause |
| `(h)` `staged_requires_manual` legs 95/96 | `staged_requires_manual` **+** `state_fits_operation` | multi-cause, already disclosed in the label itself |
| `(h)` **extra: `op_known` (unknown operation), WITH generation+strategy** | `op_known` **+** `apply_has_generation` **+** `lifecycle_has_strategy` — and PostgreSQL's *reported* constraint is `apply_has_generation` (sorts first lexically), not `op_known` at all; the recorded `signature` was already correct, only the label was misleading | **confounded — relabelled** (the adjacent `ISOLATED` leg already supplies the clean exemplar) |
| `(h)` extra ISOLATED: `op_known`, no generation/strategy | `op_known` only | single-cause |
| `(t)`, `(t-owner)` — `apply_scope`/`placement_watermark` identity, monotonicity, `DELETE` | one trigger or one grant refusal each | single-cause (different mechanism per leg; no compound `CHECK` on either table) |
| `(a)`–`(e)` fence/admission legs | one partial-unique-index violation each | single-cause |
| `(f)`, `(g)` `apply_step_intent` legs 65–74 | exactly the one clause each names (`replica_ref` deliberately set to avoid tripping the other clause every time) | single-cause (measured as a batch) |
| `(i)`, `(m)` `apply_attempt` legs 97–107 | exactly the one FK or `CHECK` each names | single-cause (measured as a batch) |
| `(k)`, `(l)`, `(o)`, `(p)`, `(u)` legs 175–200 | one grant, one trigger, or one unique-index refusal each — none is a compound `CHECK` | single-cause by construction (no multi-clause `CHECK` exists on these paths) |
| `(n)`, `(q)` legs 201–207 | read-only or engine-only; no refusal to isolate | n/a |
| `P1`–`P30` probes 208–238 | one schema/column-privilege, trigger-tamper, or topology refusal each | single-cause (privilege checks run before any `CHECK`, confirmed by design and by the `(j/I6)` legs above) |

**Findings, beyond the two the review's report already named:**

- **`(h)` "state outside the enum" (leg 76) also violates `terminal_has_timestamp`**, not merely
  `state_known` as labelled — `'TOTALLY_BOGUS'` sits outside `state_known`'s enum *and* outside
  `terminal_has_timestamp`'s three-state exemption list, so the CHECK also demands a `terminal_at`,
  which the fixture never supplied. Fixed by adding `terminal_at = now()` to the `INSERT`, exactly the
  same repair every other terminal-state `(h)` leg already uses — measured to leave `state_known` the
  sole refusal.
- **`(h)` "extra: `op_known`" (leg 88) is refused by `apply_has_generation`, not `op_known`** —
  `'nuke'` matches neither `'apply'` nor `'restart'`, but `generation`/`strategy` are still present, so
  `apply_has_generation` and `lifecycle_has_strategy` both fire too, and PostgreSQL reports whichever
  sorts first lexically (`apply_has_generation`). The recorded `status`/`signature` were **already
  correct** — this was a label-only defect, not a manifest-drift one. The adjacent leg (89) already
  supplies the clean, single-cause `op_known` exemplar, so 88 is relabelled to disclose the co-firing
  and point at 89, rather than duplicated.
- **`(j)` "replicas as a NUMBER" (leg 113) cannot be made single-cause while remaining a NUMBER** — any
  non-array, non-object JSON scalar auto-wraps under `lax` mode into one non-object element, which
  independently trips the element-type and well-formedness predicates regardless of the array-type
  clause the label names. This is the same shape as the already-documented restart `jsonb_exists`
  dead clause: a structural entanglement, not a fixable fixture defect. The unrelated `stagedGeneration:
  7` filler (confounding it with the *equality* clause on top) is fixed to `1`; the label now discloses
  the remaining, unavoidable array-shape-family entanglement instead of claiming a clean isolation it
  cannot have.
- **`(j)` "replicas EMPTY array" (leg 114) is fully fixable** — its only entanglement was the same
  `stagedGeneration: 7` filler, unrelated to the empty-array defect it names. Corrected to `1`, it is
  now single-cause and, incidentally, byte-identical to the adjacent `(BE-D17: isolates the array
  clause...)` leg that already exists for this exact purpose — a duplicate proof, not a new gap.
- **`(j)` "replicas NOT an array" (leg 128) and its restart mirror (leg 134) were both confounded by
  the same bare-STRING filler this repair wave already fixed once, in the *Java* `SchemaConstraintsIT`
  suite (R6 fix round 1's "changed from a bare string to a bare well-formed object"), but the parallel
  fix was never carried into this harness's own manifest — legs.json never changed at that commit.**
  Fixed the same way here: `replicas`/`restartsObserved` becomes a well-formed OBJECT rather than a
  bare string. Measured: `lax`-mode auto-wrap turns a single well-formed object into a 1-element array
  containing itself, so it satisfies every element-level predicate while `jsonb_typeof` — which reads
  the raw value, never through `[*]` — still correctly reports it as not an array. Both now isolate the
  array-type clause alone.
- **`(j)` restart "INVENTED `stagedGeneration`" (leg 131) is the specific leg the R6 fix round's own
  text already named** ("restart's `NOT jsonb_exists(...)` — 1 leg — permanently subsumed... kept as
  defence-in-depth, documented as such") **but that disclosure lived only in `decisions.md` prose, never
  in this harness's own evidence or in the leg's own label.** Relabelled here to say so directly, no
  fixture change — the entanglement is structural (any `stagedGeneration` key is itself a disallowed
  top-level key on a restart resolution) and cannot be isolated by any input.
- **No fourth batch:** every other leg in the manifest either isolates its named clause cleanly
  (measured, not assumed) or was already honest about firing more than one — group `(h)`'s 95/96 were
  the established precedent for disclosing this rather than forcing single-cause, and the `BE-D18`
  element-family legs' deliberate predicate overlap (139–169, mostly single-cause, three intentionally
  paired) is the same category. Table above is the full account.

**Fixes, `--record-candidate` and re-run.** Seven legs changed (76, 88, 113, 114, 128, 131, 134) — five
by `sql`/fixture repair, two (88, 131) by label only, no fixture change. `--record-candidate` reported
**0 differences**: every changed leg's `status` and `signature` matched the tracked manifest on the
first pass — confirming the repair moved no recorded value, only `sql`/`label` text. `legs.json` stays
at 239 entries; `design-block.json` is untouched (`V002__apply_request.sql` and the design's §3.1 SQL
block are byte-identical to `de8a6aa`). Re-run from the checked-in artefacts: `NO DRIFT`.
`reference-run/log-h.txt` and `log-j.txt` are refreshed (the only two tracked logs whose label/SQL text
moved; every other cross-run difference is `now()`-derived timestamps, expected and not asserted upon).
`mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — both
unchanged, since no code, CHECK, or migration changed (one transient failure on the first run,
`EngineTransactionBoundaryIT.aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`,
a timing-sensitive race assertion unrelated to this task's changes — green on immediate re-run, and not
one of the two named flakes). `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `python3 -m
unittest docs/design/backend/evidence/ddl-replay/test_paths.py` 11 tests green; `git diff --check`
clean. `BE-D18` stays **PROPOSED** — a precision fix and a class sweep are not ratification.

---

## 2. Run 5 — `BE-D16`/`BE-D17`, replayed leg by leg

**Why a fifth run.** The design-first repair wave landed two amendments to `V002__apply_request.sql`
in place — `BE-D16` (`staged_requires_manual`) and `BE-D17` (`resolution_is_evidence`'s
operation-discriminated CASE) — both stated, at the time, to leave this harness's tracked baseline
deliberately red until re-recorded. This run (R4 of that wave) does the re-recording.

| Item | Value |
|---|---|
| Commit under test | `90cce61` (doc corrections + the new `resolutionIsEvidenceNeverCallsJsonbArrayLength` text-level assertion — no SQL change) on top of the `V002`/design edits that landed `BE-D16`/`BE-D17` in prior tasks of this same repair wave |
| Phase 0 extract | lines **228–265**, md5 **`768e195b7c17861d97e402c5318be51b`** — unchanged |
| Phase 1 extract | lines **266–572** (was 266–552), md5 **`4ecc44700ad165b80c2480fc6bbb7a46`** (was `2f36077ce81bb77ae37d48315092fa7e`) — `+20` lines from `staged_requires_manual`'s new clause plus its comment, and `resolution_is_evidence`'s replaced, operation-discriminated body |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu` — fresh `postgres:14` containers, removed by the harness |
| Harness | unchanged: [`ddl-replay/`](ddl-replay/) — `replay.py`, `legs.json` (now 202 entries: 197 SQL legs, 3 phase legs, 2 live-session legs), `sessions/`, `reference-run/` |
| Method | `python3 docs/design/backend/evidence/ddl-replay/replay.py --out <dir>` first, reading every reported difference (README §"When the design's SQL changes" step 1); legs added for both amendments' refusals-by-name and positive controls (step 2); `--record-candidate <dir outside the repo>` (step 3, exits 2); `diff -u` against the tracked files (step 4, empty for `legs.json` — every added/retargeted leg's status and signature was already correct on the first `--record-candidate` pass; `design-block.json`'s phase1 entry was the only tracked value that changed); tracked files and this document updated together (step 5); re-run from the checked-in artefacts for `NO DRIFT` (step 6) |

**Step 1 — every difference the first plain run reported, before any manifest edit, and its
judgement** (five differences, all explained by `BE-D16`/`BE-D17` and nothing else):

| Difference | Judgement |
|---|---|
| `phase1` md5 (`2f36077c…` → `4ecc4470…`, lines shift 266–552 → 266–572) | **Expected.** The design's SQL block changed exactly as `BE-D16`/`BE-D17` describe; re-recorded in `design-block.json` |
| `(h) REFUSE: stop in state STAGED -> state_fits_operation`: signature `state_fits_operation` → `staged_requires_manual` | **Expected, and the known drift needing a decision (README step 3).** A `stop` + `STAGED` row now violates both constraints at once; PostgreSQL reports whichever sorts first lexically (`staged_requires_manual` before `state_fits_operation`, design §3.2's own R3 measurement). **Decision: retarget**, not accept — see below |
| `(h) REFUSE: restart in state STAGED -> state_fits_operation`: same flip | **Expected, same cause** (`README`'s own example names only the `restart` leg; the `stop` leg turns out to carry the identical drift and needs the identical decision) |
| `(j) REFUSE: bogus resolution_outcome -> resolution_known`: signature `resolution_known` → `resolution_is_evidence` | **Expected, and a fixture bug, not a rule change to accept.** This leg's exemplar carried `stagedGeneration: 7` against the row's own `generation = 1` — an arbitrary value that was harmless before `BE-D17` made the equality load-bearing. Fixed by retargeting to `1` (matching), restoring the leg's own claimed isolation of `resolution_known` |
| `(j) POS: a FULLY-EVIDENCED INDETERMINATE_RESOLVED succeeds`: status `OK` → `REFUSED` | **Expected, and the same fixture bug** — the positive control used the same stale `7`. A positive control silently becoming a refusal is not a new baseline to accept; fixed the same way (retarget to `1`), restoring it as a genuine proof of satisfiability — the exact parallel of the Java suite's own forced `GOOD_EVIDENCE` fixture change (`12` → `1`) for the identical reason |

**The known-drift decision, stated (README step 3): retargeted, not accepted, for both legs.**
`(h) REFUSE: stop/restart in state STAGED -> state_fits_operation` are retargeted to exemplars that
violate `state_fits_operation` alone — `stop` + `APPLIED` (excluded by the `stop` branch, not
`STAGED`) and `restart` + `STOPPED` (excluded by the `restart` branch's other two members). This
mirrors the Java suite's own precedent (`aStopThatClaimsToBeAppliedIsRefusedByStateFitsOperation`,
R2 of this repair wave) rather than leaving a leg whose label names one constraint while a different
one fires — the "one probe, one violation" rule this harness's Java counterpart states explicitly.
Coverage of the `STAGED` + non-manual case these two legs used to carry is not lost: two new legs
below exercise `stop`/`restart` + `STAGED` explicitly, correctly named for `staged_requires_manual`.

**Step 2 — 18 new legs, in the order they run, each with its positive control:**

| Leg | Result |
|---|---|
| `(h) REFUSE`: apply, `rolling-health-gated`/`rolling-no-validation`/`restart-all` (outage confirmed)/`scheduled`, state `STAGED` | `staged_requires_manual` ×4 — the four non-manual ladder strategies, by name |
| `(h) POS`: apply, `manual`, state `STAGED` | `[OK]` — the positive control proving the refusal is the rule, not a broken table |
| `(h) REFUSE`: `stop`/`restart` + `STAGED` (coverage retained from the retargeted legs above) | `staged_requires_manual` ×2 |
| `(j) SETUP`: a `restart` request and a second `apply` request, both already `APPLY_INDETERMINATE` | `[OK]` |
| `(j) REFUSE`: apply resolution, `stagedGeneration` **mismatched** (99 ≠ 1), otherwise fully evidenced | `resolution_is_evidence` — rejection class (ii), cleanly isolated (no other clause is false) |
| `(j) REFUSE`: apply resolution, `replicas` empty / not-an-array, `stagedGeneration` **matching** | `resolution_is_evidence` ×2 — isolates the array clauses, not the equality clause (the gap the Java suite's own Important-1 finding closed the same way) |
| `(j) POS`: apply resolution, fully evidenced, `stagedGeneration` matching | `[OK]` — the apply positive control, now generation-bound |
| `(j) REFUSE`: restart resolution, `stagedGeneration` present at all (any value) | `resolution_is_evidence` — rejection class (iv) |
| `(j) REFUSE`: restart resolution, `restartsObserved` absent / empty / not-an-array | `resolution_is_evidence` ×3 — rejection class (iii) |
| `(j) REFUSE`: apply-shaped evidence resolving a `restart` request | `resolution_is_evidence` — rejection class (i), direction 1 |
| `(j) REFUSE`: restart-shaped evidence resolving an `apply` request | `resolution_is_evidence` — rejection class (i), direction 2 |
| `(j) POS`: restart resolution, fully evidenced | `[OK]` — `BE-D17`'s own positive control, proving the restart shape is satisfiable, not merely refusable |

**Result: 197 SQL legs (122 refused, 75 ok) + 3 phase legs + 2 session legs — `NO DRIFT` on the
final run from the checked-in artefacts.** Every one of the 18 new/retargeted legs matched the
expected value on the first `--record-candidate` pass — `diff -u` against the tracked `legs.json`
was empty; only `design-block.json`'s `phase1` entry changed. Totals: run 4's 179 (107 refusals, 72
positive controls) + 15 new refusals + 3 new positive controls = 197 (122, 75).

**The text-level half of the same repair-wave task, for completeness (not this harness's own
obligation, but the same commit):** `SchemaConstraintsIT.resolutionIsEvidenceNeverCallsJsonbArrayLength`
(`backend/dp-core`) reads `resolution_is_evidence`'s installed definition back via
`pg_get_constraintdef` and asserts it never calls `jsonb_array_length` — closing the throw-freedom
gap this harness cannot see (a revert of both array clauses in `V002` *and* the design's mirrored
block passes `SketchMirrorTest` and every behavioural test, this harness included at the
constraint-name granularity it compares on, since the compound CHECK still refuses the same rows).
Measured: mutating both files back to `jsonb_array_length(...) > 0` makes that one Java assertion
fail and nothing else in `SchemaConstraintsIT`/`OutcomeTransitionsIT`/`PrivilegeTopologyIT`; reverted
against the committed green state before this run.

---

## 3. Run 4 — the `resolution_note` grant change, replayed leg by leg

**Why a fourth run.** Plan 004's external review (fifth pass) found that the application-level removal
of the free-text resolution note left the database able to reopen it: `sm_dp_runtime` still held
`UPDATE` on `resolution_note`, and table-level `INSERT`, so a legal resolution update carrying a note —
or a row born with one — would be accepted. Commit **`d87c2e7`** column-scopes both grants on
`apply_request`; `resolution_note` is in neither list. That changes the authoritative SQL block, so
run 3's evidence does not transfer and this run re-executes everything against the new bytes.

| Item | Value |
|---|---|
| Commit under test | **`d87c2e7`** (`d87c2e795f9be417d80830b881167c823cfd6d6f`) — "Design §3.1: sm_dp_runtime holds neither INSERT nor UPDATE on resolution_note" |
| Phase 0 extract | lines **228–265** (`sed -n '228,265p'`) → `phase0.sql`, md5 **`768e195b7c17861d97e402c5318be51b`** — unchanged from run 3 |
| Phase 1 extract | lines **266–552** (`sed -n '266,552p'`) → `phase1.sql`, md5 **`2f36077ce81bb77ae37d48315092fa7e`** — the one changed statement is the `apply_request` GRANT |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu` — fresh `postgres:14` containers, two per run (`ddl-replay-main-<epoch>`, `ddl-replay-negctl-<epoch>`), removed by the harness |
| Harness | **checked in:** [`ddl-replay/`](ddl-replay/) — `replay.py` (extracts the phases from the design by banner, starts PG 14, runs the manifest, fails on drift), `legs.json` (184 entries: 179 SQL legs with expected status + signature, 3 phase legs, 2 live-session legs), `sessions/`, and `reference-run/` (this run's logs). `python3 docs/design/backend/evidence/ddl-replay/replay.py --out /tmp/ddl-replay` |
| Method | run 3's legs were **reconstructed from its logs** (each `### [status] label (container role db)` / `--- SQL ---` block) into the manifest and executed in the original order — the same SQL, role, container and database per leg; the two concurrent-session legs replayed as two live `psql` sessions with a third observing. **What was compared, honestly:** the first replay compared statuses plus `CONSTRAINT NAME` and `WARNING` lines per leg against run 3 — its SQLSTATE pattern was mis-anchored and matched nothing, which an independent reproduction of the checked-in harness found; the manifest now records every refusal's SQLSTATE (107/107) alongside the constraint names, and that is what the harness compares on every run. **Prove-it-fires (three mutations):** `resolution_note` back in the UPDATE grant → 9 differences; the terminal guard raising `23514` instead of `P0001` → 9; a renamed trigger → 2; each exits 1. |

**Result: 168 replayed legs, 0 status changes, 167/167 constraint-name/warning signatures identical**
(the 168th is the (d) observation, an unmarked leg re-taken below); SQLSTATEs were compared only from
the checked-in harness onward — see Method. Phase 0: 7 statements, 0 errors, 0 warnings.
Phase 1: `SET · ALTER DEFAULT PRIVILEGES · CREATE TABLE ×5 · CREATE INDEX ×5 · CREATE FUNCTION ×3 ·
CREATE TRIGGER ×3 · ALTER TABLE · GRANT ×5` — 24 statements, 0 errors. Negative controls A and B
reproduced `WARNING: 01006: no privileges could be revoked for "public"`.

**Three run-3 legs changed by the grant, deliberately, and how:**

| Run-3 leg | Change | Run-4 outcome |
|---|---|---|
| `(j) POS: a FULLY-EVIDENCED INDETERMINATE_RESOLVED succeeds` | the statement no longer sets `resolution_note` (the runtime cannot) | `[OK]` — same row, same evidence |
| `(u) REFUSE: the same UPDATE ALSO touching resolution_note` | second column is now `resolved_by` (a GRANTED column), so what refuses is still the **trigger** | `[REFUSED] P0001 … is terminal (APPLIED) and immutable` |
| `(s) DCL: runtime UPDATE column-scoping + DELETE grants` | same query; the catalog now lists **six** `apply_request` UPDATE columns | `attempt_summary, resolution_observed, resolution_outcome, resolved_by, state, terminal_at` |

**Eleven new legs (`log-topology.txt`, `log-j.txt`, `log-pu.txt`), every one as expected:**

| Leg | Role | Result |
|---|---|---|
| `(s/I6) DCL: runtime INSERT is column-scoped too — resolution_note in neither list` | postgres | `[OK]` — INSERT lists 16 columns, UPDATE 6; the only privilege the runtime holds on `resolution_note` is `SELECT` (table-level SELECT, expanded per column by `information_schema`) — `write_grants_on_note = 0` |
| `(j/I6) REFUSE: the LEGAL resolution UPDATE also writing resolution_note` | sm_dp_runtime | `[REFUSED] 42501: permission denied for table apply_request` — a GRANT refusal, before any CHECK or trigger |
| `(j/I6) REFUSE: resolution_note alone, even to NULL` | sm_dp_runtime | `[REFUSED] 42501` — the column privilege itself is absent, not a value rule |
| `(j/I6) POS control: the OWNER may write resolution_note` | migrator + `SET ROLE sm_dp_owner` | `[OK]` — row still `APPLY_INDETERMINATE`, `note_null = t` |
| `(j/I6) row unchanged by the two refusals` | sm_dp_runtime | `[OK]` — `APPLY_INDETERMINATE`, note NULL, resolved_by NULL |
| `(j/I6) SETUP: scope row for an otherwise-unused tenant T5` | sm_dp_runtime | `[OK]` (`request_has_scope` needs it) |
| `(j/I6) REFUSE: a row BORN INDETERMINATE_RESOLVED with a resolution_note` | sm_dp_runtime | `[REFUSED] 42501` — the INSERT column grant |
| `(j/I6) REFUSE: the same INSERT naming resolution_note even as NULL` | sm_dp_runtime | `[REFUSED] 42501` |
| `(j/I6) POS control: the OWNER may insert the row naming resolution_note` | migrator + `SET ROLE sm_dp_owner` | `[OK]` |
| `(j/I6) POS control: the runtime's OWN insert of a resolved row succeeds WITHOUT naming the column` | sm_dp_runtime | `[OK]` — the grant costs the engine nothing |
| `(u/I6) REFUSE: fold + resolution_note on a terminal row` | sm_dp_runtime | `[REFUSED] 42501` — the GRANT refuses before the terminal guard can; run 3's `P0001` for this statement is now unreachable |

**(d), as the harness runs it** (`ddl-replay/reference-run/log-d.txt`, both directions): session A
holds the scope row for 6 s; session B blocks (**4.5 s** / **3.5 s** measured) and then reads the
in-flight count `= 1`; a third session observes B `active | Lock | transactionid` on A's transaction
and a `transactionid ShareLock granted = f` in `pg_locks`. The leg passes only if all three hold.

**Totals:** 179 marked legs — **107 refusals, 72 positive controls**. Reconciliation: run 3's 168
(123 obligation + 11 (s)-control + 31 probe + 3 harness/seed) + 11 new = 179. The run recorded in
`ddl-replay/reference-run/` is the harness's own execution from the checked-in manifest (fresh
containers): `NO DRIFT`.

The sections below are run 3's leg catalogue, kept because run 4 reproduced every leg in it with the
same outcome and the same refusal name; where a leg changed, the table above is authoritative.

---

## 4. Header

| Item | Value |
|---|---|
| Commit under test | **`afee944`** (`afee944898286e184089cca377df80b4cc450cf4`) — "Close the independent R8 re-review's six clerical defects; route the final evidence run" |
| Document | `docs/design/backend/eda-walking-skeleton.md` |
| Phase 0 extract | lines **216–253** (`sed -n '216,253p'`) → `phase0.sql`, md5 **`768e195b7c17861d97e402c5318be51b`** ✅ matches expected |
| Phase 1 extract | lines **254–533** (`sed -n '254,533p'`) → `phase1.sql`, md5 **`5c28fa14868fbd72cbec158193e6d86f`** ✅ matches expected |
| Engine | `PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit` |
| Containers | `pg14-c` (main + negative control A), `pg14-negctl` (negative control B) — both removed, §9 |
| Scratchpad | `.../scratchpad/pg14-final-3/` (previous runs' areas left intact) |
| Reviewer model | `claude-opus-5[1m]` (Opus 5, 1M context) |

Both md5s matched on first extraction; no range was adjusted. Nothing was carried forward from the
`91182d0` or `aa5ac48` runs — fresh containers, fresh fixtures, every leg re-executed.

### Change from `aa5ac48` — verified, not trusted
Comment-stripped diff of both files: **`(identical SQL)` for phase0 and phase1**. The only delta is
one trigger-comment paragraph in Phase 1 (rewritten to say that three of four directions died under
the old shared function, not "every update"). The run nonetheless stands on these bytes, not on
that claim.

### Roles per phase / leg

| Leg | Container | Role | DB |
|---|---|---|---|
| **Phase 0 (main)** | `pg14-c` | **`postgres`** — bootstrap superuser, owner of schema `public`; `rolsuper = t` | `smdb` |
| **Phase 1** | `pg14-c` | `sm_dp_migrator` (file's first statement is `SET ROLE sm_dp_owner`) | `smdb` |
| Runtime legs | `pg14-c` | `sm_dp_runtime` — `rolsuper=f`, `rolcanlogin=t`, `rolinherit=f` | `smdb` |
| Owner controls | `pg14-c` | `sm_dp_migrator` + `SET ROLE sm_dp_owner` | `smdb` |
| Inspection legs | `pg14-c` | `postgres` (catalog reads only) | `smdb` |
| **Neg control A** | `pg14-c` | `sm_installer` (`rolsuper=f`, `rolcreaterole=t`), **owner of** `smdb2` | `smdb2` |
| **Neg control B** | `pg14-negctl` | `sm_installer` (`rolsuper=f`, `rolcreaterole=t`), **owner of** `smdb` | `smdb` |

### Why the (s) negative control cannot be a literal Phase 0 re-run in the same cluster

PostgreSQL roles are **cluster-scoped**, so re-running the file in a second database of the same
cluster aborts on its very first statement. **Measured, not asserted** — `log-s-negA.txt:24`:

```
### [REFUSED] (s-negA) WHY the control cannot be a LITERAL Phase 0 re-run: roles are CLUSTER-scoped
CREATE ROLE sm_dp_owner    NOLOGIN;
ERROR:  42710: role "sm_dp_owner" already exists
```

**I therefore ran both variants:**
- **Control A** — same cluster (`pg14-c`), second database `smdb2` owned by the non-superuser
  `sm_installer`, with the **`REVOKE` executed in isolation** by that owner.
- **Control B** — second container `pg14-negctl` (fresh cluster, so roles do not yet exist), with
  the **literal, complete Phase 0 file** run by a non-superuser database owner.

Both are reported below; both leave the assertion **true**.

### Harness rules honoured

1. **stdin only** — both phases piped (`docker exec -i psql … < file`); no `psql -c "a; b"`.
2. **Every leg goes through `q.sh`**, which runs one group per `psql` invocation with
   `\set VERBOSITY verbose` + `\set ON_ERROR_STOP 0`, and **always** prints
   `### [OK|REFUSED] <label> (container= role= db=)`, the exact SQL, and the exact result. Refusals
   therefore carry SQLSTATE and the constraint / index / trigger / function name.
3. **Positive control beside every refusal**; every runtime privilege refusal re-run under
   `sm_dp_migrator` + `SET ROLE sm_dp_owner`.
4. **Real UUIDs**; nil UUID only as the instance-wide sentinel; every CHECK satisfied when building
   legal rows; an `apply_scope` row upserted before every request insert.
5. **Two real concurrent `psql` processes** for (d), observed from a third session, block time
   measured by `\timing`.

**Leg accounting — 168 markers total**, reconciling exactly:

| Group | Markers |
|---|---|
| Obligations (a)–(u) | 123 |
| (s) negative controls A + B | 11 |
| Hostile probes P1–P30 | 31 |
| Harness smoke / seed / setup | 3 |
| **Total** | **168** (102 `REFUSED`, 66 `OK`) |

---

## 5. Phase 0 and Phase 1 execution transcripts

Every claim in this section cites a marked leg. Log files: `log-s-pre.txt`, `log-phase0.txt`,
`log-s-post.txt`, `log-s-negA.txt`, `log-phase0-negB.txt`, `log-s-negB.txt`, `log-phase1.txt`,
`log-topology.txt`.

### 2.1 Pre-state — `log-s-pre.txt`

`### [OK] (s) PRE-STATE: executor identity, db owner, schema public owner + ACL BEFORE Phase 0`

```
 executor | executor_is_superuser        datname | db_owner
 postgres | t                            smdb    | postgres

 nspname | schema_owner |             acl_before
 public  | postgres     | {postgres=UC/postgres,=UC/postgres}
```

### 2.2 Phase 0 — as `postgres`, over stdin — `log-phase0.txt`

`EXIT=0`, 7 statements, **no WARNING**:

| # | Statement | Result |
|---|---|---|
| 1 | `CREATE ROLE sm_dp_owner NOLOGIN` | `CREATE ROLE` |
| 2 | `CREATE ROLE sm_dp_migrator LOGIN IN ROLE sm_dp_owner` | `CREATE ROLE` |
| 3 | `CREATE ROLE sm_dp_runtime LOGIN NOINHERIT` | `CREATE ROLE` |
| 4 | `GRANT sm_dp_owner TO CURRENT_USER` | `GRANT ROLE` |
| 5 | `CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner` | `CREATE SCHEMA` |
| 6 | `GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime` | `GRANT` |
| 7 | `REVOKE CREATE ON SCHEMA public FROM PUBLIC` | `REVOKE` (clean) |

### 2.3 The (s) assertions — each its own marked leg — `log-s-post.txt`

**`### [OK] (s) POST-STATE: schema public ACL AFTER Phase 0`**
```
 nspname |             acl_after
 public  | {postgres=UC/postgres,=U/postgres}     ← the C is gone from the PUBLIC entry
```

**`### [OK] (s) THE ASSERTION: has_schema_privilege(...) must be FALSE`**
```
 runtime_can_create_in_public
 f
```

**`### [REFUSED] (s) BEHAVIOURAL PROOF: runtime CREATE TABLE public.smuggled must be REFUSED`**
```
CREATE TABLE public.smuggled(x int);
ERROR:  42501: permission denied for schema public
LINE 1: CREATE TABLE public.smuggled(x int);
                     ^
LOCATION:  aclcheck_error, aclchk.c:3452
```

**`### [OK] (s) sm_data schema owner + ACL created by Phase 0`**
```
 nspname |    owner    | nspacl
 sm_data | sm_dp_owner | {sm_dp_owner=UC/sm_dp_owner,sm_dp_runtime=U/sm_dp_owner}
```

**`### [OK] (s) role attributes created by Phase 0`**
```
    rolname     | rolsuper | rolcanlogin | rolinherit
 sm_dp_migrator | f        | t           | t
 sm_dp_owner    | f        | f           | t
 sm_dp_runtime  | f        | t           | f
```

### 2.4 Negative control A — same cluster, `REVOKE` in isolation — `log-s-negA.txt`

| Marked leg | Result |
|---|---|
| `(s-negA) confirm sm_installer is NOT a superuser and owns smdb2` | `sm_installer \| f \| t` ; `smdb2 \| sm_installer` |
| `(s-negA) WHY … roles are CLUSTER-scoped` | **REFUSED** `42710: role "sm_dp_owner" already exists` |
| `(s-negA) ACL … BEFORE the revoke` | `public \| postgres \| {postgres=UC/postgres,=UC/postgres}` |
| `(s-negA) the REVOKE in isolation, as the non-superuser DATABASE OWNER` | `WARNING: 01006: no privileges could be revoked for "public"` then `REVOKE` |
| `(s-negA) ACL AFTER + the assertion, which must be TRUE` | `acl_after = {postgres=UC/postgres,=UC/postgres}` (**unchanged**) ; `runtime_can_create_in_public = t` |
| `(s-negA) BEHAVIOURAL: runtime CREATE TABLE public.smuggled SUCCEEDS here` | **OK** — `CREATE TABLE` |

### 2.5 Negative control B — fresh cluster, literal full Phase 0 — `log-phase0-negB.txt`, `log-s-negB.txt`

`EXIT=0`; the file ran complete because the cluster was fresh. Its final statement:

```
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
WARNING:  no privileges could be revoked for "public"
REVOKE
```

| Marked leg | Result |
|---|---|
| `(s-negB) confirm executor is non-superuser and owns the db` | `sm_installer rolsuper=false rolcreaterole=true`; `smdb \| sm_installer` |
| `(s-negB) ACL AFTER + the assertion, which must be TRUE` | `{postgres=UC/postgres,=UC/postgres}` ; `runtime_can_create_in_public = t` |
| `(s-negB) BEHAVIOURAL: runtime CREATE TABLE public.smuggled SUCCEEDS` | **OK** — `CREATE TABLE` |

**Both controls discriminate from the superuser run.** The `REVOKE` is a silent WARNING no-op for
anyone but the grantor, measured for a database owner (A and B) and, in the two prior runs, for a
CREATEROLE non-owner — exactly what the amended text claims.

### 2.6 Phase 1 — as `sm_dp_migrator`, over stdin — `log-phase1.txt`

`EXIT=0`. **24 statements, all green, verbatim, no edits:**

```
SET · ALTER DEFAULT PRIVILEGES · CREATE TABLE · CREATE INDEX ×4 · CREATE TABLE ×2 ·
CREATE FUNCTION ×2 · CREATE TRIGGER ×2 · ALTER TABLE · CREATE TABLE ×2 · CREATE INDEX ·
CREATE FUNCTION · CREATE TRIGGER · GRANT ×5
```

Topology, each a marked leg in `log-topology.txt`:

**`(s) Phase 1 topology: every table+index owned by sm_dp_owner`**
```
 owned_by_sm_dp_owner | total
                   21 |    21
```

**`(s) Phase 1 topology: functions and trigger wiring`** — the shared `watermarks_only_rise` is
absent; three functions, all `sm_dp_owner`-owned:
```
 apply_request_terminal_guard  | sm_data.apply_request       | forbid_terminal_regression     | O
 apply_scope_monotonic         | sm_data.apply_scope         | instance_watermark_only_rises  | O
 placement_watermark_monotonic | sm_data.placement_watermark | placement_watermark_only_rises | O
```

**`(s) DCL: runtime UPDATE column-scoping + DELETE grants`**
```
 apply_attempt       | (all 14 columns)
 apply_request       | attempt_summary, resolution_note, resolution_observed,
                       resolution_outcome, resolved_by, state, terminal_at
 apply_scope         | instance_watermark
 placement_watermark | generation
 runtime_delete_tables: apply_attempt
```
`apply_step_intent` appears in neither list — no UPDATE, no DELETE.

**`(s) ALTER DEFAULT PRIVILEGES`** — a new owner-created table gives the runtime
`runtime_select = t`, `runtime_insert = f`.

---

## 6. Obligations (a)–(u)

### (a) `stop` admitted alongside `RUNNING` and `APPLY_INDETERMINATE` — **PASS** (2 legs)
Both admitted (`log-abce.txt`).

### (b) second apply/restart refused in both states — **PASS** (3 legs)
All `23505 … CONSTRAINT NAME: apply_inflight_fence` — second `apply` vs `RUNNING`, second `restart`
vs `RUNNING`, `apply` vs `APPLY_INDETERMINATE` (arch §16.1's blocking rule firing).

### (c) second in-flight `stop` refused — **PASS** (1 leg)
`23505 … CONSTRAINT NAME: stop_inflight_fence`.

### (d) scope-row-lock serialization, both directions, two live sessions — **PASS**
1 marked leg (the cross-scope stop admit) plus five non-marker artifacts, all named:
`log-sessA.txt`, `log-sessB.txt`, `log-sessA2.txt`, `log-sessB2.txt`, `log-d-observed.txt` — the
session transcripts are raw `psql` output because they must run as separate concurrent processes.

Observed from a third session while B waited (`log-d-observed.txt`):
```
 pid | usename       | state  | wait_event_type | wait_event
 523 | sm_dp_runtime | active | Lock            | transactionid
 locktype      | mode      | granted
 transactionid | ShareLock | f
```

| Direction | B's measured block | B's admission query after A commits | Verdict |
|---|---|---|---|
| per-placement behind instance-wide | **4966.206 ms** | `instance_wide_inflight = 1` | refuse |
| instance-wide behind per-placement | **4031.218 ms** | `placement_scoped_inflight = 1` | refuse |

Cross-scope stop exemption: `### [OK] (d) ADMIT cross-scope: PLACEMENT-scoped stop while the
instance-wide apply is APPLY_INDETERMINATE`.

### (e) tenant isolation on the fences — **PASS** (2 legs)
Same `(instance_id, placement_id)` under `T2` admitted while `T1`'s identical scope was fenced; same
tenant + same scope still collides on `apply_inflight_fence`.

### (f) `hash_on_config_steps` both directions — **PASS** (9 legs)
Positive: `MATERIALIZE` with hash. Refusals, all `CONSTRAINT NAME: hash_on_config_steps` —
dir 1: `ROLLBACK`, `VERIFY` without hash; dir 2: hash on `STOP`, `PREFLIGHT`, `LIFECYCLE`,
`ROLLBACK_ASSESS`. Extras: `placement_scoped_steps`, `step_known`.

### (g) intent key discipline — **PASS** (3 legs) / read-compare **N/A-engine**
`apply_step_intent_tenant_id_apply_id_step_replica_ref_key` and
`apply_step_intent_idempotency_key_key`. Read-compare query executed:
`idem-mat-0 | sha256:aaa | sha256:DIFFERENT | f`.

### (h) malformed-row CHECKs — **PASS** (14 legs)
`state_known`; `state_fits_operation` (stop→STAGED, restart→STAGED); `apply_has_generation` (both
directions); `lifecycle_has_strategy` (both directions); plus `restart_not_manual`,
`full_outage_confirmed`, `terminal_has_timestamp`, `generation_positive`, `strategy_known`,
`op_known`. All `23514`.

*Method note:* my first `op_known` attempt was refused by `apply_has_generation` instead (the row
violated both). Re-run isolated — `### [REFUSED] (h) extra ISOLATED: op_known` →
`CONSTRAINT NAME: op_known`. Both legs are in `log-h.txt`.

### (i) cross-request intent reference refused by the triple FK — **PASS** (7 legs)
Isolated with an unused `attempt_no` so the UNIQUE cannot mask it:
`CONSTRAINT NAME: apply_attempt_tenant_id_apply_id_intent_id_fkey`. Also wrong tenant (same FK);
step disagreeing with intent (`apply_attempt_intent_id_step_fkey`); plus
`baseline_only_for_lifecycle`, `attempt_time_ordered`, `outcome_known`. Positive control: the
correct triple inserted.

### (j) resolution evidence — **PASS** (10 legs) / replica-**element** typing **N/A-engine**
All `CONSTRAINT NAME: resolution_is_evidence`: missing `resolved_by`; `'{}'`; JSON `null`;
`{"stagedGeneration": null, "replicas": []}`; string `stagedGeneration`; `replicas` as a number;
`replicas` as an empty array. Plus `resolution_known` and `resolution_only_when_resolved`.
Positive: a fully-evidenced `INDETERMINATE_RESOLVED` succeeded.

### (k) intent immutability is the grant — **PASS** (4 legs)
Runtime `UPDATE`/`DELETE` → `42501: permission denied for table apply_step_intent`. Owner controls:
the same `UPDATE` succeeded (`owner updated intent rows=1`) and the same `DELETE` succeeded
(`owner deleted intent ok`) — both rolled back.

### (l) `SET ROLE` refused, topology asserted — **PASS** (2 legs)
```
ERROR:  42501: permission denied to set role "sm_dp_owner"

 runtime_is_owner_member | migrator_is_owner_member | runtime_inherits | runtime_is_superuser
 f                       | t                        | f                | f
```

### (m) retry bounds — **PASS** (4 legs) / configured cap **N/A-engine**
`attempt_no_positive` refused `0` and `-1`; `attempt_no = 2` succeeded; duplicate
`(intent_id, attempt_no)` refused by `apply_attempt_intent_id_attempt_no_key`.

### (n) admission monotonicity — **PASS** (5 legs)

Watermarks built the way admission builds them — upsert, then `UPDATE … = higher`
(`instance_wm = 10`, `p1_wm = 20`):

| Direction | Scope | G | bar | Verdict |
|---|---|---|---|---|
| 1 placement-scoped ≤ **instance** watermark | placement P2 | 10 | 10 | **REFUSE** |
| 2 placement-scoped ≤ its **own** placement watermark | placement P1 | 15 | 20 | **REFUSE** |
| 3 instance-wide ≤ **instance** watermark | instance-wide | 10 | 10 | **REFUSE** |
| 4 instance-wide ≤ **any** placement watermark | instance-wide | 15 | 20 | **REFUSE** |
| 5 legal G above every bar | instance-wide | 21 | 20 | **ADMIT** |

**Resolve-and-raise in ONE transaction — `### [OK]`, commits cleanly:**
```sql
BEGIN;
UPDATE sm_data.apply_request SET state='INDETERMINATE_RESOLVED', … resolution_outcome='applied' …;
UPDATE sm_data.apply_scope  SET instance_watermark=30 WHERE …;
SELECT …; COMMIT;
```
In-transaction comparison, and again after commit — identical:

| candidate G | bar | verdict |
|---|---|---|
| 29 | 30 | REFUSE |
| 30 | 30 | REFUSE |
| 31 | 30 | ADMIT |

Durability leg: `INDETERMINATE_RESOLVED | applied | 30`, watermark `30`.

### (o) column-scoped grant + terminal guard — **PASS** (6 legs)
Runtime refused on `tenant_id` and `requested_by` **on a non-terminal row** (so the grant, not the
trigger, is what refuses); owner control on the same row succeeded
(`owner rewrote requested_by rows=1`). Legal `RUNNING → APPLIED` succeeded; `APPLIED → REQUESTED`
refused by `apply_request_terminal_guard`; and the same walk-back refused for the **owner** too —
the guard is a trigger, not a grant.

### (p) `staged_fence` and the one sanctioned transition — **PASS** (7 legs)
Second `STAGED` without marking → `staged_fence`; `STAGED → SUPERSEDED` succeeded; full
mark-then-insert succeeded; `STAGED → APPLIED`, `STAGED → FAILED_NO_EFFECT` and
`SUPERSEDED → STAGED` all refused — confirming `STAGED → SUPERSEDED` is the *only* sanctioned
terminal transition.

### (q) intents only for non-terminal requests — **N/A-engine, measured** (2 legs)
`### [OK] (q) NO DB ARTIFACT (measured): an intent against a TERMINAL request is ACCEPTED by the
database`, with a companion leg confirming the request was `APPLIED` at the time. The engine
obligation is load-bearing, as the document says.

### (r) stop-during-apply interaction — **N/A-engine, no DB artifact** (0 legs)
Behavioural. Its admission half (the stop exemption) is proven under (a) and (d).

### (s) full two-phase script under the stated credentials — **PASS** (11 legs + 11 control legs)
Every claim backed by a marked leg; see §2.1–2.6. Executes end to end (7 + 24 statements,
`EXIT=0`, zero edits); the assertion is **false** under the bootstrap superuser and **true** under
both non-superuser controls.

### (t) the two `*_only_rises` triggers + identity/DELETE refusals — **PASS** (24 legs)

**Value half:**

| Leg | Table | Result |
|---|---|---|
| rise `0 → 5`, `5 → 10` | `apply_scope` | **succeed** |
| equal `10 → 10` | `apply_scope` | **succeeds** |
| decrease `10 → 3` | `apply_scope` | `P0001: instance watermark may not decrease (10 -> 3)` · `CONTEXT: PL/pgSQL function sm_data.instance_watermark_only_rises() line 4 at RAISE` |
| rise `3 → 7` | `placement_watermark` | **succeeds** |
| equal `7 → 7` | `placement_watermark` | **succeeds** |
| decrease `7 → 1` | `placement_watermark` | `P0001: placement watermark may not decrease (7 -> 1)` · `CONTEXT: PL/pgSQL function sm_data.placement_watermark_only_rises() line 4 at RAISE` |

Each refusal names its **correct per-table function**. The guard binds the owner too
(`### [REFUSED] (t) the monotonic guard binds the OWNER too`).

**Identity + DELETE refusals under `sm_dp_runtime` — all 7**, each
`42501: permission denied for table …`: `apply_scope` `tenant_id`, `instance_id`, `DELETE`;
`placement_watermark` `tenant_id`, `instance_id`, `placement_id`, `DELETE`.

**Positive controls:** admission upsert + idempotent repeat; `SELECT … FOR UPDATE` in a
transaction; `INSERT` into `placement_watermark`; the legal raises above; plus
`real_placements_only` and `placement_watermark_positive` refusals.

**Owner controls (no trigger workaround needed):** on a dependency-free row the owner re-tenanted
`apply_scope` (`rows=1`), re-instanced it (`rows=1`), re-keyed `placement_watermark.placement_id`
(`rows=1`) and deleted from both — while the runtime got `42501` on the **same row**. The refusal
is the grant; the grant is narrow, not broken.

### (u) compaction branch and `request_has_scope` — **PASS** (6 legs)
`attempt_summary`-only UPDATE succeeded on an `APPLIED` row and on a `SUPERSEDED` row; the same
UPDATE also touching `resolution_note`, and also touching `terminal_at`, each refused by the
terminal guard; `request_has_scope` refused a request with no scope row; the attempt-pruning
`DELETE` half succeeded under the runtime's `apply_attempt` grant.

### Summary table — leg counts are the measured marker counts

| Obl. | Legs (markers) | Result |
|---|---|---|
| (a) | 2 | **PASS** |
| (b) | 3 | **PASS** |
| (c) | 1 | **PASS** |
| (d) | 1 marker + 5 named session/observation artifacts | **PASS** |
| (e) | 2 | **PASS** |
| (f) | 9 | **PASS** |
| (g) | 3 | **PASS** + N/A-engine (read-compare) |
| (h) | 14 | **PASS** |
| (i) | 7 | **PASS** |
| (j) | 10 | **PASS** + N/A-engine (replica-element typing) |
| (k) | 4 | **PASS** |
| (l) | 2 | **PASS** |
| (m) | 4 | **PASS** + N/A-engine (configured cap) |
| (n) | 5 | **PASS** |
| (o) | 6 | **PASS** |
| (p) | 7 | **PASS** |
| (q) | 2 | **N/A-engine** — measured, no DB artifact |
| (r) | 0 | **N/A-engine** |
| (s) | 11 + 11 control legs | **PASS** |
| (t) | 24 | **PASS** |
| (u) | 6 | **PASS** |
| **Obligation subtotal** | **123** (+11 (s)-controls) | |

---

## 7. Hostile probes

30 probes under `sm_dp_runtime` (`log-probes.txt`), plus a temp-table characterisation leg.

| # | Probe | Outcome | Verbatim |
|---|---|---|---|
| P1 | (a) `CREATE TABLE sm_data.evil` | refused | `42501: permission denied for schema sm_data` |
| **P2** | (a) `CREATE TABLE public.smuggled2` | **refused** | `42501: permission denied for schema public` |
| P3 | (a) `CREATE SCHEMA evilschema` | refused | `42501: permission denied for database smdb` |
| P4 | (a) `CREATE FUNCTION sm_data.evilfn()` | refused | `42501: permission denied for schema sm_data` |
| **P5** | (a) `CREATE TEMP TABLE` | **SUCCEEDED** | lands in `pg_temp_3`, `relpersistence='t'`; `has_database_privilege(…,'TEMP') = t`. See I1 |
| P6 | (b) `UPDATE apply_request.placement_id` | refused | `42501: permission denied for table apply_request` |
| P7 | (b) `UPDATE apply_request.generation` | refused | same |
| P8 | (b) `UPDATE apply_request.operation` | refused | same |
| P9 | (b) `UPDATE apply_request.requested_at` | refused | same |
| P10 | (b) `UPDATE apply_step_intent.tenant_id` | refused | `42501: permission denied for table apply_step_intent` |
| P11 | (c) lower a watermark by `DELETE` + re-`INSERT` | refused | `42501: permission denied for table placement_watermark` |
| **P12** | (c) lower placement watermark via `ON CONFLICT DO UPDATE` | refused | `P0001: placement watermark may not decrease (20 -> 1)` |
| **P13** | (c) lower `apply_scope` via `ON CONFLICT DO UPDATE` | refused | `P0001: instance watermark may not decrease (10 -> 1)` |
| P14 | (d) regress terminal `APPLIED → RUNNING` | refused | `P0001: apply_request d000…0001 is terminal (APPLIED) and immutable` |
| P15 | (e) `DELETE FROM apply_step_intent` | refused | `42501: permission denied for table apply_step_intent` |
| P16 | (e) `UPDATE apply_step_intent.idempotency_key` | refused | same |
| P17 | (f) request pointing at an instance existing only under another tenant | refused | `23503 … "request_has_scope"` |
| P18 | (g) `placement_watermark` with the nil sentinel | refused | `23514 … "real_placements_only"` |
| P19 | (h) fence bypass via `placement_id = NULL` | refused | `23502: null value in column "placement_id" … violates not-null constraint` |
| P20 | (i) `SET ROLE sm_dp_migrator` (sideways) | refused | `42501: permission denied to set role "sm_dp_migrator"` |
| P21 | (j) `TRUNCATE sm_data.apply_attempt` | refused | `42501: permission denied for table apply_attempt` |
| P22 | (j) `TRUNCATE sm_data.apply_request` | refused | `42501: permission denied for table apply_request` |
| P23 | `ALTER TABLE … DISABLE TRIGGER apply_scope_monotonic` | refused | `42501: must be owner of table apply_scope` |
| P24 | `DROP FUNCTION instance_watermark_only_rises() CASCADE` | refused | `42501: must be owner of function sm_data.instance_watermark_only_rises` |
| P25 | `CREATE OR REPLACE` the guard function as a no-op | refused | `42501: permission denied for schema sm_data` |
| P26 | setup: create an attempt row that definitely exists | ok | (guards against the "0 rows updated" false-green) |
| P27 | re-point that attempt to a different tenant | refused | `23503 … "apply_attempt_tenant_id_apply_id_intent_id_fkey"` |
| P28 | re-point that attempt to a different `apply_id` | refused | same FK |
| P29 | (I5 note) read every tenant's rows | succeeded | 3 tenants returned — expected, see I3 |
| P30 | (I5 note) insert under any tenant it names | succeeded | expected, see I3 |

**26 of 27 attack probes closed** — 31 `P` markers total, less P26 (a setup leg), P29/P30
(documented `I5` notes, not attacks) and the P5-detail characterisation leg, leaves 27 attack
probes, of which exactly one (**P5**) succeeded. The only object-creation success is the temp
schema. P12/P13 matter: the
`ON CONFLICT DO UPDATE` lowering path is caught by the `BEFORE UPDATE` trigger on both tables.
P23–P25 confirm the runtime cannot disable, drop, or redefine the guards.

---

## 8. Findings

**No defects. Zero statements needed changing; both phases ran verbatim.** Three informational
notes, all for the runbook author.

### I1 — INFORMATIONAL · proposed runbook hardening: revoke `TEMP`
`sm_dp_runtime` can create temp tables (`pg_temp_3`, `relpersistence='t'`), because PostgreSQL
grants `TEMP` on a database to `PUBLIC` by default and Phase 0 does not revoke it. Temp objects are
session-local, invisible to other sessions, and dropped at disconnect, and this touches neither
`sm_data` nor `public` — **the design makes no claim this contradicts**; its object-creation claim
is specifically about schema `public`, and that claim holds. Offered purely as **proposed runbook
hardening** if the install wants the no-object-creation property to be absolute:

```sql
REVOKE TEMP ON DATABASE <sm_database> FROM PUBLIC;
```

### I2 — INFORMATIONAL · (s)'s protection lives in the runbook, not the SQL
Phase 0's SQL is byte-identical across `91182d0`, `aa5ac48` and `afee944`; only its comments
changed. The fix for the `public` hazard is therefore a change of *executor*, and the file
self-protects not at all — run it under anything but the grantor and it silently WARNINGs again, as
both negative controls show. Obligation (s)'s assertion is the only mechanical guard against a
silent regression, which argues for keeping it in the migration suite's bootstrap test rather than
only in runbook prose.

### I3 — INFORMATIONAL · `I5` remains application-enforced (expected, unchanged)
The runtime can read and insert rows for any tenant it names; there is no RLS on these tables. This
matches the design — `I5` is enforced by the central data-access module — and is not a claim the DDL
makes. Recorded so this evidence set is not misread as proving tenant isolation at the database
layer.

### Method notes (not findings)
Two self-inflicted issues, both caught and re-run before scoring, both instances of the exact
mis-attribution hazard the document's harness rule exists to prevent:
1. An `op_known` probe whose row also violated `apply_has_generation`, so the wrong constraint
   fired. Re-run isolated.
2. A first attempt at the (t) batch used a shell variable holding a multi-word command; **zsh does
   not word-split unquoted variables**, so the harness never executed and **no legs were recorded**
   — the failure was loud (`no such file or directory`) rather than silently green. Re-run through
   a shell function.

Both argue the same thing the design already says: pin the constraint **name**, never "an error
occurred".

---

## 9. Cleanup

```
$ docker rm -f pg14-c pg14-negctl
pg14-c
pg14-negctl

$ docker ps -a --filter "name=pg14-" --format '{{.Names}}'
(no output — both containers removed)
```

All owner-side positive controls ran inside `ROLLBACK`; the `future_table` probe dropped its own
table. `public.smuggled` was created only in the two negative-control databases, where its success
*is* the evidence. No repository file outside this report was written, nothing was committed,
`contracts/` was not touched, and the earlier runs' scratchpads and reports were left alone.

---

## 10. Verdict

**Yes.** The DDL/DCL sketch at commit `afee944` stands green for (a)–(u) — and, per §3, so does the
sketch at **`d87c2e7`**: 179 legs, 0 status changes across the 168 replayed, 11 new legs for the
`resolution_note` grant all as expected.

- **168 legs executed**, every one emitting an `[OK]`/`[REFUSED]` marker into a named log file —
  **102 refusals** (each quoting SQLSTATE and its constraint / index / trigger / function name) and
  **66 positive controls**. Reconciliation: 123 obligation legs + 11 (s)-control legs + 31 probe
  legs + 3 harness/seed legs = 168.
- **Both phases execute verbatim** from the extracted bytes (md5s confirmed), 7 + 24 statements,
  `EXIT=0`, **zero edits**.
- **19 obligations PASS** — (a)–(p), (s), (t), (u).
- **2 obligations are N/A-engine** — (q), (r); (q) measured to confirm no DB artifact exists.
- **0 FAIL.**
- Every obligation-(s) claim in this report is backed by a marked, logged statement and result —
  including the ACL transition, the `has_schema_privilege` assertion, the behavioural
  `CREATE TABLE` in all three databases, and both negative controls. The summary table's leg counts
  are the measured marker counts, not estimates.
- The (s) negative control **cannot** be a literal Phase 0 re-run in the same cluster (roles are
  cluster-scoped; measured: `42710: role "sm_dp_owner" already exists`), so it was run both ways —
  the `REVOKE` in isolation in a second database owned by a non-superuser, and the literal complete
  Phase 0 in a second container. Both leave the assertion true.

Three informational notes (proposed `TEMP` hardening, the fix living in the runbook rather than the
SQL, and application-enforced `I5`) are recorded above; none blocks the DDL.
