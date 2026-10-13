# Solution Manager

A console — "the AWS/Azure Console for Solace's custom solutions" — to onboard, configure, deploy, govern and monitor multiple Solace solutions through one platform. Each solution exposes three categories: **Designer** (configure), **Governance** (health), **Monitoring** (common + native metrics).

Solution Manager (SM) is a configuration and governance plane, not a deployment plane: the customer's own CI/CD deploys artifacts, and SM owns the configuration of the solutions it manages, applying it by writing an external config file and restarting. A thin **control plane** holds identity, scoped RBAC, the solution catalog and entitlements; a **data plane** holds the configuration of record and all telemetry. This repository is a **monorepo** for now, to be deconstructed into per-component repos later.

**Status (2026-10-09): the `dp-remote` backend exists and is served; the design is still UNBASELINED.** The backend serves the whole console API contract (**78 operations: 77 counted served, and `exportRange` wired and tested but not counted served** until its large export stream is measured; the five agent operations are among the served ones, but the agent path as a whole is not counted served until its live gates have run) behind real authentication, and applies configuration to managed integrations running in **Docker on an SSH-reachable host** — Docker over SSH is the only runtime supported for now. Databases: `sm_control` V001-V022, `sm_data` V001-V031, `sm_metrics` V001-V004, **all anchored** (`backend/scripts/frozen-migration-anchors.txt`; the plan 015 migrations are `sm_control` V021 and V022, `sm_data` V028-V031 and `sm_metrics` V004). What the API serves, by plan: **008** — an instance can have more than one placement (`addPlacement`, `retirePlacement`, a placement-aware apply journal); **012** — `undeclareInstance` (releases an instance from management without touching its host), the `restart-all` and `rolling-no-validation` strategies, and an apply scheduled as a window; **013** — targets are registered and managed (scan, register, edit and re-pin, rotate the key, test, retire), the hierarchy is created and renamed, entitlements are set, and local users and role bindings are administered (usernames are unique per organization). A login names an `organization`, which may be omitted on an install that holds only one. `backend/local/sm-local.sh up` stands the whole thing up locally and sets it up through that API; the Postman collection's `Setup` folder walks the same sequence. The frontend is built against fixtures and integrates with that API through the Postman collection and the OpenAPI contract; it owes the login body's rename from `password` to `credential`. **Live gates (2026-10-09):** L1, L2, L4, L5 and the live apply of the Docker config-file mode fix X1 (the explicit `0640` mode on the Docker config file and its pre-images) PASSED on the E2E host, and L3 is partial; L2's first attempt found an SSH inspect defect (a container with no Docker healthcheck), fixed in `7ff85a42` ([results](docs/plans/backend/combined-008-012-013.md#live-gate-results-2026-10-09)). **Still owed:** only L3's `l3-edit` on the host: the cloud security group blocks the alternate `sshd` port, and the owner must open a port or allow an alt `sshd` on an open one. The `7ff85a42` fix is proven end to end on the host (a real Cache with no healthcheck was declared, probed and applied, 2026-10-09). **In progress:** the `host-local` agent gateway, plan 015 W1 to W4 integrated on `integration/agent-gateway` (module, schema, gateway service, operator verbs, scheduled jobs, Go agent); the real-agent harness and live gates G-A1/G-A2 have not run, so it is **not counted served**; runbooks in [`docs/runbooks/backend/`](docs/runbooks/backend/), each `UNTESTED`. **Not built:** the plain-VM runtime adapter (plan 014) and the browser's live notify. The design is **not a ratifiable baseline** — see the open items in [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md) and the exact "not done yet" list, in order, in [`backend/README.md`](backend/README.md#not-done-yet-in-the-order-it-will-be-done).

## Start here

| If you want to… | Read |
|---|---|
| Build, test or run the backend | [`backend/README.md`](backend/README.md) |
| Call the API (contract, served operations, Postman) | [`contracts/openapi/`](contracts/openapi/): [`console.yaml`](contracts/openapi/console.yaml), [`ENDPOINT-INVENTORY.md`](contracts/openapi/ENDPOINT-INVENTORY.md), [`postman/README.md`](contracts/openapi/postman/README.md) |
| Install or operate a running system | [`docs/runbooks/`](docs/runbooks/) |
| Run the whole backend locally, set up through the API | [`backend/local/README.md`](backend/local/README.md) |
| Understand the architecture | [`docs/design/architecture.md`](docs/design/architecture.md) |
| Know what was decided, and whether it's settled | [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md) |
| See what is currently wrong with the design | [`docs/tasks/codex-review-triage.md`](docs/tasks/codex-review-triage.md) |
| Add a requirement | [`docs/requirements/README.md`](docs/requirements/README.md) |
| Work on a component | `docs/design/<component>/` → `docs/plans/<component>/` → `<component>/` |
| Start a component's code | [`docs/checklists/new-module.md`](docs/checklists/new-module.md) |
| See what is being worked on | the **GitHub Project "Solution Manager"** — status and assignment live there, not in this repo |

