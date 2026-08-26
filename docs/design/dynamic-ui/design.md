# Subsystem A — Solution Package contract, registry and renderer

**Tracking:** issue `#5` · **Status:** DRAFT, R1 review findings applied · **Date:** 2026-08-11
**Relationship to `architecture.md` §14:** this document **supersedes** §14's treatment of config-driven UI
and its custom-UI escape hatch. §14 must be reconciled to it, not read alongside it.
**Entry point:** [`BRIEF.md`](BRIEF.md) — carries an errata header; where the two disagree, this document wins.

Decisions live in [`decisions.md`](decisions.md) as `UI-D1`…`UI-D23`.

> **Revision note.** An independent adversarial review of the first draft returned 5 CRITICAL, 10 MAJOR and
> 3 MINOR findings. All are applied here. Three of the criticals were cases where the correct answer was
> **already recorded and verified in this repo** and the draft failed to propagate it — the project's
> documented failure mode, reproduced. The review's audit trail and what changed are in §10.

---

## 1. Purpose and scope

`REQ-001` requires that onboarding a solution needs no new console code. This document defines the three
things that makes possible:

| Piece | Defines |
|---|---|
| **Contract** | what a solution declares — components, fields, dynamic options, actions, metrics, health, logs |
| **Registry** | where packages are stored, how versions resolve, what is rejected at load |
| **Renderer** | what a screen is obliged to do with a declaration, stated stack-neutrally |

**In scope:** all three categories — Designer (fields), Governance (health), Monitoring (metrics/graphs).

**Out of scope, deliberately:**

| Excluded | Why |
|---|---|
| Renderer **components** | Blocked on issue `#9` — `D7` locks Material-UI, the deleted design handoff specified Tailwind + Radix. Specifying controls against an unknown library settles nothing |
| **Graph-builder UX** | Deferred by explicit decision; the contract it builds on is here |
| **Package-supplied UI code** | `UI-D21` proposes **narrowing** `LOCKED` `D1`'s custom-UI escape hatch to an SM-built named screen type. ⚠️ This is a flagged conflict, not a settled removal — see §9 |

## 2. Evidence base

Every position below is grounded in a verified reading. This project has been burned three times by
confident recall (`I2`), the brief itself contained a wrong mechanism, and **the first draft of this document
quoted File Mesh figures from a lossy grep**. The File Mesh derivation is therefore checked in and
re-runnable: [`evidence/extract-fm-vocabulary.py`](evidence/extract-fm-vocabulary.py) →
[`evidence/vocabulary.txt`](evidence/vocabulary.txt). It reports 0 unparsed rows and 0 unparsable JSON.

| Verified | Source | Finding |
|---|---|---|
| Cache's config surface | `solace-cache-engine/.../CacheEngineProperties.java` | `String`, `int`, `boolean`, **four** enums (`CacheMode`, `KeySource`, `MESSAGINGMODE`, `LEADERELECTION`), **`Duration`**, **`List<String>`**, **`Map<String,String>`**, two-level nesting (`cache-engine.solace.management`), one secret |
| Cache's conditionals | all 8 `@ConditionalOnProperty` uses | Shape is equality; `LeaderElection` is an AND of two. **Two exceptions matter:** `CaffeineCacheProvider.java:24` carries **`matchIfMissing = true`** — semantically *equals-or-absent*, which the grammar cannot express (§3.3); and the three on `CacheController` are on **handler methods**, where the annotation is **inert** (§4.3) |
| Cache's providers | `provider/redis/`, `provider/jcache/` | `RedisCacheProvider` and `JCacheProvider` override **neither** `stats()` nor `size()`. Only Caffeine overrides both |
| **Correction to `BRIEF.md` §6** | `CacheController.java:64`, `CacheProvider.java:18` | The brief says `/api/cache/stats` "only works on the Caffeine provider". It has **no** `@ConditionalOnProperty`. `stats()` defaults to `return null`, the controller casts null to `CacheStats` and dereferences it → **HTTP 500**. `size()` defaults to `return 0` → **returns a plausible, wrong zero**. See §4.3 |
| Cache's validation | whole repo | pulls `spring-boot-starter-validation`, uses it **zero times**. No `@Min`/`@Max`/`@Pattern`/`@Validated`. **The MI declares no accept/reject rules** |
| Cache's health | whole repo | **no custom `HealthIndicator`**. `/actuator/health` reports Spring built-ins only |
| Cache's javadoc | `CacheEngineProperties.java` | **zero doc lines**. `helperText` cannot be derived |
| Cache's version | `pom.xml` | `spring-boot-maven-plugin` runs **`repackage` only, no `build-info` goal**; **no `spring-boot-starter-parent`** (BOM import only), so no parent-configured manifest entries; version is `0.0.1-SNAPSHOT`. **The version is not observable from the MI**, and adding `build-info` is named in `I1` as not fair game. ⚠️ One residual: the built jar's `MANIFEST.MF` was not inspected — see §9 item 7 |
| File Mesh scale | `evidence/vocabulary.txt` | **1,989** template rows. `FM_CONFIGURATION_TEMPLATE_COPY` holds **0 rows** — the brief's shadow-table warning is moot |
| File Mesh `UI_TYPE` | same | `input` 1066 · `autocomplete` 564 · **`button` 160** · `checkbox` 74 · `text` 46 · `radio` 26 · `textarea` 20 · `flag` 16 · **`table` 16** · `int` 1 (a one-row typo the adapter must normalize) |
| File Mesh `UI_TYPE_META` | same | **22 keys**: `advanced` **1989** · `type` 1386 · `disabled` 1117 · `required` 982 · `helperText` 958 · `defaultValue` 832 · `populate` 251 · **`showWhen` 230** · `wrapper` 142 · `strategy` 118 · `label` 90 · `disableCopy` 68 · `password` 54 · `freeText` 46 · **`dependency` 41** · `ignore`/`element` 24 · `multiline`/`columns`/`javaLabel` 16 · `checked` 8 · `disabledOptions` 4 |
| File Mesh applicability | same, **all 271** predicates (230 `showWhen` + 41 `dependency`) | **0 grammar violations.** Every predicate is a flat object of `field: literal`, max 3 keys, one boolean literal. No OR, no negation, no comparison. `dependency` conflates predicate with presentation — `{"fileFormat":"Delimited","label":"Delimited Configuration"}` — a wart not to copy |
| File Mesh dynamic options | same | `UI_DATA_SOURCE`: **`api` 32** · `options` 26 · `columns` 16 · **`addApi,editApi` 4**. So **36 fields need option sources SM must supply** — see §3.5 |
| File Mesh buttons | same | `COMP_SINK_SOLACE` and `COMP_SOURCE_SOLACE` each carry **14 buttons + 8 api-sourced fields** (`TEST_CONNECTION`, `SELECT_ENV`, `SELECT_BROKER`) |
| File Mesh flow model | `FM_DATA_FLOW_CONFIGURATION`, `controller-cd-connectors.ts`, `DesignerScreenComponent.tsx` | Config is `(DATA_FLOW_ID, COMPONENT_TYPE, CONFIGURATION_FIELD) → VALUE`. **No edge, node or link table anywhere.** `CONNECTOR_DESIGN varchar(4000)` is JSON `{components, links}`, maintained server-side and **read semantically** during code promotion — but `links` are **derived deterministically** from the component set (`link_id = sourceId_targetId`), so there is **no independent routing choice**. See §5.4 |
| File Mesh dead columns | `evidence/vocabulary.txt`, `queries/index.ts` | `MIN_VERSION` is **non-null on 217 rows**, `MAX_VERSION` on **0** — and `fetchTemplate` has no version predicate while `IConfigurationTemplate` omits both. Data was entered and never read |
| Actuator log endpoints | Spring Boot 3.3 reference, via WebFetch | `/actuator/loggers` returns **names and levels only**, `POST` changes them. `/actuator/logfile` returns **content** with `Range` support, and **404s unless `logging.file.name` or `logging.file.path` is set** |
| Actuator exposure | same | `management.endpoints.web.exposure.include` defaults to `health` and **specifies the exposed set outright — it replaces, it does not merge** |

