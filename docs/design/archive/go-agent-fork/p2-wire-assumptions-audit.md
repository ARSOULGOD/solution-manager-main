# Audit — `p2-wire-assumptions.md`

**Date:** 2026-09-01  
**Subject:** [`p2-wire-assumptions.md`](p2-wire-assumptions.md)  
**Auditor stance:** adversarial / brutal honesty (project review culture)  
**Verdict:** Useful as a **backend workshop agenda**. Unsafe as something to implement or to close `AG-D2` against.

---

## Summary

The document correctly refuses to invent an authoritative `.proto` under `contracts/` and mostly surfaces the SM-D28 authority states. It fails as an implementable wire sketch: the `DECIDED` tag launders PROPOSED decisions, enroll/report-credential bootstrap is underspecified, secrets/render locus is confused, admission ceilings are missing from the wire, and post-lease recovery has no desired-state re-fetch. Several CRITICAL gaps are already latent in §7’s handoff list but are not elevated in the body.

**Do not** codegen, lock `AG-D2`, or treat this file as P2 “done.”

---

## CRITICAL

### C1 — `DECIDED` launders PROPOSED into “settled”

**Where:** §1 Legend (“LOCKED or PROPOSED … DECIDED”).

**Problem:** SM-D24 / SM-D28 are **PROPOSED** (`I4`). Tagging them `DECIDED` invites implementers to ship against unratified law.

**Fix:** Split tags: `LOCKED` · `PROPOSED` · `ASSUMED` (and `DEFERRED` where needed). Never call PROPOSED “DECIDED.”

---

### C2 — Enroll bootstrap authentication hand-waved

**Where:** §2 unary `Enroll`; §4.1 `EnrollRequest`.

**Problem:** Enroll before client cert is a reasonable pattern, but the doc never states how that RPC is authenticated (enrollment token only? server TLS only? pinned DP cert?). That is the one moment an attacker cares about.

**Fix:** Explicit ASSUMED (or PROPOSED) bootstrap trust model: token presentation, server authentication, and failure modes.

---

### C3 — Report credential has no wire shape

**Where:** §4.2 issues report-only credential; §4.7 / §5 use “untrusted report.”

**Problem:** SM-D28’s post-expiry/revoke observability depends on a **separate** report-only credential. The doc never says how `ReportActual` presents it (second mTLS cert? bearer? gRPC metadata?). Unimplementable and security-sensitive.

**Fix:** Specify credential type, presentation on report/telemetry RPCs, and revocation alignment with `revocation_epoch`.

---

### C4 — Secrets / render locus on `GenerationOffer` confused

**Where:** §4.5 “Secret values in payload | DECIDED | Resolved at write”; checklist §7 item 4.

**Problem:** Mixes I6 (DP never *persists* resolved secrets) with an unspoken ASSUMED that **plaintext secrets may cross agent↔DP**. Who renders (DP vs agent) and whether the offer carries refs vs values is the load-bearing security choice — buried in the checklist, not owned in the sketch.

**Fix:** Explode into an explicit ASSUMED with threat notes; do not label it DECIDED.

---

### C5 — `verb_ceiling` / admission inputs missing from the wire

**Where:** SM-D28 admission control; §4.6 / §5 `ADMISSION_BLOCKED`.

**Problem:** Admission needs platform verb ceilings + margin vs `expires_at`. No message carries ceilings, margin, or “remaining lease vs next verb.” Agent cannot implement `ADMISSION_BLOCKED` from this doc alone.

**Fix:** ASSUME how ceilings are distributed (enroll, lease update, or generation step metadata).

---

### C6 — No desired-state re-read after lease recovery

**Where:** SM-D28 `LEASE_EXPIRED` → `ACTIVE` (“re-reads desired state before applying”); header claims usefulness for `AG-D2`.

**Problem:** Only push `GenerationOffer` exists. No pull/snapshot/cursor. Crash resume similarly has no wire generation-cursor — only “local `AG-D2`.” Header oversells unlock of persist layout.

**Fix:** Add ASSUMED fetch/snapshot/cursor kinds; narrow the purpose statement to “negotiation input,” not `AG-D2` completion.

---

## MAJOR

### M1 — `ADMISSION_BLOCKED` dual-homed

Authority state (§5) *and* `ApplyProgress` status (§4.6). Checklist admits ambiguity; tables don’t pick. Will fork implementations.

### M2 — Control + telemetry on one bidi is a silent architecture bet

High-volume `TelemetryBatch` beside lease/apply implies head-of-line blocking or serious flow-control. Checklist §7 item 7 asks; kind catalogue presents same-stream as default. Mark ASSUMED; prefer separate ingest unless justified.

