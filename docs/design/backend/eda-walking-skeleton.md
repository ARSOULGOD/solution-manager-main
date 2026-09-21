# EDA walking skeleton — apply protocol, live-console contract, minimal authorization

**Status: PROPOSED, UNBASELINED.** Decisions arising here are additions to the `BE-D*` family in
[`decisions.md`](decisions.md) — all `PROPOSED`, resting on the unratified `SM-D*` base (`I4`).
Requirements: [`REQ-006`](../../requirements/REQ-006-rbac.md) (RBAC — ACCEPTED, must, v1 — see the
flagged v1 scope shortfall in §5.1), [`REQ-012`](../../requirements/REQ-012-event-driven-live-console.md)
(event-driven live console). **Revised 2026-09-04 after independent adversarial review** (32 findings
triaged; corrections applied in place under the family's draft amendment policy and itemized in
[`decisions.md`](decisions.md)'s changelog).

**What this document is.** The walking skeleton was previously blocked by three undesigned areas —
the `dp-remote` apply protocol (open item 1), the RBAC/identity substrate (open item 24 / gate G2),
and the browser-mesh security contract's remaining shape questions (open items 32/33). This document
designs all three **as one coherent set**, so the skeleton can be planned as **event-driven from day
one**: the browser joins the mesh through the already-designed `BE-D5`/`BE-D7` path, REST remains the
authoritative snapshot/backfill (`BE-D1` — never a temporary stand-in to be rewritten later), and
polling exists only as the degraded mode `BE-D6` already assigns it.

**What this document does NOT do.** It does not amend `D10` 🔒, `D13` 🔒, `B/D12` 🔒, `B/D13` 🔒,
`B/D16` 🔒 or `SM-D15`; ⚠️ **it did not close open items 24, 32 or 33** — ✅ **24 is now CLOSED (2026-09-16, the ratified `D10` amendment)** and **32/33 are the project owner's** (ruling OR-1 — ⚠️ **not "the architect group's", as this line said**) —
this document gives each a concrete, costed proposal to approve or reject); it does not reopen
anything plans 001–003 built. Direction set by the project owner, 2026-08-31; encoded here under `I4`
discipline: proposals, flagged conflicts, no silent overrides.

---

## 1. The blocker set, collapsed

Everything the EDA walking skeleton needs and does not have reduces to **three decisions**:

| # | Blocker | Open item(s) | Designed here as | What remains — ⚠️ **for the PROJECT OWNER** (ruling OR-1; this column was headed *"the architect group"*) |
|---|---|---|---|---|
| 1 | The `dp-remote` apply protocol has no design — clause B of `FE-D18` is blocked on it, and v1's first slice would otherwise ship non-idempotent remote apply | 1 | `BE-D12` (protocol + adapter seam) + `BE-D13` (persistence, scale, retention) | ratify with the `SM-D*` chain; nothing new to decide beyond the design itself |
| 2 | RBAC/identity is unbuildable: 2 grantable verbs, no bootstrap, no identity store, no audit log | ~~24 (G2)~~, ~~absorbing 23~~ ✅ **both CLOSED 2026-09-16** | `BE-D15` (enforcement machinery, local substrate, bootstrap, audit) — the *mechanical* half; the verb catalog is now the ratified [`D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md)'s **38**, superseding [`D10-AMENDMENT-PROPOSAL.md`](../D10-AMENDMENT-PROPOSAL.md)'s draft | ✅ **Nothing — RATIFIED and `LOCKED` 2026-09-16** by the project owner, who is the final architecture authority. The catalog, bootstrap option, identity substrate and audit log are all approved. ✅ **No residue — ruling [OR-16](../DECISIONS.md) closes the last one:** the grant hierarchy is **five** levels ending at Instance, `solution_id` is added to the scope schema, and Placement is operational rather than grantable, so `instance.stop` is fully authorizable at both its approved targets (see §5.4) |
| 3 | The browser-mesh ACL has two shape gaps the G1 spike priced but could not decide | 32, 33 | `BE-D14` (ticket/session contract, `LiveStateClient` seam, tenancy scoping for v1) | ⛔ **SUPERSEDED 2026-09-16 (OR-5/OR-6)** — ⚠️ **for posture (33) only; (32) is re-framed, not withdrawn (see §4.4).** Organizations get **one Message VPN each** (on Solace Cloud, one broker service each), and **tenant-wide notify visibility is NOT acceptable** — sub-organization and environment isolation is enforced through topics and ACLs. ⚠️ What remains is **measurement**: the §11.3 spike in [`../common/contracts/console-integration.md`](../common/contracts/console-integration.md) is written and **not run**. Historical: *rule on the two proposed postures — v1 single-tenant (defers 32) and tenant-wide notify visibility (the spike's own recommendation for 33)* |

Everything else the skeleton needs already exists and is not re-decided: the event contract and its
locks (plans 002/003 — §3.5 reconciles this design's step vocabulary with it rather than re-deciding
it), the publish path (`BE-D1`, plan 003), the module layout (`BE-D3`), the ticket mechanism core
(`BE-D5`, measured by G1), the hybrid payload model (`BE-D6`), the browser transport (`BE-D7`), the
authn adapter shape (`BE-D8`).

---

## 2. `BE-D12` — the generic apply protocol: six runtime-neutral operations behind an adapter seam

### 2.1 The shape

The `dp-remote` apply path is expressed as **six operations on a `RuntimeAdapter` interface**. The
core apply engine — the state machine, the persistence of §3, the generation and idempotency rules,
and **all sequencing** — speaks only these six; everything runtime-specific lives behind the seam.
The lifecycle and observation operations are **replica-scoped**, because the strategies `SM-D15`
offers are per-replica by construction (`rolling-*`), `architecture.md` §9 sequences `dp-remote`
applies *per host*, and `AG-D3`'s division of labour assigns multi-replica ordering and health
gating to the DP — an instance-scoped verb cannot say "restart replica 2, wait, then replica 3":

⚠️ **The block below is superseded as the seam's literal signature by `BE-D21`** (callout after
the paragraph that follows it, accepted block there). Its six operation names, their scoping and
the semantics table below stand unchanged; the parameter types do not. It is left as written per
`I3` — a decision's text is flagged, never rewritten in place.

```java
public interface RuntimeAdapter {
    PreflightResult prepare(PlacementRef placement, long generation);
    MaterializeResult materializeConfig(ReplicaRef replica, long generation);
    LifecycleResult startOrUpdate(ReplicaRef replica, long generation);
    LifecycleResult stop(ReplicaRef replica);
    RuntimeStatus status(ReplicaRef replica);
    ResolutionResult rollbackOrMarkIndeterminate(PlacementRef placement, long generation);
}
```

`ReplicaRef` carries its `PlacementRef` (which carries instance and target identity); adapters never
see bare strings. **Health gating is the core's job, expressed through `status`**: between lifecycle
steps the engine polls `status(replica)` under a bounded deadline and advances, halts, or rolls back
on the result — there is deliberately no separate `healthGate` operation, because a gate is a *policy
over observations* (the core's) rather than a runtime action (an adapter's).

⚠️ **Amendment `BE-D21` (2026-09-11, design-first, before the seam is built).** The Java block above
is **superseded as the seam's literal signature**; its six operation *names*, their arity of concerns
and every semantic in the table below stand unchanged. Plan 005's design review found two places
where the printed block cannot be implemented as written, both discovered by trying to write the
first adapter against it:

1. **`ReplicaRef` cannot be widened to carry its `PlacementRef`.** The paragraph above states the
   requirement — *adapters never see bare strings* — and the block expresses it by assuming
   `ReplicaRef` is a composite. As built (plan 004), `ReplicaRef` is `record ReplicaRef(String value)`:
   a single string whose content grammar `BE-D19` mirrors into `resolution_is_evidence`, serialized as
   the `ref` key that `BE-D18`'s per-element **key-set** predicate closes over (`{ref, restarted,
   observedHash}` for `replicas`, `{ref, restarted}` for `restartsObserved`). Adding a component
   would change an evidence wire shape the database now holds rows to, in a table whose CHECK was
   re-recorded through the DDL-replay harness twice. The requirement is met instead by a **seam-side
   pair**, leaving the persisted vocabulary untouched.
2. **The rendered bytes need an owner, and the printed signature gives them none.** `materializeConfig`
   is specified as persisting *"generation `G`'s **rendered bytes**"*, and §3.5 places rendering
   before apply (the ladder's `rendered` rung, announced by the DP pre-apply). With no parameter for
   them, an adapter would have to render its own — a second renderer, per runtime, with no goldens
   watching it, and free to disagree with the `config_hash` the intent row bound before it was called.

The accepted seam signature is therefore:

```java
public interface RuntimeAdapter {
    PreflightResult prepare(PlacementRef placement, long generation);
    MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config);
    LifecycleResult startOrUpdate(TargetReplica replica, long generation);
    LifecycleResult stop(TargetReplica replica);
    RuntimeStatus status(TargetReplica replica);
    ResolutionResult rollbackOrMarkIndeterminate(PlacementRef placement, long generation);
}
```

with `record TargetReplica(PlacementRef placement, ReplicaRef ref)` and
`record RenderedConfig(byte[] bytes, String hash)`, the latter self-checking — its compact constructor
recomputes the hash and refuses a pair that does not match, so the engine and the adapter cannot
disagree about what was written. `PlacementRef` is `ScopeKey` minus the instance-wide sentinel, which
it refuses by construction: an instance-wide request is fanned out into its placements before the seam
is reached, exactly as §3.5 already fans its events out.

**This is a new decision, not an edit** (`I3`): `BE-D12`'s own text is flagged, not rewritten, and the
block above is left as the record of what was proposed. See
[`decisions.md`](decisions.md) `BE-D21` for the full rationale, the rejected alternatives, and the
implementation posture.

⚠️ **Amendment `BE-D25` (2026-09-14, design-first): the seam observes its replicas,
generation-free — an eighth operation.** `prepare(placement, generation)` is the only enumerator
above and it is generation-bearing on purpose (`SM-D21`'s round-2 correction: the launch command,
the mount and the competing-source set are re-read *for a specific apply*). Every operation that
needs the replica set and has no generation is therefore locked out, and §3.1's
`apply_has_generation` makes that concrete rather than hypothetical: a `stop` carries
`generation IS NULL`, so **it cannot learn which replicas to address**. The seam adds
`List<TargetReplica> replicas(PlacementRef)` — an *observation* beside `status`: read-only, no
generation, and no claim about fitness to apply, which stays in `prepare`. `prepare` is redefined
as "`replicas` plus the per-apply validation", and the adapter contract kit pins the two together.
**This is a new decision, not an edit** (`I3`). [`decisions.md`](decisions.md) `BE-D25`.

⚠️ **Amendment `BE-D22` (2026-09-11, design-first, before the seam is built): the sixth operation is
two operations, and §3.1's step enum already said so.** `rollbackOrMarkIndeterminate` both *reads*
(assess the failure point) and *writes* (restore the prior config) behind one name, while §3.1's
`step_known` enum carries **two** labels for the two halves — `ROLLBACK_ASSESS` for *"the hashless
assessment that restored nothing"* and `ROLLBACK` for the restorative one, which
`hash_on_config_steps` requires to carry a `config_hash` because *"a restorative rollback knows its
target generation before acting"*. An engine holding one operation cannot honour that: it must open
an intent **before** invoking the adapter (§2.2 rule 0), and at that moment it does not yet know
whether the adapter will restore, nor the hash it would restore to. Every available option was a
defect — journal `ROLLBACK_ASSESS` and then restore (breaking §3.1's own step-label-truthfulness
invariant, *"a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`"*), or open `ROLLBACK` after the
destructive act (breaking rule 0 and leaving a crash unable to tell whether the restore happened).
The persistence model was right and the seam was wrong. The seam therefore becomes **seven
operations**:

```java
    RollbackAssessment assessRollback(PlacementRef placement, long generation);  // read-only
    RestoreResult      restore(PlacementRef placement, long generation, String targetHash);
```

replacing `rollbackOrMarkIndeterminate`. `assessRollback` is non-mutating by contract — the
obligation this section's own residual table assigns to *"adapter contract tests, per adapter"* — and
journals `ROLLBACK_ASSESS`, hashless and placement-scoped exactly as `placement_scoped_steps` and
`hash_on_config_steps` require. It returns `Restorable(targetHash)`, `NothingToRestore()` or
`Unknowable(errorClass)`.

**It classifies against journal-known values only.** `intendedHash` is the `config_hash` the
`MATERIALIZE` intent already bound, handed in by the engine; the rollback target is read back from the
retained pre-image. Exactly those two values are recognized. A partial materialize leaves replica A on
the new generation and replica B on the old — they differ, and that mixture is precisely what a
rollback is *for*, so it is `Restorable`, **not** divergence. A replica that is unreadable, or holds a
**third** value, is `Unknowable`: the apply reaches `APPLY_INDETERMINATE` and nothing is overwritten.
`CONFIG_DIVERGED` is never reached merely because the expected old and new states coexist after a
materialize failure — divergence is a *lifecycle* verdict (`SM-D15`: "some restarted onto `G`, some
still run the prior generation") that the engine takes from its own journal.

Only on `Restorable` does the engine open a **`ROLLBACK`
intent carrying `targetHash` as its `config_hash`** and then call `restore`, so the target is durable
*before* the destructive action and a crash reads it back rather than guessing. **`restore` re-hashes
the retained pre-image and compares it to `targetHash` before it writes anything**; a pre-image that
has vanished or changed since the assessment means the world moved, so the adapter writes nothing and
reports a bounded classifier that reaches `APPLY_INDETERMINATE`. **Resume re-issues a `ROLLBACK` only
when every replica reads one of the two row-held values** — the intent's `config_hash` or that
replica's `MATERIALIZE` hash; anything else is `APPLY_INDETERMINATE`, because a human who wrote to the
file while the DP was down must not be silently reverted (`B/D12` 🔒). After a restore the
engine issues one **`VERIFY`** step per replica against the same hash, which is where the per-replica
restored evidence becomes durable — the assessment and restore results are transient values the
engine branches on, never the record.

**One bounded case is out of this slice and is stated rather than left open:** where the prior state
was the *absence* of a file, the restore target has no hash, so `hash_on_config_steps` makes it
unjournallable as `ROLLBACK` and rule 0 forbids acting without the entry. The assessment reports
`Unknowable("rollback.target.absent")` and the request reaches `APPLY_INDETERMINATE` for operator
confirmation — a defined, tested outcome, not an unhandled path. Admitting a hashless restorative
rollback, or recording absence as its own journalled fact, is a persistence change and belongs to a
later plan.

⚠️ **Amendment `BE-D23` (2026-09-11, design-first): `PlacementRef`'s "fanned out into its placements"
clause above is aspirational, not v1.** §3.1's journal identity cannot express per-placement steps for
one instance-wide request; the v1 engine is single-placement and refuses an instance-wide request
before admission. See §3.1's callout below and [`decisions.md`](decisions.md) `BE-D23`.

⚠️ **Amendment `BE-D24` (2026-09-14, design-first): a `ConfigLocation` names the adapter's writer
access mode, not invariably a DP-local filesystem path.** Local Docker uses `LocalFilesystem`; an SSH
placement uses `RemoteSftp` with a canonical remote directory. The generic core never opens either
path. See [`decisions.md`](decisions.md) `BE-D24`; the original `BE-D21` field spelling is not edited
in place (`I3`).

| Operation | Semantics (runtime-neutral) | Binding rules it must honour |
|---|---|---|
| `prepare` | Per-placement pre-flight, run **before every apply**, never once at enrollment: resolve the declared config path and mechanism, verify reachability and permissions, verify writer ownership, detect competing sources, and capture the per-replica set the apply will address. Returns a plan or a refusal — it never mutates the target | `SM-D21` (path is per-artifact, per-runtime, discovered by pre-flight) · `SM-D25` (on Kubernetes, refuse to write any resource SM is not the declared writer of) |
| `materializeConfig` | Persist generation `G`'s **rendered bytes** durably at the declared location for **one replica**. **Zero lifecycle effect** — writing configuration never restarts anything. Stage-everywhere = the core calling this for every replica before any lifecycle step | `SM-D3` mechanism (additional-location / overwrite-in-place) · temp → fsync → rename file semantics · `SM-D25` on K8s: a **new generation-named immutable ConfigMap**, never a patch (one per generation, not per replica) · `SM-D15`/`AG-D3`: config write ≠ restart, ever |
| `startOrUpdate` | Make **one replica** reflect the materialized generation, by executing the **declared lifecycle verb** (`SM-D19`). Invoked **only** under an operator-selected restart strategy — under the default `manual` strategy the core never calls it: the operator restarts through their own process and SM observes `restart-observed` | `SM-D15` (strategy ladder; **stage-everywhere-then-restart** ordering: every `materializeConfig` completes before the first `startOrUpdate`; outage confirmation before full-outage restarts — `architecture.md` §9) · **artifact immutability**: never pulls, replaces or re-tags an artifact — deployment is the customer's CI/CD (`SM-D2`, `I1`) |
| `stop` | Execute the declared stop verb on **one replica**, on explicit operator command. Never invoked by the core on its own initiative. Recorded and audited like any apply-family operation (§3.1's `operation = stop`) | `SM-D19` lifecycle verbs; the supervisor (`Restart=`, restart policy, kubelet) is never raced |
| `status` | Observe **one replica**: liveness, health, persisted-config **hash read-back** at the declared path, and the process/container **start baseline** (start time / epoch) that `restart-observed` detection compares against. Read-only, safe to call at any time; the engine's health gate and §2.2's resume both consume it | `SM-D22` (verification is bounded; drift compare = persisted file vs rendered content, alert-only — never silently auto-revert a human, `B/D12` 🔒) |
| `rollbackOrMarkIndeterminate` | The bounded-retry exhaustion path, placement-scoped. Where §3's journal proves the failure point, restore the prior persisted config on the affected replicas (`FAILED_ROLLED_BACK`) or report an untouched target (`FAILED_NO_EFFECT`); where a rolling strategy halted between replicas, terminate `CONFIG_DIVERGED` (§3.2) with per-replica evidence. Where the journal cannot prove the failure point, mark the apply **`APPLY_INDETERMINATE`** and stop — resolution is an explicit operator confirmation from observed state (§3.4), never a guess | `B/D12` 🔒 (config-only rollback is deterministic; last ~10 generations retained) · `FE-D18` clause B's operator-confirmation flavour of `APPLY_INDETERMINATE` — the `dp-remote` degradation `AG-D5` cross-references |

### 2.2 Safety properties — four of `B/D16`'s five, DP-persisted; the fifth named out of scope

`B/D16` 🔒 gives the `host-local` path crash-safe idempotent apply via an agent-local journal. Open
item 1 exists because `dp-remote` has no equivalent. The equivalent is **four of those properties
with the journal moved into PostgreSQL** (§3's `apply_attempt` — the DP survives its own restarts by
reading its own database, as the agent replays its journal per `AG-D4`; the DP-side steps are not the
agent's four, and §3.5 owns that mapping explicitly):

0. **Write before act** — `architecture.md` §16.1's own ordering rule, verbatim obligation: *apply
   state is persisted in the DP before any remote action*. Concretely: the intent row, and the
   attempt row for the try about to run (carrying `pre_baseline` where the step needs one), are
   **committed before the adapter is invoked**. This is what makes resume's evidence readable as
   history rather than guesswork: an intent with **no attempt row provably issued nothing**.
1. **Generation monotonicity — enforced at admission, with maintained artifacts, across BOTH scope
   shapes.** The §3.3 admission transaction holds the scope's `apply_scope` row (`SELECT … FOR
   UPDATE`) and compares against the **maintained watermarks** (§3.1): a **placement-scoped** apply
   at generation `G` is refused when `G ≤ GREATEST(instance_watermark,
   coalesce(placement_watermark[p], 0))` — NULL-explicit on purpose: a placement with no watermark
   row contributes 0, never SQL NULL into a comparison; an **instance-wide** apply is refused when
   `G ≤ GREATEST(instance_watermark, coalesce(max(placement_watermark[*]), 0))`
   — the cross-scope maxima are the point: an instance-wide `APPLIED` at 10 must fence a later
   placement-scoped 9, and vice versa, which a per-key index lookup cannot see across the sentinel
   boundary. The same transaction that lands `STAGED`/`APPLIED`/resolved-`'applied'` (the last one narrowed by `BE-D17`, §3.4, to `operation = 'apply'` resolutions only) updates the
   corresponding watermark — **and holds the scope's `apply_scope` row lock while it does**, the same
   lock admission holds, so landing and admission serialize by design rather than by accident
   (measured, plan 004 review: the placement-watermark upsert's `DO UPDATE` branch takes no lock on
   the scope row, so an FK-derived serialization would have held only for a placement's *first*
   landing). Obligation (n) proves all four directions, including immediately after an
   `INDETERMINATE_RESOLVED`.
2. **Idempotency keys, durably bound — and the executable form, measured.** Each logical
   (apply, step, replica) has exactly one **`apply_step_intent` row** (§3.1) holding the DP-assigned
   key and the intended hash. The step transaction runs at **`READ COMMITTED`**: `SELECT` the intent;
   absent → plain `INSERT`; present → compare key and hash. **The composite UNIQUE is the arbiter, not
   a lock**: a concurrent same-step create loses with `23505`, the engine retries the `SELECT` in a
   fresh transaction and compares — so the race resolves to exactly one durable intent either way. A
   differing key or hash for the same logical step, or a key reused across steps, is the protocol
   error. **Classification is by the outcome of that re-read, not by the constraint name alone —
   measured (plan 004 prototype, 2026-09-07):** a legitimate concurrent same-step create presents
   the *same* key, so it violates **both** UNIQUEs, and PostgreSQL reports whichever index it
   reaches first — `apply_step_intent_idempotency_key_key`, the inline column constraint, not the
   composite. Reading the name alone would turn every honest race into a protocol error. So *either*
   `23505` on the two intent UNIQUEs sends the loser into a fresh transaction to re-read the logical
   `(apply, step, replica)`: a row found → compare key and hash (the race); no row found → the key
   belongs to another step (the protocol error). Deliberately **not** `ON CONFLICT … DO NOTHING`:
   the execution review measured that a conflict on the `idempotency_key` index aborts the
   transaction *before* the read-compare can run — which is also why the re-read needs its own
   transaction.
   The binding survives crashes because it is a row, not engine memory. On hashless steps the key
   alone identifies the intent. Adapters must make their operations safe to re-execute under the
   same key (a rename that already happened, a ConfigMap that already exists, a replica already at
   the desired state — all report success, not failure).
3. **Deterministic resume where the journal can decide; honest `APPLY_INDETERMINATE` where it
   cannot.** On DP restart, in-flight applies resume per intent:
   - **intent with no attempt row** → rule 0 says nothing was issued: **re-run deterministically**
     (a fresh attempt under the same key) — never indeterminate;
   - **`MATERIALIZE`**, open attempt → hash read-back: nothing at the path → rerun; hash matches →
     continue; **bytes present with an unexpected hash** → a competing writer or partial write —
     never silently overwritten (`B/D12` 🔒: *never silently auto-revert a human*): the attempt
     fails with the observed hash as evidence and routes to `rollbackOrMarkIndeterminate`. (The DP
     does not mirror `AG-D4`'s `STAGED` step; an orphaned temp is swept by rule 4 — deliberate,
     stated differences.)
   - **`LIFECYCLE`**, open attempt → compare `status(replica)`'s start baseline against the
     attempt's recorded `pre_baseline`: restart observed → continue; baseline unchanged → re-issue
     under the same key; **baseline missing or unreadable → `APPLY_INDETERMINATE`**, because
     re-issuing a restart the operator may not have authorized twice is exactly the guess this state
     exists to refuse. That is the honest `dp-remote` shape: deterministic where evidence decides,
     indeterminate-with-operator-confirmation where it does not (`FE-D18` clause B; `AG-D5`'s
     cross-reference).
4. **Bounded retry, explicit cleanup.** Per-step retry counts and deadlines are bounded and recorded;
   exhaustion routes to `rollbackOrMarkIndeterminate`; superseded partials are cleaned explicitly and
   the cleanup is recorded — for Kubernetes under `SM-D25`'s full GC rule: retain the last N
   generation-named ConfigMaps for rollback, delete oldest-first, and **never delete one still
   referenced by a live or paused workload**.

**What is deliberately NOT mirrored (flagged, not hidden):** `B/D16`'s fifth clause — the
lease-bounded authority that stops a partitioned agent acting on cached state — has no `dp-remote`
twin here. On-prem v1 co-locates CP and DP in one deployable (`BE-D3`), so the partition this clause
defends against does not exist between them; the moment a split topology does exist, the DP needs an
equivalent authority bound. Routed as §8 row 7.

### 2.3 Adapters

**`DockerAdapter` first** (v1 walking skeleton, Cache slice). `SystemdAdapter` and
`KubernetesAdapter` later — but the seam is proven generic **at design time, now**, by walking all
three through the six operations:

| Operation | Docker | systemd (later) | Kubernetes (later) |
|---|---|---|---|
| `prepare` | inspect containers, verify declared mount/path visible, verify Docker API reachability, enumerate replicas | unit(s) exist, config dir declared and writable | workload exists, SM is declared writer of the ConfigMap, RBAC verbs available |
| `materializeConfig` | write file under the replica's bind-mounted/volume path (temp→fsync→rename) | same, real host directory per host | create the generation-named immutable ConfigMap (once per generation; per-replica calls after the first verify-and-succeed under the idempotency rule) |
| `startOrUpdate` | declared verb: container restart (same image — never a pull), one replica | `systemctl restart <unit>`, one host | patch the workload's ConfigMap **reference** — see the `B/D13` disposition below |
| `stop` | container stop | `systemctl stop` | scale/suspend per declared verb |
| `status` | container state + start epoch + file hash read-back | unit state + start time + file hash | pod readiness + pod start times + mounted-generation observation |
| `rollbackOrMarkIndeterminate` | restore prior file from retained copy per replica; lifecycle only where the failed apply had already restarted that replica | same | re-point the reference at the prior ConfigMap |

**`B/D13` 🔒 disposition for Kubernetes (flagged in [`decisions.md`](decisions.md)):** `B/D13` says
sequencing is uniformly `start → health-gate → next` and native primitives are *optional accelerants,
never the source of truth*, with a generation health-gated **as a unit**. Under `SM-D25` the
reference-update necessarily hands pod-by-pod sequencing to the platform's rollout — that is the
accelerant. What this design keeps with SM, per `B/D13`: the engine health-gates **the generation as
a unit** through `status` polling under a bounded deadline, and on gate failure terminates the apply
(`FAILED_ROLLED_BACK` by re-pointing the reference, or `CONFIG_DIVERGED` with evidence) rather than
trusting the platform's own success signal. The platform sequences pods; SM still owns the verdict.
On Docker and systemd — the v1 path — SM sequences replicas itself, exactly `start → health-gate →
next`.

Runtime-specific identifiers (container id, unit name, namespace/workload ref) live in **adapter-
scoped reference fields** (§3's `runtime_ref`), never as columns the core model or API depends on.
The core persistence and API model **instance / target / placement / runtime-kind / generation /
apply status** generically; a new runtime is a new adapter plus a new `runtime_ref` shape, zero core
migrations.

**Enforcement, not intention:** the plan that implements this must add an ArchUnit rule in the
existing `arch-tests` idiom — core apply packages must not depend on Docker (or any adapter's
client library) types, with a planted-violation proof, exactly as `onlyMeshbridgeTouchesJcsmp`
confines JCSMP today.

### 2.4 What `BE-D12` deliberately does not change

- **`SM-D15` stands whole**: restart is operator-selected, `manual` by default;
  stage-everywhere-then-restart; strategies the package does not support are never offered; Cache's
  forced-uniform full-outage restart still requires the confirmation dialog (`architecture.md` §9,
  open item 15).
- **The agent path is untouched in mechanism**: `host-local` keeps `AG-D4`'s local journal and
  `AG-D5`'s determinate outcomes. What is new is **reporting**: the DP maps agent apply reports into
  §3's `apply_request` terminal states (the mapping is §3.5's, owned by the DP), so the operator's
  history screen is one table regardless of locus. The agent's journal, steps and wire protocol
  (open item 6) are not changed by this design.
- **No artifact operations, anywhere** (`SM-D2`, `I1`).

---

## 3. `BE-D13` — apply persistence: control history and execution attempts, split on purpose

### 3.1 Three tables, three jobs: decided, intended, attempted

`apply_id` is durable in PostgreSQL (`sm_data`), and the model splits three things with three
lifetimes: **what an operator decided** (`apply_request`), **what the engine committed to do about
it** (`apply_step_intent` — the immutable identity substrate retries are checked against), and
**what each try actually did** (`apply_attempt`, the retry log). `apply_request` is also,
deliberately, **the DP's audit record**
for apply-family verbs (`config.apply`, `config.rollback`, `instance.restart`, stop) — see §5.3 for
why the plane boundary makes that the only honest shape.

```sql
-- ================================================================================================
-- PHASE 0 — install bootstrap. Run ONCE, in the SM database, by the CLUSTER ADMINISTRATOR credential:
-- on the pinned PG 14 floor that means the BOOTSTRAP SUPERUSER (or another owner of schema
-- `public`), because the last statement below can be executed by nobody else — see its comment.
-- Every other statement needs only CREATEROLE plus database ownership; the runbook may split them
-- off to a lesser installer, but the REVOKE cannot follow. Ordered: roles, then schema, then
-- schema-level grants — everything Phase 1 assumes. Measured on PG 14: sm_dp_migrator can run none
-- of this, and PG 14's default left CREATE on schema public open to PUBLIC.
-- ================================================================================================
CREATE ROLE sm_dp_owner    NOLOGIN;
CREATE ROLE sm_dp_migrator LOGIN IN ROLE sm_dp_owner;  -- the Flyway datasource (spring.flyway.user)
CREATE ROLE sm_dp_runtime  LOGIN NOINHERIT;            -- the application datasource; NOT an owner
                                                       -- member, so SET ROLE sm_dp_owner is refused
-- LOGIN credentials (passwords / auth method) are install-runbook material (pg_hba, managed
-- secrets) — the sketch deliberately carries none, and TCP connections need them.
GRANT sm_dp_owner TO CURRENT_USER;                     -- measured on PG 14: CREATE SCHEMA
                                                       -- AUTHORIZATION requires the EXECUTOR to be a
                                                       -- member of the target role — a non-superuser
                                                       -- installer is not, until this line (a
                                                       -- superuser bypasses it; harmless for one)
CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner;
GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime;        -- measured: without it, table grants survive
                                                       -- and every runtime query still fails
REVOKE CREATE ON SCHEMA public FROM PUBLIC;            -- ⚠ only the GRANTOR can revoke: on PG 14
                                                       -- schema public is owned by the bootstrap
                                                       -- superuser with ACL `=UC/postgres`, so this
                                                       -- needs THAT superuser — under a database
                                                       -- owner or a CREATEROLE installer alike it is
                                                       -- a SILENT WARNING no-op (both measured; the
                                                       -- runtime then created public.smuggled). PG 15
                                                       -- moved public's ownership to pg_database_owner
                                                       -- and dropped the PUBLIC grant by default
                                                       -- (release notes, verified) — but the floor is
                                                       -- 14. Obligation (s) ASSERTS the outcome:
                                                       -- has_schema_privilege('sm_dp_runtime',
                                                       -- 'public','CREATE') must be false

-- ================================================================================================
-- PHASE 1 — migrations. Run by Flyway as sm_dp_migrator; EVERY migration's first statement is
-- SET ROLE sm_dp_owner (Flyway initSql). The reason, measured precisely: membership already lets
-- the migrator ALTER owner-owned objects — what SET ROLE buys is **ownership at CREATE time**, so
-- new objects are born owned by sm_dp_owner instead of by the migrator. ALTER DEFAULT PRIVILEGES
-- lives here with the object creation it governs (its executor must be the target role or a
-- member — true both for the SET ROLE'd migrator and, after Phase 0's membership grant, for the
-- installer; placement here is cohesion, not necessity).
-- Obligation (s): this exact two-phase script runs green, end to end, from a fresh PG 14 database
-- under exactly these credentials — the migration suite's own bootstrap test, so the sketch can
-- never again drift from what executes.
-- ================================================================================================
SET ROLE sm_dp_owner;

ALTER DEFAULT PRIVILEGES FOR ROLE sm_dp_owner IN SCHEMA sm_data
    GRANT SELECT ON TABLES TO sm_dp_runtime;           -- read-baseline for future tables; WRITE
                                                       -- grants stay per-table and deliberate

-- Three-valued logic is the standing enemy throughout: an absent jsonb key yields SQL NULL,
-- `true = NULL` is NULL, and a NULL CHECK ADMITS (measured on 14.24) — hence coalesce() on every
-- jsonb-derived clause.

-- Control history: one row per operator intent (apply, restart or stop). Audit-grade. Long-lived.
CREATE TABLE sm_data.apply_request (
    apply_id        uuid PRIMARY KEY,          -- UUIDv7: time-ordered, index-friendly
    tenant_id       uuid        NOT NULL,      -- canonical tenant key (architecture §3.1 seam 1; the
                                               -- BE-D4 topic slug is a separate, derived name)
    instance_id     uuid        NOT NULL,
    placement_id    uuid        NOT NULL,      -- nil UUID = the explicit instance-wide sentinel
                                               -- (never SQL NULL: NULL keys defeat the fences).
                                               -- No referential guard exists for it yet — routed,
                                               -- with the placements table, §8 row 13
    operation       text        NOT NULL
        CONSTRAINT op_known CHECK (operation IN ('apply','restart','stop')),
    generation      bigint
        CONSTRAINT generation_positive CHECK (generation IS NULL OR generation > 0),
    strategy        text
        CONSTRAINT strategy_known CHECK (strategy IS NULL OR strategy IN
            ('manual','rolling-health-gated','rolling-no-validation','restart-all','scheduled')),
                                               -- mirrors SM-D15's ladder verbatim (a ladder change is
                                               -- a reviewed migration); whether the PACKAGE supports
                                               -- the value is admission-checked — package-relative,
                                               -- inexpressible statically
    outage_confirmed_by text,
    requested_by    text        NOT NULL,      -- principal id — never a display name
    requested_at    timestamptz NOT NULL,
    state           text        NOT NULL
        CONSTRAINT state_known CHECK (state IN ('REQUESTED','RUNNING','STAGED','APPLIED',
            'FAILED_NO_EFFECT','FAILED_ROLLED_BACK','CONFIG_DIVERGED','SUPERSEDED',
            'APPLY_INDETERMINATE','INDETERMINATE_RESOLVED','STOPPED','STOP_FAILED')),
    terminal_at     timestamptz,
    resolved_by     text,
    resolution_outcome text
        CONSTRAINT resolution_known CHECK (resolution_outcome IN ('applied','not_applied')),
    resolution_observed jsonb,
    resolution_note text,
    attempt_summary jsonb,
    UNIQUE (tenant_id, apply_id),
    -- FK to the scope row (added below apply_scope's creation in the real migration order; shown
    -- here for reading coherence): a request cannot exist without its serialization point, so the
    -- admission discipline is structural, not conventional. The admission transaction's scope-row
    -- upsert precedes the request insert anyway.
    -- FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id)
    CONSTRAINT apply_has_generation   CHECK ((operation = 'apply') = (generation IS NOT NULL)),
    CONSTRAINT lifecycle_has_strategy CHECK ((operation IN ('apply','restart')) = (strategy IS NOT NULL)),
    CONSTRAINT restart_not_manual     CHECK (operation <> 'restart' OR strategy <> 'manual'),
    CONSTRAINT state_fits_operation   CHECK (CASE operation
            WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
            WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
            ELSE                state NOT IN ('STOPPED','STOP_FAILED') END),
    -- BE-D16: STAGED is exclusively the manual strategy's terminal state — an implication, not a
    -- biconditional (a manual apply is REQUESTED/RUNNING before it ever reaches STAGED). Self-defending
    -- (coalesce'd) rather than resting on state_fits_operation's stop/restart branches, whose STAGED
    -- exclusion is now exactly as redundant with THIS CHECK as the other branch always was (design
    -- §3.2, R3 correction) — the stop branch stays load-bearing for every OTHER state it excludes,
    -- not for STAGED.
    CONSTRAINT staged_requires_manual CHECK (state <> 'STAGED' OR coalesce(strategy = 'manual', false)),
    CONSTRAINT terminal_has_timestamp CHECK
            ((state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')) = (terminal_at IS NULL)),
    CONSTRAINT full_outage_confirmed  CHECK
            (strategy IS DISTINCT FROM 'restart-all' OR outage_confirmed_by IS NOT NULL),
    -- Resolution is EVIDENCE: resolution_outcome = 'applied' feeds rule 1's monotonicity and must
    -- never rest on an unsupported claim. Every clause coalesced — '{}' passed the uncoalesced form
    -- via NULL propagation, measured. Shape is OPERATION-SPECIFIC (BE-D17): for 'apply', DB proves
    -- stagedGeneration EQUALS the request's own generation (jsonb-to-jsonb, no cast — a cast can
    -- THROW ahead of its own type guard under undefined AND-order, measured) and replicas is a
    -- NON-EMPTY ARRAY (compared against '[]'::jsonb, never jsonb_array_length, which itself throws
    -- on a non-array operand); for 'restart', DB proves NO stagedGeneration key at all and
    -- restartsObserved is a NON-EMPTY ARRAY, by the same throw-free pattern. Replica/observation-
    -- ELEMENT typing is now ALSO DB-enforced (BE-D18, design §3.1's R6 correction of this comment):
    -- ten further jsonb_path_exists(…, silent => true) predicates below (six for 'apply', four for
    -- 'restart') refuse a malformed element, an absent-but-mandatory 'observedHash' key on an apply
    -- element (its VALUE stays nullable), an unexpected element or top-level key, a malformed hash,
    -- and the lax-mode array-unwrap escape every one of those would otherwise leave open (a
    -- filter applied directly to an array-valued element auto-unwraps under lax mode, so a dedicated
    -- '.type()' predicate — which never unwraps — closes it). The engine's typed resolver (own
    -- refusal tests) stays the construction-time first line; this CHECK is no longer merely
    -- evidence-shaped, it is element-complete.
    CONSTRAINT resolution_is_evidence CHECK ((state = 'INDETERMINATE_RESOLVED') = (
            resolved_by IS NOT NULL
            AND resolution_outcome IS NOT NULL
            AND coalesce(jsonb_typeof(resolution_observed) = 'object', false)
            AND CASE operation
                WHEN 'apply' THEN
                    coalesce(resolution_observed -> 'stagedGeneration' = to_jsonb(generation), false)
                    AND coalesce(jsonb_typeof(resolution_observed -> 'replicas') = 'array', false)
                    AND coalesce(resolution_observed -> 'replicas' <> '[]'::jsonb, false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                            && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"
                            && exists(@.observedHash)))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].keyvalue() ? (@.key != "ref" && @.key != "restarted"
                            && @.key != "observedHash")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].observedHash.type() ? (@ != "null" && @ != "string")',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.replicas[*].observedHash ? (!(@.type() == "null" ||
                            (@.type() == "string" && @ like_regex "^[a-z0-9]+:[0-9a-f]{16,128}$")))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.keyvalue() ? (@.key != "stagedGeneration" && @.key != "replicas")',
                        '{}'::jsonb, true), false)
                WHEN 'restart' THEN
                    NOT coalesce(jsonb_exists(resolution_observed, 'stagedGeneration'), false)
                    AND coalesce(jsonb_typeof(resolution_observed -> 'restartsObserved') = 'array', false)
                    AND coalesce(resolution_observed -> 'restartsObserved' <> '[]'::jsonb, false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                            && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"))',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.restartsObserved[*].keyvalue() ? (@.key != "ref" && @.key != "restarted")',
                        '{}'::jsonb, true), false)
                    AND NOT coalesce(jsonb_path_exists(resolution_observed,
                        '$.keyvalue() ? (@.key != "restartsObserved")', '{}'::jsonb, true), false)
                ELSE false
            END)),
    CONSTRAINT resolution_only_when_resolved CHECK (state = 'INDETERMINATE_RESOLVED'
            OR (resolved_by IS NULL AND resolution_outcome IS NULL
                AND resolution_observed IS NULL AND resolution_note IS NULL))
);

