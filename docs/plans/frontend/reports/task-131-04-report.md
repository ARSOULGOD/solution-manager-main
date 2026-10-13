# Task 131.04 — Canonical `project()` connection resolution

## Built

- Extended canonical view-model types with catalog attributes, revisions, connection bindings,
  and connection/override value provenance.
- Implemented precedence: literal override → selected connection → declared default → unset.
- Bound fields are read-only until overridden; unresolved selectors emit the specified diagnostic.
- Refused secret-to-non-secret resolution in follow-up commit `c17f05e`.

## Verification

- Canonical view-model tests cover resolution, fallback, overrides, unresolved connections, and
  secret handling.
- Contract fixtures regenerate without silent goalpost changes.
- Delivered by `aa11a6c`; secret-boundary correction in `c17f05e`.
