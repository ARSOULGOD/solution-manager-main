# P2 — Working ASSUMED baseline (surrogate wire contract)

**Date:** 2026-09-02  
**Status:** **SURROGATE WIRE CONTRACT** · ASSUMED · in force until backend DP owner publishes  

> Go-agent treats [`p2-wire-assumptions.md`](p2-wire-assumptions.md) **as the working `.proto` stand-in** for paper planning (and any future codegen only if explicitly approved). This is **not** a file under `contracts/` and does **not** complete workplan P2’s “protocol specified” gate in the ratification sense — it is an operational choice to unblock go-agent while backend is silent.

---

## 1. Decision

| Item | Value |
|---|---|
| In-force wire reference | [`p2-wire-assumptions.md`](p2-wire-assumptions.md) (post-audit remediation) |
| Role | **Surrogate `.proto`** — kinds, fields, states, transport assumptions |
| Authority | Still **ASSUMED**; backend single-writer for real `contracts/*.proto` |
| Supersession | Any backend-published contract **wins immediately**; update or annex this baseline |
| Audit | [`p2-wire-assumptions-audit.md`](p2-wire-assumptions-audit.md) — findings addressed; residuals noted |

---

## 2. What this allows / still blocks

**Allowed now**
- Design agent state machines, persist sketches (`AG-D2` drafts), and plans **against** the assumptions doc
- Cite message kinds/fields from assumptions as if they were the wire API in go-agent docs and Issues
- Backend negotiation: “reject/replace ASSUMED rows” rather than “start from blank”

**Still blocked**
- Claiming P2 **ratified** / SM-D24/SM-D28 ratified (`I4`)
- Checking a fake authoritative `.proto` into `contracts/` without backend ownership
- Go **product** code until walking skeleton accepted (workplan parallelism B) — unless a later explicit decision changes that
- Treating ASSUMED names as forever-stable for field agents without a compatibility window

---

## 3. Rules of engagement

1. Prefer changing **ASSUMED** rows in `p2-wire-assumptions.md` (with changelog) over silent drift in code or plans.
2. Do not invent parallel wire docs; this baseline points at one file.
3. When backend speaks: land their contract under `contracts/`, mark this baseline superseded, diff ASSUMED → actual.

---

## 4. History

| Date | Event |
|---|---|
| 2026-09-02 | Freeze as-is (pre-remediation), choice B |
| 2026-09-02 | Audit remediation applied to assumptions |
| 2026-09-02 | **Proceed with assumptions as surrogate `.proto` until backend docs arrive** |

---

## Changelog

| Date | Change |
|---|---|
| 2026-09-02 | Initial freeze; then rebaseline after remediation; then elevate to surrogate wire contract pending backend. |