The decisive fact for §3.3: **two independent implementations — Spring annotations in Java, JSON in a React
app — converged on AND-of-equalities.** 271 predicates, 0 violations, verified by a re-runnable script.

## 3. The contract

### 3.1 Package envelope

```yaml
solution: cache
packageVersion: 3                 # the package's own revision, independent of the MI's
appliesTo: ">=2.2 <2.5"           # observed MI versions this package serves
provenance:
  author: solace
  signature: ...                  # see §7.4 — asserts T-KEYS and T-TYPES passed
authoring:
  reviewedRows: 23                # this solution's own counts; see §7.2
  totalRows: 25
components: [ ... ]
```

### 3.2 Component — the unit of composition (`UI-D1`)

A solution declares component **types**; an instance composes them. Cache is the degenerate
one-component case; File Mesh is source + sink.

```yaml
components:
  - type: cache-engine
    cardinality: exactly-one      # File Mesh: role: source, cardinality: one-of-many
    configPath: cache-engine      # property prefix SM writes under
    loggerPrefix: com.solace.solutions.cache
    fields:  [ ... ]
    actions: [ ... ]              # §3.5 — never config
    metrics: [ ... ]
    health:  { ... }
    logs:    { ... }
```

### 3.3 Fields, applicability and discriminators (`UI-D2`, `UI-D3`, `UI-D4`)

**The grammar is closed at two constructs: `field: literal` equality, implicitly AND-ed.** No OR, no
comparison, no negation. `!=` was proposed during design and dropped — no evidence, and 271 predicates of
counter-evidence. `in [...]` is held as the single provisional extension, unimplemented until a verified
case appears.

⚠️ **`matchIfMissing = true` is not expressible in this grammar**, and Cache uses it on its most important
conditional: `@ConditionalOnProperty(name="cache-engine.provider", havingValue="caffeine", matchIfMissing=true)`.
Semantically that is *equals-or-absent*. Rather than adding an operator, the contract **resolves absence
before the predicate runs**: the field declares `default: caffeine`, and `when:` evaluates against
**defaults-applied values**, never raw input. `provider` has **no Java field initializer** — its real
if-omitted behaviour lives in the annotation — so the extractor must treat `matchIfMissing` as a
default-value source (§7.3). Without this, `when: {provider: caffeine}` evaluates false on every config that
omits `provider`, and stats/size are never collected on the one provider where they work.

Fields are a **flat list**; each child carries its **whole ancestor chain**, as File Mesh does in production.
Not nested — a flat predicate is one evaluation against the value map, with no tree walk, which is what lets
the Java and TypeScript evaluators stay in agreement (§6.5).

```yaml
fields:
  - key: ACTION
    type: enum
    options: [download, deploy]
    discriminator: true
    ui: { section: Action, order: 1 }

  - key: DOWNLOAD_LOCATION
    type: enum
    options: [GIT, LOCAL]
    discriminator: true
    when: { ACTION: download }
    ui: { section: Action, order: 2 }

  - key: GIT_CONFIGURATION
    type: enum
    options: [CLOUD, ONPREM]
    discriminator: true
    when: { ACTION: download, DOWNLOAD_LOCATION: GIT }      # full chain, not just the parent
    ui: { section: Git, order: 1 }

  - key: GIT_CLOUD_TOKEN
    type: string
    secret: true
    when: { ACTION: download, DOWNLOAD_LOCATION: GIT, GIT_CONFIGURATION: CLOUD }

  - key: DEPLOYMENT_MODE
    type: enum
    options: [standalone, highavailable]
    when: { ACTION: deploy }                                 # the other branch
```

**Discriminator semantics.**

- Changing a discriminator **re-evaluates all descendants**, collapsing deeper branches.
- Values in inactive branches are **retained in the draft** so switching back restores work, but are
  **never rendered into the config file**.
- Diff, export and the config hash follow the **rendered** set, not the draft.

**Two load-time gates close this design's gaps** (§6.4): gate 5 rejects an unsatisfiable predicate
(`{ACTION: deploy, DOWNLOAD_LOCATION: GIT}` — `DOWNLOAD_LOCATION` exists only under `download`); gate 5b
rejects a `when:` **value** that is not a member of the referenced field's `options`. Without 5b,
`when: {ACTION: deplyo}` — or `{ACTION: deploy}` after an option is renamed to `DEPLOY` — passes every gate
and the field is silently unreachable forever, with nothing to complain because validation only runs the
live branch.

### 3.4 Closed vocabularies, open data (`UI-D14`)

This is the rule that protects `REQ-001`. The predicate evaluator is generic —

