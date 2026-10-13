# File Mesh source/sink and connector types in the Solution Package

**Status: PROPOSED.** Adds `UI-D26` and `FE-D55`. Ratifies with the `UI-D*` and `FE-D*`
families respectively, in the order `CLAUDE.md` sets out.

**Date:** 2026-09-21

---

## 1. What this is

File Mesh instances are either a **source** or a **sink**, and each talks to one of four
file systems: **NWFile** (the default), **FTP**, **SFTP** or **GCS**. The Designer must
render the right fields for whichever pair the operator picks, and the list of pairs must
come from the Solution Package, never from the frontend.

This design does that **without changing `contracts/solution-package.schema.json`, without
changing the `when` grammar, and without any File Mesh identifier appearing in React.**
Every mechanism it uses already exists and is already gated.

### What it is not

- Not a new dynamic-form framework. `project()` is unchanged apart from a convergence fix
  that is already an open item (§8).
- Not a File Mesh screen. The Designer renders this package the same way it renders Cache.
- Not a schema revision. Zero new properties, zero new predicate operators.
- Not a backend or database change.

---

## 2. Evidence base

Two real `application-operator.yml` files, supplied 2026-09-21: a File Mesh **source**
(port 8094, `spring.application.name: SOURCE_TEST_V1`) and a **sink** (port 8083,
`File Connector(Sink)`). Both are NWFile deployments of connector 2.2.0.

Everything in §3 is read off those two files. Where they do not answer a question, §11
records it as open rather than guessing — `I2`.

### 2.1 The three structural facts

**Fact 1 — one config document per role, not per connector.** Both files carry *every*
connector subtree simultaneously, each gated by its own flag:

```yaml
file:
  source:                     # the sink file says `sink:`
    gcs:            { enabled: 0, projectId: …, retrySettings: { … } }
    sftp_settings:  { enabled: 0, ip: …, port: 22, user: …, password: … }
    ftp_settings:   { enabled: 0, ip: …, port: 21, user: …, secured: true }
```

There is no file-per-connector. This rules out modelling `COMP_SOURCE_SFTP` as a component
type: a component type implies a distinct configuration document, and File Mesh produces
one document per role.

**Fact 2 — NWFile has no subtree.** It is the state where all three flags are `0`, with the
location carried by the stream binding. Both samples are NWFile; the source's keystore path
names it outright (`SOURCE_NOSUBORG_Alice_FF_NWFILE_T3`).

**Fact 3 — connector keys are already namespaced by subtree.** `sftp_settings.ip`,
`ftp_settings.ip`, `gcs.projectId`. Two connectors never contend for the same key, so gate 9
(per-component key uniqueness) is satisfied with no prefixing, and `renderedPath` —
`configPath + "." + key` — comes out correct with no special handling:
`file.source` + `sftp_settings.ip` → `file.source.sftp_settings.ip`.

### 2.2 Correction to `vocabulary.txt`

`docs/design/dynamic-ui/evidence/vocabulary.txt` lists 17 `COMP_*` names including
`COMP_SOURCE_GCS` but no `COMP_SINK_GCS`. The sink YAML has a full `gcs:` block carrying a
sink-only key, `enableBulkComposeStrategy`. **GCS sink exists.** The vocabulary dump is
incomplete; it is evidence of the legacy template's component naming, not of the connector's
real capability surface, and it is not used by this design.

---

## 3. The model

| Axis | Modelled as | Justified by |
|---|---|---|
| **Role** (source / sink) | a **component** — `role`, and a distinct `configPath` (`file.source` / `file.sink`) | Fact 1: different root, different field sets, and the binder direction inverts |
| **Connector** (NWFile / FTP / SFTP / GCS) | a **`virtual` `discriminator` enum field** inside that component, with `when` on each branch | Fact 1: all subtrees share one document, so the choice is intra-document |
| **Role × connector** combinations | a field in the role's component *carrying* the connector's `when` | needs no new grammar; see §3.3 |

### 3.1 The discriminator

