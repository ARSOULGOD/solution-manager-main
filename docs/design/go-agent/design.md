# Go-Agent Component Design

**Component:** go-agent · **Locus:** `host-local` · **Stack:** Go
**Date:** 2026-08-13 · **Revised:** 2026-08-18 (R1 — audit corrections, §14)
**Status:** DRAFT. **PROPOSED, not ratified. No code.**
**Decisions:** [`decisions.md`](decisions.md) — `AG-D1`…`AG-D12`, all `PROPOSED`.

> ⚠️ **Nothing in this document may be implemented as settled (`I4`).** Its entire base — `SM-D7`, `SM-D15`,
> `SM-D19`, `SM-D21`, `SM-D24`, `SM-D25`, `SM-D28` — is `PROPOSED`, and one of those proposals revises a
> `LOCKED` decision. See §0.

---

## 0. Flagged conflicts and unratified base

### 0.1 Flagged conflict — the agent session is re-homed by a `PROPOSED` decision over a `LOCKED` one

⚠️ **Flagged, not resolved (`I4`).**

| This document | Against | Disposition |
|---|---|---|
| §3, §4 place enrollment, lease renewal, generation delivery and report-actual on an **agent ↔ Data Plane** session | **`B/D16` is `LOCKED`** and defines the session as **agent ↔ Control Plane** ([`../DECISIONS.md`](../DECISIONS.md) — `B/D16`, §8) | The re-homing is **`SM-D24`** ([`../DECISIONS.md`](../DECISIONS.md) — `SM-D24`, §10), which is `PROPOSED`; the authority model completing it is **`SM-D28`** (§10), also `PROPOSED`. **The locked decision stands until the architect group rules.** This document is written against `SM-D24`/`SM-D28` because they are the only account of the DP-owned-config architecture, **not** because the question is closed. |
| §4.2 lease semantics, `RENEW_DEGRADED` / `LEASE_EXPIRED` / `REVOKED` / `CLOCK_SUSPECT` / `ADMISSION_BLOCKED` / `RE_ENROLL_REQUIRED` | `B/D16` states only "short authorization lease it must renew" | `SM-D28` is a `PROPOSED` elaboration, not a contradiction. Recorded here so the dependency is visible. |
| §5.4 retains `APPLY_INDETERMINATE` on the `host-local` path, **narrowly scoped** | [`../frontend/decisions.md`](../frontend/decisions.md) `FE-D18` **clause B** makes `APPLY_INDETERMINATE` the **`dp-remote`** degradation, *because* `host-local` carries `B/D16`'s guarantees | Not a conflict once scoped: see `AG-D5`. The wide reading in this document's first draft **was** a conflict, and is retracted in §14. |

**What follows from the flag, concretely:** if the architect group upholds `B/D16` unchanged, §3 and §4 re-home to
the CP and §5's persisted state is unaffected — the apply machinery is locus-bound, not plane-bound. If it ratifies
`SM-D24`/`SM-D28`, this document stands as written. **Either way the `.proto` does not exist**
([`../architecture.md`](../architecture.md)`:837`, open item 6 in [`../DECISIONS.md`](../DECISIONS.md)), so
no wire-level work can start.

### 0.2 The unratified base, itemized

| Depends on | Status | If it does not ratify |
|---|---|---|
| `SM-D7` — two-role agent with mTLS identity and scoped authz is v1 scope, not deferred | PROPOSED | §3 has no anchor; the agent has no identity model |
| `SM-D15` — restart is operator-selected, **`manual` by default** | PROPOSED | §5.3 collapses; see the note below |
| `SM-D19` — Solution Package carries lifecycle verbs, config mechanism, uniformity flag, telemetry contract | PROPOSED | §5.1, §6.1 have no input format |
| `SM-D21` — the config path is **per-artifact and per-runtime, discovered by pre-flight** | PROPOSED | §5.1's declared path becomes a guess |
| `SM-D24`, `SM-D28` — session and authority at the DP | PROPOSED | §3, §4 re-home to the CP per `B/D16` |
| `SM-D25` — Kubernetes apply via **generation-named immutable ConfigMaps**, single declared writer | PROPOSED | §5.1's Kubernetes row is unspecified |

⚠️ **`SM-D15` is the load-bearing one.** It is `PROPOSED`, but the manual default is already depended on by two
sibling documents: [`../frontend/design.md`](../frontend/design.md) §10.1 stages config and waits for
`restart-observed`, and [`../dynamic-ui/decisions.md`](../dynamic-ui/decisions.md) `UI-D7` gates metric collection
on the last **`restart-observed`** configuration *because* restart is manual by default. An agent that restarts on
its own falsifies both. See §5.3.

### 0.3 Reading order

Read [`../architecture.md`](../architecture.md) §6 (config ownership), §9 (restart & apply), §10 (telemetry
acquisition) and §13 (capability limits) before this document. A component design may refine the architecture; it
may not contradict it.

---

## 1. Scope and non-goals

**In scope.** One Go binary implementing the `host-local` locus: enrollment and mTLS identity, the agent↔plane
protocol state machine, crash-safe configuration apply, lifecycle execution **on command**, local telemetry
collection and forwarding, runtime version observation, Kubernetes leader election, and the agent's own health
reporting.

**Out of scope, explicitly.**

| Not in this component | Where it lives |
|---|---|
| `dp-remote` — SSH / Docker API / K8s API reach from the DP | Backend. Its apply protocol is **open item 1**, a ratification blocker (open item 1 in [`../DECISIONS.md`](../DECISIONS.md)) |
| Restart **strategy** selection, ordering, health gating, `CONFIG_DIVERGED` remediation | DP + console — [`../architecture.md`](../architecture.md)`:739`, [`../frontend/design.md`](../frontend/design.md) §10.1. See §5.3 |
| Artifact deployment | The customer's CI/CD (`SM-D2`). The agent never pulls, unpacks or places an artifact |
| Config authoring, validation, versioning, rendering | CP authors and validates; **the DP renders and owns generations** (`SM-D1`). The agent receives bytes plus a hash, and writes them |
| Rollup, retention, archive, query | DP storage layer |
| Any change to a managed integration | **Forbidden by `I1`** — see §10.1 |

