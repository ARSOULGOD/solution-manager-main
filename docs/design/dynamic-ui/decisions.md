# Subsystem A decisions — `UI-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `UI-D*` is a fifth family alongside `D*`,
`B/D*`, `SM-D*` and the other per-component families.

> **Amendment policy for this draft.** `I3` says to change a decision, add a new one that supersedes it —
> never edit a decision's meaning in place. That rule protects decisions **something has been built or
> ratified against**. `UI-D1`…`UI-D21` were committed as a first draft (`0db4b4d`) and corrected within the
> same review cycle, before any ratification and with no code against them, so the R1 corrections are applied
> **in place** and itemized in the changelog at the end of this file. Post-ratification, the supersession rule
> applies normally.

⚠️ **Three items conflict with `LOCKED` decisions or supersede a `PROPOSED` one.** Per `I4` they are flagged,
not resolved: **`UI-D19` vs `B/D4`**, **`UI-D21` vs `D1`**, and **`UI-D12` supersedes `SM-D14`**.

---

| ID | Decision | Status |
|---|---|---|
| `UI-D1` | Component is the unit of composition | PROPOSED |
| `UI-D2` | Applicability grammar closed at equality + AND, over defaults-applied values | PROPOSED |
| `UI-D3` | Fields are a flat list carrying the full ancestor chain | PROPOSED |
| `UI-D4` | Discriminators are explicit; inactive branches retained but never rendered | PROPOSED |
| `UI-D5` | Undeclared metrics are quarantined, not rejected | PROPOSED |
| `UI-D6` | `metrics_pending` 7 days on daily partitions; `series_pending` retained long | PROPOSED |
| `UI-D7` | Applicability gates collection, against the `restart-observed` config | PROPOSED |
| `UI-D8` | Absence renders blank, never zero; a gap is > 2× the declared scrape interval | PROPOSED |
| `UI-D9` | Governance is green/red, plus `RESTART_EXPECTED` and `INACTIVE_REPLICA` | PROPOSED |
| `UI-D10` | Logs are streamed on request, never persisted; exposure union is declared, not read | PROPOSED |
| `UI-D11` | Packages are runtime-loaded data, one per published version with an `appliesTo` range | PROPOSED |
| `UI-D12` | MI version is declared, corroborated rather than derived | PROPOSED ⚠️ |
| `UI-D13` | Registry in `sm_control`, values in `sm_data`, referenced by value | PROPOSED |
| `UI-D14` | Closed vocabularies, open data — with one canonical definition each | PROPOSED |
| `UI-D15` | The view model is a pure function guarded by a shared fixture suite | PROPOSED |
| `UI-D16` | Two validation tiers — bind blocks, semantic warns with recorded override | PROPOSED |
| `UI-D17` | Provenance is a property of the column, not the cell | PROPOSED |
| `UI-D18` | T-TYPES asserts the declared type **binds**; a signature asserts T-KEYS and T-TYPES passed | PROPOSED |
| `UI-D19` | Solace authors packages, the customer loads them | PROPOSED ⚠️ |
| `UI-D20` | The canvas is SM's generic composer; layout is presentation state | PROPOSED |
| `UI-D21` | Package-supplied UI code is replaced by SM-built named screen types | PROPOSED ⚠️ |
| `UI-D22` | Dynamic option sources and actions are closed-vocabulary members | PROPOSED |
| `UI-D23` | `yaml` fields are a bounded tunnel with four stated limits | PROPOSED |

---

## `UI-D1` — Component is the unit of composition

A solution declares component **types**; an instance composes instances of them, each with `cardinality` and
an optional `role`.

**Rationale.** Cache has one component (~25 fields); File Mesh has many — `COMP_SOURCE_GRID` 180,
`COMP_SOURCE_FTP` 132, `COMP_SINK_SOLACE` 90. A flat field list fits Cache and cannot express File Mesh,
which already persists exactly this shape: `FM_DATA_FLOW_CONFIGURATION` is
`(DATA_FLOW_ID, COMPONENT_TYPE, CONFIGURATION_FIELD) → VALUE`.

## `UI-D2` — Applicability grammar closed at equality + AND, over defaults-applied values

