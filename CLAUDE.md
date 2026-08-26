# Solution Manager

A console — "AWS/Azure Console for Solace's custom solutions" — to onboard, configure, deploy, govern and monitor multiple Solace solutions through one platform. Each solution exposes three categories: **Designer** (configure), **Governance** (health), **Monitoring** (common + native metrics).

**Status: pre-implementation, UNBASELINED.** No application code yet, and the design is **not a ratifiable baseline** — an independent review found seven blocking defects. Read [`docs/tasks/codex-review-triage.md`](docs/tasks/codex-review-triage.md) before building anything on it.

**This is a monorepo**, to be deconstructed into per-component repos later. Contracts are in-tree; module boundaries are **to be** enforced at build time (ArchUnit) — **not implemented; there is no code and no CI**.

## Where the design lives

Two cross-cutting documents under `design/`, plus one folder per component. Read them rather than relying on this file — `CLAUDE.md` is an index, not a summary.

| File | Contains |
|---|---|
| **`docs/design/DECISIONS.md`** | The decision register — every decision with a stable ID, status, rationale and supersession history. Plus deferred items, the hardening backlog, and all open items. |
| **`docs/design/architecture.md`** | The overall architecture — planes, Target model, domain model, config ownership, instance authority, storage, restart, telemetry, topology matrix, capability limits, v1 slice, sub-spec scope. |

| **`docs/design/<component>/`** | Per-component `design.md` + `decisions.md` (`BE-D*`/`FE-D*`/`AG-D*`/`RT-D*`). |
| **`docs/tasks/codex-review-triage.md`** | The outstanding defects from independent review, with verification references. |

*(Consolidated 2026-08-10 from three predecessor specs, since removed; their content is absorbed into the files above.)*

## Repo layout

One tree. All documentation under `docs/`; each component's code gets a top-level folder.

| Path | Contains |
|---|---|
| `docs/requirements/` | Every incoming requirement, with an ID and a **trace** to the decision that satisfies it. Start at `docs/requirements/README.md`. |
| `design/` | Architecture + decision register + `common/` (contracts, canonical conventions, cross-cutting lessons) + one folder per component holding its `design.md` and `decisions.md`. |
| `docs/plans/` | **Approved** implementation plans, one folder per component. Authoritative; the GitHub Project tracks execution against them. |
| `backend/` `frontend/` `go-agent/` `runtime/` | **Code**, one folder per component. Each has a README pointing at its design. No code yet. |
| `contracts/` | Machine-readable contracts (`.proto`, OpenAPI, JSON Schema) — **not created yet**; their design lives in `docs/design/common/contracts/`. |
| `docs/runbooks/` | Operational procedures, for operators running the system. |
| `docs/tasks/` | `lessons.md` (repo-local lessons) · `codex-review-triage.md` (review history). **Work in flight lives in the GitHub Project, not here.** |
| `docs/checklists/` | Development-time gates: spec review, plan review, definition of done, new-module setup, external-review prompt. |

Everything lives in this one tree: design, plans and code move in a single reviewed PR. **Contracts are in-tree but still single-writer** — a contract change is a deliberate reviewed change, never a convenience edit, because it is consumed by four modules.

The two mandated blocks at the bottom of this file are **authoritative and live here** — one copy, no sync machinery. Their rationale and changelog are in `docs/design/common/conventions/`. When the monorepo is split, each new repo copies the blocks from here and a drift check becomes worth building; while there is one tree it is not.

⚠️ **"Runtime" means two different things.** In the architecture, *runtime* = where the **MIs** execute (VM · Docker · Kubernetes), which SM does not own. `design/runtime/` and `runtime/` mean **SM's own container packaging** — Dockerfiles, image layering, compose for local dev. Say "MI runtime" or "SM packaging" rather than bare "runtime".

### Two rules when working with these docs