```jsonc
{
  "key": "connectorType",
  "type": "enum",
  "discriminator": true,
  "virtual": true,
  "default": "NWFILE",
  "required": true,
  "options": ["NWFILE", "FTP", "SFTP", "GCS"],
  "ui": { "section": "CONNECTOR", "order": 1, "label": "Connector type" }
}
```

`virtual: true` because no `file.source.connectorType` property exists — the field drives the
form and the predicates and is never written (`UI-D24`). The **options list is the package's**,
so a package version supporting only `["NWFILE", "SFTP"]` yields a two-item dropdown with no
frontend change. That is the requirement "the frontend must not hard-code which connector
types are supported", satisfied by construction.

### 3.2 Branch fields

```jsonc
{ "key": "sftp_settings.enabled",  "type": "int",    "default": 1,
  "when": { "connectorType": "SFTP" }, "ui": { "readOnly": true } },
{ "key": "sftp_settings.ip",       "type": "string", "required": true,
  "when": { "connectorType": "SFTP" } },
{ "key": "sftp_settings.port",     "type": "int",    "default": 22, "min": 1, "max": 65535,
  "when": { "connectorType": "SFTP" } },
{ "key": "sftp_settings.password", "type": "secret",
  "when": { "connectorType": "SFTP" } }
```

### 3.3 Combination-specific fields need no new grammar

`SOURCE + SFTP` is "a field declared in `COMP_SOURCE` whose `when` names `SFTP`". The role
axis is the component; the connector axis is the `when`. The one combination field the
evidence actually contains:

```jsonc
// COMP_SINK only ⇒ this is the SINK+GCS field
{ "key": "gcs.enableBulkComposeStrategy", "type": "bool", "default": true,
  "when": { "connectorType": "GCS" } }
```

No `all` block is needed. (A `when` object is already an implicit AND, so `all` is available
anyway for any genuinely two-key case.)

### 3.4 Mutual exclusion is structural

A single enum can hold one value, so at most one connector branch is ever visible, so at most
one `*_settings.enabled: 1` is ever written. Three independent `enabled` booleans would have
permitted FTP and GCS at once and would have required an exclusion rule the grammar cannot
express. **Choosing the enum is what removes the need for a schema change.**

### 3.5 Inactive branches are omitted, not zeroed

Confirmed with the requester 2026-09-21: the connector treats an absent subtree as disabled.
So selecting SFTP writes `sftp_settings.*` and emits no `ftp_settings:` or `gcs:` block at
all. This is what the engine already does for free — a `when`-hidden field is excluded from
`rendered` ([`project.ts`](../../../contracts/view-models/src/project.ts) `excluded` /
`reason: 'hidden'`) — and it is the reason no "write a value for a hidden field" concept is
needed. **If that assumption is ever falsified, this is the clause to revisit**, and the
alternative is a genuine `UI-D*` addition.

---

## 4. `UI-D26` — role is a component, connector is a discriminator

**Decision.** A solution whose instances differ by *role* declares one component per role,
distinguished by `role` and `configPath`. A solution whose instances differ by a choice
*within* one configuration document declares that choice as a `virtual` `discriminator` enum
field and gates the dependent fields with `when`.

**Rationale.** The component boundary tracks the configuration document; the discriminator
tracks a branch inside one. Applying either mechanism to the other axis breaks something
real: role-as-discriminator would force `file.source.*` and `file.sink.*` into one
`configPath`, and connector-as-component would invent documents the connector does not read.

**Generic, not File Mesh.** The rule names no solution. Any solution with a
document-per-variant axis and a branch-within-document axis maps onto it.

**Supersedes nothing.** Consistent with `UI-D1` (packages declare component types),
`UI-D2` (equality + AND), `UI-D4` (retention), `UI-D24` (virtual fields).

---

## 5. `FE-D55` — an instance binds to its component types

**Problem.** [`deriveComponentSelection`](../../../frontend/src/features/designer/DesignerPage/componentSelection.ts)
returns one entry per declared component, a degenerate case its own header documents
(`one-of-many` composition "is not invented"; §16 Q10 open). With source and sink in one
package, an instance would render both — but a File Mesh instance is one Spring Boot app on
one port, never both.

**Decision.** `deriveComponentSelection` takes an optional set of component types declared by
the instance. When present, it returns only those, in package declaration order. When absent,
it returns today's result unchanged.

