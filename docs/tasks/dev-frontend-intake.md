# Intake report — `origin/dev/frontend` into the merged backend baseline

| | |
|---|---|
| **Purpose** | Classify what may be taken from `origin/dev/frontend`, what must be reconciled first, and what must not be taken at all — and recommend the import sequence. |
| **Backend baseline (ours)** | `85170b4` *Merge agent gateway design*, branch `contracts-backend-consumption` |
| **Frontend source (theirs)** | `origin/dev/frontend` @ `c27fda1` *feat(frontend): validate multi-version solution packages* |
| **Merge base** | `9c0d06e` *Close the external review's seam-bypass and post-close-send findings* |
| **Evidence** | [`evidence/dev-frontend-intake-census.sh`](evidence/dev-frontend-intake-census.sh) → [`.txt`](evidence/dev-frontend-intake-census.txt). Re-run it before quoting any figure here. |
| **Prepared** | 2026-09-11 |

⚠️ **This is an intake analysis, not an approval.** Nothing here imports anything, and the sequence in §6 is a
recommendation for a branch that does not yet exist.

**Method note (`L12`).** Every count below is produced by the committed census script. It pins all three refs,
verifies the merge base is what this report claims, and **§7 re-reads this document and fails if any figure quoted
here no longer matches what it computed** — so evidence and prose cannot drift apart silently. Its last line reads
`CHECKS PASSED (0 failed)` or the figures are unusable.

Three rounds of correction, each worth recording because each was a different failure:

1. An ad-hoc "44 `BE-D12` citations" conflated **files** with **lines**. Verified: **6 files / 29 lines**.
2. Four of the script's own self-checks had inverted conditions and could never fire — `L7`, in the checker.
3. The citation measures were **self-referential**: taken over the worktree, they counted *this report*, so quoting a
   figure changed it. They are now taken against the commit `85170b4`. Round one shipped two stale figures (9/38 and
   8/39 in the evidence, 6/29 and 7/36 in the prose) and `CHECKS PASSED` did not notice, **because no check guarded a
   quoted number**. §7 is that check, added in response.

A grep count is not a verified figure, and a green checker is not evidence unless a check exists for the thing you
are claiming.

---

## 1. Scale

| Measure | Value |
|---|---|
| Commits on theirs not in the base | **239** |
| Files changed vs base | **1,349** |
| `frontend/` | 1,016 files, +93,551 / −5,785 |
| `docs/` | 185 files, +24,574 / −277 |
| `contracts/` | 49 files, +2,585 / −335 |
| `go-agent/` | 21 files, +1,857 |

This is a large, coherent body of frontend work — roughly 125 completed FE tasks, an as-built REST contract, and a
full RBAC/Groups console — sitting on a branch whose *non-frontend* areas diverge from the backend baseline in ways
that cannot be resolved by merging.

## 2. Why a direct merge is refused — measured, not asserted

`git merge origin/dev/frontend` into `85170b4` would compute its base at `9c0d06e`, where `backend/pom.xml` exists;
on theirs it does not. The danger is **not** that the merge would loudly delete the backend. It is that it would
**quietly** do so:

| | Files |
|---|---|
| `backend/` files present on ours | **172** |
| deleted by theirs relative to the base | **73** |
| → **silently deleted by the merge** (deleted theirs, untouched ours ⇒ git takes the delete, no conflict) | **70** |
| → raised as a `modify/delete` **conflict** | **3** |

The three that would conflict are `arch-tests/.../PlaneBoundaryTest.java`, `dp-core/pom.xml` and
`dp-core/.../SpringTransactionBoundary.java`. An operator resolving three conflicts would reasonably conclude the
merge was nearly clean. **`backend/pom.xml` — the reactor root — is in the silent set**, along with 20 `mesh-events`
files, 17 spike files, 12 `dp-core`, 12 `arch-tests` and the module POMs. The census asserts the reactor root's
membership in that set explicitly, so this claim fails loudly if it ever stops being true.

**Provenance: an evil merge, not a later deletion.** The only commit on theirs touching `backend/pom.xml` in full
history is the merge `0bea7a2` *"Merge contracts-backend-consumption into dev/frontend"*. Its frontend-side parent
`4863355` does not have the file; its other parent **is** `9c0d06e`, which does. The merge resolution took the
frontend side wholesale and dropped the backend tree, with no commit-message record of the choice. Nothing
subsequently restored it.

