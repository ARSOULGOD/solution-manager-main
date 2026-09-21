# Solution Manager — Architecture & Design

**Last updated:** 2026-08-10
**Status:** Pre-implementation. On-prem-first.
**Companion:** [`DECISIONS.md`](DECISIONS.md) — the decision register. This file records *how the system works*; that file records *what was decided, its status, and why*.

> **Consolidated 2026-08-10** from three predecessor specs (`2026-06-17` parent architecture, `2026-07-08` Subsystem B, `2026-08-04` on-prem-first revision rev 2), which are removed.
>
> ⚠️ **Decisions in the `SM-D*` family are PROPOSED, not ratified.** This document describes the *proposed* on-prem-first architecture. Where it conflicts with a LOCKED decision, `DECISIONS.md` marks the conflict — flag it rather than treating this document as settled.

---

## 1. Purpose

Solution Manager is a console — "the AWS/Azure Console for Solace's custom solutions" — to onboard, configure, deploy, govern and monitor multiple Solace solutions through one platform. Each solution is presented through three categories:

- **Designer** — configure the solution.
- **Governance** — health checks and operational status.
- **Monitoring** — common metrics (CPU, network) plus solution-native metrics.

### Objectives

1. A **unified configuration layer**, so the UI becomes genuinely configurable when onboarding a new solution.
2. Support the existing solutions: **File Mesh**, **Cache**, **DB MI**.
3. **Deployable at the client site or hosted as multi-tenant SaaS**, including a compliance-driven self-hosted control plane.
4. Support **Docker, Kubernetes and bare-Linux** deployments.
5. **RBAC** for access and control.

*(The original objective "the UI must drive deployment of artifacts into client environments" is **proposed for retirement** by SM-D2, which is **PROPOSED and not ratified** — the objective is therefore still formally LOCKED. See the ratification matrix in `DECISIONS.md`. Artifact deployment belongs to the customer's CI/CD; SM owns configuration.)*

---

## 2. The existing landscape

The three solutions are heterogeneous in exactly the dimensions the platform must abstract over. That heterogeneity drives the whole architecture.

| | **Cache Engine** | **File Mesh (MI)** | **DB MI** (future) |
|---|---|---|---|
| Type | Standalone Spring Boot app (fat JAR + Docker) | Micro-integration on a shared connector framework + a Node/Postgres manager | Reuses the File Mesh MI framework |
| Config | `application.yml` / `@ConfigurationProperties` (`CacheEngineProperties`) | Connection objects / flows / uploaded YAMLs in Postgres — **not** the command-center event POJOs | Its own adapter config |
| Telemetry | **PULL** — `/actuator/health`, `/api/cache/stats` (hit/miss/evictions), `/api/cache/size` (separate endpoint), `/actuator/metrics` | **PUSH** — command-center events over Solace; heartbeat at `adapterState.heartBeatTimeStamp` (nested, not root) | Command-center events |
| Actuator | yes | **none at all** | TBD |
| **Build-generated config metadata** | ⚠️ **none** — no `spring-boot-configuration-processor` in `pom.xml`, no `spring-configuration-metadata.json` anywhere | ⚠️ none — but **`FM_CONFIGURATION_TEMPLATE`** in Postgres is a richer, versioned equivalent | TBD |
| Runtime | Java 17, Spring Boot 3.3.5 | Java 17 connectors + Node/TS manager | TBD |

**The consequence that shapes everything:** the two flagship solutions already use *opposite* telemetry models, and one of them exposes no actuator. Any design that assumes a single acquisition path is wrong on arrival. This is why telemetry acquisition is a per-binding policy and the normalized model is transport-agnostic (SM-D4, SM-D23).

### 2.1 The File Mesh manager already exists — and informed the stack

The File Mesh Manager backend lives inside `file-mesh-ui/` (not a separate repo): **Node/Express/TypeScript + PostgreSQL 13 + Docker Compose**, with ~30 tables already modelling much of a control plane (orgs + sub-orgs, users, authorizations, environments, connection objects, brokers, flows, connector binary versions, heartbeat and state-event tables, alerts, git config). Five services: one API plus **four Solace listener services** that consume queues and persist to Postgres — the telemetry-collector pattern, already built.

**The four listeners, and where they go:**

| Listener | What it carries | Altitude | Destination in the new design |
|---|---|---|---|
| `command_center` | adapter **run-level** start/complete/error + run totals | run-outcome | agent's / DP's `solace_event` collector |
| `heartbeat_queue` | liveness (eventType 6) | alive | same |
| `file_complete_events` | **per-file** events + CSV-on-disk + completion email | per-item | same, minus CSV and email coupling |
| `error_listner` | read-only alerting engine: in-memory dedup + SMTP | — | becomes a **cross-cutting platform alerting service** |

File-Mesh-specific topic structure (`org/subOrg/owner/flowType`) is quarantined in a `solutions/file-mesh` module. CSV-on-disk, string-concatenated SQL and email coupling are **not** inherited.

**Backend audit findings** drive the hardening backlog in `DECISIONS.md`: unauthenticated `/fm/cronjob` and `/fm/actuator`, inconsistent role checks, org isolation that trusts a client-supplied `orgName`, string-concatenated SQL, no migration framework, metrics tables with no `TIMESTAMP` index and a manual purge to unencrypted CSV, and multi-*org* rather than multi-*tenant* design.

---

## 3. Architecture overview

Three tiers. On-prem, the control and data planes are co-located in one deployment behind one dashboard, separated structurally rather than physically.

```
   CONTROL PLANE (thin governance)                 DATA PLANE (owns config + data)
   ┌─────────────────────────────┐  authorize +    ┌───────────────────────────────┐
   │ solution catalog             │  validate      │ config-of-record (authoritative)│
   │ schema registry              │───────────────►│ metrics-agnostic ingest          │
   │ entitlement · RBAC           │                │ PostgreSQL, monthly partitions   │
   │ config authoring UI          │◄───────────────│ rollups + retention + archive     │
   │ lifecycle governance         │  telemetry     │ dp-remote actor (SSH/Docker/K8s)  │
   │ (holds NO config copy)       │  + state       │ home for host-local agents        │
   └─────────────────────────────┘                └──────────────┬─────────────────┘
      on-prem: co-located · one deployment · one dashboard        │
      two schemas · two backend modules · two UI API clients      │
                                                                  │
        dp-remote (SSH / Docker API / K8s API) · host-local agent · broker · federated
                                                                  ▼
                                        RUNTIME (VM · Docker · Kubernetes)
                                        MIs — deployed by customer CI/CD; UNMODIFIED
```

- **Control Plane** — governs: catalog, schema registry, entitlement, RBAC, config authoring UI, lifecycle governance. Holds **no business or telemetry data and no copy of config**.
- **Data Plane** — owns configuration-of-record and all business + telemetry data; runs the metrics-agnostic ingest and store; is the actor for `dp-remote`; and is where **`host-local` agents terminate** — generations, report-actual and telemetry all end at the DP. **The CP issues the agent's authorization; the DP validates it** (SM-D24). B/D16 described an agent↔CP session, which puts the channel on the wrong side of the boundary now that the DP owns config and generations. ⚠️ **The wire protocol does not yet exist** — no `.proto`, no state machine.
- **Runtime** — where MIs run. Artifacts deployed by the **customer's CI/CD**; MIs are **never modified**.

### 3.1 Why the planes split this way

Keeping business and telemetry data in the DP means customer data stays resident in the customer's environment — a stronger compliance story than shipping telemetry up to a vendor-operated CP. It also makes the future SaaS split a boundary change rather than a rewrite.

**Separability is achieved structurally, not by deploying twice:**

| Layer | Today (co-located) | What makes the later split cheap |
|---|---|---|
| **Schema** | `sm_control` + `sm_data` in one database | **no cross-schema joins, no cross-schema foreign keys** — *to be* CI-enforced (not implemented). The split becomes a connection-string change, not a table migration. |
| **Backend** | one application, two route trees (`/api/cp/*`, `/api/dp/*`), two modules, two data-access layers bound to their own schema and pool | **CP code never imports DP code or its data-access module, and vice versa** — *to be* enforced by ArchUnit/module rules (not implemented) |
| **UI** | two API clients (`cpClient`, `dpClient`) with independently configurable base URLs; state domains split by plane | a screen may render both planes' data, but **composition happens in a container/hook, never in a leaf component** |

Three seams that are cheap now and expensive later:
1. **Tenant boundary** — `tenant_id` on every table in both schemas, carried even with a single tenant.
2. **Token audience** — once split, the DP must validate a CP-issued token. Build it that way from day one, in one process, or authz breaks at the split.
3. **Interface granularity** — 50 in-process calls composing a screen become 50 *network* calls later. Define coarse/batch interfaces now.

A fourth: **CP↔DP compatibility/interface versioning**, since after the split they become separately upgradable processes.

### 3.2 Deployment topologies and editions

One codebase, two deployment shapes. Agents and data planes behave identically in both.

| | Control-plane location | Tenancy | Identity | Entitlement enforcement |
|---|---|---|---|---|
| **SaaS** | Solace-operated | multi-tenant | Keycloak → tenant IdPs | live DB record; super-admin toggle |
| **Self-hosted / on-prem** | customer environment (incl. air-gapped) | single tenant | Keycloak → the customer's IdP | **Solace-signed entitlement manifest verified locally** — no phone-home |

Self-hosted is the multi-tenant core pinned to one tenant, which is why the tenant boundary is carried even with a single tenant.

**The solution artifact, descriptor and schema are byte-identical across both** (B/D17). Only the **control plane** ships in two editions, chosen at build/packaging time: the SaaS edition enables authoring for Solace; the self-hosted edition disables authoring and verifies a signed manifest. Manifest signing happens in Solace's per-customer licensing step, driven by the CP edition and issuance channel — never by a flag on the solution build. This localizes the entire SaaS/on-prem difference to the CP edition and the entitlement-enforcement path, avoiding per-mode solution builds.

**Air-gap** is supported as a *topology* — the outbound-only `host-local` agent needs no internet path, and entitlement is a locally verified signed manifest. Offline artifact-bundle tooling is not built, and under SM-D2 it is moot: the customer's own pipeline delivers artifacts.

---

## 4. The Target model

A **Deployment Target** is *where* something runs. How a *particular solution* is configured and observed there is a
property of the **pairing**, not of the place — so it lives on an **Instance↔Target binding** (SM-D23).

```
Target  {
  location                                        # cluster / host / site
  driver:        docker | k8s | systemd
  tenant / trust domain
  control_locus: dp-remote | host-local | observe-only
  ownership_scope                                 # who may authorize target.use (D10)
}

Binding { instance, target,                       # one solution ON one target
  infra_telemetry_source: direct-poll | local-collector | broker | federated
  config_mechanism:       additional-location | overwrite-in-place
  render_outputs[]                                # WHICH documents SM renders
  runtime_attachments[]                           # WHERE each lands, per runtime (SM-D21)
                                                  # discovered by per-binding pre-flight
  selectors                                       # how this instance's processes are found
  lifecycle_owner:        sm | customer
  restart_strategy
}
```

**Why the binding, not the Target.** One Kubernetes cluster can host **Cache** (HTTP-polled actuator, one config
path) and **File Mesh** (broker events, a different path, no actuator at all). Putting acquisition and config
mechanism on the Target cannot express that without duplicating the Target *and its credentials*. `ownership_scope`
is required because D10 authorizes `target.use` against an owner.

| Axis | Value | Meaning |
|---|---|---|
| **`control_locus`** | `dp-remote` | the Data Plane reaches into the runtime to write config and run lifecycle — **SSH** (VM) · **Docker API** · **K8s API**. Requires reachability + credentials (SM-D11). |
| | `host-local` | an agent on the host/node executes the same drivers locally. Outbound-only, no stored host credentials. |
| | `observe-only` | SM never writes to this Target at all. Requires a non-config identity binding. |
| **`infra_telemetry_source`** | `direct-poll` | the DP scrapes the workload's actuator/Prometheus endpoint over the network |
| | `local-collector` | our agent scrapes locally and pushes outbound |
| | `broker` | the MI already emits *infra* metrics to Solace and SM subscribes |
| | `federated` | **opt-in** — the customer's own Prometheus/OTel `remote_write`s (SM-D17) |

**The business/broker telemetry channel is not an axis** — it is a separately configured ingest channel (broker URL, durable queue, topic pattern, ACL, payload→identity mapping), always-on **for those solutions that emit to it**. It is not universal: **Cache emits no business metrics to the broker at all.** Durability is a declared per-signal property, not a property of the channel (SM-D27). A Target hosting a broker-emitting MI therefore has two simultaneous inbound paths. Hence the field name `infra_telemetry_source`.

**Shorthand** (documentation only, never schema): "Mode P" = `{dp-remote, direct-poll}`; "Mode C" = `{host-local, local-collector}`. Two combinations worth naming because a single-field model could not express them: `{dp-remote, federated}` serves a footprint-refusing customer on Kubernetes, and `{observe-only, local-collector}` serves a customer who allows a collector but lets SM write nothing.

### 4.1 The honest trade between loci

`dp-remote` is zero-footprint, but because SM also applies config and runs lifecycle it is **not zero-privilege**: the DP must reach into the host, so it holds runtime credentials and needs inbound reachability. `host-local` avoids all of that — the agent acts locally, outbound-only — at the cost of a hosted footprint.

**What each locus costs to build:** Docker and Kubernetes drivers are **locus-agnostic for free** (both speak a network API; only endpoint and credential differ). Only the **VM/systemd driver bifurcates** — local `exec` vs SSH transport. So the incremental cost of `dp-remote` is one SSH transport, the SM-D11 credential/audit subsystem, and a `dp-remote` apply protocol.

**Safety properties are locus-specific and must not be assumed platform-wide.** B/D16's monotonic generations, idempotency keys, authorization lease, active revocation and crash-safe resume are properties of the **agent↔CP mTLS protocol**. They apply to `host-local` and **do not exist for `dp-remote`** unless separately built — which is required v1 scope and currently an open item.

---

## 5. Domain model

**Ownership tree** (RBAC scopes hang here):
`Tenant → Organization → (optional Sub-org) → Environment (DEV/TEST/PROD) → Solution Instance`

**Placement** (decoupled):
`Deployment Target = location + control_locus + driver + telemetry source`

A Solution Instance is **owned by** a tree node and **placed on** a Target. Re-targeting is a rebind; ownership, RBAC and config history are unchanged. This mirrors AWS (account/OU vs region/VPC) and Azure (subscription/RG vs region/AKS).

### 5.1 The Solution Package

Onboarding a solution means **publishing a versioned package, not writing bespoke console code**:

```
Solution Package (per version)
  ├─ config schema      JSON Schema from a declared SOURCE ADAPTER (SM-D20) —
  │                     curated+contract-tested (Cache) | FM_CONFIGURATION_TEMPLATE (File Mesh)
  ├─ render outputs   [ { name, render_source } … ]    # WHICH documents SM renders
  ├─ runtime attachments [ {                   # WHERE each lands in the RUNNING artifact
  │                         path: /app/external/spring/config/application.yml,
  │                         runtime: docker,   # DISCOVERED by per-binding pre-flight
  │                         persistence: bind-mount | volume | configmap | host-dir },
  │                       … ],                  # File Mesh's: UNKNOWN, discovery task
  ├─ artifact identity  [ { digest | checksum -> package version } … ]   # recorded, not fetched
  ├─ external config    { mechanism: additional-location | overwrite-in-place,
  │                       requiredProps: [ management.info.env.enabled,
  │                                        management.endpoints.web.exposure.include,
  │                                        management.metrics.tags.sm_instance_id ] }
  ├─ identity           per-source correlation strategies (§7.1)
  ├─ version detection  { endpoint: /actuator/info, pointer: $.build.version, optional: true }
  ├─ lifecycle verbs    per runtime: start | stop | restart | run-now
  ├─ restart strategy   optional default; absent ⇒ manual
  ├─ config uniformity  mustBeUniformAcrossReplicas: bool (default true)
  ├─ telemetry contract health + metric mappings, metric types, indexable-label caps
  ├─ ui enrichment      labels, grouping, ordering, validation, custom-UI reference
  ├─ artifact coords    INFORMATIONAL only — "deploy cache-engine:2.1"
  └─ signature          signed release/entitlement manifest
```

**Schema source is a declared adapter, not build generation (SM-D20).** D3 assumed the schema is generated from the
solution's build. Cache has **no annotation processor and no generated metadata**, and adding one is `HC1`-forbidden —
so generation has no input. Each solution declares its adapter: a **curated schema verified by a bidirectional
key-set contract test** (Cache — launching the artifact and checking it *accepts* fields proves nothing, because Spring
ignores unknown properties), or **`FM_CONFIGURATION_TEMPLATE`** as a legacy adapter input (File Mesh). See SM-D20 for
the test's required shape.

**Artifact identity is recorded, even though artifacts are not deployed by SM.** These are different things and rev 3
conflated them: SM-D2 removes SM's role in *fetching and deploying by digest* (no registry, no image-pull credentials,
no digest-pinned deploy unit). It does **not** remove the need to **know which immutable artifact is running** — both
the SM-D20 contract test and the SM-D14 version rule depend on it. The package therefore carries an
`artifact_identity[]` mapping **immutable digest (or artifact checksum) → package version**, populated by the operator
or their CI. SM consumes it; SM never fetches with it.