**Backward compatibility is the fallback, not a migration.** Cache and every other shipped
package declare component sets that render correctly today; none of them supplies a binding,
so none of them changes behaviour. This is proven by test, not asserted (§10).

**Still open.** §16 Q10 — the wire contract for declaring an instance — remains open. This
decision defines how the Designer *consumes* a binding; it does not define how one is
*authored*. Until Q10 closes, the binding is supplied by the fixture layer.

---

## 6. Why no schema change

Each gate, against the proposed package:

| Gate | Requirement | Status |
|---|---|---|
| 4 | `when` keys name a field in the same component | `connectorType` is declared in both components |
| 5 | predicate entails its target's own predicate | `connectorType` carries no `when`; nothing to entail |
| 5b | `when` value is a member of the target's options | `"SFTP"` ∈ `["NWFILE","FTP","SFTP","GCS"]` |
| 6 | a discriminator has an enumerable domain | it is an `enum` |
| 9 | keys unique within a component | subtree namespacing (Fact 3) |
| 11 | `options` XOR `optionsSource` | static `options` only |
| 12 | a keyed-on discriminator has a default or is settable | `default: "NWFILE"`, and settable |
| 13 | secrecy declared one way | `type: secret`, never `secret: false` alongside |

Nothing in the proposal reaches a gate it does not already satisfy.

---

## 7. Why the condition engine is not extended

The brief asked for `equals`, `notEquals`, `in`, `all`, `any`. `UI-D2` gives equality and
implicit AND — so `equals` and `all` are present. The other three are **not added**:

**No evidence requires them.** FTP and SFTP share no field; they have parallel subtrees, so
each field carries a single-value `when`. A single enum discriminator makes `in` redundant by
construction: there is no "this field applies to FTP or SFTP" case, because there is no field
that belongs to both.

**They are not cheap.** Gate 5 proves reachability by entailment over equality pairs; gate 5b
checks option membership; gate 12 proves a keyed-on discriminator is settable. All three
assume a conjunction of equalities. `any`/`notEquals` turn each from a lookup into a
satisfiability check, across four consuming modules, for a case the evidence does not contain.

**If a real case appears**, it supersedes `UI-D2` with that evidence attached. Pre-building it
is the kind of speculative generality this repo's review history already penalised.

---

## 8. The `project()` convergence (open item 36)

Two implementations disagree about hidden fields:

- [`contracts/view-models/src/project.ts`](../../../contracts/view-models/src/project.ts) —
  `validations: visible ? bindAndSemanticValidations(…) : []`
- [`frontend/contracts/view-model/project.ts`](../../../frontend/contracts/view-model/project.ts) —
  `validations: evaluateValidations(field, …)`, evaluated regardless of `visible`

Today this is latent: no shipped package pairs `required` with `when`. **This design makes it
live** — `sftp_settings.ip` is `required` and hidden whenever the connector is not SFTP. Under
the frontend implementation, selecting NWFile would block submit on a control nobody can see.

**The frontend implementation converges on the canonical one: a non-visible field produces no
validations.** This is in scope here because this design creates the defect's first trigger.
It is registered as open item 36 and flagged in commit `9951dec`.

---

## 9. State handling and validation (already decided; restated for completeness)

**Hidden values are retained in UI state and stripped from the saved payload.** Not
re-decided here — it is the shipped behaviour, and it is the safest of the three options the
brief listed:

- Retained: `useForm({ shouldUnregister: false })` seeded with the whole draft
  ([`useDesignerDraft.ts`](../../../frontend/src/features/designer/form/useDesignerDraft.ts)) — `UI-D4`.
- Stripped: `payload` derives from `visibleValues()`, which filters on `field.visible`.
  `getValues()` is called in exactly one place and ESLint forbids it elsewhere in `src/`.
- On a discriminator flip: `mergeReset` writes the newly-active branch's declared defaults,
  merged over rather than replacing other instances' values (`FE-D17`).

Worked example — SOURCE+SFTP, operator fills host/port/user/path, then switches to NWFile:
the four values stay in memory, disappear from the form, and are absent from the save.
Switching back restores them. Saving while on NWFile never writes `sftp_settings.*`.

