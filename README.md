# Solution Manager

A console — "the AWS/Azure Console for Solace's custom solutions" — to onboard, configure, deploy, govern and monitor multiple Solace solutions through one platform. Each solution exposes three categories: **Designer** (configure), **Governance** (health), **Monitoring** (common + native metrics).

**Status: pre-implementation, UNBASELINED.** No application code yet, and the design is not a ratifiable baseline — see the open items in [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md). **Monorepo** for now, to be deconstructed into per-component repos later.

## Start here

| If you want to… | Read |
|---|---|
| Understand the architecture | [`docs/design/architecture.md`](docs/design/architecture.md) |
| Know what was decided, and whether it's settled | [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md) |
| See what is currently wrong with the design | [`docs/tasks/codex-review-triage.md`](docs/tasks/codex-review-triage.md) |
| Add a requirement | [`docs/requirements/README.md`](docs/requirements/README.md) |
| Work on a component | `docs/design/<component>/` → `docs/plans/<component>/` → `<component>/` |
| Start a component's code | [`docs/checklists/new-module.md`](docs/checklists/new-module.md) |
| Operate the system | [`docs/runbooks/`](docs/runbooks/) |
| See what is being worked on | the **GitHub Project "Solution Manager"** — status and assignment live there, not in this repo |

⚠️ **`SM-D*`, `UI-D*`, `DM-D*` and `FE-D*` are all PROPOSED, not ratified** — only `D*` and `B/D*` are binding. Where a proposal conflicts with a `LOCKED` decision, the locked decision stands: flag the conflict, don't resolve it silently. The families must also ratify **in order** — see the ratification matrix in [`docs/design/DECISIONS.md`](docs/design/DECISIONS.md).

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
    go-agent/         the host-local agent                               (AG-D*, none yet)
                      backend/ and runtime/ are not created yet          (BE-D*, RT-D*)
  plans/              APPROVED implementation plans (created when written)
  runbooks/           operational procedures, for operators
  checklists/         spec review · plan review · definition of done · new module
  tasks/              lessons.md · codex-review-triage.md  (work tracking lives in the Project)

contracts/            MACHINE-READABLE contracts + fixtures + gate validator (run-checks.sh)

backend/  frontend/  go-agent/  runtime/    CODE — READMEs only; no code yet
```

**Design and decisions sit together, per folder.** A subsystem's `design.md` and `decisions.md` live in
`docs/design/<folder>/` — never in `common/`, which holds only what more than one of them shares.

**[`contracts/`](contracts/)** holds machine-readable contracts — build inputs for three toolchains, not documentation. It now contains the **Solution Package schema**, its load-time gate validator, the File Mesh source adapter, two working fixtures and 13 negative fixtures. Run `./contracts/run-checks.sh`. Design and rationale stay in [`docs/design/dynamic-ui/`](docs/design/dynamic-ui/) and [`docs/design/common/contracts/`](docs/design/common/contracts/).

## Components

| Folder | Component | Stack |
|---|---|---|
| `backend/` | control plane + data plane | Java 17 / Spring Boot |
| `frontend/` | console | React + Vite |
| `go-agent/` | `host-local` agent | Go |
| `runtime/` | **SM's own container packaging** | Docker |

**Why a monorepo.** Contracts stay in-tree, so there is no publishing pipeline to build before work can start and no cross-repo PR choreography for a contract change. Module boundaries **can be** enforced at build time (ArchUnit for the Java modules) rather than by review discipline — only possible in one tree. ⚠️ **Nothing is enforced today: there is no code and no CI.** This is the reason to stay in one tree, not a description of current state. Independent release cadence comes from path-scoped CI and independent artifacts, not from separate repos.

**Designed to be split later.** Each component's design and plans are self-contained under `docs/design/<component>/` and `docs/plans/<component>/`, referencing cross-cutting material by path, so extraction is a move rather than a rewrite. Two things must be settled **before** splitting: contract publishing (versioned artifacts for Maven, npm and Go), and an agent↔plane protocol that survives separate deployables.

## One terminology warning

**"Runtime" means two different things:**

- In the architecture, **runtime** = where the *managed integrations* execute — VM, Docker, Kubernetes. SM does **not** own this; the customer's own CI/CD deploys artifacts into it.
- **`docs/design/runtime/`** and **`runtime/`** = **SM's own container packaging** — Dockerfiles, image layering, compose for local development.

Prefer **"MI runtime"** or **"SM packaging"** over bare "runtime" in anything you write.