`when:` is an object of `field: literal`, implicitly AND-ed. **No OR, no comparison, no negation.** It
evaluates against **defaults-applied** values, never raw input.

**Rationale.** Two independent implementations converged and stopped: every `@ConditionalOnProperty` in Cache
is equality-shaped (one is an AND of two), and **all 271** File Mesh predicates (230 `showWhen` + 41
`dependency`) are flat `field: literal` objects with **0 grammar violations**, verified by
[`evidence/extract-fm-vocabulary.py`](evidence/extract-fm-vocabulary.py). Max 3 keys.

`!=` was proposed and **dropped** — no evidence, 271 predicates of counter-evidence. `in [...]` is held as the
single provisional extension and is **unimplemented**.

⚠️ **`matchIfMissing = true` is why "defaults-applied" is in the title.** `CaffeineCacheProvider.java:24`
carries it, making the condition *equals-or-absent* — inexpressible in this grammar. Rather than add an
operator, absence is resolved before the predicate runs: the field declares `default: caffeine` and the
evaluator sees the resolved value. `provider` has **no Java field initializer**, so `matchIfMissing` is itself
a default-value source for the extractor (`UI-D17`, design §7.3). Without this, `when: {provider: caffeine}`
is false on every config that omits `provider`.

**Why minimal matters beyond taste:** every operator is code in two evaluators that must agree. Grammar size
is divergence risk. Relates to `UI-D14`, `UI-D15`.

## `UI-D3` — Fields are a flat list carrying the full ancestor chain

Fields are flat. Each child's `when:` carries its **whole** ancestor chain, not just its parent.

**Rationale.** What File Mesh does in production at three levels deep. A flat predicate is one evaluation with
no tree walk, which keeps two evaluators in agreement.

**Cost, closed by gates 5 and 5b:** an unsatisfiable predicate can be written, and so can one naming a value
that is not an option member. Both are rejected at load.

## `UI-D4` — Discriminators are explicit; inactive branches retained but never rendered

`discriminator: true` marks a field whose value selects a child subtree. Changing it re-evaluates all
descendants. Inactive-branch values are **retained in the draft** but **never rendered into the config file**;
diff, export and the config hash follow the rendered set.

**Rationale.** Explicit marking lets the renderer swap a subtree deliberately and lets validation run only the
live branch. Retention preserves work on switch-and-switch-back; non-rendering prevents the MI receiving
settings for a server type it is not using. Gate 6 requires discriminators be `enum`.

## `UI-D5` — Undeclared metrics are quarantined, not rejected

Declaration gates **plottability**; the per-instance cardinality cap (`SM-D30`) gates **acceptance**.
Undeclared metrics land in `series_pending`/`metrics_pending` and are listed as "needs typing". Type is
**never** inferred.

**Rationale.** `V001` makes `series.metric_type` `NOT NULL` with a three-value CHECK and FKs `metrics_raw` to
`series`, so the schema rejects undeclared metrics **by construction**. That is wrong: SM configures a scrape
*path*, not a metric list — one path returns many names, so undeclared arrivals are the **normal case at
onboarding**. Rejecting costs history permanently.

**Why a separate table rather than `metric_type = 'unknown'`:** if `'unknown'` is legal in `series`, something
in the type-aware rollup path will eventually treat it as a gauge.

⚠️ **Series identity must include a source discriminator** — a stable hash of the declared `source` — in both
the pending key and V001's `series_identity_unique`. `source_key` is *replica* identity, not *scrape*
identity, so without it `cache_size` from `/actuator/prometheus` and `cache_size` from `/api/cache/stats`
merge into one series on the same replica, and `TYPE_SUSPECT` then observes a merged stream. Gate 9 covers the
declaration side. See `UI-D18`'s note on what a constraint test cannot catch.

## `UI-D6` — `metrics_pending` 7 days on daily partitions; `series_pending` retained long

Samples drop by partition at 7 days on a **daily** scheme; the catalog row is retained long or indefinitely.

**Rationale.** What mattered about a longer window was *visibility*, which is the catalog row. Daily
partitioning is correct despite `metrics_raw` being monthly — pending is queried only two ways, so the
cross-partition scan cost that drove monthly does not apply. **Operational delta:** ~8 live partitions plus a
pre-created horizon, and a daily maintenance job.