```java
boolean applies(Map<String,Object> when, Map<String,Object> values) {   // values = defaults-applied
    for (var e : when.entrySet())
        if (!Objects.equals(values.get(e.getKey()), e.getValue())) return false;
    return true;
}
```

— with no knowledge of any solution. Per-solution code can only creep in through a **dispatch field**, so
every such field is a closed set:

> **The contract's vocabularies are closed sets; the data inside them is open.**
>
> - `when:` operators — `==`, `allOf`
> - `type` — `string, int, bool, enum, duration, list, map, table, yaml, secret`
> - `optionsSource.kind` — `sm-catalog` (§3.5)
> - `action.kind` — `test-connection, browse-remote-path, refresh-catalog` (§3.5)
> - `source.kind` — **transport mechanisms only**, canonically spelled as in `architecture.md` §10:
>   `http_json`, `prometheus`, `solace_event`, `exec`, `jdbc_query`. **Never a solution name.**
>   `kind: cache-stats` is the failure mode
> - `mark` — `line, gauge, single-stat` (per `SM-D31`)
>
> Adding a **member** to any set is an SM release. Adding a solution, component, field, predicate, metric,
> action or graph is **data**.

⚠️ **`source.kind` has one canonical definition, in `contracts/solution-package.schema.json`.**
`architecture.md` §10 must point at it rather than restate it — two authorities for a set whose whole
purpose is CI rejection is self-defeating. The first draft of this document silently dropped `exec` and
added `jdbc_query`; both are recorded as decisions in `UI-D14`, not typos.

**Acceptance gate:** onboard a second MI from a package file alone, with **zero commits to SM**. §3.5 exists
because the first draft's vocabulary could not express File Mesh, which made this gate self-refuting.

### 3.5 Dynamic options and actions (`UI-D22`)

File Mesh needs two things static fields cannot express: **36 fields whose options come from a lookup**, and
**160 buttons**. Without both, the acceptance gate above is unpassable by the very fixture §8.1 names.

**Options from a lookup.** A field declares either static `options` or an `optionsSource`, never both:

```yaml
  - key: SELECT_BROKER
    type: enum
    optionsSource: { kind: sm-catalog, entity: broker }
    when: { ALLOW_SEMP: "yes" }
```

`optionsSource.kind` is closed at **`sm-catalog`**, whose `entity` is itself closed:
`broker · environment · connection · instance · metric`. These are **SM-owned entities** — the package names
what it wants, SM owns the query. A package can never supply a URL, because a package-supplied URL is a
package-supplied code path in everything but syntax.

**Actions are not fields.** They render as controls but hold no value and appear in no config file:

```yaml
actions:
  - key: TEST_CONNECTION
    kind: test-connection
    inputs: [ solace.host, solace.vpn, solace.username, solace.password ]
    when: { solace.enabled: true }
```

`action.kind` is closed at `test-connection · browse-remote-path · refresh-catalog`. Each is
**SM-implemented**; the package requests it and names its inputs.

⚠️ **`I6` consequence, load-bearing.** `test-connection`'s inputs include a `secretRef`. The action must
execute **server-side only**, resolving the secret at render time, and its response must carry a verdict and
never the credential. A client-side connection test would require shipping the resolved secret to the
browser, which `I6` forbids outright.

**`type: table`** covers File Mesh's 16 repeatable-record fields, declaring its own `columns` — each column
a field declaration restricted to scalar types. `list` and `map` remain for the simple cases Cache needs.

### 3.6 `yaml` fields are a bounded tunnel (`UI-D23`)

`type: yaml` exists because File Mesh has 12 such fields. Its content is **not** a set of package fields, so
three guarantees do not extend into it: T-KEYS never sees its inner keys, `secret: true` masking cannot mask
a password pasted inside it, and §7.1's bind-tier "certainty" evaporates because Spring binds whatever is
there. Left unbounded it defeats `I6` and `UI-D16` together. Therefore:

1. A yaml field renders into an **isolated document or a namespaced subtree** — **never merged over
   SM-written keys**, so it cannot shadow a property SM set elsewhere in the same file.
2. Load and save **block** on any inner key colliding with a declared field key in the same `configPath`.
3. Content is **scanned for secret-looking values** and **warns** (semantic tier, §7.5) — it cannot be
   masked, so the operator must be told it will be stored in clear.
4. Its scope is **explicitly outside T-KEYS**, stated in the contract rather than left as an inference.

## 4. Metrics, discovery and quarantine

### 4.1 Declaration

```yaml
metrics:
  - name: cache.hit_rate
    metricType: gauge                 # REQUIRED. Never inferred (SM-D26)
    unit: ratio                       # REQUIRED
    source:
      kind: http_json                 # canonical spelling, §3.4
      path: /api/cache/stats
      port: 8081
      pointer: /hitRate
      onUnavailable: degrade          # REQUIRED — architecture.md §10: mark unavailable, never a flat zero
    when: { provider: caffeine }      # gates COLLECTION — see §4.3 for against which state
    layer: package-baseline           # vs. user-extension, per SM-D30
```

`onUnavailable` was omitted from the first draft despite `architecture.md:273` already requiring it per
mapping. It is mandatory: its absence is exactly how a flat zero reaches a graph.

### 4.2 Undeclared arrivals are quarantined, not rejected (`UI-D5`, `UI-D6`)

`V001__telemetry_schema.sql` makes `series.metric_type` `NOT NULL` with a three-value CHECK and FKs
`metrics_raw` to `series`, so an undeclared metric has **no legal row** — the schema rejects **by
construction**. That is wrong, because **SM configures a scrape *path*, not a metric list.** One path returns
many names and SM cannot enumerate them in advance, so undeclared arrivals are the **normal case at
onboarding**. Rejecting costs history permanently; storing costs disk.

| Condition | Behaviour |
|---|---|
| Arrives **undeclared** | **Accepted**, quarantined, listed as "needs typing". Not plottable |
| Arrives **over the per-instance cardinality cap** | **Rejected and surfaced** (`SM-D30`) |

Implemented as a **quarantine pair**, not by relaxing the CHECK to allow `'unknown'` — if `'unknown'` is
legal in `series`, something in the type-aware rollup path will eventually treat it as a gauge.

| Table | Holds | Retention | Partitioning |
|---|---|---|---|
| `series_pending` | catalog row — name, labels, source identity, `first_seen`, `last_seen`, instance | **long / indefinite** (tiny) | none |
| `metrics_pending` | the samples | **7 days**, dropped by partition | **daily** |