1. **⚠️ `SM-D*` decisions are PROPOSED, not ratified.** The architect group has confirmed only that SM is a config/governance plane rather than a deployment plane, and that `dp-remote` may hold host credentials. Everything else in that family awaits sign-off. Where a `SM-D*` decision conflicts with a `LOCKED` one, **the locked one stands — flag the conflict, don't silently resolve it.** `DECISIONS.md` marks each conflict.
2. **Decision IDs are immutable and deliberately not renumbered.** Three families coexist — `D1`–`D17` (parent architecture), `B/D1`–`B/D17` (deploy/agent/telemetry loop), `SM-D1`–`SM-D28` (on-prem-first revision). A citation like "per D8" must never shift meaning. **To change a decision, add a new one that supersedes it** — never edit a decision's meaning in place. Note `D17` (first vertical slice) and `B/D17` (editions) are different decisions.

## Where work is tracked

**Repo owns decisions and requirements. The GitHub Project owns status and assignment.** Four places recording "what
is unresolved" is how the design accumulated 47 review findings; this split is deliberate.

| Artifact | Home |
|---|---|
| Requirements (`REQ-*`), decisions, rationale, architecture, lessons | **this repo** — versioned, reviewed, traceable |
| Status, assignment, sequencing, who-is-doing-what | **GitHub Project "Solution Manager"** |
| Open items | **both**: `DECISIONS.md` states *what is unresolved and why it blocks*; an Issue tracks *the work* |

**Link direction: issue → decision ID, never decision → issue number.** Issue numbers embedded in prose is the exact
brittle cross-reference that broke in six places when the open items were renumbered.

⚠️ **Anything decided in an issue thread must land in `DECISIONS.md` before the issue closes.** GitHub is good at
status and poor at rationale; a closed thread is not a durable record.

## Hard constraints

The full set is in the **Project Invariants** block at the bottom of this file (`I1`–`I7`), synced from
`docs/design/common/conventions/project-invariants.md`. The two that bite hardest when writing design docs:

- **`I1` — no MI development.** Deployment configuration is fair game; code and build changes are not. **Audit
  every proposal against it before proposing it.** Formalized as SM-D16.
- **`I2` — verify external facts before writing them down.** This project has been burned three times by
  confident recall. WebFetch works; web search does not.

## How the mandated blocks apply in this repo

Both mandated blocks are carried below. A few items map differently in a repo that is currently documentation-only:

| Block item | Here |
|---|---|
| `docs/tasks/todo.md` | **Removed.** Status and assignment live in the GitHub Project; a single tracked file is a merge-conflict magnet across a team and a second place to check. An *implementation plan* is a different artifact and lives at `docs/plans/<component>/`. |
| `docs/tasks/lessons.md` | **Present, same as every repo** — lessons about working *in this repo*. **Cross-cutting** lessons go to `docs/design/common/lessons.md`, which every repo reads. When in doubt, choose cross-cutting. |
| "Run tests, check logs" | Verification here means: **all markdown links resolve**, decision IDs are complete and correctly prefixed, and the spec passed `docs/checklists/spec-review.md` — *including* its independent adversarial pass. |
| "Autonomous bug fixing", "diff behavior vs main" | No runtime code in this repo; these apply in the code repos. |
| `I5`–`I7` (SQL binding, secrets, plane boundary) | Code-level invariants — they constrain what may be *designed* here, and are enforced in the code repos. |

## Architecture in one paragraph

*(This is the **proposed** on-prem-first shape. See `docs/design/architecture.md` for the caveats.)*

Control plane / data plane. A **thin CP** governs — catalog, schema registry, entitlement, RBAC, config authoring UI — and holds **no copy of config and no business data**. A **DP** owns configuration-of-record plus all telemetry, and is either the actor itself (`dp-remote`: reaches into hosts via SSH / Docker API / K8s API, holding scoped credentials) or the home that **outbound-only Go agents** (`host-local`) report to. On-prem the two planes are co-located in one deployment behind one dashboard, separated by schema, backend module and UI client so the SaaS split is a boundary change rather than a rewrite. **Customer CI/CD deploys artifacts; SM owns only configuration**, applied by writing an external config file and restarting. Telemetry acquisition is a per-binding policy normalizing into one transport-agnostic model, stored in **plain PostgreSQL** with monthly partitions and type-aware rollups. Control plane is **Java 17 / Spring Boot** (proposed, reversing the earlier Node/TS decision); frontend React + Vite; agent Go.

## Reference codebases (sibling directories)