**Deferred, recorded:** CP-orchestrated agent self-upgrade and canary rollout across large fleets (`SM-D7` defers
exactly this and nothing else); `scheduled` workloads, cut from v1 because a restart window hours out cannot fit
inside `SM-D28`'s one-hour lease ([`../DECISIONS.md`](../DECISIONS.md), supersession matrix).

---

## 2. Roles and topology

Per the agent topology note at [`../DECISIONS.md`](../DECISIONS.md) §8, agent-topology note (`D8` / `B/D2`):

| Role | Cardinality | Responsibility |
|---|---|---|
| **control** | **1 per Target/cluster** — *not* per node; HA by leader election (§8) | Receives generations, applies config (§5), executes lifecycle verbs **on command** (§5.3), reports actual |
| **telemetry** | **scales with nodes** — DaemonSet, **one collector per node, not per pod** | Collects locally, normalizes, buffers, pushes (§6) |

Both roles are compiled into one binary and enabled independently at startup (`AG-D1`). On a single host both run
in one process.

**Why the roles are separable at all:** they fail differently and are secured differently. A control role holds
`apply-config` and `lifecycle` capabilities in its lease; a telemetry-only role holds neither. Collapsing them
would give every node in a cluster the authority to restart production processes.

**Collector isolation is a hard requirement, not a quality goal** — "a bad collector fails alone, never the
process" ([`../architecture.md`](../architecture.md)`:784`). A panicking or wedged collector must not take down
the apply path sharing the binary.

---

## 3. Identity, enrollment and authority

### 3.1 Enrollment

Ownership per `SM-D28`: **CA, certificate issuance, enrollment approval and the revocation epoch belong to the
CP; identity validation, lease holding/renewal, generation delivery and report-actual belong to the DP.**

Routing is **agent → DP → CP** ([`../DECISIONS.md`](../DECISIONS.md) — `SM-D28`, §10), deliberately: the agent never
needs a route to the CP, which is what preserves the on-prem posture where only the DP is reachable from the MI
runtime.

1. The operator installs the agent with a **single-use, target-bound enrollment token** (`B/D16`).
2. The agent generates a keypair and CSR locally. **The private key never leaves the host and is never
   transmitted** — it is written mode `0600`, owned by the agent's service account.
3. The agent presents token + CSR to the DP; the DP relays to the CP; the CP approves and signs; the DP relays the
   certificate back and caches the trust bundle.
4. The agent opens an mTLS session to the DP and requests its first lease.
5. **A separate capability-restricted report credential** (`capabilities: [report-only]`) is issued at
   enrollment (`SM-D28`), so an agent that has lost apply authority is still *observable*.

**Cert rotation is manual for v1** (`B/D16`). **The lease, not the certificate TTL, bounds authority.**

**Trust-bundle rotation** never requires a synchronized restart: the CP publishes with overlapping validity, the
DP serves both during the overlap, the agent accepts either.

### 3.2 The lease is the authority, and it is bound to the session

The agent holds a short CP-issued lease that the DP renews (`SM-D28`). Claims that the agent must enforce
locally, not merely receive:

| Claim | Agent obligation |
|---|---|
| `target_id` | Refuse any command naming a different Target |
| `binding_ids[]` | Refuse any command for a binding not listed |
| `revocation_epoch` | **Refuse any lease carrying a lower epoch than the highest seen** (monotonic, persisted) |
| `expires_at` (absolute UTC) | Never compute a duration; compare against local clock and **report the offset** |
| `audience` = DP instance id | Refuse a lease minted for another DP |
| `nonce`/`jti` | Single-use at renewal |
| `capabilities[]` | `apply-config` gates §5.1; `lifecycle` gates §5.3; neither is implied by the other |

**The lease binds to the agent's mTLS public key, not to a textual `agent_id`** — a lease lifted from one agent is
unusable by another even under the same claimed id.

**Revocation epochs are subject-scoped:** a per-agent epoch keyed by key thumbprint, and a fleet epoch used only
for deliberate mass revocation. The agent must treat these as distinct; conflating them turns one compromised host
into a fleet-wide outage.

---

## 4. Protocol and state machine

### 4.1 Transport

gRPC over HTTP/2 on mTLS, **agent-initiated and outbound-only** — the property that makes `host-local` the answer
to overlapping CIDR, NAT and LB topologies ([`../architecture.md`](../architecture.md)`:806` ff.). Library choice
and its rationale: `AG-D11`.

⚠️ **The wire contract does not exist.** No `.proto`, no message set, no state-machine spec —
[`../architecture.md`](../architecture.md)`:837` and open item 6 in [`../DECISIONS.md`](../DECISIONS.md),
which **blocks all v1 agent work**. This document specifies *obligations*, not encodings. `B/D16`'s HTTP/1.1
long-poll fallback is **not designed here** and is recorded as open item AG-6 (§13.2).

### 4.2 Partition state machine

Directly from `SM-D28`; reproduced because the agent is the enforcing side.

| State | Trigger | Agent may | Agent may **not** |
|---|---|---|---|
| `ACTIVE` | lease valid, renewal succeeding | apply · lifecycle · report | — |
| `RENEW_DEGRADED` | renewal failing, lease not yet expired | **reconcile cached desired state** · report | **accept a new generation** |
| `LEASE_EXPIRED` | past `expires_at` | **local, atomic cleanup only** (remove a temp file, revert a rename it already staged) · report on the report credential | write config · restart a process · invoke any remote command |
| `REVOKED` | in-band `Revoke`, or an observed epoch increment | stop immediately; **discard cached desired state** | everything |
| `CLOCK_SUSPECT` | local clock outside the CP's stated tolerance | as `LEASE_EXPIRED` — **fail closed** | as `LEASE_EXPIRED` |
| `ADMISSION_BLOCKED` | insufficient lease remaining for the next step's `verb_ceiling` | report the reason | start the step |
| `RE_ENROLL_REQUIRED` | cert expired, or CP rejects the identity | re-enroll | act on cached state |