⚠️ **`SM-D*`, `UI-D*`, `DM-D*` and `FE-D*` are all PROPOSED, not ratified** — only `D*` and `B/D*` are binding. Where a proposal conflicts with a `LOCKED` decision, the locked decision stands: flag the conflict, don't resolve it silently. The families must also ratify **in order** — see the ratification matrix in [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md). Owner rulings (`OR-*`) in the same register are binding on the work they name, and the owner rules on behalf of the architect group (`OR-69`).

## Layout

```
docs/                 ALL documentation lives here
  requirements/       every requirement, with an ID and a trace to what satisfies it
  design/
    DECISIONS.md      the decision register + the ratification matrix, which is the ONLY
                      authority on status — for every family, including those below
    architecture.md   the overall architecture (§14 superseded by dynamic-ui/)
    common/           contract design · convention rationale · cross-cutting lessons
    dynamic-ui/       Subsystem A — config-driven UI, Solution Package   (UI-D*)
    dynamic-metrics/  query API, widgets, dashboards, chart contract     (DM-D*)
    frontend/         the console                                        (FE-D*)
    backend/          control + data plane, event backbone               (BE-D*)
                      backend/agent-gateway/ — the host-local agent path (AGW-D*; plan 015 W1-W4 integrated, live gates pending; runbooks in docs/runbooks/backend/)
    go-agent/         the host-local agent                               (AG-D*)
  plans/              APPROVED implementation plans, one folder per component (backend: 001–014; 015 approved (OR-128) and planned on the agent-gateway branch)
  runbooks/           operational procedures, for operators
  checklists/         spec review · plan review · definition of done · new module
  tasks/              lessons.md · codex-review-triage.md  (work tracking lives in the Project)

contracts/            MACHINE-READABLE contracts + fixtures + gates (run-checks.sh)
  solution-package.schema.json   the Solution Package schema, home of every closed vocabulary
  validation/                    load-time gates, package fixtures, negative cases
  view-models/                   the project() engine (npm package)
  events/                        the v1 mesh event contract (envelope, notify schemas, AsyncAPI)
  openapi/                       console.yaml, ENDPOINT-INVENTORY.md and the generated Postman collection

backend/              Java 17 / Spring Boot Maven reactor — control + data plane, launcher, arch-tests
frontend/             React + Vite console (fixture-backed; see its README)
go-agent/ runtime/    the agent (on integration/go-agent, not merged into UAT) and SM's own container packaging (launcher image, compose)
```

**Design and decisions sit together, per folder.** A subsystem's `design.md` and `decisions.md` live in
`docs/design/<folder>/` — never in `common/`, which holds only what more than one of them shares.

**[`contracts/`](contracts/)** holds machine-readable contracts — build inputs for the backend and frontend toolchains, not documentation. Run `./contracts/run-checks.sh --strict` before changing anything there (it needs `frontend/node_modules`, or `SM_FRONTEND_NODE_MODULES`, and `contracts/view-models/node_modules`). Design and rationale stay in [`docs/design/dynamic-ui/`](docs/design/dynamic-ui/) and [`docs/design/common/contracts/`](docs/design/common/contracts/).

## Components

| Folder | Component | Stack | State |
|---|---|---|---|
| `backend/` | control plane + data plane, `dp-remote` | Java 17 / Spring Boot, PostgreSQL 14 | serves the console API; Docker over SSH |
| `frontend/` | console | React + Vite | code present, fixture-backed; integrates via the OpenAPI contract and Postman collection |
| `go-agent/` | `host-local` agent | Go | built on `integration/go-agent` (plan 015 W1-W2), not merged; live gates pending |
| `runtime/` | **SM's own container packaging** | Docker | launcher image and single-host compose exist (plan 015 W2) |

**Why a monorepo.** Contracts stay in-tree, so there is no publishing pipeline to build before work can start and no cross-repo PR choreography for a contract change. Module boundaries are enforced at build time rather than by review discipline — only possible in one tree. In the backend, `arch-tests` enforce the plane boundary (`I7`) and the confinement of JDBC, JCSMP and `docker-java` with tested ArchUnit rules; the frontend enforces its own boundaries with ESLint and `frontend/scripts/check-guards.sh`; `contracts/run-checks.sh` gates the contracts, the frozen database migrations and the licence check (`REQ-014`). ⚠️ **There is no general CI**: these gates run when someone runs them. The one automated runner is `.github/workflows/migration-freeze.yml`, which reports a failure but cannot block a merge on this plan. Independent release cadence comes from path-scoped CI and independent artifacts, not from separate repos.

**Designed to be split later.** Each component's design and plans are self-contained under `docs/design/<component>/` and `docs/plans/<component>/`, referencing cross-cutting material by path, so extraction is a move rather than a rewrite. Two things must be settled **before** splitting: contract publishing (versioned artifacts for Maven, npm and Go), and an agent↔plane protocol that survives separate deployables.

## One terminology warning

**"Runtime" means two different things:**

- In the architecture, **runtime** = where the *managed integrations* execute — VM, Docker, Kubernetes. SM does **not** own this; the customer's own CI/CD deploys artifacts into it. SM's `dp-remote` backend supports **Docker over SSH** only for now.
- **`docs/design/runtime/`** and **`runtime/`** = **SM's own container packaging** — Dockerfiles, image layering, compose for local development.

Prefer **"MI runtime"** or **"SM packaging"** over bare "runtime" in anything you write.

