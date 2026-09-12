# Go-agent — sequenced workplan (contract-spine)

**Date:** 2026-08-26  
**Status:** DRAFT — workflow for moving from component design to first vertical slice  
**Component:** `go-agent`  
**Approach:** Contract-spine (approved in brainstorming)  
**Parallelism:** Paper-only until walking skeleton — design review + `.proto` with backend; **no agent product code** until Docker + `dp-remote` skeleton is accepted  

**Depends on:** [`design.md`](design.md), [`decisions.md`](decisions.md) (`AG-D*`), [`../../docs/design/DECISIONS.md`](../../docs/design/DECISIONS.md), [`../../docs/design/architecture.md`](../../docs/design/architecture.md) §15  

---

## 1. Goal and hard rules

**Goal:** Move from today’s DRAFT component docs to a first **host-local** vertical slice — enroll → mTLS → lease → one config apply → report-actual → Cache HTTP scrape — without writing agent product code until the platform walking skeleton is accepted.

**Hard rules:**

| Rule | Why |
|---|---|
| `SM-D*` / component design stay **PROPOSED** until ratification | `I4`; open item D |
| **No go-agent implementation code** until walking skeleton (Docker + `dp-remote`) accepted end-to-end | Architecture §15; chosen parallelism B |
| **Do not invent the wire schema alone** — agent↔DP `.proto` is joint with backend | Design §12 open item A; contracts single-writer |
| Honour `AG-D1`…`AG-D6` | One binary dual-role; persist layout deferred; K8s Lease later; atomic writes; Cache HTTP only in v1; libraries not locked in design |
| Out of first slice | K8s HA/DaemonSet depth, `solace_event`, auto cert rotation, HTTP/1.1 fallback, `scheduled`, fleet self-upgrade |

**Workplan exit:** An **APPROVED** plan at `docs/plans/go-agent/001-host-local-vertical-slice.md` that backend can implement DP-side against, and that go-agent executes only after the skeleton gate.

---

## 2. Phases

| Phase | Owner work | Waits on | Done when |
|---|---|---|---|
| **P0 — Spec harden** | Pass 1 self-review of `go-agent/docs/*` via [`docs/checklists/spec-review.md`](../../docs/checklists/spec-review.md); fix gaps; open GitHub Issues linked to SM-D24 / SM-D28 / `AG-D*` | — | Pass 1 checked; holes listed, not silent |
| **P1 — Adversarial review** | Brief independent reviewer (Pass 2); triage: fix now / route / accept as limit | Reviewer | Dispositions recorded; design still DRAFT but review-clean |
| **P2 — Wire contract** | **Surrogate wire in force:** [`p2-baseline.md`](p2-baseline.md) treats [`p2-wire-assumptions.md`](p2-wire-assumptions.md) as the working `.proto` stand-in until backend publishes under `contracts/`. Audit: [`p2-wire-assumptions-audit.md`](p2-wire-assumptions-audit.md). Real protocol still **unspecified** in contracts tree | Backend DP owner (to replace) | Authoritative `.proto` in `contracts/`; baseline superseded |
| **P3 — Mechanism gaps** | Close `AG-D2` (persist layout, renew backoff/jitter) against surrogate wire; Cache introspection recipe without `I1` breach. **Doc:** [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) | Surrogate P2 in force ([`p2-baseline.md`](p2-baseline.md)) | `AG-D2` layout decided (ASSUMED); open item F sketched for Cache |
| **P4 — Implementation plan** | Write [`docs/plans/go-agent/001-host-local-vertical-slice.md`](../../docs/plans/go-agent/001-host-local-vertical-slice.md) via writing-plans | P1–P3 (skeleton may still be in flight) | Plan **APPROVED**; checkable tasks; explicit out-of-scope. **Status now: DRAFT written** |
| **P5 — Code** | Execute 001: scaffold → enroll/session/lease → apply/report → HTTP collector | **Skeleton accepted** + P4 approved + contracts consumable | Design §11 success criteria for this slice |

**Parallel (not go-agent-owned, blocks later work):** platform walking skeleton; `SM-D*` ratification; K8s GitOps writer model (SM-D25) — needed before deep K8s apply, **not** before Docker host-local 001.

```text
P0 → P1 ─┐
         ├→ P2 → P3 → P4 ──┐
skeleton ──────────────────┴→ P5 (code)
```

---

## 3. First vertical slice (001 scope)

**Runtime posture:** Docker host-local, Cache, single node, both roles in one process (`AG-D1`).

**In:**

1. Enroll via DP→CP (single-use target-bound token → CSR → cert)
2. Agent-initiated mTLS session to DP; lease `ACTIVE` + renew at 50% elapsed
3. One generation → launch-spec pre-flight → atomic config write (`AG-D4`) → restart verb
4. report-actual: file hash, restart-observed, version observe (honest `VERSION_UNKNOWN` if needed)
5. Telemetry: scrape Cache actuator/HTTP KPIs → push normalized samples with gap markers (`AG-D5`)
6. Lease expiry / revoke paths that stop apply and mark reports untrusted (SM-D28)

**Out of 001:** K8s Lease HA (`AG-D3` → later plan), DaemonSet cardinality tests, multi-replica stage-everywhere, `solace_event`, auto cert rotation, full secret-backend integration beyond on-target `0600` write, GitOps ConfigMap writer.

**Backend must deliver for 001 (from P2):** enroll relay, cert/trust bundle, lease mint/renew/revoke, generation delivery, report-actual + telemetry ingest matching the proto. DP lease **store** is backend; agent holds local persist only (`AG-D2`).

**Success:** design.md §11 host-local criteria for this slice — not “full agent product.”

---

## 4. Tracking, risks, immediate actions

### Tracking

| Artifact | Home |
|---|---|
| Design / `AG-D*` / rationale | `go-agent/docs/` + cross-cutting `docs/design/` |
| Status / assignment | GitHub Project “Solution Manager” — Issues for P0–P5 |
| Links | Issue → decision ID (SM-D28, open item 6, `AG-D2`, …); never decision → issue number in prose |
| Approved plan | `docs/plans/go-agent/001-host-local-vertical-slice.md` (after P4) |

### Risks

| Risk | Mitigation |
|---|---|
| Proto stalls → agent path idle | Time-box joint backend+agent workshop; message kinds + state machine first, fields second |
| Coding early against a divergent mock | No product code until skeleton (parallelism B); contract + plan only before P5 |
| Design treated as ratified | Keep DRAFT / UNBASELINED banners; ratification is open item D |
| Scope creep (K8s / `solace_event`) | 001 exit criteria fixed in §3; later plans for `AG-D3` / phase 2 |

### Immediate next actions (this week)

1. Review [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) (P3).
2. Review/approve [`docs/plans/go-agent/001-host-local-vertical-slice.md`](../../docs/plans/go-agent/001-host-local-vertical-slice.md) (P4 DRAFT).
3. Do **not** execute P5 / scaffold until walking skeleton accepted + plan APPROVED.

---

## 5. What this document is not

- Not a replacement for [`design.md`](design.md) (component architecture).
- Not an implementation plan — that is P4 (`docs/plans/go-agent/001-…`).
- Not the authoritative `.proto` — surrogate is P2 ([`p2-baseline.md`](p2-baseline.md)); real contract is backend `contracts/`.

---

## Changelog

| Date | Change |
|---|---|
| 2026-08-26 | Initial contract-spine workplan from brainstorming (goal C, parallelism B, approach 1). |
| 2026-09-02 | P2 surrogate wire; P3 [`p3-mechanism-gaps.md`](p3-mechanism-gaps.md) drafted. |
