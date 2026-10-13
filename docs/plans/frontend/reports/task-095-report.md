# FE-095 · Targets preview — Report

## Scope and gate

Last task of Phase 7 (`090`–`095`), which `task-list.md` marks ⛔ **NOT SCHEDULED** (Q4 / Q5). `095`'s
ready-class is `BLOCKED` "for anything beyond read-only" — but the whole deliverable **is** a read-only model
preview: there is no wire half for a screen that never writes to a target. Credential entry is deliberately
absent, blocked on `DECISIONS.md` open items 1 and 2 (the `dp-remote` apply protocol). Built now, in the same
session, against the fixture-backed `TargetsRepository` (already read-only by construction, task 033). No new
`FE-D*` — nothing was decided here. GitHub issue #162.

## What was built

**`TargetsPreview`** (`src/features/targets/TargetsPreview.tsx`) — replaces the `RouteStub` at
`src/app/routes/TargetsRoute.tsx`. Not in v1 nav; reachable via `AccountMenu`'s existing DEV-only preview link,
same as `/admin`.

| Region | Content |
|---|---|
| **Header** | "Targets" + the "reachable by URL only" sub-line, with `Later increment` / `Not in v1 navigation` chips. No action slot — the screen is read-only. |
| **Locus banner** | An `info` `Alert`: `dp-remote` stores host credentials centrally; `host-local` stores none (outbound agent reports in); `observe-only` — SM never writes. |
| **Table** | Target (id + monospace location) · Driver (`driverVocab` label) · Control locus (`locusVocab` `Chip` + `InfoTip` carrying the per-locus credential-posture tip) · Ownership scope (`/`-joined path). |
| **Model** | Two `Paper`s — the **Target** and **Binding** field lists, verbatim from the reference, as monospace `pre-line` text. |
| **Blocked note** | A `warn` `Alert`: "Not designed here — blocked. Credential entry is intentionally absent … Nothing on this screen writes to a target." |

Loading → `Skeleton`; failure → `ErrorPanel` with `Retry`. Follows `reference/app/deferred.jsx`'s
`TargetsScreen`. The locus chip uses `locusVocab`'s own `tone` (`neutral`) rather than the reference's ad-hoc
`warn`/`idle` — the vocabulary module is the single source of truth for a state's tone (`FE-013`); the security
*meaning* is carried by the banner and the per-row tips, which is what the acceptance criterion asks for.

## Contract seam

None touched. `TargetsRepository.list()` and the `TARGETS` fixture + `*/cp/targets` MSW handler already
existed (task 033). `TargetsRepository` has **no** write method and this task adds none — verified by the
existing `targetsRepository.test.ts` (`'create' | 'update' | 'delete' in repo` all `false`).

## Wiring

- `src/app/routes/TargetsRoute.tsx` — was `<RouteStub title="Targets" />`; now builds
  `createTargetsRepository(createCpClient(config.apiBasePath))` in the route (app layer owns `RuntimeConfig`)
  and renders `<TargetsPreview repository={…} />`, the same pattern `AdminRoute` / `SolutionsRoute` use.
- `src/app/routes/RouteStub.tsx` — **deleted.** `TargetsRoute` was its last consumer; an orphaned scaffold
  file is worse than none ("each route module under `src/app/routes/` is deleted as its real page replaces
  it"). Two stale comment references (`router.test.tsx`, `AppShell.tsx`) trimmed.
- `src/features/targets/index.ts` — new barrel: `TargetsPreview`, `createTargetsRepository`,
  `TargetsRepository`.
- `messages.en.ts` — new `targetsPreview*` keys.

## Testing

- `TargetsPreview.test.tsx` (5) — every target renders its location / driver label / locus chip / scope path;
  the `control_locus` security meaning is stated (banner title + body); **read-only** — no `textbox` /
  `checkbox` / `combobox`, and every `role="button"` present is a locus `InfoTip` (accessible name is the tip
  text), never an action control; the Target and Binding field lists render; a load failure shows the error
  panel and `Retry` recovers.
- `TargetsPreview.a11y.test.tsx` (2) — the loaded preview is axe-clean in light and dark.
- `npm run lint` clean · `npm run typecheck` clean.
- `npx vitest run src/features/targets src/app` — 25 files / 280 tests green (covers the `RouteStub` removal:
  `router.test.tsx` still resolves `/targets`, `AppShell.test.tsx` still shows the DEV-only Targets menu item).
  `npm run test:a11y -- src/features/targets` — 2 green. Full suite `npx vitest run` — green.
- Not done: live browser verification — no browser harness in this session. Covered by the fixture-backed
  component tests above; follows `reference/app/deferred.jsx` closely.

## Acceptance

- ☑ read-only — no action slot, no form controls, `TargetsRepository` has no write method.
- ☑ no credential entry — no inputs of any kind; the blocked-work `Alert` states why it is absent.
- ☑ locus security meaning stated — the `info` banner spells out `dp-remote` / `host-local` / `observe-only`,
  and each row carries `locusVocab`'s credential-posture tip via `InfoTip`.

## Files

| File | Change |
|---|---|
| `src/features/targets/TargetsPreview.tsx` | New — the read-only preview. |
| `src/features/targets/TargetsPreview.types.ts` | New — `TargetsPreviewProps`. |
| `src/features/targets/index.ts` | New — public surface. |
| `src/features/targets/TargetsPreview.test.tsx` | New. |
| `src/features/targets/TargetsPreview.a11y.test.tsx` | New. |
| `src/app/routes/TargetsRoute.tsx` | `RouteStub` → real `TargetsPreview` wired to `createTargetsRepository`. |
| `src/app/routes/RouteStub.tsx` | Deleted — last consumer gone. |
| `src/app/router.test.tsx` / `src/app/shell/AppShell.tsx` | Trimmed stale `RouteStub` comment references. |
| `src/shared/i18n/messages.en.ts` | New `targetsPreview*` keys. |
| `docs/plans/frontend/task-list.md` / `execution.md` | `095` → ✅ read-only; Phase 7 note. |

## Next

Phase 7 (`090`–`095`) is now built end to end, though the phase stays ⛔ not scheduled per ruling F3. Next in
task order is Phase 8 hardening (`096`–`104`, `110`) — several already done (`098`, `110`); `096` (code
splitting + bundle budget) is the first `READY` one outstanding.
