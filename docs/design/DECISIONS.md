# Solution Manager — Decision Register

**Last updated:** 2026-08-10
**Companion:** [`architecture.md`](architecture.md) — the architecture this register governs. This file records *what was decided and why*; `architecture.md` records *how the system works*.

> **Consolidated 2026-08-10** from three predecessor specs (`2026-06-17` parent architecture, `2026-07-08` Subsystem B, `2026-08-04` on-prem-first revision rev 2). Those files are removed; their content lives here and in `architecture.md`.

---

## ID discipline (binding)

**Identifiers are immutable and were deliberately NOT renumbered during consolidation.** A citation like "per D8" or "per SM-D16" must never shift meaning. Three ID families are therefore preserved as-is:

| Family | Origin | Meaning |
|---|---|---|
| `D1`–`D17` | 2026-06-17 parent architecture | Platform-wide foundations |
| `B/D1`–`B/D17` | 2026-07-08 Subsystem B | Deploy / agent / telemetry loop |
| `SM-D1`–`SM-D31`, `SM-D11a` | on-prem-first revision + correction rounds 2–5 | On-prem-first revision |

**To change a decision, add a new one that supersedes it** — never edit a decision's meaning in place. Record the supersession in both entries.

## Status legend

| Status | Meaning |
|---|---|
| **LOCKED** | Ratified by the architect group. Binding. |
| **PROPOSED** | Awaiting architect-group ratification. **Not binding.** Where a PROPOSED decision conflicts with a LOCKED one, the LOCKED one stands and the conflict must be flagged, not silently resolved. |
| **SUPERSEDED** | Replaced **by a ratified decision**; retained so citations resolve. Points at its replacement. |
| **RETIRED** | No longer applicable at all, **by ratified decision**. |
| **LOCKED — proposed supersession by SM-Dx** | Still **binding**. A proposal exists to replace it but has not been ratified. **Build against the locked decision, not the proposal.** |

⚠️ **A PROPOSED decision can never change another decision's status.** Only ratification can. Rev 1 of this register
violated that rule — proposed SM-D2 marked locked B/D6 and D16 `RETIRED`, and proposed SM-D4 "superseded" locked D5 —
which let two developers build different systems while both claiming compliance. Corrected below.

⚠️ **All `SM-D*` decisions are PROPOSED.** The architect group has confirmed only two things from the revision: that SM is a config/governance plane rather than a deployment plane, and that `dp-remote` may hold host credentials. Everything else in that family awaits sign-off.

---

## Hard constraints (not decisions — not negotiable, not subject to ratification)

### HC1 — No MI development
**Solution Manager may never require new *development* on the managed integrations.** MI development depends on the **MDK (Micro Development Kit)** owned by another Solace team; SM controls neither their roadmap nor their approach.

