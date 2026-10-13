# Worked example — onboarding `db-designer`

What it takes to add a new solution to Solution Manager, written out concretely for a
hypothetical `db-designer` MI. Read [`architecture.md` §5.1](../../../../architecture.md) and
[`dynamic-ui/design.md` §3](../../../../dynamic-ui/design.md) for the rules; this directory is
the illustration.

> The linked `package.json` is a **canonical, machine-validated authoring example** for
> `contracts/solution-package.schema.json`. This walkthrough and `descriptor.yaml` remain
> illustrative. The real byte-upload endpoint and signed outer bundle are still `§16 Q10` TBD.

## What is specified, and what is not

| Half | File | Status |
|---|---|---|
| **Config schema, actions, metrics** — what the Designer renders | [`frontend/contracts/packages/db-designer/package.json`](../../../../../../frontend/contracts/packages/db-designer/package.json) | **Canonical example.** Validates against the root JSON Schema and browser/Python semantic gates. Its version arrays pass unchanged into version-filtered projection. |
| **Everything else** — render outputs, runtime attachments, external config, version detection, lifecycle, health, signature | [`descriptor.yaml`](descriptor.yaml) | **Illustrative.** §5.1 fixes the *block list*; nothing fixes the YAML spelling or the file layout. |

The split matters: the first half can be authored today against a real, contract-tested shape.
The second half is still proposed and needs its own schema and review.

## The three things a package must supply

`REQ-001` is the whole point — onboarding must not require console code. So everything
solution-specific arrives as **data**:

1. **A config schema** — field declarations, their types, defaults, predicates and layout.
   Rendered by the generic Designer. Comes from a **declared source adapter** (`SM-D20`), not
   from build generation: `curated-contract-tested` (as here and for Cache) or
   `FM_CONFIGURATION_TEMPLATE` (File Mesh). D3's "generate it from the build" premise is false
   — Cache ships no config metadata and adding an annotation processor is `I1`-forbidden.
2. **A telemetry contract** — health check plus metric mappings, driving generic collector
   primitives so no collector code is written. Every metric declares `metricType`, `unit` and
   `onUnavailable` (all required — `SM-D26`, `architecture.md` §10).
3. **Placement and lifecycle** — where SM writes the config file in the running artifact, how
   it restarts the MI, and how it detects the running version.

The vocabularies these draw on are **closed sets** (`UI-D14`); the data inside them is open.
`type`, `source.kind`, `action.kind`, `optionsSource.kind` and `mark` may only take members SM
already ships. Adding a member is an SM release. **Adding a solution is data.** If your package
needs a `source.kind` of `db-stats`, the design has gone wrong — `kind` names a *transport*
(`http_json`, `prometheus`, `solace_event`, `exec`, `jdbc_query`), never a solution.

## Proposed bundle layout

A `.sompkg` is a signed zip. This layout is a **proposal** — only the contents are specified:

```
db-designer-1.0.0.sompkg
├── descriptor.yaml          identity, attachments, lifecycle, health, external config
├── package.json             the SolutionPackage — components, fields, actions, metrics
└── META-INF/
    ├── manifest.sha256      digest of every entry
    └── signature.p7s        Solace signature over the manifest; absent ⇒ dev-mode only
```

The two halves of that bundle are the two files this example ships: `descriptor.yaml` here, and
`package.json` in `frontend/contracts/packages/db-designer/`.

## What `package.json` buys you

Running the example through the real projection engine (`frontend/contracts/view-model/project.ts`)
produces, with no SM code change:

- Three sections — **Connection**, **Pool**, **Mapping** — ordered by each field's `ui.order`.
- `DB_VENDOR` as a `discriminator`: with `postgres` selected, `SCHEMA` is visible and
  `SERVICE_NAME` is `visible: false`. Values in the inactive branch stay in the draft and are
  never rendered into the config file.
- `PASSWORD` as `masked: true` with `value: { kind: 'secretUnset' }` — the secret value never
  reaches the view model, the config tables, the diff or the export (`I6`).
- `REQUIRED` bind-tier validations on the three required fields that have no value.
- `POOL_MAX_SIZE` and `QUERY_TIMEOUT` behind the advanced disclosure, with defaults applied and
  `source: 'default'` so the UI can tell an authored value from a defaulted one.
- Two graphs, `db.pool.active` and `db.query.count`, from the `metrics[]` declarations.
- A server-side `TEST_CONNECTION` action. ⚠️ It takes `PASSWORD` as an input, so it **must**
  execute server-side and resolve the secret at render time — a browser-side connection test
  would ship the resolved credential to the client, which `I6` forbids outright.