**Payoff:** 7 days of samples lets `SM-D30`'s `TYPE_SUSPECT` observe monotonicity and *suggest* `counter`
before typing — from evidence, never from the name.

## `UI-D7` — Applicability gates collection, against the `restart-observed` config

A metric whose `when:` is false is **not collected**. Collection applicability evaluates against the last
**`restart-observed`** configuration — never the draft, never `desired`. Between apply and restart the metric
renders as **`pending-config-change`**, not as retirement. Form applicability evaluates against the draft
(`UI-D15`); the divergence is deliberate.

**Rationale.** Corrects [`BRIEF.md`](BRIEF.md) §6, which says `/api/cache/stats` "only works on the Caffeine
provider". It has **no** `@ConditionalOnProperty`. `CacheProvider.stats()` defaults to `return null`; the
controller casts and dereferences it → **HTTP 500**. `size()` defaults to `return 0` → a **plausible, wrong
zero**, so a `cache.size` gauge on Redis plots a flat zero line forever. `RedisCacheProvider` and
`JCacheProvider` override neither.

⚠️ **The first draft also claimed mode-gated routes 404. That is false** — the three
`@ConditionalOnProperty` annotations on `CacheController` are on **handler methods** of an unconditionally
registered `@RestController`, where the annotation is **inert**. The routes exist in every mode; `mode: READ`
does not remove `POST /api/cache`. An MI defect worth recording, and an extractor rule (design §7.3).

⚠️ **Naming the state is load-bearing.** Restart is manual by default (`SM-D15`), so gating on the draft would
stop collecting valid metrics from a still-running Caffeine instance — and `UI-D9`'s cross-check would then
*confirm a false outage*.

**Scope of the justification is narrow.** SM does **not** police MI correctness at runtime. The reason it may
gate is that **SM wrote the field**: SM set `provider: redis`, so it already knows `cache.size` is
meaningless. Self-consistency, not defence.

## `UI-D8` — Absence renders blank, never zero; a gap is > 2× the declared scrape interval

Rollups emit **no row** for an empty bucket; the query API returns absent points as absent; charts neither
zero-fill nor connect across gaps; **no counter delta is computed across a gap.**

**Rationale.** `architecture.md:273` already requires this per acquisition mapping
(`onUnavailable: degrade — never render a flat zero`); these rules are how it holds end to end. It is also
what makes `UI-D9`'s cross-check valid — a zero-filled graph reads as "data arriving, value 0", the opposite
conclusion. The delta rule is the restart case: a restart produces a gap *and* resets the counter, so a delta
across it yields a large negative or a large **false spike**.

⚠️ **The threshold is part of the contract, not either implementation.** `architecture.md:650` supplies the
basis (expected sample count from the declared interval) but no multiplier. Without one in the contract, the
Java rollup and the TypeScript chart will disagree about where a line breaks — so the `UI-D15` fixture suite
must carry gap cases. Relates to `SM-D26`.

## `UI-D9` — Governance is green/red, plus `RESTART_EXPECTED` and `INACTIVE_REPLICA`

**Green** on `UP`, **red** on no response — for **`service` workloads only**. Two qualifiers are **not red**:
`RESTART_EXPECTED` and `INACTIVE_REPLICA`.

**Rationale.** If `/actuator/health` reports UP while the application is broken, that is the MI developer's
defect, not SM's to detect. Cache has no custom `HealthIndicator` and `I1` forbids asking for one. A
three-dimension status widget with `UNKNOWN` states was drafted and cut.

⚠️ **Both qualifiers were already recorded in this repo and the first draft's flat green/red contradicted
both:**

- `architecture.md:797` — "Restart windows render as *unready/gap*, **not 'down'**." Painting an SM-initiated
  restart red reports an outage SM itself caused.
- Open item 15 (`DECISIONS.md:811`) and `architecture.md:835`, verified against `LeaderElection` — a Cache
  `active_standby` **standby reports `health: UP` while processing nothing**. Under flat green/red it is
  **green with empty graphs**, the cross-check never fires, and the plausible-wrong-zero this design exists to
  prevent reappears in a form `when:` cannot gate, since both replicas share identical config.