Splitting the two makes 7 days cheap: what mattered about a longer window was *visibility*, which is the
catalog row. Daily partitioning is correct despite `metrics_raw` being monthly — pending is queried only two
ways ("list undeclared metrics seen recently", "promote one series"), so the cross-partition scan cost that
drove monthly does not apply. **Operational delta:** ~8 live partitions plus a pre-created horizon, and a
**daily** maintenance job.

⚠️ **Series identity must include the source.** Neither `series_pending`'s key nor V001's
`series_identity_unique` distinguishes `cache_size` arriving from `/actuator/prometheus` from `cache_size`
arriving from `/api/cache/stats` on the same replica. `source_key` is *replica* identity, not *scrape*
identity. Both keys therefore add a **source discriminator** — a stable hash of the declared
`source` — giving `(tenant, instance, placement, metric_name, source_discriminator, labels_fingerprint)`
for pending, and the same addition to the declared-series key. Without it two paths interleave into one
series and `TYPE_SUSPECT` then "observes" a merged stream. See §8.3 for why a constraint test cannot catch
this.

**Payoff.** 7 days of retained samples lets `SM-D30`'s `TYPE_SUSPECT` check observe monotonicity and
**suggest** `counter` before anyone types the metric — from evidence, never from the name. The dropdown is
`series`; the needs-typing list is `series_pending`.

**Promotion** is `INSERT…SELECT` into `series` + `metrics_raw`, carrying retained history across, followed by
a **rollup backfill** for the promoted window.

### 4.3 Applicability gates collection, against the restart-observed config (`UI-D7`)

The brief's stated mechanism was wrong, and the true one is more dangerous. Switching Cache's `provider`
away from `caffeine` produces **two** failure modes, not three:

| Endpoint | Guard | On non-Caffeine |
|---|---|---|
| `/api/cache/stats` | none | **HTTP 500** — `stats()` returns `null`, cast and dereferenced |
| `/api/cache/size` | none | **returns `0`** |

⚠️ **The first draft claimed a third mode — mode-gated routes returning 404 — and that is false.** The three
`@ConditionalOnProperty` annotations on `CacheController` sit on **`@PostMapping`/`@DeleteMapping` handler
methods** of an unconditionally-registered `@RestController`. Spring evaluates conditions during
bean-definition registration; `RequestMappingHandlerMapping` registers every handler method regardless. **The
routes exist in every mode.** An operator setting `mode: READ` believing writes are gone still has a working
`POST /api/cache`. This is an MI defect worth recording, and an extractor rule: **method-level
`@ConditionalOnProperty` must be ignored, never harvested as a `when:`** (§7.3).

The surviving hazard is `/api/cache/size` returning `0`. A `cache.size` gauge on a Redis-backed instance does
not error, does not 404 and does not gap — it **plots a flat zero line forever**, and every layer reports
healthy.

⚠️ **This is a confirmation, not a discovery.** `architecture.md:290-293` already states it: *"switching Cache
to Redis through SM's own Designer form turns its KPI graphs into 500s and a flat zero, and a flat zero is
worse than an error"*, along with the per-mapping `when:` predicate and `onUnavailable:`. The brief's wrong
mechanism and the architecture's correct one coexisted in this repo, and the first draft of this document
rediscovered the architecture's version by reading source instead of reading §10. What §4.3 adds is only the
precise mechanism — `null` cast and dereferenced versus a `0` default — and the correction that mode-gating
does not work at all.

So a metric whose `when:` is false is **not collected**. Two things must be stated precisely, because the
first draft left both implicit:

**Which state gates collection.** Applicability for **collection** evaluates against the last
**`restart-observed`** configuration — never the draft, and never `desired`. §6.5's obligation that `when:`
evaluates against the draft applies **to the form only**. This divergence is correct and deliberate: restart
is manual by default (`SM-D15`), so an operator can save `provider: redis` and not restart for hours. Gating
collection on the draft would stop collecting valid metrics from a still-running Caffeine instance — and
`UI-D9`'s cross-check would then **confirm a false outage**. Between apply and restart, the metric renders
as **`pending-config-change`**, not as retirement.

**Why SM may gate at all.** SM does **not** police MI correctness at runtime — an MI returning wrong data on
a configured path is an integration defect caught in dev testing. The justification is narrower: **SM wrote
the field.** SM set `provider: redis`, so SM already knows `cache.size` is meaningless. Declining to scrape
it is self-consistency, not defence.

### 4.4 Absence is not zero (`UI-D8`)

Zero is a legitimate value, so absence must be visually distinct. `architecture.md:273` already states the
rule per acquisition mapping (`onUnavailable: degrade — never render a flat zero`); these four rules are how
it holds end to end:

1. **Rollups emit no row for an empty bucket** — never a zero row. `GROUP BY bucket` does this naturally;
   the hazard is adding a `generate_series` LEFT JOIN because a chart looked ragged.
2. **The query API returns absent points as absent** — not `null` that a chart library coerces to `0`.
3. **The chart does not connect across gaps and does not zero-fill.** The line breaks.
4. **No counter delta is computed across a gap.** A restart produces a gap *and* resets the counter; a delta
   across it yields a large negative or, with naive reset-handling, a large **false spike**.

⚠️ **Rule 4 needs a threshold, or it is unimplementable.** A gap is declared when the interval since the
previous sample exceeds **2 × the declared scrape interval** (`architecture.md:650` supplies the basis —
expected sample count from the declared interval — but no multiplier). The multiplier lives in the
**contract**, not in either implementation, and the §6.5 fixture suite must carry gap cases: without it the
Java rollup and the TypeScript chart will disagree about where a line breaks.

## 5. Governance and logs

### 5.1 Health is green/red, with two non-red qualifiers (`UI-D9`)

Cache has no custom `HealthIndicator`, so `/actuator/health` reports that the JVM is alive and the disk is
not full. `I1` forbids asking for more.

**If `/actuator/health` reports UP, the application is working.** If it reports UP while the application is
broken, that is the MI developer's defect, not SM's to detect. Governance is **green** on `UP` and **red** on
no response. The three-dimension status widget with `UNKNOWN` states drafted during design was cut.

⚠️ **But two states already recorded in this repo are not red, and the first draft's flat green/red
contradicted both:**

