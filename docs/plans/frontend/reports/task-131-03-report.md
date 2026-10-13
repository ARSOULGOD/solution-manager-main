# Task 131.03 — `valueFrom` validation gates

## Built

- Added gates 15–19 for same-component selector resolution, descriptor attribute/type
  compatibility, bidirectional secret safety, duplicate-binding warnings, and required-selector
  consistency.
- Wired descriptor validation and binding checks into `contracts/run-checks.sh`.
- Made negative-fixture generation exercise the semantic gates.

## Verification

- The negative fixtures fail at their intended gates.
- The positive binding gate and descriptor gate pass in the strict contract run.
- Delivered by `411fd98`.
