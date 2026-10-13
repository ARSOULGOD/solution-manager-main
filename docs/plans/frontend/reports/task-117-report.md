# FE-117 · Undeclare an instance — Report

## What this closes

Reported as "delete/pause action not present". Investigation found neither is a defect, and that the
two are not the same case:

| Asked for | Status found |
|---|---|
| **Delete** (the workload) | **Out of scope, permanently.** SM is a config/governance plane; lifecycle belongs to the customer's CI/CD (`SM-D16`, `I1`). Not one of the four lifecycle verbs the architecture defines. |
| **Pause** | **Unbuildable today.** `stop` is a real verb (`architecture.md` §5: `start \| stop \| restart \| run-now`) but it is **package-declared per runtime**, and `ActionKind` in the package contract carries no lifecycle member — a package cannot declare it. "Pause" is also not "stop"; only `stop` is defined. |
| **Undeclare** | Coherent, in scope, and the only one SM may actually perform. **Built here.** |

The deciding fact: **restart — fully specified (`SM-D15`), dialog shipped in FE-043 — still does not
execute**, because multi-instance apply has no protocol (open item 1 / `CLAUDE.md` blocking item 3).
Adding two *less*-specified lifecycle actions above a specified one that cannot run would have built
straight onto that gap.

## What was built

An **undeclare** action: removes SM's record of an instance and nothing else. Offered as a toolbar
action beside the existing `Restart N…`, matching how instance actions already work on this screen
and in the reference — rather than a new actions column, which would have required CSV-exclusion and
card-view placement rules in the shared `DataTable` contract.

**The copy carries the whole feature.** An operator reading "Remove" will reasonably assume it stops
or deletes the workload; every other console works that way. The dialog states both halves —
what IS removed (record, config of record, staged changes, history) and what is NOT (the process,
which keeps running unmanaged). A confirmation that left that ambiguous would be worse than no
action at all.

## Files

| File | Summary |
|---|---|
| `src/shared/api/planeClientFactory.ts` | `deleteWithCorrelation` — the missing symmetric seam. A removal reports its own outcome, and `ToastInput.correlationId` is required, never client-fabricated (`FE-D13`). |
| `src/features/inventory/instances/instancesRepository.ts` | `undeclare()`; header records why no lifecycle verb sits beside it. |
| `src/features/inventory/instances/UndeclareInstancesDialog.tsx` | **New.** Names every instance; states both halves; `consequence: high`, not `outage`. |
| `src/features/inventory/instances/InstancesPage.tsx` | `Remove N…` bulk action, `allSettled` mutation, toast, selection cleanup, `liveSelection`. |
| `src/app/dev/devDeclaredInstances.ts` | Tombstones (`undeclare`/`removedIds`), own storage key. |
| `src/app/dev/devPackageHandlers.ts` | `DELETE` route; merged list filtered through tombstones; `GET` 404s a removed id. |
| `src/shared/testdoubles/handlers/dp.ts` | Stateless `DELETE` for the default fixture set. |

## Rulings

1. **`consequence: 'high'`, not `'outage'`.** Nothing restarts and nothing stops, so claiming an
   outage would be a lie in the other direction. What makes it high is the config history, which
   does not return when the instance is re-declared.

2. **Removals are TOMBSTONES.** A fixture array cannot be mutated, and an instance must be removable
   whether it came from the session store or the baked fixtures. A removal that matches no
   declaration is recorded as a removed id and filtered from the merged list. Own storage key rather
   than folded into the declaration entries — the two answer different questions, and a combined
   shape would have forced a migration of the working v1 format.

3. **Re-declaring a removed name lifts its tombstone**, or the merged list would filter the row
   straight back out and the declaration would look like it had silently failed.

4. **`allSettled`, not `all`.** With several selected, one failure must not hide the successes, and
   the operator needs to know exactly which are still declared. The dialog stays open naming them;
   a partial failure the operator must act on is not delivered as something that auto-dismisses.

5. **The default `dpHandlers` DELETE is stateless**, for the same reason the declare POST is: a
   handler that actually removed rows would make every suite's `CACHE_INSTANCES` count depend on
   test order.

## A bug the unit tests missed and the browser caught

After a successful removal the grid correctly went 12 → 10 rows, but the toolbar still read
**"2 selected"** and `?sel=` still named both removed rows.

**Root cause:** `DataTable` restores a selected id whose row has left `rows`, treating it as
"the caller filtered it away, not a real deselection" — correct for the env filter (FE-042), wrong
for a removal, and the component cannot tell the two apart. It raced the refetch and re-armed the
bulk actions against rows that were gone.

**Fix:** `InstancesPage` now resolves `liveSelection = selection ∩ instances`. A selection may name
a row a filter is hiding; it may never name a row that does not exist. Regression test added.

## Verification

| Gate | Result |
|---|---|
| `npm run typecheck` | passes |
| `npm run lint` | passes, `--max-warnings=0` |
| `vitest run` (instances · app/dev · DataTable · api · instance) | **356 passed**, 32 files |
| Browser, `npm run dev` | full flow, **no console errors** |

```
before : 0 selected · 12 of 12 rows
after  : 0 selected · 10 of 10 rows   toast "2 instances removed" + correlation id
URL    : /solutions/cache/instances   (?sel= cleared)
reload : 0 selected · 10 of 10 rows   (tombstones persisted)
```

## ⚠️ Unrelated working-tree change, not made by this task

`src/shared/testdoubles/data/solutions.ts` has its `cache-nightly` fixture entry **deleted** in the
working tree. This breaks 6 tests across 4 files (`sidebarSolutions`, `solutionsRepository`,
`SolutionCard`, `SolutionsPage`) — all of which assert on the unsigned dev-mode package.

Not caused by this work, and demonstrated so: restoring the file and re-running `eslint --fix` and
`prettier --write` against it produces **no change**. The edit came from outside the session, and
re-appeared within a minute of a `git checkout` restore. **Left alone** pending the author's
intent — if the deletion is wanted, those 6 tests need updating with it.
