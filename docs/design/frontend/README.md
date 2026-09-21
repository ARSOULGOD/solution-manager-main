# frontend

The console: schema-driven renderer, plane-split API clients, version/restart UX, declared-inventory management.

**Stack:** React + Vite + MUI · **Status:** design drafted (`FE-D*`); **no code yet**. **UNBASELINED.**

## Before writing code here

This component does not own the platform architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../DECISIONS.md`](../DECISIONS.md) |
| Overall architecture | [`../architecture.md`](../architecture.md) |
| **This component's design** | [`design.md`](design.md) |
| This component's decisions | [`decisions.md`](decisions.md), ID family `FE-D*` |
| **Visual specification of record** — *proposed, `FE-D25`* | [`reference/`](reference/) — the approved `sm-v2` mockup. **Where the build and the reference disagree, the reference wins** (per `FE-D25`, which is `PROPOSED`, not ratified — `I4`) |
| **State vocabulary — read before building anything stateful** | [`reference/STATE-VOCABULARY.md`](reference/STATE-VOCABULARY.md) — **35 entries in eight tables plus one ordered ladder, across five files**, transcribed verbatim with `file:line` |
| Palette provenance | [`reference/PALETTE-PROVENANCE.md`](reference/PALETTE-PROVENANCE.md) — what is verified, what is design-authored, what is unconfirmed |
| Dynamic UI | [`../dynamic-ui/design.md`](../dynamic-ui/design.md) |
| Dynamic Metrics | [`../dynamic-metrics/design.md`](../dynamic-metrics/design.md) |
| Approved plan | `../../plans/frontend/NNN-<slug>.md` — **none yet** |
| Setup checklist | [`../../checklists/new-module.md`](../../checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` / `UI-D*` / `DM-D*` / `FE-D*` are PROPOSED. Do not start implementing as though settled. See [`../../tasks/codex-review-triage.md`](../../tasks/codex-review-triage.md).

⚠️ **Open item 19b is narrowed, not closed.** The palette is verified against Solace product source, but 19b assigns the visual reference to the **designer**, so it remains unconfirmed. Theme work requires the §24 gate-2 waiver at [`GATE-2-WAIVER.md`](GATE-2-WAIVER.md), which is **unsigned**. Visual-regression baselines stay blocked regardless.

**If the design is wrong, raise it — do not invent architecture in code.** Component-level decisions get an ID in the `FE-D*` family and are recorded in [`decisions.md`](decisions.md), not in code comments.