- **Fair game** — deployment configuration: launch specs, env vars, mounts, and the contents of a config file SM writes (including Spring `management.*` / `info.*` properties and Micrometer tags).
- **Not fair game** — code changes, build-plugin changes (e.g. adding Spring Boot's `build-info` goal), new endpoints, new telemetry emission.

Formalized as **SM-D16**: *anything SM needs from an MI must be satisfiable by something SM controls, or be optional with a defined degradation.* Audit every proposal against this before proposing it.

### HC2 — Server-side tenant isolation
Isolation is enforced server-side, never by client-supplied scope. An agent or caller can only ever receive its own tenant's data. This is a direct response to the File Mesh audit finding that some controllers trusted a client-supplied `orgName`.

---

## 1. Scope & platform

| ID | Decision | Status |
|---|---|---|
| **D1** | **Hybrid platform** — shared services + config-driven UI by default + a per-solution **custom-UI escape hatch**. Avoids a lowest-common-denominator form generator while keeping onboarding declarative. | LOCKED |
| **D9** | **One codebase, multi-tenant-capable core, collapsible to single-tenant** — the same product runs as multi-tenant SaaS or self-hosted single-tenant (incl. air-gapped). | LOCKED |
| **B/D17** | **Editions:** the solution artifact + descriptor + schema are byte-identical across SaaS and on-prem. Only the **control plane** ships in two editions, chosen at build/packaging time (SaaS: multi-tenant, authoring enabled, entitlement = live DB record · self-hosted: authoring disabled, air-gap-capable, entitlement = Solace-signed manifest verified locally). Manifest signing happens in Solace's per-customer licensing step, driven by the CP edition and issuance channel — never by a flag on the solution build. | LOCKED |
| **SM-D9** | The three primary objectives — dynamic screens with no new code, dynamic graphs + config change, and long-window retention — are **independent of `control_locus` and `infra_telemetry_source`**. | PROPOSED |
| **Objective #4** | *"The UI must drive deployment of artifacts (JARs/images) into client environments."* | **LOCKED — proposed retirement by SM-D2** |

**Scope posture (SM-D-era):** **on-prem first, SaaS-extensible.** Deliver and operate for on-prem (CP + DP co-located, one deployment, one dashboard); defer SaaS *operation* while protecting four seams — tenant boundary in the schema, telemetry ingest behind an interface, control locus as a clean boundary, and CP/DP separation through schema + backend + UI.

## 2. Planes, placement & topology

| ID | Decision | Status |
|---|---|---|
| **D11** | **Ownership tree** `Tenant → Organization → (Sub-org) → Environment → Solution Instance`, **decoupled from placement** (`Deployment Target`). An instance is *owned by* a tree node and *placed on* a Target; re-targeting is a rebind that leaves ownership, RBAC and config history intact. | LOCKED |
| **D12** | **Two org levels for v1** (parent pointer retained for later nesting). | LOCKED |
| **B/D7** | A Deployment Target is bound to **exactly one driver + location + capacity** at enrollment. The descriptor declares runtime requirements; pre-flight validates fit; the customer provisions the compute. | LOCKED — *enforcement lost under SM-D2; requirements are now advisory* |
| **SM-D1** | **CP governs; DP owns config + business data.** The rule: *the CP is authority over **who may** change config and **what shape** it may take; the DP is authority over **what the config is**.* The CP holds catalog, schema registry, entitlements, RBAC and the authoring UI — and **no copy of config**. The separation seam runs through **schema** (`sm_control` / `sm_data`, no cross-schema joins or FKs), **backend** (two modules, two data-access layers, neither importing the other) and **UI** (two API clients with independently configurable base URLs). Two further seams: **token audience** (build "DP validates a CP-issued token" from day one) and **interface granularity** (coarse/batch interfaces, since in-process calls become network calls after the split). | PROPOSED |
| **SM-D12** | ⚠️ *Revised by SM-D23 — cardinality was wrong.* A Target carries **`{control_locus, infra_telemetry_source, config_mechanism}`**; an Instance carries **`lifecycle_owner`**. `control_locus ∈ {dp-remote, host-local, observe-only}`. "Mode P"/"Mode C" are documentation shorthand for the two common pairings, never schema values. | PROPOSED |
| **SM-D10** | **Lifecycle ownership is per-instance.** `control_locus` is the Target-level *ceiling*; `Instance.lifecycle_owner ∈ {sm, customer}` narrows within it and never widens. An instance on an `observe-only` Target is forced to `customer`. | PROPOSED |
| **D2** | **Pull-based agent; outbound-only — no inbound access, no stored client credentials.** | LOCKED · ⚠️ **SM-D12 proposes superseding this for `dp-remote`**, where SM holds host credentials and needs inbound reach. The guarantee would survive only for `host-local`. |
| **B/D11** | **Artifact delivery:** (a) agent-based with a filesystem/JAR-repo source — default; (b) agentless CP→SSH — *"a quarantined legacy exception… not part of the v1 build… never first-class."* | LOCKED · ⚠️ **SM-D12 proposes promoting (b)** from an *artifact* transport to a *config* transport **and** to a first-class v1 locus — a category change plus an inversion of "never first-class". (a)'s artifact leg is retired by SM-D2. |

## 3. Deployment & configuration

| ID | Decision | Status |
|---|---|---|
| **D3** | ⚠️ *Premise false for Cache — see SM-D20.* **Config schema is generated from the solution's own build, then enriched**, and published as a versioned, CI-tested contract. Drift is prevented by contract tests, not assumption. | LOCKED |
| **D4** | Schema **pinned to artifact version**; deploy unit = the bundle `{artifact@v, schema@v, config}`. Strict-validate at the control plane, tolerant reader at the app. | LOCKED · ⚠️ **partially superseded** by SM-D2/SM-D14 — the artifact leg is gone; only render-against-observed-version survives |
| **B/D3** | **First deploy is agent-pulled and version-pinned:** author generation → agent pulls → pre-flight → materialize config → fetch artifact → apply → health-gate → report actual. *"The agent is always the actor; the CP never touches the host."* | LOCKED · ⚠️ **actor model superseded for `dp-remote`** (SM-D12); ordering, health-gating and report-actual survive for config |
| **B/D12** | **One reconcile loop, three triggers** — upgrade (apply a newer generation), rollback (re-apply a prior known-good one), drift (actual ≠ desired). Retain the last ~10 generations. **Drift policy:** existence/health drift auto-reconciles; **config/spec drift is alert-only with manual UI reconcile** (re-apply desired, or adopt actual as a new generation). Never silently auto-revert a human. | LOCKED · ⚠️ artifact revert impossible under SM-D2; **config-only rollback survives and stays deterministic** |
| **B/D13** | **What to launch is data** in the descriptor; **how to launch it is the driver's job** per runtime. The **agent is the cross-runtime orchestrator** — sequencing is uniformly `start → health-gate → next`; native primitives are optional accelerants, never the source of truth. A generation is **health-gated as a unit**: fresh deploy fails ⇒ roll back the partial + mark FAILED; upgrade fails ⇒ roll back to the prior generation. | LOCKED · artifact leg retired; the sequencing model is reused by SM-D15 |
| **B/D14** | **Workload types** `service` \| `scheduled` \| both; the descriptor declares what is supported and the **end user selects mode + schedule at deploy time**. Native scheduler where available (K8s CronJob, systemd timer); agent-as-scheduler on Docker. Health for scheduled work = **last-run-success**, not liveness. | LOCKED · under `dp-remote` the Docker scheduler relocates to the DP |
| **B/D9** | Components versioned independently; **a solution version is a BOM** pinning the exact component set; parent/child variants share common fields. The generation pins the resolved BOM. | LOCKED · ⚠️ **superseded in practice** — SM-D19 has no BOM and SM-D14 detects a single version string. Component-level versioning defers with the artifact path. |
| **B/D10** | **Compatibility declared in the release descriptor, enforced at pre-flight** — required agent capabilities + optional min-agent-version; the agent advertises capabilities; mismatch surfaces "agent upgrade required" before any deploy. | LOCKED · applies to `host-local`; pre-flight *enforcement* generally lost under SM-D2 |
| **B/D6** | Artifact source: coordinates in desired state, registry endpoint as per-Target config; DP-local registry/mirror for multi-node and air-gap; digest-pin integrity + signed manifest-with-digests. | **LOCKED — proposed retirement by SM-D2** |
| **D16** | Artifact distribution (as above, parent-level). | **LOCKED — proposed retirement by SM-D2** |
| **SM-D2** | **Artifact deployment is the customer's CI/CD; SM owns configuration.** SM does not push artifacts, host registries, hold image-pull credentials, or gate artifact access. Removes an entire subsystem. | PROPOSED *(group-confirmed in principle)* |
| **SM-D3** | ⚠️ *Corrected by SM-D21 (config path) and SM-D22 (drift verification).* **Config apply = external-file replacement + restart**, via one of two declared mechanisms: **`additional-location`** (preferred — MI launched with `SPRING_CONFIG_ADDITIONAL_LOCATION` at a path SM owns; a one-time *launch-spec* change, not development) or **`overwrite-in-place`** (SM writes the file the MI already reads; zero launch change, but CI/CD becomes a competing writer). Declared per solution, overridable per Target. **SM owns one property source, not the effective config** — see `architecture.md` for the precedence trap, the launch-spec pre-flight, and effective-config verification. | PROPOSED |
| **SM-D13** | **Declared-only instance authority.** SM manages only what an operator declared; entitlement is checked at declaration. Undeclared processes are **not** discovered, adopted, monitored or listed. Correlation is a **per-source declared strategy** (not one mechanism). A declared instance is *logical*; processes carrying its identity are replicas, so replica scale-out is tracked automatically. Consistent with D2-era intent — parent §10 already held that "which solution runs here is an explicit user decision, not inferred from heartbeats." | PROPOSED |
| **SM-D14** | **Version-mismatch rule.** Observe the running version; states are `OK` / `VERSION_DRIFT` / `VERSION_UNKNOWN` / `MISSING`, with `MISSING` further split into `RESTART_EXPECTED` / `UNREACHABLE` / `NOT_EXPOSED` / `MISSING`. **Invariant: a config form is never rendered against any schema but the observed version's.** Detection is locus-dependent — reliable under `host-local` (runtime introspection), best-effort under `dp-remote` (needs `build-info`, which HC1 forbids requiring ⇒ degrades to "unverified"). | PROPOSED |
| **SM-D15** | **Restart strategy is operator-selected, `manual` by default.** SM must not encode a restart strategy it cannot verify. Opt-in ladder: `rolling-health-gated` · `rolling-no-validation` · `restart-all` · `scheduled`. **SM offers only strategies the solution's declared capabilities support.** Multi-replica default ordering is **stage-everywhere-then-restart**, so a halt leaves config uniform-but-unapplied rather than divergent; `CONFIG_DIVERGED` is an explicit state. | PROPOSED |
| **SM-D19** | ⚠️ *`launchVar` replaced by a declared per-artifact path — SM-D21.* **Solution Package redefined** — artifact legs out, config-ownership legs in: config schema · external-config mechanism + required `management.*` properties · per-source identity strategies · optional version detection · lifecycle verbs · optional restart-strategy default · config-uniformity flag · telemetry contract · UI enrichment · artifact coordinates (informational only) · signed manifest. | PROPOSED |

## 4. Telemetry

| ID | Decision | Status |
|---|---|---|
| **D5** | **The agent normalizes at the edge** into one model (collectors: scrape / event / exec), declared per solution and contract-tested, so the UI reads one model regardless of origin. | LOCKED · ⚠️ **superseded for `direct-poll` and `federated`**, where normalization happens in the DP |
| **B/D2** | **Agent collects locally and pushes normalized outbound.** Pipeline semantics: bounded node-local buffer with an explicit backpressure/drop policy, dedupe keys, freshness markers, replay/backfill on reconnect, edge auth. **Gap semantics are path-specific** — the broker/event path is durable; the pull/actuator path is best-effort with explicitly marked gaps. HA is **Detect** (self-heartbeat, investigated across cause-specific dimensions) + **Recover** (auto-restart + persisted buffer). Load-balanced endpoints use cluster-check-style dispatch. | LOCKED · refined by SM-D4 |
| **SM-D4** | ⚠️ *Corrected by SM-D27 — the "always" is conditional.* **Business metrics ride an always-on broker channel where the MI already emits them** (not an axis value). **Infra transport is per Target** from `infra_telemetry_source ∈ {direct-poll, local-collector, broker, federated}`. Ingestion is **metrics-agnostic**: any declared metric is mapped by the solution's contract and stored with no new application code. **Polling is always DP-local — the CP never polls across a customer boundary.** Pair transport selection with a reachability pre-flight **and ongoing reachability monitoring**. | PROPOSED |
| **SM-D6** | **One collector binary for the telemetry role** (SD + scrape + broker-subscribe + normalize + push), running either in-process in the DP or as a hosted agent; reuse Prometheus's `discovery`/`scrape` packages rather than reimplementing SD. **The control side is not one binary** — it is per-runtime drivers × execution locus, and only the systemd driver bifurcates (local `exec` vs SSH). | PROPOSED |
| **SM-D5** | **The declared inventory is the only source of truth.** Under declared-only authority, `dp-remote` has no separate discovery mechanism: the inventory *is* the scrape target list, served as `http_sd`. Cloud SD, Consul, DNS, Docker socket and K8s API are **optional import helpers** that pre-fill or refresh that inventory, never independent sources. One inventory serves three purposes: scrape targets, remote-exec target list, correlation set. | PROPOSED |
| **SM-D17** | **`federated` telemetry is an opt-in adapter, never assumed.** No customer is presumed to run Prometheus/OTel, and it is never the answer to a capability gap. Correlation uses a **Micrometer common tag**, not `info.*`. Ingest **accepts only series correlating to a declared instance**; the rest are dropped and counted as rejected. | PROPOSED |

## 5. Storage & retention

| ID | Decision | Status |
|---|---|---|
| **D14** | **PostgreSQL for everything** — system of record, latest health, and time-series metrics. **VictoriaMetrics and Timescale are NOT adopted**; the reason is operational — avoid a second datastore to operate. Retention and indexing are handled as hardening, not as a new datastore. | LOCKED |
| **SM-D8** | ⚠️ *Corrected by SM-D26.* **Plain PostgreSQL with declarative monthly partitioning.** Metrics-agnostic envelope over a **normalized `series` dimension** with integer `series_id`. Tiers: raw **6 months minimum, gated on a sizing model** · hourly rollup 2 years · daily rollup indefinite · verified encrypted archive before drop. Rollups are **plain tables with incremental upsert** (never `MATERIALIZED VIEW`) and are **metric-type-aware** (gauge vs counter, with materialized deltas and reset detection). Query tier routing is automatic with a Raw override; raw stays fully queryable and exportable for the whole window. Ingest stays behind an interface. **Confirms D14 and adds the mechanism.** Timescale is additionally blocked by TSL licensing — see `architecture.md`. | PROPOSED |

## 6. Identity, tenancy, RBAC & entitlement

| ID | Decision | Status |
|---|---|---|
| **D10** | **Azure-style RBAC** — permissions `resource.action` → role definitions (built-in + custom) → scoped assignments → inheritance. The **permission catalog is policy-as-code**; route-derivation is only a lint for unprotected endpoints, never the source of truth. Enforcement is a **single centralized deny-by-default check** every request passes. **Two-sided binding authorization:** deploying an instance to a Target requires `instance.bindTarget` on the instance's ownership scope **and** `target.use` on the Target. | LOCKED |
| **D13** | **Per-tenant BYO IdP via a Keycloak broker** — one identity story for SaaS and self-hosted. Login is tenant-aware; identities are tenant-scoped; IdP groups map to role assignments via OIDC claims and/or SCIM. *(Deferred out of v1 — v1 uses local auth.)* | LOCKED |
| **B/D4** | *(Confirmed 2026-08-11: entitlement is **binary per solution + version range**. A licensed-instance-count dimension was considered and **declined** — the console is the licensed asset, the runtime is governed by contract.)* **Enablement = publish (Solace-only) + entitle-per-tenant.** A **Solace super-admin role sits above tenant admin** and the entitlement-grant permission is Solace-reserved. Enforced at the API **and** the artifact-pull credential. SaaS: live toggle. On-prem: Solace-signed manifest verified locally, no phone-home. | LOCKED · ⚠️ **loses the artifact-pull leg** under SM-D2; with SM-D13 removing discovery, enforcement is **declaration-only** |
| **B/D8** | **Agent authorization scope:** desired state is computed per-Target and delivered only to the agent whose mTLS identity maps to it; pull credentials are scoped to exactly the assigned artifacts; the agent cannot enumerate the catalog. No self-select. | LOCKED · applies to `host-local` |
| **SM-D11** | **`dp-remote` may hold host credentials**, subject to mandatory handling: dedicated secret backend encrypted at rest (OpenBao reference impl) · **per-Target scoping** · **least-privilege accounts** (write only the external config dir, restart only that service — not root, not cluster-admin) · rotation and immediate revocation without redeploy · encrypted transport with **every remote write and command audit-logged** · prefer short-lived/brokered credentials. **Residual risk accepted:** a `dp-remote` DP is a credential concentration point; least-privilege bounds the blast radius but does not remove the concentration. This is the standing argument for steering security-sensitive Targets to `host-local`. | PROPOSED *(group-confirmed)* |

### SM-D11a — `dp-remote` SSH transport security. **PROPOSED (NEW — completes SM-D11)**

⚠️ SM-D11 covered *credential storage* and said "encrypted transport", which says nothing about **who you connected
to** or **what you executed**. The existing File Mesh remote-exec helper is the anti-pattern: it supplies **no host
verifier** and interpolates a path straight into a shell command — `` mkdir "${formattedPath}" `` in
`file-mesh-ui/file-mesh-services/src/helpers/serverOperations/createRemoteFolderOrFile.ts`. That is command injection
reachable from configuration data, plus DNS/MITM redirection to an attacker host. **This must not be copied**, and
"reconcile with the existing FM implementation" (the SM-D3 follow-up) means *learn the targeting mechanics, not the
security posture*.

| Requirement | Why |
|---|---|
| **Pinned host keys or SSH-CA validation** per Target; fail closed on mismatch | without server authentication, DNS or MITM silently redirects the DP to an attacker |
| **Explicit host-key rotation** (add-new → verify → retire-old) | pinning without rotation is an outage the first time a host is rebuilt |
| **No shell.** Lifecycle verbs are an **allowlisted, parameterised** set run via argument vector — never string concatenation | eliminates injection as a class instead of escaping per call site |
| **SFTP write to a temp path, then atomic rename** | a truncated config file from a dropped transfer is worse than no write |
| **Canonical-path + symlink checks**; refuse to follow a symlink out of the declared config directory | a planted symlink turns a config write into arbitrary file overwrite |
| **Restricted `sudoers`** — restart exactly the declared unit, write exactly the declared directory. Not root. | least privilege is only real if the host enforces it |
| **Audit per operation** with Target identity, resolved host, **host-key fingerprint**, exact argument vector, outcome | without the fingerprint the audit cannot prove *which* host was configured |

**This is a ratification blocker**, not sub-spec detail: `dp-remote` is the v1 locus and this is its security contract.

## 7. Stack & build approach

| ID | Decision | Status |
|---|---|---|
| **D6** | Control plane in **Node.js / TypeScript**. | LOCKED · ⚠️ **SM-D18 proposes superseding** with Java 17 / Spring Boot |
| **D15** | **Generalize-and-harden the existing File Mesh Node/Postgres/MUI codebase**, not greenfield. | LOCKED · ⚠️ **SM-D18 proposes superseding** with greenfield |
| **D7** | Frontend **React + Vite + Material-UI** — adopt the File Mesh MUI component stack (~66 wrappers, setup wizard, tables, charts), migrate CRA→Vite, express the mockup's visual design as an **MUI theme**. | LOCKED · ⚠️ **conflicts with the deleted design handoff**, which specified Tailwind v4 + Radix on the Solace Agent Mesh Design System. **Unresolved — see open item 7.** |
| **D8** | Agent in **Go** — lightweight, native Docker/K8s ecosystem, bounded component. | LOCKED · unaffected by SM-D18 |
| **SM-D18** | **Control plane is Java 17 + Spring Boot, greenfield.** Decisive reasons are **organizational and operational**: bench strength (Cache, File Mesh MI, DB MI and the MDK are all Java/Spring), and on-prem already requires a JRE so the JVM is free. Parent §8 had already classified the valuable half (authz, tenancy, migrations, data-access) as rebuild-not-inherit, and the four Solace listener services leave the Node backend regardless. **The Spring `Binder` argument is explicitly *not* decisive** — `Binder` needs the MI classpath, which SM-D2 removes; what a Java CP actually gains is relaxed-binding name canonicalisation. Costs: shared TS types lost (mitigated by OpenAPI codegen), phase-2 File Mesh becomes a port, and the broker collector is built twice (Java/JCSMP in the DP, Go in the agent). | PROPOSED |

**Data-access (open, parent §18.8):** the defect to fix is **string-concatenated SQL**, not "no ORM". Direction: **parameterized SQL on a connection pool** (HikariCP + `NamedParameterJdbcTemplate` under SM-D18), **no heavyweight ORM** (avoid lazy-load/entity-graph latency), a thin query builder only for genuinely dynamic SQL. Non-negotiable either way: **bind parameters everywhere** and **one central data-access module enforcing tenant/authz scope**. Open sub-question: keep or drop **Oracle** support.

## 8. Agent & protocol

| ID | Decision | Status |
|---|---|---|
| **B/D1** | **Control channel is agent-initiated, outbound-only, long-lived stream.** The agent pulls desired state; the CP signals "new generation available" down the open stream — pull-shaped for security, push-latency in practice. The agent caches last desired state and survives CP outages. | LOCKED |
| **B/D16** | ⚠️ *Re-homed by SM-D24 — the agent terminates at the DP.* **Agent ↔ CP protocol: gRPC/HTTP2 bidi over mTLS on 443**, agent-initiated, transport-agnostic message set, HTTP/1.1 long-poll fallback. Phases: Enroll (single-use target-bound token → CSR → CP-CA-signed cert) → Session → Steady state → Teardown. **Revocation is active *and* lease-bounded** — the agent holds a short authorization lease it must renew, and stops reconciling when it expires, so a partitioned or rogue agent cannot act on cached state. **Cert rotation is manual for v1**; the lease, not the cert TTL, bounds authority. **Apply is crash-safe and idempotent** — persisted generation state, idempotency keys, deterministic resume, explicit cleanup of superseded partials. | LOCKED · ⚠️ **these safety properties exist only for `host-local`.** They have **no `dp-remote` equivalent** unless separately built — see open item 3. |
| **B/D15** | **UTC is canonical internally** — storage, transmission, tokens, telemetry, retention windows, audit. Local time is a display concern only. Cron schedules are declared in a chosen timezone but **evaluated in UTC**. **Ordering uses monotonic generation numbers, never wall-clock.** NTP required on CP and DP; the agent reports clock offset and the CP alerts on excessive skew. | LOCKED |
| **SM-D7** | **The `host-local` agent is two-role with mTLS identity and scoped authorization, and this is in scope — not deferred.** An actor that can restart processes and rewrite config on a customer host must be authenticated and authorized from day one; identity cannot be bolted on later. Only large-scale fleet ops (CP-orchestrated self-upgrade/canary, massive fleets) are deferred. | PROPOSED |

**Agent topology (D8 / B/D2 / B §3):** one Go binary, two roles — **control** (1 per Target/cluster, HA via leader election; *not* per node) and **telemetry** (scales with nodes; **DaemonSet, one collector per node — not per pod**). On a single host both roles run in one process.

## 9. v1 scope & sequencing

| ID | Decision | Status |
|---|---|---|
| **D17** | *First vertical slice* — original v1 was a Docker-only thin slice with a hand-authored descriptor. **(Not to be confused with B/D17, editions — see §1.)** | **SUPERSEDED** by B/D5 |
| **B/D5** | **All three runtimes in v1** (Docker + VM/systemd + K8s), build order Docker → systemd → K8s. **Cache is the first *output* of the generator**, not a bespoke build. **Hard gate:** a thin **walking skeleton** — one generated screen, one graph, one role, one Docker `service` deploy with health + telemetry, accepted **end-to-end** — must pass **before any** systemd/K8s/scheduled/File-Mesh work begins. In-scope ≠ built-simultaneously. | LOCKED |
| **SM-D-era v1** | B/D5 **stands in full**, which puts **both loci** in v1 — `host-local` and its SM-D7 identity machinery are v1 scope, not a later increment, because `direct-poll` cannot reach K8s pods or off-host Docker. Walking skeleton runs on **Docker + `dp-remote`** (cheapest locus, no CA/enrollment yet), then `host-local` → systemd → Kubernetes → `scheduled`. | PROPOSED |

**Phasing:** v1 **Cache** → phase 2 **File Mesh** (broker event collector, custom-UI escape hatch, scoped multi-tenant RBAC, FM backend port) → phase 3 **DB MI** (proves MI-family reuse of the Solution Package contract).

---

## 10. Corrections from independent review (2026-08-10)

An independent review found seven blocking defects. Each was **verified against the actual Cache and File Mesh source
before being accepted** — see `docs/tasks/codex-review-triage.md` for the verification table. The decisions below
record the corrections. All are **PROPOSED** and ratify together with the rest of the `SM-D*` family.

### SM-D20 — Config schema comes from per-solution **source adapters**, not build-generated metadata. **PROPOSED (proposes superseding D3)**

**D3's premise is false for the v1 target.** Cache has **no `spring-boot-configuration-processor`** and **no
generated `spring-configuration-metadata.json`** (verified: `solace-cache-engine/pom.xml`, and no metadata file
anywhere in the tree). Adding the processor is a build change, which **`HC1` forbids**. So "schema generated from the
solution's own build" has no input and cannot be implemented.

Replace with a **declared source adapter per solution**:

| Solution | Schema source |
|---|---|
| **Cache** | a **curated schema contract**, verified by a **bidirectional completeness test** (below). |
| **File Mesh** | **`FM_CONFIGURATION_TEMPLATE`** plus its value tables (`file-mesh-ui/database/file-mesh-schema.sql:3531`). |
| Future MI | declare an adapter; build-generated metadata remains *allowed* where a solution happens to produce it. |

**`FM_CONFIGURATION_TEMPLATE` is a legacy adapter input — NOT the reference model for the registry.** ⚠️ Rev 3
overstated it. Its shape is genuinely instructive and the architecture was wrong to omit it, but it is **not a working
implementation of version-aware rendering**:

```
UI_TYPE · UI_TYPE_META · UI_DATA_SOURCE          → the schema-driven renderer contract
SECTION · SECTION_ORDER · SUB_SECTION · SECTION_FIELD_ORDER   → layout and grouping
MIN_VERSION · MAX_VERSION                        → version-aware rendering
CONFIG_FILE_RELATIVE_PATH · DEFAULT_VALUE · CONFIGURATION_PATH
```

- ⚠️ **`MIN_VERSION`/`MAX_VERSION` are not used.** The production query `fetchTemplate` filters only on
  `COMPONENT_TYPE` and `FILE_PROCESS_CONFIGURATION` with **no version predicate**
  (`file-mesh-services/src/database/queries/index.ts`), and the backend's `IConfigurationTemplate` interface **omits
  both fields entirely** (`file-mesh-services/src/types/IConfigurationTemplate.ts`). The columns exist; nothing reads
  them. Rev 3's claim that they "solve version-aware rendering" was unsupported.
- `UI_TYPE` + `UI_TYPE_META` **are** a real, proven renderer contract and worth studying.
- The table is **denormalized and not semver-filtered** — `MIN_VERSION`/`MAX_VERSION` are `varchar(20)` with no
  ordering semantics.

**So:** use it as **File Mesh's schema source** and as *evidence of what a renderer contract needs*. Design the
registry properly — normalized, semver-aware, version-filtered — and do **not** treat this table as the model to
copy.

**⚠️ Correction (round 2): "launch it and see if it accepts the fields" cannot detect drift.**
`CacheEngineProperties` is annotated `@ConfigurationProperties(prefix = "cache-engine")` with no
`ignoreUnknownFields = false` (`solace-cache-engine/.../CacheEngineProperties.java:10`), and **Spring ignores unknown
properties by default**. So a property *deleted* from Cache still "passes" when SM sends it, and a property *added* to
Cache is never exercised at all. The test proved nothing in either direction.

**The anti-drift mechanism is a bidirectional key-set contract, not a launch smoke test:**

| Check | Detects |
|---|---|
| **Curated keys ⊆ artifact keys** — every curated key must bind to a real field, asserted by reflecting over the `@ConfigurationProperties` type graph (or by asserting a *typed* bind failure, not silent acceptance) | a property **removed or renamed** upstream |
| **Artifact keys ⊆ curated keys** — every bindable field must appear in the curated schema, or be explicitly listed as intentionally unmanaged | a property **added** upstream that SM cannot configure |
| **Semantic binding tests** — for each field: a valid value binds to the expected typed result, and an invalid value is *rejected* | type/format drift that key comparison alone misses |
| **Version pinning** — the contract is asserted against the **declared digest** (SM-D21/SM-D26 identity rules), not "latest" | the contract silently passing against a different build |

Run in CI against the real artifact. A failure blocks publishing that package version.

**Why:** the objective ("onboarding a solution needs no new console code") survives; only the *source* of the schema
changes. But the substitute for generation has to be a real equivalence check — a smoke test that cannot fail is not
an anti-drift mechanism.

### SM-D21 — The config path is **per-artifact and per-runtime, discovered by pre-flight** — not a generic launch variable. **PROPOSED (revises SM-D3, SM-D19)**

SM-D19 specified `launchVar: SPRING_CONFIG_ADDITIONAL_LOCATION` with a path of SM's choosing. **That fails on Cache**:
its image passes `--spring.config.additional-location=/app/external/spring/config/` on the **command line**
(`solace-cache-engine/Dockerfile:12`), and command-line properties outrank environment variables. SM would write
`/etc/sm/…` while Cache kept reading `/app/external/…`. This is precisely the precedence trap `architecture.md` §6.1
documents — and the design walked into it.

- The Solution Package declares the **config path per artifact and runtime**, not a variable for SM to set.
- **Enrollment pre-flight inspects the actual launch command** (`ENTRYPOINT`/`CMD`, unit file, pod spec) and resolves
  the effective config location. **Mismatch blocks enrollment** rather than degrading silently.
- Where the artifact already has an external location, **SM writes there** — no launch-spec change at all.

**⚠️ Correction (round 2): the launch-spec change for Cache is NOT zero.** The image does `RUN mkdir -p
/app/external/spring/config` and declares **no `VOLUME`** (`solace-cache-engine/Dockerfile:6`), so that path lives in
the container layer — **SM's config is destroyed when the container is replaced**. Discovering the right *path* is
necessary but not sufficient.

- The package declares a **persistent attachment per runtime**: bind mount or named volume (Docker), the
  generation-named ConfigMap mount (Kubernetes), a real host directory (systemd).
- **Pre-flight runs per binding before every apply**, not once at Target enrollment. Launch command, mount and
  competing-source set can all change between applies; a one-time check certifies a state that has since expired.
- No systemd or Kubernetes launch artifacts exist in the Cache repo, so those paths are **undeclared, not zero-cost** —
  they must be produced with the customer.

### SM-D22 — Effective-config verification is **bounded**; drift detection never relies on `configprops` hashing. **PROPOSED (revises SM-D3)**

`architecture.md` §6.1 proposed hashing `/actuator/configprops`. **Both branches fail:**
- **Sanitized** (the default) — values render as `******`, so two materially different configurations hash
  identically. A changed password or cache size reads as "in sync".
- **Unsanitized** — Cache's config carries `vpn`/`username`/`password`
  (`solace-cache-engine/.../CacheEngineProperties.java:31-33`) and Cache has **no Spring Security at all**, so this
  would expose broker credentials on an unauthenticated endpoint — breaching invariant **`I6`**.

Adding a protected management path is MI development, forbidden by `HC1`. So this is **not fixable; it is bounded**:

| Layer | Verification |
|---|---|
| The file SM wrote | hash of SM's rendered content — proves SM's write landed, nothing more |
| Competing property sources | **launch-source pre-flight** (SM-D21), re-run on demand |
| Effective values | only for a declared **non-secret allowlist**, and only where the solution exposes a **protected** actuator |
| Everything else | reported as **`effective-unverified`** in the UI — an honest state, not a silent pass |

**Never read unsanitized `configprops`.**

**⚠️ Correction (round 2): the limitation was stated and then ignored downstream.** Restart states, `PENDING_RESTART`
clearing and divergence remediation all still used an "effective-config hash" as convergence evidence. **Cache cannot
produce one** — it exposes `health,info,metrics` only
(`solace-cache-engine/src/main/resources/application.yml:10`). Five distinct states are now defined in
`architecture.md` §6.1: **desired → rendered → persisted → restart-observed → effective-verified**. Every downstream
gate uses **`restart-observed`**; `effective-verified` is an extra badge where achievable. **Cache is permanently
`effective-unverified`**, and the UI must never present `restart-observed` as proof of convergence.

### SM-D23 — Acquisition, config mechanism, selectors and lifecycle live on an **Instance↔Target binding**, not on the Target. **PROPOSED (revises SM-D12)**

SM-D12 put `infra_telemetry_source` and `config_mechanism` on the Target. They are properties of **a solution running
on a target**. One Kubernetes cluster hosting **Cache** (HTTP poll, one config path) and **File Mesh** (broker events,
a different path) **cannot be expressed** without duplicating the Target and its credentials.

```
Target   { location, driver, tenant/trust domain, control_locus, ownership_scope }
Binding  { instance, target, infra_telemetry_source, config_mechanism,
           render_outputs[], runtime_attachments[],   # MANY, and distinct concepts
           selectors, lifecycle_owner, restart_strategy }
```

`ownership_scope` is **new and was missing**: D10 requires `target.use` authorization on a Target, which needs an
owner to authorize against.

**⚠️ Correction (round 2): cardinality and attachments.**
- **A binding has *many* config attachments, not one `config_path`** — and **render output is not the same thing as a
  runtime attachment.** ⚠️ Rev 3 cited `/projConfig/application.yml` and `/src/main/resources/application.yml` as
  evidence of File Mesh runtime mounts. **That inference was wrong.** Those are the File Mesh *manager's own*
  generation paths: it reads versioned template YAMLs from
  `process.cwd()/artifacts/connectorType/<type>/<version>/Config/` to seed its UI, and stores
  `CONFIG_FILE_RELATIVE_PATH` as generator metadata
  (`file-mesh-ui/file-mesh-services/src/controllers/controller-cd-defaultDataFlowConfiguration.ts`). They say nothing
  about how a *deployed* MI externalizes configuration. Two separate concepts:

  | Field | Meaning |
  |---|---|
  | `render_outputs[]` | which logical config documents SM renders — `{ name, render_source }` |
  | `runtime_attachments[]` | where each rendered document must land **in the running artifact** — `{ path, runtime, persistence }`, **discovered by pre-flight against the real launch spec** |

  **File Mesh's runtime attachment is currently UNKNOWN** and is an explicit discovery task for the File Mesh adapter.
  Do not assume it mirrors the template layout.
- **Cardinality is explicit:** one Instance has **one active binding per Target**; an Instance may be bound to several
  Targets (multi-site); a Target hosts many bindings from different solutions. **A binding is immutable: re-targeting creates a new `binding_id` and retires the old one.** Historical bindings are
  retained, and because `binding_id` is part of series identity, telemetry can never be retroactively reattributed or
  blended across placements (SM-D26).
- **Authorization invariant:** a binding requires `instance.bindTarget` on the Instance's ownership scope **and**
  `target.use` on the Target's `ownership_scope`. Both, always — this is D10's two-sided rule, which had no anchor
  before `ownership_scope` existed.
- **Propagation:** telemetry acquisition is described as **per-binding** everywhere (`architecture.md` §10, §12), not
  per-Target. `lifecycle_owner` lives on the binding only — rev 2 left it on both the binding and the Instance.

**Why:** cardinality follows reality. A Target is a place; how a given solution is configured and observed there is a
property of the pairing.

### SM-D24 — The agent terminates at the **Data Plane**; the Control Plane issues authorization the DP validates. **PROPOSED (revises B/D16 for this architecture)**

**B/D16 defines agent↔CP** enrollment, CA, lease and desired-state delivery. But **SM-D1 makes the DP the owner of
configuration and generations**, and after the SaaS split the CP holds no config — so the CP cannot deliver
generations, and having it renew agent authority puts the session on the wrong side of the boundary.

- **Operational channel is agent ↔ DP.** Generations, report-actual and telemetry terminate at the DP.
- **The CP issues authorization; the DP validates it** — this is exactly SM-D1's token-audience seam, now
  load-bearing rather than forward-looking.
- **Ownership assigned explicitly:** CA and enrollment issuance = CP; lease renewal and revocation enforcement = DP
  against CP-issued authority; generation state = DP.
- **B/D16's safety properties (monotonic generations, idempotency keys, lease-bounded authority, active revocation,
  crash-safe apply) are retained and re-homed**, not discarded.
