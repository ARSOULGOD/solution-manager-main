# D-ACCEPT: reading the live file back into field values, without ever storing a read-back secret

**Status: PROPOSED, UNBASELINED.** Written 2026-10-05 by [plan 010](../../plans/backend/010-dp-data-model-and-remote-flow.md)
Task T10.3, on branch `p010/designs` off `integration/dp-remote-demo` @ `2ac941a`. It is the "read-back/parse design"
that [`OR-45`](../DECISIONS.md) says `accept-runtime` needs first. It decides nothing the owner has not ruled. The
owner decides on behalf of the architect group ([`OR-69`](../DECISIONS.md)). This note **edits no other file**
(§10).

> **Ruled 2026-10-06 (`OR-104`), and built by plan 010 T10.4.** The owner answered §9 on behalf of the architect group
> ([`DECISIONS.md`](../DECISIONS.md) `OR-104`). **Q-A1 `U-2`**: accept the mappable values; undeclared keys are listed by path
> only in `notAccepted[]` (capped at 200, `truncated`), and the response states the next apply removes them. **Q-A2 `V-4`**
> ("since it is useful for user to identify if password is different"): a read-back secret is **never stored**; the draft
> keeps the current `secretRef`; the data plane compares the file's value with the stored one **in memory** through a
> compare-only `dp-api` port (`SecretCompare`) and reports only `same` or `differs`; the value never leaves dp-core. **Q-A3
> `X-1`**: accept stages an `accept-runtime` generation and writes nothing to the host; `fileBytesDifferFromRendered` says so.
> **Q-A4 `M-1`**: exact declared kebab paths only; other spellings are reported `undeclared-key`, not mapped. **Q-A5 (a)**: an
> unreadable host is `503 SERVICE_UNAVAILABLE`, whose contract text is widened to "a dependency did not answer; retry".
> **Q-A6 is not ruled** (the owner asked for more detail): until it is, a `yaml`-typed field is reported
> `yaml-field-not-accepted` and keeps what it holds (no v1 package has one).
>
> The four drafts below are now **`BE-D39`** (A-1, the read-only seam operation), **`BE-D40`** (A-2, the pre-scan),
> **`BE-D41`** (A-3, the secret rule, amended by `V-4`) and **`BE-D42`** (A-4, stage and report), allocated in
> [`decisions.md`](decisions.md) at this task; built as `BE-D36`–`BE-D39` and renumbered at the S8-S11 merge, after T9.3 (`OR-103`) took `BE-D36`–`BE-D38`. Every one stays
> **PROPOSED**; the rulings above bind the build, they do not ratify the decisions.
>
> **Where the build departs from the text below (each recorded, none re-decides a ruling).**
> 1. **The round-trip check (§3.3 item 7) runs in the gateway, not the data plane.** It compares a mapped value with what the
>    server's own projection renders for it, and only the gateway holds the projection (the DP never reads the package). Same
>    check, same `422 round-trip`, different module.
> 2. **A mapped value equal to the field's effective value leaves the draft alone** (new, §3.3 item 5's other half). A file SM
>    itself rendered carries every defaulted field; writing those into the draft would mint a new generation for a file that
>    says nothing new, and guard 1 (`unchanged`) would never fire for the most ordinary accept.
> 3. **`readConfig` is a `default` method on `RuntimeAdapter`** (throws `config.read.unsupported`), not abstract (§10 item 2):
>    some fifteen test classes delegate the seam, and the two certified adapters override it. The contract kit pins it for every
>    certified adapter; `RuntimeSeamTypesTest` now pins ten operations.
> 4. **`SmOverlayKeys` was a seam with a stand-in, and the stand-in is gone** (§4.2, §10 item 6): D-LOG's `OverlayKeys` was not in
>    this tree when T10.4 was built, so the stand-in owned `management.*` and `logging.level.*`. At the S8-S11 merge
>    `RuntimeReadBackConfiguration.smOverlayKeys` was pointed at the shared `OverlayKeys.owns` (`BE-D37`) and
>    `SmOverlayKeys.DEFAULT` was deleted. The shared function is narrower than the stand-in: it owns exactly the two keys SM
>    writes (`management.endpoints.web.exposure.include`, `management.endpoint.loggers.access`), so `management.server.port`
>    and `logging.level.*` are now reported `undeclared-key` (a level change is configuration, `OR-103`).
> 5. **The contract (§5) is tightened by one**: `fileBytesDifferFromRendered` and `truncated` are *required* (the server always
>    sends them), and a `secret-not-read` entry may carry `comparison: same|differs`. `maxLength: 256` bounds a path.
> 6. **File-level refusals are `422` except the host not answering**: an absent file (`file-absent`) or one that is not a regular
>    file or is over the 1 MiB bound (`file-not-readable`) is the host having answered, so it is a refusal of the file, not a
>    `503`. An instance with no staged configuration has nothing to read the file against (`422`).
> 7. **A `declared` `SnakeYAML` dependency** (§10 item 4) is one pom line for an artifact already on the classpath through
>    `jackson-dataformat-yaml`; no artifact is new (`I8`).

**Decisions drafted here** carried the literal placeholder `«next free BE-D»` (§8) until T10.4 allocated them (note above). No id
was reserved ahead of that ([`OR-11`, `OR-17`](../DECISIONS.md)).

**Binding inputs, which this design implements and does not re-decide:**

