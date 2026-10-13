# FE-120 · Instance count derived, never a hardcoded fixture number — Report

## What this closes

`SOLUTIONS` in `data/solutions.ts` hardcoded `instanceCount: 12` for Cache, and nothing recomputed it. After
declaring an instance the grid showed 13 and Home's `StatStrip` showed 13 — that one *is* derived, in
`devPackageHandlers.ts`'s `/cp/home/summary` — but the Solutions card and the sidebar still read 12. Same
staleness after undeclaring.

`cache-nightly` was wrong before anything was declared at all: the card claimed 1 instance and its grid was
empty.

## What was built

`withInstanceCounts(cards, instances)` in `data/solutions.ts`, returning each card with `instanceCount`
recomputed from the live instance list. Applied in **both** handlers serving `/cp/solutions`:

- `src/shared/testdoubles/handlers/cp.ts`, over `CACHE_INSTANCES` — the default fixture set is stateless, so
  that is its instance list.
- `src/app/dev/devPackageHandlers.ts`, over `allInstances()` — the merged list, so declaring **and**
  undeclaring both move the number.

⚠️ **Keyed on the CARD ID, never `card.solution`.** The grid behind a card is
`/dp/solutions/:solutionId/instances`, which filters on the card id — counting any other way would give a
number that disagrees with the grid the card links to. `cache` and `cache-nightly` are two cards sharing one
`solution`, which is exactly where the two readings diverge. Asserted directly: a test walks every card and
requires `card.instanceCount === grid.total`.

`derivePackageCatalog.ts` keeps `instanceCount: 1` as its derivation-time value, with a comment that the
handler is authoritative.

## The one honest fixture change

**`cache-nightly` goes 1 → 0.** No instance carries `solutionId: 'cache-nightly'`, so 0 is the true count.
That card was already lying; deriving the number is what surfaced it.

## Files

| File | Summary |
|---|---|
| `src/shared/testdoubles/data/solutions.ts` | `withInstanceCounts()`, with the card-id rule and its rationale. |
| `src/shared/testdoubles/handlers/cp.ts` | Applied over `CACHE_INSTANCES`. |
| `src/app/dev/devPackageHandlers.ts` | Applied over the merged `allInstances()`. |
| `src/app/dev/derivePackageCatalog.ts` | Comment recording that the handler is authoritative. |
| `src/app/dev/devPackageFlow.test.ts` | Three tests: the count moves on declare and on undeclare; every card agrees with its own grid; the nightly card reports 0. |

## Verification

`npm run lint` · `npm run typecheck` · `npm test` · `npm run build` — all green.

Driven in a browser at `npm run dev`, zero console errors:

- Before declaring: Solutions reads `Cache Engine (12 instances)`, `File Mesh (0 instances)`,
  `Cache Engine · nightly` **0 instances** — previously 1 with an empty grid.
- After declaring one instance: `Cache Engine (13 instances)`, on the card **and** in the sidebar.
- After two more declarations: 15 on both. Undeclaring returns the number, asserted in
  `devPackageFlow.test.ts`.

## Found, not fixed (out of scope)

Loading a package whose `solution` id matches a baked catalog card produces two cards with the same id, so
both now report the same derived count (e.g. "Cache Engine (15 instances)" beside "Cache (15 instances)").
Deriving the count makes the duplication more obvious, but the duplication itself is pre-existing —
`allSolutions()` concatenates `SOLUTIONS` and the dev store without de-duplicating by id, and React already
logged `Encountered two children with the same key` for it before this change. Recorded rather than fixed; see
the FE-118 report, which found the same thing.