- **The `.proto` does not exist.** The contract index previously said "specified"; it now reads **"concept locked,
  protocol unspecified"** until the wire format and state machine are written.

### SM-D25 — Kubernetes config apply uses **generation-named immutable ConfigMaps**, with a single declared writer. **PROPOSED (revises SM-D3 §6.2)**

`ignoreDifferences` is **not sufficient**. Verified against Argo CD documentation: it affects only diff computation —
*"during the sync stage, the desired state is applied as-is."* `RespectIgnoreDifferences=true` is required, **and even
then applies only when the resource already exists**, so first creation still overwrites.

- **Immutability is the mechanism, not an obstacle.** Rev 1 listed "immutable ConfigMaps cannot be patched" as a
  constraint. Instead: create a **new generation-named immutable ConfigMap** per config version and update the
  workload's reference. This also gives **deterministic rollback**, which mutable ConfigMaps cannot, and avoids
  asynchronous mount-propagation entirely.
- **Writer ownership is declared per resource, per binding — not per Target.** After SM-D23 a Target hosts several
  bindings, which may be governed differently: SM may own the ConfigMap for one solution while GitOps owns everything
  for another. The declaration is `{ binding, resource_kind, writer: sm | gitops }`, and SM refuses to write any
  resource it is not the declared writer of.