Dropped with SM-D2: **digest-pinned artifact *delivery***, image-pull parameters, and **runtime-requirement pre-flight gating** — SM cannot refuse a deploy it does not perform, so requirements become advisory documentation.

**File Mesh's config does not come from command-center event POJOs** (those are telemetry). Instance *values* live in
`FM_CONNECTION_OBJECTS`, `FM_DATA_FLOW_CONFIGURATION` and `FM_CONNECTOR_CONFIG` — but the **schema and UI metadata**
live in **`FM_CONFIGURATION_TEMPLATE`**, which rev 1 of this document omitted entirely:

```
COMPONENT_TYPE · CONFIGURATION_FIELD · CONFIGURATION_DESCRIPTION
UI_TYPE · UI_TYPE_META · UI_DATA_SOURCE            → the schema-driven renderer contract (§14)
SECTION · SECTION_ORDER · SUB_SECTION · SECTION_FIELD_ORDER   → layout and grouping
MIN_VERSION · MAX_VERSION                          → version-aware rendering (§7.2)
CONFIG_FILE_RELATIVE_PATH · DEFAULT_VALUE · CONFIGURATION_PATH
```

**`UI_TYPE` + `UI_TYPE_META` are a real, proven renderer contract** worth studying. But ⚠️ **`MIN_VERSION`/`MAX_VERSION`
are dead columns**: `fetchTemplate` has **no version predicate** (`file-mesh-services/src/database/queries/index.ts`)
and `IConfigurationTemplate` **omits both fields**. Per **SM-D20** this table is File Mesh's schema source and a
**legacy adapter input — NOT the reference model for the registry**, which must be designed normalized, semver-aware
and version-filtered.

### 5.2 The telemetry contract

A declarative block in the descriptor, driving generic collector primitives so a new solution needs **no collector code**:

```yaml
telemetry:
  health:
    type: http;  path: /actuator/health;  port: 8081;  map: { up: "$.status == 'UP'" }
  metrics:
    # ⚠️ Provider-conditional: CacheProvider.stats() returns null by default and ONLY
    # CaffeineCacheProvider overrides it. With provider: redis|jcache this endpoint 500s
    # and /api/cache/size returns a constant 0 — and `provider` is a field SM itself writes.
    - type: http_json;  path: /api/cache/stats; port: 8081
      when: "config.provider == 'caffeine'"      # applicability predicate over CONFIG
      onUnavailable: degrade                      # mark unavailable; never render a flat zero
      map: { cache.hit_rate: {from: $.hitRate, type: gauge},
             cache.miss_count: {from: $.missCount, type: counter},
             cache.evictions:  {from: $.evictionCount, type: counter} }
    - type: http_json;  path: /api/cache/size;  port: 8081      # separate endpoint
      map: { cache.size: {from: $, type: gauge} }
    # NOTE: Cache has NO /actuator/prometheus — no micrometer-registry-prometheus
    # dependency (solace-cache-engine/pom.xml), exposure is health,info,metrics only
    # (src/main/resources/application.yml). Use the endpoints that exist:
    - type: http_json;  path: /actuator/metrics/jvm.memory.used; port: 8081
      map: { jvm.memory.used: {from: $.measurements[0].value, type: gauge} }
    - type: solace_event; topicPattern: "*/command-center/heartbeat/>"
      map: { heartbeat.ts: $.adapterState.heartBeatTimeStamp }   # nested, not root
```

Primitives: `http_json` · `prometheus` · `solace_event` · `exec` · `jdbc_query`.