- `/Users/parikshit/Work/Development/solace-cache-engine` — **Cache**. Java 17 / Spring Boot 3.3.5. Config via `application.yml` + `CacheEngineProperties`. PULL telemetry: `/actuator/health`, `/api/cache/stats`, `/api/cache/size`. Docker image + external config mount. **v1 integration target.**
- `/Users/parikshit/Work/Development/pubsubplus-connector-file-common` — File Mesh MI **shared framework** (commons lib). Config via typed event models; PUSH telemetry via command-center events over Solace. DB MI is expected to reuse it.
- `/Users/parikshit/Work/Development/file-mesh-ui` — the **existing File Mesh Manager**, frontend *and* backend. React 17 + CRA + TS + Redux-Saga + MUI; Node/Express/TS + PostgreSQL 13 + Docker Compose; ~30 tables; four Solace listener services. **Audited** — findings drive the hardening backlog in `DECISIONS.md`. Screenshots also on `~/Desktop`. Note: it is **no longer the codebase we generalize-and-harden** if SM-D18 (greenfield Java) is ratified — but its UI components and listener parsing remain harvest targets for phase 2.

## Conventions / workflow

- This project uses the **superpowers** workflow: brainstorm → spec (`docs/`) → implementation plan → execute. Use subagents for broad exploration to keep context clean.
- **The user asks for independent adversarial review of specs** before treating them as done, and it has caught real errors. Self-review catches contradictions; it does not catch wrong facts.
- **Verify external technical facts with WebFetch before writing them into a design doc.** **Web search is disabled** in this environment (LiteLLM/Vertex policy), but **WebFetch of specific URLs works**. Framework defaults, licence terms and database semantics have all been asserted wrongly from memory on this project — don't repeat that.
- **Don't offer bare labelled options.** When presenting choices, state what does *not* change across them, show the concrete delta per option, anchor against the status quo, and run the same real scenarios through each.
- Don't use the Agent tool unless the user asks for it.
- **Stakeholder deck:** `~/Desktop/Solution_Manager_platform_transition.pptx` (16 slides; slides 4 and 13 show *target* architecture, slide 16 carries v1 scope). Predates the on-prem-first revision — treat as stale where it conflicts.

## Highest-priority open items

Full list in `DECISIONS.md`. The ones that block:

1. **Ratification of `SM-D1`…`SM-D28`**, with `docs/design/architecture.md` §13's capability limits explicitly accepted — in particular that the outbound-only / no-stored-credentials guarantee would hold only for `host-local`.
2. **Compute the storage sizing model** — SM-D8 should not be ratified before it exists.
3. **Specify the `dp-remote` apply protocol** — the agent protocol's safety properties (generation ordering, idempotency, lease, crash-safe resume) have no `dp-remote` equivalent.
4. **GitOps prerequisite** — confirm customers will exclude an SM-owned ConfigMap from Argo/Flux scope, or `additional-location` on Kubernetes is unusable.
5. **Frontend stack conflict** — `D7` locks Material-UI; the (deleted) design handoff specified Tailwind + Radix on the Solace Agent Mesh Design System. **No in-repo visual reference remains** — re-obtain from the designer or Figma.

---



<!-- claude-workflow: authoritative text. Rationale/changelog: docs/design/common/conventions/claude-workflow.md -->

## Workflow Orchestration

### 1. Plan Mode Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately – don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Use the Superpowers Skills — don't improvise the workflow

| Stage | Skill | Model |
|---|---|---|
| **Designing a system** — any creative or architectural work | `superpowers:brainstorming` | strongest available |
| **Writing the plan** | `superpowers:writing-plans` | strongest available |
| **Executing the plan** | `superpowers:executing-plans` | **a lower-cost model** |

Design and planning are where judgement matters and where a mistake is expensive. Executing against an
already-written plan is mechanical by comparison — use a cheaper, faster model for it.

### 3. Implementation Execution Strategy

**Before executing any plan, ASK the developer which session mode to use. Never assume, and never start executing
without asking.** State which option you recommend and why; the decision is the developer's.

- **Option 1 (Same Session):** single focused tasks only (<5 steps)
  - Context accumulates exponentially with subagents
  - Much more expensive for multi-task work

- **Option 2 (Parallel Session):** multi-task implementations
  - Open NEW session with `superpowers:executing-plans` skill
  - Fresh context prevents exponential cache costs
  - Estimated ~70% cheaper for large implementations