- Where SM is the writer, the customer's reconciler must exclude **both** the ConfigMap **and** the workload's config
  reference — `ignoreDifferences` alone does not prevent overwrite on sync, and `RespectIgnoreDifferences` is
  ineffective on first creation. **Argo/Flux ownership remains an unresolved customer question** (open item).
- **ConfigMap garbage collection:** retain the last N generations for rollback, then delete oldest-first, never
  deleting a ConfigMap still referenced by a live or paused workload.
- **Deployment and StatefulSet rollout semantics are tested separately.** `subPath` mounts and `envFrom` never
  receive updates and are unsupported for SM-managed config.

### SM-D26 — Storage corrections: pin PostgreSQL ≥ 14; tenant in facts; cascading counter correction; coherent retention. **PROPOSED (revises SM-D8)**

| Defect | Correction |
|---|---|
| `DETACH PARTITION … CONCURRENTLY` was mandated but is **PG 14+**, while the reference deployment runs **postgres:13** (`file-mesh-ui/docker-compose.yml:7`) | **Pin PostgreSQL ≥ 14** as a platform prerequisite. Verified against the PG 14.0 release notes. |
| `metrics_raw` carried **no `tenant_id`**, contradicting the seam "on every table in both schemas"; the archive copied a whole partition while claiming tenant-partitioned archives | **Carry tenant and immutable placement identity in the fact row**; composite tenant foreign keys; **one archive manifest per tenant and range**, verified per tenant. |
| `(series_id, ts)` cannot separate two business events sharing a timestamp | **Store events separately from metrics**, keyed on the **source message/event ID**. Metrics keep `(series_id, ts)`. |
| Counter `delta` includes carry-over `first − prev.last`, so a late sample invalidates the next bucket and every tier above — but correction reprocessed only "touched" buckets | **Cascade invalidation**: the touched bucket, its successor, and all affected upper-tier buckets. Tests required for multiple resets, boundary resets, gaps and out-of-order arrival. *(The alternative — delta as the sum of within-bucket positive increments — removes the cascade but loses the between-bucket increment; recorded as a trade, not silently adopted.)* |
| Retention was simultaneously "6 months minimum", "gated on sizing" and "not guaranteed" | Six months is an **enforced capacity prerequisite**: if the sizing model does not support it, the deployment is not conformant and the shortfall is declared. **Not** a soft target. |
| The ~100 bytes/row estimate excluded overheads | Sizing must include **WAL, bloat, backup and rollup workspace**, and be **benchmarked** at expected ingest/query/export concurrency before SM-D8 is ratified. |