| Qualifier | Why it is not red |
|---|---|
| **`RESTART_EXPECTED`** | `architecture.md:797`: "Restart windows render as *unready/gap*, **not 'down'**." An SM-initiated restart makes the MI non-responsive; painting that red reports an outage SM itself caused |
| **`INACTIVE_REPLICA`** | Open item 15 (`DECISIONS.md:811`) and `architecture.md:835`, verified against `LeaderElection`: a Cache `active_standby` **standby reports `health: UP` while processing nothing**. Under flat green/red it is **green with empty graphs**, so the cross-check below never fires — and the plausible-wrong-zero this design was built to prevent reappears in a form `when:` cannot gate, since both replicas share identical config |

**Scope:** `UI-D9` applies to **`service` workloads only.** `LOCKED` `B/D14` makes health for `scheduled`
workloads last-run-success rather than liveness. Scheduled is out of v1 scope (ratification matrix), so this
is a stated boundary, not a v1 gap.

The operator workflow this supports, and the reason §4.4 is load-bearing:

> **red badge → open graphs → no data confirms it.**

That cross-check only works if the graph shows a gap. A zero-filled graph would show a flat line at zero and
read as "data arriving, value is 0" — the opposite conclusion. And it only works if `INACTIVE_REPLICA` is
distinguishable, or a healthy standby looks identical to a broken active.

### 5.2 Logs (`UI-D10`)

| Endpoint | Returns |
|---|---|
| `/actuator/loggers` | logger **names and levels**, not content. `POST` changes a level at runtime |
| `/actuator/logfile` | **file contents**, with HTTP `Range` support for tailing |

**Levels** are per-component and **content** is whole-file. The useful workflow is: set `DEBUG` on one
logger, then read the file. A component declares its `loggerPrefix`, making "turn on DEBUG for this
component" declarative for any MI — a *suggestion* in the extractor (§7.3), because logger names aligning
with the component decomposition is an assumption.

Neither endpoint works on Cache today: its `logging:` block is **commented out** so `logging.file.name` is
unset and `logfile` **404s**, and its exposure list is `health,info,metrics`. Both are fixable in files SM
writes (`I1` permits).

⚠️ **`exposure.include` replaces rather than merges, and "read the existing value" is not implementable.**
Writing a partial list silently un-exposes `metrics` and **kills SM's own telemetry**. But SM cannot read the
effective value to union with: it resolves across 11 property sources (`SM-D22` makes it unobservable), the
classpath `application.yml` lives inside the jar, and SM's `additional-location` file **outranks** that
classpath file anyway. The union is therefore defined as:

> **SM's required set ∪ the package's declared `baselineExposure`** — a curated per-solution field, verified
> by launch-spec pre-flight (`SM-D21`).

**Residual risk, stated rather than discovered:** exposure a *customer* added outside the package's declared
baseline is still lost. If their build exposed `prometheus` for their own scraping, SM's write un-exposes it.
Pre-flight surfaces this; the contract cannot prevent it.

Two constraints:

- **Log content is streamed on request and never persisted by SM.** Otherwise SM becomes a log store holding
  whatever the MI printed, making `I6` far harder.
- **`loggers` POST is privileged and audited** — it changes behaviour on a running production MI.

If an MI does not expose `logfile`, the log view **degrades with a stated reason** — `I1`'s "optional with a
defined degradation".

### 5.3 Declaration

```yaml
health:
  probe: { kind: actuator-health, path: /actuator/health }
logs:
  logfile: { path: /actuator/logfile }
  baselineExposure: [ health, info, metrics ]     # curated; the union base, §5.2
  requiresConfig: [ logging.file.name ]
```

### 5.4 The canvas, and what `UI-D21` actually proposes (`UI-D20`, `UI-D21`)

File Mesh's flow editor was the one integration believed to require a package-supplied UI module. On the
evidence it does not: config is `(flow, component, field) → VALUE` with **no edge, node or link table
anywhere**, and while `CONNECTOR_DESIGN` persists `{components, links}` and is read semantically during code
promotion, its **`links` are derived deterministically from the component set** (`link_id =
sourceId_targetId`). There is no independent routing choice for a contract to express.

⚠️ The first draft called `CONNECTOR_DESIGN` "canvas layout". That is wrong — it is a composition record
that server-side code reads. The conclusion survives; the characterization did not.

Therefore:

1. **The drag-and-drop canvas is SM's generic instance composer** — built once, driven by each package's
   component declarations. Not per-solution code. Cache is the one-node case; File Mesh is source + sink.
2. ⚠️ **`UI-D21` proposes to *narrow* `LOCKED` `D1`, not to dissolve a question.** `D1` explicitly includes
   "a per-solution **custom-UI escape hatch**" (`DECISIONS.md:59`). Proposing an SM-built named screen type
   instead is a supersession the architect group must take — see §9. The first draft asserted the question
   had "dissolved", which under `I4` is precisely the error the invariant exists to prevent.
3. ⚠️ **Canvas layout is presentation state, excluded from the config hash and the rendered file.** Otherwise
   dragging a node registers as a configuration change and triggers a restart.

## 6. Registry, versions and rendering

### 6.1 Version is declared, not derived (`UI-D12`) — proposes superseding `SM-D14`

**The version is not observable from the MI** (§2), and making it so requires the `build-info` goal, which
`I1` forbids. What SM can see:

| Runtime | Mechanism | Problem |
|---|---|---|
| Kubernetes | image tag from the pod spec | tags are mutable; `latest` is common |
| Docker | image tag / digest | a digest is not a semantic version — cannot range-match `>=2.2 <2.5` |
| VM | jar filename, or `MANIFEST.MF` over SSH | filename is convention; Cache configures no manifest entries |

So the operator **declares** the version at binding time and observation **corroborates** it — the
declared-first pattern already chosen for agentless instances (`SM-D13`).

⚠️ **This proposes superseding `SM-D14` in two respects, and must be recorded as such rather than presented
as a derivation.** `SM-D14` states an invariant — "a config form is **never** rendered against any schema but
the **observed** version's" — and `BRIEF.md:72` both lists it under "do not re-open" and says
`VERSION_UNVERIFIED` blocks editing. This document renders against the **declared** version and **allows**
editing under `UNVERIFIED`. The reasoning is sound; the process was not. Under `I3` a supersession is a new
decision, not prose.

| State | Meaning | Editing |
|---|---|---|
| `VERSION_DRIFT` | declared 2.4, image tag says 2.3 | **blocked**, reason stated |
| `VERSION_UNKNOWN` | no loaded package's `appliesTo` covers the declared version | **blocked**, reason stated |
| `VERSION_UNVERIFIED` | nothing observable corroborates the declaration | **allowed**, badge shown |

