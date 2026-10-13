# FE-048 · FieldRow — Report

## What I implemented

`FieldRow` (`UI-D4`, `UI-D16`, `UI-D17`, `I6`; design §10, §18; UI §A.8.4) — the one component every
Designer field renders through, switching on `field.controlKind` only, never on a solution or field name.

| File | Summary |
|---|---|
| `frontend/src/features/designer/FieldRow/FieldRow.tsx` | Renders: discriminator hint (`InfoTip`) · rendered path (mono, gated by `showRenderedPath`) · `Default`/`Modified` indicator · the control (via `controlRegistry`, adapting `ViewField` into each control's real prop shape) · the validation-message list (block/warn, warn appending the Solace-recommendation caption). |
| `frontend/src/features/designer/FieldRow/FieldRow.types.ts` | `FieldRowProps` — `field: ViewField`, a separately-supplied live `value`/`onChange`, `disabled?`, `showRenderedPath?`. |
| `frontend/src/features/designer/FieldRow/{FieldRow.test.tsx,FieldRow.a11y.test.tsx,index.ts}` | 16 tests: rendering, discriminator hint, rendered-path gating, virtual-field parity, Default/Modified, required asterisk, block/warn validation + `aria-invalid`/`aria-describedby`, disabled state, and one instance of every `controlKind`, in both colour modes. |
| `frontend/src/shared/i18n/messages.en.ts` | 5 new keys under a "FieldRow (task 048)" block. |

## The contract gap this task ran into, and what I did about it

Before writing `FieldRow`, I checked what `ViewField` (FE-046) actually carries against what `UI §A.8.4`
needs: `label`, a `discriminator` flag, `renderedPath` (to detect a `virtual` field), and a `value`
structured enough to tell `Default` from `Modified`. **None of these existed on `ViewField`** — FE-046 pinned
itself literally to `design.md` §6.5's compressed prose shape (`key, controlKind, value, options, enabled,
visible, masked, advanced, helperText, validations`), which omits them entirely.

I verified this wasn't a misreading by checking three independent sources against each other before touching
anything:
1. `docs/plans/frontend/implementation.md` §6.0.2's own `VmField` sketch — has `uid`, `renderedPath`,
   `section`, `discriminator`, `label`, and a `FieldValue` union (`value(v,source) | unset | secretRef |
   secretUnset`).
2. UI §A.8.1/A.8.4's prose — names the same fields.
3. The actual reference prototype, `docs/design/frontend/reference/app/designer_fields.jsx` and
   `designer_data.jsx` — a hand-built `Vv`/`F()` factory producing exactly that shape, field-by-field, for
   real Cache and File Mesh data.

I asked the user how to resolve this (a shared, cross-language contract, `UI-D15` still `PROPOSED`) rather
than deciding unilaterally. Chose: **extend `ViewField` now.**

### What actually changed in `contracts/view-model/`

- `FieldDeclaration` gained `label?: string` (authored — the reference hand-picks labels that don't follow
  from the key mechanically, e.g. `max-size` → "Max entries"; never humanized) and `virtual?: boolean`
  (authored; the only source of "no rendered path").
- `Validation` gained `code: string` (`REQUIRED`, `ABOVE_RECOMMENDED`, per `design.md` §7.5's own examples) —
  `FieldRow`'s required-asterisk keys off `validations.some(v => v.code === 'REQUIRED')`, matching the
  reference exactly, rather than a separate boolean nothing else produces yet.
- `ViewField` gained `label: string` (falls back to `key`), `renderedPath: string | null`, `discriminator:
  boolean`, and `value: ViewFieldValue` where `ViewFieldValue = {kind:'value',value,source:'default'|'draft'}
  | {kind:'unset'} | {kind:'secretRef',ref} | {kind:'secretUnset'}` — the same shape `SecretField`'s own
  `SecretFieldState` (task 023) already expects.
- `project()` (`projectField`/`projectValue`) now computes all four: `label = field.label ?? field.key`;
  `renderedPath = field.virtual === true ? null : \`${configPath}.${field.key}\``; `discriminator =
  field.discriminator === true`; and `value` from whichever of the *raw* `DraftValues` entry or the field's
  `default` resolved it (checked in that order against the **raw**, not defaults-applied, map — the
  defaults-applied map has already erased which one supplied the value, which is exactly the distinction
  `Default` needs).
- All 11 existing fixtures updated to the new `expected` shape; 2 new fixtures added
  (`label-authored-and-fallback.json`, `virtual-field-no-rendered-path.json`) proving the two behaviours
  FE-046 had no obligation to prove. 13/13 fixtures pass.
- `contracts/fixtures/README.md`'s "`null` vs. absent" pinning note updated for the new `{kind: ...}` shape.

### Deliberate limitation: `secretRef` is declared, never produced

`ViewFieldValue` includes `secretRef`/`secretUnset` because the shared contract (and `SecretField`'s existing
`SecretFieldState`) needs that vocabulary. But `project()` **always** emits `secretUnset` for a `secret: true`
field, never `secretRef` — distinguishing a genuine reference token from an accidentally-raw secret string in
`DraftValues` needs a contract change to `DraftValues` itself (today a secret field's draft entry is just a
`string`, indistinguishable from plaintext at the type level), and getting that wrong would defeat exactly
what the `secret-masked-everywhere` fixture's obligation-7 leak-scan exists to catch. I left this as a
documented gap (in `types.ts`'s doc comments and here) rather than guessing at a `DraftValues` redesign that
wasn't part of what was approved.

### Deliberate limitation: no `columns` on `ViewField`

`table`'s `FieldControl` branch always calls `TableControl` with no `columns` — `FieldDeclaration.columns`
isn't threaded through `project()` (same gap FE-047's own report already flagged: "no `table`-typed field
example yet" in FE-046's fixtures). `TableControl` already renders its package-declares-columns placeholder
for this; not something this task needed to fix to satisfy its own acceptance criteria.

## Rulings

1. **`FieldRow`'s header does not re-render the field's label or a required asterisk as separate text.**
   The reference prototype's `FieldRow` does (`<span className="fld-lbl">{field.label}</span>` next to a raw,
   label-less `<input aria-label=...>`), because the prototype's controls are unstyled raw HTML with no label
   chrome of their own. Our real controls (`TextField`/`NumberField`/`Select`/`Switch`/`DurationControl`,
   tasks 012/047) already render a proper, accessible, required-aware MUI label — passing `field.label` a
   second time through `FieldRow`'s own header would visually and semantically duplicate it. `FieldRow`'s
   header carries only what the controls *can't* express: the discriminator hint, rendered path, and
   `Default`/`Modified`.
2. **`Default` and `Modified` are computed as mutually exclusive**, matching the reference's own
   `isDefault = ... && !changed` — not `field.value.source === 'default'` alone. Caught by a failing test I
   wrote against the reference's own formula before fixing the component, not designed in correctly the first
   time.
3. **A block-tier validation's message is passed into the control's own `error` prop** (not just listed
   separately), so the already-verified `aria-invalid`/`aria-describedby` wiring in every `shared/ui` control
   (task 012) does the accessibility work, rather than `FieldRow` inventing new, untested `aria-*` wiring of
   its own. `FieldRow` additionally renders the *full* validation list (block and warn) as its own row, per UI
   §A.8.9/§A.8.4 — in the blocked case this means the block message legibly appears twice (once as the
   control's native error text, once in the list); accepted as the cost of reusing proven a11y wiring instead
   of duplicating it.
4. **`isRequired` is derived from `field.validations.some(v => v.code === 'REQUIRED')`**, not a new boolean
   on `ViewField`. `FieldDeclaration.required` exists, but nothing currently threads it into the view model —
   task 053 (`UI-D16`, live validation) is what will actually populate `validations`, including `REQUIRED`
   entries, and deriving the asterisk from that array is exactly what the reference itself does. Until task
   053 runs, `validations` stays `[]` and no asterisk shows — correct (there's no requiredness signal yet),
   not a regression.
5. **`controlRegistry` entries are invoked through a per-`controlKind` adapter that builds each control's
   real prop shape**, not a shared `ViewField`-shaped prop — this is what FE-047's own report named as this
   task's job. A local `CommonControlProps` (label/required/helperText/error/disabled) is declared in
   `FieldRow.tsx` itself rather than imported from `@shared/ui/internal/FieldProps`, following FE-047's own
   ruling on that internal, unbarrelled type.
6. **`list`/`map`/`table` values are narrowed defensively** (non-string list items, non-string map values,
   and non-object table rows are dropped, never coerced) — the package schema only ever declares
   `List<String>`/`Map<String,String>` for these kinds (`design.md` §7.3), so anything else in `DraftValues`
   would be a data bug upstream, not something to render as `"[object Object]"`.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run --project contracts                       # 13/13 fixtures passed (11 updated + 2 new)
npm run lint                                              # clean
npm run typecheck                                         # clean
npx vitest run src/features/designer/FieldRow             # 2 files, 16 tests, all passed
npx vitest run                                            # full suite: 159 files, 757 tests
npm run build                                             # production build succeeds
```

One full-suite run showed 3 `DataTable` a11y tests failing with `jest-axe`'s "Axe is already running" and one
`InstancesPage` 320px-reflow timeout — both **pre-existing parallelism flakes**, already documented in the
FE-046 report for the same `InstancesPage` file. Re-ran full suite clean (757/757); re-ran the flaking
directory standalone (`src/features/inventory/instances`, 59/59) to confirm — not caused by this task's
changes, and no file this task touched appears in either failure.

## Status

DONE — implemented, tested (757/757 on a clean full run, 16/16 new, 13/13 fixtures), lint-clean,
typecheck-clean, zero `jest-axe` violations across every `controlKind` in both colour modes, production build
succeeds. Extended a previously-DONE task's contract (FE-046) with the user's explicit sign-off before
touching it — flagged here rather than reversed silently. Not yet closed on GitHub — pending review.