**Scope:** `LOCKED` `B/D14` makes health for `scheduled` workloads last-run-success rather than liveness.
Scheduled is out of v1, so this is a stated boundary rather than a v1 gap — but the first draft asserted a
universal, which under `I4` conflicted with a locked decision unflagged.

## `UI-D10` — Logs are streamed on request, never persisted; exposure union is declared, not read

`/actuator/logfile` is the interface. Content is streamed and **never stored by SM**. `loggers` `POST` is
privileged and audited. A component declares `loggerPrefix`. Absent `logfile`, the view degrades with a stated
reason.

**Rationale.** Verified via WebFetch: `loggers` returns names and **levels** only; `logfile` returns
**content** with `Range` support and **404s unless `logging.file.name` or `logging.file.path` is set**. So
levels are per-component and content is whole-file. Persisting content would make SM a log store holding
whatever the MI printed, making `I6` far harder.

⚠️ **Neither endpoint works on Cache today** — `logging:` is commented out and exposure is
`health,info,metrics`. Both are fixable in files SM writes (`I1` permits). But
`management.endpoints.web.exposure.include` **replaces rather than merges**, and **"read the existing value
and write the union" is not implementable**: the effective value resolves across 11 property sources
(`SM-D22` makes it unobservable), the classpath `application.yml` is inside the jar, and SM's
`additional-location` file outranks it anyway. The union is therefore **SM's required set ∪ the package's
declared `baselineExposure`**, a curated field verified by launch-spec pre-flight (`SM-D21`).

**Residual risk, stated:** exposure a *customer* added outside the declared baseline is still lost. Pre-flight
surfaces it; the contract cannot prevent it.

## `UI-D11` — Packages are runtime-loaded data, one per published version with an `appliesTo` range

One self-contained package per published version, each declaring the MI version range it serves. **No
per-field `since`/`until`.** Loaded at runtime — **no SM redeploy.** Overlapping ranges rejected at load.

**Rationale.** The npm property required is that installing a package version must not require reinstalling
the runtime; a package baked into the SM image means every MI release needs an SM release, fatal on-prem.
Per-field versioning is rejected **on evidence**: File Mesh's `MIN_VERSION` is non-null on **217 rows** and
`MAX_VERSION` on **0**, while `fetchTemplate` has no version predicate and `IConfigurationTemplate` omits
both. The mechanism was built, partly populated, and never wired.

## `UI-D12` — MI version is declared, corroborated rather than derived ⚠️

The operator declares the version at binding; observation corroborates. `VERSION_DRIFT` and `VERSION_UNKNOWN`
block editing. ⚠️ **`VERSION_UNVERIFIED` must not block** — it is Cache-on-a-VM's permanent state.

**Rationale.** **The version is not observable from the MI**: `spring-boot-maven-plugin` runs `repackage`
only with no `build-info` goal, there is **no `spring-boot-starter-parent`** (BOM import only) so no
parent-configured manifest entries, and the version is `0.0.1-SNAPSHOT`. Adding `build-info` is named in `I1`
as not fair game. Image tags are mutable; digests are not semantic versions. Declared-first matches `SM-D13`.

> ⚠️ **THIS SUPERSEDES `SM-D14`, and the first draft failed to say so.** `SM-D14` states an invariant — "a
> config form is **never** rendered against any schema but the **observed** version's" — and
> [`BRIEF.md`](BRIEF.md) both lists it under "do not re-open" and says `VERSION_UNVERIFIED` blocks editing.
> This decision renders against the **declared** version and **allows** editing under `UNVERIFIED`. The
> reasoning is sound; presenting it as "giving SM-D14's three states a derivation" was an `I3` failure that
> left the register, the brief and the spec stating three different rules.

**Residual:** the packaged jar's `MANIFEST.MF` was not inspected (design §9 item 7). If `Implementation-Version`
appears there, the VM/SSH path gains a corroboration source; declared-first survives either way.

## `UI-D13` — Registry in `sm_control`, values in `sm_data`, referenced by value

`sm_control.solution_package` holds the document; `sm_data.instance_config_value` carries
`(solution, package_version)` **as values, with no cross-schema FK and no join** (`I7`).

