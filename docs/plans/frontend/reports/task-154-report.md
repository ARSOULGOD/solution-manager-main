# FE-154 · Location bindings and File Mesh instances — Report

Sixth task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §11.1).
Adds the two-key `spring.cloud.stream.bindings` carve-out per role, and exercises `FE-D55` end to end through
real fixture instances rather than a synthetic `ComponentSelection`.

---

## 1 · What was built

- `COMP_SOURCE_BINDING` / `COMP_SINK_BINDING` — one field each, `input-0.destination` / `output-0.destination`,
  `configPath: spring.cloud.stream.bindings`, `required`. The package is now `components=4 fields=94`, PASS.
- Two bound instances, **`fm-source-1`** and **`fm-sink-1`**, `componentTypes: ['COMP_SOURCE',
  'COMP_SOURCE_BINDING']` / `['COMP_SINK', 'COMP_SINK_BINDING']`.
- `InstanceRow.componentTypes?`, threaded through the `dp` MSW handler into `DesignerLoad.componentTypes` —
  the wire the Designer and Governance both already read (`FE-151`).

## 2 · A finding that changed the plan mid-task

The plan said to add the two instances to `CACHE_INSTANCES`. `instances.test.ts` already asserts
`expect(CACHE_INSTANCES).toHaveLength(12)`, citing the reference spec's own closed enumeration (§A.13.2). Adding
rows there would have broken a **documented, deliberate invariant**, not merely a number — that array is the
reference's ported 12-instance set covering every health/version/config-state combination, and instance count is
part of what it pins.

**Correction, as built:** a separate `FILE_MESH_INSTANCES` export, merged explicitly by consumers that want every
instance across every solution — `devPackageHandlers.ts`'s `allInstances()` already does this generically for
`CACHE_INSTANCES` plus session-declared instances, so `FILE_MESH_INSTANCES` joins that merge. The default MSW
fixture handler (`shared/testdoubles/handlers/dp.ts`) gets its own `ALL_INSTANCES = [...CACHE_INSTANCES,
...FILE_MESH_INSTANCES]`, since it does not go through `devPackageHandlers.ts` at all. `instances.test.ts`'s
twelve-count assertion is untouched and still passes.

## 3 · A second finding: a boundary violation in my own first draft

The first cut of the end-to-end test lived in `shared/testdoubles/data/` and imported
`deriveComponentSelection` from `features/designer/...`. `eslint-plugin-boundaries` rejected it —
`shared` may not depend on `feature` (`CLAUDE.md`: module boundaries are enforced, not a suggestion). Moved to
`src/features/designer/DesignerPage/componentSelection.fileMesh.test.ts`, which already legitimately imports
`@shared/testdoubles/*` (the allowed direction). Caught by lint before commit, not shipped.

A second, trivial lint finding in the same pass: an import inserted mid-block in `dp.ts` broke `import/order`
(a stray edit placed a `const` between two import lines). Fixed by moving the declaration after the imports.

## 4 · What deliberately did not change

- `CACHE_INSTANCES` itself — zero rows added, zero rows changed. `instances.test.ts` proves this.
- Every consumer that reads `CACHE_INSTANCES` alone and was never meant to see other solutions (e.g. the RBAC
  hierarchy derivation, which is explicitly Cache-org-scoped) — untouched, on purpose.
- The `§16 Q10` gap. `componentTypes` is still fixture-supplied; no wire contract for authoring one exists.

## 5 · Evidence

| Check | Result |
|---|---|
| `deriveComponentSelection` fed from the real `fm-source-1`/`fm-sink-1` fixture rows, through `packageForSolution` | `fm-source-1` → `['COMP_SOURCE', 'COMP_SOURCE_BINDING']`; `fm-sink-1` → `['COMP_SINK', 'COMP_SINK_BINDING']`; `cache-prod-01` (no binding) → `['cache-engine']`, the fallback |
| `instances.test.ts` (the twelve-count guard) | still PASS |
| `validate-package.py`, file-mesh | PASS `components=4 fields=94` |
| all nine packages | 9 / 9 PASS |
| Targeted suite (9 files: instances, componentSelection ×2, packages, DesignerPage, GovernancePage, InstancesPage, devPackageFlow, msw server) | **9 / 9 files, 118 / 118 tests**, run twice (before and after the boundary fix) |
| `typecheck` (whole tree) | clean |
| `lint` (whole tree, `--max-warnings=0`) | clean |
| No File Mesh identifier in non-fixture `src/` | confirmed — every grep hit is a comment or a pre-existing gallery row, no branching |