**⚠️ Correction of this correction (round 2): the DDL as first written could not be created.**

| Defect | Fix |
|---|---|
| `events_raw` was monthly-partitioned with `PRIMARY KEY (tenant_id, source_event_id)` — **a partitioned unique/primary constraint must include every partition-key column**, and `ts` was missing. §8.2's own indexing note already stated this rule. | `PRIMARY KEY (tenant_id, ts, source_event_id)` — time-local de-duplication. Suppressing a *re-published* event carrying a different timestamp needs a **separate bounded ledger** keyed on `source_event_id`; a global unique index across partitions is impossible. Whether that ledger is needed is a backend sub-spec question. |
| `metrics_raw` declared `FOREIGN KEY (tenant_id, series_id)` with **no matching unique constraint** on `series` | `series` gains `PRIMARY KEY (tenant_id, series_id)` |
| The decision promised "immutable placement identity in facts" and the schema **did not store it** — `target_id` sat on the mutable `series` row, so a rebind would **retroactively reattribute history** | **round 4:** bindings are **immutable** — a rebind creates a new `binding_id`, which is part of series identity. `placement_epoch` is **deleted**; it only existed to compensate for mutable bindings, and left raw/rollup keys able to collide. |
| Series identity had **no replica/source discriminator**, so replicas of one instance collapsed into one series and collided on `(tenant_id, series_id, ts)` | `series.source_key`, contract-defined per solution (pod name, host, container id), included in the series uniqueness constraint |

**The storage design is not ratifiable as prose.** It must be expressed as **executable migration DDL with constraint
tests** — "cannot be created" is a class of defect that only a migration run catches, and it has now occurred twice.

### SM-D27 — Durability is a **declared, verified per-signal property**; broker delivery is not durable by assumption. **PROPOSED (revises SM-D4)**

Two overstatements corrected:

1. **"Business metrics always ride the broker" is conditional, not absolute.** `DECISIONS.md` stated it absolutely
   while `architecture.md` said "where the MI already does so". **Cache has no broker business metrics at all** — its
   KPIs are HTTP-polled. The conditional form is correct.
2. **"The broker path is durable" is not unconditional.** File Mesh exposes `SOLACE_MESSAGING_MODE` with
   `DIRECT`/`PERSISTENT`/`NON-PERSISTENT` (`file-mesh-ui/database/file-mesh-schema.sql:4362`). *Its default is
   `PERSISTENT`*, so the honest statement is **"durable by default, configurable to non-durable"** — not "not
   durable", and not "durable".

**⚠️ Correction (round 2): conditional wording is not a mechanism.** Rev 2 added the caveat and no contract, and the
contract index still called telemetry "specified". Publication mode alone does not establish durability — endpoint
type, spool quota, acknowledgement and replay all matter, and `PERSISTENT` publication to a **non-durable endpoint** is
still lossy.

⚠️ **Rev 3's predicate was both over-restrictive and insufficient**, which is worse than either alone:

- **Over-restrictive:** it accepted only `durable-queue`. **Solace durable *topic* endpoints also retain Guaranteed
  messages**, so a perfectly sound topology was rejected. It also *required* `replay: supported` — but Message Replay is
  a separately configured broker feature, and ordinary queue **redelivery** does not need it. Durability and
  replayability are different properties.
