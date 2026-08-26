# Design

All design lives here: two cross-cutting documents, `common/` for shared material, and one folder per component holding its own `design.md` and `decisions.md`.

| Path | Scope |
|---|---|
| [`DECISIONS.md`](DECISIONS.md) | **The decision register.** Every decision, its stable ID, status, rationale and supersession history. Cross-cutting only — component-level decisions (`BE-D*`/`FE-D*`/`AG-D*`/`RT-D*`) live in that component's design folder. |
| [`architecture.md`](architecture.md) | **The overall architecture.** Planes, Target model, domain model, config ownership, instance authority, storage, restart, telemetry, topology, capability limits, v1 slice. |
| [`common/`](common/) | Cross-repo contracts, canonical `CLAUDE.md` convention blocks, cross-cutting lessons. |
| `backend/` · `frontend/` · `go-agent/` · `runtime/` | **Not created yet** under this tree. go-agent working docs currently live in the module at [`../../go-agent/docs/`](../../go-agent/docs/). Placeholder READMEs were deleted — an empty folder with a stub invites misfiling and inflates apparent progress. |

Read `architecture.md` before any component design. A component design may refine the architecture but must not
contradict it — if it needs to, that is a new decision in `DECISIONS.md`, not a local override.

## Monorepo, with the split in mind

Everything is in one tree, so design, plan and code change in a single reviewed PR — the frequent case is one PR by
construction. The project will be **deconstructed into per-component repos later**; two rules keep that cheap:

1. **Component design and plans are self-contained.** `design/<component>/` and `plans/<component>/` reference
   cross-cutting material by path and never inline it. Extraction becomes a move, not a rewrite.
2. **The decision register is never fragmented.** One ID namespace with cross-family supersession stays in
   `DECISIONS.md` regardless of how the code is arranged. Component-level decisions use their own prefix
   (`BE-D*`/`FE-D*`/`AG-D*`/`RT-D*`) in `<component>/decisions.md`.

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

| Family | Origin | Recorded in |
|---|---|---|
| `D1`–`D17` | parent architecture (2026-06-17) | `DECISIONS.md` |
| `B/D1`–`B/D17` | deploy / agent / telemetry loop (2026-07-08) | `DECISIONS.md` |
| `SM-D1`–`SM-D28` | on-prem-first revision + round-2 corrections — **all PROPOSED** | `DECISIONS.md` |
| `BE-D*` · `FE-D*` · `AG-D*` · `RT-D*` | per-component | `<component>/decisions.md` |

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