**Every state has an exit** — `SM-D28`'s recovery table is normative. `CLOCK_SUSPECT` and `RE_ENROLL_REQUIRED`
are **not** absorbing states; an NTP hiccup must not park an agent permanently.

**Renewal:** attempted at 50% elapsed of a **one-hour** lease; hard expiry at `expires_at`. Backoff and jitter are
unspecified — open item AG-5.

### 4.3 Expiry bounds work, not only new work

Three rules, all `SM-D28`, all enforced **by the agent**:

1. **Admission control.** A step starts only if `expires_at − now ≥ verb_ceiling + margin`, where `verb_ceiling`
   is a **platform-configured maximum per lifecycle verb** — *not* a package declaration. (A package field would
   require MI cooperation SM cannot compel; §9 of the architecture states warm-up duration is something SM "cannot
   know and cannot require anyone to declare".) A refused admission enters `ADMISSION_BLOCKED` **with the reason
   surfaced**, never a silent defer.
2. **Every step carries an absolute deadline**, `min(step_start + max_step_duration, expires_at)`, enforced by
   process kill, SSH-channel close or watch cancel. **There is no unbounded wait anywhere in the apply path.**
3. **After expiry, local atomic cleanup and reporting only** — on the report credential.

### 4.4 Reports from an untrusted state are displayed, never believed

The report credential carries the **same `revocation_epoch`** and is revoked on the same path. Reports emitted
from `LEASE_EXPIRED`, `REVOKED` or `CLOCK_SUSPECT` are **labelled untrusted in the store and may never satisfy a
convergence gate** (`SM-D28`).

**This is load-bearing for the agent specifically:** `restart-observed` — the one signal that closes
`RESTART_EXPECTED`, clears `PENDING_RESTART` and resolves `CONFIG_DIVERGED`
([`../architecture.md`](../architecture.md)`:467`, `:357`) — is **agent-reported**. Without the untrusted label a
compromised agent could forge convergence evidence after revocation, and the console would show "configuration
applied" for config never written.

---

## 5. Configuration apply

### 5.1 Mechanism

The agent receives **rendered bytes plus a content hash**, never a schema and never a template. Rendering is the
DP's (`SM-D1`); the agent's job is placement and durability.

| Concern | Rule |
|---|---|
| Mechanism | `additional-location` (preferred) or `overwrite-in-place`, declared per solution and overridable per Target (`SM-D3`) |
| Path | **Declared per artifact and per runtime, discovered by pre-flight** (`SM-D21`) — not a generic launch variable |
| Pre-flight | Runs **per binding, before every apply**, not once at Target enrollment ([`../architecture.md`](../architecture.md)`:344`). The launch command, the mount and the competing-source set can all change between applies |
| Docker | bind mount or named volume at the declared path |
| Kubernetes | **new generation-named immutable ConfigMap per config version**, then update the workload's reference (`SM-D25`). Never patch a ConfigMap. `subPath` mounts and `envFrom` **never receive updates** and are unsupported |
| systemd | a real host directory, with the unit's config dir declared |
| File semantics | write temp → `fsync` file → `rename` → `fsync` parent directory. Mode `0600`, owned by the service account |
| Writer ownership | On Kubernetes, `{binding, resource_kind, writer: sm \| gitops}` is declared, and **the agent refuses to write any resource it is not the declared writer of** (`SM-D25`) |

**The agent does not auto-revert drift.** Hand-edits are detected by comparing the persisted file against the
rendered content and are **alert-only with manual UI reconcile** — "**never silently auto-revert a human**"
([`../architecture.md`](../architecture.md)`:418`).

### 5.2 Apply is crash-safe and idempotent — `AG-D4`

**`B/D16` is `LOCKED` and requires this of `host-local`:** apply is "crash-safe and idempotent — persisted
generation state, idempotency keys, deterministic resume, explicit cleanup of superseded partials"
([`../DECISIONS.md`](../DECISIONS.md) — `B/D16`, §8). [`../architecture.md`](../architecture.md)`:181` states these are
**properties of the `host-local` path**. **The go-agent is that path**, so these are its obligations to provide,
not properties it may assume something else supplies.

**Journal.** Before any filesystem or lifecycle action, the agent appends a record to a local, `fsync`-ed apply
journal in its state directory:

```
apply_record {
  rollout_generation   # monotonic, DP-assigned; per binding
  idempotency_key      # DP-assigned; stable across retries of the SAME intent
  binding_id, target_path, config_hash
  step                 # RECEIVED -> STAGED -> PERSISTED -> REPORTED
  step_deadline        # absolute UTC, per §4.3 rule 2
  temp_path            # while STAGED
  outcome              # terminal only
}
```

**Monotonicity.** A generation lower than the highest **durably recorded** generation for that binding is
rejected, not applied late. This is what makes two in-flight applies safe to reorder on the wire.

**Idempotency.** A repeated `idempotency_key` whose `config_hash` matches the recorded one is a **no-op that
re-reports the recorded outcome**. A repeated key with a *differing* hash is a **protocol error** — the DP reused
a key for a different intent — and is refused rather than guessed at.

**Deterministic resume.** On start, the agent replays the journal. Recovery is a function of the last recorded
step, with no operator input:

| Last step | Resume action |
|---|---|
| `RECEIVED` | discard; the DP will redeliver. Nothing was written |
| `STAGED` | verify `temp_path` against `config_hash`: matching ⇒ complete the rename; mismatched or missing ⇒ delete and re-request |
| `PERSISTED` | re-read the file, verify the hash, re-report. **Do not rewrite** |
| `REPORTED` | nothing; the record is closed |

**Explicit cleanup of superseded partials.** At startup and after every successful apply, temp files belonging to
a generation below the last persisted generation for that binding are deleted, and the deletion is journalled.
Orphaned temps are a silent disk leak and, worse, a future resume's ambiguous input.

**Kubernetes variant.** The same journal, with `temp_path` replaced by the generation-named ConfigMap name.
Resume from `STAGED` = the ConfigMap exists but the workload reference was not updated; resume from `PERSISTED` =
both done. ConfigMap GC retains the last N generations for rollback and **never deletes one still referenced by a
live or paused workload** (`SM-D25`).

### 5.3 The agent never infers a restart — `AG-D3`

> **The agent writes configuration. It restarts a managed integration only on an explicit lifecycle command from
> the Data Plane, carrying the `lifecycle` capability in the current lease. A config write is never, in itself, a
> reason to restart anything.**

⚠️ This document's first draft said the Configuration Applier "triggers restart of managed integration when
configuration changes." **That is retracted** (§14, R1-1). It contradicted `SM-D15` and would have broken three
mechanisms in sibling documents.

**Why the rule is the rule:**

- **`SM-D15` — restart is operator-selected, `manual` by default** ([`../DECISIONS.md`](../DECISIONS.md) — `SM-D15`, §3).
  [`../architecture.md`](../architecture.md)`:714`: *"SM must not encode a restart strategy it cannot verify —
  guessing wrong takes down production."* Restart behaviour depends on replica independence, HA model, warm-up
  duration and standby promotion — properties SM cannot know and cannot require anyone to declare.
- **Under `manual`, writing the file is the whole operation.** The instance enters `PENDING_RESTART`
  (*"Configuration staged · restart required · N replicas pending"*), the operator restarts through their own
  process, and SM detects `restart-observed` ([`../architecture.md`](../architecture.md)`:716`). An
  auto-restarting agent makes `PENDING_RESTART` **unreachable**.
- **The console depends on it.** [`../frontend/design.md`](../frontend/design.md) §10.1 stages config and waits for
  `restart-observed`, and suppresses `rolling-*` when `mustBeUniformAcrossReplicas` is true.
- **`UI-D7` depends on it.** Metric-collection applicability evaluates against the last `restart-observed`
  configuration precisely *because* restart is manual; gating on the draft "would stop collecting valid metrics
  from a still-running Caffeine instance", and the governance cross-check "would then *confirm a false outage*"
  ([`../dynamic-ui/decisions.md`](../dynamic-ui/decisions.md), `UI-D7`).
- **On Cache it would be actively unsafe.** Cache's `mustBeUniformAcrossReplicas` is **genuinely forced true**
  (shared management queue + namespace) and `rolling-health-gated` is **unavailable — "not 'opt-in'; unsafe if
  enabled"** ([`../architecture.md`](../architecture.md)`:840`, open item 15 at
  open item 15 in [`../DECISIONS.md`](../DECISIONS.md)). So every Cache restart is a **full-outage** restart, and
  [`../architecture.md`](../architecture.md)`:741` requires the UI to state the outage and the operator to
  confirm it *before* applying. An agent that restarts on a config write takes that outage without anyone
  confirming it.

