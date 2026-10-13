# Solution Manager console — frontend execution plan

| | |
|---|---|
| **Component** | `frontend/` (+ `contracts/`) |
| **Executes** | [`implementation.md`](implementation.md) — **111** tasks, `FE-001`…`FE-110`, `FE-111` (105 feature/hardening + 6 Phase R). **That plan is authoritative**; this one only says *how, in what order, by whom, and how we know it worked.* |
| **Design** | [`../../design/frontend/design.md`](../../design/frontend/design.md) — the spec both plans argue from |
| **Requirements** | `REQ-001` `REQ-002` `REQ-003` `REQ-005` `REQ-006` `REQ-007` `REQ-008` `REQ-009` (`REQ-004` not implemented — implementation plan §13.1) |
| **Decisions** | `D7` 🔒 `D10` 🔒 `D13` 🔒 `B/D15` 🔒 · `I1`–`I7` · `FE-D1`–`FE-D29` (`FE-D26` superseded by `FE-D29`) · `UI-D*` · `DM-D*` · `SM-D*` |
| **Route** | `superpowers:subagent-driven-development` — fresh implementer subagent per task, task review after each, one controller session per phase group |
| **Status** | **DRAFT — NOT APPROVED.** Authorises nothing on its own; see §1. |

**Goal:** Turn 111 written tasks into merged, reviewed, tested frontend code without the controller losing its place, without a subagent inventing a backend contract, and without a single one of the design's `must not` rules being quietly dropped.

---

## 0. Amendment (2026-08-20) — actual route diverges from §2/§10 for small tasks

`FE-001`/`FE-002` ran through the `subagent-driven-development` route this plan specifies below:
fresh implementer subagent per task, controller review, report written to the git-ignored
`$WS/task-NNN-report.md`. Starting `FE-003`, the developer chose **direct in-session
implementation** instead — no implementer/reviewer subagent split — for single, well-scoped
tasks, confirmed explicitly on `FE-003` (see the repo-local memory this note mirrors). §§1–14
below describe the original SDD design and are kept as the record of that design and as the
route to fall back to for large, multi-file, multi-session work; they are **not** being followed
task-by-task for small tasks.

**What still holds regardless of route:**
- **Every task gets a committed report** at
  [`reports/task-NNN-report.md`](reports/README.md), one per `FE-NNN`, written when the task is
  verified done. This replaces `$WS/task-NNN-report.md` as the durable copy — the scratch
  workspace is still produced when the SDD route is actually used, but the committed report is
  what's expected to exist regardless of which route ran. See
  [`reports/README.md`](reports/README.md) for the shape and for which early tasks were
  reconstructed after the fact vs. written live.
- **The register (§7) and the matching GitHub issue are still the status record**, updated and
  closed exactly as §14–16 describe, whichever route produced the task.

**How to apply when picking up the next task:** default to direct in-session implementation for
a single, well-scoped task; fall back to the full SDD route (§8) for something that genuinely
needs a fresh, isolated context (e.g. a large multi-file task, or one where controller context is
already crowded). Either way, write `reports/task-NNN-report.md` before marking the task done.

---

## 1. What this authorises — and what it does not

⚠️ **This plan does not lift the implementation plan's Gate.** [`../README.md`](../README.md) still says *"Do not start a plan when it depends on a `PROPOSED` decision that ratification could still change,"* and every `FE-D*`, `UI-D*`, `DM-D*` and `SM-D*` decision this work implements is `PROPOSED`. That is recorded as risk R3 and open question Q18, owned by the architect group.

What this plan does is make the work **executable the moment the Gate lifts**, and bound exactly what may run before then:

| | |
|---|---|
| **May be scheduled** | The **105** tasks classed `READY` (66) or `FIXTURE` (39) — **after the Gate lifts** |
| **May not be scheduled** | `FE-094` (`DECISION`, blocked on Q5) and the **wire calls** of `043`, `061`, `062`, `095` (`BLOCKED`, open item 1) |
| **Ships anyway** | The **UX halves** of `043 · 061 · 062` — the implementation plan classes these `FIXTURE` for the interface and `BLOCKED` only for the network call. They are marked ◐ in the register |
| **Not scheduled at all** | Phase 7 (`090`–`095`), gated on Q4 and Q5 — see §6.7 |

**A `FIXTURE` task carries one extra, non-negotiable obligation:** it ships behind a §6.0.5 repository interface, and every invented shape carries `// INVENTED —` or `// CONTRACT: TBD —` in source. A `FIXTURE` task that hard-codes a guessed endpoint into a component has failed its review even if every test passes.

---

## 2. Route — the three decisions, and why

Asked and answered before writing this, per `CLAUDE.md` §3 and [`plan-template.md`](../../design/common/conventions/plan-template.md) (*"the ask is mandatory"*).

| Decision | Choice | Why |
|---|---|---|
| **Session mode** | **SDD, one controller session per phase group**, each resuming from the ledger | `plan-review.md`: *"new session if >5 tasks, >3 files, or >1 component"* — at 110 tasks this is not a judgement call. But a single controller across 110 tasks will compact repeatedly, and the skill names lost-controller re-dispatch as *"the single most expensive failure observed."* Per-session controllers bound that context while the ledger makes the handoff safe. |
| **Concurrency** | **Strictly sequential implementers**, batching same-shape work | The protocol's hard rule: *"Never dispatch multiple implementation subagents in parallel (conflicts)."* Parallelism would require separate git worktrees and three live controllers; §9's batching recovers most of the wall-clock without putting `src/shared/ui/index.ts` under concurrent edit. |
| **Tracking** | **§7's register (committed) + the SDD ledger (git-ignored)** | `CLAUDE.md` mandates the GitHub Project for status, but **`gh` is not installed on this machine** (`command not found: gh`). The register is the durable, reviewable record and is shaped to seed the Project verbatim once `gh` exists — see §15. |

> **Ruling — §8's types-only PRs are not scheduled.** The implementation plan's §8 rule 1 (*"publish the interface before the implementation"*) is a **parallel-stream device**: it exists so stream E can code against stream B/C/D signatures still in flight. Under sequential execution every §6.0 block is already owned by a task that creates it (§6.0.1→`026`/`028`, §6.0.2→`046`, §6.0.3→`071`, §6.0.5→`033`, §6.0.6→`032`, §6.0.7→each component task). Three extra publication dispatches would produce files their owning task then rewrites. **Cost if wrong:** if we later switch to worktree parallelism, those three PRs must land first — re-read §8 before doing so.

---

## 3. Workspace and tooling

### 3.1 Branch and worktree

Work happens on a feature branch off `design/frontend`, **never on `main`**. The skill requires an isolated workspace; use `superpowers:using-git-worktrees` to create or verify one at session start.

```bash
git rev-parse --abbrev-ref HEAD     # expect a frontend feature branch, not main
```

### 3.2 The blocker that had to be fixed first

The canonical extractor `superpowers/.../scripts/task-brief` matches headings of the form `^#+ Task <N>`. **This plan has 110 headings in `#### FE-NNN · …` form and zero in `Task N` form** — the ID format the implementation plan was commissioned with. The canonical script exits 3 (*"task 1 not found"*) on the very first dispatch.

Renaming the headings was rejected: `FE-NNN` is a stated requirement of the plan, and the IDs are cited ~400 times across §7, §8, §11, §13, §14, §17 and §18.