⚠️ `VERSION_UNVERIFIED` **must not block.** It is Cache-on-a-VM's permanent state; blocking makes v1 unusable.

### 6.2 Packages are runtime-loaded data (`UI-D11`, `UI-D19`)

- **One self-contained package per published version**, each declaring the range it serves. **No per-field
  `since`/`until`.**
- **Loaded at runtime** via upload or a watched directory. **No SM redeploy.**

The npm property required is that installing a package version must not require reinstalling the runtime; a
package baked into the SM image means every MI release needs an SM release, which is fatal on-prem.
Per-field versioning is rejected **on evidence**: File Mesh's `MIN_VERSION` is populated on 217 rows,
`MAX_VERSION` on none, and neither is read by `fetchTemplate` or present in `IConfigurationTemplate`. The
mechanism was built, populated, and never wired.

**Authorship: Solace authors, the customer loads when ready** — full flexibility on *when*, `B/D4` intact. An
explicit **dev mode** permits unsigned loads: off by default, loud, recorded.

### 6.3 Storage and the plane boundary (`UI-D13`)

```
sm_control.solution_package      (solution, package_version, applies_to, signature, document jsonb)
sm_data.instance_config_value    (tenant, instance, component, field_key, value, …,
                                  declared_package (solution, package_version))   -- VALUES, not FKs
```

Per `I7` that reference is carried **as values, with no cross-schema foreign key and no join**. Consequence:
rendering needs schema from one schema and values from the other, so composition happens in the API layer
**above both data-access modules** — two reads assembled, never one query.

### 6.4 Resolution and load-time gates

```
resolve(solution, declaredVersion):
    hits = loaded packages for solution whose appliesTo contains declaredVersion
    hits.isEmpty()  -> VERSION_UNKNOWN
    hits.size() > 1 -> unreachable; overlap is rejected at load (gate 3)
    else            -> that package
```

The registry **rejects on upload**, never fails at render:

| # | Gate |
|---|---|
| 1 | validates against the package JSON Schema |
| 2 | signature verifies (unsigned only in explicit, recorded dev mode) |
| 3 | `appliesTo` does not overlap another loaded package for the same solution |
| 4 | every `when:` key names a field in the same component |
| 5 | every `when:` key is **reachable** under the same ancestor chain |
| **5b** | every `when:` **value** is type-compatible with, and for enums a **member of**, the referenced field's `options` |
| 6 | every discriminator is an `enum` — free text cannot drive a subtree |
| 7 | every metric declares `metricType`, `unit` **and** `source.onUnavailable` |
| 8 | every `type`, `optionsSource.kind`, `action.kind`, `source.kind` and `mark` is a member of its closed vocabulary |
| **9** | **field keys, metric names and action keys are each unique within a component** |
| **10** | no `yaml` field's declared subtree collides with a declared field key in the same `configPath` (§3.6) |
| **11** | a field declares `options` **or** `optionsSource`, never both |

Gates 5b, 9, 10 and 11 were absent from the first draft. Gate 9 matters most: two declared metrics sharing a
name passed load and merged silently.

### 6.5 The renderer's obligations (`UI-D15`)

Specifiable while issue `#9` is open because the **view model is separated from the components**.

```
viewModel = project(package, componentSelection, draftValues, observedState)

  -> sections[ { title, order,
                 fields[ { key, controlKind, value, options, enabled, visible,
                           masked, advanced, helperText, validations[] } ],
                 actions[ { key, kind, enabled } ] } ]
     graphs[  { metric, mark, transform, resolution, thresholds } ]
     status   { health: green|red|restart_expected|inactive_replica, versionState, configState }
```

`project` is data in, data out — no DOM, no component library, no HTTP. It is therefore the brief's third
deliverable in executable form.

| Obligation | Why it is load-bearing |
|---|---|
| `when:` evaluated against **defaults-applied draft values** for the **form** | §3.3 — raw draft breaks `matchIfMissing` fields |
| **Collection** applicability evaluated against **`restart-observed`**, not the draft | §4.3 — the divergence is deliberate |
| Hidden branches keep draft values; **only visible fields render into config** | §3.3 |
| Changing a discriminator **re-evaluates all descendants** | File Mesh's chains are three deep |
| `advanced: true` behind a toggle | **all 1,989** File Mesh rows carry the flag; a 132-field FTP component is unusable without it |
| `secret: true` masked in form, **diff, export and history** | `I6` |
| `test-connection` executes **server-side only** | §3.5 — client-side would ship the resolved secret |
| **Bind-tier validation blocks; semantic-tier warns** | §7.5 |
| `VERSION_DRIFT`/`UNKNOWN` block; **`UNVERIFIED` does not** | §6.1 |
| Counters render as **rate or delta**, never a raw monotonic climb | `SM-D26` |
| Gaps break the line at **> 2 × declared scrape interval**; no zero-fill, no connect-across | §4.4 |
| Resolution tier chosen automatically (≤7d raw · 7–90d hourly · >90d daily) | User picks metric and mark, never the tier |
| Health green/red **plus `RESTART_EXPECTED` and `INACTIVE_REPLICA`** | §5.1 |
| Canvas layout excluded from the config hash | §5.4 |
| `restart-observed` is **never** presented as convergence | `SM-D22` |

**The divergence guard.** `project` must exist in Java and TypeScript. The contract ships a **shared fixture
suite** — `(package, draftValues) → expected viewModel` cases in JSON — that both implementations must pass
in their own CI. It must include **gap cases** (§4.4) and **defaults-applied predicate cases** (§3.3), the two
places the implementations are most likely to diverge. The suite's own design must pin JSON number
formatting and key ordering, or the comparison itself becomes the divergence.

## 7. Validation and the authoring pipeline

### 7.1 What "rejected by the MI" actually means

Cache declares **no accept/reject rules at all** (§2), so the concept splits:

| Tier | What it is | Can SM prevent it? |
|---|---|---|
| **Bind-time** | `max-size: abc`, bad enum, malformed `Duration` — Spring's binder fails, **app will not start** | **Yes, with certainty.** Derivable from the Java type. **Except inside a `yaml` field** (§3.6) |
| **Semantic** | `max-size: -1`, unreachable `host`, a JSON Pointer to a nonexistent field | **No.** Binds fine; fails at runtime or misbehaves silently |

### 7.2 The sheet: provenance is a property of the column (`UI-D17`)

