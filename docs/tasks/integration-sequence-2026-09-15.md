# Integration sequence — landing plan 005 and the planning work

⚠️ **Updated 2026-09-16.** The planning branch now also carries the **ratified** `D10` amendment, **eighteen** owner
rulings (`OR-1`…`OR-18` — earlier revisions said eleven, then fifteen), **plan 009**, and the Argon2id benchmark
evidence. ⛔ **The order below is CORRECTED, not unchanged** — see the note under *The required order*.

**Status: historical integration sequence.** The instructions and starting measurements below are retained as
provenance; they are not statements about the current integration HEAD, branch state, or publication status.

Three streams need to reach `integration/dp-remote-demo`, and they interact. This file exists because an earlier
handover called the final step "a clean fast-forward" — which was true of the *committed* integration head and
misleading about the tree as it actually stands.

## Starting situation (historical measurement)

| Fact | Measurement |
|---|---|
| `integration/dp-remote-demo` HEAD | `2a0047e` at the start of this sequence; the current integration HEAD includes the preserved demo commits, merged plan 005 and the rebased planning candidate |
| Its worktree | ⚠️ **dirty: 32 modified + 12 untracked, no stash, nothing preserved anywhere.** `git diff --shortstat` says *1,212 insertions* — ⚠️ **tracked only.** The 11 untracked source files (excluding `.playwright-cli/`) add ~863 more lines, so the **at-risk volume is ~2,075 lines**, not 1,212 |
| `plan005-runtime-completion` (`ba7db2e`) | **not** an ancestor of `2a0047e`; 49 changed paths |
| `planning/g2-and-integration-reference` | branched at `2a0047e`; docs only; **zero** path overlap with the dirty **demo** |
| ⚠️ **planning ∩ plan 005** | **two files, both prose registers** — `docs/design/DECISIONS.md` and `docs/design/backend/eda-walking-skeleton.md`. Plan 005 rewrites both and so does this branch |
| ⚠️ **plan 005 ∩ dirty demo** | **exactly one file** — `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/SshDockerRuntimeAdapter.java` |
| `git merge-tree --write-tree 2a0047e plan005-runtime-completion` | exit 0 — **against the committed head only** |

**Why that one file matters.** The demo's uncommitted edits to `SshDockerRuntimeAdapter.java` are invisible to
`merge-tree`, which reads commits. Commit the demo and plan 005's merge is being computed against different
content. **The merge must be re-measured after step 1, not before it.**

## The required order

⛔ **CORRECTED 2026-09-16 by owner instruction, because the previous order could not execute.** It had **step 5
rebase the planning branch** and **step 7 commit the planning work** — so step 5 would have rebased a branch with
**20 uncommitted files**, which `git rebase` refuses (`cannot rebase: You have unstaged changes`). ⛔ **A rebase
cannot precede the commit of the thing being rebased.** Committing planning now moves to **step 1**.
⚠️ **Twenty, not nineteen** — this file is itself the twentieth.

