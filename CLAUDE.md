# Solution Manager

A console — "AWS/Azure Console for Solace's custom solutions" — to onboard, configure, deploy, govern and monitor multiple Solace solutions through one platform. Each solution exposes three categories: **Designer** (configure), **Governance** (health), **Monitoring** (common + native metrics).

**Status: Phase 0 frontend exists; backend gate-first foundations and the Plan 004–005 `dp-remote` apply engine are merged on this integration lineage, while the design remains PROPOSED and the production control-plane HTTP surface is not implemented. UNBASELINED. Current backend transition: Plan 006 supplies local identity and scoped RBAC; Plan 007 supplies the contracted HTTP surface.**

> **Current-status correction (2026-09-17):** The dated backend narrative below is historical, not a current inventory. `cp-api` contains the ratified 38-verb catalog and operation registry; the frontend contains demo screens and demo API clients; and Plan 005 is merged on this lineage. Production control-plane HTTP handlers remain absent, and the stated design/ratification gates remain in force. Decision ids are allocated from the merged current `DECISIONS.md` register, not deferred for a future Plan 005 merge.
**Backend (2026-08-28, merged):** `docs/design/backend/` holds `design.md` + `decisions.md` (`BE-D1`–`BE-D9`, PROPOSED) — event-driven backbone on the **customer's** event mesh (dedicated VPN), browser joins via the Solace WebApp SDK (`~/Work/Development/RapidPilot/webAppSDK`) through a gateway-proxied `wss` with post-RBAC JWT tickets, hybrid thin-notify + raw-tier chart tails, Postgres stays sole truth. Requirement `REQ-012`. Survived five adversarial rounds (R5/R5.1 spec, R6 plan, R7/R8 post-execution — all triaged in `docs/tasks/codex-review-triage.md`; R8 measured and closed two wire escapes past R7's Java shape guard). **Gate-first plan [`docs/plans/backend/001-mesh-foundations-and-gates.md`](docs/plans/backend/001-mesh-foundations-and-gates.md) is EXECUTED** — every task independently reviewed; its Results section is the completion record. What now exists: the G1 broker spike (`backend/spikes/mesh-authz/`), `contracts/events/` behind `run-checks.sh` check 10, and a Maven reactor with `mesh-events` (envelope + topic derivation) and the `EventPublisher` seam, `I7` enforced by tested ArchUnit rules. **G1 CLOSED:** the mechanism `BE-D5` left UNSETTLED is chosen — candidate (a), the substitution-variable ACL, measured against a live broker in [`docs/design/backend/spike-authz-result.md`](docs/design/backend/spike-authz-result.md) — but **measured single-tenant**, with gaps routed to the **project owner** (OR-1) — ⚠️ **NARROWED 2026-09-16 by OR-5/OR-6:** one VPN per Organization largely dissolves *multi-tenant composition of one profile*, leaving sub-organization/environment ACL composition, and the notify-tier exception is now **expressible but unmeasured**. **`BE-D5` ratification stays blocked on open items 32, 33 *and* 35** until the §11.3 spike matrix, which is **written and not executed**, runs against a live broker. **G2 CLOSED and the `D10` amendment RATIFIED and `LOCKED` (2026-09-16)** — [`docs/design/D10-G2-APPROVAL-2026-09-15.md`](docs/design/D10-G2-APPROVAL-2026-09-15.md) **is** the amendment, and ⛔ **where old `D10` conflicts with it the amendment controls**. **The project owner is the final architecture authority**; open item 40 is **closed**, and a `LOCKED` decision now changes only by an explicit written owner ruling naming the decision and the scope — ordinary implementation approval is **never** an amendment. The 2026-09-15 one-off authorization is historical provenance. Ratified scope: the 35-member permission catalog (both addenda), **scope-evaluated** bindings with downward inheritance (`BE-D15` §5.1's whole-install deviation **rejected**), install-time bootstrap admin with server-enforced rotation, minimal local identity (`source ∈ {local, idp}`), durable/expiring/revocable sessions with CSRF, append-only CP audit, and one `authorizationService.require` with no controller-local checks and no production super-admin bypass. **The catalog is 38 verbs** — the ratified 35 plus `instance.stop`, `audit.view` and `user.edit` (2026-09-16) — with **three built-in roles**: Administrator (all 38, **bootstrap/emergency only**), Viewer (8), Operator (19). **Bounded still means bounded:** no **strategy-assertion** verbs, no **`group.*`** verbs, and ⚠️ **no decision ids are reserved in advance** — `DECISIONS.md` is the one central allocator and ids are assigned only after plan 005 merges. ⚠️ ⚠️ The tree still holds a **different 35-member** catalog — `frontend/src/shared/authz/catalog.ts`, citing an uncorroborated `D18` 🔒 — plus a 4-string demo vocabulary; the ratified one is the amendment's **38** (that record §6). **G3 closed in-spec and now executable** (event envelope, `stateVersion`-only ordering, check 10) — **closed for the envelope only**: the catalog carries four schema-ahead-of-design items ([Known gaps](contracts/events/README.md)) pending the `BE-D4` amendment, so generated types are not settled yet. **Auth-bearing backend code is now permitted, within the ratified amendment's bounded scope** — the bar that read *"no auth-bearing backend code exists or may be written before G2"* is discharged, and open item 38's `CLAUDE.md`-vs-`BE-D15` conflict is moot with it. What still gates is the **closed catalog**, not the gate: an endpoint needing an unapproved verb cannot ship. ⚠️ The only authorization code in the tree today is `backend/demo-launcher`'s four hard-coded strings, built under a waiver that **expired 2026-09-14** — it is demonstration scaffolding, not a production authority, and plan 006 replaces it. `frontend/` holds a themed Vite + strict-TS + MUI scaffold with the transcribed state vocabulary and eight enforced gates — **no screens, no API clients, nothing plane-specific**. Everything else is still design-only, and the design is **not a ratifiable baseline** — an independent review found seven blocking defects. Read [`docs/tasks/codex-review-triage.md`](docs/tasks/codex-review-triage.md) before building anything on it.

**This is a monorepo**, to be deconstructed into per-component repos later. Contracts are in-tree; module boundaries are enforced at build time where code exists — the backend reactor's `arch-tests` enforce `I7` with tested ArchUnit rules under `mvn verify`, and the frontend enforces its own boundaries locally via ESLint plus `frontend/scripts/check-guards.sh` — each guard covered by a self-test that plants a violation and checks it is reported. **There is still no CI**, so nothing runs these gates automatically.

## Where the design lives

Two cross-cutting documents under `design/`, plus one folder per component. Read them rather than relying on this file — `CLAUDE.md` is an index, not a summary.

| File | Contains |
|---|---|
| **`docs/design/DECISIONS.md`** | The decision register — every decision with a stable ID, status, rationale and supersession history. Plus deferred items, the hardening backlog, and all open items. |
| **`docs/design/architecture.md`** | The overall architecture — planes, Target model, domain model, config ownership, instance authority, storage, restart, telemetry, topology matrix, capability limits, v1 slice, sub-spec scope. ⚠️ **§14 is superseded** by `docs/design/dynamic-ui/design.md`, and **§10's `source.kind` list** by `contracts/solution-package.schema.json`; neither is reconciled yet. |
| **`docs/design/dynamic-ui/`** | **Subsystem A — the config-driven UI** (`UI-D*`): Solution Package, applicability grammar, schema registry, quarantine. Its executable half is `contracts/`. |
| **`docs/design/dynamic-metrics/`** | Query API, widget/dashboard model, aggregation, chart contract (`DM-D*`). |
| **`docs/design/<component>/`** | Per-component `design.md` + `decisions.md` — `frontend/` (`FE-D*`), `go-agent/` (`AG-D*`), `backend/` (`BE-D*`) and `backend/agent-gateway/` (`AGW-D*` — a Java Maven Data Plane component co-deployed with DP v1, not a separate app/process and distinct from the host-local Go agent; **designed 2026-09-08, not built**) exist; `runtime/` (`RT-D*`) does not. |
| **`docs/tasks/codex-review-triage.md`** | The outstanding defects from independent review, with verification references. |

*(Consolidated 2026-08-10 from three predecessor specs, since removed; their content is absorbed into the files above.)*

## Repo layout

One tree. All documentation under `docs/`; each component's code gets a top-level folder.

| Path | Contains |
|---|---|
| `docs/requirements/` | Every incoming requirement, with an ID and a **trace** to the decision that satisfies it. Start at `docs/requirements/README.md`. |
| `docs/design/` | Architecture + decision register + `common/` (contract *design*, canonical conventions, cross-cutting lessons) + one folder per subsystem/component holding its own `design.md` and `decisions.md` — `dynamic-ui/`, `dynamic-metrics/`, `frontend/`, `go-agent/`. Start at `docs/design/README.md`. |
| `docs/plans/` | **Approved** implementation plans, one folder per component. Authoritative; the GitHub Project tracks execution against them. |
| `backend/` `frontend/` `go-agent/` `runtime/` | **Code**, one folder per component. Each has a README pointing at its design. Exists so far: the frontend Phase 0 scaffold, and the backend Maven reactor + G1 spike. `go-agent/` and `runtime/` are still empty. |
| `contracts/` | **Machine-readable contracts — exist, executable, and normative.** Four parts: `solution-package.schema.json` is the **canonical home of every closed vocabulary** (`UI-D14`) and the only thing at the top level; **`validation/`** holds the load-time gates plus the package fixtures and 13 negative cases; **`view-models/`** is the `project()` engine as a self-contained npm package; **`events/`** is the v1 mesh event contract (`BE-D4`) — envelope + notify schemas, AsyncAPI taxonomy, fixtures and negatives, gated by check 10. **Run `./contracts/run-checks.sh` before changing anything here.** The engine lives inside `contracts/` rather than a root `packages/` because it is a shared build input for two toolchains — the same reason the schema is here — but the schema level holds no code. Design and rationale stay in `docs/design/dynamic-ui/` and `docs/design/common/contracts/`. Still to come: `.proto` and OpenAPI. |
| `docs/runbooks/` | Operational procedures, for operators running the system. |
| `docs/tasks/` | `lessons.md` (repo-local lessons) · `codex-review-triage.md` (review history). **Work in flight lives in the GitHub Project, not here.** |
| `docs/checklists/` | Development-time gates: spec review, plan review, definition of done, new-module setup, external-review prompt. |

Everything lives in this one tree: design, plans and code move in a single reviewed PR. **Contracts are in-tree but still single-writer** — a contract change is a deliberate reviewed change, never a convenience edit, because it is consumed by four modules.

The two mandated blocks at the bottom of this file are **authoritative and live here** — one copy, no sync machinery. Their rationale and changelog are in `docs/design/common/conventions/`. When the monorepo is split, each new repo copies the blocks from here and a drift check becomes worth building; while there is one tree it is not.

⚠️ **"Runtime" means two different things.** In the architecture, *runtime* = where the **MIs** execute (VM · Docker · Kubernetes), which SM does not own. `design/runtime/` and `runtime/` mean **SM's own container packaging** — Dockerfiles, image layering, compose for local dev. Say "MI runtime" or "SM packaging" rather than bare "runtime".

### Two rules when working with these docs

1. **⚠️ `SM-D*`, `UI-D*`, `DM-D*` and `FE-D*` are PROPOSED, not ratified.** Only `D*` and `B/D*` are binding. The architect group has confirmed only that SM is a config/governance plane rather than a deployment plane, and that `dp-remote` may hold host credentials. Where a proposal conflicts with a `LOCKED` decision, **the locked one stands — flag the conflict, don't silently resolve it.** Several are also proposals *against other proposals* (`UI-D12` vs `SM-D14`, `DM-D5` vs `SM-D31`), so the families ratify **in order**: `SM-D*` → `UI-D*` → `DM-D*` → `FE-D*` → `BE-D*`, with `AG-D*` after `SM-D*` and `AGW-D*` after both `BE-D*` and `AG-D*`. `DECISIONS.md`'s **ratification matrix** is the only authority on status and marks each conflict.
2. **Decision IDs are immutable and deliberately not renumbered. Cite a family prefix, never a range** — a range in prose is stale the next time a decision is added. The families: `D*` (parent architecture, `D1`–`D17`, closed) · `B/D*` (deploy/agent/telemetry loop, closed) · `SM-D*` (on-prem-first revision; currently through `SM-D31`, plus `SM-D11a` as an insertion) · `UI-D*` (`docs/design/dynamic-ui/`) · `DM-D*` (`docs/design/dynamic-metrics/`) · `FE-D*` (`docs/design/frontend/`) · `AG-D*` (`docs/design/go-agent/`) · `BE-D*` (`docs/design/backend/`) · `AGW-D*` (`docs/design/backend/agent-gateway/`; Java Maven component co-deployed with DP v1, not a separate app/process and distinct from the host-local Go agent; ratifies after `BE-D*` and `AG-D*`) · the reserved `RT-D*`. A citation like "per D8" must never shift meaning. **To change a decision, add a new one that supersedes it** — never edit a decision's meaning in place. Note `D17` (first vertical slice) and `B/D17` (editions) are different decisions.

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

### Backend module boundary — `dp-core` / `agent-gateway` (PROPOSED `AGW-D2`, designed 2026-09-08, not built)

An intra-Data-Plane rule beside `I7`'s cross-plane one, kept out of the synced invariants block because it is
module-specific and belongs in the module's design plus `arch-tests`. Full statement and enforcing artifacts:
[`docs/design/backend/agent-gateway/design.md`](docs/design/backend/agent-gateway/design.md) §2, [`decisions.md`](docs/design/backend/agent-gateway/decisions.md) `AGW-D2`.

- `dp-core` owns all durable Data Plane business state and **all writes to `sm_data`**.
- `agent-gateway` owns agent-facing gRPC/mTLS sessions, leases (in relay), backpressure, command delivery and reconnect handling.
- v1 may co-deploy both in the Data Plane deployable, but they remain separate modules and independently deployable.
- `agent-gateway` may not import DP repositories, JPA entities, or access DP tables directly — it holds **no datasource and no DB role**.
- Gateway-to-DP interaction uses **typed semantic ports** in `dp-api`; in-memory callbacks are never the sole contract.
- The DP persists command intent **before** delivery. Agent reports are idempotent and tenant/agent/target bound.
- Every in-process port has **contract tests that a future remote adapter must pass**.
- Future scale-out must not require changing apply semantics, persistence ownership, or telemetry meaning.

**Enforced by** (once built): ArchUnit rules `agwUsesDpApiOnly`, `agwHoldsNoDatasource`, `onlyAgwTouchesGrpc` and
siblings in `backend/arch-tests/`, each with a planted-violation proof, plus a pom dependency allow-list. Until those
tests exist and fire, this is a proposal, not a guarantee (`L19`).

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

1. **Ratification of `SM-D1`…`SM-D31` + `SM-D11a`**, with `docs/design/architecture.md` §13's capability limits explicitly accepted — in particular that the outbound-only / no-stored-credentials guarantee would hold only for `host-local`. `SM-D29`–`SM-D31` are load-bearing, not an appendix: they are the direct base of `UI-D5`, `DM-D5`, `DM-D7`, `DM-D11` and `DM-D12`. Then, in order, the `UI-D*`, `DM-D*` and `FE-D*` sets.
2. **Compute the storage sizing model** — SM-D8 should not be ratified before it exists.
3. **Specify the `dp-remote` apply protocol** — the agent protocol's safety properties (generation ordering, idempotency, lease, crash-safe resume) have no `dp-remote` equivalent.
4. **GitOps prerequisite** — confirm customers will exclude an SM-owned ConfigMap from Argo/Flux scope, or `additional-location` on Kubernetes is unusable.
5. **Frontend stack — settled; visual reference — not.** Split as open items 19a and 19b. **19a is CLOSED:** `FE-D4` picks MUI outright per LOCKED `D7` — "no Tailwind, no Radix, no shadcn" — and the frontend sub-spec now exists, so this blocks nothing; what remains is ratifying that reading with the `FE-D*` set. **19b is narrowed, not closed** (2026-08-19): an in-repo reference now exists — the approved `sm-v2` mockup at `docs/design/frontend/reference/` (proposed `FE-D25`) — and the palette is **verified against Solace product source** (proposed `FE-D26`; the retired interim pair `#003057`/`#00a89e` was simply wrong). What remains is **authority, not provenance**: 19b assigns the reference to the designer, so theme work runs under the `frontend/design.md` §24 gate-2 **waiver** and visual-regression baselines stay blocked. Three questions sit with the designer. Inventing a palette from memory still violates `I2`.
6. ✅ **`REQ-006` (scoped RBAC) — RATIFIED 2026-09-16 and buildable; none of it is built yet.** Open item **24** is closed by [`docs/design/D10-G2-APPROVAL-2026-09-15.md`](docs/design/D10-G2-APPROVAL-2026-09-15.md), and open items **23** and **38** with it. Implementation is [plan 006](docs/plans/backend/006-local-identity-and-scoped-rbac.md) — **not started**. Read the approval record before writing any authorization code: it enumerates the exact **38** verbs and the three built-in roles, requires scope evaluation rather than whole-install, and names what it does **not** grant. The historical statement of the gap follows, because it explains why the amendment exists: The approved mockup's own catalog enumerates **30 permissions of which 2 are grantable**; under `D10` 🔒's deny-by-default, every core v1 screen is unauthorizable — Designer, Governance, restart, metric typing, package load, and the Admin screens themselves. Open item 23 recorded only the six `DM-D18` metrics verbs and framed the consequence as "dashboards unbuildable"; the real consequence is **the console** is unbuildable under its own acceptance criterion. First-admin bootstrap, the local identity store and the audit log are also undesigned. **A consolidated `D10` amendment proposal is drafted** — [`docs/design/D10-AMENDMENT-PROPOSAL.md`](docs/design/D10-AMENDMENT-PROPOSAL.md) — and needs the architect group. This was the only v1-must requirement in that state; ⛔ **it no longer is** — see this item's ✅ heading above.
7. **The visual reference has an errata list, and it is load-bearing.** `FE-D25` makes the mockup win on visuals and copy; [`docs/design/frontend/reference/ERRATA.md`](docs/design/frontend/reference/ERRATA.md) records **twelve places it is wrong**, including one where it makes SM **deploy artifacts** (contradicting the one confirmed architectural position, and contradicting the mockup's own copy) and one where it **misstates LOCKED `D10`**. Read the errata before transcribing any screen.
8. **Two new blockers from the sub-specs** — the `console` composition module vs `I7` (~~open item 21~~ ✅ **CLOSED 2026-09-16, ruling OR-10**: `console-api` is approved as a thin composition module, so this is neither a build blocker nor a ratification question any more. ⚠️ Residual docs task only: `architecture.md` §3.1 must name the third plane module), and server-side redaction of `yaml`-embedded secrets (open item 22, gates Phase 2 / `type: yaml`). ~~Plus open item 23: `DM-D18` adds six RBAC verbs to LOCKED `D10`~~ ✅ **open item 23 is CLOSED 2026-09-16** — the ratified amendment catalogues all six (§1.1 rows 16–18, 20–22). An earlier revision of this line still said *"flagged, not resolved"*.
9. ⚠️ **The scope model — RULED 2026-09-16; the frontend intake is still open.** ✅ **Two hierarchies, and they differ in length — do not use one where the other belongs.** **(a) API / topic / event vocabulary — SIX names** (ruling **OR-2**): `Organization → Sub-organization → Environment → Solution → Instance → Placement`, on **immutable ids**. **(b) RBAC GRANT hierarchy — FIVE levels** (ruling **OR-16**, which amends `D11` and names it): the same list **ending at Instance**. ⛔ **Placement is an operational child identity of Instance and is NOT an independently grantable level** — a placement-targeted operation authorizes through the owning Instance scope plus any required **Target** permission. `tenant_id` remains an **internal storage key only**. That **amends `SM-D32`**, which stopped at *Solution Instance* and said *Organization Unit*; ⚠️ **a document that says Organization Unit is stale — but a *grant* document that omits Placement is CORRECT.** Scope-evaluated authorization with downward inheritance is ratified with the `D10` amendment. The earlier proposal was `Organization → Organization Unit → Environment → Solution Instance` (fixed depth): **`SM-D32`–`SM-D34`**, full paper at [`docs/design/D11-AMENDMENT-PROPOSAL.md`](docs/design/D11-AMENDMENT-PROPOSAL.md). ✅ **Two hierarchies, both ruled.** For **APIs, topics and events** the vocabulary is the six names `Organization → Sub-organization → Environment → Solution → Instance → Placement`, on immutable ids (**OR-2**). For **grants** it is **five** levels ending at **Instance** (**OR-16**, which amends `D11` and names it): ⛔ **Placement is an operational child identity of Instance, never an independently grantable RBAC level** — a placement-targeted operation authorizes through the owning Instance scope plus any required Target permission. ✅ **`solution_id` is added to the RBAC scope schema now; `placement_id` is not added to role bindings.** `tenant_id` stays an internal storage key only. ⚠️ **That corrects `SM-D32` twice:** the level is **Sub-organization**, not "Organization Unit", and the **grant** depth is **five**, ending at Instance (OR-16) — not four-plus-placement, and not six. `D12` is confirmed. ✅ **`D11` is formally AMENDED by OR-16**, which names it as §0 requires — the gap that OR-2 alone left open. Its decoupling-from-placement and rebind-preserves-ownership clauses are untouched. ✅ **The scope storage schema is extended to match** — plan 006 Task 2 adds `solution_id` (OR-16). Five new open items: **34** — `origin/dev/frontend` records `D18`/`D19`/`D20` as **LOCKED** in a family this repo declares closed at `D17`, uncorroborated here, and the answer decided whether G2 was already closed — ⚠️ **that half is now moot**: G2 is closed by the 2026-09-15 approval on this baseline's own evidence, and what remains of 34 is the **id-allocation** question, which the approval deliberately does not touch (it took no `D*` number, and the frontend cites `D18` 🔒 for a *different* 35-verb catalog); **35** — environment-level mesh isolation, a surface `SM-D32` creates and organization-scoped topics do not cover; **36** — the identity-vocabulary rename, one coordinated release, its contract leg gated on G3; **37** — `BE-D12` names two different decisions on two branches (and `FE-D25`/`FE-D26` likewise); **38** — ✅ **CLOSED 2026-09-16, moot** (the ratification date, per `L8`): the `CLAUDE.md`-vs-`BE-D15` conflict was about what an *open* G2 gates, and G2 is closed. ⚠️ **35 is NARROWED 2026-09-16 (OR-5/OR-6), not resolved** — the architecture is decided, the broker enforcement is **unmeasured**: approving sub-organization-scoped bindings creates principals whose REST scope the broker ACL cannot enforce. ⚠️ **Do not merge `origin/dev/frontend`:** measured, a direct merge **silently deletes 70 `backend/` files** (including the reactor root POM) while raising only 3 conflicts — see [`docs/tasks/dev-frontend-intake.md`](docs/tasks/dev-frontend-intake.md) and the delivery sequence in [`docs/tasks/demo-delivery-sequence.md`](docs/tasks/demo-delivery-sequence.md).
10. **Backend: plan 001 is executed and merged, and what blocks plan 002 is now three open items the *project owner* owes.** ⚠️ **Three — open items 32, 33 and 35 — and the owner, not the group:** G2 was a fourth and is **closed**, and ruling **OR-1** makes the project owner the final architecture authority. (An earlier revision of this heading said "two", which disagreed with this item's own tail and with the register.) G1 is CLOSED — candidate (a), the substitution-variable ACL, measured — but it left **two priced ACL-shape gaps** that block `BE-D5` ratification and cannot be closed by more spiking ⚠️ **(as stated before OR-5/OR-6; those rulings changed the shape of both gaps — see the corrected summary at the top of this file — but left a *measured* broker spike owed)**: **multi-tenant composition** (only the username level substitutes, the tenant level is a topic literal, and a VPN has one `default` client username, so one fallthrough profile carries one tenant — three routes priced, none picked, none measured) and **the notify-tier exception shape** (design §4's notify topics have no per-subject level to substitute at — three options costed, the first recommended). Both are in [`docs/design/backend/spike-authz-result.md`](docs/design/backend/spike-authz-result.md), and the install runbook's ACL provisioning cannot be written until they land. **G2 is CLOSED and the `D10` amendment is RATIFIED (2026-09-16)** ([`docs/design/D10-G2-APPROVAL-2026-09-15.md`](docs/design/D10-G2-APPROVAL-2026-09-15.md)) — auth-bearing backend tasks are unblocked within that scope and the three mesh verbs are catalogued. ⚠️ **Cataloguing them still does not ratify `BE-D5`**: items 32/33/35 are **narrowed, not closed** — **one Message VPN per Organization** is decided (on Solace Cloud, one broker service per Organization), while **lower-scope ACL behaviour is gated on a measured broker spike that has not run**, so the install runbook's ACL section still cannot be written. `BE-D6` supersedes `DM-D17` and `BE-D7` amends `FE-D19` — flagged, ratify after those families. `BE-D9` requires the reviewed `solace_event` extension in the contracts schema.

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
Cross-cutting decisions live in `docs/design/DECISIONS.md`, which also holds the **ratification matrix — the only
authority on the status of every family**. IDs are **immutable** and were deliberately not renumbered. The families:
`D*` (parent architecture) · `B/D*` (deploy/agent/telemetry) · `SM-D*` (on-prem-first revision) ·
`UI-D*` (dynamic UI) · `DM-D*` (dynamic metrics) · `FE-D*` (frontend) · `BE-D*` (backend) · `AG-D*` (go-agent) ·
`AGW-D*` (agent gateway) · the reserved `RT-D*`.
Bodies of the per-folder families live in `docs/design/<folder>/decisions.md`.

- **Cite a family prefix, never a range.** A range written into prose is stale the next time a decision is added.
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