Deleting immediately loses work on a mis-click. Deleting only at save time leaks inactive
branches into the rendered config. Retain-and-strip avoids both.

**Validation follows visibility** (§8): a hidden `required` field never blocks submission.

---

## 10. Backward compatibility, and how it is proven

Every addition is optional:

| Surface | Guarantee |
|---|---|
| `solution-package.schema.json` | unchanged — no new property, no new operator |
| Packages with no `when` | behave exactly as now; `evaluate(undefined, …)` returns `[]` |
| Instances with no component binding | `deriveComponentSelection` falls back to today's result |
| `renderedPath` / save payload format | unchanged: `configPath + "." + key`, sorted, byte-stable (`FE-D17`) |

Proven by:

1. `./contracts/run-checks.sh` passes unchanged, including the 17 negative cases.
2. The existing view-model fixtures pass byte-identically — the engine's behaviour for
   packages without predicates is untouched.
3. A new fixture pins `deriveComponentSelection`'s fallback: a package with no binding
   returns one entry per component, exactly as before.
4. A new negative fixture pins the §8 rule: a hidden `required` field yields no `REQUIRED`
   validation in **both** implementations.

---

## 11. Scope, and what remains open

### In scope

The `file.*` tree for both roles — 50 source and 41 sink config fields as counted off the
samples, plus one `connectorType` discriminator in each — and a minimal binding component per
role (§11.1).

Source, by subtree: `general` 11, `command_center` 10, `gcs` 6 (3 of them under
`retrySettings`), `sftp_settings` 6, `ftp_settings` 6, `file_to_event` 5,
`directory_wildcard` 2, `solace_out` 2, `scheduler` 1, `directory_replication` 1.
Sink: `general` 9, `command_center` 9, `gcs` 7, `sftp_settings` 6, `ftp_settings` 6,
`file_owner_permissions` 4.

### The binding carve-out

The location field lives at `spring.cloud.stream.bindings.input-0.destination` (source) /
`output-0.destination` (sink), outside `file.*`. Per the requester it is **the same field for
all four connectors** for this pass. Since `spring.*` is otherwise deferred, exactly those two
keys are carved out as a second, two-field component per role
(`configPath: spring.cloud.stream.bindings`). An instance composes `COMP_SOURCE` +
`COMP_SOURCE_BINDING`; `ComponentSelection` is a list, so this is ordinary `UI-D1`
composition, not a special case.

⚠️ **Assumption on record.** Treating one path field as adequate for all four connectors is a
working assumption, not a verified connector fact — both samples are NWFile. Verify against an
FTP/SFTP/GCS deployment before ratifying `UI-D26`.

### Deferred to a second pass

`server.ssl.*`, the rest of `spring.*`, `solace.*`, `logging.*`, `management.*` — as
additional components, unblocked by this design.

### Secrets — `I6`

`sftp_settings.password`, `ftp_settings.password`, `solace.java.client-password` and
`server.ssl.key-store-password` appear in clear in the samples. They must be declared
`type: secret` and hold a `secretRef://` reference; the engine refuses a literal outright
(`SECRET_NOT_A_REFERENCE`). **SM cannot round-trip the supplied YAMLs verbatim** — those
values must move to the secret backend first. This is a property of `I6`, not a limitation
introduced here.

### Data defects found in the samples

1. The source's `command_center` block declares both `useOutputDestinationSolaceCredentials: true`
   and `useoutputdestinationsolacecredentials: 'true'`. Spring's relaxed binding treats these
   as one property; the package must declare it once. Which casing and which type (`bool` vs
   `string`) is authoritative is **open**.
2. `file_type: 4`, `dest_file_name_type: 3`, `wildcard_type: 0`, `multiple_files_flag: 0` are
   magic integers. They should be `enum` with integer `options` and authored labels, but the
   sample files do not define the value sets. Pending that, they are declared `int` with the
   observed value as `default`. **Open.**

### Open questions carried forward