**Division of labour, explicitly:**

| Owned by the **DP / console** | Owned by the **agent** |
|---|---|
| Strategy selection from the ladder (`manual` · `rolling-health-gated` · `rolling-no-validation` · `restart-all`) | Executing one lifecycle verb, on one target, on command |
| Suppressing strategies the package's capabilities do not support, and all `rolling-*` under `mustBeUniformAcrossReplicas` | Enforcing that the lease actually carries `lifecycle` |
| **Multi-replica ordering — `stage-everywhere-then-restart`** so a halt leaves config uniform-but-unapplied rather than divergent ([`../architecture.md`](../architecture.md)`:739`) | Reporting per-replica `persisted` and `restart-observed` so the DP can sequence |
| Health gating between replicas; halt-on-failure; `CONFIG_DIVERGED` remediation | Reporting health observations; never deciding on them |
| The outage-confirmation dialog | Nothing — the agent never prompts |

**The one thing that does restart itself is not the agent.** Existence/health drift — crashed, killed, vanished —
**auto-reconciles**, because "restarting what should be running is the supervisor's job"
([`../architecture.md`](../architecture.md)`:420`). That supervisor is systemd's `Restart=`, the Docker restart
policy, or the kubelet — **configuration the customer already owns**. The agent *observes* such a restart and
reports `restart-observed`; it does not perform it and must not race the supervisor by adding a second one.

**Lifecycle verbs** (`start` · `stop` · `restart` · `run-now`) are declared per runtime in the Solution Package
(`SM-D19`, [`../architecture.md`](../architecture.md)`:216`). The agent executes the declared verb; it never
invents one, and it never substitutes a process kill for a verb the package did not declare.

### 5.4 Apply outcomes — `AG-D5`

Because §5.2 supplies `B/D16`'s guarantees, the `host-local` apply path reports a **determinate** outcome in every
case the journal can reconstruct:

| Outcome | Means |
|---|---|
| `APPLIED` | file persisted at the declared path, hash verified by read-back |
| `FAILED_NO_EFFECT` | failed before rename; temp discarded; the previous file is untouched |
| `FAILED_ROLLED_BACK` | failed after rename; the previous content was restored from the retained copy |
| `SUPERSEDED` | a higher generation for the same binding landed first; this one was never applied |

