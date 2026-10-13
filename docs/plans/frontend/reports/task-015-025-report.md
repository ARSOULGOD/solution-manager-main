# FE-015–025 · Shared UI kernel remainder — Report

## What I implemented

Completed Phase 1 kernel tasks **015–025** on `dev/frontend` after FE-011–014. Each primitive lives in `frontend/src/shared/ui/<Name>/` (component, types, unit test, axe test in both themes, gallery demo) and is exported from `src/shared/ui/index.ts` and registered in `/dev/gallery`.

| Task | Components |
|---|---|
| 015 | `Paper`, `PageHeader`, `SectionHead`, `PageWidth` (layout) |
| 016 | `Dialog`, `ConfirmDialog`, `HighConsequenceDialog` |
| 017 | `Menu` (caption rows, `aria-haspopup` / `aria-expanded`) |
| 018 | `ToggleGroup`, `RouteTabs` (tabs are links; focus moves into the panel) |
| 019 | `EmptyState`, `Skeleton` (required width/height) |
| 020 | `DataTable` on `@mui/x-data-grid` Community |
| 021 | `Toaster` + `OutcomeBadge` (correlation id required) |
| 022 | `DiffView` + `SECRET_MASK` (secret rows type-incapable of plaintext) |
| 023 | `SecretField` (write-only, no reveal; FIXTURE wire shape) |
| 024 | `Breadcrumbs`, `SidebarNav` (Home + Solutions only; accent bar + `aria-current`) |
| 025 | `FilterBar`, `SearchField` (debounce free text only; polite result count) |

## Rulings

1. **Master-detail is Pro-only** (verified [MUI X master-detail](https://mui.com/x/react-data-grid/master-detail/) 2026-08-20). FE-020's expander is a Community expander button (`aria-expanded`) plus a panel under the grid — not `getDetailPanelContent`.
2. **MUI `Tabs` nests a second `role="tablist"`** in v9, which fails axe. `RouteTabs` is NavLink-based so there is one tablist.
3. **Page width is a `PageWidth` layout component** (`default` 1240 / `wide` 1560), not a copied class.
4. **`SecretField` does not use `TextField`'s `reveal`** — that wrapper is `type="text"` unless reveal is on. Entry is a password `TextField` with no show/hide control.
5. **Drawer navy** is `theme.palette.nav` (always-dark rail), added in the palette factory so `shared/ui` never reads raw tokens.

## Testing

```
npm run lint
npx tsc -p tsconfig.json --noEmit
npm test
```

All green (lint, typecheck, full Vitest run).

## Status

DONE (implementation + register). GitHub issues #37–#47 and git commits were **not** closed/created here — say if you want those next.
