# FE-155 · Register the decisions — Report

Final task of [plan 129](../129-filemesh-source-sink-connectors.md). Registers `UI-D26` and `FE-D55`, and opens
the DECISIONS.md item the spec's §14 finding was missing.

---

## 1 · What was needed

Per `I3`, a spec proposing decision IDs is not itself the register. `UI-D26` and `FE-D55` needed formal entries
before this plan could be considered done, and spec §14's dialect finding needed an open item — it had been
recorded only in a report and two lessons, not in the one place `DECISIONS.md` says open items live.

## 2 · What changed

| Change | File |
|---|---|
| `UI-D26` row + full section, changelog entry | `docs/design/dynamic-ui/decisions.md` |
| `FE-D55` row + full section | `docs/design/frontend/decisions.md` |
| Open item 37 (the `frontend/contracts/packages/` gate) | `docs/design/DECISIONS.md` |

**IDs confirmed free before use**, not assumed: `grep`'d the max of each family (`UI-D25`, `FE-D54`) and the max
open-item number (36) immediately before writing, per plan Task 155 Step 1.

`UI-D26`'s section states its evidence, its relation to `UI-D1`'s existing File-Mesh-component-types language
(same fact, narrower claim — `UI-D1` says variants are *expressible* as components; `UI-D26` says *which axis*
maps to *which* mechanism when the underlying document is shared), and flags the two items still open before
ratification (spec §11: the single-location-field assumption, and the absent-subtree-is-disabled assumption).

`FE-D55`'s section states the "every consumer must go through this function" obligation explicitly — the
Governance finding from `FE-151` — so a future consumer added without reading `FE-151`'s report still has the
rule in the one place decisions live.

Open item 37 supersedes nothing in item 36; both stay open, and item 37's entry says what `FE-149` already
closed (all nine packages pass; the interim "author flat" rule is superseded) versus what remains (a CI gate; the
authored-vs-normalized question, which is the architect group's).

## 3 · What deliberately did not change

- **Ratification status.** Both new decisions are `PROPOSED`, consistent with every other `UI-D*`/`FE-D*` entry.
  This plan does not ratify anything — ratification is the architect group's, per `CLAUDE.md`'s family order.
- **`UI-D1`, `UI-D2`, `UI-D4`, `UI-D24`.** Cited as consistent-with in `UI-D26`'s section; none is edited, per `I3`.

## 4 · Evidence

Link check run over all three files after editing: every relative link resolves. No placeholder text
(`TBD`/`TODO`) in either new section.

## 5 · Also corrected in this task

Plan 129's own field tables for Tasks 152/153 (written before authoring) listed sample-observed values as
`default`s for `adapter_id` and the `file_owner_permissions` override fields, and proposed `enum` for two fields
authored as `string`. As executed, no default is authored for an identifier-shaped field (an `I2`/environment
concern), and the two fields stayed `string` for lack of a confirmed value set. The plan is corrected to match
what was built, with the reasoning, rather than left to silently disagree with the code.

## 6 · Plan 129 status

All seven tasks (`FE-149`–`FE-155`) complete. Final verification (full suite, typecheck, lint, nine-package
validation, the manual Designer/Governance checks, the no-File-Mesh-in-React grep) is recorded in the plan's own
"Final verification" section and this session's closing summary.
