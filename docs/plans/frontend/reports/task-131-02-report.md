# Task 131.02 — Solution Package connection bindings

## Built

- Added `connectionType`, `valueFrom`, `ui.listColumn`, and `ui.promoteToDeclare` to the package
  contract and frontend types.
- Retired `optionsSource.entity: broker` in favour of `connection` plus `connectionType`.
- Added negative fixtures and regenerated the standalone frontend validator.
- Updated Cache and File Mesh fixtures to the new vocabulary.

## Verification

- Schema negatives reject missing connection types and invalid bindings.
- Generated validator matches the canonical schema during frontend builds.
- Delivered by `fc04c5c`; fixture-generation correction in `10f0a51`.