**Consequence:** rendering needs schema from one schema and values from the other, so composition happens in
the API layer above both data-access modules — two reads assembled, never one query.

## `UI-D14` — Closed vocabularies, open data — with one canonical definition each

`when:` operators, `type`, `optionsSource.kind`, `action.kind`, `source.kind` and `mark` are **closed sets**.
Adding a member is an SM release; adding a solution, component, field, predicate, metric, action or graph is
**data**. `source.kind` carries transport mechanisms only and **never a solution name**.

**Rationale.** Answers a direct design-session challenge that string-matching predicates require SM code and
so are not truly dynamic. The evaluator is five generic lines with no knowledge of any solution; the real risk
is a **dispatch field**, so every dispatch field is closed. **Acceptance gate:** onboard a second MI from a
package file alone with **zero commits to SM**, enforced by gate 8 and a `kind: cache-stats` negative fixture.

⚠️ **One canonical definition, in `contracts/solution-package.schema.json`.** The first draft stated
`source.kind` as `http-json, http-prometheus, solace-event, jdbc-query` while `architecture.md:288` says
`http_json · prometheus · solace_event · exec` — every spelling different, `exec` silently dropped,
`jdbc_query` silently added. Two authorities for a set whose purpose is CI rejection is self-defeating. The
canonical set is **`http_json`, `prometheus`, `solace_event`, `exec`, `jdbc_query`**; `architecture.md` §10
must point at the schema rather than restate it. Retaining `exec` and adding `jdbc_query` are recorded here as
decisions, not typos.

## `UI-D15` — The view model is a pure function guarded by a shared fixture suite

`project(package, componentSelection, draftValues, observedState) → viewModel` is data in, data out. A shared
JSON fixture suite of `(package, draftValues) → expected viewModel` cases must pass in both the Java and
TypeScript CI, **including gap cases (`UI-D8`) and defaults-applied predicate cases (`UI-D2`)** — the two
places the implementations are most likely to diverge. The suite must pin JSON number formatting and key
ordering, or the comparison itself becomes the divergence.

**Rationale.** Separating the view model from components makes the renderer specifiable while issue `#9`
(`D7` Material-UI vs. Tailwind + Radix) is open — only the last mile is stack-dependent. It is also the
brief's third deliverable in executable form. Two implementations of one function is how `when:` semantics
drift.

## `UI-D16` — Two validation tiers: bind blocks, semantic warns with recorded override

Bind-time failures block. Semantic failures **warn**, with an override recording who, when and why into config
history. Wording must say whose rule it is: not *"invalid"* but *"outside the range Solace recommends"*.

**Rationale.** Cache pulls `spring-boot-starter-validation` and uses it **zero times** — no `@Min`, `@Max`,
`@Pattern` or `@Validated` anywhere. **The MI declares no accept/reject rules**, so there is nothing to
mirror. Bind-time failures are certainties derivable from the Java type — **except inside a `yaml` field**
(`UI-D23`). Semantic rules are **SM's assertions, not the MI's**; a green validation means "SM has no
objection", not "the MI will accept this".

## `UI-D17` — Provenance is a property of the column, not the cell

Three column groups in the authoring sheet: **derived + test-verified** (`component`, `key`, `type` — locked),
**derived suggestion** (`options`, `default`, `when`, `secret`, `discriminator`, `optionsSource` — confirm),
**authored** (`helperText`, `section`, `order`, `advanced`, `required`, `min`, `max`, `pattern` — write). One
`reviewed` flag per row; the package records `authoring: { reviewedRows, totalRows }`.

**Rationale.** "Let the user verify it" is not real verification across ~2,000 rows. Per-column provenance
concentrates attention on the cells needing judgement, and recorded review coverage makes an unreviewed
extraction **visible in the registry**. `helperText` is fully authored because Cache has **zero javadoc
lines**.

## `UI-D18` — T-TYPES asserts the declared type **binds**; a signature asserts T-KEYS and T-TYPES passed

Adds **T-TYPES** alongside `SM-D20`'s bidirectional **T-KEYS**. A signature *means* both passed against MI
version X; a package failing either cannot be signed, therefore cannot load outside dev mode.

