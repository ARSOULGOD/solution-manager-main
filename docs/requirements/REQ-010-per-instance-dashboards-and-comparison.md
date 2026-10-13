# REQ-010 — Per-instance dashboards, and comparing one metric across instances

| | |
|---|---|
| **Status** | ACCEPTED |
| **Source** | Project owner (decides on behalf of the architect group, `OR-69`), 2026-09-30 — monitoring ruling M-Q23, "(a) Generic demo format + REQ-010 (Recommended)", with M-Q10's compare requirement. Recorded as `OR-78`. |
| **Raised** | 2026-09-30 |
| **Priority** | not stated by the owner; v1 shape per `OR-78` (confirm at the next owner sitting) |
| **Phase** | v1 |

## Requirement

An operator can build and share dashboards for a single solution instance, and can compare one metric across several
instances.

## Why

The owner's words (M-Q10, verbatim): "for a particular metrics our UI has option of selecting multiple instance to
compare how that particular metrics is working overall. We need to make sure our backend also supports that." The
console's monitoring replacement needs a requirement behind it: until now no requirement asked for dashboards,
widgets or comparison (`traceability.md`, the `DM-D*` row).

## Acceptance

The owner stated three elements (M-Q23), and this requirement carries those and no others:

1. **Per-instance shared dashboards.** A dashboard belongs to one instance and is shared with everyone who can see
   that instance.
2. **Line, gauge and single-stat widgets.**
3. **One-metric multi-instance comparison.** The query API serves one metric across multiple instance ids, independent
   of any dashboard (`OR-78`, M-Q10). An instance the viewer cannot see is omitted exactly as a nonexistent one is
   (`OR-79`, M-Q17).

## Satisfied by

`OR-78` (the dashboard document shape and the comparison requirement), `OR-79` (query visibility and limits),
`OR-74` (the metric catalog the widgets draw from) · `SM-D31` and the `DM-D*` family, **PROPOSED** · to be built by
plan 011 (`docs/plans/backend/011-monitoring-backend.md`, a draft, not yet approved).

## Conflicts

None known. `DM-D5` (`entity` and `tenant` scopes, a `personal` layer) is **not** covered: it reverses `SM-D31`'s
rejection of per-user dashboards and has no requirement. Out of v1 per `OR-78`: server-side Explore drafts, saved
comparison widgets and templating.

## Notes

- The package-default dashboard layer that can be hidden and never deleted comes from `SM-D31` (PROPOSED) and the
  demo's behaviour, which `OR-78` adopts as a **shape**, not as a requirement. It is not an acceptance criterion here.
- `REQ-011` ("reusable across multiple products") stays reserved and unraised.
