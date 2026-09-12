# Solution Manager — agent context

> Durable context for agents. Path: `go-agent/agent.md` (whole monorepo).
> Update on explicit session wrap-up only (skill: `agent-md-context`).

## Snapshot
- **Date:** 2026-09-02
- **Baseline:** UNBASELINED — `SM-D*` PROPOSED; do not implement as ratified
- **Focus:** P2 surrogate wire = `go-agent/docs/p2-wire-assumptions.md` until backend `contracts/`; no product Go code until walking skeleton

## Project in one paragraph
Solution Manager is a config/governance plane for Solace managed integrations (not a deploy plane). Thin CP governs; DP owns config-of-record and telemetry. Loci: `dp-remote` (DP holds host credentials) and `host-local` (outbound Go agent). Customer CI/CD owns artifacts; SM writes external config + restart. v1 walking skeleton is Docker + `dp-remote` first; host-local agent is step 2. Agent↔DP wire protocol does not exist yet (open item 6).

## Canonical docs
| What | Where |
|---|---|
| Index | `CLAUDE.md` |
| Decisions | `docs/design/DECISIONS.md` |
| Architecture | `docs/design/architecture.md` |
| Review triage | `docs/tasks/codex-review-triage.md` |
| go-agent design | `go-agent/docs/design.md` |
| go-agent decisions | `go-agent/docs/decisions.md` (`AG-D*`) |
| go-agent workplan | `go-agent/docs/workplan.md` |
| Spec review checklist | `docs/checklists/spec-review.md` |

## Active workstreams
| Stream | Status | Next |
|---|---|---|
| Walking skeleton (Docker + `dp-remote`) | Platform gate; not go-agent-owned | Accept E2E before any go-agent code |
| go-agent docs | DRAFT design + `AG-D1`…`AG-D6` + contract-spine workplan | P0 Pass 1 self-review; P1 adversarial; P2 `.proto` with backend |
| Agent↔DP protocol | **Surrogate in force** — `go-agent/docs/p2-wire-assumptions.md` via `p2-baseline.md` until backend `contracts/` | Backend replaces; open item 6 still formally open |
| Implementation plan 001 | Not started | After P1–P3; then `docs/plans/go-agent/001-host-local-vertical-slice.md` |

## Blocking open items (agent-relevant)
- Open item **6** — agent↔DP `.proto` + transport state machine
- Open item **8** / **D** — ratify `SM-D1`…`SM-D28` (+ capability limits in architecture §13)
- Open item **14** / **C** — Solace Go API maturity (`solace_event`; phase 2 only — `AG-D5`)
- Open item **4** / **E** — K8s GitOps writer (SM-D25); blocks deep K8s apply, not Docker host-local 001
- `AG-D2` — persist layout + renew backoff still TBD until proto exists

## Hard constraints (do not violate)
- **I1** no MI development · **I2** verify external facts (WebFetch) · **I3** immutable decision IDs · **I4** `SM-D*` PROPOSED · **I5** server-side tenant isolation · **I6** secrets never in config storage · **I7** no CP↔DP import/join
- go-agent parallelism **B**: paper (review + proto) OK now; **no product code** until skeleton accepted
- Design lives under `go-agent/docs/` (not `docs/design/go-agent/`)

## Session log (newest first, max 10)

### 2026-08-26 — go-agent docs + workplan + agent-md skill
- **Done:** Rewrote `go-agent/docs/design.md` + `decisions.md` (`AG-D1`…`AG-D6`); added `go-agent/docs/workplan.md` (contract-spine P0–P5); created project skill `.cursor/skills/agent-md-context/`; seeded this `agent.md`
- **Decided:** Workplan approach = contract-spine; parallelism = paper-only until skeleton; first slice = enroll→lease→apply→report→Cache HTTP scrape; skill = project, path `go-agent/agent.md`, explicit invoke only, whole-monorepo scope
- **Files touched:** `go-agent/docs/*`, `go-agent/README.md`, `docs/design/README.md`, `.cursor/skills/agent-md-context/SKILL.md`, `go-agent/agent.md`
- **Next:** P0 Pass 1 self-review on go-agent design/decisions; GitHub Issues for P1 review + P2 proto; book backend for proto session; do not scaffold Go module yet