- **Insufficient:** it accepted `auto-ack`, omitted publisher NACK handling, omitted discard policy, omitted
  transactional de-duplication, treated `producer-declared` as verification, had no evidence freshness, and modelled
  `evidence` as a **scalar** while the predicate needed three values simultaneously.

**Two independent properties, verified separately:**

```yaml
durability:
  claim: guaranteed | best-effort
  producer:                              # only the MI can attest
    delivery: persistent | direct | non-persistent
    publish_confirmation: awaited | fire-and-forget    # publisher ACK/NACK handled?
  broker:                                # SEMP-inspectable
    endpoint_kind: queue | topic-endpoint          # BOTH can be durable
    endpoint_durable: true | false                 # the property that matters
    spool_quota_mb: <int>
    quota_headroom_verified: true | false
    discard_policy: reject-to-sender | discard     # silent discard defeats the claim
    replay_log: enabled | disabled                 # SEPARATE from durability
  consumer:                              # our collector
    acknowledgement: after-commit | auto-ack | none
    commit_idempotent: true | false                # dedupe identity present?
    redelivery_handling: retry-bounded | none
  evidence:                              # a SET, each with a timestamp
    - { kind: producer-declared | broker-inspected | consumer-tested, at: <utc> }
```

| State | Requires |
|---|---|
| **`guaranteed-delivery-verified`** | `claim: guaranteed` · producer `persistent` + `publish_confirmation: awaited` · broker `endpoint_durable: true` (**queue *or* topic endpoint**) + `quota_headroom_verified` + `discard_policy: reject-to-sender` · consumer `acknowledgement: after-commit` + `commit_idempotent: true` · evidence contains **`broker-inspected`** and **`consumer-tested`**, each **fresher than the declared TTL** |
| **`replay-recoverable`** | an **independent** flag: `replay_log: enabled` and a tested replay procedure. Orthogonal to the above — a signal can be guaranteed but not replayable, or replayable but best-effort. |
| **`guaranteed-unverified`** | claims guaranteed; any attestation missing, stale, or `producer-declared` only |
| **`best-effort`** | anything else — including `delivery: direct`, `acknowledgement: auto-ack`, or `discard_policy: discard` |

Five rules rev 3 got wrong or missed:

1. **`producer-declared` is not verification.** It records what the solution claims. Only `broker-inspected` and
   `consumer-tested` are evidence, and both are required.
2. **`auto-ack` can never be verified.** Acking on receipt loses any event whose commit fails afterwards.
3. **Ack-after-commit alone is not enough — the commit must be idempotent.** A consumer that commits and then crashes
   *before* acking will be redelivered and, without a dedupe identity, **persist a duplicate**. This is why event
   identity (§8.2) and durability are the same conversation.
4. **Silent discard defeats the claim.** An endpoint that discards on quota overrun without `reject-to-sender` loses
   data while every other field still looks correct.
5. **Durability ≠ no gaps, and ≠ replayable.** Gap and freshness detection are retained on **every** path. What
   durability buys is recovery after *consumer* downtime; it does not survive quota overrun, an operator purge, or an
   unreplayable window.

Until broker inspection and a consumer test exist, **every broker signal is at best `guaranteed-unverified`** and
renders with a caveat badge.

---

### SM-D28 — Agent authority is a **short CP-issued lease the DP renews**; reconciliation of *cached* state survives a CP outage **until the lease expires**. **PROPOSED (NEW — completes SM-D24)**

SM-D24 moved the agent's session to the DP but left the authority model undefined. The two obvious readings have
opposite failure modes: renewing from cached CP authority lets **revocation go unseen indefinitely** — unacceptable for
an actor that restarts production processes — while contacting the CP on every renewal means **a CP outage halts all
reconciliation**, destroying the resilience the pull model exists for.

**Decided: a short CP-issued lease, renewed by the DP against the CP.** ⚠️ *Rev 3's title claimed a CP outage "never
stops reconciliation." That was wrong and is corrected here: authority **does** expire during a sufficiently long
outage. Bounded, not unlimited, is the whole point.*

### Roles

| Role | Owner |
|---|---|
| CA, certificate issuance, enrollment approval, revocation epoch | **CP** |
| Agent identity validation, lease holding/renewal, generation delivery, report-actual | **DP** |
| Lease **issuance** | **CP** (the DP requests; it never mints) |

### Lease claims (the security contract, not an implementation detail)

```
lease {
  agent_id, target_id            # bound to ONE target — an agent cannot act on another's
  binding_ids[]                  # exactly the bindings this agent may apply
  revocation_epoch               # CP's current epoch; agent refuses lower
  issued_at, expires_at          # UTC, absolute — never a duration the agent computes
  audience: <dp-instance-id>     # a DP-scoped token; NOT replayable at another DP or at the CP
  nonce / jti                    # single-use at renewal; replay is rejected
  capabilities[]                 # apply-config | lifecycle | report-only
}
```

**Duration: 1 hour lease, renewal attempted at 50% elapsed, hard expiry at `expires_at`.** Chosen so a routine CP
restart or deploy is invisible, while a real outage bounds unrevoked authority to at most an hour.

### Partition state machine

| State | Trigger | Agent may | Notes |
|---|---|---|---|
| `ACTIVE` | lease valid, renewal succeeding | apply new generations · lifecycle · report | normal |
| `RENEW_DEGRADED` | renewal failing, lease not yet expired | **reconcile cached desired state**; **accept no new generation**; report | the resilience window; UI shows the Target as degraded with time-to-expiry |
| `LEASE_EXPIRED` | past `expires_at` | **stop reconciling.** Report-only, using a **separate report credential** that carries no apply capability | so an expired agent is still *observable* — silence and no-authority must be distinguishable |
| `REVOKED` | in-band `Revoke`, or epoch increment observed | stop immediately; discard cached desired state | takes effect on the open stream within seconds |

**Expiry must bound work, not just forbid new work.** ⚠️ Rev 3 said the in-flight component "completes" with no
deadline — so a restart, a health gate, a remote script or a blocked filesystem call could run **indefinitely** after
authority expired. A compromised agent could start a long lifecycle operation seconds before expiry and keep acting.
Three rules close it:

1. **Admission control, bounded by a *platform* ceiling — not a package declaration.** An apply step may only start if
   `expires_at − now ≥ verb_ceiling + margin`, where `verb_ceiling` is a **platform-configured maximum per lifecycle
   verb** (write-config, restart, health-gate). ⚠️ Round 4 said `max_step_duration` was "declared per step in the
   package" — but that field exists nowhere in the Solution Package, and §9 states in its own words that warm-up
   duration is something SM "cannot know and cannot require anyone to declare". A platform ceiling needs no MI
   cooperation.
   **Validated invariant:** `lease_duration > max(verb_ceiling) + margin`. Without it a step can never be admitted and
   the generation defers forever. A refused admission enters **`ADMISSION_BLOCKED`** with the reason surfaced — round 4
   deferred silently, with `ACTIVE` still showing healthy.
2. **Every step carries an absolute deadline** (`deadline = min(step_start + max_step_duration, expires_at)`), enforced
   by the agent — process kill, SSH channel close, watch cancel. There is no unbounded wait anywhere in the apply path.
3. **After expiry the agent may only perform *local, atomic* cleanup and reporting** — remove a temp file, roll back a
   rename it already staged — using the **report credential** below. It may not restart a process, invoke a remote
   command, or write config.

**Report credential — observable is not the same as trusted.** A separate **capability-restricted** credential
(`capabilities: [report-only]`) issued at enrollment, so that an expired agent is still **observable**: silence and
loss-of-authority must be distinguishable from a dead host.

⚠️ Round 4 made it "longer-lived" with **no revocation path**, which inverted which capability is bounded. Apply
authority expired within the hour while the report credential kept running — and `restart-observed`, the single signal
that closes `RESTART_EXPECTED`, clears `PENDING_RESTART` and resolves `CONFIG_DIVERGED`, is **agent-reported**. A
compromised agent could therefore keep forging convergence evidence after revocation: the console would show
"configuration applied" for config never written. Corrected:

- The report credential carries the **same `revocation_epoch`** and is revoked on the same path.
- Reports from `LEASE_EXPIRED`, `REVOKED` or `CLOCK_SUSPECT` are **labelled untrusted in the store** and **may never
  satisfy a convergence gate** — they are displayed, not believed.

**Lease binding.** The lease is bound to the **agent's mTLS public key / session**, not to a textual `agent_id`. A
lease lifted from one agent cannot be presented by another even with the same claimed id.

**Revocation epoch is subject-scoped.** There are **two** epochs: a **per-agent** epoch (identified by the key
thumbprint in the lease, so revoking one agent revokes exactly one agent) and a **fleet** epoch used only for a
deliberate mass revocation. Rev 3 said "epoch" without saying which — a single global epoch would have revoked the
whole fleet on one compromise.

**Recovery transitions** (rev 3 defined only the degradation path):

| From | To | Trigger |
|---|---|---|
| `RENEW_DEGRADED` | `ACTIVE` | renewal succeeds, epoch still valid |
| `LEASE_EXPIRED` | `ACTIVE` | renewal succeeds; agent re-reads desired state before applying anything |
| `LEASE_EXPIRED` | `RE_ENROLL_REQUIRED` | certificate expired, or CP rejects renewal as unknown/revoked identity |
| `REVOKED` | `RE_ENROLL_REQUIRED` | operator re-approves; **cached desired state is discarded first** |
| any | `CLOCK_SUSPECT` | local clock outside the CP's stated tolerance |
| `CLOCK_SUSPECT` | `ACTIVE` | offset back within tolerance for N consecutive **DP-provided** timestamps (the DP's lease-response time is the reference clock, never the agent's own) |
| `RE_ENROLL_REQUIRED` | `ACTIVE` | enrollment completes and a fresh lease is issued |
| any | `ADMISSION_BLOCKED` | not enough lease remaining for the next step's `verb_ceiling`; recovers on renewal |