## 6 · Manual verification (plan Task 154 Step 6) — done, in a real browser

Completed in a follow-up pass, after the host's sleep-cycle problem was understood well enough to attempt it.
`playwright` (already a repo dependency) drove headless Chromium against `npm run dev` (Vite, MSW browser
worker active — the same `dp`/`devPackageHandlers` fixture wiring `FE-154` modified). Seven screenshots,
`console --errors` checked on every page: **zero console errors throughout.**

- **Designer, `fm-source-1`.** Composer canvas shows exactly two nodes — `COMP_SOURCE` (32 fields) and
  `COMP_SOURCE_BINDING` (1 field). **No sink node.** Inspector: `Connector type` defaults to `NWFILE`, the
  authored helper text renders verbatim, `Adapter ID` blocks as `required` with no invented default (the `I2`
  decision in `FE-152` §2, visible on screen, not just in a fixture).
- **Designer, `fm-sink-1`.** `COMP_SINK`, "Search 42 fields…" (41 + discriminator, matching `FE-153`'s count
  exactly). `Auto-create directory: 1`, `Destination file name type: 3` — the sink-specific defaults authored in
  `FE-153`, correct and distinct from the source's.
- **Connector switch, live.** Selecting `SFTP` on `fm-source-1`: the dropdown itself offers exactly
  `NWFILE / FTP / SFTP / GCS` — the package's own list, nothing hard-coded. The source node's field count goes
  **32 → 38**, exactly +6 — the SFTP branch's six fields (`enabled/ip/port/user/password/strictHostKeyChecking`)
  becoming visible, matching `FE-152`'s authored subtree tally precisely. `Host`/`Username` flip from absent to
  visible-and-blocking; `Port` shows its authored default `22`.
- **Governance, both instances.** The log viewer's `Component` selector shows `COMP_SOURCE` on the source
  instance and `COMP_SINK` on the sink instance — the exact `FE-151` fix (the second `deriveComponentSelection`
  consumer this plan found and corrected), now seen working outside the test suite that proved it.

**One gap surfaced, out of this plan's scope, worth flagging rather than silently accepting:** File Mesh does
not appear in the left-nav solutions sidebar. That list is drawn from `shared/testdoubles/data/solutions.ts`'s
`SOLUTIONS` catalog, a different array from the `InstanceRow`/`FILE_MESH_INSTANCES` fixtures this plan added —
`SOLUTIONS` still lists only `cache`. An operator can reach these instances by direct URL
(`/solutions/file-mesh/instances/fm-source-1/designer`) but not by clicking through the sidebar. Consistent with
§16 Q10 (no declare/catalog flow exists for File Mesh yet) — this plan proves the *rendering* is correct once on
the page; it does not claim File Mesh is discoverable through normal navigation.

**Retention and non-blocking, driven live (Step 6 items 4–5).** Typed `sftp.example.com` into `Host` on SFTP,
switched to NWFile, switched back: the field read back **`sftp.example.com`** — `UI-D4` retention holds outside
the test suite that proved it. At NWFile, the only red/required field on screen was the pre-existing `Adapter ID`
(never filled, by design — `I2`); the SFTP branch's `Host`/`Username` contributed no blocking issue while hidden,
and the CONNECTOR section's field count and the source node's field count both returned to their NWFile values
(`1` and `32`) exactly. `FE-150`'s fix holds in the running app, not only in the fixture that pinned it.

**All five items of plan Task 154 Step 6 are now confirmed in a real browser**, not inferred from unit tests.

## 7 · Open

Unchanged from `FE-152`/`FE-153`: Q-E, Q-F, Q-G, plus spec §11.1's own flag — the single-location-field
assumption for all four connectors remains unverified against a non-NWFile deployment.