⚠️ **This list is not authoritative.** `source.kind` is a closed vocabulary enforced by a load-time gate, and it has
exactly one canonical definition — `contracts/solution-package.schema.json`, specified in
[`dynamic-ui/design.md`](dynamic-ui/design.md) §3.4 (`UI-D14`). Read it there; do not extend it here. `jdbc_query` was
added by `UI-D14`; `exec` is retained.

**Every mapping may declare a `when:` predicate over config values, plus `onUnavailable:`.** An SM-written config value
can invalidate the telemetry contract — switching Cache to Redis through SM's own Designer form turns its KPI graphs
into 500s and a flat zero, and **a flat zero is worse than an error** because §8.3's gap semantics cannot distinguish
"0 entries" from "provider does not report size". `onUnavailable: degrade` marks the metric unavailable rather than
plotting a false value. This is a required contract-test case: *an SM-written config change must not silently break a
declared metric.* **`type` (counter / gauge / histogram) is required per metric** — the storage layer branches on it (§8.3). Every mapping requires a **contract test against the real artifact**. A genuinely new *protocol* is the only case needing a new primitive.

---

## 6. Configuration ownership

SM owns configuration; the customer's CI/CD owns the artifact. Two mechanisms:

| Mechanism | How | Launch-spec change | Survives CI/CD redeploy | Conflict handling |
|---|---|---|---|---|
| **`additional-location`** *(preferred)* | MI launched with `SPRING_CONFIG_ADDITIONAL_LOCATION` at a path SM owns | one-time (env var + mount) — **deployment configuration, not development** | ✅ VM/Docker: CI/CD never touches that path · ⚠️ K8s: the path is a **ConfigMap**, a CI/CD-owned object (§6.2) | required on K8s only |
| **`overwrite-in-place`** | SM writes the file the MI already reads | **none** | ❌ CI/CD may template or clobber it | **required** — CI/CD is a competing writer |

Applying a change = **write the file + restart**, executed locally by the agent or remotely by the DP. Hot reload exists only where an app already supports `/actuator/refresh` + `@RefreshScope`; the no-development default is restart.

### 6.1 SM owns one property source, not the effective config

**This is the trap most likely to produce silent failure.** Spring Boot resolves properties across an 11-deep stack, ordered lowest → highest: **(3) config data files … (5) OS environment variables … (6) Java system properties … (10) `SPRING_APPLICATION_JSON` … (11) command-line arguments** — and command-line properties always beat file-based sources.

SM's file therefore sits near the **bottom**. Consequences:

- Any `SERVER_PORT`, `SPRING_DATASOURCE_URL`, `-D…` or `--prop=…` already in the customer's launch **silently and permanently overrides SM**.
- `spring.config.name` / `spring.config.location` / `spring.config.additional-location` **must themselves be set as environment properties**, and the command line beats the env var. A customer launch script carrying its own `--spring.config.additional-location=/their/path` means **SM's directory never loads at all** — while a naive file-hash check reports "in sync."
- If the customer already uses `SPRING_CONFIG_ADDITIONAL_LOCATION`, SM setting it **replaces** theirs and breaks their app. `spring.config.location` (non-additional) replaces the defaults outright.

**Required responses:**
1. **Launch-spec pre-flight at enrollment** — inspect the actual launch command (`ENTRYPOINT`/`CMD`, unit file, pod
   spec) and environment for competing sources and for a pre-existing `spring.config.location` /
   `additional-location`. **Refuse to enroll** until reconciled; append to an existing additional-location list rather
   than replacing it. **SM does not choose the path — it discovers and honours the artifact's own.** Cache's image
   already passes `--spring.config.additional-location=/app/external/spring/config/` on the command line, which
   outranks any environment variable SM could set, so SM writes *there* (SM-D21).

   ⚠️ **The launch-spec change for Cache is NOT zero** — rev 2 claimed it was, and that was wrong. The image only does
   `RUN mkdir -p /app/external/spring/config` (`solace-cache-engine/Dockerfile:6`); it declares **no `VOLUME`**, so the
   directory lives in the container layer and **anything SM writes there is destroyed when the container is replaced**.
   A **persistent attachment is required per runtime**, declared in the package:

   | Runtime | Attachment SM requires |
   |---|---|
   | Docker | a bind mount or named volume at the declared path |
   | Kubernetes | the generation-named ConfigMap mounted at the declared path (§6.2) |
   | systemd | a real host directory, with the unit's config dir declared |

   **Pre-flight runs per binding, before every apply — not once at Target enrollment.** The launch command, the mount
   and the competing-source set can all change between applies. A one-time enrollment check certifies a state that no
   longer holds.
#### The five config states — do not collapse them

Rev 2 acknowledged that effective config is often unobservable and then kept using an "effective-config hash" to clear
restart states. Those are different claims and need different names:

| State | Means | How it is established |
|---|---|---|
| **desired** | what the operator authored | **DP record** — the CP *authorizes and validates*, the DP *commits and versions*. The CP holds no config copy (SM-D1). |
| **rendered** | the file content SM produced from desired + schema | hash of SM's render |
| **persisted** | that content is on the target at the declared path | read the file back |
| **restart-observed** | the process restarted after persist | new process start time / uptime reset |
| **effective-verified** | the JVM actually resolved *these* values | **only** via a protected `configprops`-class endpoint, non-secret allowlist |

**`restart-observed` is the strongest state most solutions will ever reach, and it does not prove convergence** — an
environment variable or command-line property can still be overriding the file, invisibly and permanently.
**Cache reaches `restart-observed` and no further**: it exposes `health,info,metrics` only
(`solace-cache-engine/src/main/resources/application.yml:10`), so it is permanently **`effective-unverified`**.

Everywhere downstream — `RESTART_EXPECTED` closing, `PENDING_RESTART` clearing, `CONFIG_DIVERGED` remediation — the
gate is **`restart-observed`**, with `effective-verified` shown as an extra badge where available. Never present
`restart-observed` to the user as proof of convergence.

2. **Verification is bounded — `configprops` hashing does NOT work (SM-D22).** Sanitized (the default) renders values
   as `******`, so a changed password or cache size hashes identically to the old one. Unsanitized would expose
   Cache's broker credentials on an unauthenticated actuator, breaching invariant `I6`. Adding a protected management
   path is MI development, forbidden by `HC1`. So: hash **the file SM wrote** (proves the write landed), rely on the
   launch-source pre-flight above for competing sources, verify **effective** values only for a declared
   **non-secret allowlist** where a protected actuator exists, and mark everything else **`effective-unverified`** in
   the UI. Never read unsanitized `configprops`.
3. **Compute the `management.*` union against *declared* values — "merge" alone is unimplementable.** SM cannot read
   the artifact's shipped `management.*` block: it lives in the jar's classpath `application.yml`, and under
   `dp-remote` SM has no way to read inside the image. Writing `include: health,info` to enable correlation would
   **silently un-expose `/actuator/metrics`** and kill the very KPIs the telemetry contract declares — while the file
   hash reports "in sync" and all five config states report success.
   **Therefore:** the curated schema (SM-D20) carries the artifact's **shipped `management.*` values as declared
   facts** — they *are* knowable, from the artifact's repo — and SM writes the **union** of those with what it needs.
   `management.*` is a **second, separately-sourced key space** in SM-D20's bidirectional key-set test, because
   `CacheEngineProperties` is `@ConfigurationProperties(prefix = "cache-engine")` and a test over that type graph never
   sees a `management.*` key.

### 6.2 Kubernetes: GitOps is a competing writer

On Kubernetes the SM-owned path is a **mounted ConfigMap** — an API object in the manifest set the customer's pipeline owns. **Argo CD or Flux with self-heal will revert both SM's ConfigMap patch and its rollout-triggering annotation**, possibly mid-rollout, leaving replicas split across config versions.

**`ignoreDifferences` alone is NOT sufficient** — verified against Argo CD documentation: it affects only diff
computation, and *"during the sync stage, the desired state is applied as-is."* `RespectIgnoreDifferences=true` is
required, **and even then applies only when the resource already exists**, so first creation still overwrites.

**The mechanism is immutability, not patching (SM-D25).** Rather than patching a mutable ConfigMap:

1. Write a **new generation-named immutable ConfigMap** per config version (`<instance>-cfg-<gen>`).
2. Update the **workload's reference** to it — which is what triggers the rollout, no annotation hack needed.
3. Retain the last N for **deterministic rollback** — something a mutable ConfigMap cannot provide.

This also sidesteps asynchronous mount propagation entirely. **Writer ownership is declared per resource, per binding — not per Target (SM-D25).** A Target hosts several bindings
which may be governed differently: SM may own the ConfigMap for one solution while GitOps owns everything for another.
The declaration is `{ binding, resource_kind, writer: sm | gitops }`, and SM refuses to write any resource it is not the
declared writer of. Where SM is the writer, the customer's reconciler must exclude **both** the ConfigMap **and** the
workload's config reference — `ignoreDifferences` alone does not prevent overwrite on sync, and
`RespectIgnoreDifferences` is ineffective on first creation. **ConfigMap GC:** retain the last N generations for
rollback, delete oldest-first, never delete one still referenced by a live or paused workload.

⚠️ **Customer acceptance of SM-as-writer versus GitOps-PR-integration is unresolved, and Kubernetes is v1 scope — so
this blocks baseline ratification, not just implementation.**

Remaining K8s constraints: ConfigMaps cap at ~1 MiB; **`subPath` mounts and `envFrom` never receive updates** and are
**unsupported** for SM-managed config; rollout semantics (`maxUnavailable`/`maxSurge`, readiness probes) hold for
**Deployments but not StatefulSets** — test each separately.

### 6.3 Drift