⚠️ Round 4 left `CLOCK_SUSPECT` and `RE_ENROLL_REQUIRED` as **absorbing states** — an NTP hiccup would have parked an
agent permanently non-reconciling with no path back and no stated reference clock. **The state machine now terminates
and every state has an exit.**

**Clock skew.** `expires_at` is absolute UTC. The agent compares against its own clock and **reports its offset**; if
the offset exceeds tolerance it enters `CLOCK_SUSPECT` and **treats the lease as expired** — failing closed, because a
skewed clock is exactly how a lease gets honoured past its end.

**Enrollment routing:** the agent enrolls **through the DP to the CP** (agent → DP → CP), so the agent never needs a
CP route — preserving the on-prem posture where only the DP is reachable from the runtime. The CP signs; the DP relays
and caches the trust bundle.

**Replay prevention:** DP-scoped `audience` plus a single-use `nonce` at renewal. A lease captured from one DP is
useless at another and cannot be replayed at the CP.

**Trust-bundle rotation:** the CP publishes the bundle with overlapping validity; the DP serves both old and new during
the overlap; agents accept either. Rotation therefore never requires a synchronized restart.

**Revocation propagation:** the CP increments a monotonic `revocation_epoch`. The DP learns it at its next CP contact
and refuses any lease below it; agents receive it in every lease and in-band. Worst-case propagation is one lease
period; typical is seconds.

**Remaining for the agent sub-spec** — mechanism only: the wire format (`.proto`), exact renewal backoff/jitter, the
persisted-state file layout, and the DP-side lease store. `B/D16`'s monotonic generations, idempotency keys and
crash-safe apply carry over unchanged.

**Why:** it is the only split where both failure modes are bounded — revocation lands within one lease period, and a CP
outage costs *new* authority rather than the running loop. It also makes SM-D1's token-audience seam load-bearing now,
which is the right time to get it right.

---

## Deferred (recorded, not built)