| Ruling | What it fixes here |
|---|---|
| [`OR-45`](../DECISIONS.md) | `accept-runtime` is contracted and built; it needs a read-back/parse design first. |
| [`OR-60`](../DECISIONS.md) | `acceptRuntimeConfig.placementId` is added and required: it names which placement's file is read back. |
| [`B/D12`](../DECISIONS.md) 🔒 | Config drift is alert-only with a manual UI reconcile: *re-apply desired, or adopt actual as a new generation*. Never silently auto-revert a human. |
| [`OR-34`](../DECISIONS.md) | The server re-projects a draft, refuses a mismatch or an undeclared key, and stores its own result. |
| [`OR-33`](../DECISIONS.md) | Config of record is immutable per-generation snapshots; a stage equal to the latest writes no row (guard 1). |
| [`OR-32`](../DECISIONS.md) (flagged: it names `I6`) | Hashes are keyed (`hmacsha256v<n>`), computed in the DP over the read-back bytes. |
| [`OR-53`](../DECISIONS.md) | Generic, package-driven; no branch on the solution name. |
| `I1`, `I2`, `I5`, `I6`, `I7` | No MI development; measured facts; tenant scope; **no secret value in config storage**; plane boundary. |

**Flagged, not resolved:** (1) `OR-32` stays flagged by the owner's own record; nothing here relies on a reading of
it beyond what plan 010 T5.1 built. (2) §4.2's "overlay keys are stripped" depends on D-LOG's single
`OverlayKeys` function (`docs/design/backend/log-levels.md` §4.1, draft L-2). (3) Q-A5: the contract has no
defined code for "the host could not be read" (§9).

**What this does not design:** `restore-runtime` (T10.2); the Designer's stage path (T5.3), which this design reuses
without change; accepting the runtime's *version* (`architecture.md` §7.1's `VERSION_DRIFT` correction path is a
different operation); secret-field *value* entry (T10.1).

---

## 1. The running scenarios

Every option below is run through these cases, on the Cache package (`contracts/validation/fixtures/cache-2.4.package.json`:
one component `cache-engine`, `configPath` `cache-engine`, 25 fields, one secret field `solace.password`, no `yaml`
field). Cases A to H are what a host file actually looks like after a human has been in it.

| # | Scenario |
|---|---|
| **A** | **A break-glass value edit.** An operator edits `cache-engine.max-size` from `100000` to `250000` and `ttl` from `120m` to `30m` on the host. Both are declared, non-secret fields. |
| **B** | **A plus a changed secret.** The same edit, and the operator also changed `cache-engine.solace.password` on the host. |
| **C** | **Keys the package does not declare.** The operator added `server.port: 9090` and `spring.main.banner-mode: off` at the top level, and `cache-engine.new-flag: true` under the component. All three are valid Spring configuration. |
| **D** | **YAML constructs.** A hand-editor introduced an anchor and alias (`ttl: &t 30m` and `idle: *t`), or a duplicate key, or a second document. |
| **E** | **A cosmetic edit.** Comments added, whitespace changed, keys reordered. No value differs. |
| **F** | **A file that cannot be read.** Absent, empty, 3 MiB, not YAML, or a directory. |
| **G** | **Values that are wrong.** `max-size: lots`; `mode: SUPER` (not an option); `key-source: PAYLOAD` with `topic-key-levels: 3` still present (that field is inactive under the current discriminator); a required field (`namespace`) deleted. |
| **H** | **SM's own keys are present.** The rendered file carries the overlay (`management.*`, `logging.level.com.solace.solutions.cache`, D-LOG §4), and the operator changed the logging level on the host. |

**The status quo (measured).** Nothing reads the host's config back into values. `acceptRuntimeConfig` is contracted
(`contracts/openapi/console.yaml:787-840`: `config.edit` **and** `config.overrideWarning`, `baseStateVersion` and
`placementId` required, `200 StagedConfig`, `409`, `422`) and unbuilt. The transport can read the file
(`SshDockerTransport.statusSnapshot` carries the bytes) but the seam deliberately returns **only a keyed hash**:
`RuntimeStatus.observedHash` (`backend/dp-core/.../runtime/RuntimeStatus.java`), and the snapshot's javadoc says it
"cannot express a hash computed on the customer host". An operator who made a break-glass fix has two tools today:
**restore** (re-apply the desired generation, discarding the host edit) or **re-enter the change by hand** in the
Designer. Scenarios A to H all end at "re-type it".

---

## 2. Measured facts

### 2.1 What the render does to the file (from the plan, not yet code)

[Plan 010 T6.2](../../plans/backend/010-dp-data-model-and-remote-flow.md) (`Render`): the renderer reads the
generation's `rendered_entries`, resolves each `secretRef` in memory, builds nested YAML under the package's
`configPath` by `renderedPath`, serializes with **sorted keys**, and returns the bytes. So:

- the host file is **rebuilt from the generation's entries at every apply**. Any key outside those entries (scenario
  C) is removed at the next apply. This is true with or without accept-runtime;