**Resolution: [`bin/fe-task-brief`](bin/fe-task-brief)** — the same awk extraction with this plan's heading grammar, and one correction the canonical script does not need: it stops at the next heading of level 1–4, not merely the next task heading. Without that, the final task and every phase-final task absorb the text that follows them (task `104` extracted **1,199 lines** before the fix; it is 9 now).

Verified across all 110: every brief starts with its own heading, contains exactly one task heading, spills no `### Phase` / `## §` / `# Appendix` content, and is 6–26 lines. `1`, `001` and `FE-001` resolve identically; a missing task exits 3, a malformed id exits 2.

**Everything else in the loop is used unmodified** — `sdd-workspace` and `review-package` are heading-agnostic.

### 3.3 The three commands

```bash
SD=~/.claude/plugins/cache/claude-plugins-official/superpowers/6.3.0/skills/subagent-driven-development
P=docs/plans/frontend/implementation.md

WS=$("$SD/scripts/sdd-workspace" "$P")          # .superpowers/sdd/implementation/ (git-ignored)
docs/plans/frontend/bin/fe-task-brief "$P" 46  # -> $WS/task-046-brief.md
"$SD/scripts/review-package" "$P" "$BASE" HEAD  # -> $WS/review-<base7>..<head7>.diff
```

`$WS` holds the ledger, briefs, reports and review packages for this plan only. It is scratch: `git clean -fdx` destroys it, and `git log` plus the register are the recovery path.

---

## 4. Preflight conflict scan

Mandated before Task 1: *"One row for every pair of tasks that share a file or an interface… 'The scan is clean' without those rows is not a scan you ran."* Run against the plan text, not from memory. **Every ruling below is copied into the ledger at session S1 start.**

### 4.1 Findings