| # | Step | Why it is here and not elsewhere |
|---|---|---|
| **1** | **Review the planning diff and commit it locally in coherent scoped commits.** | ⛔ **Must precede any rebase.** 19 uncommitted files cannot be rebased, and an uncommitted register is the one artifact a merge conflict can destroy without a build failure to reveal it. Local only — no push. |
| **2** | **Review, test and preserve the dirty demo work in scoped commits on `integration/dp-remote-demo`.** ⛔ **Exclude `.playwright-cli/`.** | ~2,075 at-risk lines with no stash and no branch (measured 2026-09-16: **32 modified + 12 untracked**). ⚠️ **Test before committing.** **Three coherent scopes, measured:** **(a)** `dp-core` SSH — `SshDockerTransport`, `SshjDockerTransport`, `SshDockerRuntimeAdapter`, `SshDockerConfigEditor`, `SshDockerDiagnostics` + new `SshDockerMetrics.java` + 3 modified and 1 new test; **(b)** `demo-launcher` — 5 modified + 4 new classes, 3 new tests, 2 new `demo_control` migrations; **(c)** `frontend` — designer (`DesignerPage`, `RestartConfirm`, `ApplyOutcomePanel`), governance (`GovernancePage`, `LogViewer`), monitoring chart, i18n, test doubles. ⛔ **Scope (a) must land as ONE commit** — see step 4. ⛔ **Never rewrite, discard, stash or reset that worktree**; commit what is there. |
| **3** | **Re-measure plan 005 against the newly committed integration head.** | `git merge-tree --write-tree` read `2a0047e` and returned 0; the demo's uncommitted `SshDockerRuntimeAdapter.java` edits were **invisible** to it. After step 2 the content differs, so the measurement must be **re-taken, not re-asserted**. ⚠️ **Expect it to stay clean:** measured 2026-09-16, plan 005 inserts 10 lines at **~line 47** (the `replicas()` enumerator) while the demo rewrites **lines 94–99** of `status()` — ~47 lines apart, so Git should auto-merge both. |
| **4** | **Merge plan 005, resolving `SshDockerRuntimeAdapter.java` deliberately.** | The single code overlap — ⛔ **and the risk is SEMANTIC, not textual.** The two hunks do not collide (step 3), so a clean auto-merge proves nothing: the demo's `status()` rewrite calls `transport.statusSnapshot(p)`, `StatusSnapshot.container()` and `.configHash()`, **none of which exist at `2a0047e`** — they arrive only with step 2's scope (a). ⛔ **So the merge must keep BOTH plan 005's `replicas()` and the demo's transport methods, and the verification is `mvn -f backend/pom.xml -o test` compiling and passing — not an absence of conflict markers.** |
| **5** | **Rebase the already-committed planning branch onto that integration head.** | Now possible, because step 1 committed it. |
| **6** | **Resolve `docs/design/DECISIONS.md` and `docs/design/backend/eda-walking-skeleton.md` manually, preserving both streams.** | The two prose registers both streams rewrite, and the two places a mis-merge is **invisible** rather than a build failure. ⛔ **Verify by name afterwards** that plan 005's `BE-D25`/`BE-D26` work survived — ⚠️ **the decision *rows* live in [`backend/decisions.md`](../design/backend/decisions.md), which planning does not touch; what `DECISIONS.md` carries is the `BE-D*` matrix row and open item 1's mentions** — and its `eda-walking-skeleton.md` edits survived **alongside** this branch's `OR-1`…`OR-18`, the `D10` and `D11` rows, items 39/40 and the §4.4/§5.1/§5.4/§6 gate rows. A conflict resolved by taking one side wholesale loses one stream silently. |
| **7** | **Re-run all checks, and refresh every fact measured against `2a0047e`.** | `git diff --check`, relative links, in-document anchors, markdown tables, `./contracts/run-checks.sh --strict`, `mvn -f backend/pom.xml verify`. ⚠️ **Stale by construction after step 4:** §13/§14 of [`console-integration.md`](../design/common/contracts/console-integration.md) most of all — the seven-operation `RuntimeAdapter` becomes **eight** (`BE-D25`'s `replicas`); the health gate, rolling strategies, resume and stop move from *"implemented on a branch that is not an ancestor"* to *"implemented here"*; the `BE-D` ceiling moves `BE-D24` → `BE-D26`. ✅ **This is also when permanent decision ids may first be allocated** — ruling **OR-17**: only after plan 005 is merged and the merged `DECISIONS.md` has been re-read. |
| **8** | **Fast-forward `integration/dp-remote-demo` — only after step 7 verifies.** | ⛔ Not before. It is a fast-forward onto an integration head that does not exist until step 4. |
| **9** | **Push without force — only after confirming local and remote refs.** | ⛔ **No `--force`, no `--force-with-lease`.** ⚠️ **Measured: neither `integration/dp-remote-demo` nor `planning/g2-and-integration-reference` has an upstream**, and no `push.autoSetupRemote` is configured — so a bare `git push` fails with *"no upstream branch"*. The first push is `git push -u origin integration/dp-remote-demo`, naming the branch explicitly. Confirm `git rev-parse HEAD` against `git rev-parse @{u}` once it exists, and inspect `git log --oneline @{u}..HEAD` first. ⛔ **Stop and ask if a non-fast-forward is reported** — do not resolve it with force. |

⚠️ **Steps 1 and 2 are independent of each other** and may be done in either order; everything from step 3 onward
is strictly ordered. ⛔ **Do not reorder 3→9.**

### Step 1's commit split — five coherent scopes

| Commit | Contents |
|---|---|
| 1 | **The ratified `D10` amendment and the `D11` amendment** — `docs/design/D10-G2-APPROVAL-2026-09-15.md`, `docs/design/D10-AMENDMENT-PROPOSAL.md`, `docs/design/D11-AMENDMENT-PROPOSAL.md`, and the historical [`D10-G2` demo waiver](../design/archive/demo-waivers/D10-G2-DEMO-WAIVER-2026-09-14.md) |
| 2 | **The register** — `docs/design/DECISIONS.md` (`OR-1`…`OR-18`, the `D10`/`D11` rows, the open-items sweep) |
| 3 | **The shared reference** — `docs/design/common/contracts/console-integration.md`, `docs/design/common/contracts/README.md`, `docs/design/backend/BE-D4-AMENDMENT-PROPOSAL.md`, `docs/design/backend/design.md`, `docs/design/backend/eda-walking-skeleton.md` |
| 4 | **The plans** — `docs/plans/backend/006`–`009` plus `docs/plans/backend/evidence/006-argon2id-benchmark.md` |
| 5 | **Requirements, task sequences and the index** — `docs/requirements/REQ-006-rbac.md`, `docs/requirements/traceability.md`, `docs/tasks/demo-delivery-sequence.md`, `docs/tasks/integration-sequence-2026-09-15.md`, `CLAUDE.md` |

⛔ **`contracts/` is not in any of these commits** — this branch does not modify it, and a cosmetic fixture reformat
picked up during the session was reverted deliberately.

## What must not be said until it is true

- ⛔ Do **not** describe the integration as "a clean fast-forward" before step 3 has been re-run and step 4 has
  completed. It is a fast-forward of the *planning* branch onto an integration head **that does not exist yet**.
- ⛔ Do **not** push before step 9, and then **without force**. Confirm local and remote refs first; **stop and ask**
  if a non-fast-forward is reported.
- ⛔ Do **not** rebase or reset any other worktree to make a step easier.
- ⛔ Do **not** run the §11.3 broker ACL spike until the demo environment is **down**. It provisions VPNs, client
  usernames and ACL profiles — shared state of exactly the kind that breaks a live demo — and until it runs,
  `BE-D5` cannot ratify and live notify cannot be enabled for any shared install.
