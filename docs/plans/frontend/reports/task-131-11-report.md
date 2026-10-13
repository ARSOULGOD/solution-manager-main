# Task 131.11 — Connection drift banner

## Built

- Added revision-based `detectDrift()` with coverage for advanced revisions, matching revisions,
  unreferenced connections, and deleted connections.
- Added the warning-level connection drift banner. It names the connection and affected fields and
  offers only **Review and save**; rollout remediations remain the responsibility of
  `DivergedBanner`.
- Mounted the banner beside the existing Designer banners for both flat and composed solutions.
- Extended `DesignerLoad` with optional current catalog entries and applied connection revisions.
  The catalog is also passed into `useDesignerDraft`, closing the integration gap where the page
  previously projected bound fields with an empty catalog.
- The review action expands and focuses the first affected field.

## Plan correction

Task 11 said to mount the banner but did not provide `DesignerForm` either input needed for drift
detection. Both inputs are optional until the server-side effective-configuration fingerprint and
per-connection revision response exist. Their absence produces no banner and preserves existing
load responses.

The banner says fields **may differ**. A revision proves the connection changed but the client
cannot truthfully claim which resolved values changed.

## Verification

- `npm test -- src/features/designer/ConnectionDriftBanner/` — 2 files, 8 tests passed.
- `npm run typecheck` — passed.
- `npm run lint` — passed with zero warnings.
- `npm run build` — passed; package validator matched the canonical schema.
- `npm test -- src/features/designer/DesignerPage/DesignerPage.test.tsx
  src/features/designer/ConnectionDriftBanner/` — 3 files, 23 tests passed. The Composer
  interaction test was corrected to select its source node before expecting the inspector; the
  component intentionally starts with no node selected.
- `npm run typecheck`, `npm run lint`, and `npm run build` — passed.
- The complete `npm test` run exposed unrelated baseline failures: the uncommitted File Mesh
  package rewrite no longer matches its contract tests, while Gallery/AppShell/state tests run in
  an environment where `localStorage` is unavailable. Connection Objects focused suites passed.
- `./contracts/run-checks.sh --strict` — contract/view-model tests and connection gates passed;
  overall strict result remains blocked by the unavailable external File Mesh SQL dump at
  `/Users/banalapradeep/Work/Development/file-mesh-ui/database/file-mesh-schema.sql`.