| # | Finding | Evidence | Ruling | Cost if wrong |
|---|---|---|---|---|
| **F1** | **`011`–`035` originally stated deps only in a phase preamble** | Was 79 of 104 `Deps` lines | **Resolved.** Every task `001`–`110` now has a `**Deps**` line in `implementation.md`. Register §7 is a mirror, not a reconstruction | A missing Deps line would ship a brief without prerequisites |
| **F2** | **Task heading grammar breaks the canonical extractor** | §3.2 | Adapter written and verified across all 110 | Loop cannot dispatch; caught at task 1 |
| **F3** | **Phase 7 is classed `FIXTURE` but says "scheduling stays behind the Phase 7 gate"** — a task cannot be both schedulable and gated | Tasks `090`–`093` | **Both hold, at different levels.** `FIXTURE` describes *implementability*; the gate describes *authorisation*. Phase 7 is planned in full and **not scheduled** until Q4 and Q5 are answered | If Q4/Q5 land early we lose nothing — the phase is ready to run |
| **F4** | **`020` DataTable and `041` inventory grid both own selection state** | `020`: *"Selection is **controlled** and the owner puts it in the URL"*; `042`: URL serialisation | No conflict — `020` is controlled-only and holds no state; `042` owns the URL. Dispatch `020` with this sentence verbatim so the implementer does not add internal selection state | An uncontrolled grid breaks `042`'s round-trip test |
| **F5** | **`022` DiffView is consumed by four tasks** (`056`, `059`, `062`, and `023`'s mask contribution) | §6.0.7 `DiffRow` | `022` is the sole owner of `DiffRow`/`SECRET_MASK`. `056`/`059`/`062` **consume, never extend**. Any new `kind` is a change to `022`, reviewed there | A local diff variant duplicates the secret-masking rule — the one rule that must exist once |
| **F6** | **`013` vocabularies are consumed by `041`, `060`, `064`, `065`, `082`** | 26 entries across 6 vocabularies | `013` is the sole writer of `src/shared/vocab/*`. Consumers import; none defines a status string. `041`'s test is the enforcement | A screen-local status string is exactly the drift `UI-D9` exists to prevent |
| **F7** | **`029` and `070` both touch query keys** | `029` creates `queryKeys.ts`; `070` creates the canonical hash that feeds it | `029` ships the factory with a **pluggable** metrics key; `070` supplies the hash. `070` must not create a second key factory | Two factories ⇒ dedup silently stops working; `103` catches it late |
| **F8** | **`046` and `052` both hold draft values** | `046` `project(pkg, sel, draft, observed)`; `052` RHF `shouldUnregister: false` | RHF is the **only** store; `046` is pure and receives draft as an argument. `052` calls `renderedFields()` for the payload and **never** `getValues()` | The exact `FE-D17` failure: hidden branches reach the server |
| **F9** | **Two tasks create `src/shared/api/types.ts`-adjacent files** — `026` (`types.ts`) and `028` (`ApiError.ts`, `errorCodes.ts`) | Both list Files | `026` declares the interfaces; `028` implements `ApiError` and the registry. `026` must not inline a second error class | Duplicate error types; typecheck catches it |
| **F10** | **`061` mandates behaviour a reviewer could read as a defect** — a mutation that deliberately reports neither success nor failure | `ApplyOutcome` has no success variant | **Plan-mandated and correct.** The reviewer constraints block (§10) carries `G11` verbatim so this is reviewed as compliance, not flagged as a bug | A reviewer "fixes" it into a success path — the single worst regression available in this codebase |
| **F11** | **`010` dev gallery and `030` dev auth mock both rely on `import.meta.env.DEV`**, and `101` asserts both are absent from production | Three tasks, one mechanism | `010` and `030` each guard at registration; `101` asserts. No shared helper — a shared one becomes a single point of failure for two independent guarantees | A missed guard ships a mock; `101` is the net |
| **F12** | **`002` self-hosts fonts, contradicting the DS file it transcribes** (which `@import`s Google Fonts) | §B.3, `REQ-003` air-gap | Self-hosting wins — `REQ-003` is a requirement, the `@import` is an artefact of the source file. `002` records the substitution in `PROVENANCE.md`; `100` asserts zero third-party requests | An air-gapped deployment hangs on a font request |
| **F13** | **G16 / census drift** — a prior execution draft copied superseded `FE-D26` (desktop ≥1280) and listed 104 tasks after Phase R added `105`–`110` | Implementation `G16` = `FE-D29`; headings `001`–`110` | **This document matches the implementation plan:** reviewer lens is 320 px reflow; register includes Phase R | A reviewer "fixes" missing breakpoints as defects, or skips six tasks |

### 4.2 Self-consistency

Every task's own text was checked against itself — the tests it specifies against the code it specifies, the files it creates against files it later touches. **No task contradicts itself.** Three tasks (`043`, `061`, `062`) specify UI for a call they may not make; that is the documented `BLOCKED`/`FIXTURE` split, not an inconsistency.

**Nothing found contradicts the Global Constraints `G1`–`G20`.** `G16` in this document is the implementation plan's `FE-D29` text, not the superseded `FE-D26` floor.

---

## 5. The nine phases

Nine phases, seven controller sessions. A session ends at a gate; the next session's controller starts fresh, reads the ledger, and resumes. Nothing but the ledger and git crosses the boundary.

| Phase | Tasks | Count | Session | Entry | Exit gate |
|---|---|---|---|---|---|
| **0 · Foundation** | `001`–`010`, `105` | 11 | **S1** | Branch + worktree | **Gate A** — build · lint · typecheck · unit · a11y · e2e all green in CI; reflow harness exists |
| **1 · Shared UI kernel** | `011`–`025` | 15 | **S2** | Gate A | — (rolls into Gate B) |
| **2 · API seam, auth, permissions** | `026`–`035` | 10 | **S2** | Gate A | **Gate B** — a component is buildable against a repository interface with no knowledge of its backing |
| **3 · Shell, identity, inventory** | `036`–`045`, `106`, `107`, `111`, `112`, `114`–`120` | 21 | **S3** | Gate B | **Gate C** — every Phase 3 screen renders 5 states from fixtures; shell and inventory reflow |
| **4 · Designer** | `046`–`063`, `108`, `113`, `121`–`125` | 25 | **S4** | Gate C | **Gate D** — **fixture** e2e journey 1: save carries a generation, apply renders `APPLY_INDETERMINATE`. Does **not** close open item 1 |
| **5 · Governance** | `064`–`067` | 4 | **S5** | Gate C (not D) | `NOT_APPLICABLE` never red; standby ≠ healthy |
| **6 · Monitoring** | `068`–`089`, `109` | 23 | **S5** | Gate C (not D) | Gaps render as breaks in every path; Explore/dashboard reflow |
| **7 · Admin & Targets** | `090`–`095` | 6 | **S7** | **Q4 + Q5 answered** | ⛔ **not scheduled** |
| **8 · Hardening** | `096`–`104`, `110` | 10 | **S6** | Phases 3–6 merged | §18's readiness gate answered with evidence; `verify:reflow` green |

**Phase numbers are the implementation plan's own** — `Phase 4` means the same 25 tasks in both documents. A **session** is the controller-session bundling chosen in §2: seven sessions cover nine phases, because Phases 1+2 and Phases 5+6 are each small enough to share one controller without straining its context, and Phase 5 alone (4 tasks) does not justify spinning up a session.

**Phases 5–6 enter at Gate C, not Gate D.** Governance and Monitoring depend on the shell and the seam, not on Designer. If Phase 4 stalls on open item 1, Phases 5–6 proceed — that independence is the plan's §7 critical path, and it is the main reason the walking skeleton being blocked does not block the project.

**The critical path is `001 → 003 → 004 → (011–035) → 036 → 044 → 046 → 052 → 055 → 061`.** `046` is the longest single-owner item on it and depends only on the test harness (`008`), so it is the first task of Phase 4 with nothing queued behind it.

---

## 6. Phase detail

### 6.1 Phase 0 — Foundation · `001`–`010` + `105` · session **S1**

Strictly ordered: `001 → 002 → 003 → 004`, then batch **B0** (`005`, `006`, `010`), then `007`, `008`, `009`, then **`105`** (deps `002`, `008`; extends `009`'s lint). `105` must land before Gate A: later phases' responsive tasks depend on the breakpoint tokens and `assertReflow`.

`009` is the highest-value task in the phase and is dispatched on the **most capable model**: it converts eight design rules into build failures, and each needs a fixture file that must *fail* lint. A `009` that passes its own fixtures is broken, not done. `105` is also **O**: one breakpoint source, container-query primitives, and a harness that must fail on its own overflow fixture.

**Gate A**
```bash
npm run lint && npm run typecheck && npm test && npm run build && npm run e2e
npm run lint -- src/test/lint-fixtures   # MUST exit non-zero
npm test -- src/test/reflow.ts           # harness self-test
```

### 6.2 Phases 1–2 — Kernel and seam · `011`–`035` · session **S2**

Two independent trees. Order: `011`–`025` (kernel), then `026`–`035` (seam) — sequential, but with no edge between them, so a stall in one does not block the other.

Batches **B1** (`014`, `015`, `017`, `019`) and **B2** (`011`, `018`, `024`, `025`) collapse eight presentational wrappers into two dispatches. Held out deliberately: `012` (aria wiring), `013` (26 vocabulary entries), `016` (focus trap), `020` (Community-only), `021`, `022` (secret-masking), `023` (write-only) — each carries a correctness rule that deserves its own review surface.

**Gate B** — the one that is easy to fake. Prove it, don't assert it: pick any Phase 3 screen task and confirm it compiles against a repository interface with the fixture provider unbound. If a component imports `msw`, Gate B has not passed.

### 6.3 Phase 3 — Shell and inventory · `036`–`045`, `106`, `107`, `111`, `112`, `114`–`120` · session **S3**

`041` is the phase's centre and takes the **most capable model**: it renders all 12 fixture instances across 5 health, 5 not-found, 4 version and 6 config states, and must contain **zero** `if (driver === …)` or `if (locus === …)`. That absence is `REQ-005` and `REQ-008` — a table-driven test asserts it.

`043` ships its **dialog and copy only**. Its wire call is blocked (open item 1). The dispatch must say so, or an implementer will helpfully wire it.

`106` (deps `036`, `105`) and `107` (deps `020`, `041`, `105`) run after the screens they adapt. `107` takes **O**: the card view must emit the same state labels as the grid with **zero** `driver`/`locus` branches.

`111` (deps `036`, `037`, `106`) is a post-hoc defect fix, not part of Phase R: the desktop drawer ships with no collapse control and the login card ignores dark mode. It runs after `106` because it changes the same drawer state machine.

`112`, `114`–`120` were added to this phase **after** it first closed (`§0`'s amendment route): the sidebar's solution list, the DEV package loader, declare/undeclare, the select-all defect, the multi-version package (`FE-D31`) and the grid's `Package version` column and derived instance count. `118`–`120` are where the version dimension entered the inventory half of the console; `121`–`125` carry it into the Designer.

**Gate C** — every screen renders loading / empty / populated / partial / error from fixtures; no demo-state control exists in the build; `assertReflow` passes on the shell and inventory at 320 px.

### 6.4 Phase 4 — Designer · `046`–`063`, `108`, `113`, `121`–`125` · session **S4**

Starts with `046` (`project()`) on the most capable model. It is pure, imports nothing from `src/`, and its shared JSON fixture suite is run by the **Java CI too** — so `contracts/fixtures/README.md` pinning number formatting and key ordering is part of the task, not a nicety.

`061` is the hardest task in the plan. Its dispatch carries `G11` and `F10`'s ruling verbatim: **no variant of `ApplyOutcome` means "succeeded", and that is the requirement.**

`108` runs after `063` (and `105`). **O** — 100-field fixture at 320 px; stacked secret diffs still mask both sides.

`113` and `121`–`125` were added to this phase after it first closed: the Designer's view model became derivable from a real package file, the instance's `packageVersion` was threaded to every `project()` call (`FE-D37`), the read was resolved in favour of the browser projecting (`FE-D33`, §16 **Q19**), and `123`–`125` assembled the components built by `046`–`063` into the actual screen and route — which had been a placeholder until then.

**Gate D** — **fixture** e2e journey 1 green: login → instance → edit → save (generation on the request, inactive branch absent from the payload) → apply → `APPLY_INDETERMINATE` rendered, **not** success and **not** failure. This proves the UX contract. **It does not prove `dp-remote` apply correctness** (open item 1 / R1).

### 6.5 Phases 5–6 — Governance and Monitoring · `064`–`089`, `109` · session **S5**

The largest session — 27 tasks. `070`/`071` (engines) and `072`/`075`/`082`/`086` take the most capable model; the rest are standard or cheap.

The phase's acceptance is a negative: **no fixture in the suite may produce a zero-filled point, and no rendering path may connect across a gap.** `071`'s fixture suite proves it at the model layer, `073` at the ECharts option layer, `099` journey 3 end to end.

`109` runs after `086` (and `105`). Dashboard stacks by widget `order`; tabular alternative is primary below `sm`.

### 6.6 Phase 8 — Hardening · `096`–`104` + `110` · session **S6**

Each task is a check rather than a feature, so Phase 8 may begin as soon as Phase 3 merges and run alongside Phase 6's tail — the one place this plan permits overlap, because these tasks add CI jobs rather than components.

`104` closes the plan: fills the implementation plan's Review section. `FE-D25`–`FE-D29` are **already recorded** as `PROPOSED` in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md); task 104 must not edit them in place (`I3`) and must not treat them as ratified (`I4`).

`110` (deps `105`–`109`, `098`) is the reflow CI job — six widths × screen states. Dispatch before `104`.

### 6.7 Phase 7 — Admin and Targets · `090`–`095` · session **S7** · ⛔ NOT SCHEDULED

Blocked on **Q4** (RBAC verb catalog unratified — only `target.use` and `instance.bindTarget` are grantable) and **Q5** (first-admin bootstrap unspecified). `094` is the plan's single `DECISION` task; `095` is `BLOCKED` beyond read-only.

Per ruling **F3** the phase is planned in full and simply not run. When Q4 lands, `090`–`093` become schedulable without re-planning: the catalog is data (§6.0.6), so Q4 changes a fixture, not an implementation.

---

## 7. Task register — all 125

The durable record. Model: **O** = most capable · **S** = standard · **H** = cheapest.
Status: `☐` schedulable · `◐` UX only, wire call blocked · `⛔` blocked · `⚠️` decision required.

| # | Task | Phase | Deps | Ready | Model | Batch | Status |
|---|---|---|---|---|---|---|---|
| 001 | Application scaffold, strict TypeScript, lint, CI | 0 | none | READY | S | — | ✅ (d693205..db4aee1) |
| 002 | Design-token layer with stated provenance | 0 | 001 | READY | H | — | ✅ (db4aee1..157c47a) |
| 003 | MUI theme factory, colour mode, ECharts theme bridge | 0 | 002 | READY | S | — | ✅ (157c47a..33f5e91) |
| 004 | Providers, router, route table, code splitting | 0 | 003 | READY | S | — | ✅ (33f5e91..9f541e8) |
| 005 | Runtime configuration boot | 0 | 001 | READY | H | B0 | ✅ (9f541e8..031f8bc) |
| 006 | Error boundaries and the error taxonomy | 0 | 004 | READY | S | B0 | ✅ (031f8bc..1911cf3) |
| 007 | Message catalogue and formatting | 0 | 001 | READY | S | — | ✅ (62981d1..8cf5121) |
| 008 | Test harness | 0 | 001 | READY | S | — | ✅ (0cd08ff..d288cab) |
| 009 | Architecture guardrails in lint | 0 | 001 | READY | **O** | — | ✅ (a98e139..190999c) |
| 010 | Dev-only component gallery | 0 | 003, 004 | READY | H | B0 | ✅ (5c55a7f..458aecc) |
| 105 | Responsive foundation: breakpoints, layout primitives, reflow harness | 0 | 002, 008 | READY | **O** | — | ✅ (dc6ad1b..089d2d6) |
| 011 | Buttons | 1 | 003, 010 | READY | S | B2 | ✅ (0c3da70..5c5ff2c) |
| 012 | Form control wrappers | 1 | 003, 010 | READY | S | — | ✅ (83efa68..6dd8f24) |
| 013 | Chip, StateChip and the state-vocabulary module | 1 | 003, 010 | READY | S | — | ✅ (3cad708..74cb7df) |
| 014 | Alert / Banner and Tooltip / InfoTip | 1 | 003, 010 | READY | S | B1 | ✅ (cd233cf..d48464c) |
| 015 | Surfaces and page furniture | 1 | 003, 010 | READY | H | B1 | ✅ |
| 016 | Dialog, ConfirmDialog, high-consequence confirm | 1 | 003, 010 | READY | S | — | ✅ |
| 017 | Menu / Popover | 1 | 003, 010 | READY | H | B1 | ✅ |
| 018 | ToggleButtonGroup and route-driven Tabs | 1 | 003, 010 | READY | S | B2 | ✅ |
| 019 | EmptyState and Skeleton | 1 | 003, 010 | READY | H | B1 | ✅ |
| 020 | DataTable on MUI X Data Grid **Community** | 1 | 003, 010 | READY | **O** | — | ✅ |
| 021 | Toast and the persistent outcome badge | 1 | 003, 010 | READY | S | — | ✅ |
| 022 | DiffView | 1 | 003, 010 | READY | S | — | ✅ |
| 023 | SecretField | 1 | 003, 010 | FIXTURE | S | — | ✅ |
| 024 | Breadcrumbs and SidebarNav | 1 | 003, 010 | READY | H | B2 | ✅ |
| 025 | FilterBar and SearchField | 1 | 003, 010 | READY | S | B2 | ✅ |
| 026 | HTTP core | 2 | 001 | READY | S | — | ✅ (f02d52d) |
| 027 | Plane clients behind one gateway origin | 2 | 026, 005 | READY | H | — | ✅ (f02d52d) |
| 028 | Typed error codec and retry policy | 2 | 026 | FIXTURE | S | — | ✅ (f02d52d) |
| 029 | QueryClient defaults, fail-closed | 2 | 028 | READY | H | — | ✅ (f02d52d) |
| 030 | Auth adapter, local session, DEV-only mock | 2 | 001 | FIXTURE | S | — | ✅ (f02d52d) |
| 031 | Route guards, 401 handling, idle lock | 2 | 030, 004, 005 | READY | S | — | ✅ (f02d52d) |
| 032 | Permission model, omit-vs-disable | 2 | 001 | READY | S | — | ✅ (f02d52d) |
| 033 | Fixture layer (MSW) and repository interfaces | 2 | 008 | READY | **O** | — | ✅ (f02d52d) |
| 034 | Contract types seam and codegen script | 2 | 001 | FIXTURE | H | — | ✅ (f02d52d) |
| 035 | Streaming download helper | 2 | 026, 016 | FIXTURE | S | — | ✅ (f02d52d) |
| 036 | AppShell | 3 | 024, 017, 021, 030 | READY | S | — | ✅ (c81c0de) |
| 037 | Login screen | 3 | 030, 031, 012, 014 | FIXTURE | S | — | ✅ (1a145f3) |
| 038 | Home | 3 | 036, 019, 013, 033 | FIXTURE | S | — | ✅ (d630a59) |
| 039 | Solutions catalog | 3 | 036, 013, 015, 019 | FIXTURE | S | — | ✅ (f91d7d4) |
| 040 | Load-a-package dialog | 3 | 039, 016, 032 | FIXTURE | H | — | ✅ (1a42988) |
| 041 | Instance inventory grid | 3 | 020, 013, 033 | FIXTURE | **O** | — | ✅ (ac63625) |
| 042 | Inventory URL state, filters, deep-link focus | 3 | 041, 025 | READY | S | — | ✅ (0f154dc) |
| 043 | Bulk restart confirmation | 3 | 041, 016 | BLOCKED | S | — | ◐ UX only (2b33722) |
| 044 | Instance context header and category tabs | 3 | 018, 021, 013 | FIXTURE | H | — | ✅ (37d0b9e) |
| 045 | Declare-instance stub and the 403 page | 3 | 041, 016, 032 | FIXTURE | H | — | ✅ (a2850ef) |
| 106 | Responsive app shell and navigation | 3 | 036, 105 | READY | S | — | ✅ (0adb01a) |
| 107 | Responsive data presentation — table-to-card | 3 | 020, 041, 105 | FIXTURE | **O** | — | ✅ (3bc3917) |
| 111 | Collapsible nav drawer at desktop widths; dark-mode login contrast fix | 3 | 036, 037, 106 | READY | S | — | ✅ (bf6fc75) |
| 112 | Sidebar solution list wired to the catalog | 3 | 036, 039, 033 | READY | S | — | ✅ (41b4a5d, efa80cf) |
| 114 | Load a Solution Package from a file at runtime (dev) | 3 | 040, 113 | READY | **O** | — | ✅ (bdba0e0) |
| 115 | Declare an instance from a form (supersedes 045's stub) | 3 | 041, 045, 016, 114 | FIXTURE | **O** | — | ✅ (884b249) |
| 116 | Fix select-all clearing the selection instead of filling it | 3 | 020, 041 | READY | S | — | ✅ (91c0d18) |
| 117 | Undeclare an instance | 3 | 041, 115, 116 | FIXTURE | **O** | — | ✅ (884b249) |
| 118 | A package declares several versions; an instance is declared against one | 3 | 046, 113, 114, 115 | FIXTURE | **O** | — | ✅ (4fd53c7) |
| 119 | Package Version column, and a grid whose column widths actually apply | 3 | 020, 041, 107, 118 | READY | **O** | — | ✅ (bdeb633) |
| 120 | Instance count derived, never a hardcoded fixture number | 3 | 033, 039, 112, 115, 117 | READY | S | — | ✅ (e0f9b63) |
| 046 | View-model types, `project()`, shared fixture runner | 4 | 008 | FIXTURE | **O** | — | ✅ (78206f0) |
| 047 | Control registry | 4 | 012, 023, 046 | READY | S | — | ✅ (98db387) |
| 048 | FieldRow | 4 | 047 | READY | S | — | ✅ (0826ffc) |
| 049 | SectionCard and advanced disclosure | 4 | 048 | READY | S | — | ✅ (16cf3df) |
| 050 | Section navigation and jump | 4 | 049 | READY | H | — | ✅ (ed489e4) |
| 051 | Field search | 4 | 049 | READY | H | — | ✅ (3071936) |
| 052 | Draft form protocol (React Hook Form) | 4 | 046, 049 | READY | **O** | — | ✅ (4e57caf) |
| 053 | Validation tiers | 4 | 052 | READY | S | — | ✅ (d50ad1b) |
| 054 | ValidationSummary | 4 | 053, 050 | READY | H | — | ✅ (56e7647) |
| 055 | Save flow, save bar, config generation | 4 | 052, 053, 026 | FIXTURE | S | — | ✅ (7fa4a5d) |
| 056 | `409` conflict → refetch and diff | 4 | 055, 022 | FIXTURE | S | — | ✅ (e14dfc4) |
| 057 | Warning override with recorded reason | 4 | 053, 055, 016 | FIXTURE | H | — | ✅ (fdbbd63) |
| 058 | Package-declared actions | 4 | 047, 026 | FIXTURE | H | — | ✅ (d4df62d) |
| 059 | Version states, blocked banner, accept/restore | 4 | 046, 022, 016 | FIXTURE | **O** | — | ✅ (4ec535c) |
| 060 | Rollout card: config stepper and replica table | 4 | 013, 020 | FIXTURE | S | — | ✅ (676272d) |
| 061 | Apply: strategy picker, restart confirm, `APPLY_INDETERMINATE` | 4 | 060, 016, 032 | BLOCKED | **O** | — | ✅ (3275a13) |
| 062 | `CONFIG_DIVERGED` and rollback with a diff preview | 4 | 060, 022, 016 | BLOCKED | S | — | ✅ (712a8fa) |
| 063 | Composer canvas (multi-component solutions) | 4 | 049, 052 | FIXTURE | **O** | — | ✅ (f476980) |
| 108 | Responsive Designer | 4 | 049, 050, 052, 055, 063, 105 | FIXTURE | **O** | — | ✅ (acee7f1) |
| 113 | Solution Packages as the source of the Designer view model | 4 | 033, 046 | READY | **O** | — | ✅ (4dd4518) |
| 121 | One version rule: `packageVersion` reaches every `project()` call | 4 | 046, 052, 063, 118 | READY | S | — | ✅ (c2538ba) |
| 122 | The Designer read carries the package, not a projection (resolves Q19) | 4 | 033, 046, 113, 121 | FIXTURE | **O** | — | ✅ (836ba84) |
| 123 | `DesignerPage` — the screen, and the route that renders it | 4 | 044, 047–059, 121, 122 | FIXTURE | **O** | — | ✅ (0bf9caf) |
| 124 | The composer branch and the rollout/apply half | 4 | 060–063, 122, 123 | BLOCKED | S | — | ✅ (24ee15f) |
| 125 | Designer reflow, accessibility sweep, and the Gate D journey | 4 | 105, 108, 123, 124 | FIXTURE | S | — | ✅ (edefec4) |
| 064 | Health hero and replica cards | 5 | 044, 013, 015 | FIXTURE | S | — | ✅ (8c2257e) |
| 065 | Configuration-state ladder | 5 | 064 | FIXTURE | H | — | ✅ (90ef801) |
| 066 | Log viewer | 5 | 015, 016, 021 | FIXTURE | S | — | ✅ (80c33ba) |
| 067 | Governance states and accessibility pass | 5 | 064–066 | FIXTURE | H | — | ✅ (0bf9d31) |
| 068 | MetricsProvider and capabilities (fail closed) | 6 | 027, 029 | READY | S | — | ✅ (d41a584) |
| 069 | Catalog queries | 6 | 068 | READY | H | — | ✅ (b818c4d) |
| 070 | `buildQuery` and the canonical query hash | 6 | 068 | READY | **O** | — | ✅ (321be0f) |
| 071 | `projectSeries` and its shared fixture suite | 6 | 008 | READY | **O** | — | ✅ (da583e9) |
| 072 | ChartRenderer core | 6 | 003, 071 | READY | **O** | — | ✅ (25bbc04) |
| 073 | Gaps, coverage, thresholds, annotations | 6 | 072 | READY | S | — | ✅ (3588fee) |
| 074 | Tooltip and legend | 6 | 073 | READY | S | — | ✅ (71d8b7c) |
| 075 | Chart accessibility | 6 | 072–074 | READY | **O** | — | ✅ (6bd5cf5) |
| 076 | Single-stat and gauge marks | 6 | 071, 015 | READY | H | — | ✅ (e1991f8) |
| 077 | Explore shell and gated control steps | 6 | 068–076 | READY | H | — | ✅ (6534955) |
| 078 | InstanceSelector with projected series count | 6 | 077, 068 | READY | S | — | ✅ (00e52d3) |
| 079 | MetricSelector, `needs-typing`, type-metric dialog | 6 | 069, 077 | FIXTURE | S | — | ✅ (986b246) |
| 080 | Aggregation and group-by controls | 6 | 077, 079 | READY | S | — | ✅ (7e0bd26) |
| 081 | TimeRangePicker and ResolutionControl | 6 | 068, 032 | READY | S | — | ✅ (7024e06) |
| 082 | Explore result states and typed `422` rendering | 6 | 077–081, 028 | FIXTURE | **O** | — | ✅ (9e700ad) |
| 083 | Explore draft persistence | 6 | 077 | READY | H | — | ✅ (6657d2a) |
| 084 | SaveWidgetDialog, widget config schema and migrations | 6 | 082, 016 | FIXTURE | S | — | ✅ (e01a5a9) |
| 085 | DashboardView and toolbar | 6 | 084, 081 | FIXTURE | S | — | ✅ (a260497) |
| 086 | DashboardGrid and WidgetFrame | 6 | 085, 006, 072 | FIXTURE | **O** | — | ✅ (8710106) |
| 087 | Layers: badge, filter, hide-not-delete, reset | 6 | 086 | FIXTURE | S | — | ✅ (6cf3664) |
| 088 | CSV export | 6 | 035, 085 | READY | H | — | ✅ (e6311df) |
| 089 | Monitoring first-run | 6 | 085, 019 | FIXTURE | H | — | ✅ (e09fab7) |
| 109 | Responsive Monitoring — Explore and dashboard grid | 6 | 077, 081, 085, 086, 105 | FIXTURE | S | — | ✅ (348cdfa) |
| 090 | Admin shell, tabs, and the honesty banner | 7 | 018, 032 | FIXTURE | H | — | ✅ (e8c8748) |
| 091 | Roles and RoleEditor | 7 | 090, 020 | FIXTURE | S | — | ✅ |
| 092 | Assignments and ScopeTree | 7 | 090 | FIXTURE | S | — | ✅ |
| 093 | Access check (effective permissions) | 7 | 092 | FIXTURE | H | — | ✅ |
| 094 | Users (local) | 7 | 090, 020 | DECISION | S | — | ✅ (`FE-D38`) |
| 095 | Targets preview | 7 | 090 | BLOCKED | S | — | ✅ read-only (no wire half — never writes) |
| 096 | Code splitting and a bundle budget | 8 | 072, all features | READY | S | — | ✅ |
| 097 | Palette and contrast validation in CI | 8 | 002, 003 | READY | S | — | ✅ |
| 098 | Automated accessibility sweep | 8 | all screens | READY | S | — | ✅ (43ab5df) |
| 099 | End-to-end journeys | 8 | all features | FIXTURE | **O** | — | ✅ |
| 100 | CSP and air-gap verification | 8 | 096 | READY | S | — | ✅ |
| 101 | Production build assertions | 8 | 005, 010, 030 | READY | S | — | ☐ |
| 102 | Observability and secret-leak prevention | 8 | 026, 006 | READY | S | — | ☐ |
| 103 | Performance verification | 8 | 086, 041, 049 | READY | S | — | ☐ |
| 110 | Reflow conformance in CI | 8 | 105–109, 098 | READY | S | — | ✅ (9c6dfcf) |
| 104 | Documentation and plan closure | 8 | all | READY | H | — | ☐ |

**Tally — verified against the register by extraction, not transcription (re-run 2026-08-28):** 125 tasks · 74 `READY` · 45 `FIXTURE` · 1 `DECISION` · 5 `BLOCKED` · **119 schedulable (95%) after the Gate lifts**. Model split: 27 **O**, 69 **S**, 29 **H**.

**Done: 85 of 125** — phases 0–5 complete (`001`–`067`, `105`–`125`); phases 6–8 not started; phase 7 not scheduled. `043`, `061`, `062`, `095` and `124` ship **UX only** against `DECISIONS.md` open item 1. The as-built map — what each task actually produced, and where the code and the design now disagree — is [`design.md` §28](../../design/frontend/design.md#28-as-built-map-2026-09-03).

---

## 8. The per-task loop

Eight steps. The controller runs all eight for every task and **does not pause between tasks** — the skill is explicit that "should I continue?" prompts waste the developer's time.

```bash
# 0 · setup, once per session
SD=~/.claude/plugins/cache/claude-plugins-official/superpowers/6.3.0/skills/subagent-driven-development
P=docs/plans/frontend/implementation.md
WS=$("$SD/scripts/sdd-workspace" "$P")

# 1 · record BASE before dispatching — never HEAD~1, which drops all but the
#     last commit of a multi-commit task
BASE=$(git rev-parse HEAD)

# 2 · extract the brief (adapter, §3.2)
docs/plans/frontend/bin/fe-task-brief "$P" 046      # -> $WS/task-046-brief.md

# 3 · dispatch the implementer (§8.1 template, model from §7's register)
# 4 · handle the report: DONE | DONE_WITH_CONCERNS | NEEDS_CONTEXT | BLOCKED

# 5 · package the diff
"$SD/scripts/review-package" "$P" "$BASE" HEAD       # -> $WS/review-<b7>..<h7>.diff

# 6 · dispatch the task reviewer (§8.2) — brief + report + package + §10 lens
# 7 · fix loop if needed (§11), max 5 rounds
# 8 · ledger the completion, mark the todo, next task
```

**Never** dispatch a reviewer without a diff file. **Never** fix findings in the controller session — controller fixes pollute the coordination context and skip review.

### 8.1 Implementer dispatch — the shape for this plan

Uses the skill's `implementer-prompt.md` with the five slots filled for this project. The brief is the **single source of requirements** — exact values never get paraphrased into the prompt.

```
description: "Implement FE-046: view-model types, project(), fixture runner"
model: opus                                   # from §7's register — NEVER omit
prompt: |
  You are implementing FE-046 of the Solution Manager console frontend.

  ## 1 · Where this fits
  The console renders Designer, Governance and Monitoring for any solution
  from a Solution Package, with zero per-solution React code. You are building
  the pure view-model engine every Designer screen consumes.

  ## 2 · Your requirements
  Read this first — it is your requirements, with the exact values to use
  verbatim: <WS>/task-046-brief.md
  Do not read the full plan file.

  ## 3 · Interfaces and decisions from earlier tasks
  <only what the brief cannot know: signatures already merged, names chosen,
   the ledger pointer for any finding parked in this area>

  ## 4 · Constraints that bind you (from the plan's Global Constraints)
  <the G-rules that apply to THIS task, copied verbatim — see §10>

  ## 5 · Ambiguity already resolved for you
  <the controller's rulings — e.g. F8: RHF is the only draft store;
   project() receives draft as an argument and stays pure>

  ## Report
  Write your full report to <WS>/task-046-report.md.
  Return only: Status, commits, one-line test summary, concerns, report path.
```

**Four rules the controller must not break:**
1. **Never paste accumulated history.** A dispatch describes one task. A real session's dispatch hit 42k characters of which 99% was pasted prior-task summaries.
2. **Never omit `model`.** An omitted model silently inherits the session's most expensive one, defeating §9 entirely.
3. **Never let an implementer read `implementation.md`.** It is 3,081 lines; the brief is 6–26.
4. **The implementer dispatches no subagents** — and above all no reviewer. Review arrives from the controller. A worker-spawned reviewer is a duplicate seat whose approval counts for nothing.

### 8.2 Task reviewer dispatch

Uses the skill's `task-reviewer-prompt.md`. Three paths — brief, report, review package — plus §10's constraints block. Both verdicts are required: **spec compliance AND task quality**. A report missing either is not accepted.

**The controller does not pre-judge.** No "don't flag X", no "at most Minor", no "the plan chose this". If a finding looks like a false positive, let it be raised and adjudicate it in the loop. Finding **F10** is the live risk here: `061`'s no-success-variant is plan-mandated and correct, and the way to protect it is to hand the reviewer `G11` verbatim as its lens — not to tell it what not to say.

---

## 9. Model tiering and batching

### 9.1 Tiering

Per the skill: *"Turn count beats token price"* — the cheapest model routinely takes 2–3× the turns on multi-step work and costs more overall. Mid-tier is the **floor** for implementers working from prose descriptions, which is what most of these 110 tasks are.

| Tier | Count | When | Examples |
|---|---|---|---|
| **O** most capable | 19 | Pure engines, cross-language fixture contracts, subtle correctness, whole-branch review, reflow IA | `046` `070` `071` `009` `020` `033` `041` `052` `061` `072` `075` `082` `086` `099` `105` `107` `108` |
| **S** standard | 62 | Multi-file integration, feature screens, hooks | most of Phases 3–8 |
| **H** cheapest | 29 | Single-file transcription where §6.0.7 or §B.1 gives the exact content | `002` (≈60 token values from §B.1) `015` `017` `019` `104` |

Reviewers are tiered to the **diff**, not the task: a small mechanical diff takes a mid tier; `046`'s engine diff takes the most capable. Fix-loop rounds 4–5 go one tier **above** the implementer that got stuck.

### 9.2 Batching

*"Batch small same-shape work… reserve one-dispatch-per-task for work that needs its own judgment, its own tests, or its own review surface."*

| Batch | Tasks | Why they batch |
|---|---|---|
| **B0** | `005` `006` `010` | Three small independent modules, all available once `004` lands; same test shape |
| **B1** | `014` `015` `017` `019` | Four presentational MUI wrappers, no state logic, identical test shape (render + keyboard + axe in both themes) |
| **B2** | `011` `018` `024` `025` | Four simple interactive wrappers with props already given in §6.0.7 |

**Deliberately not batched**, though they look similar: `012` (aria wiring done once so no field author can get it wrong), `013` (26 vocabulary entries — the single source of every status string), `016` (focus trap and restore), `020` (Community-tier constraint), `021`, `022` (a secret must be unable to reach the DOM), `023` (write-only, no reveal). Each carries a rule whose violation is invisible in a batch diff.

**11 dispatches saved; 7 correctness rules keep their own review surface.**

---

## 10. The reviewer's constraints block

Handed verbatim to every task reviewer as its attention lens, filtered to the G-rules the task actually touches. This is copied from the implementation plan's Global Constraints — **exact values, not paraphrase**.

> `G1` **React + Vite + Material-UI.** No second component library, no second styling runtime, no second charting stack.
> `G2` TypeScript `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. **No `any` in app code — including tests.**
> `G3` MUI X Data Grid **Community** only. `-pro`, `-premium`, `@mui/x-charts-pro` may not enter the lockfile.
> `G4` `echarts` may be imported by exactly one module: `features/monitoring/chart/ChartRenderer.tsx`.
> `G5` One same-origin gateway. No `VITE_*` variable may name a plane host.
> `G6` Tenant is display-only — never a path segment, never a request parameter.
> `G7` The browser never receives a resolved secret — not in a form, diff, export, history, log, or `yaml` blob.
> `G8` No raw colour outside `src/shared/theme`.
> `G9` Timestamps UTC on the wire; **every time axis labels its zone**.
> `G10` **Absence is never zero.** A missing bucket produces no point; an unknown count renders `—`. Never a zero line, never connect-across.
> `G11` **`restart-observed` is never presented as convergence, and an apply outcome is never derived from an HTTP response.**
> `G12` Status is text + icon + colour. Colour is never the only channel, in either theme.
> `G13` Unauthorized ⇒ **omit**. Capability-limited ⇒ **disable with a stated reason**. Hiding a control is **not** a security boundary.
> `G14` Never retry a `422`; never retry a mutation; never apply an optimistic update to apply or restart.
> `G15` No alerting affordance. Nothing may imply anyone is notified or paged.
> `G16` **Responsive: desktop, tablet and mobile.** Content reflows to **320 CSS px** with no two-dimensional scrolling (WCAG 2.2 AA SC 1.4.10). Breakpoints `xs 320 · sm 600 · md 905 · lg 1240 · xl 1560`. Data tables and persistent toolbars scroll on one axis **inside their own container**, never the page body. The reference `min-width: 1280px` must not ship.
> `G17` **WCAG 2.2 Level AA** for chrome, forms and navigation; charts additionally require a non-colour channel and a tabular alternative.
> `G18` No MI development may be required. Every degradation states the package's own reason.
> `G19` **Air-gap clean.** No analytics, no beacons, no CDN, no third-party fonts. Self-host everything.
> `G20` No production build may contain the auth mock, dev gallery, MSW, a fixture dataset, a secret, or a plane hostname.

**All 20 global constraints are represented.** `G16` is `FE-D29` (supersedes `FE-D26`). A reviewer who has not been handed it will copy the prototype's `min-width: 1280px` into production — that is a defect, not a restoration of the mock. `G17` and `G16` must both hold; a ≥1280 floor cannot satisfy SC 1.4.10.

**Six rules are enforced by the type system, and the reviewer should verify the types rather than the usage:** a secret cannot reach `DiffView` (`DiffRow.old/new` admit only `SECRET_MASK` on a secret row); an ungrantable verb cannot gate a feature (`requireGrantable`); an apply cannot report success (`ApplyOutcome` has no such variant); the control registry is exhaustive over `ControlKind`; `IconButton` requires `aria-label`; `contracts/` imports nothing from `src/`.

---

## 11. Fix loop and rulings

A fix round is **one fix dispatch plus one scoped re-review**. Five rounds maximum. Rounds 1–3 resume the original implementer (its context is intact); rounds 4–5 dispatch a **fresh** implementer one tier up, told plainly that prior attempts failed and to read the report file for what was tried.

Two routes leave the loop immediately:
- **Minor findings never enter it.** They are ledgered as `Task <N>: minor (deferred): <one-liner>` and the final whole-branch review is pointed at that list. A roll-up nobody reads is a silent discard.
- **A plan-mandated finding is the controller's to rule on** — weighed against the plan text, with the **spec as binding authority**, and ledgered before acting. Not dismissed because the plan mandates it; not fixed against the plan without a recorded ruling.

**Adjudicate only at the cap.** Adjudicating early to end a loop is pre-judging under another name. At round 5, each open finding is parked with a ruling, or — if load-bearing — ruled on with the smallest change that unblocks dependent work and carried into the next dispatch.

**Every `Ruling:` line in the ledger is collected and surfaced to the developer at session end**, in the order made, each with what it costs if wrong. That list is the only place decisions taken on their behalf reach them.

---

## 12. Verification — the exact commands

From the implementation plan §6.0.8. Every task's verification is one of these; **no task is marked done on an assertion.**

| Task class | Tasks | Command |
|---|---|---|
| Standard component/unit | most of `011`–`095` | `npm run lint && npm run typecheck && npm test -- <path> && npm run test:a11y -- <path>` |
| Pure engine + shared fixtures | `046` `070` `071` | `npm run test:contracts` — **must be byte-identical to the Java runner's input** |
| Route / integration | `004` `031` `042` `045` | `npm test -- <path> --environment jsdom` with a memory router |
| End-to-end | `099`, each gate | `npm run e2e -- --grep "<journey name>"` |
| Guardrail lint fixtures | `009` | `npm run lint -- src/test/lint-fixtures` — **must exit non-zero**; a passing run is the failure |
| Bundle / build assertions | `096` `100` `101` | `npm run build && npm run verify:bundle` |
| Palette / contrast | `097` | `npm run verify:palette` — fails below 3:1 (chart) or 4.5:1 (text) in **either** theme |
| Full a11y sweep | `098` | `npm run test:a11y -- --all` + the manual keyboard script |
| Performance | `103` | `npm run test:perf` — request dedup, visibility gating, render budgets |
| Reflow / responsive | `105` `110` | `npm test -- src/test/reflow.ts` (105); `npm run verify:reflow` (110) |

**Every task also runs the phase gate before it can be marked done:**
```bash
npm run verify   # = lint && typecheck && test && test:contracts && build
```

### 12.1 Phase gates

| Gate | Proves | Command / evidence |
|---|---|---|
| **A** | Foundation green | `npm run verify && npm run e2e` **and** `npm run lint -- src/test/lint-fixtures` exits non-zero |
| **B** | The seam is real | A Phase 3 screen compiles against a repository interface with the fixture provider unbound; **no component imports `msw`** |
| **C** | Five states everywhere | Every Phase 3 screen renders loading/empty/populated/partial/error from fixtures; no demo-state control in the build |
| **D** | Walking skeleton **UX contract** (fixtures) | `npm run e2e -- --grep "walking skeleton"` — save carries a generation, inactive branch absent from payload, apply renders `APPLY_INDETERMINATE`. **Does not close open item 1** |

Gate D is the acceptance the design names by hand (§19). It passes only if the apply result is **neither** success **nor** failure.

---

## 13. The ledger

`$WS/progress.md`, first line `# SDD ledger — plan: docs/plans/frontend/implementation.md`.

Conversation memory does not survive compaction; the ledger does. **After a compaction, trust the ledger and `git log` over recollection.**

| Line | When |
|---|---|
| `Task <N>: complete (commits <b7>..<h7>, review clean)` | Review clean |
| `Task <N>: complete (commits <b7>..<h7>, <K> parked)` | Breaker tripped at round 5 |
| `Task <N>: fix round <R>/5 (<X> addressed, <Y> open — <one-liners>; commits <a7>..<b7>)` | Each fix round |
| `Task <N>: minor (deferred): <one-liner>` | Minor finding, never enters the loop |
| `Task <N>: parked — <finding> — Ruling: <why the code stands>` | Adjudicated at cap |
| `Task <N>: Ruling: <finding> — <decided and why>` | Any controller ruling |
| `Phase <N>: gate <X> passed — <evidence>` | Phase close |

A task with a `complete` line is **done — do not re-dispatch it.** A task whose last line is a fix round is mid-loop: resume at the next round.

**§4's thirteen preflight rulings are written to the ledger before task `001` is dispatched.**

---

## 14. Phase handoff and recovery

Because each session gets a fresh controller, the handoff is the failure surface. It is deliberately thin.

**Closing a phase**
1. Confirm every task in the phase has a `complete` line.
2. Run the gate; append `Phase <N>: gate <X> passed — <evidence>`.
3. Update §7's register: `☐` → `✅`, with the commit range. **This is the committed record; the ledger is scratch.**
4. Collect every `Ruling:` line into the session's closing message, in order, each with what it costs if wrong.
5. Do **not** delete the workspace. It is deleted once, after the final whole-branch review of the last session.

**Opening a phase** — read this plan §§1–4 and §7, read `$WS/progress.md`, `git log --oneline` since the previous phase's base, then resume at the first task with no `complete` line. Read nothing else; the briefs carry the rest.

**Recovery after compaction or `git clean -fdx`** — the ledger is git-ignored and may be gone. Rebuild from: §7's register (committed, carries `✅` and commit ranges) + `git log` + the last phase-close message. Re-dispatching a completed task sequence is the most expensive failure this protocol has observed; the committed register exists precisely so it cannot happen silently.

---

## 15. Seeding the GitHub Project

`CLAUDE.md` is explicit: *"Repo owns decisions and requirements. The GitHub Project owns status and assignment."* **`gh` is not installed here**, so §7's register carries status in the interim — deliberately shaped to migrate without re-keying.

When `gh` is available against `parikshit-coe/solution-manager`:

```bash
# one issue per task, seeded from the register; the register's columns
# map 1:1 to Project fields: Phase -> Iteration, Model -> Size, Ready -> Status
gh project create --owner parikshit-coe --title "Solution Manager"
```

**Link direction: issue → decision ID, never decision → issue number** (`CLAUDE.md`). Once the Project is live, §7's Status column becomes a mirror and the Project is authoritative — this plan and the register stay authoritative for *what* and *in what order*.

⚠️ **Anything decided in an issue thread must land in `DECISIONS.md` before the issue closes.** GitHub is good at status and poor at rationale.

---

## 16. Definition of done

### 16.1 Per task
The implementation plan's §17.2 global DoD applies in full — every task, no exceptions. The three that get skipped most often, restated:

- [ ] **Tests passing — output seen, not inferred** ([`definition-of-done.md`](../../checklists/definition-of-done.md): *"Evidence before assertions"*)
- [ ] **Error state distinct from empty state**, and tested separately (`FE-D10`)
- [ ] **Every invented shape flagged in source** with `// INVENTED —` or `// CONTRACT: TBD —`, so a grep enumerates the debt
- [ ] **Corresponding GitHub issue closed** ([`task-list.md`](task-list.md)'s Issue column) — only after the checks above pass, never on assertion alone. If work surfaced a decision, it lands in `DECISIONS.md` first — an issue thread is not durable (`CLAUDE.md`, `I3`)

### 16.2 Per phase
- [ ] Every task has a ledger `complete` line
- [ ] The gate passed with evidence recorded
- [ ] §7's register updated and committed
- [ ] Rulings surfaced to the developer

### 16.3 Whole branch
- [ ] Final whole-branch review on the **most capable model**, via `superpowers:requesting-code-review`, pointed at the ledger's deferred-minor and parked lines so it can triage what must be fixed before merge
- [ ] Findings fixed in **ONE** dispatch, then **exactly one** scoped re-review. There is no second fix wave
- [ ] `FE-D25`–`FE-D29` already present in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md) as `PROPOSED`; no in-place edit of meaning (`I3`); none treated as ratified (`I4`)
- [ ] The implementation plan's Review section filled in
- [ ] Workspace deleted — git history is the record
- [ ] `superpowers:finishing-a-development-branch`

---

## 17. What must not happen

| ✗ | Why |
|---|---|
| Dispatching two implementers in parallel | Protocol's hard rule — file conflicts |
| Letting an implementer read `implementation.md` | 3,081 lines vs a 6–26 line brief |
| Omitting `model` from a dispatch | Silently inherits the session's most expensive model |
| Fixing findings in the controller session | Pollutes coordination context, skips review |
| Reviewing without a diff file | The reviewer re-derives the diff and burns context |
| Using `HEAD~1` as review BASE | Silently drops all but the last commit of a multi-commit task |
| Telling a reviewer what not to flag | Pre-judging. Let it raise the finding; adjudicate in the loop |
| Scheduling `094`, or the wire calls of `043`/`061`/`062`/`095` | `DECISION` / `BLOCKED` — §1 |
| Promoting a fixture to a contract | `FE-D7`: hand types are **replaced wholesale**, never extended |
| Wiring a guessed endpoint into a component | The seam exists so rework is one adapter, not one screen |
| Deleting the workspace before the final review | It is the recovery map |

---

## Review

*(Filled in after execution: what changed versus this plan, and why. Anything decided during execution lands in [`../../design/DECISIONS.md`](../../design/DECISIONS.md) as a new superseding decision before the issue closes — `I3`.)*
