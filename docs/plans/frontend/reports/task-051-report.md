# FE-051 · Field search — Report

## What I implemented

`FieldSearch` + `useFieldSearch` (design §10/§17, UI §A.8.8) — the debounced, flat search over every
Designer field across all sections, labelled `Search N fields…`.

| File | Summary |
|---|---|
| `frontend/src/features/designer/FieldSearch/useFieldSearch.ts` | Owns `query`, and derives `totalFieldCount` (every declared field, visible or not — the reference's own placeholder count) and `hits` (applicable-only matches against `label key renderedPath section`, lower-cased substring, matching the reference `SearchResults`'s own predicate). `isActive` is `query !== ''` — an empty query yields no hits, mirroring the reference's own `{search ? <SearchResults/> : <normal view>}` toggle so a future page that assembles this alongside `SectionNav`/`SectionCard` can hide the normal list while a search is active. |
| `frontend/src/features/designer/FieldSearch/FieldSearch.tsx` | The box (`SearchField`, `FE-025`) plus, once a query is active, a `role`-free `aria-live="polite"` region: either `t('fieldSearchEmpty')` or the match-count caption followed by one `FieldRow` per hit, each preceded by its section-title crumb. |
| `frontend/src/features/designer/FieldSearch/{FieldSearch.types.ts,index.ts}` | `FieldSearchProps` — a controlled component (`query`/`onQueryChange`/`totalFieldCount`/`hits` all owned by the caller, same split `SectionNav`/`useSectionJump` use) plus `values`/`onFieldChange`/`disabled` mirroring `SectionCard`'s own field-editing props. |
| `frontend/src/features/designer/FieldSearch/{FieldSearch.test.tsx,FieldSearch.a11y.test.tsx,useFieldSearch.test.ts}` | 18 tests: label reflects the total (including hidden) field count; no results panel while idle; matching debounced via `SearchField` and excluding a hidden-branch field even when its label matches; the exact empty-state and match-count copy; the result region's `aria-live="polite"`; edits on a matched field forward through `onFieldChange`; the hook's own matching/case-insensitivity/hidden-exclusion/idle-clearing behaviour in isolation (`renderHook`). A11y: idle box, an active hit list, and the empty state, both colour modes, zero violations. |
| `frontend/src/shared/i18n/messages.en.ts` | 3 new keys: `fieldSearchLabel` (plural, `Search N field(s)…`), `fieldSearchResultCount` (plural, `N field(s) match "{query}"`), `fieldSearchEmpty` (`No fields match "{query}".`). |

## The contract gap this task ran into, and what I did about it

UI §A.8.8 specifies the crumb as `component · section`. Exactly like `SectionNav` (task 050), `ViewSection`
carries no component-instance identity to supply the first half — the same gap, proven the same way (the
`multi-component-selection` fixture: two different component instances of the same type project sections
identical in everything but field values). I did not re-derive this evidence independently; I re-used
`SectionNav`'s own documented finding rather than re-litigating it, since nothing has changed about
`ViewSection`'s shape since task 050. The crumb here is the section title alone, exact for a single-component
solution (Cache, v1's target) and disclosed — in `FieldSearch.tsx`'s module doc — rather than faked, flagged
for `FE-063` (Composer canvas) alongside `SectionNav`'s own flag.

## Design decisions, and why

1. **Reused the reference's exact matching predicate**, not a fuzzy or tokenized one: `designer.jsx`'s
   `SearchResults` builds `${label} ${key} ${renderedPath||""} ${section}`.toLowerCase() and does a plain
   substring `includes`. The plan gives no matching algorithm of its own, and `FE-D30` makes the reference the
   source of truth for behaviour the plan doesn't pin down further — copying it exactly (including matching on
   the *rendered path*, e.g. `broker.port`, not just the label) means a query like `"broker.port"` hits, which
   a naive label-only search would miss.
2. **`totalFieldCount` counts every declared field, not just applicable ones.** I checked this against the
   reference twice — both its multi-component call site (`model.components.reduce((n,c)=>n+c.fields.length,0)`)
   and its single-component one (`comp.fields.length`) sum the raw array length, unfiltered by `fieldVisible`.
   This is a deliberate asymmetry with `hits` itself (applicable-only, per this task's own acceptance
   criterion) — the placeholder tells the operator how big the field space is, the results respect what's
   currently reachable. Getting this backwards (filtering the count too) would make the label change under a
   discriminator flip, which the reference never does.
3. **`FieldSearch` is a controlled component; `useFieldSearch` is a separate, independently-tested hook** —
   the same split `SectionNav`/`useSectionJump` (task 050) already established in this same directory tree,
   not a new pattern introduced here. It lets a future Designer page lift `isActive` to decide whether to
   render `FieldSearch`'s results *instead of* the normal `SectionNav`+`SectionCard` view (the reference's own
   toggle) without `FieldSearch` needing to know that view exists.
4. **No debounce inside `useFieldSearch` itself** — `SearchField` (task 025, design §17) already debounces
   free text, and `FieldSearch` is its only Designer consumer today. Debouncing again in the hook would double
   the delay for no benefit and risk two competing timers drifting out of sync.
5. **The crumb renders outside `FieldRow`, as a caption above it, rather than adding a `crumb` prop to
   `FieldRow` itself.** The reference's own `FieldRow` takes a `crumb` prop; ours (task 048) doesn't, and it's
   already shipped with its own tests and a11y sign-off. Extending a closed, already-reviewed component for
   one caller's presentational need is a bigger blast radius than rendering the crumb as a sibling `Typography`
   — visually equivalent, and `FieldRow.tsx` is untouched.
6. **Result count and empty-state copy are their own message keys (`fieldSearchResultCount` /
   `fieldSearchEmpty`), not the generic `resultCount` key `FilterBar` already has.** UI §A.8.8 pins specific
   copy (`"No fields match "X"."`, not `"0 results"`) that the generic key can't produce, so I used
   `SearchField` directly (for the debounced input) rather than the full `FilterBar` wrapper (which would have
   forced the generic phrasing).

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/FieldSearch   # 3 files, 18 tests, all passed
npx eslint src/features/designer/FieldSearch src/shared/i18n/messages.en.ts   # clean
npx tsc --noEmit -p .                               # clean
npx vitest run                                      # full suite: 168 files, 798 tests
npm run build                                       # production build succeeds
```

Two full-suite runs each showed a different pre-existing flake unrelated to this change — `DataTable`'s
"Axe is already running" / menu-timeout cluster on one run, `InstancesPage`'s reflow timeout on the other
(the same class of parallelism flake the FE-046/048/050 reports already document, in files this task never
touched). Re-running `DataTable` in isolation passed 28/28 clean.

## Status

DONE — implemented, tested (18/18 new, full suite green modulo the documented pre-existing flake), lint-clean,
typecheck-clean, zero `jest-axe` violations across idle/hit-list/empty states in both colour modes, production
build succeeds. One contract gap re-confirmed and deliberately deferred (component-instance identity on
`ViewSection`, same as task 050's finding), disclosed in-code rather than worked around. Issue:
[#66](https://github.com/parikshit-coe/solution-manager/issues/66).