**Rationale.** `SM-D20`'s key-set test passes while `ttl` is declared `string` against a `Duration`, and the
first operator entering `120` instead of `120m` discovers it at MI startup. Both read the MI as a fixture —
reading is not developing, so `I1` holds.

⚠️ **"Binds", not "equals".** Cache's `provider` is a Java `String` *and* is Cache's natural discriminator,
which gate 6 requires be an `enum`. Strict type equality would make the flagship example unsatisfiable.
`enum` binds to `String`; `duration` binds to `Duration`; `int` does not bind to `String`. The binding table
lives in the contract schema.

⚠️ **Neither test extends inside a `yaml` field** (`UI-D23`), and **neither can catch a semantic series
merge** — that insert *succeeds*, violating no constraint, which is why `UI-D5` puts the source discriminator
in the key and adds a behavioural test rather than relying on DDL.

## `UI-D19` — Solace authors packages, the customer loads them ⚠️

Authored and signed by Solace; loaded by the customer on their own schedule. Explicit **dev mode** permits
unsigned loads — off by default, loud, recorded.

**Rationale.** Delivers the operational requirement (no SM redeploy per MI release) while leaving `B/D4`
intact.

> ⚠️ **FLAGGED CONFLICT, not resolved (`I4`).** Customer-**authored** packages conflict with `B/D4`, which
> locks publishing a solution to Solace super-admin. This decision sidesteps rather than resolves it. If the
> architect group wants customer authorship: a customer-written package defines the field names SM writes into
> the MI's config, so a wrong name renders config that prevents the MI starting; and version-range
> declarations move into customer hands, which is entitlement scope.

## `UI-D20` — The canvas is SM's generic composer; layout is presentation state

The drag-and-drop canvas is SM's **generic instance composer**, driven by each package's component
declarations. ⚠️ **Canvas layout is presentation state, excluded from the config hash and the rendered file** —
otherwise dragging a node registers as a config change and triggers a restart.

**Rationale.** `FM_DATA_FLOW_CONFIGURATION` is `(flow, component, field) → VALUE` and there is **no edge, node
or link table anywhere** in File Mesh's schema.

⚠️ **The first draft mischaracterized `CONNECTOR_DESIGN` as "canvas layout".** It is JSON
`{components, links}`, maintained server-side and **read semantically** during code promotion. The conclusion
survives only because its `links` are **derived deterministically** from the component set
(`link_id = sourceId_targetId`) — there is no independent routing choice. Citing it as an analogy for
excluding presentation from config was wrong.

## `UI-D21` — Package-supplied UI code is replaced by SM-built named screen types ⚠️

No mechanism for a package to supply its own UI module. Where a non-form screen is needed, SM ships it as a
**named screen type** the package requests.

**Rationale.** File Mesh's flow editor was the sole known case requiring package-supplied code, and `UI-D20`
shows it is expressible in the contract. A package able to execute code in an authenticated console session is
a materially different security posture and should not be adopted speculatively.

> ⚠️ **FLAGGED CONFLICT, not resolved (`I4`).** `D1` is **`LOCKED`** and explicitly includes "a per-solution
> **custom-UI escape hatch**" (`DECISIONS.md:59`). `architecture.md` §14 still states solutions supply a
> custom-UI module, and the phasing table lists the escape hatch as phase-2 scope with sandboxing on the
> Deferred list. This is therefore a **proposed narrowing of a locked decision**, for the architect group.
> The first draft asserted the question had "dissolved" and claimed only one locked conflict existed — exactly
> the error `I4` exists to prevent.

## `UI-D22` — Dynamic option sources and actions are closed-vocabulary members

A field declares static `options` **or** an `optionsSource`, never both. `optionsSource.kind` is closed at
**`sm-catalog`**, whose `entity` is closed at `broker · environment · connection · instance · metric`.
**Actions are not fields** — they hold no value and appear in no config file — and `action.kind` is closed at
`test-connection · browse-remote-path · refresh-catalog`. `type: table` declares its own scalar `columns`.

**Rationale.** The first draft's vocabulary **could not express File Mesh at all**, which made `UI-D14`'s
acceptance gate self-refuting: `COMP_SINK_SOLACE` carries **14 buttons and 8 api-sourced fields**
(`TEST_CONNECTION`, `SELECT_ENV`, `SELECT_BROKER`), and across the template `UI_DATA_SOURCE` shows **`api` 32
and `addApi,editApi` 4** — 36 fields whose options SM must supply — plus `table` on 16 rows.