### M3 — Sequence diagram overstates push

`GenerationOffer` every steady-state loop reads like continuous push. B/D1 is pull-shaped with “new generation available.” Need notify vs full payload, and accept/nack before apply, or overlapping generations race.

### M4 — Multi-replica (scope C) half-finished

Both `ReplicaProgress` and “per-replica rows in `ReportActual`.” No stage-barrier / restart-wave messages. Insufficient for SM-D15 `CONFIG_DIVERGED`.

### M5 — Lease claim block stale vs dual epochs

Copied SM-D28 snippet shows singular `revocation_epoch`; §4.4 ASSUMEs two epoch fields. Readers won’t know which wins.

### M6 — Capability vocabulary open-ended

B/D10 needs a closed, versioned set. Free strings (`local-collector-http`, …) will rot. ASSUME a closed enum.

### M7 — `Error` kind has zero fields

Catalogue entry only; no field sketch.

### M8 — No DP-forced teardown / drain

Only agent→DP `Teardown`. Revoke ≠ orderly drain.

### M9 — DP address / trust bootstrap unspecified

Out-of-band install is fine — say so explicitly or agents invent it.

---

## MINOR / hygiene

| ID | Issue |
|---|---|
| N1 | `DECIDED deferred` is a confusing status — use `DEFERRED` |
| N2 | `(001)` marking inconsistent (column vs section headers) |
| N3 | No max message size, compression, telemetry ACK, keepalive vs lease renew |
| N4 | Pre-flight only as `PREFLIGHT_FAILED` string — no structured competing-source report |
| N5 | Purpose overclaim: good for negotiation; not sufficient for `AG-D2` or codegen |

---

## What is actually good

- Correct refusal to put a fake `.proto` in `contracts/`.
- SM-D28 state list mostly present (including recovery paths).
- §7 handoff questions are the right questions (several CRITICAL items are latent there).
- Out-of-scope list (`solace_event`, HTTP/1.1 fallback, `dp-remote`) matches architecture.
- Non-binding banner is honest; the body sometimes is not (see C1).

---

## Recommended fix order (if revising the assumptions doc)

1. Retag LOCKED / PROPOSED / ASSUMED / DEFERRED (C1, N1).
2. Enroll auth + report-credential presentation (C2, C3).
3. Secret/render locus on `GenerationOffer` (C4).
4. Admission ceilings on the wire (C5).
5. Desired-state re-fetch / cursor after lease recovery (C6).
6. Resolve dual-homed `ADMISSION_BLOCKED`; split or justify telemetry transport (M1, M2).
7. Tighten multi-replica, capabilities enum, `Error`, teardown, bootstrap (M4–M9).

---

## Disposition

| Finding | Suggested (2026-09-01) | Status (2026-09-02) |
|---|---|---|
| C1–C6 | **Fix now** in `p2-wire-assumptions.md` | **Addressed** in assumptions (retag; enroll bootstrap; report bearer; secret locus ASSUMED; ceilings on `LeaseUpdate`; `DesiredStatePull`/`Snapshot`; purpose = negotiation input) |
| M1–M9 | **Fix now** or route to backend workshop | **Addressed** in assumptions (`ADMISSION_BLOCKED` apply-only; separate telemetry channel ASSUMED; replica coordination signals; dual epochs; closed capability enum; `Error` sketch; `DrainRequest`; OOB DP bootstrap). Residual inconsistency risk: see audit follow-ups below |
| N1–N5 | **Fix now** (cheap) or accept as editorial | **N1 addressed** (DEFERRED tag). N2–N5 partially / editorial |

**P2 “done when” unchanged:** versioned `.proto` under contracts still supersedes all assumptions. Remediation ≠ protocol specified.

### Follow-ups after remediation (honest residuals)

| ID | Residual |
|---|---|
| R1 | Sequence diagram in assumptions still shows `TelemetryBatch` on the lease steady loop — contradicts “separate telemetry channel” |
| R2 | §7 checklist item 5 still asks whether `ADMISSION_BLOCKED` is lease vs apply — body already chose apply status |
| R3 | §4.7 still allows “per-replica rows” while §4.9 owns multi-replica — dual path not fully killed |
| R4 | Assumptions changelog / header date may lag the remediation pass |
| R5 | Audit banner on assumptions still quotes pre-fix verdict verbatim — accurate as history, easy to misread as current quality |

---

## Changelog

| Date | Change |
|---|---|
| 2026-09-01 | Initial adversarial audit of `p2-wire-assumptions.md`. |
| 2026-09-02 | Record remediation dispositions after go-agent owner applied audit fix order; note residuals R1–R5. |
