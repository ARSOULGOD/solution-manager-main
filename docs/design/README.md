# Design

All design lives here: two cross-cutting documents, `common/` for shared material, and one folder per subsystem or component holding its own `design.md` and `decisions.md`.

| Path | Scope |
|---|---|
| [`DECISIONS.md`](DECISIONS.md) | **The decision register.** Every decision, its stable ID, status, rationale and supersession history — plus the **ratification matrix**, which is the only authority on the status of *every* family, including the ones recorded elsewhere. Bodies are cross-cutting only; `UI-D*`/`DM-D*`/`FE-D*`/`AG-D*`/`BE-D*` and the reserved `RT-D*` live in their own folder. |
| [`archive/demo-waivers/`](archive/demo-waivers/) | **Historical records, not active decisions.** The expired `D10-G2` and `SM-D11A` demo waivers are preserved with their provenance and bodies; current authority comes from the ratified approval and applicable decisions. |
| [`architecture.md`](architecture.md) | **The overall architecture.** Planes, Target model, domain model, config ownership, instance authority, storage, restart, telemetry, topology, capability limits, v1 slice. ⚠️ **§14 is superseded** by [`dynamic-ui/design.md`](dynamic-ui/design.md) and **§10's `source.kind` list is superseded** by `contracts/solution-package.schema.json` (`UI-D14`); neither has been reconciled yet. |
| [`common/`](common/) | Cross-repo contract **design** — incl. [`common/contracts/backend-consumption.md`](common/contracts/backend-consumption.md), how backend code consumes `contracts/` without drift (`BE-D10`/`BE-D11`) — canonical `CLAUDE.md` convention blocks, cross-cutting lessons. The machine-readable contracts themselves live in [`../../contracts/`](../../contracts/). |
| [`dynamic-ui/`](dynamic-ui/) | **Subsystem A — the config-driven UI.** [`design.md`](dynamic-ui/design.md) + [`decisions.md`](dynamic-ui/decisions.md) (`UI-D*`) + [`BRIEF.md`](dynamic-ui/BRIEF.md) + `evidence/`. Solution Package, applicability grammar, schema registry, quarantine. Its executable half is [`../../contracts/`](../../contracts/). **The largest design body in the repo.** |
| [`dynamic-metrics/`](dynamic-metrics/) | **Dynamic Metrics.** [`design.md`](dynamic-metrics/design.md) + [`decisions.md`](dynamic-metrics/decisions.md) (`DM-D*`). Query API, widget/dashboard model, aggregation, chart contract. |
| [`frontend/`](frontend/README.md) | Console design — [`design.md`](frontend/design.md) + [`decisions.md`](frontend/decisions.md) (`FE-D*`). **DRAFT, no code.** |
| [`go-agent/`](go-agent/) | `host-local` agent design — [`design.md`](go-agent/design.md) + [`decisions.md`](go-agent/decisions.md) (`AG-D*`). **DRAFT.** |
| [`backend/agent-gateway/`](backend/agent-gateway/design.md) | **Java Data Plane Agent Gateway module design** — [`design.md`](backend/agent-gateway/design.md) + [`decisions.md`](backend/agent-gateway/decisions.md) (`AGW-D*`) + the gated [`lease-and-registry-sketch.md`](backend/agent-gateway/lease-and-registry-sketch.md). It is a Java Maven component co-deployed with DP v1, not a separate app or process, and is distinct from the host-local Go agent. It owns sessions, not durable state; there is no gateway code or `.proto`. **DRAFT; ratifies after `BE-D*` and `AG-D*`.** |
| [`backend/`](backend/design.md) | Backend design — [`design.md`](backend/design.md) + [`decisions.md`](backend/decisions.md) (`BE-D*`): event-driven backbone on the customer mesh, module layout, mesh tickets, ingest. **DRAFT; plan 001's gate-first foundations and plan 005's eight-operation `RuntimeAdapter` implementation are merged on this lineage** ([`spike-authz-result.md`](backend/spike-authz-result.md) closed G1). [`eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) (`BE-D*`, PROPOSED) designs the walking-skeleton blocker set: the `dp-remote` apply protocol + persistence, the live-console session contract, and the minimal authorization machinery. |
| `runtime/` | **Not created yet.** Will hold that component's `design.md` and `decisions.md` when written (`RT-D*`). |

⚠️ **Everything except `D*` and `B/D*` is `PROPOSED`.** `SM-D*`, `UI-D*`, `DM-D*` and `FE-D*` are all unratified, and
several are proposals *against other proposals*. The ratification matrix in [`DECISIONS.md`](DECISIONS.md) states the
required ordering; nothing here may be implemented as though settled (`I4`).

Read `architecture.md` before any component design. A component design may refine the architecture but must not
contradict it — if it needs to, that is a new decision in `DECISIONS.md`, not a local override.

## Monorepo, with the split in mind

Everything is in one tree, so design, plan and code change in a single reviewed PR — the frequent case is one PR by
construction. The project will be **deconstructed into per-component repos later**; two rules keep that cheap:

1. **Component design and plans are self-contained.** `design/<component>/` and `plans/<component>/` reference
   cross-cutting material by path and never inline it. Extraction becomes a move, not a rewrite.
2. **Decision *status* is never fragmented.** Bodies may live beside their subsystem — `UI-D*`, `DM-D*`, `FE-D*`,
   `AG-D*`, `AGW-D*`, `BE-D*` and the reserved `RT-D*` are recorded in `<folder>/decisions.md` — but the ID namespace is shared,
   supersession crosses families, and **one ratification matrix in `DECISIONS.md` decides what is binding**. A second
   status table anywhere is a defect.

**Contracts are in-tree but single-writer.** A monorepo removes the publishing pipeline and the cross-repo PR
choreography; it does **not** make a contract change casual — four modules consume them. Before splitting, contract
publishing (versioned Maven/npm/Go artifacts) must be settled.

## ⚠️ Terminology: two meanings of "runtime"

These are different things and the collision is a known wart:

| Term | Meaning |
|---|---|
| **runtime** (architecture) | Where the *managed integrations* execute — VM · Docker · Kubernetes. Used throughout `architecture.md` §3, §12. **SM does not own this**; the customer's CI/CD deploys artifacts into it. |
| **`docs/design/runtime/`** and the root **`runtime/`** | **SM's own container packaging** — Dockerfiles and image layering for the control plane, data plane and console, plus compose assets for local development. |

When writing in either place, say **"MI runtime"** or **"SM packaging"** rather than bare "runtime."

## Decision ID families

IDs are immutable and were deliberately not renumbered on consolidation. To change a decision, **add one that
supersedes it** — never edit a decision's meaning in place.

**Cite a family prefix, never a range** — a family grows by appending, so any range written into prose is stale the
next time a decision is added. Current extent is whatever the family's file holds; **status is decided only by the
ratification matrix** in [`DECISIONS.md`](DECISIONS.md).

| Family | Origin | Recorded in |
|---|---|---|
| `D*` (`D1`–`D17`, closed) | parent architecture (2026-06-17) | [`DECISIONS.md`](DECISIONS.md) — **LOCKED** |
| `B/D*` (`B/D1`–`B/D17`, closed) | deploy / agent / telemetry loop (2026-07-08) | [`DECISIONS.md`](DECISIONS.md) — **LOCKED** |
| `SM-D*` | on-prem-first revision + correction rounds 2–5 (`SM-D11a` is an insertion, not a renumbering) | [`DECISIONS.md`](DECISIONS.md) — **all PROPOSED** |
| `UI-D*` | Subsystem A — dynamic UI | [`dynamic-ui/decisions.md`](dynamic-ui/decisions.md) — **all PROPOSED** |
| `DM-D*` | Dynamic Metrics | [`dynamic-metrics/decisions.md`](dynamic-metrics/decisions.md) — **all PROPOSED** |
| `FE-D*` | frontend console | [`frontend/decisions.md`](frontend/decisions.md) — **all PROPOSED** |
| `BE-D*` · `AG-D*` · `AGW-D*` · `RT-D*` | per-component families | `<component>/decisions.md`; `BE-D*` is active in [`backend/decisions.md`](backend/decisions.md), `AG-D*` in [`go-agent/decisions.md`](go-agent/decisions.md), `AGW-D*` in [`backend/agent-gateway/decisions.md`](backend/agent-gateway/decisions.md), `RT-D*` remains reserved until written |

`D17` (first vertical slice) and `B/D17` (editions) are **different decisions** — always check the prefix.

## Writing a component design

1. Confirm the requirement exists in [`../requirements/`](../requirements/) and is traced.
2. Brainstorm → spec, using the `superpowers:brainstorming` workflow.
3. Get it reviewed **independently and adversarially** before treating it as done — see
   [`../checklists/spec-review.md`](../checklists/spec-review.md). Self-review catches contradictions; it does not
   catch wrong facts.
4. Record component decisions with an ID; reference cross-cutting ones rather than restating them.
5. Only then write the plan — at `docs/plans/<component>/`, per
   [`common/conventions/plan-template.md`](common/conventions/plan-template.md).