**A package can never supply a URL.** It names an SM-owned entity and SM owns the query; a package-supplied
endpoint is a package-supplied code path in everything but syntax, which defeats `UI-D14`.

⚠️ **`I6` consequence, load-bearing.** `test-connection`'s inputs include a `secretRef`, so it must execute
**server-side only**, resolving the secret at render time and returning a verdict — never the credential. A
client-side test would require shipping the resolved secret to the browser.

**Residual risk:** three `action.kind` members cover File Mesh's observed buttons by category, but 160 buttons
were not individually classified. A miss is a vocabulary addition, i.e. an SM release.

## `UI-D23` — `yaml` fields are a bounded tunnel with four stated limits

`type: yaml` exists because File Mesh has 12 such fields. Four limits:

1. Rendered into an **isolated document or namespaced subtree** — **never merged over SM-written keys**.
2. Load and save **block** on an inner key colliding with a declared field key in the same `configPath`
   (gate 10).
3. Content is **scanned for secret-looking values** and **warns** — it cannot be masked, so the operator must
   be told it will be stored in clear.
4. Its scope is **explicitly outside T-KEYS**, stated rather than inferred.

**Rationale.** Unbounded, a yaml field defeats three guarantees at once: T-KEYS never sees its inner keys, so
they can shadow properties SM wrote elsewhere in the same file; `secret: true` masking cannot reach inside it,
so a pasted password is stored, diffed and exported in clear, defeating `I6`; and `UI-D16`'s bind-tier
certainty evaporates because Spring binds whatever is there. The first draft admitted `yaml` to the closed
vocabulary and said nothing about its content.

---

## Changelog — R1 review corrections

Independent adversarial review of draft `0db4b4d` returned 5 CRITICAL, 10 MAJOR, 3 MINOR. All accepted; three
were found to be *understated*. Applied in place per the amendment policy above.

| Decision | Change |
|---|---|
| `UI-D2` | Title and body amended for `matchIfMissing`; predicates now evaluate over **defaults-applied** values. Evidence corrected: 271 predicates, not 88 |
| `UI-D5` | **Source discriminator added to series identity**; note that a constraint test cannot catch a semantic merge |
| `UI-D7` | Title and body amended to name **`restart-observed`** as the gating state; the false mode-gated-404 claim retracted with the reason |
| `UI-D8` | Title and body amended with the **2× scrape-interval** gap threshold; `architecture.md:273`'s pre-existing rule cited |
| `UI-D9` | Title and body amended for **`RESTART_EXPECTED`** and **`INACTIVE_REPLICA`**; scoped to `service` workloads per `B/D14` |
| `UI-D10` | Exposure union **redefined** as SM's required set ∪ the package's declared `baselineExposure`; "read the existing value" retracted as unimplementable |
| `UI-D11` | Evidence strengthened — `MIN_VERSION` non-null on 217 rows, `MAX_VERSION` on 0 |
| `UI-D12` | Recast as an explicit **supersession of `SM-D14`**, flagged ⚠️; jar-manifest residual recorded |
| `UI-D14` | `source.kind` given **one canonical definition**; `exec` retained, `jdbc_query` recorded as an addition; `optionsSource.kind` and `action.kind` added as closed sets |
| `UI-D15` | Fixture suite must include gap and defaults-applied cases, and pin JSON formatting |
| `UI-D18` | Amended to **"binds", not "equals"**; `yaml` and semantic-merge exclusions stated |
| `UI-D20` | `CONNECTOR_DESIGN` mischaracterization retracted; conclusion re-grounded on links being derived |
| `UI-D21` | Recast from "dissolved" to a **proposed narrowing of `LOCKED` `D1`**, flagged ⚠️ |
| **`UI-D22`** | **NEW** — dynamic option sources, actions, `type: table`. Closes the unpassable acceptance gate |
| **`UI-D23`** | **NEW** — `yaml` field limits. Closes an `I6` and bind-tier hole |
| Header | "One such conflict exists" corrected to **two conflicts and one supersession** |
