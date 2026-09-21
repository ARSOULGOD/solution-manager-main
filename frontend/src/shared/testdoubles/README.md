# Fixture layer (task 033)

Every screen in this plan is built before its endpoint exists (§9.1). A
component never knows whether a fixture or a real server answered it — it
calls a hook, the hook calls a **repository interface**
(`src/features/*/…Repository.ts`), and a provider decides which
implementation is bound. This directory is the fixture half of that seam:
MSW handlers plus the data they serve.

```
Component ──► useXxx() ──► XxxRepository (interface, hand-typed today)
                                 └── create<Xxx>Repository(client)
                                       ├── in tests/dev: intercepted by MSW (this directory)
                                       └── against a real backend: unchanged — only the handlers disappear
```

There is deliberately **one** implementation per repository, not two. It
always calls the real plane client (`cpClient`/`dpClient`/`consoleClient`,
task 027); "fixture vs real" is entirely a question of whether MSW is
running, never a second code path a component could pick the wrong one of.

## Layout

| Path | Contents |
|---|---|
| `server.ts` | The MSW node server (task 008), now registering `handlers/index.ts`'s array as its default set |
| `handlers/cp.ts` | Control-plane routes: home, solutions, targets, admin/RBAC, auth |
| `handlers/dp.ts` | Data-plane routes: instance inventory, designer, apply, actions, governance |
| `handlers/console.ts` | The Dynamic Metrics query surface + dashboards |
| `data/*.ts` | The fixture datasets each handler serves |

## The specified-vs-invented discipline (§9.3)

**Every fixture module in `data/` opens with a header** naming what is
**SPECIFIED** (with a citation — a decision id, a design section, or
`dynamic-metrics` §5.4–5.8's endpoint table) and what is **INVENTED** for
this fixture, with nothing behind it in any contract. Read that header
before trusting a field's shape.

At a glance:

| Module | Specified | Invented |
|---|---|---|
| `data/instances.ts` | The state vocabularies (`shared/vocab`, task 013) | Every instance field; the entire attention feed, its severity vocabulary, ordering and dedupe |
| `data/solutions.ts` | Package metadata shape (`UI-D11`) | `authoring.reviewedRows`/`totalRows` — no schema field carries these (§16 Q10) |
| `data/designer.ts` | The view-model field/section/action shape (plan §6.0.2, mirrored here — not yet imported from `contracts/`, which task 046 creates) | Every field's value/section/discriminator; rollout column names (§16 Q12); the diff/conflict sets. **Restart strategies moved out** — `features/designer/restartStrategies.ts` (FE-101) |
| `data/metrics.ts` | The query request/response shape and the 5 SPECIFIED error codes (`dynamic-metrics` §5.4–5.8) | The catalog's 8 entries; the deterministic series generator's synthetic values |
| `data/rbac.ts` | The permission catalog (`shared/authz/catalog.ts`, task 032) | Roles, principals, the scope tree, assignments, local users — `AdminRepository` is CONTRACT: TBD entirely (§16 Q4/Q5) |
| `data/targets.ts` | The Target/Binding model fields named in UI §A.12.6 | The three rows themselves |

## Rules

0. **Nothing here is imported as a VALUE by shipped code.** A `import type`
   from this directory is fine — TypeScript erases it. A value import pulls
   the whole fixture module into the production bundle, which is how
   `data/designer.ts`'s restart-strategy catalog shipped until FE-101 caught
   it (`npm run verify:production`, assertion `no-fixture-dataset`). If
   shipped code needs something that lives here, that thing is not a fixture
   — move it to a real module, as `features/designer/restartStrategies.ts`
   now is.
1. **No feature imports `msw`.** A feature imports its repository
   interface; only this directory and `server.ts` know MSW exists.
2. **`onUnhandledRequest: "error"`** (task 008) — an un-fixtured call fails
   the test that made it, rather than the fixture silently drifting from
   what a component actually calls. `server.test.ts` proves this for a
   request nothing here could ever match.
3. **A fixture is never promoted to a contract** (`FE-D7`). When a real
   OpenAPI contract lands, the corresponding hand type in
   `shared/api/contracts/` (task 034) is replaced WHOLESALE, and the
   repository file that used to import a local fixture type switches to
   importing the generated one — this directory's shape never becomes load
   -bearing on its own.
4. A field standing in for something genuinely unspecified carries a
   `CONTRACT: TBD` marker in the repository file that types it (not
   restated on every fixture row) so a grep for that string enumerates the
   plan's own debt (§9.3 rule 5, §9.2's table).

## Instance-state coverage

`data/instances.ts`'s 12-row `CACHE_INSTANCES` spans every health,
not-found, version and config state in `shared/vocab` — see that module's
header comment for the enumeration and `instances.test.ts` for the
assertion that every vocabulary key is reachable from at least one row.
