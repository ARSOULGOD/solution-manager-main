# Go-agent decisions — `AG-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified. **There is no agent
code**, and none may be written — open item AG-1 (the agent↔plane `.proto`) is unwritten.
*2026-10-09: superseded for plan 015 by `OR-126`/`OR-128`; the agent is built under plan 015.* (A header note, not a decision edit.)

**ID discipline (`I3`):** IDs are immutable and never renumbered. `AG-D*` is the per-component family reserved for
this component in [`../README.md`](../README.md) and in [`CLAUDE.md`](../../../CLAUDE.md), alongside `BE-D*`,
`FE-D*` and `RT-D*`. This family starts at **`AG-D1`** and does **not** re-decide `D*`, `B/D*`, `SM-D*`, `UI-D*` or
`DM-D*` — cross-cutting choices are cited, never restated. To change a decision, **add one that supersedes it**.

> **Amendment policy for this draft.** `I3`'s supersession rule protects decisions something has been built or
> ratified against. `AG-D1`…`AG-D12` are a first draft with no code and no ratification, so review corrections in
> the same cycle may be applied in place and itemized in a changelog. **Post-ratification the supersession rule
> applies normally.**

⚠️ **This family rests on an unratified base, and one item of that base revises a `LOCKED` decision.** Per `I4`
nothing here may be implemented as though settled.

| This family | Against | Disposition |
|---|---|---|
| **The whole family assumes the agent's session terminates at the Data Plane** — `AG-D4`, `AG-D5`, `AG-D6` and `AG-D11` are all written against it | **`B/D16` is `LOCKED`** and defines the session as agent↔**Control Plane** ([`../DECISIONS.md`](../DECISIONS.md) — `B/D16`, §8). The re-homing is **`SM-D24`** + **`SM-D28`**, both `PROPOSED` | **Flagged, not resolved.** The locked decision stands until the architect group rules. The apply machinery (`AG-D4`, `AG-D5`) is **locus-bound, not plane-bound**, so it survives either outcome; `AG-D11`'s transport and the enrollment relay do not. See [`design.md`](design.md) §0.1 |
| `AG-D5` retains `APPLY_INDETERMINATE` on the `host-local` path, **narrowly scoped** | [`../frontend/decisions.md`](../frontend/decisions.md) `FE-D18` **clause B** makes `APPLY_INDETERMINATE` the **`dp-remote`** degradation, *because* `host-local` carries `B/D16`'s guarantees | **Not a conflict once scoped.** `AG-D4` supplies the guarantees; `AG-D5` narrows the state to one case that `B/D16` does not cover. The first draft's wide reading **was** a conflict and is retracted ([`design.md`](design.md) §14, R1-2) |
| `AG-D3` assigns restart strategy, ordering and gating **entirely** to the DP | `SM-D15` is `PROPOSED`, yet `AG-D3` treats its `manual` default as binding | **Deliberate.** Two sibling documents already depend on the manual default — [`../frontend/design.md`](../frontend/design.md) §10.1 and [`../dynamic-ui/decisions.md`](../dynamic-ui/decisions.md) `UI-D7`. Building the opposite would falsify both. Flagged as a dependency, not resolved |
| `AG-D17` provides stable `item_id` and `sequence` persistence/reset semantics — the agent's answer to `AGW-20` | [`docs/design/backend/agent-gateway/decisions.md`](../backend/agent-gateway/decisions.md) `AGW-20` | **Paired answers:** this decision supplies UUID identity and `sequence` persistence; DP-side watermark, projection and fingerprint remain `AGW-20`'s |
| `AG-D18` establishes periodic `ActualStateReport` cadence, answering `AGW-19`'s requirement for regular liveness signals | [`docs/design/backend/agent-gateway/decisions.md`](../backend/agent-gateway/decisions.md) `AGW-19` | **Answered:** the DP delivers the cadence via `SessionAccept.limits.report_interval` |
| `AG-D19` and `AG-D20` flag architectural gaps requiring `AGW` owners' consideration | [`docs/design/backend/agent-gateway/decisions.md`](../backend/agent-gateway/decisions.md) §4 | **Routed, not resolved:** `AG-D19` flags missing outcome for "changed-but-failed"; `AG-D20` flags placement-discovery built on protocol silence |
| `AG-D26` supersedes `AG-D20`'s binding-id label, and `AG-D28` supersedes `AG-D22` for production | `AG-D20` and `AG-D22` stand as written (`I3`); plan 015 `OR-126` A6 | **Supersession by citation, no row edited.** `AG-D20`'s label (`com.solace.sm.binding-id`) cannot be set before the DP mints the id; the DP now derives the placement from container facts (`AGW-D30`), and the declarations come from the wire |
| `AG-D29` records the on-disk spool as a **named `I6` exception** | `I6` (secrets never land in config storage) is about SM's storage; the agent's host disk holds the rendered file anyway (it is the destination) | **Ruled, `OR-129`.** The exception is named, bounded (0600/0700, purged at terminal, swept at start) and enforced; `OR-126` (A5) still holds for SM (`AGW-D26`) |

⚠️ **External facts in this file were verified by WebFetch on 2026-08-18, per `I2`.** Every version number, licence
and library guarantee below carries its source. This project has been burned three times by confident recall.

---