-- The fences, NAMED (refusals surface by index name; the migration tests are written against these):
CREATE UNIQUE INDEX apply_inflight_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') AND operation <> 'stop';
CREATE UNIQUE INDEX stop_inflight_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state IN ('REQUESTED','RUNNING') AND operation = 'stop';
CREATE UNIQUE INDEX staged_fence ON sm_data.apply_request
    (tenant_id, instance_id, placement_id)
    WHERE state = 'STAGED';
    -- at most one STAGED per scope: admission marks the elder SUPERSEDED BEFORE inserting (the one
    -- terminal transition the trigger below permits), and this fence turns forgetting that order
    -- into a unique violation instead of silent accumulation
CREATE INDEX apply_history ON sm_data.apply_request (tenant_id, instance_id, requested_at DESC);

-- The admission SCOPE ROW — the serialization point AND the instance-wide generation watermark.
-- An exact lock row, deliberately NOT pg_advisory_xact_lock: advisory locks are database-wide with
-- hash-typed keys (int4 hashtext collides — measured — and even 64-bit hashing shares one unnamed
-- namespace with every other subsystem); SELECT … FOR UPDATE on this row is collision-free,
-- tenant-scoped by its own key, and visible in pg_locks with its true identity.
CREATE TABLE sm_data.apply_scope (
    tenant_id          uuid   NOT NULL,
    instance_id        uuid   NOT NULL,
    instance_watermark bigint NOT NULL DEFAULT 0
        CONSTRAINT instance_watermark_nonneg CHECK (instance_watermark >= 0),
                                               -- highest generation reaching STAGED/APPLIED/
                                               -- resolved-'applied' through an INSTANCE-WIDE request
    PRIMARY KEY (tenant_id, instance_id)
);

-- Per-placement watermarks, maintained in the same transaction that lands the state they record:
CREATE TABLE sm_data.placement_watermark (
    tenant_id    uuid   NOT NULL,
    instance_id  uuid   NOT NULL,
    placement_id uuid   NOT NULL
        CONSTRAINT real_placements_only CHECK
            (placement_id <> '00000000-0000-0000-0000-000000000000'),
                                               -- instance-wide lives on apply_scope.instance_watermark;
                                               -- the sentinel has no business here
    generation   bigint NOT NULL
        CONSTRAINT placement_watermark_positive CHECK (generation > 0),
    PRIMARY KEY (tenant_id, instance_id, placement_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id)
);

-- Watermarks only RISE — a trigger, because the hazard is row-relative: the runtime's UPDATE right
-- reaches every row, so an unguarded statement could rewind any tenant's fence and re-admit a
-- superseded generation (measured). With this guard, an engine bug touching the wrong row can only
-- OVER-fence (fail-safe direction — an over-raised watermark refuses applies, never re-admits stale
-- ones). The trigger guards the VALUE; the row's IDENTITY is guarded by the column-scoped grant at
-- the end of this phase — the two together close both ways of moving a fence.
-- ONE FUNCTION PER TABLE, deliberately: a shared function branching on TG_TABLE_NAME does not work —
-- PL/pgSQL resolves every field reference in an IF condition against the firing row's type, so
-- `NEW.generation` raises 42703 on apply_scope and `NEW.instance_watermark` on placement_watermark:
-- measured on PG 14, three of the four directions died — every RISE on both tables (so no watermark
-- could ever be raised, any role) and the placement DECREASE, whose 42703 an earlier run had read as
-- the refusal it was asserting; only the apply_scope decrease still fired correctly. Which is
-- exactly why the harness rule below demands the refusal's NAME, not merely an error.
CREATE FUNCTION sm_data.instance_watermark_only_rises() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.instance_watermark < OLD.instance_watermark THEN
        RAISE EXCEPTION 'instance watermark may not decrease (% -> %)',
            OLD.instance_watermark, NEW.instance_watermark;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION sm_data.placement_watermark_only_rises() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.generation < OLD.generation THEN
        RAISE EXCEPTION 'placement watermark may not decrease (% -> %)',
            OLD.generation, NEW.generation;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_scope_monotonic BEFORE UPDATE ON sm_data.apply_scope
    FOR EACH ROW EXECUTE FUNCTION sm_data.instance_watermark_only_rises();
CREATE TRIGGER placement_watermark_monotonic BEFORE UPDATE ON sm_data.placement_watermark
    FOR EACH ROW EXECUTE FUNCTION sm_data.placement_watermark_only_rises();

ALTER TABLE sm_data.apply_request ADD CONSTRAINT request_has_scope
    FOREIGN KEY (tenant_id, instance_id) REFERENCES sm_data.apply_scope (tenant_id, instance_id);
    -- the FK promised above: no request without its lock row

-- Step intents: the IMMUTABLE identity substrate of §2.2 rule 2 — exactly ONE row per logical
-- (apply, step, replica), holding the DP-assigned key and the INTENDED hash. Immutable by the DCL
-- at the end of this phase: the runtime role cannot UPDATE or DELETE here.
CREATE TABLE sm_data.apply_step_intent (
    intent_id       uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    apply_id        uuid        NOT NULL,
    replica_ref     text        NOT NULL,      -- adapter vocabulary; the sentinel '@placement' marks
                                               -- placement-scoped steps
    step            text        NOT NULL
        CONSTRAINT step_known CHECK (step IN ('PREFLIGHT','MATERIALIZE','LIFECYCLE','VERIFY',
                                              'ROLLBACK','ROLLBACK_ASSESS','STOP')),
    idempotency_key text        NOT NULL UNIQUE,
    config_hash     text,
    created_at      timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, apply_id) REFERENCES sm_data.apply_request (tenant_id, apply_id),
    UNIQUE (tenant_id, apply_id, step, replica_ref),
    UNIQUE (tenant_id, apply_id, intent_id),   -- target of apply_attempt's triple FK
    UNIQUE (intent_id, step),                  -- target of apply_attempt's step-consistency FK
    CONSTRAINT hash_on_config_steps CHECK
        ((step IN ('MATERIALIZE','VERIFY','ROLLBACK')) = (config_hash IS NOT NULL)),
    CONSTRAINT placement_scoped_steps CHECK
        ((step IN ('PREFLIGHT','ROLLBACK','ROLLBACK_ASSESS')) = (replica_ref = '@placement'))
);

-- Execution journal: one row per RETRY of one intent. Diagnostic-grade. Prunable.
CREATE TABLE sm_data.apply_attempt (
    attempt_id      uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    apply_id        uuid        NOT NULL,
    intent_id       uuid        NOT NULL,
    step            text        NOT NULL,      -- denormalized from the intent, consistent BY SHAPE
                                               -- via the (intent_id, step) FK — so per-step CHECKs
                                               -- on attempts are expressible
    FOREIGN KEY (tenant_id, apply_id, intent_id)
        REFERENCES sm_data.apply_step_intent (tenant_id, apply_id, intent_id),
    FOREIGN KEY (intent_id, step) REFERENCES sm_data.apply_step_intent (intent_id, step),
    FOREIGN KEY (tenant_id, apply_id) REFERENCES sm_data.apply_request (tenant_id, apply_id),
    attempt_no      smallint    NOT NULL
        CONSTRAINT attempt_no_positive CHECK (attempt_no >= 1),
                                               -- lower bound the DB's; the configured cap is the
                                               -- engine's (obligation (m)); smallint is a deliberate
                                               -- last-ditch backstop at 32767
    pre_baseline    jsonb,
    CONSTRAINT baseline_only_for_lifecycle CHECK (step = 'LIFECYCLE' OR pre_baseline IS NULL),
    started_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    CONSTRAINT attempt_time_ordered CHECK (ended_at IS NULL OR ended_at >= started_at),
    step_deadline   timestamptz NOT NULL,
    outcome         text
        CONSTRAINT outcome_known CHECK (outcome IN ('SUCCEEDED','FAILED','TIMED_OUT')),
    observed_hash   text,                      -- read-back EVIDENCE, never intent
    error_class     text,                      -- classified, greppable; never raw secrets (I6 —
                                               -- engine obligation: classification, not free text)
    runtime_ref     jsonb,
    UNIQUE (intent_id, attempt_no)
);
CREATE INDEX attempt_prune ON sm_data.apply_attempt (tenant_id, started_at);
CREATE UNIQUE INDEX attempt_open_fence ON sm_data.apply_attempt (intent_id)
    WHERE outcome IS NULL;
    -- BE-D20: at most one OPEN attempt per intent, regardless of attempt_no — closes the gap
    -- UNIQUE (intent_id, attempt_no) leaves open (a sequential second open, no numbering collision
    -- at all). A legal retry stays legal once the prior attempt is closed (outcome IS NOT NULL).

-- Terminal states stay terminal by TRIGGER — with exactly ONE legal terminal transition:
-- STAGED → SUPERSEDED, because §3.3's admission algorithm requires marking the elder STAGED row
-- before inserting its replacement (and staged_fence enforces that order). Everything else that is
-- terminal is immutable. Owner-owned like everything else.
CREATE FUNCTION sm_data.forbid_terminal_regression() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'STAGED' AND NEW.state = 'SUPERSEDED' THEN
        RETURN NEW;                            -- sanctioned transition 1: the supersession mark
                                               -- (which also refreshes terminal_at to the
                                               -- supersession time — §3.2)
    END IF;
    IF OLD.state NOT IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE') THEN
        IF NEW.state = OLD.state
           AND to_jsonb(NEW) - 'attempt_summary' = to_jsonb(OLD) - 'attempt_summary' THEN
            RETURN NEW;                        -- sanctioned write 2: §3.3's compaction fold —
                                               -- attempt_summary, and ONLY attempt_summary, may be
                                               -- written on a terminal row (measured: without this
                                               -- branch, compaction was unimplementable while the
                                               -- attempt DELETE half succeeded)
        END IF;
        RAISE EXCEPTION 'apply_request % is terminal (%) and immutable', OLD.apply_id, OLD.state;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER apply_request_terminal_guard BEFORE UPDATE ON sm_data.apply_request
    FOR EACH ROW EXECUTE FUNCTION sm_data.forbid_terminal_regression();

-- Per-table runtime grants (each table's migration carries its own):
GRANT SELECT, INSERT ON sm_data.apply_step_intent TO sm_dp_runtime;  -- IMMUTABLE: no UPDATE/DELETE
GRANT SELECT,
      INSERT (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,
              outage_confirmed_by, requested_by, requested_at, state, terminal_at, resolved_by,
              resolution_outcome, resolution_observed, attempt_summary),
      UPDATE (state, terminal_at, resolved_by, resolution_outcome,
              resolution_observed, attempt_summary)
    ON sm_data.apply_request TO sm_dp_runtime;
    -- COLUMN-scoped UPDATE: identity (tenant_id, instance_id, placement_id, operation, generation,
    -- strategy, requested_by, requested_at, outage_confirmed_by) is not runtime-writable. Measured
    -- hazard this closes: the runtime credential re-tenanting rows (an I5 write surface) and
    -- re-attributing requested_by on terminal rows. resolution_note is DELIBERATELY absent from
    -- BOTH lists — INSERT is column-scoped too, or a row could be born with a note: it is the
    -- schema's one free-text column and v1 writes no free text (I6 — the residual table); the
    -- runtime credential cannot write it at all, so no engine path, present or mistaken, can. A
    -- comments feature adds the column to both grants in its own migration, with its own design.
GRANT SELECT, INSERT, UPDATE, DELETE ON sm_data.apply_attempt TO sm_dp_runtime;  -- close-out + §3.3 pruning
GRANT SELECT, INSERT, UPDATE (instance_watermark) ON sm_data.apply_scope        TO sm_dp_runtime;
GRANT SELECT, INSERT, UPDATE (generation)         ON sm_data.placement_watermark TO sm_dp_runtime;
    -- engine state, not audit: the admission transaction locks and maintains these. UPDATE is
    -- COLUMN-scoped to the watermark value alone — the identity columns (tenant_id, instance_id,
    -- placement_id) are not runtime-writable. Closes the bypass a value-only trigger leaves open:
    -- re-keying a row moves a fence to another scope without lowering any watermark, un-fencing the
    -- original scope. No engine path mutates watermark identity (a scope row is upserted by its own
    -- key and only ever raised); measured on PG 14: the column grant still admits the admission
    -- upsert, `SELECT … FOR UPDATE` (needs UPDATE on at least one column) and the raise, and
    -- refuses every identity UPDATE and DELETE.
```

**One measured consequence of the Phase-1 ordering, stated so it is deliberate rather than
accidental (plan 004 prototype, 2026-09-07):** Flyway creates `sm_data.flyway_schema_history` *before*
the first migration runs, so `V001`'s `ALTER DEFAULT PRIVILEGES … GRANT SELECT ON TABLES` does not
reach it and `sm_dp_runtime` cannot read the migration history (`42501`; `information_schema.tables`
shows the runtime five tables, not six). That is the desired posture — the application has no
business reading migration bookkeeping — and the runbook must not expect otherwise.

The **in-flight state open item 1 demands** is exactly these rows: resume after a DP crash reads the
non-terminal `apply_request` rows, their `apply_step_intent` rows (which step, which key, which
intended hash) and the latest `apply_attempt` per intent — `pre_baseline` makes the `LIFECYCLE` case
decidable, and §2.2 rule 0's write-before-act ordering makes "no attempt row" mean, provably,
"nothing was issued".

**What the database proves, and what stays engine-enforced — each with its artifact or its named
test (L19):** the constraints and fences above make malformed states, generation-less applies,
hash-rule violations in both directions, key reuse, cross-request intent references, terminal-state
regression, identity rewrites and duplicate in-flight/staged scopes unrepresentable; the DCL makes
intent immutability and the schema/ownership topology structural. The engine's own invariants, each
pinned by a named obligation, are:

| Engine invariant | Why no DDL can hold it | Pinned by |
|---|---|---|
| Step-label truthfulness (a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`) | the DB cannot see what the adapter did | adapter contract tests, per adapter |
| `I6` in `resolution_note` — **schema ahead of use, and now DB-enforced** | a `text` column is a free-text sink: the one place an authenticated operator could paste a token or command output during an incident, persisted and then printed by JDBC's TRACE bind-value line the moment TRACE is turned on to diagnose it (plan 004 review, fourth pass). The database CAN enforce "unwritten", and after the fifth pass it does: §3.1 grants `sm_dp_runtime` **neither INSERT nor UPDATE on the column** (both grants are column-scoped), so no engine path — present, future or mistaken — can write it; the Java side (no note component on `Resolution`, the resolving statement omits the column) is the mirror, not the guard. The column and its place in the DDL stay so a comments feature can land without a table change, but that feature owes its own design (sensitive-content handling, retention, access control, logging) and adds the column to both grants in its own migration. Every jsonb evidence bind is redacted from the JDBC bind-value log structurally (a `SqlValue` rendering as a length), not by logger level | `42501` on a legal resolution UPDATE that also names the column, and on an INSERT that names it (even as NULL), each with an owner positive control — evidence run legs `(j/I6)`, plan 004 `PrivilegeTopologyIT`; the catalog leg shows `SELECT` as the runtime's only privilege on the column |
| `SUPERSEDED` marking order at admission | ordering inside one transaction | obligation (p) |
| Generation monotonicity (§2.2 rule 1) | cross-row comparison under concurrency | admission query holding the `apply_scope` row lock, against the maintained watermark tables; obligation (n) |
| Per-element typing in `resolution_observed` — **now DB-enforced by `BE-D18`** (§3.4 amendment); both key-exactness levels (top-level and per-element) are DB-enforced too, closing `BE-D17`'s own "key-exactness… is the Java seam's job" reading for the raw-SQL boundary specifically | **Now it can, per `BE-D18`** (as with the `resolution_note` row above, this column answers the heading in the affirmative): ten `jsonb_path_exists(…, silent => true)` JSONPath predicates across the two operation branches — element type-check, element well-formedness (for `apply`, including a mandatory, if nullable-valued, `observedHash` key), element key-set, `observedHash` type-check, hash format (`apply` only), top-level key-set — each measured on PG 14.24, the two type-checks added in a fix round to close a `lax`-mode filter-on-array escape the other predicates alone missed (§3.4) | the typed resolver stays the construction-time first line (`ApplyResolutionEvidence`/`RestartResolutionEvidence`/`ReplicaObservation`/`RestartObservation`/`ValueGrammar`/`ReplicaRef`); `Evidence.readResolutionEvidence` gains one required change (below); obligation (j), extended again by `BE-D18` |
| `ref`-content grammar parity between `ReplicaRef` and `resolution_is_evidence`'s `like_regex` clause — **now closed by `BE-D19`** (§3.4 amendment), superseding this section's own earlier "one-sided containment is the safe direction" reading, flagged in place rather than rewritten (`I3`) | It could not before `BE-D19`: `\s`/`\S`'s locale-dependent character class and Java's `isBlank()` were never proven equal, and the runtime's column-scoped `UPDATE` grant on `resolution_observed` lets a write reach the CHECK without ever passing through `ReplicaRef`'s constructor — measured, a lone U+001C–U+001F codepoint is accepted by the pre-`BE-D19` DDL and thrown on read. **Now it can, per `BE-D19`**: both sides enforce the identical, locale-independent `ValueGrammar.REPLICA_REF` (`^[A-Za-z0-9._:/@+\\-]{1,255}$`) — a dedicated grammar, not the shared `ValueGrammar.VALUE` (rejected on two disqualifying facts verified against primary sources: length 128 < Kubernetes' 253-character subdomain names; no backslash for systemd's escaped unit names) | `ReplicaRef`'s compact constructor now delegates to `ValueGrammar.require(ValueGrammar.REPLICA_REF, …)` instead of `isBlank()`; obligation (j), extended a third time by `BE-D19` |
| At most one OPEN attempt per intent — **now DB-enforced by `BE-D20`** (§3.4 amendment); previously only assumed, not proven, by `UNIQUE (intent_id, attempt_no)`, which refuses a same-number collision but not a sequential second open under a different, non-colliding number | It could not before `BE-D20`: the numbering UNIQUE is keyed on `attempt_no`, not on "is any attempt for this intent still open," so two commits at different numbers never touch it — measured, deterministically, two rows with `outcome IS NULL` for one intent were accepted with only that UNIQUE in place | the partial unique index `attempt_open_fence ON apply_attempt (intent_id) WHERE outcome IS NULL`; `AttemptService.open`'s classification of both `Constraints.ATTEMPT_NO_UNIQUE` and `Constraints.ATTEMPT_OPEN_FENCE` as `ConcurrentAttempt`; new obligation (v) |
| Configured retry cap | configuration the DB cannot know | obligation (m) |
| Intents only for non-terminal requests | cross-row temporal rule | obligation (q) |
| Stop-during-apply interaction (§3.3) | behavioural, not relational | obligation (r) |
| `I6` in `error_class`/`pre_baseline`/`runtime_ref`/`resolution_observed` | content, not shape — but the **shape is typed at the persistence seam** (plan 004): a closed-grammar error class, identifier-only runtime references, a typed process baseline and typed resolution evidence, so an environment line, a `Bearer` header, padded base64, a stack trace or an exception message is unrepresentable before any SQL runs — a bare identifier-shaped token is not excluded by shape, so the types are **shape controls, not a secret barrier** — the I6 *content* control on `runtime_ref`/`pre_baseline`/`error_class` is each **adapter's**: its reference builder is the only writer and is tested against sentinel secrets drawn from its own inputs (inspect output, environment, mounted files) | the seam's type tests (secret-shape refusals; a compact token measured *accepted* by shape) + **per-adapter sentinel tests — a named obligation of the adapter plan** |

