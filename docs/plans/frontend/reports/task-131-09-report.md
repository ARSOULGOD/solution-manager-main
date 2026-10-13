# Task 131.09 — Declare Instance connection selectors

## Built

- Promoted package fields marked `ui.promoteToDeclare` into the declaration flow.
- Added scope-filtered connection selectors backed by the Connections repository.
- Threaded selected connection values into the declared instance payload without sending client
  scope as a repository filter.
- Preserved `valueFrom` and `promoteToDeclare` through package normalization.

## Verification

- Declare-form and dialog tests cover promoted fields, exact scope matching, and selection.
- Normalization passthrough is guarded by `40cbaec`.
- Delivered by `232c239`; optional-replica compatibility correction in `d7093e5`.
