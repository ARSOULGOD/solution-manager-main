# REQ-013 — Fleet apply

**Status:** ACCEPTED (owner ruling `OR-47`, 2026-09-28) · **Priority:** v1

## Requirement

An operator can apply one configuration change to many instances of the same solution in a single action — for
example, one change to `cache-1`, `cache-2` and `cache-eu`.

## Acceptance

- Each instance is authorized individually (`config.apply`, plus `instance.restart` when the strategy restarts); a
  principal who holds the verb on only some of the selected instances is told which are refused.
- Each instance gets its own apply request, journal and audit line; one fleet action is traceable to all of them.
- Partial failure, ordering and batching are defined and visible; one failing instance does not silently stop or
  hide the others.

## Trace

No decision yet — needs a design before the apply slice (`OR-47`).