### 4. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One task per subagent for focused execution

### 5. Self-Improvement Loop
- After ANY correction from the user: capture the pattern as a lesson
  - **Module-specific** → `docs/tasks/lessons.md`  (lessons stay in the repo — an issue thread is not durable)
  - **Cross-cutting** (affects more than one module, or is architectural/contract-level) →
    `docs/design/common/lessons.md`. **When in doubt, choose cross-cutting**
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 6. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 7. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes – don't over-engineer
- Challenge your own work before presenting it

### 8. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests – then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management

**The implementation plan is ALWAYS authoritative.** The working checklist is derived from it; when the two
disagree the plan wins — update the plan deliberately rather than letting the checklist drift.

1. **Plan First**: Write the plan to `docs/plans/<component>/NNN-<slug>.md` via `superpowers:writing-plans`, with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: In the **GitHub Project** ("Solution Manager"), against the issue for this work — not in a file. Repo owns decisions and requirements; the Project owns status and assignment
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add a review section to the plan
6. **Capture Lessons**: Update the appropriate `lessons.md` after corrections (see §5)

## Core Principles

- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

---

<!-- project-invariants: authoritative text. Rationale/changelog: docs/design/common/conventions/project-invariants.md -->

## Project Invariants (Solution Manager)

### I1 — No MI development (hard constraint, not negotiable)
Solution Manager may never require new **development** on the managed integrations. MI development depends on the
**MDK (Micro Development Kit)** owned by another Solace team, whose roadmap SM does not control.

- **Fair game** — deployment configuration: launch specs, env vars, mounts, and the contents of a config file SM
  writes (including Spring `management.*` / `info.*` properties and Micrometer tags).
- **Not fair game** — code changes, build-plugin changes (e.g. adding Spring Boot's `build-info` goal), new
  endpoints, new telemetry emission.

Anything SM needs from an MI must be satisfiable by something SM controls, **or** be optional with a defined
degradation. Audit every proposal against this before writing code.

### I2 — Verify external facts before writing them down
Do **not** state framework defaults, licence terms, or database semantics from memory. Web search is disabled in
this environment; **WebFetch of specific URLs works** — use it. This project has been burned three times by
confident recall: Spring Boot actuator defaults, TimescaleDB licensing, and PostgreSQL `DETACH PARTITION` locking.
Self-review catches contradictions; it does not catch wrong facts.

### I3 — Decision-ID discipline
Decisions live in `docs/design/DECISIONS.md`. IDs are **immutable** and were deliberately not
renumbered. Four families coexist: `D*` (parent architecture), `B/D*` (deploy/agent/telemetry),
`SM-D*` (on-prem-first revision), and per-component `BE-D*` / `FE-D*` / `AG-D*` / `RT-D*`.

- **To change a decision, add a new one that supersedes it** — never edit a decision's meaning in place.
- **Check the prefix.** `D17` (first vertical slice) and `B/D17` (editions) are different decisions.

### I4 — `SM-D*` decisions are PROPOSED, not ratified
The architect group has confirmed only that SM is a config/governance plane rather than a deployment plane, and
that `dp-remote` may hold host credentials. **Do not implement against an unratified decision as if settled.**
Where a `SM-D*` conflicts with a `LOCKED` decision, the locked one stands — **flag the conflict, don't resolve it
silently.**

### I5 — Server-side tenant isolation
Tenant scope is never derived from client-supplied input. Every query goes through the central data-access module
that applies tenant/authz scope. All SQL uses **bind parameters** — no string interpolation, ever. This is a
direct response to audit findings on the predecessor codebase.

### I6 — Secrets never land in config storage
Config values marked `secretRef` resolve only at render time from the secret backend. Secret **values** must never
be persisted in config tables, version history, field-level diffs, exports, archives, or logs. Config hashes are
computed **over references, not resolved values**.

### I7 — Cross-plane boundary
Control-plane code must not import data-plane code or its data-access module, and vice versa. No cross-schema
joins and no cross-schema foreign keys between `sm_control` and `sm_data`. **To be enforced in CI — not implemented yet** — if your change needs
to cross that line, the design is wrong, not the rule.