- the host file **contains the secret value in clear** (T10.1: "the rendered file on the host contains the value
  (unavoidable — it is the MI's config)"). A read-back therefore *sees* every secret.

### 2.2 Parsers disagree, and the plan's "the same YAML library the renderer uses" is not enough (measured 2026-10-05)

The renderer's library is Jackson `jackson-dataformat-yaml` **2.21.4** over SnakeYAML **2.4** (the reactor's resolved
classpath, `mvn -o dependency:build-classpath -pl dp-core`; `SshDockerConfigEditor.java:6, 25` already uses it).
The Cache itself reads its file with Spring Boot 3.5.16's `OriginTrackedYamlLoader`. Verbatim from
`https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.16/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/env/OriginTrackedYamlLoader.java`
(fetched 2026-10-05): `loaderOptions.setAllowDuplicateKeys(false)`, `loaderOptions.setMaxAliasesForCollections(Integer.MAX_VALUE)`,
`loaderOptions.setAllowRecursiveKeys(true)`, `loaderOptions.setCodePointLimit(Integer.MAX_VALUE)`; the constructor
extends `SafeConstructor`.

A 20-line Java probe (scratchpad; §11) parsed the same documents with Jackson (`ObjectMapper(new YAMLFactory())`,
what the renderer uses) and with a SnakeYAML 2.4 `SafeConstructor` load (the nearest to the MI):

| Document | Jackson (renderer's parser) | SnakeYAML safe load (nearest the MI) |
|---|---|---|
| `x: &a hello` / `y: *a` | **`{"x":"hello","y":"a"}`: the alias becomes the anchor's *name*, silently** | `y = hello` |
| `base: &b {x: 1}` / `d: {<<: *b, y: 2}` | `d = {"<<": "b", y: 2}`: the merge key is a literal key, its value the anchor name | `d = {x: 1, y: 2}` |
| `a: 1` / `a: 2` | `{a: 2}` (last wins, no error) | `{a: 2}` with a `WARNING: duplicate keys found : a` |
| `a: 1` / `---` / `b: 2` | `{a: 1}` (second document ignored) | **error**: expected a single document |
| `a: !!java.lang.ProcessBuilder [ls]` | `{a: [ls]}` (tag ignored) | error: global tag not allowed |
| `b: 1:30` | `"1:30"` (string) | `90` (int) |
| `a: yes`, `b: no`, `c: on`, `d: off` | `true, false, true, false` | the same |
| `a: 010`, `a: 0x10`, `a: 1_000` | `8, 16, 1000` | the same |
| an empty document | **error** (no content) | `null` |
| `cache-engine.max-size: 5` beside `cache-engine: {ttl: 1h}` | two top-level keys; the first is the literal `cache-engine.max-size` | the same |

And with `JsonParser.Feature.STRICT_DUPLICATE_DETECTION` enabled on the Jackson mapper, `a: 1` / `a: 2` throws
`JsonParseException`. Jackson refuses duplicates when asked, so no second parser is needed for that rule.

**What this settles.** An aliased value read through Jackson is **a different value, with no error** (an alias of a
secret's anchor would even put a *name* where a value belongs). The MI honours aliases (Boot sets no alias bound), so a
hand-edited file can be perfectly valid to the MI and wrongly read by SM. Boot refuses a duplicate key and so does the
strict Jackson setting, so a file with one cannot be a running configuration. Flat dotted keys (`cache-engine.max-size`)
are valid Spring and are literal keys to Jackson: the mapper must treat them as the Boot loader does.

### 2.3 The seam, the file bound, and the stage path (measured in the tree)

- `RuntimeAdapter` has `replicas`, `prepare`, `materializeConfig`, `startOrUpdate`, `stop`, `status`, `assessRollback`,
  `restore` (`RuntimeAdapter.java:42-109`). **None returns file bytes.** `RuntimeStatus` carries `observedHash`.
- `SshjDockerTransport.readFile` is bounded: `MAX_FILE_BYTES = 1024 * 1024` (`:28`), `statExistence` first, a
  non-regular file refused (`:126-128`), the path checked against the placement's declared path (`requireDeclaredPath`).
- `config_generation` stores `component_selection`, `draft_values`, `rendered_entries`, a keyed `content_digest`, and
  `origin IN ('edit','rollback','restore-runtime','accept-runtime')` (`T5.2`); secret fields hold
  `{"secretRef": "<uuid>"}` only. `stage` takes the next generation under the instance lock and checks
  `baseStateVersion` (`409` on stale); identical content writes no row and returns `unchanged = true` (guard 1).
- The stage flow is composed in the **gateway**: fetch the package document (CP catalog), fetch the observed state (DP),
  re-project with the CP's `DesignerStageValidator`, then call `ConfigRecordService.stage`. The DP does not read the
  package on the worker path (T5.3, T6.2).
- `project.ts` (`contracts/view-models/src/project.ts`): `isSecret(f)` is `f.secret === true || f.type === 'secret'`
  (`:66`); `normalize` is at `:83` and refuses a wrong-typed value as a `BIND_TYPE` block; `renderedPathFor(configPath, key)`
  is `configPath === '' ? key : configPath + '.' + key` (`:120`).

---

## 3. The pipeline, one decision at a time

The five steps in the plan (read, parse and map, drop secrets, refuse the unmappable, stage) each hide a choice. Each
is laid out as options with the concrete delta from the status quo, then run through A to H.

### 3.1 The read: what crosses the adapter seam

| | **R0: status quo** | **R-a: a new read-only seam operation** | **R-b: the adapter parses** | **R-c: widen `status`** |
|---|---|---|---|---|
| **What it is** | the seam returns a hash only | `RuntimeAdapter.readConfig(TargetReplica)` returns a `ConfigReadBack(bytes, keyedHash)`; **dp-core** parses | the adapter parses and returns a map with secret paths already removed, given the package's secret paths | `RuntimeStatus` gains the bytes |
| **New surface** | none | one operation, its kit test (`AbstractRuntimeAdapterContractTest`), both adapters and the in-memory one; the certified-adapter inventory (`PlaneBoundaryTest.java:224-228`) is unchanged because the same two classes implement it | YAML parsing and the secret-path list inside every adapter (Docker today, Kubernetes later) | none |
| **Where secrets travel** | never leave the host except as a hash | the bytes (secrets in clear) reach dp-core, in one request thread | scrubbed before the seam; the package's secret paths cross the seam instead | **every status poll** carries them, on a path the health and ladder readers call each interval |
| **The kit can test the parse once** | n/a | **yes**, one parser | no: once per adapter | n/a |

**Recommendation: R-a.** A parser belongs in one place. R-c is rejected on its own terms: a secret-bearing byte array
on a hot read path buys nothing. R-b is the option that keeps secrets off dp-core, at the cost of N parsers; §3.4
shows a cheaper way to keep them out of the *gateway* and the response.

The `ConfigReadBack` type has **no `toString`, no `equals` over bytes, and a `close()` that zeroes the array**; the
caller zeroes it in a `finally` (the pattern T10.1 uses for a request buffer). The path read is the placement's own
declared path (the placement is looked up by `(tenant, instance, placement)`); the request names no path, host or
command (`I5`, B2).

### 3.2 The parse: what is refused before any value is read

| | **P0: status quo** | **P-1: Jackson as-is** | **P-2: an event pre-scan, then Jackson with strict duplicates** | **P-3: SnakeYAML safe load with Boot's options** |
|---|---|---|---|---|
| **What it is** | no parser | `ObjectMapper(new YAMLFactory()).readTree(bytes)` | a SnakeYAML *event* pass refuses any anchor, alias, non-core tag or second document; then Jackson with `STRICT_DUPLICATE_DETECTION` reads the tree | `Yaml(SafeConstructor, LoaderOptions)` with the aliases bound |
| **D** (anchor/alias, duplicate, second document) | n/a | **wrong value accepted**: alias becomes its anchor's name (§2.2); duplicate last-wins; second document ignored | refused: `anchor`, `alias`, `duplicate-key`, `multi-document` | alias honoured as the MI does; duplicate refused only if `allowDuplicateKeys(false)`; a bound on expansion is ours to add |
| **E** cosmetic edit | n/a | parses; same values | parses; same values | parses; same values |
| **F** empty / not YAML / 3 MiB | n/a | an empty document is an exception, not an empty map; a 3 MiB file never arrives (`MAX_FILE_BYTES`) | the same, named | the same |
| **`b: 1:30`** | n/a | string `"1:30"` | string `"1:30"` | integer 90 (as the MI) |
| **Cost** | none | none | one more library call (SnakeYAML is already on dp-core's classpath through Jackson; it needs a **declared** dependency) | a second parse path with different typing from the renderer's |

**Recommendation: P-2.** It refuses exactly the constructs Jackson reads *wrongly* and the MI reads *differently*, and
it adds no construct the renderer ever writes. Refusing is the right default: the Designer never renders an anchor, so a
file containing one is a hand-edit SM cannot represent faithfully, and the honest answer is `422` naming the reason
(`anchor`, `alias`, `tag`, `multi-document`, `duplicate-key`, `not-a-mapping`, `empty`, `too-deep`). The tree walk caps
nesting depth (32, our own rule) and node count. P-2 does **not** close `1:30`-style typing differences between the
renderer's parser and the MI's; §3.3's round-trip check catches those that matter for a mapped field.

### 3.3 The map: from the tree to `(instanceKey, fieldKey)`

The mapping inverts `renderedPathFor` against the package, and handles what a real file contains.

1. **Flatten the way Boot does.** Walk the tree; at each node the path is the concatenation of keys joined by `.`.
   A flat key `cache-engine.max-size` and a nested `cache-engine: {max-size: …}` are the same path. If **both**
   spellings of one declared path are present, refuse (`ambiguous-key`): Boot's precedence between them is not
   something SM should guess.
2. **Match a declared path exactly.** The gateway builds a `FieldPathTable` from the package and the component
   selection: `renderedPath → {instanceKey, fieldKey, type, secret, yaml}` for **every declared field, active or
   not**. A node whose path equals a declared path takes its **whole subtree** as that field's value (this is how a
   `list` or `map` field such as `template-mappings` arrives). A node that is not a declared path is descended into.
3. **No relaxed binding.** Boot binds `maxSize`, `max_size` and `max-size` to one property. This design matches the
   declared kebab path only; any other spelling is reported as an unmapped key (§3.5). This is stated, not
   measured against Boot's `ConfigurationPropertyName` rules, and is Q-A4.
4. **Type with `normalize`.** Each candidate value goes through the projection's own `normalize` (`project.ts:83`), so
   a wrong type is a `BIND_TYPE` block exactly as in the Designer. The Java port of that logic is the one the server
   already has (T1.2).
5. **Absent means unset, for a visible field.** A non-secret declared field **that the current projection shows** and
   that has no node in the file becomes unset in the accepted draft: the file is the runtime's truth, and a missing key
   means the MI uses its default. A declared field the projection does **not** show (its `when` is false, for example a
   TLS keystore path while TLS is off) is not rendered, so the file's silence about it is no runtime fact: its stored
   draft value is **kept** (`OR-108` (6)). A *secret* field's draft value is **never taken from the file** (§3.4).
6. **Inactive fields.** A field whose `when` predicate is false under the accepted values is not rendered. If the file
   still carries its key (G, `topic-key-levels` under `key-source: PAYLOAD`), the value goes into the draft like any
   other, and the server's re-projection decides it is inactive. It is reported as `inactive-field` (informational,
   not an error).
7. **The round-trip check.** After mapping, the DP re-renders the candidate draft's rendered entries to canonical
   content and compares it with the canonical flattening of the read-back restricted to mapped paths. A difference
   means the mapping lost or changed a value (for example a scalar the two parsers type differently): refuse
   `422 round-trip`. This catches **mapping** loss. It cannot catch a disagreement between SM's parser and the MI's
   (both sides of the check use the same parser); P-2's refusals and §2.2's table are the defence there.

### 3.4 Secrets: the I6 rule, and what each option does to B

The rule from the plan and from S10 ("`accept-runtime` reads back secrets: it must refuse or drop them, `I6`"): a
read-back secret value is **never stored**.

| | **V-1: drop at the walk** | **V-2: refuse the whole accept if any secret key is in the file** | **V-3: adopt the read-back value into the secret store** | **V-4: V-1, plus an in-memory compare** |
|---|---|---|---|---|
| **What it is** | The walker matches a secret field's path and **does not read its value**. The accepted draft keeps the current `secretRef`. The response lists the field as `secret-not-read`. | A secret node anywhere in the file refuses the call. | The value is encrypted as a new `CONFIG_FIELD` secret and the draft points at it. | V-1, and the DP decrypts the stored secret and compares it to the file's value in memory, reporting only `same` or `differs`. |
| **Contradicts the plan's S10 non-goal** | no | no | **yes**: it reads a secret back and stores it | no persistence; it does read the value |
| **Scenario A** (no secret edit) | accepted; `solace.password` listed as `secret-not-read` | **refused**: the file always contains the password SM rendered, so *every* accept is refused | accepted; the password is re-stored | accepted; listed as `same` |
| **Scenario B** (password changed on the host) | accepted for the other fields; `solace.password` `secret-not-read` | refused | accepted; the new password is now stored | accepted; listed as `differs` |
| **What the next apply does to B's changed password** | **rewrites the old password over the host's** (the generation still points at the old secret) | n/a | keeps the host's new password | rewrites the old one, but the operator was told it `differs` |
| **New code** | none beyond the walker | none | a store write from a read-back path, and a reading of `I6` | a compare-only secret port (`dp-api`) |

**Recommendation: V-1.** V-2 is dead on arrival (scenario A shows it makes accept impossible for any instance with a
secret field). V-3 is against the plan's stated non-goal and extends SM's credential holdings to values no operator
entered through SM. V-1's honest cost is scenario B: **a password changed on the host is reverted by the next apply.**
The response and the console copy must say so in words ("the password in the file was not read; applying will
restore the stored one"). V-4 is the cheap way to turn that warning from "may differ" into "differs"; whether the
compare is acceptable is Q-A2.

**Where secrets are kept out of the gateway and the response.** The DP port takes the `FieldPathTable` (which carries
the `secret` flag per path) and returns **only** `ReadBackCandidate{ values, notAccepted[], readBackHash }`. Secret
values never leave dp-core, never reach the gateway, never appear in a response or a log. The secret `String` that
SnakeYAML builds during the event pass lives in the request thread's heap until collection: this is a stated
residual (a `String` cannot be zeroed), and it is the same class of residual as T10.1's request buffer, not a
persistence. The walker also never *reads* a secret node's text, so no copy is made after the event pass.

A `yaml`-typed field is **not accepted at all** (`yaml-field-not-accepted`): it can carry credentials the walker
cannot identify (open item 22, `YAML_SECRET_SCAN` is a warning, not a guarantee). Cache has none; the rule is
there for the package that does.

### 3.5 The unmappable: keys the package does not declare

| | **U0: status quo** | **U-1: refuse** | **U-2: accept the mappable, report the rest** | **U-3: the request acknowledges the drop** |
|---|---|---|---|---|
| **What it is** | n/a | any node that is not a declared path, not an overlay key and not inside a subtree-valued field is `422` | such nodes are listed by *path only* in `notAccepted[]`; the accept proceeds | the request carries `acknowledgeDropped: [paths]`; a mismatch is `422` |
| **C** (`server.port`, `spring.main.banner-mode`, `cache-engine.new-flag`) | n/a | refused. The operator can never accept while their extra keys are in the file; their options are to delete the keys or to restore. | accepted; the three paths are listed `undeclared-key`; **the next apply removes them from the file** (§2.1) | the UI shows the three and the operator confirms |
| **Contract change** | none | none | `notAccepted[]` on the response (needed anyway for secrets, §5) | a request field and a UI step |
| **`B/D12`: "never silently auto-revert a human"** | holds | holds | holds **only if** the response is read: the loss is reported, not silent | holds strongest |

**Recommendation: U-2**, with the loss stated in the response and the console copy. U-1 makes a break-glass accept
fail precisely when an operator has done something extra to a production host. U-3 is the more careful option and it
costs a request field; if the owner wants the confirmation to be an explicit act rather than a message, U-3 is the
upgrade (Q-A3). A key name is not a secret value, but it is host-file content: the response lists names only, capped
at 200 with a `truncated` flag.

### 3.6 Nothing to accept

Two checks, in this order:

1. **Bytes.** The DP re-renders the latest desired generation to its canonical bytes and keyed hash and compares with
   `readBackHash`. Equal means the file is exactly what SM wrote. This is a fast path.
2. **Content.** The mapped candidate goes through the normal stage. `ConfigRecordService.stage`'s **guard 1**
   (`OR-33`) returns `unchanged = true` when the candidate's content digest equals the latest generation's. This is
   the authoritative test. It also covers E (a cosmetic edit), and B+C-only cases where nothing mappable differs.

The answer for "nothing to accept" is **`200` with `unchanged: true`**, not `422`: the call succeeded, and the file
carries no new content. `unchanged: true` with a non-empty `notAccepted[]` is a legitimate response (B and C with no
value edit), and the console must show both.

### 3.7 The stage

The candidate draft goes through the **normal stage path** unchanged: the gateway re-projects with
`DesignerStageValidator` and refuses on any `block` validation or `StageRefusalReason` (`OR-34`), then calls
`ConfigRecordService.stage` with `origin = 'accept-runtime'` and `derivedFromGeneration` = the latest generation.
Three consequences:

- **A block is a refusal, not a partial accept.** G (`max-size: lots`, `mode: SUPER`, a deleted required `namespace`)
  is `422` naming the *field and the rule*, never the value (the contract's rule for stage and declare, `console.yaml:585, 709`).
  A generation must be valid; the operator fixes the file or restores.
- **Warnings are accepted.** `BELOW_RECOMMENDED` and friends do not block a stage.
- **Overlay entries are carried forward** from the latest generation (D-LOG §9), so accept never drops a configured log
  level. A host edit of an overlay key is **not** adopted (H): it is listed `sm-owned-overlay`.

Authorization is `config.edit` **and** `config.overrideWarning` at the instance (`console-integration.md` §5.3 row
42), decided in the gateway before either plane is called, as for every verb. The call does one bounded SSH read, in
the request thread, **holding no database transaction** (Global Constraints, m3); the stage then takes its own
transaction.

### 3.8 After the accept: the bytes gap

The new generation `G+1` renders to SM's canonical bytes. The host's bytes are the operator's. Unless they happen to
be identical (scenario A with a sorted, comment-free file), **the host file still differs from `G+1`'s rendering**:
the `persisted` rung does not reach `G+1` until `G+1` is applied, and the file stays "drifted" by hash.

| | **X-1: stage only** (the contract's "writes nothing to the host") | **X-2: stage, then submit a `manual` apply** | **X-3: record the host's bytes as `G+1`'s materialization** |
|---|---|---|---|
| **What happens to the host** | nothing | the file is rewritten in canonical form, no restart | nothing |
| **The ladder after** | `desired` and `rendered` at `G+1`; `persisted` stays at the previous generation; drift persists | `persisted` reaches `G+1` | `persisted` would claim `G+1` while the bytes are not `G+1`'s rendering |
| **Fit with the contract** | exact (`acceptRuntimeConfig` "Writes nothing to the host") | contradicts it; a second admission; needs `config.apply` | needs a journal write with no apply behind it; against the journal's truthfulness rule |
| **Scenario A** | accepted; the operator sees a persisted/rendered difference and applies when ready | the host is rewritten without the operator's say | a rung that lies |

**Recommendation: X-1.** The response carries `fileBytesDifferFromRendered: true` when the read-back hash differs from
`G+1`'s rendering, and the console says: "Accepted as generation N. The file on the host still differs from SM's
rendering until N is applied (a manual apply rewrites it in SM's canonical form, with no restart)." X-3 is rejected: it
would make the ladder report what the journal did not prove.

⚠️ **Multi-placement.** The config of record is per instance. Accepting placement `p1`'s file changes the instance's
desired configuration for `p2` too. BE-D23 makes v1 single-placement; the response states which placement was read.

---

## 4. The overlay and the secrets are separate concerns, and share one rule

### 4.1 The refusal order (so the cheap refusals come first)

1. authorization (`config.edit` and `config.overrideWarning`); `placementId` belongs to the instance and tenant and is live;
2. read: absent file, not regular, over `MAX_FILE_BYTES`, host unreachable (§3.1);
3. parse: P-2's refusals (§3.2);
4. map: ambiguous key, a type `normalize` refuses, round-trip loss (§3.3);
5. stage: the server's re-projection, blocks, `409` stale (§3.7).

### 4.2 Overlay keys are stripped by the function that writes them

The overlay set is the shared `OverlayKeys` of D-LOG (`BE-D37`; the draft's `forPlacement(...)` became
`forLoggerSupport` for the entries and `owns` for the predicate). The walker removes the paths `OverlayKeys.owns` names
*before* matching, so SM's own exposure and loggers-access keys are never counted as the operator's unmapped keys (H), and
an operator edit of one is reported `sm-owned-overlay`. There is one key list: a second one would drift.

---

## 5. The contract change this task owns

`console.yaml` is single-owner per task; T10.3 is the only editor in W10-W11. The additive change, drafted (a reviewed
change, not made here):

```yaml
AcceptedRuntimeConfig:
  allOf:
    - $ref: '#/components/schemas/StagedConfig'
    - type: object
      required: [unchanged, notAccepted, placementId]
      properties:
        unchanged: { type: boolean }
        placementId: { type: string, format: uuid }
        fileBytesDifferFromRendered: { type: boolean }
        truncated: { type: boolean }
        notAccepted:
          type: array
          maxItems: 200
          items:
            type: object
            required: [path, reason]
            properties:
              path: { type: string }
              fieldKey: { type: string }
              reason:
                type: string
                enum: [secret-not-read, undeclared-key, sm-owned-overlay, inactive-field, yaml-field-not-accepted]
```

and the `422` description names the refusal reasons of §3.2 and §3.3 in its `detail` (clients switch on `code`, not
on `detail`; no new `ErrorCode` is drafted, because `VALIDATION_FAILED` already means "a blocking validation, or an
envelope refusal"). **Not covered by the existing codes:** a host that cannot be reached over SSH. `SERVICE_UNAVAILABLE`
is documented for credential-verification capacity and `INTERNAL_ERROR` for infrastructure failures; neither reads as
"the host did not answer". Q-A5.

---

## 6. What T10.4 must prove (named tests, with the planted violation that turns each red)

T10.4's list in the plan stays. This design adds these, each with a mutation:

| Test | Planted violation |
|---|---|
| `aSecretFieldsValueIsNeverInAnyResponseLogOrRow` (scan response, captured log, every `sm_data` column, for the value written into the host file) | read the secret node's value into the draft |
| `aSecretFieldKeepsItsCurrentSecretRef` | take the secret node's text as the field value |
| `aYamlTypedFieldIsNotAccepted` | treat a `yaml` field like a string field |
| `anAnchorAnAliasATagAMergeKeyOrASecondDocumentIsRefused422` | parse with Jackson alone (alias becomes the anchor's name) |
| `aDuplicateKeyIsRefused422` | drop `STRICT_DUPLICATE_DETECTION` |
| `aFlatAndANestedSpellingOfOnePathIsRefusedAmbiguous` | let the later one win |
| `anUnmappedKeyIsReportedByPathNeverByValue` | include the value in `notAccepted` |
| `anOverlayKeyIsStrippedByTheFunctionTheRendererUses` | strip a hard-coded list instead of `OverlayKeys` |
| `aBlockingValueIs422NamingTheFieldNotTheValue` | echo the value in `detail` |
| `aFileWhoseContentEqualsTheLatestGenerationIsUnchangedAndWritesNoRow` | bypass guard 1 |
| `acceptedBytesDifferingFromRenderedIsReported` | always report `false` |
| `theReadHoldsNoDatabaseTransaction` | read inside the stage transaction |
| `theConfigReadBackBuffersAreZeroedAfterUse` | drop the `finally` |
| `aRetiredOrForeignPlacementIs404` | skip the `(tenant, instance, placement)` lookup |
| `theRoundTripCheckRefusesALossyMapping` | skip the check |

---

## 7. Invariant audit

| Invariant | How the design holds it |
|---|---|
| **`I1`** (no MI development) | The MI is untouched: SM reads a file it already writes, through the same SFTP path the apply uses. No new endpoint, emission or build change. The parser is SM's. |
| **`I2`** (verify external facts) | §2.2: Boot's loader options are quoted from the source at tag `v3.5.16` with the URL and date; the parser behaviours are measured with a probe against the reactor's own library versions. **Unmeasured and labelled:** Boot's relaxed-binding rules (§3.3 item 3, Q-A4), and the MI's resolver for `1:30`-style scalars. |
| **`I5`** (tenant scope) | The read is of the placement's declared path, looked up by `(tenant, instance, placement)`. The request names no host, path or command. The tenant is the session's. |
| **`I6`** (secrets) | A secret field's value is never read into the draft, never stored, never returned and never logged (§3.4). The walker does not read a secret node's text. `notAccepted` lists paths, not values. The `ConfigReadBack` bytes are zeroed. **Stated residual:** a `String` built by the YAML event pass lives until collection in the request thread; no persistence. |
| **`I7`** (plane boundary) | dp-core reads and parses; the gateway composes (it is the one module permitted both `-api`s) and hands the DP a `FieldPathTable` value object. The DP never imports CP. |
| **`B/D12`** | Accept is a manual operator act; it never reverts the host; losses are stated (§3.5, §3.8). |
| **One mutating path** | Accept goes through `ConfigRecordService.stage`; it writes nothing to the host. |

---

## 8. Draft decisions (the integrator allocates the ids; T0.3 registers them)

### `BE-D39` (draft A-1): accept-runtime reads the host's file through one new read-only seam operation, and dp-core parses it

**Decision.** `RuntimeAdapter.readConfig(TargetReplica)` returns a closed `ConfigReadBack` (bytes, keyed hash,
zeroing `close()`, no printable form). dp-core parses and maps; no adapter contains a YAML parser. The gateway passes a
`FieldPathTable`; the DP returns a `ReadBackCandidate` that contains no secret value. **Status: PROPOSED.** Adds an
operation to the seam `BE-D21`/`BE-D22`/`BE-D25` define; flagged for the contract kit.

### `BE-D40` (draft A-2): a read-back file is parsed with a pre-scan that refuses what Jackson reads wrongly

**Decision.** Before any value is read, an event pass refuses an anchor, an alias, a non-core tag, a second document;
the tree read is Jackson with strict duplicate detection; the walk is depth- and size-capped. A mapping that does not
round-trip is refused. **Status: PROPOSED.** Rests on §2.2's measurement.

### `BE-D41` (draft A-3): a read-back secret is dropped at the walk; the draft keeps the current secret reference

**Decision.** A secret field's node value is never read. The accepted draft keeps its current `secretRef`; the
response lists the field as `secret-not-read`. A `yaml`-typed field is not accepted. SM-owned overlay keys are stripped
by D-LOG's `OverlayKeys` and listed `sm-owned-overlay`. **Status: PROPOSED.** Implements the S10 non-goal. Waits on
Q-A1 and Q-A2.

### `BE-D42` (draft A-4): accept stages an `accept-runtime` generation and writes nothing to the host; the response states what was not accepted and that the bytes differ

**Decision.** The candidate draft goes through the normal stage path with `origin = 'accept-runtime'`. A block
refuses; warnings do not. Unmapped keys are reported by path (capped); the host file is rebuilt at the next apply, which
removes them. **Status: PROPOSED.** Waits on Q-A3 and Q-A5.

---

## 9. Owner questions this design raises

Each has a status quo, the options with their delta, and a recommendation. **None is decided here.**

| Id | Question | Status quo (measured) | Options: delta | Recommendation | Gates |
|---|---|---|---|---|---|
| **Q-A1** | **May the call be refused for an unmappable key, or only report it?** | An operator has no way to adopt host edits; both tools discard them. | **U-1** refuse; **U-2** accept and report; **U-3** accept on an explicit acknowledgement (a request field). §3.5 | **U-2**; **U-3** if the owner wants the loss to be a deliberate act | T10.3's contract text, T10.4 |
| **Q-A2** | **A secret changed on the host: drop, adopt, or compare?** | The next apply reverts it (the generation points at the old secret) whatever accept does. | **V-1** drop; **V-3** adopt (contradicts the S10 non-goal; needs a reading of `I6` from the owner); **V-4** drop, plus an in-memory compare and `differs`. §3.4 | **V-1**; **V-4** as a cheap addition | T10.4 |
| **Q-A3** | **Is the post-accept bytes gap acceptable?** The host file differs until `G+1` is applied. | n/a | **X-1** stage only; **X-2** stage and apply (contradicts "writes nothing to the host"); **X-3** record the host's bytes (rejected). §3.8 | **X-1** | the contract's response |
| **Q-A4** | **Exact declared-path matching only, or Boot's relaxed binding?** | n/a | **M-1** exact kebab path, others reported; **M-2** Boot's relaxed rules (camelCase, underscores): accepts more of what the MI accepts, needs a measured copy of Boot's rule, and risks two spellings of one key | **M-1** | T10.4 |
| **Q-A5** | **Which code answers "the host could not be read"?** | `VALIDATION_FAILED` is "a blocking validation or an envelope refusal"; `SERVICE_UNAVAILABLE` is documented for credential-verification capacity. | **(a)** widen `SERVICE_UNAVAILABLE` to "a dependency did not answer; retry"; **(b)** `INTERNAL_ERROR`; **(c)** `VALIDATION_FAILED` | **(a)** | the contract text |
| **Q-A6** | **Is a `yaml`-typed field never acceptable, or acceptable once open item 22 closes?** | open item 22 gates Phase 2 / `type: yaml`; Cache has none. | **(a)** never accepted in v1; **(b)** accepted with the item-22 redaction once it exists | **(a)** | later |

---

## 10. What this design asks of later tasks (for the integrator)

This design edits no other file. The deltas it implies:

1. **T10.4** builds to §3 and §6. Its listed files (`RuntimeReadBack`, `ReverseRenderedPathMapper`) become: the
   `readConfig` operation (A-1), a `dp-core` `ConfigReadBack` parser/mapper, the gateway's `FieldPathTable` builder, and
   the controller. Its tests add §6's.
2. **The seam**: `RuntimeAdapter.readConfig` and its kit test, both adapters, `InMemoryRuntimeAdapter`.
   `RUNTIME_ADAPTER_IMPLEMENTATIONS` is unchanged.
3. **The CP side**: a `cp-api` helper that derives the `FieldPathTable` from a package document and component selection
   (the Java `renderedPathFor` and `isSecret` already live in `DesignerProjector`).
4. **A declared dependency** on `org.yaml:snakeyaml` in dp-core's pom (today it arrives transitively). The licence gate
   (`I8`) applies at the pinned version (2.4 is on the reactor's classpath today).
5. **Contract**: §5's `AcceptedRuntimeConfig` and the `422` text, owned by T10.3's follow-on contract commit
   (the only `console.yaml` editor in W10-W11). `OR-60`'s line "T10.3 may narrow the 422 only by a later reviewed change"
   is why this is a reviewed change.
6. **D-LOG's `OverlayKeys`** (`log-levels.md` §4.1) must exist before T10.4 starts (§4.2).
7. **T9.4** carries overlay entries forward in `stage`; accept depends on it (§3.7).
8. **The plan text** says "`422` or `unchanged`" for "nothing to accept" and "a type `normalize` refuses". This design
   answers: nothing to accept is `200 unchanged: true`; a refused type is `422` naming the field.

---

## 11. Reproducing the parser probe

Under the session scratchpad, nothing in the repository. Classpath from `mvn -o -q -f backend/pom.xml -pl dp-core
dependency:build-classpath -Dmdep.outputFile=…` (SnakeYAML 2.4, Jackson 2.21.4):

```
// Y.java: for each document, print Jackson's ObjectMapper(new YAMLFactory()).readValue(doc, Object.class)
//         and new Yaml(new SafeConstructor(new LoaderOptions())).load(doc)
// Z.java: a SnakeYAML event pass (Yaml.parse) that returns "alias", "anchor", "tag <t>" or "multi-document",
//         and ObjectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree("a: 1\na: 2")
javac -cp "$CP" Y.java Z.java && java -cp "$CP:." Y && java -cp "$CP:." Z
```

Observed (2026-10-05): the table in §2.2; the event pass returned `anchor` for the alias, anchor-only and merge
documents, `multi-document` for two documents, `tag tag:yaml.org,2002:java.lang.ProcessBuilder` and
`tag tag:yaml.org,2002:str` for tagged scalars, and a `ParserException` for `a: [1, 2`; strict duplicate detection
threw `JsonParseException`. The probe's own duplicate-key tracking was a stub and is **not** relied on; the
strict-Jackson setting is the design's rule.