**Consequence for intake:** every import must be **path-scoped and explicit**. No merge, no `git checkout theirs -- .`,
no whole-directory copy above the level of `frontend/`.

## 3. Classification

### 3.1 Safe — frontend-only candidates

`frontend/**` is the bulk of the branch and has **no runtime backend dependency** — it builds against MSW fixtures,
and the as-built REST record notes of the dummy backend that it *"reaches no MI host, opens no SSH session, and polls
no actuator"* (`console-rest-surface.md:139` — that sentence is about the dummy backend, not about `frontend/`, and an
earlier draft of this report cited it as though it were evidence of self-containment).

⚠️ **It is not self-contained at build time, and this blocks the import.** Two files reach up out of the tree into
the single-writer `contracts/` directory §3.2 forbids importing, and both are hard couplings rather than references:

| File | Reaches | Kind |
|---|---|---|
| `frontend/contracts/solution-package/validate.ts:12` | `import solutionPackageSchema from '../../../contracts/solution-package.schema.json'` | **compile-time** — the schema is bundled straight into Ajv |
| `frontend/contracts/solution-package/validate.test.ts:14` | `resolve(here, '../../../contracts/validation/negative')` | **test-time** — reads the shared negative fixtures |

Two further references are **not** couplings and should not be counted as such: `types.ts:4` and
`packages/README.md:6` merely cite the schema as the authority, and `scripts/generate-types.mjs` looks for
`../contracts/openapi.yaml`, which does not exist on either branch — the script says so and exits cleanly.

And the coupling is to the **changed** schema. Theirs moves `packageVersion` from `"type": "integer", "minimum": 1`
to an array; the baseline still has the integer form; the frontend's own fixture
`frontend/contracts/packages/db-designer/package.json:3` is `"packageVersion": [`, and `validate.test.ts` asserts it
is valid. Theirs' head commit is literally *"feat(frontend): validate multi-version solution packages"*. **Importing
`frontend/**` onto this baseline therefore red-tests immediately**, and no amount of ordering fixes it — the schema
change has to travel with it, through the single-writer process. §6 step 2 is amended accordingly.

Also safe in kind:

| Path | Note |
|---|---|
| `frontend/**` | The whole tree. Bring it wholesale; it has no counterpart on the baseline. |
| `docs/plans/frontend/**` | New folder, no counterpart. 118 files under `reports/` — 116 `task-*-report.md`, one issue report and a README — plus three plans. |
| `docs/checklists/a11y-checklist.md`, `a11y-manual-keyboard-script.md` | New files, additive. |
| `.github/**` | Frontend CI only; verify it does not claim to gate the backend before taking it. |

⚠️ Two qualifications, neither blocking. `frontend/` **is** where the identity vocabulary is wrong relative to
`SM-D34` (§4.3), so importing it does not make it correct — it makes it *present and consistently wrong*, which is
the right order of operations. And `docs/plans/frontend/execution.md` records a direct-in-session execution route
that diverges from `CLAUDE.md`'s plan workflow; worth a read before adopting it as process.

### 3.2 Requires reconciliation — design and contract

| Path | Why it cannot be taken as-is |
|---|---|
| `contracts/**` (49 files) | Single-writer, gated by `run-checks.sh` check 10, mirrored by the backend under `BE-D10`'s key locks. §4.2. |
| `docs/design/DECISIONS.md` | Contains the `D18`/`D19`/`D20` LOCKED claims — **open item 34**. |
| `docs/design/backend/decisions.md`, `design.md`, `spike-authz-result.md` | Contain a colliding `BE-D12` — **open item 37** — and a tenant→organization sweep through backend design text the backend team owns. |
| `docs/plans/backend/001`, `002`, `003` | **Executed plan records on our baseline.** Theirs edits them for vocabulary. An executed plan's Results section is a completion record; editing it retroactively rewrites history. Take nothing here. |
| `docs/design/frontend/**` | The `FE-D` families **diverge at `FE-D25`**, and two ids already mean different things on the two branches — see §4.4. Theirs also carries ~28 further decisions resting on `D18`/`D19`/`D20`. |
| `docs/design/frontend/reference/sm-v2/` → `reference/` | A **path flattening** that breaks **36 citing lines across 7 baseline documents**, including `DECISIONS.md`, `D10-AMENDMENT-PROPOSAL.md` and `eda-walking-skeleton.md`. §4.4. |
| `docs/design/{dynamic-ui,dynamic-metrics}/**`, `architecture.md`, `D10-AMENDMENT-PROPOSAL.md` | Vocabulary sweeps into documents whose decisions are cited from the backend design. Reconcile, do not overwrite. |
| `docs/requirements/**`, `docs/tasks/lessons.md` | Additive in substance; re-apply by hand, keeping both sides' lessons. |
| `go-agent/**` (21 files) | A forked `AG-D*` family already known not to be a base. Out of scope for the demo; do not import with the frontend. |
| `docs/design/common/contracts/solution-manager-api-reference.docx` | A **binary** Word document in a text-reviewed design tree. Not reviewable by diff; take the Markdown, leave this. |

