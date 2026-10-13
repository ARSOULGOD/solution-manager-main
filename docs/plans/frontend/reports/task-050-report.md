# FE-050 · Section navigation and jump — Report

## What I implemented

`SectionNav` + `useSectionJump` (design §10, UI §A.8.7) — the left rail that jumps to a Designer section:
clicking an entry expands its section and moves both scroll position and keyboard focus to its first
applicable field.

| File | Summary |
|---|---|
| `frontend/src/features/designer/SectionNav/SectionNav.tsx` | The rail. One button per `ViewSection`: title, and either a blocking dot, a warning dot, or the applicable-field count — mutually exclusive, blocking wins, mirroring `SectionCard`'s own rule. Each button's `aria-label` carries the full status phrase (`"{title} — {status}"`) since the compact dot alone doesn't. |
| `frontend/src/features/designer/SectionNav/SectionNav.types.ts` | `SectionNavProps` — `sections: readonly ViewSection[]`, `onJump: (index: number) => void`. |
| `frontend/src/features/designer/SectionNav/useSectionJump.ts` | The behaviour: owns the `expanded` index set (the same controlled shape `SectionCard` takes), plus `jumpToSection(index)` — expands the target, then in a `useEffect` (after the expand has actually committed to the DOM) scrolls its first applicable field's row into view and focuses the first focusable control inside it. |
| `frontend/src/features/designer/SectionNav/{SectionNav.test.tsx,SectionNav.a11y.test.tsx,useSectionJump.test.tsx,index.ts}` | 8 tests: per-section entries and `onJump` index, count-vs-dot rendering, blocking-over-warning precedence, accessible label text; an integration test wiring `SectionNav` + `useSectionJump` + real `SectionCard`s proving a jump both scrolls (`scrollIntoView` called) and focuses (`document.activeElement`); a no-op case when the target section has no applicable field. A11y: 3 entry kinds × 2 colour modes, zero violations. |
| `frontend/src/features/designer/SectionCard/sectionFieldGroups.ts` (+ `.test.ts`) | Extracted the applicable/all-advanced/normal/advanced/block/warn arithmetic out of `SectionCard.tsx` into `groupSectionFields()`, so `SectionNav`'s dot logic and `SectionCard`'s chip logic read the exact same rule instead of two hand-copies that could drift. `SectionCard.tsx` refactored to call it; its own 10 tests still pass unchanged. |
| `frontend/src/shared/i18n/messages.en.ts` | 1 new key (`sectionNavLabel`, the rail's landmark label). Per-entry status text reuses `sectionCardBlockCount`/`sectionCardWarnCount`/`sectionCardFieldCount` from task 049 rather than duplicating them. |

## The contract gap this task ran into, and what I did about it

UI §A.8.7 describes the rail as "a left rail listing each **component** (multi-component only) and its
sections" — a two-level list, component header above section entries. I checked what `ViewSection` (FE-046)
actually carries against that: `title`, `order`, `fields`, `actions` — **no component-instance identifier at
all**. I verified this wasn't a misreading using the same three-source check FE-048's report used:

1. `types.ts`'s own `ViewSection` interface — no `instanceId`/`componentType` field.
2. `project()`'s internal bucketing key (`${instanceId}::${sectionKeyFor(field)}`) — proves the engine *has*
   the identity while building each section, but never emits it on the `ViewSection` it returns.
3. The `multi-component-selection` fixture (`contracts/fixtures/view-model/multi-component-selection.json`) —
   two different component instances (`source-1`, `source-2`) of the *same* component type project two
   `ViewSection`s that are identical in `title` ("General") and in `renderedPath` prefix ("source-grid." —
   `configPath` is a per-component-**type** property, not per-instance), distinguishable only by the values
   inside their fields. This rules out even a fallback like "parse the component name from a field's rendered
   path" — the data needed to group correctly isn't recoverable from what `project()` emits today, not even
   indirectly.

Unlike FE-048's gap, this one **doesn't block any acceptance criterion of this task** — both criteria ("jump
moves focus, not only scroll", "dots reflect live validation") hold for a flat list of sections, and the only
scenario that needs component grouping (a multi-component solution, File Mesh) isn't assembled anywhere in the
app yet; v1's target is Cache, which is single-component. So rather than extending the shared contract
pre-emptively for a scenario nothing exercises yet (and which `FE-063`, Composer canvas, will need to solve
for real — it needs per-instance identity anyway, for canvas node selection), I left `SectionNav` rendering
the flat list `ViewModel.sections` actually provides, with the gap and its evidence documented in
`SectionNav.tsx`'s own module doc and in `SectionNav.types.ts`, flagged for whoever assembles multi-component
nav for real. This is a **disclosed limitation**, not a silent one — same standard FE-048 applied to its
`secretRef`/`table`-columns gaps.

## Design decisions, and why

1. **`useSectionJump` defers the scroll/focus to a `useEffect`, keyed by a `jumpToken` counter, not
   `pendingFieldKey` state itself.** First cut kept the pending target in `useState` and called
   `setPendingFieldKey(null)` at the end of the effect to clear it — `eslint-plugin-react-hooks`'s
   `set-state-in-effect` rule flags exactly that pattern (calling `setState` synchronously inside an effect
   body risks cascading renders). Moved the pending target into a `ref` (mutated freely inside an effect,
   no lint concern) and used a separate `jumpToken` state purely to *trigger* the effect. The reason the
   effect has to exist at all, rather than doing the DOM work inline in the click handler: a collapsed
   section's field rows aren't in the DOM yet, so the query for the "first applicable field's row" would find
   nothing until React has committed the re-render the same click's `setExpanded` call causes.

2. **Focus target is found by DOM query on `data-field-row`/`data-field-key`, not a ref registry.** `FieldRow`
   already stamps both attributes on its root (task 048) — reusing them means `useSectionJump` and
   `SectionNav` needed zero changes to `FieldRow` or `SectionCard`. `jumpToSection` only knows a field's
   *key*, so the search is `querySelectorAll('[data-field-row]')` filtered by `dataset.fieldKey`, not a
   templated CSS-attribute selector (avoids needing `CSS.escape` for a value that's ultimately package
   data). The caller supplies `containerRef` — a ref on whatever scrollable element wraps every rendered
   `SectionCard` — the same shape the reference prototype's own `rowRefs`/`formRef` pair fills.

3. **Block/warn dots use `theme.palette.tone`, not MUI's built-in `error`/`warning` palette slots** —
   `Chip`/`Alert` already established that rule (task 013, `FE-D5`: "colour comes from `theme.palette.tone`,
   never a literal"); using MUI's own severity colors here would risk a second, slightly different red/amber
   showing up for the same semantic meaning next to `SectionCard`'s chips.

4. **A bare colour dot never stands alone as the field's only cue** — each nav button's `aria-label` states
   the section title plus the exact same full status phrase `SectionCard`'s header chip would show
   (`"{title} — {status}"`), so a screen-reader user gets identical information to the dot's sighted meaning
   even though the rail's width doesn't have room to show the phrase visually. The applicable-field-count
   fallback (no block, no warn) *is* shown as visible text already, matching `SidebarNav`'s own parenthetical-
   count precedent.

5. **`groupSectionFields()` extracted rather than left duplicated.** `SectionNav`'s dot rule and
   `SectionCard`'s chip rule are the identical computation (applicable fields, all-advanced, block/warn
   counts) — writing it twice risked exactly the kind of two-implementations-drift `UI-D15`'s own rationale
   warns about for `project()`/`projectSeries()`, just at a smaller scale. `SectionCard.tsx`'s existing 10
   tests were re-run unchanged after the refactor to confirm no behaviour moved.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/SectionNav   # 3 files, 14 tests, all passed
npx vitest run src/features/designer              # 23 files, 93 tests, all passed
npx eslint .                                       # clean
npx tsc --noEmit -p .                              # clean
npx vitest run                                     # full suite: 165 files, 780 tests
npm run build                                      # production build succeeds
```

One full-suite run showed the pre-existing `InstancesPage` 320px-reflow test timing out — the same
parallelism flake already documented in the FE-046 and FE-048 reports, in a file this task never touched.
Re-ran clean: 780/780.

## Status

DONE — implemented, tested (780/780 on a clean full run, 14/14 new across `SectionCard`+`SectionNav`, zero
`jest-axe` violations in both colour modes), lint-clean, typecheck-clean, production build succeeds. One
contract gap identified and deliberately deferred (component-instance identity on `ViewSection`, needed only
for multi-component nav grouping) with its evidence recorded here and in-code, rather than extended
unilaterally for a scenario this task doesn't exercise. Not yet closed on GitHub — pending review.