- **SaaS operation** — CP/DP physical split, cross-plane sync, multi-tenant operation. Seams protected now.
- **Per-tenant Keycloak SSO brokering** (v1 = local auth / single realm).
- **Large-scale fleet ops** — CP-orchestrated agent self-upgrade/canary, massive agent fleets. *(Agent identity/mTLS/authz is NOT deferred — SM-D7.)*
- **SM-driven artifact deployment** — registry, digest-pinning, per-artifact cosign, offline air-gap bundle tooling, image-pull credentials. **Component-level versioning and the solution BOM (B/D9) defer with it.**
- **Timescale / VictoriaMetrics** — ingest interface preserved. Deferred on operational grounds; for Timescale the TSL licensing bill is an additional cost. Revisit is triggered by the SM-D8 raw-tier ceiling being exceeded, not by preference.
- **Histograms / summaries and quantile-preserving rollups.**
- **Config hot-reload** via `/actuator/refresh` + `@RefreshScope` — requires MI development (HC1) unless a solution already supports it.
- **Custom-UI module plugin sandboxing / versioning / supply-chain.**
- **Dual-collector telemetry redundancy** (B/D2's Detect + Recover stands).
- **Full DAG component ordering; pre/post-deploy lifecycle hooks** (v1 uses an ordered list with health gates).
- **HTTP/1.1 fallback transport; auto cert rotation** (h2 egress is a documented v1 install prerequisite).
- **`solace_event` broker ACL / event replay** design (phase 2, with File Mesh).

## Hardening backlog (from the File Mesh backend audit)

Not all v1-blocking, but none may be inherited into `platform-core`:

1. **Unauthenticated `/fm/cronjob` (incl. data purge) and `/fm/actuator`** — secure with auth + permit.
2. **Inconsistent `permit()` / role checks** — replace with centralized deny-by-default (D10).
3. **Leaky org isolation** — a client-supplied `orgName` is trusted in places. Verify scope every request; add a true `tenant_id` boundary (HC2).
4. **String-concatenated SQL** — move to parameterized SQL in a central data-access module.
5. **No migration framework** — schema is a single ~1.1 MB init `.sql`. Introduce Flyway/Liquibase before any schema evolution.
6. **Metrics tables** lack a `TIMESTAMP` index and partitioning; purge is a manual unauthenticated cron endpoint writing **unencrypted CSV**. Superseded in design by SM-D8, whose archive path exists specifically to not repeat this.
7. **Multi-org, not multi-tenant** — add the tenant boundary the current schema lacks.

---

### SM-D29 — Storage portability boundary: `sm_control` stays portable-capable, `sm_data` is PostgreSQL-only. **PROPOSED (NEW)**

The team asked for multi-database support, reasoning that a query builder would provide it. A query builder abstracts
**SQL dialect for CRUD**; it does not abstract what the telemetry schema actually rests on. Every one of these is
PostgreSQL-specific with no portable equivalent:

| Dependency | Used by | Portable? |
|---|---|---|
| Declarative **RANGE partitioning** on `ts` | `metrics_raw`, `events_raw`, rollups | different model in Oracle/MySQL/SQL Server |
| `ALTER TABLE … DETACH PARTITION … CONCURRENTLY` | retention/archive without an `ACCESS EXCLUSIVE` stall | **PostgreSQL 14+ only.** No equivalent |
| `COPY … TO STDOUT` | streaming archive + raw export, no superuser, no server-side file write | dialect-specific bulk paths, different privilege models |
| **JSONB** + generated columns + expression indexes | `labels_jsonb`, `payload_jsonb`, promoted dimensions | JSON indexing/operators differ materially |
| `ON CONFLICT … DO UPDATE` | idempotent rollup upsert | `MERGE` / `ON DUPLICATE KEY`, different semantics |
| "a unique constraint must include the partition key" | the fact-table PKs — proven by `T10` in the constraint tests | rule does not exist elsewhere |

**Decided: the boundary is the plane, not the query layer.**

- **`sm_data`** (metrics, events, rollups, retention, archive) is **PostgreSQL ≥ 14 only, permanently.** Porting it
  means a second DDL, a second retention implementation and a second constraint-test suite — a storage adapter, not a
  dialect switch.
- **`sm_control`** (catalog, RBAC, entitlement, config-of-record, audit) is ordinary relational CRUD and is written to
  **stay portable-capable**: parameterized SQL, a thin query builder for dynamic cases, **no PostgreSQL-only
  constructs**. Nothing is ported now.
- **Start on PostgreSQL for both.** The `sm_control`/`sm_data` split already exists for the CP/DP seam (`SM-D1`, `I7`),
  so this costs nothing extra today and keeps the option open.

**What "multi-DB" can honestly be sold as:** *"the control plane runs on your database; telemetry requires PostgreSQL
≥ 14."* Not an unbounded promise.

**Consequence for the Oracle question** (open item 13): it is now scoped to **`sm_control` only**, and remains open.
Nothing in `docs/requirements/` currently asks for multi-DB — if a customer does, it needs a `REQ-*` with a source
before the control-plane portability constraint is treated as binding.

**Why:** the request was real, but the proposed mechanism could not have delivered it, and discovering that after the
telemetry layer was written would have been expensive.

---

### SM-D30 — The metric catalog is a **package baseline plus user extensions**, in two layers, with mandatory typing and a hard cardinality cap. **PROPOSED (NEW)**

`REQ-001` is "truly configurable", and that extends to telemetry: a user must be able to graph a metric the package
author did not anticipate — pulled by the agent or pushed to the broker.

**Two layers, never merged:**

| Layer | Owner | On package upgrade |
|---|---|---|
| **baseline** | the Solution Package's telemetry contract | **replaced wholesale** |
| **extension** | the operator, via the collector configuration | **never touched** |

Namespacing is what makes the upgrade safe. A user extension is stored and displayed as user-defined, so a package
upgrade can replace its own metrics without ever clobbering an operator's.

**Collision rule:** if a later package declares a metric name that already exists as a user extension, the **package
wins the name**; the user's is retained under a suffixed name and flagged for review. Never silently merged — their
`metric_type` or `unit` may differ, which would corrupt existing rollups.

**Three guards, all mandatory — this is the price of the flexibility:**

1. **`metric_type` and `unit` are required at declaration, not optional.** They cannot be inferred. `SM-D26` computes
   `delta` and reset-detection from `metric_type`, so a **counter mislabelled as a gauge produces a plausible-looking
   wrong graph** — the worst failure mode, because nothing errors. The UI must refuse to save without them.
2. **Empirical type check.** After N samples, SM compares observed behaviour to the declared type: a `counter` that
   decreases outside a detected reset, or a `gauge` that only ever rises, raises a **`TYPE_SUSPECT`** warning on the
   metric. It does not auto-correct — the operator declared it — but the graph carries the warning.
3. **Hard cardinality cap per instance**, plus a per-metric label-key allowlist. Series count is series × buckets in
   every rollup tier, so an unbounded user-added label is the classic time-series blow-up. **Breach rejects the new
   series and surfaces the rejection as an ingest metric** — never silent degradation.

**A user extension is second-class in exactly one way:** a package-declared graph may not depend on one, since the
package cannot assume it exists.

**Why:** the requested flexibility is real and the design should support it. But "users may add metrics" moves
`metric_type`, `unit` and cardinality from vendor-controlled to user-supplied, and each of those silently corrupts
something downstream if wrong. Layering plus the three guards is what makes it safe rather than merely possible.

---

### SM-D31 — Dashboards are **per-instance and shared**, with package defaults as a separate, upgradeable layer. **PROPOSED (NEW)**

Same layering principle as `SM-D30`, for the same reason.

| Layer | Source | On package upgrade |
|---|---|---|
| **default graphs** | the Solution Package | **replaced wholesale** — the vendor's view improves with the package |
| **user graphs** | the operator | **never touched** |

The rendered dashboard is the **union**. An operator may **hide** a default but not delete it — hiding is an override
stored in the user layer, so a later package can still improve that default and the operator's choice survives.

- **Scope: per instance, shared.** Everyone with view access on an instance sees the same graphs. This is an
  operations console: two people debugging the same incident must be looking at the same thing.
- **RBAC:** new verbs required — `dashboard.view` / `dashboard.edit` / `dashboard.reset`. `D10` has none today, and
  editing a shared dashboard is a higher-consequence action than viewing one.
- **v1 mark set: `line`, `gauge`, `single-stat`.** Deferred: heatmaps, stacked areas, multi-axis. Stated as a limit so
  the renderer contract is bounded.
- **Type-aware plotting is not optional.** A `counter` renders as a rate or delta; plotting the raw monotonic value is
  a defect, not a user preference.
- **Resolution stays automatic** — ≤7 days raw, 7–90 days hourly, >90 days daily, with a forced-Raw override. The user
  picks the metric and the mark, never the tier.

**Why:** per-user dashboards fragment an operations view and double the RBAC surface; per-solution templates cannot
express that one instance matters differently from another. Per-instance shared, with defaults layered beneath, is the
arrangement that upgrades cleanly and keeps a team looking at one picture.

---

## Ratification matrix

**One authoritative status table.** Round 2 found status claims scattered and inconsistent across documents; this is
the only place that decides. **Nothing in the `SM-D*` family is binding until the group ratifies the set.**

| Family | Status | Meaning for a developer |
|---|---|---|
| `D1`–`D17` (parent) | **LOCKED** | Binding today. Build against these. |
| `B/D1`–`B/D17` (Subsystem B) | **LOCKED** | Binding today. |
| `SM-D1`–`SM-D31` | **PROPOSED, ratify as a set** | **Not binding.** Where one conflicts with a locked decision, the locked decision wins. |

Locked decisions with a proposal against them — **all still binding**:

| Locked | Proposal | If ratified |
|---|---|---|
| `D2`, parent `§17` (outbound-only, no stored creds) | `SM-D12` | guarantee narrows to `host-local` only |
| `D3` (schema from the build) | `SM-D20` | replaced by per-solution source adapters |
| `D4`, `§7` (schema pinned to artifact version) | `SM-D2`, `SM-D14` | artifact leg drops; render against *observed* version |
| `D5` (agent normalizes at the edge) | `SM-D4`, `SM-D6` | DP normalizes for `direct-poll`/`federated` |
| `D6` (Node/TS control plane) | `SM-D18` | Java 17 / Spring Boot |
| `D11` (an instance is placed on **a** Target — singular) | `SM-D23` | an Instance may be bound to **several** Targets (multi-site); placement becomes part of series identity |
| `D14`, `§11` (storage) | `SM-D8`, `SM-D26` | same conclusion, corrected mechanism |
| `D15` (generalize-and-harden) | `SM-D18` | greenfield |
| `D16`, `B/D6` (artifact distribution) | `SM-D2` | retired |
| **Objective #4** (UI drives artifact deployment) | `SM-D2` | retired |
| `B/D3`, `B/D16` (agent is the actor; agent↔CP) | `SM-D12`, `SM-D24`, `SM-D28` | actor model changes for `dp-remote`; session homes at DP |
| `B/D4` (entitlement at API + artifact-pull) | `SM-D2`, `SM-D13` | declaration-only |
| `B/D9` (component BOM) | `SM-D19` | BOM unexpressible; defers with the artifact path |
| `B/D12` (rollback reverts config **and** artifact) | `SM-D2` | config-only rollback |
| `B/D14` (workload types `service` \| `scheduled`, agent-as-scheduler on Docker) | `SM-D12`, **round-5 scope cut** | DP-as-scheduler under `dp-remote`. **`scheduled` is CUT from v1**: it is structurally incompatible with SM-D28's one-hour lease (a restart window hours out cannot fit inside the authority that must admit it), and no v1 solution needs it — Cache runs as `service`. The *capability* remains locked; only its v1 inclusion is withdrawn. |

**Round-2 corrections** (`SM-D20`–`SM-D28`) are proposals *against other proposals* — they do not change any locked
status and take effect only when the set is ratified.

## Open items

**The baseline is UNBASELINED.** Prerequisites are listed **before** the ratify item, because ratifying first and then
doing the work is how a gate becomes decorative.

| # | Item | Kind |
|---|---|---|
| 1 | **Specify the `dp-remote` apply protocol** — generation ordering, apply id/idempotency, DP-persisted per-host in-flight state, bounded retry, `APPLY_INDETERMINATE`. **The walking skeleton runs on `dp-remote`, so v1's first slice has non-idempotent remote apply without this.** | **ratification blocker** |
| 2 | **`dp-remote` SSH security contract** (SM-D11a) — host-key pinning/CA + rotation, shell-free allowlisted commands, SFTP temp+rename, symlink checks, restricted sudoers, audit with host-key fingerprint. | **ratification blocker** |
| 3 | **Produce executable migration DDL + constraint tests** for metrics/events/rollups (SM-D26). Prose has failed three times; only a migration run catches "cannot be created", and only a lifecycle test catches a rebind-backfill collision. | **ratification blocker** |
| 4 | **Resolve the Kubernetes writer model** with customers (SM-D25) — SM as declared writer of a generation-named ConfigMap + workload reference, or GitOps PR integration. Kubernetes is v1. | **ratification blocker** |
| 5 | **Benchmark the physical metrics schema** at expected ingest/query/export concurrency, incl. WAL, bloat, backup and rollup workspace. | **blocks ratifying SM-D8** |
| 6 | **Write the agent↔DP `.proto` and state machine** (SM-D24/SM-D28 decide the authority model; the wire format does not exist). | blocks v1 agent work |
| 7 | **Design the schema registry against a real adapter contract** (SM-D20) — normalized, semver-aware, version-filtered. `FM_CONFIGURATION_TEMPLATE` is an input, **not** the model. | blocks backend + frontend sub-specs |
| 8 | **RATIFY `SM-D1`…`SM-D28` + `SM-D11a`** as a set, with the capability limits in `architecture.md` §13 explicitly accepted — in particular that outbound-only holds only for `host-local`, and that drift detection is bounded (SM-D22). Items 1–4 must close first. | the gate |
| 9 | **Commercial sign-off on the entitlement downgrade** — declaration-only enforcement, no detection of unentitled usage. Narrower than "cannot detect": observed-version + telemetry still covers *declared* instances; what is lost is **shadow-deployment** detection. | commercial |
| 10 | **Inspect the File Mesh SSH-exec implementation** for *targeting mechanics only* — its security posture is the anti-pattern (SM-D11a). | `dp-remote` VM transport |
| 11 | **Discover File Mesh's runtime config externalization** — the template paths are generation-time, not runtime mounts. | phase 2 |
| 12 | **Confirm File Mesh's metrics read path** (CSV files vs DB) with the original developer. | phase 2 |
| 13 | **Oracle support: keep or drop.** | backend sub-spec |
| 14 | **Solace Go API maturity** for the agent's `solace_event` collector — gates the `host-local` collector only. | agent sub-spec |
| 15 | **Cache `active_standby` makes `rolling-health-gated` unsafe, not merely unavailable.** Verified: `LeaderElection` binds an exclusive flow and starts/stops the ingestion consumer on `FLOW_ACTIVE`/`FLOW_INACTIVE`, exposing leader state **only in a log line** — no endpoint, no metric, no health contributor. A standby therefore reports `health: UP` while processing nothing, so a health gate passes a replica that took no traffic, and its per-replica KPIs read zero indistinguishably from a broken instance. SM cannot obtain leader state without MI development (`HC1`). **Outcome: health-gated rolling restart is UNAVAILABLE for Cache**, and §7.2 needs an `INACTIVE_REPLICA` state. `mustBeUniformAcrossReplicas` is genuinely forced true (shared management queue + namespace). | **blocks `rolling-health-gated` on Cache; blocks KPI interpretation** |
| 16 | **Contract publishing + read-only consumption** — required **before** the monorepo is split, not now. | the repo split |
| 17 | **Generate `traceability.md` and a decision index** rather than maintaining them by hand. | hygiene |
| 18 | **Legal review of the TimescaleDB position**, including the unexamined *distribution* premise. Only if revisited. | none currently |
| 19 | **Frontend component stack is unresolved** — LOCKED `D7` says React + Vite + **Material-UI** reusing File Mesh's stack; the (deleted) design handoff specified **Tailwind v4 + Radix** on the Solace Agent Mesh Design System, and **no in-repo visual reference remains**. Three documents previously claimed this was tracked as "open item 7"; it was tracked nowhere. Frontend is v1 and the config-driven renderer (REQ-001) cannot be specified without it. | **blocks the frontend sub-spec** |
| 20 | **Upgrade of SM itself** — procedure, migrations against large partitioned tables, CP↔DP version skew, rollback. | operability |
