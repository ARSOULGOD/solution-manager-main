# Task 131.12 — Declare→Designer audit, activate/deactivate/delete UI, doc reconciliation

## Why this task exists

Not in the original plan script. Asked to "complete the Connection Objects feature end to end,"
audited tasks 1–11 against their own reports and against the actual diffs (`git show`, not the
reports' prose) before changing anything. Requirements 1–9 and 11 of that audit were already fully
built in the working tree — declare-time selection, draft persistence, Designer load's
`connectionCatalog`/`appliedConnectionRevisions`, `valueFrom` resolution, precedence,
override/revert, `$from` save payloads, secret safety, and drift detection all checked out against
`docs/design/dynamic-ui/connection-objects.md` and its `UI-D27`/`UI-D28`/`UI-D29`. One real gap
survived the audit.

## Built

**Requirement 10, closed.** `connectionsRepository.ts`'s `deactivate()` and `remove()` (with its
client-side `usedByCount > 0` guard) had existed since `87b167c`, and MSW handlers for both since the
uncommitted `cp.ts` work, but nothing in the UI ever called them — `ConnectionDrawer` had no
status control and no delete action.

- `ConnectionDrawer`: a `Status` switch and a `Delete` button, both gated behind new optional
  `onDeactivate`/`onDelete` props so they render only when editing an existing connection (never
  while creating — `connection.id === ''` then). `Delete` disables with an explained reason when
  `usedByCount > 0`, and confirms via `ConfirmDialog` when it is not.
- `ConnectionsRoute`: wires both props to the repository, only for `editing !== null`, invalidating
  `queryKeys.connections.list()` on success — the same pattern `onSave` already used.
- Five new tests in `ConnectionDrawer.test.tsx`: hidden in create mode, status toggle fires
  `onDeactivate`, `Delete` disabled+explained when in use, confirm-then-delete when unused.

## Doc reconciliation (the actual ask of this session)

Audited the committed record against the working tree, not just the working tree against itself:

1. **`docs/design/dynamic-ui/connection-objects.md` was never committed.** `131-connection-objects.md`
   and its eleven task reports landed in `fb210b0`, and that plan cites the design doc throughout —
   but the design doc itself stayed **untracked** on disk. At `fb210b0`, the plan's central citation
   was a dead link. `decisions.md`/`design.md`'s `UI-D27`/`UI-D28`/`UI-D29` entries were similarly
   still uncommitted. Recorded as [[L21]] in `docs/design/common/lessons.md` (cross-cutting: this is
   a plan/design-doc sequencing failure, not specific to this feature).
2. **`task-131-08-report.md` overclaimed.** It stated `fdb0420` added "create/edit/deactivate/delete
   flows"; the commit's actual diff added `onSave` only. Corrected in place with a note, per this
   repo's rule against silently rewriting a report versus stating what was wrong and why.
3. **Workflow Orchestration block genericized**, per explicit request: `CLAUDE.md`'s §"Workflow
   Orchestration" assumed Claude Code exclusively (plan mode, named `superpowers:*` skills,
   "subagents" as the only delegation mechanism) — unfollowable literally once a session runs on a
   different tool (this session's own predecessor used Codex). Reworded every section to state the
   required behavior with tool-specific mechanisms kept as one way to satisfy it, not the rule
   itself. Changelog entry added to
   `docs/design/common/conventions/claude-workflow.md` per that file's own editing rule. Nothing in
   §1–§8's obligations was weakened or removed.

## Verification

- `npm test -- src/features/connections/` — 7 files, 41 tests passed (36 pre-existing + 5 new).
- `npm test -- src/features/inventory/instances/ src/features/designer/` — 88/89 files passed; the
  2 failures in `componentSelection.fileMesh.test.ts` are the pre-existing, out-of-scope defect from
  the uncommitted File Mesh `package.json` rewrite (per constraints, left untouched) — unrelated to
  Connection Objects, confirmed by reading the failing assertions and the file under test.
- `npm run typecheck` — clean.
- `npm run lint` — clean at `--max-warnings=0`.
- `npm run build` — succeeds.
- `./contracts/run-checks.sh --strict` — checks 1, 2, 3, 7–12 (everything Connection-Objects-related,
  including gates 15–19 and descriptor validation) pass. Checks 4–6 fail only on the documented,
  pre-existing blocker: the external File Mesh SQL dump is not present at
  `~/Work/Development/file-mesh-ui/database/file-mesh-schema.sql` (`$SM_FILEMESH_SQL` unset in this
  environment).

## Not yet done

- **`connection-objects.md`, the `decisions.md`/`design.md` diff, this report, and the
  `ConnectionDrawer`/`ConnectionsRoute` changes are still uncommitted** — per this session's standing
  instruction to ask before every commit. Should land together, per [[L21]]: the plan/design-doc/
  decision-register triad as one reviewed change, this time actually together.
- A route-level integration test for the new `onDeactivate`/`onDelete` wiring in `ConnectionsRoute`
  itself was not added — no route-level test exists for the pre-existing `onSave` wiring either, so
  this follows the file's own established coverage boundary (drawer unit tests + repository tests)
  rather than introducing a new pattern unasked.