⚠️ **`APPLY_INDETERMINATE` is retained on this path for exactly one case, and it is not "an apply failed".** It is
reported when **both**: (a) the journal shows a step in flight and the lease expired before that record reached a
terminal step, **and** (b) the only report available comes from `LEASE_EXPIRED`, `REVOKED` or `CLOCK_SUSPECT` and
is therefore **untrusted and cannot satisfy a convergence gate** (§4.4). It **resolves deterministically** on the
agent's next `ACTIVE` session by replaying the journal (§5.2) — **not** by an operator confirming actual state.

⚠️ **Cross-reference, and the retraction it corrects.** The operator-confirmation flavour of
`APPLY_INDETERMINATE` is the **`dp-remote`** degradation, and it exists there *precisely because* `dp-remote` has
none of `B/D16`'s safety properties: [`../frontend/decisions.md`](../frontend/decisions.md) `FE-D18`
**clause B** is `BLOCKED` on open item 1 for that reason, and
[`../architecture.md`](../architecture.md)`:844` states the gap in terms. This document's first draft imported
that weaker locus wholesale — "agent reports `APPLY_INDETERMINATE` state requiring operator confirmation" — which
inverted the point of building an agent at all. Retracted in §14 (R1-2).

---

## 6. Telemetry collection

### 6.1 Collection

- Discovers local instances by the **per-source declared identity strategy** (`SM-D13`) — declared inventory
  first, plus Docker socket or Kubernetes API import where available. **Undeclared processes are never adopted.**
- Reuses Prometheus `discovery` and `scrape` packages rather than reimplementing them (`AG-D11` records the
  dependency posture).
- Normalizes into the transport-agnostic model of the package's telemetry contract (`SM-D19`); `type` (counter ·
  gauge · histogram) is **required per metric** because the storage layer branches on it
  ([`../architecture.md`](../architecture.md)`:300`).
- **Never polls a Service/LB VIP** — it hits one random replica, is non-aggregatable, and masks a dead replica
  ([`../architecture.md`](../architecture.md)`:806` ff.). Discover and scrape each instance.
- Series identity uses a **stable ordinal or hostname**, never anything that changes on every restart
  ([`../architecture.md`](../architecture.md)`:574`) — an unstable key creates a new series per restart and
  fragments history.

### 6.2 Buffer and drop policy — `AG-D9`

`B/D2` is `LOCKED` and fixes the pipeline semantics ([`../DECISIONS.md`](../DECISIONS.md) — `B/D2`, §4, restated at
[`../architecture.md`](../architecture.md)`:765`): **bounded local buffer with an explicit backpressure/drop
policy · dedupe keys · freshness/staleness markers · replay/backfill on reconnect · edge auth on collected
endpoints.** `B/D2` also requires HA as **Detect** (self-heartbeat) **+ Recover** (auto-restart + *persisted*
buffer).

The design consequences the agent must implement:

| Requirement | Agent obligation |
|---|---|
| **Bounded** | A byte ceiling and a time horizon, both configured, both reported as gauges. Unbounded buffering on a customer's production host is an outage the agent caused |
| **Persisted** | The buffer survives agent restart (`B/D2` Recover, `../architecture.md:784`) — so it is disk-backed, not purely in-memory |
| **Drop policy is explicit** | Drop **oldest first** and **count every dropped sample**, emitting the count as a first-class metric and a staleness marker on the affected series. A silent drop is indistinguishable from an MI that stopped emitting |
| **Gap semantics are path-specific** | `B/D2`: the broker/event path is **durable**; the pull/actuator path is **best-effort with explicitly marked gaps**. The agent must not present a best-effort gap as durable data |
| **No zero-fill, ever** | Absence renders blank, never zero — the agent emits *no sample* rather than a synthesized one ([`../architecture.md`](../architecture.md)`:273`, `UI-D8`) |
| **Dedupe** | A dedupe key per sample so replay/backfill on reconnect cannot double-count a counter |

**Why oldest-first rather than newest-first:** the console's live view degrades gracefully when history is lost and
misleads badly when the present is lost. A newest-first drop under sustained backpressure would freeze every graph
at the moment congestion started, while the buffer quietly stayed full of stale data.

### 6.3 What the agent does not do with telemetry

It does not roll up, retain, downsample, or answer queries. It holds no database (§10.2). It forwards.

---

## 7. Version observation — `AG-D12`

The agent **observes and reports; it does not adjudicate.** Version state is decided by the DP against the
declared version.

- **The version is generally not observable from the MI.** Cache's `spring-boot-maven-plugin` runs `repackage`
  only, with **no `build-info` goal**, and there is no `spring-boot-starter-parent` — and **adding `build-info` is
  named in `I1` as not fair game** ([`CLAUDE.md`](../../../CLAUDE.md), `UI-D12`). So version detection is
  *corroboration*, never derivation.
- Mechanisms, all read-only and all optional: process/image inspection (image path, container id, JAR manifest),
  an API endpoint where one already exists, filesystem inspection of an installation directory.
- **Each observation is reported with its mechanism and its confidence**, so the DP can distinguish "the image tag
  says 1.4.0" (mutable, weak) from "`Implementation-Version` in `MANIFEST.MF` says 1.4.0" (strong).
- **Absence of a signal is a first-class report, not silence.** `VERSION_UNVERIFIED` is Cache-on-a-VM's permanent
  state (`UI-D12`) and must not read as a failure.

---

## 8. Kubernetes leader election — `AG-D6`

