# Task 131.10 — Designer provenance and override

## Built

- Added connection and override provenance to Designer fields.
- Bound fields render read-only with an explicit Override action; overridden fields can revert to
  the connection.
- Bound secrets use Replace rather than copying a value into the draft.
- Save payloads serialize active bindings as `$from` references while overrides remain literals.
- Changing a selector rebinds non-overridden fields without discarding explicit overrides.

## Verification

- Draft-hook and FieldRow suites cover override, revert, selector changes, payload references,
  provenance chips, and secret behaviour.
- Delivered by `12d5049`.
