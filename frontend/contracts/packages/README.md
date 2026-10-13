# Solution Package examples and projection fixtures

This directory contains both upload-ready examples and older internal projection fixtures. Every solution
uses the same `<solution>/package.json` layout; auxiliary files belonging to that solution live beside its
package file. The canonical
multi-version shape (`UI-D25`) uses a non-empty ordered `packageVersion` array and optional component/field
`version` arrays. The `/solutions` load dialog validates that canonical structure directly against
[`contracts/solution-package.schema.json`](../../../contracts/solution-package.schema.json).

| Package | Status |
|---|---|
| `cache/` | Internal multi-version projection fixture for the v1 target solution. Its legacy presentation metadata is not an upload authoring example. |
| `file-mesh/` | Canonical multi-version example with ten reusable components and component/field version membership. |
| `db-designer/` | Canonical hypothetical authoring reference. Load it through `/solutions`; the browser validates it before showing the confirmation action. |
| `notifications/` | Canonical multi-version notification-service example. |
| `orders/` | Canonical multi-version order-service example. |
| `payments/` | Canonical multi-version payment-service example. |
| `reporting/` | Canonical multi-version reporting-service example. |
| `scheduler/` | Canonical multi-version scheduler-service example. |
| `search/` | Canonical multi-version search-service example. |

The root JSON Schema is the structural authority. Browser types in `../solution-package/` are consumption
types only; changing those types does not change the contract. The frontend validator compiles the root schema
into a checked standalone module and mirrors its semantic gates for immediate feedback. A future backend remains
responsible for repeating the same checks on the uploaded bytes.

`cache/draftValues.json` is not part of the package: it is the per-instance config-of-record `project()`
takes as its third argument. Originally extracted from the `CACHE_VIEW_MODEL` fixture so its own (now
deleted) equivalence test could reproduce that fixture's exact scenario, it is now also the shared
config-of-record `src/shared/testdoubles/data/packages.ts` seeds every fixture Cache instance's Designer
from (task 122) — one file, two purposes, never two copies.

## The invented v6

`cache/package.json` declares `"packageVersion": [6, 7]` — one file serving two schema versions, which is the
shape FE-118 introduced so an instance can say which version it is configured against.

⚠️ **v6 is authored demo history. It is not derived from the Cache MI, and no Cache release corresponds to
it.** The real package was written at v7 and has never had a predecessor. v6 exists so the console has a
package with more than one version in it — otherwise the version selector on the declare form, the
`Package version` grid column and `project()`'s version filter would all be exercised only by test fixtures
and never by anything an operator can see.

What v6 is defined to be: **v7 plus three fields added, minus one field replaced.** Three fields, all in the
Expiry / eviction area, are added in v7 (marked `"version": [7]`); one Sizing field is added in v7 and takes
over from a v6-only field it replaces (marked `"version": [6]`):

| Field key | Section | Version | Why it is a plausible version boundary |
|---|---|---|---|
| `expiry-header-key-pointer` | Expiry | `[7]` | Header-driven per-entry expiry — an addition to a cache that previously expired only by TTL. |
| `expiry-header-format` | Expiry | `[7]` | Meaningless without the pointer above; the two arrive together. |
| `max-weight` | Sizing | `[7]` | Weight-based eviction alongside the entry-count bound `max-size` already provided, replacing `max-size-bytes` below. |
| `max-size-bytes` | Sizing | `[6]` | The v6 byte-bound sizing field `max-weight` is authored to have replaced in v7 — added by `FE-121` so the version filter has a field to exercise in the *removed-in-v7* direction, not only *added-in-v7*. |

Every other component and field carries `"version": [6, 7]` explicitly. The `version` key is *optional* in the
contract — absent means "every version the package supports" — but both shipped packages write it on every
declaration anyway, so the file reads as the authoring reference for what a versioned package looks like.

**v6 is no longer a strict subset of v7** — `max-size-bytes` exists only at v6. `max-weight` and
`max-size-bytes` both carry a `default`, precisely so `FE-121`'s version-threading tests can assert that a
v6 draft seeds the v6-only default and never the v7-only one, and the reverse at v7 — a claim that needs
both sides to actually have a default to be worth anything. While it still existed,
`src/app/dev/cachePackageEquivalence.test.ts` asserted whole-object equivalence against `CACHE_VIEW_MODEL`
**at v7** (unaffected by a v6-only field) and, separately, the v6-projection: the fields added and removed
in each direction, never only "v6 is a strict subset." `FE-122` deleted both the fixture and its test —
Cache's version-threading coverage now lives in `mergeReset.test.ts` and `useDesignerDraft.test.ts` instead.

`db-designer/package.json` declares `"packageVersion": [1]`. A one-version package still uses the array shape;
package authors can add later members and scope components or fields with their optional `version` arrays.

## File Mesh package

`file-mesh/package.json` declares package versions 1, 2 and 3. Its 163 fields are distributed across ten
reusable components: file source, file sink, cache, server, Spring Boot Admin, Spring Cloud Stream, Solace,
application, logging and management. Component and field `version` arrays identify exactly which package
versions include each declaration; omitting `version` continues to mean all declared package versions. Every
field supplies `ui.helperText` derived from its declared label, section and value type.

The file passes the canonical schema and semantic validation gates in development mode. It supersedes the
older two-component, single-version Composer fixture that previously occupied this path.

## What authoring `cache/package.json` surfaced

Converting a real 26-field solution from a baked fixture to a package found three gaps, when
`CACHE_VIEW_MODEL` and its equivalence test still existed to check against. None is resolved here —
flagged, per `I3`/`I4`. The fixture and its test are gone (`FE-122`), but the gaps they found are not:

1. **`ActionDeclaration` carries no `label` and no `ui.section`** (§3.5). The fixture's own `VmAction`
   type required both. The Cache fixture put `TEST_BROKER_CONNECTION` in the `Broker` section and
   called it "Test connection"; a package can express neither, so `project()` lands it in `General`
   labelled from its key. This was the *only* thing that stopped the derived view model being
   byte-identical to the fixture — asserted explicitly in the (now-deleted) equivalence test.

2. **`when:` cannot express the Cache fixture's own documented condition.** The fixture comments
   `topic-key-levels` as "hidden until key-source is TOPIC/**COMPOSITE**" — an OR, which `UI-D2`'s
   grammar (equality only, implicitly AND-ed; no OR, no comparison, no negation) has no form for.
   Declared here as `when: { key-source: TOPIC }`, which is **narrower than the fixture's stated
   intent**. It happens to project identically in this scenario because `key-source` is unset and
   both render invisible — so no test catches it, and it would diverge the moment an operator
   selected `COMPOSITE`. Either the grammar needs an OR form or Cache's schema needs restating.

3. **A section's order is the first field's `ui.order`.** `project()` derives sections as an
   emergent grouping of `ui.section` strings and takes each one's position from the `ui.order` of
   the first field it sees for that section (project.ts documents this as "not pinned by the design
   doc"). So `ui.order` is written here as the **section's** index on every field, which is the only
   reading the engine actually honours. Field order within a section is declaration order.