| # | Question | Blocks |
|---|---|---|
| Q-A | Does the location binding differ per connector? | ratification of `UI-D26`'s carve-out |
| Q-B | Which casing/type of `useOutputDestinationSolaceCredentials` is authoritative? | that one field |
| Q-C | What are the value sets behind the magic integers? | enum labelling, not rendering |
| Q-D | §16 Q10 — the declare-instance wire contract | how a binding is *authored* (`FE-D55` consumes one either way) |

None blocks implementation; each is a field-level correction, not a structural one.

---

## 12. Payload examples

**SOURCE + NWFile**
```yaml
file.source.general.adapter_id: SOURCE_TEST_V1
file.source.general.max_bandwidth_limit: 100000000
file.source.scheduler.restart_time_sec: 10
spring.cloud.stream.bindings.input-0.destination: /home/…/file_paths.cfg
# no gcs:, no sftp_settings:, no ftp_settings:
```

**SOURCE + SFTP**
```yaml
file.source.general.adapter_id: SOURCE_TEST_V1
file.source.sftp_settings.enabled: 1
file.source.sftp_settings.ip: sftp.example.com
file.source.sftp_settings.port: 22
file.source.sftp_settings.user: svc_filemesh
file.source.sftp_settings.password: secretRef://fm/source/sftp-pw
file.source.sftp_settings.strictHostKeyChecking: "no"
spring.cloud.stream.bindings.input-0.destination: /inbound/feed
```

**SINK + SFTP** — the same branch under `file.sink.*`, plus sink-only
`general.auto_create_directory`, `general.dest_file_name_type`, `file_owner_permissions.*`,
and `spring.cloud.stream.bindings.output-0.destination`.

**SINK + GCS**
```yaml
file.sink.gcs.enabled: 1
file.sink.gcs.projectId: GCS_PROJECT_ID
file.sink.gcs.credentialsFilePath: /etc/filemesh/gcs-creds.json
file.sink.gcs.enableBulkComposeStrategy: true        # sink-only
file.sink.gcs.retrySettings.maxAttempts: 5
file.sink.gcs.retrySettings.retryDelayMultiplier: 3
file.sink.gcs.retrySettings.maxDurationMinutes: 2
spring.cloud.stream.bindings.output-0.destination: gs://bucket/prefix
```

---

## 13. Before and after

| | Before | After |
|---|---|---|
| `solution-package.schema.json` | `discriminator` / `when` / `virtual` present | **identical** |
| Predicate grammar | equality + implicit AND | **identical** |
| `frontend/contracts/packages/file-mesh/package.json` | synthetic filler (`FILE_MESH_MANAGER_1`, `"value_5"`), two components, zero `when` | real fields from the operator YAMLs; two role components + two binding components; four connector branches each |
| Component selection | every declared component renders | instance binds to its components; unbound instances render as before |
| `frontend` `project()` | validates hidden fields | matches canonical — hidden ⇒ no validations |
| Cache / orders / other packages | — | untouched, behaviour unchanged |
| React source | no File Mesh strings | **no File Mesh strings** |

**Added:** one discriminator field per role component, one `when` per branch field, one
optional argument to `deriveComponentSelection`. Nothing else.

---

## 14. Finding: `frontend/contracts/packages/` holds two dialects

Found while confirming this design is solution-agnostic. **Recorded, not fixed here.**

Validating all nine frontend packages against the canonical schema:

```
cache          FAIL (29 gate violations)
db-designer    FAIL (13 gate violations)
file-mesh PASS · notifications PASS · orders PASS · payments PASS
reporting PASS · scheduler PASS · search PASS
```

Two incompatible spellings of the same contract are in the tree:

| | Canonical (`solution-package.schema.json`) | The cache / db-designer spelling |
|---|---|---|
| Presentation metadata | nested: `ui: { label, helperText, advanced }` | flat on the field: `label`, `helperText`, `advanced` |
| `component.role` | enum `source \| sink \| none` | db-designer uses `"primary"` |
| `column` | no `required` / `default` | db-designer's table columns carry both |