| Group | Columns | Reviewer's job |
|---|---|---|
| **Derived + test-verified** | `component`, `key`, `type` | **Don't touch.** Locked. Guaranteed by CI forever (§7.4) |
| **Derived suggestion** | `options`, `default`, `when`, `secret`, `discriminator`, `optionsSource` | **Confirm.** Machine-proposed, not machine-guaranteed |
| **Authored** | `helperText`, `section`, `order`, `advanced`, `required`, `min`, `max`, `pattern` | **Write.** Nothing can derive these |

One `reviewed` flag per row. The package records `authoring: { reviewedRows, totalRows }` so a package
assembled from unreviewed extraction is **visible in the registry**. Per the brief's existing position the
sheet is an **authoring front-end** — it emits a valid package validated by the same JSON Schema as any other
source, is not a parallel schema source, and is never authoritative on round-trip.

### 7.3 The extractor skill

For a Spring Boot MI the tier-1 type mapping is explicit:

```
String → string      int|long → int        boolean → bool
enum   → enum + options from the constants
Duration → duration  List<String> → list   Map<String,String> → map
nested @ConfigurationProperties class → nested path segment
```

Four rules, three of them corrections:

- **Defaults come from field initializers — *and* from `matchIfMissing`.** `provider` has no initializer, so
  the initializer rule alone derives nothing for Cache's most important field; its real if-omitted value
  (`caffeine`) lives in the annotation (§3.3). Never from the sample `application.yml`, whose values disagree
  with the initializers three times and which contains `ivr: /Users/sravanthotakura/Work/ivr.txt`.
- ⚠️ **Method-level `@ConditionalOnProperty` must be ignored, not harvested.** It is inert (§4.3). Harvesting
  it produces a `when:` for behaviour that does not exist.
- **Class-level `@ConditionalOnProperty` becomes a *suggested* `when:`** — group 2, because it guards *beans*,
  not fields.
- **File Mesh's extractor is a SQL query**, not an AI task — key, type, default, `helperText`, section, order,
  `advanced`, `showWhen` and `UI_DATA_SOURCE` are all columns. It must normalize File Mesh's real defects:
  `UI_TYPE` disagreeing with `meta.type`, `defaultValue` untyped (`0` / `"10"` / `"0"`-for-boolean),
  `type: 'string'` vs `'text'`, the one-row `UI_TYPE: int`, and `dependency`'s predicate/`label` conflation.

### 7.4 Two contract tests, and what a signature means (`UI-D18`)

| Test | Asserts |
|---|---|
| **T-KEYS** (`SM-D20`, existing) | bidirectional: every package key exists in the MI; every MI property has a package field or an explicit exclusion with a reason. **Does not extend inside `yaml` fields** (§3.6) |
| **T-TYPES** (new) | every field's declared `type` **binds** to the MI's actual property type |

⚠️ **"Binds", not "equals".** Cache's `provider` is a Java `String`, and it is also Cache's natural
discriminator — which gate 6 requires be an `enum`. A strict type-equality test would make the flagship
example unsatisfiable. `enum` binds to `String`; `duration` binds to `Duration`; `int` does not bind to
`String`. The binding table lives in the contract schema.

T-TYPES closes the gap `SM-D20` leaves open: a key-set test passes happily while `ttl` is declared `string`
against a `Duration`, and the first operator entering `120` instead of `120m` discovers it at MI startup.
Both tests read the MI as a fixture — reading is not developing, so `I1` holds.

> **A signature asserts that T-KEYS and T-TYPES passed against MI version X.** A package failing either
> cannot be signed, therefore cannot load outside dev mode.

### 7.5 The two tiers in the operator's language (`UI-D16`)

**Bind-tier blocks.** **Semantic-tier warns, with an explicit override recorded** — who, when, and why, into
config history. Wording must say whose rule it is: not *"invalid"* but *"outside the range Solace recommends
(1–1,000,000)"*. Semantic rules are **SM's assertions, not the MI's**; a green validation means "SM has no
objection", not "the MI will accept this".

## 8. Executable deliverable

### 8.1 Artifacts

| Artifact | Proves |
|---|---|
| `evidence/extract-fm-vocabulary.py` + `vocabulary.txt` | §2's File Mesh figures are re-derivable, not recalled. **Exists** |
| `contracts/solution-package.schema.json` | the contract is expressible, not merely describable |
| `contracts/fixtures/cache-2.4.package.json` | the **real** Cache surface validates — `Duration`, `List<String>`, `Map<String,String>`, two-level nesting, four enums, a secret, a `String` discriminator |
| `contracts/fixtures/filemesh-ftp-solace.package.json` | it generalizes — **`COMP_SOURCE_FTP` (132 fields) + `COMP_SINK_SOLACE` (90 fields, 14 buttons, 8 api-sourced)**, the three-level chain, `type: table` |
| `contracts/fixtures/viewmodel/*.json` | `project()` is deterministic, and Java and TypeScript agree — including gap and defaults-applied cases |
| `backend/.../V002__metric_pending.sql` + `V002_constraints_test.sql` | quarantine, daily partitions, promotion, the source discriminator |

⚠️ **The File Mesh fixture is the gate on §3.5, and should be attempted before ratification.** The first
draft's vocabulary could not express `COMP_SINK_SOLACE` at all; §3.5 is the fix, and only building the
fixture proves the fix is sufficient. Prose has now been wrong about this once.

### 8.2 The negative fixtures matter more than the positive ones

| Fixture | Rejected by |
|---|---|
| `when:` names a field not in the component | gate 4 |
| `when: {ACTION: deploy, DOWNLOAD_LOCATION: GIT}` | gate 5 — unreachable |
| `when: {ACTION: deplyo}` | gate 5b — value not an option member |
| `discriminator: true` on a free-text `string` | gate 6 |
| metric with `metricType` but no `unit`, or no `onUnavailable` | gate 7 |
| **`source.kind: cache-stats`** | gate 8 — the `REQ-001` guard |
| **two metrics sharing a name in one component** | gate 9 |
| a `yaml` field whose subtree collides with a declared key | gate 10 |
| a field declaring both `options` and `optionsSource` | gate 11 |
| an `optionsSource` supplying a URL rather than an `entity` | gate 8 |
| two packages with overlapping `appliesTo` | gate 3 |
| unsigned package, dev mode off | gate 2 |

### 8.3 What V002 can and cannot settle