⚠️ **Amendment `BE-D23` (2026-09-11, design-first): this journal identity is placement-blind, so the
v1 engine is single-placement.** `apply_step_intent`'s logical key is
`UNIQUE (tenant_id, apply_id, step, replica_ref)`, and `placement_scoped_steps` pins `replica_ref` to
the literal `'@placement'` for `PREFLIGHT`, `ROLLBACK` and `ROLLBACK_ASSESS`. One `apply_id` can
therefore hold **at most one** `PREFLIGHT` intent — no matter how many placements an instance-wide
request addresses. Replica-scoped steps are keyed by `replica_ref` alone within the apply, and
`replica_ref` is adapter vocabulary (`BE-D19`): two placements whose adapter produces the same label —
a systemd unit name, a Kubernetes workload name — collapse onto one intent, and if their rendered
configs differ the second `ensureIntent` reports `IntentMismatch`, turning a legitimate two-placement
apply into a protocol error. Neither is a coding defect; both are the model. **No plan may work around
it by encoding a placement into `replica_ref`** — that would silently redefine a column `BE-D19`
grammar-locks and `BE-D18` closes over. Until an identity amendment lands, an instance-wide apply is
**refused before admission**, persisting nothing. The amendment is named and not taken here: it is a
change to the intent table's key, which is `BE-D13`'s, and it belongs to the plan that revisits
`sm_data` against a live database. [`decisions.md`](decisions.md) `BE-D23`.