The control role is **1 per Target/cluster**, so on Kubernetes the standby instances must not act. Election uses
`k8s.io/client-go/tools/leaderelection` (Apache-2.0, verified 2026-08-18 from
[the client-go LICENSE](https://raw.githubusercontent.com/kubernetes/client-go/master/LICENSE)).

⚠️ **Leader election elects; it does not fence.** The package documentation states, verbatim: *"This
implementation does not guarantee that only one client is acting as a leader (a.k.a. fencing)."* Verified
2026-08-18 from [pkg.go.dev/k8s.io/client-go/tools/leaderelection](https://pkg.go.dev/k8s.io/client-go/tools/leaderelection).

**Therefore leader election is an optimization, and mutual exclusion comes from elsewhere.** The authority that
actually prevents two agents from applying conflicting config is the pair of guarantees already required:
**DP-assigned monotonic rollout generations and idempotency keys** (§5.2) plus a **lease bound to the agent's mTLS
key** (§3.2). A deposed leader that has not yet noticed cannot do damage, because its generation is stale and its
idempotency key is either a no-op or a refused mismatch. **Designing as though election were fencing is the
failure mode**; the journal is what closes it.

**Clock behaviour, also verified from the same page:** the implementation is tolerant of arbitrary *absolute*
clock differences (it uses local timestamps and watches record changes), but **not** of arbitrary clock *drift
rate*; the tolerated fast/slow ratio is approximately `LeaseDuration : RenewDeadline`, and the documentation
"highly recommend[s]" clock synchronization between nodes. This composes with `SM-D28`'s `CLOCK_SUSPECT` state —
the agent already reports its offset and fails closed (§4.2), so a drifting node loses apply authority before it
loses election sanity.

**Standby behaviour:** a standby runs the telemetry role normally (its lease carries no `apply-config`) and holds
no cached desired state it may act on. On losing leadership mid-apply the agent completes only what §4.3 rule 2's
deadline permits, then steps down; the journal makes the successor's resume deterministic.

---

## 9. Health and self-reporting

- **Self-heartbeat**, per `B/D2`'s Detect. A missing heartbeat is **investigated across cause-specific
  dimensions** — agent / node / broker / cert / ingest / clock — **rather than collapsed into one conclusion**
  ([`../architecture.md`](../architecture.md)`:784`). The agent must therefore emit enough dimension-specific
  signal for that discrimination to be possible.
- Own-process metrics (memory, goroutines, buffer depth, drop count, journal depth, lease time-remaining) exposed
  as Prometheus metrics.
- **Reports `restart-observed`** — new process start time / uptime reset
  ([`../architecture.md`](../architecture.md)`:357`) — which is the gate for `PENDING_RESTART`,
  `RESTART_EXPECTED` and `CONFIG_DIVERGED`. It is **not** proof of convergence and must never be reported as
  such; Cache reaches `restart-observed` and no further ([`../architecture.md`](../architecture.md)`:362`).
- **Bootstrap ordering must be reportable.** Before SM's first apply-and-restart, actuator exposure is at its
  default, so a newly declared instance is **unobservable until after that first restart**
  ([`../architecture.md`](../architecture.md)`:448`). The agent must distinguish `NOT_EXPOSED` from `MISSING` and
  `UNREACHABLE` ([`../architecture.md`](../architecture.md)`:467`) rather than reporting one undifferentiated
  absence.

---

## 10. Cross-cutting invariants

### 10.1 `I1` — no MI development

| Rule | How the agent satisfies it |
|---|---|
| No code, build-plugin, endpoint or telemetry changes to any MI | The agent only **writes files SM owns** at declared paths, sets env/mounts in the launch spec, and **reads** what the MI already exposes |
| Version detection | Runtime introspection only (§7). **No `build-info` requirement** — explicitly named in `I1` as not fair game |
| Health | Whatever `/actuator/health` already returns. Cache has no custom `HealthIndicator` and `I1` forbids asking for one (`UI-D9`) |
| Restart | The **declared** lifecycle verb (`SM-D19`), executed on command. No MI-side hook, no injected shutdown endpoint |
| Leader/standby state | **Not obtainable.** Cache exposes leader state only in a log line; the agent must not pretend otherwise (open item 15 in [`../DECISIONS.md`](../DECISIONS.md)) |

**Stated limit, not solvable here:** on a single-port unauthenticated MI, telemetry reachability implies
reachability of its mutating API — Cache shares `8081` between `/actuator/*` and `POST`/`DELETE /api/cache*`
([`../architecture.md`](../architecture.md)`:839`). The agent mitigates by using a **collector-only network
path**; it cannot close the hole without MI development.

### 10.2 `I5` — tenant isolation binds the planes, not the agent — `AG-D10`

⚠️ **Correction (§14, R1-5).** This document previously cited `I2` for tenant isolation and asserted SQL
bind-parameter compliance. `I2` is *"verify external facts before writing them down"*; the invariant meant is
**`I5`**. The SQL claim was also inapplicable:

**The agent holds no database and issues no SQL.** It has a state directory (the apply journal, §5.2, and the
persisted telemetry buffer, §6.2) and nothing else. `I5`'s bind-parameter and server-side-scope rules bind the
**planes** — the CP and DP data-access modules — and are enforced there.

What `I5` does require **of the agent**:

- **Tenant and Target scope are never self-asserted.** They are properties of the agent's mTLS identity and the
  claims in its lease (§3.2), assigned by the CP and validated by the DP. The agent never sends a tenant id the
  DP is expected to trust.
- The agent **refuses** any command outside its `target_id` / `binding_ids[]` — defence in depth behind the DP's
  own check, not a substitute for it.

### 10.3 `I6` — secrets

- **`secretRef` values resolve at render time, in the DP.** The agent receives rendered bytes; where those bytes
  contain a resolved secret they are written **atomically, mode `0600`, owned by the service account**
  ([`../architecture.md`](../architecture.md)`:914`) and **never** copied into the journal, a log line, a metric
  label, an error message or a crash dump. **The journal stores the config *hash*, never the content.**
- **Config hashes are computed over references, not resolved values** (`I6`).
- ⚠️ **Stated limit:** "resolve secretRefs → tmpfs" is **unachievable** even locally, because the JVM reads the
  file from disk at restart ([`../architecture.md`](../architecture.md)`:914`). The agent does not pretend to
  offer it.

### 10.4 `I7` — plane boundary

`I7` constrains CP and DP code. The agent is neither, and imports neither. Its relevance here is directional: the
agent's operational channel terminates at **one** plane (the DP under `SM-D24`, the CP under `LOCKED` `B/D16` —
§0.1). It must not acquire a second channel to the other plane as a convenience; enrollment is **relayed** through
the DP for exactly this reason (§3.1).

---

## 11. Technology choices

Every entry has an ID and a rationale in [`decisions.md`](decisions.md). Summarized here; the decisions file is
authoritative.

| Area | Choice | Decision |
|---|---|---|
| Packaging | one static, cgo-free binary; roles selected at startup | `AG-D1` |
| Language floor | build on a **supported** Go release; floor `go 1.25` | `AG-D2` |
| RPC | `google.golang.org/grpc` over mTLS; `crypto/tls` for the TLS stack | `AG-D11` |
| Kubernetes | `k8s.io/client-go`, incl. `tools/leaderelection` — **elects, does not fence** | `AG-D6` |
| Agent metrics | `prometheus/client_golang`; `prometheus/prometheus` `discovery` + `scrape` for collection | `AG-D9`, `AG-D11` |
| Agent config | stdlib `flag` + environment + one file; **no Viper** | `AG-D7` |
| Logging | stdlib **`log/slog`** with `JSONHandler`; no third-party logger | `AG-D8` |
| Buffer | bounded, disk-backed, drop-oldest, counted | `AG-D9` |

⚠️ **Retracted from the first draft:** "Go 1.20+" (unsupported — `AG-D2`), "google.gRPC" (not a package name),
"Viper or similar" (`AG-D7` rejects it), "zap or zerolog" (`AG-D8` rejects both). OpenTelemetry tracing was listed
as "where beneficial"; that is not a decision and is now open item AG-7.

---

## 12. Testing and verification

**Unit.** Components in isolation with the DP connection, filesystem, clock and Kubernetes API mocked. The clock
is injected everywhere — `SM-D28`'s lease, deadline and `CLOCK_SUSPECT` logic is untestable against a real one.

**Journal / crash-safety (the load-bearing suite, `AG-D4`).** For every step boundary in §5.2, kill the process
and assert the resume action:

| Case | Assertion |
|---|---|
| Kill after `RECEIVED`, before temp write | nothing on disk; DP redelivers; no duplicate apply |
| Kill after temp write, before rename | resume completes the rename; final content hash matches |
| Kill after rename, before report | resume **re-reads and re-reports**; **does not rewrite** |
| Redeliver same `idempotency_key`, same hash | no-op; recorded outcome re-reported |
| Redeliver same `idempotency_key`, **different** hash | refused as a protocol error |
| Deliver generation N−1 after N | rejected as stale, never applied late |
| Startup with temps from N−2 and N−1 present | both deleted; deletion journalled |
| Lease expires mid-step | step killed at its deadline; only local atomic cleanup runs; `APPLY_INDETERMINATE` per §5.4 and **resolved by replay**, not by an operator |

**Restart-boundary tests (`AG-D3`) — these are regression tests for R1-1:**

- A config apply with **no** lifecycle command **must not** restart anything, on every runtime driver.
- The instance **must** be left in `PENDING_RESTART`.
- A lifecycle command whose lease lacks the `lifecycle` capability **must** be refused.
- A Cache-shaped fixture (`mustBeUniformAcrossReplicas: true`) **must** never see a `rolling-*` sequence
  originate at the agent.

**Integration.** Against a DP test double: enrollment, lease renewal, renewal failure into `RENEW_DEGRADED`,
expiry into `LEASE_EXPIRED`, epoch increment into `REVOKED`, clock skew into `CLOCK_SUSPECT`, and every recovery
transition in `SM-D28`'s table — **including that no state is absorbing.**

**Leader election.** Two agents against a real API server in `kind`/`envtest`. ⚠️ The assertion is **not** "only
one leader ever" — the package does not promise that (§8). It is: **a deposed leader's apply is rejected by
generation/idempotency, and the successor's resume is deterministic.**

**Telemetry.** Bounded-buffer overflow drops oldest and counts; reconnect backfills without double-counting a
counter; a gap is marked, never zero-filled; a panicking collector does not take the process down.

**Success criteria.** Enrollment and mTLS established · generations applied crash-safely and idempotently ·
**no restart without an explicit command** · telemetry normalized, buffered and backfilled · version observations
reported with mechanism and confidence · authority lifecycle correct at every transition · `I1`, `I5`, `I6`
respected.

---

## 13. Open items and dependencies

### 13.1 Blocking — nothing starts without these

| # | Item | Blocks |
|---|---|---|
| AG-1 | **The agent↔plane `.proto` and state machine do not exist** ([`../architecture.md`](../architecture.md)`:837`; open item 6 in [`../DECISIONS.md`](../DECISIONS.md)) | **all v1 agent work** |
| AG-2 | **`SM-D24`/`SM-D28` unratified against `LOCKED` `B/D16`** (§0.1) — which plane the session terminates at | §3, §4; the enrollment topology |
| AG-3 | **`verb_ceiling` values are not set.** `SM-D28` requires a platform-configured maximum per lifecycle verb and the validated invariant `lease_duration > max(verb_ceiling) + margin` — no values exist | §4.3 admission control; without it every apply is either always admitted or never |
| AG-4 | **Persisted-state file format is unspecified** — `SM-D28` names it as remaining agent sub-spec work. §5.2 specifies the *contents*; the encoding, versioning and forward-compatibility rule are open | §5.2 |
| AG-5 | **Renewal backoff and jitter unspecified** (`SM-D28`, sub-spec item). A fleet with synchronized renewal is a self-inflicted thundering herd | §4.2 |

### 13.2 Non-blocking, recorded

| # | Item |
|---|---|
| AG-6 | HTTP/1.1 long-poll fallback (`B/D16`) is undesigned. Is it v1? |
| AG-7 | OpenTelemetry tracing in the agent — listed as "where beneficial" in the first draft, which is not a decision |
| AG-8 | **Solace Go API maturity** for a `solace_event` collector in the agent ([`../architecture.md`](../architecture.md)`:906`). If insufficient, the broker collector stays Java/JCSMP in the DP |
| AG-9 | Per-MI version-introspection mechanisms — which are available for Cache, and at what confidence |
| AG-10 | Agent self-upgrade and version-skew window against the DP ([`../architecture.md`](../architecture.md)`:918`) |
| AG-11 | Whether the telemetry role needs its own lease/credential distinct from the control role's, or a capability-scoped variant suffices |
| AG-12 | **Apply-journal retention.** Nothing ever deletes a terminated record's `apply/<binding>/<gen>.json`, so records accumulate one per generation for the life of the data directory. Every cost is linear in that count: `LoadJournal` at start-up, `Journal.ByCommand` on every progress, finish and error event, `PersistedGeneration` per binding on every report tick, and the full clone `Snapshot()` takes on every event. Inert at v1 configuration-change rates (operator-driven, tens a year), but owed before the telemetry slice raises the event rate by orders of magnitude. The rule belongs in `AG-D4`: keep each binding's watermark generation plus *N*. Pruning below the watermark is safe, because a redelivered older generation is refused `STALE_GENERATION` by the watermark anyway. Raised by the plan-005 final review (M4). |
| AG-13 | **Pending actual-state reports are unbounded.** During a DP outage the periodic cadence (`AG-D18`) keeps minting items. With no connection the send is dropped, but the item is still appended and the whole `reports/pending.json` rewritten each time — quadratic I/O over the outage. On reconnect the backlog goes out as one `ActualStateReportBatch`, and nothing checks that batch against the message limits. At v1 scale a day-long outage at a one-minute interval is roughly 1,440 items (~500 KB), approaching a 1 MB limit; a multi-day outage would exceed it, and a DP that rejected the batch would be sent the same oversized batch after every reconnect. Recommended direction: coalesce pending items per `(binding, replica)` and keep the newest. An older unacknowledged observation of a replica is superseded by a newer one, which carries the latest `restart_observed_at`. Needs the `AG-D*` owners to confirm this does not conflict with `AG-D17`: an item is immutable while it is pending, and dropping an item is not mutating it. Raised by the plan-005 final review (M5). |

### 13.3 Depends on, and does not own

`SM-D19` Solution Package format (config mechanism, lifecycle verbs, uniformity flag, telemetry contract) ·
`SM-D21` per-artifact config path discovery · `SM-D25` Kubernetes writer ownership, which turns on the
**unresolved customer question** of whether Argo/Flux will exclude an SM-owned ConfigMap
([`../DECISIONS.md`](../DECISIONS.md) open item 4) · the DP-side generation and lease stores · the telemetry
contract from the backend design.

---

## 14. R1 audit corrections

Independent audit, 2026-08-18: this document had been written to a visibly earlier standard than its siblings —
no evidence citations, no per-choice rationale, no flagged-conflict section, and no decisions file.

| # | Severity | Finding | Correction |
|---|---|---|---|
| R1-1 | MAJOR | *"Triggers restart of managed integration when configuration changes"* — contradicted `SM-D15`, made `PENDING_RESTART`, the outage-confirmation dialog and `rolling-*` suppression unreachable, and would restart Cache without an operator confirming an outage | **Retracted.** §5.3 + `AG-D3`: restart only on an explicit DP lifecycle command carrying the `lifecycle` capability. Division of labour tabulated; regression tests in §12 |
| R1-2 | MAJOR | Apply reported `APPLY_INDETERMINATE` with no rollout generation, idempotency key or crash-safe resume — importing the **`dp-remote`** degradation into the component that is supposed to *provide* `LOCKED` `B/D16`'s guarantees | **Fixed.** §5.2 + `AG-D4` add the journal, monotonic generations, idempotency keys, deterministic resume and superseded-partial cleanup. §5.4 + `AG-D5` scope `APPLY_INDETERMINATE` to the lease-expiry-plus-untrusted-report case and cross-reference `FE-D18` clause B |
| R1-3 | MAJOR | Cited `LOCKED` `B/D16` (agent↔**CP**) then specified the whole session as agent↔**DP** (`SM-D24`, `PROPOSED`) with **no flagged-conflict section anywhere** | **Fixed.** §0.1 flags it per `I4`, states that the locked decision stands until the architect group rules, and says what changes under each outcome |
| R1-4 | MAJOR | **No decisions file**, though `AG-D*` is reserved for this component in [`../README.md`](../README.md) and `CLAUDE.md` | **Fixed.** [`decisions.md`](decisions.md) created — `AG-D1`…`AG-D12`, all `PROPOSED` |
| R1-5 | MINOR | Cited **`I2`** for tenant isolation (`I2` is *verify external facts*), and asserted SQL bind-parameter compliance for a component with no database | **Fixed.** §10.2 cites `I5`, states the agent holds no database and issues no SQL, and names what `I5` does bind on the agent side |
| R1-6 | — | Technology choices stated without rationale, and several were wrong or stale (`Go 1.20+`, "google.gRPC", "Viper or similar", "zap or zerolog") | **Fixed.** §11 + `AG-D1`, `AG-D2`, `AG-D7`, `AG-D8`, `AG-D11`, each with a rationale and, where external, a verified citation (`I2`) |
| R1-7 | — | Leader election presented as the HA mechanism, with no mention that `client-go` explicitly does **not** provide fencing | **Fixed.** §8 + `AG-D6`, verified against the package documentation |

---

*Design only. No code exists. Implementation must not begin against the `PROPOSED` base in §0 — and cannot begin
regardless, because open item AG-1 (the `.proto`) is unwritten.*