### 3.3 Forbidden — must not be imported in any form

1. **The `backend/` tree state from theirs.** Not the deletions (§2), and not `backend/README.md`'s regressed content.
2. **Any `docs/design/**/decisions.md` file**, until open items 34 and 37 are resolved. A decision-register import is
   how an id silently changes meaning.
3. **Edits to `docs/plans/backend/001–003`.** Executed records.
4. **`contracts/events/envelope.schema.json` and `asyncapi.yaml` renames**, outside the `BE-D4` amendment (§4.2).

## 4. Explicit call-outs

### 4.1 Backend deletions
§2. **70 silent, 3 conflicting, of 172.** Root cause is the evil merge `0bea7a2`.

### 4.2 Contract and schema changes — the wire-breaking one

Theirs renames the event envelope's **required** field:

```
-  "required": [ …, "producer", "tenant",        "entity", "stateVersion", … ]
+  "required": [ …, "producer", "organization",  "entity", "stateVersion", … ]
```

and the four AsyncAPI topic addresses from `sm/v1/{tenant}/…` to `sm/v1/{organization}/…`.

The topic *parameter* rename is cosmetic — the topic string's shape is unchanged, the level is still positional. **The
envelope key rename is not.** It is a breaking change to a normative, check-10-gated contract that the backend mirrors
under `BE-D10`'s key locks; the census lists **eight `mesh-events` files** that name the field, including
`SchemaMirrorLockTest`, `EnvelopeGoldenTest` and `NotifyTopicsTest` — tests that exist precisely to fail when the
mirror drifts.

**Recommendation.** Do not cherry-pick it. `BE-D4` is already the subject of a pending amendment (G3) carrying four
riders, and `contracts/` is single-writer by policy. The rename should **ride that amendment**, landing the schema,
the backend mirror and the frontend in one reviewed change. Recorded as **open item 36(c)**.

Also present: four new validation negatives (`gate12`–`gate14`) and changes to `solution-package.schema.json` and
`view-models/`. These are ordinary contract work and reviewable on their own merits — but through the single-writer
process, not as part of a frontend intake.

### 4.3 Identity-vocabulary changes

Substantively, theirs and the approved scope model **agree**: Organization is the isolation root, Tenant stops being a
level, the isolation boundary itself is untouched, and the browser never supplies an organization identity. Three
divergences to settle, none deep:

| | Theirs | `SM-D32`–`SM-D34` |
|---|---|---|
| Middle level | **Sub-organization** | **Organization Unit** |
| Wire keys | three spellings: `organization`, `{subOrganization}`, `org`/`subOrg`/`env` | one canonical set (§5.2 of the amendment) |
| RBAC `scope: string[]` | carries **names** | must carry **ids** — a renamed unit must not move a grant |

The third is a behavioural change, not a spelling change, and it is the one that needs a test. All three are
**open item 36**.

### 4.4 Decision and register changes that cannot be cherry-picked

