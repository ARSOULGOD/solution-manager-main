# Solution Manager — Decision Register

**Last updated:** 2026-09-11 (`SM-D32`–`SM-D34`, the v1 scope model; open items 34–38)
**Companion:** [`architecture.md`](architecture.md) — the architecture this register governs. This file records *what was decided and why*; `architecture.md` records *how the system works*.

> **Consolidated 2026-08-10** from three predecessor specs (`2026-06-17` parent architecture, `2026-07-08` Subsystem B, `2026-08-04` on-prem-first revision rev 2). Those files are removed; their content lives here and in `architecture.md`.

---

## ID discipline (binding)

**Identifiers are immutable and were deliberately NOT renumbered during consolidation.** A citation like "per D8" or "per SM-D16" must never shift meaning. **Cite a family prefix, never a range** — a family grows by appending, and a range written into prose is stale the next time a decision is added. The families:

| Family | Origin | Meaning | Recorded in |
|---|---|---|---|
| `D*` (`D1`–`D17`, closed) | 2026-06-17 parent architecture | Platform-wide foundations | here |
| `B/D*` (`B/D1`–`B/D17`, closed) | 2026-07-08 Subsystem B | Deploy / agent / telemetry loop | here |
| `SM-D*` | on-prem-first revision + correction rounds 2–5. `SM-D11a` is an **insertion**, not a renumbering | On-prem-first revision | here |
| `UI-D*` | Subsystem A — dynamic UI | Solution Package, renderer, schema registry, quarantine | [`dynamic-ui/decisions.md`](dynamic-ui/decisions.md) |
| `DM-D*` | Dynamic Metrics | Query API, widgets, dashboards, chart contract | [`dynamic-metrics/decisions.md`](dynamic-metrics/decisions.md) |
| `FE-D*` | Frontend console | Console composition, state, client boundary | [`frontend/decisions.md`](frontend/decisions.md) |
| `AG-D*` | `host-local` agent | Enrollment, apply journal, lifecycle, leader election, buffering | [`go-agent/decisions.md`](go-agent/decisions.md) |
| `BE-D*` · `RT-D*` | per-component families | backend · SM packaging | `<component>/decisions.md`; `BE-D*` is active in [`backend/decisions.md`](backend/decisions.md), `RT-D*` remains reserved until written |
| `OR-*` | ⚠️ **dated owner rulings — NOT permanent decision ids** | cross-cutting | **this file**, *Owner architecture rulings*. They exist so a ruling can be cited **before** the register can safely allocate (**OR-11**), and each is **superseded by a permanent id on allocation** — an `OR-*` citation is expected to be rewritten then. ✅ **The policy is settled by ruling [OR-17](#owner-architecture-rulings--2026-09-16):** `OR-*` ids are **temporary**; permanent ids are allocated **only after plan 005 is merged and this file has been re-read**; and **dated approval documents stay provenance and must keep being cited, but do not replace a permanent central-register id**. ⛔ **Do not pre-allocate a `D*` number for a ruling.** Listed here because a prefix that appears in a `LOCKED` row and not in this table is exactly the drift `I3` prevents |

Only the **status** of every family is centralized — see the [ratification matrix](#ratification-matrix). The bodies of `UI-D*`, `DM-D*`, `FE-D*`, `AG-D*` and `BE-D*` live in their component folders; this file never restates them.

**To change a decision, add a new one that supersedes it** — never edit a decision's meaning in place. Record the supersession in both entries.

## Status legend

| Status | Meaning |
|---|---|
| **LOCKED** | **Ratified by the decision authority. Binding.** ⚠️ **Updated 2026-09-16:** this row read *"Ratified by the architect group"*, and ruling **OR-1** makes the **project owner** the final architecture authority. Rulings `OR-1`…`OR-15` and the `D10` amendment are `LOCKED` on the owner's authority; earlier LOCKED decisions were ratified by the architect group. **Either route yields `LOCKED`; neither is downgraded by the other.** |
| **PROPOSED** | Awaiting ratification by the decision authority — ⚠️ **the project owner (OR-1); this row said "architect-group"**. **Not binding.** Where a PROPOSED decision conflicts with a LOCKED one, the LOCKED one stands and the conflict must be flagged, not silently resolved. |
| **SUPERSEDED** | Replaced **by a ratified decision**; retained so citations resolve. Points at its replacement. |
| **RETIRED** | No longer applicable at all, **by ratified decision**. |
| **LOCKED — proposed supersession by SM-Dx** | Still **binding**. A proposal exists to replace it but has not been ratified. **Build against the locked decision, not the proposal.** |

⚠️ **A PROPOSED decision can never change another decision's status.** Only ratification can. Rev 1 of this register
violated that rule — proposed SM-D2 marked locked B/D6 and D16 `RETIRED`, and proposed SM-D4 "superseded" locked D5 —
which let two developers build different systems while both claiming compliance. Corrected below.

⚠️ **All `SM-D*` decisions are PROPOSED.** ⚠️ **Read with OR-1: the ratifying authority is the project owner.** Only two things from the revision have been confirmed: that SM is a config/governance plane rather than a deployment plane, and that `dp-remote` may hold host credentials. Everything else in that family awaits sign-off.

⚠️ **`UI-D*`, `DM-D*`, `FE-D*` and `AG-D*` are PROPOSED in full, and rest on the unratified `SM-D*` base.** Nothing in them is binding, and three of them supersede or amend something that outranks them — see the [ratification matrix](#ratification-matrix) for the required ordering.

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
| **D11** | ✅ **AMENDED 2026-09-16 by ruling [OR-16](#owner-architecture-rulings--2026-09-16), which names `D11` as §0 requires.** ⛔ **Read the amendment for the hierarchy; the original text below is retained unedited (`I3`) and is no longer the hierarchy of record.** **Under OR-16:** the canonical ownership **and grant** hierarchy is `Organization → Sub-organization → Environment → Solution → Instance` (**five grantable levels**, `solution_id` added to the RBAC scope schema); **Placement is an operational child/resource identity of Instance** — present in APIs, topics, events and runtime operations, ⛔ **never an independently grantable level**, and a placement-targeted operation authorizes through **the owning Instance scope** plus any required **Target** permission; `tenant_id` is an **internal storage key**, not an external level. ✅ **Untouched by the amendment:** the decoupling from placement and *"re-targeting is a rebind that leaves ownership, RBAC and config history intact"* — OR-16 keeps Placement out of the grant tree for precisely that reason. **Original text, retained:** *Ownership tree `Tenant → Organization → (Sub-org) → Environment → Solution Instance`, **decoupled from placement** (`Deployment Target`). An instance is owned by a tree node and placed on a Target; re-targeting is a rebind that leaves ownership, RBAC and config history intact.* | **LOCKED, hierarchy clause AMENDED by OR-16** |
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
| **D10** | ✅ **AMENDED and RATIFIED, 2026-09-16 — read this row *and* [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md), which is `LOCKED`.** ⛔ **Where the text below conflicts with that amendment, the amendment controls.** It supplies the **38-member** catalog (35 ratified + 3 added by its §1.7), fixes evaluation as **scope-evaluated with downward inheritance** and bidirectional *visibility*, adds the bootstrap / identity / session / CSRF / audit clauses and the one enforcement point, and defines **three built-in roles** (§1.8). ⚠️ **One clause below is narrowed in v1:** *role definitions (built-in **+ custom**)* — v1 ships those three built-in roles only, with the custom-role write path ungranted. The amendment carries a **dated identity, not a `D*` number** (ruling **OR-11**). The text below is preserved unedited (`I3`): **Azure-style RBAC** — permissions `resource.action` → role definitions (built-in + custom) → scoped assignments → inheritance. The **permission catalog is policy-as-code**; route-derivation is only a lint for unprotected endpoints, never the source of truth. Enforcement is a **single centralized deny-by-default check** every request passes. **Two-sided binding authorization:** deploying an instance to a Target requires `instance.bindTarget` on the instance's ownership scope **and** `target.use` on the Target. | LOCKED |
| **D13** | **Per-tenant BYO IdP via a Keycloak broker** — one identity story for SaaS and self-hosted. Login is tenant-aware; identities are tenant-scoped; IdP groups map to role assignments via OIDC claims and/or SCIM. *(Deferred out of v1 — v1 uses local auth.)* | LOCKED |
| **B/D4** | *(Confirmed 2026-08-11: entitlement is **binary per solution + version range**. A licensed-instance-count dimension was considered and **declined** — the console is the licensed asset, the runtime is governed by contract.)* **Enablement = publish (Solace-only) + entitle-per-tenant.** A **Solace super-admin role sits above tenant admin** and the entitlement-grant permission is Solace-reserved. Enforced at the API **and** the artifact-pull credential. SaaS: live toggle. On-prem: Solace-signed manifest verified locally, no phone-home. | LOCKED · ⚠️ **loses the artifact-pull leg** under SM-D2; with SM-D13 removing discovery, enforcement is **declaration-only** |
| **B/D8** | **Agent authorization scope:** desired state is computed per-Target and delivered only to the agent whose mTLS identity maps to it; pull credentials are scoped to exactly the assigned artifacts; the agent cannot enumerate the catalog. No self-select. | LOCKED · applies to `host-local` |
| **SM-D11** | **`dp-remote` may hold host credentials**, subject to mandatory handling: dedicated secret backend encrypted at rest (OpenBao reference impl) · **per-Target scoping** · **least-privilege accounts** (write only the external config dir, restart only that service — not root, not cluster-admin) · rotation and immediate revocation without redeploy · encrypted transport with **every remote write and command audit-logged** · prefer short-lived/brokered credentials. **Residual risk accepted:** a `dp-remote` DP is a credential concentration point; least-privilege bounds the blast radius but does not remove the concentration. This is the standing argument for steering security-sensitive Targets to `host-local`. | PROPOSED *(group-confirmed)* |
| **SM-D32** | **The v1 scope model** — `Organization → Organization Unit → Environment → Solution Instance`, fixed depth, `Organization` the isolation root. ⚠️ **Amends `D11` 🔒's hierarchy clause; confirms `D12` 🔒.** Body below; full proposal in [`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md). | PROPOSED |
| **SM-D33** | **Scope-evaluated authorization** — downward prefix inheritance over `SM-D32`'s tree, deny-by-default, allow-only, one enforcement point. Closes `BE-D15`'s flagged whole-install v1 deviation from `D10` 🔒. | PROPOSED |
| **SM-D34** | **`organization` externally, `tenant_id` internally** — one value, two names; no physical rename absent a concrete safety reason; authorization above, isolation below — ⚠️ a flagged **re-reading** of `I5`, whose text binds authz scope to the data-access module. | PROPOSED |

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
| **D7** | Frontend **React + Vite + Material-UI** — adopt the File Mesh MUI component stack (~66 wrappers, setup wizard, tables, charts), migrate CRA→Vite, express the mockup's visual design as an **MUI theme**. | LOCKED · ⚠️ Once conflicted with the deleted design handoff (Tailwind v4 + Radix on the Solace Agent Mesh Design System). **Stack half resolved — open item 19a is CLOSED**, `D7` stands. **Visual half: see open items 19a/19b**, not "open item 7", which was never tracked. `FE-D25` supplies the mockup artifact and `FE-D26` its verified palette, both PROPOSED. |
| **D8** | Agent in **Go** — lightweight, native Docker/K8s ecosystem, bounded component. | LOCKED · unaffected by SM-D18 |
| **SM-D18** | **Control plane is Java 17 + Spring Boot, greenfield.** Decisive reasons are **organizational and operational**: bench strength (Cache, File Mesh MI, DB MI and the MDK are all Java/Spring), and on-prem already requires a JRE so the JVM is free. Parent §8 had already classified the valuable half (authz, tenancy, migrations, data-access) as rebuild-not-inherit, and the four Solace listener services leave the Node backend regardless. **The Spring `Binder` argument is explicitly *not* decisive** — `Binder` needs the MI classpath, which SM-D2 removes; what a Java CP actually gains is relaxed-binding name canonicalisation. Costs: shared TS types lost (mitigated by OpenAPI codegen), phase-2 File Mesh becomes a port, and the broker collector is built twice (Java/JCSMP in the DP, Go in the agent). | PROPOSED |

**Data-access (open, parent §18.8):** the defect to fix is **string-concatenated SQL**, not "no ORM". Direction: **parameterized SQL on a connection pool** (HikariCP + `NamedParameterJdbcTemplate` under SM-D18), **no heavyweight ORM** (avoid lazy-load/entity-graph latency), a thin query builder only for genuinely dynamic SQL. Non-negotiable either way: **bind parameters everywhere** and **one central data-access module enforcing tenant/authz scope**. Open sub-question: keep or drop **Oracle** support.

## 8. Agent & protocol

| ID | Decision | Status |
|---|---|---|
| **B/D1** | **Control channel is agent-initiated, outbound-only, long-lived stream.** The agent pulls desired state; the CP signals "new generation available" down the open stream — pull-shaped for security, push-latency in practice. The agent caches last desired state and survives CP outages. | LOCKED |
| **B/D16** | ⚠️ *Re-homed by SM-D24 — the agent terminates at the DP.* **Agent ↔ CP protocol: gRPC/HTTP2 bidi over mTLS on 443**, agent-initiated, transport-agnostic message set, HTTP/1.1 long-poll fallback. Phases: Enroll (single-use target-bound token → CSR → CP-CA-signed cert) → Session → Steady state → Teardown. **Revocation is active *and* lease-bounded** — the agent holds a short authorization lease it must renew, and stops reconciling when it expires, so a partitioned or rogue agent cannot act on cached state. **Cert rotation is manual for v1**; the lease, not the cert TTL, bounds authority. **Apply is crash-safe and idempotent** — persisted generation state, idempotency keys, deterministic resume, explicit cleanup of superseded partials. | LOCKED · ⚠️ **these safety properties exist only for `host-local`.** They have **no `dp-remote` equivalent** unless separately built — see open item **1** (this row previously said "open item 3", a stale number). |
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

### SM-D32 — The v1 scope model: `Organization → Organization Unit → Environment → Solution Instance`, fixed depth. **PROPOSED (NEW — amends `D11` 🔒's hierarchy clause; confirms `D12` 🔒)**

**Full proposal:** [`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) — the model, the authorization algorithm,
the session claims, the REST shape, the mapping onto plan 004, the demo seed and the two gates.

```
Organization                     -- the isolation root. RBAC root. One per install in v1.
  └── Organization Unit          -- department / business unit / team
        └── Environment          -- dev / stage / prod
              └── Solution Instance
                    └── Placement        -- the instance↔Target binding (SM-D23)
```

- **The level mapping is a collapse and a rename, not a deletion** — `D11`'s five levels become four:
  former **`Tenant` → `Organization`** (the isolation root keeps its semantics and takes the name), former
  **`Organization` → `Organization Unit`**, the optional **`(Sub-org)` removed**, `Environment` and
  `Solution Instance` unchanged. **`Tenant` as the *isolation boundary* is untouched and un-superseded** — `HC2`
  stands word for word, read as *organization* isolation.
- ⚠️ `origin/dev/frontend`'s `D20` states the **same mapping** (*"former Tenant → Organization and former
  Organization → Sub-organization; the former Sub-org level is removed"*); the divergence is the middle level's
  **name** — Sub-organization there, Organization Unit here — recorded in
  [`../tasks/dev-frontend-intake.md`](../tasks/dev-frontend-intake.md) §4.3, not resolved here.
- **`D12` 🔒 holds, both clauses.** *"Two org levels for v1"* — Organization + Organization Unit. *"Parent pointer
  retained for later nesting"* — by a nullable `organization_unit.parent_unit_id` that v1 never populates
  ([`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) §6.4). ⚠️ An earlier draft claimed this clause satisfied
  while proposing no column for it — a guarantee with no enforcing artifact (`L19`). **No hierarchy editor in v1.**
- **Fixed depth, not a recursive tree.** A scope is a typed 0-to-4-element **id** path; its length is its level. `[]`
  is whole-install — rendered, installer/database-managed, never created by the console.
- **Parents are immutable in v1.** Re-parenting an instance, unit or environment changes which grants reach a resource
  and which history is attributable to whom; it is a governance operation with its own audit story, not a field edit.
  Re-*targeting* (placement) is unaffected — `SM-D23` stands.
- **An Environment is not a tenant.** Environments are scopes inside one isolation boundary, sharing the
  organization's rows, topic space and `tenant_id`. Per-environment tenancy would multiply the isolation root, break
  `apply_scope`'s per-organization lock grain and fragment `SM-D26`'s series identity.

**Why:** the locked tree's top level earned nothing operationally while reading as a navigational choice, and the
level operators actually model — a department or team — had the apologetic name "Sub-org". ⚠️ **Proposal against a
LOCKED decision, flagged not resolved:** until the group rules, `D11`'s tree binds. Nothing in plan 004 depends on the
difference.

### SM-D33 — Authorization is scope-evaluated with downward inheritance, deny-by-default, allow-only. **PROPOSED (NEW — closes `BE-D15`'s flagged whole-install deviation)**

Evaluated inside `BE-D15`'s existing single enforcement point `authorizationService.require(principal, verb, resource)`
— this changes **what `require` computes**, not where it is called, so no endpoint signature moves.

```
1. org  := session.organizationId          -- SERVER-DERIVED, never from the request (I5, HC2)
2. path := scopePathOf(resource)           -- [] or [orgId, unitId?, envId?, instanceId?]
3. if path != [] and path[0] != org: deny  -- cross-organization refused before any binding is read
4. grants := { b : (b.scopePath == [] or b.scopePath[0] == org)
                   and isPrefix(b.scopePath, path) }
5. verbs  := union { role(b).permissions : b in grants }
6. return verb in verbs                    -- deny by default
```

⚠️ **A binding's organization is read from its own `scopePath`, never from a separate column.** An earlier draft
carried both, which made the whole-install `[]` binding unrepresentable and so **denied the installer-seeded first
admin every verb**. `scopePathOf` is defined for every resource kind — including the ones that sit at `[]` (the
package catalog, role definitions) and the Deployment Target, which is outside the ownership tree by `SM-D23`'s
design and carries its own `ownership_scope` path — in the amendment's §3.2. A resource kind absent from that table
is unauthorizable, which is how something ships outside the check.

- **Inheritance is the prefix rule and nothing else** — downward only; `isPrefix([], p)` holds for every `p`. No deny
  rules; grants are additive and can only widen.
- **`D10` 🔒's two-sided binding rule is preserved intact** — `instance.bindTarget` on the instance's path **and**
  `target.use` on the Target's `ownership_scope`, which is now a path in this tree. `SM-D23` said that rule had no
  anchor before `ownership_scope` existed; this supplies it.
- **Scope paths are ids, not names.** A renamed unit must never silently move a grant. ⚠️ The as-built console surface
  carries **names** in its `scope: string[]`; changing it to ids is a behavioural change, not a spelling change —
  open item 36.
- **Every element of a scope path is an id, never a name** — a renamed unit must move no grant. Where prose writes
  a path as `[org, unit, dev]` it is shorthand for the ids at those positions.
- **Scope paths are canonical database records, not arrays** — `role_binding.scope_id` and
  `target.ownership_scope_id` reference one `sm_control.scope` table whose **depth and typed position** are a CHECK,
  whose **parentage** is composite foreign keys (the `apply_attempt` triple-FK device `BE-D13` already uses), and
  whose **organization-consistency** falls out of a single shared `organization_id` column. A client-supplied array
  is input to resolution, **never persisted and never trusted**: one write point, `scopes.canonicalize`, resolves
  each level within the session's organization, ArchUnit-enforced as the only writer, `SELECT, INSERT` only by DCL,
  committing in the binding's own transaction, with `ON DELETE RESTRICT` throughout. Canonicality needs **five
  partial unique indexes** rather than one `UNIQUE`, because PG 14 treats nulls as distinct and `NULLS NOT
  DISTINCT` is PG 15 — the same PG-14 null semantics `BE-D13` met in the apply fences, verified against the
  PostgreSQL 14 documentation per `I2`. **The same composite FKs sit on the hierarchy tables themselves**, so a
  resource's derived ancestor tuple is as trustworthy as a canonical scope row — canonicalizing only the grant side
  would leave an instance storable under an environment that is not its parent, which a perfectly valid binding
  would then reach. ⚠️ **The whole-install scope is the one exception, and it is a bootstrap
  necessity, not a convenience:** it has no organization to resolve within and the principal needing it is the
  install's *first*, so no session exists when its binding is written. It is therefore **migration-seeded**,
  `canonicalize` **refuses** `kind='install'` at runtime, the bootstrap binding references the seeded `scope_id`,
  and the `scope_one_install` partial index limits it to one row **for every writer, direct SQL included**.
  **Exactly one scope row has a non-runtime origin** — the migration writes
  `scope_install` and nothing else, asserted after migration by obligation (q) rather than trusted; every other
  scope, the demo's environment scope included, comes from `canonicalize` at runtime, because every other scope has
  an organization to resolve within and a session to resolve it from — for the demo operator that session belongs
  to a named, authenticated, `user.create`/`role.assign`-authorized setup command, not to a privileged installer
  shortcut ([`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) §8). Hierarchy rows are **not** scope rows and are
  installer-seeded without that discipline, their parentage guaranteed for any writer by the resource-side FKs.
  Eighteen named prove-it-fires obligations (a)–(h), (i₁), (i₂), (j)–(q) in
  [`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) §6.5, including that **deleting the prefix check must
  turn the sibling-environment test red**, and an explicit statement of the one property no database constraint can
  enforce (resolution within the session's organization).
- **Existence is privileged; membership of a result set is not.** One rule covers a direct lookup and an
  explicitly-named scope filter alike. Explicit `unitId`/`environmentId` first resolve to **exactly one** canonical
  scope or the answer is 404 — which absorbs the nonexistent id, the other-organization id, the wrong-level id and
  the incoherent pair into one failure mode. A scope is then **visible** when it is prefix-comparable with a held
  grant **in either direction**: at or above it, **or an ancestor of something held**. A collection with no explicit
  scope returns **200** with the authorized subset (possibly `[]`); an explicit scope that resolves and is visible
  returns **200** with the subset within it; anything else returns **404**, byte-identical within each route,
  asserted by equality rather than by separate 404 assertions. A visible resource whose specific verb is missing →
  **403**, **without naming the verb** — matching `origin/dev/frontend`'s `FE-D27`, with the underlying
  permission-enumeration question still [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md) §4's.
  ⚠️ **The ancestor direction is required for correctness, not generosity**: without it `demo-operator`, granted at
  `[org, unit, dev]`, would 404 on its own parent unit and on every filtered list naming that unit — the console
  could not render the hierarchy for the principal §8's seed exists to demonstrate. It discloses nothing: a
  principal already knows the scopes on the path of their own grant. ⚠️ Two earlier drafts were wrong here — the
  first had two rules that disagreed (404 for a lookup, empty list for a filter); the second unified them on an
  at-or-above-only test and broke the demo path.

⚠️ **Proposal against a `PROPOSED` decision.** `BE-D15` §5.1 evaluates bindings whole-install in v1 and flags it as
*"a real v1 narrowing of a LOCKED model"* that *"falls short of the scoped-assignment clause"* of `D10` 🔒 and
`REQ-006`. `SM-D33` closes that deviation, so it may not ratify in the same round as `BE-D15` — the existing
`SM-D*`-before-`BE-D*` chain order already satisfies this. `BE-D15` states the schema is shaped so either ruling is
*"a policy change, not a migration"*; this decision takes that at its word.

**Why:** `REQ-006` is ACCEPTED/must/v1 and its acceptance sentence begins *"a user's effective permissions derive from
scoped assignments"*. Shipping whole-install evaluation would meet the centralized-check clause and fail the
acceptance clause, and the scope tree has no purpose if every grant reaches everything.

### SM-D34 — `organization` is the external vocabulary; `tenant_id` stays the internal storage key, unrenamed. **PROPOSED (NEW)**

Two surfaces, one value, deliberately different names.

| Surface | Name | Rule |
|---|---|---|
| Public REST, frontend, session, display | `organizationId` / `organization` | **`tenant`, `tenantId`, `tenant_id` never appear.** No client-supplied `organizationId` as an authority claim — where a create request names a desired owning unit or environment, that is resource data the server authorizes against the session, never proof the caller may act there (`HC2`, the File Mesh `orgName` finding). |
| `sm_data` / `sm_control` storage, `TenantId`, DCL, replay baseline | `tenant_id` | **Unrenamed.** Its *value* is the `organizationId`. |

**The rename trigger, stated as a rule rather than a preference:** a physical rename is justified only by a concrete
safety reason — specifically, only if two *different* isolation keys would otherwise coexist in one schema.
Vocabulary alone is not such a reason, because the external requirement is satisfied at the API layer where it
actually lives. Absent that trigger, a rename buys a changed word and spends a migration across five `sm_data` tables,
their DCL role topology, the DDL-replay baseline and every golden test.

**Authorization above, isolation below.** Authorization (unit/environment, the `SM-D33` prefix rule) is enforced in
the CP/gateway; **isolation** is enforced in DP central data access as `tenant_id = :t`, bound from the
session-derived organization. **Unit and environment never enter the isolation predicate** — were scope pushed down
into it, a scope-resolution bug would become a *cross-organization* leak instead of an over-broad in-organization
read. Every `WHERE tenant_id = :t AND …` predicate plan 004 already ships stays exactly as written.

⚠️ **This is a re-reading of `I5`, flagged rather than asserted as conformance.** `I5` reads *"every query goes
through the central data-access module **that applies tenant/authz scope**"* — it binds authz scope to the
data-access module, and this split moves the authz half above it. The claim is that the split is **stronger** than
the literal reading because the isolation predicate becomes unconditional rather than computed; the group should
accept or reject that explicitly. Where scope must reach a DP query at all (a scope-filtered instance list), it
arrives as **a set of instance ids, never as a scope predicate**, so `I7` holds and no cross-schema join appears —
and that mechanism depends on `architecture.md` §16.1's unresolved **CP/DP instance identity** gap resolving the way
it recommends (*"CP owns identity, DP references an opaque id"*). Stated as a dependency in
[`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) §6.2, not as settled.

⚠️ **The wire contract is NOT renamed here.** `contracts/events/envelope.schema.json`'s `tenant` field and the
`sm/v1/{tenant}/…` topic parameters are single-writer, gated artifacts; renaming them rides the pending `BE-D4`
amendment (G3), never a cherry-pick — open item 36.

---

## Owner architecture rulings — 2026-09-16

**The project owner is the final architecture authority for Solution Manager.** These are rulings, not proposals.

⛔ **How a `LOCKED` decision changes from here.** Only by an **explicit written owner architecture ruling that
names the affected decision and the scope of the change**. **Ordinary implementation approval is not an
amendment** — approving a plan, a task, a PR or an endpoint never amends a decision, however clearly it implies
one. A decision that changed because a plan needed it to is exactly the failure this rule exists to prevent.

⚠️ **No permanent ids are allocated here in this integration pass.** **This file is the one central allocator**;
plan 005 is now merged (including `BE-D25`/`BE-D26`) and this register has been re-read, so a later decision may
allocate the next free id from this merged register. Nothing reserves an id in advance. The existing `OR-*` labels
remain dated temporary rulings until a specific permanent allocation is made. ⚠️ Open items 34 and 37 are why: two
branches already allocated the same ids in good faith.

| # | Ruling, 2026-09-16 | Amends / closes |
|---|---|---|
| **OR-1** | ✅ **The `D10` amendment is RATIFIED and `LOCKED`** — [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md). Where old `D10` conflicts with it, **the amendment controls**. The 2026-09-15 one-off authorization becomes historical provenance | amends `D10` 🔒 · closes items **23**, **24**, **38**, **40** |
| **OR-2** | ⚠️ **SUPERSEDED IN PART by [OR-16](#owner-architecture-rulings--2026-09-16), 2026-09-16 — the *grant* half.** OR-16 rules the grantable hierarchy is the **five** levels `Organization → Sub-organization → Environment → Solution → Instance`, with **Placement operational but not grantable**. ✅ **What survives:** this ruling's vocabulary and immutable-id requirement for **APIs, topics and events**, in which Placement **does** appear. Original text: ✅ **The canonical hierarchy is `Organization → Sub-organization → Environment → Solution → Instance → Placement`.** External APIs, RBAC scopes and topics use **this vocabulary and immutable ids**; `tenant_id` may remain an **internal storage key** only | amends `SM-D32`'s hierarchy clause (which stopped at *Solution Instance* and said *Organization Unit*) and, through it, `D11` 🔒's hierarchy clause |
| **OR-3** | ✅ **REST is authoritative** for commands, state, recovery and backfill; **events never become authoritative state**. **CLI/terminal clients may use adaptive REST polling as their primary mechanism**; **browser clients use events as *refetch now* notices** and fall back to polling. ⛔ Polling is not a substitute for EDA in the browser | confirms `BE-D1`; extends `BE-D6` with the CLI posture |
| **OR-4** | ✅ **`instance.apply-outcome` is approved** as a **placement-scoped** notification. **Full shape, so a producer written from this row alone emits a valid envelope:** `entity.kind = "placement"` (a `const`, as `instance.health` and `instance.rollout` already use), `entity` = `kind` + `instanceId` + **required** `placementId`; **`payload`** = `applyId` + the terminal `state` key (copied from `BE-D13`'s request states); **envelope** = `stateVersion` and the rest of the standard envelope. ⛔ **No error details** — no message, no remote error, no `ErrorClass` string. ⚠️ Emitting `entity.kind = "instance"` with a `placementId` is the shape the gate rejects for the sibling events, so the `kind` value is not optional detail. Its **normative contract lands later as one reviewed single-writer change**. ⛔ The complete `BE-D4` amendment is **not** approved — its unrelated gaps stay open | partially resolves the `BE-D4` amendment ([`backend/BE-D4-AMENDMENT-PROPOSAL.md`](backend/BE-D4-AMENDMENT-PROPOSAL.md) §2) |
| **OR-5** | ✅ **Default isolation boundary: one Message VPN per Organization.** On **Solace Cloud**, strict isolation means **one broker service per Organization** unless an explicit shared-service profile is approved. **Inside** the VPN, Sub-organization and Environment isolation is enforced **through topics and ACLs** | decides the cross-organization half of items **32**/**33**/**35** |
| **OR-6** | ✅ **Canonical instance topic prefix:** `sm/v1/{organizationId}/{subOrganizationId}/{environmentId}/notify/solution/{solutionId}/instance/{instanceId}/{aspect}`; **placement facts** extend it with `/placement/{placementId}/{aspect}`. **Organization-scoped facts may terminate earlier**; ⛔ **never invent fake lower scopes** to pad a topic | amends `BE-D4`'s taxonomy; the `contracts/events/` change is a reviewed single-writer change, not yet made |
| **OR-7** | ✅ **Password storage: Argon2id through `DelegatingPasswordEncoder`**, Bouncy Castle `bcprov-jdk18on` pinned exactly. **Floor: 19 MiB memory, 2 iterations, parallelism 1**, tuned toward ~1 s verification. Measured: [`../plans/backend/evidence/006-argon2id-benchmark.md`](../plans/backend/evidence/006-argon2id-benchmark.md) | details `BE-D15` §5.3's `credential_hash` |
| **OR-8** | ✅ **Three built-in roles** — Administrator (all 38, **bootstrap/emergency only**), Viewer (8), Operator (19). Everything else Administrator-only. **Roles remain data; never controller-local role checks** | `D10` amendment §1.8 |
| **OR-9** | ✅ **Three further verbs approved** — `instance.stop` (instance/placement, HIGH, audited, idempotent), `audit.view` (scope-filtered, server-redacted, STANDARD), `user.edit` (profile/login, HIGH; status stays `user.deactivate`). **The catalog is 38 members** | narrows item **39** |
| **OR-10** | ✅ **A thin `console-api` module is approved** in the existing backend deployable: depends **only** on CP and DP **service interfaces**, owns **no database**, performs **no cross-schema SQL**. CP owns package/hierarchy/config/auth data; DP owns apply/runtime/health/metrics. Broker consumers persist into the **owning plane**; broker notices only trigger refetch. `console-api` composes CP/DP reads **concurrently** and returns `stateVersion` plus **per-surface availability/degraded** information | ✅ closes item **21** |
| **OR-11** | ✅ **One central decision-id allocator: this file.** No plan or paper reserves an id; plan 005 is merged and this register is re-read, so a future decision may now allocate from the merged register | governs `I3` in practice |
| **OR-12** | ✅ **`Administrator` is bootstrap/emergency only.** ⛔ **`Administrator` bound at a narrower scope is NOT a production operator role** — narrowing the *scope* of a role holding all 38 verbs still grants all 38 wherever it reaches, which is least *reach*, not least *privilege*. `Operator` (OR-8) is what an operator gets | closes plan 006's routed question R1 |
| **OR-13** | ✅ **`user.deactivate` is `ConsequenceClass.HIGH`** — a decision of record, not a copied marking: neither `frontend/src/shared/authz/catalog.ts` nor `architecture.md` §16 marked it. Deactivation ends a person's access and voids the effect of their grants | closes plan 006's R2; closes the attribute half of item **39** |
| **OR-14** | ✅ **`package.load` is organization-scoped**, not install-scoped. A multi-organization install must not need an install-wide grant to load a package, and the narrower default widens later without re-granting | closes plan 006's R3; fixes [`common/contracts/console-integration.md`](common/contracts/console-integration.md) §5.2 row 7 |
| **OR-15** | ✅ **`GET /cp/auth/identity` requires authentication only** — no verb is needed to read the **current** principal, including its own effective permissions. `authz.introspect.self` governs the Admin **access-review** screen; `authz.introspect.any` still governs reading *another* principal's. Rationale: requiring the verb makes a role created without it render an **empty overlay for a principal who can nonetheless act** | closes plan 006's R4 and §4.3 of the integration reference |
| **OR-16** | ✅ **`D11` is AMENDED — the canonical ownership *and grant* hierarchy is `Organization → Sub-organization → Environment → Solution → Instance`** (**five** grantable levels). **Placement is an operational child/resource identity of Instance**: it appears in APIs, topics, events and runtime operations, and is ⛔ **NOT an independently grantable RBAC level**. Authorization for a placement-targeted operation **resolves through the owning Instance scope**, plus any separately required **Target** permission (`D10`'s two-sided rule). `tenant_id` remains an **internal storage key** and is **not** an external hierarchy level. ⛔ **Add `solution_id` to the RBAC scope schema now; do NOT add `placement_id` to role bindings.** | **supersedes `D11` 🔒's hierarchy clause and supersedes the hierarchy half of [OR-2](#owner-architecture-rulings--2026-09-16)** — this is the ruling that *names* `D11`, which §0 requires and OR-2 did not. Closes plan 006's handover question 9. `D11`'s decoupling-from-placement and rebind-preserves-ownership clauses are **untouched** |
| **OR-17** | ✅ **Decision identity policy.** `OR-*` identifiers are **temporary register identifiers**. **Permanent decision ids may be allocated only after plan 005 is merged and the merged [`DECISIONS.md`](DECISIONS.md) has been re-read** — a condition now met; they are never pre-allocated from a plan or a paper. **Dated approval documents remain provenance and must continue to be cited**, but ⛔ **they do not replace permanent central-register ids** | reconciles the `OR-*` row of the [family table](#id-discipline-binding) with [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md) §5 item 1, which said a dated identity *"is now the general policy rather than a workaround"*. ⛔ **That reading is superseded:** dated identity is provenance, not a substitute for an id. Confirms OR-11's single central allocator |
| **OR-18** | ✅ **The production `stop` API belongs to [plan 007](../plans/backend/007-production-api-and-event-driven-walking-skeleton.md)'s authenticated apply/runtime surface.** It uses **`instance.stop`** — **Administrator-only, `HIGH`, audited, idempotent** (catalog row 36). It must **preserve the engine's actual instance/placement targeting semantics** as plan 005 implements them, and ⛔ **placement targeting is authorized through the Instance scope — no placement-level role binding** (OR-16) | closes the ownership gap in which `008` pointed at plan 007 and plan 007's non-goals declined it, so no plan owned the endpoint. Requires OpenAPI, authorization, audit, idempotency and integration tests |

⚠️ **OR-12…OR-15 are registered here because §0 forbids the alternative.** They were first written as "R1–R4"
inside [plan 006](../plans/backend/006-local-identity-and-scoped-rbac.md), and a decision that lives only in a plan
is exactly what *"ordinary implementation approval is not an amendment"* rules out. The plan now cites these rows
rather than asserting the rulings itself.

## Ratification matrix

**One authoritative status table.** Round 2 found status claims scattered and inconsistent across documents; this is
the only place that decides. **Nothing in the `SM-D*` family is binding until the group ratifies the set.**

| Family | Status | Meaning for a developer |
|---|---|---|
| `D1`–`D17` (parent) | **LOCKED** | Binding today. Build against these. ✅ **`D10`'s catalog and evaluation legs are AMENDED and RATIFIED, 2026-09-16** — [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md) is itself `LOCKED`, and **where old `D10` conflicts with it the amendment controls**. It carries the **38-member** catalog, scope-evaluated bindings with downward inheritance, bootstrap/identity/session/CSRF/audit, one enforcement point, and **three built-in roles**. **The project owner is the final architecture authority**, and from here a `LOCKED` decision changes only by an explicit written owner ruling naming the decision and the scope — ordinary implementation approval is **never** an amendment (see Owner architecture rulings above; open item 40 is **closed**). Residue: open item **39**. |
| `B/D1`–`B/D17` (Subsystem B) | **LOCKED** | Binding today. |
| `SM-D1`–`SM-D34` + `SM-D11a` | **PROPOSED, ratify as a set** | **Not binding.** Where one conflicts with a locked decision, the locked decision wins. **`SM-D32`–`SM-D34` (drafted 2026-09-11, [`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md)):** the v1 scope model (`Organization → Organization Unit → Environment → Solution Instance`, fixed depth), scope-evaluated authorization with downward prefix inheritance, and the external-`organization`/internal-`tenant_id` vocabulary posture. ⚠️ `SM-D32` amends **`D11` 🔒**'s hierarchy clause and confirms `D12` 🔒; `SM-D33` closes `BE-D15`'s flagged whole-install deviation and is therefore a proposal against a proposal — see the ordering rules below. ⚠️ **`SM-D32`'s hierarchy clause is AMENDED by ruling OR-2 (2026-09-16):** the canonical hierarchy is `Organization → Sub-organization → Environment → **Solution** → **Instance** → **Placement**` — two levels deeper than `SM-D32`'s *Solution Instance*, and *Sub-organization* is the canonical word where `SM-D32` said *Organization Unit*. External APIs, RBAC scopes and topics use that vocabulary and **immutable ids**; `tenant_id` stays an **internal storage key** only, which is `SM-D34`'s posture unchanged. ⛔ **For RBAC scopes read [OR-16](#owner-architecture-rulings--2026-09-16), not OR-2:** the **grant** hierarchy is **five** levels ending at **Instance**, `solution_id` joins the scope schema, and **Placement is operational, not grantable**. OR-2's six-name list is the **API / topic / event** vocabulary. ⚠️ **`SM-D33` is confirmed in substance** by the [ratified `D10` amendment](D10-G2-APPROVAL-2026-09-15.md) §1.2, which approves scope-evaluated authorization with downward inheritance and **rejects** `BE-D15` §5.1's whole-install v1 deviation. That confirmation does **not** ratify the `SM-D*` family — the set still ratifies as a set (open item 8) — it removes one of the questions the set was carrying. Five items were routed rather than resolved; status as of **2026-09-16** (⚠️ **the ratification date; an earlier revision dated this block 09-15**): **34** (the `D18`/`D19`/`D20` record on `dev/frontend` — ⚠️ **narrowed**: G2 is closed independently by the [2026-09-15 approval](D10-G2-APPROVAL-2026-09-15.md), so what remains is the **id-allocation** half and the competing `D18` 🔒 catalog), **35** (environment-level mesh isolation), **36** (the vocabulary rename, its contracts leg gated on G3), **37** (the `BE-D12` and `FE-D25`/`FE-D26` id collisions, which gate any register intake) and **38** (`CLAUDE.md` vs `BE-D15` on whether G2 gates auth-bearing *code* or only *endpoints* — ✅ **closed 2026-09-16, moot**: G2 is closed, so neither reading gates anything). ⚠️ Two new items arrived with that closure: **39** — ⚠️ **now NARROWED to six** (nine surfaces, three approved by OR-9) — and ~~**40**~~ ✅ **already CLOSED by OR-1**. |
| `UI-D*` ([dynamic-ui](dynamic-ui/decisions.md)) | **PROPOSED, ratify as a set — *after* the `SM-D*` set** | **Not binding.** The whole family rests on the `SM-D*` base; ratifying it first would settle derivations before their premises. `UI-D12` **supersedes `SM-D14`**, so it cannot be ratified in the same round as the decision it replaces. |
| `DM-D*` ([dynamic-metrics](dynamic-metrics/decisions.md)) | **PROPOSED** | **Not binding.** `DM-D5` supersedes `SM-D31` and must ratify **after** it, never in the same set. `DM-D18` adds six verbs to **LOCKED `D10`** — ✅ **granted 2026-09-16** by the ratified [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md) §1.1 (rows 16–18, 20–22) — the ratification date, not the 2026-09-15 authorization date (`L8`); open item 23 closed. `DM-D13` discards `D7`'s assumed chart harvest. |
| `FE-D*` ([frontend](frontend/decisions.md)) | **PROPOSED** | **Not binding.** Rests on `D7` 🔒 plus the `UI-D*`/`DM-D*` sets; two clauses are additionally blocked (`FE-D18` clause B on open item 1, `FE-D23`'s yaml clause on open item 22). **2026-08-19:** two decisions added. **`FE-D25`** (`sm-v2` is the visual specification of record) supersedes nothing and ratifies **with** the set. **`FE-D26`** (verified product palette governs UI semantics) **supersedes the paint clause of `FE-D5`**. ⚠️ `FE-D5` was **not** amended in place — an earlier draft did that, and independent review found the family's draft amendment policy licenses *review corrections* only while `FE-D5` contained no error. **An ordering constraint therefore does apply**, per the rule below about a superseding decision ratified alongside what it supersedes. `FE-D26`'s body proposes that `FE-D5`'s explicit body-and-index supersession marker resolves it for same-round ratification, and offers separate rounds (`FE-D5` first) as the fallback — **the architect group picks; this row does not decide it.** Palette verified, designer confirmation still pending (open item 19b). |
| `AG-D*` ([go-agent](go-agent/decisions.md)) | **PROPOSED** | **Not binding.** `AG-D1`…`AG-D12`. Ratifies after the `SM-D*` set — `AG-D3`/`AG-D4`/`AG-D5` rest on `SM-D15`, `SM-D24` and `SM-D28`. ⚠️ `AG-D5` is flagged against `LOCKED` `B/D16`; the locked decision stands. Five blocking open items (`AG-1`…`AG-5`), of which the missing agent↔plane `.proto` blocks all v1 agent work. |
| `AGW-D*` ([backend/agent-gateway](backend/agent-gateway/decisions.md)) | **PROPOSED** | **Not binding.** The plane-side Agent Gateway family defines the Java Maven component co-deployed with DP v1, not a separate app/process and not the host-local Go agent: sessions, mTLS enrollment relay, command delivery, HA routing, telemetry ingress and future extraction while keeping durable state in `dp-core`. `AGW-D1` amends `BE-D3` (four to five modules) and `AGW-D7` amends `SM-D28`; both are flagged and ordered after the decisions they affect. `AGW-D15` and `AGW-D16` are separately gated mechanism decisions; the wire contract, report intake and durable audit obligations remain open. Ratifies after `BE-D*` and `AG-D*`; `B/D16` remains binding until the architect group rules. |
| `BE-D*` ([backend](backend/decisions.md)) | **PROPOSED** | **Not binding.** `BE-D1`…`BE-D9` (drafted 2026-08-27): event-driven backbone on the customer mesh, module layout incl. the composition module (formerly open item 21, ✅ closed by OR-10 as `console-api`), mesh tickets, hybrid live console, ingest. `BE-D10`/`BE-D11` (drafted 2026-08-28): contracts consumption — mirror-and-lock bindings including key/enum/scoping/type/bound locks, a shared publisher contract-test kit, and the single contract-artifact resolver / checked runtime-copy seam, per [`common/contracts/backend-consumption.md`](common/contracts/backend-consumption.md). Ratifies **after** the `SM-D*` → `UI-D*` → `DM-D*` → `FE-D*` chain: ⚠️ `BE-D6` **supersedes `DM-D17`** (proposal against proposal — never the same round) and `BE-D7` amends `FE-D19`/touches `FE-D7`. `BE-D3` proposed a resolution of **open item 21** — ⚠️ ✅ **21 is CLOSED 2026-09-16 by OR-10, which approves `console-api` rather than `BE-D3`'s shape**. `BE-D9` extends the canonical `solace_event` source in `contracts/solution-package.schema.json` (reviewed contract change). ⚠️ `BE-D5`'s subscribe-authorization mechanism was flagged **unproven and gated on a broker spike** by R5 external review (2026-08-27); the **G1 spike ran (2026-08-27) and chose candidate (a), the substitution-variable ACL** — [`spike-authz-result.md`](backend/spike-authz-result.md). `BE-D5` ratification is now blocked instead by **three named decisions sitting with the project owner — open items 32 (multi-tenant composition), 33 (notify-tier exception shape) and 35 (below-organization subscription isolation)** — ⚠️ **three, not two, and the owner, not the group** (ruling **OR-1**), all NARROWED 2026-09-16 with broker enforcement still **unmeasured**, both stated in full in the open-items table below rather than restated here. The SaaS browser↔mesh topology remains an open question, not a packaging change. Satisfies [`REQ-012`](../requirements/REQ-012-event-driven-live-console.md). **`BE-D12`–`BE-D15` (drafted 2026-08-31, [`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md)):** the `dp-remote` apply protocol + persistence (the design open item 1 demanded — six runtime-neutral operations behind a `RuntimeAdapter` seam; three tenant-keyed tables — request/intent/attempt — with constraint-bearing DDL and role-topology DCL in the sketch), the live-console session contract (fixed ticket claims, memory-only storage, `LiveStateClient` seam, v1 postures **proposed** for open items 32/33), and the authorization machinery implementing [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md)'s own recommendations. ⚠️ `D10`/`D13`/`B/D16` stay locked and untouched; item 24 is ✅ **closed 2026-09-16** — the ratification date, not the 2026-09-15 authorization date (`L8`) — by the [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md), which lifts **G2** and no other gate and creates items **39**/**40**, of which ⚠️ **40 is itself now closed by ruling OR-1**; items 32/33/35 remain the **project owner's** (OR-1) — the new decisions give each a concrete proposal. **`BE-D16`/`BE-D17` (drafted 2026-09-08, same document, §3.2/§3.4):** design-first corrections to two defects an external review found in the executed plan 004 — `BE-D16` makes `STAGED` exclusively `manual`'s terminal state (a non-manual apply must stay `RUNNING` through every lifecycle step); `BE-D17` splits resolution evidence by operation (`apply` evidence must equal the request's own generation; `restart` evidence carries no generation at all) and confines watermark-raising to resolved `apply`s. Both amend `BE-D12`/`BE-D13` by cross-reference, per `I3`, and are now **implemented**: `staged_requires_manual` and the operation-discriminated `resolution_is_evidence` land in `V002__apply_request.sql` and the design's §3.1 SQL block, guarded in `OutcomeService.land`/`OutcomeService.resolveIndeterminate`, with evidence split into `ApplyResolutionEvidence`/`RestartResolutionEvidence`/`RestartObservation` (commits `647d4e9`, `5f04d79`, `d15dc4b`); the DDL-replay baseline was re-recorded against both (commits `90cce61`, `221672d`), later superseded by `BE-D18`'s own re-record (commit `de8a6aa`, below). Implementation is not ratification — both stay **PROPOSED**. **`BE-D18` (drafted 2026-09-09, same document, §3.4):** a fourth external review measured that `resolution_is_evidence`, even after `BE-D17`, validated only the evidence **container** — `{"replicas":[{}]}` and `{"restartsObserved":[null]}` were both accepted — so `BE-D18` adds ten `jsonb_path_exists(…, silent => true)` predicates (six `apply`, four `restart`) giving per-element typing, a mandatory-but-nullable `observedHash` key, and both key-exactness levels (top-level and per-element), including a `lax`-mode array-unwrap escape closed in its own fix round. Amends `BE-D17`'s `resolution_is_evidence` shape and its "key-exactness is the Java seam's job" reading, by cross-reference per `I3`. **Implemented** (commits `56119a4`, `22ebd61`), including the one Java-side fix the amendment named (`Evidence.readResolutionEvidence` now refuses a `replicas` element missing `observedHash` rather than null-coalescing it); the DDL-replay baseline is re-recorded against it (commit `de8a6aa`). Implementation is not ratification — `BE-D18` stays **PROPOSED**. **`BE-D19`/`BE-D20` (drafted 2026-09-09, same document, §3.4, design-first — S1 of a bounded three-stage repair):** a post-handover external review found two further MAJOR defects, both measured on live PostgreSQL 14.24. `BE-D19`: `ReplicaRef`'s `isBlank()` and `resolution_is_evidence`'s `like_regex "\\S"` were not equivalent (PG's `\s` excludes U+001C–U+001F, Java's does not), so a raw write via `sm_dp_runtime`'s existing grant could commit a `ref` that then throws on read — closed by one shared, locale-independent grammar — `ValueGrammar.REPLICA_REF`, a new dedicated constant, not the shared `ValueGrammar.VALUE` (an R1 fix round withdrew `VALUE` after proving it against Docker/systemd/Kubernetes primary sources disqualified it on length and on excluding backslash) — on both sides, amending `BE-D18`'s own "one-sided containment is safe" reading. `BE-D20`: `AttemptService.open`'s only fence, `UNIQUE (intent_id, attempt_no)`, refused only a same-number collision, so a second, non-colliding open landed with no violation at all — closed by a second, independent partial unique index (`attempt_open_fence`), amending `BE-D13`'s attempt model. Design-and-register only; not implemented. **`BE-D21` (drafted 2026-09-11, same document, §2.1, design-first — before the seam is built):** the `RuntimeAdapter` signature `BE-D12` §2.1 prints is not implementable as written, found by plan 005's design review while writing the first adapter against it. `ReplicaRef` cannot be widened to carry its `PlacementRef` (its grammar is mirrored into `resolution_is_evidence` by `BE-D19` and its `ref` key is closed over by `BE-D18`'s per-element key-set predicates, so a second component would change an evidence wire shape the database now holds rows to) — the requirement that *adapters never see bare strings* is met instead by `record TargetReplica(PlacementRef, ReplicaRef)`; and the rendered bytes have no parameter, so an adapter would render its own — a second renderer per runtime, free to disagree with the `config_hash` the intent row bound before it was called — closed by a caller-owned, self-checking `RenderedConfig`. Six operations, unchanged names and semantics; parameter types and one parameter change. Amends `BE-D12` by cross-reference per `I3`. ⚠️ This is the seam every future adapter and the contract-test kit inherit, so ratification decides whether it **stays**, not whether it may be built: implementation is **not** gated on it, the same posture `BE-D16`–`BE-D18` carry. Design-and-register only; not implemented — [`../plans/backend/005-runtime-adapter-and-docker-apply.md`](../plans/backend/005-runtime-adapter-and-docker-apply.md) Tasks 1–2 build it. **`BE-D22`/`BE-D23` (drafted 2026-09-11, same day, design-first — from an external review of plan 005's own task graph):** `BE-D22` splits `BE-D12`'s sixth operation in two, because `BE-D13` §3.1 already carries two journal steps for it — `ROLLBACK_ASSESS` (hashless) and `ROLLBACK` (which `hash_on_config_steps` requires to carry a `config_hash`) — and an engine holding one conflated operation must either journal a restore as an assessment (against §3.1's own step-label-truthfulness invariant) or open `ROLLBACK` after the destructive act (against §2.2 rule 0, leaving a crash unable to tell whether the restore happened). The seam becomes seven operations (`assessRollback` read-only, `restore` hash-bearing and journalled first), with a per-replica `VERIFY` step after a restore as the **durable** evidence — the values the adapter returns are transient. The one unjournallable case, a prior state of *absence*, is decided rather than deferred: `APPLY_INDETERMINATE`. `BE-D23` makes the v1 engine **single-placement**: `apply_step_intent`'s `UNIQUE (tenant_id, apply_id, step, replica_ref)` with `placement_scoped_steps` pinning `replica_ref` to the literal `'@placement'` admits at most one `PREFLIGHT` intent per `apply_id`, so an instance-wide fan-out journals the second placement's pre-flight as a retry of the first's — measured against the shipped DDL. An instance-wide apply is refused before admission, persisting nothing; encoding a placement into `replica_ref` is explicitly forbidden (`BE-D19` locks that grammar on both sides, `BE-D18` closes over its values); and the identity amendment that would fix it is named and routed to the plan that revisits `sm_data`, since it moves the DDL-replay baseline and the measured fence interactions. Both amend `BE-D12` — and `BE-D21` — by cross-reference per `I3`. **`BE-D24`–`BE-D26` (2026-09-08 / 2026-09-14):** `BE-D24` makes a config location name its writer access mode (`LocalFilesystem` / `RemoteSftp`) rather than always a local `Path`; `BE-D25` adds a generation-free `replicas(PlacementRef)` observation to the seam — an eighth operation — because `prepare` is generation-bearing and `apply_has_generation` makes a `stop`'s generation NULL; `BE-D26` makes a `stop` scope-granular and records why instance-wide is admissible for a stop though not for an apply (`BE-D23`'s limit is a property of the placement-scoped steps, which a stop never writes) — **with the residual replica-label collision `BE-D23`'s second clause names refused explicitly rather than worked around**. `BE-D25`/`BE-D26` are implemented and tested (plan 005); implementation is not ratification. |

**Ratification order is not optional.** `SM-D*` set → `UI-D*` set → `DM-D*` → `FE-D*`. Each later family contains at least one proposal *against a proposal* in an earlier one, and ratifying a superseding decision alongside the decision it supersedes leaves two live readings of the same rule — the failure `UI-D12` vs `SM-D14` already produced once.

Locked decisions with a proposal against them — **all still binding**:

| Locked | Proposal | If ratified |
|---|---|---|
| `D2`, parent `§17` (outbound-only, no stored creds) | `SM-D12` | guarantee narrows to `host-local` only |
| `D3` (schema from the build) | `SM-D20` | replaced by per-solution source adapters |
| `D4`, `§7` (schema pinned to artifact version) | `SM-D2`, `SM-D14` | artifact leg drops; render against *observed* version |
| `D5` (agent normalizes at the edge) | `SM-D4`, `SM-D6` | DP normalizes for `direct-poll`/`federated` |
| `D6` (Node/TS control plane) | `SM-D18` | Java 17 / Spring Boot |
| `D11` (ownership tree `Tenant → Organization → (Sub-org) → Environment → Solution Instance`) | `SM-D32`, ⚠️ **superseded in substance by rulings OR-2 and — for the grant hierarchy — OR-16** | `Tenant` stops being a *level* (it stays the isolation **boundary**, `HC2` untouched, and `tenant_id` is an internal storage key only). ⚠️ **Two corrections to `SM-D32`'s reading, both from OR-2:** the level is **Sub-organization**, *not* "Organization Unit"; and the **grant** hierarchy is **five levels** — `Organization → Sub-organization → Environment → Solution → Instance` (**OR-16**; Placement is operational, and OR-2's six-name list is the API/topic/event vocabulary) — *not* "four plus placement". **`D12` 🔒 is confirmed, not amended** — this is still two org levels with the parent pointer retained. ✅ **RESOLVED 2026-09-16 by ruling [OR-16](#owner-architecture-rulings--2026-09-16)**, which names `D11` explicitly and so satisfies §0 — ⚠️ **OR-2 alone did not, and an intermediate revision of this row wrongly claimed it did.** ⛔ **OR-16 also corrects OR-2's depth:** the grant hierarchy is **five** levels ending at **Instance**; **Placement is operational, not grantable**, and `solution_id` is added to the scope schema now while `placement_id` is not added to role bindings |
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
| `D1` (hybrid platform incl. a per-solution **custom-UI escape hatch**) | `UI-D21` | escape hatch replaced by SM-built named screen types. **Flagged, not resolved** — `dynamic-ui/decisions.md` `UI-D21`, `dynamic-ui/design.md` §9 |
| `B/D4` (publishing a solution is Solace super-admin only) | `UI-D19` | customer-**loaded**, Solace-**authored** packages; chosen specifically so `B/D4` is not touched. Customer authorship would touch it — `dynamic-ui/design.md` §6.2, §9 |
| `D7` (React + Vite + MUI, harvest File Mesh wrappers incl. **charts**) | `DM-D13`, `FE-D4`, `FE-D5`, `FE-D25` | `FE-D4` **upholds** `D7`'s stack outright (no Tailwind, no Radix, no shadcn) — see open item 19a. `DM-D13` selects ECharts and so discards the assumed chart-wrapper harvest; MUI remains the chrome. **`FE-D25` (2026-08-19)** supplies the artifact for `D7`'s *"express the mockup's visual design as an MUI theme"* — the `sm-v2` import — as a **new proposed decision rather than a re-reading of `D7`**, since `D7` is `LOCKED` and predates it and `I3` forbids shifting a locked decision's referent in place. The imported artifact sits on the Solace Agent Mesh DS, i.e. the system the deleted Tailwind/Radix handoff paired with; adopting its **paint** through an MUI theme leaves `D7`'s stack clause and `FE-D4` untouched, so this does **not** reopen 19a. Brand paint is **verified but not designer-confirmed** — open item 19b, narrowed |
| `D10` (Azure-style RBAC; permission catalog is policy-as-code) | `DM-D18`; ✅ **resolved by the [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md), RATIFIED and `LOCKED` 2026-09-16** — all six are catalog members (§1.1 rows 16–18, 20–22), so open item 23 is closed. ⚠️ **The status change is the 2026-09-16 ratification, not the 2026-09-15 authorization** (`L8`); the authorization said in its own terms that it was not ratification | six new verbs — `dashboard.view` / `.edit` / `.reset`, `metrics.query` / `.export` / `.forceRaw`. A verb that does not exist in `D10` cannot be granted under deny-by-default. ~~**Flagged, not resolved — open item 23**~~ ⛔ **struck 2026-09-16 — this cell's own first column says item 23 is closed; the two halves contradicted each other** |

⚠️ **`D10` and `SM-D33` are deliberately *not* in the table above.** `SM-D33` does not propose against `D10` 🔒 — it
**restores conformance** with `D10`'s scoped-assignment clause. The live deviation from `D10` is `BE-D15` §5.1's
whole-install v1 evaluation, which is a **PROPOSED** decision narrowing a LOCKED one and is recorded as such in the
`BE-D*` row and in the proposal-against-proposal note below. Filing `SM-D33` as a conflict would list `D10` twice and
name the wrong decision as the one deviating.

**Round-2 corrections** (`SM-D20`–`SM-D28`) are proposals *against other proposals* — they do not change any locked
status and take effect only when the set is ratified.

**Proposals against `PROPOSED` decisions** — the same rule, one layer up. `UI-D12` supersedes `SM-D14`; `DM-D5`
supersedes `SM-D31`. Neither changes the other's status, and neither may be ratified in the same round as the decision
it replaces. `SM-D33` closes the whole-install scope deviation `BE-D15` flags. Each is recorded in its own family's file; only their **ordering** is decided here. The `SM-D33`/`BE-D15` pair needs no extra sequencing — the chain already ratifies `SM-D*` before `BE-D*`.

## Open items

**The baseline is UNBASELINED.** Prerequisites are listed **before** the ratify item, because ratifying first and then
doing the work is how a gate becomes decorative.

| # | Item | Kind |
|---|---|---|
| 1 | **Specify the `dp-remote` apply protocol** — generation ordering, apply id/idempotency, DP-persisted per-host in-flight state, bounded retry, `APPLY_INDETERMINATE`. **The walking skeleton runs on `dp-remote`, so v1's first slice has non-idempotent remote apply without this.** **The design now exists (2026-08-31): `BE-D12`/`BE-D13`, [`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §2–§3** — every listed property covered; four of `B/D16`'s five properties mirrored DP-side (the lease clause named out of scope while CP/DP are co-located). **Amended 2026-09-08 by `BE-D16`/`BE-D17`** (same document, §3.2/§3.4): plan 004's execution surfaced two design defects in this coverage — a non-manual apply could land irreversibly `STAGED`, and resolution evidence was not bound to the request's own generation — both closed design-first, corrected here and not yet in the SQL block or a migration (the next task in the repair wave). **Implemented and proven 2026-09-14** ([`../plans/backend/005-runtime-adapter-and-docker-apply.md`](../plans/backend/005-runtime-adapter-and-docker-apply.md)): generation ordering, apply id/idempotency, DP-persisted in-flight state and bounded retry execute against a real PostgreSQL journal and, for the apply path, a real Docker runtime; **crash-safe resume is proven against the real journal but only against an in-memory adapter** — no Docker-backed test exercises it. The fifth property — lease-bounded authority — remains out of scope while CP and DP are co-located (design §8 row 7). **Implementation does not close this item:** `BE-D12` is PROPOSED, and the same sentence `BE-D16`, `BE-D17` and `BE-D18` each carry applies here. One v1 limit is now measured rather than assumed and belongs to the ratification conversation: the **apply** engine is single-placement (`BE-D23`). The stop half is closed — `BE-D25` gives the seam a generation-free replica observation and `BE-D26` makes a stop scope-granular, including instance-wide, whose admissibility for a stop and refusal for an apply is structural rather than a concession — and **obligation (r) is PROVEN** in §3.3's own terms. The item stays open as a ratification blocker until the design ratifies with the chain; `FE-D18` clause B unblocks at that ratification. | **ratification blocker — design PROPOSED, awaiting ratification** |
| 2 | **`dp-remote` SSH security contract** (SM-D11a) — host-key pinning/CA + rotation, shell-free allowlisted commands, SFTP temp+rename, symlink checks, restricted sudoers, audit with host-key fingerprint. | **ratification blocker** |
| 3 | **Produce executable migration DDL + constraint tests** for metrics/events/rollups (SM-D26). Prose has failed three times; only a migration run catches "cannot be created", and only a lifecycle test catches a rebind-backfill collision. This includes the disposition of the orphan `backend/src/main/resources/db/migration/V001__telemetry_schema.sql` (+ its `V001_constraints_test.sql`), which no module builds: relocate into the metrics module's own Flyway series with a version that does not collide with `sm_data`'s `V001`–`V006`, or delete. | **ratification blocker** |
| 4 | **Resolve the Kubernetes writer model** with customers (SM-D25) — SM as declared writer of a generation-named ConfigMap + workload reference, or GitOps PR integration. Kubernetes is v1. | **ratification blocker** |
| 5 | **Benchmark the physical metrics schema** at expected ingest/query/export concurrency, incl. WAL, bloat, backup and rollup workspace. | **blocks ratifying SM-D8** |
| 6 | **Write the agent↔DP `.proto` and state machine** (SM-D24/SM-D28 decide the authority model; the wire format does not exist). | blocks v1 agent work |
| 7 | **Design the schema registry against a real adapter contract** (SM-D20) — normalized, semver-aware, version-filtered. `FM_CONFIGURATION_TEMPLATE` is an input, **not** the model. | blocks backend + frontend sub-specs |
| 8 | **RATIFY `SM-D1`…`SM-D31` + `SM-D11a`** as a set, with the capability limits in `architecture.md` §13 explicitly accepted — in particular that outbound-only holds only for `host-local`, and that drift detection is bounded (SM-D22). Items 1–4 must close first. ⚠️ **`SM-D29`/`SM-D30`/`SM-D31` are in the set and are load-bearing**, not an appendix: they are the direct base of `UI-D5` (quarantine of undeclared metrics), `DM-D5` (dashboard scopes), `DM-D7` (rendered-series cap vs the `SM-D30` cardinality cap), `DM-D11` (automatic resolution) and `DM-D12` (`mark` set). A gate that stopped at `SM-D28` would ratify none of their premises. | the gate |
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
| 19a | **Component stack — CLOSED.** `D7` 🔒 stands: React + Vite + **Material-UI**. [`frontend/decisions.md`](frontend/decisions.md) `FE-D4` picks it outright — *"All chrome is MUI. **No Tailwind, no Radix, no shadcn, no parallel token set.**"* — and reads the deleted Tailwind/Radix handoff as a **visual-reference** gap, not a licence to run two stacks. The frontend sub-spec now exists ([`frontend/design.md`](frontend/design.md)), so this no longer blocks it. **What remains is ratification of that reading**, with the `FE-D*` set. ⚠️ Three documents once claimed this was tracked as "open item 7"; it was tracked nowhere. | **ratify with `FE-D*`** |
| 19b | **The visual reference must be confirmed by the designer.** ⚠️ **Narrowed, not closed, 2026-08-19.** Two of the three original gaps are gone: an in-repo reference now exists ([`frontend/reference/`](frontend/reference/), adopted as the visual spec of record by proposed `FE-D25`), and the palette is **verified against Solace product source** — all 58 unique hex values in the Agent Mesh token set appear in `SolaceLabs/solace-agent-mesh` upstream, font family `Rubik` verified at `lib/index.css:123` ([provenance record](frontend/reference/PALETTE-PROVENANCE.md)). The interim pair `#003057`/`#00a89e` is **retired as wrong** (verified: `#015B82`, `#00C895`). **What remains is authority, not provenance:** this item assigns the reference to *the designer or Figma*, and a product repository is neither — so per `L8` the item does not close on the strength of a proposal. **Four questions for the designer:** (1) does the product palette govern console UI, with the Solace Brand Book governing identity only? (2) does the brand book file's claim to be "Brand Book v2.9" hold — is `#093B5F` really its navy? (3) how is the untokenised purple `#f2ecfb`/`#6b3fa0` resolved? It is **not one chip** — it paints the PROD environment chip, the IdP-group chip, the layer-origin badge on Dashboards/Explore and the RBAC group-principal icon. Adopt the verified `accent-purple` pair (which **lowers** contrast 6.39:1 → 5.80:1, both AA), or promote the mockup's values to named tokens. (4) ⚠️ **the dark theme has its own untokenised palette** — `#0a2c47`, `#0e344f`, `#1c344a`, `#213546`, `#2b4358`, `#8ea9bd`, `#eaf1f6`, `#c9d4dd` have no basis in either palette, so **dark mode cannot be both faithful and token-pure** until answered. A log-console palette (`#5db0e6`, `#6f8ba0`, `#7fe0c2`, `#9fb2c1`, `#c9e2ef`, `#ff8a8c`, `#ffb765`) is in the same position but gates nothing built yet. Until answered, building on this palette requires the [`frontend/design.md`](frontend/design.md) §24 gate-2 **waiver** — a developer decision. Also still open: visual-regression baselines. | **blocks designer sign-off + visual regression; theme work proceeds under waiver** |
| 20 | **Upgrade of SM itself** — procedure, migrations against large partitioned tables, CP↔DP version skew, rollback. | operability |
| 21 | ✅ **CLOSED 2026-09-16 — ruling OR-10.** A thin **`console-api`** module is approved in the existing backend deployable: it depends **only** on CP and DP **service interfaces** (never their data-access modules), owns **no database**, and performs **no cross-schema SQL** — so `I7` holds and `UI-D13`'s *"two reads assembled, never one query"* is satisfied by construction. CP owns package/hierarchy/config/auth data; DP owns apply/runtime/health/metrics; broker consumers persist into the **owning plane** and broker notices only trigger refetch. `console-api` composes CP/DP reads **concurrently** and returns `stateVersion` plus **per-surface availability/degraded** information. ⚠️ `architecture.md` §3.1 still names only two modules — that text needs the third added. Original statement (historical): **Declare the `console` composition module, or reject it.** `UI-D13` requires composing `sm_control` (schema registry) and `sm_data` (values, series) *"in the API layer above both data-access modules — two reads assembled, never one query"*, but `I7` forbids CP and DP code importing each other, so that layer is a **third module** — and `architecture.md` §3.1 names only two. [`dynamic-metrics/design.md`](dynamic-metrics/design.md) §5.2 **assumes** a `console` module depending on both plane **service interfaces** (not their data-access modules), routes composed reads at `/api/console/v1/*`, and explicitly flags it for decision here rather than resolving it locally; [`dynamic-metrics/decisions.md`](dynamic-metrics/decisions.md) `DM-D1` repeats the flag. Options: a third module, a CP-side composer calling the DP over its public API, or a narrowing of `I7`. | ✅ **CLOSED 2026-09-16 (OR-10)** — `console-api` is approved, so it blocks nothing. ⚠️ Residual docs task only: `architecture.md` §3.1 must name the third plane module |
| 22 | **Server-side redaction of `yaml`-embedded secrets** in diff / export / history payloads. `I6` forbids secret values in field-level diffs, exports, archives and logs; `UI-D23` ([`dynamic-ui/design.md`](dynamic-ui/design.md) **§3.7** — not §3.6, which is `UI-D24`) accepts that a secret inside a `yaml` blob **cannot be masked** and settles for warn-only, stored in clear. [`frontend/design.md`](frontend/design.md) §26 item 14 binds only what the console owns — the **payload** must be redacted server-side — and gates the feature: **no `type: yaml` field ships until it is.** Still undecided is whether the **stored** value is redacted too, which is Subsystem A's call. **Gates Phase 2, not v1** — `type: yaml` exists for File Mesh's 12 fields and Cache declares none. **Route to the architect group with the `UI-D*` set.** | **gates Phase 2 / `type: yaml`** |
| 23 | ✅ **CLOSED 2026-09-16** by the [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md) — all six `DM-D18` verbs are members of the approved **38**-verb catalog (§1.1 rows 16–18 and 20–22 — row 19, `metric.declare`, is a `UI-D5` quarantine verb, not a `DM-D18` one), and `metrics.export`/`metrics.forceRaw` carry the high-consequence marking `architecture.md` §16 gave them. **Original statement (historical — superseded by the banner above; retained because it explains why the amendment exists):** ⚠️ **SCOPE CORRECTED 2026-08-19 — see item 24, which supersedes this framing.** The six `DM-D18` verbs are a *fraction* of the gap: the approved mockup enumerates **30 permissions of which only 2 are grantable**. This item remains as the metrics/dashboard slice; the console-wide problem is item 24. **`DM-D18` adds six RBAC verbs to LOCKED `D10`** — `dashboard.view` / `dashboard.edit` / `dashboard.reset` (already flagged by `SM-D31`) plus `metrics.query` / `metrics.export` / `metrics.forceRaw`, the last two high-consequence per `architecture.md` §16. `D10`'s catalog is policy-as-code and deny-by-default, so **a verb that does not exist cannot be granted** — the dashboards and export features are unbuildable until the catalog is extended. **FLAGGED, NOT RESOLVED (`I4`): `DM-D18` is PROPOSED and cannot amend a LOCKED decision.** Extending `D10` requires either the architect group amending `D10` or a new decision that supersedes it — never a `DM-D*` entry acting as if it had. | ✅ **CLOSED 2026-09-16** — subsumed by the ratified `D10` amendment (`L8`: the ratification date, not the 2026-09-15 authorization) |
| 24 | ✅ **CLOSED 2026-09-16 — this was G2, and the amendment is now RATIFIED and `LOCKED`.** [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md) **is** the `D10` amendment; ⛔ where old `D10` conflicts with it, the amendment controls. **The project owner is the final architecture authority** (§0), so no countersign was owed and **item 40 is closed too**. The catalog is **38** verbs with **three built-in roles**. ⚠️ **Two things the closure does not carry**, both named in that record §3 and §5: no **strategy-assertion** verbs and no **`group.*`** vocabulary (item 39 b/c). ✅ `instance.stop`, `audit.view` and `user.edit` **are** carried — they were withheld on 2026-09-15 and approved on 2026-09-16. Original statement (historical — superseded by the banner above; retained because it explains why the amendment exists):** 🔴 **`REQ-006` is unbuildable as recorded, and the recorded gap understates it roughly fourfold.** The approved mockup's own catalog (`frontend/reference/sm-v2/app/admin_data.jsx:8-19`) enumerates **30 permissions: 2 grantable, 6 proposed, 22 absent**. `D10` 🔒 is deny-by-default with a policy-as-code catalog and `REQ-006`'s acceptance is *"no endpoint is reachable without it"* — so with only `instance.bindTarget` and `target.use` grantable, **every core v1 screen is unauthorizable**: Designer (`config.view/edit/apply` absent), Governance (`health.view`, `logs.read` absent — and `logs.setLevel` is absent while `UI-D10` calls the loggers POST *"privileged and audited"*, privileged by a verb that does not exist), restart (`instance.restart`), metric typing (`metric.declare`), package load (`package.load`), and the Admin screens themselves (`role.*`, `user.*`). Item 23 and [`../requirements/traceability.md`](../requirements/traceability.md) recorded only the six `DM-D18` verbs, framing the consequence as *"dashboards and export are unbuildable"*; **the real consequence is that the console is unbuildable under its own acceptance criterion, or v1 ships endpoints outside the check.** Also undesigned and previously unrecorded: **first-admin bootstrap** (the mockup itself calls it *"the single largest hole"*, `admin.jsx:105`); the **local identity store** (`D13` 🔒 Keycloak deferred — user CRUD, credential policy, and *"admin-set temporary credential — the chosen option"* at `admin.jsx:87`, a decision made inside a mockup and recorded nowhere); the **audit log** (`deferred.jsx:53`: *"unspecified shape"*, while Governance copy already promises *"recorded in the audit log"*); **deactivate-never-delete** retention (`admin.jsx:137`); and who may call the **access-check / effective-permissions** introspection, which is a permission-enumeration surface. **A consolidated paper is drafted and awaiting the architect group: [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md)** — the 28-verb catalog, three bootstrap options costed, the minimal identity substrate, the audit log, and the introspection-scope question. It decides nothing (`I4`, `L8`); it exists so the group has something to approve or reject. **2026-08-31: the machinery the amendment assumes is now designed** — `BE-D15` ([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §5): `RoleBinding → Role → Permission` over the code-owned deny-by-default catalog, one `authorizationService.require()` enforcement point, the local substrate with `source` discriminator and deactivate-never-delete, the installer-seeded single-use first admin (the paper's own recommendation A), and the append-only audit log — so approving the amendment is one decision that makes the console buildable, not four interlocking ones. **The gate itself is unchanged: no auth-bearing endpoint ships before this group rules.** | ✅ **G2 CLOSED 2026-09-16** — the amendment is RATIFIED and `LOCKED`, not a one-off authorization (`L8`: the 2026-09-15 authorization said in its own terms that it was not ratification). Residue: **39** only — ⚠️ **NARROWED: three of the nine verbs approved, six letters remain.** ⛔ **40 is CLOSED too** (OR-1: the project owner is the final architecture authority), so it is no longer residue |
| 25 | **The approved mockup's own in-screen "invented — needs a product decision" notes were never routed here.** A JSX comment is not a route, and [`../checklists/spec-review.md`](../checklists/spec-review.md) requires that *anything undesigned is explicitly routed — never silently absent*. The set: the **`409` resolution controls** (`designer_panels.jsx:108-116`); **the entire dashboard document shape** (`dashboards.jsx:5-7,118`); the **per-replica row schema** (`designer_panels.jsx:31` — `renderedConfigVersion` and `restartObserved` are invented column names); the **`422` error body**; **`partial.unavailable[]`**; the metric catalog's missing **`firstSeen` / suggested-type**; and the **absent unit vocabulary**. Each needs either a contract addition or an explicit deferral with a stated degradation. | blocks Monitoring + apply UX |
| 26 | **Accessibility precedence is undecided, and `FE-D25` has quietly promoted a failing idiom to specification.** [`frontend/design.md`](frontend/design.md) §18 sets **WCAG 2.2 AA** for chrome and forms. The approved mockup's recurring interaction idiom is click-only `<div>`s: section headers (`designer.jsx:73` — `role="button"` and `aria-expanded` but no `tabIndex`, no key handler), `RolloutCard` header (`designer_panels.jsx:68` — no role at all), menu items, scope-tree nodes (`admin_iam.jsx:24`), metric-catalog rows (`explore.jsx:98`), instance rows with no-op checkboxes (`explore.jsx:120`), and sortable `<th>`s with `aria-sort` but no keyboard affordance (`grid.jsx:84`). The only precedence rule on record is §8.1's *"status never icon-only"*. **Someone must state that the AA bar subordinates the reference's interaction patterns**, or faithful transcription and the accessibility gate collide screen by screen. | blocks UX accept |
| 27 | **Three console-wide questions nobody has owned.** (a) **Series colour identity** — `charts.jsx:78` assigns palette entries positionally, so one instance wears different colours in adjacent widgets on the same dashboard; an incident-reading hazard, not a cosmetic one. (b) **Timezone** — `charts.jsx:11` renders every timestamp in UTC and no document decides this; an operator correlating against local logs hits it in week one. (c) **English-only, deliberately or by default** — every string in the mockup's 22 files is hardcoded English, and `FE-D25`'s *transcribe, do not paraphrase* makes copy contractual, so an i18n retrofit would invalidate the fidelity rule. Decide it rather than defaulting into it. | UX correctness |
| 28 | **The console has no threat model.** Session mechanics are decided (`FE-D24`, `FE-D14`) but nothing considers the console as an attack surface: CSP and clickjacking posture (§26 item 10 asks only about ECharts and `unsafe-eval`), **rate limiting on the query API** (the series cap protects legibility, not the server), **CSV export as an exfiltration channel**, the **on-device config-draft persistence** the login screen's approved copy promises (see [`frontend/reference/ERRATA.md`](frontend/reference/ERRATA.md) E6 — a draft may hold a secret typed but not yet converted to a `secretRef`), and **permission enumeration** via the access-check introspection. Also: **same-operator concurrency** — `FE-D18` covers two users, but two tabs of one operator each holding a React Hook Form draft is unexamined. | security |
| 29 | **Row expansion vs `FE-D11`'s Community pin.** [`frontend/design.md`](frontend/design.md) §9 and `FE-D11` pin **MUI X Data Grid Community**, and `instances.jsx:1` claims Community — but the screen's central interaction is per-row expansion into replica cards, and **master-detail (`getDetailPanelContent`) is a Pro feature** (verified at mui.com, 2026-08-19). The mockup dodged it by hand-rolling a custom `<table>` (`grid.jsx`), which is not MUI X at all — so transcribing literally produces a **second table implementation** against `FE-D4`, while honouring `FE-D11` literally **drops the expansion**. §8.1's licence verification checked column resize, never master-detail. Decide explicitly: drop expansion (link to a detail route), hand-roll one shared expansion wrapper, or make Pro a procurement item. | blocks Inventory |
| 30 | **Health-gate unsuitability needs a contract field, not a solution-identity flag.** `designer_panels.jsx:202` disables the rolling-health-gated restart strategy `if (model.isFlagship)`, with hardcoded Cache prose. The *condition* is real (open item 15); the *mechanism* is the per-solution branch `REQ-001` exists to reject, inside the one component meant to render every solution. No contract field carries it — `contracts/view-models/src/types.ts` has no `isFlagship` — and the **restart-strategy vocabulary itself** (`designer_data.jsx:193`, five strategies with semantic order and consequence levels) has no contract home either. The honest shape is a package-declared *health-gate-unsuitable + reason*. | blocks Designer · `REQ-001` |
| 31 | **Two Governance/Designer gaps with no contract behind them.** (a) **Log streaming has no replica dimension** — `governance.jsx:53` selects a component, but an instance has multiple replicas and `UI-D10`'s `/actuator/logfile` is **per-process**, so whose file streams is undefined. The same screen also conflates a **view filter** with an **audited production mutation**: choosing `DEBUG` in what reads as a level filter fires the `loggers` POST (errata **E13**). (b) **The composer has no engine support** — `UI-D20`'s composer screen (`designer.jsx:350-399`) needs a palette of declarable component types, cardinality rules and a node/edge view; `project()`'s `ViewModel` (`contracts/view-models/src/types.ts`) emits none of them. Phase 2, but previously unrouted. | blocks Governance logs · Phase 2 composer |
| 32 | ⚠️ **NARROWED 2026-09-16 — the cross-organization half is DECIDED; the lower-scope half is gated on measurement.** Ruling **OR-5**: the default isolation boundary is **one Message VPN per Organization**, and on **Solace Cloud** strict isolation means **one broker service per Organization** unless an explicit shared-service profile is approved. So multi-tenant *composition of one profile* is no longer the question it was — organizations do not share a VPN. ⛔ **What remains open is Sub-organization and Environment ACL behaviour inside the VPN**, which OR-6's topic shape makes expressible and which **nothing has yet measured**: the spike matrix is written in [`common/contracts/console-integration.md`](common/contracts/console-integration.md) §11.3 and is **NOT executed** (the demo environment is active). Original statement (historical): **Multi-tenant composition of `BE-D5`'s substitution-variable ACL is unmeasured and undecided.** The G1 spike ([`backend/spike-authz-result.md`](backend/spike-authz-result.md), 2026-08-27) chose candidate (a) — `sm/v1/<tenant>/stream/$client-username/>` on a profile bound to the VPN's `default` client username — and measured **zero broker writes per grant**. But **only the *username* level substitutes**: the tenant level is a topic literal, and a Message VPN has exactly one `default` client username, so **the zero-writes result holds for a single tenant only**. Three routes are priced in the result document (a VPN per tenant · a pre-created client-username per tenant · a tenant-bearing username grammar); **none is picked and none is measured**. Until one is, the mechanism `BE-D5` proposes has no demonstrated multi-tenant form and the install-time provisioning runbook cannot be written. **Decided by: the architect group** (not plan 002 — it changes what `BE-D5` says the broker is configured to do, not how SM calls it). **2026-08-31 — a v1 posture is PROPOSED, not decided:** `BE-D14` ([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §4.4) proposes the v1 walking skeleton is explicitly **single-tenant** — the exact shape G1 measured — so this item gates **multi-tenancy** (before any second tenant onboards), not the skeleton. The ruling remains this group's. | **blocks `BE-D5` ratification · blocks the provisioning runbook** |
| 33 | ⚠️ **NARROWED 2026-09-16 — OR-5/OR-6 replace the premise.** The notify-tier exception was unexpressible because design §4's topics had no per-subject level to substitute at. **OR-6 gives the taxonomy organization, sub-organization and environment levels**, so a scoped notify exception is now expressible — ⛔ **but not measured.** Until the §11.3 spike runs, the exception *shape* is decided and its *broker behaviour* is not. `BE-D5` still cannot ratify. Original statement (historical): **The notify-tier exception shape under `BE-D5`'s chosen mechanism is unresolved.** Substitution scoping needs a per-subject topic level to substitute at, and **design §4's notify topics have none** — `sm/v1/<tenant>/notify/instance/<instanceId>/<aspect>` is keyed by the entity, not by the subscriber — so a substitution-scoped notify exception is **not expressible** in the mechanism candidate (a) selected. Three options are priced in [`backend/spike-authz-result.md`](backend/spike-authz-result.md) with the first recommended; **none is chosen**. Notify is the tier every logged-in browser subscribes to at login, so this is not a Phase-2 corner: it decides what a browser's default subscribe ACL actually is. **Decided by: the architect group**, with item 32 — the two compose, and picking either alone can invalidate the other. **2026-08-31 — the spike's recommended option is now formally PROPOSED for v1:** `BE-D14` ([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §4.4) proposes tenant-wide notify visibility, stated as a runbook limit (`I6` already bars values from notify payloads). The ruling remains this group's. | **blocks `BE-D5` ratification · blocks the provisioning runbook** |
| 34 | ⚠️ **NARROWED 2026-09-16 — the G2 half is answered, the id half is not.** *"The answer decides whether G2 is already closed"* no longer holds: G2 is closed by [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md) on this baseline's own evidence, irrespective of what `origin/dev/frontend`'s `D18`/`D19`/`D20` rows are. **What remains is the id-allocation question**, and it is now load-bearing twice over: the 2026-09-15 amendment deliberately took **no `D*` number** because `D18` is already spoken for on that branch, and the frontend's shipped `catalog.ts` cites `D18` 🔒 for a **different** 35-member catalog than the approved one (that record §6). **Original statement (historical — superseded by the banner above; retained because it explains why the amendment exists):** ⚠️ **Two registers disagree about what is LOCKED.** `origin/dev/frontend` records three **new parent-family** decisions — **`D18`** (the ratified 35-operation permission catalog, 2026-09-04), **`D19`** (v1 local auth and the Bootstrap Super Admin, 2026-09-04) and **`D20`** (no Tenant entity; Organization is the top isolation level, 2026-09-07) — each marked **LOCKED**, and `D20` states it *"supersedes `D11`'s hierarchy clause and all older parent-decision tenant terminology"*. **Nothing in this baseline corroborates an architect-group ruling on any of the three**, and the `D*` family is declared **closed at `D1`–`D17`** here and in `CLAUDE.md`, so the ids themselves are outside the family's stated range. Two readings, and this register cannot choose between them: either the group did ratify and this baseline is stale, in which case **open item 24 (G2) is closed** and every auth-bearing endpoint is unblocked — or the marks were recorded without a ruling, which is precisely the `I4`/`L8` failure the status legend was written to prevent. ⚠️ **The consequence is asymmetric and large:** `BE-D15` §5.4 holds that until G2 lands *"no auth-bearing endpoint ships"*, so this single question decides whether login, the ticket endpoint and the whole authenticated REST surface are buildable now or blocked. [`D11-AMENDMENT-PROPOSAL.md`](D11-AMENDMENT-PROPOSAL.md) deliberately neither adopts nor contradicts the record; the intake report ([`../tasks/dev-frontend-intake.md`](../tasks/dev-frontend-intake.md) §4) treats the substance as input only. **Needed: the architect group confirms or denies the three rulings, and if confirmed, the ids are re-homed into a family that is open.** | ⚠️ **NARROWED 2026-09-16** — the G2 half is answered; the **id-allocation** half and the competing `D18` 🔒 catalog still block register intake from `dev/frontend` |
| 35 | ⚠️ **NARROWED 2026-09-16 — the architecture is decided, the enforcement is unmeasured.** ⛔ **Organization-wide notify visibility is no longer acceptable**, and OR-6's topic shape is what replaces it: sub-organization and environment appear as real topic levels, so an environment-scoped principal's subscription can be confined to what its REST scope permits. ⛔ **The ACL behaviour that would enforce it is UNMEASURED** — §11.3's matrix (two organizations × two sub-organizations × two environments, with explicit allow/refuse expectations per leg) is written and **not executed**, because the demo environment is active. Until it runs and passes, **live notify may not be enabled for any shared or customer install**, and the EDA acceptance criterion cannot be met. Original statement (historical): ⚠️ **ESCALATED 2026-09-15, not resolved.** The [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md) §1.2 approves scope-evaluated bindings with downward inheritance, so principals scoped **below** the organization now exist **by decision** rather than hypothetically: REST would enforce environment scope and the broker, under the organization-wide notify posture proposed in [`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §4.4, would not. This bounds what plan 007 may publish to the notify tier. Original statement: **Environment-level isolation on the mesh is undecided, and `SM-D32` creates the surface that needs it.** Topics are `sm/v1/<organization>/…` and open item 33's proposed v1 posture is **organization-wide notify visibility**. `SM-D32` introduces principals legitimately scoped *below* the organization — a Developer granted at `[org, unit, dev]` — so under an organization-scoped subscribe ACL that browser can subscribe to **PROD** notify events its REST scope forbids it to read. **REST would enforce environment scope and the broker would not.** `BE-D5`'s chosen mechanism (candidate (a), the substitution-variable ACL) substitutes only at the client-username level and the topics carry no environment level to scope at — structurally open item 33, one level down. Three shapes are available and **none is chosen**: an environment level in the taxonomy (a `BE-D4` change, riding G3); an environment-bearing client-username grammar (composes with item 32's routes and cannot be picked independently of it); or notify payloads carrying nothing environment-distinguishing, which is a confidentiality claim about payload contents where `I6` today guarantees only that *values* are absent. **The v1 demo seed has one environment, so the exposure is not reachable — that is a property of the seed, not of the design, and it ends the moment a second environment exists.** Gate: decide before (a) a second Environment holds data any principal is scoped away from, or (b) direct-browser-mesh ships beyond the single-organization demo. **Decided by: the architect group**, with items 32 and 33 — the three compose. | **blocks multi-environment installs · blocks browser-mesh beyond the demo** |
| 36 | **The identity-vocabulary rename must land as one coordinated release, and part of it is a gated contract change.** `SM-D34` makes `organization` the external vocabulary while `tenant_id` stays the storage key. Three distinct pieces, deliberately not bundled: **(a)** the console REST surface, which as built uses three spellings for two concepts — `organization` on auth/user/group payloads, `{organization}`/`{subOrganization}` in cascade paths, and `org`/`subOrg`/`env` on instance rows — and whose own as-built record warns *"a backend must not normalize them unless the frontend changes in the same release"*; **(b)** the RBAC `scope: string[]`, which as built carries **names** and under `SM-D33` must carry **ids**, so a renamed unit cannot silently move a grant — a **behavioural** change, not a spelling change; **(c)** `contracts/events/envelope.schema.json`'s required `tenant` field and the `sm/v1/{tenant}/…` topic parameters, which `origin/dev/frontend` has already renamed to `organization` on its own branch. **(c) is single-writer and gated**: the envelope is check-10-gated, mirrored under `BE-D10`'s key locks by `mesh-events`, and `BE-D4` already carries four pending riders — so it rides the `BE-D4` amendment (G3), never a cherry-pick. | **blocks the vocabulary rename · (c) additionally blocked on G3** |
| 37 | **`BE-D12` names two different decisions on two branches, and both allocations were made in good faith.** At the merge base the family ended at `BE-D11`; this baseline then allocated **`BE-D12` = the `dp-remote` apply protocol** (implemented, plan 004) while `origin/dev/frontend` independently allocated **`BE-D12` = "Organization replaces Tenant at every backend boundary"**. The collision is structural, not careless — but `I3` makes ids immutable and citations must never shift meaning, and this baseline's `BE-D12` is cited in **6 files / 29 lines** outside its own defining file (verified by [`../tasks/evidence/dev-frontend-intake-census.sh`](../tasks/evidence/dev-frontend-intake-census.sh) §4). **The `BE-D` collision surface is exactly one id** — `origin/dev/frontend`'s backend family ends at `BE-D12`, so there are no `BE-D13`+ allocations there to shift (an earlier draft of this item said there were; verified by the census §4, which now lists theirs' full `BE-D` set). This baseline runs to `BE-D20`. **The `FE-D` family is worse and diverges earlier:** ours and theirs disagree on **`FE-D25`** (ours *"sm-v2 is the visual specification of record"*, theirs *"Idle-lock draft preservation"*) and **`FE-D26`** (ours *"the verified product palette governs UI semantics"*, theirs *"Desktop-only ≥ 1280 px — SUPERSEDED"*), so *"per `FE-D26`"* already means two different things — and theirs' `FE-D29` supersedes *their* `FE-D26`, which on this baseline is a live palette decision, while theirs' `FE-D52` restates ours' `FE-D26` verbatim under a new number. The census compares the families id-by-id rather than by their maxima, so this figure is derived, not eyeballed. **No decision-register file may be cherry-picked from `dev/frontend` until this is re-homed**; the substance of their `BE-D12` is absorbed by `SM-D34` and needs no `BE-D` id at all. | **blocks decision-register intake — resolve before any `docs/design/**/decisions.md` import** |
| 38 | ✅ **CLOSED 2026-09-16 — moot.** ⚠️ **(dated 09-16, not 09-15: ruling OR-1 and the ratification are what closed it; an earlier revision of this row said 09-15)** The dispute was over what an **open** G2 gates: `CLAUDE.md` barred auth-bearing *code*, `BE-D15` §5.4 barred only a shipped *endpoint*, and the stricter reading controlled in the interim. G2 is now closed ([`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md)), so neither reading gates anything: auth-bearing code **and** endpoints may be built within that record's bounded scope, and outside it the limit is the closed catalog, not this gate. `CLAUDE.md` is corrected accordingly. **Original statement (historical — superseded by the banner above; retained because it explains why the amendment exists):** ⚠️ **`CLAUDE.md` and `BE-D15` disagree about what G2 gates, and the difference is most of a plan.** `CLAUDE.md` states *"**no auth-bearing backend code** exists or may be written before G2"* and, in its own open-items list, *"every auth-bearing backend **task** waits on it"* — the **code** is barred. `BE-D15` §5.4 bars only exposure: *"no auth-bearing **endpoint** ships … the machinery may exist as a **tested library** only"*, *"building a library is not shipping an endpoint"*. Under the first reading the authorization service, the permission registry, `principal`/`credential_hash`, `role_binding`, the canonical `scope` table and the auth audit log all wait for G2; under the second they are buildable today. **Interim ruling, recorded in [`../tasks/demo-delivery-sequence.md`](../tasks/demo-delivery-sequence.md) §2.1: the stricter rule — `CLAUDE.md` — controls.** `BE-D15` is **PROPOSED**, and per `I4` an unratified decision does not narrow a standing project rule; its endpoints-only reading is a proposal *about the gate's scope*, not a ruling on it. ⚠️ `BE-D15`'s text is **not edited** (`I3`) and its status is unchanged. **This holds until the architect group either ratifies G2 (open item 24) or issues a signed, narrow, written waiver naming the exempt components and an expiry** — a waiver being a document, not a verbal go-ahead and not an inference from a deadline. Resolving item **34** may moot this entirely. | ✅ **CLOSED 2026-09-16 — moot**; G2 is closed, so neither reading gates anything |
| 39 | ⚠️ **NARROWED 2026-09-16 — three approved, the rest prepared as ONE follow-on amendment.** ⚠️ **The lettering below is the [`D10` amendment](D10-G2-APPROVAL-2026-09-15.md) §5 item 3's, unchanged** — an earlier revision of this row silently re-lettered it, which broke eleven live citations and is exactly the `I3` drift *"a citation must never shift meaning"* forbids. Struck letters keep their letter. ✅ **Approved** (ruling **OR-9**, catalog rows 36–38): ~~**(a)** `instance.stop`~~ (instance/placement scope, HIGH, audited, idempotent) · ~~**(f)** `user.edit`~~ (profile/login, HIGH; status stays `user.deactivate`) · ~~**(g)** `audit.view`~~ (scope-filtered, server-redacted, STANDARD). ✅ The attribute question that rode this item is also closed: `user.deactivate` is **HIGH** (**OR-13**). ⛔ **Still PROPOSED — not approved, each needing one owner answer:** **(b)** high-consequence **strategy-assertion** verbs — `architecture.md` §16 names *"assert `restart-all`, `rolling-no-validation`"* and the catalog has none, so **those two strategies are unselectable**; ⚠️ [`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §8 row 12 **now routes it here directly** — an earlier revision of this text said row 12 routed it to *item 24* and therefore re-routed here; row 12 has since been corrected, so the re-route is no longer needed — *is overriding a strategy's safety a verb of its own, or a confirmation on `config.apply`?* **(c)** `group.view` / `group.create` / `group.edit` / `group.status.edit` / `group.member.manage` with consequence classes — *are groups a v1 concept at all, given `D13`'s IdP deferral, and does a group grant roles or only collect principals?* (the shape differs from `catalog.ts`'s shipped five: `group.status.edit` replaces `group.deactivate`) **(d)** `instance.undeclare`, HIGH — *inventory removal must not silently stop the workload: does undeclare require a prior stop, refuse while running, or leave it running and orphaned?* **(e)** **package actions** — *fixed metadata and authorization semantics must be defined **before** any generic execute permission; a `package.action.execute` that authorizes whatever a package declares is an unbounded grant.* **(h)** **Agent Gateway** — *enumerate the actual human actions first; `agent.*` verbs before those actions are named would be verbs for a UI nobody has designed.* **(i)** `instance.version.redeclare`, HIGH, **and archive/restore** (`architecture.md` §16) — *which resource is re-declared or archived? Identify it first; do not create a generic permission.* ⚠️ **Also still open: `instance.restart.overrideSafety`**, HIGH — folded into (b), since it is the same question asked from the other side. Each corresponding operation answers `501`, never `403` ([`common/contracts/console-integration.md`](common/contracts/console-integration.md) §5.6 rule 5). | **owner — one follow-on amendment; blocks the named API operations, blocks nothing already ratified** |
| 40 | ✅ **CLOSED 2026-09-16 — ruling OR-1.** **The project owner is the final architecture authority**; no separate countersign was ever owed, and the `D10` amendment is **ratified and `LOCKED`**. The standing rule that replaces this question: a `LOCKED` decision changes only by an **explicit written owner architecture ruling naming the affected decision and the scope**, and **ordinary implementation approval is never an amendment**. The 2026-09-15 one-off authorization is **historical provenance**, not a precedent. Original statement (historical): ⚠️ **Who may amend a `LOCKED` parent decision?** [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md) §5 states *"It does not amend `D10`. **Only the architect group can.**"* and its header invokes `I4`. The [2026-09-15 authorization](D10-G2-APPROVAL-2026-09-15.md) is the **project owner's** — its own words: *"explicit written authorization in the project session"* — and it **authorizes implementation against** `D10`'s catalog leg **without amending it**, which it states in terms and calls a one-off. Both statements now stand in this tree; the approval flags the conflict rather than resolving it (`I4`). Two questions, once: **(1)** does the group counter-sign, amend or overrule the 2026-09-15 authorization, and **formally ratify the `D10` amendment** (which the authorization deliberately did not do)? **(2)** what is the **standing rule** for authority over locked decisions — the 2026-09-15 record declares itself a **one-off for G2**, so this is a question about the future, not about that record? Until answered, read the authorization as *sufficient to unblock work in this repository* and the `D10` amendment as **unratified**. ⚠️ Nothing is un-built by this item: it governs the **status** of the work, not its permissibility. | **governance — architect group; blocks calling the amendment "ratified", blocks nothing being built** |

⚠️ **Reading an existing citation of "open item 19".** Item 19 was one item answering two questions; the sub-specs cite
it for both. Resolve a citation by what it claims, not by its number:

| A citation that says | Resolves to | Note |
|---|---|---|
| the **visual system / brand tokens / DS reference** is missing — `FE-D12`'s visual-regression gate, `frontend/design.md`'s "do not start code" list | **19b — narrowed 2026-08-19** | ⚠️ **Re-read before citing.** A reference now exists (`FE-D25`) and the palette is verified (`FE-D26`), so "missing reference" is stale: 19b is now only the *designer's confirmation*. `FE-D5`'s paint clause and `design.md`'s typography and contrast rows have been superseded or amended accordingly and no longer read as "missing" |
| open item 19 **blocks the frontend sub-spec**, the component library, or the query-cache choice — `dynamic-metrics/design.md` §11 and §21 item 5, `dynamic-metrics/decisions.md` `DM-D13` | **19a — CLOSED** | **stale.** The sub-spec exists and `FE-D4` settled the stack; only ratification of that reading remains |
| the **stack reading must be accepted before the walking skeleton** — `frontend/design.md`'s UNBASELINED header | **19a — CLOSED, ratification pending** | accurate in substance: the reading is settled in the spec, unratified by the group |

Sub-spec text is not edited from here — those citations are corrected in their own files.