## Adding `db-designer` for real — the order of work

1. **Author the config schema** and pin it with a **bidirectional key-set contract test**
   (`SM-D20`). Launching the artifact and checking it *accepts* your fields proves nothing:
   Spring ignores unknown properties silently. The test must assert both directions — every
   declared key is bound, and every bound key is declared.
2. **Discover the runtime attachment per binding.** Where the config file lands is discovered by
   pre-flight, not assumed, and a *persistent* attachment is required for each runtime you
   support. On Kubernetes this is blocked on open item 4 — the customer must exclude the
   SM-owned ConfigMap from Argo/Flux scope, or `additional-location` is unusable there.
3. **Declare the telemetry contract** and contract-test every mapping against the real artifact.
   Include the case that bites: *an SM-written config change must not silently break a declared
   metric.* Cache is the cautionary tale — SM's own Designer can switch `provider` to `redis`,
   after which `/api/cache/stats` 500s and `/api/cache/size` returns a constant `0`. That is why
   `when:` and `onUnavailable: degrade` exist; a flat zero is worse than an error.
4. **Record artifact identity** — digest → package version, from the operator or their CI. SM
   consumes it and never fetches with it.
5. **Sign and publish.** Then load it through the console.

Note what is *not* on this list: no React screens, no collector, no SM commits. That is
`REQ-001`'s acceptance gate — onboard a second MI from a package file alone, with zero commits
to SM.

## `I1` — audit before you author

Solution Manager may never require new **development** on the MI. Deployment configuration is
fair game: launch specs, env vars, mounts, and the contents of the config file SM writes
(including the `management.*` properties in `descriptor.yaml`'s `requiredProps`). Code changes,
build-plugin changes, new endpoints and new telemetry emission are not. Anything your package
needs from the MI must be satisfiable by something SM controls, **or** be optional with a defined
degradation — which is what `versionDetection.optional: true` and `onUnavailable: degrade` are for.

## Seeing it in the console

Run `npm run dev`, open `/solutions`, choose the linked `package.json`, review the validation
summary and select **Confirm and load**. The DEV package store adds the catalog card and makes the
package available to the generic Designer with no per-solution SM code. Edit the JSON and choose
it again to see the validation or rendering change.

Installing the parsed document into the in-browser catalog is DEV-only by construction:
`SolutionsRoute.tsx` imports the DEV repository wrapper inside a literal
`if (import.meta.env.DEV)` branch (`FE-D24`), so Vite drops that store/parser module graph from a
production build. Canonical preflight remains available in production because it is required
operator feedback; persistence will belong to the future backend.

The assembled Designer projects the selected package through the same generic engine used by the
fixture-backed solutions: `DB_VENDOR` discriminates `SCHEMA` from `SERVICE_NAME`, `PASSWORD` is
masked, Pool fields are advanced disclosure, and `TEST_CONNECTION` is a server-side action.

## "Load a package" in the console — what it does today

The `/solutions` screen's **Load a package** button is super-admin-only and accepts the canonical
`.json` document. The browser reads the bytes locally and checks:

1. valid JSON and the complete root JSON Schema;
2. at least one component and valid component, field, type, UI metadata, action and metric shapes;
3. predicate references and values, action inputs, duplicate keys/names, YAML subtree collisions,
   unreachable read-only predicate drivers and contradictory secret declarations;
4. provenance/dev-mode admission and overlap with a loaded runtime range for the same solution.

An invalid file shows every structural problem with its exact JSON Pointer path and keeps
**Confirm and load** disabled. A valid file shows solution/version/range and component, field,
action and metric counts so the operator can review what will be loaded.

⚠️ **This is not wired to a real byte-upload backend.** Only after local validation does the
current HTTP repository post file metadata (`fileName`, `fileSize`) to
`POST /cp/solutions/load`, because multipart versus pre-signed upload is still unspecified
(`§16 Q10`). In DEV, the browser parses the same bytes again through the canonical boundary,
holds the normalized package pending, and adds it to the DEV catalog only after confirmation.
The MSW response still simulates signed-versus-dev-mode from the file name.

Before production loading can work, the backend must define the real upload and
verify-versus-commit lifecycle, receive the bytes, verify the signature, repeat the canonical
schema/semantic gates, and persist the admitted package. Browser validation is feedback, not a
trust boundary.

**The admission rule itself is real** and worth knowing (`UI-D19`): a Solace-signed package is
always admitted. An unsigned one is admitted **only when the console is itself running in
development mode** — not merely because the server reports `devMode: true`. A dev-mode package
is listed in the catalog with an "Unsigned · dev-mode" banner and is deliberately **omitted from
the sidebar's solution list**.