| | Finding |
|---|---|
| **`D18` / `D19` / `D20`** | Recorded **LOCKED** on theirs; the `D*` family is closed at `D17` here; `D20` supersedes `D11`'s hierarchy clause. Uncorroborated in this baseline. **Open item 34** — and it decides whether G2 is closed, so it is the highest-value question in this report. |
| **`BE-D12` collision** | Ours = the `dp-remote` apply protocol (implemented). Theirs = "Organization replaces Tenant at every backend boundary". Both allocated in good faith from a base where the family ended at `BE-D11`. Ours is cited in **6 files / 29 lines**. The surface is **exactly one id** — theirs' backend family ends at `BE-D12`, so there is nothing beyond it to shift. **Open item 37.** |
| **`FE-D25` / `FE-D26` collide** | The families diverge at **`FE-D25`**, not at the maxima. Ours `FE-D25` = *"sm-v2 is the visual specification of record"*, theirs = *"Idle-lock draft preservation"*. Ours `FE-D26` = *"the verified product palette governs UI semantics"*, theirs = *"Desktop-only ≥ 1280 px — SUPERSEDED"*. So *"per `FE-D26`"* already means two things — `I3`'s exact prohibition, same class as the `BE-D12` collision. Worse: theirs' `FE-D29` *supersedes their* `FE-D26`, and on this baseline `FE-D26` is a live, non-superseded palette decision; theirs' `FE-D52` restates ours' `FE-D26` verbatim under a new number. The census compares the two families **id-by-id**, not by maxima. **Open item 37**, with `BE-D12`. |
| **`sm-v2/` flattening** | Breaks **36 citing lines in 7 baseline documents**. Either keep the baseline's path, or re-point all 36 in the same commit. |

## 5. What the intake is actually *for*

Two artifacts on theirs are worth more than the rest for the demo, and neither is code:

1. **`docs/design/common/contracts/console-rest-surface.md`** (3,284 lines) — the as-built HTTP contract for 66
   operations across 13 screen groups, with request/response shapes, error bodies and the React Query key each one
   feeds. ⚠️ Read its own honesty markers: **only the §16 metrics surface is `SPECIFIED`**; everything else is
   `CONTRACT: TBD` — client-invented and confined to one repository file. It is an excellent *starting point* for the
   real contract and must not be mistaken for one.
2. **`frontend/`** itself — a working console that already renders the hierarchy, the RBAC screens and the Designer.

## 6. Recommended import sequence → `integration/dp-remote-demo`

Branch from the **merged backend baseline** (`85170b4` or its successor), never from `dev/frontend`. Each step is
independently verifiable; nothing later is started until the step before it is green.

| # | Step | Gate before starting |
|---|---|---|
| 0 | Create `integration/dp-remote-demo` from the backend baseline. Confirm `mvn -f backend/pom.xml verify` and `./contracts/run-checks.sh --strict` are green **before** anything is imported, so any later breakage is attributable. | — |
| 1 | **Resolve open item 37** (the `BE-D12` collision) on paper. Cheap: theirs' substance is absorbed by `SM-D34` and needs no `BE-D` id. | — |
| 2 | **Land the `solution-package.schema.json` `packageVersion` change** (integer → array) through the single-writer contract process, with `run-checks.sh --strict` green. §3.1 measures why this cannot follow the frontend: the frontend compiles the root schema in and its own tests assert the array form. | step 0 |
| 3 | Import **`frontend/**`** wholesale, plus `docs/plans/frontend/**` and the two a11y checklists. No other path. Verify the frontend's own gates (`check-guards.sh`, lint, vitest) pass **and** the backend build is untouched. | step 2 |
| 4 | Import **`console-rest-surface.md`** as a *reference* document, re-pointing its relative links. Do not promote it to a normative contract. | step 2 |
| 5 | Re-point the **`sm-v2/`** citations, **both directions in one commit**: 36 lines across 7 baseline `docs/` files cite `sm-v2/`, and 39 lines across 37 of theirs' `frontend/` source files carry the flattened `reference/app/…` path that is wrong on this layout. A re-pointing scoped only to `docs/` leaves the incoming half broken. | step 2 |
| 6 | Apply the **vocabulary rename** (open item 36 a + b) across frontend and the new REST layer as **one** change, including `scope: string[]` names → ids with a test that a renamed unit does not move a grant. | `SM-D32`–`SM-D34` ratified |
| 7 | Land the **envelope/topic rename** (36 c) inside the `BE-D4` amendment, with schema, `mesh-events` mirror and frontend in one reviewed commit. | **G3** |
| 8 | Import nothing from `docs/design/**/decisions.md` or `docs/plans/backend/**`, ever. Re-derive instead. | — |

**Steps 1 and 4–5 are unblocked today. Steps 2–3 are not**, and an earlier draft of this report wrongly said they
were: importing `frontend/**` requires the root-schema change first (§3.1), and that is a `contracts/` change, which
is single-writer and reviewed. So the honest statement is that the console can reach the real backend baseline
**after one reviewed contract change**, not with none. Step 6 waits on ratification of `SM-D32`–`SM-D34`; step 7
waits on G3.
