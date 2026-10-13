# Task 131.05 — Frontend `project()` parity

## Built

- Ported connection catalog types, `valueFrom` resolution, provenance, precedence, and diagnostics
  to the frontend view-model engine.
- Added the canonical connection-resolution cases to the frontend test suite.
- Corrected selector option resolution to use the catalog in follow-up commit `9fc8f84`.

## Verification

- Frontend view-model tests cover the canonical cases and fixture parity.
- Delivered by `48e2264`; catalog-option correction in `9fc8f84`.
