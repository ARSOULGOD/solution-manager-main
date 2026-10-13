# Plan 007, Task 2 — status report (entangled, not executed)

**Outcome: STOPPED before writing code.** Investigation found the entanglement the dispatch
instructions warned about, and it is worse than "the declare dialog produces the same
`InstanceRow` type" — the real contract's `Instance` shape has almost no field overlap with the
fixture `InstanceRow` the entire Instances feature (not just declare) is built on. No test,
adapter, or component code was written or modified. No files were changed.

## What Task 2 asked for

`listInstances`/`getInstance` adapter against the real `Paged<Instance>` / `Instance` envelopes,
producing something the instance detail header (shared by Designer/Governance/Monitoring tabs)
can still render.

## What exists today

`frontend/src/features/inventory/instances/instancesRepository.ts` already calls
`GET /solutions/{solutionId}/instances` and `GET /instances/{instanceId}`, typed as
`Promise<Paged<InstanceRow>>` / `Promise<InstanceRow>` — but `InstanceRow` (defined in
`frontend/src/shared/testdoubles/data/instances.ts`) is a **fixture type that was never backed by
any API** (its own header says so: "INVENTED: no endpoint anywhere"). It carries:

```
id, name, solutionId, packageVersion, env, org, subOrg, driver, locus,
replicas: ReplicaRow[], healthRollup, notFoundReason, version: {state, observed, declared},
configState, lastSeen, componentTypes?, connections?, appliedConnectionRevisions?
```

Checked git history on this file (per dispatch instructions): the "2-line fixup" referenced
turned out to be from plan 005's agent landing `declare()`/`undeclare()` on top of the task-033
scaffold in commit `884b2494`, not a shape reconciliation — `list`/`get` are unchanged since
`ac63625e` (Aug 21) and still return raw `InstanceRow`.

## What the real contract actually returns

`contracts/openapi/console.yaml`'s `Instance` schema (`allOf` `Versioned` +):

```
instanceId (uuid), name, solutionId (uuid), environmentId (uuid), declaredPackageVersion,
acquisition: { mode: direct-http|via-ssh },   // no host/address — OR-82
placements: [{ placementId, targetId, containerName, probeStrength }],
stateVersion (from Versioned)
```

There is **no** `env`/`org`/`subOrg`/`driver`/`locus`/`replicas`/`healthRollup`/`version`/
`configState`/`lastSeen` on this schema at all. Those concepts have moved to a different plane
of the API entirely: health is `Governance`'s placement-scoped `getHealth` (wired in plan 010,
9-state enum, no top-level rollup per `OR-39`/`OR-55`), version/config state lives under the
Designer/config-ladder operations (plan 008/010), and there is no "driver"/"locus" field in the
contract anywhere I could find — those look like pure fixture inventions tied to the old
on-prem-first vocabulary draft, superseded by `targetId`/`acquisition.mode`.

`org`/`subOrg` are also gone from the instance itself — the real hierarchy is
`Organization → Sub-organization → Environment → Solution → Instance → Placement` by id
(`environmentId` only), resolved via the plan-001 hierarchy repository, not stored per-row.

## Why this is not a Task-2-sized adapter problem

The dispatch brief anticipated the risk at the declare-dialog boundary. The actual blast radius
is the entire feature folder. `InstanceRow` (fixture shape) is the row type consumed by:

| File | What it needs from `InstanceRow` that the real `Instance` does not have |
|---|---|
| `columns.tsx` | `env`, `org`/`subOrg` (identity cell), `driver`, `locus`, `healthRollup`/`notFoundReason`, `replicas[].health`, `version.{state,observed,declared}`, `configState`, `lastSeen` — **7 of 9 grid columns** render fields absent from `Instance` |
| `InstancesPage.tsx` | Builds the grid from `InstanceRow[]`, filters by `env` |
| `ReplicaDetailPanel.tsx` | Expects `replicas: ReplicaRow[]` with per-replica `health` — no replica/health concept on `Instance`; that's `placements[]` (no health field) joined against Governance's separate `getHealth` call |
| `DeclareInstanceDialog.tsx` / `declareForm.ts` | Produce `DeclareInstanceInput`/row shape with `driver`/`locus`/`replicas`/`componentTypes` — none in the real `declareInstance` body either (plan 007 Task 1 already flagged this half; same root cause) |
| `UndeclareInstancesDialog.tsx`, `BulkRestartDialog.tsx` | Take `InstanceRow[]` and read `name`/`env` for confirmation copy |
| `*.test.tsx`/`*.test.ts` | All construct `InstanceRow` fixtures matching the invented shape |

An adapter that maps `Instance` → something the **header** can render (the one explicit
requirement in the dispatch) is buildable in isolation — `instanceId→id`, `name`, `solutionId`,
`environmentId`. But the **list grid's 7 health/version/config/driver/locus columns have no
source data in `listInstances`/`getInstance` at all.** Making the grid render real data would
require either:

1. Dropping those columns from the list view (a visible UX regression, needs a design call), or
2. Fetching Governance's `getHealth`/config-ladder per row to backfill them (N+1 calls per page,
   a performance/architecture call, and still doesn't source `driver`/`locus` from anywhere), or
3. Keeping the columns fixture-backed indefinitely alongside a real identity-only header (a
   silent split between "what the grid shows" and "what's real" that nothing currently flags to
   the user).

None of these is a safe unilateral engineering judgment call — each changes what operators see
on a production screen, and (1)/(2) are visual/UX decisions, matching exactly the class of
decision the dispatch told me to escalate rather than guess at.

## Relationship to Task 1 / D-3

This is the same root cause as Task 1's declare-instance drift (D-3, already flagged as needing
designer input): the whole `InstanceRow` fixture vocabulary (`driver`, `locus`, `componentTypes`,
inline health/version/config) was invented pre-contract and never reconciled. Task 2 cannot be
executed "underneath" that vocabulary without either (a) producing a half-real, half-fixture
`InstanceRow` that misleads whoever reads the code next, or (b) redesigning the list/detail
screens' field set — which is Task 1 Step 2's gated UX decision, just reached from the list side
instead of the declare-dialog side.

## Recommendation (not actioned)

Fold Task 2 into the same designer-input item as D-3 rather than treating it as independently
unblocked: the plan's premise that Task 2 is "just an adapter" assumed `Instance` and
`InstanceRow` were shape-compatible modulo renames. They are not — `Instance` carries almost none
of the fields the grid displays. Whoever makes the D-3 call needs to decide, for the list screen
simultaneously: which of the 7 fixture-only columns are dropped, replaced with a live Governance
join, or deliberately held as "not yet wired" with a stated reason — before any adapter code is
written.

## Verification run

None — no code was changed, so lint/tsc/vitest were not run. Repo left exactly as found; only
this report file was added.

## Files touched

- `docs/plans/integration/reports/007-task-report.md` (this file, new)

No other files were created or modified.
