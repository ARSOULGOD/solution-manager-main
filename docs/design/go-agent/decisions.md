# Go-agent decisions — `AG-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified. **There is no agent
code**, and none may be written — open item AG-1 (the agent↔plane `.proto`) is unwritten.

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
| `AG-D21` | The telemetry role is a separate pipeline with its own loop; the reactor publishes a read-only view | PROPOSED |
| `AG-D22` | Interim local declarations file, schema-shaped, until the plane delivers declarations | PROPOSED |
| `AG-D23` | One logical stream per binding; one batch in flight per stream | PROPOSED |
| `AG-D24` | Buffer: one file per batch; global byte and age caps; drop-oldest; in-flight spared; gap reasons `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, `UNREGISTERED`, `CORRUPT`, or the reject code | PROPOSED |
| `AG-D25` | A telemetry write failure is counted, not fatal — unlike control-path `Persist` | PROPOSED |
| `AG-D26` | The DP sets the telemetry pull and push intervals per agent on `SessionAccept.limits`; a push interval holds stored batches until the next tick | PROPOSED |

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

## `AG-D21` — The telemetry role is a separate pipeline; the reactor publishes a read-only view

Telemetry runs in its own goroutines — one collector per binding, a buffer, and a sender with its own loop and pure reducer (events in, effects out, table-testable like the control reducers). The control reactor receives **no telemetry events**. It publishes one immutable value, `View.Current()` (session phase and limits, the lease's binding set, the DP-minted ids), through a mutex-guarded `view.Hub` holding one immutable `Snapshot` (corrected after the final review: not an atomic pointer); `Changed()` is closed and replaced on every publish. `InLease` and `Lookup` are methods on that snapshot, so a reader never sees a session from one publish and ids from another. The agent's roles are `--roles control` (default), `control,telemetry` and `telemetry`; a telemetry-only agent advertises `report-only` and refuses commands locally with `CAPABILITY_MISSING`, through a capability check added in front of the lease-only offer gate.

**Rationale.** Collector isolation is a hard requirement (a bad collector fails alone, never the process, and must not delay a `Revoke`); `AG-12`'s costs are per reactor event, so keeping telemetry off the reactor leaves that rate where slice 1 measured it; and the wire already separates the streams. **Rejected.** *Telemetry events through the reactor* — couples collector latency to the control path and multiplies `Snapshot` deep copies (`L23`).

**Policy note, routed to `AG-11`.** `InLease` follows the lease grant's binding set and ignores the authority phase, so collection continues while the lease is `LEASE_EXPIRED`, `CLOCK_SUSPECT` or `RENEW_DEGRADED`. Nothing is sent unless the session is `ACTIVE`, and the DP refuses an unleased binding at ingest (`BINDING_NOT_IN_LEASE`, which the agent records as a gap). Accepted for this slice; whether telemetry should follow the lease phase belongs with `AG-11`.

## `AG-D22` — Interim local declarations file, schema-shaped

Until the plane delivers metric declarations (`AG-14`), the agent reads a local YAML file (`--telemetry-config`) of bindings, each with an interval and `metrics[]` entries that are valid instances of the solution-package schema's `metric` object restricted to the `http_json` source. `unit` is required although never sent, so an entry survives the move to a plane-delivered source unchanged. The file is validated at start; an invalid file refuses to start (exit 2) naming the binding and metric index, and a binding id must be a safe path component (it names a directory), so `.` and `..` are refused. **Rejected.** *Inventing a wire message for declarations* — the wire is the gateway design's to define. Response bodies from scraped endpoints are never logged or stored (`I6`): only numbers at declared pointers leave the collector.

**Amended by `AG-D26` (2026-10-09).** The declared `interval` becomes the fallback: when the DP sets `telemetry_pull_interval` on `SessionAccept.limits`, every binding is pulled at that instead, and the scrape timeout is clamped to it. Nothing else in this decision changes.

## `AG-D23` — One logical stream per binding; one batch in flight per stream

`stream_id` is the binding id, and each stream has at most one batch in flight. `TelemetryReject` carries no `batch_seq`, so serializing each stream is the only way a reject names its batch (recorded as `AG-15`). Sender selection takes the oldest `produced_at` across streams that have something to send, nothing in flight, resolvable ids and are past their retry time, so more streams than credits never starves one. A batch whose ids stay unresolved for two report intervals of an open stream is dropped as a gap, so a container restarted before its new incarnation was reported does not stall its stream until the age cap. **Rejected.** *Pipelining several batches per stream* — unattributable rejects.

## `AG-D24` — The disk buffer: per-batch files, global caps, drop-oldest, marked gaps

This is `AG-D9` made concrete. The buffer holds one file per batch under `<data-dir>/telemetry/<binding>/`, written through `store.Save`, plus a `state.json` carrying `acked_seq` and pending gap markers. Two global caps (`--telemetry-buffer-bytes`, `--telemetry-buffer-age`) apply on every append and on a periodic sweep; the batch with the oldest `produced_at` across all streams is dropped first, and an in-flight batch is never dropped, because a gap marker must not claim a batch the DP may already have committed. Every drop appends a gap `{from_seq, to_seq, reason}` and is counted by reason. Gap reasons: `DROPPED_OLDEST`, `EXPIRED`, `OVERSIZE`, `UNREGISTERED` (ids still unknown after two report intervals of an open stream), `CORRUPT` (a batch file unreadable at load **or** at runtime), or the gateway's reject code where retrying the same bytes cannot succeed. The oversize check compares the stored batch's JSON size to `max_batch_bytes`; the telemetry packages cannot import the generated protobuf, and JSON is larger than the protobuf encoding, so the check is conservative. Stream ids reaching `Trim`/`Append` must be safe path components, which blocks path traversal from a gateway-supplied `stream_id`. On trim, `state.json` is written before the files are deleted, and a failed state write never lets a seq be reused. **Rejected.** *Segment files* — a later optimization, not a v1 need.

## `AG-D25` — A telemetry write failure is counted, not fatal

A `store.Save` failure in the telemetry buffer drops the batch, increments `sm_agent_telemetry_buffer_write_errors_total`, logs at `WARN` and the agent keeps running; load-time failures are reported the same way and never stop the agent starting. This deliberately differs from the control path, where a failed `Persist` is fatal: configuration change fails closed, while telemetry is best-effort with marked gaps (`B/D2`). Slice 1's `Persist` behaviour is unchanged.

## `AG-D26` — The DP sets the telemetry pull and push intervals per agent

The Data Plane sets two durations per agent, `telemetry_pull_interval` and `telemetry_push_interval`, on `SessionAccept.limits` beside `report_interval`. **Pull** replaces every binding's declared `interval` (`AG-D22`) when it is at least 1 s, and the scrape timeout becomes min(declared timeout, pull interval). **Push** holds stored batches on disk until the next push tick, then sends every batch produced at or before that tick, oldest first, as individual batches under the existing credits and the one-in-flight rule (`AG-D23`). It must be shorter than `--telemetry-buffer-age`. `0` or absent means the local default: the declared interval, and sending each batch as soon as it is written, which is slice 2's behaviour. A value outside those bounds is refused, logged at `WARN` and counted in `sm_agent_telemetry_limit_ignored_total{limit}`, and the default applies. Values are recomputed on every new session and only then: a session drop keeps them, a reconnect that repeats them resets no ticker, and a changed value reaches a connected agent at its next session. Deletion on ack and both buffer caps are unchanged (`AG-D24`).

**Rationale.** A predictable send schedule, set by the person operating the Data Plane (`REQ-013`). `SessionAccept.limits` already carries the DP-set `report_interval`, so two more fields follow an existing pattern instead of adding a message. **Rejected.** *Merging the stored batches into one larger batch at each push* — fewer messages was not the goal, and it would rework `AG-D23`/`AG-D24`'s seq, in-flight and gap guarantees. *Per-binding intervals from the DP* — that is delivering declarations, `AG-14`. *A mid-session update message* — a new message type and its ordering rules, kept as the later option in `AG-23`. *Local flags* — the DP is the source.

⚠️ **Neither field is in gateway §4.3.** The DRAFT `.proto` carries them as an agent-side ask, open item `AG-23`; a gateway that sends neither leaves slice 2's behaviour. Spec: [`../../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md); plan: [`../../plans/go-agent/007-telemetry-cadence.md`](../../plans/go-agent/007-telemetry-cadence.md).

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-09 | `AG-D26`, `PROPOSED`, from plan `docs/plans/go-agent/007-telemetry-cadence.md`: the DP sets the telemetry pull and push intervals per agent on `SessionAccept.limits`. Amends `AG-D22`, recorded in both entries. |
| 2026-10-02 | `AG-D21` to `AG-D25`, all `PROPOSED`, from plan `docs/plans/go-agent/006-telemetry-slice.md`: the separate telemetry pipeline and its read-only view, the interim declarations file, per-binding streams with one batch in flight, the disk buffer and its gap reasons, and non-fatal telemetry write failures. |
| 2026-09-22 | `AG-D13`–`AG-D20` from `docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md`: DRAFT proto, reactor architecture, clean rewrite, fake gateway, the answers to `AGW-19` and `AGW-20`, lifecycle outcomes and Docker binding resolution. |
| 2026-08-18 | **Family created (`AG-D1`…`AG-D12`), all `PROPOSED`.** Raised by an audit finding that [`design.md`](design.md) had been written to an earlier standard than its siblings — no citations, no per-choice rationale, no flagged conflicts and **no decisions file**, though `AG-D*` is reserved for this component in [`../README.md`](../README.md). Four of the twelve correct first-draft statements rather than merely record them: `AG-D3` retracts agent-initiated restart (contradicted `SM-D15`), `AG-D4` + `AG-D5` supply `LOCKED` `B/D16`'s crash-safety guarantees the draft had replaced with the `dp-remote` degradation, `AG-D6` retracts the claim that `client-go` election guarantees a single leader, and `AG-D10` corrects an `I2`/`I5` miscitation. `AG-D2`, `AG-D7`, `AG-D8`, `AG-D11` replace named-library-without-rationale entries, each with a WebFetch-verified citation per `I2`. See [`design.md`](design.md) §14. |