The flattening is deliberate (commit `b13c241`) — it is the shape the renderer consumes after
[`normalize.ts`](../../../frontend/contracts/solution-package/normalize.ts) maps canonical
nested metadata onto presentation fields. What is *not* deliberate is storing two packages in
the post-normalization shape alongside seven in the pre-normalization shape, under one
directory, with nothing in CI distinguishing them. `role` diverges the same way:
`solution-package/types.ts` restates the canonical enum, while
`view-model/types.ts` types `role` as bare `string`, so `"primary"` passes one and fails the
other.

**Same family as open item 36** (divergent `project()` implementations): one contract, two
implementations, no gate proving they agree.

### 14.1 The engine reads a *third* shape, and that is the part that bites

The frontend engine is not a reader of either dialect cleanly. It reads `label`, `helperText`
and `advanced` **flat on the field**, but `section`, `order` and `readOnly` **nested under
`ui`** ([`project.ts`](../../../frontend/contracts/view-model/project.ts) lines 218, 236, 244,
271, 303). `toFrontendPackage()` produces exactly that hybrid from a canonical package, and
the dev-upload path calls it ([`parsePackage.ts`](../../../frontend/src/app/dev/parsePackage.ts)).
The **bundled**-package path does not — it casts the JSON straight through
([`packages.ts`](../../../frontend/src/shared/testdoubles/data/packages.ts)).

So the flattening of cache and db-designer was not a dialect preference; it was a workaround
for a missing normalize call (recorded at the time as L19 / open item 36).

> **Correction, 2026-09-21.** An earlier draft of this section said file-mesh's authored
> `ui.label` was being ignored "right now". That was true of the *working tree* — which held an
> uncommitted flat→canonical conversion — not of `HEAD`, where file-mesh is authored flat and
> renders correctly. The claim is retracted. What holds is the conditional: **any canonical
> package that reaches the engine without `toFrontendPackage()` renders raw keys**, and the
> uncommitted file-mesh was exactly that case.

**Consequence for this design: one prerequisite task.** Authoring 93 new fields into that trap
is not acceptable, so the implementation normalizes bundled packages at the boundary and
converts cache and db-designer back to canonical. That makes all nine packages pass
`validate-package.py` and removes the workaround rather than extending it. `UI-D26` uses
`role: source | sink`, which is canonical either way.

**What stays open** is the governing question, not the packages: whether
`frontend/contracts/packages/` holds authored or normalized documents, and a CI gate proving
it. That gate does not exist — nothing in `.github/workflows/frontend.yml` validates these
files, which is why two of them drifted unnoticed.

**Proposed as a new open item.** Fixing it means deciding whether
`frontend/contracts/packages/` holds authored packages or normalized ones, then gating that
choice in CI — separate work, not in this plan.

### 14.2 What executing it found

Recorded as `FE-149` ([report](../../plans/frontend/reports/task-149-report.md)).

**A regression in `cache`.** Commit `b13c241` ("flatten db-designer/cache fields") also rewrote
`cache/package.json`'s versioning. Against the last good state (`c2538ba`) it made exactly four
collateral changes: root `packageVersion` `[6, 7]` → `[2.3, 2.4]`; every field's `version` scope
dropped; the v6-only `max-size-bytes` field deleted; and `max-weight`'s default (`536870912`)
deleted. Four `FE-121`/`FE-D37` tests were red on the branch because of it. This is the
**third instance of one failure family** in this area — a contract restated by hand in several
places with no gate proving they agree: open item 36 (two `project()` implementations), the
dialect split above, and now a version scheme that `cache/package.json` (`[2.3, 2.4]`),
`solutions.ts` and every cache instance (`[6, 7]`) disagreed on. The repair restored `[6, 7]`
and the four items; the history, not judgement, decided which scheme was right.

**A relationship to L19 that must be stated, not left implicit.** [L19](../common/lessons.md)
and open item 36 recorded an interim rule: *until the two `project()` implementations are
reconciled, author `cache`/`file-mesh`/`db-designer` flat.* `FE-149` does the opposite —
canonical data, normalized at one boundary. That discharges L19's "until" from the data side,
but it is a change to a recorded position, and L19 and item 36 now say so. **It does not
reconcile the two engines**, and it does not answer which one is the contract.

**The baseline was red.** Four failing tests and one lint error existed at `HEAD` before any of
this work; the plan had assumed a green branch. The lesson is recorded as L20.