⚠️ **Amendment `BE-D26` (2026-09-14, design-first): `BE-D23`'s limit is a property of the
placement-scoped steps, and a `stop` meets none of them.** The callout above is about `PREFLIGHT`,
`ROLLBACK` and `ROLLBACK_ASSESS`, the three steps `placement_scoped_steps` pins to
`replica_ref = '@placement'`. **A stop journals none of them.** Its only step is `STOP`, which that
CHECK leaves alone and which therefore carries a real `replica_ref` — so
`UNIQUE (tenant_id, apply_id, step, replica_ref)` admits one `STOP` intent per replica, across any
number of placements, under one `apply_id`. An instance-wide **stop** is therefore journalable and
is admitted; an instance-wide **apply** stays refused, and the asymmetry is structural rather than
a concession. The placements an instance-wide stop addresses are the distinct real `placement_id`s
of that instance's own `apply_request` history — every placement SM has been asked to act on,
succeeded or not; `placement_watermark` is the wrong enumerator, because a placement SM wrote
config to and then failed on would be invisible to it. A stop that addresses no known placement
lands `STOP_FAILED`, not `FAILED_NO_EFFECT`: `state_fits_operation` admits only
`REQUESTED`/`RUNNING`/`STOPPED`/`STOP_FAILED` for a stop, measured. **This does not lift `BE-D23`, including its second clause.** Replica-scoped steps are still keyed
by `replica_ref` alone, and `replica_ref` is still adapter vocabulary — so two placements of one
instance whose adapter yields the same label (the SSH adapter's is the configured container name)
would still collapse onto one intent. The engine therefore enumerates every placement **before
acting** and refuses the whole instance-wide stop with `stop.replica.label.collision` when that
happens: nothing journalled, nothing stopped. No plan may encode a placement into `replica_ref`,
and an instance-wide apply — and a colliding instance-wide stop — still need the identity
amendment `BE-D23` names.

An apply that **follows** a stop must start what it finds, so a restart strategy captures each
replica's liveness at pre-flight: running-then-not-running is obligation (r) and halts; already
down at pre-flight is the state the apply begins from, and the lifecycle verb starts it. §2.2 rule
3's baseline-missing rule is untouched — that rule governs **resume**, not the forward path.
[`decisions.md`](decisions.md) `BE-D26`.

### 3.2 The state model — the `manual` default has a real terminal state

The request records **SM's own part of the operation**; the instance's five config states
(`desired → rendered → persisted → restart-observed → effective-verified`, `architecture.md` §6.1 —
**never collapsed**, per its own rule) remain the binding's ladder, carried by
`instance.config-state` events and read via `status`. Request states:

⚠️ **Amendment `BE-D16` (2026-09-08, design-first repair wave R1).** An external review of the
completed plan 004 found this section's original `STAGED` row unenforceable as written: the qualifier
*"or it has not begun"* described a **mid-flight** condition — a non-manual apply that has persisted
every replica's config but not yet issued a lifecycle step — inside a row this table marks
**terminal**, and nothing in the executable design distinguished the two. `terminal_has_timestamp`
excludes `STAGED` from the non-terminal set unconditionally, `V006`'s terminal-guard trigger freezes
every terminal state (permitting only `STAGED → SUPERSEDED`), and `RequestState.validFor(APPLY)`
accepted `STAGED` for every strategy — so a rolling apply's `OutcomeService.land(t, id, STAGED)`
succeeded and then froze immutably, with no path to `APPLIED`. `BE-D16` closes the escape by removing
the ambiguous clause outright and stating the invariant as a plain **implication**, with no
mid-flight reading:

> `state = 'STAGED'` implies `strategy = 'manual'` (and, transitively, `operation = 'apply'`: two
> existing clauses jointly rule out the other two operations — `lifecycle_has_strategy` already
> forbids `operation = 'stop'` from carrying any non-NULL `strategy` at all, so `strategy = 'manual'`
> is impossible for `stop` regardless of `state` (measured: `('stop','manual','STAGED')` is refused
> by `lifecycle_has_strategy` alone, before this invariant is even reached); `restart_not_manual`
> forbids `operation = 'restart'` with `strategy = 'manual'` directly. Both are load-bearing for this
> parenthetical — neither alone rules out both other operations).

This is an implication, not a biconditional — `strategy = 'manual'` does not imply `state = 'STAGED'`
back (a manual apply is `REQUESTED`/`RUNNING` before it ever reaches `STAGED`). Calling it a
biconditional anywhere in this document or `decisions.md` is a naming error the R1 fix round found
and removed: this design writes true biconditionals in one fixed house form —
`apply_has_generation CHECK ((operation = 'apply') = (generation IS NOT NULL))`,
`lifecycle_has_strategy` — and an implementer told to "express the biconditional above" in that form
would write `CHECK ((state = 'STAGED') = (strategy = 'manual'))`, which is a **different, wrong**
constraint: measured on PG 14.24, it rejects a manual apply at `REQUESTED` (`strategy = 'manual'` is
true there, `state = 'STAGED'` is false, so the biconditional itself is false), making every manual
apply unrepresentable at admission. The relationship here is one-directional; only the implication
form is correct.

**NULL-safety, measured against a live PG 14.24 (R1 fix round).** Written as a bare
`CHECK (state <> 'STAGED' OR strategy = 'manual')`, this implication is **not** NULL-safe by itself:
PostgreSQL's three-valued logic admits a hypothetical `('stop', NULL, 'STAGED')` row, because
`false OR NULL` evaluates to NULL and a CHECK **passes** on NULL, not only on TRUE — measured. The
CHECK actually written below is the self-defending, coalesced form, and once it exists as its own
constraint it closes this hole **by itself**, for every operation — it does not depend on
`state_fits_operation` at all.

⚠️ **Correction (R3 of the repair wave, once `staged_requires_manual` had actually landed and could
be measured rather than argued — see `BE-D17`'s changelog entry).** The paragraph above, before this
correction, claimed `state_fits_operation`'s **`stop`** branch is what "actually closes the hole, and
… stays load-bearing, not redundant", and that its **`restart`** branch "becomes redundant
(harmlessly)". Both claims were imprecise, in complementary directions, once measured against the
landed CHECK: **(a)** "harmlessly" is true for *enforcement* (a `('stop', NULL, 'STAGED')` row is
refused either way) but false for *diagnostics* — the row now violates **both**
`staged_requires_manual` and `state_fits_operation` at once, and PostgreSQL reports whichever
constraint it names first; measured across all six of this schema's tables, that ordering tracks
neither DDL order, nor `ALTER` order, nor OID order, but the two constraint *names'* own lexical
order — `staged_requires_manual` sorts before `state_fits_operation` (`...ag...` < `...at...`), so
the refusal surfaces as `staged_requires_manual`, not `state_fits_operation`, for that exact row
(`SchemaConstraintsIT`'s own `aStopThatClaimsToBeStagedIsRefusedByStagedRequiresManual` is this
measurement, and R2's retargeting of the `stop`/`STAGED` probe to `APPLIED` was this same fact found
early, for `state_fits_operation` alone). **(b)** Consequently, the `stop` branch's `STAGED`
exclusion is now **exactly as redundant** as the `restart` branch's always was — `staged_requires_manual`
alone already excludes `STAGED` from any non-manual strategy, and both `restart` (via
`restart_not_manual`) and `stop` (via `lifecycle_has_strategy` forcing `strategy` NULL) are
non-manual — so "stays load-bearing, not redundant" is wrong for the `STAGED` case specifically. The
`stop` branch remains load-bearing for everything else it excludes (`APPLIED`, both `FAILED_*`
variants, `CONFIG_DIVERGED`, `APPLY_INDETERMINATE`, `INDETERMINATE_RESOLVED`, `SUPERSEDED`), which
`staged_requires_manual` says nothing about — it is redundant for `STAGED` alone, not in general. **The
coupling, stated rather than left implicit:** refusal-by-name on a non-manual `STAGED` row is
*stable* (some CHECK always fires), but *which name* surfaces is silently tied to the two
constraints' lexical order — a future rename of either constraint could flip which name a caller
sees, with no change to what is refused.

The implementing amendment must nonetheless write the CHECK in this sketch's own coalesced house form,
matching every other NULL-sensitive clause rather than resting on the cross-constraint argument
above as its only defence:

```
CONSTRAINT staged_requires_manual CHECK (state <> 'STAGED' OR coalesce(strategy = 'manual', false))
```

— self-defending on its own text, so a future, unrelated change to `state_fits_operation` or
`restart_not_manual` cannot silently reopen this specific hole.

A non-manual apply's mid-flight "persisted everywhere, nothing restarted yet" condition is real and
stays representable — as `RUNNING` at the request level, and as the `persisted` rung on the ladder
(`instance.config-state`) — never as a value of this column. `STAGED → SUPERSEDED` remains the one
sanctioned terminal transition (§3.3's admission algorithm requires it to stage a replacement), and
Plan 005 must be able to walk a non-manual apply `RUNNING → APPLIED` — the walk the prior text left
ambiguous by letting `STAGED` stand in for it.

**The Java mirror is `OutcomeService.land`, not `RequestState.validFor` — decided here, not left to
the implementing task.** `validFor(Operation op)` receives only the operation, never the strategy,
and `BE-D16`'s invariant is strategy-dependent, so `validFor` cannot express it without a signature
change that would drag `StateFitsOperationMirrorTest` and every caller with it. `land`'s own
`ApplyRequestRow row` already carries `row.strategy()` (it is `land` that calls `lockScope(row)` and
reads `row.operation()` for the existing `validFor` check), so the guard belongs there, in the same
place and the same style as the existing `state_fits_operation` mirror immediately above it in that
method: before `land` writes the transition, when `terminal == RequestState.STAGED`, refuse unless
`row.strategy() == Strategy.MANUAL`, naming `staged_requires_manual` in the refusal message exactly
as the existing code names `state_fits_operation` — a readable `IllegalStateException`, not a 23514
out of a repository. No new mirror type or method signature is needed; the existing method gains one
more guard clause.

**With its own executable-DDL prove-it-fires obligation, paired with `BE-D17`'s.** The implementing
amendment adds `staged_requires_manual` alongside the existing enum/state constraints in §3.1's
sketch, with its own obligation extending the (a)–(u) list: refuse a non-manual `STAGED`
insert/update (all four non-manual strategies, by name — `rolling-health-gated`,
`rolling-no-validation`, `restart-all`, `scheduled`), and accept a manual one (the positive control).
This is the direct counterpart of `BE-D17`'s own obligation below (§3.4: "extending (j)… must prove
each of the four") — the two amendments land in the same task and neither's obligation is optional
relative to the other's.

**How the CHECK lands — a ruling, not left open.** `SketchMirrorTest` fixes the migration split as a
statement-text multiset **and** two hard counts — `assertEquals(6, files.size(), "the split is six
migrations: " + files)` and `assertEquals(25, phase1Statements().size(), …)` — so a new `V007`
migration adding this CHECK would fail all three assertions, while `apply_request` (and
`resolution_is_evidence` below it) is created in `V002__apply_request.sql`. **Ruling: the
implementing amendment edits `V002` in place to add `staged_requires_manual` as one more constraint
clause inside the existing `CREATE TABLE`; it does not add a seventh migration, and because it is a
clause inside the one statement that already exists rather than a new statement, `phase1Statements()`'s
count of 24 is unaffected (the arithmetic the re-review settled for this amendment: this and `BE-D17`'s
replacement are both inline table-constraint edits, so no statement is added or removed and the
`6`/`24`/`7` counts and the per-file statement vector all stay exactly as measured).** This is exactly
the posture the plan's own Prerequisites already state for this exact situation: *"no database
carries these tables yet, so a defect in a migration is fixed by amending the design's SQL block and
the migration together … rather than by a down-migration, and that posture must be revisited by the
first plan that ships to a live database"* (plan 004, Prerequisites). Repeated here because it is
load-bearing for this amendment too: **the moment any `sm_data` install exists outside a test
container, an in-place edit to `V002` stops being available, and a real later migration becomes the
only honest way to add this CHECK.**

**A second, separate landing obligation this amendment incurs — the tracked DDL-replay evidence.**
[`evidence/ddl-replay/design-block.json`](evidence/ddl-replay/design-block.json) pins this section's
own SQL-block md5s, and `replay.py` fails loudly ("the design's SQL block changed") the moment
`V002`'s CHECK text changes underneath it — by design: a DDL change unaccompanied by a green run of
that harness is not evidenced (per the harness's own `README.md`, "Why this exists"). **This
amendment's landing is not complete when the CHECK lands in `V002`; it is complete when the harness's
baseline is re-recorded to match**, following the procedure at
[`evidence/ddl-replay/README.md`](evidence/ddl-replay/README.md) §"When the design's SQL changes"
exactly (new legs for the refusal-by-name and its positive control; `--record-candidate <dir>`; a
reviewed diff; the tracked files and `../ddl-execution-pg14.md` updated together in one commit) — not
restated here because that document is the one place it is allowed to drift from. **This document
states that the obligation exists; it does not itself discharge it** — this task (R1) touches no SQL
and runs no harness, so the tracked baseline is deliberately left exactly as it stands today, still
describing the pre-amendment shape, and will read as a legitimate red gate (a design-block md5
mismatch) the moment the next task (R2 of this repair wave, which edits the SQL block and the
migrations) starts — that redness is expected, not a defect this task left behind. **Landed (R4 of
this repair wave, commits `90cce61`, `221672d`):** the baseline is re-recorded against both
amendments together; `NO DRIFT` on the final run — see `evidence/ddl-execution-pg14.md`'s Run 5. This
paragraph stays as the record of the obligation this task stated, not as a claim it remains
undischarged.

This document states the invariant, its NULL-safe CHECK text, its Java-side home, and where it lands;
it does not itself edit the SQL block or add the constraint (the Global Constraint governing this
repair wave: design first, then SQL and migration, never the reverse). See
[`decisions.md`](decisions.md)'s `BE-D16` and its changelog entry.

| State | Means | Terminal? |
|---|---|---|
| `REQUESTED` / `RUNNING` | admitted / executing. **For a non-manual strategy, `RUNNING` also covers the interval after every targeted replica's config is persisted but before lifecycle steps begin or complete** — the interval the pre-`BE-D16` text mis-described as a possible `STAGED` reading; post-`BE-D16` it is `RUNNING`, full stop, until the strategy's lifecycle work reaches one of the terminal states below (`APPLIED`, a `FAILED_*` variant, or `CONFIG_DIVERGED`) or the blocking `APPLY_INDETERMINATE` state (itself non-terminal, per this same table's own `Terminal?` column) | no |
| `STAGED` | every targeted replica has generation `G` **persisted**, under `strategy = manual` — **exclusively**: `BE-D16` (above) makes `state = 'STAGED'` imply `strategy = 'manual'` with no exception, superseding the prior "or it has not begun" clause, which was the unenforced ambiguity. **This is the `manual` strategy's terminal state, and the only strategy that ever terminates here** — SM's part is done; the instance sits at `persisted` on the ladder, the console shows `PENDING_RESTART` from the ladder (not from this row), the operator restarts on their own process, and `restart-observed` arrives by observation, after and independent of this request | **yes — exclusively for `manual`; unreachable for every other strategy** |
| `APPLIED` | the strategy included lifecycle steps and every targeted replica reached **`restart-observed`** with the persisted hash verified. Deliberately *not* claiming `effective-verified` — that ladder step is bounded (`SM-D22`) and permanently absent for some MIs; it belongs to the ladder, not to this row | yes |
| `FAILED_NO_EFFECT` / `FAILED_ROLLED_BACK` | per `AG-D5`'s vocabulary, placement-wide | yes |
| `CONFIG_DIVERGED` | a rolling strategy halted between replicas: some restarted onto `G`, some still run the prior generation. The state `SM-D15` names explicitly; carries per-replica evidence in `attempt` rows and `resolution_observed` on remediation. **Stated limit (not a defect):** the halt does not un-stage — stage-everywhere already put `G` on every replica's disk, so the un-restarted replicas **will adopt `G` on their next external restart** (supervisor, kubelet, operator), outside SM's control and after this request went terminal. That is `SM-D15`'s uniform-but-unapplied shape; the console's remediation copy must say it, and the fences deliberately do not block the corrective apply | yes (until remediated by a new request) |
| `SUPERSEDED` | a newer request for the same scope was admitted while this one was `STAGED`: the staged-but-never-activated config was overwritten. **Transition order, stated and fenced:** admission of the new request (under §3.3's serialization) first marks the elder `STAGED` row `SUPERSEDED` — the marking UPDATE also refreshes `terminal_at` to the supersession time, so the row's terminal timestamp is when it *became* superseded, not when it staged — then inserts; §3.1's `staged_fence` makes forgetting that order a unique violation. A `RUNNING` request is never superseded — admission blocks instead (§3.3). On `host-local`, `AG-D5`'s `SUPERSEDED` report surfaces only during agent journal replay of an `APPLY_INDETERMINATE` request and lands as this terminal state through §3.4's replay path — the DP fence makes in-flight supersession unreachable on both loci, which is the fence working, not a contradiction | yes |
| `APPLY_INDETERMINATE` | §2.2 rule 3's evidence-exhausted case. **Blocks all further applies on its scope until resolved** — `architecture.md` §16.1's binding constraint, enforced by §3.3 | no (blocking) |
| `INDETERMINATE_RESOLVED` | the operator confirmed from observed state; `resolution_outcome` says whether the resolved operation counts as applied — for `apply`, feeding §2.2 rule 1's monotonicity off `G` itself; for `restart`, no generation is in play at all — and `resolution_observed` carries the evidence, in one of two operation-specific shapes (`BE-D17`, §3.4) | yes |
| `STOPPED` / `STOP_FAILED` | `operation = stop` outcomes — an operator-commanded stop is recorded, audited, and visible to the history screen like any other high-consequence action | yes |

### 3.3 Admission control, indexes, retention

- **Single-in-flight, honestly enforced.** Two mechanisms, because the pinned PostgreSQL 14 floor
  (`SM-D26`) treats NULL index keys as distinct and even `NULLS NOT DISTINCT` (a PG 15 feature) would
  not make an instance-wide request exclude a per-placement one:
  1. **two partial unique indexes**, split by operation so the database constraint matches the
     stop-exemption prose rather than contradicting it (an operation-blind predicate would make a
     `REQUESTED` stop collide with the very `RUNNING`/`APPLY_INDETERMINATE` apply it exists to
     escape):
     `ON apply_request (tenant_id, instance_id, placement_id) WHERE state IN
     ('REQUESTED','RUNNING','APPLY_INDETERMINATE') AND operation <> 'stop'` — the apply/restart
     fence, with the **nil-UUID sentinel** (never NULL) as the instance-wide key; including
     `APPLY_INDETERMINATE` is what implements `architecture.md` §16.1's *"blocking further applies
     until an operator confirms actual"* — and
     `ON apply_request (tenant_id, instance_id, placement_id) WHERE state IN
     ('REQUESTED','RUNNING') AND operation = 'stop'` — the stop family's own, narrower concurrency
     rule: at most one in-flight stop per scope, never fenced by an apply's state.
     **Both fences are tenant-scoped**, like every other key on these tables: instance ids are UUIDs
     today, but import/restore, test fixtures and any future non-global identity model can reuse
     them, and a fence that lets tenant A's in-flight apply block tenant B's is a tenant-isolation
     claim the schema makes and the admission path breaks;
  2. an **admission transaction** serialized per tenant-and-instance by the `apply_scope` **lock
     row** (§3.1): `INSERT … ON CONFLICT DO NOTHING` on the scope's own primary key (a single-arbiter
     upsert — legitimate here, unlike rule 2's multi-unique case), then `SELECT … FOR UPDATE` — an
     exact, collision-free, tenant-keyed serialization the execution review's hash-lock could not
     give (int4 `hashtext` collides, measured; any advisory key shares one database-wide unnamed
     namespace). The held row serializes the cross-scope rule the fences cannot express: no
     per-placement admission while
     an instance-wide request is in flight or unresolved, and vice versa; the same transaction
     performs the `SUPERSEDED` marking of an elder `STAGED` row. The index is the belt for identical
     scopes; the serialized admission check is the braces for overlapping ones — both are stated
     because neither alone is sufficient. **`operation = stop` is exempt from the blocking check,
     deliberately**: `architecture.md` §16.1's constraint blocks further *applies* behind an
     unresolved `APPLY_INDETERMINATE`, and fencing the operator's emergency lever would be a wider
     reading than the constraint requires. **What a mid-apply stop does, specified rather than
     implied (obligation (r)):** the stop executes immediately at the adapter — it does not queue
     behind the apply and the apply is not cancelled in place; the running apply's next step observes
     the stopped replica through `status` and its health gate, and terminates through its normal
     failure paths (`FAILED_*`, or `CONFIG_DIVERGED` with the stop visible in the per-replica
     evidence). No torn write is possible: `materializeConfig` is atomic (temp→fsync→rename /
     immutable ConfigMap), so a stop can strand an apply, never corrupt one. Admission control is
     **locus-independent** — `host-local` requests flow through the same admission and the same
     table; execution then belongs to the agent protocol rather than to §2's adapters.

  **Executable-DDL obligations for the implementing plan (prove-it-fires, not prose):** migration
  tests must demonstrate (a) a `stop` is **admitted** while an apply for the same scope is `RUNNING`
  and while one is `APPLY_INDETERMINATE`; (b) a second apply/restart is **refused** in both of those
  states; (c) a second in-flight `stop` for the same scope is refused; (d) the scope-row-lock check
  refuses a per-placement apply while an instance-wide one is in flight, and vice versa — proven
  live with two concurrent sessions (the second blocks — on the scope-row upsert or on `FOR UPDATE`,
  whichever it reaches first; either way the same serialization — then refuses cleanly) — **and a
  placement-scoped `stop` is admitted while an instance-wide apply is `RUNNING` or
  `APPLY_INDETERMINATE`** (the cross-scope half of the stop exemption, not just the same-scope
  half); (e) the same `(instance_id, placement_id)` under **different tenants** does **not** collide
  on either fence or the lock, while the same tenant-and-scope still collides as intended; (f)
  `hash_on_config_steps` refuses **both directions**: a restorative `ROLLBACK` (or
  `MATERIALIZE`/`VERIFY`) intent without `config_hash`, AND a hash supplied on a hashless step
  (`STOP`/`PREFLIGHT`/`LIFECYCLE`/`ROLLBACK_ASSESS`); (g) a second intent for the same
  `(apply, step, replica)` with a different key is refused by the composite UNIQUE, a reused
  `idempotency_key` under another step is refused by the key UNIQUE, and the read-compare rejects a
  retry presenting the recorded key with a different hash; (h) `state_known`,
  `state_fits_operation`, `apply_has_generation` and `lifecycle_has_strategy` each refuse their
  malformed row (a state outside the enum must be impossible, or the fence predicates guard
  nothing); (i) an `apply_attempt` referencing another request's intent — same tenant, wrong
  `apply_id` — is refused by the triple FK; (j) `resolution_is_evidence` and
  `resolution_only_when_resolved` refuse each of: `INDETERMINATE_RESOLVED` with a missing
  `resolved_by`, with `'{}'::jsonb`, with JSON `null`, with
  `{"stagedGeneration": null, "replicas": []}` (present keys, worthless values — the equality and
  non-empty-array clauses must each fire), with a string `stagedGeneration` — and any non-null
  resolution field on any other state; the engine's typed resolver additionally refuses a malformed
  replica **element**, with its own test. **Extended by `BE-D17` (§3.4), landed rather than left
  pending — the shape is now operation-specific, and this obligation additionally proves, by name,
  all four rejection classes §3.4 names:** (i) wrong-operation evidence, both directions —
  apply-shaped evidence (`stagedGeneration` + `replicas`) resolving a `restart` request, and
  restart-shaped evidence (`restartsObserved`, no `stagedGeneration` key at all) resolving an
  `apply` request; (ii) a mismatched apply generation — `stagedGeneration` present, numeric, but ≠
  `generation` (with the numeric-equality positive control: `20.0` accepted against
  `generation = 20`); (iii) missing required observations — an absent, empty, or non-array
  `replicas`/`restartsObserved` for the resolved operation; (iv) an invented restart staged
  generation — a `restart` resolution whose evidence carries a `stagedGeneration` key at all, any
  value, refused by the key's mere presence. **Extended further by `BE-D18` (§3.4), specified but not
  yet landed by this task — a design-only round; the implementing task lands both the CHECK and this
  obligation's new legs, at the Java boundary (one required reader change, below) and at raw SQL
  (new):** the same obligation additionally proves, by name, element-shape and key-exactness refusals
  the container-only CHECK left representable — for `apply`: a `replicas` element that is not an
  object, is missing `ref` or `restarted`, carries a blank or non-string `ref`, a non-boolean
  `restarted`, or **omits the `observedHash` key entirely** (element well-formedness, now seven
  hostile shapes, not six — `Evidence.writeApply` always emits `observedHash`, as `putNull` when
  unavailable, so an element with only `ref`/`restarted` is a shape this build never produces and is
  now refused, not merely under-specified); a `replicas` element carrying any key outside
  `{ref, restarted, observedHash}` (element key-set); an `observedHash` **present** but neither JSON
  `null` nor a string matching `^[a-z0-9]+:[0-9a-f]{16,128}$` (hash format — unreachable once the
  key-required clause above also refuses an absent key, and proven with its own positive control: a
  present `null` accepts); and a `resolution_observed` object carrying any top-level key outside
  `{stagedGeneration, replicas}` (top-level key-set). **Extended again, R5 fix round 1 (§3.4):** a
  `replicas` element that is itself an **array** — `[[]]`, `[[],[]]`, `[[{<well-formed>}]]`,
  `[{<well-formed>}, []]` — escapes every predicate above under `lax` mode's filter-on-array
  auto-unwrap; a dedicated element-type predicate (`$.replicas[*].type() ? (@ != "object")`) closes
  it, and its `observedHash`-typed counterpart closes the identical escape for `observedHash: []` /
  `["sha256:…"]` / `[null]`. Measured (developer, PG 14.24), current totals superseding the
  pre-fix-round count: 53 apply legs, 20 restart legs, zero regressions. For `restart` — no
  `observedHash` axis exists there at all, so no key-required or hash-format clause applies — the
  mirror shape over `$.restartsObserved[*]` and the key sets `{ref, restarted}`/`{restartsObserved}`
  (an `observedHash` key on a restart element is itself refused by the element key-set, which is what
  makes "a restart observation has no `observedHash`" a DB-enforced fact, not merely a Java-typed
  one), plus its own element-type predicate for the same array-escape class. Every new predicate uses
  the four-argument `jsonb_path_exists(target, path, vars, silent => true)` form — `silent => true` is
  load-bearing, not cosmetic: `.keyvalue()` throws `jsonpath item method .keyvalue() can only be
  applied to an object` on a non-object array element even in `lax` mode, measured, and the
  two-argument form would reintroduce exactly the `AND`-order-dependent throw hazard this design's own
  §3.4 correction already removed twice (the `::numeric` cast, `jsonb_array_length`) — a future editor
  "simplifying" back to two arguments reopens it. **Do not reach for `strict` mode to close the
  array-escape instead — §3.4's fix-round text names the measured regression that creates** (a
  missing `ref`/`restarted`/`observedHash` silently accepted, not merely the array escape left open).
  **Three boundary negatives this obligation names, not merely implies —
  none discharged by this task:** (1) a Java-level negative — `Evidence.readResolutionEvidence` must
  refuse (not silently null-coalesce) a `replicas` element whose `observedHash` key is entirely
  absent; (2) a raw-SQL negative — `resolution_is_evidence` refuses, by name, an apply resolution
  whose `replicas` element carries only `{ref, restarted}`, beside its own positive control (the same
  element with `observedHash: null`); (3) a DDL-replay leg for this exact case, recorded in the same
  `--record-candidate` pass as this obligation's other new legs. **Extended a third time by `BE-D19`
  (§3.4 amendment, R1 fix round):** a `replicas`/`restartsObserved` element's `ref` carrying a lone
  U+001C or U+001F codepoint is refused, by name, at raw SQL — the parity negative the write-direction
  gap left open — beside the positive control that an ordinary identifier-shaped `ref`
  (`"cache-engine-1"`) is accepted; a `ref` carrying a literal backslash (a systemd escaped-unit-name
  shape) and a `ref` longer than 128 characters (a Kubernetes-shaped subdomain name, up to 255) are
  now ACCEPTED — the two cases that disqualified the amendment's withdrawn `ValueGrammar.VALUE`-based
  first pass, proven admitted by the grammar that replaced it (`ValueGrammar.REPLICA_REF`); and the two
  literals `SchemaConstraintsIT`'s own hostile-ref positive control carries (a `ref` with
  quotes/backslash/space/`=`/an accented character, and a `ref` with an embedded newline) move from
  that test's accepted assertions to refused ones, each refused by the same predicate's name — still
  refused under the wider grammar, by their OTHER characters, not by the backslash. None of these legs
  are discharged by this task; (k) an `UPDATE` on `apply_step_intent` under
  `sm_dp_runtime` is refused by privilege — and the same statement **succeeds** via `sm_dp_migrator`,
  proving the refusal is the grant, not a broken table; (l) `SET ROLE sm_dp_owner` under
  `sm_dp_runtime` is refused (no membership), and `pg_has_role('sm_dp_runtime','sm_dp_owner','MEMBER')`
  is asserted false so the topology cannot drift silently; (m) `attempt_no_positive` refuses
  `attempt_no` 0 and −1, and the **engine** refuses to open an attempt beyond the configured retry
  cap — an application-level prove-it-fires, because the cap is configuration the database cannot
  know; (n) the admission monotonicity check refuses **all four cross-scope directions**: a
  placement-scoped apply ≤ the instance watermark, a placement-scoped apply ≤ its own placement
  watermark, an instance-wide apply ≤ the instance watermark, and an instance-wide apply ≤ any
  placement's watermark — including immediately after an `INDETERMINATE_RESOLVED` with
  `resolution_outcome = 'applied'`; (o) under `sm_dp_runtime`, an `UPDATE` of
  `apply_request.tenant_id` or `requested_by` is refused by the **column-scoped grant**, a
  terminal→non-terminal state walk (`APPLIED → REQUESTED`) is refused by the
  `apply_request_terminal_guard` trigger — and the positive control, a legal `RUNNING → APPLIED`
  walk, succeeds; (p) inserting a second `STAGED` row for a scope without first marking the elder
  `SUPERSEDED` is refused by `staged_fence`; the marking itself — a real
  `STAGED → SUPERSEDED` `UPDATE` — **passes through the terminal-guard trigger** (its one sanctioned
  terminal transition), the full mark-then-insert order succeeds end to end, and a `STAGED → APPLIED`
  walk is refused by the same trigger; (q) the
  engine refuses to create an intent against a terminal request (cross-row, engine-enforced, its own
  test); (r) a `stop` executed while an apply is mid-flight halts the apply with per-replica evidence
  (`FAILED_*`/`CONFIG_DIVERGED`), never a torn write — the engine-level interaction test §3.3's
  exemption paragraph defines; (s) the **full two-phase Phase-0/Phase-1 script of §3.1 executes end
  to end on a fresh PostgreSQL 14 under exactly the stated credentials** — the bootstrap superuser
  (the owner of schema `public` on PG 14) for Phase 0, asserting, not assuming, the outcome:
  `has_schema_privilege('sm_dp_runtime','public','CREATE')` is **false** afterwards — with the
  negative control that the same Phase 0 run by a **non-superuser database owner** leaves it
  **true** (the `REVOKE` is a silent WARNING no-op for anyone but the grantor — measured for both
  a database owner and a CREATEROLE installer; **harness note:** roles are cluster-scoped, so in a
  cluster where Phase 0 has already run the control is the `REVOKE` executed in isolation in a
  second database owned by the non-superuser, or a second cluster — a literal re-run aborts at
  `CREATE ROLE` before it reaches the statement under test) — and `sm_dp_migrator` for Phase 1 —
  the migration suite's own bootstrap test, standing because execution evidence does not transfer
  to text it never ran (three times measured now). The standing evidence for this design's exact
  sketch is [`evidence/ddl-execution-pg14.md`](evidence/ddl-execution-pg14.md), which names the
  commit and the extracted-script hashes it executed; (t) the two `*_only_rises` triggers refuse a decrease
  on both watermark tables (the measured one-statement rewind-and-re-admit hazard), and a rise
  succeeds — **and, under `sm_dp_runtime`, the column-scoped grant refuses an `UPDATE` of every
  identity column on both tables** (`apply_scope.tenant_id`, `.instance_id`;
  `placement_watermark.tenant_id`, `.instance_id`, `.placement_id`) **and a `DELETE` on either**,
  while the same role's admission upsert, `SELECT … FOR UPDATE` and a legal watermark raise all still
  succeed (the positive controls: the grant is narrow, not broken); (u) the terminal-guard's compaction branch: an `attempt_summary`-only UPDATE on a
  terminal row succeeds, the same UPDATE also touching any other column is refused, and
  `request_has_scope` refuses a request whose scope row does not exist. **Added by `BE-D20` (§3.4
  amendment): (v) the `attempt_open_fence` partial unique index refuses a second OPEN
  (`outcome IS NULL`) attempt for one intent whether or not its `attempt_no` collides with the first —
  proven both sequentially (deterministic, no race) and by two concurrent callers — distinct BY NAME
  from `apply_attempt_intent_id_attempt_no_key` (the two conflicts are named separately in the test, not
  conflated), with the positive control that a retry is legal once the prior attempt is closed
  (`outcome` set).** **Test-harness rule, measured:** every refusal is asserted
  per-statement with its constraint/index/trigger **name** — a batched psql script mis-attributes
  errors (the execution review's own first run read clean while an obligation was unproven).
- **Indexes:** all named in §3.1's DDL — the three fences (`apply_inflight_fence`,
  `stop_inflight_fence`, `staged_fence`), the `apply_scope`/`placement_watermark` primary keys (the
  lock row and the monotonicity reads),
  `apply_history` (tenant-safe history screen), the intent UNIQUEs (the lookups), and
  `attempt_prune (tenant_id, started_at)` for pruning/archival/export scans, which address attempts
  independently of their requests. **A fourth fence, landed by `BE-D20`
  (§3.4 amendment, commit `e1b8490`): `attempt_open_fence` on `apply_attempt (intent_id) WHERE outcome
  IS NULL`** — mechanism-identical to the three above, guarding "at most one open attempt" the same way
  they guard "at most one in-flight request".
- **Growth is linear, and here is the arithmetic.** `apply_request`: one row per operator action —
  a heavy site doing 100 applies/day writes 36.5k rows/year; audit data at log-line cost, no
  partitioning machinery (`SM-D26`'s tiers are for telemetry facts). `apply_step_intent`: at most
  `replicas × steps` per request (≤ 70 rows/apply at 10 replicas) — one row per logical step,
  retries excluded by construction. `apply_attempt`: at most `replicas × steps × max_retries` per
  request, all bounded — a 10-replica instance at 3 retries worst-cases 150 rows/apply (~5.5M
  rows/year at that same heavy site), typically ~15 (~550k/year).
- **Retention split — audit outlives diagnostics, by policy:** `apply_request` rows are retained
  long-term (default: never dropped in v1; archival per `architecture.md` §8.5 archive-before-drop
  when a policy lands — §8 row 4), and `apply_step_intent` rows live exactly as long as their
  request (they are its identity, not its diagnostics). `apply_attempt` rows are prunable once their
  request is terminal and a diagnostic window has passed (default 90 days), **except** attempts
  under requests that terminated `FAILED_*`, `CONFIG_DIVERGED` or `INDETERMINATE_RESOLVED`, which
  are retained as long as their request — failures are where the diagnostics earn their keep.
- **Compaction:** attempt pruning is a plain indexed `DELETE` in `started_at` batches; the pruning
  job first folds the pruned rows into the request's `attempt_summary` (counts, last error class per
  step, per-replica final outcomes) so the request row keeps explanatory power after its attempts are
  gone.
- **`I6`:** none of the three tables ever stores rendered config content or secret values — hashes, references,
  classified error strings and adapter identifiers only; `pre_baseline` holds process metadata, never
  configuration.

### 3.4 `APPLY_INDETERMINATE`, exactly as `FE-D18` clause B expects

One name, everywhere: the state is **`APPLY_INDETERMINATE`** in this design's enum, matching
`architecture.md` §16.1, `FE-D18`, `AG-D5` and `frontend/design.md`. Resolution is **by observed
state, never by response code**: the console shows staged generation + per-replica `restart-observed`
(from `status` and the ladder), the operator confirms what actually happened, and the row records
`resolved_by`, `resolution_outcome` (`applied` | `not_applied` — the input §2.2 rule 1's monotonicity
needs) and `resolution_observed` (the evidence snapshot) — and, in v1, **no free-text note**: the
`resolution_note` column exists in §3.1 but nothing writes it (residual table). While unresolved, §3.3
blocks further applies on the scope.

**The two flavours stay distinct (`AG-D5`), and their row shapes differ on purpose:** the agent's
`APPLY_INDETERMINATE` resolves *deterministically* on its next session by journal replay — the DP
records that as a **direct transition** `APPLY_INDETERMINATE → APPLIED / FAILED_* / SUPERSEDED`,
with all four resolution fields staying NULL (`resolution_only_when_resolved` requires exactly
that), because no operator confirmed anything: the journal did. `INDETERMINATE_RESOLVED`, with its
evidence-bearing CHECK, is **exclusively** the `dp-remote` operator-confirmation flavour.

⚠️ **Amendment `BE-D17` (2026-09-08, design-first repair wave R1) — resolution evidence is
operation-specific, and an apply's evidence is bound to the request's own generation.** The same
external review found `resolution_is_evidence` (§3.1) evidence-*shaped* but not evidence-*bound*: it
required only that `resolution_observed -> 'stagedGeneration'` be *some* number, never that it equal
`apply_request.generation` — so evidence naming generation 21 could resolve a generation-20 request
`applied`, raising the watermark off the request row's 20 while the evidence itself asserted 21,
leaving 21 still admissible under §2.2 rule 1's monotonicity. Separately, the same shape was required
on **every** `INDETERMINATE_RESOLVED` row regardless of `operation`, so a `restart` resolution — whose
request carries no `generation` at all (`apply_has_generation`) — had to supply a `stagedGeneration`
that certified nothing and that `raiseWatermark`'s `operation = 'apply'` gate then ignored: positive,
present, and meaningless. `BE-D17` replaces the one evidence shape with two, discriminated by the
resolved request's own `operation` column (never a caller-asserted flag), and fixes the apply shape
to prove the equality rather than merely the type.

**Reachability, stated rather than assumed:** a `restart` request can reach `APPLY_INDETERMINATE`
and therefore `INDETERMINATE_RESOLVED` — `state_fits_operation`'s `restart` branch is
`state NOT IN ('STAGED','STOPPED','STOP_FAILED')`, which admits `APPLY_INDETERMINATE`, mirrored
identically by `RequestState.validFor(RESTART)`. This document's own §2.2 rule 3 and this section's
opening paragraph above speak of the in-flight *apply* case only, because that is where `dp-remote`'s
resume ambiguity originates; they are not read as excluding a restart's own indeterminate path, and
`BE-D17`'s restart branch below exists because that path is real, not hypothetical.

- **`operation = 'apply'` — `resolution_observed` is exactly**
  `{"stagedGeneration": <number>, "replicas": [<non-empty array>, …]}`, where `stagedGeneration`
  **must equal `generation`** (the request row's own column — a same-row, not cross-row, comparison,
  so an ordinary CHECK expresses it; numeric equality, not textual — `{"stagedGeneration": 20.0}`
  against `generation = 20` is intended to be **accepted**, `20.5` **refused**) and `replicas` is
  unchanged from today's shape (a non-empty array of `{"ref": …, "restarted": …, "observedHash": …}`
  elements per `ReplicaObservation`; per-element typing stays the Java-side typed resolver's job, per
  the residual table).
- **`operation = 'restart'` — `resolution_observed` is exactly**
  `{"restartsObserved": [<non-empty array>, …]}` — a **different key**, deliberately: a restart
  resolution asserts, per replica, whether the operator's restart was observed, as a non-empty array
  of elements shaped `{"ref": <replica ref, same string form as `ReplicaObservation.ref`>,
  "restarted": <boolean>}` — **two fields, not three: no `observedHash` at all**, because a restart
  resolution verifies no generation and has no config hash to read back; a restart observation is
  never a `ReplicaObservation` with a null hash, it is a distinct, smaller record (name it, e.g.,
  `RestartObservation(ReplicaRef ref, boolean restarted)`, the implementing amendment's Java type).
  `resolution_observed` carries **no `stagedGeneration` key at all** on a restart resolution — its
  presence is itself the defect this amendment closes (an invented number with no request generation
  to check it against), so the intended CHECK must refuse the key's mere *presence*, not merely a
  wrong value.

**Key-exactness — which layer holds it, stated so the implementing task does not have to guess:**
neither clause below tests that `resolution_observed` has *no other* top-level keys; the intended
CHECK is a floor (the required keys are present, typed, and — for `apply` — equal), not a ceiling. As
with `replicas` today (`ResolutionEvidence`'s own javadoc: *"proves… the JSON has no other keys at
all"*), key-exactness is the **Java seam's** job — the implementing amendment's typed
`ApplyResolutionEvidence`/`RestartResolutionEvidence` (or equivalent) writer is the only path that
produces this column's bytes, and it is what refuses an extra key, not the CHECK.

Stated as the intended CHECK, in the coalesced, no-NULL-escape form §3.1's own comment requires
(`'{}'` passed the uncoalesced form, measured) — **specification for the implementing amendment, not
a change to §3.1's fenced sketch**:

```
CONSTRAINT resolution_is_evidence CHECK ((state = 'INDETERMINATE_RESOLVED') = (
        resolved_by IS NOT NULL
        AND resolution_outcome IS NOT NULL
        AND coalesce(jsonb_typeof(resolution_observed) = 'object', false)
        AND CASE operation
            WHEN 'apply' THEN
                coalesce(resolution_observed -> 'stagedGeneration' = to_jsonb(generation), false)
                AND coalesce(jsonb_typeof(resolution_observed -> 'replicas') = 'array', false)
                AND coalesce(resolution_observed -> 'replicas' <> '[]'::jsonb, false)
            WHEN 'restart' THEN
                NOT coalesce(jsonb_exists(resolution_observed, 'stagedGeneration'), false)
                AND coalesce(jsonb_typeof(resolution_observed -> 'restartsObserved') = 'array', false)
                AND coalesce(resolution_observed -> 'restartsObserved' <> '[]'::jsonb, false)
            ELSE false
        END))
```

Three measured corrections against the form this document specified before this fix round, all from
the same review, running the amended CHECK on a live PG 14.24: **first**, the apply clause no longer
casts (`(resolution_observed ->> 'stagedGeneration')::numeric = generation`) — PostgreSQL does not
guarantee subexpression evaluation order across an `AND`, and a cast that can *throw* on a
non-numeric operand is measured to do so **only when the planner evaluates it first**: the identical
CHECK with its two clauses swapped raised `22P02 invalid input syntax for type numeric` instead of
`23514 resolution_is_evidence` for the same row, on the same server — an implementer-invisible
planner choice deciding whether a bad row is refused as intended or crashes the statement. The
`resolution_observed -> 'stagedGeneration' = to_jsonb(generation)` form above needs no cast: it
compares two `jsonb` values (a non-numeric or absent `stagedGeneration` simply compares unequal, and
`to_jsonb(generation)`'s numeric-jsonb equality is what makes `20` and `20.0` compare equal while `21`
does not — measured). **Second**, both non-empty-array clauses — the `replicas` one inherited
unchanged from the pre-amendment CHECK, and the `restartsObserved` one this amendment adds as a
second copy of the same pattern — no longer call `jsonb_array_length`: that function *throws*
`22023 cannot get array length of a scalar` on a non-array operand, and it sat behind exactly the
sibling-`jsonb_typeof`-across-an-`AND` guard the first correction had just rejected as unsafe — a
narrower version of the identical defect, re-introduced by this amendment's own second array clause.
Measured with `resolution_observed = {"stagedGeneration": <a value equal to generation>, "replicas":
5}` (a scalar, not an array) across all six permutations of the surrounding clauses: **`23514`
three times, `22023` three times**, for the identical row — an implementer-invisible planner choice
again deciding whether a malformed row is refused as intended or crashes the statement. Replaced by
`resolution_observed -> 'replicas' <> '[]'::jsonb` (and the `restartsObserved` equivalent): jsonb
inequality **never throws**, regardless of the operand's type — a non-array value simply compares
unequal to the empty-array literal `'[]'::jsonb`, correctly registering as "not proven empty", and the
sibling `jsonb_typeof(...) = 'array'` clause is what actually excludes non-arrays; an empty array
compares equal to `'[]'::jsonb` and is correctly refused as `<>` evaluating false. With all three
corrections applied, there is no erroring operand anywhere left in this CHECK for `AND`-order to
expose — the categorical claim is now true of the whole constraint, not only of the apply clause's
generation comparison. **Third**, `resolution_observed ? 'stagedGeneration'` is replaced by
`jsonb_exists(resolution_observed, 'stagedGeneration')` — the same NULL-safe key-presence test,
spelled with the function form because `?` collides with a JDBC positional parameter marker in
prepared statements (the migration runner and any hand-written JDBC against this table both use
`?`-marker binding).

`state_fits_operation` already makes `operation = 'stop'` unable to reach `INDETERMINATE_RESOLVED`, so
the `ELSE false` branch is a defensive backstop, not a live path. **At the time this paragraph was
written, under `BE-D17` alone,** this CHECK was exactly as evidence-*shaped*, not evidence-*complete*,
as its predecessor — per-element typing inside `replicas` and `restartsObserved` stayed the engine's
typed resolver, with its own refusal tests — closing only the two things a database could then prove:
which shape belongs to which operation, and, for `apply`, that the asserted generation is the
request's own. **Corrected here, landing `BE-D18` (R6):** per-element typing is no longer the
resolver's alone — the ten predicates of this section's own amendment below make the database prove
element shape and both key-exactness levels too, so this CHECK is now element-complete, not merely
evidence-shaped; the engine's typed resolver remains the construction-time first line, not the only
line.

**Four classes must be rejectable, at both the Java boundary and raw SQL, once this lands:** (i)
wrong-operation evidence — apply-shaped evidence resolving a restart request, or vice versa; (ii) a
mismatched apply generation — `stagedGeneration` present, numeric, but ≠ `generation`; (iii) missing
required observations — an absent or empty `replicas`/`restartsObserved` array for the resolved
operation; (iv) an invented restart staged generation — a `restart` resolution whose evidence carries
a `stagedGeneration` key at all, any value. The implementing amendment's own executable-DDL obligation
(extending (j)) must prove each of the four, alongside the existing null/empty/wrong-type refusals (j)
already proves.

**The Java-boundary half of class (i) is named here, not left for the implementing task to place.**
`OutcomeService.resolveIndeterminate` is the only method that ever writes the four resolution
columns (`OutcomeService`'s own javadoc, on `land`: "{@link #resolveIndeterminate} is the only path
to it"), so it is where the operation↔evidence match belongs — before that method persists anything,
it must refuse an evidence value shaped for the wrong operation against `row.operation()`, by the
same before-any-write pattern
`land`'s existing `state_fits_operation`/`staged_requires_manual` (`BE-D16`, §3.2) guards already use.
This forces a change to today's single `ResolutionEvidence`/`Resolution` shape: one evidence type
that always carries a `stagedGeneration` cannot represent a restart's evidence at all (there is no
value to put there), so the implementing amendment's Java side needs either two evidence records
(an `ApplyResolutionEvidence` and a `RestartResolutionEvidence`, per §"Key-exactness" above) behind
one sealed `Resolution.evidence()` type, or an equivalent operation-typed split — the exact shape is
the implementing task's to choose, but *that* a split is needed, and *where* the match is checked,
are decided here. **Superseded prose, flagged rather than edited (Java files are out of scope for
this design-only round):** `ResolutionEvidence`'s and `ReplicaObservation`'s javadoc, as it read at
drafting time, described today's single, apply-only shape (a `stagedGeneration` on every resolution,
`replicas` as the only per-element type) — both were superseded by this amendment. **Landed (R3 of
this repair wave, commit `5f04d79`):** both javadocs are updated alongside the type split —
`ResolutionEvidence` is now sealed over `ApplyResolutionEvidence`/`RestartResolutionEvidence`. This
paragraph stays as the record of the gap this design-only round flagged, not as a claim it remains
open.

**How the CHECK lands — the same ruling as `BE-D16`'s, restated for this replacement case.**
`resolution_is_evidence` is created inside `apply_request`'s own `CREATE TABLE`, in
`V002__apply_request.sql` — the same file `SketchMirrorTest`'s `assertEquals(6, files.size(), …)` and
`assertEquals(25, phase1Statements().size(), …)` fix in shape and count. **Ruling: the implementing
amendment replaces the existing `resolution_is_evidence` CHECK body in `V002` in place; it does not
add a later `ALTER TABLE … DROP CONSTRAINT / ADD CONSTRAINT` migration.** This edits the text of an
existing constraint clause inside the existing `CREATE TABLE` statement — it does not add or remove a
statement, so it carries none of the file/statement-count consequences a new migration would; §3.2's
identical note about the `6`/`24`/`7` counts and the per-file statement vector applies here too. Per
the plan's own Prerequisites, *"no database carries these tables yet, so a defect in a migration is
fixed by amending the design's SQL block and the migration together … rather than by a
down-migration, and that posture must be revisited by the first plan that ships to a live database"*
— repeated here because a `CHECK` replacement is exactly the kind of change an in-place edit stops
being able to make safely once real rows exist under the old shape: **the moment any `sm_data`
install exists outside a test container, this amendment's landing mechanism must become an `ALTER
TABLE … VALIDATE CONSTRAINT`-style migration with a backfill or a migration-time data audit, not an
in-place edit.**

**The same second, separate landing obligation as `BE-D16`'s — the tracked DDL-replay evidence.**
This amendment rewrites the `resolution_is_evidence` body, and
[`evidence/ddl-replay/design-block.json`](evidence/ddl-replay/design-block.json)'s pinned md5s make
`replay.py` fail loudly the moment `V002` changes underneath it. The same procedure applies, at the
same place — [`evidence/ddl-replay/README.md`](evidence/ddl-replay/README.md) §"When the design's SQL
changes" — and is not restated here for the same reason: legs for both amendments' refusals-by-name
(and their positive controls) land together, in one `--record-candidate` pass, one reviewed diff, one
`../ddl-execution-pg14.md` update, because both amendments change the same `CREATE TABLE`. This
design states that the obligation exists for `BE-D17` exactly as it does for `BE-D16`; it does not
itself discharge it. **Landed (R4 of this repair wave, commits `90cce61`, `221672d`):** the baseline
is re-recorded against both amendments together; `NO DRIFT` on the final run — see
`evidence/ddl-execution-pg14.md`'s Run 5. This paragraph stays as the record of the obligation this
design stated, not as a claim it remains undischarged.

**Superseded prose, flagged rather than edited — §3.1's own SQL comment is inside the protected
sketch and is not touched by this round.** The comment immediately above `resolution_is_evidence` in
§3.1 ("`Resolution is EVIDENCE: … DB proves: resolver named, stagedGeneration a NUMBER, replicas a
NON-EMPTY ARRAY…`") describes the pre-amendment, operation-blind shape this decision replaces —
`stagedGeneration` is no longer required to be merely *a number* on every resolution (it is required
on `apply` resolutions only, and to equal `generation`), and the comment says nothing about
`restartsObserved`. It is accurate today and wrong the moment `BE-D17` lands; the implementing
amendment corrects it together with the CHECK it describes, in the same edit — this design cannot
correct it now without touching the block this round is forbidden to touch. §3.3's own
executable-DDL obligation list carries the same flag on its (j) entry, marked directly there rather
than only pointed at from here: it currently describes the pre-amendment, operation-blind refusal
set, and is superseded in the same way, for the same reason, pending the same future edit.
**Landed (R3 of this repair wave, the `BE-D17` implementing task):** both corrections above are
made — §3.1's comment now states the two operation-specific shapes and §3.3's (j) now names all
four rejection classes by number, each proven by name in the executable suite. This paragraph stays
as the record of why the design-only rounds left them untouched, not as a claim that they still are.

**Watermarks, reconciled with §2.2 rule 1's own sentence.** §2.2 rule 1 states *"the same transaction
that lands `STAGED`/`APPLIED`/resolved-`'applied'` updates the corresponding watermark"* (that sentence
now carries its own forward pointer to this paragraph) — written before this amendment and left as
written (`I3`: a sentence's meaning is not edited in place). Read after `BE-D17`, that clause applies
to a resolved-`'applied'` row **only when the resolved request's `operation` is `apply`**, and the
watermark it raises is `generation` — the request's own column, now provably equal to the evidence's
`stagedGeneration` by the CHECK above, never the evidence taken on faith beyond that equality. A
`restart` resolution's `resolution_outcome = 'applied'` — a value the enum permits for either
operation, since `resolution_outcome` is not itself operation-typed — **raises no apply-generation
watermark and moves no fence**: `raiseWatermark`'s existing `operation = 'apply'` gate is the correct
behaviour and stays; what `BE-D17` removes is the meaningless number a restart resolution used to
have to manufacture to satisfy the old, operation-blind shape. A restart's `resolved_by` /
`resolution_outcome` / `resolution_observed` remain exactly what §5.3's audit union reads — this
amendment changes their *shape*, not their status as the DP's audit evidence for the restart verb.

This document states the invariant and its intended CHECK; it does not itself change §3.1's fenced
sketch, the `RequestState`/`Resolution` types, or any migration — that is the implementing amendment's
task, next in this repair wave. See [`decisions.md`](decisions.md)'s `BE-D17` and its changelog entry.

⚠️ **Amendment `BE-D18` (2026-09-09, design-first repair wave R5) — resolution evidence is
element-complete, not merely evidence-shaped; the container check has no element floor.** A fourth
external review measured that `resolution_is_evidence`, even after `BE-D17`, validates only the
evidence **container**: an apply resolution with `resolution_observed = {"stagedGeneration": <the
request's own generation>, "replicas": [{}]}` and a restart resolution with
`resolution_observed = {"restartsObserved": [null]}` are both **accepted** by PostgreSQL today — the
non-empty-array clauses require an array with at least one element, and say nothing about what that
element is. Because `sm_dp_runtime` holds column-scoped `UPDATE` on `resolution_observed` (§3.1's
grant, unchanged by this amendment), a direct SQL statement, a migration defect, or a future
repository bypass can write a generation-matching apply resolution with **no replica reference, no
restart observation and no hash**, which `OutcomeService.resolveIndeterminate`'s caller then treats
as grounds to raise the watermark (§2.2 rule 1) — an audit record asserting an observation that never
happened. §3.1's own SQL comment ("Replica/observation-ELEMENT typing stays the engine's typed
resolver… this CHECK is evidence-shaped, deliberately not claimed evidence-complete") and this
section's mirroring prose above are the posture this amendment overturns for the element and key-set
questions specifically — **flagged, not edited: both are inside text this round does not touch**
(§3.1's is inside the protected sketch; this section's own restatement is left as written per `I3`) —
the implementing amendment corrects both, in the same edit that lands the CHECK, exactly as
`BE-D16`/`BE-D17`'s implementing tasks corrected their own predecessor prose.

**The ruling: PostgreSQL JSONPath enforcement at the database boundary, not a revoked grant.** Between
the two options the defect admits — (a) extend the CHECK with `jsonb_path_exists` predicates over the
element and key shape, or (b) revoke `sm_dp_runtime`'s `UPDATE` on `resolution_observed` and force
every resolution through a routine — the developer ruling is (a). (b) was rejected here, not measured
against: it would relocate the enforcement point outside the column-scoped-grant model §3.1 already
uses for every other write surface on this table (`resolution_note`'s own residual-table row, §3.1, is
the same idiom: a grant that admits exactly the shape callers may write), trading one clean mechanism
for a second one solely for this column.

**Immutability gate, measured (developer, PG 14.24, `postgres:14`, container removed) — the
precondition for using these functions in a CHECK at all.** From `pg_proc`:
`jsonb_path_exists(target, path, vars, silent)` → `provolatile = 'i'`; the `@?` operator
(`jsonb_path_exists_opr`) → `i`; `jsonb_path_match` → `i`; the `@@` operator (`jsonb_path_match_opr`)
→ `i`. **Every `_tz` variant is `s`** (stable, not immutable) — which is exactly why none is used
here. A `CREATE TABLE … CHECK (coalesce(jsonb_path_exists(d, '$…'), false))` was accepted by DDL on
this measurement.

**The throw hazard, found and closed — `silent => true` is load-bearing, not a style choice.**
`.keyvalue()` **throws** (`jsonpath item method .keyvalue() can only be applied to an object`) on a
non-object array element, even in `lax` mode (the default, and the mode every path below uses
implicitly) — measured. The four-argument form of `jsonb_path_exists`, with its fourth argument
`silent => true`, suppresses the throw and returns `false` instead. This is the same hazard class
§3.4's own earlier correction already removed twice in this CHECK (the `::numeric` cast,
`jsonb_array_length`) — a future editor "simplifying" any predicate below back to the two-argument
form reintroduces exactly that throw, on exactly the malformed input the predicate exists to refuse
cleanly rather than crash the statement on.

**The `observedHash` key is now mandatory on an `apply` replica element — its *value* stays
nullable, its *presence* does not.** `Evidence.writeApply` (`backend/dp-core/.../persistence/
Evidence.java`) always emits the key, as `node.putNull("observedHash")` when the hash is unavailable —
so `{"ref": "r1", "restarted": false}` (no `observedHash` key at all) is a shape this build never
produces, and accepting it at the database would admit a second wire representation for the one model
value `ApplyResolutionEvidence`'s nullable `observedHash` already expresses with the key present and
`null`. It is also, precisely, the "missing required observation" this amendment exists to reject.
The element well-formedness predicate below therefore requires `exists(@.observedHash)` in addition to
the `ref`/`restarted` checks; the separate hash-*format* predicate (unchanged) still governs what the
key's value may be when it exists. Measured (developer, PG 14.24), against the well-formedness clause
("key-required") and the hash-format clause ("value") separately:

| element | key-required predicate | value predicate |
|---|---|---|
| `{"ref":"r1","restarted":true,"observedHash":null}` | accept | accept |
| `{"ref":"r1","restarted":true,"observedHash":"sha256:0123456789abcdef"}` | accept | accept |
| `{"ref":"r1","restarted":false}` — key absent | **reject** | (n/a) |
| `observedHash:"nope"` / `observedHash:7` | accept | **reject** |
| `[{}]` / `[null]` | **reject** | (n/a) |

A restart element carries no `observedHash` axis at all — `RestartObservation` has no such component,
`Evidence.writeRestart` never writes the key, and the restart branch's element key-set predicate
already refuses one if present — so the restart branch's well-formedness predicate takes no
`exists()` clause; nothing about the restart shape changes.

**The predicates — six for `apply`, four for `restart` (two and one of them added in R5 fix round 1,
below), each `AND`-ed onto the existing generation-equality and non-empty-array clauses of its own
`CASE` branch (§3.4 above), each measured working (developer, PG 14.24):**

```
-- apply branch, in addition to the existing generation-equality and non-empty-array clauses
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*] ? (!(@.type() == "object" && @.ref.type() == "string" && @.ref like_regex "\\S"
                        && @.restarted.type() == "boolean" && exists(@.observedHash)))',
    '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*].keyvalue() ? (@.key != "ref" && @.key != "restarted" && @.key != "observedHash")',
    '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*].observedHash.type() ? (@ != "null" && @ != "string")', '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*].observedHash ? (!(@.type() == "null" ||
        (@.type() == "string" && @ like_regex "^[a-z0-9]+:[0-9a-f]{16,128}$")))', '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.keyvalue() ? (@.key != "stagedGeneration" && @.key != "replicas")', '{}'::jsonb, true), false)

-- restart branch, in addition to the existing key-absence and non-empty-array clauses: the same
-- element-type, element-well-formedness and key-set shape, minus the hash axis entirely (no
-- observedHash type-check or format clause: a restart element has no observedHash field to require
-- at all — an observedHash key on a restart element is refused by the element key-set below, which
-- is what makes "a restart observation has no observedHash" a DB-enforced fact, not merely a
-- Java-typed one), over '$.restartsObserved[*]', element keys limited to {ref, restarted}, top keys
-- limited to {restartsObserved}
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.restartsObserved[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.restartsObserved[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                                 && @.ref like_regex "\\S" && @.restarted.type() == "boolean"))',
    '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.restartsObserved[*].keyvalue() ? (@.key != "ref" && @.key != "restarted")',
    '{}'::jsonb, true), false)
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.keyvalue() ? (@.key != "restartsObserved")', '{}'::jsonb, true), false)
```

Each predicate asserts that **no element (or no top-level key) violates the rule**, deliberately not
that some element satisfies it — the same idiom the CHECK already uses for the non-empty-array
clauses' complement: a positive existential over the malformed shape, negated and `coalesce`d, so an
empty or all-conforming array is refused as "no violation found" rather than vacuously satisfying a
positive claim it never made. The two `.type()` predicates (three, counting the restart one) are the
**one exception to "filter over the malformed shape"** in this list — they filter over a plain type
name, not over the candidate item itself, and §3.4's fix-round correction below is exactly about why
that distinction is load-bearing.

**Full text, both branches, as the intended amended `resolution_is_evidence`:**

```
CONSTRAINT resolution_is_evidence CHECK ((state = 'INDETERMINATE_RESOLVED') = (
        resolved_by IS NOT NULL
        AND resolution_outcome IS NOT NULL
        AND coalesce(jsonb_typeof(resolution_observed) = 'object', false)
        AND CASE operation
            WHEN 'apply' THEN
                coalesce(resolution_observed -> 'stagedGeneration' = to_jsonb(generation), false)
                AND coalesce(jsonb_typeof(resolution_observed -> 'replicas') = 'array', false)
                AND coalesce(resolution_observed -> 'replicas' <> '[]'::jsonb, false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.replicas[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.replicas[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                        && @.ref like_regex "\\S" && @.restarted.type() == "boolean"
                        && exists(@.observedHash)))',
                    '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.replicas[*].keyvalue() ? (@.key != "ref" && @.key != "restarted"
                        && @.key != "observedHash")', '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.replicas[*].observedHash.type() ? (@ != "null" && @ != "string")',
                    '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.replicas[*].observedHash ? (!(@.type() == "null" ||
                        (@.type() == "string" && @ like_regex "^[a-z0-9]+:[0-9a-f]{16,128}$")))',
                    '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.keyvalue() ? (@.key != "stagedGeneration" && @.key != "replicas")',
                    '{}'::jsonb, true), false)
            WHEN 'restart' THEN
                NOT coalesce(jsonb_exists(resolution_observed, 'stagedGeneration'), false)
                AND coalesce(jsonb_typeof(resolution_observed -> 'restartsObserved') = 'array', false)
                AND coalesce(resolution_observed -> 'restartsObserved' <> '[]'::jsonb, false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.restartsObserved[*].type() ? (@ != "object")', '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.restartsObserved[*] ? (!(@.type() == "object" && @.ref.type() == "string"
                        && @.ref like_regex "\\S" && @.restarted.type() == "boolean"))',
                    '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.restartsObserved[*].keyvalue() ? (@.key != "ref" && @.key != "restarted")',
                    '{}'::jsonb, true), false)
                AND NOT coalesce(jsonb_path_exists(resolution_observed,
                    '$.keyvalue() ? (@.key != "restartsObserved")', '{}'::jsonb, true), false)
            ELSE false
        END))
```

**Two Critical escapes, found and fixed (R5 fix round 1, measured against the verbatim predicates
this section specified before this round).** An independent review measured that a **filter applied
to an array-valued candidate auto-unwraps in `lax` mode** — the mode every path above uses implicitly
— rather than testing the filter against the array as a single item: for `$.replicas[*] ? (predicate)`,
when a `replicas` element `@` is itself an **array** rather than an object, PostgreSQL unwraps `@` and
re-applies the filter to `@`'s own elements instead of to `@` as a whole; if `@` is empty, or every one
of its elements happens to satisfy the (negated) predicate, the filter reports **no violation** for
that candidate — even though `@` itself is not an object at all.

**Critical 1 — the well-formedness filter (including the prior round's `exists(@.observedHash)`
clause) never sees an array-valued `replicas`/`restartsObserved` element as malformed.** Measured,
accepted by the pre-fix-round CHECK: `{"stagedGeneration": <matching>, "replicas": [[]]}` (the pure
zero-observation write this amendment exists to close, surviving it); `[[],[]]`;
`[[{"ref":"r1","restarted":true,"observedHash":null}]]` (worse: `Evidence.readResolutionEvidence`
cannot even read this back — `node.path("ref").asText()` on an `ArrayNode` yields `""`, and
`ReplicaRef` throws on it); `[{<well-formed>}, []]`. The `restart` mirror: `{"restartsObserved":
[[]]}`, `[[{<well-formed>}]]`, `[{<well-formed>}, []]`.

**Critical 2 — the same unwrap escapes the hash-format predicate.** Measured, accepted:
`observedHash: []`, `observedHash: ["sha256:0123456789abcdef"]`, `observedHash: [null]` — none
expressible in Java (`observedHash` is a plain `String`, never an array), and all three would make
the reader throw.

**The fix: test the item's `.type()` directly, before any filter runs — not a filter over the item at
all.** `.type()` reports an SQL/JSON item's own type, including `"array"`, and — unlike a filter — it
does **not** auto-unwrap: `$.replicas[*].type()` yields the literal type name of `@` itself as a
plain string, even when `@` is an array. Filtering on *that string* (`?(@ != "object")`) filters a
scalar, never an array, so the auto-unwrap hazard cannot arise. Three predicates close both escapes —
`$.replicas[*].type() ? (@ != "object")`, `$.replicas[*].observedHash.type() ? (@ != "null" && @ !=
"string")`, and the `restart` counterpart `$.restartsObserved[*].type() ? (@ != "object")` — folded
into the predicate list and the full text above. Verified, zero regressions across 53 apply and 20
restart legs (measured against the corrected CHECK) — superseding this section's earlier 13/8 counts
below, which predate this fix and no longer describe the full predicate set.

**A measured trap: do not reach for `strict` mode to close this instead.** It is the natural
instinct, and it is a regression — though the mechanism, corrected here (R6), is not the one this
section first stated. Under `strict`, `@.ref` on an object that lacks `ref` is a structural error,
but a structural error inside a filter predicate does not propagate out of the filter: SQL/JSON path
filters evaluate to a three-valued logic (true / false / Unknown), a filter-predicate error yields
**Unknown**, and an item for which the filter is Unknown is simply **not matched** — no exception
ever reaches `jsonb_path_exists`, and `silent` (measured either way, PG 14.24) has nothing to swallow.
The call itself returns **`false`** outright — not NULL — because no element satisfied the (negated)
filter, which is exactly the same "no violation found" reading `coalesce` gives a real NULL, so the
practical conclusion is unchanged: `[{}]`, a missing `ref`, a missing `restarted`, **and an absent
`observedHash`** would all flip to ACCEPT under `strict`, exactly the malformations this amendment
exists to reject. `lax` mode, with the `.type()` predicates above doing the array-detection work a
filter cannot, is the correct and the only measured-safe choice — say this here so a future editor
does not "fix" the unwrap by reaching for `strict` and reopens a worse hole.

**Measured results (developer, PG 14.24, pre-fix-round counts — see the correction immediately
above for the current, superseding totals).** Apply branch, 13 legs: **two** legitimate shapes accept
(`observedHash` present-and-`null`, present-and-a-valid-hash — an absent key is no longer one of
them); each of eleven hostile legs is refused by exactly one predicate — `[{}]`, `[null]`, a missing
`ref`, a blank `ref`, a non-string `ref`, a non-boolean `restarted`, and **an absent `observedHash`
key** by element well-formedness (seven, not six); an extra element key by the element key-set; a
too-short hash and a non-string hash by the hash-format clause (an absent key is refused separately,
by element well-formedness — not "first": `AND`-order is irrelevant here as elsewhere, per item 4
below; proven with the present-`null` positive control above); an extra
top-level key by the top-level key-set. Restart branch, 8 legs, unaffected by the key-required change
(no `observedHash` axis exists there): the well-formed shape accepts; the same five malformations are
refused by element well-formedness; an `observedHash` key on a restart element is refused by the
element key-set; an extra top-level key is refused by the top-level key-set.

**Shape facts this amendment mirrors from the Java source, not from invention — the DDL must not be
stricter than the type that already writes this column, and must not be more lenient about presence
than that type already is.**
- `Evidence.writeApply` writes `node.putNull("observedHash")` — a **JSON `null`, always present as a
  key** — whenever `ReplicaObservation.observedHash()` is `null`, and a real hash string otherwise; it
  never omits the key. The key-required well-formedness clause and the value-format clause together
  mirror exactly that: the key must exist, and when it does its value must be `null` or a matching
  hash string.
- `ValueGrammar.HASH = ^[a-z0-9]+:[0-9a-f]{16,128}$` (`{16,128}`, not `+`) — the hash-format
  predicate's regex is copied from this pattern verbatim, not re-derived.
- `ReplicaRef` validates **only non-blank** (`Objects.requireNonNull` plus `value.isBlank()`) — no
  character grammar, and `ValueGrammar.VALUE` is deliberately **not** imposed on `ref` here: doing so
  would make the database refuse rows Java accepts, which is exactly backward for a mirror that must
  never be stricter than the type it mirrors. `like_regex "\\S"` (at least one non-whitespace
  character) is the DB-side statement of the same "non-blank" rule `isBlank()` states in Java — **not
  equivalent, corrected here (R5 fix round 1): one-sided containment, not equivalence.** Enumerated
  over U+0001–U+FFFD (developer, second measurement pass): Java's `Character.isWhitespace`
  recognizes every character PostgreSQL's `\s` class does, **plus four more** — U+001C–U+001F. A
  `ref` consisting solely of one of those four: Java's `isBlank()` calls it blank (the constructor
  throws), while PG's `\S` — which never matches those four — calls the same string non-blank (the
  DDL accepts it). **The containment is one-sided and safe in the direction that matters:** PG's
  whitespace class is a proper subset of Java's, so the DDL is never *stricter* than `ReplicaRef` (it
  never refuses a `ref` Java would accept) — in this one edge case it is more *lenient* than Java, not
  equivalent to it. This is also **locale-dependent**: PostgreSQL's regex character classes resolve
  via the server's collation (typically libc `iswspace`); under the `C` locale specifically, PG's
  whitespace class collapses to the six ASCII whitespace characters, still a subset of Java's, so the
  safe direction holds regardless of locale even though the exact excluded set does not.

⚠️ **This "safe direction" conclusion is incomplete — flagged, not edited in place (`I3`; superseded by
the `BE-D19` amendment at the end of this section).** It reasons only about the WRITE direction: a
`ref` the DDL accepts that Java's constructor would reject. It never asks the converse, and the
converse is real: `sm_dp_runtime` holds column-scoped `UPDATE` on `resolution_observed` (§3.1's grant,
unchanged by this amendment), so a raw write reaches this CHECK without ever passing through
`ReplicaRef`'s constructor. A `ref` consisting solely of one of the four excluded codepoints
(U+001C–U+001F) is therefore **accepted and committed** here, and then **throws** in
`Evidence.readResolutionEvidence` (`new ReplicaRef(...)` → `IllegalArgumentException`, since that
constructor's `isBlank()` check calls the very same codepoint blank): a poison row, written and then
unreadable — the same class `BE-D18` closed for element *shape*, left open for `ref` *content*.
Measured (an external review, PG 14.24, cited by `BE-D19`): PG's `\S` is `TRUE` for codepoints 28–31.
The corrected invariant is one shared, locale-independent grammar on both sides, not a one-sided
containment — see the `BE-D19` amendment below.

**Preserved semantics — all four mandatory, restated precisely against the correction above:**
1. `ApplyResolutionEvidence.observedHash` **remains nullable** — a present key with a JSON `null`
   value is accepted by the hash-format clause exactly as before. What changes is a second,
   independent axis: the *key's presence* is now mandatory too (the well-formedness clause's
   `exists(@.observedHash)`), closing the "missing required observation" case §3.3's obligation (j)
   already names by number, above. Nullability of the value and mandatoriness of the key are
   orthogonal, and this amendment tightens only the second.
2. A restart observation has **no** `stagedGeneration` and **no** `observedHash` — the former is
   `BE-D17`'s existing `jsonb_exists` clause, untouched; the latter is now additionally DB-enforced,
   not merely Java-typed, because the restart element key-set predicate refuses any element carrying
   an `observedHash` key, and no `exists()` requirement is added for a field the shape never has.
   **Correction, R6 fix round 1:** "untouched" describes the clause's *text*, not its *effect* — this
   amendment's own top-level key-set predicate (predicate 10) now refuses any restart document
   carrying a `stagedGeneration` key at all, for the independent reason that `stagedGeneration` is not
   `restartsObserved`, so `jsonb_exists`'s own refusal is subsumed and no leg can pin it any more
   (measured; kept as defence-in-depth, not because any test still exercises it alone).
3. Apply generation equality remains the **request-row** comparison
   (`resolution_observed -> 'stagedGeneration' = to_jsonb(generation)`, `BE-D17`'s clause) — this
   amendment adds no second comparison and does not touch it.
4. Every clause added here is **NULL-safe** (`coalesce`d against a NULL `jsonb_path_exists` result)
   and **refuses with the named CHECK, never throws** (`silent => true` converts every internal
   evaluation error — e.g. `.keyvalue()` applied to a non-object array element — into that same NULL,
   rather than propagating `jsonpath item method .keyvalue() can only be applied to an object`; no
   cast, no `jsonb_array_length`, no other throwing operand is introduced) — consistent with, and not
   a fifth exception to, the three throw-freedom corrections this section's `BE-D17` text above
   already made. **Corrected here (R5 fix round 1): the dominant source of that NULL is not a NULL
   `target`** (a case the surrounding `AND`'s other clauses already exclude, and which this section
   said, incorrectly, was the reason for the `coalesce`) **— it is `silent => true` suppressing an
   internal structural error**, measured: `jsonb_path_exists('{"replicas":[5]}',
   '$.replicas[*].keyvalue() ? (…)', '{}', true)` returns **NULL** (the same call without `silent`
   raises `jsonpath item method .keyvalue() can only be applied to an object`). `coalesce(…, false)`
   therefore converts *"this predicate could not be evaluated on this document"* into *"accept"* for
   that one clause — a **load-bearing fail-open**, safe only because some other, independent clause
   is relied on to reject the same malformed input by a path that does not hit the same error. **This
   makes clause independence a correctness requirement, not an implementation detail:** the element
   key-set predicate's `.keyvalue()` call fails open exactly this way on a non-object `replicas`
   element, and is safe only because the element-type predicate (`$.replicas[*].type() ? (@ !=
   "object")`, added this fix round) independently rejects that same element through `.type()`, which
   cannot throw on any input and therefore never needs to fail open. Removing the type predicate
   without re-auditing every other clause's fail-open reliance would silently reopen exactly the hole
   this fix round closes. Because no predicate here can throw regardless of operand shape,
   `AND`-evaluation order remains irrelevant to correctness for this CHECK, as it was after `BE-D17`'s
   corrections — this amendment does not reopen that question, it extends the same guarantee to ten
   clauses, not seven.
5. **A second clause with the identical permanently-unpinned property, pre-existing and not
   introduced by this amendment (noted here, in R7 of the repair wave, for completeness):**
   `jsonb_typeof(resolution_observed) = 'object'` (§3.1's top-level type guard, above the operation
   `CASE`) is also measured unpinnable — on a non-object `resolution_observed`, the `->` operator
   yields SQL NULL for `stagedGeneration`, `replicas` and `restartsObserved` alike, which the apply
   generation-equality clause or the restart is-array clause each refuse on their own; no leg can
   isolate this clause alone, with or without `BE-D18`'s ten predicates in play. This was already
   true under `BE-D17` — `BE-D18` neither causes nor fixes it. Kept as defence-in-depth, not because
   any test still exercises it alone.

**Key-exactness — the layer that holds it changes; flagged, not silently reversed.** `BE-D17`'s own
"Key-exactness" paragraph above states plainly that neither of its clauses tests for extra top-level
keys and that "key-exactness is the **Java seam's** job… not the database's." This amendment's
top-level and element key-set predicates make that no longer true for **this table's raw-SQL
boundary**: after `BE-D18`, an unexpected key at either level is refused by name at the database, not
only structurally excluded by a typed Java writer. This is **not** a reversal of `BE-D17`'s statement
about where key-exactness lived at the time it was written — it is superseded in part, by
cross-reference, exactly as `BE-D17` itself superseded part of `BE-D13` (register table,
`decisions.md`). The Java seam does not stop enforcing it: `ApplyResolutionEvidence`/
`RestartResolutionEvidence`'s exhaustive record shape and `Evidence.writeApply`/`writeRestart`'s fixed
field lists mean the Java write path was already, and remains, incapable of emitting an extra key —
this amendment adds a second, independent line at the boundary a Java-level guarantee cannot reach: a
write that never goes through Java at all.

**One Java change is required — decided here, not left to the implementing task.** Every predicate
this amendment adds at the SQL boundary is already mirrored, unchanged, by code that landed under
`BE-D17` — `ReplicaRef`'s non-blank check, Java's `boolean restarted` component, `ValueGrammar.HASH`
inside `ReplicaObservation`'s constructor, and the closed-record/fixed-writer shapes that make an
extra key unrepresentable — with **one exception, on the read side only**:
`Evidence.readResolutionEvidence` currently reads
```
JsonNode hash = node.path("observedHash");
…
hash.isNull() || hash.isMissingNode() ? null : hash.asText()
```
which collapses "key present, value `null`" and "key entirely absent" into the same Java `null` —
tolerating, on read, exactly the shape `Evidence.writeApply` never writes and this amendment's own
well-formedness predicate now refuses at the database. The implementing task must change this
`isMissingNode()` branch from a silent `null` coercion into a **refusal** — the same exception shape
`Evidence.read()` already throws elsewhere in this class, **named here so two implementers do not
each invent a different one**: `throw new IllegalStateException("sm_data." + column + " does not
hold JSON this build wrote")`, with `column` bound to `"resolution_observed"` at this call site,
mirroring the "corruption report, not a caller-recoverable failure" posture that exception already
carries (no cause attached, no value quoted, `I6`) — while the `isNull()` branch continues to map to
Java `null` exactly as today. **No existing test depends on the current coalescing** — the change is
additive, not a behavior any passing test today relies on. This is the **one** Java-side
consequence of `BE-D18`; every other axis of this method's existing leniency (a missing `ref`, a
non-boolean `restarted`, read via `.path(...).asText()`/`.asBoolean()` defaults) is untouched by this
decision and is not, by this decision, ruled on either way. **Three boundary negatives this ruling
requires, not merely implies, none discharged by this task** (restated from §3.3's obligation (j)
extension above, so the requirement is visible from both anchors): a Java-level negative proving the
reader refuses a `replicas` element missing `observedHash`; a raw-SQL negative proving
`resolution_is_evidence` refuses the same shape by name, beside a positive control carrying
`observedHash: null`; and a DDL-replay leg for this exact case, recorded in the same
`--record-candidate` pass as this amendment's other new legs.

**Landing mechanism — the same ruling as `BE-D16`'s and `BE-D17`'s, restated for this addition.**
`resolution_is_evidence` is created inside `apply_request`'s own `CREATE TABLE`, in
`V002__apply_request.sql` — the file `SketchMirrorTest`'s `assertEquals(6, files.size(), …)` and
`assertEquals(25, phase1Statements().size(), …)` fix in shape and count. **Ruling: the implementing
amendment appends these ten predicates to the existing `resolution_is_evidence` CHECK body, in
`V002` and the design's §3.1 SQL block, to identical text, in place — it does not add a later
`ALTER TABLE` migration.** This is an edit to the text of an existing constraint clause inside the
existing `CREATE TABLE` statement, adding no statement and removing none, so it carries none of the
file/statement-count consequences a new migration would; `BE-D16`/`BE-D17`'s identical note about the
`6`/`24`/`7` counts and the per-file statement vector applies here too. Per the plan's own
Prerequisites, repeated a third time because it is no less true for a third amendment to the same
constraint: *"no database carries these tables yet, so a defect in a migration is fixed by amending
the design's SQL block and the migration together… rather than by a down-migration, and that posture
must be revisited by the first plan that ships to a live database"* — the moment any `sm_data` install
exists outside a test container, this amendment's landing mechanism, like its two predecessors', must
become a real migration with its own validation story, not an in-place edit.

**The DDL-replay evidence obligation — stated, not discharged, exactly as `BE-D16`'s and `BE-D17`'s
were.** [`evidence/ddl-replay/design-block.json`](evidence/ddl-replay/design-block.json) pins §3.1's
own SQL-block md5s; `replay.py` fails loudly the moment `V002`'s CHECK text changes underneath it. The
same procedure applies, at the same place —
[`evidence/ddl-replay/README.md`](evidence/ddl-replay/README.md) §"When the design's SQL changes" —
with new legs for each of the fifty-three apply-branch and twenty restart-branch cases above (a
refusal by name and a positive control, the `(j/I6)` template — including the array-escape legs the
fix round added and the three boundary negatives named just above), in one `--record-candidate`
pass, one reviewed diff, one `../ddl-execution-pg14.md`
update, one changelog row. **This task (R5) touches no SQL and runs no harness** — the tracked
baseline is deliberately left exactly as it stands today (`NO DRIFT` against the current,
pre-`BE-D18` `V002`, per the R4 re-record), and it will read as a legitimate red gate the moment the
next task edits `V002` to add these predicates — that redness is expected, not a defect this task
leaves behind, exactly as `BE-D16`'s R1 text stated for itself.

This document states the invariant, its intended CHECK text, its Java-side disposition (one required
reader change, above) and where it lands; it does not itself edit the SQL block, `V002`, the
DDL-replay baseline, or any Java file — that is the implementing task's, next in this repair wave. See
[`decisions.md`](decisions.md)'s `BE-D18` and its changelog entry.

**`BE-D19` amendment — `ReplicaRef` and `resolution_is_evidence` must enforce the same grammar, not a
one-sided containment.** A post-handover external review measured, deterministically, on live
PostgreSQL 14.24, that the "safe direction" conclusion flagged above is wrong: `sm_dp_runtime`'s
column-scoped `UPDATE` on `resolution_observed` lets a raw write commit a `ref` the DDL's
`like_regex "\\S"` accepts and `ReplicaRef`'s `isBlank()` constructor would reject — a lone
U+001C–U+001F codepoint is the measured case — and that row is then unreadable
(`Evidence.readResolutionEvidence` throws). `\s`/`\S` is additionally **locale-dependent** (PostgreSQL
resolves it via the server's collation, typically libc `iswspace`), so retuning the regex to chase
Java's exact whitespace set is not a stable fix even setting the write-direction gap aside. **The
binding acceptance criterion this amendment exists to satisfy, stated as its purpose:** the
raw-runtime path — a write through `sm_dp_runtime`'s existing grant, not only a write mediated by
`ReplicaRef`'s constructor — must not be able to create a row that `Evidence.readResolutionEvidence`
rejects on read.

**R1 fix round — `ValueGrammar.VALUE` was proposed in this amendment's first pass and is now
WITHDRAWN, disqualified by direct proof, not merely re-labelled a preference.** Runtime refs are
**infrastructure identifiers, not display labels**: the grammar must reject line breaks, C0 control
characters, whitespace-only values and unconstrained Unicode/CJK, while preserving every valid
identifier the current and near-term adapter runtimes (Docker, systemd, Kubernetes) actually use, plus
the `@placement` sentinel. `ValueGrammar.VALUE = ^[A-Za-z0-9._:/@+-]{1,128}$` fails the preservation
half on two independently disqualifying facts, each verified against primary documentation (`I2`), not
recalled:
- **Length.** Kubernetes object names used by Pods, Deployments and Services follow the **RFC 1123
  DNS subdomain** form: *"contain no more than 253 characters… contain only lowercase alphanumeric
  characters, '-' or '.'"* — verified by fetching
  [kubernetes.io/docs/concepts/overview/working-with-objects/names](https://kubernetes.io/docs/concepts/overview/working-with-objects/names/)
  on 2026-09-09. `VALUE`'s `{1,128}` bound refuses a legal 200-character pod name outright, and
  `ReplicaRef`'s own javadoc already lists *"a pod name"* as a documented current use, not a
  hypothetical.
- **Backslash.** systemd's unit-name grammar states *"the 'unit name prefix' must consist of one or
  more valid characters (ASCII letters, digits, ':', '-', '_', '.', and '\\')"* and separately *"the
  total length of the unit name including the suffix must not exceed 255 characters"* — verified by
  fetching [man7.org/linux/man-pages/man5/systemd.unit.5.html](https://man7.org/linux/man-pages/man5/systemd.unit.5.html)
  on 2026-09-09. `systemd-escape` maps an arbitrary string (a device or mount path) into a unit name
  using `\xNN`-style escapes — a path containing a space becomes a unit name containing a literal
  backslash — so an escaped unit name is a legitimate `ref` value `VALUE`'s class cannot express at
  all (no `\` in `[A-Za-z0-9._:/@+-]`), independent of length.

Both facts disqualify `VALUE` outright; the correct response is not to compromise it into something in
between, but to **define a dedicated explicit grammar** and justify it against the same primary
sources, which this fix round does.

**The ruling: a new, dedicated grammar — `ValueGrammar.REPLICA_REF` — not a widened `VALUE`.**
`VALUE`/`HASH` stay exactly as they are: `ValueGrammar`'s own javadoc states they are `I6` shape
controls specifically *because* they "cannot express `=`, a space, a quote, a backslash or a newline,"
and `RuntimeRef.of`/`ErrorClass` depend on that exclusion to keep secret-shaped content unrepresentable
in `runtime_ref`/`error_class`. Admitting backslash into a grammar `ReplicaRef` alone uses — rather than
widening the shared `VALUE` constant every other I6-sensitive column also uses — keeps that exclusion
intact everywhere it still matters. The new constant, as a bracket expression:

```
^[A-Za-z0-9._:/@+\\-]{1,255}$
```

(`A-Z`, `a-z`, `0-9`, `.`, `_`, `:`, `/`, `@`, `+`, one escaped literal backslash, a trailing literal
hyphen; length `{1,255}`) — verified, not merely derived, by direct experiment in this session
(2026-09-09) against a throwaway `postgres:14` container (`PostgreSQL 14.24 (Debian 14.24-1.pgdg13+2)`,
per `SELECT version()` — matching this project's own pinned floor and citation convention) and against
a standalone `java.util.regex.Pattern` compiled with OpenJDK. Both engines were run against the
identical 17-case matrix below and produced identical accept/refuse results for every case:

| case | value (abbreviated) | len | PG 14.24 | Java `Pattern` |
|---|---|---|---|---|
| plain identifier | `cache-engine-1` | 14 | accept | accept |
| sentinel | `@placement` | 10 | accept | accept |
| Docker image digest shape | `sha256:` + 64 hex | 71 | accept | accept |
| Docker container-name shape | `my_container.01-test` | 20 | accept | accept |
| systemd escaped-name fragment | `foo\x2dbar.mount` (one literal `\`) | 16 | accept | accept |
| Kubernetes-shaped long subdomain | 200-char lowercase/`-`/`.` string | 200 | accept | accept |
| length boundary | 255×`a` | 255 | accept | accept |
| bare backslash | `\` | 1 | accept | accept |
| empty | `""` | 0 | refuse | refuse |
| whitespace-only | three spaces | 3 | refuse | refuse |
| embedded newline (plain) | `a`, U+000A, `b` | 3 | refuse | refuse |
| lone U+001C | — | 1 | refuse | refuse |
| lone U+001F | — | 1 | refuse | refuse |
| CJK | `日本語` | 3 | refuse | refuse |
| hostile-ref control | `r1 "q" \ = café` | 15 | refuse | refuse |
| hostile-ref newline control | `r1` + U+000A + `with-a-newline` | 17 | refuse | refuse |
| over length | 256×`a` | 256 | refuse | refuse |

**Why this specific class, cited against the same primary sources, item by item:**
- **Docker container names.** Verified against `daemon/names/names.go` in `moby/moby` (fetched
  2026-09-09; Docker's CLI reference does not itself state a naming grammar, so the engine source is
  the primary source used): `RestrictedNameChars = "[a-zA-Z0-9][a-zA-Z0-9_.-]"` — letters, digits, `_`,
  `.`, `-` — a strict subset of `REPLICA_REF`'s class.
- **Docker image digests and repository references.** Verified against `regexp.go` in
  `distribution/reference` (fetched 2026-09-09, the canonical grammar Docker itself uses for image
  references): the digest form is `[A-Za-z][A-Za-z0-9]*(?:[-_+.][A-Za-z][A-Za-z0-9]*)*[:][[:xdigit:]]{32,}`
  (an algorithm name, `:`, then hex) and the tag form is `[\w][\w.-]{0,127}` — both fit inside
  `REPLICA_REF`'s class and length; the `:` this needs was already in `VALUE`'s class too, so this is
  confirmation, not a new character.
- **systemd unit names.** Verified against `systemd.unit(5)` (man7.org, fetched 2026-09-09, quoted
  above): letters, digits, `:`, `-`, `_`, `.`, `\` for the raw prefix, plus a single `@` for the
  template/instance separator (*"a template unit must have a single '@' at the end of the unit name
  prefix"*), max 255 characters total — `REPLICA_REF`'s class and `{1,255}` bound match this exactly,
  not merely accommodate it.
- **Kubernetes object names.** Verified against
  [kubernetes.io/docs/concepts/overview/working-with-objects/names](https://kubernetes.io/docs/concepts/overview/working-with-objects/names/)
  (fetched 2026-09-09, quoted above): RFC 1123 subdomain names (lowercase alphanumeric, `-`, `.`, max
  253) and RFC 1123 label names (lowercase alphanumeric, `-`, max 63) are both subsets of
  `REPLICA_REF`'s class and within its 255-character bound. A `namespace/name` combined form (needing
  `/`) is not used by any code or design text today — `/` is retained in the class because it was
  already in `VALUE`'s (Docker repository path components use it too), not because a combined form is
  adopted; flagged as unused, not decided.
- **The `@placement` sentinel.** `ReplicaRef.PLACEMENT = new ReplicaRef("@placement")` matches
  trivially (letters and `@`, both in the class).
- **PostgreSQL escaping facts the SQL-side text depends on**, each verified against primary
  documentation rather than assumed: `standard_conforming_strings` defaults to `on` since PostgreSQL
  9.1, so backslashes inside an ordinary single-quoted SQL string constant are **not** processed as
  escapes and pass through literally (verified against
  [postgresql.org/docs/14/sql-syntax-lexical.html](https://www.postgresql.org/docs/14/sql-syntax-lexical.html),
  fetched 2026-09-09) — the outer SQL string wrapping the whole jsonpath expression adds no escaping
  layer of its own; and the SQL/JSON Path Language's own string-literal rule states *"any backslashes
  you want to use in the regular expression must be doubled"* because `like_regex`'s pattern argument
  is itself a jsonpath string literal (verified against
  [postgresql.org/docs/14/functions-json.html](https://www.postgresql.org/docs/14/functions-json.html),
  fetched 2026-09-09) — the same rule the design's own pre-existing `\\S` text already relies on,
  restated here because this amendment leans on it for a literal-backslash **class member**, not only
  a metacharacter escape.

**Future Unicode / display-name support is explicitly out of scope for v1 and is not this amendment's
concern.** `REPLICA_REF` is an infrastructure-identifier grammar; nothing in it is intended to admit a
human-chosen display label carrying non-ASCII text. Any such support is a separate design amendment,
should the need arise, and is not implied or half-built by this one.

**What stops being accepted, relative to today — re-verified against the empirically-measured class
above, not adjusted from the withdrawn draft's summary.** Exactly two literals in
`SchemaConstraintsIT.everyLegitimateApplyReplicaShapeIsAcceptedByResolutionIsEvidence`
(`backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SchemaConstraintsIT.java`) flip from
accepted to refused, confirmed against the *widened* `REPLICA_REF` class exactly as they were against
the withdrawn `VALUE`-based draft — re-read, not merely carried over:
1. `"r1 \"q\" \\ = café"` — measured refused (table above, "hostile-ref control" row): a space, two
   double-quotes, `=` and the accented Latin `é` are still outside the class even though the same
   literal also carries a backslash, which the class now admits — the OTHER characters are what
   refuse it.
2. `"r1\nwith-a-newline"` — measured refused (table above): the embedded newline (U+000A) is not in
   the class.

No other `ReplicaRef`/`"ref"` literal anywhere else in `backend/dp-core/src/test` uses anything outside
`REPLICA_REF`'s class (verified by search, as for the withdrawn draft) — every other positive control
(`"r1"`, `"r2"`, `"cache-engine-1"`, `"cache-engine-2"`, `@placement`) is unaffected. **Nothing the
withdrawn `VALUE`-based draft would have accepted becomes newly refused under `REPLICA_REF`** —
`REPLICA_REF`'s class is `VALUE`'s class plus one escaped backslash, and its length bound (255) is
strictly wider than `VALUE`'s (128) — so this correction only *widens* what a `ref` may contain,
relative to the design's own first-pass draft; it narrows nothing further relative to today's
`isBlank()`. No DDL-replay leg exists for `ref` content today (verified by search of
`evidence/ddl-replay/legs.json`), so this amendment's new legs (below) are additions, not edits to a
tracked one. `apply_step_intent.replica_ref` carries no character-content CHECK at all today — only
the `placement_scoped_steps` biconditional at the bottom of `apply_step_intent`'s `CREATE TABLE`
references the column, and only to test equality against the `'@placement'` sentinel, never its
shape (`text NOT NULL`, `V004__apply_step_intent.sql:8`) — and **this repair widens that sibling gap,
measurably, rather than merely leaving it flagged as out of scope.** The mechanism is the same
three-part chain `BE-D19` itself closes on `resolution_observed`, reproduced on a second column: a
bare, CHECK-free column; `V004__apply_step_intent.sql:26`'s `GRANT SELECT, INSERT ON
sm_data.apply_step_intent TO sm_dp_runtime`, which lets the runtime role write any `text` into it with
nothing at the SQL layer to refuse a hostile value; and `StepIntentRepository.java:74`'s read-back,
`new ReplicaRef(rs.getString("replica_ref"))`, which runs the exact constructor this amendment
tightens. **Before** this amendment, that constructor's only gate is `isBlank()`, so the poison set on
this column is "blank or whitespace-only." **After** it, the same constructor enforces
`ValueGrammar.REPLICA_REF` (`^[A-Za-z0-9._:/@+\\-]{1,255}$`), so the poison set enlarges to everything
outside that class — `"cache engine 1"` (a space), `"café"` (non-ASCII), a CJK string, or a
256-character value all become raw-insertable through the existing `INSERT` grant and unreadable the
next time `StepIntentRepository`'s row mapper runs. That is `BE-D19`'s own defect class — a raw write
PostgreSQL accepts and Java refuses to read back — reproduced on `replica_ref`, not a new one.

**Sized, and deliberately deferred — not silently left flagged.** Two facts keep this out of
`BE-D19`'s own scope rather than folding it in. First, `BE-D19`'s binding criterion is
`resolution_is_evidence` / `Evidence.readResolutionEvidence` — the `resolution_observed` jsonb path
specifically — never `apply_step_intent`; widening it here would be scope creep into a column this
decision never priced. Second, `apply_step_intent` is immutable by grant
(`V004__apply_step_intent.sql:26`'s own comment, `-- IMMUTABLE: no UPDATE/DELETE`): the runtime role
can only `INSERT` a row once, never rewrite one, so the poison-row path requires a bypass at insert
time (a CP-side validation defect, a migration defect, or a future repository bypass) rather than the
standing, repeatable `UPDATE` surface `resolution_observed` has today. That distinction makes deferral
defensible; it does not make the gap absent. Closing it needs its own amendment — most naturally a
character-content CHECK on `apply_step_intent.replica_ref` reusing `ValueGrammar.REPLICA_REF`'s own
pattern, since the column holds the same adapter-vocabulary values — and this document does not draft
one. The ruling above (a new, dedicated `REPLICA_REF` grammar, not a widened `VALUE`) stands
unchanged; only this record of what it leaves open does.

**The exact DDL text the implementing task transcribes — one predicate, one substring, both operation
branches, appended nowhere new.** `resolution_is_evidence`'s element well-formedness predicate already
contains the substring `@.ref like_regex "\\S"` once per branch (`V002__apply_request.sql` lines 94 and
118, and the design's own §3.1 SQL block above, inside the protected fence this amendment does not
touch). The intended replacement — **empirically measured working on PostgreSQL 14.24 in this session,
not derived by reasoning alone**, per the two-layer escaping citation above (jsonpath string-literal
doubling; the outer SQL string layer adds none) — is that exact substring, and only that substring, in
both occurrences. The source text needs **exactly four consecutive backslash characters** at the point
representing the one literal backslash class member — two layers of doubling (jsonpath-string, then
regex-bracket-escape), each doubling once:

```
@.ref like_regex "\\S"
```
becomes (four backslash characters between `+` and `-`, exactly as measured):
```
@.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$"
```

so the apply branch's well-formedness clause reads (surrounding text unchanged):

```
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.replicas[*] ? (!(@.type() == "object" && @.ref.type() == "string"
        && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"
        && exists(@.observedHash)))',
    '{}'::jsonb, true), false)
```

and the restart branch's:

```
AND NOT coalesce(jsonb_path_exists(resolution_observed,
    '$.restartsObserved[*] ? (!(@.type() == "object" && @.ref.type() == "string"
        && @.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$" && @.restarted.type() == "boolean"))',
    '{}'::jsonb, true), false)
```

No predicate is added or removed, and the surrounding `AND`-chain, `coalesce`, `silent => true` and
throw-freedom properties this design already established for this exact clause are untouched — this is
a literal-text substitution inside an existing, already-measured-safe predicate, not a new one. Because
no statement is added or removed, this edit carries none of the file/statement-count consequences a
new migration would (`SketchMirrorTest`'s `6`/`24` counts, unaffected, exactly as `BE-D16`/`BE-D17`/`BE-D18`'s
own in-place text edits were) — an implementing-task fact, not asserted as executed by this commit.
**S2 must still re-run this exact text against a live PostgreSQL 14 container before landing it** —
this session's measurement used an isolated, throwaway container and a hand-built fixture set, not
`V002` itself; transcription is a fresh opportunity to get a backslash count wrong, and "measured once,
elsewhere" does not substitute for measuring the actual landed text.
**Landed (S2 of this repair wave, commit `4ecf5e7`):** the transcribed text was re-measured against a
live PostgreSQL 14.24 container and a standalone `java.util.regex.Pattern`, and the backslash count was
verified byte-by-byte in both source contexts — 4 in `V002__apply_request.sql` at both branches, 4 in
`ValueGrammar.java`. An independent re-review then ran 92 adversarial values through the real CHECK and
the real `ReplicaRef` constructor under `lc_collate = en_US.UTF-8`: zero disagreement.

**The exact Java change — one class, two methods, decided here so two implementers do not each choose
differently.** `ValueGrammar.java` gains a third constant, alongside `VALUE`/`HASH`, with its own
javadoc stating plainly that it is deliberately wider than `VALUE` (admits a literal backslash) and is
`ReplicaRef`'s grammar alone — never a substitute for `VALUE` on `RuntimeRef`/`ErrorClass` or any other
I6-sensitive column, which must keep excluding backslash:

```java
/**
 * Infrastructure-identifier grammar for {@link ReplicaRef} alone: Docker container names and
 * image digests, systemd unit names (including escaped names, which may carry a literal
 * backslash), and Kubernetes RFC 1123 label/subdomain names (up to 253 characters), verified
 * against each runtime's own naming rules (BE-D19) — not {@link #VALUE}, which every OTHER
 * I6-sensitive column keeps using precisely because it excludes backslash.
 */
static final Pattern REPLICA_REF = Pattern.compile("^[A-Za-z0-9._:/@+\\\\-]{1,255}$");
```

(the Java source text also needs exactly four backslash characters at that position — one doubling for
the Java string literal, one for the regex engine's own bracket-escape — empirically confirmed in this
session via a standalone `Pattern.compile` run producing `.pattern() == "^[A-Za-z0-9._:/@+\\-]{1,255}$"`,
which matched every case in the table above identically to PostgreSQL). `ReplicaRef`'s compact
constructor (`backend/dp-core/src/main/java/com/solace/sm/dp/apply/model/ReplicaRef.java:16-21`)
changes from:

```java
public ReplicaRef {
    Objects.requireNonNull(value, "replica ref");
    if (value.isBlank()) {
        throw new IllegalArgumentException("replica ref must not be blank");
    }
}
```

to:

```java
public ReplicaRef {
    Objects.requireNonNull(value, "replica ref");
    ValueGrammar.require(ValueGrammar.REPLICA_REF, value, "a replica ref",
            "an infrastructure identifier — Docker/systemd/Kubernetes-shaped (BE-D19), not merely "
                    + "non-blank");
}
```

`ValueGrammar` is package-private in the same package as `ReplicaRef`
(`com.solace.sm.dp.apply.model`), so no visibility change is needed; `ValueGrammar.require` already
refuses without echoing the value (`I6`), so the refusal message stays value-free. No test in this
suite pins the old literal message text `"replica ref must not be blank"` (verified by search), so
this is a behavior-visible change (what is accepted) with no pinned-message regression. This is now
enforced identically wherever a `ReplicaRef` is constructed — `Evidence.readResolutionEvidence` (both
the `apply` and `restart` branches), `StepIntentRepository`'s read-back, and `ReplicaRef.PLACEMENT`
itself — because it is the one constructor every call site shares. `ValueGrammar`'s class-level
javadoc ("the two character grammars…") must be updated to "three" in the same edit, since it states a
count.

**Executable-DDL and Java prove-it-fires obligations — extending obligation (j) a third time (§3.3
above), by name, not by prose. One standing rule applies to every leg below and to any future one: a
test result must never be filed as an environmental flake without first explaining, in the leg's own
commentary, why the observed production behavior could not have caused it — the same failure mode
that let this defect's own earlier "safe direction" claim stand unchallenged.**
1. **The two parity negatives that motivated this amendment, at raw SQL**: a `resolution_observed`
   apply document whose `replicas[0].ref` is a lone U+001C is **refused** by `resolution_is_evidence`
   (named), beside the positive control that the same document with `ref = "cache-engine-1"` is
   accepted; repeated for a lone U+001F, and for the restart branch (`restartsObserved[0].ref`) in both
   cases. These are the legs the write-direction gap this amendment closes did not have before.
2. **The same two parity negatives, at Java**: `new ReplicaRef(` + a lone U+001C + `)` and the U+001F
   equivalent both throw `IllegalArgumentException` (true both before and after this change, for
   different reasons — `isBlank()` today, the character class after — so this leg proves it is STILL
   true post-change, not a new refusal).
3. **The disqualifying cases that ruled out `VALUE`, now proven ACCEPTED, at both Java and raw SQL**: a
   Kubernetes-shaped identifier longer than 128 characters (e.g. 200), and a systemd-escaped-name
   fragment carrying a literal backslash — both must be accepted, named as the two cases this fix
   round exists to admit, not merely as generic positive controls.
4. **The two flipped legs, both directions, re-verified against the new class**: `SchemaConstraintsIT`'s
   café literal and newline literal (named above) move from that test's accepted assertions to refused
   ones — both `new ReplicaRef(...)` at Java and `resolution_is_evidence` at raw SQL must refuse each,
   named, with the unaffected literals (`"r1"`, `"r2"`, `@placement`) kept as the surviving positive
   controls in the same test.
5. **Empty and whitespace-only, at both Java and raw SQL**: `new ReplicaRef("")` and a three-space
   `ref` both refused, beside a CJK literal (e.g. a Japanese-script string) refused at both — the three
   cases the developer's rejection list names by category, each proven by name rather than assumed
   from the character class alone.
6. **The sentinel and both length boundaries**: `ReplicaRef.PLACEMENT` continues to construct without
   throwing (positive control); a 255-character `ref` is accepted and a 256-character one is refused
   at both Java and raw SQL (the `{1,255}` bound, exercised at both edges, not merely asserted).

**The DDL-replay evidence obligation — stated, not discharged, exactly as `BE-D16`'s, `BE-D17`'s and
`BE-D18`'s were.** This commit changes no SQL and runs no harness: `evidence/ddl-replay/legs.json` and
`design-block.json` are untouched, the tracked baseline stays exactly as recorded against `BE-D18`
(commit `de8a6aa`), and a fresh `replay.py` run against the current, unmodified `V002` reads `NO DRIFT`
— this amendment's own commit is doc-only and does not need re-verifying that fact for it to hold.
Once the implementing task transcribes the predicate-text change above into `V002` and the design's
§3.1 SQL block, [`evidence/ddl-replay/README.md`](evidence/ddl-replay/README.md) §"When the design's
SQL changes" applies in full: run the harness, add the obligations above as new legs (a refusal by
name beside a positive control, the `(j/I6)` template), record with `--record-candidate`, review every
reported difference (the `V002`/Phase-1 md5 changes; no phase-file statement or command-tag count
changes, since no statement is added or removed), copy the candidate files over the tracked ones, and
add that task's own changelog row — one commit, per the README's step 5. That task's run will,
expectedly, go red the moment it touches `V002`, exactly as `BE-D16`'s R1 text and `BE-D18`'s own
landing note both already state for themselves; this design-only commit leaves that gate exactly where
it stands today (green, `NO DRIFT`), not red.

**Landing mechanism — the same ruling as `BE-D16`'s, `BE-D17`'s and `BE-D18`'s, restated for this
fourth amendment to the same CHECK.** The implementing task edits `V002__apply_request.sql` and the
design's §3.1 SQL block to identical text, in place — the substring substitution above, in both
branches — and edits `ValueGrammar.java` (the new `REPLICA_REF` constant and its javadoc, plus the
class-level "two"→"three" count) and `ReplicaRef.java` (the constructor) to the Java text above, in the
same commit. No down-migration, no new migration file, per the Prerequisites language
`BE-D16`/`BE-D17`/`BE-D18` each already carried and repeat here a fourth time because it is no less
true: *"no database carries these tables yet, so a defect in a migration is fixed by amending the
design's SQL block and the migration together… rather than by a down-migration, and that posture must
be revisited by the first plan that ships to a live database."* This design document does not itself
edit the SQL block, `V002`, `ValueGrammar.java`, `ReplicaRef.java`, or the DDL-replay baseline — that is
the implementing task's, next in this repair wave. See [`decisions.md`](decisions.md)'s `BE-D19` and
its changelog entries (including the R1 fix round recorded above).

**Landed (S2 and S3 of this repair wave, commits `4ecf5e7`, `0fd35c8`, `edb87e1`):** the predicate text
is in `V002__apply_request.sql` (md5 `a4e9829d…` → `c02ca25e…`) and mirrored byte-identically into the
§3.1 SQL block; `ValueGrammar.REPLICA_REF` and `ReplicaRef`'s constructor carry the Java side. The
DDL-replay obligation above is **discharged, not still pending**: `legs.json` and `design-block.json`
did change, at `edb87e1`, and the baseline is no longer `BE-D18`'s — see `BE-D20`'s own closing
annotation below for the combined re-record, since both amendments were recorded in one pass.

**`BE-D20` amendment — at most one OPEN attempt per intent, fenced by a partial unique index, not a
numbering coincidence.** §2.2 rule 0 above states apply state is *"persisted in the DP before any
remote action"* and that *"the intent row, and the attempt row for the try about to run… are committed
before the adapter is invoked"* — language that implicitly assumes exactly one attempt is "the try
about to run" at any moment. `AttemptService.open`
(`backend/dp-core/src/main/java/com/solace/sm/dp/apply/engine/AttemptService.java:74-103`) counts open
rows and inserts `attempt_no = count + 1`; the only DDL fence today is `UNIQUE (intent_id, attempt_no)`
(`V005__apply_attempt.sql:31`, and the design's own §3.1 SQL block above, inside the protected fence
this amendment does not touch), which refuses a duplicate **only when two openers compute the identical
number**. A post-handover external review measured, deterministically (no race needed), on live
PostgreSQL 14.24: once attempt 1 **commits**, a second caller reads `count = 1`, inserts
`attempt_no = 2`, and that insert is **accepted** — no violation, two rows with `outcome IS NULL` for
one intent, and (per §2.2 rule 4's "bounded retry" and rule 0's "write before act," both of which this
breaks) two adapters can act on the same step under the belief each is the authoritative try. The same
review measured that a partial unique index `ON (intent_id) WHERE outcome IS NULL` **refuses** the
second open, and that the legal retry after closing attempt 1 **is still allowed** — the fence
distinguishes "two attempts open at once" from "one attempt, then another, in sequence," which is
exactly the distinction `UNIQUE (intent_id, attempt_no)` alone cannot draw.

**The ruling: a second, independent partial unique index, named `attempt_open_fence`, alongside the
existing numbering UNIQUE — not a replacement for it.** This mirrors §3.3's own three existing fences
(`apply_inflight_fence`, `stop_inflight_fence`, `staged_fence`) in mechanism: a `WHERE`-qualified unique
index over the column that must be singular among open rows.

**The exact DDL text the implementing task transcribes.** One new statement, landing in
`V005__apply_attempt.sql` immediately after its existing `CREATE INDEX attempt_prune ON
sm_data.apply_attempt (tenant_id, started_at);` (line 33) and before that file's `GRANT` (line 35), and
at the corresponding point in the design's §3.1 SQL block above (immediately after the same
`CREATE INDEX attempt_prune` line):

```sql
CREATE UNIQUE INDEX attempt_open_fence ON sm_data.apply_attempt (intent_id)
    WHERE outcome IS NULL;
    -- BE-D20: at most one OPEN attempt per intent, regardless of attempt_no — closes the gap
    -- UNIQUE (intent_id, attempt_no) leaves open (a sequential second open, no numbering collision
    -- at all). A legal retry stays legal once the prior attempt is closed (outcome IS NOT NULL).
```

**Unlike `BE-D16`/`BE-D17`/`BE-D18`'s in-place text edits, this is a NEW statement, stated so it is not
mistaken for the same zero-statement-count landing pattern.** `24` appears **four times** in
`backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/SketchMirrorTest.java`, not once,
and this amendment's one new statement moves all four, not the single assertion that happens to fail
first: the class javadoc's own measured count (line 27, *"measured 7 Phase-0 and 24 Phase-1
statements"*), `theStatementCountsMatchTheMeasuredPg14EvidenceRun`'s assertion itself (line 56,
`assertEquals(24, phase1Statements().size(), …)`), and that same assertion's failure message, twice
over (lines 57-58, *"Phase 1 is the 24 statements … executed, plus nothing: SET ROLE is Flyway
initSql and is counted in the 24"*). All four sites' `24` becomes `25`; `SketchMirrorTest`'s file
count (`6`) is unaffected — the statement lands inside the existing `V005` file, it does not add a
seventh migration. The implementing task must update all four sites, not merely re-run the one
assertion. **Confirmed NOT to move, a different `24` naming a different object entirely:**
`RefusalShapesIT.theDesignsOwnNamedConstraintsAreAllPresent`'s count, at `RefusalShapesIT.java:173`
(its query read in full at lines 162-177), counts `pg_constraint` rows with `con.contype = 'c'` —
CHECK constraints only — and a plain `CREATE UNIQUE INDEX … WHERE …` is recorded by PostgreSQL in
`pg_index`/`pg_class`, never in `pg_constraint`, so `attempt_open_fence` leaves that assertion at
`24`, unaffected; the two `24`s name unrelated object sets and must not be conflated. Any OTHER
hardcoded object-count assertion this repository carries for `sm_data.apply_attempt`'s object set is
the implementing task's own audit — not enumerated here as exhaustive, because this amendment does
not run the test suite.

**How the engine tells the two conflicts apart — by name, not by behavior; both are named, neither is
conflated with the other.** *(Scoped to this amendment's own two database fences — a later fix layers a
third, pre-insert path onto `AttemptService.open` that also raises `ConcurrentAttempt` but names no
PostgreSQL constraint at all, so "both are named" describes these two, not every provenance the shipped
method can raise; see [`decisions.md`](decisions.md)'s `BE-D20` row.)* `AttemptService.open`'s catch clause
(`Constraints.ATTEMPT_NO_UNIQUE`, currently the only name it checks) is extended to also name
`Constraints.ATTEMPT_OPEN_FENCE` (a new constant this amendment adds to
`backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/Constraints.java`, string value
`"attempt_open_fence"` — PostgreSQL's own name for a plain `CREATE UNIQUE INDEX`, exactly as the three
existing fence constants already are): `e.namesAnyOf(Constraints.ATTEMPT_NO_UNIQUE,
Constraints.ATTEMPT_OPEN_FENCE)` replaces the current single-name check, `status.setRollbackOnly()`
and the `ConcurrentAttempt` refusal are unchanged. Both names currently produce the **same** caller-facing
outcome, deliberately: in both cases the correct engine action is identical — do not act, resume will
read the row that won. But the two conflicts are **not the same conflict**, and this amendment states
the distinction explicitly so a future editor does not read the extended `namesAnyOf` call as one
broadened check:
- `ATTEMPT_NO_UNIQUE` fires **only** when two openers compute the identical `attempt_no` — a genuine
  same-number race at `READ COMMITTED`, and it is the ONLY one of the two names that can ever fire for
  that scenario's loser if the winner's row has not yet committed when the loser's insert runs.
- `ATTEMPT_OPEN_FENCE` fires whenever the intent already has an open row (`outcome IS NULL`),
  **regardless of what number the new insert computed** — it is strictly broader, and it is the
  **only** one of the two names that can fire for `BE-D20`'s own defect (a sequential second open with
  a genuinely different, non-colliding `attempt_no`, since `ATTEMPT_NO_UNIQUE` cannot be violated when
  the two numbers differ).
- For the genuine-race case, which of the two names PostgreSQL actually reports for the loser is **not
  asserted here** — this design does not invent an unmeasured fact about multi-index conflict-check
  ordering the way it would not invent one about `like_regex` locale behavior. The implementing task's
  concurrent-callers test (below) must measure and record, by name, which constraint(s) are actually
  observed across repeated runs — exactly as §2.2 rule 2 already does for the analogous ambiguity
  between the two `apply_step_intent` UNIQUEs — and `AttemptService.open` must accept either name for
  that reason, not just the one this design happens to expect.

**Executable-DDL and Java prove-it-fires obligations — a new obligation (v), naming both the sequential
and the concurrent case §3.3's obligation list did not previously have to distinguish:**
1. **Sequential second-open, deterministic, no race at all**: open attempt 1 for an intent (do not
   close it); a second `open()` call for the same intent is refused with `ConcurrentAttempt`, and the
   underlying `PgConstraintViolation` names `attempt_open_fence` (not `apply_attempt_intent_id_attempt_no_key`
   — the two numbers differ, `1` and the next one the count-based insert would compute, so only the
   fence can fire). This is a raw-SQL leg too (a plain second `INSERT` with `outcome IS NULL`, same
   `intent_id`, a different `attempt_no`, refused by the fence's name, beside the positive control that
   the first `INSERT` succeeds) — added to `evidence/ddl-replay/legs.json`, single-session, following
   the existing `(j/I6)` template; no live two-session leg is required for this case, consistent with
   this design's own precedent that neither the pre-existing attempt-number race nor the intent-key race
   has one either (both are proven JUnit-only).
2. **The retry-after-close positive control, in the same test**: close attempt 1 (`outcome` set,
   `WHERE outcome IS NULL` in `AttemptRepository.close` now excludes it from the fence); a subsequent
   `open()` for the same intent **succeeds** as attempt 2 — the measured fact that the fence does not
   forbid legitimate sequential retries, only concurrent/duplicate open ones.
3. **Concurrent-callers, deterministic regardless of collision** — replacing, not merely re-running,
   the two existing non-deterministic barrier tests this defect explains:
   `AttemptIT.twoConcurrentOpensForOneIntentLandExactlyOneAttemptAndTheLoserIsToldSo` and
   `EngineTransactionBoundaryIT.aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`
   both currently assert exactly one attempt row lands with commentary stating the test "proves
   nothing" and must be re-run if the two openers serialize onto different numbers instead of colliding
   on the same one (verified by reading both files: `AttemptIT` line 208's assertion message and
   `EngineTransactionBoundaryIT` line 185's identical pattern). Once `attempt_open_fence` lands, that
   caveat is no longer true: exactly one attempt row lands **regardless of whether the two callers
   collide on the same `attempt_no` or serialize onto different ones**, because the loser's insert now
   fails the fence even when its own number is unique. Both tests' "or this test proves nothing about
   the race" language must be removed as obsolete, not merely left alongside a now-guaranteed outcome,
   and each must additionally assert, by name, which constraint(s) the loser's `PgConstraintViolation`
   carried (per the classification note above, not asserted here as one fixed name). *(This describes
   `AttemptService.open` as this amendment itself leaves it — every loser refused by inserting. A later
   fix gives `open()` a pre-check that can refuse a loser before any insert, with no
   `PgConstraintViolation` and no name at all; the two tests named above must accept that outcome too,
   not only a named fence. See `decisions.md`'s `BE-D20` row.)*
4. **A non-deterministic result from any of the three tests above (1–3) is never to be recorded as a
   flake unless the reachable production behavior behind it is explained** — this is the standing
   rule `BE-D19` states generally, restated here in `BE-D20`'s own terms because this is the decision
   that exists *because* the rule was broken: `EngineTransactionBoundaryIT`'s intermittent failure was
   filed as "a third flake source, environmental," on the strength of being green on re-run and
   appearing on a commit that touched no Java, and nobody asked why a race test could legitimately
   produce two successes — that question is the missing fence. Once these three tests are
   deterministic (regardless of thread timing, by construction), a failure in any of them again is
   evidence of a regression in the fence, not noise to re-run past.

**Watch item — the fence converts a transient, self-healing clock glitch into a permanently wedged
intent; priced here, not a reason to withhold the fence.** Both methods that bracket every adapter
call stamp their timestamps from the JVM, not the database: `AttemptService.open`'s `Instant now =
clock.instant()` (line 83) becomes `started_at`, and `AttemptService.close` (lines 118-119) passes
`clock.instant()` through to `AttemptRepository.close(…, Instant endedAt)`, which writes
`ended_at = :ended`. `attempt_time_ordered` (`V005__apply_attempt.sql:23`,
`CHECK (ended_at IS NULL OR ended_at >= started_at)`) refuses the row the moment those two clock
reads disagree, and two reachable paths make them disagree with no bug in this design's own code:
- **Cross-node.** After a failover, the node that closes an attempt is not always the node that
  opened it. Two hosts' wall clocks can differ by tens of milliseconds under healthy NTP, and by an
  unbounded amount if NTP is broken on either host — a fast step opened on one node and closed on a
  lagging one can write `ended_at < started_at`.
- **Single-node.** `Clock.systemUTC()` is a wall clock, not `nanoTime` — `chronyc makestep`,
  `ntpd -g`, a VM snapshot restore, or a manual `date -s` can step it backwards inside one JVM
  between the two reads, with no second node involved at all.

**Why this is a design item and not an ops note.** Today, a `close` refused by
`attempt_time_ordered` leaves the attempt row open (`outcome IS NULL`) and does nothing else — and
defect B, the count-based numbering gap `BE-D20` exists to close, lets the *next* `open()` for that
intent still land. The wedged-open row is therefore a transient, self-healing artifact today: the
intent keeps making progress under a new attempt number, and only the diagnostic journal carries the
scar. Once `attempt_open_fence` lands, that self-heal is exactly what the fence removes: the
wedged-open row now permanently satisfies the fence's `WHERE outcome IS NULL`, so **every**
subsequent `open()` for that intent refuses with `ConcurrentAttempt` — forever, until a human writes
an `outcome` onto that row by hand. `BE-D20` does not introduce the clock disagreement; it changes
its consequence from "one wasted attempt number" to "the intent is stuck." *(A later fix, outside
this decision, narrows the consequence one step further: before it, a wedged intent AT the cap
boundary still surfaced as `RetryCapExceeded` — the classification pointing a caller at
`rollbackOrMarkIndeterminate` — because the cap check ran ahead of any open-attempt check; after it,
the open-attempt check runs first, so that same wedged intent is `ConcurrentAttempt` even at the cap
boundary. That is the required classification, not a defect — but it removes the last path by which
a wedged intent could still reach the exhaustion path a human operator might otherwise have used to
notice it, so a human writing the missing `outcome` by hand is now the ONLY way out at any attempt
count, not only below the cap.)*

**Not this decision's to fix.** The candidate remedies — `greatest(started_at, :ended)` in
`AttemptRepository.close`'s `UPDATE`, moving `ended_at` to a database-clock default (`now()` at the
SQL layer rather than a bound JVM `Instant`), or relaxing `attempt_time_ordered` to tolerate a
bounded skew — are each a design choice with its own trade-offs (silently rewriting a
caller-supplied timestamp; giving up the JVM-clock precision the rest of §2.2 assumes; or admitting a
magic tolerance constant nothing today justifies), and each requires its own decision. **S2 must not
improvise one of these while landing `attempt_open_fence`.** None of them changes the ruling below:
the fence is strictly better than no fence — it closes a deterministic, always-reproducible defect
(defect B) in exchange for exposing a pre-existing, clock-dependent one that today merely wastes an
attempt number — so `BE-D20` proceeds as specified.

**Measured build state at this task's base commit (`e577f2a`) — the strongest evidence this decision
has, not incidental noise.** An independent review's own `mvn clean verify` run at that commit went
**BUILD FAILURE**:
`EngineTransactionBoundaryIT.aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`
failed at line 185 (`expected: <1> but was: <2>`) — both callers succeeded: caller 1 committed
`attempt_no = 1`, caller 2 then read `count = 1` and inserted `attempt_no = 2`, violating nothing at
all under today's `UNIQUE (intent_id, attempt_no)` alone. Surefire **273 / 0 / 0 / 0**; failsafe
**176 total / 174 executed / 2 skipped**, **1 failure**. This is defect B — the exact gap `BE-D20`
exists to close — reproducing live at HEAD, not test flakiness, and per obligation 4 above it must
never be filed as one: the failure is a second attempt landing beside an already-committed first, on
a live PostgreSQL 14.24, with no fence to stop it. **The S1 report's earlier green run of the same
test was a lucky interleaving, not a property of the code** — the same test, run again, now fails;
nothing about the production path changed between the two runs, only which of the two callers' reads
happened to observe the other's commit first.

**The DDL-replay evidence obligation — stated, not discharged, exactly as `BE-D16`'s, `BE-D17`'s and
`BE-D18`'s were, and as `BE-D19`'s above.** This commit changes no SQL and runs no harness: the tracked
baseline stays exactly as recorded against `BE-D18` (commit `de8a6aa`), unaffected, and a fresh
`replay.py` run against the current, unmodified `V005` reads `NO DRIFT`. Once the implementing task
adds the `CREATE UNIQUE INDEX attempt_open_fence` statement to `V005` and the design's §3.1 SQL block,
[`evidence/ddl-replay/README.md`](evidence/ddl-replay/README.md) §"When the design's SQL changes"
applies in full — and this time step 1's warning that "each difference must be the intended
consequence" bites harder than `BE-D19`'s edit: because a STATEMENT is added, not just a literal
swapped inside one, the harness will additionally report a changed statement/command-tag count for the
Phase-1 group (one more `CREATE INDEX` tag) alongside the `V005`/Phase-1 md5 change — both expected,
both reviewed, neither silently accepted. Add the two new sequential legs above, record with
`--record-candidate`, review, copy the candidate files over the tracked ones, and add that task's own
changelog row — one commit, per the README's step 5. This design-only commit leaves the gate exactly
where it stands today (green, `NO DRIFT`).

**Landing mechanism.** The implementing task adds the `CREATE UNIQUE INDEX attempt_open_fence`
statement to `V005__apply_attempt.sql` and the design's §3.1 SQL block, at the point named above; adds
`Constraints.ATTEMPT_OPEN_FENCE` to `Constraints.java`; extends `AttemptService.open`'s
`namesAnyOf` call to include it; and replaces the two non-deterministic tests as specified in
obligation 3 above — all in the same commit, since the index, the constant, the engine classification
and the test replacement are one coherent change with no defensible intermediate state (a landed index
with no engine classification for it would make `ConcurrentAttempt` stop firing correctly for the case
this amendment exists to fix). **This design document does not itself add the index, edit
`Constraints.java` or `AttemptService.java`, or touch either non-deterministic test — that is the
implementing task's, next in this repair wave.** The same live-database revisit obligation the
Prerequisites language already states for `V002` applies identically to `V005`: this in-place-migration
posture holds only while no `sm_data` install exists outside a test container, and must be revisited by
the first plan that ships to a live database. See [`decisions.md`](decisions.md)'s `BE-D20` and its
changelog entry.

**Landed (S2 and S3 of this repair wave, commits `e1b8490`, `0fd35c8`, `edb87e1`).** The index is in
`V005__apply_attempt.sql` (md5 `04914626…` → `628453bd…`) and mirrored into the §3.1 SQL block (phase 1
`a4d51cf2…` lines 266–610 → `366c7d84…` lines 266–615); `Constraints.ATTEMPT_OPEN_FENCE`,
`AttemptService.open`'s two-name `namesAnyOf` and the three corrected javadocs landed with it, and
`SketchMirrorTest`'s four `24` sites and per-file vector moved to **25** / `{1,6,9,2,4,2}`. **The
DDL-replay obligation stated above for both `BE-D19` and `BE-D20` is discharged at `edb87e1`,** and the
warning it gave was justified: the re-record reported eight differences, and only three were the
expected md5/tag/topology changes. The other five were **pre-emption of pre-existing legs** — the new
unique index is evaluated before the `AFTER ROW` FK trigger, so three FK legs reported
`attempt_open_fence` instead of their FK; the `(m) POS` "legal retry" leg flipped to refused because its
recorded sequence never closed attempt 1; and a duplicate-number leg reported the fence instead of the
numbering UNIQUE. All five were repaired to their byte-identical original recorded values by two
inserted `SETUP` close legs, so each still isolates the constraint its label names. `legs.json`
239 → 259 entries; a clean run reads `NO DRIFT`.

**Three javadoc sites go stale under this amendment and must be corrected in the same commit — S2's
obligation, matching `BE-D19`'s own "two"→"three" correction idiom, not discharged by this
document.** `AttemptService.java:29-33`'s class-level javadoc states the exact guarantee defect B
disproves: *"What does is `UNIQUE (intent_id, attempt_no)`: exactly one of them lands attempt
`n + 1`, the other's insert is refused with 23505 on `apply_attempt_intent_id_attempt_no_key`…"*.
`AttemptService.java:71-72`'s `@throws ConcurrentAttempt` doc reads *"when another opener landed
this attempt number first (the `(intent_id, attempt_no)` UNIQUE refused this one)"*.
`ConcurrentAttempt`'s own class javadoc, at `AttemptService.java:127`, reads *"A concurrent
`open()` for the same intent won the `(intent_id, attempt_no)` race."* All three name only the
numbering UNIQUE as the refusing mechanism; each must be corrected to also name
`attempt_open_fence`, since after this amendment lands either index can be the one that actually
refused the loser.

### 3.5 Vocabularies: execution steps vs the wire contract — one mapping, one owner

`apply_attempt.step` is an **execution** vocabulary internal to `sm_data`. The **wire** vocabulary
for rollout progress is the contract's and only the contract's:
`contracts/events/notify-events.schema.json` fixes `instance.rollout.payload.step` to
`[staged, restart-requested, restart-observed]` (check-10-gated; its canonical *home* is the open
`BE-D4` amendment item — `contracts/events/README.md` Known gap 2 — which this design feeds, not
forks). The DP owns the mapping:

| Execution fact (this design) | Wire event emitted |
|---|---|
| a replica's `MATERIALIZE` succeeded | `instance.rollout` `step: staged` **for that replica** — the contract requires `payload.replicaId` on every rollout event, so `staged` is per-replica by construction; "stage-everywhere complete" is simply the last of them |
| all targeted replicas persisted | the ladder's `instance.config-state` event, `persisted` rung (`stateVersion` = the generation) — **emitted for instance-wide requests only**: the contract event is instance-scoped, and two concurrent per-placement applies at different generations would race it (the envelope's monotonic rule would discard the lower and the instance ladder would over-claim). Per-placement progress rides the placement-scoped `instance.rollout` events; a per-binding ladder refinement is `BE-D4`-amendment material (§8 row 13) |
| `LIFECYCLE` issued for a replica | `instance.rollout` `step: restart-requested` for that replica |
| restart observed (baseline comparison via `status`, either locus) | `instance.rollout` `step: restart-observed` for that replica — and the ladder's `instance.config-state` `restart-observed` rung once all targeted replicas have it |
| — (not this engine's to emit) | the ladder's `rendered` rung is announced at render time (DP, pre-apply); `effective-verified` is `SM-D22`-bounded and permanently absent for some MIs — its event exists only where the rung is achievable |
| **terminal apply outcomes** (`APPLIED`, `FAILED_*`, `CONFIG_DIVERGED`, `APPLY_INDETERMINATE`…) | ⚠️ **no wire event exists for these in the v1 catalog — stated as a v1 limit, and routed.** The contract's rollout enum ends at `restart-observed`; an apply that fails emits nothing, so the "event-driven from day one" console learns failures only by REST refetch (its fallback poll and any screen it has open). A terminal-outcome notice (a rollout-step extension or a new event) is proposed into the same `BE-D4` amendment (§8 row 11); until it lands, the console's apply screen must poll the apply status — the one place `DM-D17`'s machinery is primary, not fallback |
| `PREFLIGHT` / `VERIFY` / `ROLLBACK` / `ROLLBACK_ASSESS` / `STOP` transitions | **no rollout event in v1.** If the console needs wire-visible rollback/verify progress, that is new catalog vocabulary and goes through the `BE-D4` amendment (Known gap 2's home), never through a second enum |

**Instance-wide applies emit placement-scoped events**: `instance.rollout` requires `placementId`
(Known gap 3 — rollout is placement-scoped by contract), so a nil-sentinel (instance-wide) request
emits one event stream **per affected placement** from its per-replica attempts. The apply engine is
the producer; the mirror-and-lock rules (`BE-D10`) apply to it like any other producer.

**Agent-report mapping (the other half of §2.4):** `host-local` agent outcomes (`AG-D5`:
`APPLIED`, `FAILED_NO_EFFECT`, `FAILED_ROLLED_BACK`, `SUPERSEDED`, self-resolving indeterminate) map
1:1 onto §3.2's terminal states at the *request* level; the agent's journal steps
(`RECEIVED/STAGED/PERSISTED/REPORTED`) are **not** written into `apply_attempt` — the DP records its
own protocol exchanges with the agent as attempts on the request instead. The agent's wire protocol
remains open item 6; this paragraph binds only what the DP does with reports once they arrive.

---

## 4. `BE-D14` — the live-console session contract

`BE-D5`/`BE-D6`/`BE-D7` already designed the mechanism; the G1 spike measured it. This section fixes
the **contract** — what a ticket is, what the browser does with it, what the frontend consumes —
and proposes the two postures that let the walking skeleton ship while open items 32/33 stay open — ⚠️ **with the
**project owner**, not the architect group (OR-1), and ⛔ **only posture (32) survives**: (33) is WITHDRAWN (§4.4).
**EDA is the design, not the aspiration:** events are change notices, REST is the
authoritative snapshot and backfill (`BE-D1`), and `DM-D17`-style polling exists only as the
degraded mode `BE-D6` already made it.

### 4.1 Ticket claims — fixed set, gateway-owned, audited

Every mesh ticket the gateway mints carries **at least** (the table is a floor, not a ceiling):

| Claim | Content | Rule |
|---|---|---|
| `iss` | the gateway (the one issuer the broker's OAuth profile trusts — `resourceServerRequiredIssuer`, validated by the broker per `BE-D5`/G1) | fixed at install |
| `aud` | the broker audience configured in the VPN's OAuth profile (`resourceServerRequiredAudience`) | validated by the broker; pins tickets to this install's mesh |
| `sub` | the **session's subscription identity** — an opaque, gateway-minted, per-console-session value | **The authorization decision** under candidate (a): `$client-username` substitutes it, so the session's readable stream branch is `sm/v1/<tenant>/stream/<sub>/…`. Gateway-owned end to end; never user-supplied text; **must not contain `/`** — documented by Solace and explicitly *unenforced by the broker* (G1 quotes the vendor doc verbatim), so the gateway enforces it with a test |
| `tenant` | server-derived tenant (`I5`) | never client-supplied; matches the topic-level literal the ACL enforces |
| `grant_id` | the stream grant(s) this ticket carries — **audit and entitlement bookkeeping, not the branch name, and the component that reads it is named**: the **DP tail publisher** consults the gateway-registered live grant set for the session before publishing any series to the session's branch (with its own refusal test — a series without a live grant is never published), and audit records correlate on these ids. The broker cannot read claims per-series (its exception is branch-wide) — the broker isolates sessions; the DP enforces grants | grants are per-chart, per-session, short-lived (`BE-D6`); a refreshed ticket accumulates the session's live grants, exactly `BE-D5`'s *"stream claims added per `BE-D6` grants"* model — **§4.5 explains why `sub`, not `grant_id`, names the branch** |
| `purpose` | `notify` (the session ticket; stream entitlement is discriminated by a non-empty `grant_id` set on the same ticket, not by a second value) | audit discriminator; kept as a claim so that, if scope-separated profiles are later measured per the spike's candidate-(b) note, per-purpose tickets can return without a claim-shape change |
| `exp` | ≤ 15 min from mint | the **only revocation lever** (G1: rotation is not revocation; `disconnectOnTokenExpirationEnabled` is a hard deadline) |
| `jti` | unique ticket id | the audit correlation key for mint/renew/revoke records |

The broker's validation of `iss`/`aud`/signature against the gateway's JWKS is `BE-D5`'s designed and
G1-measured mechanism, inherited here unchanged.

**Minting is RBAC-gated and audited.** `mesh.ticket.mint`, `mesh.stream.grant`,
`mesh.stream.revoke` — the three verbs already in the [`D10` amendment addendum](../D10-AMENDMENT-PROPOSAL.md)
— run through §5's `authorizationService.require(...)` like every other verb, and every mint, renewal
and revocation writes an audit record carrying `jti`, principal, purpose, tenant and grant ids.
**Revocation's real effect under the session-scoped branch (§4.5), stated identically here and in
the amendment paper's scope note:** revoking a stream grant removes it from the DP tail publisher's
live grant set, so the DP **stops publishing that series to the session's branch at its next publish
decision — effectively immediate, not TTL-bound** (under the grant-scoped model a first draft had,
TTL was the bound; the session-scoped branch moved the lever to the DP). What remains TTL-bounded is
the **session's mesh access as a whole**: revoking the session (deactivation, logout-force) stops
renewal, and the current ticket's subscriptions survive at most to `exp` (`BE-D5`'s stated limit).
The verb is still high-consequence (operator-on-operator); both bounds go in the catalog entry the
group ratifies, so the granted power and the delivered effect read the same.

### 4.2 Browser rules

- **Memory-only ticket storage.** The ticket lives in a closure/module variable inside the mesh
  layer — never `localStorage`, never `sessionStorage`, never a cookie. A page reload loses it and
  fetches a fresh one through the session; that is the design, not a bug (the session cookie is the
  durable credential; the ticket is 15-minute mesh currency).
- **Renewal on the gateway's clock, executed by the client, with the background-tab limit named.**
  G1's conclusion is verbatim *"the gateway must re-ticket proactively, on its own clock"*: the mint
  response therefore carries a server-computed `renewAfter`, and the client schedules against that
  value rather than deriving its own policy from `exp`. **Named limit:** a backgrounded tab's
  throttled timers can miss the renewal window regardless (G1 flags exactly this as unverifiable from
  Node), and the broker disconnects at `exp` with no grace and no usable reason — so the recovery
  path is part of the contract, not an afterthought: the wake-up lands in
  `onResync('recovered')` → fresh ticket → reconnect → REST backfill from `stateVersion` (below).
  Renewal is a **planned reconnect** (the SDK replaces the session; in-flight messages are lost;
  charts re-backfill — all per `BE-D5`/G1).
- **Expiry or any reconnect forces REST backfill from last `stateVersion`.** The envelope's
  monotonic per-entity `stateVersion` (`BE-D4`) is the watermark: on resync, refetch active
  queries and discard any notice not newer than the state already held. Missed events are healed by
  reading, never replayed (`BE-D1`).
- **The browser never publishes.** Enforced at the broker (`publishTopicDefaultAction: disallow`,
  **empty** publish exceptions) and asserted from SEMP **config** at provisioning — G1 measured that
  a publish denial is invisible to the client, so no client-side check can substitute. The frontend
  seam simply exposes no publish API.
- Connect retries are **bounded** (`connectRetries: 0` + app-level bounded retry with the degraded
  banner) — G1: the SDK otherwise retries a rejected login forever and the user is told nothing. The
  error-class asymmetry G1 measured is inherited as-is: subscribe denials are actionable
  (`SUBSCRIBE_ACL_DENIED`); every `401`/`AUTH_FAILED` refusal is one undifferentiated shape covering
  five causes (expired, unknown key, bad signature, wrong audience, unreachable JWKS), so the client
  never renders a cause it cannot know — it resyncs.

### 4.3 `LiveStateClient` — the frontend seam, part of the first design

The SPA consumes one abstraction, in the frontend's `shared/mesh` module (`BE-D7`'s confinement,
`FE-D16` pattern, guard + self-test), so reconnect/backfill discipline is built once:

```ts
interface LiveStateClient {
  /** Connect with a ticket supplier; the client owns renewal scheduling (per renewAfter) and reconnects. */
  start(tickets: TicketSupplier): void;
  stop(): void;
  /** live = mesh session up · degraded = polling fallback active (BE-D6/DM-D17) */
  readonly state: 'live' | 'degraded' | 'stopped';
  /** Notify events: change notices only. Handlers invalidate queries; they NEVER write data. */
  onNotify(handler: (n: NotifyEvent) => void): Unsubscribe;
  /** Chart tails: raw-tier, range-ends-now only (BE-D6). Many concurrent tails ride ONE session (§4.5). */
  openTail(grant: StreamGrant, sink: TailSink): TailHandle;
  /** Fired after every (re)connect, with the reason — the signal to refetch from stateVersion. */
  onResync(handler: (reason: 'connected' | 'renewed' | 'recovered') => void): Unsubscribe;
}
```

Contract points, binding on the implementing plan: notify handlers **invalidate TanStack Query keys
and nothing else** (`FE-D7`/`BE-D7`: events never write into the query cache; data always arrives by
REST); `openTail` appends only points newer than the backfill watermark and re-backfills on every
resync (gaps stay server-declared, `DM-D16`); `state` drives the visible *live / degraded-to-polling*
indicator; the generated `NotifyEvent` types come from the **AsyncAPI/event-schema half** of the
contracts (`backend/design.md` §12.1 item 6 — the Console OpenAPI is `FE-D7`'s REST half; the two
paths are distinct and separately gated), and the routed IEEE-754 `Long.MAX_VALUE` watch item in
[`common/contracts/backend-consumption.md`](../common/contracts/backend-consumption.md) §3 lands on
whoever generates them.

### 4.4 Tenancy postures for v1 — ⛔ proposal (33) WITHDRAWN 2026-09-16 (OR-5/OR-6); proposal (32) re-framed, not withdrawn

⚠️ **An earlier revision of this heading said "both proposals WITHDRAWN", and that over-closed one of them.**
Only **(33)** — tenant-wide notify visibility — is withdrawn; the note below withdraws it explicitly and OR-6's
taxonomy replaces it. **(32)** — *"the v1 walking skeleton is explicitly single-tenant"* — is **not** withdrawn:
OR-5 (one Message VPN per Organization) dissolves the *"multi-tenant composition of one profile"* framing but says
nothing against a single-organization v1 skeleton, so **(32) survives as an unratified proposal** and
[`../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md`](../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md)
correctly still rests on it as one. **Two different statuses; do not collapse them.**

⚠️ **Read with the heading above.** These two proposals were written to let the skeleton ship on the **measured**
mechanism while items 32/33 stayed open. ⛔ **Proposal (33) is now WITHDRAWN**, so only **(32)** still functions
that way; and 32/33/35 are the **project owner's** to close (OR-1), not the group's.

- **Proposal (32): the v1 walking skeleton is explicitly single-tenant.** One install, one tenant,
  one VPN — exactly the shape G1 measured (zero broker writes per grant, tenant edge enforced by the
  topic literal). Multi-tenant composition remains open item 32, **decided before any second tenant
  is onboarded**, not before the skeleton exists. This converts item 32 from "blocks the skeleton"
  into "blocks multi-tenancy" — which is what it actually is.
⛔ **Superseded, 2026-09-16 — read [`../DECISIONS.md`](../DECISIONS.md) rulings OR-5/OR-6 and open item 35 with this section.**
⛔ **Organization-wide notify visibility is NOT acceptable and proposal (33) below is not adoptable as written.**
The replacement architecture is decided: **one Message VPN per Organization** (on Solace Cloud, one broker service
per Organization unless a shared-service profile is explicitly approved), and **inside** the VPN,
sub-organization and environment isolation enforced **through topics and ACLs** — with those levels now present in
the taxonomy (OR-6). ⚠️ What is **not** decided is the ACL *behaviour*: the spike matrix in
[`../common/contracts/console-integration.md`](../common/contracts/console-integration.md) §11.3 is written and
**not executed**. Until it runs, live notify may not be enabled for any shared or customer install.

The historical framing follows.
Proposal (33) below was written when every principal was evaluated whole-install, so *tenant-wide notify* leaked
nothing a principal could not already read. The 2026-09-15 authorization **rejected** whole-install evaluation in
favour of scope-evaluated bindings with downward inheritance, which creates principals scoped below the
organization — and for those, tenant-wide notify **does** leak instance existence and change across an environment
boundary their REST scope enforces. The proposal is therefore **not** adoptable as written for v1; it is a
hard gate (open item 35), and `I6`'s value-exclusion bounds the leak without closing it.

- **Proposal (33): tenant-wide notify visibility for v1** — the spike result's own recommendation:
  the fallthrough profile carries `sm/v1/<tenant>/notify/>` alongside the substitution-scoped stream
  exception. Every in-tenant console session sees every in-tenant notify event; `I6` already bars
  values from notify payloads, so what leaks is existence and change, within the tenant, which under
  proposal (32) is this install's own operators. Stated as a limit in the install runbook.

**Flag (`I4`):** ⚠️ **(32) is still a proposal against an open item; (33) is WITHDRAWN** (see the §4.4 heading),
and the items are routed to the **project owner** (OR-1), not the architect group. The provisioning runbook
section that encodes the ACL cannot be *published* until the owner rules **and the §11.3 matrix has run**, but the
walking-skeleton plan can build against the single-tenant shape because it is the measured one.

### 4.5 Why the branch is session-scoped, not grant-scoped

A broker session authenticates **one** username, so under candidate (a) it can read **one**
substituted branch — the token's `sub`. If `sub` were the grant id, a dashboard with two live raw
charts would need two broker sessions, two websockets through the proxy, two renewal clocks and two
reconnect seams — contradicting `BE-D7`'s one-session shape. `design.md` §5 step 4's wording — *"a
refreshed token carrying that **one** stream claim"* — is the same one-session idea with a
grant-scoped singular this decision **amends to a plural** (grants accumulate on the session
ticket); flagged in [`decisions.md`](decisions.md)'s conflicts table rather than cited as if it
already said so. So: **`sub` names the session's branch**; the DP publishes each granted tail to
`sm/v1/<tenant>/stream/<sub>/<seriesKey>` (the taxonomy's existing level below the substituted one);
a new grant refreshes the ticket's `grant_id` set and the DP starts publishing that series under the
session's branch — no ACL change, no second session, many concurrent tails. ⚠️ **`BE-D4` names the
substituted level `{grantId}`** — in its decision body and in `contracts/events/asyncapi.yaml`'s
stream channel — so this is a level *rename* (`{grantId}` → the session's `sub`), flagged in
[`decisions.md`](decisions.md)'s conflicts table and **routed into the same `BE-D4` amendment that
`contracts/events/README.md`'s Known gaps already feed** (§3.5's precedent; §8 row 10). Nothing breaks
today — check 10 does not lock the address string and no Java deriver covers the stream tier — but
`contracts/` is normative and single-writer, so the rename lands as a reviewed amendment item, never
as a drive-by. **Costs, stated:** the
DP publishes one copy per *subscribed session* of a series (grants are per-chart per-session anyway,
so this fan-out is bounded by live viewers of that series); and within one session, tail isolation is
gateway/DP-enforced (only granted series are published to the branch) rather than broker-enforced —
the broker isolates *sessions* from each other, which is the `I5`-relevant boundary. This subsumes
what the G1 residual-exposure list already said: the entitlement line is the gateway; the broker is
the second line.

---

## 5. `BE-D15` — minimal v1 authorization: real semantics, small surface

`REQ-006` is v1-must; `D10` 🔒 fixes the model (deny-by-default, policy-as-code catalog); the
[`D10` amendment proposal](../D10-AMENDMENT-PROPOSAL.md) supplies the verb catalog and the three
open questions. This section designs the **machinery** so that approving the amendment makes the
console buildable immediately — and so that nothing here has to be rewritten for SSO, SCIM or custom
roles. **No fake auth, no placeholder semantics:** every rule below is the real one.

### 5.1 The model — `RoleBinding → Role → Permission`, nothing else

- **Permission**: an entry in the **code-owned catalog** — a checked-in enum/registry. Its members
  are the **33 grantable verbs plus the introspection pair = 35 catalog members**: `D10` 🔒's two
  existing grantable verbs (`instance.bindTarget`, `target.use` — which the amendment paper leaves
  *unchanged*, i.e. outside its 28), the amendment's 28, the mesh addendum's 3, and
  `authz.introspect.self`/`.any` (riding the amendment as its own addendum — §5.2).
  Deny-by-default is structural: a verb not in the catalog cannot be named in code, and a verb not
  granted through a binding is denied. The catalog is reviewed like a contract — adding a verb is a
  deliberate change with an owner, never a side effect.
- **Role**: a named set of permissions. **v1 ships built-in roles only** (defined in code beside the
  catalog), but they are **stored and evaluated as data**: a `role` row per built-in role, seeded at
  migration, marked `built_in = true` — and "immutable through the API" has an artifact, not a
  convention: by the §3.1 DCL pattern applied in `sm_control`, the CP runtime role holds
  `GRANT SELECT ON sm_control.role` **only** in v1 (rows are migration-seeded), with the
  runtime-write refusal test mirroring obligation (k). Custom roles later = granting the write path
  for non-built-in rows, a migration; zero evaluation changes. The v1 role *set* is proposed from the mockup's
  Admin-screen catalog (`frontend/reference/sm-v2/app/admin_data.jsx`) **as a proposal with a source,
  not as authority** — the repo's own rule is that a decision made inside a mockup is not a decision,
  so the set ratifies with the amendment.
- **RoleBinding**: `(principal_id, role_id, scope)` — ⛔ **`scope evaluated whole-install in v1` is RULED OUT
  2026-09-16.** The ratified amendment requires **canonical scoped bindings with downward scope inheritance**, so
  the binding is scope-evaluated in v1. The typing note below still holds.

  ⛔ **DISCHARGED 2026-09-16.** The flagged deviation read: *"Whole-install evaluation … **falls short of the
  scoped-assignment clause** — a real v1 narrowing of a LOCKED model … The architect group rules on it as part of
  the amendment: accept whole-install for v1 … or require scope evaluation in v1."* **The amendment took the
  second option.** [`../D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md) is RATIFIED and
  `LOCKED`; whole-install evaluation was **rejected**, so `D10` 🔒's scoped-assignment clause and `REQ-006`'s
  acceptance sentence are satisfied rather than narrowed, and the deviation no longer exists to route.

  ✅ **The scope tree's depth is also settled — ruling OR-16.** The grant hierarchy is **five** levels ending at
  **Instance**, `solution_id` is added to the schema, and ⛔ **Placement is operational, not grantable** (which
  raises §3.2's Placement row from proposal to ruling). Recorded in
  [`../../plans/backend/006-local-identity-and-scoped-rbac.md`](../../plans/backend/006-local-identity-and-scoped-rbac.md)
  Task 2 — ⛔ **not a G2 residue, and not a reason to add a `placement_id` column.**
- **No numeric role levels. No `isAdmin` anywhere.** The administrator is a built-in role with all
  catalog permissions bound — special by *grant*, not by code path.

### 5.2 One enforcement point

```java
public interface AuthorizationService {
    /** Throws AccessDeniedException unless `principal` holds `verb` for `resource`. Deny by default. */
    void require(Principal principal, Permission verb, ResourceRef resource);
}
```

Every endpoint calls `authorizationService.require(...)` — gateway and core services alike; **no
controller-level role checks, no annotations that encode roles, no second path**. `resource` is a
typed reference (kind + id + tenant) so scoped bindings can be evaluated later without signature
changes. The ticket-minting and stream-grant paths (§4.1) call the same method with the mesh verbs.
Introspection ("effective permissions") is itself catalogued — `authz.introspect.self` /
`authz.introspect.any` — **added to the amendment paper as a dated addendum** (the mesh verbs'
precedent) so the group votes on a paper that contains what the code will enforce; the self/any
split is this design's proposed answer to the paper's own §4 enumeration-surface question.

**Enforcement of the enforcement:** the implementing plan adds an ArchUnit rule — no class outside
the authorization package references role names or binding tables directly; endpoints depend on
`AuthorizationService` only — with a planted violation, in the established idiom.

### 5.3 The local identity substrate (v1) — extending `BE-D8`, shaped for `D13`'s future

`BE-D8` already decided the store's location (`sm_control`, reached through `cp-core`'s service
interface), deactivate-never-delete, and installer-secret bootstrap in outline. **`BE-D15` details
and now owns those clauses** (flagged in [`decisions.md`](decisions.md) — one owner, no second live
copy):

- `principal`: `(id, source, username, credential_hash, status, created_at, deactivated_at)` with
  `source ∈ {local, idp}` **from day one** (the amendment's own recommendation) — SSO later adds
  `idp` principals and a group→role mapping; nothing else changes. `credential_hash` is a salted
  adaptive hash (parameters recorded with the hash for future migration); null for `idp` rows.
  SCIM-style *provisioning* (group sync, automated deprovisioning against deactivate-never-delete)
  is deliberately not designed here — `source` and deactivation are the seams it will use, and the
  claim is only that endpoints won't change, because provisioning writes rows, it does not check
  permissions differently.
- **Deactivate-never-delete**: `status = deactivated` ends login and voids bindings' effect;
  the row, its bindings history and its audit lines survive, so config history stays attributable.
- **Sessions** are gateway-owned (`BE-D8`), issued only to `active` principals; `FE-D24` idle
  timeout ends them (and drops the in-memory mesh ticket with them). Credential policy v1: minimum
  length + lockout counter — deliberately small, recorded as such.
- **First admin — installer-seeded (the amendment's option A):** packaging writes one `local`
  principal bound to the administrator role, with a **single-use** credential the first sign-in must
  rotate (enforced server-side: `must_rotate` blocks every other endpoint until changed). **Cited
  with the paper's own qualifier, not around it:** the paper recommends A because it alone works on
  all three loci without new tooling, *and* notes B is materially safer where the console is not
  reachable before setup — *"a security posture call, not an engineering one."* This design supplies
  A's mechanics; the posture call stays the group's, with the amendment.
- **Audit log — split along the plane boundary, because `I7` forbids the alternative:** privileged
  **CP and gateway** actions (role/user/binding changes, package load, ticket mint/renew/revoke with
  `jti`) write an append-only `sm_control` table
  `(at, actor_principal_id, verb, resource_kind, resource_id, tenant, outcome, detail jsonb)`,
  synchronously within *their own* transaction. Privileged **DP** actions (`config.apply`,
  `config.rollback`, `instance.restart`, stop) are audited by **`apply_request` itself** — §3.1 is
  audit-grade by design, carrying actor, scope, confirmation and outcome. A DP mutation writing the
  CP's audit table synchronously would need exactly the cross-plane transaction `I7` forbids and
  `BE-D3` routes around; the honest shape is two audit sources with **one query-time union at the
  gateway** (`/api/console/v1/audit`, composing both planes' service interfaces — the `BE-D3`
  pattern). Mutability, with the artifacts named rather than asserted: the `sm_control` log is
  append-only **by the same DCL pattern as §3.1** — `GRANT SELECT, INSERT ON sm_control.audit_log TO
  sm_cp_runtime`, no UPDATE/DELETE, owner/migrator topology identical, with the runtime-UPDATE
  refusal test mirroring obligation (k); `apply_request` is engine-mutable until terminal via the
  **column-scoped grant** and immutable thereafter via the **`apply_request_terminal_guard` trigger**
  (§3.1, obligation (o)). Retention follows the audit tier (§3.3).

### 5.4 What stays gated, said plainly

> ✅ **G2 closed, and the `D10` amendment RATIFIED and `LOCKED` 2026-09-16** — [`../D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md). Auth-bearing endpoints may ship **within that record's scope**, and the plan's `BLOCKED-ON-G2` markings are discharged. ⛔ **This section's *"until the architect group rules"* is superseded**: the project owner is the final architecture authority and open item 40 is closed. The catalog is **38** verbs; §5.1's *"33 grantable + the introspection pair = 35"* count is **stale** — read the amendment's §1.1 and §1.7. Two clauses of this section are **overtaken rather than satisfied**: the approval **rejects** §5.1's whole-install scope evaluation (bindings are scope-evaluated with downward inheritance), and it approves the catalog at 35 members *as §5.1 enumerates them* — which is **not** the 35 the frontend's `shared/authz/catalog.ts` ships (that record §6). The text below is preserved as written.

`BE-D15` is the machine the amendment proposal assumed; it makes the amendment **cheap to approve**,
not unnecessary.

⛔ **DISCHARGED 2026-09-16.** This paragraph read: *"Until the architect group rules on open item 24 … **no
auth-bearing endpoint ships** … the plan must mark those tasks BLOCKED-ON-G2 explicitly."* Item 24 is **CLOSED**,
the amendment is RATIFIED and `LOCKED`, and the project owner is the final architecture authority — so ⛔ **no plan
may mark a task BLOCKED-ON-G2 any more**, and [`../../plans/backend/006-local-identity-and-scoped-rbac.md`](../../plans/backend/006-local-identity-and-scoped-rbac.md)
and [`007-production-api-and-event-driven-walking-skeleton.md`](../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md)
correctly do not.

⚠️ **What replaces the gate is the closed catalog, not a lifted restriction.** An endpoint whose verb the ratified
**38**-member catalog does not contain still may not ship — it answers `501`, never `403`. The §5.1 scope question
the paragraph listed is answered by [OR-2](../DECISIONS.md)'s hierarchy, ✅ **and the storage schema is now ruled to match it**: ruling **OR-16** amends `D11`, fixes the **grant**
hierarchy at **five** levels ending at **Instance**, and adds **`solution_id`** to the RBAC scope schema. ⛔ **No
`placement_id` in role bindings** — a placement is an operational child identity of its Instance, and a
placement-targeted operation authorizes through the owning Instance scope plus any required Target permission.
⚠️ **Nothing here is an open question any more**, and nothing here was ever a G2 residue.

---

## 6. Gate map after this design

| Gate / item | State after this document |
|---|---|
| Open item 1 (apply protocol) | **Design exists** (`BE-D12`/`BE-D13`, **amended 2026-09-08 by `BE-D16`/`BE-D17`** — STAGED terminality restricted to `manual`, resolution evidence made operation-specific and generation-bound; both defects found by external review of plan 004's execution, both design-first per the plan's own Global Constraint. Both now landed, verbatim, in this design's own §3.1 SQL block and in `V002__apply_request.sql` — implemented, not merely designed — but `BE-D16`/`BE-D17` stay **PROPOSED**: landing a CHECK and its Java mirror is implementation, not ratification). Remains a ratification blocker until ratified with the `SM-D*` chain; `FE-D18` clause B unblocks when it ratifies |
| G2 / open item 24 (`D10` amendment) | ✅ **CLOSED, and the amendment RATIFIED and `LOCKED` 2026-09-16**: [`../D10-G2-APPROVAL-2026-09-15.md`](../D10-G2-APPROVAL-2026-09-15.md). The project owner is the final architecture authority; **open item 40 is closed**. The catalog is **38** verbs with **three built-in roles**, and ⛔ where old `D10` conflicts, the amendment controls. The 35-member catalog and both addenda are approved; **§5.1's whole-install scope deviation is rejected** in favour of scope evaluation with downward inheritance, so `SM-D33`'s prefix algorithm is what `authorizationService.require` must implement. ⚠️ §8 row 12's strategy-assertion verbs are **not** in the approved catalog, so the strategy picker stays unavailable. Was: *Unchanged — architect group. `BE-D15` implements the proposal's recommendations so approval is one decision, not four; the amendment now also carries the introspection addendum and the §5.1 scope question.* |
| Open item 32 (multi-tenant ACL) | ⚠️ **Re-assessed again 2026-09-16 (OR-5): organizations do not share a VPN**, so "multi-tenant composition of one profile" is largely dissolved; what remains is **sub-organization/environment ACL behaviour inside** the VPN, **unmeasured**. Previously: *"32 gates multi-tenancy, not the skeleton."* That reading survives for *multi-tenancy* but **not** for the skeleton's **completion**: with item 35 (below), the broker's subscription shape does not enforce the scope model the 2026-09-15 authorization approved, so the **event-driven** skeleton cannot be declared complete. **Project owner** (OR-1) |
| Open item 33 (notify tier) | ⛔ **The v1 proposal is WITHDRAWN (OR-5/OR-6), not still proposed:** tenant-wide notify visibility is not acceptable, and the taxonomy now carries organization / sub-organization / environment levels to replace it. ⚠️ **Still a hard gate** with 32 and 35, because the ACL behaviour is **unmeasured** — §11.3's matrix is written and not run |
| Open item 35 (environment-level mesh isolation) | ⛔ **GATE, narrowed 2026-09-16 (OR-5/OR-6): the architecture is decided, the enforcement is UNMEASURED** — §11.3's matrix is written and not executed. §4.4 is the surface it lands on. The authorization approved scope-evaluated bindings with downward inheritance, so principals scoped **below** the organization now exist by decision. Under §4.4's proposed **organization-wide notify visibility**, such a browser can subscribe to notify events for an environment its REST scope forbids it to read: REST enforces environment scope, the broker does not. `I6` keeps values off the notify tier, so what leaks is **which instances exist and when they change** — which is what an environment boundary is for. A **confidentiality gap, not a bounded limit**. **Project owner** (OR-1) |
| G1 residue (`BE-D5` ratification) | Blocked on **32/33 and 35** — an earlier row omitted 35. ⚠️ And the §11.3 matrix as written closes **35** only: `BE-D5`'s substitution mechanism lives on the **stream** tier, so its leg 14 must run too |
| G3 residue (`BE-D4` amendment) | **Now carries four riders from this design** (§8 rows 10–13: stream-level rename, terminal-outcome notice, plus feeds): generated `NotifyEvent`/`LiveStateClient` **types** stay unsettled until it lands, exactly as `design.md` §13's G3 row says |
| `SM-D*` → … → `BE-D*` ratification chain | Unchanged; this document's decisions join the `BE-D*` set at the end of it |
| The walking-skeleton plan (next numbered plan) | **Writable once this design survives independent adversarial review.** What is actually buildable **pre-G2**: `BE-D12`/`BE-D13` end to end (protocol, adapters, persistence, events), the `BE-D15` machinery as a tested library, and the `LiveStateClient` contract as hand-written interface + tests (its **generated event types** wait on the `BE-D4` amendment — the G3 row above — per `FE-D7`'s no-hand-typed-wire-models rule). ⛔ **The "what waits for G2" clause is DISCHARGED 2026-09-16.** It read: *"login, the ticket endpoint, stream grants, and every authenticated REST surface — i.e. the browser-facing half of the skeleton."* G2 is closed, so **login and the authenticated REST surface wait on no ratification gate** — they are plans 006/007. ⚠️ **"Wait for nothing" would be an overclaim, and an intermediate revision of this row made it:** plan 007's own Acceptance section says its REST half is *"not wholly executable now"* — its session-backed tasks wait for plan 006's code to exist, which is **sequencing, not a gate**. ⚠️ **The ticket endpoint and stream grants are BUILDABLE and may not be ENABLED until 32/33/35 close** (plan 007 Tier 3 — *"their output may not be turned on"*) — and their **generated event types** additionally wait on the `BE-D4` amendment (the tickets themselves do not) — **not on G2**, and ACL-provisioning runbook *publication* waits on 32/33 with them. **Two different gates; the G2 one is gone and the broker one is not.** |

## 7. Security analysis

- **`I5`** — tenant: server-derived claim in every ticket; topic-level literal enforced by the
  broker ACL (measured); the canonical `tenant_id` rides **all three apply tables and both audit
  surfaces**, per architecture §3.1's tenant-boundary seam, the admission fences and the `apply_scope`
  lock row are tenant-keyed by shape, and the identity columns are outside the runtime's
  column-scoped grant — with
  the topic slug a derived, separately-named thing; no API accepts a client-supplied tenant anywhere
  in this design. How a `sm_data` row's `tenant_id` is validated against the CP without a
  cross-schema join is the architecture's own named gap, routed here as §8 row 9 rather than
  silently absorbed.
- **`I6`** — no secret values in any of the three apply tables (`apply_request`,
  `apply_step_intent`, `apply_attempt` — hashes, references, classified errors, process metadata
  only; the classification rule is the engine's, in §3.1's residual table), none in audit `detail`,
  none in tickets (claims are identifiers), none on any topic (unchanged `BE-D4` property);
  credential hashes are the only secret-adjacent stored bytes and are hashes by construction.
- **`I7`** — the apply engine, its tables **and the DP's audit record (`apply_request`)** live in
  `dp-core` (`sm_data`); identity, roles, bindings and the CP audit log live in `sm_control` behind
  `cp-core`'s service interface; the gateway composes (including the §5.3 audit union) and mints but
  owns no schema; **no new cross-plane edge** — the audit design exists in two halves precisely to
  keep it that way. New ArchUnit obligations named in §2.3 and §5.2.
- **Bearer-ticket exposure** — unchanged from `BE-D5`/G1 and restated: TTL is the revocation window;
  rotation is not revocation; the gateway is the entitlement line and the broker the second line;
  within one session, per-grant tail isolation is gateway/DP-enforced (§4.5), the broker isolating
  sessions from each other.

## 8. Open questions (routed, not silently absent)

| # | Question | Routed to |
|---|---|---|
| 1 | Verb catalog / bootstrap / substrate / audit ratification | ✅ **CLOSED 2026-09-16** — ratified by the owner as the `D10` amendment; the catalog is **38** verbs with **three built-in roles**. Items 24 and 40 closed |
| 2 | ~~Multi-tenant ACL composition~~ **Sub-organization / environment ACL composition inside one VPN** | **project owner** — open item 32. ⚠️ **Re-framed 2026-09-16 (OR-5):** organizations do not share a VPN, so *"multi-tenant composition of one profile"* is largely dissolved and *"before the second tenant"* is no longer the trigger — the trigger is **enabling live notify for any shared or customer install** |
| 3 | Notify-tier exception shape | **project owner** — open item 33, with 32. ⚠️ **OR-6 makes it expressible** (the taxonomy now carries the levels); what is owed is the **measurement**, not the shape |
| 4 | `apply_request` long-term archival policy (years? export format?) | operability, with `architecture.md` §8.5 when an archive policy lands |
| 5 | v1 whole-install scope evaluation vs `D10`'s scoped model and `REQ-006`'s acceptance clause (§5.1's flagged deviation) | ✅ **CLOSED 2026-09-16 — the deviation is REJECTED.** The amendment's §1.2 requires scope-evaluated bindings with downward inheritance (and bidirectional *visibility*), so `SM-D33`'s prefix algorithm is what `require` implements. ⚠️ Residual: OR-2's hierarchy is **six** levels and the scope schema is four — plan 006 Task 2 records it |
| 6 | Whether scope-separated mesh profiles (notify vs stream) use candidate (b) per token | measure before building (the spike marks it inferred-only); v1 uses one profile per §4.4 |
| 7 | The `dp-remote` equivalent of `B/D16`'s lease-bounded authority, required the moment CP and DP are not co-located (§2.2's named non-mirror) | with the SaaS/split-topology design (backend design §16 question 7's neighbourhood) |
| 8 | Wire-visible rollback/verify progress (new rollout vocabulary), if the console needs it | the `BE-D4` amendment — `contracts/events/README.md` Known gap 2's home (§3.5) |
| 9 | CP/DP tenant validation without a cross-schema join — how a `sm_data` row's `tenant_id` (incl. §3.1's tables) is validated against the CP-owned tenant registry (`architecture.md` §16.1 names this gap; every File Mesh multi-tenant leak was this class) | the CP/DP instance-identity protocol `architecture.md` §16.1 already requires (two-phase create/delete, identity owner, orphan detection) — designed together, before multi-tenancy ships; v1 single-tenant (§4.4) bounds the exposure, it does not close the question |
| 10 | The `BE-D4` stream-level rename — the taxonomy names the substituted level `{grantId}` (`contracts/events/asyncapi.yaml`, `BE-D4`'s body); under §4.5 it carries the session's `sub` | the `BE-D4` amendment (with Known gaps 1–4 and rows 8/11/13) — flagged in [`decisions.md`](decisions.md)'s conflicts table |
| 11 | A terminal-apply-outcome wire notice — the v1 catalog has none (§3.5), so apply failures are REST-discovered; the apply screen polls until this lands | the `BE-D4` amendment — proposed as a rollout-step extension or a new notify event |
| 12 | High-consequence **strategy-assertion verbs** — `architecture.md` §16 names them (*"assert `restart-all`, `rolling-no-validation`"*) and the **38**-member catalog still has none, so the strategy picker is not distinguishably authorizable | ⚠️ **Re-routed 2026-09-16: open item 39, sub-question (b)** — item 24 is closed, so this no longer has a home there. Still the owner's, still not verbs added unilaterally here |
| 13 | Per-binding config-state ladder refinement (a placement-scoped apply cannot honestly move an instance-scoped ladder — §3.5's emission rule is the v1 containment) and a referential guard for the nil-UUID placement sentinel | the `BE-D4` amendment (entity kinds) and the placements-table schema work, respectively |
| 14 | **Cross-scope supersession.** §3.3 supersedes only an elder `STAGED` row on the *exact* scope. An instance-wide apply admitted while a placement-scoped `STAGED` row exists (or a placement apply over an instance-wide `STAGED`) leaves the elder `STAGED` although its staged bytes are wholly (first case) or partly (second) overwritten — measured in the plan 004 prototype (`MonotonicityIT`: a placement `STAGED` row survives an admitted instance-wide apply). Two candidate rules: (i) instance-wide admission supersedes every placement-scoped `STAGED` row under the instance, the overwrite being total; (ii) a placement admission leaves an instance-wide `STAGED` row standing (its other placements are intact) and records the partial overwrite in the new request's evidence. Not decided here | `BE-D13` ratification, with the `SM-D*` chain; **v1 implements exact-scope supersession** and plan 004's tests pin that behaviour so the gap stays visible rather than implied away |

## 9. Impact notices — relay to the owning workstreams (the `design.md` §12 pattern)

1. **Frontend (`FE-D*` owners):** `FE-D18` clause A's stated limit — *"no protection against a
   concurrent apply … the last remote write wins, silently, until open item 1 closes"* — is **closed
   by §3.3's admission fences when `BE-D12`/`BE-D13` ratify**: a second in-flight apply is refused at
   admission, so the UI may then say so instead of disclaiming. Flagged here rather than edited into
   the `FE-D*` family (`I4`); the retraction lands with ratification.
2. **Frontend, second notice:** the apply screen's terminal-outcome freshness is REST-polled until §8
   row 11's wire notice lands (§3.5) — the one surface where `DM-D17` polling is primary in v1, and
   the visible *live / degraded* indicator must not claim liveness for it.
3. **Install runbook / packaging:** §3.1's provisioning DCL introduces the install-time database
   step — Phase 0 executed by the **bootstrap superuser** on the pinned PG 14 floor (the only grantor
   who can revoke `public`'s CREATE; measured, obligation (s)) — and the **two-credential, two-process**
   arrangement: Phase 1 runs as a separate one-shot migration step holding the `sm_dp_migrator`
   credential (Flyway with `SET ROLE sm_dp_owner` initSql — an init container/Job on Kubernetes, a
   pre-start command on-prem), and the **application process receives only the `sm_dp_runtime`
   credential** and verifies the schema's presence at startup. **JDBC URLs are locations only** on
   either side — because Flyway logs the URL at INFO and a connection-failure message embeds it, the
   URL must fit a grammar (scheme, `host[:port]/database`, and an allowlist of non-secret pgjdbc
   parameters with fixed value grammars) rather than merely avoid a list of credential parameter
   names: a blocklist of `user`/`password`/`sslpassword` was shown (plan 004 review, third pass) to
   admit a value smuggled into any other property, `options=` included. Anything outside the grammar
   is refused before any connection, by position, without echoing the URL (pgjdbc does not parse
   userinfo — measured — but a libpq-style URI does carry it, and the host grammar has no place for
   it, so such a URI fails by name); failure output from the migration step carries classifiers only,
   and no PostgreSQL **integrity refusal** (SQLSTATE class 23) crosses the persistence boundary
   with the driver exception attached as its cause — its `DETAIL` echoes the rejected row and loggers
   print cause chains; refusals in other classes propagate with the driver exception, whose primary
   messages name objects (a table, a role, a host), not row text. Revised 2026-09-07 (plan 004 review):
   an earlier draft bound both credentials into the application (`spring.flyway.user`), which would
   have made any in-process compromise a schema-owner compromise — the DCL then protects only against
   *accidental* use of the runtime datasource. Both steps belong in the install prerequisites
   alongside the broker items.
