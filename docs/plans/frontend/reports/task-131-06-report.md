# Task 131.06 — Connections repository and fixtures

## Built

- Added descriptor-backed connection fixtures for both initial connection types.
- Kept secret values out of fixture rows; only `secretStatus` crosses the read seam.
- Added `toCatalogEntries()` with revisions and descriptor-derived secret attribute metadata.
- Added the catalog repository seam with list/get/create/update/status/delete/descriptor methods.
- Repository list requests never accept client-supplied organization scope.

## Verification

- Fixture-shape, secret-safety, catalog projection, inactive-row, request-shape, and guarded-delete
  tests pass.
- Delivered by `87b167c`; non-scalar selector guard in `99e18a2`.