Config survives redeploys under `additional-location` on VM/Docker, and the MI re-reads it on restart. Hand-edits are drift — detected by comparing the **persisted file** against the rendered content, plus effective values only where `effective-verified` is achievable — and are **alert-only with manual UI reconcile** (re-apply SM's version, or adopt actual as a new version). **Never silently auto-revert a human.** Under `overwrite-in-place`, and under `additional-location` on GitOps-managed Kubernetes, the same policy applies but fires far more often.

Existence/health drift (crashed, killed, vanished) **auto-reconciles** — restarting what should be running is the supervisor's job. Only config/spec drift is human-owned. This split is what avoids the 2 a.m. incident where the platform silently reverts an operator's break-glass fix.

---

## 7. Instance authority & version states

SM manages only what an operator **declared**. Entitlement is checked at declaration. Undeclared processes are not discovered, adopted, monitored or listed.

**Why declared-only:** SM must not author config for something it cannot validate, and an auto-created instance cannot have its owning Org/Sub-org inferred (a Target yields tenant and environment, but not org). Discovery-first would move registration friction to *after* the instance is running unplaced, while adding an unsafe config-authoring path.

Because there is no discovery, `dp-remote` needs no separate discovery mechanism: the **declared inventory is the scrape target list**, served as `http_sd`. Cloud SD, Consul, DNS, the Docker socket and the K8s API are **optional import helpers** that pre-fill or refresh that inventory. One inventory serves three purposes — scrape targets, remote-exec target list, correlation set — so a host is entered once.

**A declared instance is *logical*;** the processes carrying its identity are its **replicas**. Since scaled-out replicas inherit the same config file/ConfigMap, they inherit the identity, so replica scale-out is tracked automatically.

### 7.1 Correlation is a per-source strategy

There is no single correlation primitive. Critically, **Spring Boot 3.x defaults defeat the obvious approach**: `management.info.env.enabled` defaults to `false`, `management.endpoints.web.exposure.include` defaults to `health` only, and when the `env` contributor *is* enabled it surfaces **every** `info.*` property from every source — so `info.*` is not a private channel and must never hold anything sensitive.

| Source | Strategy |
|---|---|
| `direct-poll` | `info.sm.instanceId` via `/actuator/info` — **requires SM to also write** `management.info.env.enabled: true` and `management.endpoints.web.exposure.include` (merged, not replaced) |
| `local-collector` | agent knows its own host + declared desired state; no in-band identity needed |
| `federated` | **Micrometer common tag** `management.metrics.tags.sm_instance_id` — `/actuator/prometheus` does *not* emit `info.*` as labels. Fallback: operator-configured label mapping |
| `broker` | **payload/topic pointer** declared in the contract — File Mesh identifies itself by its own topic structure and `adapterState`, and will never carry SM's id |
| `observe-only` Target | **non-config identity binding** — declared address, K8s label selector, or broker topic (SM writes nothing, so it cannot inject an id) |

All of these are properties SM writes or config SM holds, so HC1 is satisfied.

**Bootstrap ordering:** before SM's first apply-and-restart, actuator exposure is at its default — so a newly declared instance is **unobservable until after that first restart**, which defaults to `manual`. The sequence is declare → apply → restart → observable, and the UI must say so rather than showing an unexplained `MISSING`.

### 7.2 Version states

| Observed vs declared | Package published for observed? | State | Config screen |
|---|---|---|---|
| match | — | `OK` | editable |
| differs | ✅ | `VERSION_DRIFT` | **read-only** until reconciled |
| differs | ❌ | `VERSION_UNKNOWN` | **blocked, with stated reason**; monitoring continues |
| declared, not found | — | see the split below | — |

**Invariant: a config form is never rendered against any schema but the observed version's.** This preserves strict-validate-at-CP + tolerant-reader-at-app without SM controlling the artifact.

**Correction paths.** `VERSION_DRIFT`: *accept the runtime* — update the declaration to the observed version, re-rendering config against **its** schema with a **field-level diff** (added/defaulted · removed · type-or-constraint changed · renamed-needs-mapping); or *restore the runtime* — SM instructs the operator to deploy the declared version through their pipeline, then re-checks. `VERSION_UNKNOWN`: *publish* the observed version's package → becomes `VERSION_DRIFT`; or *roll back*; or *mark monitor-only*, accepting that SM won't manage that instance's config while telemetry continues. **The field-level diff view is the load-bearing UX** for all of this — and it must **mask secret values**.

**"Not found" is four states, not one.** A single `MISSING` would fire a false alert on every config change, because the default `manual` restart means the operator restarts the instance themselves — and under `direct-poll` that restart window *is* "declared, not found." `direct-poll` also cannot distinguish instance-absent from network-unreachable, actuator-not-exposed, port-changed or host-down.

| State | Meaning | Alerts? |
|---|---|---|
| `RESTART_EXPECTED` | suppression window opened automatically by `PENDING_RESTART`, closed by **`restart-observed`** (new process start time) — *not* an effective-config hash, which most solutions cannot produce; bounded, then escalates | no |
| `UNREACHABLE` | transport-level failure — host/network scope, not instance scope; fed by ongoing reachability monitoring | yes, as a **Target/host** alert |
| `NOT_EXPOSED` | transport reachable, actuator absent or not exposed — the §7.1 bootstrap case | yes, as a **configuration** finding |
| `MISSING` | transport reachable, actuator exposed, instance genuinely absent beyond N intervals | yes, as an **instance** alert |

**Version detection is locus-dependent — a genuine capability difference.** `host-local`: the agent can read *identity* from the runtime (image digest, JAR path, `ExecStart`, pod spec) — but
**identity is not a version**, and rev 2 wrongly called this reliable. **Cache renames its artifact to a fixed
`solace-cache-engine.jar`** (`solace-cache-engine/Dockerfile:10`), so the filename carries no version, and image *tags*
are mutable. The only trustworthy runtime identity is an **immutable digest**, so the platform requires an
**operator- or CI-declared mapping from digest (or artifact checksum) to a published package version**. A matching
digest yields an **observed** version; anything else is **`VERSION_UNVERIFIED`** and the config screen is blocked
exactly as for `VERSION_UNKNOWN`. SM never infers a version from a mutable tag or a fixed filename. `dp-remote`: depends on `/actuator/info` exposing `$.build.version`, which needs Spring Boot's `build-info` goal — **development work HC1 forbids requiring**. Where absent, SM trusts the declaration and marks it **"unverified"**: no drift state, correction is manual at upgrade time. A secondary `dp-remote` fallback is reading the JAR filename or image tag over the same channel used for apply.

---

## 8. Storage

### 8.0 Platform prerequisite: PostgreSQL ≥ 14

**Pinned.** `ALTER TABLE … DETACH PARTITION … CONCURRENTLY` — which §8.5 depends on to archive without an
`ACCESS EXCLUSIVE` stall — was **introduced in PostgreSQL 14** (verified against the 14.0 release notes). The
reference File Mesh deployment runs **`postgres:13`** (`file-mesh-ui/docker-compose.yml:7`), so rev 1 mandated a
mechanism unavailable on the version actually in use. PostgreSQL **≥ 14** is a platform prerequisite, checked at
install.

### 8.1 Why plain PostgreSQL

**One datastore, for operational reasons.** On-prem, every additional stateful component is something the customer must install, back up, monitor, patch and upgrade. That argument is decisive and applies equally to **VictoriaMetrics** — which is Apache-2.0 and would bring compression, native retention and downsampling at **no licensing cost**. It is deferred on operational grounds, not licensing grounds.

**TimescaleDB carries an additional, specific licensing cost.** It is split-licensed: `src/` is Apache-2 (hypertables, chunking, partitioning) while `tsl/` is under the **Timescale License** and contains exactly **continuous aggregates, compression, and the time-series query-planner optimizations** — i.e. the three features worth adopting. The TSL's "Value Added Products or Services" exception permits shipping inside a self-hosted product on three conditions; the third is the blocker: end users must be prevented, *"either contractually or technically,"* from modifying the database schema. On-prem the customer has superuser on their own host, so "technically" is impossible and it must be **contractual** — a schema-modification prohibition in the customer agreement, TSL pass-through notice, and legal review. Stripping the TSL features leaves only hypertables, which is close to what declarative partitioning already provides. *(An unexamined branch: both conditions gate **distribution**, and a customer installing the extension into their own database may not make Solace a distributor at all. Routed to legal, not resolved.)*

The cost of plain PostgreSQL is **no columnar compression**, which is why the sizing model is mandatory.

### 8.2 Schema

```
-- THE SCHEMA IS NO LONGER DEFINED HERE.
-- Source of truth: backend/src/main/resources/db/migration/V001__telemetry_schema.sql
-- Constraint tests:  backend/src/test/sql/V001_constraints_test.sql
-- Verified on PostgreSQL 16: migration applies; 4 positive + 7 negative assertions pass.
--
-- Shape (see the migration for exact DDL):
--   placement        immutable, identity-bearing: (instance, target). Retired, never mutated.
--   binding_policy   MUTABLE, versioned: telemetry source, config mechanism, restart strategy,
--                    lifecycle owner, selectors. NOT part of telemetry identity.
--   series           identity = tenant + instance + PLACEMENT + metric + package_version
--                             + source_key + labels_fingerprint
--   metrics_raw      PK (tenant_id, series_id, ts)          -- ts is the partition key
--   metrics_rollup_* PK (tenant_id, series_id, bucket_start)
--   events_raw       PK (tenant_id, ts, source_namespace, source_event_id)
--   No DEFAULT partition anywhere (it makes DETACH ... CONCURRENTLY illegal).
```

**Three constraints that the first correction attempt got wrong**, and which any migration will refuse to create if
they are wrong again:

1. **A UNIQUE/PRIMARY KEY on a partitioned table must include every partition-key column.** Both fact tables are
   RANGE-partitioned on `ts`, so `ts` is in both primary keys. The earlier `PRIMARY KEY (tenant_id, source_event_id)`
   on a partitioned `events_raw` **cannot be created at all** — and §8.2's own indexing note already stated the rule,
   which is what makes it an inexcusable slip rather than an obscure one.
2. **A foreign key needs a unique constraint on the referenced columns.** `series` therefore has
   `PRIMARY KEY (tenant_id, series_id)`, not a bare `series_id`.
3. **Event de-duplication is time-local, not global.** `PRIMARY KEY (tenant_id, ts, source_event_id)` suppresses a
   redelivery that carries the *same* timestamp — which broker redelivery does. It does **not** catch the same logical
   event re-published with a different timestamp; that needs a separate bounded de-duplication ledger keyed on
   `source_event_id` alone, which is deliberately **not** in the partitioned fact table because a global unique index
   across partitions is impossible. Whether that ledger is needed is an open question for the backend sub-spec.

**Placement identity is immutable and lives in series identity (SM-D26).** `binding_id` is part of the series
uniqueness constraint, and a binding is never mutated — so a fact can never be reattributed to a placement that did not
produce it. Rev 3 tried to achieve this with a mutable binding plus an epoch column on the fact; that left the raw and
rollup keys able to collide. Immutability is the simpler and sounder mechanism.

#### Placement is immutable; policy is not — that split is what makes identity sound

⚠️ **Round 5 correction: "the binding is immutable" was too broad.** A binding also carries **mutable policy** —
`restart_strategy`, `lifecycle_owner`, `selectors`, attachments. Taken literally, an operator flipping `manual` →
`rolling-health-gated` in the UI would have minted a new identity, **ended every existing graph, and broken REQ-007's
six months of history on a UI toggle.** The record is therefore split:

| Record | Mutability | In telemetry identity? |
|---|---|---|
| **`placement`** — `(instance, target)` | **immutable**; re-targeting creates a new one and retires the old | **yes** |
| **`binding_policy`** — telemetry source, config mechanism, restart strategy, lifecycle owner, selectors | **mutable, versioned** | **no** |

Re-targeting creates a new `placement_id`, and `placement_id` is part of series identity — so old-placement data keeps
its own series and can never be overwritten by, or blended into, the new placement's. **`placement_epoch` is deleted**;
it existed only to compensate for a mutable binding.

Because a rebind yields a new `binding_id`, and `binding_id` is part of series identity, **a rebind yields new series**.
Old-placement data keeps its old series and can never collide with or be blended into the new placement's. **`placement_epoch`
is therefore deleted** — it existed only to compensate for mutable bindings. Removing the mutability removes the whole
defect class rather than adding a column to the key.

`D11`'s "re-targeting is a rebind; config history unchanged" still holds at the *Instance* level: config history follows
the Instance, telemetry follows the binding.

**Series identity includes `binding_id` *and* `source_key` — both are required.**

- `source_key` discriminates **replicas of one instance on one Target** (pod name, host, container id — declared per
  solution). Without it, replicas collapse into one series and their samples collide on `(tenant_id, series_id, ts)`.
- `binding_id` discriminates **the same instance on different Targets.** SM-D23 permits an Instance to be bound to
  several Targets (multi-site). The obvious `source_key` values are **not globally unique** — a pod named
  `cache-0` exists in every cluster, and a host named `app01` in every site. Without `binding_id` in series identity,
  **two sites silently merge into one series and overwrite each other at identical scrape timestamps.** This was a
  defect introduced by the multi-site cardinality, not an original one.
- **`source_key` is the *stable* replica identity, not the ephemeral incarnation.** A pod UID or container ID changes on
  every restart and would create a new series per restart, fragmenting history. Use the stable ordinal or hostname
  (StatefulSet ordinal, DaemonSet node, systemd host); put the ephemeral incarnation in `labels_jsonb`, where it is
  queryable but **not part of identity**.
- **Rollups carry the same identity as raw** — `(tenant_id, series_id, bucket_start)`. Since `series_id` encodes
  instance, binding and source, no rollup can blend two placements.

⚠️ **Multi-target placement proposes superseding locked `D11`**, which states an instance is placed on *a* Deployment
Target — singular. Recorded in the ratification matrix; **the locked singular model stands until ratified.**

#### Event identity — `source_event_id` is not always available

`events_raw` requires a stable per-event identifier for de-duplication, and **File Mesh cannot supply one from its
payload**: the command-center `Header` carries `timestamp, adapterType, channel, adapterVersion, adapterID, runID,
host, user, ip, eventType, adapterStartTime` — **no event or message ID**
(`pubsubplus-connector-file-common/.../commandcenter/events/common/Header.java`). Resolution, in preference order:

1. **Broker message identity, namespaced.** A Solace replication-group message ID (RGMID) is unique **only within a
   given endpoint and replication group** — not globally. So event identity is
   `(binding_id, source_namespace, source_event_id)` where `source_namespace` = broker + replication-group + endpoint.
   Rev 3 used the bare message ID and would have collided across endpoints. Requires a durable endpoint — the same
   precondition `guaranteed-delivery-verified` needs (§10).
2. **A contract-declared composite key** where it does not — e.g. `adapterID + runID + eventType + timestamp`
   (+ a per-item discriminator such as filename for per-file events). Declared per solution in the telemetry contract,
   never inferred.
3. **Ingest-assigned surrogate** as a last resort, which means **de-duplication is not possible** for that signal and
   the signal is marked accordingly.

A `DIRECT` (non-persistent) signal generally lands in case 2 or 3, which is another reason durability and event
identity are the same conversation.

**`tenant_id` is in the fact row, not only the dimension** — the seam in §3.1 requires it, and a per-partition archive
is otherwise inherently multi-tenant. The composite FK means a fact can never reference another tenant's series.

**Normalizing series identity into a dimension table and carrying an integer `series_id` on the fact row is the single largest byte saving available in plain PostgreSQL** — an EAV shape storing `metric_name` as text plus a per-row JSONB label map on every sample is the worst case for both size and index selectivity.

Numeric series go in `value_numeric`; arbitrary/business payloads in `payload_jsonb`. The declared metric contract drives the mapping, so onboarding a solution needs **no new tables and no new application code**. Units are normalized to canonical form at ingest, so no query-time unit maths. The dedupe key `(series_id, ts)` is what makes `ON CONFLICT` upserts legal; note a unique index on a partitioned table must include the partition key, which `ts` is.

**Sizing model (mandatory before ratifying the retention window):**

```
rows      = instances × series_per_instance × (retention_seconds / scrape_interval)
bytes/row ≈ 24 (tuple header + alignment) + 8 (series_id) + 8 (ts) + 8 (value)
            + index overhead ≈ 40–50   →  plan at ~100 bytes/row all-in
```

Worked example — **100 instances × 100 series × 15 s × 6 months** ≈ 10.5 × 10⁹ rows ≈ **~1 TB** normalized (**~3 TB or worse** un-normalized). Every deployment declares a **raw-tier ceiling**; on exceeding it, the options in preference order are: lengthen the scrape interval · reduce series per instance via label caps · shorten raw retention · move the metrics tier to a metrics-native backend behind the ingest interface.

**Indexing:** the fact table carries `(series_id, ts)` plus `(ts)` for rollup and retention jobs; series-scoped queries resolve `series_id` from the dimension first. Declarative partitioning propagates parent indexes to each partition automatically. **Label filtering is served by indexing the `series` dimension** (GIN on `labels_jsonb`, or generated columns + expression indexes for declared indexed dimensions), then joining on `series_id` — one row per series, not per sample.

**Future index changes need care:** `CREATE INDEX … CONCURRENTLY` is **not supported on a partitioned table**. Adding one later under load requires `CREATE INDEX ON ONLY parent` (created invalid) → per-partition `CREATE INDEX CONCURRENTLY` → `ALTER INDEX … ATTACH PARTITION`. A metrics table's index set always changes eventually.

### 8.3 Tiers, rollups and retention

| Tier | Grain | Retention | Notes |
|---|---|---|---|
| Raw | scrape interval | **6 months — enforced capacity prerequisite** | monthly declarative partitions |
| Hourly rollup | 1 h | 2 years | ~1% of raw; **partitioned monthly** |
| Daily rollup | 1 d | indefinite | negligible; **partitioned yearly** |
| Archive | — | policy-driven | encrypted CSV.gz, out-of-band |

**Six months is an enforced prerequisite, not a soft target (SM-D26).** Rev 1 described it simultaneously as a
minimum, as reducible, and as "not guaranteed" — incoherent. If the benchmarked sizing model does not support six
months of raw at the deployment's ingest rate, **the deployment is not conformant** and the shortfall is declared to
the customer, not silently absorbed.

**"6 months" is a minimum, not a maximum.** A monthly partition can only be dropped once every row in it exceeds the retention age, so steady state holds **6–7 months on disk**. The requirement is recorded as *"at least 6 months, dropped at month granularity."* Weekly partitions would bound the overshoot to 7 days and are the drop-in change if it is ever reinterpreted as a compliance *maximum*.

**Rollups are plain tables with incremental upsert — never `MATERIALIZED VIEW`.** `REFRESH MATERIALIZED VIEW` recomputes all history every time: O(everything), degrading forever. Incremental upsert over one closed bucket is O(one bucket), so **rollup cost is constant regardless of retained history**. Daily rolls up **from hourly, not raw** — 24 input rows per series instead of thousands.

**Rollups are metric-type-aware.** Columns: `count, sum, min, max, first, last, first_ts, last_ts, delta, reset_count`.

| `metric_type` | Rollup semantics | Upward re-aggregation |
|---|---|---|
| **`gauge`** | `avg = sum/count`; min/max as stored | `sum(sum)/sum(count)`, `min(min)`, `max(max)` — **never `avg(avg)`**, which is wrong whenever bucket sample counts differ |
| **`counter`** | **materialize `delta` at rollup time**: within-bucket `last − first`, plus carry-over `first − prev.last`; **reset detection** — a sample lower than its predecessor is a JVM restart, so the new value contributes as-is and `reset_count` increments. ⚠️ **The carry-over makes bucket N depend on N−1, so correction must cascade** (below). | **`sum(delta)`** — a plain sum, which is why the delta is materialized rather than derived at query time. `rate = delta / bucket_seconds` |
| **`histogram`/summary** | **unsupported in v1** — quantiles cannot be averaged. Only `_count` and `_sum` are ingested, as counters | n/a |

Type-awareness is not optional: **Cache's actual KPIs are counters** (`missCount`, `evictionCount`, `http_server_requests_seconds_count`, GC counts), and `avg`/`sum` over a monotonic counter is meaningless. The failure mode is insidious — a graph is *correct* at the raw tier and silently becomes nonsense the moment the range crosses into the hourly tier.

`last` is not a plain SQL aggregate: within a bucket `(array_agg(value ORDER BY ts DESC))[1]` works inside one `GROUP BY`, but re-aggregating upward needs ordering by the stored `last_ts`.

**Gaps:** carry `count` **and an expected-sample-count** (derived from the declared scrape interval) to the UI, so a low-coverage point renders as a gap rather than a confident line. **This applies to the raw tier too** — otherwise gaps are invisible in exactly the forced-Raw view operators use during incidents.

**Cardinality:** rollup rows = series × buckets. The contract **caps which label keys may participate in series identity**; exceeding the cap is an ingest rejection with a counted metric, not silent series explosion. **Rollups apply only to `value_numeric`** — JSONB business payloads cannot be averaged and are raw-only with their own retention.

**Late-arriving data** is designed for, and it constrains partitioning. Agents backfill after reconnect, so raw rows can land after their bucket was rolled up *and* after their partition was dropped — and PostgreSQL **errors** on an insert mapping to no existing partition. The obvious remedy (a `DEFAULT` partition) is **illegal alongside `DETACH PARTITION … CONCURRENTLY`**. Resolution:

- **No `DEFAULT` partition, ever.**
- **Pre-create partitions N months ahead** with matching `CHECK` constraints.
- **Backfill is bounded to a declared window** strictly inside raw retention; older samples are **rejected and counted** as an ingest metric, never silently dropped.
- **Rollup correction cascades (SM-D26).** A watermark of *touched* buckets is **not sufficient for counters**: a late
  sample changes bucket N's `last`, invalidating **N+1's carry-over** and every upper-tier bucket containing either.
  Correction must invalidate **the touched bucket, its successor, and all affected upper tiers**. Idempotent upsert
  makes re-running safe; it does not make a stale successor correct.
  *Recorded trade:* defining `delta` as the sum of **within-bucket positive increments only** removes the cascade, at
  the cost of losing the increment between the last sample of N−1 and the first of N. The cascade is chosen so no
  counted event is dropped.
  **Required tests:** multiple resets in one bucket · a reset on a bucket boundary · gaps · out-of-order arrival · a
  late sample landing in an already-rolled-up bucket.
- **Archived ranges are immutable** — guaranteed by the bound above.

### 8.4 Query tier routing

Driven by the fact that a graph is ~600–1200 pixels wide and cannot display more than ~1000 points:

| Requested range | Tier read |
|---|---|
| ≤ 7 days | **raw**, with on-the-fly `date_trunc` bucketing — cheap over a small range, so **no stored 5-minute tier is needed** |
| 7–90 days | hourly rollup |
| > 90 days | daily rollup |
| any, **Raw forced** | raw |

A **resolution control** (`Auto · Raw · Hourly · Daily`) lets an operator force full fidelity during an incident. **Raw stays fully queryable and exportable for the entire window** — rollups are an index over raw, never a replacement. Raw export is a streaming server-side `COPY … TO STDOUT` piped to the client, never an in-memory API response.

### 8.5 Archive before drop

The requirement is sound; File Mesh's implementation of it is the anti-pattern (an unauthenticated cron endpoint purging to unencrypted CSV on local disk).

```
1. ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY    -- see lock caveat
2. COPY (SELECT * FROM <partition>) TO STDOUT (FORMAT csv)  -- streamed, gzipped, encrypted
3. record manifest { tenant, range, row_count, sha256, destination, ts }
4. VERIFY: re-read + checksum match + row-count match
5. DROP TABLE <partition>                -- only after step 4 passes
```

`COPY … TO STDOUT` streams over the wire protocol into SM's own job, needing **no superuser and no server-side file write** — unlike `COPY TO PROGRAM`, which needs both. JSONB exports as text. Detaching before archiving means a failed archive never loses data.

**Locking caveat:** plain `DETACH PARTITION` acquires **`ACCESS EXCLUSIVE` on the whole partitioned table**, blocking every reader and writer — a production stall on a continuously ingesting table, not an instant operation. `CONCURRENTLY` reduces this but: cannot run in a transaction block · waits for all other transactions using the table (an ingest-heavy table may never quiesce, so bound the wait and retry) · still takes `ACCESS EXCLUSIVE` on the partition itself · allows **at most one pending detach per table**, so retention must serialize · needs **`DETACH … FINALIZE`** recovery after an interruption, detected on job startup · and is **not allowed with a `DEFAULT` partition**. A maintenance-window fallback using the plain form is documented for deployments that cannot quiesce.

**Archives are produced and verified per tenant, not per partition (SM-D26).** A monthly partition spans every
tenant, so copying it wholesale contradicts the tenant-partitioned claim. Step 2 selects `WHERE tenant_id = $1`, and
step 3 writes **one manifest per tenant and range**, each verified independently before step 5 drops the partition.

**Non-negotiables:** driven by the authenticated internal scheduler, never an unauthenticated HTTP endpoint · **encrypted at rest** · access-controlled destination · **tenant-partitioned** so a restore cannot cross the plane boundary · SHA-256 in the manifest · **verify before drop** · a **tested restore path** · a retention policy on the archives themselves · archives are DP-resident and **never travel to the CP**. Destination is configurable: encrypted local volume or NFS as the on-prem default, S3-compatible object store optionally — on-prem cannot assume object storage exists.

---

## 9. Restart & apply

Restart behaviour depends on properties SM cannot know and cannot require anyone to declare: replica independence, HA model, warm-up duration, whether standby promotion is needed. **SM must not encode a restart strategy it cannot verify** — guessing wrong takes down production.

**Default is `manual`:** SM writes the config file and does nothing else. The instance enters `PENDING_RESTART` — *"Configuration staged · restart required · N replicas pending."* The operator restarts through their own process; SM detects **`restart-observed`** (process start time changed) and clears the flag — which does **not** claim the config is effective; see the five states in §6.1. This needs **zero knowledge of any MI's HA model** and is safe by construction.

Everything above the default is **opt-in and operator-asserted**, offered from the UI:

| Strategy | Behaviour | Requires |
|---|---|---|
| `manual` | **default** — stage, notify, wait | nothing |
| `rolling-health-gated` | one replica at a time; wait for healthy; halt on failure | a declared health mapping **and** the operator's assertion that replicas are independently restartable |
| `rolling-no-validation` | one at a time with a fixed delay | nothing — the path for MIs with **no health endpoint at all** (File Mesh MI exposes no actuator) |
| `restart-all` | simultaneous; fastest; **full outage** | operator acknowledgement |
| `scheduled` | any of the above at a chosen window | — |

**SM offers only strategies the solution's declared capabilities support** — health-gated variants appear only where a health mapping exists. A per-instance default lets an operator choose once.

**How rolling differs by runtime:**

| Runtime | Mechanism | Who does the work |
|---|---|---|
| Kubernetes | create a **new generation-named immutable ConfigMap** and update the workload's **reference** (§6.2, SM-D25) — never patch a ConfigMap, never patch an annotation | **Kubernetes** — a Deployment honours `maxUnavailable`/`maxSurge` and readiness probes; **a StatefulSet follows its own `updateStrategy`** (`RollingUpdate` with `partition`, or `OnDelete`, which does **not** roll automatically) and is handled separately. Rollback = point the reference back at the prior ConfigMap. |
| VM/systemd, multi-host | per host: write config → `systemctl restart` → poll health until healthy (bounded) → next | **SM** |
| Docker, multi-host | same, via Docker API per host | **SM** |
| Single replica | not possible | — |

**Config divergence is the dangerous failure mode.** The on-failure default is *halt*, which would leave a fleet split across two config versions. For a **distributed cache** that is not a slow rollout — divergent member lists, partition counts or `active_standby` roles risk **split-brain and data loss**. Therefore: a **`CONFIG_DIVERGED`** state showing each replica's **rendered-config version and `restart-observed` status** — not an effective hash, which most solutions cannot supply — with explicit remediation; **`stage-everywhere-then-restart` as the multi-replica default** so a halt leaves config *uniform-but-unapplied* rather than split; and a **per-solution `mustBeUniformAcrossReplicas` flag** (default true) that **suppresses `rolling-*` strategies** where uniformity is required.

**Stated limit:** a single-replica instance always incurs an outage window on config change — unavoidable without MI development. The UI must state it before applying (*"requires restart · 1 replica · expect ~N s unavailability · continue?"*) and the operator decides.

---

## 10. Telemetry acquisition

**Governing principle:** acquisition transport is a **per-binding policy** (SM-D23 — one Target can host solutions with different acquisition) and the normalized model is **transport-agnostic** — store, graphs and UI never branch on how a metric arrived. The ingest layer is **metrics-agnostic**: it accepts any declared metric, maps it by the contract, and stores it with no new application code per solution.

| Source | Footprint | MI development? | Constrained by |
|---|---|---|---|
| `direct-poll` | none | no | LB aggregation · K8s pod IPs unroutable · overlapping CIDR · needs inbound reach |
| `local-collector` | agent per host/node | no | requires a hosted footprint |
| `broker` | none | **yes for infra metrics** (business metrics already emitted) | solution must emit them |
| `federated` | none *(ours)* | no | **requires the customer to already run Prometheus/OTel** |

**Gap semantics are per-signal and declared, not per-path (SM-D27).** "The broker path is durable" is not
unconditional: File Mesh exposes `SOLACE_MESSAGING_MODE` with `DIRECT`/`PERSISTENT`/`NON-PERSISTENT`, so a broker
signal is **durable by default** (`PERSISTENT`) but **configurable to non-durable**. Each signal declares its delivery
guarantee, and where durability is *claimed* it must be verified: persistent delivery, durable endpoint, spool quota,
acknowledgement, dedupe key, replay. A `DIRECT` signal is marked **best-effort** and its gaps rendered as gaps. The
**poll path is always best-effort** — samples during collector downtime are lost gaps, explicitly marked.

**Where the broker collector runs.** Under `host-local` the `solace_event` collector is in the Go agent. Under `dp-remote` and `observe-only` there is no agent, so the subscriber lives **in the Java DP**. It is therefore implemented **twice** — Java/JCSMP and Go — which is a real cost, and which means Solace Go API maturity gates only the `host-local` path.

**Pipeline semantics** (both loci): bounded local buffer with an explicit backpressure/drop policy · dedupe keys · freshness/staleness markers · replay/backfill on reconnect · edge auth on collected endpoints.

⚠️ **"Edge auth" is unavailable on Cache, and the round-4 "bind the management port separately" prerequisite was
unachievable.** Verified: Cache has **no `management.server.port`** — `/actuator/*` and the business API share
`server.port: 8081` — and **no Spring Security**. Worse, that API is **mutating and unauthenticated**:
`POST /api/cache`, `DELETE /api/cache`, `DELETE /api/cache/invalidate`, whose `@ConditionalOnProperty` guards are on
**methods**, where that annotation is not evaluated — so `cache-engine.mode: READ` does **not** disable them.

There is no separate port to bind, so the honest control is a **network path that admits only the collector**, with an
**accepted residual**:

| Runtime | Control | Residual |
|---|---|---|
| Docker | dedicated bridge network; DP attached to it; **8081 not published to the host** | the mutating API is reachable from that network |
| Kubernetes | `NetworkPolicy` admitting only the collector's pod selector | same |
| systemd | bind to loopback + local reverse proxy fronting only the actuator paths | closest to a real fix — the proxy can path-allowlist |

**The residual is real and must be accepted at ratification:** wherever `direct-poll` can reach the actuator, it can
also reach cache flush and delete. It cannot be closed without MI development (`HC1`), and it is **not** closed by the
`mode` property. Recorded in §13. **HA is Detect + Recover** — self-heartbeat, with a missing heartbeat **investigated across cause-specific dimensions** (agent / node / broker / cert / ingest / clock) rather than collapsing to one conclusion, plus runtime auto-restart and a node-local persisted buffer. Collector isolation: a bad collector fails alone, never the process.

### 10.1 Why not just poll the actuators from the control plane

Meeting the position fairly: **it is fine for a small, flat, single-network fleet** — which is exactly why v1's walking skeleton uses it. It fails as a platform default on seven axes, two of them incompatible with the security posture:

| Axis | CP polls actuator | Edge collect + outbound push |
|---|---|---|
| **Security topology** 🔴 | needs inbound access into each customer network | zero inbound; identical everywhere |
| **Load-balanced replicas** 🔴 | the CP knows only the VIP; each poll hits one random backend; per-JVM metrics non-aggregatable; a dead replica is invisible | co-located collector reaches every replica |
| Scale | the CP originates N/T req/s and tracks address, credential and health per instance | load distributes to edges; the CP is a write sink |
| Discrete events | lost between polls (completed files, errors, restarts) | every event captured — why the FM MI already pushes |
| Ephemeral / NAT | cannot poll churning pods or changing IPs | the collector reports what is local |
| Liveness | "no answer" is ambiguous | a missing heartbeat is explicit, resolved across dimensions |
| Outage resilience | poll gaps are lost | buffered and backfilled |

**Co-locating CP and DP on-prem cures exactly one of these — the first.** The rest are accepted and deferred by v1's narrow single-network scope, and they **re-bind the moment a Target has more than one replica or sits behind an LB**. That is precisely why `host-local` is v1 scope rather than a later increment.

**On resolving addresses:** identity → current address is resolved at scrape time from the runtime's own authority (K8s API, Docker socket, declared inventory), never from a cached IP. Restart windows render as *unready/gap*, not "down."

---

## 11. Networking forcing functions

Properties of networks, not preferences. These are why the loci exist.

- **Load-balancer aggregation trap.** Polling *through* a Service/LB VIP hits one random replica — non-aggregatable, and a dead replica is masked. Never poll the VIP; discover and scrape each instance. Kills `direct-poll` behind an LB.
- **Overlapping CIDR across VPCs.** Identical private ranges make a central poller **impossible** to route. Evaporates when the actor is local. Decisive for `host-local` in multi-VPC.
- **Kubernetes per-pod.** Correct discovery is via the K8s API / EndpointSlices from **inside or adjacent to** the cluster; an external DP cannot reach pod IPs. Forces `local-collector` or `federated` for pod-level metrics — but **not** for config apply, which the K8s API serves remotely.
- **Config/lifecycle reach.** `dp-remote` must reach *into* the host to write config and restart ⇒ inbound + stored credentials. `host-local` acts locally ⇒ outbound-only. The central posture trade.

## 12. Topology matrix

| Concern | Bare VM | Single-host Docker | Multi-host Docker | Kubernetes | Across VPCs |
|---|---|---|---|---|---|
| **Config apply / lifecycle** | `dp-remote` (SSH) or `host-local` | either (Docker API) | either (Docker API per host) | **either — the K8s API is remotely routable** | `host-local` (CIDR blocks remote) |
| **Config-writer conflict** | none under `additional-location` | none | none | ⚠️ **GitOps self-heal reverts SM's patches** (§6.2) | per its runtime |
| **Infra telemetry** | `direct-poll` or `local-collector` | either | `local-collector` (off-host poll not routable) | **`local-collector` or `federated`** — `direct-poll` cannot reach pods | `local-collector` |
| **Business telemetry** *(always-on channel)* | `broker` | `broker` | `broker` | `broker` | `broker` |
| **Broker collector runs in** | DP (Java/JCSMP) or agent (Go) | same | same | same | same |
| **Discovery** | declared inventory (`http_sd`) | declared + Docker socket import | declared + per-host import | declared + K8s API import | declared per VPC |
| **Scheduling** | systemd timer | agent- or DP-as-scheduler | same | K8s CronJob | same |

Columns where only `host-local` works for telemetry are exactly the forcing functions — that is where the agent is not optional.

## 13. Capability limits

Stated, not solved. These must be accepted knowingly at ratification.

| Limit | Cause |
|---|---|
| **Drift detection cannot verify effective config for secret-bearing solutions** — those fields report `effective-unverified` | SM-D22: `configprops` is sanitized by default (hashes collide) and unsanitizing would leak credentials on an unauthenticated actuator; a protected path needs MI development (`HC1`) |
| **Version-aware config rendering depends on a per-solution schema adapter, not build metadata** | SM-D20: Cache ships no annotation processor and no generated metadata; adding one violates `HC1` |
| **The agent↔plane wire protocol does not exist yet** — no `.proto`, no state machine | SM-D24 re-homes the session to the DP; B/D16 described agent↔CP and was never implemented |
| **Kubernetes requires a single declared config writer** — SM or GitOps, not both | SM-D25: `ignoreDifferences` does not prevent overwrite on sync, and `RespectIgnoreDifferences` is ineffective on first creation |
| **On a single-port unauthenticated MI, telemetry reachability implies reachability of its mutating API** | Cache shares `8081` between `/actuator/*` and `POST`/`DELETE /api/cache*`, has no Spring Security, and its `mode` guards are `@ConditionalOnProperty` on methods (not evaluated). Mitigated by a collector-only network path; **not closable without MI development.** |
| **`rolling-health-gated` is unavailable for Cache** | `active_standby` leader state is exposed only in a log line — a standby reports `health: UP` while processing nothing, so a health gate would pass a replica that took no traffic. Not "opt-in"; **unsafe if enabled.** |
| **PostgreSQL ≥ 14 required** | §8.0: `DETACH PARTITION … CONCURRENTLY` is PG 14+; the reference deployment runs 13 |
| **Six-month retention is a conformance prerequisite, not a guarantee under any load** | SM-D26: no columnar compression; if the benchmark fails, the deployment is declared non-conformant |
| **The platform-wide "outbound-only, no stored client credentials" guarantee is gone** — it holds **only for `host-local`** | `dp-remote` needs inbound reach and holds host credentials. Reverses D2 and parent §17. |
| **`dp-remote` apply carries none of B/D16's safety properties unless separately built** — no generation ordering, idempotency key, lease, active revocation or crash-safe resume | those are agent↔CP protocol properties; until a `dp-remote` equivalent exists that apply path is best-effort and non-idempotent |
| **On GitOps-managed Kubernetes, SM's config can be reverted by the customer's own reconciler**, possibly mid-rollout | §6.2 — mitigated by an excluded ConfigMap, a prerequisite SM cannot technically enforce |
| **SM owns one property source, not the effective config** — a customer env var or `-D` flag silently overrides SM | §6.1 — mitigated by pre-flight and effective-config verification, not eliminated |
| **Raw retention is gated on the sizing model, not guaranteed at 6 months** | no columnar compression in plain PostgreSQL |
| **Histograms and summaries unsupported in v1** | quantiles cannot be averaged into rollups |
| **No per-pod / per-JVM metrics** for a footprint-refusing customer on Kubernetes without `federated` | pod IPs unroutable from outside; the LB-fronted Service hits a random replica per request. Available remotely via the K8s API: pod status, restart counts, resource requests/limits, events — **health and lifecycle state yes, per-replica metrics no** |
| **Entitlement enforced at declaration only** — cannot block *or* detect unentitled usage | no artifact-pull credential, no discovery |
| **No shadow-deployment visibility** | declared-only authority |
| **No runtime-requirement pre-flight gating** | SM does not perform the deploy |
| **Single-replica config change always incurs an outage** | restart-to-apply; avoidable only with MI development |
| **Version drift undetectable where `build-info` is absent under `dp-remote`** | HC1 — cannot require a build change; degrades to "unverified" |
| **Scale-out of *undeclared* processes untracked** | declared-only authority (replicas of *declared* instances are tracked) |
| **`dp-remote` DP is a credential concentration point** | least-privilege bounds the blast radius; it does not remove the concentration |

## 14. Config-driven UI

> ⚠️ **SUPERSEDED by [`dynamic-ui/design.md`](dynamic-ui/design.md) + [`dynamic-ui/decisions.md`](dynamic-ui/decisions.md) (`UI-D1`…`UI-D23`).**
> This section is retained for context only. It predates the Subsystem A design and is wrong or incomplete in three
> ways: it has **no component model** (a solution is a set of component *types*, per `UI-D1`), it renders against the
> **observed** version (`UI-D12` supersedes that — the version is not observable from the MI), and its custom-UI
> module is under a **proposed narrowing** (see the escape-hatch note below). Read the Subsystem A documents instead.

The default UI is **config-driven**: Designer forms render from `schema + enrichment`; Governance from the telemetry contract's `health` mapping; Monitoring from its `metrics` mappings. Onboarding a solution therefore renders its screens with **no new React code**.

The renderer is a runtime **schema-driven engine**, independent of the component library: each schema field + enrichment maps to a control (string→text field, enum→select, boolean→switch, secret→masked field, object→grouped fieldset). It is **version-aware** — it renders against the observed version's schema (§7.2) — and is strictly more robust than a YAML-leaf parser because it consumes a generated, enriched, typed schema rather than guessing.

Solutions whose UX exceeds generated forms supply a **custom-UI module** (this is how File Mesh's flow-DAG editor is absorbed rather than dumbed down): **dynamic for the common case, custom module for the rich case**, both in one shell and theme.

⚠️ **`UI-D21` proposes narrowing this to SM-built named screen types, with no package-supplied code.** `D1` is
**`LOCKED`** and names the escape hatch, so **`D1` stands until the architect group decides** — this is a flagged
conflict, not a settled change (`I4`). The evidence behind the proposal: File Mesh's flow editor persists config as
`(flow, component, field) → VALUE` with no edge/node/link table, and `CONNECTOR_DESIGN`'s `links` are derived
deterministically from the component set, so there is no independent routing choice a contract cannot express. See
[`dynamic-ui/design.md`](dynamic-ui/design.md) §5.4.

⚠️ **The frontend component stack is settled; the visual reference is not.** *(This section previously read "unresolved" — open item **19a closed** in favour of `D7`.)* D7 locks React + Vite + **Material-UI**, reusing File Mesh's ~66 MUI wrappers and expressing the mockup as an MUI theme. The design handoff (deleted from this repo on 2026-08-10) specified **Tailwind v4 + Radix/shadcn on the Solace Agent Mesh Design System**, instructing implementers to map primitives to that DS rather than reimplement. ⚠️ **Partly resolved 2026-08-19.** An in-repo visual reference exists again — the approved `sm-v2` mockup at [`frontend/reference/`](frontend/reference/), proposed as the specification of record by `FE-D25` — and it sits on the **same Solace Agent Mesh Design System** the deleted handoff named, with its palette verified against that product's source (`FE-D26`). This does **not** reopen the stack question: sm-v2's paint is adopted through an MUI theme, so `D7`'s stack clause and `FE-D4` stand and open item **19a** remains closed. What is still outstanding is the designer's confirmation of the palette — open item **19b**. *(Earlier text here cited "open item 7", which was never tracked; the live items are 19a and 19b.)*

Also a prerequisite for phase 2: **audit the existing File Mesh UI and harvest reusable components** (setup wizard, connection objects, runtime status, dashboards, role management). Expect to redesign parts so they are shared between the platform shell and File Mesh's custom-UI module rather than rebuilt.

## 15. Recommended v1

All three runtimes and both loci are in scope. `direct-poll` cannot reach K8s pods or off-host Docker, so **`host-local` and its identity machinery are v1 scope, not a later increment.**

**v1 scope:** one solution (Cache) · single-tenant · local auth · Docker + systemd + Kubernetes · both loci · `service` workloads only (**`scheduled` cut from v1** — incompatible with SM-D28's lease model, and no v1 solution needs it) · built-in roles on the new deny-by-default authz · health + 2–3 Cache KPIs.

**The walking-skeleton gate holds, and in-scope ≠ built-simultaneously.** Build order:

1. **Walking skeleton (the gate)** — one generated Designer screen · one graph · one role · one config apply end-to-end · on **Docker** via **`dp-remote`** (cheapest locus: no CA, enrollment or lease machinery yet). Nothing below starts until this is accepted end-to-end. Includes: CP catalog + entitlement + one RBAC role; the plane seams of §3.1 from the first commit; **the launch-spec pre-flight of §6.1** (without it SM cannot know its config is effective); `manual` apply; `{dp-remote, direct-poll}` telemetry **only — no broker collector, because Cache emits no broker telemetry** (the DP's JCSMP collector arrives with File Mesh in phase 2); the storage schema with **the sizing model computed for the target deployment**; and the `dp-remote` apply protocol.
2. **`host-local` agent** — enrollment, CA, mTLS, scoped authz, the B/D16 protocol, the Go collector. Unlocks LB / NAT / overlapping-CIDR / multi-VPC and is the precondition for real K8s telemetry.
3. **systemd driver** — both loci (local `exec` and SSH).
4. **Kubernetes** — K8s API config apply (both loci), in-cluster/DaemonSet collector, leader-elected control role, and the §6.2 GitOps prerequisite.

Then **phase 2 — File Mesh** (broker event collector — a Node→Java port of the listener parsing, not reuse; custom-UI escape hatch; scoped multi-tenant RBAC; the FM backend port) → **phase 3 — DB MI**.

## 16. Sub-specs

Each needs its own brainstorm → spec → plan cycle, and is authoritative in its area once written.

| Sub-spec | Must settle |
|---|---|
| **Backend** (Java/Spring Boot) | `sm_control`/`sm_data` schemas + tenant boundary · CP/DP module boundaries + ArchUnit rules · token audience · coarse cross-plane interfaces · **CP↔DP interface versioning** · Flyway migrations · Spring Security deny-by-default authz + policy-as-code catalog · data access on HikariCP + `NamedParameterJdbcTemplate` (+ the Oracle keep-or-drop decision) · physical metrics schema, partition management, rollup/retention/archive jobs on Quartz · metrics-agnostic ingest interface · **`dp-remote` apply protocol** · `dp-remote` transports + credential handling and audit · **secrets-in-config model** · **alerting service** · **DP backup/restore + config-as-code export** · **SM self-upgrade** · **CP/DP instance-identity protocol** · **source-adapter contract generation** (SM-D20 — curated + bidirectional key-set test for Cache, `FM_CONFIGURATION_TEMPLATE` adapter for File Mesh; **not** build-metadata generation) · **Java/JCSMP broker collector** |
| **Frontend** (React + Vite) | **resolve the MUI vs Tailwind/Radix conflict first** · schema-driven renderer · `cpClient`/`dpClient` split incl. the post-split browser↔DP topology · plane-split state domains · version states + **field-level diff with secret masking** · restart CTA, strategy picker, per-replica progress, `CONFIG_DIVERGED` remediation, outage confirmation · resolution control + gap rendering (raw tier included) · declared-inventory management + bulk import · **RBAC verbs for high-consequence actions** (assert `restart-all`, `rolling-no-validation`, forced-raw query, streaming export, version re-declaration, archive/restore) · harvest File Mesh UI components |
| **Agent** (Go) | enrollment + mTLS + scoped authz · protocol state machine (lease duration/renewal, expired-lease-mid-apply, persisted-state format) · collector primitives + Prometheus `discovery`/`scrape` reuse · **Solace Go API maturity** · runtime version introspection · K8s leader election |

### 16.1 Gaps routed to sub-specs, with binding constraints

The mechanism belongs to a sub-spec; the constraint is binding and stated here.

| Gap | Constraint the sub-spec must satisfy |
|---|---|
| **Secrets inside solution config** — MI configs carry DB passwords, broker credentials, keystore passwords (credential handling covers only *host* credentials) | Config schema gains a **`secretRef`** type. Values live **only** in the secret backend and are **never persisted** in DP config tables, version history, field-level diffs, raw exports or archives. The rendered-config hash is computed **over references, not resolved values**. On-target files are written **atomically, mode 0600, owned by the service account**. Secret rotation is a first-class flow. *(Note: "resolve secretRefs → tmpfs" is unachievable under `dp-remote` — the JVM reads the file from disk at restart.)* |
| **Alerting** | Drift is "alert-only", and `MISSING`/`UNREACHABLE` and credential expiry all assume alerting exists. Required: channels, dedup, severity, routing, per-scope subscription, and **RBAC for who receives what**. Phase 2 must also replace File Mesh's `error_listner`. **Until it exists, drift and version states are UI-state-only and nobody is notified** — say so plainly rather than implying alerts exist. |
| **`dp-remote` apply failure semantics** | Write-temp-then-rename · keep last N config files on the target for revert · an **apply id + per-host apply state persisted in the DP before any remote action** · bounded retry · an explicit **`APPLY_INDETERMINATE`** state blocking further applies until an operator confirms actual. Covers SSH drop mid-write, file written but restart failed, connection lost between the two. |
| **DP database backup** | The DP is the **only** copy of configuration-of-record and its history. Backup/restore with a **tested restore and stated RPO/RTO** is a v1 non-functional requirement, plus a **config-as-code export** (git-friendly per-instance YAML with history) so configuration survives total database loss. A CP-only restore recovers nothing operational. |
| **SM self-upgrade** | Upgrade procedure · migrations against large partitioned tables · **CP↔DP version skew after the split** · agent↔DP protocol compatibility window · rollback of a failed upgrade. |
| **CP/DP instance identity** | Cross-schema FKs are forbidden, yet an instance is owned CP-side while its config lives DP-side. Required: name the **identity owner** (recommended: CP owns identity, DP references an opaque id) · a **two-phase create/delete protocol** so the entitlement check and the DP config write cannot half-commit · an **orphan-detection job** · and how a `sm_data` row's `tenant_id` is validated without a join. Every multi-tenant leak in the File Mesh audit was this class of unenforced boundary. |

## 17. Reference codebases (sibling directories)

- `/Users/parikshit/Work/Development/solace-cache-engine` — **Cache**. Java 17 / Spring Boot 3.3.5. Config via `application.yml` + `CacheEngineProperties`. PULL telemetry: `/actuator/health`, `/api/cache/stats`, `/api/cache/size`. Docker image + external config mount. **v1 integration target.**
- `/Users/parikshit/Work/Development/pubsubplus-connector-file-common` — File Mesh MI **shared framework**. Config via typed event models; PUSH telemetry via command-center events over Solace. DB MI is expected to reuse it.
- `/Users/parikshit/Work/Development/file-mesh-ui` — the **existing File Mesh Manager**, frontend and backend. React 17 + CRA + Redux-Saga + MUI; Node/Express/TS + PostgreSQL 13; ~30 tables; four Solace listener services. Audited — see §2.1 and the hardening backlog.

## 18. Glossary

- **Control locus** — where the driver executes: `dp-remote` · `host-local` · `observe-only`.
- **Infra telemetry source** — how *infra* metrics are acquired for a Target. The business/broker channel is separate and always-on, not a value here.
- **Config mechanism** — how SM owns the config file: `additional-location` · `overwrite-in-place`.
- **Lifecycle owner** — per-Instance: `sm` or `customer`. Narrows within the Target's `control_locus` ceiling; never widens.
- **Config states** — five distinct states, never collapsed (§6.1): **desired** (authored) → **rendered** (SM's file
  content) → **persisted** (on the target) → **restart-observed** (process restarted after persist) →
  **effective-verified** (the JVM demonstrably resolved these values). Downstream gates use **`restart-observed`**;
  `effective-verified` requires a protected `configprops`-class endpoint and a non-secret allowlist, and is
  **unavailable for Cache**, which exposes `health,info,metrics` only. **`restart-observed` is not proof of
  convergence** — an env var or command-line property can still be overriding the file.
- **Declared inventory** — the operator-maintained set of hosts/instances SM manages; simultaneously the `http_sd` scrape source, the `dp-remote` target list, and the correlation set.
- **Logical instance** — a declared Solution Instance; the processes carrying its identity are its replicas.
- **Series** — a row in the `series` dimension: the unique `(tenant, instance, metric_name, label-set)` tuple, addressed by integer `series_id`.
- **Rollup** — a partitioned table of pre-aggregated `count/sum/min/max/first/last/delta` per series per bucket, populated incrementally and type-aware.
- **Generation** — an immutable, version-pinned deploy unit carrying a monotonic generation number.
- **Solution Package** — the versioned bundle that onboards a solution (§5.1).
- **Desired state** — the **DP's** record of what a Target should run, authored through the CP (which authorizes and
  validates but stores no copy — SM-D1) and pulled and cached by the agent. **The CP is not the store**; a CP-side copy
  would make the SaaS split a data migration with conflict resolution rather than a boundary change. The write is
  two-step with an **idempotency key**: CP authorizes → DP commits; a repeated key is a no-op, an unacknowledged write is
  retried, never duplicated.
- **Entitlement** — per-tenant enablement of a solution and its allowed versions.