⚠️ **`series_pending` identity is not settleable by a constraint test, and the first draft said it was.** A
two-scrape-path merge (§4.2) **violates no constraint** — the insert *succeeds* and two logical series become
one. DDL proved "cannot be created" defects in V001; it cannot prove "should have been two series." The fix
is the **source discriminator in the key** (§4.2) plus a **behavioural** test that ingests the same metric
name from two declared sources and asserts two `series_pending` rows. Gate 9 covers the declaration side.

**Promotion partition coverage** is settled by arithmetic, not a test: 7-day-old data always lands in the
current or previous monthly partition, both of which exist under a 6-month retention with partitions
pre-created ahead (`V001:92-97`). The only residual is promotion during the **first week of a brand-new
deployment** whose earliest partition begins this month; the promotion path checks coverage and pre-creates.

## 9. Open items and flagged conflicts

⚠️ **Flagged, not resolved** (per `I4`). **Two conflicts with `LOCKED` decisions, and one supersession:**

| | Conflict |
|---|---|
| **`UI-D19` vs `B/D4`** | Customer-**authored** packages conflict with `B/D4`, which locks publishing a solution to Solace super-admin. §6.2 chose *Solace authors, customer loads*, which delivers the operational requirement **without** touching `B/D4`. If the architect group wants customer authorship: a customer-written package defines the field names SM writes into the MI's config, so a wrong name renders config that prevents the MI starting; and version-range declarations move into customer hands, which is entitlement scope |
| **`UI-D21` vs `D1`** | `D1` is `LOCKED` and explicitly includes "a per-solution **custom-UI escape hatch**". `UI-D21` proposes replacing it with an SM-built named screen type. The File Mesh evidence supports it (§5.4), but this is a **proposed narrowing of a locked decision**, and `architecture.md` §14 plus the phase-2 scope table still assert the escape hatch |
| **`UI-D12` supersedes `SM-D14`** | `SM-D14`'s "never rendered against any schema but the observed version's" is inverted to *declared*, and `BRIEF.md`'s "`VERSION_UNVERIFIED` blocks" is inverted to *does not block*. Recorded as a supersession (§6.1), not a derivation |

Remaining open:

| # | Item |
|---|---|
| 1 | **Graph-builder UX** — deferred by decision |
| 2 | **Renderer components** — blocked on issue `#9`. `project()` is stack-neutral, so this blocks the last mile only |
| 3 | **`in [...]`** — the single provisional grammar extension. Unimplemented until a verified case appears |
| 4 | **`series_pending` cardinality cap** — `SM-D30` mandates a hard per-instance cap. The quarantine table is the least-controlled input and needs its own number and mechanism |
| 5 | **Digest→version mapping** — where image digests are the only observable identifier, the mapping to a semantic version is data SM holds. Who populates it is unspecified |
| 6 | **File Mesh logger prefixes** — whether FM's logger names align with its `COMPONENT_TYPE` decomposition is unverified. If not, per-component log control degrades for FM |
| 7 | **Cache's built `MANIFEST.MF`** — `UI-D12`'s "not observable" premise is verified for `/actuator/info` and the pom, but the packaged jar was not inspected. Settled by `mvn package && unzip -p target/*.jar META-INF/MANIFEST.MF`. If `Implementation-Version` appears, the VM/SSH path gains a corroboration source; declared-first survives regardless |
| 8 | **`architecture.md` reconciliation** — §14 (config-driven UI, escape hatch, render-against-observed) and §10 (`source.kind` primitives) both contradict this document and must be updated or pointed at it |
| 9 | **`action.kind` completeness** — three kinds cover File Mesh's observed buttons by category, but 160 buttons were not individually classified. A miss means a vocabulary addition, i.e. an SM release |

## 10. R1 review audit trail

An independent adversarial review of the first draft (commit `0db4b4d`) returned 18 findings. Applied here:

| Finding | Severity | Where fixed |
|---|---|---|
| Mode-gated 404 claim false — method-level `@ConditionalOnProperty` is inert | CRITICAL | §2, §4.3, §7.3 |
| Every File Mesh figure unreproducible (1,494→1,989; showWhen 88→230; 13 meta keys omitted) | CRITICAL | §2 + `evidence/` script |
| `REQ-001` gate unpassable by its own named fixture — no dynamic options, no actions, no `table` | CRITICAL | **§3.5 (new)**, §3.4, §6.4, §8.1 |
| `UI-D7` never named the config state gating collection | CRITICAL | §4.3, §6.5 |
| `UI-D21` conflicts with `LOCKED` `D1`; "one conflict exists" was false | CRITICAL | §1, §5.4, §9 |
| `UI-D12` inverts `SM-D14` twice without recording a supersession | MAJOR | §6.1, §9 |
| `matchIfMissing = true` inexpressible; `provider` has no initializer | MAJOR | §3.3, §7.3 |
| Series identity lacks a source discriminator; no uniqueness gate; a constraint test cannot catch a merge | MAJOR | §4.2, §6.4 gate 9, §8.3 |
| Gates validate `when:` keys but never values | MAJOR | §6.4 gate 5b |
| `UI-D9` contradicts `RESTART_EXPECTED`, open item 15's `INACTIVE_REPLICA`, and `B/D14` | MAJOR | §5.1 |
| `type: yaml` unvalidated — an `I6` and bind-tier hole | MAJOR | **§3.6 (new)**, §6.4 gate 10, §7.1, §7.4 |
| `source.kind` defined differently here and in `architecture.md` §10; `onUnavailable` omitted | MAJOR | §3.4, §4.1, §9 item 8 |
| `CONNECTOR_DESIGN` mischaracterized as layout | MAJOR | §2, §5.4 |
| `BRIEF.md` contradicts this document with no errata | MAJOR | `BRIEF.md` header |
| "Read the existing value" of `exposure.include` not implementable | MAJOR | §5.2, §5.3 |
| No gap threshold multiplier | MINOR | §4.4, §6.5 |
| "3 enums" (four); File Mesh row counts inside a Cache example; partition count | MINOR | §2, §3.1, §4.2 |
| §8.3 promotion coverage settleable by arithmetic | MINOR | §8.3 |

**Not accepted, with reason:** none. All 18 were accepted; three were found to be *understated* — `D1`'s
escape hatch, open item 15's `INACTIVE_REPLICA`, and `architecture.md:273`'s existing `onUnavailable`
requirement were all already in this repo, verified, and simply not propagated.