| ID | Decision | Status |
|---|---|---|
| `AG-D1` | One static, cgo-free binary; both roles compiled in, selected at startup | PROPOSED |
| `AG-D2` | The Go floor tracks the upstream support window — build on a supported release, `go 1.25` floor | PROPOSED |
| `AG-D3` | The agent never infers a restart; lifecycle runs only on an explicit DP command | PROPOSED |
| `AG-D4` | Apply is a persisted, resumable journal keyed by rollout generation + idempotency key | PROPOSED |
| `AG-D5` | `APPLY_INDETERMINATE` is not a `host-local` apply failure mode; four determinate outcomes are named | PROPOSED ⚠️ |
| `AG-D6` | `client-go` leader election **elects, it does not fence**; mutual exclusion comes from the journal and the lease | PROPOSED |
| `AG-D7` | Agent configuration is stdlib `flag` + environment + one file; **no Viper** | PROPOSED |
| `AG-D8` | Structured logging is stdlib **`log/slog`**; no third-party logging library | PROPOSED |
| `AG-D9` | The telemetry buffer is bounded, disk-backed, **drop-oldest**, and every drop is counted and reported | PROPOSED |
| `AG-D10` | The agent holds **no database and issues no SQL**; `I5` binds the planes, and binds the agent only on scope self-assertion | PROPOSED |
| `AG-D11` | `google.golang.org/grpc` + `crypto/tls` + `client-go` + Prometheus libraries; dependencies are few, verified and boring | PROPOSED |
| `AG-D12` | The agent reports version **observations with mechanism and confidence**, never a version verdict | PROPOSED |
| `AG-D13` | The agent↔DP `.proto` is DRAFTed in-repo under `go-agent/proto/agentgateway/v1draft/`, transcribed 1:1 from the agent-gateway design §4.3/§4.11; only `internal/wire` imports the generated code | PROPOSED |
| `AG-D14` | The agent is an event-driven reactor: one inbox, pure reducers `session → authority → commands → reports`, effects executed `Persist → Delete → Send → RunOp → timers`, one runtime worker | PROPOSED |
| `AG-D15` | `internal/` is rewritten against the gateway design; the superseded packages, the Docker-lifecycle and telemetry-collect demos and the `java-demo` fixtures are **archived** under `go-agent/oldGo-Agent/`, not deleted | PROPOSED |
| `AG-D16` | The DP test double is a scenario-driven **fake gateway** at wire level, shared by tests and a binary; it is not evidence about the plane | PROPOSED |
| `AG-D17` | `item_id` is a UUIDv7 minted once per report item and immutable across retries; `sequence` is a per-`(binding, replica)` counter persisted before use and reseeded from DP time when lost — the agent's answer to `AGW-20` | PROPOSED |
| `AG-D18` | A full `ActualStateReport` is sent at `SessionAccept.limits.report_interval` even when nothing changed — the agent's answer to `AGW-19` | PROPOSED |
| `AG-D19` | Lifecycle-verb outcomes are decided by **observation**, not by the verb's return; a changed-but-failed state has no `AG-D5` outcome and is routed | PROPOSED |
| `AG-D20` | On Docker a binding's container is found by the label `com.solace.sm.binding-id`, its config path read from the container's own launch args/env, and the host path mapped through a required bind mount | PROPOSED |
| `AG-D21` | The telemetry role is collectors → buffer → sender beside the control loop, fed only by the control loop's published immutable view — **reconstructed 2026-10-08, no source text existed** | PROPOSED |
| `AG-D22` | Metric declarations are an interim local file of schema-shaped `http_json` metrics — **reconstructed 2026-10-08, no source text existed** | PROPOSED |
| `AG-D23` | The telemetry sender is a pure reducer over credits, in-flight batches and the stream's state; credits are the only backpressure — **reconstructed 2026-10-08, no source text existed** | PROPOSED |
| `AG-D24` | The telemetry buffer is a file per batch, bounded by bytes and age across all streams, drop-oldest, every drop a gap marker — **reconstructed 2026-10-08, no source text existed** | PROPOSED |
| `AG-D25` | A failed buffer write drops the batch and is counted; it is never fatal — **reconstructed 2026-10-08, no source text existed** | PROPOSED |
| `AG-D26` | Discovery by label, reporting container facts, never conclusions; the container is then found by name through the DP-supplied binding (plan 015; supersedes `AG-D20`'s binding-id label) | PROPOSED |
| `AG-D27` | Health, container start time and the write precondition are observed facts the agent reports (plan 015; applies `AG-D19`) | PROPOSED |
| `AG-D28` | Metric declarations arrive on the wire from the DP; the file flag is development-only (plan 015; supersedes `AG-D22` for production) | PROPOSED |
| `AG-D29` | The command payload is spooled on the host under a named `I6` exception (`OR-129`); purged at terminal, swept at start | PROPOSED |
| `AG-D30` | The DP may set the telemetry push interval per session on `Limits.telemetry_push_interval`; unset is `OR-73`'s 1 min, `0` sends each batch when written (go-agent plan 008; applies `AGW-D27` item 4) | PROPOSED |

---

## `AG-D1` — One static, cgo-free binary; both roles compiled in, selected at startup

One binary contains both the **control** and **telemetry** roles ([`design.md`](design.md) §2). Each is enabled or
disabled independently at startup. **`CGO_ENABLED=0`**, no plugin mechanism, no dynamically loaded collectors.

**Rationale.** The topology note at [`../DECISIONS.md`](../DECISIONS.md) §8, agent-topology note already fixes "one Go binary, two
roles" — this decision records *why that shape is chosen and what it forbids*, which the first draft asserted
without either. Three reasons, in order of weight:

1. **The deployment surface is a customer's production host.** SM has no artifact-delivery machinery to lean on —
   `SM-D2` puts artifact deployment in the customer's CI/CD, so the agent must be installable as a single file the
   customer can place with tooling they already have. A static binary is installable by `scp`, an RPM, a
   DaemonSet image or a Dockerfile `COPY` with no runtime, no interpreter and no shared-library matrix.
2. **The two roles have different cardinalities but identical dependencies.** Control is one per Target/cluster,
   telemetry scales with nodes — but both need the same mTLS stack, the same protocol client and the same lease
   logic. Two binaries would duplicate all of it and let the two copies drift on the security-critical half.
3. **cgo off is a security and portability decision, not a performance one.** It removes glibc/musl variance
   across the VM, Docker and Kubernetes cases and keeps the binary auditable as one artifact.

**What it forbids, deliberately:** no plugin-loaded collectors. A collector is Go code in this repo or it does not
exist. A plugin mechanism would let a third party execute code inside a process that holds `apply-config` and
`lifecycle` authority on a production host — the same reasoning `UI-D21` applies to package-supplied UI code.

**Rejected:** two binaries (drift on the shared security path); a supervisor plus workers (a second lifecycle to
get wrong, on a host whose supervisor the customer already owns); cgo-enabled builds for a faster TLS path (no
measured need, and it reintroduces the libc matrix).

## `AG-D2` — The Go floor tracks the upstream support window; `go 1.25` floor

The `go` directive is set to a **currently supported** Go release and raised as the window moves. At the time of
writing the floor is **`go 1.25`** and CI builds on the latest patch of **1.26**.

**Rationale.** ⚠️ **The first draft said "Go 1.20+", which is an unsupported release.** Verified 2026-08-18 from
[go.dev/doc/devel/release](https://go.dev/doc/devel/release): the policy is *"Each major Go release is supported
until there are two newer major releases"*, and the most recent release listed is **Go 1.26.0 (2026-02-10)**, latest
patch **go1.26.6 (2026-08-13)**. Under that policy the supported majors today are **1.25 and 1.26**, so Go 1.20
stopped receiving security backports at the Go 1.22 release.

**Why this matters more for this component than most.** The agent's whole reason to exist is that it is the
outbound-only, mTLS-authenticated actor on a customer's production host. Its TLS stack, its ASN.1/x509 parsing and
its HTTP/2 implementation are **the standard library**. Pinning to an unsupported release means the security
fixes listed against 1.26 — `crypto/tls`, `encoding/asn1`, `net/http`, `net/url` among them — are unavailable to
the one component that most needs them.

**Stated as a rule rather than a number** because a hardcoded version in a design document rots; the rule does not.
The number is recorded so a reader can tell when the rule was last applied.

**Rejected:** floor at the newest release (blocks distro Go toolchains needlessly, and on-prem customers often
build in air-gapped environments a release behind); floor at "whatever builds" (how a project ends up on 1.20).

## `AG-D3` — The agent never infers a restart; lifecycle runs only on an explicit DP command

The agent writes configuration. It restarts a managed integration **only** on an explicit lifecycle command from
the Data Plane, and only when the current lease carries the **`lifecycle`** capability (`SM-D28`). **A config write
is never, in itself, a reason to restart anything.** Strategy selection, multi-replica ordering, health gating and
outage confirmation belong to the DP and console. Full division of labour: [`design.md`](design.md) §5.3.

**Rationale.** ⚠️ **This retracts the first draft's "triggers restart of managed integration when configuration
changes"** — a two-line statement that broke four mechanisms in three documents.

- **`SM-D15`** ([`../DECISIONS.md`](../DECISIONS.md) — `SM-D15`, §3) makes restart operator-selected and **`manual` by
  default**, because — [`../architecture.md`](../architecture.md)`:714` — *"SM must not encode a restart strategy
  it cannot verify; guessing wrong takes down production."* Restart behaviour depends on replica independence, HA
  model, warm-up duration and standby promotion: properties SM **cannot know and cannot require anyone to
  declare** (and `I1` forbids asking an MI to declare them).
- **`PENDING_RESTART` becomes unreachable.** Under `manual`, SM writes the file and does nothing else; the
  instance sits in *"Configuration staged · restart required · N replicas pending"* until the operator acts
  ([`../architecture.md`](../architecture.md)`:716`). An auto-restarting agent means that state can never be
  observed.
- **The console is built on the manual default.**
  [`../frontend/design.md`](../frontend/design.md) §10.1 stages config, waits for `restart-observed`, and
  suppresses `rolling-*` when `mustBeUniformAcrossReplicas` is true.
- **`UI-D7` is built on it too.** Metric-collection applicability evaluates against the last **`restart-observed`**
  configuration *because* restart is manual — gating on the draft "would stop collecting valid metrics from a
  still-running Caffeine instance", and the governance cross-check "would then *confirm a false outage*"
  ([`../dynamic-ui/decisions.md`](../dynamic-ui/decisions.md), `UI-D7`).
- **On Cache it is not merely wrong, it is unsafe.** `mustBeUniformAcrossReplicas` is **genuinely forced true**
  (shared management queue + namespace) and `rolling-health-gated` is **unavailable — "not 'opt-in'; unsafe if
  enabled"**, because an `active_standby` standby reports `health: UP` while processing nothing
  ([`../architecture.md`](../architecture.md)`:840`; open item 15 in [`../DECISIONS.md`](../DECISIONS.md)).
  Every Cache restart is therefore a **full outage**, and
  [`../architecture.md`](../architecture.md)`:741` requires the operator to confirm it *before* applying. An agent
  that restarts on a config write takes production down with nobody having agreed to it.

**The apparent counter-example is not one.** Existence/health drift — crashed, killed, vanished — **does**
auto-reconcile, because "restarting what should be running is the supervisor's job"
([`../architecture.md`](../architecture.md)`:420`). That supervisor is systemd's `Restart=`, the Docker restart
policy or the kubelet — **already the customer's**, and configuration `I1` permits SM to influence. The agent
*observes* such a restart and reports `restart-observed`; adding a second restarter would race the first.

**Why the capability check is separate from the command.** `SM-D28`'s lease carries `capabilities[]`, and
`apply-config` does **not** imply `lifecycle`. A telemetry-role agent, or a control-role agent whose lease was
issued for config-only work, must refuse a restart even if a well-formed command arrives.

**Rejected:** restart-on-change with an opt-out (defaults are what ship; the safe case must be the default);
restart-on-change only where the package declares a strategy default (`SM-D19` allows an optional default, but a
*declaration* is not the *operator assertion* the ladder above `manual` requires); letting the agent choose
`rolling-*` from replica count (it cannot know replicas are independently restartable — that is exactly the
assertion `SM-D15` requires a human to make).

## `AG-D4` — Apply is a persisted, resumable journal keyed by rollout generation + idempotency key

Before any filesystem or lifecycle action the agent appends an `fsync`-ed record carrying the DP-assigned
**monotonic rollout generation**, the DP-assigned **idempotency key**, the config hash, the step and an absolute
step deadline. Resume is a pure function of the last recorded step. Superseded partials are deleted and the
deletion journalled. Full specification: [`design.md`](design.md) §5.2.

**Rationale.** **`B/D16` is `LOCKED` and already requires exactly this** — apply is "crash-safe and idempotent —
persisted generation state, idempotency keys, deterministic resume, explicit cleanup of superseded partials"
([`../DECISIONS.md`](../DECISIONS.md) — `B/D16`, §8) — and [`../architecture.md`](../architecture.md)`:181` states these
are properties of the **`host-local`** path. **The go-agent *is* that path.** The first draft's §6 specified none
of them, which meant the component whose job is to *provide* the guarantee had instead assumed someone else
supplied it. This decision does not invent a mechanism; it discharges an obligation the register already imposed.

**Why each element is load-bearing, not ceremony:**

- **Monotonic generations** make wire reordering safe. Without them, generation N−1 arriving after N is applied
  late and the host silently ends up on stale config — with the DP believing it is on N.
- **Idempotency keys** make retry safe. A repeated key with a matching hash re-reports the recorded outcome; a
  repeated key with a **differing** hash is refused as a protocol error rather than guessed at, because the only
  two readings ("the DP reused a key" and "the content changed under the same intent") have opposite correct
  responses.
- **Deterministic resume** is what removes the operator from the recovery path. The three interesting crash points
  — before the temp write, between temp and rename, between rename and report — each have exactly one correct
  action, and the journal is what tells them apart. In particular, resume from `PERSISTED` **re-reads and
  re-reports; it does not rewrite**, so a crash cannot turn one apply into two writes.
- **Cleanup of superseded partials** is not hygiene. An orphaned temp from generation N−2 is a future resume's
  ambiguous input, and on a config directory it is also a file a human may find and mistake for current config.

**Kubernetes uses the same journal** with the generation-named immutable ConfigMap standing in for the temp file
(`SM-D25`), which is why `SM-D25`'s "new ConfigMap per version, then update the reference" maps cleanly onto
`STAGED → PERSISTED`. Mutable ConfigMaps could not be journalled this way — there would be no distinguishable
staged state — which is a second, independent reason to prefer them.

**Rejected:** in-memory apply state (loses the crash case entirely, which is the only case that matters);
a lockfile instead of a journal (records that *an* apply was running, not *which* one or *how far*); trusting the
DP to re-derive host state after a crash (it cannot — the DP has no read path to a `host-local` filesystem except
through this agent).

**Open:** the on-disk **encoding, versioning and forward-compatibility rule** for the journal is unspecified —
`SM-D28` names the persisted-state file layout as remaining agent sub-spec work ([`design.md`](design.md) open item
AG-4).

## `AG-D5` — `APPLY_INDETERMINATE` is not a `host-local` apply failure mode ⚠️

The `host-local` apply path reports one of four **determinate** outcomes — `APPLIED`, `FAILED_NO_EFFECT`,
`FAILED_ROLLED_BACK`, `SUPERSEDED`. `APPLY_INDETERMINATE` is retained for **one** case: the journal shows a step in
flight, the lease expired before that record reached a terminal step, **and** the only available report comes from
`LEASE_EXPIRED` / `REVOKED` / `CLOCK_SUSPECT` and is therefore untrusted. It **resolves by journal replay on the
next `ACTIVE` session**, not by an operator confirming actual state.

**Rationale.** ⚠️ **The first draft said "Agent reports `APPLY_INDETERMINATE` state requiring operator
confirmation", which imported the wrong locus's degradation.**

`APPLY_INDETERMINATE` exists in this project as the **`dp-remote`** remedy, and it exists there for a stated
reason: [`../architecture.md`](../architecture.md)`:844` — *"`dp-remote` apply carries none of `B/D16`'s safety
properties unless separately built — no generation ordering, idempotency key, lease, active revocation or
crash-safe resume"* — and [`../architecture.md`](../architecture.md)`:916` defines the state as one that **blocks
further applies until an operator confirms actual**. [`../frontend/decisions.md`](../frontend/decisions.md)
`FE-D18` **clause B** is `BLOCKED` on open item 1 for precisely this, and its rationale says the guarantees it
needs "are `B/D16` properties of an agent↔CP protocol `dp-remote` has no equivalent of."

So the first draft had the `host-local` agent — the component that *has* those properties — adopting the fallback
that exists because another path *lacks* them. **That inverts the reason for building an agent at all**: if
`host-local` apply is also indeterminate and also needs an operator to confirm, the agent buys nothing over SSH
except a footprint.

**Why one narrow case survives rather than none.** `SM-D28` is explicit that reports from `LEASE_EXPIRED`,
`REVOKED` or `CLOCK_SUSPECT` are **labelled untrusted and may never satisfy a convergence gate**. So there is a
genuine window where the *agent* knows what happened but the *DP* may not believe the only report it has. Calling
that `APPLIED` would let a revoked agent forge convergence evidence; calling it `FAILED` would be a lie in the
common case. It is genuinely indeterminate **from the DP's side** — and it is the only such case.

**The resolution path is the difference that matters.** Under `dp-remote` the remedy is human: block, and have an
operator confirm actual state. Under `host-local` the remedy is mechanical: the journal (`AG-D4`) is on the host,
so the next authorized session replays it and the state resolves itself. Same name, different closure — and the
console must not offer the operator-confirmation affordance on a `host-local` binding.

**Rejected:** dropping the state entirely on this path (it would force a wrong `APPLIED`/`FAILED` in the untrusted
window); reusing the `dp-remote` semantics verbatim (`FE-D18` clause B's operator-confirmation flow is wrong here
and would train operators to confirm what the agent can resolve); a fifth outcome name to avoid the collision
(one state with two documented closures is clearer than two states meaning the same thing).

## `AG-D6` — `client-go` leader election elects, it does not fence

The control role uses `k8s.io/client-go/tools/leaderelection` (Apache-2.0, verified 2026-08-18 from the
[client-go LICENSE](https://raw.githubusercontent.com/kubernetes/client-go/master/LICENSE)) to keep one active
control agent per cluster. **Mutual exclusion is *not* provided by election** — it is provided by `AG-D4`'s
DP-assigned monotonic generations and idempotency keys plus `SM-D28`'s session-bound lease.

**Rationale.** ⚠️ **Verified 2026-08-18 from
[pkg.go.dev/k8s.io/client-go/tools/leaderelection](https://pkg.go.dev/k8s.io/client-go/tools/leaderelection), the
package documentation states verbatim: *"This implementation does not guarantee that only one client is acting as a
leader (a.k.a. fencing)."*** The first draft said leader election "ensures only one active control role agent per
Target/cluster" — a guarantee the library explicitly disclaims, and the kind of confident-recall error `I2` exists
to catch.

This is not a reason to reject the library; it is a reason to put the safety property somewhere else. **A deposed
leader that has not yet noticed cannot do damage**, because its rollout generation is stale (rejected as
non-monotonic) and its idempotency key is either a no-op or a refused mismatch. Its lease is bound to its own mTLS
key, so it cannot present the successor's authority. Election therefore buys **efficiency** — one scraper, one
apply loop, no thundering herd against the API server — while the journal and the lease buy **correctness**.
Designing as though election were fencing is the failure mode.

**Clock behaviour, from the same verified source.** The implementation is tolerant of arbitrary *absolute* clock
differences between nodes (it uses only local timestamps and watches for changes to the election record) but **not**
of arbitrary clock *drift rate*; the tolerated fast/slow ratio is approximately `LeaseDuration : RenewDeadline` —
the documented example being 60 s / 30 s to tolerate one node running twice as fast — and clock synchronization
between nodes is "highly recommended". This composes with `SM-D28`'s `CLOCK_SUSPECT`: a drifting agent already
reports its offset and **fails closed on apply authority**, so it loses the ability to act before it loses
election sanity. The two mechanisms must be configured as a pair, not independently.

**Rejected:** a hand-rolled election on a `Lease` object (reimplements the same non-guarantee with fewer
eyes on it); electing via the DP as arbiter (a CP/DP outage would then stop the *telemetry* role too, and it puts
a Kubernetes concern on the wrong side of an outbound-only channel); assuming a Deployment with `replicas: 1`
suffices (it does not — a partitioned node can run a second pod, which is the exact case election addresses).

## `AG-D7` — Agent configuration is stdlib `flag` + environment + one file; no Viper

Agent configuration resolves from three sources with a fixed, documented precedence: command-line flags →
environment → one file. Parsing is stdlib `flag` plus a single YAML/JSON decode. **Viper is not used.**

**Rationale.** The first draft said "Viper or similar for configuration management", which is a library name where
a decision should be. Viper is **MIT**, verified 2026-08-18 from the
[Viper LICENSE](https://raw.githubusercontent.com/spf13/viper/master/LICENSE) — so licence is not the objection.
The objections are scope and precedence:

1. **The agent's own configuration is tiny and hostile to sprawl.** It needs the DP endpoint, the enrollment token
   path, the certificate and key paths, the state directory, the enabled roles, the buffer bounds and the log
   level. That is a struct, not a configuration framework.
2. **Precedence complexity is a recorded project scar.** `SM-D22` exists because the effective value of a Spring
   Boot property "resolves across **11 property sources**" ([`../dynamic-ui/decisions.md`](../dynamic-ui/decisions.md)
   `UI-D10`; [`../dynamic-ui/design.md`](../dynamic-ui/design.md) §7.3), which makes the effective configuration of
   a managed integration *unobservable* — the reason SM can only ever claim to own **one property source**
   ([`../architecture.md`](../architecture.md)`:362` ff.). Adopting a library whose selling point is layered precedence
   (files, remote KV, env, flags, aliases, live watch) reproduces that exact problem inside the component whose
   job is to be predictable on someone else's production host.
3. **Every dependency in this binary is auditable surface.** The agent holds a private key and the authority to
   restart production processes; a config library that can read from a remote KV store is a network client nobody
   asked for.

**Precedence is stated, not inherited:** flags override environment, environment overrides the file, and the
agent **logs the resolved source of every value at startup** — so an operator debugging the agent is never in the
position SM is in with the MI it manages.

**Rejected:** Viper (above); `koanf` (same shape, smaller — still solves a problem the agent does not have);
environment-only (a `host-local` install on systemd wants a file the operator can review and version).

## `AG-D8` — Structured logging is stdlib `log/slog`; no third-party logging library

Logging is `log/slog` with `JSONHandler` in production and `TextHandler` for local development, at
`Debug`/`Info`/`Warn`/`Error`. **zap and zerolog are not used.**

**Rationale.** The first draft said "zap or zerolog", which is two libraries and no decision. Verified 2026-08-18
from [pkg.go.dev/log/slog](https://pkg.go.dev/log/slog): `log/slog` is **in the Go standard library** (added in
**Go 1.21**) and provides exactly what the agent needs — structured key/value attributes, the four levels above,
`JSONHandler` and `TextHandler`, attribute groups, a `LevelVar` for runtime level changes, and a `LogValuer`
interface for custom rendering. Given `AG-D2`'s floor of `go 1.25`, it is **unconditionally available**.

**Why stdlib wins here specifically:**

- **Zero dependency on the security-critical path.** See `AG-D1` and `AG-D7`: this binary's dependency list is
  audited surface.
- **`LogValuer` is the `I6` hook.** Secret-bearing types can implement it to render redacted, so masking is a
  property of the *type* rather than of every call site that logs it. `I6` forbids secret values in logs, and a
  per-call-site discipline is exactly how that gets violated once.
- **`LevelVar`** gives runtime level changes without a config reload — useful on a host where restarting the agent
  to raise log level is a poor trade.
- **The `MultiHandler` addition in Go 1.26** (per the same source) covers the only fan-out case foreseen, without
  a wrapper library.

**Explicit obligations, not just a library choice.** Agent logs must never contain a resolved secret, a rendered
config body, or a telemetry *value* that `I6`/`FE-D13`-class rules exclude — the journal stores a config **hash**,
and so do the logs. Every log line carries the correlation identifiers needed to tie it to a rollout generation.

**Rejected:** zap (fastest, but the agent's log volume is not the bottleneck and it is another dependency);
zerolog (same); `log` (unstructured — unusable for the dimension-specific heartbeat diagnosis at
[`../architecture.md`](../architecture.md)`:784`).

## `AG-D9` — The telemetry buffer is bounded, disk-backed, drop-oldest, and every drop is counted

A byte ceiling **and** a time horizon, both configured and both exported as gauges. The buffer **survives agent
restart**. On overflow the **oldest** samples are dropped, the count is exported as a first-class metric, and the
affected series carries a **staleness marker**.

**Rationale.** `B/D2` is **`LOCKED`** and already mandates the shape — "bounded node-local buffer with an explicit
backpressure/drop policy, dedupe keys, freshness markers, replay/backfill on reconnect, edge auth"
([`../DECISIONS.md`](../DECISIONS.md) — `B/D2`, §4, restated at [`../architecture.md`](../architecture.md)`:765`) — plus
HA as **Detect + Recover** with a *persisted* buffer ([`../architecture.md`](../architecture.md)`:784`). The first
draft repeated the phrase "explicit backpressure/drop policy" without ever stating the policy, which is the one
thing `B/D2` asks the agent to decide. This decision states it.

**Why *oldest*-first is the right drop.** Under sustained backpressure the console's live view degrades gracefully
when history is lost and misleads badly when the present is lost. Newest-first would freeze every graph at the
moment congestion began while the buffer stayed full of stale samples — indistinguishable, to an operator, from an
MI that stopped emitting. Given `UI-D8`/[`../architecture.md`](../architecture.md)`:273` ("absence renders blank,
never zero"), a frozen line is a *worse* failure than a marked gap, because a gap is honest.

**Why the drop count is a metric and not a log line.** A silent drop is indistinguishable from an MI that stopped
emitting, and that ambiguity propagates: `UI-D9`'s governance cross-check reads empty graphs as evidence. The drop
counter is what lets "SM dropped it" be separated from "nothing was produced". Same reasoning as
[`../architecture.md`](../architecture.md)`:784`'s insistence that a missing heartbeat be investigated across
cause-specific dimensions rather than collapsed to one conclusion.

**Why disk-backed rather than memory.** `B/D2`'s Recover leg says *persisted* buffer, and the agent's own
auto-restart is part of that leg — an in-memory buffer would make the recovery mechanism destroy the data it is
recovering. Bounding by bytes **and** time matters because either alone fails: bytes alone lets a low-rate
instance hold week-old samples that no longer have consumers; time alone lets a high-cardinality instance fill the
host's disk. Filling a customer's production disk is an outage the agent caused.

**Gap semantics stay path-specific**, per `B/D2`: the broker/event path is durable, the pull/actuator path is
best-effort with **explicitly marked** gaps. The agent must not present a best-effort gap as durable data.

**Dedupe keys are part of this decision, not a separate one:** without them, replay/backfill on reconnect
double-counts a counter, and `SM-D26`'s counter `delta` then propagates the error into every rollup tier above it.

**Rejected:** unbounded buffering (an outage the agent causes on the customer's host); drop-newest (above); block
the collector on backpressure (a wedged forwarder would then stop collection everywhere, violating
[`../architecture.md`](../architecture.md)`:784`'s collector isolation); an embedded database for the buffer (a
second storage engine to operate inside a binary whose selling point is that it is one file).

## `AG-D10` — The agent holds no database and issues no SQL

The agent's only persistent state is its state directory: the apply journal (`AG-D4`), the telemetry buffer
(`AG-D9`), its key and certificate, and the highest observed `revocation_epoch`. **No embedded database, no SQL,
no query interface.**

**Rationale.** ⚠️ **This corrects a first-draft error.** That draft's "Tenant Isolation" section cited **`I2`** —
which is *"verify external facts before writing them down"* — for tenant isolation, and asserted that "all
SQL/queries use bind parameters (no string interpolation)" for a component that has no database. The invariant
meant is **`I5`**, and the bind-parameter clause is unsatisfiable-because-inapplicable rather than satisfied.

**What `I5` actually binds where.** `I5`'s central data-access module, server-side scope application and
bind-parameter rule constrain the **CP and DP** — they are enforced in the planes, and `I7` keeps those two apart.
The agent is neither plane and imports neither.

**What `I5` does bind on the agent:** **scope is never self-asserted.** Tenant and Target scope are properties of
the agent's mTLS identity and of the lease claims the CP issues and the DP validates (`SM-D28`) — the agent never
transmits a tenant id the DP is expected to trust, which is the client-supplied-input case `I5` was written
against after the File Mesh audit. The agent additionally **refuses** any command outside its own `target_id` /
`binding_ids[]`, as defence in depth behind the DP's check, never as a substitute for it.

**Why "no database" is a decision and not an observation.** The tempting additions are exactly the ones to refuse:
a local metric store for offline dashboards, a local config cache queryable by the operator, a local audit log
with retention. Each turns the agent into a data plane on the customer's host — with tenant scope, retention,
backup and `I6` obligations that `I7` deliberately concentrates in the DP. The buffer is a **queue**, not a store;
the journal is a **log**, not a table.

**Rejected:** SQLite for the journal and buffer (real appeal — transactions and crash safety for free — but it adds
a storage engine, a file format to migrate, and the standing temptation to query it; `AG-D4`'s append-and-`fsync`
journal is small enough to specify completely); a local Prometheus TSDB for offline retention (a second retention
policy outside DP governance).

## `AG-D11` — Dependencies are few, verified and boring

| Purpose | Dependency | Licence |
|---|---|---|
| Protocol transport | `google.golang.org/grpc` | Apache-2.0, verified 2026-08-18 from the [grpc-go LICENSE](https://raw.githubusercontent.com/grpc/grpc-go/master/LICENSE) |
| TLS / x509 | stdlib `crypto/tls`, `crypto/x509` | stdlib (`AG-D2`) |
| Kubernetes API + election | `k8s.io/client-go` | Apache-2.0, verified 2026-08-18 from the [client-go LICENSE](https://raw.githubusercontent.com/kubernetes/client-go/master/LICENSE) |
| Agent's own metrics | `prometheus/client_golang` | licence **not yet verified** — see below |
| Scrape + discovery reuse | `prometheus/prometheus` `discovery`, `scrape` | licence **not yet verified** — see below |
| Config, logging | stdlib (`AG-D7`, `AG-D8`) | stdlib |

**Rationale.** The first draft listed "google.gRPC", which is not a package name, and gave no licence for anything.
Each entry above is either the standard library or the reference implementation of a protocol the architecture
already commits to: gRPC/HTTP2 over mTLS is `B/D16`; Kubernetes leader election and API discovery are
[`../architecture.md`](../architecture.md) §12; Prometheus `discovery`/`scrape` reuse is named as agent scope at
[`../architecture.md`](../architecture.md)`:906`. **Reusing `scrape` rather than writing a scraper matters** — it
is where Prometheus' text/protobuf parsing, staleness handling and relabelling already live, and all three are
easy to get subtly wrong in ways that surface as wrong graphs rather than as errors.

⚠️ **`I2` gap, stated rather than guessed:** the two Prometheus libraries' licences were **not** verified in this
pass and are recorded as unverified. They must be verified by WebFetch before the dependency lands — this project
has been burned by asserted licence terms once already (TimescaleDB/TSL).

**Rejected:** a hand-rolled HTTP/2 protocol client (reimplements flow control and stream lifecycle); `connect-go`
(interesting for the HTTP/1.1 fallback `B/D16` mentions, but that fallback is undesigned — open item AG-6 — so
adopting it now would be a decision made by dependency rather than by design); vendoring a metrics-exposition
formatter instead of `client_golang` (no benefit).

## `AG-D12` — Version observations carry mechanism and confidence; the agent never issues a verdict

The agent reports what it can observe about a managed integration's version, **each observation tagged with the
mechanism that produced it and a confidence level**. It never computes `OK` / `VERSION_DRIFT` / `VERSION_UNKNOWN`,
and absence of any signal is reported explicitly rather than as silence.

**Rationale.** **The version is generally not observable from the MI, and `I1` forbids making it so.** Cache's
`spring-boot-maven-plugin` runs `repackage` only, with **no `build-info` goal**, and there is no
`spring-boot-starter-parent` to supply manifest entries (`UI-D12`); adding `build-info` is named in `I1` as **not
fair game**. `UI-D12` therefore makes the version **declared at binding and corroborated by observation**, and
`SM-D14` makes detection locus-dependent — reliable-ish under `host-local`, degrading to "unverified" under
`dp-remote`.

The agent is the strongest corroboration source that exists, and that is precisely why it must not adjudicate:

- **The signals differ in strength and the difference is not the agent's to collapse.** "The image tag says 1.4.0"
  is mutable and weak; "`Implementation-Version` in `MANIFEST.MF` says 1.4.0" is strong; "a version endpoint
  returned 1.4.0" is strong but only exists where the MI already exposes one. Reporting one number would throw
  away the information the DP needs to decide whether `VERSION_DRIFT` blocks editing.
- **The verdict has UI consequences the agent cannot see.** `VERSION_DRIFT` and `VERSION_UNKNOWN` **block
  editing**; ⚠️ **`VERSION_UNVERIFIED` must not** — it is Cache-on-a-VM's permanent state (`UI-D12`). An agent
  that reported a bare verdict would eventually block editing on a healthy instance forever.
- **Absence must be positive.** `NOT_EXPOSED`, `UNREACHABLE`, `RESTART_EXPECTED` and genuine `MISSING` are four
  states, not one ([`../architecture.md`](../architecture.md)`:467`), and the bootstrap sequence
  (declare → apply → restart → observable, `:448`) guarantees a window where a correctly-configured instance is
  unobservable. Silence during that window must not read as failure.

**Rejected:** deriving a single authoritative version in the agent (throws away provenance, and `I1` blocks the one
mechanism that would make it reliable); requiring a version endpoint from the MI (`I1`); inferring version from
config-schema fit (circular — the schema is selected *by* version, `SM-D14`).

## `AG-D13` — The `.proto` is drafted in-repo, under `go-agent/proto/`, never `contracts/`

Open item 6 / `AG-1` / `AGW-2` routes the authoritative `.proto` to the architect group **with the `AG-D*` owners**, and the gateway design's §4 is explicitly "the input" to it. Building the agent needs an encoding now. So `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` transcribes §4.3's catalogue and §4.11's enum one-for-one — same message names, same field names, nothing invented — under a header that says DRAFT and non-authoritative. It is **not** placed under `contracts/`, which is single-writer and normative.

Two transcription notes are recorded because they are the only places the transcription had to decide anything. Proto scopes enum values at package level, so values carry the enum-name prefix (`ERROR_CODE_SUPERSEDED`); the bare names in the design stay the semantic names and `internal/msg` uses them. And `CommandAck.detail.code` needs an enum the design leaves implicit: §4.4 rule 5 puts `ADMISSION_BLOCKED` there and §4.8 uses `LEASE_NOT_ACTIVE`, neither of which is in §4.11 — so `AckDetailCode` carries those two plus a reused `ErrorCode`.

**Containment.** Only `internal/wire` and `internal/wire/codec` import the generated package; every other package speaks `internal/msg` structs. When the reviewed contract lands, the swap is one directory and one codec file, and no reducer changes. **Exit condition:** this decision is superseded the day `contracts/` carries the real proto.

## `AG-D14` — The agent is an event-driven reactor with pure reducers

Every input — an inbound message, a timer, a finished operation, a connection event, a signal — becomes an `Event` on one inbox. One loop drains it and passes it to pure reducers that return `(state, []Effect)`; the loop executes the effects in the fixed order **`Persist → Delete → Send → RunOp → timers → connection`**. Reducers never read a clock, never do I/O, never spawn goroutines: the only impurities arrive through `reactor.Env` (`Now`, `Offset`, `NewID`, `Jitter`, `Limits`).

**Rationale.** The properties this component must prove are sequencing properties — "no `GenerationAvailable` is acted on unless the lease is `ACTIVE`", "the journal entry is fsynced before `CommandReceived` leaves the process", "a resent `LeaseRenew` reuses its `message_id`". Under a reducer they are table tests over `(state, event) → effects`; under goroutines with shared state they are timing arguments. The effect order **is** the agent-side write-before-act rule, in one place instead of at every call site.

**Rejected.** *Goroutine per stream with a mutex* — idiomatic, but scatters lease and journal transitions across five goroutines. *Full event sourcing* — the design requires only the command journal and the lease grant to be durable (`AG-D4`, `AGW-D15`); journaling heartbeats and credits is persistence with no requirement behind it, and the repo already holds the "events are notices, rows are the record" position (`BE-D1`).

**Consequence:** a failed `Persist` is fatal. The agent stops rather than act without durability.

## `AG-D15` — `internal/` is rewritten; the superseded scaffold is archived, not deleted

The scaffold in `go-agent/internal/` was written against the surrogate wire. The gateway design's fork inventory named three concrete incompatibilities — journal phases (`PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE` vs the baseline `RECEIVED→STAGED→PERSISTED→REPORTED`), a config shape encoding a separate telemetry endpoint where the design has one connection, and `WriteFileAtomic` without the parent-directory fsync §5.1 requires. All three are structural, so the packages are replaced rather than patched.

**Replaced, but kept.** `internal/{config,docker,persist,telemetry}`, the two demo CLIs and the `java-demo` fixtures move to **`go-agent/oldGo-Agent/`** with their git history; nothing is deleted. The Docker-lifecycle CLI (`start|stop|restart|status`) and the telemetry-collect CLI were proofs that predate the wire, and their jobs now belong to `internal/runtime/docker` (driven by commands, never by an operator at a prompt) and to slice 2's telemetry role — but they remain the clearest record of how the Engine API was driven and what shape local telemetry took, which is worth having while those are rebuilt.

The archive carries its own `go.mod` so the parent module's `go build ./...` skips it: archived packages no longer resolve their imports and are not expected to compile. It is reference material with no authority — where it disagrees with the gateway design or the baseline `AG-D*` family, they win.

What survives into the new packages is **re-derived, not copied**: the atomic-write primitive (now with the parent fsync) and the symlink-escape refusal the gateway design explicitly credits as a genuine addition.

## `AG-D16` — The DP test double is a scenario-driven fake gateway

`internal/fakegw` implements the DRAFT proto's server side over real mTLS/h2: it mints a CA, signs CSRs, enforces single-use enrollment tokens with `(token, CSR)` replay, issues and renews leases under the single-use nonce, and runs an ordered YAML **scenario** (`expect`, `offer`, `superseded`, `error`, `revoke`, `drain`, `disconnect`, policy switches) while recording a transcript. The same engine runs in-process from `go test` and as `cmd/fake-gateway` for demos.

**What it is not.** It has no ports, no persistence and no `dp-core`. **It is not evidence about the plane's correctness** — the gateway design's own §8 failure matrix and its PostgreSQL execution harness are. A green scenario here means the agent obeys the protocol as this repo reads it, nothing more.

## `AG-D17` — `item_id` and `sequence` (the agent's answer to `AGW-20`)

The gateway design asks the `AG-D*` owners for two things it cannot invent: a stable per-item report identity, and `sequence` reset/persistence semantics.

**`item_id`** is a UUIDv7 minted once when an observation becomes a report item. The item is written to `reports/pending.json` **before** it is sent and is re-sent byte-identical — same id, same sequence, same content — across reconnects and process restarts until a `ReportAck` names it. A new observation of the same replica is a new item; a pending item is never edited. That answers AGW-20's four properties: type (UUIDv7 string), uniqueness within one agent (globally, in practice), persistence across restarts (the pending file), and a non-reuse horizon (unbounded — ids are never reissued).

**`sequence`** is a per-`(binding_id, replica_ref)` `uint64`, incremented and **persisted to `reports/cursor.json` before the item exists**. A restart therefore cannot reset it, which removes the case AGW-20 names as unsolved from this agent's behaviour by construction. If the cursor is lost with the data directory, the counter is **reseeded from `dpNow.UnixNano()`**: any previous value is necessarily smaller, so monotonicity holds without the agent knowing what it was.

⚠️ **This is the agent half only.** The DP-side watermark, the canonical projection and the stored fingerprint remain AGW-20's, and this decision does not close it.

## `AG-D18` — A periodic report at `report_interval` (the agent's answer to `AGW-19`)

The agent sends a full `ActualStateReport` for every binding at `SessionAccept.limits.report_interval` **even when nothing has changed**, and once immediately after every `SessionAccept`.

**Why it is not optional.** The DP's replica registry retires an incarnation whose `last_seen_at` has gone stale, and `last_seen_at` is refreshed **only** by an actual-state report — `Heartbeat` deliberately carries no replica or incarnation identity. Without a cadence, a healthy unchanged workload is silence-retired; taking liveness from the heartbeat instead would keep a vanished replica alive for as long as its agent lives, which is the exhaustion the sweep exists to prevent. The DP delivers the interval, so the plane sets the rate and the agent does not guess it.

## `AG-D19` — Lifecycle outcomes are decided by observation

`restart` and `stop` report `APPLIED` only when the **post-state is observed** — for `restart`, running with a `StartedAt` newer than before; for `stop`, not running — and `FAILED_NO_EFFECT` when the call failed and the pre-state is unchanged. The verb's own return value is never the verdict: a Docker `restart` that returns success while the container fails its health check has changed the world and not achieved the goal.

⚠️ **The gap, stated rather than papered over.** `AG-D5`'s outcome set has no member for "the verb changed state and still failed". This design reports `FAILED_NO_EFFECT` with the observed facts and lets the next `ActualStateReport` — which is what convergence actually reads (gateway §4.8) — carry the truth. Adding an outcome is a wire change and therefore the `AG-D*` owners' and `AGW`'s jointly; **routed, not decided here.**

## `AG-D20` — Docker binding resolution by label, path by discovery

Nothing in gateway §4.3 carries placement facts to the agent: a command names a `binding_id`, and the agent must find the container, the declared config path and a writable host path itself. On Docker, for v1:

| Fact | Source |
|---|---|
| the container | the label `com.solace.sm.binding-id=<id>`, exactly one match (zero → `ErrNotFound`, many → `ErrAmbiguous`) |
| the config path | the container's own `--spring.config.additional-location=` / `--spring.config.location=` argument, else `SPRING_CONFIG_ADDITIONAL_LOCATION` / `SPRING_CONFIG_LOCATION` |
| the host path | that directory mapped through a **bind mount** (required — the agent writes on the host) |
| `replica_ref` / `incarnation` | container name (RFC 1123-valid) / container ID (hex) |

**`I1` check.** All four are **deployment configuration** — a label, a launch argument, a mount — which `I1` names as fair game. None requires an MI code or build change. Pre-flight refuses (`PREFLIGHT_REFUSED`) when any of them cannot be established, and reports competing Spring sources (`SPRING_APPLICATION_JSON`, `SPRING_CONFIG_LOCATION`) rather than writing beside them (`SM-D21`).

⚠️ **Flagged to the `AGW` owners:** this convention exists because the plane carries no placement facts. If the protocol later carries them, this decision is superseded rather than extended.

## `AG-D21` — The telemetry role is collectors -> buffer -> sender, beside the control loop, fed only by its published view

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/pipeline.go` and
`go-agent/internal/telemetry/view/view.go` (@ `87e338e1`); no source text existed** — the identifier is cited by `go-agent/CLAUDE.md`,
`internal/telemetry/pipeline.go` and `view/view.go` and defined nowhere on the branch (measured by `git grep`). It records what the
code does; it is not evidence of what was intended.

The telemetry role (`--roles control,telemetry`) is a pipeline of three stages run beside the control loop: **one collector per
declared binding**, isolated so a panic or hung endpoint fails alone; the on-disk **buffer** (`AG-D24`); and the **sender**
(`AG-D23`), plus a one-minute age sweep. The pipeline never touches the control loop's reducers: the loop publishes **one
immutable `Snapshot`** (the session and its limits, the lease's binding ids, the DP-minted id table, a halted flag), replaced
whole, so a reader never sees a session from one publish and ids from another. A binding outside the lease collects nothing. The
first batch waits for the first `ReportAck` that mints the ids every sample carries.

**Consequence on the agent path.** The DP must return `replica_id` and `incarnation_id` in every `ReportResult` (`AGW-D25`), or
no batch is ever sent.

**Enforced by:** the pipeline/collector/view unit tests in the agent (`go test ./internal/telemetry/...`).

## `AG-D22` — Metric declarations: an interim local file of schema-shaped `http_json` metrics

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/declare/declare.go`, `examples/telemetry.yaml`,
`README.md` and `RUNBOOK.md`; no source text existed.** Superseded for production by `AG-D28`.

`--telemetry-config` names a YAML file (`schema_version: 1`) of bindings; each has an `interval` (>= 1 s), an optional `timeout`
(default `min(interval, 5s)`, never above the interval) and `metrics[]` shaped like the Solution Package schema's metric object
(`name`, `metricType` `counter` or `gauge`, `unit`, `source`). The source kind is `http_json` only (`port`, `path` starting `/`,
a required RFC 6901 `pointer`, `onUnavailable` `degrade` or `error`); `histogram`, `when` and the later kinds (`prometheus`,
`solace_event`, `exec`, `jdbc_query`) are **refused**. Parsing is strict (unknown keys are errors). A pointer absent from the
document produces **no sample, never a zero**. The file is *interim*: the plane did not deliver declarations (the branch cites `AG-14`; no definition exists; superseded for production by `AG-D28`).

**Enforced by:** `declare_test.go`, `collect_test.go`.

## `AG-D23` — The telemetry sender is a pure reducer over credits, in-flight batches and the stream's state

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/send/reducer.go` and `runner.go`; no source text
existed.** Applies `AG-D14` (reactor with pure reducers) to the Telemetry stream.

`send.Reduce(state, event, env)` returns `(state, effects)`; a `Runner` executes the effects against the buffer and the stream.
**Credits are the only backpressure** (`AGW-D8`): one batch in flight per credit; a `SessionUp` resets credits to
`Limits.initial_credits` and requeues in-flight batches; a broken stream returns the credits its in-flight batches held and
reopens with jittered exponential backoff (1 s doubling to a 30 s cap). A `TelemetryAck` trims the buffer to its high watermark; a
`TelemetryReject` with a *terminal* code (`PAYLOAD_TOO_LARGE`, `UNKNOWN_REPLICA`, `INCARNATION_RETIRED`, `UNDECLARED_LABEL`,
`LABEL_INVALID`, `BINDING_NOT_IN_LEASE`, `TENANT_MISMATCH`) drops the batch as a gap and does not retry it; any other reject retries
after the gateway's `retry_after`. A stream with no known ids is not sent. The oldest produced batch is sent first.

**Enforced by:** `reducer_test.go`, `runner_test.go`.

## `AG-D24` — The telemetry buffer: a file per batch, bounded by bytes and age across all streams, drop-oldest, every drop a gap marker

**Status: PROPOSED. Reconstructed on 2026-10-08 from `go-agent/internal/telemetry/buffer/buffer.go`, `tmetrics/tmetrics.go` and
`README.md`; no source text existed.** Implements `AG-D9`.

One file per batch under `<data-dir>/telemetry/<stream>/`, written atomically, surviving an agent restart. Two **global** caps:
`--telemetry-buffer-bytes` (default 64 MiB) and `--telemetry-buffer-age` (default 24 h, must be positive). When either is
exceeded the **oldest batch across all streams** is dropped first, and **every drop is recorded as a gap marker** the stream's next
batch carries (`from_seq`, `to_seq`, reason `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, `UNREGISTERED` or `CORRUPT`) and counted
(`sm_agent_telemetry_dropped_batches_total{reason}`); both caps are exported as gauges, not merely enforced. A batch produced in the
future (the clock stepped back) is never expired early. A stream id is a safe path segment.

**Enforced by:** `buffer_test.go`; the telemetry end-to-end tests.

## `AG-D25` — A failed buffer write drops the batch and is counted; it is never fatal

**Status: PROPOSED. Reconstructed on 2026-10-08 from `buffer.Append`'s doc comment and `tmetrics.WriteErrors`; no source text
existed.** **Deliberately differs from `AG-D14`**, whose consequence is that a failed `Persist` of the *command journal* is fatal.

A telemetry batch that cannot be written (disk full, permissions) is **dropped, reported through the error return and counted**
(`sm_agent_telemetry_buffer_write_errors_total`); the agent keeps running. `WriteAtomic` leaves no partial file, so the failed
batch's sequence number is reused. The asymmetry is the point: losing a telemetry sample is a gap the plane can see; losing the
command journal's durability would let the agent act without being able to prove it did.

**Enforced by:** `buffer_test.go` (a failing `Save` drops, counts and reuses the sequence).

## `AG-D26` — Discovery by label, reporting facts; the container is found by name through the DP-supplied binding

**Status: PROPOSED.** Records `OR-126` (A6). **Supersedes `AG-D20`** (a binding's container found by the label
`com.solace.sm.binding-id`), which flagged itself superseded the day the protocol carried placement facts. Applies
`AGW-D30`.

1. A container opts in with the label **`com.solace.sm.instance=<name>`** and the label **`com.solace.sm.config-file=<absolute
   path in the container>`** (optionally `com.solace.sm.health-port`). These are deployment configuration (`I1`).
2. The agent scans for labelled containers (running or stopped) and sends a `DiscoveryReport`: container id, name, image
   reference, **only `com.solace.sm.*` labels**, bind mounts, published ports — **facts, not conclusions**. It is a complete
   snapshot, sent after every `SessionAccept` and whenever the set or an id changes. At most 64 items per report.
3. The agent derives **no** placement field and names **no** environment, organization or target. The DP decides (`AGW-D30`).
4. The `DiscoveryResult` for a registered container carries its **`binding_id` (the placement id)**; the agent persists
   `container_name -> binding_id` and resolves a command's container **by name** (a re-created container keeps its placement by
   name). *Draft finding F-1: without `binding_id` in the result the agent cannot reach the placements it caused the DP to
   create; this is a hole in the plan's wire, closed in `docs/design/backend/agent-gateway/proto-amendments-015.md`.*
5. `AG-D20`'s other facts stand: the config path is the **label's** (`AGW-D30`; `AG-D20`'s launch-arg/env discovery remains only for the
   development `binding-id` path, so that the file the agent writes is the file the DP recorded — review R-20), mapped through a **bind
   mount** (required — the agent writes on the host); `replica_ref` is the container name and `incarnation` the container id.

6. *(Implementation clarification, plan 015 T2.3, 2026-10-10.)* v1 rescans on `SessionAccept` and on every report tick (`ReportInterval`) and sends a
   `DiscoveryReport` only when the snapshot changed; it does not subscribe to Docker events (`start`/`die`/`destroy`), so a change is reported
   within one report interval, not instantly. The truncation counter is `sm_agent_discovery_truncated_total`. A container is commanded by name only if it carries the instance label; one container
   name maps to one binding id and a later registration of the same id under another name replaces the first. A container removed between the list
   and the inspect is skipped, not a failed scan.

**Rejected.** *Keeping the `binding-id` label* — the id is DP-minted after discovery. *The agent choosing the instance's
solution or environment* — `I5`.

**Enforced by:** `discovery_test.go` (two labelled containers -> two items with their mounts and ports; an unlabelled one none; a
re-created container reports its new id under the same name; non-`com.solace.sm.` labels never leave the host).

## `AG-D27` — Health, container start time and the write precondition are observed facts

**Status: PROPOSED.** Applies `OR-101`, `OR-112`, `AG-D19`. Wire: `ActualStateReport.health`/`container_started_at`,
`CommandAck.container_started_at`, `CommandOffer.expected_current_sha256`, `OPERATION_REMATERIALIZE`.

1. **Health is the declared probe's observed outcome (`OR-130` D-4); Docker's check is never the gate.** The DP sends the per-binding
   `HealthProbe{kind (actuator-health|http-status), path, host_port, strength}` (mirroring `dp.api.inventory.HealthProbeSpec`) as
   `TelemetryDeclarations` field 5, under the existing `CAPABILITY_DECLARATIONS`. The agent GETs the host loopback at `host_port` (the same
   place the `dp-remote` via-ssh curl probes) and **reports facts**: the HTTP status, the actuator `status` string, or refused/timeout. The DP
   maps them with the existing `ProbeResultMapper`, identically on both paths. Docker's health state may ride along as an informational hint
   only. **No declared probe -> nothing is probed and the DP records `NOT_APPLICABLE`.** Not running or inspect failed -> `UNREACHABLE`.
   "Running" never becomes "healthy" (`OR-101`). The agent sends no strength it cannot know; the DP reads the *declared* strength.
2. **Start time is the host's clock, untouched.** Docker `State.StartedAt` is reported as read: **no `Clock.Offset()` is applied**
   (the existing `restart_observed_at` *is* offset-corrected and is left alone). A never-started container reports no value. This
   is `OR-112`'s same-clock rule: the DP compares host values with host values.
3. **The precondition.** On `OPERATION_REMATERIALIZE` the agent hashes the file as it stands, **before** writing: equal to
   `expected_current_sha256` (or the file absent when it is present and empty) -> write as `APPLY`; otherwise `OUTCOME_REFUSED`
   / `ERROR_CODE_TARGET_MOVED` and **no write**. The check and the write are one journaled step (`AG-D4`). The offer's digest and
   the file's digest are never logged (`AG-D29`).
4. A REMATERIALIZE offer without the field is a protocol error (`ERROR_CODE_KEY_HASH_MISMATCH`), never an unconditional write.
5. `OPERATION_REMATERIALIZE` is an `apply-config` operation with its own `verb_ceilings` key (`rematerialize`); a lease without the key gives
   it a zero ceiling and every REMATERIALIZE fails, so the agent's operation tables carry the member and T2.3 tests a lease that has it
   and one that does not (review R-7). The ack of an APPLY, a REMATERIALIZE and a RESTART carries `container_started_at`.

6. *(Implementation clarification, plan 015 T2.3, 2026-10-10; items 1-5 above are as first written.)*
   - The precondition passes when the file already holds the offer's **own** payload: the replay of a crashed REMATERIALIZE (rename done,
     journal not yet), which the idempotent writer completes as PERSISTED. Where the adapter offers a conditional writer it evaluates the
     precondition **on the read the write is based on**; the window that remains is between that read and the rename (a few syscalls, only
     another writer of the same file could use it).
   - A lease with **no ceiling** for a verb gives it a zero ceiling; the agent refuses such an operation before dispatch (`CAPABILITY_MISMATCH`)
     instead of arming a zero-length timer and still executing it. A step whose context is already over never starts a write.
   - A container that is not running is `UNREACHABLE` and is **not** probed (another process may own the port); a declared probe is then
     reported as `failure=REFUSED` so the DP maps it like a refusal. An **inspect failure** observes nothing (no invented refusal). The probe
     only runs when its `host_port` is one the container itself publishes, never follows a redirect and bypasses proxies. Any failure to get
     an answer other than a timeout is `REFUSED` (the wire carries only those two reasons).
   - `container_started_at` rides only on an **APPLIED** ack (and on the report): a failed attempt establishes no `OR-112` baseline.

**Enforced by:** a probe test against a stub HTTP server (200/UP, 503, refused, none declared); `commands_test.go` (a differing
precondition writes nothing and acks `REFUSED`); mutation proofs in T2.3 (skip the precondition; report `HEALTHY` when Docker says healthy but the probe fails).

## `AG-D28` — Metric declarations arrive on the wire; the file flag is development-only

**Status: PROPOSED.** Supersedes `AG-D22` for production by citation. The DP sends `TelemetryDeclarations{binding_id, interval,
metrics[]}` per binding after `SessionAccept` and on change; each message **replaces that binding's set atomically**. The metrics
are the package's `http_json` metric objects for the placement (the shape `AG-D22` already parses); `interval` is `OR-73`'s 5 s.
A metric the agent cannot collect (histogram, an unknown kind) is dropped with a counter, never a zero sample. `--telemetry-config`
remains for development and tests. The agent sends no declaration of its own. *(T2.3 clarification:)* a binding carries at most 256 metrics and the
agent at most 64 declared bindings; a probe kind the agent does not know drops the probe (nothing is probed, the metrics still apply) rather than the message.

**Enforced by:** `declare_test.go` (a message replaces the set atomically); `AgentTelemetryBridgeIT` for the DP's side.

## `AG-D29` — The command payload is spooled on the host under a named `I6` exception (`OR-129`); purged at terminal, swept at start

**Status: PROPOSED.** Resolved in place by `OR-129` (the owner's ruling on `OR-128` (14)) before first merge; see the changelog.

The rendered payload is the configuration file's content and **can contain resolved secrets** (`I6`). The agent writes that file to the
host anyway, so a second on-host copy for the life of a command is a bounded exception, **accepted by name** (`OR-129`):

1. `Persist{Blob}` stays (`go-agent/internal/commands/commands.go:258`): the payload is a spool file, mode 0600 in a 0700 directory owned
   by the agent's user.
2. It is deleted in the effect batch that makes the record terminal (the existing `reactor.Delete` before the terminal `Send`,
   `commands.go:323`) — before the outcome leaves the process, which satisfies `OR-129` (2) "once pushed and acknowledged" — on **every**
   terminal path, including `REFUSED`, `RENDER_CHANGED`, `TARGET_MOVED`, superseded and deadline. No wire acknowledgement is added.
3. At start, before the first event, the agent deletes every spool file whose record is terminal, acknowledged, superseded or absent.
4. The payload and any plain digest of it are never logged (`CommandOffer.LogValue` and `String` print neither; plan 015 Global constraint 9).
   The command journal's plain `ConfigHash` copy exists regardless (it is the agent's journal, `AG-D4`, not SM's storage, `OR-32`).
5. `OR-126` (A5) still holds for SM: the DP stores no rendered bytes (`AGW-D26`). An `Error{ERROR_CODE_RENDER_CHANGED}` answering a pull is
   a terminal refusal of that command.
6. *(Implementation clarification, plan 015 T2.3, 2026-10-10.)* The purge removes the whole generation directory (`apply/<binding>/<generation>`),
   not only the `payload` file, so no empty directory or crashed-write temp file is left. The start-up sweep also **re-checks the modes and the
   owner** of every live spool directory and file (tightens a wider mode to 0700/0600, removes a directory that holds a non-regular entry or is
   not owned by the agent's user), removes stray `*.tmp` files of a crashed write, and drops a **superseded** record's spool (a generation below the
   binding's highest PERSISTED/APPLIED one, left live by a crash between the newer record and the older one's partial-delete); the command
   reducer also refuses such a generation as `STALE_GENERATION` at dispatch. Not swept: the `.<name>.*.sm-tmp` copy a crashed `configwriter.Write`
   leaves next to the target on the host (it is in the customer's config directory, 0600; the next write of that file replaces it). A live `RECEIVED` record **resumes from the spool**
   (its payload must hash to the record's `config_hash`; a corrupt spool is discarded for the DP to redeliver) and is held, not refused, until
   the lease admits it, exactly like `STAGED`: the DP acknowledged it as received and awaits an outcome.
7. *(Plan 015 S4.)* The ack `detail.message` is a **code-keyed template** chosen from the error's class (config-write phase, deadline, file
   operation, `%T` of anything else), bounded to 160 bytes and printable. No config-writer or Docker error text is copied into it: such text can
   quote the rendered file or the digests (`yaml: ... "password: ..."`, `payload hash X != declared Y`).

**Rejected.** *Memory only with a re-pull after a restart* — a crash plus a changed rendering (a rotated secret) fails an apply that the
spool would finish from the local file. *Logging the digest "for debugging"* — it lets log readers test guessed secrets.

**Enforced by:** `commands_test.go` — file and directory modes; the purge on each terminal outcome; the sweep deletes terminal and unknown
files and keeps a live `RECEIVED`/`STAGED` file; resume from the spool after a restart without `CommandPull`. Mutation proofs: skip the
sweep; write 0644; skip the purge on `REFUSED`. `msg_test.go` (offer `LogValue`/`String` contain neither payload nor digest).

## `AG-D30` — The DP may set the telemetry push interval per session; unset is `OR-73`'s 1 min

**Status: PROPOSED.** Applies `AGW-D27` item 4 ("5 s collection / 1 min push", `OR-73`, `OR-128` (8)) on the agent side, where
only the 5 s collection was built (`AG-D28`). Amends nothing; `AG-D23`'s credits and `AG-D24`'s buffer are unchanged. Spec:
[`specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](specs/2026-10-13-go-agent-telemetry-push-interval-design.md);
plan: [`../../plans/go-agent/008-telemetry-push-interval.md`](../../plans/go-agent/008-telemetry-push-interval.md).

1. `SessionAccept.limits.telemetry_push_interval` (field 8, appended to `contracts/agent/v1`) sets how often the sender releases
   stored batches. **Unset** (what today's gateway sends) means `OR-73`'s **1 min**; **`0`** means each batch is sent as soon as it
   is written (what G-A1 and G-A2 measured); a value is valid when at least 1 s and below `--telemetry-buffer-age`. Anything else
   is refused — logged once per session and counted in `sm_agent_telemetry_limit_ignored_total{limit="push"}` — and the default
   applies. When the buffer age is 1 min or less the default cannot apply, and the agent sends at once and logs why.
2. The value is resolved at each new session and only then; a session drop keeps the schedule; nothing is persisted, so everything
   stored before an agent restart is due on the first session.
3. At each tick the stored batches go individually, in order, one in flight per stream (`AG-D23`); oversize and `UNREGISTERED`
   drops do not wait for a tick.
4. Pull is untouched: `TelemetryDeclarations.interval`, per binding (`AG-D28`).
5. Holding batches widens a lease-change loss: the DP admits a batch against the session's current lease, so when a binding leaves the lease (re-placed or unbound) what the agent still holds for it — up to one push interval — is refused `BINDING_NOT_IN_LEASE` and dropped as a gap. No agent-side change recovers it (central open item 48 (e)).

**Rejected.** *A DP-set pull on `Limits`* (solution-manager-main's plan 007) — a second source for the number
`TelemetryDeclarations.interval` already carries. *Absent means send at once* — it leaves `AGW-D27` item 4 unbuilt against
today's gateway. *Merging stored batches into one at push time* — it reworks `AG-D23`/`AG-D24`'s seq, in-flight and gap
guarantees. *A local flag* — the DP is the source.

⚠️ **Flagged (`I4`), not resolved:** a DP-chosen value departs from `OR-128` (8)'s "fixed values"; the default keeps the fixed
value. Central open item 48 routes it, with the Java gateway sending the field.

**Enforced by:** `cadence_test.go` (`TestResolve`); `reducer_test.go` (the push cases); `runner_test.go`
(`TestRunnerHoldsANewBatchUntilThePushTick`, `TestRunnerSendsWhatWasStoredBeforeItStartedAtOnce`); `pipeline_test.go`;
`codec_test.go` (`TestTelemetryPushIntervalKeepsItsThreeStates`); the integration tests `TestIntegrationTelemetryPushInterval*`.
Mutation proofs M1–M4 in plan 008's Results.

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-13 | `AG-D30` added, `PROPOSED`, by go-agent plan 008: the DP may set the telemetry push interval per session; unset is `OR-73`'s 1 min. Ported from solution-manager-main's plan 007, where the idea was that tree's `AG-D26` on a DRAFT wire and also carried a pull interval; neither that id nor the pull half is carried (this register's `AG-D26` is discovery). No earlier row edited (`I3`). |
| 2026-10-10 | Plan 015 T2.3 implementation clarifications (no decision's meaning changed, `I3`): `AG-D26` item 6 (rescan cadence, not Docker events), `AG-D27` **item 6** (appended; item 5 is untouched: replay passes, writer-side precondition, zero-ceiling refusal, probe pinned to the container's published ports, `StartedAt` only on APPLIED), `AG-D29` items 6 and 7 (purge removes the generation directory; the sweep re-checks modes/owner, removes crashed-write temp files and drops superseded records; a live `RECEIVED` record resumes from the hash-checked spool and is held, not refused; the ack detail is a template, never error text). The six wire additions are appended to `contracts/agent/v1/agent_gateway.proto` (field numbers verbatim from `proto-amendments-015.md`). |
| 2026-10-09 | **`AG-D13` superseded by its own exit condition** ("never `contracts/`; superseded the day `contracts/` carries the real proto"), `OR-126` (A1), plan 015 T1.2: `agent_gateway.proto` now lives at `contracts/agent/v1/agent_gateway.proto` (package, field numbers and content unchanged), with its `buf.yaml` workspace, `baseline.json` and `check_proto_append_only.py` (`contracts/run-checks.sh` step 18). The moved file is **normative under `contracts/`' single-writer rule**: a change is a deliberate reviewed change, append-only, never a convenience edit. `AG-D13`'s own text is not edited (`I3`); this row records the supersession. |
| 2026-10-09 | `AG-D29` **resolved in place by `OR-129` before first merge** (unmerged, unratified, nothing built; the amendment policy above, and the text's own provision that the unchosen variant is deleted when the ruling is recorded). `AG-D27` item 1 corrected in place for `OR-130` D-4 (the declared probe, not Docker's check, is the health signal). `AG-D22`'s dangling `AG-14` citation annotated. Header note added. ⚠️ `AG-D26`–`AG-D29` cite `AGW-D25`/`AGW-D26`/`AGW-D30`, `decisions-015-draft.md` and `proto-amendments-015.md`, which exist only on the backend PR; the agent PR merges first, so those links dangle until the backend PR merges. |
| 2026-10-08 | `AG-D21`–`AG-D25` **reconstructed** from the code that cites them (`go-agent/internal/telemetry/**`) — each cited by `go-agent/README.md`, `CLAUDE.md`, `RUNBOOK.md` and Go files at `87e338e1` and defined nowhere on that branch; each marked "no source text existed" and recording what the code does, not what was intended. `AG-D26`–`AG-D29` added by plan 015 T0.3 (reviewed 2026-10-08, texts from `docs/design/backend/agent-gateway/decisions-015-draft.md`): discovery by label, observed health and write precondition, declarations from the wire, and the command payload on the host — **`AG-D29` is open pending `OR-128` (14)**. **Forward pointer, open items:** `AG-D*` text, the specs and the plans taken with this register cite the Agent Gateway's open items 34–39 (e.g. the cgo item 35 in `specs/2026-09-21-go-agent-control-path-design.md` and plan `005`); those are central items **42–47** since 2026-10-08 (34 → 42, 35 → 43, 36 → 44, 37 → 45, 38 → 46 closed, 39 → 47 — the map in [`../DECISIONS.md`](../DECISIONS.md) after the open-items table), and the central items 34–39 are different questions. No earlier row is edited (`I3`). |
| 2026-09-22 | `AG-D13`–`AG-D20` from `docs/design/go-agent/specs/2026-09-21-go-agent-control-path-design.md`: DRAFT proto, reactor architecture, clean rewrite, fake gateway, the answers to `AGW-19` and `AGW-20`, lifecycle outcomes and Docker binding resolution. |
| 2026-08-18 | **Family created (`AG-D1`…`AG-D12`), all `PROPOSED`.** Raised by an audit finding that [`design.md`](design.md) had been written to an earlier standard than its siblings — no citations, no per-choice rationale, no flagged conflicts and **no decisions file**, though `AG-D*` is reserved for this component in [`../README.md`](../README.md). Four of the twelve correct first-draft statements rather than merely record them: `AG-D3` retracts agent-initiated restart (contradicted `SM-D15`), `AG-D4` + `AG-D5` supply `LOCKED` `B/D16`'s crash-safety guarantees the draft had replaced with the `dp-remote` degradation, `AG-D6` retracts the claim that `client-go` election guarantees a single leader, and `AG-D10` corrects an `I2`/`I5` miscitation. `AG-D2`, `AG-D7`, `AG-D8`, `AG-D11` replace named-library-without-rationale entries, each with a WebFetch-verified citation per `I2`. See [`design.md`](design.md) §14. |
