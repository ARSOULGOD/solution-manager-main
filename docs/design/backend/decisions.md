# Backend decisions — `BE-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified. Plan 001's backend
foundation code exists and is merged; the wider backend remains design-first.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `BE-D*` is the per-component family
recorded in [`../README.md`](../README.md). It does not re-decide `D*`, `B/D*`, `SM-D*`, `UI-D*`, `DM-D*`,
`FE-D*` or `AG-D*` — cross-cutting choices are cited, not restated. To change a decision, add one that
supersedes it.

> **Amendment policy for this draft.** As with the `UI-D*`/`DM-D*`/`FE-D*` first drafts: until something is
> built or ratified against this family, review corrections in the same cycle may be applied in place and
> itemized in the changelog. Post-ratification the supersession rule applies normally.

⚠️ **This family rests on an unratified base** — `SM-D18` (Java/Spring greenfield), `SM-D1` (plane split),
`SM-D8`/`SM-D26` (storage) and the `UI-D*`/`DM-D*`/`FE-D*` sets are all `PROPOSED`. Per `I4`, nothing here
may be implemented as though settled, and this family ratifies **after** `SM-D*` → `UI-D*` → `DM-D*` →
`FE-D*`.

⚠️ **Conflicts flagged, not resolved (`I4`):**

| This family | Against | Disposition |
|---|---|---|
| `BE-D6` live freshness | `DM-D17` (refresh is polling; *"the data plane has no push channel to the browser… streaming is deferred"*) | **Supersession, flagged.** `DM-D17` is `PROPOSED`, so this is a proposal against a proposal — `BE-D6` must ratify **after** `DM-D17`, never in the same set. Polling is retained as the fallback path, so `DM-D17`'s machinery survives as degraded mode rather than being deleted. |
| `BE-D7` browser mesh access | `FE-D19` (browser → same-origin gateway only) | **Amendment, flagged to the frontend family.** The one-public-origin rule survives — the SDK connects to `wss://<gateway-origin>/mesh`, reverse-proxied. What changes is that the origin now carries a websocket that terminates at the broker. Routed to the `FE-D*` owners; see design §12. |
| `BE-D7` | `FE-D7` (two REST clients; leaf components never fetch) | **No third client.** The mesh is not a query path — events only invalidate; data always arrives via `cpClient`/`dpClient`. The SDK is confined to one `shared/mesh` module (design §12). |
| `BE-D5` ticket issuing | `SM-D1`'s UI seam / gateway posture | Extends the gateway with a token-issuer role. A defensible extension of the seam, not a restatement — flagged with the family. |
| `BE-D8` OIDC support | `D13` 🔒 (Keycloak **deferred** from v1) | **Not a reopening.** `D13` deferred *shipping Keycloak*; `BE-D8` builds the adapter seam `FE-D8` already requires and ships **local auth** in v1. OIDC is the adapter's second implementation, scheduled when `D13`'s deferral lifts. |
| `BE-D8` first-admin bootstrap + identity store | `D10` 🔒 verb catalog gap (**open item 24** — the consolidated RBAC/identity gap, not item 23's metrics verbs; [`../D10-AMENDMENT-PROPOSAL.md`](../D10-AMENDMENT-PROPOSAL.md)) | The enforcement layer designed here is catalog-agnostic; the catalog itself is the architect group's. ~~Bootstrap mechanism (installer one-time secret) is proposed here~~ **→ ownership transferred 2026-09-04: `BE-D15` details and owns the identity store and bootstrap clauses** ([`eda-walking-skeleton.md`](eda-walking-skeleton.md) §5.3); `BE-D8` keeps the authn adapter, the two providers, and the session/ticket path. **The mesh adds new authorization surfaces of its own** — ticket minting, chart-tail grant — whose verbs are routed to the same amendment paper (R5 finding 9). |
| `BE-D9` `solace_event` extension | `contracts/solution-package.schema.json` is single-writer; `UI-D14` (one canonical definition per vocabulary) | **Extends the existing canonical `solace_event` kind — no new kind, no rename.** The extension is a deliberate reviewed contract change shipped **with** this family's ratification, never a convenience edit. An earlier draft of this decision introduced a `broker-push` kind — that was a rename of the canonical enum and was corrected in R5 (finding 4). |
| `BE-D9` collector locus | `architecture.md` per-locus collector assignment (Go agent under `host-local`) | **Flagged, not resolved.** Centralizing `solace_event` collection in `mesh-bridge` would supersede the architecture's assignment — routed as design §16 question 8 with the `AG-D*` owners; v1 unaffected. |
| `BE-D12` apply protocol | `B/D16` 🔒 (safety properties are `host-local`'s, via the agent) · `SM-D15` (restart operator-selected) · `SM-D2` (no artifact operations) | **Mirror of four of `B/D16`'s five properties, not amendment — the fifth (lease-bounded authority) is named out of scope** (design §2.2: CP/DP co-located on-prem; the split-topology bound is routed as design §8 row 7). The locked agent path is untouched in mechanism; the DP maps agent *reports* into the shared request states (design §3.5). `startOrUpdate`/`stop` execute only declared lifecycle verbs, replica-scoped, under an operator-selected strategy — `SM-D15`'s `manual` default means the core never calls them uncommanded; no operation touches an artifact. Rests on unratified `SM-D21`/`SM-D22`/`SM-D25` (`I4`). |
| `BE-D12` K8s sequencing | `B/D13` 🔒 (sequencing uniformly `start → health-gate → next`; native primitives are optional accelerants, never the source of truth) | **Accelerant reading, flagged.** On Kubernetes, `SM-D25`'s reference-update hands pod-by-pod sequencing to the platform's rollout — used as the accelerant `B/D13` permits, while SM keeps the verdict: the engine health-gates the generation **as a unit** through `status` polling and terminates `FAILED_ROLLED_BACK`/`CONFIG_DIVERGED` on gate failure rather than trusting the platform's success signal (design §2.3). On Docker/systemd (v1) SM sequences replicas itself, exactly the locked shape. If the group reads `B/D13` as forbidding even that delegation, the K8s adapter must sequence pods itself — an adapter change, not a seam change. |
| `BE-D14` v1 tenancy postures | open items **32**/**33** (architect group) · `BE-D2`/`BE-D4` multi-tenant text | **Proposals, not resolutions.** v1-single-tenant (32 gates multi-tenancy, not the skeleton) and tenant-wide notify visibility (the spike's own recommended option for 33) are offered to the group; `BE-D5` ratification stays blocked on their ruling. |
| `BE-D14` stream-level rename | `BE-D4` (taxonomy names the substituted stream level `{grantId}` — decision body and `contracts/events/asyncapi.yaml`) | ⚠️ **Level rename, flagged and routed — never a drive-by contract edit.** Under the session-scoped branch the substituted level carries the session's `sub`, not a grant id; the rename rides the same `BE-D4` amendment `contracts/events/README.md`'s Known gaps already feed (design §4.5). Nothing breaks today (check 10 does not lock address strings; no Java deriver covers the stream tier), but `contracts/` is normative and single-writer. |
| `BE-D14` grant accumulation | `design.md` §5 step 4 (*"a refreshed token carrying that **one** stream claim"* — grant-scoped singular) | **Amendment, flagged:** the session ticket accumulates its live grants as a set (`BE-D14` §4.1/§4.5); design §5's singular wording is amended by this decision at ratification, not silently cited as support. |
| `BE-D14` branch↔session mapping | `BE-D5` (ticket claims carry granted scopes; stream claims added per grant) · `BE-D7` (one session, one origin) | **Consistent, made explicit** (a first draft had `sub == grant_id`, which would have forced one broker session per live chart — corrected in review): `sub` is the **session's** branch identity, grants accumulate as `grant_id` claims on ticket refresh (exactly `BE-D5`'s model), the DP publishes granted tails under the session branch, and many tails ride one session (design §4.5, with the fan-out and gateway-enforced within-session isolation costs stated). |
| `BE-D15` authorization machinery | `D10` 🔒 (model) · `D13` 🔒 (Keycloak deferred) · open item **24** / [`../D10-AMENDMENT-PROPOSAL.md`](../D10-AMENDMENT-PROPOSAL.md) | **Implements the amendment proposal's own recommendations; amends nothing.** The catalog (35 members: `D10`'s two existing verbs + 28 + 3 mesh + the introspection pair, the pair added to the paper as a dated addendum), bootstrap choice (option A's mechanics designed; the paper's own A-vs-B security-posture qualifier carried, not elided), substrate and audit log remain the group's single amendment decision (G2); until it lands, no auth-bearing endpoint ships — the machinery may be built and tested as a library only. `D13` untouched: local substrate carries the `source` discriminator so SSO is additive. |
| `BE-D15` v1 scope evaluation | `D10` 🔒 (**scoped** assignments; its two-sided rule is scope-relative) · `REQ-006` acceptance (*"effective permissions derive from scoped assignments"*) | ⚠️ **Flagged v1 deviation, routed to the group with the amendment (design §5.1, §8 row 5):** v1 evaluates bindings whole-install. That satisfies the centralized deny-by-default clauses and falls short of the scoped-assignment clause — the group accepts it for v1 (scope arriving with `SM-D23`) or requires scope in v1; the schema makes either a policy change, not a migration. Never presented as a refinement detail. |
| `BE-D15` identity substrate ownership | `BE-D8` (store location, deactivate-never-delete, installer-secret bootstrap, in outline) | **`BE-D15` details and now owns those clauses** (design §5.3) — one owner, no second live copy; `BE-D8` keeps the authn adapter, the two providers and the session/ticket path. |
| `BE-D16` STAGED terminality | `BE-D12`/`BE-D13` (§3.2's request-state table — the previous "or it has not begun" clause, measured unenforced by any DDL, trigger or Java guard) | **Amendment, flagged — a meaning change, so a new decision per `I3`, not an edit to either.** `state = 'STAGED'` implies `strategy = 'manual'`, with no exception; a non-manual apply now stays `RUNNING` through every lifecycle step until a terminal outcome. Closes a real gap an external review of plan 004 found (a rolling apply could land `STAGED` and freeze immutably, with no path to `APPLIED`). The implementing CHECK (`staged_requires_manual`) and the `OutcomeService.land` Java guard are implemented, in `V002__apply_request.sql`/the design's §3.1 SQL block (commit `647d4e9`), with the DDL-replay baseline re-recorded against it (commits `90cce61`, `221672d`) — implementation is not ratification, `BE-D16` stays PROPOSED. |
| `BE-D17` resolution evidence shape | `BE-D13` (`resolution_is_evidence` — the previous operation-blind, generation-unbound evidence shape) | **Amendment, flagged — a meaning change, so a new decision per `I3`, not an edit to `BE-D13`.** Two operation-discriminated shapes replace one: an `apply` resolution's evidence must equal the request's own `generation`; a `restart` resolution's evidence carries `restartsObserved` and no `stagedGeneration` key at all. Only an `apply` resolution may raise an apply-generation watermark. Closes a real gap an external review of plan 004 found (evidence naming one generation could resolve a request at another, and restart resolutions had to manufacture a meaningless number). The implementing CHECK (`resolution_is_evidence`'s operation-discriminated CASE), the `ApplyResolutionEvidence`/`RestartResolutionEvidence`/`RestartObservation` split and the `OutcomeService.resolveIndeterminate` guard are implemented, in `V002__apply_request.sql`/the design's §3.1 SQL block (commits `5f04d79`, `d15dc4b`), with the DDL-replay baseline re-recorded against it (commits `90cce61`, `221672d`) — implementation is not ratification, `BE-D17` stays PROPOSED. |
| `BE-D18` element-complete evidence | `BE-D17` (`resolution_is_evidence` — the previous container-only check, and its "key-exactness… is the Java seam's job" reading of `resolution_observed`) | **Amendment, flagged — a meaning change, so a new decision per `I3`, not an edit to `BE-D17`.** Six `jsonb_path_exists(…, silent => true)` predicates (`apply`) and four (`restart`) add per-element typing, a mandatory-but-nullable `observedHash` key, and both key-exactness levels (top-level and per-element) to `resolution_is_evidence`, refusing at raw SQL what the container-only check left representable — `{"replicas":[{}]}`, `{"restartsObserved":[null]}`, a `replicas` element missing `observedHash` entirely, and (closed in R5 fix round 1, an independent review's finding) an **array-valued** `replicas`/`restartsObserved` element or `observedHash` value, which `lax` mode's filter-on-array auto-unwrap otherwise passes as "no violation found". Closes a real gap a fourth review found: `sm_dp_runtime`'s column-scoped `UPDATE` grant on `resolution_observed` lets a bypass write (direct SQL, a migration defect, a future repository bypass) land a generation-matching apply resolution with no replica reference, no restart observation and no hash, which `OutcomeService` then treats as grounds to raise the watermark. Requires one Java-side change, specified here and made: `Evidence.readResolutionEvidence` now refuses a `replicas` element missing `observedHash` (an `IllegalStateException`, not a null-coalesce), rather than silently mapping it to the same Java `null` a present-and-`null` value produces. The CHECK and the Java change are both implemented, in `V002__apply_request.sql`/the design's §3.1 SQL block and `Evidence.java` respectively (commit `56119a4`), with a fix round (R6 fix round 1, commit `22ebd61`) restoring four pre-existing clauses `BE-D18`'s own landing had silently unpinned — apply's `stagedGeneration = to_jsonb(generation)` (`BE-D17`'s entire reason for existing, 4 legs), apply's and restart's `jsonb_typeof(...) = 'array'` (1 leg each), and restart's `NOT jsonb_exists(resolution_observed, 'stagedGeneration')` (1 leg, permanently subsumed by the top-level key-set predicate and kept as defence-in-depth, not revivable by any leg) — three restored to their original pin counts by element-completing six fixtures, the fourth left permanently unpinnable; full account in the changelog rows below. A further residual fix round (R7, commit `3a5d423`) then gave the register itself a durable history of R6 and its fix round (both had landed without a changelog row of their own), fixed a second logically-dead clause and two more stale fixtures the same L20 pattern had left behind, and the DDL-replay baseline was re-recorded against the amendment (commit `de8a6aa`) — see the changelog rows below. Implementation is not ratification, `BE-D18` stays PROPOSED. |
| `BE-D19` shared `ref` grammar | `BE-D18` (its own body text: *"`ReplicaRef`'s mirror of Java's `isBlank()` is a one-sided containment, not an equivalence… the DDL is never stricter than `ReplicaRef` — the required direction"*) and this document's §3.4 prose making the same claim | **Amendment, flagged — a meaning change, so a new decision per `I3`, not an edit to `BE-D18`.** A post-handover external review measured the claim incomplete: it reasons only about the write direction, and `sm_dp_runtime`'s existing `UPDATE` grant on `resolution_observed` makes the read direction real — a raw write can commit a `ref` PostgreSQL's `\S` accepts and Java's `isBlank()` would reject (a lone U+001C–U+001F codepoint, measured), which then throws on read. **R1 fix round:** the decision's first pass proposed the shared `ValueGrammar.VALUE`; the developer's direction required proving it against current/near-term Docker, systemd and Kubernetes identifier rules before adopting it, and it failed on two verified facts (Kubernetes RFC 1123 subdomain names run to 253 characters against `VALUE`'s 128 cap; systemd escaped unit names carry a literal backslash `VALUE`'s class cannot express) — withdrawn, replaced by a new, dedicated grammar, `ValueGrammar.REPLICA_REF`, verified against primary sources for Docker/systemd/Kubernetes and empirically measured on both PostgreSQL 14.24 and `java.util.regex.Pattern` in the same session. **This repair also widens, not merely leaves flagged, the sibling gap on `apply_step_intent.replica_ref`** (no character-content CHECK — `V004__apply_step_intent.sql:8`/`:26`, read back by the same constructor at `StepIntentRepository.java:74`): sized, and deliberately deferred to its own future amendment — out of this decision's own binding criterion (`resolution_is_evidence`/`readResolutionEvidence`) and defensible because the column is INSERT-only for the runtime role — not resolved here. **Implemented** (S2, commit `4ecf5e7`): `ValueGrammar.REPLICA_REF`, `ReplicaRef`'s constructor, and `resolution_is_evidence`'s element well-formedness predicate in both operation branches of `V002__apply_request.sql` and the design's §3.1 SQL block (mirrored in `0fd35c8`), with the DDL-replay baseline re-recorded against it (`edb87e1`) — implementation is not ratification, `BE-D19` stays PROPOSED. |
| `BE-D20` one-open-attempt fence | `BE-D13` (`apply_attempt`'s only concurrency control, `UNIQUE (intent_id, attempt_no)`, silently assumed sufficient to keep exactly one attempt open per intent) and `AttemptService.open`'s own javadoc, which reads that UNIQUE as "the guarantee" | **Amendment, flagged — a meaning change, so a new decision per `I3`, not an edit to `BE-D13`.** A post-handover external review measured, deterministically, that the numbering UNIQUE refuses only a same-number collision: once one attempt commits, a second opener computing a different, non-colliding number lands a second OPEN row with no violation at all — two adapters can then act on the same step. Adds a second, independent partial unique index (`attempt_open_fence`) fencing "at most one open attempt per intent," distinct from and alongside the numbering UNIQUE, not a replacement for it. **Watch item, not a blocker:** landing the fence converts a transient, self-healing clock-skew glitch (cross-node wall-clock drift after a failover, or a single-node clock step, making `close`'s JVM-clock `ended_at` land before `open`'s, refused by `attempt_time_ordered`) into a permanently wedged intent — priced in the design body; the remedy (a `greatest()` guard, a DB-clock `ended_at`, or a relaxed CHECK) is a future decision's, not S2's; `BE-D20` proceeds regardless. **Implemented** (S2, commit `e1b8490`): the partial unique index `attempt_open_fence` in `V005__apply_attempt.sql` and the design's §3.1 SQL block (mirrored in `0fd35c8`), `Constraints.ATTEMPT_OPEN_FENCE`, `AttemptService.open`'s two-name `namesAnyOf` classification and its three corrected javadocs, with the DDL-replay baseline re-recorded against it (`edb87e1`) — implementation is not ratification, `BE-D20` stays PROPOSED. |

---

| ID | Decision | Status |
|---|---|---|
| `BE-D1` | Events are change notices; PostgreSQL rows are the only system of record | PROPOSED |
| `BE-D2` | SM rides the customer's existing event mesh, in a dedicated Message VPN | PROPOSED |
| `BE-D3` | Four modules: `cp-core`, `dp-core`, `console-gateway`, `mesh-bridge` — the gateway is open item 21's composition layer | PROPOSED |
| `BE-D4` | Versioned topic taxonomy `sm/v1/…` split notify / stream / internal, contract-bound by AsyncAPI in `contracts/` | PROPOSED |
| `BE-D5` | Browser mesh access via gateway-minted short-lived JWTs, broker-validated (OAuth/JWKS); no per-session SEMP — authentication verified; **G1 spike ran 2026-08-27 and chose candidate (a), the substitution-variable ACL** ([`spike-authz-result.md`](spike-authz-result.md)). Ratification now blocked by two named gaps with the architect group: **multi-tenant composition** (open item 32) and the **notify-tier exception shape** (open item 33) ⚠️ | PROPOSED |
| `BE-D6` | Hybrid payload model: thin notify-then-fetch for state; data-bearing live chart tails only at raw tier ending at now ⚠️ | PROPOSED |
| `BE-D7` | The browser joins the mesh through the Solace WebApp SDK over a gateway-proxied websocket, one public origin ⚠️ | PROPOSED |
| `BE-D8` | One authn adapter, two providers: local DB users (v1, with installer-secret bootstrap) and OIDC SSO; both feed one session and one ticket path | PROPOSED |
| `BE-D9` | Broker ingest extends the canonical `solace_event` source; collector locus flagged against the architecture's per-locus assignment ⚠️ | PROPOSED |
| `BE-D10` | Contracts are consumed by **mirror-and-lock**: hand-written Java bindings locked to the schema files by equality tests in `mvn verify` (keys, enums, entity scoping, primitive types and bounds), arbitrated by shared golden/negative fixtures and a shared publisher contract-test kit; no codegen, no producer-side runtime schema validation in v1 | PROPOSED |
| `BE-D11` | Contract artifacts reach Java tests only through the single `ContractArtifacts` resolver (mesh-events test-jar, `-Dsm.contracts.root` override as the repo-split seam), and reach runtime only as build-time resource copies guarded by a byte-equality test; no copies committed under `backend/` | PROPOSED |
| `BE-D12` | The `dp-remote` apply protocol is six runtime-neutral operations (`prepare` · `materializeConfig` · `startOrUpdate` · `stop` · `status` · `rollbackOrMarkIndeterminate`; lifecycle and observation replica-scoped, so the `SM-D15` ladder is expressible) behind a `RuntimeAdapter` seam — Docker first, systemd/Kubernetes later — mirroring four of `B/D16`'s five safety properties with the journal DP-persisted (the lease clause named out of scope); core model and API are runtime-agnostic ⚠️ | PROPOSED |
| `BE-D13` | Apply persistence is **three tables with three jobs**: `apply_request` (decided — also the DP's audit record; audit-grade, long-lived, `STAGED` is `manual`'s honest terminal state **— narrowed to *exclusively* `manual`'s, superseded in part by `BE-D16`** — `APPLY_INDETERMINATE` blocks its scope until operator-resolved with observed-state evidence **whose shape is superseded in part by `BE-D17`, now operation-specific rather than uniform**), `apply_step_intent` (intended — the **immutable identity substrate, immutable by executable DCL in the sketch** — `sm_dp_owner`/`sm_dp_migrator`/`sm_dp_runtime` roles, ownership, `PUBLIC` revoked, runtime granted SELECT/INSERT and no UPDATE/DELETE, no owner membership so `SET ROLE` is refused, migrations run by a **separate one-shot process** holding the migrator credential while the application process holds only the runtime credential; one row per logical step binding the idempotency key to the intended hash via UNIQUEs plus serialized insert-or-read-compare, so a differing-hash retry is a durable protocol error, not an engine-memory one), and `apply_attempt` (attempted — the retry log, diagnostic-grade, prunable, bound to its intent by the **triple FK** `(tenant_id, apply_id, intent_id)` so cross-request attribution is unrepresentable). Enums and cross-field rules are **real CHECK constraints, not comments** (state/operation/step enums; `apply_has_generation`; `lifecycle_has_strategy`; `state_fits_operation`; `resolution_is_evidence` — resolver, outcome AND a non-empty evidence object with the FE-D18 keys, all-null outside `INDETERMINATE_RESOLVED` **(shape superseded in part by `BE-D17`)**; `hash_on_config_steps` — **biconditional**; `restart_not_manual`; `terminal_has_timestamp`; `full_outage_confirmed`; `attempt_no_positive`; per-step attempt CHECKs via the `(intent_id, step)` FK; every jsonb clause `coalesce`d against NULL propagation, which a PG-14 execution run measured admitting `'{}'`), the three named fences plus the `apply_scope` lock row and `placement_watermark` tables (exact `FOR UPDATE` serialization — no advisory-lock hashing, whose int4 collisions were measured — carrying the cross-scope generation watermarks an index lookup cannot see across the sentinel boundary) and the `apply_request_terminal_guard` trigger (exactly two sanctioned terminal writes: the `STAGED → SUPERSEDED` mark admission itself requires, and the `attempt_summary`-only compaction fold — measured unimplementable without it), plus the per-table `*_only_rises` triggers (a runtime rewind re-admitting a superseded generation was measured; now watermarks can only over-fence, the fail-safe direction — one function per table, because a shared `TG_TABLE_NAME` branch was measured dying with 42703 on every rise and on the placement decrease — no watermark could ever be raised) and the `request_has_scope` FK (no request without its serialization row), column-scoped runtime grants (audit identity on `apply_request`; watermark identity on `apply_scope`/`placement_watermark`, whose UPDATE reaches only the watermark value — a value-only trigger leaves re-keying open) and the full **two-phase, ordered** provisioning/migration DCL (Phase 0 bootstrap by the bootstrap superuser — on PG 14 the only grantor who can revoke `public`'s CREATE, a database owner measured as a silent no-op: roles, owner-membership grant, schema, schema grants, asserted-not-assumed public-CREATE revoke; Phase 1 Flyway under `SET ROLE sm_dp_owner`, where `ALTER DEFAULT PRIVILEGES` becomes legal) in the sketch, the residual engine-enforced invariants tabulated with their named tests, and twenty-one executable prove-it-fires obligations (a)–(u) — (s) being that the exact two-phase script executes end to end on a fresh PG 14, because the earlier execution evidence proved a prior form and does not transfer **— the numbering UNIQUE `(intent_id, attempt_no)`'s sufficiency as the sole attempt-concurrency control is superseded in part by `BE-D20`, which adds a second, independent partial unique index fencing "at most one open attempt per intent"**. Canonical `tenant_id` on **all three** tables with tenant-consistent FKs; single-in-flight enforced by **tenant-scoped**, sentinel-keyed, **operation-split** partial unique fences **plus** an admission transaction serialized on the `apply_scope` lock row; growth linear; execution steps map onto the contract's rollout enum, never fork it ⚠️ | PROPOSED |
| `BE-D14` | Live-console session contract: fixed ticket claim floor (`iss`/`aud`/`sub`/`tenant`/`grant_id`/`purpose`/`exp`/`jti`), **session-scoped stream branch** (`sub`; grants accumulate as claims — many tails, one session), memory-only browser storage, renewal on the gateway's clock (`renewAfter`) with background-tab throttling a named limit healed by resync, resync = REST backfill from last `stateVersion`, one `LiveStateClient` frontend seam, mint/renew/revoke audited with the revocation latency bound stated; v1 postures proposed for open items 32/33 ⚠️ | PROPOSED |
| `BE-D15` | Minimal v1 authorization: `RoleBinding → Role → Permission` over a code-owned deny-by-default catalog (33 grantable verbs + the introspection pair = 35 members), one `authorizationService.require(principal, verb, resource)` enforcement point, local identity substrate (detailing `BE-D8`'s clauses) with `source` discriminator and deactivate-never-delete, installer-seeded single-use first admin, audit split along the plane boundary (`sm_control` log for CP/gateway verbs; `apply_request` is the DP's record; gateway query-time union) ⚠️ | PROPOSED |
| `BE-D16` | `state = 'STAGED'` implies `strategy = 'manual'`, with no exception — closes the "or it has not begun" ambiguity that let a non-manual apply land irreversibly `STAGED`; amends `BE-D12`/`BE-D13`'s request-state terminality ⚠️ | PROPOSED |
| `BE-D17` | Resolution evidence is operation-specific: `apply` evidence's `stagedGeneration` must equal the request's own `generation`; `restart` evidence carries `restartsObserved` and no `stagedGeneration` key at all; only an `apply` resolution may raise an apply-generation watermark **— its "key-exactness… is the Java seam's job" reading of `resolution_observed` is superseded in part by `BE-D18`, now also DB-enforced** — amends `BE-D13`'s `resolution_is_evidence` shape ⚠️ | PROPOSED |
| `BE-D18` | `resolution_observed`'s per-element shape and both key-exactness levels (top-level and per-element) are DB-enforced: ten `jsonb_path_exists(…, silent => true)` JSONPath predicates across `resolution_is_evidence`'s two branches (`apply`: element type-check, element well-formedness — including a mandatory, if nullable-valued, `observedHash` key — element key-set, `observedHash` type-check, hash format, top-level key-set; `restart`: the same minus the hash axis), refusing `{"replicas":[{}]}`, `{"restartsObserved":[null]}`, a `replicas` element that omits `observedHash` entirely, **and — closed in a fix round after an independent review measured the escape — an array-valued `replicas`/`restartsObserved` element or `observedHash` value, which `lax` mode's filter-on-array auto-unwrap otherwise lets through** (e.g. `{"replicas":[[]]}`, `observedHash: ["sha256:…"]`); all at raw SQL, not only at the Java construction boundary; requires one Java-side fix (`Evidence.readResolutionEvidence` must refuse a missing `observedHash` key rather than null-coalescing it) — the one construction/read-side gap the amendment's own predicates found; amends `BE-D17`'s `resolution_is_evidence` shape and its "Java seam's job" reading of key-exactness **— and this decision's own `ReplicaRef`/`\S` "one-sided containment is the safe direction" mirror-posture reading is superseded in part by `BE-D19`, which found the write-direction-only reasoning incomplete** ⚠️ | PROPOSED |
| `BE-D19` | `ReplicaRef` and `resolution_is_evidence`'s per-element well-formedness clause enforce **one shared, locale-independent character grammar** — `ValueGrammar.REPLICA_REF` (`^[A-Za-z0-9._:/@+\\-]{1,255}$`, one literal backslash admitted, length to 255) — in place of Java's `isBlank()` and the DDL's `like_regex "\\S"`, which a post-handover external review measured NOT equivalent in the direction that matters: PG's `\s` class excludes U+001C–U+001F while Java's `Character.isWhitespace` includes them, so a raw `UPDATE` on `resolution_observed` (a write path `ReplicaRef`'s constructor never sees) could commit a `ref` consisting solely of one such codepoint, which then throws in `Evidence.readResolutionEvidence` — a poison row, written and unreadable, the same class `BE-D18` closed for element shape and left open for `ref` content. **R1 fix round, per the developer's explicit direction:** the first pass's candidate, the shared `ValueGrammar.VALUE` (`^[A-Za-z0-9._:/@+-]{1,128}$`), was proven — not merely argued — to disqualify itself against current/near-term runtime identifiers: verified against Kubernetes' own docs, RFC 1123 subdomain names (Pod/Deployment/Service names) run to 253 characters, past `VALUE`'s 128-character cap; verified against `systemd.unit(5)`, an escaped unit name (`systemd-escape`'s output for an arbitrary device/mount path) carries a literal backslash, absent from `VALUE`'s class entirely. `REPLICA_REF` is a NEW, dedicated constant — `VALUE`/`HASH` are untouched, preserving their stricter, backslash-excluding `I6` shape-control role for `RuntimeRef`/`ErrorClass` — verified against Docker's `moby/moby` container-name source and `distribution/reference`'s image-reference grammar, systemd's unit-name grammar (including its 255-character cap, which sets `REPLICA_REF`'s own bound), and Kubernetes' subdomain/label rules, and empirically measured identical on both live PostgreSQL 14.24 and `java.util.regex.Pattern` across a 17-case matrix in the same session. Narrows what a `ref` may contain relative to `isBlank()`: still refuses quotes, a space, `=`, non-ASCII characters and embedded whitespace that two literals in `SchemaConstraintsIT`'s hostile-ref test carry as positive controls today (re-verified refused under the wider grammar, by their other characters, not by the backslash the grammar now admits) — and caps length at 255, not 128. Amends `BE-D18`'s "one-sided containment is the safe direction" mirror-posture reading of `ref` — the write-direction-only half of that reasoning, by cross-reference per `I3` ⚠️ | PROPOSED |
| `BE-D20` | At most one **OPEN** `apply_attempt` row (`outcome IS NULL`) per intent, fenced by a new, independent partial unique index — `attempt_open_fence ON apply_attempt (intent_id) WHERE outcome IS NULL` — alongside the existing `UNIQUE (intent_id, attempt_no)`, not replacing it. A post-handover external review measured, deterministically (no race required), that the numbering UNIQUE alone refuses only a same-`attempt_no` collision: once attempt 1 commits, a second opener computing a different, non-colliding number (`count + 1`) lands attempt 2 with no violation at all — two adapters can then act on the same step, the same hazard §2.2 rule 0 ("write before act") and rule 4 (bounded, non-duplicated retry) implicitly assume cannot happen. The new fence refuses that second open regardless of numbering, while a legal retry after the prior attempt is closed stays legal (measured, both directions). The engine classifies a refusal under either index's name as `ConcurrentAttempt` — the caller-facing action is identical either way — while the design states the two conflicts are not the same conflict: only `attempt_open_fence` can fire for a non-colliding sequential second open, and which name fires for a genuine numbering race is left to the implementing task's own measurement, not asserted here. Requires the deterministic replacement of two existing non-deterministic barrier tests (`AttemptIT`, `EngineTransactionBoundaryIT`) whose "collide or this proves nothing" caveat this fence retires. Amends `BE-D13`'s attempt model, by cross-reference per `I3` ⚠️ | PROPOSED |
| `BE-D28` | DP secrets are two-level `AES-GCM` envelopes (per-secret data key wrapped by the install KEK) with an AAD binding tenant, secret and purpose; values write-once, no `DELETE` for the runtime role — implements `OR-30` | PROPOSED |
| `BE-D29` | The master key is validated at boot against a per-key canary, minted only on a fresh install or a proven rotation; a wrong key refuses to start before the worker ⚠️ | PROPOSED |
| `BE-D30` | Rotation re-wraps data keys with an operator command holding both keys, per tenant in batches, and never drops a key it cannot prove empty ⚠️ | PROPOSED |
| `BE-D31` | The config hash key is a per-tenant versioned envelope secret; the hash is `hmacsha256v<n>:<hex>`, computed in the DP from read-back bytes; an unloadable version verifies as *unverifiable*, not a mismatch — implements `OR-32` ⚠️ | PROPOSED |
| `BE-D32` | Declare is a two-phase protocol across two planes: the DP mints the id, the CP holds the governance node, and a lazy tenant-scoped sweep finishes any interrupted declare — records `OR-27`, `OR-40`, `OR-41`, `OR-71`, `OR-82` ⚠️ | PROPOSED |
| `BE-D33` | `cp-core` depends on the plane-neutral `mesh-events` module and publishes `catalog.package` through its OWN after-commit wrapper over the ONE shared raw transport; `sm.mesh.enabled` defaults to `false` (a declared degraded state, never a refusal to boot) — plan 010 T3.3, executing plan 007 Task 5a ⚠️ | PROPOSED |
| `BE-D34` | Verify-then-retry: the engine reconciles `APPLY_INDETERMINATE` against a fresh host observation (never from the engine's own belief), superseding the part of `BE-D12`/`BE-D16` that left it alone — by owner ruling (`OR-59`, `OR-63`, `OR-64`), not ratification; plan 010 T6.8 ⚠️ | PROPOSED |
| `BE-D35` | The runtime seam gains a ninth operation, `rematerializeIfCurrent`, a conditional re-write that only the reconciler calls — plan 010 T6.8 ⚠️ | PROPOSED |
| `BE-D36` | Log levels: the level is READ live over SSH from the placement's loopback, and every CHANGE is configuration — no management port, no actuator move, no live write, no journaled live-set operation (`OR-103`, option Rd; amends `OR-43` P1's live SET for v1) ⚠️ | PROPOSED |
| `BE-D37` | The SM-owned overlay is ONE function (`OverlayKeys`): the only source of the keys SM adds beside a package's fields and the only predicate saying which keys are SM's; the stage, the renderer and the read-back strip all go through it | PROPOSED |
| `BE-D38` | A package declares logger support in an optional `logs.loggers` block (requires `loggerPrefix` and `logs.baselineExposure`); nothing is inferred; a package with no block gets no live read and no overlay | PROPOSED |
| `BE-D39` | The runtime seam gains a tenth operation, `readConfig`, the one READ-ONLY operation: it returns the replica's config file bytes with the DP-computed keyed hash, and dp-core (never an adapter) parses it — D-ACCEPT, plan 010 T10.4 (`OR-45`, `OR-104`) ⚠️ | PROPOSED |
| `BE-D40` | A read-back file is parsed with a pre-scan that refuses what Jackson reads wrongly (an anchor, an alias, any tag, a second document, a non-scalar key, an over-deep or over-large file), then Jackson with strict duplicate detection — D-ACCEPT, measured against Boot's own loader options ⚠️ | PROPOSED |
| `BE-D41` | A read-back secret is never stored: the draft keeps the current `secretRef`, and the DP compares the file's value with the stored one in memory through a compare-only port and reports only `same` or `differs` (`V-4`); a `yaml` field is not accepted; SM's overlay keys are stripped, not adopted — `OR-104` Q-A2/Q-A4/Q-A6 ⚠️ | PROPOSED |
| `BE-D42` | `accept-runtime` stages an `accept-runtime` generation through the normal stage path and writes nothing to the host; unmappable keys are reported by path (capped at 200) and the next apply removes them; the response says whether the file's bytes still differ from the rendering — `OR-104` Q-A1/Q-A3/Q-A5 ⚠️ | PROPOSED |
| `BE-D43` | A broker credential is stored in `sm_secret` under its OWN purpose, `BROKER_CREDENTIAL`, amending `BE-D28`'s closed purpose set (the purpose is part of the AAD, so a broker credential labelled `CONFIG_FIELD` would open as a config field); one broker connection per (organization, environment), registered by a DP operator command, the credential held by reference — plan 011 P11-5.1 (`OR-107`, OQ-3 (a)) ⚠️ | PROPOSED |
| `BE-D«next-or112b»` | **One live broker connection per (host, port, message VPN) across the whole install**, refused by the install-operator command `BrokerConnectionMain` in its own words and guaranteed by a partial unique index over `(lower(host), port, vpn) WHERE retired_at IS NULL` (`sm_data` migration `V019`, a placeholder number); the uniqueness read is the operator command's own, install-wide and package-private, never application runtime; adapter ids are unique only within one VPN, which is why — `OR-112` (2), `W3-06` ⚠️ | PROPOSED |
| `BE-D«next»` | Consumer-side load-time package validation uses `com.networknt:json-schema-validator` 2.0.8 (Apache-2.0) behind `BE-D11`; `BE-D10`'s mirror-and-lock is unaffected; a catalog entry's `stateVersion` is `max(load_seq)` of its packages, with no control-plane trigger — plan 010 T3.0/T3.2 ⚠️ | PROPOSED |
| `BE-D44` | The apply journal is placement-aware: `apply_step_intent` carries the placement each intent addresses, unique on `(tenant_id, apply_id, placement_id, step, replica_ref)`; the idempotency key names the placement only for an instance-wide request — supersedes `BE-D23`'s identity statement; plan 008 S2, `OR-121` (OQ-8.2 (a)) ⚠️ | PROPOSED |
| `BE-D45` | A rollout across an instance's placements is a fleet of placement-scoped applies; an instance-wide apply stays refused before admission — amends `BE-D12` §2.1 and `BE-D21` by cross-reference; plan 008, `OR-121` (OQ-8.1 (b)) ⚠️ | PROPOSED |
| `BE-D46` | An instance-wide stop addresses the instance's **live** placements from the registry, never request history — amends `BE-D26`'s enumerator; plan 008 S3, `OR-121` | PROPOSED |
| `BE-D47` | A placement is added to an active instance by `addPlacement` under `instance.bindTarget` (at the instance) + `target.use` (at the target), holding the instance row and re-checking `active`; refused for a push-metrics package; re-settles the ladder — plan 008 S3, `OR-121` (OQ-8.3 (a)/(i), OQ-8.9 (a), OQ-8.10 (a)) ⚠️ | PROPOSED |
| `BE-D48` | `apply_request.placement_id` is referentially guarded against unknown and other-instance placements by the mechanism plan 008 measures (MF-3); liveness stays the service's and the catalog's — plan 008 S4, `OR-121` (OQ-8.5 (a)) | PROPOSED |
| `BE-D49` | Undeclare releases an instance from management under `instance.undeclare`; it never deletes and never touches the workload (`?workload=leave-running`); recovery never writes the Control Plane — plan 012, `OR-121` (OPS-Q2 U-C, OPS-Q3 P-1, OPS-Q4 (a)) ⚠️ | PROPOSED |
| `BE-D50` | The ungated strategies' semantics: one placement is restarted with no gate and verified by read-back; across placements a `rolling-no-validation` fleet waits a settle delay between batches and no `rolling-*` batch holds two placements of one instance — plan 012, `OR-121` (OPS-Q8 K-2 + (x), OQ-8.1 (b)) | PROPOSED |
| `BE-D51` | Overriding restart safety is the verb `instance.restart.overrideSafety`; a `restart-all` outage confirmation is recorded as the caller's session principal, never a browser string — plan 012, `OR-121` (OPS-Q6 B-1, OPS-Q7 C-2) ⚠️ | PROPOSED |
| `BE-D52` | A scheduled apply is a DP-owned schedule record admitted as an ordinary apply inside its window, re-authorized at fire by a gateway loop over a `dp-api` port; `strategy: "scheduled"` answers `422` — amends `OR-53`/`OR-60`; plan 012 T7, `OR-121` (OPS-Q9 Sch-2, OPS-Q10 (c)) ⚠️ | PROPOSED |
| `BE-D53` | One placement is retired by the verb that adds one (`instance.bindTarget` at the instance), under undeclare's rules: never touches the host, refused for the last live placement and while anything is in flight on it — built by plan 008 S3, `OR-121` (OPS-Q12 R-1, OQ-8.4) | PROPOSED |
| `BE-D54` | Targets are registered over HTTP in one composed call, authorized and pre-checked before either plane is written, with the key sealed in the DP — plan 013, `OR-121` (D-REG OQ-1 (a), OQ-8 (a)) ⚠️ | PROPOSED |
| `BE-D55` | A host key is pinned only by an explicit field, and only if the host offers it on a fresh DP scan; the DP refuses loopback, link-local, unspecified, multicast and non-allow-listed addresses at registration and at every use — plan 013, `OR-121` (OQ-2 (b), OQ-3 (d)) ⚠️ | PROPOSED |
| `BE-D56` | Data-plane target changes are audited in the same transaction, in the append-only `sm_data.target_connection_event` — plan 013, `OR-121` | PROPOSED |
| `BE-D57` | The scope tree is named (columns from `OR-120`), and sub-organizations and environments are created and renamed over HTTP under `hierarchy.edit`; organizations are not created over HTTP in v1 — plan 013, `OR-121` (OQ-1 (a), OQ-5 (a)) | PROPOSED |
| `BE-D58` | The plain-VM runtime is reached over SSH as transport `ssh-systemd`, paired with target kind `vm` — plan 014 (approved as a plan, implementation deferred), `OR-121` (Q-V10 (a), Q-V2 (a)) ⚠️ | PROPOSED |
| `BE-D59` | A systemd replica's start token is `ActiveEnterTimestamp` in UTC (`--timestamp=us+utc`, systemd ≥ 248) and its `InvocationID` — plan 014, `OR-121` (Q-V4 (a)) | PROPOSED |
| `BE-D60` | On a VM, SM's account is unprivileged except for exact-argv sudo rules per unit — plan 014, `OR-121` (Q-V1 (a)) | PROPOSED |
| `BE-D61` | Secret-bearing files are given an explicit mode and checked before they reach the target path — VM in plan 014; Docker by a separate pre-PR task (Q-V13 (b)), `OR-121` ⚠️ | PROPOSED |
| `BE-D62` | One runtime per host per organization in v1 — plan 014, `OR-121` (Q-V9 (a)) | PROPOSED |
| `BE-D63` | VM logs are read through one exact-argv `journalctl` tail of a constant line count — plan 014, `OR-121` (Q-V3 (e)) | PROPOSED |
| `BE-D64` | The metrics reset-signal vocabulary gains `systemd` (additive) — plan 014, `OR-121` (Q-V6 (a)) | PROPOSED |
| `BE-D65` | A systemd restart that outlasts its bound is looked at once more by the adapter before it throws; no engine change — plan 014, `OR-121` (Q-V11 (b)) | PROPOSED |
| `BE-D66` | Connections (Solace broker / remote server config objects, D-4) are split cp/dp exactly like Targets: `cp-core` owns the row, `dp-core` reuses `EnvelopeSecretStore` for secret attributes and runs the connectivity test (JCSMP / SSH auth-only, no host-key pinning); adds the `connection.*` verbs under `OR-130` — plan 013 | PROPOSED |
| `BE-D66` | A blocking agent adapter behind per-placement routing; commands keyed on the intent key; fail fast; a concurrency cap so an agent can never starve `dp-remote` — plan 015, `OR-128` (10) (Q6 (b)); extends `BE-D13` by one hop | PROPOSED |
| `BE-D67` | Agent-path parity is minimal-plus-health; every other operation on an agent placement or target is a contracted, coded refusal, never a silent no-op — plan 015, `OR-128` (10) (Q9 (a)) | PROPOSED |
| `BE-D68` | `target_connection` transport `agent` and `acquisition_mode` `agent`; the `enrolled` event — plan 015 T1.1; widens `V007`/`V022` without editing them (`OR-22`) | PROPOSED |
| `BE-D69` | The launcher's three cross-plane adapters: a second composer beside `OR-10`'s `console-gateway` — plan 015, `OR-126` (A3) ⚠️ flagged against `I7`, not resolved | PROPOSED |
| `BE-D70` | An agent may be the `sm_control` actor of `AGENT_ENROLLED`, `AGENT_INSTANCE_REGISTERED` (and a lease-time write if one exists) — **amends `OR-25` by name**; records `OR-128` (5) | PROPOSED |
| `BE-D71` | The agent target is created in both planes at enrolment (CP then DP, `BE-D32` recovery), its envelope from the token, `created_by` the issuing principal; every target operation on it is contracted — plan 015 T3.5 | PROPOSED |

---

## `BE-D1` — Events are change notices; PostgreSQL is the only system of record

Every SM event announces that state changed; it is never the state's authoritative carrier. Any consumer —
the console, another module, a future alerting service — can reconstruct correct current state from
PostgreSQL through the existing REST/query APIs without replaying a single event. Notify and internal
propagation events are **direct (non-persistent) messages**: a lost event costs freshness, never
correctness.

**Rationale.** This is what makes a customer-operated broker (`BE-D2`) a tolerable dependency: broker down
degrades the console to polling and delays internal propagation, but config-of-record, apply, RBAC and the
query API keep working. It also keeps `D14` 🔒 (plain PostgreSQL) untouched — no event store, no outbox
table in v1 (an outbox is a known upgrade if propagation ever needs at-least-once; nothing in this design
does, because Postgres is truth).

**Rejected:** event sourcing (config history as an event log) — it would re-home the system of record onto
infrastructure SM does not operate, and `SM-D22`'s config-generation model already gives history in rows.
Guaranteed (persistent) delivery for notify events — it buys nothing when every consumer heals by reading.

## `BE-D2` — The customer's existing mesh, in a dedicated Message VPN

SM attaches to the event mesh the deployment site already runs. Install provisions **one dedicated Message
VPN** for SM (client-usernames, ACL profiles, the OAuth profile of `BE-D5`); MI business telemetry is
consumed read-only from the customer's own VPN/topics per package `source` declarations. A reserved
topic-prefix inside a shared VPN is the documented fallback where a dedicated VPN cannot be granted, at an
explicitly weaker isolation posture.

**Rationale.** Every SM customer is a Solace customer; **File Mesh and other push-mode MIs'**
business/reporting events already travel on this mesh (command-center events — Cache is HTTP-polled and
contributes nothing here), so attaching there is also the ingest path for that class of MI (`BE-D9`).
Bundling a broker inside SM packaging was rejected: it duplicates infrastructure the site already has,
adds an operations burden `D14` deliberately avoided for the datastore, and separates SM from the very
topics the reporting metrics arrive on. **Cost, stated:** SM's *liveness* features depend on a broker SM
does not operate, and day-one install requires broker-admin cooperation — both accepted, bounded by
`BE-D1`'s degradation guarantee.

## `BE-D3` — Four modules; `console-gateway` is the composition layer open item 21 demanded

`cp-core` (owns `sm_control`: catalog, schema registry, entitlement, RBAC store, package load) · `dp-core`
(owns `sm_data`: config-of-record, apply/rollout, telemetry ingest + rollups) · `console-gateway` (serves
the SPA; terminates `/api/cp/*`, `/api/dp/*` and the composed `/api/console/v1/*`; reverse-proxies `/mesh`;
mints `BE-D5` tickets; hosts the `BE-D8` authn adapter) · `mesh-bridge` (the JCSMP layer, packaged inside
`dp-core` initially but module-bounded so it can split).

`console-gateway` depends on both planes' **service interfaces**, never their data-access modules, and owns
no schema — `I7` holds (the `BE-D8` local-user store lives in `sm_control` and is reached through
`cp-core`'s service interface, not owned by the gateway). On-prem all four ship as one deployable. **The
SaaS split is a boundary change for the REST surface only**; the browser↔mesh path does **not** survive the
split as designed — a Solace-hosted gateway cannot proxy websockets into a customer's broker without
inbound reach (R5 finding 1). SaaS live delivery needs a DP-side relay or outbound tunnel, recorded as
design §16 question 7 and designed with the split.

**Rationale.** `UI-D13` requires composing `sm_control` and `sm_data` reads *"in the API layer above both
data-access modules"*, `I7` forbids either plane being that layer, and `architecture.md` §3.1 named only two
modules — open item 21. This decision proposes the third module and gives it the jobs that genuinely sit
above both planes: composition, session, ticket minting, static serving. **Rejected:** a CP-side composer
(couples the DP's availability to CP deploys and tempts cross-schema reads); narrowing `I7` (the invariant
is the reason the SaaS split is a boundary change, not a rewrite).

## `BE-D4` — Versioned topic taxonomy, contract-bound by AsyncAPI

```
sm/v1/<tenant>/notify/instance/<instanceId>/health
sm/v1/<tenant>/notify/instance/<instanceId>/config-state
sm/v1/<tenant>/notify/instance/<instanceId>/version
sm/v1/<tenant>/notify/instance/<instanceId>/rollout/<applyId>
sm/v1/<tenant>/notify/catalog/package
sm/v1/<tenant>/stream/<grantId>/<seriesKey>        (data-bearing chart tails, BE-D6)
sm/v1/internal/<domain>/…                          (CP↔DP propagation; never browser-granted)
```

Notify payloads carry identifiers only — the **common envelope** (design §3: `eventId`, `occurredAtUtc`,
`producer`, `tenant`, `entity`, monotonic `stateVersion`) plus event-specific identifier fields; no config value, no secret,
no metric value, no label values (`I6` by construction; a leaked notify subscription leaks existence, never
data). The taxonomy and payload schemas live as an **AsyncAPI document in `contracts/`**, versioned in the
topic (`v1`) from the first commit, with the same gate discipline as the package schema: fixtures, negative
cases, and a check wired into `contracts/run-checks.sh`.

**Rationale.** `common/contracts` requires every contract to carry a version from its first commit; a topic
string is as much a wire contract as a JSON body, and an event contract that lives only in Java code is the
cross-implementation drift `UI-D15`/`DM-D20` exist to prevent — the browser (TypeScript, via the SDK) and
the backend (Java) both parse these topics.

## `BE-D5` — Gateway-minted JWTs, broker-validated via OAuth/JWKS; no per-session SEMP

The broker's OAuth profile for SM's VPN is configured **once at install** to trust the `console-gateway` as
issuer (JWKS served by the gateway). Per session: after login and a deny-by-default RBAC check, the gateway
mints a short-lived JWT (target ~15 min, renewable over REST) whose claims carry the **server-derived
tenant** (`I5` — never client-supplied) and the granted subscription scopes (notify-only at login; stream
claims added per `BE-D6` grants). The SDK presents the JWT as its OAuth credential. SEMP is used only at
install/config time (VPN, ACL profiles, OAuth profile) — never per session.

Verified 2026-08-27 (`I2`) against
[Solace client-authentication docs](https://docs.solace.com/Security/Configuring-Client-Authentication.htm):
the broker validates access-token JWTs as an OAuth resource server, retrieves signing keys from a configured
`jwks` endpoint, and multiple authentication schemes can be enabled per Message VPN.

⚠️ **What that verification does NOT establish (R5 finding 2): subscribe *authorization*.** The docs verify
authentication; Solace subscription authorization binds to client-username / authorization-group ACLs with
limited topic-substitution variables — an arbitrary per-grant claim is not an ACL input. Three candidate
mechanisms, **decided by the Phase-0 broker spike (design §13) before this decision ratifies**:
(a) substitution-variable ACLs over per-grant client identities; (b) short-lived authorization groups per
grant; (c) tenant-coarse ACL (`sm/v1/<tenant>/stream/>`) with unguessable grant-ids, accepting a stated
within-tenant exposure. The spike's written result is cited here at ratification.

**G1 result (2026-08-27).** The spike ran, against `soltr_10.25.0.177` / SEMP 2.48, and chose **candidate (a),
the substitution-variable ACL** — `sm/v1/<tenant>/stream/$client-username/>` on a profile bound to the VPN's
`default` client username, with **zero broker writes per grant**, measured in the composition the
recommendation actually uses. Candidates (a) and (b) proved **mutually exclusive, not layered**. The written
result is [`spike-authz-result.md`](spike-authz-result.md). *(The three-candidate paragraph above is the
decision as proposed and is deliberately left as written — `I3`: a decision's meaning is never edited in
place. This paragraph records the spike's outcome, not a new decision.)*

**Ratification is still blocked** — no longer by the spike, but by **two gaps the spike priced and did not
close**, both sitting with the architect group and both recorded as open items in
[`../DECISIONS.md`](../DECISIONS.md): **multi-tenant composition of the substitution mechanism** (open item
32 — only the *username* level substitutes, so the zero-writes result is measured single-tenant) and the
**notify-tier exception shape** (open item 33 — design §4's notify topics carry no per-subject level to
substitute at). Each has options priced in the result document; neither is decided here.

**Signing-key lifecycle (R5 finding 7).** The gateway's signing key is persisted across restarts; rotation
is overlapping — old and new keys stay published in JWKS through the broker's cache/refresh window, which is
an install-runbook parameter; negative tests cover old-token-after-rotation and new-token-before-refresh
(design §14). A gateway restart must never invalidate every live ticket.

**Rationale.** The alternative — provisioning broker client-usernames/ACLs per session over SEMP — makes SM
an administrator of customer production infrastructure on every login, a credential-custody posture worse
than `dp-remote`'s and rejected for it. JWT claims move authorization decisions to where `D10` already runs.
The mesh's own verbs (ticket mint, stream grant/revoke) are named into the `D10` amendment (open item 24)
rather than checked ad hoc. **Limit, stated:** a revoked session's mesh access survives until token expiry
(≤ TTL); the TTL is the revocation latency and is chosen accordingly.

## `BE-D6` — Hybrid payload model; live tails only at raw tier ending at now ⚠️

State (health, config-state, version, rollout, catalog) is **notify-then-fetch**: the event invalidates, the
refetch goes through REST where tenant scope and RBAC already apply. Charts may additionally receive a
**live tail**: the `DM-D1` backfill query — having passed RBAC — returns a `streamTopic` plus a token
bearing that one subscription claim, and the DP publishes normalized points (values + series identity only)
to it. **A live tail exists only when the served tier is raw and the range ends at now.** History,
aggregation (`DM-D8`), gaps (`DM-D16`), retention and tier routing (`DM-D11`) are always and only the query
API; the browser appends points newer than the backfill watermark and never aggregates. On
reconnect the browser re-runs backfill from its watermark, so gaps stay server-declared.

**Rationale.** Notify-then-fetch keeps `I5`/`I6` enforcement in one place for everything stateful; the tail
exception exists because live values are the point of an open incident chart. The raw-tier-and-now rule is
`DM-D8`'s tier-crossing refusal applied to streaming: appending live seconds onto hourly rollup buckets is
the plausible-wrong-graph failure `SM-D30` exists to refuse. ⚠️ Supersedes `DM-D17` as the default (see
conflicts table); polling remains the fallback.

## `BE-D7` — The browser joins the mesh via the WebApp SDK over a gateway-proxied websocket ⚠️

The SPA uses the Solace WebApp SDK, connecting to `wss://<gateway-origin>/mesh`, which the gateway
reverse-proxies to the broker's websocket listener. One public origin (`FE-D19`'s rule, amended not
reversed); the broker is never directly exposed to operator networks; the SDK connects **only after**
authentication, holding only a `BE-D5` ticket. In the frontend the SDK is imported solely through a
`shared/mesh` module (the `FE-D16` pattern), enforced by a guard with a self-test.

**Rationale.** Direct browser→broker was rejected on two failures: operator networks frequently cannot
reach broker listeners, and per-session provisioning on customer infrastructure requires standing SEMP admin
credentials (see `BE-D5`). A gateway-terminated SSE fan-out was rejected because it re-implements the
broker's one competency in SM code and abandons the SDK. **Limit, stated:** every browser websocket now
transits the gateway — its proxy is on the availability path for liveness (not for correctness, per
`BE-D1`).

## `BE-D8` — One authn adapter: local DB users (v1) and OIDC SSO; one session, one ticket path

The `console-gateway` owns authentication behind one adapter interface (`FE-D8`'s shape, server-side).
**v1: local users** — a credential store in `sm_control` (per-user salted hash, deactivate-never-delete so
config history stays attributable), first admin created by **installer-provided one-time secret** (proposed;
routed to the architect group with the `D10` amendment). *(2026-09-04: the store and bootstrap clauses are
now detailed and owned by `BE-D15` — [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §5.3; this
decision keeps the adapter, the two providers and the session/ticket path.)* **OIDC SSO** is the adapter's second
implementation, activated when `D13`'s deferral lifts; identities become IdP-supplied, assignments are
untouched. Both providers produce the same gateway session, and `BE-D5` tickets are minted from the session
— the broker trusts one issuer regardless of login type.

**Rationale.** REQ-006 is v1-must and nothing owned the identity substrate — the RBAC *model* is `D10`'s and
the verb catalog is the architect group's, but users have to exist and log in for either to matter. The
separation of identity from assignment mirrors the approved mockup's structure so the IdP migration is a
data change. **Rejected:** shipping Keycloak in v1 (re-opens `D13` 🔒); email-invitation flows (introduce an
email capability SM does not have).

## `BE-D9` — Broker ingest extends the canonical `solace_event` source ⚠️

Telemetry acquisition stays a per-binding policy (`SM-D4`/`D5`). This decision **extends the canonical
`solace_event` `source.kind` already defined in `contracts/solution-package.schema.json`** — no new kind, no
rename (`UI-D14`: one canonical definition per vocabulary) — with the fields broker ingest needs: queue vs
topic binding, delivery mode, the mapping to canonical `name`, `metricType`, `unit` and stable
replica/source identity, an idempotency/dedupe key, and the **durability evidence `SM-D27` requires** so a
best-effort subscription is never presented as durable. A deliberate, single-writer, reviewed contract
change. The consumer subscribes, normalizes, writes to `sm_data`, and only then emits notify/stream
messages. Pull-mode sources (Cache's actuator poll) are untouched and emit the identical notify events
after their writes. Undeclared series arriving by broker quarantine exactly as any other (`UI-D5`) —
transport grants no trust. Raw MI payloads never reach a browser-subscribable topic (`DM-D3` holds).

⚠️ **Collector locus — flagged, not resolved (R5 finding 5, `I4`).**
[`../architecture.md`](../architecture.md) assigns the `solace_event` collector per control locus: **Go
agent under `host-local`**, Java DP under `dp-remote`/`observe-only` — implemented twice by design. This
decision's "`mesh-bridge` is the DP's only mesh client" would centralize collection in the DP now that SM
rides the customer mesh: one implementation, no open-item-14 dependency — but it supersedes the
architecture's locus assignment, and DP→mesh reachability on `host-local` sites is exactly the constraint
that locus exists for. **Routed as design §16 question 8**, decided with the `AG-D*` owners before phase-2
File Mesh work. v1 is unaffected (Cache is HTTP-polled).

**Rationale.** The stated reason for `BE-D2` — business/reporting metrics already arrive on the customer
mesh — is only usable if broker ingest is a first-class declared source rather than File Mesh
special-casing, which is precisely the per-solution branch `REQ-001` forbids. An earlier draft introduced a
`broker-push` kind; R5 (finding 4) corrected that to an extension of the existing canonical kind.

---

## `BE-D10` — Contracts consumption: mirror-and-lock, no codegen, no producer-side runtime validation

**Full design:** [`../common/contracts/backend-consumption.md`](../common/contracts/backend-consumption.md) —
this entry records the decision; the mechanics, the rejected options (codegen, runtime validation) with the
scenarios run through them, and the exact test specification live there and are not restated here.

The backend consumes `contracts/events/` through hand-written Java bindings (`EventEnvelope`, `NotifyTopics`,
`NotifyEventShapes`, `EventJson`) kept honest three ways: **(i)** a schema-lock test in `mesh-events` that
parses the schema JSON files in `mvn verify` and asserts equality — both directions — between the schema's
required/allowed keys, enums, entity scoping, primitive types and numeric/string bounds and the mirrored Java
constants/checkers; **(ii)** the shared fixture/negative/manifest goldens both implementations already run
(the `UI-D15`/`DM-D20` pattern), extended with enum/bound negatives; **(iii)** wire-honesty tests on emitted
bytes through a shared publisher contract-test kit every publisher implementation must extend. The deliberate
`packageVersion` asymmetry (events README Known gap 4) is **pinned** by the lock test, not resolved by it.

Where the input is user-supplied at runtime — solution-package upload in `cp-core` — the model inverts:
runtime validation against the packaged schema **is** the feature there, with parity to the Python gates via
the same fixtures and 13 negatives.

**Rationale.** R7 F1 and R8 F1/F2 were all mirror-drift defects that two green suites failed to point at;
the lock turns that class into a red build. Codegen was rejected for v1 because this schema's two-field
`oneOf` dispatch, two `not` scoping shapes and deliberate any-type hole still require hand-written dispatch
and the same lock/golden tests; runtime validation on producers moves the red from `mvn verify` to the
customer's environment while duplicating what typed construction already enforces at zero runtime dependencies.

---

## `BE-D11` — One contract-artifact resolver for tests; checked build-time copies for runtime

**Full design:** [`../common/contracts/backend-consumption.md`](../common/contracts/backend-consumption.md) §4.

Java tests reach `contracts/` only through `com.solace.sm.events.contracttest.ContractArtifacts`
(`mesh-events` test scope, exported as a Maven test-jar; resolution order `-Dsm.contracts.root`, then walk-up),
replacing the three hand-rolled resolvers plan 001 left behind. Java **runtime** never reads the contracts
tree: a runtime consumer gets a build-time resource copy plus a test asserting the packaged copy is
byte-identical to the in-tree source while the monorepo exists, and to the selected versioned contracts
artifact after repo split. No schema or fixture is ever committed under `backend/`.

**Rationale.** Three resolvers had already drifted in shape (one brittle relative path). The system-property
override is the repo-split seam — when contracts become versioned artifacts, consumption changes inside one
class — and it is also what makes the `BE-D10` lock test's prove-it-fires demonstration reproducible (point
the override at a mutated copy, watch the red). The byte-equality rule applies `common/contracts/README.md`'s
"regenerated with a diff check" governance to copies before the first runtime consumer exists to improvise.

---

## `BE-D12` — The `dp-remote` apply protocol: six runtime-neutral operations behind a `RuntimeAdapter` seam

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §2 — the per-operation
semantics table, the binding rules each operation inherits (`SM-D15`, `SM-D19`, `SM-D21`, `SM-D22`,
`SM-D25`, `SM-D2`), the three-runtime walk-through proving the seam generic at design time, and the
ArchUnit obligation confining adapter client libraries.

The core apply engine speaks exactly `prepare` · `materializeConfig` · `startOrUpdate` · `stop` ·
`status` · `rollbackOrMarkIndeterminate` — **lifecycle and observation replica-scoped**, because
`SM-D15`'s ladder and `architecture.md` §9's per-host sequencing are per-replica and an
instance-scoped verb cannot express them; health gating is the core's policy over `status`
observations, not an adapter action. Docker, systemd and Kubernetes are adapters behind that
interface, and runtime-specific identifiers live only in adapter-scoped `runtime_ref` fields. The
protocol mirrors **four of `B/D16`'s five** safety properties for `dp-remote` — generation
monotonicity, idempotency keys, resume that is deterministic where recorded evidence decides
(`pre_baseline` makes the lifecycle step decidable) and honestly `APPLY_INDETERMINATE` where it does
not, bounded retry with explicit cleanup incl. `SM-D25`'s never-delete-a-referenced-ConfigMap rule —
with the journal persisted in PostgreSQL (`BE-D13`) instead of an agent-local file; the fifth
(lease-bounded authority) is named out of scope while CP and DP are co-located, routed as design §8
row 7. `APPLY_INDETERMINATE`'s operator-confirmation flavour is exactly the `dp-remote` degradation
`AG-D5` cross-references and `FE-D18` clause B waits on.

**Rationale.** Open item 1 has been a named ratification blocker since the on-prem revision: v1's
first slice runs on `dp-remote`, and without this design it ships non-idempotent remote apply.
Designing the protocol Docker-specific was rejected because the core tables and API would then need
migrating for systemd/Kubernetes — the per-solution-branch failure `REQ-001` rejects, relocated into
infrastructure. **Rejected:** reusing the agent's journal wire-format directly (the DP's journal is
rows it already owns; a file journal on the DP host would be a second source of truth beside
Postgres, against `D14`'s spirit).

## `BE-D13` — Apply persistence: decided, intended, attempted — three tables, real constraints

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3 — the constraint-bearing
DDL sketch, the growth arithmetic, indexes, the tenant-scoped operation-split partial unique fences,
the engine-enforced residuals, and the retention/archival split.

`apply_id` is durable in `sm_data`. `apply_request` records what the operator decided (who, what
generation or stop, which strategy, which confirmation) and is audit-grade with long retention — it
is also **the DP's audit record** (the `I7`-honest half of `BE-D15`'s split);
`apply_step_intent` records what the engine committed to per logical (apply, step, replica) —
insert-only, one row per step, durably binding the idempotency key to the intended hash so a
differing-hash retry is a protocol error the database itself remembers across crashes;
`apply_attempt` records each retry of each intent (the DP-persisted in-flight state open item 1
demands, including the pre-lifecycle `status` baseline that makes resume decidable) and is prunable
after a diagnostic window — except attempts under failed/diverged/indeterminate-resolved requests,
which live as long as their request; intents live as long as theirs. The enums and cross-field
rules are **real CHECK constraints in the sketch, not commentary** — a state outside the model
would silently escape the fence predicates — with the two residual engine-enforced invariants
stated in §3.1 (step-label truthfulness, `SUPERSEDED` marking order, replica-element typing, the
configured retry cap; intent immutability is a **database guarantee expressed as executable DCL in
the sketch** — roles, ownership, `PUBLIC` revoke, grants, no-membership `SET ROLE` refusal, with the
migration path on its own Flyway datasource). The state model gives `SM-D15`'s `manual` default an honest
terminal state (`STAGED` — the ladder's five config states stay the binding's, never collapsed into
the request row), names `CONFIG_DIVERGED`, and blocks a scope with an unresolved
`APPLY_INDETERMINATE` (`architecture.md` §16.1's constraint) via **tenant-scoped**, sentinel-keyed,
**operation-split** partial unique indexes (`(tenant_id, instance_id, placement_id)` keys and the
tenant-keyed `apply_scope` lock row — reused instance ids from import/restore/fixtures must never let one
tenant's in-flight apply fence another's; `operation <> 'stop'` on the apply fence, so the emergency
stop is admissible by the database and not merely by prose — the stop family carries its own
one-in-flight fence) **plus**
a serialized admission transaction — both, because PG 14 treats NULL keys as distinct and no index
can make instance-wide exclude per-placement; the design names executable-DDL prove-it-fires
obligations (a)–(u), covering the admission behaviours, every named constraint in both directions
where directional, the triple-FK cross-request refusal, the resolution-evidence refusals (typed
values, not key presence), the runtime-role UPDATE and `SET ROLE` refusals, and the retry bounds. **All three tables** carry the canonical `tenant_id`
(architecture §3.1's tenant-boundary seam; `apply_attempt` is pruned/archived/exported independently
and must never depend on a join for tenant safety — its FK to its intent is the **triple**
`(tenant_id, apply_id, intent_id)`, so an attempt cannot reference another request's intent even
within a tenant), with the topic tenant slug a separately-named derivation and the CP/DP no-join
validation question routed (design §8 row 9, `architecture.md` §16.1's own named gap). `config_hash`
is required exactly on the config-writing, -verifying and -restoring steps
(`MATERIALIZE`/`VERIFY`/`ROLLBACK`, by CHECK — a restorative rollback knows its target generation
before acting, so crash-resume can prove which config was restored) and honestly absent for
`STOP`/`PREFLIGHT`/`ROLLBACK_ASSESS` (the hashless assessment that restored nothing) — no
placeholder hashes. Execution steps map onto the contract's
`[staged, restart-requested, restart-observed]` rollout enum through one DP-owned mapping (design
§3.5); they never fork it. Growth is linear in applies and bounded attempts; no partitioning
machinery — this is audit data, not telemetry facts.

**Rationale.** One table doing both jobs either bloats the audit tier with retry diagnostics or
prunes the control history with them; the split lets audit outlive diagnostics by policy, which is
the property `I6`-adjacent audit obligations and the operator's history screen both need. Secret
values never land in either table — hashes, references and classified error strings only.

## `BE-D14` — Live-console session contract ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §4 — the claim table, browser
rules, the `LiveStateClient` seam contract, and the two v1 tenancy proposals.

Every gateway-minted mesh ticket carries at least `iss`, `aud`, `sub` (the **session's**
subscription-branch identity — gateway-owned, never user-supplied, never containing `/` — Solace
documents that constraint as broker-unenforced, so the gateway enforces it with a test), `tenant`
(server-derived, `I5`), `grant_id` (the session's live stream grants, accumulating on refresh —
exactly `BE-D5`'s claim model; design §4.5 is why the *branch* is the session's, not the grant's:
one broker session reads one substituted branch, so grant-named branches would cost one websocket
per live chart — and the branch level `BE-D4` names `{grantId}` is therefore renamed to the
session's `sub`, flagged below and routed to the `BE-D4` amendment), `purpose` (`notify` on the one
session ticket; stream entitlement is discriminated by a non-empty `grant_id` set), `exp` (≤ 15 min
— the only revocation lever, per the G1 measurements) and `jti` (the audit key). Tickets live memory-only in the browser; renewal executes client-side
against a **gateway-computed `renewAfter`** (G1: the gateway re-tickets proactively on its own
clock) and is a planned reconnect, with background-tab timer throttling a named limit healed by the
resync path; every resync triggers REST backfill from the last `stateVersion`; the browser never
publishes (broker-enforced, config-asserted); mint/renew/revoke are RBAC-gated (the three amendment
verbs) and audited; grant revocation is **DP-effective** (the tail publisher drops the series at its
next publish decision — the session-scoped branch moved the lever off the TTL), while session-wide
mesh access stays TTL-bounded — both bounds stated identically here and in the amendment's scope
note. The frontend consumes one `LiveStateClient` abstraction in `shared/mesh`
whose notify handlers only invalidate queries — REST stays the authoritative snapshot/backfill
(`BE-D1`), by contract.

⚠️ Proposes two v1 postures to the architect group: **single-tenant walking skeleton** (open item 32
then gates multi-tenancy, not the skeleton — the measured G1 shape is exactly single-tenant) and
**tenant-wide notify visibility** (open item 33's spike-recommended option). Neither is resolved
here; `BE-D5` ratification stays blocked on the group's ruling.

**Rationale.** The mechanism was designed (`BE-D5`/`BE-D7`) and measured (G1); what the skeleton
lacked was the *contract* — which claims, which storage, which renewal discipline, which frontend
seam — and a tenancy posture that does not hold the skeleton hostage to a multi-tenancy decision no
v1 install exercises. **Rejected:** localStorage/sessionStorage ticket persistence (a bearer
credential with a 15-minute life gains nothing from surviving a reload and loses XSS containment);
reactive renewal (G1 measured the broker's refusal taxonomy unusable as a signal).

## `BE-D15` — Minimal v1 authorization: `RoleBinding → Role → Permission`, one enforcement point ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §5 — the model, the
`AuthorizationService` contract, the identity substrate, bootstrap, the audit log, and §5.4's
explicit gate.

Permissions are a code-owned deny-by-default catalog of **35 members — 33 grantable verbs plus the
introspection pair**: `D10` 🔒's two existing grantable verbs (outside the amendment's 28 by the
paper's own text) + the [`D10` amendment proposal](../D10-AMENDMENT-PROPOSAL.md)'s 28 + the 3
mesh-addendum verbs + `authz.introspect.self`/`.any`, the last pair added to the paper as a second
dated addendum so the group votes on what the code will enforce. Roles are named permission sets — built-in only in v1 (the set
proposed from the mockup **as a proposal with a source, not authority**), stored and evaluated as
data so custom roles are additive; bindings are `(principal, role, scope)` with scope typed for
`SM-D23`'s tree and **evaluated whole-install in v1 — a flagged deviation** from `D10`'s scoped
model and `REQ-006`'s scoped-assignments acceptance clause, routed to the group with the amendment
(design §5.1). Every endpoint authorizes through
`authorizationService.require(principal, verb, resource)` — no numeric role levels, no `isAdmin`
code paths, no controller-local checks (ArchUnit-enforced). The local identity substrate details
and now owns `BE-D8`'s store/bootstrap clauses: `source` discriminator (`local`/`idp`),
deactivate-never-delete, installer-seeded single-use forced-rotate first admin (the amendment's
option A — carried **with** its qualifier that B is materially safer pre-exposure and the choice is
a security-posture call for the group). Privileged actions are audited **split along the plane
boundary** (`I7` forbids a DP mutation writing a CP table in one transaction): CP/gateway verbs →
the append-only `sm_control` log; DP apply-family verbs → `apply_request` itself; one query-time
union at the gateway.

⚠️ **Implements the amendment proposal's recommendations; amends nothing.** `D10` 🔒 and `D13` 🔒
stand; open item 24 (G2) remains the architect group's single decision, and until it lands no
auth-bearing endpoint ships — the machinery may exist as a tested library only, and any plan built
on this decision must mark endpoint tasks BLOCKED-ON-G2.

**Rationale.** `REQ-006` is v1-must and the enforcement machinery is the same regardless of which
verbs the group approves — designing it now makes the amendment one approval instead of four
interlocking ones, and designing it generically (bindings as data, scope typed, source
discriminator) is what makes SSO/SCIM/custom roles additive rather than rewrites. **Rejected:**
role enums with ordinal comparison and `isAdmin` flags (both make the catalog decorative and the
audit trail unreadable); per-endpoint annotations as the enforcement mechanism (a second path is
how one endpoint ships unchecked).

## `BE-D16` — `STAGED` is exclusively the `manual` strategy's terminal state ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.2 (the amendment callout and
the rewritten `STAGED`/`RUNNING` rows) — amends `BE-D12`/`BE-D13`'s request-state semantics; neither
decision's own text is rewritten in place (`I3`).

`state = 'STAGED'` implies `strategy = 'manual'`, with no exception — the implication the previous
text's "or it has not begun" clause left unenforced. (This is one-directional, not a biconditional:
`strategy = 'manual'` does not imply `state = 'STAGED'` back — a manual apply is `REQUESTED`/`RUNNING`
before it ever reaches `STAGED`. An R1 fix round found and removed a naming error that had called it
a biconditional in five places across this document and the design; see the design's §3.2 for why the
biconditional *form* — `CHECK ((state = 'STAGED') = (strategy = 'manual'))` — is measured wrong, not
merely mis-named: it rejects a manual apply at `REQUESTED`.) A non-manual apply (`rolling-health-gated`,
`rolling-no-validation`, `restart-all`, `scheduled`) **must remain `RUNNING`** through
materialization and every lifecycle step until it reaches one of `APPLIED`, a `FAILED_*` variant,
`CONFIG_DIVERGED` or `APPLY_INDETERMINATE` — it must never be observed as `STAGED`. The mid-flight
condition the old clause tried to describe (every replica persisted, no lifecycle step yet issued,
under a non-manual strategy) is real and stays representable — as `RUNNING` at the request level and
as the `persisted` rung on the five-state config ladder (`instance.config-state`) — never as a value
of this column. `STAGED → SUPERSEDED` remains the one sanctioned terminal transition (§3.3's
admission algorithm requires it to stage a replacement), and Plan 005 must be able to walk a
non-manual apply `RUNNING → APPLIED` — the walk `BE-D13`'s original text left ambiguous by letting
`STAGED` stand in for it.

**NULL-safety and the CHECK's exact text (design §3.2, measured against PG 14.24).** A bare
`CHECK (state <> 'STAGED' OR strategy = 'manual')` is not NULL-safe — `false OR NULL` is NULL, and a
CHECK passes on NULL — so the intended CHECK is
`CONSTRAINT staged_requires_manual CHECK (state <> 'STAGED' OR coalesce(strategy = 'manual', false))`,
self-defending rather than resting only on `state_fits_operation`'s `stop` branch (which is the
clause that actually closes the corresponding hole today, and stays load-bearing — only its
`restart` branch's own `STAGED` exclusion becomes redundant, harmlessly, under this rule). The Java
mirror is **`OutcomeService.land`**, not `RequestState.validFor` — `validFor(Operation)` receives no
strategy and cannot express a strategy-dependent rule without a signature change; `land` already
holds `row.strategy()` and gains one guard clause alongside its existing `state_fits_operation`
check. The CHECK itself lands by editing `V002__apply_request.sql` in place, as one more constraint
clause inside the existing `CREATE TABLE` — not a new statement, so `SketchMirrorTest`'s
`assertEquals(6, files.size(), …)`/`assertEquals(24, phase1Statements().size(), …)` and the per-file
statement vector are all unaffected, ruling out a new migration by measurement, not by preference —
per the plan's own Prerequisites ("no database carries these tables yet… that posture must be
revisited by the first plan that ships to a live database"). **A second, later obligation, not
discharged here:** the tracked DDL-replay evidence (`evidence/ddl-replay/`) pins this section's own
SQL-block md5s and fails loudly the moment `V002` changes; the implementing amendment re-records the
baseline through the harness's own `--record-candidate` procedure (`evidence/ddl-replay/README.md`
§"When the design's SQL changes") in a **separate, later commit** of its own, together with
`BE-D17`'s — never the same commit as the CHECK: `replay.py`'s `design_is_clean()` refuses
`--record-candidate` while the design file has uncommitted changes, so the candidate always names a
commit that already carries the CHECK, and the harness README's own step 5 puts the candidate files,
the run's logs, `../ddl-execution-pg14.md`'s update and this changelog row in one ordinary, reviewed
commit that follows it. This design states that obligation; it has since been met — the baseline is
re-recorded against both amendments (commits `90cce61`, `221672d`; see `evidence/ddl-execution-pg14.md`'s
Run 5). **Lands with its
own executable-DDL
prove-it-fires obligation, paired with `BE-D17`'s below:** refuse a non-manual `STAGED`
insert/update by name, accept a manual one — the direct counterpart of `BE-D17`'s four rejection
classes, since both land in the same task and neither obligation is optional relative to the
other's.

**Rationale.** An external (Codex) review of the completed plan 004 found the ambiguity had no
enforcement anywhere it should have: `terminal_has_timestamp` excludes `STAGED` from the non-terminal
set unconditionally, `V006`'s terminal-guard trigger permits only `STAGED → SUPERSEDED`, and
`RequestState.validFor(APPLY)` accepted `STAGED` for every strategy — so `OutcomeService.land(t, id,
STAGED)` on a rolling apply succeeded and then froze immutably forever, with no path to `APPLIED`.
Narrowing the enum instead of the implication (removing `STAGED` as a legal value on non-manual
rows via a second column or a state split) was rejected: `STAGED` is not wrong for `manual`, only
unconditional, and the model already carries `strategy`, so expressing the dependency directly needs
no new column and no new enum value. **This decision changes the meaning of what `BE-D12`/`BE-D13`
specify for the request-state table's terminality; it is a new decision per `I3`, not an edit to
either.** Status: **PROPOSED**, like every other `BE-D*` — the CHECK, the `V002` edit and the
`OutcomeService.land` Java-side mirror have since landed (commit `647d4e9`), but implementation does
not change ratification status.

## `BE-D17` — Resolution evidence is operation-specific and generation-bound ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.4 (the amendment callout, the
two evidence shapes, the intended CHECK specification, and the watermark reconciliation with §2.2
rule 1) — amends `BE-D13`'s `resolution_is_evidence` shape; the CHECK's original text is not
rewritten in place (`I3`).

A `restart` request can reach `APPLY_INDETERMINATE`/`INDETERMINATE_RESOLVED` — `state_fits_operation`'s
`restart` branch admits `APPLY_INDETERMINATE`, mirrored by `RequestState.validFor(RESTART)` — so the
`restart` shape below is a real path, not a hypothetical one. `resolution_observed` on an
`INDETERMINATE_RESOLVED` row takes one of two shapes, discriminated by the resolved request's own
`operation` column (never a caller-asserted flag):

- **`operation = 'apply'`:** `{"stagedGeneration": <number, MUST equal the request's own
  `generation` column, numerically — `20.0` accepted against `generation = 20`, `20.5` refused>,
  "replicas": [<non-empty array of `{"ref", "restarted", "observedHash"}` elements>, …]}` — the same
  `replicas` shape as today, with per-element typing staying the engine's typed resolver.
- **`operation = 'restart'`:** `{"restartsObserved": [<non-empty array of `{"ref", "restarted"}`
  elements — two fields, no `observedHash`, since a restart resolution verifies no generation and
  reads back no config hash>, …]}` — a distinct key, and **no `stagedGeneration` key at all**; a
  restart request carries no `generation` (`apply_has_generation`), so nothing exists for a
  `stagedGeneration` to certify, and the CHECK must refuse the key's mere presence, not merely a
  wrong value. Neither shape is closed against extra top-level keys by the CHECK — that key-exactness
  is the **Java seam's** job (a typed writer, mirroring `ResolutionEvidence`'s own claim to prove "no
  other keys at all"), not the database's.

Only a resolved **`apply`** may raise an apply-generation watermark, and it raises it to the request's
own `generation` — now provably equal to the evidence's `stagedGeneration` — never to a value taken
from the evidence alone. A resolved **`restart`** raises no watermark and moves no fence; the
`operation = 'apply'` gate on `raiseWatermark` is correct and unchanged — what this decision removes
is the meaningless `stagedGeneration` a restart resolution previously had to manufacture to satisfy
the old, operation-blind shape. §2.2 rule 1's existing sentence ("the same transaction that lands
`STAGED`/`APPLIED`/resolved-`'applied'` updates the corresponding watermark") is read, after this
decision, as applying to a resolved-`'applied'` row only when its `operation` is `apply` — the
sentence's own text is left as written per `I3`; this decision is the correction that governs going
forward. Four rejection classes are binding on both the Java boundary and raw SQL: wrong-operation
evidence, a mismatched apply generation, missing required observations, and an invented restart
staged generation. **The Java-boundary half of class (i) is named, not left open:**
`OutcomeService.resolveIndeterminate` — the only method that ever writes the four resolution
columns — is where the operation↔evidence match belongs, checked before it persists anything,
mirroring `land`'s existing before-any-write guards; this forces today's single
`ResolutionEvidence`/`Resolution` shape to split into two operation-typed evidence records (or an
equivalent), whose exact Java shape is the implementing task's to choose. The `ResolutionEvidence`/
`ReplicaObservation` javadoc that described today's pre-amendment, apply-only shape has since been
updated alongside the type split (commit `5f04d79`) — `ResolutionEvidence` is now sealed over
`ApplyResolutionEvidence`/`RestartResolutionEvidence`. This paragraph stays as the record of the gap
this design-only round flagged, not as a claim it remains open.

**The CHECK's exact text, measured against PG 14.24 (design §3.4).** The apply clause compares
`resolution_observed -> 'stagedGeneration' = to_jsonb(generation)` — jsonb-to-jsonb, no `::numeric`
cast — because PostgreSQL does not guarantee `AND`-clause evaluation order and the cast form is
measured to *throw* (`22P02`) instead of refusing (`23514`) when the planner evaluates it before the
type check it was paired with; the cast-free form has no operand left that can throw. **Both
non-empty-array clauses** (`replicas` and `restartsObserved`) compare against `'[]'::jsonb` rather
than calling `jsonb_array_length(...) > 0` — that function itself throws (`22023`) on a non-array
operand and is guarded by exactly the same sibling-`jsonb_typeof`-across-an-`AND` pattern the cast fix
had just rejected as unsafe; measured with a scalar `replicas` value across six clause permutations:
`23514` three times, `22023` three times, for the identical row. `jsonb <> '[]'::jsonb` never throws
regardless of operand type, so with this fix there is no erroring operand anywhere left in the CHECK.
The key-presence test is `jsonb_exists(resolution_observed, 'stagedGeneration')`, not the `?`
operator — functionally identical, but `?` collides with a JDBC positional-parameter marker. The
CHECK lands by replacing the existing `resolution_is_evidence` body in `V002__apply_request.sql` in
place — an edit to an existing constraint clause, not a new statement, so it carries the same
`SketchMirrorTest`-count non-consequence `BE-D16` states; the same revisit-once-live obligation
applies, and the same tracked-DDL-replay-evidence obligation applies too, landing together with
`BE-D16`'s in one `--record-candidate` pass because both amendments change the same `CREATE TABLE`
(design §3.4 for the procedure; not discharged by this decision).

**Rationale.** An external (Codex) review of the completed plan 004 found `resolution_is_evidence`
evidence-*shaped* but not evidence-*bound*: it required only that `resolution_observed ->
'stagedGeneration'` be some number, never that it equal the request's own generation, so evidence
naming generation 21 could resolve a generation-20 request `applied` and raise the watermark to 20
while asserting 21 — the persisted record contradicting the fence it fed. Separately, the shape was
required uniformly across both operations, so a restart resolution — whose request has no generation
at all — had to supply a positive `stagedGeneration` that `raiseWatermark`'s own `operation = 'apply'`
gate then ignored: present, positive, and meaningless. Binding the evidence against a second,
independently-sourced value (e.g. the latest `apply_step_intent` for the placement) was rejected —
`generation` already lives on the same row, so a same-row comparison needs no join and no
three-valued escape beyond the existing `coalesce` discipline; a cross-row source would also reopen
exactly the concurrent-read hazard §2.2 rule 1's own admission-lock design exists to avoid. **This
decision changes the meaning of what `BE-D13` specifies for resolution evidence; it is a new decision
per `I3`, not an edit to `BE-D13`.** Status: **PROPOSED** — the CHECK text above has since landed in
§3.1's fenced sketch, the `V002` migration and the Java `Resolution`/`ResolutionEvidence` types
(commits `5f04d79`, `d15dc4b`), but implementation does not change ratification status.

## `BE-D18` — Resolution evidence is element-complete, not merely evidence-shaped ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.4 (the amendment callout, the
ten predicates, the immutability-gate and throw-hazard measurements, the fix-round correction for
`lax` mode's filter-on-array auto-unwrap escape and the measured trap of reaching for `strict` instead,
the shape-mirroring facts, the Java-side disposition) and §3.1's residual table (the per-element-typing
row, now superseded) — amends `BE-D17`'s `resolution_is_evidence` shape; `BE-D17`'s own text is not
rewritten in place (`I3`).

`resolution_is_evidence`, even after `BE-D17`, validates only the evidence **container**: a non-empty
`replicas`/`restartsObserved` array, with nothing said about its elements. Measured:
`{"stagedGeneration": <matching>, "replicas": [{}]}` and `{"restartsObserved": [null]}` are both
**accepted**. `BE-D18` closes this by adding, to each of `resolution_is_evidence`'s two operation
branches, six (`apply`) or four (`restart`, which has no hash field to validate)
`jsonb_path_exists(…, silent => true)` JSONPath predicates: **element type-check** (a `replicas`/
`restartsObserved` element's own `.type()` must be `"object"` — added in a fix round after an
independent review measured that the well-formedness filter below, applied directly to an
array-valued element, auto-unwraps under `lax` mode and reports "no violation" for an empty array or
an array of well-formed inner objects; `.type()` never unwraps, so filtering on its scalar result
closes the escape without the regression `strict` mode would introduce — see the design for the full
mechanism and the measured trap), **element well-formedness** (the element must be an object with a
string, non-blank `ref` and a boolean `restarted`; for `apply` only, it must additionally carry the
`observedHash` key — present, though its *value* may be JSON `null` — because `Evidence.writeApply`
always emits that key and a two-key element is a shape this build never produces), **element
key-set** (no key outside `{ref, restarted, observedHash}` for `replicas`, `{ref, restarted}` for
`restartsObserved` — the latter is what makes "a restart observation has no `observedHash`" a
DB-enforced fact, not merely a Java-typed one), **`observedHash` type-check** (`apply` only — the
same array-unwrap escape, closed the same way, for `observedHash: []`/`["…"]`/`[null]`), **hash
format** (`apply` only — when the `observedHash` key's value is not JSON `null`, it must be a string
matching `^[a-z0-9]+:[0-9a-f]{16,128}$`, mirroring `ValueGrammar.HASH` verbatim), and **top-level
key-set** (`resolution_observed` may carry no key outside `{stagedGeneration, replicas}` for `apply`,
`{restartsObserved}` for `restart`) — closing the key-exactness gap `BE-D17`'s own text left to the
Java seam alone. `silent => true` is load-bearing throughout: `.keyvalue()` throws on a non-object
array element even in `lax` mode, and the four-argument form is what turns that throw into a clean
`false` — but it is also, measured this fix round, the dominant source of the NULL `coalesce` guards
against (not a NULL `target`, as this decision first stated): every clause that can hit an internal
structural error fails open on that error, safely only because an independent clause (the `.type()`
predicates, which cannot throw) rejects the same malformed input by a different path — clause
independence is therefore a correctness requirement this design now states explicitly, not an
implementation detail.

Every predicate is measured (developer, PG 14.24) working across 53 apply-branch legs and 20
restart-branch legs, zero regressions — superseding this decision's original 13/8 counts, which
predated the fix-round's array-escape predicates. The four `jsonb_path_*` functions and operators
used are measured `IMMUTABLE` (`provolatile = 'i'` in `pg_proc`) — every `_tz` variant, correctly
excluded, is `s`.

**One Java change, no more.** `ReplicaRef`, `ValueGrammar.HASH`, and the closed-record shapes of
`ApplyResolutionEvidence`/`RestartResolutionEvidence` (landed under `BE-D17`) already enforce, at
construction time, everything these predicates enforce at the database except one: `Evidence.
readResolutionEvidence` currently reads a missing `observedHash` key and a present-but-`null` one as
the same Java `null` (`hash.isNull() || hash.isMissingNode() ? null : hash.asText()`), silently
tolerating on read a shape `Evidence.writeApply` never writes and this amendment now refuses at the
database. The implementing task must change the `isMissingNode()` branch from a silent coercion into
the same exception `Evidence.read()` already throws elsewhere in this class —
`IllegalStateException("sm_data." + column + " does not hold JSON this build wrote")`, named here so
two implementers do not each invent a different one — mirroring the "corruption report, not a
caller-recoverable failure" posture that exception already carries, while the `isNull()` branch is
untouched; no existing test depends on the current coalescing, so the change is additive. Everywhere
else, `BE-D18` closes only the one gap construction-time enforcement cannot reach: a write that
bypasses it entirely through `sm_dp_runtime`'s existing column-scoped `UPDATE` grant on
`resolution_observed`. **`ReplicaRef`'s mirror of Java's `isBlank()` is a one-sided containment, not
an equivalence, corrected this fix round:** PostgreSQL's `\s` character class is a proper subset of
Java's `Character.isWhitespace` (missing U+001C–U+001F), so the DDL is never *stricter* than
`ReplicaRef` — the required direction — but is, in that one edge case, more lenient than it.

**Landing, as `BE-D16`/`BE-D17`'s own ruling extends a third time:** the predicates are appended, in
place, to the existing `resolution_is_evidence` CHECK body in `V002__apply_request.sql` and the
design's §3.1 SQL block, to identical text — an edit to an existing clause, not a new migration, per
`SketchMirrorTest`'s file/statement counts and the plan's own Prerequisites (revisit obligation once a
live database exists). The tracked DDL-replay baseline (`evidence/ddl-replay/`) is unaffected by this
decision, whose own commit touches no SQL; it goes red, expectedly, the moment the implementing task
edits `V002`, and is re-recorded together with the new legs (including the three boundary negatives
for the `observedHash`-presence rule) in that task's own commit, per `evidence/ddl-replay/README.md`
§"When the design's SQL changes."

**Rationale.** A fifth review (this repair wave's R5) measured that three prior reviews had read a
container check as satisfying an element requirement — `resolution_is_evidence`'s comment says
"evidence-shaped, deliberately not claimed evidence-complete," and that gap is exploitable precisely
because `sm_dp_runtime` holds `UPDATE` on the column it guards: a migration defect or a direct-SQL
bypass can write a generation-matching apply resolution with no replica reference at all, which
`OutcomeService` then treats as grounds to raise the watermark — an audit record asserting an
observation that does not exist. Revoking the grant and routing every resolution through a routine
(option (b)) was ruled against, not measured against: it relocates enforcement outside the
column-scoped-grant idiom this table already uses everywhere else, for no benefit a CHECK does not
already give more cheaply. Requiring `observedHash`'s mere *presence*, not only typing its value, was
a mid-review correction to this same decision (an earlier draft accepted an absent key as a third
legitimate shape): `Evidence.writeApply` always emits the key, so a two-key element is a wire shape
this build never produces, and admitting it at the database would let one model value
(`ApplyResolutionEvidence.observedHash() == null`) travel as two different `resolution_observed`
shapes — exactly the "missing required observation" this amendment exists to reject. **This decision
changes the meaning of what `BE-D17` specifies for `resolution_observed`'s key-exactness (from "the
Java seam's job" to "DB-enforced, in addition to the Java seam"); it is a new decision per `I3`, not an
edit to `BE-D17`.** Status: **PROPOSED**, like every other `BE-D*` — this decision is design-and-
decision-register only; the CHECK, the `V002` edit, the `Evidence.readResolutionEvidence` fix and the
DDL-replay re-record are the next task's.

## `BE-D19` — `ReplicaRef` and the DDL enforce one shared, locale-independent grammar

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.4 (the flag on the superseded
"safe direction" passage, the `BE-D19` amendment callout, the R1 fix round withdrawing
`ValueGrammar.VALUE` and adopting `ValueGrammar.REPLICA_REF` with its primary-source citations and
empirical measurements, the full enumeration of what stops being accepted, the exact DDL and Java
text, the executable-DDL/Java obligations extending obligation (j) a third time, and the
DDL-replay/landing obligations) and §3.1's residual table (a new row for `ref`-content grammar parity)
— amends `BE-D18`'s mirror-posture reading of `ReplicaRef` and `resolution_is_evidence`'s `\S` clause
for `ref`; `BE-D18`'s own text is not rewritten in place (`I3`), only flagged.

A post-handover external review measured, deterministically, that `BE-D18`'s own "one-sided
containment is the safe direction" conclusion — carried forward from an earlier fix round — reasons
only about the WRITE direction (a `ref` the DDL accepts that Java's constructor would reject) and
never asks the converse. The converse is real: `sm_dp_runtime` holds column-scoped `UPDATE` on
`resolution_observed` (§3.1's grant, unchanged), so a raw write is never filtered through
`ReplicaRef`'s constructor at all. Measured on live PostgreSQL 14.24: PostgreSQL's `\S` character
class is `TRUE` for codepoints 28–31 (U+001C–U+001F), which Java's `Character.isWhitespace` — and
therefore `String.isBlank()` — treats as whitespace. A `resolution_observed` document whose `ref` is a
lone U+001C is **accepted and committed** by `resolution_is_evidence`, and then **throws** in
`Evidence.readResolutionEvidence` (`new ReplicaRef(...)` → `IllegalArgumentException`, because that
same constructor's `isBlank()` check calls the identical codepoint blank on the read side): a poison
row, written and unreadable — the same class `BE-D18` closed for element *shape*, left open for `ref`
*content*. `\s`/`\S` is additionally locale-dependent (PostgreSQL resolves it via the server's
collation), so narrowing the regex to chase Java's exact whitespace set would not be a stable fix even
setting the write-direction gap aside. **The binding acceptance criterion this decision exists to
satisfy:** the raw-runtime path must not be able to create a row that `Evidence.readResolutionEvidence`
rejects on read.

**R1 fix round — the first pass's `ValueGrammar.VALUE` candidate is WITHDRAWN, disqualified by direct
proof against primary sources, per explicit developer direction that it must be proven rather than
assumed.** Runtime refs are infrastructure identifiers, not display labels: the grammar must reject
line breaks, C0 control characters, whitespace-only values and unconstrained Unicode/CJK, while
preserving every valid identifier the current and near-term adapter runtimes (Docker, systemd,
Kubernetes) actually use, plus the `@placement` sentinel. `ValueGrammar.VALUE` fails the preservation
half on two independently disqualifying, primary-source-verified facts (`I2`): **length** —
Kubernetes RFC 1123 DNS subdomain names (Pod/Deployment/Service names) run to 253 characters, past
`VALUE`'s 128-character cap, verified against
[kubernetes.io/docs/concepts/overview/working-with-objects/names](https://kubernetes.io/docs/concepts/overview/working-with-objects/names/);
and **backslash** — a `systemd-escape`d unit name (the form an arbitrary device or mount path becomes)
carries a literal backslash, absent from `VALUE`'s class entirely, verified against
[man7.org/linux/man-pages/man5/systemd.unit.5.html](https://man7.org/linux/man-pages/man5/systemd.unit.5.html)
(which also states the 255-character unit-name maximum this decision's new grammar adopts as its own
bound). Both facts disqualify `VALUE` outright; the response is a dedicated explicit grammar, not a
compromise on `VALUE` itself.

**The ruling: a new, dedicated grammar — `ValueGrammar.REPLICA_REF = ^[A-Za-z0-9._:/@+\\-]{1,255}$`
— not a widened `VALUE`.** `VALUE`/`HASH` are untouched: `ValueGrammar`'s own javadoc states they are
`I6` shape controls specifically because they exclude backslash (among other characters), and
`RuntimeRef.of`/`ErrorClass` depend on that exclusion; widening `VALUE` itself would have weakened
those, unrelated columns. `REPLICA_REF` is `ReplicaRef`'s grammar alone. Verified against primary
sources for every runtime the direction named: Docker container names (`moby/moby`'s
`daemon/names/names.go`: `[a-zA-Z0-9][a-zA-Z0-9_.-]`, a strict subset) and image digests/tags
(`distribution/reference`'s `regexp.go`); systemd unit names (letters, digits, `:`, `-`, `_`, `.`,
`\`, plus a single `@` for the template/instance form, max 255 chars); Kubernetes RFC 1123
subdomain/label names (both subsets, within 255); and the `@placement` sentinel (trivial). Empirically
measured — not merely derived — identical accept/refuse behavior on both a throwaway `postgres:14`
container (`PostgreSQL 14.24`, matching this project's pinned floor) and a standalone
`java.util.regex.Pattern`, across 17 cases: every runtime-shaped identifier above accepts, together
with the sentinel, a bare backslash, and the 255-character boundary; empty, whitespace-only, an
embedded newline, a lone U+001C, a lone U+001F, a CJK string, both of `SchemaConstraintsIT`'s existing
hostile-ref literals (unchanged from the withdrawn draft — they carry other excluded characters
besides the backslash the grammar now admits), and a 256-character string all refuse. **Future
Unicode/display-name support is explicitly out of scope for v1** and is not implied by admitting a
literal backslash for infrastructure-identifier purposes; any such support is a separate design
amendment.

**What stops being accepted, relative to today — unchanged from the withdrawn draft's own finding,
re-verified rather than carried over on trust.** Exactly two literals in
`SchemaConstraintsIT.everyLegitimateApplyReplicaShapeIsAcceptedByResolutionIsEvidence` flip from
accepted to refused: a `ref` carrying quotes, a backslash, a space, `=` and the accented character `é`
(refused by the OTHER characters, not the backslash, which `REPLICA_REF` now admits), and a `ref`
carrying an embedded newline. No CJK literal exists in the current suite — a since-corrected
description of this defect had suggested one; this decision states what the suite actually contains,
per `I2`. Nothing the withdrawn `VALUE`-based draft would have accepted becomes newly refused under
`REPLICA_REF` — its class is a strict superset (`VALUE`'s class plus one escaped backslash) and its
length bound is strictly wider (255 vs 128).

**The exact DDL text — one substring, both operation branches, empirically measured, not only
derived.** `resolution_is_evidence`'s element well-formedness clause contains `@.ref like_regex "\\S"`
once per branch (`V002__apply_request.sql:94,118`); the ruling replaces that exact substring, in both
occurrences, with `@.ref like_regex "^[A-Za-z0-9._:/@+\\\\-]{1,255}$"` — four backslash characters at
the SQL/jsonpath source-text level represent the one literal-backslash class member, per two
verified/measured escaping layers: PostgreSQL's `standard_conforming_strings` defaults to `on` since
9.1 (the outer single-quoted SQL string adds no escaping of its own — verified against
[postgresql.org/docs/14/sql-syntax-lexical.html](https://www.postgresql.org/docs/14/sql-syntax-lexical.html)),
and the SQL/JSON Path Language's own rule that "any backslashes you want to use in the regular
expression must be doubled" because `like_regex`'s pattern is itself a jsonpath string literal
(verified against
[postgresql.org/docs/14/functions-json.html](https://www.postgresql.org/docs/14/functions-json.html)).
The full four-backslash text, embedded in the complete apply-branch well-formedness predicate, was run
against a live PostgreSQL 14.24 container in this session and produced the correct accept/refuse
result for every case above, including the backslash- and length-disqualifying cases. **S2 must still
re-run this exact text before landing it** — this session's measurement used an isolated container and
hand-built fixtures, not `V002` itself. Full text for both branches is in the design amendment. Because
this is a literal-text substitution inside an existing predicate, it carries none of the
file/statement-count consequences a new migration would (`SketchMirrorTest`'s `6`/`24` counts are
unaffected), exactly as `BE-D16`/`BE-D17`/`BE-D18`'s own in-place text edits were.

**The exact Java change — one class, two constants, one constructor.** `ValueGrammar.java` gains
`REPLICA_REF = Pattern.compile("^[A-Za-z0-9._:/@+\\\\-]{1,255}$")` (again four backslash characters at
the Java source-text level — one doubling for the Java string literal, one for the regex engine's own
bracket-escape, empirically confirmed via a standalone `Pattern.compile` run whose `.pattern()` matched
every case in the same 17-case matrix identically to PostgreSQL), with its own javadoc stating it is
deliberately wider than `VALUE` and is `ReplicaRef`'s alone. `ReplicaRef`'s compact constructor changes
from `Objects.requireNonNull` plus a hand-written `isBlank()` throw to
`ValueGrammar.require(ValueGrammar.REPLICA_REF, value, "a replica ref", …)` — full text in the design
amendment. `ValueGrammar` is package-private in the same package as `ReplicaRef`, so no visibility
change is needed, and `ValueGrammar.require` already refuses without echoing the value (`I6`). No test
in this suite pins the old literal message text (verified by search), so this is a behavior-visible
change with no pinned-message regression, applying identically at every call site that constructs a
`ReplicaRef`.

**Executable-DDL and Java obligations extend obligation (j) a third time** (§3.3, design amendment),
under a standing rule this decision adds for every future leg too — a test result must never be filed
as an environmental flake without first explaining why the observed production behavior could not have
caused it, the same failure mode that let `BE-D18`'s own "safe direction" claim stand unchallenged:
raw-SQL and Java parity negatives for a lone U+001C and a lone U+001F in both operation branches,
beside positive controls that ordinary identifiers accept; the two disqualifying cases (a
>128-character Kubernetes-shaped identifier, a backslash-carrying systemd-shaped identifier) now proven
ACCEPTED at both Java and raw SQL, named as the cases this fix round exists to admit; the two flipped
`SchemaConstraintsIT` literals moving from accepted to refused, re-verified against the wider class;
empty, whitespace-only and CJK refused by name; and both length boundaries (255 accepted, 256 refused)
exercised at both edges. None are discharged by this task.

**Landing and DDL-replay — same ruling as `BE-D16`/`BE-D17`/`BE-D18`, a fourth time.** The predicate
substitution lands in `V002__apply_request.sql` and the design's §3.1 SQL block, to identical text, in
place; `ValueGrammar.java` gains the new constant and javadoc (updating its class-level "two character
grammars" count to "three"); `ReplicaRef.java`'s constructor changes as above — all in the implementing
task's own commit, per the Prerequisites language each predecessor amendment already carried. The
tracked DDL-replay baseline is untouched by this decision's own commit, which changes no SQL and runs
no harness; it goes red, expectedly, only once the implementing task edits `V002`, and is re-recorded
together with the new legs named above, per `evidence/ddl-replay/README.md` §"When the design's SQL
changes."

**Rationale.** Tuning the DDL's regex to match Java's exact `Character.isWhitespace` set (the
alternative this decision's first pass implicitly considered) would still have left the mirror
locale-dependent and required re-deriving that set by hand — a maintenance hazard the chosen approach
(express the POSITIVE character set directly, on both sides, from verified runtime grammars) avoids
entirely. Widening the shared `ValueGrammar.VALUE` constant itself, rather than adding a dedicated one,
was considered and rejected: `VALUE`'s exclusion of backslash is load-bearing for `RuntimeRef`/`ErrorClass`,
columns this decision has no reason to touch. **This decision changes the meaning of `BE-D18`'s own
reading of `ReplicaRef`'s mirror posture (from "one-sided containment is safe because it is the
required direction" to "the write-direction argument alone is incomplete; a dedicated shared grammar,
verified against the runtimes actually in scope, is required"); it is a new decision per `I3`, not an
edit to `BE-D18`.** Status: **PROPOSED**, like every other `BE-D*` — this decision is
design-and-decision-register only; the `V002` edit, `ValueGrammar.java`, `ReplicaRef.java`, the test
moves and the DDL-replay re-record are the next task's.

## `BE-D20` — At most one OPEN attempt per intent, DB-enforced

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.4 (the `BE-D20` amendment
callout, the exact index DDL and its landing point in `V005`, the constraint-naming discipline that
tells the two conflicts apart, the sequential-and-concurrent obligations, and the DDL-replay/landing
obligations), §3.3 (new obligation (v) and the `attempt_open_fence` addition to the Indexes bullet) and
§3.1's residual table (a new row for the one-open-attempt invariant) — amends `BE-D13`'s attempt model;
`BE-D13`'s own text is not rewritten in place (`I3`), only flagged.

`AttemptService.open` counts open rows for an intent and inserts `attempt_no = count + 1`; the only
database fence today is `UNIQUE (intent_id, attempt_no)` (`V005__apply_attempt.sql:31`), which refuses
a duplicate only when two openers compute the identical number. A post-handover external review
measured, deterministically — no race required — that once attempt 1 **commits**, a second caller
reads `count = 1`, inserts `attempt_no = 2`, and that insert is **accepted**: two rows with
`outcome IS NULL` exist for one intent, and two adapters can act on the same step under the belief each
is the authoritative try. This breaks an assumption §2.2 rule 0 ("write before act," committing "the
attempt row for the try about to run" before the adapter is touched) and rule 4 (bounded, non-duplicated
retry) both implicitly make: that exactly one attempt is ever "the try" in flight. The same review
measured that a partial unique index `ON (intent_id) WHERE outcome IS NULL` refuses the second open
while still allowing the legal retry once the prior attempt is closed — the distinction the numbering
UNIQUE alone cannot draw.

**The ruling: a second, independent partial unique index, `attempt_open_fence`, alongside the existing
numbering UNIQUE — not a replacement for it.** This mirrors, in mechanism, §3.3's three existing
partial-unique fences (`apply_inflight_fence`, `stop_inflight_fence`, `staged_fence`): a
`WHERE`-qualified unique index over the column that must be singular among open rows. Exact DDL:

```sql
CREATE UNIQUE INDEX attempt_open_fence ON sm_data.apply_attempt (intent_id)
    WHERE outcome IS NULL;
```

landing in `V005__apply_attempt.sql` immediately after its existing `attempt_prune` index and in the
design's §3.1 SQL block at the corresponding point. **Unlike `BE-D16`/`BE-D17`/`BE-D18`'s in-place text
edits, this is a new statement**: `SketchMirrorTest`'s `phase1Statements()` count (`24`) becomes `25`;
its file count (`6`) is unaffected. Separately verified: the three existing partial-unique fences are
plain `CREATE UNIQUE INDEX … WHERE …` statements, recorded by PostgreSQL in `pg_index`/`pg_class`,
never `pg_constraint` — so `RefusalShapesIT.theDesignsOwnNamedConstraintsAreAllPresent`'s count of `24`
(CHECK constraints only) is unaffected by this new index.

**How the engine tells the two conflicts apart.** *(This decision's own two — a later fix layers a
third, pre-insert path onto `AttemptService.open` that also raises `ConcurrentAttempt` but names no
PostgreSQL constraint at all; "both" below names these two, not every provenance the shipped method can
raise.)* `Constraints.java` gains
`ATTEMPT_OPEN_FENCE = "attempt_open_fence"`; `AttemptService.open`'s catch clause extends
`e.namesAnyOf(Constraints.ATTEMPT_NO_UNIQUE)` to
`e.namesAnyOf(Constraints.ATTEMPT_NO_UNIQUE, Constraints.ATTEMPT_OPEN_FENCE)` — both continue to raise
`ConcurrentAttempt`, because the correct caller action is identical either way: do not act, resume will
read the row that won. The two names are not the same conflict, stated so a future editor does not read
the extended call as one broadened check: `ATTEMPT_NO_UNIQUE` fires only on an exact `attempt_no`
collision; `ATTEMPT_OPEN_FENCE` fires whenever the intent already has an open row regardless of the new
row's number, and it is the **only** one of the two that can fire for this decision's own defect (a
non-colliding sequential second open). Which name a genuine numbering race reports is not asserted by
this decision — the implementing task's concurrent-callers test must measure and record it, exactly as
§2.2 rule 2 already does for the analogous ambiguity between the two `apply_step_intent` UNIQUEs.

**Obligations, replacing two non-deterministic tests, not merely re-running them.** A new obligation
(v) requires: a deterministic sequential second-open, refused by `attempt_open_fence`'s name (never
`apply_attempt_intent_id_attempt_no_key`, since the numbers differ), beside the positive control that
the retry succeeds once the prior attempt is closed; and a concurrent-callers test that is now
deterministic **regardless of whether the two openers collide on the same number or serialize onto
different ones** — closing the exact gap that made `AttemptIT.twoConcurrentOpensForOneIntentLandExactlyOneAttemptAndTheLoserIsToldSo`
and `EngineTransactionBoundaryIT.aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback`
non-deterministic by construction (both currently assert exactly one attempt row lands, with commentary
stating the test "proves nothing" if the two callers serialize instead of colliding — verified by
reading both files). That caveat is retired, not merely left beside a now-guaranteed outcome; both
tests must additionally name which constraint(s) fired. **A standing rule, in this decision's own
terms:** a non-deterministic result from any of these tests is never to be recorded as a flake unless
the reachable production behavior behind it is explained — `EngineTransactionBoundaryIT`'s own
intermittent failure was filed exactly that way, on the strength of a green re-run, and that is why
this decision exists at all. Once the three tests above are deterministic by construction, a failure
in any of them is a fence regression, not noise.

**Landing and DDL-replay.** The index, `Constraints.ATTEMPT_OPEN_FENCE`, `AttemptService.open`'s
extended classification, and the two test replacements land together — a landed index with no engine
classification for it would make `ConcurrentAttempt` stop firing correctly for exactly the case this
decision exists to fix. The tracked DDL-replay baseline is untouched by this decision's own commit,
which changes no SQL and runs no harness; the implementing task's re-record must expect a changed
statement/command-tag count for the Phase-1 group (one more `CREATE INDEX` tag), not only a changed
md5, per `evidence/ddl-replay/README.md` §"When the design's SQL changes."

**Rationale.** Revoking `sm_dp_runtime`'s `INSERT`/`UPDATE` on `apply_attempt` and forcing every open
through a stored routine was not measured against: it would relocate enforcement outside the
column/grant idiom this schema already uses everywhere else (the same reasoning `BE-D18`'s own
rationale gave for a structurally identical choice), for no benefit a partial unique index does not
already give more cheaply. **This decision adds a fence to `BE-D13`'s attempt model that did not exist
before; it is a new decision per `I3`, not an edit to `BE-D13`.** Status: **PROPOSED**, like every
other `BE-D*` — this decision is design-and-decision-register only; the index, `Constraints.java`,
`AttemptService.java` and the two test replacements are the next task's.


## `BE-D21` — The `RuntimeAdapter` seam's literal signature: `TargetReplica` and a caller-owned `RenderedConfig` ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §2.1 (the amendment callout, the
accepted interface block, and the two measured reasons) — amends `BE-D12`'s printed `RuntimeAdapter`
signature; `BE-D12`'s own text is not rewritten in place (`I3`), only flagged.

`BE-D12` §2.1 prints `materializeConfig(ReplicaRef replica, long generation)` and states beside it that
*"`ReplicaRef` carries its `PlacementRef` …; adapters never see bare strings"*. Neither half is
implementable as written against the code plan 004 shipped, and plan 005's design review found both by
attempting to write the first adapter:

- **`ReplicaRef` is not, and may not become, a composite.** As built it is
  `record ReplicaRef(String value)`. Its content grammar is mirrored into `resolution_is_evidence` by
  `BE-D19`, and it is serialized as the `ref` key that `BE-D18`'s per-element **key-set** predicates
  close over — `{ref, restarted, observedHash}` for `replicas`, `{ref, restarted}` for
  `restartsObserved`, where the second set is what makes "a restart observation has no `observedHash`"
  a DB-enforced fact. A second component would change an evidence wire shape the database now holds
  rows to, in the one `CREATE TABLE` whose CHECK has already been re-recorded through the DDL-replay
  harness three times (`BE-D16`/`BE-D17`, then `BE-D18`). **The requirement `BE-D12` stated is met, at
  the seam, by a pair:** `record TargetReplica(PlacementRef placement, ReplicaRef ref)`. Adapters still
  never see a bare string; the persisted vocabulary is untouched.
- **The rendered bytes must have exactly one owner, and the printed signature gives them none.**
  `materializeConfig` is specified as persisting *"generation `G`'s **rendered bytes**"*, and §3.5
  places rendering before apply — the ladder's `rendered` rung is *"announced at render time (DP,
  pre-apply)"*. An adapter with no parameter for them must render its own, which is a second renderer
  per runtime with no goldens watching it (exactly the shape `BE-D10` rejected codegen and a second
  mapper for), and it is free to disagree with the `config_hash` the intent row bound **before** the
  adapter was invoked under §2.2 rule 0 — the disagreement the intent row exists to make impossible
  across a crash. The accepted form passes
  `record RenderedConfig(byte[] bytes, String hash)`, whose compact constructor recomputes the hash and
  refuses a mismatched pair, so "what the engine bound" and "what the adapter wrote" are the same value
  by construction rather than by convention.

**The accepted signature**, in full, is the block in §2.1's amendment callout: `prepare` and
`rollbackOrMarkIndeterminate` unchanged and placement-scoped; `materializeConfig`, `startOrUpdate`,
`stop` and `status` taking `TargetReplica`; `materializeConfig` additionally taking `RenderedConfig`.
`PlacementRef` is `ScopeKey` minus the instance-wide sentinel, refused by construction — an
instance-wide request is fanned out into its placements before the seam is reached, which §3.5 already
requires of the wire events for the same reason. **Six operations, unchanged names, unchanged
semantics table, unchanged "no `healthGate` verb" ruling.** This decision changes the parameter types
and adds one parameter; it does not add, remove or re-scope an operation.

⚠️ **Two clauses above are amended, same day, by later decisions; they are flagged here and not
rewritten (`I3`).** `BE-D22` replaces the sixth operation with two (`assessRollback` + `restore`), so
"six operations… does not add an operation" reads as **seven** going forward. `BE-D23` makes the
"fanned out into its placements" clause aspirational: the v1 engine is single-placement and refuses an
instance-wide request before admission, because `apply_step_intent`'s identity cannot express
per-placement steps for one request. Everything else in this decision stands.

**Implementation posture, stated rather than left to be inferred.** Implementation is **not gated on
ratification of this decision**, and that is the same posture `BE-D16`, `BE-D17` and `BE-D18` each
carry: the whole `BE-D*` family is PROPOSED, plan 004 was built against it, and each of those
amendments records *"implementation does not change ratification status."* The gate that does apply to
plan 005 is **G2**, which this seam does not touch — no principal, no endpoint, no `sm_control` object.
What ratification decides is not whether the seam may be built but **whether it stays**: this signature
is inherited by every future adapter (`SystemdAdapter`, `KubernetesAdapter`) and by the contract-test
kit every adapter must pass, so a later reversal is a change to all of them at once. That is precisely
why it is a citable decision rather than a plan footnote, and why the architect group is told about it
now rather than at the first rejected pull request.

**On the draft amendment policy.** This family's header permits in-place review corrections *"until
something is built or ratified against this family"*, and nothing is built against §2.1 yet — no
`RuntimeAdapter` exists in `backend/`. An in-place edit would therefore have been permitted by the
letter of that policy. It is declined anyway: [`../../plans/backend/005-runtime-adapter-and-docker-apply.md`](../../plans/backend/005-runtime-adapter-and-docker-apply.md)
is reviewed and committed, its first task builds this seam, and a governing interface that every future
adapter inherits should carry an ID a reviewer can cite rather than a diff they must find.

**Rationale.** The alternative — implement `BE-D12`'s printed block literally — was rejected on
measurement, not taste: it requires either widening `ReplicaRef` (a change to a database-enforced
evidence shape, for a seam convenience) or having each adapter render its own bytes (a second renderer
per runtime, and a hash the intent row cannot hold it to). A third alternative, leaving the deviation
as a plan-level note to be reconciled later, was rejected because the plan's own Task 13 would then be
the first place a reviewer learned that the shipped seam differs from the design — after eleven tasks
had been built on it. **This decision changes the meaning of what `BE-D12` specifies for the
`RuntimeAdapter` signature; it is a new decision per `I3`, not an edit to `BE-D12`.** Status:
**PROPOSED**, like every other `BE-D*` — this decision is design-and-decision-register only; the
interface, `TargetReplica`, `RenderedConfig` and the contract-test kit are plan 005 Task 1's and Task
2's.


## `BE-D22` — The exhaustion path is two operations, because the journal already has two steps ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §2.1 (the `BE-D22` amendment
callout, the two replacement operations, the journal shape and the absence case) — amends `BE-D12`'s
`rollbackOrMarkIndeterminate` operation and, transitively, `BE-D21`'s accepted block; neither
decision's own text is rewritten in place (`I3`), only flagged.

`BE-D12` §2.1 gives the exhaustion path **one** operation that both reads and writes:
*"restore the prior persisted config on the affected replicas (`FAILED_ROLLED_BACK`) or report an
untouched target (`FAILED_NO_EFFECT`)… or mark the apply `APPLY_INDETERMINATE`"*. `BE-D13` §3.1 gives
the same path **two** journal steps, with different rules: `ROLLBACK_ASSESS` is *"the hashless
assessment that restored nothing"*, and `ROLLBACK` is a config-writing step that
`hash_on_config_steps` **requires** to carry a `config_hash`, because *"a restorative rollback knows
its target generation before acting"*. §3.1's engine-invariant table states the consequence outright:
*"a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`"*, enforced by *"adapter contract tests, per
adapter"*.

**An engine holding one operation cannot satisfy both.** §2.2 rule 0 requires the intent and the
attempt to be committed **before** the adapter is invoked, and at that moment the engine does not know
whether the adapter will restore, nor the hash it would restore to. Plan 005's first draft was
measured against this by an external review and found to take the worst available branch — open
`ROLLBACK_ASSESS` with a null hash, call the operation, receive a restorative result, and land
`FAILED_ROLLED_BACK`: a restore journalled as an assessment. The other two branches are no better.
Opening `ROLLBACK` **after** the destructive action breaks rule 0 and leaves a crash in the window
unable to tell whether the restore happened — the precise guess `APPLY_INDETERMINATE` exists to
refuse. Guessing a `config_hash` to satisfy the CHECK would put a value in an audit column that
describes nothing.

**The persistence model was right and the seam was wrong.** The sixth operation is replaced by two:

```java
    RollbackAssessment assessRollback(PlacementRef placement, long generation);   // read-only
    RestoreResult      restore(PlacementRef placement, long generation, String targetHash);
```

- `assessRollback` is **non-mutating by contract** and journals `ROLLBACK_ASSESS` — hashless and
  placement-scoped, exactly as `hash_on_config_steps` and `placement_scoped_steps` require. Its
  non-mutation is the executable form of §3.1's step-label-truthfulness invariant, and it lands in the
  adapter contract-test kit — the enforcement home §3.1 already names.

  **It classifies against journal-known values only, and the engine supplies them.** The signature is
  `assessRollback(PlacementRef, long generation, String intendedHash)`, where `intendedHash` is the
  `config_hash` the `MATERIALIZE` intent already bound — a row, not an inference. Exactly two values
  are recognized for a replica: the **rollback target hash** read back from the retained pre-image,
  and that replica's **intended hash**. A placement every one of whose replicas reads one of those two
  is `Restorable(targetHash)` and proceeds; a replica whose config is unreadable, or holds a **third,
  unknown** value, is `Unknowable` and the apply reaches `APPLY_INDETERMINATE` with nothing
  overwritten. An earlier draft of this decision had the adapter return `Diverged()` whenever the
  replicas' hashes merely differed, which is wrong in the ordinary case this path exists for: after a
  partial materialize, replica A holds the new generation and replica B still holds the old, the two
  differ, and that is precisely the mixture a rollback is *for* — not divergence. **`CONFIG_DIVERGED`
  is never reached merely because the expected old and new states coexist after a materialize
  failure.** Divergence is a verdict about *lifecycle*, which `SM-D15` defines as "some restarted onto
  `G`, some still run the prior generation"; the engine takes it from its own journal — whether any
  `LIFECYCLE` attempt succeeded — and never from a config-hash comparison. `RollbackAssessment` is
  therefore three variants: `Restorable(targetHash)`, `NothingToRestore()`, `Unknowable(errorClass)`.
- Only on `Restorable` does the engine open a **`ROLLBACK` intent carrying `targetHash` as its
  `config_hash`**, and only then call `restore`. The restore target is therefore durable **before** the
  destructive act, which is what makes resume decidable. **A restart never guesses whether a restore
  occurred.**

  **`restore` re-checks its own target before it writes, and writes nothing if it cannot.** The
  signature is `restore(PlacementRef, long generation, String targetHash)`, and the first thing it
  does is hash the retained pre-image and compare it to `targetHash`. A pre-image that has gone
  missing, or that no longer hashes to the value the assessment recorded, means the world moved
  between assess and restore — so the adapter **writes nothing** and reports a bounded classifier
  (`rollback.preimage.vanished`, `rollback.preimage.altered`) that the engine maps to
  `APPLY_INDETERMINATE`. Without this check the hash on the `ROLLBACK` intent would be a claim about a
  file nobody re-read, and a restore could overwrite live configuration with bytes that are no longer
  the recorded target.

  **Resume re-issues a `ROLLBACK` only against journal-recognized values.** A `ROLLBACK` intent with an
  open attempt is resolved by reading each replica's hash back and comparing it against **two** row-held
  values: the intent's own `config_hash` (the target) and that replica's `MATERIALIZE` intent
  `config_hash` (the intended). Every replica at the target means the restore landed; a mixture of the
  two known values means it landed partially and re-issuing is safe, because both values are ones this
  apply itself wrote; **anything else — unreadable, or a third unknown value — is
  `APPLY_INDETERMINATE`, and the engine never overwrites it.** That last clause is the whole point: a
  human or a competing writer who changed the file while the DP was down must not have their write
  erased by a rollback that resumed on autopilot (`B/D12` 🔒, *never silently auto-revert a human*).
- After a successful restore the engine issues one **`VERIFY`** step per replica against the same
  hash. `VERIFY` is replica-scoped and hash-bearing, so each replica's read-back lands in its own
  `apply_attempt.observed_hash`. This is where the per-replica restored evidence becomes **durable**.
  The values `assessRollback` and `restore` return are transient — the engine branches on them and
  they are then gone — and no plan may describe them as the record. §3.2's `CONFIG_DIVERGED` row
  already says where the record is: *"per-replica evidence in `attempt` rows and
  `resolution_observed`"*.

**Terminal mapping**, with no unrouted path: `NothingToRestore` → `FAILED_NO_EFFECT`; `Unknowable` →
`APPLY_INDETERMINATE`; `Restorable` → restore, then `VERIFY` per replica, and the verify verdict is
taken against the same two journal-known values — **every** replica at `targetHash` →
`FAILED_ROLLED_BACK`; every replica at `targetHash` **or** its intended hash, but not all at the
target → `CONFIG_DIVERGED` (an incomplete restore: the config is genuinely not uniform, and the
`VERIFY` attempts are the per-replica evidence); **any** replica unreadable or holding a third value →
`APPLY_INDETERMINATE`.

**One bounded case, decided rather than deferred.** Where the prior state was the **absence** of a
file, the restore target has no hash: `hash_on_config_steps` makes it unjournallable as `ROLLBACK`,
and rule 0 forbids acting without the journal entry. The assessment reports
`Unknowable("rollback.target.absent")` and the request reaches `APPLY_INDETERMINATE` for operator
confirmation. That is a defined and tested outcome, not an unhandled path — and it is the honest one,
because the engine knows what to do and cannot record having done it. Admitting a hashless restorative
rollback, or recording absence as its own journalled fact, is a change to `BE-D13`'s DDL and belongs
to the plan that revisits `sm_data` against a live database.

**Rationale.** Narrowing the seam instead — forbidding the adapter to restore at all in v1 and routing
every partial write to `APPLY_INDETERMINATE` — was rejected on two grounds. It inflates
`APPLY_INDETERMINATE` with cases the journal *can* decide, which is the opposite of the honesty
`BE-D12` §2.2 rule 3 asks for; and "the adapter must not restore" is an obligation the engine cannot
check, the same *"the DB cannot see what the adapter did"* problem §3.1 names — whereas after the
split the engine simply never calls the restoring method, which is structural. Keeping one operation
and relaxing `hash_on_config_steps` was rejected because it changes plan 004's DDL to accommodate a
seam defect. **This decision changes the meaning of what `BE-D12` specifies for the exhaustion path,
and of `BE-D21`'s accepted block; it is a new decision per `I3`, not an edit to either.** Status:
**PROPOSED**, like every other `BE-D*` — design-and-decision-register only; the interface, the
contract-kit obligation, the Docker implementation and the engine's journal shape are plan 005's.

## `BE-D23` — The v1 apply engine is single-placement: `apply_step_intent`'s identity is placement-blind ⚠️

**Full design:** [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §3.1 (the `BE-D23` amendment
callout after the engine-invariant table) — amends `BE-D12` §2.1's instance-wide reading and
`BE-D21`'s "fanned out into its placements" clause; neither text is rewritten in place (`I3`).

`apply_step_intent`'s logical key is `UNIQUE (tenant_id, apply_id, step, replica_ref)`, and
`placement_scoped_steps` pins `replica_ref` to the literal `'@placement'` for `PREFLIGHT`, `ROLLBACK`
and `ROLLBACK_ASSESS`. **One `apply_id` can therefore hold at most one `PREFLIGHT` intent**, however
many placements an instance-wide request addresses. An engine that fanned out would find the second
placement's `ensureIntent` returning the *first* placement's row — same logical key, same (null) hash,
so no mismatch is raised — and its attempts would be recorded as retries of another placement's
pre-flight. Replica-scoped steps are keyed by `replica_ref` alone within the apply, and `replica_ref`
is adapter vocabulary (`BE-D19`): two placements whose adapter produces the same label — a systemd
unit name, a Kubernetes workload name — collapse onto one intent, and where their rendered configs
differ the second `ensureIntent` raises `IntentMismatch`, reporting a legitimate two-placement apply as
a protocol error. Neither is a coding defect. Both are the model.

**Until an identity amendment lands, an instance-wide apply (`placement_id` = the nil sentinel) is
refused before admission and persists nothing** — no `apply_request` row, no intent, no attempt, no
adapter call. Refusing *before* admission rather than landing a failure is deliberate: a request the
engine cannot journal correctly should not occupy the scope, and a `FAILED_*` row would imply an
attempt was made.

**What this forbids, explicitly.** No plan may encode a placement identity into `replica_ref` to work
around this. `replica_ref`'s content grammar is locked by `BE-D19` on **both** sides (`ReplicaRef` and
`resolution_is_evidence`'s `like_regex`), its values are closed over by `BE-D18`'s per-element
key-set predicates, and `placement_scoped_steps` compares it to a literal. Overloading it would
silently redefine a column three decisions constrain, and would make `@placement` ambiguous.

**What the amendment would have to change**, named so the next plan does not rediscover it: the intent
table's logical key must carry the placement — either a `placement_id` column on `apply_step_intent`
with the UNIQUE and `placement_scoped_steps` widened to match, or a composite sentinel that
`placement_scoped_steps` can still validate. Both are changes to `BE-D13`'s DDL, both move the
DDL-replay baseline, and both need the fence and watermark interactions re-measured, because §2.2
rule 1's instance-wide comparison assumes one request per scope rather than one intent per placement.
**That is a persistence plan, not a paragraph in an adapter plan.**

**Rationale.** The alternative — let plan 005 add the column itself — was rejected because plan 005 is
an adapter and engine plan whose own Global Constraints forbid touching `sm_data`, and because the
change is not local: it lands in the same `CREATE TABLE` whose CHECK has been re-recorded through the
DDL-replay harness three times, and it interacts with fences that were measured against the current
key. Deferring the decision and letting the engine fan out anyway was rejected on the measurement
above: the journal would be wrong, silently, in the common case. **This decision narrows what
`BE-D12` §2.1 and `BE-D21` say about instance-wide requests; it is a new decision per `I3`, not an
edit to either.** Status: **PROPOSED**, like every other `BE-D*` — design-and-decision-register only;
the pre-admission refusal and the two tests that pin the identity limit are plan 005's.

---

## `BE-D24` — A config location names the writer access mode; it is not always a local `Path` ⚠️

`BE-D21`'s `ConfigLocation` constructor was built for Docker as `(containerPath, Path localDir,
fileName, mechanism)`. That representation has two meanings collapsed into one: the workload-visible
path and the filesystem access path used by the adapter. It is correct for the local Docker adapter,
whose process opens the bind-mounted host directory. It is false for an SSH placement: the DP process
does not possess the remote host's directory and must not construct a local `Path` which merely spells
it.

The runtime seam therefore replaces `localDir` with a closed writer-access value:
`LocalFilesystem(Path directory)` or `RemoteSftp(String canonicalDirectory)`. `containerPath` remains
the workload-visible path, and `fileName` remains one segment. `RemoteSftp` carries no host, credential
or private-key reference; host identity belongs to the SSH adapter's `RuntimeRef` and connection
configuration, never the generic location returned to the core. The core continues to use the location
only as pre-flight evidence; reads, pre-images, atomic placement and restore remain adapter operations.

**Rationale.** Representing a remote path as `Path.of(remoteDirectory)` makes a local capability claim
the process cannot fulfil. It invites a future engine or controller to open the path locally, which is
both wrong and a route around the runtime seam. Adding an SSH-specific location type to the engine was
rejected: the distinction is writer access, not SSH, and a future agent or Kubernetes adapter may also
be remote from the DP process. **This amends `BE-D21`'s `ConfigLocation` field shape; its original text
is not rewritten (`I3`).** Status: **PROPOSED**. The change is deliberately limited to the runtime
seam and does not ratify `SM-D11a`, G2, or any remote transport.

---

## `BE-D25` — The seam observes its replicas, generation-free ⚠️

**Status: PROPOSED.** Amends `BE-D12` §2.1's operation set by cross-reference; `BE-D12`'s text is
not rewritten (`I3`).

`prepare(placement, generation)` is the seam's only replica enumerator, and it is generation-bearing
by design — it re-reads the launch command, the mount and the competing-source set *for a specific
generation's apply* (`SM-D21`'s round-2 correction). Every operation that needs the replica set but
has no generation is therefore locked out. `apply_request`'s `apply_has_generation` CHECK makes that
concrete and not hypothetical: a `stop` carries `generation IS NULL`, so **a stop cannot learn which
replicas to address**.

Three shapes were available. Give a stop a synthetic generation — a lie the journal would then hold.
Overload `prepare` with a nullable generation — which makes the one operation whose whole purpose is
per-apply re-validation sometimes mean something else. Or add the observation. The seam adds it:

```java
/** Read-only. The replicas of one placement, as the runtime currently has them. */
List<TargetReplica> replicas(PlacementRef placement);
```

**Eight operations, not seven.** It is an *observation*, beside `status`, not an action: it mutates
nothing, takes no generation, and makes no claim about whether those replicas are fit to apply to —
that judgement stays in `prepare`, which keeps its pre-flight refusals. `prepare` is redefined as
"`replicas` plus the per-apply validation", and the contract kit pins the relationship directly: an
adapter whose `replicas` disagrees with the replica set its own `prepare` returns fails the kit.

**Rationale.** The alternative that looks cheapest — have the engine remember the replica set from
the last apply — is the one that is actually wrong: the replica set is runtime state, it changes
between operations, and an operator stopping an instance needs the set as it is now, not as it was
when the last config landed. Resume already reads its replica set from the **journal** rather than
from the runtime, and for the opposite and equally deliberate reason (§2.2 rule 3: a replica that
appeared or vanished while the DP was down must not change what resume thinks an apply was doing).
The two reads are different questions and both are correct.

---

## `BE-D26` — A `stop` is scope-granular, and instance-wide is admissible for a stop though not for an apply ⚠️

**Status: PROPOSED.** Amends `BE-D23` by cross-reference — narrowing what its limit covers, not
overturning it. `BE-D23`'s text is not rewritten (`I3`).

An operator must be able to stop one placement, or a whole solution instance. `BE-D23` refuses an
instance-wide **apply** before admission, and the reason is specific: `placement_scoped_steps` pins
`replica_ref` to the literal `'@placement'` for `PREFLIGHT`, `ROLLBACK` and `ROLLBACK_ASSESS`, so
one `apply_id` can hold at most one of each, however many placements it addresses.

**A stop journals none of those steps.** Its only step is `STOP`, which `placement_scoped_steps`
leaves alone and which therefore carries a real `replica_ref`. Measured against the shipped DDL:
`UNIQUE (tenant_id, apply_id, step, replica_ref)` admits one `STOP` intent per replica, across any
number of placements, under one `apply_id`. `BE-D23`'s limit is a property of the placement-scoped
steps, not of instance-wide scope as such, and a stop meets none of its preconditions.

**Which placements an instance-wide stop addresses.** The distinct real `placement_id`s of that
instance's own `apply_request` history — every placement SM has ever been asked to act on, whether
or not the request succeeded. There is no placements registry in v1 (deferred; see plan 005's scope),
and this is not a substitute for one: it is the **complete set of placements SM has any relationship
with**, which is the honest boundary of what a stop can promise. `placement_watermark` was the other
candidate and is strictly worse — it holds only placements whose apply reached a watermark-raising
terminal state, so a placement SM wrote config to and then failed on would be invisible to a stop.

**`BE-D23`'s SECOND clause still binds, and an instance-wide stop is refused when it bites.**
Corrected after an independent review caught the omission: this decision's first draft reasoned
only about `placement_scoped_steps` and never re-read `BE-D23`'s other half — *"replica-scoped
steps are keyed by `replica_ref` alone within the apply, and `replica_ref` is adapter vocabulary
(`BE-D19`): two placements whose adapter produces the same label collapse onto one intent"*.
`STOP` **is** a replica-scoped step. The Docker adapter's daemon-unique container ids hide this;
the SSH adapter's replica ref is the *configured container name*, so `cache-engine` on host A and
host B is the ordinary shape, and the second placement's stop would be journalled as a retry of
the first's — same key, both hashes null, so not even an `IntentMismatch` to notice it by.

Encoding the placement into `replica_ref` is what `BE-D23` forbids outright. So the engine
**enumerates every placement first and refuses the whole request** — `STOP_FAILED` with
`stop.replica.label.collision`, nothing journalled and nothing stopped — when two placements of
one instance yield the same replica label. A single-placement stop can never hit it: labels are
unique within a placement by construction. The general fix is `BE-D23`'s named identity amendment,
which belongs to the plan that revisits `sm_data`.

**What a stop reports, stated as what the journal actually holds.** One `STOP` attempt per
replica, carrying the runtime reference and the config hash read back from that replica.
`LifecycleResult.issued` — `false` for a replica already stopped, which §2.2 rule 2 requires an
adapter to report as success — is returned to the caller and **is not journalled**: there is no
column for it, and `RuntimeRef` is grammar-locked to identifiers (`BE-D19`), so it is not a place
to put an outcome. A placement whose enumeration fails is reported in the outcome's classifier and
**cannot be journalled at all**, because a `STOP` intent must carry a real `replica_ref` and there
is no replica to name; the classifier is the *first* failure, not the last, so it does not depend
on iteration order. All three are stated limits, not claims.

A stop that addresses no known placement lands `STOP_FAILED` with `stop.no.known.placement` —
**corrected in place before code was built against it** (the draft-amendment policy `BE-D22`
used): the first draft said `FAILED_NO_EFFECT`, and `state_fits_operation` refused it by name,
because that CHECK admits only `REQUESTED`/`RUNNING`/`STOPPED`/`STOP_FAILED` for a stop. Between
the two states it leaves, `STOP_FAILED` is the honest one — reporting `STOPPED` would tell an
operator the instance is down when SM never looked at anything. A placement that is known and
currently holds **no replicas** is different and lands `STOPPED`: nothing to stop is not a
failure, and it is the same answer `ResumePlanner` gives an `ABSENT` replica.

**Stopping is not the end of the workflow, and an apply that follows a stop must start what it
finds.** An apply under a restart strategy captures each replica's liveness at pre-flight and uses
it to tell two situations apart that look identical at the lifecycle step: a replica that was
**running when the apply began and is not running now** was taken down underneath it — obligation
(r), halt `CONFIG_DIVERGED` with per-replica evidence — while a replica that was **already down at
pre-flight** is the state the operator asked SM to apply from, so the lifecycle verb starts it and
the gate waits for *running and healthy* instead of for a moved baseline. Without that distinction
`stop the instance, then apply the new generation under restart-all` is impossible, which is the
first thing this decision's own scope-granular stop invites an operator to do. §2.2 rule 3's
"baseline missing → indeterminate" is untouched: that rule is about **resume** re-issuing a restart
nobody authorized twice, and this is the forward path, where the request itself is the
authorization.

**Resume is narrowed the same way.** `resume(ScopeKey)` alongside `resume(TenantId)`: an operator
recovering one instance should not have to sweep a tenant, and a tenant sweep should remain
available for a daemon that has none. The scope filter is free — `nonTerminal` already returns rows
carrying their own scope — and `ScopeKey.overlaps` already gives instance-wide the right meaning.
**`overlaps` is symmetric, and that is disclosed rather than hidden:** resuming a *placement* also
resumes any in-flight **instance-wide** request covering it, and completing an instance-wide stop
stops placements the caller did not name. That is the correct reading of an instance-wide request —
it is one request over the whole instance, not a bundle of per-placement ones — but a caller who
wants "only this placement, whatever else is in flight" does not have it.

**A design-text observation, flagged not resolved.** §3.2's `CONFIG_DIVERGED` row illustrates the
state as *"a rolling strategy halted between replicas: some restarted onto `G`, some still run the
prior generation"*. Obligation (r)'s halt can land on the **first** replica, with none restarted.
`CONFIG_DIVERGED` is still the right state — the row's own *stated limit* is that the halt does not
un-stage, so every replica holds `G` on disk and will adopt it on its next external restart, which
is exactly as true with zero restarted as with one — but the illustrative clause does not cover the
case, and widening it is `BE-D13`'s text to change, not this decision's.

**Known interaction, not changed here.** `stop_inflight_fence` is per *exact* scope (plan 004,
proven by `AdmissionFencesIT`), so an instance-wide stop and a placement stop on the same instance
can both be in flight and can address the same replica. That is safe because `stop` is idempotent
and the two requests hold separate `apply_id`s, so their `STOP` intents cannot collide. Making the
stop fence overlap-aware would be a plan 004 change with a migration, and is not taken here.

---

## `BE-D27` — `stateVersion` comes from one `sm_data` sequence, stamped by trigger — never "the config generation" ⚠️

**Status: PROPOSED.** Supersedes the `stateVersion` sentence in [`design.md`](design.md) §3 — *"the config
generation where one exists (config-state, rollout), otherwise a per-entity sequence the owning module
increments with the row it commits"* — and the matching parenthesis in the `instance.config-state` catalog row
(*"`stateVersion` = the config generation"*). Records owner ruling **`OR-35`** (2026-09-28,
[`../DECISIONS.md`](../DECISIONS.md)), which ruled the correction and asked that it be recorded as a new decision
(`I3`: `BE-D4`'s envelope text and `design.md`'s sentence are not edited in meaning; each carries a pointer here).
`BE-D4`'s envelope shape and **`stateVersion`-only ordering** (`B/D15`) are unchanged — this decision changes only
**where the number comes from**.

**The defect, measured.** One config generation emits several `instance.config-state` notices as the `SM-D22`
ladder advances (`desired` → `rendered` → `persisted` → `restart-observed`). If `stateVersion` were the generation,
all of them would carry the **same** value, and a consumer applying the envelope's rule — *discard a notice whose
`stateVersion` is not newer than the state already fetched* — would discard every notice after the first. The rule
the envelope exists to enable would drop the very movements it is meant to announce.

**The rule.**

1. `stateVersion` comes from **one `sm_data` sequence**, `sm_data.state_version_seq`, assigned by a **trigger** on
   every owning row when it changes: `instance_state`, `config_generation`, `placement_health`, `apply_request`
   (a new `state_version` column, which is what `observeApply`'s `sinceStateVersion` backfill reads) and
   `fleet_action`. It is **never** stamped on the immutable `placement` row.
2. **A write that changes nothing an observer can see does not stamp.** Two named cases: the `apply_request`
   compaction fold, which rewrites only `attempt_summary`; and a health probe that confirms the state already
   recorded. A stamp is a statement that something observable moved.
3. **Commit-order rule.** Every stamping transaction **first takes the owning `instance_state` row lock** (for
   `fleet_action`, its own row lock). Within one instance, stamp order therefore equals commit order, so a
   `sinceStateVersion` backfill cannot skip a late committer. A sequence alone does not give this: `nextval()` is
   not transactional, so two concurrent transactions can stamp `9` and `10` and commit in the order `10`, `9`,
   and a client that observed `10` and backfilled `> 10` would never see `9`.
4. **Lock order** is `apply_scope → instance_state → (row)` everywhere. A copy-forward (rollback, restore,
   accept, fleet member generation) and the submit that follows it are **two transactions** — the stage commits
   holding the `instance_state` lock only, then admission takes `apply_scope` and then `instance_state`. One
   transaction doing both would invert admission's order and can deadlock. A crash between the two leaves a
   staged generation with no apply: harmless, and a re-run is idempotent.

⚠️ **Rule 3 is this design's reading of `OR-35`, not the owner's wording.** `OR-35` rules the single sequence and
the stamping trigger; that a sequence alone does not give commit order across concurrent transactions is this
decision's own inference, so the review of the migration that builds it (plan 010 T2.1) must confirm it — with a
two-thread interleaving test, not by reading. Until then it stands as the design, not as an owner ruling.

**What does not change.** `BE-D4`'s envelope fields; `stateVersion`-only ordering and stale-discard; refetch as the
always-safe default; the envelope being defined once and referenced by every schema. Stamp values are
**monotonic per instance, not dense** — a gap means a rolled-back or skipped stamp, never a missed notice — and
no consumer may infer "nothing happened" from a gap.

**Not built here.** This is a record. The sequence, the trigger, `apply_request.state_version` and the lock
discipline are built by plan 010 T2.1/T2.2 (and `config_generation`, `placement_health`, `fleet_action` by their
own tasks); until they land, nothing emits a `stateVersion` from this sequence. Implementation is not
ratification — it stays **PROPOSED** with the `BE-D*` set.

---

## `BE-D28` — DP secrets are two-level envelopes with an AAD that binds tenant, secret and purpose

**Status: PROPOSED.** Implements owner ruling **`OR-30`** ([`../DECISIONS.md`](../DECISIONS.md)); drafted as K-1 in
[`secret-store.md`](secret-store.md) §12, designed in §3 there, built by plan 010 T2.4 (`V009`, the envelope and the store). It
re-decides nothing and edits no earlier decision (`I3`).

A secret is encrypted with `AES/GCM/NoPadding` (256-bit key, 96-bit random IV, 128-bit tag) under a fresh per-secret data
key, and that key is wrapped by the install's key-encryption key the same way. Both operations feed the same AAD
(`aad_version || tenant_id || secret_id || len || purpose`); `key_id` is not in the AAD. The row is
`{key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version}`. A secret's value is write-once. The runtime role holds
`UPDATE` on `key_id`, `wrapped_dek`, `dek_iv` only, and no `DELETE`.

**Rationale:** rotation never handles a plaintext, and the one privilege rotation needs cannot touch a value
(`secret-store.md` §3, K3, K5). Serves `I6`.

---

## `BE-D29` — The master key is validated at boot against a canary; the canary is minted only on a fresh install or a proven rotation ⚠️

**Status: PROPOSED.** Implements **`OR-30`**; drafted as K-2 in [`secret-store.md`](secret-store.md) §12, designed in §4
there, built by plan 010 T2.4 (`KekCanaryCheck`) and T2.6 (the launcher refuses to boot without `SM_DP_MASTER_KEY`). Flags
that document's OQ-2 and OQ-3 for the owner.

`SM_DP_MASTER_KEY` (standard base64, 32 bytes) and its id come from the environment, are read as strings and never echoed.
A canary envelope per key id lives in `sm_secret.kek_canary` with no tenant. Boot mints a canary only when the canary table
is empty or when a previous key's canary verifies; otherwise a missing or failing canary refuses to start, before the
worker.

**Rationale:** a wrong key must fail at boot and must never be able to establish itself as the right one
(`secret-store.md` §4, K1, K2).

---

## `BE-D30` — Rotation re-wraps data keys with an operator command that holds both keys, and never drops a key it cannot prove empty ⚠️

**Status: PROPOSED.** Implements **`OR-30`** and relies on **`OR-49`**; drafted as K-3 in
[`secret-store.md`](secret-store.md) §12, designed in §5 there. Flags that document's OQ-1 (the tenant list does not reach
every tenant with secrets).

A rotation gives the application the new key as current and the old as previous **before** any row moves, then
`KeyRotationMain` re-wraps per tenant in batches, one `UPDATE` per row, guarded by the old id. It reports counts and never
an unqualified "none remain". A retired key is kept as long as any backup names it.

**Rationale:** `secret-store.md` §5, K3, K7.

---

## `BE-D31` — The config hash key is a per-tenant envelope secret, versioned, never pruned; the hash is computed in the DP ⚠️

**Status: PROPOSED.** Implements **`OR-32`**, which stays flagged for the architect group because it names `I6`; drafted as
K-4 in [`secret-store.md`](secret-store.md) §12, designed in §7 there, built by plan 010 T5.1 (`HmacConfigHasher`,
`EnvelopeHashKeySource`) and T5.2 (the config of record). Flags that document's OQ-5 (per-tenant key; no v1 key rotation).

`CONFIG_HASH_KEY` secrets carry a `key_version`, unique per tenant, never reused. The hash is spelt
`hmacsha256v<n>:<64 hex>`, which fits the existing grammar and CHECK unchanged. It is computed by the adapter from bytes the
transport returns, never on the customer host. A version whose key is not loadable verifies as **unverifiable**, a distinct
outcome from a mismatch. The key is minted on first use by whichever caller.

**Rationale:** `secret-store.md` §7, K3, K8.

---

## `BE-D32` — Declare is a two-phase protocol across two planes; the DP mints the id, the CP holds the governance node, and a lazy tenant-scoped sweep finishes any interrupted declare ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder (plan 010 T4.1); the integrator allocated `BE-D32` at
merge, in merge order (plan 010, Global Constraints). Records owner rulings **`OR-27`**, **`OR-40`**, **`OR-41`**, **`OR-71`** and
**`OR-82`** ([`../DECISIONS.md`](../DECISIONS.md)) as a protocol; it re-decides none of them and edits no earlier
decision (`I3`). It is **built** by plan 010 T4.2 (DP phases and sweep), T4.3 (CP registration) and T4.4 (the
orchestrator and the served routes); until those land this is a record, not behaviour.

**Why a protocol.** `OR-27` puts the instance in the Data Plane (the DP mints the id and owns target binding,
placements, config and discovered facts) and leaves the Control Plane only a **governance node** for RBAC. A declare
therefore writes two planes. `I7` forbids a cross-schema join, a cross-schema foreign key, and either plane importing
the other, so there is **no distributed transaction** to lean on. The order of the writes is chosen instead so that
every crash window leaves a state a sweep can finish.

**The five steps.**

| Step | Plane | Commits | Crash here leaves | Recovered by |
|---|---|---|---|---|
| 1 | gateway | nothing | nothing | none needed |
| 2 | CP checks: `instance.create` at the environment; `instance.bindTarget` at the environment; `target.use` at the target's ownership scope; entitlement **pre-check** | nothing | nothing | none needed |
| 3 | DP `reserve` | `instance_state` (`lifecycle = pending`) and its `placement` rows | a `pending` DP row with no CP node | the sweep: a `pending` row older than the window and **not** registered is abandoned |
| 4 | CP `register` | the environment's Solution node on the **first** declare there (`OR-41`), the `instance` row and its scope rows; **quota is enforced here, under `FOR UPDATE` on the entitlement row** (`OR-40`) | a CP node and a `pending` DP row | the sweep: a `pending` row that **is** registered is activated |
| 5 | DP `activate` | `lifecycle = active` | nothing | none needed |

**The rules.**

1. **The DP mints the id** (`OR-27`), at step 3, before the CP has seen it. The CP's `instance` table carries a
   global `UNIQUE (instance_id)`, so a duplicate id is refused by the database, not by convention.
2. **Authorization is settled before either plane is written.** Step 2 calls the one enforcement point
   (`authorizationService.require`) for `instance.create`, `instance.bindTarget` and `target.use`. The DP tenant is
   the session's organization, never a request field (`I5`), and `require` answers `404` for a resource in another
   organization, so the two planes cannot disagree about whose declare it is.
3. **Quota is binding only at step 4.** The step-2 entitlement check is a pre-check that spares a doomed `reserve`;
   the check that counts is inside `register`, serialized by the entitlement row lock, so two racing declares cannot
   both take the last slot (`OR-40`). An over-quota refusal abandons the just-reserved DP row synchronously; the
   sweep would reach the same result later.
4. **The first declare in an environment creates its Solution node** (`OR-41`), authorized by `instance.create`,
   pointing at the catalog package. Concurrent first declares converge on one node through a unique index and a
   retry.
5. **The acquisition mode is chosen at declare and carries no host** (`OR-71`, as amended by `OR-82`). The body names
   `acquisition = {mode: direct-http | via-ssh}`, chosen by the operator, `direct-http` listed first, **no automatic
   fallback**. The orchestrator hands the mode to `reserve`, which stores it as **instance-level** state
   (`instance_state.acquisition_mode`), so it survives a re-target onto a new placement. In `direct-http` the DP
   polls the **target's registered address** (`target_connection.host`, fixed by the registering operator command)
   at the placement's health path. No host or address of any kind is read from the declare body; a body whose
   `acquisition` carries any property besides `mode` is `422`. *Erratum, 2026-10-05 (plan 010 S4 review; the
   superseded wording is kept here, not silently edited): this rule first said the port too was "package-declared"
   and that "no host, address or port override of any kind" was read from the body. `console.yaml` makes
   `placement.healthPort` a required body field (the host-side port the engine reaches, M12) and the orchestrator
   stores it as the probe's `hostPort`. The corrected reading: probe `kind`, `path` and `strength` come from the
   package; the host-side port comes from the body's `healthPort`; no host or address comes from the body.*
6. **A refused or abandoned declare frees what it held.** `abandon` retires the row's placements in the same
   transaction, so the container name, directory and health port are reusable at once. An abandoned or pending row
   is excluded from every read.
7. **The Control Plane never deletes a governance node.** A node left by an interrupted declare is completed by the
   sweep's `activate`, not removed. The CP's only recovery action is therefore to complete a registration, never
   to undo one.

**The sweep has two rules.**

- **A registered `pending` row is activated at any age.** The CP already holds its governance node, so activation is
  always safe and never races a declare still in flight.
- **An unregistered `pending` row is abandoned only after the window** (`sm.dp.declare.abandon-after`, default ten
  minutes), because it may be a declare between steps 3 and 4. A fresh unregistered row is left alone.

Both are idempotent, and neither depends on the other plane's clock: the window is read from the DP row's own age.

**The sweep is lazy, tenant-scoped, and has no background loop (plan 010 review finding `B4`).** It runs inside the requests whose
correctness a stale `pending` row can affect, for **that request's tenant only**: before `reserve` in every
`declareInstance` (a stale row holds names and, once registered, quota), and before the read in `listInstances` and
`getInstance` (a registered but pending instance must become visible). A stale row in a tenant that never declares
or lists again is invisible and harmless, because it holds names only against that tenant's own future declares,
which sweep it first. The sweep therefore needs **no tenant enumeration** and adds no cross-tenant read; it does not
touch the two enumerations Plan 010 already permits (`OR-44`, `OR-49`).

**Why the gateway composes it.** The sweep needs one Control Plane answer ("which of these pending ids did the CP
register?"), and `dp-core` may not import `cp-api` (`I7`). The DP exposes two tenant-bound methods, `pending` and
`settlePending`; the gateway, the one module permitted both `-api`s, asks the CP in between. Neither plane learns
of the other.

**What does not change.** `OR-27`'s ownership split; `OR-40`'s entitlement semantics; `OR-41`'s node creation;
`OR-71`'s per-instance mode, direct-first, no-fallback rule; `OR-82`'s address rule. The agent path (an enrolled
agent's instances auto-registering, `OR-27`) is `AGW-D*` and is **not** specified here.

**Not built here.** Docs only. Implementation is not ratification; this stays **PROPOSED** with the `BE-D*` set.


---

## `BE-D33` — The control plane publishes `catalog.package` through its own after-commit wrapper over the one shared raw transport; the mesh is off unless configured ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder (plan 010 T3.3); the integrator allocated `BE-D33` at merge, in merge order (plan 010, Global Constraints).
It records what plan 007 Task 5a required ("Step 3 records a decision") and adds the one deviation T3.3 makes. It re-decides
no earlier decision and edits none (`I3`); it narrows a sentence in `cp-core/pom.xml`'s `<description>`, which is corrected in
the same change.

**1. `cp-core` depends on `mesh-events`.** `catalog.package` is the control plane's event (design §3; `envelope.schema.json`
closes `producer` to `cp-core | dp-core`), so the control plane must construct an `EventEnvelope` and hold an `EventPublisher`.
`mesh-events` is plane-neutral by construction (`EventPublisher`'s javadoc: "both planes publish through it"; `PlaneBoundaryTest`:
neutrality "is exactly what makes it safe for BOTH planes to consume"), and its two ArchUnit rules point the other way, so no
rule is violated. `I7` is unaffected: `cp-core` still imports no `dp-api`/`dp-core` type and no broker SDK type
(`cpNeverImportsDp`, `onlyMeshbridgeTouchesJcsmp` stay green). The POM `<description>` that said "never on … `mesh-events`" is corrected.

**2. One shared raw transport, two plane-local after-commit wrappers.** The broker session and the drop counter are ONE:
`MeshBridgeConfiguration`'s raw `meshEventPublisher` bean. Each plane wraps it in its own `AfterCommitEventPublisher` over its
own `TransactionBoundary`, so "publish after commit" means after *that plane's* commit. The control plane's boundary
(`CpTransactionBoundary`) additionally requires the `sm_control` resource to be bound to the thread, because Spring's
synchronization registry is per thread and shared by every transaction manager: a boundary that asked only "is a transaction
active?" would hold a catalog notice for a *data-plane* commit. A control-plane transaction nested inside a data-plane one
fires at the outermost completion (later, never earlier) — no production path nests them, and the loss mode is a discarded
freshness notice, which `BE-D1` accepts. The control-plane wrapper is `defaultCandidate = false`, like the raw transport, so an
unqualified `List<EventPublisher>` still sees exactly the data plane's primary composition (a second default candidate would
let a fan-out service double-publish).

**3. Deviation from plan 007 Task 5a: the launcher constructs nothing.** Task 5a had the launcher build the control-plane
wrapper. The launcher may construct no bean (`CompositionBoundaryTest`), and does not need to: `cp-core`'s own
`CatalogEventsConfiguration` takes `@Qualifier("meshEventPublisher") EventPublisher` — typed by the `mesh-events` interface —
which is the qualified injection `MeshBridgeConfiguration` measured to resolve a `defaultCandidate = false` bean.
`ControlPlaneConfiguration` imports it with one line. The raw bean is **required**: a context that composes the control plane
without the data plane's mesh bridge refuses to start naming `meshEventPublisher` rather than quietly dropping notices
(cp-core's own tests add a stub bean; the launcher gets the real one).

**4. The mesh has no default broker and is off by default.** The production data plane now imports the bridge, so every boot
reaches `MeshProperties`. `sm.mesh.enabled` defaults to `false`: host/vpn/username are then not required and the raw bean is a
dropping publisher that counts every drop, with one WARN at boot — a **declared degraded state**, never a refusal to boot.
`sm.mesh.enabled=true` applies every `requireText` unchanged, so a *partial* configuration still refuses to boot (fail closed on a
typo, not silently dropped). The password is a deployment secret (`I6`), supplied from the environment, never committed.

**5. What the event carries.** `tenant` is the session's organization id (the topic level, `I5`); `entity` is
`{kind: solution, solution: <name>}`; `stateVersion` is the catalog entry's (it rises on every load); the payload is
`solution` and `packageVersion`, the latter the stored canonical version text (e.g. `[1]`, `["2.4.0"]`) because the notify schema
leaves `packageVersion` untyped (`contracts/events/README.md`, Known gap 4) — the `BE-D4` amendment will type it, and this
emitter changes with it. An idempotent re-load stores nothing and publishes nothing.

**Not changed:** the publisher inventory (`CatalogEventEmitter` *calls* an `EventPublisher`, it is not one, and a second
*instance* of an already-kitted implementation is not a new implementation); browser mesh notify stays gated by open items 32/33/35.

---

## `BE-D43` — A broker credential is stored under its own secret purpose, `BROKER_CREDENTIAL`, amending `BE-D28`'s closed set ⚠️

**Status: PROPOSED.** Records owner ruling **`OR-107`** (plan 011 OQ-3 (a), 2026-10-06), built by plan 011 P11-5.1 (`sm_data` migration
`V017`, renumbered at merge from the lane's placeholder `V018`). **It amends `BE-D28` and edits nothing in it (`I3`).**

`BE-D28` closed the purposes a secret may carry: `SSH_PRIVATE_KEY`, `CONFIG_FIELD`, `CONFIG_HASH_KEY` (and `CANARY`, which never lives in
`sm_secret.secret`). The purpose is part of the authenticated data (`aad_version || tenant_id || secret_id || len || purpose`), so
the choice is not cosmetic: a broker credential stored as `CONFIG_FIELD` is a broker credential that **opens as a config field**
(and `SecretCompareImpl` / `ConfigGenerationRenderedConfigSource` would accept it as one), and a relabelled row is exactly what the
AAD exists to refuse. The Data Plane's push path (`OR-107`: one broker connection per organization and environment, the Data
Plane subscribing directly on the customer's broker) holds a credential that is none of those things.

So the closed set gains one member, **`BROKER_CREDENTIAL`**, in three places that must agree: the `secret_purpose` CHECK
(`sm_secret.secret`, widened by the migration, which drops and re-adds the one named constraint), `SecretPurpose.java`, and the push
path's readiness check (the application refuses to start against a database whose CHECK does not name it, rather than failing at an
operator's first registration). Nothing else of `BE-D28` changes: the same two-level envelope, the same write-once value, no `DELETE`
for the runtime role, the same rotation (`KeyRotationMain` re-wraps every row under the purpose it carries).

**Where it is written and read.** Written only by `BrokerConnectionMain` (a standalone operator command with the `TargetConnectionMain`
shape, the credential read from a FILE whose path is in the environment, zeroised, never echoed), in the same transaction as the
`sm_data.broker_connection` row that holds its reference. Resolved only by the session manager in `meshbridge.ingest`, for one connect,
in memory; the plan, the decoder, the attribution and the service cannot reach the secret store (an ArchUnit rule with a planted
violation).

**Rationale:** the smallest change that keeps `BE-D28`'s purpose-binding meaningful. **Alternative recorded and not taken:** reusing
`CONFIG_FIELD`, which needs no migration and mislabels the secret in its own authenticated data.

---

## `BE-D«next-or112b»` — One live broker connection per broker and message VPN across the install ⚠️

**Status: PROPOSED.** Records owner ruling **`OR-112` (2)** ("okay aligned"; finding `W3-06`), built on `BE-D43`'s `broker_connection` and **editing
nothing in it (`I3`)**: `BE-D43`'s own rule, one connection per (organization, environment), stands and is a different, narrower key. The `sm_data`
migration number, `V019`, is a **placeholder** (the other `OR-112` lane may take `V018`; the integrator renumbers to the next free number at merge, as it
did for `V017`).

**The rule.** At most one LIVE (`retired_at IS NULL`) `broker_connection` per **(host, port, message VPN) across the whole install**: from any
environment, of any organization. The host is compared without regard to case (a DNS name is case-insensitive); the scheme is not part of the key (one
listener is one broker whichever scheme names it); retiring a connection releases its broker and VPN.

**Why.** The Data Plane subscribes **directly** on the customer's broker, one broad subscription per connection (`OR-107`), and the key that attributes an
event to an instance is the **adapter id** the integration publishes. An adapter id is unique only **within one message VPN**; the Data Plane's
attribution is scoped by connection and organization, so two live connections on one VPN would each receive every adapter's events of the other (the
runbook's counted blind spot, doubled), would hold two sessions where one carries the same bytes, and would attribute one VPN's `ADAPTER_A` to whichever
organization's instance happened to configure that id first. One live connection per broker and VPN makes "which organization owns this VPN's
traffic" a single answerable fact, and makes the runbook's second rule, **adapter ids unique across everything sharing a VPN**, the operator's to keep
rather than the Data Plane's to guess.

**Two enforcers, as the standing pattern** (`BE-D43`'s field grammars do the same):

1. **The command.** `BrokerConnectionMain` reads, before it seals any credential and after its idempotence check (so an identical re-run is still "already
   registered"), whether any live connection holds the broker and VPN, and refuses in one fixed sentence that names no value, no other organization and no
   environment.
2. **The database.** The unique index `broker_connection_live_broker_once` over `(lower(host), port, vpn) WHERE retired_at IS NULL` refuses the same
   thing under a race and for a row written by any other route; a `23505` on it is surfaced by name. **`tenant_id` is deliberately not in the index**: the
   rule is per install. (A database that already holds two live connections to one broker fails that statement and the migration does not apply: retire
   one as the database owner and run it again; nothing is chosen silently.)

**The install-wide read is the operator command's own, not application runtime (`I5`).** `I5` says tenant scope is never derived from client input and
every query applies it. This one statement deliberately names no `tenant_id`, because the rule is across organizations and an organization-scoped read
could not see the other organization's row. It is safe **here and only here** because its caller is the install operator's standalone command, run with
the DP runtime credential on the host (the command's whole authority, as `BE-D43` records), and because the answer never leaves the command as a row, an
id or an organization: it selects one fixed refusal sentence. The read is **package-private** in `JdbcBrokerConnectionRepository` and absent from the
`BrokerConnectionRows` interface the application's push path holds, so no request, tick or session manager can call an install-wide read; a test pins both.

**What does not change.** `BE-D43` (the credential's purpose, the one-per-environment index, the runtime role's `SELECT`/`INSERT` only); the push path
(it reads its organization's live connections as before); registering another environment's broker, another port, or another VPN on the same host (the key is the tuple given: another port of the *same* broker and VPN is the operator's duty to avoid, `register-a-broker-connection.md` rule 1).

**Alternative recorded and not taken:** the index alone. It guarantees the rule but answers the operator with a constraint name; the command's sentence says
what the rule is and what to do.

---

## `BE-D«next»` — Load-time package validation uses networknt 2.0.8, consumer-side; the catalog `stateVersion` is `max(load_seq)` ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder for the integrator to allocate at merge, in merge order (plan 010, Global
Constraints), by plan 010's S2/S3 slice-review fix round; it records what T3.0's Step 2 asked for ("draft a decision row") and T3.2's
`stateVersion` rule, neither of which was drafted. It re-decides no earlier decision and edits none (`I3`).

**1. The library.** `com.networknt:json-schema-validator` **2.0.8** (Apache-2.0; published POM name `Apache License Version 2.0`, no comma),
with its one new transitive `com.ethlo.time:itu` 1.14.0 (Apache-2.0), added to `cp-core` only. It is the Jackson 2 line (the `3.0.x` line needs
Jackson 3, which this reactor does not use). All **24** keywords `contracts/solution-package.schema.json` uses are supported at draft 2020-12
(`$schema $id $ref $defs type enum const properties required additionalProperties items minItems minLength minProperties minimum uniqueItems
pattern allOf anyOf oneOf not if then title description`); every `$ref` is a local fragment, so remote fetching is off. Measured and sourced in
[`docs/tasks/t3-0-json-schema-library.md`](../../tasks/t3-0-json-schema-library.md); the owner approved the one online resolution (`OR-86`) and the
two bundled Unicode/IETF data tables with notices (`OR-87`).

**2. What it is for, and what it is not.** This is **consumer-side** load-time validation at `loadPackage` (`backend-consumption.md` §9, `BE-D11`):
the control plane refuses a package that fails a gate before anything is stored. `BE-D10`'s "mirror-and-lock, no codegen, no producer-side runtime
validation" is **unaffected**: the schema is still mirrored into the backend byte-for-byte and locked by `PackageSchemaMirrorLockTest`, and
nothing generates types from it. The Python `contracts/validation` stays the normative gate until the parity tests say otherwise; the Java port is the
second implementation, run against it as an oracle.

**3. Two library gaps found and closed by the port, not by the library.** networknt compares `uniqueItems` elements with `JsonNode.equals`
(so `[1, 1.0]` is "unique"; Python's `==` says it is not): `UniqueItemsGate` closes it for the three version arrays. A U+0000 in any string passes
both gate-1 implementations and then fails PostgreSQL's `jsonb` cast with an error that quotes part of the document: `CatalogService` refuses it as a
gate-1 failure before any write. Both are pinned by tests.

**4. `stateVersion` of a catalog entry is `max(load_seq)` of its packages** (`solution_package.load_seq` is an identity column), read inside the
loading transaction, with **no control-plane trigger** and no second sequence. It rises on every load that stores a package and not on an idempotent
re-load. It is deliberately not the data plane's `OR-35` sequence (`BE-D27`): the two planes share no schema (`I7`), and a catalog notice needs ordering
only within one entry.

**Not changed:** `BE-D10`, `BE-D11`, `BE-D27`; the licence allow-list gained one spelling (`OR-86`).

---

## `BE-D34` — Verify-then-retry: the engine reconciles `APPLY_INDETERMINATE` against a fresh host observation, superseding the part of `BE-D12`/`BE-D16` that left it alone ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder (plan 010 T6.8); the integrator allocated `BE-D34` at merge, in merge order
(plan 010, Global Constraints). **It supersedes a PROPOSED decision by owner ruling, not by ratification** (`OR-1`, `OR-59`, `OR-63`, `OR-64`):
exactly one sentence of `BE-D12`/`BE-D16` — plan 005's rule that recovery never touches `APPLY_INDETERMINATE` ("resuming one would be the engine
confirming its own uncertainty"; `ApplyExecutor.resume`'s skip) — and plan 007 Task 4b's planned "enumerated and then left alone" test, which was
never written. Nothing else in either decision changes; neither is edited in place (`I3`). Flagged, not ratified.

**1. Why the old concern is answered, not ignored.** The old rule forbade the engine *confirming its own uncertainty*. The reconciler never does:
every decision rests on a **fresh observation of the host** taken outside any lock — the keyed hash of each replica's file (`OR-32`), the adapter's
**retained pre-image** (read through the existing read-only `assessRollback`), and each replica's start baseline — and every branch either proves the
state from that observation or refuses to act. A request is judged "restarted by this request" exactly as `HealthGate` judges it (the current baseline is
present and differs from the latest `LIFECYCLE` attempt's `pre_baseline`, host-clock values only; a replica with no readable baseline is *undecided* and
never restarted). Content SM did not write is never overwritten.

**2. The decision table** is `ReconcileDecision` (pure, one test per row): intended and restarted (or `manual`) → confirm; intended and not restarted →
restart only; prior content, or an adapter-recorded absence (`Q-R1 (a)`) → conditional re-apply then restart and verify; anything else → give up
`reconcile.foreign.content`; unreadable or undecided → bounded retry. Under `rolling-health-gated` a confirmation takes one read-only gate observation:
`HEALTHY` confirms and journals a succeeded `LIFECYCLE` attempt (the step `observeApply` derives `gateProved` from); any other signal gives up
`reconcile.gate.unproven`; **an unreadable probe is a failed read and is retried** — a refinement of "anything else", on the table's own "a read fails"
row. Bounds (`Q-R3 (a)`): 5 failed inspections with 30 s doubling to 5 min; 1 re-drive; no re-drive past 1 h from the first sighting; give-ups
`reconcile.exhausted`, `reconcile.redrive.exhausted`, `reconcile.redrive.too.late`, `reconcile.redrive.cap` (the per-intent attempt cap fired),
`reconcile.superseded.by.later.request` (a stop admitted while indeterminate forbids any write; a confirmation, being a read, is still allowed).

**3. Step 0, measured.** (a) `APPLY_INDETERMINATE → RUNNING` is **already legal in the DDL** (`ReconciliationSchemaIT` pins it): `state_fits_operation`
allows `RUNNING` for an apply, `terminal_has_timestamp` wants `NULL` for both, `apply_inflight_fence` lists both, and `forbid_terminal_regression` exempts an
`APPLY_INDETERMINATE` source. `OutcomeService.land` refuses the walk in Java, so `beginRedrive` is the one new method that takes it; no CHECK changes.
(b) A second attempt on the same `MATERIALIZE`/`LIFECYCLE` intent is legal (intents are immutable, attempts append; `ReconciliationSchemaIT`); the per-intent
cap counts the original run's attempts. (c) `resolveIndeterminate` can record the confirmation, with `resolved_by = sm-reconciler`: **a new meaning** for a
field documented as "the principal who confirmed" — a fixed non-principal token that cannot collide with a principal id (a UUID). The alternative, the
direct `APPLY_INDETERMINATE → APPLIED` transition with NULL resolution columns, carries no evidence and so cannot hold the observation. (d) `resolveIndeterminate`
on a `RUNNING` request throws `IllegalStateException` naming the state; the reconciler that loses a race finds the request no longer indeterminate and closes
its bookkeeping (`resolved.by.operator`) without a host call. **M18** (Docker `State.StartedAt`), measured locally on Docker Desktop with a throw-away
`alpine` container (2026-10-05): `StartedAt` changes on `docker restart` and on stop-then-start; a stopped container keeps its last `StartedAt` with
`Running=false` (the adapter reports no baseline for it); a recreated container has a new id and a new `StartedAt`. **Pending for W9 on the demo host:**
`State` while a restart is in progress, and the daemon-clock statement; nothing in the engine depends on them (a replica with no readable baseline is
undecided).

**4. Three phases, no lock across a host.** Observe with no lock held; decide and transition in one short transaction under `apply_scope → instance_state →
row` (`beginRedrive` re-reads under the lock, checks the observed `state_version` and that no newer request exists, walks the request, and counts the
re-drive in the same transaction); act through the engine's own step loop with the request `RUNNING`. The scope lock is released before any host call, so an
emergency stop is never queued behind a probe. A stop admitted after the decision runs alongside the re-drive; the re-drive meets the stopped replica by
observation and ends `CONFIG_DIVERGED` (`lifecycle.replica.not.running`), design §3.3. **Obligation (r) stays UNPROVEN** and this decision does not claim it.
A re-drive never enters `BE-D22`'s rollback.

**5. Bookkeeping and the alert.** `sm_data` V011 adds `apply_reconciliation` (counters, `next_attempt_at`, `first_indeterminate_at`, a terminal `gave_up_at`)
— **not a stamped owning row**, a reading flagged here: the counters are bookkeeping, the observable change is the request's classifier — and
`apply_request.reconcile_class`, which the `OR-35` stamp trigger stamps because it is not the compaction fold (`M-1`). A give-up
(`OutcomeService.recordReconcileGiveUp`) writes the classifier, closes the bookkeeping and publishes one `instance.apply-outcome(APPLY_INDETERMINATE)` after
commit, in one transaction (a re-drive that gives up mid-flight is walked back in that same transaction: still one notice). `observeApply` exposes it as
`reconcileReason`. Home attention listing is T10.5's.

**6. No new cross-tenant read.** The worker hands the reconciler a tenant from `OR-44`'s existing enumeration, which already yields a tenant whose only open
row is `APPLY_INDETERMINATE`; boot enqueues (a bookkeeping row due now) and never observes a host.

**Not claimed:** that this decision or `BE-D12`/`BE-D16` is ratified; that obligation (r) is closed; that the give-up reaches home attention (T10.5).

**Clarification (2026-10-11, plan 015 W4 note (d), the follow-up review of the W3 integration; appended, no meaning changed, `I3`) - a deliberate
`dp-remote` behaviour change.** Section 4 says a re-drive's conditional re-write is "journaled as another attempt of its original `MATERIALIZE` intent".
That attempt used to close `SUCCEEDED` with **no `write_baseline`** (`dp-remote` never recorded a redrive baseline; parity with the forward write was
missing). `ApplyExecutor#rematerialize` now closes it with `WriteBaselinePolicy.of(adapter, replica.placement(), intent.intentId(), status)`, the same
call the forward `materialize` makes: for `dp-remote` that is `status.baseline()` (the container start the read-back reported), and for the agent path the
ack's start of the intent's newest attempt that has one (`AGW-D28` item 2, `OR-139`, clarified), so a re-drive that retries after a read-back miss no longer
closes `SUCCEEDED` without it. The config ladder's start route can therefore compare a later container start with a re-driven write's baseline, where before a
re-driven request could not reach `restart-observed` by that route. Proved by `IndeterminateReconcilerIT` (`dp-remote`), `AgentAckBaselineIT` (agent), and
the `dp-remote` baseline ITs (`ConfigLadderIT`, `PostRestartVerifyIT`, `RestartObservedGenerationIT`, `RollingApplyIT`, `StartObservationIT`), run green on the lane's final tree (counts in plan 015 T4.6 Results).

---

## `BE-D35` — The seam gains one operation, `rematerializeIfCurrent`, a conditional re-write that only the reconciler calls ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder (plan 010 T6.8, M-4); the integrator allocated `BE-D35` at merge, beside `BE-D25`'s
precedent of growing the seam. It amends `BE-D12` §2.1's operation list from eight to nine; it edits no earlier decision (`I3`).

`materializeConfig` writes without re-checking its target (unlike `restore`, which re-reads the pre-image). A reconciler that observed "prior content" and then
wrote with `materializeConfig` could overwrite a file a human saved in between. `rematerializeIfCurrent(replica, generation, config,
expectedCurrentHash)` re-reads the replica's file immediately before the write, writes **nothing** and throws `reconcile.target.moved` if the file's hash is
not `expectedCurrentHash` (`null` = still absent), and otherwise behaves as `materializeConfig` — zero lifecycle effect, atomic replace, read-back. It captures
**no pre-image** (the generation's was retained by the original write; capturing again could retain content SM did not write — the Docker contract test caught
exactly that in the first draft, so `ConfigFileStore.rewrite` exists). `RuntimeSeamTypesTest` pins the nine operations; the adapter contract kit gained five
tests; `SshDockerRuntimeAdapter` and `DockerAdapter` implement it.

**The residual window.** It narrows the race exactly as `restore` does; it does not close it. Neither SFTP nor a bind-mount write has a compare-and-swap, so a
writer that lands between the re-check and the atomic replace is not detected. This is stated, not hidden.

## `BE-D39` — The seam gains a tenth operation, `readConfig`: read-only, bytes plus a keyed hash, parsed in dp-core ⚠️

**Status: PROPOSED.** Drafted as D-ACCEPT's draft A-1 ([`accept-runtime.md`](accept-runtime.md) §3.1, §8) under a `«next free BE-D»`
placeholder (plan 010 T10.3); allocated at T10.4 as `BE-D36` and renumbered `BE-D39` at the S8-S11 merge, because plan 010 T9.3 took `BE-D36`–`BE-D38` first. It amends
`BE-D12` §2.1's operation list from nine to ten, the third time the seam has grown (`BE-D25`, `BE-D35`); it edits no earlier
decision (`I3`). Implements `OR-45` (accept-runtime needs a read-back design) as ruled by `OR-104`.

`accept-runtime` must read the host's config file back into values. The seam deliberately returned only a keyed hash
(`RuntimeStatus.observedHash`), and the SSH transport's own javadoc says it "cannot express a hash computed on the customer
host". `readConfig(TargetReplica)` returns a closed `ConfigReadBack`: the file's bytes (bounded at 1 MiB, the SSH adapter's
`MAX_FILE_BYTES`, enforced identically by the Docker adapter) and the keyed hash (`OR-32`) the adapter computed over exactly
those bytes. Reading is **read-only by shape** (the Docker implementation holds no reference to the engine client; the SSH one
calls only `readFile`), so it is safe under any strategy and at any time.

**Parsing is not in the seam.** A parser belongs in one place, so no adapter contains a YAML parser; dp-core parses
(`BE-D40`). The bytes carry every secret SM rendered, in clear, so `ConfigReadBack` is deliberately inert (`I6`): no printable
form that names a byte (length and hash only), no `equals` over the bytes, a copy out and never its own array, and a `close()`
that zeroes the array it owns, called in a `finally`.

**Shape of the change.** A `default` method on `RuntimeAdapter` that throws `config.read.unsupported`, overridden by the two
certified adapters and the in-memory one, rather than an abstract method: some fifteen test classes delegate the seam, and an
abstract method would have broken every one of them (and every other lane's new delegate) for no safety, since the contract kit
pins the operation for every certified adapter (four tests: bytes and keyed hash, never mutates, a second read after a close,
an absent file is `config.read.absent`). `RuntimeSeamTypesTest` pins the set at ten. **Only code in `dp.apply.runtime..` may name
`RuntimeAdapter`** (`onlyProductionCodeTouchesTheMutatingRuntimeAdapter`), so the read-back service lives there and is wired by
`RuntimeReadBackConfiguration`, which looks the adapter up lazily (a boot with the production adapter withdrawn answers `503`
instead of failing).

## `BE-D40` — A read-back file is parsed with a pre-scan that refuses what Jackson reads wrongly ⚠️

**Status: PROPOSED.** D-ACCEPT's draft A-2 ([`accept-runtime.md`](accept-runtime.md) §2.2, §3.2); allocated at T10.4. Resting on a
measurement made 2026-10-05 with a 20-line probe against the reactor's own library versions (Jackson 2.21.4 over SnakeYAML 2.4)
and Spring Boot 3.5.16's `OriginTrackedYamlLoader` options, fetched from source: **Jackson reads an alias as its anchor's
name** (`y: *a` becomes `"a"`, no error), a merge key as a literal `<<` key, a duplicate key last-wins, and a second document
not at all, while Boot honours aliases and merge keys and refuses a duplicate. A hand-edited file can therefore be valid to the
MI and wrongly read by SM, and a wrongly read value would become the new desired configuration.

So a SnakeYAML **event** pass (no node graph is built, so there is nothing to expand) refuses an anchor, an alias, **any**
explicit tag (the renderer never writes one; the design said "non-core", the build is stricter), a second document, a
non-mapping top, a non-scalar mapping key, nesting past 32 and more than 100,000 events; then Jackson reads the tree with
`STRICT_DUPLICATE_DETECTION`. Every refusal is a closed reason with fixed text and **no cause** (a parser's message quotes the
text it choked on, which can be a secret fragment). The round-trip check that catches a mapping that lost or changed a value
runs in the gateway, where the server's own projection is (see the note at the top of `accept-runtime.md`). `SnakeYAML` is now a
declared dependency of dp-core, an artifact already on its classpath through `jackson-dataformat-yaml` (no new artifact, `I8`).

## `BE-D41` — A read-back secret is never stored; it is compared in memory and reported `same` or `differs` ⚠️

**Status: PROPOSED.** D-ACCEPT's draft A-3 ([`accept-runtime.md`](accept-runtime.md) §3.4, §3.5), **amended by owner ruling `OR-104` Q-A2
(`V-4`)**: allocated at T10.4. Ruled 2026-10-06, "since it is useful for user to identify if password is different". Satisfies the
plan's S10 non-goal and `I6` (a secret value is never in config storage, history, a hash input an attacker can test, a log, an
event or a response).

A secret field's node is never read into the draft. The accepted draft **keeps its current `secretRef`**. The field is listed
`secret-not-read` with, at most, `comparison: same|differs`: the data plane compares the file's value with the stored secret in
memory, in constant time, through **`SecretCompare`**, a `dp-api` port with one operation that returns an `Outcome` and
nothing else. The code that reads a file back holds that port and no `SecretResolver` or `SecretStore`, so it can compare and drop a
read-back secret but cannot fetch, return or adopt one: arch-tests' `readBackNeverHoldsASecretReader` enforces that, with a
planted violation. The candidate's text exists for one call and is zeroed straight after; the stored material is closed (zeroed)
before the answer returns. "Could not compare" (a foreign tenant's secret, another purpose, no stored reference, a key that cannot
be opened) makes **no claim** (no `comparison`), never `differs`. **Stated residual:** a `String` the YAML parser builds from the file
lives until collected in the request thread; it is the same class of residual as `SecretMaterial`'s, and it is not a persistence.

**What this does and does not fix.** The next apply rewrites the file from the generation, so a password changed on the host is
**reverted by the next apply** whatever accept does. `V-4` turns that from "may differ" into "differs", which is what the owner
wanted; it does not adopt the host's password. A `yaml`-typed field is never accepted (`yaml-field-not-accepted`) and keeps what it
holds (Q-A6 is not yet ruled; no v1 package has one). SM's own keys (`management.*`, `logging.level.*`, today a stand-in for D-LOG's
`OverlayKeys`) are stripped before any declared match and listed `sm-owned-overlay`. Matching is the exact declared kebab path
(`M-1`); `maxSize` and `max_size` are listed `undeclared-key`, not mapped. This amends nothing earlier; it implements the S10
non-goal.

## `BE-D42` — `accept-runtime` stages an `accept-runtime` generation, writes nothing to the host, and reports what it did not adopt ⚠️

**Status: PROPOSED.** D-ACCEPT's draft A-4 ([`accept-runtime.md`](accept-runtime.md) §3.5 to §3.8), as ruled by `OR-104` Q-A1 (`U-2`),
Q-A3 (`X-1`) and Q-A5 (a); allocated at T10.4. Implements `OR-45` and respects `B/D12` 🔒 (a manual act, never an auto-revert).

The composed draft is the latest generation's draft with each mapped non-secret value that **differs from the field's effective
value** written in, each declared non-secret field the file does not carry unset, and secret and `yaml` fields untouched. It goes
through the same server-side half `stageConfig` runs (`OR-34`): the server re-projects, refuses an undeclared key and a blocking
validation (a `422` naming the package's own field key and a closed rule code, never the value), checks the round trip, and stores its
own result with `origin = accept-runtime`, `derivedFromGeneration` = the latest, and the presentation layout left alone. A warning does
not block. Equal content writes no row (`OR-33` guard 1): `200 unchanged: true`, legitimately with a non-empty report.

**`U-2`:** keys the package does not declare are listed by path only in `notAccepted[]` (capped at 200, `truncated`), and the next apply
removes them. **`X-1`:** nothing is written to the host; `fileBytesDifferFromRendered` is true when the file read back is not
byte-identical to SM's rendering of the returned generation (re-rendered in the DP with secrets resolved in memory, compared by keyed
hash; "could not compare" reports `true`). **`Q-A5 (a)`:** a host that did not answer is `503 SERVICE_UNAVAILABLE` ("a dependency did not
answer; retry"), not `INTERNAL_ERROR` and not `VALIDATION_FAILED`; a file that was read and is refused is `422`. The operation requires
`config.edit` **and** `config.overrideWarning` at the instance, the second named on denial. The config of record is per instance, so
accepting one placement's file changes the instance's desired configuration for every placement (`BE-D23` makes v1 single-placement).

---

## `BE-D36` — Log levels: the level is read live over SSH, and every change is configuration ⚠️

**Status: PROPOSED.** Drafted under a `«next free BE-D»` placeholder (plan 010 T9.2, [`log-levels.md`](log-levels.md) option **Rd**); allocated here by plan 010 T9.3
under owner ruling `OR-103` (2026-10-06, Q-L1 option C). It **amends `OR-43` P1's live `SET` for v1** and edits no earlier decision (`I3`): `OR-43` stands unedited, and its
live set remains the documented later step (option Ra), which nothing built here forecloses.

**Decision.** For a placement whose package declares logger support (`BE-D38`):

1. `getLogLevel` is a bounded live read: the DP asks the placement's own Spring Boot loggers endpoint over the pooled SSH session, from the host's loopback, through the
   same fixed `curl` the health poller runs (`HostProbe`), at the placement's declared host port. The logger name is the component's `loggerPrefix`, never a request field.
2. SM exposes the endpoint **read-only**: the overlay (`BE-D37`) adds `loggers` to `management.endpoints.web.exposure.include` and sets
   `management.endpoint.loggers.access: read-only`, which measured to answer `405` to a write (`log-levels.md` P-7). There is **no `management.server.port`**, no
   `management.server.address`, no move of any actuator endpoint, and no write surface.
3. Every level **change** is configuration (T9.4: a normal apply and a restart). `setLogLevel` therefore admits nothing in this release: `mode: config` and `mode: live`
   both answer `501 NOT_IMPLEMENTED` with a fixed detail, and `mode: live` on a direct-HTTP instance answers `422` as the contract states. There is no journaled live-set
   operation, no fourth `apply_request.operation`, no `V013` for it, and `runtimeOverride` is `false` because SM can never have set one.
4. A host that cannot answer is an **availability with a reason**, never an error: `direct-http` (no SSH path), no declared host port, a refused or timed-out
   connection, a `404` (not exposed yet, or the logger is unknown to the process), a level outside the contract's set, an answer that is not a level. A reason is fixed
   text and quotes neither the package nor the host's body.
5. Two grammars are checked in the DP **before any lookup or session**: the logger prefix (`[A-Za-z_][A-Za-z0-9_]*` segments joined by `.`, at most 255, not `ROOT`) and the
   declared loggers path (`/` segments of `[A-Za-z0-9_.-]`, no `..`). The prefix grammar is stricter than a Java identifier on purpose: **`$` is excluded**, because the probe
   command does not quote the URL and the host's shell would expand it.

**What this deliberately does not do.** It does not read the *configured* level (T9.4's), does not offer `OFF` or `FATAL`, and does not claim the Cache starts with the overlay or
that a real Cache answers as the probe did: that is the owner-gated E2E. The port read is the placement's declared health host port, on the premise (Rd's own) that the
actuator did not move; a placement with no declared port is `unavailable`, not guessed.

**Flagged.** (1) `OR-103` rules Rd for v1 only; if the owner later schedules Ra, Q-L2, Q-L3 and Q-L5 of `log-levels.md` are asked again. (2) The contract's `setLogLevel` text
and the endpoint inventory are corrected to say so (`console.yaml`, `ENDPOINT-INVENTORY.md`).

---

## `BE-D37` — The SM-owned overlay is one function, shared by the stage, the renderer and the read-back ⚠️

**Status: PROPOSED.** Drafted as draft L-2 of [`log-levels.md`](log-levels.md) §4.1; allocated here by plan 010 T9.3 (`OR-103`). Together with `BE-D38`, it implements `OR-34`'s
"refuse an undeclared key" unchanged: that rule is about keys the **client** supplies, and the client never supplies an overlay key.

**Decision.** `OverlayKeys` (`dp-core`, `com.solace.sm.dp.config`) is the one place that decides which properties SM adds beside a package's fields and what they say
(`forLoggerSupport`), and the one predicate that says which paths are SM's (`owns`).

- **The stage** (`CanonicalContent`) is where overlay entries enter a generation. The gateway resolves only the package's *fact* (`LoggerSupport`: its `logs.baselineExposure`) and passes it
  in the stage command; it cannot mint a key. The DP appends the entries to the rendered set under the reserved instance key `@sm-overlay`, so they are part of the keyed digest (a
  changed declaration is a new generation), travel with a copy-forward unchanged, and make a resumed apply render the same bytes whatever the package says by then (the worker never
  reads a package, M8). A stage refuses a package field at an owned path, an overlay-keyed entry at a path SM does not own, and a command that both carries overlay entries and declares support.
- **The renderer** (`ConfigGenerationRenderedConfigSource`) writes the stored entries as top-level keys beside the component sections, and refuses an overlay-keyed entry at a path `owns`
  says is not SM's (the second line, for a row a second writer put there).
- **The read-back** (accept-runtime, T10.3) strips by `owns` before matching, and reports an operator's edit of an owned key as `sm-owned-overlay`. T10.3 must call `owns`, not restate the list.

The exposure value is the **sorted union** of the package's declared baseline and SM's required names, because the SM-written file replaces the MI's own exposure list and SM cannot
read the effective one (`UI-D10`). A baseline entry outside the endpoint grammar, or a component that declares loggers with no baseline, yields **no overlay** (fail-safe: a list that
withdrew what the MI exposes today would be worse than none).

**Not built here.** `logging.level.<prefix>` (P2, T9.4) and `management.server.*` (option Ra) are not emitted; `OverlayKeys.owns` gains the former when T9.4 adds it.

---

## `BE-D38` — A package declares logger support in `logs.loggers`; nothing is inferred ⚠️

**Status: PROPOSED.** Drafted as draft L-5 of [`log-levels.md`](log-levels.md) §8 (option G2); allocated here by plan 010 T9.3 under `OR-103` Q-L4 ("go ahead with recommendation"). The schema
change is the reviewed, additive, optional one `contracts/` single-writership requires.

**Decision.** `contracts/solution-package.schema.json` gains `logs.loggers` (closed object, both members optional): `path` (default `/actuator/loggers`, the probe-path grammar, because it
reaches a shell) and `levelProperty` (default `logging.level`, T9.4's). The empty object declares support with both defaults. Two cross-field rules are schema-level: a component that declares
`logs.loggers` must declare `loggerPrefix`, and `logs` that declares `loggers` must declare `baselineExposure` (`BE-D37`'s union needs it). A package with no block gets **neither** the live read
**nor** the overlay: `loggerPrefix` alone is never read as support (a non-Boot MI can carry a prefix), and `getLogLevel` says so as `unavailable` without touching a host. No branch on the solution
name (`OR-53`); the Cache fixture gains the block as an ordinary package would.

---

## `BE-D44` — The apply journal is placement-aware ⚠️

**Status: PROPOSED.** Drafted as plan 008's draft decision 1
([`../../plans/backend/008-placement-registry-and-apply-journal-identity.md`](../../plans/backend/008-placement-registry-and-apply-journal-identity.md));
allocated 2026-10-07 under owner ruling **`OR-121`** (plan 008 OQ-8.2 (a), the draft's recommendation). Built by plan 008
S2 (`sm_data` migration allocated in the [combined schedule](../../plans/backend/combined-008-012-013.md#5-migration-numbers)).

`apply_step_intent` carries the placement each intent addresses; uniqueness is `(tenant_id, apply_id, placement_id,
step, replica_ref)`. **The idempotency key names the placement only for an instance-wide request**
(`applyId:placementId:step:replica`); a placement-scoped request keeps `applyId:step:replica`, because its `apply_id`
already fixes the placement, so every request admitted before the migration resumes onto its own rows. The migration
refuses to run while an instance-wide stop is non-terminal. **Supersedes `BE-D23`'s identity statement** (both of its
clauses are closed in the DDL); `BE-D23`'s prohibition on encoding a placement into `replica_ref` is carried forward
unchanged. `BE-D23`'s text is not edited (`I3`).

---

## `BE-D45` — A rollout across an instance's placements is a fleet of placement-scoped applies ⚠️

**Status: PROPOSED.** Plan 008 draft decision 2; allocated under **`OR-121`** (OQ-8.1 (b)).

An instance-wide **apply** stays refused before admission. Rolling a generation across an instance's placements is a
fleet whose members are those placements (`REQ-013`'s batches, `halt-remaining`, per-member journals), because
`OR-53` (`placementId` required), `OR-4` (placement-scoped events), `OR-42` (per-placement rollback evidence) and every
per-placement read path already hold. "Between placements" therefore belongs to the fleet: plan 012's settle delay is a
wait between fleet batches and its outage confirmation is fleet-level (`BE-D50`, `BE-D51`). Amends `BE-D12` §2.1 and
`BE-D21` by cross-reference; disposes of `eda-walking-skeleton.md` §8 row 14 as unreachable.

---

## `BE-D46` — An instance-wide stop addresses the instance's live placements from the registry

**Status: PROPOSED.** Plan 008 draft decision 3; allocated under **`OR-121`**. Built by plan 008 S3 (moved there from
S4 by the combined schedule, because S3 also builds placement retirement, which makes the old enumerator's defect
reachable).

The instance-wide stop enumerates `PlacementRows.live`, never `apply_request` history; a retired placement in the
history no longer fails the stop; an instance with no live placement lands `STOP_FAILED` / `stop.no.known.placement`.
An ArchUnit rule forbids a placement enumerator in `apply.persistence`, with a planted violation. Amends `BE-D26`'s
enumerator by cross-reference.

---

## `BE-D47` — A placement is added to an active instance by `addPlacement` ⚠️

**Status: PROPOSED.** Plan 008 draft decision 4; allocated under **`OR-121`** (OQ-8.3 (a), OQ-8.3s (i), OQ-8.8 (a),
OQ-8.9 (a), OQ-8.10 (a)).

`POST /dp/instances/{instanceId}/placements`, authorized by `instance.bindTarget` **at the instance** (`OR-16`) and
`target.use` at the target, in one DP transaction that holds the instance row `FOR SHARE` and re-checks `active`, in
`reserve`'s lock order; the probe and metrics are copied from the instance's declared package version, never the
body. It does not count against the instance quota. A package that declares a `solace_event` metric source is
refused `409` (push attribution cannot name a placement, `I1`). The call re-settles the ladder through the landing
path's settle function and firing rule, so the stored rung, the read and `instance.config-state` agree.

---

## `BE-D48` — `apply_request.placement_id` is referentially guarded

**Status: PROPOSED.** Plan 008 draft decision 5; allocated under **`OR-121`** (OQ-8.5 (a)). Built by plan 008 S4.

An apply request cannot name an unknown placement or a placement of another instance: a foreign key over a `STORED`
`NULLIF(placement_id, nil)` column if plan 008's MF-3 measures PostgreSQL 14 accepts it, otherwise a `BEFORE INSERT`
trigger with a named refusal. The nil UUID stays the instance-wide sentinel. Liveness stays the service's and the
catalog's (a retired placement passes the guard and is refused at pre-flight). Closes the second half of
`eda-walking-skeleton.md` §8 row 13 for unknown and other-instance placements.

---

## `BE-D49` — Undeclare releases an instance from management; it never deletes and never touches the workload ⚠️

**Status: PROPOSED.** Drafted as O-1 in [`ops-501.md`](ops-501.md) §9; allocated under **`OR-121`** (OPS-Q2 U-C, named
in the ruling; OPS-Q3 P-1 with Administrator + Operator; OPS-Q4 (a); OPS-Q5 (a)). Built by plan 012 T3–T5.

Undeclare is `DELETE /dp/instances/{instanceId}?workload=leave-running`, authorized by `instance.undeclare` at the
instance. It runs three steps: DP `beginRetire` (refuses anything in flight, under the `apply_scope` lock), CP
`deregister` (inside the request, under its session's audit context), DP `completeRetire`. Recovery mirrors `BE-D32`
and **never writes the Control Plane**: a lazy, per-request, tenant-scoped sweep completes a `retiring` row whose CP
node is already deregistered, and rolls back to `active` a `retiring` row whose CP node is still live after the
window; an authorized retry resumes at the CP step under its own session. Nothing is deleted in any schema; `BE-D32`
rule 7 stands. Bindings at a retired instance's scope grant and reveal nothing. The workload, the config file and SM's
hidden files on the host are left as they are. Retirement frees the instance name (extending `OR-88`), its placements'
container names, directories and ports, and its quota slot. An unresolved `APPLY_INDETERMINATE` blocks undeclare; a
manual-resolution API is routed to its own design.

---

## `BE-D50` — The ungated strategies' exact semantics

**Status: PROPOSED.** Drafted as O-2 in [`ops-501.md`](ops-501.md) §9; allocated under **`OR-121`** (OPS-Q8 K-2 and
(x); plan 008 OQ-8.1 (b)). Built by plan 012 T2 and T6.

`rolling-no-validation` and `restart-all` restart one placement with no gate and verify the read-back (`OR-64` Q-G2);
on one placement they are the same host action. Across an instance's placements (a fleet, `BE-D45`): a
`rolling-no-validation` fleet opens its next batch no earlier than `sm.dp.fleet.no-validation.settle` (default 30 s)
after the current batch's last member's `terminal_at`; no `rolling-*` fleet batch may hold two placements of one
instance (`422`, `fleet.batch.instance.repeated`); a `restart-all` fleet does not wait and may, under its
confirmation.

⚠️ **Rider, 2026-10-08 (`W4 review W4B-1`; no meaning changed, `I3`): the settle delay's anchor is extended beyond
`terminal_at`.** The text above says the delay runs from the last member's `terminal_at`. A member that settled `failed`
because the reconciler gave up on an `APPLY_INDETERMINATE` request has no `terminal_at` (`terminal_has_timestamp`, V002),
yet the host may still be restarting: the reconciler's first inspection is due at once and several classifiers give up on
it. The reconciler closes the request's `apply_reconciliation` row with `gave_up_at` in the give-up transaction (V011), a
persisted instant. The built anchor is therefore `COALESCE(apply_request.terminal_at, apply_reconciliation.gave_up_at)`,
tenant-bound, with no migration. **Owner to confirm the extension** (the alternative is the literal `terminal_at`, which
leaves a given-up member unanchored and lets the next batch open at once). Separately, `sm.dp.fleet.no-validation.settle=0`
(withdraws the delay) was added by the lane and neither this decision nor `OPS-Q8` (x) rules on it: **owner to confirm that
`0` is allowed** or require a positive value (`W4B-5`). Both rules apply within one fleet only (`W4B-2`; stated in the
contract).

⚠️ **Rider, 2026-10-09 (Fable F-5; no meaning changed, `I3`): `settle = 0` is confirmed.** The "owner to confirm that `0` is allowed" above is
answered by `OR-125` (6) ("the settle delay may be configured to 0"), so `sm.dp.fleet.no-validation.settle=0` is a ruled configuration,
not an open question. The `COALESCE(terminal_at, gave_up_at)` anchor is confirmed by the same item. `operations.md` ("Known limits") says so.

---

## `BE-D51` — Overriding restart safety is a verb, and an outage confirmation is the caller's principal ⚠️

**Status: PROPOSED.** Drafted as O-3 in [`ops-501.md`](ops-501.md) §9; allocated under **`OR-121`** (OPS-Q6 B-1 with
Administrator + Operator; OPS-Q7 C-2). The verb is row 40 of the ratified `D10` amendment's §1.9. Built by plan 012 T1
and T2.

Both ungated strategies require `instance.restart.overrideSafety` at the instance, in addition to `config.apply` (or
`config.rollback`) and `instance.restart`; a fleet asks it per member in `verdict()`. `restart-all` additionally
requires a confirmation, checked in the gateway before any DP call; the wire keeps `outageConfirmedBy` (added to
`rollbackApply` and `restoreRuntimeConfig`) and ignores its value; the server records the session principal id in
`apply_request.outage_confirmed_by` and `fleet_action.outage_confirmed_by`.

---

## `BE-D52` — A scheduled apply is a DP-owned schedule record admitted as an ordinary apply inside its window ⚠️

**Status: PROPOSED.** Drafted as O-4 in [`ops-501.md`](ops-501.md) §9; allocated under **`OR-121`** (OPS-Q9 Sch-2,
which the ruling states *"amends `OR-53`/`OR-60` by name"*; OPS-Q10 (c) by the draft's recommendation). Built by plan
012 T7.

`sm_data.apply_schedule` holds the request until `notBefore`; a `ScheduleFirer` in `console-gateway` finds due rows
through a new `dp-api` port (`ApplySchedulePort`: `tenantsWithDueSchedules`, `due`, `admit`), re-authorizes the
submitter with `require` at fire, and admits an ordinary apply. A scheduled rollback copies its target forward at fire,
not at submit; a stage landing in between does not cancel it. Outside `notAfter` it lands `MISSED`; cancel is a state
of the schedule row (`config.apply` at the instance). The apply journal, `RequestState`, the fences and the event
catalog are unchanged. `strategy: "scheduled"` answers `422` (use `schedule`). ⚠️ Shape (c) is a third
application-side cross-tenant read, exposed across a module API; the draft states it amends `OR-44`/`OR-49`/`OR-92`,
which `OR-121` adopts through its blanket clause without naming them — flagged for the owner in the combined schedule.

---

## `BE-D53` — One placement is retired by the verb that adds one, under undeclare's rules

**Status: PROPOSED.** Drafted as O-5 in [`ops-501.md`](ops-501.md) §9; allocated under **`OR-121`** (OPS-Q12 R-1;
plan 008 OQ-8.4, which routed it here). **Built by plan 008 S3** (ruled before S3 started); plan 012 T5 adds the
`instance_retirement` record once that table exists.

`DELETE /dp/instances/{instanceId}/placements/{placementId}?workload=leave-running`, `instance.bindTarget` at the
instance; never touches the host; refused `409` for the last live placement (that is undeclare) and while any
non-terminal request or running fleet reservation names it; one DP transaction, no CP write beyond the authorization
audit line. It ships together with `BE-D46`'s live-placement enumerator.

⚠️ **Rider, 2026-10-08 (`W2 review W2A-1`; no meaning changed, `I3`): the "authorization audit line" does not exist.** The
authorization path writes no per-call audit line for these routes, and neither does the gateway, so "no CP write beyond the
authorization audit line" describes a record that is not there: **as built, neither `addPlacement` nor `retirePlacement`
records WHO** (see the changelog's W2 review rider and plan 008 S3 deviation (6)). Do not read this decision as promising an
actor record.

---

## `BE-D54` — Targets are registered over HTTP in one composed call, pre-checked before either plane is written ⚠️

**Status: PROPOSED.** Drafted in [`registration-api.md`](registration-api.md) §12; allocated under **`OR-121`**
(D-REG OQ-1 (a), OQ-7 (a), OQ-8 (a), OQ-11 (b), OQ-12 (a), OQ-13 (a), OQ-14 (b)). Built by plan 013 W1.

`POST /cp/targets` authorizes `target.create` at the requested ownership scope writing nothing; runs every data-plane
pre-check read-only (grammar, key parse, address policy, a fresh scan with the pin offered, host-key uniqueness); then
writes the CP governance row; then the DP connection record with its `SSH_PRIVATE_KEY` envelope. Each write is
idempotent on its natural key, and a concurrent duplicate re-runs the replay path. A replay is detected by record
**and** by comparing public-key fingerprints; a different key is a `409`. The private key is write-only JSON, parsed
(unencrypted only) before it is sealed, and represented outside `dp-core` only by its public-key fingerprint.

---

## `BE-D55` — A host key is pinned only explicitly and only if offered on a fresh DP scan; the DP refuses forbidden addresses at every use ⚠️

**Status: PROPOSED.** Drafted in [`registration-api.md`](registration-api.md) §12; allocated under **`OR-121`**
(OQ-2 (b), OQ-3 (d)). Built by plan 013 T1.2, T1.4, T1.5.

Scans are untrusted and pin nothing. Registration and re-pin re-scan from the DP and refuse a pin the host does not
offer; the whole offered set is recorded (`OR-89`), and a re-pin retires the keys no longer offered. The DP refuses to
register, and refuses to connect at every later use (apply, pool, health, metrics), when the target's name resolves to
a loopback, link-local, unspecified or multicast address, or to one outside an optional operator allow-list. It
connects to the checked address where the client supports it. The production policy has no relaxing setting.

---

## `BE-D56` — Data-plane target changes are audited in the same transaction, in `sm_data`

**Status: PROPOSED.** Drafted in [`registration-api.md`](registration-api.md) §12; allocated under **`OR-121`**.
Built by plan 013 T1.3–T1.5.

`target_connection_event` is append-only and written with every insert, edit, re-pin, rotation and retirement of a
connection record; it holds field names and the public-key fingerprint, never key material or a reference.

---

## `BE-D57` — The scope tree is named, and sub-organizations and environments are created over HTTP

**Status: PROPOSED.** Drafted in [`registration-api.md`](registration-api.md) §12; allocated under **`OR-121`**
(OQ-1 (a), OQ-5 (a)). Built by plan 013 W2. ⚠️ **The `name` columns themselves are `OR-120`'s** (`sm_control`
`V015__hierarchy_display_names.sql`, 1–100 characters with a shape CHECK, on branch `p/names`); this decision adds only
per-parent uniqueness, the runtime write grants and the HTTP routes. D-REG's 1–128 length is withdrawn in favour of
`OR-120`'s published shape.

Organization, organization unit and environment carry a `name`, unique per parent. `hierarchy.edit` creates
sub-organizations and environments at the parent scope and renames nodes. Organizations are not created over HTTP in
v1.

⚠️ **Rider, 2026-10-08 (`W5 review W5C-1`; no meaning changed, `I3`): "renames nodes" is not one scope.** Per **`OR-125`** (3) an
**organization** rename is evaluated at **install** scope (`hierarchy.edit` at the install anchor, then at the named
organization), so an organization-bound Administrator is refused `403` and the install-wide organization-name `409` is no
cross-tenant oracle; a sub-organization or environment rename still evaluates `hierarchy.edit` at the node, and its sibling-name `409`
inside one organization is accepted. `D10-G2-APPROVAL-2026-09-15.md` §1.9 row 42 carries the same dated note. **Consequence
(`W5C-2`, ruled `OR-131`):** only the install-bound principal's own organization can be renamed over HTTP; any other organization is renamed by the audited operator command `OrganizationRenameMain`.

⚠️ **Rider, 2026-10-09 (Fable F-2; no meaning changed, `I3`): the operator command is audited with no actor.** `OR-131` Q1 ruled it "in the
`EntitlementMain` pattern", which writes an `operator-cli` audit row naming no actor. The wave-6 build instead named a local principal of
the organization as the actor (a deviation); `sm_control` V020 admits a no-actor `HIERARCHY_NODE_RENAMED` on `organization` from client
address `operator-cli` only, and the command now records no actor. The HTTP rename is unchanged: it names its authenticated principal and a
no-actor write from any other address is refused by the database. Honest limit: the `operator-cli` gate catches an application path that forgets its actor; it is not authentication: any `sm_cp_runtime` session could set the client-address GUC, and the HTTP application cannot produce `operator-cli` only because `ClientAddresses.canonicalize` accepts IP literals only (the same posture as `EntitlementMain`'s V018 exemption).

---

## `BE-D58` — The plain-VM runtime is reached over SSH as transport `ssh-systemd`, paired with target kind `vm` ⚠️

**Status: PROPOSED.** Drafted as V-1 in [`vm-adapter.md`](vm-adapter.md) §11; allocated under **`OR-121`** (plan 014
**approved as a plan, implementation deferred**; Q-V10 (a) and Q-V2 (a) by the draft's recommendation, Q-V0 (c)
named in the ruling).

A target of kind `vm` is reached only by `ssh-systemd`: pinned-host-key SSH, SFTP for the one config file, and the
closed `systemctl` command set of the design's §8. A placement on such a target names a `.service` unit inside the
target's immutable `allowed_unit_prefix`. The adapter is `SshSystemdRuntimeAdapter`, certified by the contract kit, and
the engine reaches it through a routing adapter keyed by the DP connection's transport. Rests on the owner's `OR-62`
amendment (Q-V10 (a)). It adds a second implementation of `BE-D12`'s operations; it does not change them.

---

## `BE-D59` — A systemd replica's start token is `ActiveEnterTimestamp` in UTC and its `InvocationID`

**Status: PROPOSED.** Drafted as V-2 in [`vm-adapter.md`](vm-adapter.md) §11; allocated under **`OR-121`** (Q-V4 (a),
named in the ruling: systemd 248 or later only).

`PreBaseline.startedAt` is `ActiveEnterTimestamp` printed with `--timestamp=us+utc`; `processRef` is `InvocationID`.
A zero or empty stamp is no baseline. The adapter refuses hosts below systemd 248 and never parses a local-zone stamp.

---

## `BE-D60` — On a VM, SM's account is unprivileged except for exact-argv sudo rules per unit

**Status: PROPOSED.** Drafted as V-3 in [`vm-adapter.md`](vm-adapter.md) §11; allocated under **`OR-121`** (Q-V1 (a)).

Reads use unprivileged `systemctl show`. Writes are confined by filesystem permission to the declared directory.
Restart and stop run through `sudo -n` against `sudoers` lines with exact arguments, generated by SM from an
operator-supplied `systemctl` path and proven by `prepare`'s `sudo -n -l` before every apply; the log rule is
`BE-D63`'s. No root login, no wildcard, no shell. This is `SM-D11`/`SM-D11a` made concrete for systemd.

---

## `BE-D61` — Secret-bearing files are given an explicit mode and checked before they reach the target path ⚠️

**Status: PROPOSED.** Drafted as V-4 in [`vm-adapter.md`](vm-adapter.md) §11; allocated under **`OR-121`** (Q-V13 (b):
the Docker half is a separate, dated task before the `OR-115` PR — task X1 of the
[combined schedule](../../plans/backend/combined-008-012-013.md); the VM half is plan 014 T4).

The rendered file and SM's retained copies get their mode by an explicit SFTP `setattr` on the temp file, which is
stat-checked before the rename and deleted with a refusal if any "other" bit is set; the target is stat-checked again
after.

**Rider, 2026-10-09 (task X1, Docker half; adds, does not change the decision).** The mode is **0640** (owner read/write,
group read, no "other" bit). Measured on the E2E host (read-only, redacted): the Cache process runs as uid:gid 0:0 inside
a rootful container, the bind-mounted directory is owned 1000:1000 mode 700, and `application.yml` and the pre-image
were 1000:1000 mode 664 (the SSH session's umask 0002); the container reads the file, so dropping "other" loses nothing.
It applies to the config file, its pre-images and their fingerprint files (every write goes through the transport's
`writeFileAtomically`), is a named constant (`SshjDockerTransport.SECRET_FILE_MODE`, not configurable), and a refusal
carries the classifier `ssh.config.mode.too.open`.

**Rider addendum, 2026-10-09 (X1 review X1-1, X1-4, X1-5, X1-7).** The temp file is **created with** 0640 (the create request
carries the attributes) and set again by `setattr`, so no secret byte is ever written to a file more open than 0640; a server
that rejects the `setattr` is `ssh.config.mode.unsupported`; a stat that fails after the rename is
`ssh.config.mode.unverified`, and any post-rename refusal means the target is already replaced (pre-image rollback applies).
The group read bit is kept because it matches plan 014's 0640 for the VM half; the measured reader is root, so 0600 would
also work here and the owner may tighten it.

---

## `BE-D62` — One runtime per host per organization in v1

**Status: PROPOSED.** Drafted as V-5 in [`vm-adapter.md`](vm-adapter.md) §11; allocated under **`OR-121`** (Q-V9 (a)).

The consequence of `OR-89` for mixed hosts is stated, not worked around: a host registered as an `ssh-docker` target
cannot also be an `ssh-systemd` target in the same organization.

---

## `BE-D63` — VM logs are one exact-argv `journalctl` tail of a constant line count

**Status: PROPOSED.** Written after the ruling, as [`vm-adapter.md`](vm-adapter.md) §11 said it would be; allocated
under **`OR-121`** (Q-V3 (e)).

`readLogs` on `ssh-systemd` runs one more **exact** sudoers line,
`journalctl -u <unit> -o short-iso-precise --utc --no-pager -q -n <N>` with a constant `N`, in the separate-argument
form; the DP filters the lines by time, so paging back stops at line `N`. A sudoers regex rule (Q-V3 (a)) may be added
later where sudo ≥ 1.9.10; file-only logs (Q-V3 (c)) are a later step.

---

## `BE-D64` — The metrics reset-signal vocabulary gains `systemd`

**Status: PROPOSED.** Written after the ruling; allocated under **`OR-121`** (Q-V6 (a)).

`restartDetection` and the `sm_metrics` signal CHECK gain the additive value `systemd`; stored rows are unchanged. A
client switching exhaustively on the enum sees a new value.

---

## `BE-D65` — A systemd restart that outlasts its bound is looked at once more before the adapter throws

**Status: PROPOSED.** Written after the ruling; allocated under **`OR-121`** (Q-V11 (b)). No engine change (plan 014
Global Constraint 1 holds).

On the bound, the adapter reads `systemctl show` (bounded) for a short window: a changed `InvocationID` with
`ActiveState=active` returns `issued=true` and the gate or ladder proceeds; `failed` throws `restart.failed`; anything
else throws `restart.timeout`, which lands `CONFIG_DIVERGED` as today.

## `BE-D66` — A blocking agent adapter behind per-placement routing; commands keyed on the intent key; fail fast; capped

**Status: PROPOSED.** Records `OR-128` (10) (brief Q6 (b)). Extends `BE-D13`'s write-before-act by one hop.

0. **The reference to the intent is one composite key** `(tenant, apply, intent, intent key, placement)` into `apply_step_intent`, and a
   command may address only a placement on its agent's own target (trigger), so a row cannot name two intents or another agent's
   placement (review R-13).
1. **The agent path is a `RuntimeAdapter`** (`AgentRuntimeAdapter`), blocking behind the existing seam, chosen **per placement**
   by `RoutingRuntimeAdapter` from `target_connection.transport` (`ssh-docker` -> SSH, `agent` -> agent; an unknown transport is
   **refused, never defaulted**). One `ApplyExecutor`. `RuntimeOperationContext` correlates a command with the journal
   (apply, intent, intent key; the attempt rides as correlation only).
2. **Command identity.** An agent command is keyed on the apply journal intent's **attempt-free** `idempotencyKey`
   (`applyId:step:replica`) **and its operation** — `rematerializeIfCurrent` reuses the `MATERIALIZE` intent
   (`ApplyExecutor`), so an `APPLY` and a `REMATERIALIZE` of one intent are different commands (review R-6). `enqueue` returns, in
   order: the **live** command for that `(key, operation)`; else the latest **`ACKED` with success**, **but only if no attempt on that
   intent closed `FAILED` after it** (a DP-side failure after the agent's success — a read-back that failed — must re-send, not
   no-op); else a **new** command with a new wire generation — only on the first execution or the engine's bounded retry
   after a terminal failure, refusal or supersession. A resumed apply calls `supersedeOutstanding` first (`QUEUED`/`OFFERED` of
   other keys -> `SUPERSEDED`; `DELIVERED` left to their ack or deadline). A re-sent command therefore carries the **same command
   id and wire generation**, which is what the agent's journal dedupes on: a `RESTART` runs **exactly once** across an SM crash.
3. **Fail fast.** Before enqueue, `AgentSessionPort.liveFor(agent, placement)` (open session **and** unexpired lease holding the
   placement); false -> `agent.session.absent` at once. A closed session or expired lease ends a wait with `agent.session.lost`.
   Per-operation ceilings (30/60/120 s) apply only while the session is live.
4. **An offline agent can never starve dp-remote.** The worker claims at most `sm.dp.agent.max-concurrent-requests` (default
   pool size - 1, boot-validated `>= 1` and `< pool size`; with the listener on, the pool must be >= 2) requests whose placement is
   on an agent target; at the cap the claim query skips agent-target requests.
5. **Operations:** `replicas` (one per placement, `ReplicaRef` = placement id), `prepare` (`PREFLIGHT`), `materializeConfig`
   (`APPLY`, no bytes in the command, `MaterializeResult(hasher.hash(tenant, bytes), runtimeRef)`), `rematerializeIfCurrent`
   (`REMATERIALIZE` with `expected_current_sha256`; no such generation -> `reconcile.target.moved` without sending),
   `startOrUpdate`/`stop` (`RESTART`/`STOP`), `status` (through `AgentObservedStatePort`), `assessRollback` (`Unknowable`),
   `restore` (refused `agent.rollback.unsupported`), `readConfig` (default refusal `config.read.unsupported`).

**Rationale.** The engine already serializes, journals and recovers; an adapter inherits all of it. Keying on the intent key makes
crash recovery a property of identity rather than of timing.

**Rejected.** *A global switch and a second executor* (the branch) — coexistence is the product. *Keying on the attempt* — a
resumed apply would mint a second `RESTART`. *Async/reactive adapter* — the seam is blocking; the cap is the fix for blocking.

**Enforced by:** `AgentRuntimeAdapterContractTest` (the contract kit; `restore`/`readConfig` declared unsupported; an `APPLY` and a `REMATERIALIZE` of one
intent are two commands; a success followed by a failed attempt re-sends); `RoutingRuntimeAdapterTest`; **`AgentCommandCrashRecoveryIT`** (kill after `DELIVERED`, restart over the same database: restart
count 1, one command row); **`AgentStarvationIT`**; `ProductionApplyConfigurationTest#exactlyOneApplyExecutor`.

## `BE-D67` — Agent-path parity is minimal-plus-health; everything else is a coded refusal

**Status: PROPOSED.** Records `OR-128` (10) (brief Q9 (a)). Every operation SM serves on a dp-remote placement or target either
**works** on an agent's through the same engine or answers a **contracted refusal** — never a silent no-op (Global constraint 8).

*Works:* declare via `OR-27`, stage/submit/observe apply, rolling and ungated strategies, `restart-observed`, stop, fleet apply,
health (`AGW-D28`), metrics (`AGW-D27`), config ladder, `retirePlacement`, `undeclareInstance`.

*Refused* (HTTP **501 `AGENT_OPERATION_UNSUPPORTED`**, nothing read or written, no SSH connection constructed): `readLogs`,
`getLogLevel`, `setLogLevel`, `acceptRuntimeConfig` (reads the host's config), `restoreRuntimeConfig`, and — *draft finding D-C2, confirmed by
the T0.3 review* — `rollbackApply`, refused at admission so no doomed apply request is admitted. The health poller and the
metrics collector **skip** agent placements (they have their own path); their skip is tested.

*Follow-on slice (not v1):* logs, log level, read-config, rollback restore on agent placements.

**Rejected.** *Parity with logs and rollback in v1* — each needs a new wire verb and agent code; none is `OR-27`'s point.
*Answering `200 unavailable` for logs* — reads as a host problem.

**Enforced by:** endpoint ITs per refused operation (status, `code`, **no SSH spy call**); `HealthPollerIT#skipsAgentPlacements`;
`CollectionPlanBuilderTest#excludesAgentAcquisition`; `RoutingRuntimeAdapterTest`.

## `BE-D68` — `target_connection` transport `agent`; `acquisition_mode` `agent`; the `enrolled` event

**Status: PROPOSED.** Widens `V007`, `V012` (`target_pinned_key_recorded()`), `V022` and constrains `V027`'s `host_key_algorithm` (`OR-22`: those files are never edited; `VDA` widens them). Built by plan 015 T1.1.

1. `target_connection.transport` admits `agent`. A **shape CHECK** makes each transport's fields all-or-nothing: `ssh-docker`
   needs host, port, user, pin and credential and no `agent_id`; `agent` needs `agent_id` and none of those (nor a credential
   fingerprint, nor a `host_key_algorithm`). **`ssh-docker` puts no constraint on `host_key_algorithm`**: `V027` made it nullable
   on purpose (NULL = the pinned key's type is not known, for every pre-V027 SSH row; the connector records it on first success),
   so an `ADD CONSTRAINT` requiring it would fail on an upgraded database and forbid `V027`'s fallback. The envelope stays `NOT NULL` for both, so `V007`'s `placement_envelope` trigger holds for agent placements.
2. One live agent target per agent (partial unique index); the agent-identity <-> target reference is **deferred**, so neither row
   can commit without the other.
3. `instance_state.acquisition_mode` gains `agent`.
4. `target_connection_event.kind` gains `enrolled`, with an `actor_kind` (`principal`/`agent`); `enrolled` <-> `agent` is a
   biconditional CHECK; `enrolled` carries no fingerprint.
5. `V012`'s pinned-key triggers are replaced to **skip a NULL pin** (an agent has none). *Found by the DDL draft, not by the
   plan: without it the first agent target fails `23502`.*
6. `OR-125` (2) is `sm_data` `V027` (`host_key_algorithm text NULL`), folded into item 1 above. `OR-125` (1) is `V026`
   (`placement.created_by` / `retired_by uuid`, **no kind column**): `V026`'s own comment says an agent's id goes into the same
   columns, so `VDA` needs no placement DDL and an agent actor is told apart by joining `agent_identity`.

**Enforced by:** `AgentSchemaIT` (the shape tests, the envelope trigger on an agent placement, the `enrolled`-event tests, the
live-target index, the deferred reference).

## `BE-D69` — The launcher's three cross-plane adapters: a second composer beside `OR-10`'s

**Status: PROPOSED.** Extends the composition rule `OR-10` / `BE-D3` state for `console-gateway`. *Flagged, not resolved:
whether this widening of "the only composer" needs ratifying with the `BE-D*` set; it is the branch's built shape and `I7`
holds.*

The gateway (`dp-api` only) needs three things only the CP has: lease issuance, enrolment certificate issuance and the agent's
registration state. The **launcher** implements `LeaseIssuer`, `EnrollmentCertificateIssuer` and `AgentRegistrationSource` over the
CP's own service interfaces (`ControlPlaneLeaseIssuer`, `ControlPlaneEnrollmentCertificateIssuer`, `ControlPlaneRegistrationSource`),
passing `AgentRequestOrigin` into the CP audit context. These are the **only classes in the launcher** that may adapt a `cp-api` type to a `dp-api` type; the `OR-27` completer is
`console-gateway`'s (`AGW-D30`), the existing `OR-10` composer. The CP target row at enrolment is written by the
enrolment-certificate adapter (`BE-D71`).

**Rejected.** *The gateway importing `cp-api`* — `AGW-D2`. *Reading CP tables from the DP* — `I7`.

**Enforced by:** `CompositionBoundaryTest` (the launcher may adapt exactly these three; a planted fourth fails);
`agentGatewayUsesDpApiOnly`.

## `BE-D70` — An agent may be the `sm_control` actor of the agent operations (amends `OR-25` by name)

**Status: PROPOSED.** **Records** `OR-128` (5), the owner's ruling that amends `OR-25` by name: a runtime write must carry a *principal*
actor except `LOGIN_REFUSED`, `LOCKOUT_CLEARED`, the operator-command `ENTITLEMENT_SET` **and the operator-cli `HIERARCHY_NODE_RENAMED` on `organization`** (`OR-131` Q1, `sm_control` V020); the ruling adds the agent. This text
specifies the mechanism and re-decides nothing.

1. `AGENT_ENROLLED` (token consumption, certificate issue, the CP target row) and `AGENT_INSTANCE_REGISTERED` (the `OR-27`
   governance node) are written **by the agent**: they require an agent actor (`sm.audit.actor_agent`) and **no principal**.
   Any other operation carrying an agent actor is refused `OR025`, so a stray setting cannot launder a principal's act.
   `change_audit.actor_agent_id` records it (one kind of actor per row, by CHECK).
   **The exemption is scoped as every other no-actor exemption is (`m3`, `Q-E`):** `AGENT_ENROLLED` may write only
   `agent_registration` and `target`; `AGENT_INSTANCE_REGISTERED` only the declare path's governance rows (`solution`, `instance`,
   `scope`); and each only for an agent that is an **active** registration **of the row's own organization** (and, for
   `AGENT_INSTANCE_REGISTERED`, one whose token has been consumed). A revoked agent can register nothing (review R-2).
2. The correlation id and client address stay **mandatory**. On the agent path they come from the gateway-minted
   `AgentRequestOrigin` (a per-RPC correlation id and the gRPC peer's address) — **never from an agent-supplied field**.
3. `created_by` of the agent target, its registration and its instances is the **principal who issued the token**.
4. *Found by the DDL draft:* token **issue** and **revoke** are principal acts that also write `agent_registration` and need names:
   `AGENT_ENROLMENT_ISSUED` and `AGENT_REVOKED`. `AGENT_LEASE_ISSUED` is added **only if** T2.2 Step 0 finds a lease-time `sm_control`
   write (the branch's registry writes at token consumption and certificate issue only).
5. `enrollment_token_digest` is an audit **secret column** (never copied into `old_values`/`new_values`); the issued certificate
   chain and trust bundle are not copied either.
6. The DP's mirror: `target_connection_event.actor_kind = 'agent'` for `enrolled`; and for a placement `OR-27` created, the agent's
   id in `V026`'s `placement.created_by` (`OR-125` (1)), which has no kind column, so the actor is distinguished by joining `agent_identity`.
   The audit function is rebuilt from **V020's** body (V020's `HIERARCHY_NODE_RENAMED` gate and marker comment kept verbatim) with the
   agent edits applied.

**Rejected.** *Attributing every agent-caused write to the issuing principal* (`OR-128` (5) option (b)) — `OR-27` says "audited
with the agent as actor". *A NULL actor with a NULL-context exemption* — it is exactly what `OR-25` closed.

**Enforced by:** `AgentRegistrationSchemaIT` (each agent operation with an agent actor, correlation id and address accepted; neither
actor -> `OR025`; agent actor without client address -> `OR025`; any other operation with an agent actor -> `OR025`; both actors ->
`OR025`; an agent operation on any other table, for another organization's row, before consumption, or after revocation ->
`OR025`); mutation proof: let a non-agent operation accept an agent actor (red).

**Clarification (2026-10-11, plan 015 T4.6, `OR-140`; appended, no meaning changed, `I3`).** `OR-140` (owner, answering plan 015 section 10 Q-C1) adds a
**second non-principal kind of actor**, the *system actor*, and the built shape is this. (1) *Mechanism.* `sm_control` `V022` adds
`sm_control.system_actor` (one seeded row, `agent-jobs`, `SystemActors.AGENT_JOBS`; the runtime role has no grant on it), `change_audit.actor_system_id`
and `change_audit_actor_one_kind` widened to at most one of three kinds; the audit function is rebuilt whole from `V021`'s body with four marked edits
(`SYS-EDIT 1..4`). (2) *The `OR-25` amendment, exactly.* A write audited to the system actor (`sm.audit.actor_system`) is allowed **only** for
`AGENT_REVOKED` (an `agent_registration` going `active` -> `revoked`) and `TARGET_RETIRED` (an `agent`-transport `target` going live -> retired), from the
client address `system-job`, with no principal and no agent actor, and for a registered system actor. The check runs before the agent-operation block, so
a system actor on `AGENT_ENROLLED` is refused by it. Any other operation, table, kind of change or client address with a system actor is refused `OR025`.
**Every other operation still fails closed without a principal**; `AGENT_REVOKED` and `TARGET_RETIRED` with no actor at all are refused as before. No new
`audit_operation`: the sweep's rows read as an operator's revoke and retire do, with a different kind of actor. (3) *Who uses it.* Only the scheduled
enrolment sweep (`AgentEnrolmentSweepJob`, gateway) through `AgentEnrolmentSweepService`; its candidate list no longer carries the issuing principal, so the
issuer cannot be attributed by accident. (4) *The cross-tenant read.* `OR-140` amends `OR-44`/`OR-49` for the two jobs only; `TenantEnumerationBoundaryTest`
names the two reads and holds each to one caller (plan 015 T4.6). (5) *Enforced by* `SystemActorSchemaIT` and the readiness stage for `V022`.

## `BE-D71` — The agent target is created in both planes at enrolment; every target operation on it is contracted

**Status: PROPOSED.** Applies `BE-D32`'s order and recovery to a target. Built by plan 015 T3.5.

**The two-plane write.** On `Enroll(token, CSR)`: **(1) CP, one transaction**, under `AGENT_ENROLLED` (actor the agent, origin
from the RPC): consume the token (or replay it), sign the certificate, register the **CP target row** (`name` = the token's target
name, `kind docker`, transport `agent`, ownership scope = the token's environment) through `TargetRegistration`'s write path,
idempotent on organization and name. `target.create` is **not** repeated here: it was checked at token issue against the issuing
principal. **(2) DP, one transaction:** `target_connection` (transport `agent`, the envelope from the **token**, never from the
agent; `created_by` the issuing principal), `agent_identity` (carrying, **copied from the enrolment record**, the environment, the
catalog solution id, the solution name and the declared package version — the DP cannot read the CP, `I7`, and `OR-27` needs all
of them), and a `target_connection_event` `enrolled`. `EnrollmentResult` (`dp-api`, T2.1a) therefore carries all of them with the
envelope and the issuing principal. **(3)** return the
certificate. **Recovery:** a crash between (1) and (2) leaves a CP target the DP lacks, listed degraded as an interrupted
`registerTarget` is; the agent's retry of the same token+CSR replays (1) to the same certificate and ids and completes (2). If the
agent never retries, the lazy tenant-scoped sweep on `listTargets` retires a degraded **agent** target once the token's `expires_at`
plus the `BE-D32` window has passed. **Never write the DP row first** (a mutation proof): that leaves an orphan the CP does not know.

**Every existing target operation on an agent target:**

| Operation | On an agent target |
|---|---|
| `listTargets`, `getTargetConnection` | Listed; `transport: agent`; the connection is the `AgentTargetConnection` variant |
| `registerTarget` | Unchanged; cannot create an agent target |
| `scanHostKeys` | Unchanged: not target-scoped (it scans an address) |
| `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` | `409 TARGET_TRANSPORT_UNSUPPORTED`; nothing read or written; no SSH constructed |
| `retireTarget` | `409 AGENT_STILL_ENROLLED` until its agent is revoked; after `revokeAgent`, as today (DP connection first, CP row second) |
| `addPlacement`, or a declare naming an agent target | `409 TARGET_IS_AGENT_MANAGED`: placements come only from `OR-27` in v1 |
| `retirePlacement`, `undeclareInstance` on an agent placement | As today, plus the discovery tombstone in the same DP transaction |

**Rejected.** *A target created by the operator in advance* — the envelope would then come from a second place than the token.
*Flattening `TargetConnection` into one object with optional SSH fields* — the contract would stop saying which fields exist for which
transport.

**Enforced by:** `AgentEnrollmentTargetIT` (enrol -> one CP target with the audit row, one DP row with the token's envelope, one
`enrolled` event; a crash between (1) and (2) -> degraded listing, then the replayed `Enroll` completes it with the same ids; a
never-retried one is swept; a placement outside the envelope refused); endpoint ITs per row of the table; mutation proofs (DP row
first; envelope from the agent; `editTargetConnection` allowed).

**Clarification (2026-10-11, plan 015 T4.6, `OR-140`; appended, `I3`; the decision's text above is not edited).** This records `OR-140`'s ruling on the *recovery* clause above ("the lazy tenant-scoped sweep on
`listTargets`"), by owner ruling and not by ratification: the sweep is a **scheduled job** (`AgentEnrolmentSweepJob`, `sm.gateway.agent-sweep.*`), and
`listTargets` runs no sweep and writes nothing to the control plane (plan section 10 Q-C2). The window (the token's `expires_at` plus `sm.dp.declare.abandon-after`),
the order (revoke the registration first, look at the data plane again, retire only if the target is still absent) and the paging are unchanged. Its two
control-plane writes are audited to the system actor (`BE-D70`, clarified), with one correlation id per abandoned target and the client address `system-job`.
*Exactly once across SM instances* matches `ScheduleFirer` (no lock): each state move is conditional (`revokeIfActive`, `markRetired`), so a second instance
that read the same candidate changes nothing and writes no audit row; `OR-44` also mandates one replica. A disabled job (`enabled=false`) leaves an interrupted
enrolment listed degraded until an operator revokes and retires it.

---

## `BE-D66` — Connections get the cp/dp split Targets already has, and the `connection.*` verbs

**Status: PROPOSED.** Written for integration plan [`013-connections-screen-and-targets-removal.md`](../../plans/integration/013-connections-screen-and-targets-removal.md), allocated under owner ruling **`OR-130`** (this session, 2026-10-09).

D-4 (`/cp/connections*`, `/cp/connection-types` have no contract operation) is closed by giving Connections a
real backend, split exactly the way Target already is — not a new pattern:

- **`cp-core`** owns the `connection` row (`sm_control`, migration `V021`): id, name, type, scope, non-secret
  attributes (JSONB), `secretStatus`, status, revision, `usedByCount`, audit columns. Authorization, ownership
  scope (`I5`) and optimistic concurrency live here, same shape as `target`.
- **`dp-core`** holds secret material and runs connectivity tests. Secrets (`password`, `privateKey`,
  `passphrase`) are sealed via the existing `EnvelopeSecretStore` (`sm_data.secret`) under a new
  `SecretPurpose.CONNECTION_CREDENTIAL`, keyed by `connectionId` — no new secret-storage mechanism. Testing a
  Solace Broker connection opens and tears down a real JCSMP session; testing a Remote Server connection
  completes an SSH auth handshake and disconnects. **Deliberately excluded:** host-key scanning/pinning — that
  stays exclusive to Target registration (`BE-D54`/`BE-D55`); a Connection is a config value object, never a
  deployment-trust anchor, and the two are not merged (see `OR-130`'s own framing).
- **`console-gateway`** composes `createConnection`/`updateConnection`'s single `testBeforeSave` flag into one
  call spanning both planes, mirroring `TargetController`.

This is additive only: no change to `Target`, `Placement`, or the `dp-remote` apply protocol. The `/targets`
preview route is removed from the frontend as a separate, unrelated change (nav/UI only); the Target domain and
its API are untouched.

Cites `OR-130` for the verb grant; cites `BE-D54` for the composed-call pattern it mirrors; cites `I5`/`I6`/`I7`
for the scope/secret/plane-boundary rules it follows rather than relaxes.

---

## Changelog

| Date | Change |
|---|---|
| 2026-10-12 | **Reconciliation into `UAT` (`OR-147`): `BE-D66` now names two decisions — flagged, not resolved.** Plan 015 registered `BE-D66`–`BE-D71` (agent adapter, parity, transport, launcher adapters, agent actor, agent target) while integration plan 013 registered `BE-D66` (Connections cp/dp split and the `connection.*` verbs) on another branch. Both are kept as written (`I3`: IDs are immutable), in both the index and the bodies; a citation of `BE-D66` must say which. The owner may give one a new ID by a superseding decision. Same posture as `OR-147` takes for the two `OR-130` rows. |
| 2026-10-09 | **`BE-D66` added** (Connections cp/dp split + `connection.*` verbs, `OR-130`) for integration plan 013, consolidating D-4's long-open Connections gap. No existing decision edited (`I3`). |
| 2026-09-08 | **Incoming Agent Gateway amendment annotated; no backend decision text changed.** [`agent-gateway/decisions.md`](agent-gateway/decisions.md) proposes `AGW-D1`, a fifth plane module, as an amendment to `BE-D3`'s module count. The conflict is flagged and ordered after `BE-D3`; its body and meaning remain untouched per `I3`. `AGW-D6` also proposes an `IN_FLIGHT` completion and awaiting-completion resume extension to `BE-D12`'s `RuntimeAdapter`; the backend family owns the Java shape. |
| 2026-08-27 | Initial `BE-D1`…`BE-D9`, drafted with [`design.md`](design.md) on the `backend-design` branch. Broker OAuth/JWKS capability verified by WebFetch (`BE-D5`). Conflicts flagged: `DM-D17` supersession, `FE-D19`/`FE-D7` amendments, `SM-D1` seam extension, `D13` non-reopening, `D10` catalog dependency, `source.kind` contract change. |
| 2026-08-27 | **R5.1 verification pass (Codex): all ten R5 fixes confirmed; three residual findings, all accepted and applied in place.** (1) A **common event envelope** (`eventId`, `occurredAtUtc`, `producer`, `tenant`, entity refs, monotonic per-entity `stateVersion`) added to design §3 — the delivery paragraph had claimed stale-discard by `generation`/`ts` while four catalog events carried neither, and `B/D15` 🔒 forbids wall-clock ordering; ordering/stale-discard now use `stateVersion` only, and event-specific payloads no longer duplicate envelope fields. (2) Phasing restated as **gate-first** (design §13): G1 broker spike, G2 `D10`/identity closure (open item 24) gating every auth-bearing task, G3 envelope-before-AsyncAPI (closed in-spec). (3) `BE-D2`/`REQ-012` rationale narrowed from "the MIs'" to **push-mode MIs'** telemetry (Cache is HTTP-polled). |
| 2026-08-27 | **R5 independent adversarial review (Codex): 10 findings, 9½ accepted, applied in place per the draft amendment policy.** `BE-D3`: the "SaaS split is a packaging change" claim withdrawn — the browser↔mesh path needs a DP-side relay/tunnel for SaaS (finding 1, question 7). `BE-D5`: subscribe-authorization declared **unsettled** with three candidates and a gating Phase-0 broker spike (finding 2), signing-key lifecycle added (finding 7), mesh verbs routed to the `D10` amendment / open item 24 (finding 9). Event identity made placement-scoped where the state is per-placement, per `SM-D23` (finding 3). `BE-D9`: `broker-push` corrected to an extension of the canonical `solace_event` kind incl. `SM-D27` durability evidence (finding 4), and the collector-locus conflict with the architecture's per-locus assignment flagged as question 8 (finding 5). Event catalog pinned to exact vocabulary enums and the full five-state ladder (finding 6 — **its quoted "GREEN/RED/YELLOW/GRAY" line does not exist in the spec**; the valid residue was fixed). `REQ-012` de-solutioned (finding 8). Token renewal restated as a planned reconnect with re-backfill, per the SDK's actual `updateAccessToken` behaviour (finding 10). |
| 2026-08-27 | **G1 spike result recorded** — [spike-authz-result.md](spike-authz-result.md); `BE-D5` cites it at ratification; chosen mechanism: **(a) substitution-variable ACL** (`sm/v1/<tenant>/stream/$client-username/>` bound to the VPN's `default` client username — **zero broker writes per grant, measured SINGLE-TENANT**). Candidates (a) and (b) proved **mutually exclusive**, not layered: a pre-created client username beats the groups claim. ⚠️ **Two gaps left open and BOTH block `BE-D5` ratification, because both ask what the single fallthrough profile's exception set may contain.** **(1) Multi-tenancy:** only the *username* level substitutes — the tenant level is a topic **literal**, and a Message VPN has exactly **one** `default` client username, so one profile carries one tenant — while `BE-D2` provisions **one dedicated VPN per install** and design §4/§11 make **tenant a topic level inside it** that the ACL must enforce (fixture in design §14), i.e. several tenants share one VPN. Three routes priced (wildcard the tenant level → the broker stops enforcing the tenant edge; fold the tenant into `sub` → a `BE-D4` taxonomy change; per-tenant client usernames → candidate (c)'s cost model, and the measured precedence order means such an object *beats* the `default` fallthrough, so zero-writes is gone). **None measured, none recommended — architect group's call.** **(2) Notify tier:** design §4's notify topics carry **no per-subject level to substitute at**, so a substitution-scoped notify exception is **not expressible** — a blanket `sm/v1/<tenant>/notify/>` would hand every subject every tenant-wide notify event and the isolation claim leaks straight back out; the measured deny on a stream-only profile merely confirms it. Three options costed, recommendation is tenant-wide notify visibility for v1 (`I6` already bars values from notify payloads). Also measured and carried: rotation is **not** revocation (an evicted key kept connecting for **151 s** (uncommitted transcript; the committed test records 60 538 ms) with the JWKS fetch counter unmoved, and died 70 ms after one current-kid login forced a re-read; no SEMP force-refresh action exists, `endpointJwksRefreshInterval` defaults to 86400 s with a 60 s floor); issuer-restart key persistence holds; renewal is a **session-replacing reconnect** the SDK never reports, losing in-flight messages, so `BE-D6` chart tails must re-backfill; `disconnectOnTokenExpirationEnabled` is `true` by default, making the renewal deadline hard and proactive re-ticketing mandatory; four distinct broker refusal shapes, one with a misleading message; publish denials are invisible to the client; the SDK retries a refused login forever unless bounded. **Design §14 fixture attribution corrected:** the `I5` cross-tenant fixtures were measured under candidate (c)'s per-tenant client usernames, not under the chosen mechanism. Broker floor: **Enterprise edition** (OAuth is unavailable on Standard), SEMP 2.32 for candidate (b), verified at 2.48. Reverse proxy: SMF keepalives measured at **3.0 s**, so the `proxy_read_timeout` floor is ~10 s and nginx's 60 s default was never a hazard — Task 1's premise corrected. Evidence: `backend/spikes/mesh-authz/`, 30 tests green from a cold start. |
| 2026-08-27 | **`BE-D5` status metadata refreshed after G1 (R8 fix wave)** — the family-table row and a dated "G1 result" paragraph appended to the `BE-D5` body now mirror the central ratification matrix (candidate (a) chosen; ratification blocked by open items 32/33). Per the amendment policy this is a review correction: status metadata only, the three-candidates paragraph and the decision's meaning untouched (`I3`). |
| 2026-08-28 | **`BE-D10` and `BE-D11` added (contracts consumption)** with the new cross-cutting design [`../common/contracts/backend-consumption.md`](../common/contracts/backend-consumption.md), on branch `contracts-backend-consumption`. Mirror-and-lock consumption of `contracts/events/` (schema-lock tests, shared goldens, wire honesty; codegen and producer-side runtime validation rejected with scenarios); single `ContractArtifacts` resolver + checked build-time copies. Motivated by R7 F1 / R8 F1–F2 — all three were mirror-drift defects no existing check pointed at. |
| 2026-08-28 | **R11 plan-review correction: the blank-tenant drift closed schema-side.** `EventEnvelope` refuses a blank tenant (`isBlank()`) while `envelope.schema.json` said only `minLength: 1`, so `"tenant": "   "` validated in Python and was refused by Java — cross-language drift on an ACL-bearing topic level (`I5`). The schema hardens to match Java (`"pattern": "\\S"`, a reviewed single-writer contract change carried by plan 002 Task 4) with a shared `blank-tenant.json` negative wired into both reason maps, a construction-time-rejection shape in the Java negative suite, and a lock pin so removing the pattern reopens nothing silently. The residual exotic-whitespace difference between the two engines' character classes is accepted and documented in the design §3. Also fixed: the design's stale "handledEvents() is the only code change" sentence now states the table-driven refactor plan 002 actually requires. |
| 2026-08-28 | **R10 plan-review corrections applied in place (`BE-D10` mechanics only, meaning untouched).** The lock derives the envelope/entity field lists from `getRecordComponents()` instead of restating them as test literals (a literal list is a third mirror — the exact defect class the lock exists to kill); the four payload-enum sets may stay `private` because the package-private branch table carries the enforced members (supersedes R9's blanket package-private instruction); §5.2's "every publisher implementation extends the kit" is stated honestly as a review gate until a second implementation exists, with an executable inventory check assigned to that PR. Plan 002's mutation demos now assert Maven failed AND attribute the failure to the lock — a piped `mvn \| tail` exits with tail's status and would let a dead lock pass its own demonstration. |
| 2026-08-28 | **R9 contract-consumption review corrections applied in place.** `BE-D10`'s lock now covers primitive types, numeric/string bounds, both `not` shapes, enum/bound shared negatives, and the future second-publisher bypass via a shared publisher seam + abstract contract-test kit; the unverified generator generalization was removed. `BE-D11` now states the post-split runtime-copy source: the selected versioned contracts artifact, under byte-equality check. |
| 2026-08-28 | **Plan 003 (publish-path lifecycle) executed — no decision text changed, and no new decision needed: all four deliverables implement design sentences already written.** (1) `BE-D1` §3's after-commit ordering ("a module publishes an event only *after* its PostgreSQL transaction commits") is now enforced at the seam by `AfterCommitEventPublisher` over the framework-free `TransactionBoundary` (both in `mesh-events`, so the module stays Spring-free and plane-neutral), with `SpringTransactionBoundary` in `dp-core` as the one class that knows the commit is Spring-managed; a rollback discards the notice, and the loud guards stay in the caller's own frame. This is the pre-outbox form of the upgrade path the seam was reserved for. (2) `JcsmpEventPublisher`'s **open bean-registration question is resolved**: the Spring path is `connectingInBackground(MeshProperties)` over a `ReconnectingTransport` that **starts disconnected** and connects with capped backoff (plus JCSMP channel reconnection for mid-session outages), so a broker that is down at startup costs counted drops and never a failed boot — design §9 row 1, proven live in `MeshLifecycleIT` and against a dead broker in `MeshBridgeConfigurationTest`. The eager `MeshProperties` constructor remains the fail-fast path for tools and ITs. (3) `backend-consumption.md` §5.2's promised **executable publisher inventory** landed (`PlaneBoundaryTest.PUBLISHER_IMPLEMENTATIONS`), triggered as promised by the second `EventPublisher` implementation. (4) Design §2's "no other module holds a broker session" became the executable `onlyMeshbridgeTouchesJcsmp` ArchUnit rule. Both new gates carry planted-violation proofs. Evidence: [`../../plans/backend/003-publish-path-lifecycle.md`](../../plans/backend/003-publish-path-lifecycle.md) Results. |
| 2026-08-31 | **`BE-D12`–`BE-D15` added (EDA walking-skeleton blockers)** with the new design [`eda-walking-skeleton.md`](eda-walking-skeleton.md), on branch `contracts-backend-consumption`, direction set by the project owner: the walking skeleton is event-driven from day one (REST = authoritative snapshot/backfill per `BE-D1`, never a polling-first stand-in to rewrite later). `BE-D12`/`BE-D13` design the `dp-remote` apply protocol + persistence open item 1 demanded (six runtime-neutral operations behind a `RuntimeAdapter` seam, Docker first; `apply_request`/`apply_attempt` split); `BE-D14` fixes the live-console session contract (claim set, memory-only tickets, proactive renewal, `LiveStateClient` seam) and proposes v1 postures for open items 32/33; `BE-D15` designs the authorization machinery implementing the `D10` amendment proposal's own recommendations. Conflicts flagged, not resolved: `D10`/`D13`/`B/D16` locked and untouched; items 24/32/33 remain the architect group's. |
| 2026-09-04 | **Independent adversarial review of `BE-D12`–`BE-D15` (32 findings: 12 MAJOR / 20 MINOR / 7 INFO) — corrections applied in place per the draft amendment policy.** The big four: (1) `BE-D13`'s state model redrafted — `STAGED` is `manual`'s honest terminal state (the previous enum had none, and `APPLIED` would have collapsed the five config states); `CONFIG_DIVERGED` and `stop` homed; `APPLY_INDETERMINATE` (one name now, matching the corpus) blocks its scope per `architecture.md` §16.1 and resolves with observed-state evidence per `FE-D18` clause B; the single-in-flight index was **not enforceable as written** (PG-14 NULL-distinct semantics) — replaced by nil-UUID sentinel + partial unique index + serialized admission transaction. (2) `apply_attempt.step` no longer forks the contract's rollout enum — design §3.5 maps execution steps onto `[staged, restart-requested, restart-observed]` and feeds Known gap 2's `BE-D4` amendment; instance-wide requests emit placement-scoped events. (3) `BE-D14`'s `sub == grant_id` (one broker session per live chart) corrected to a **session-scoped branch** with grants accumulating as claims — which is `BE-D5`'s model, so the supersession the draft implied is gone. (4) `BE-D12`'s lifecycle/status operations gained the replica dimension without which `SM-D15`'s ladder was unexpressible; `B/D13` 🔒 flagged with an accelerant disposition for K8s; the `B/D16` lease clause named as deliberately unmirrored. Also: catalog fixed to 33 (`D10`'s two live verbs were excluded by "exactly 28+3"; introspection pair routed to the amendment paper as a dated addendum); audit log split along the plane boundary (`I7` made "synchronous cross-plane audit" impossible as written); v1 whole-install scope evaluation flagged as a deviation from `D10` 🔒/`REQ-006` and routed with the amendment; three G1 citations corrected (`/`-in-`sub` is vendor-documented not spike-measured; refusal taxonomy; renewal clock is the gateway's, background-tab throttling named); option-A and `DM-D18` amendment citations restored to the paper's actual wording; `mesh.stream.revoke`'s TTL-bounded effect stated for the verb row; `BE-D8`/`BE-D15` substrate ownership resolved; `iss` added to the claim floor; document indexed in `docs/design/README.md`. Review record: `.superpowers/sdd/design-eda-walking-skeleton-review.md` (session artifact). |
| 2026-09-04 | **Re-review of the fix wave: all 10 fix-list items verified closed; 11 new narrow defects found in the rewrite, all corrected in place.** The three MAJORs: the session-scoped stream branch renames `BE-D4`'s `{grantId}` taxonomy level — now flagged as a conflicts row and routed to the `BE-D4` amendment rather than left implicit (nothing breaks today: check 10 does not lock address strings); §3.5's `staged` rollout event is emitted **per replica** (the contract requires `payload.replicaId` on every rollout event — a placement-level `staged` could not be contract-valid); the catalog count corrected to **35 members** (33 grantable + the introspection pair — "exactly 33" would have reproduced the original M2-7 for the pair §5.2 requires enforced). MINORs: `purpose` stays a single-valued claim with stream entitlement discriminated by `grant_id` presence (one session ticket serves both tiers); the audit mutability claim restated honestly (`sm_control` append-only; `apply_request` engine-mutable until terminal, no API mutation path to either); `operation` gains `restart` so `instance.restart` is audited distinguishably; `operation = stop` exempted from the `APPLY_INDETERMINATE` blocking check (the constraint blocks applies, not the emergency lever) and admission control stated locus-independent; `SUPERSEDED` widened to carry the agent's in-flight case (`AG-D5`) so the 1:1 report mapping holds; the `instance.config-state` `persisted` rung mapped, with `rendered`/`effective-verified` explicitly placed; `BE-D8`'s row and body annotated with the `BE-D15` ownership transfer; the amendment paper's first addendum retitled "Addendum 1" and its catalog count aligned. Re-review record: `.superpowers/sdd/design-eda-walking-skeleton-rereview.md` (session artifact). |
| 2026-09-04 | **External (Codex) review of the walking-skeleton design: 3 MAJOR + 1 MINOR, all corrected in place.** (1) The stop exemption was prose the index contradicted — an operation-blind predicate would refuse a `REQUESTED` stop against the very `RUNNING`/`APPLY_INDETERMINATE` apply it exists to escape; the fence is now **operation-split** (apply fence `operation <> 'stop'`; a separate one-in-flight stop fence), with four executable-DDL prove-it-fires obligations named for the implementing plan. (2) The apply tables violated architecture §3.1's tenant-boundary seam (*"`tenant_id` on every table in both schemas, even single-tenant"*): both tables now carry canonical `tenant_id`, `apply_attempt`'s FK is tenant-consistent `(tenant_id, apply_id)`, the tenant-scoped indexes exist, the topic slug is named as a separate derivation, and the CP/DP no-join validation gap is routed as design §8 row 9 rather than absorbed. (3) `config_hash NOT NULL` did not fit `STOP`/`PREFLIGHT`/`ROLLBACK`: now nullable with a CHECK requiring it exactly on the byte-touching steps (`MATERIALIZE`/`VERIFY`), `STOP` honestly hashless, and §2.2's idempotency rule scoped accordingly. (4) One trailing-whitespace line removed. |
| 2026-09-07 | **Codex confirmation review R3 (2 MAJOR + 1 MINOR), corrected in place.** (1) The admission fences were tenant-blind while the tables claimed tenant isolation: both partial unique indexes now key `(tenant_id, instance_id, placement_id)` and the advisory lock hashes `(tenant_id, instance_id)` — reused instance ids (import/restore, fixtures, a future non-global identity model) must never let one tenant's in-flight apply fence another's; executable-DDL obligation (e) added (cross-tenant non-collision, same-tenant collision). (2) A byte-restoring rollback could be journaled hashless, breaking the resume-provability the table exists for: the step splits into restorative `ROLLBACK` (hash-bearing by the same CHECK as `MATERIALIZE`/`VERIFY` — the target generation is known before acting) and hashless `ROLLBACK_ASSESS` (no-restore/indeterminate assessment), with obligation (f) and the §2.2 idempotency rule updated. (3) One stale singular ("the single-in-flight partial unique index") in `BE-D13`'s full-design line corrected. |
| 2026-09-07 | **Codex confirmation review R4 (2 MAJOR + 1 test gap), corrected in place.** (1) The hash/enum rules lived in SQL comments, not constraints — a malformed `state` could bypass the fence predicates and a real restore could be journaled `ROLLBACK_ASSESS` hashless: the DDL sketch now carries **real CHECK constraints** (`op_known`, `state_known`, `step_known`, `apply_has_generation`, `lifecycle_has_strategy`, `state_fits_operation`, `resolution_is_evidence`, `hash_on_config_steps`), obligation (h) proves each fires, and the three invariants no DDL can hold (step-label truthfulness, intent immutability, `SUPERSEDED` order) are stated as engine-enforced with their test homes. (2) Idempotency had no durable substrate — one row per attempt, nothing binding key↔hash: added **`apply_step_intent`** (insert-only, one row per logical step, two UNIQUEs + serialized insert-or-read-compare; `apply_attempt` becomes its retry log with `observed_hash` as evidence distinct from intent), obligation (g) proves the conflicts fire; §2.2 rule 2 and §3.1 rewritten around the three-table model (decided / intended / attempted). (3) Obligation (d) extended with the cross-scope half of the stop exemption: a placement-scoped stop admitted during an instance-wide `RUNNING`/`APPLY_INDETERMINATE` apply. |
| 2026-09-07 | **Codex confirmation review R5 (4 MAJOR + 1 MINOR), corrected in place — all structural FK/CHECK semantics.** (1) `apply_attempt`'s two independent FKs let an attempt reference another request's intent within a tenant, mis-attributing resume/audit/pruning evidence: `apply_step_intent` gains `UNIQUE (tenant_id, apply_id, intent_id)` and the attempt's intent edge is now the **triple FK** — obligation (i) proves the cross-request insert is refused. (2) `resolution_is_evidence` accepted a NULL resolver and `'{}'`/JSON-null evidence, letting an unsupported `resolution_outcome='applied'` drive §2.2 monotonicity: the CHECK now requires `resolved_by`, a non-empty JSON **object** (`jsonb_typeof`), and both FE-D18 evidence keys, and the new `resolution_only_when_resolved` forces all four fields NULL on every other state — obligation (j) enumerates the refusals. (3) Intent "immutability" was a review convention: now a **database-privilege guarantee** — the runtime role holds SELECT/INSERT and no UPDATE/DELETE on `apply_step_intent`, migrations run as the owner role, obligation (k) proves the runtime-role UPDATE is refused (privileges over triggers: simpler, visible in one GRANT; the owner-role residual is stated). (4) `hash_on_config_steps` was one-way while the prose claimed "honestly absent": now **biconditional** — hash exactly on `MATERIALIZE`/`VERIFY`/`ROLLBACK`, refused elsewhere — obligation (f) tests both directions. (5) The register's stale "Both tables" corrected to all three. Obligations now (a)–(k). |
| 2026-09-07 | **Codex confirmation review R6 (2 MAJOR + 1 MINOR) — the same defect class a third time (guarantees asserted one artifact short of enforceable), corrected in place and captured as cross-cutting lesson L19.** (1) "Privilege-backed immutability" had no DCL: the sketch now carries the executable role topology — `sm_dp_owner` NOLOGIN owning all three tables, `sm_dp_migrator` (owner member, the Flyway datasource), `sm_dp_runtime` NOINHERIT non-member (the application datasource), `PUBLIC` revoked, per-table grants (`apply_step_intent`: SELECT/INSERT only) — with obligations (k) reframed (runtime UPDATE refused / migrator succeeds) and (l) added (`SET ROLE` refused; `pg_has_role` asserted false). (2) `resolution_is_evidence` validated key presence, so `{"stagedGeneration": null, "replicas": []}` passed: it now types the values (`jsonb_typeof` number / non-empty array) and is explicitly labelled evidence-SHAPED, with replica-element typing named as the engine's typed resolver plus test — obligation (j) extended with the null/empty and wrong-type refusals. (3) Retry boundedness was a comment: `attempt_no_positive CHECK (attempt_no >= 1)` added, with the configured upper cap engine-enforced under new obligation (m). Obligations now (a)–(m). |
| 2026-09-07 | **Two commissioned independent adversarial reviews (fresh corpus: 12 MAJOR/16 MINOR/4 INFO, FIX-THEN-ACCEPT; PG-14 EXECUTION review: sketch ran verbatim on 14.24, 10/13 obligations PASS, 2 BLOCKER + 6 MAJOR + 16 MINOR measured) — all 44 findings corrected in place, in one wave.** Measured blockers: `resolution_is_evidence` admitted `'{}'` via three-valued logic (`true = NULL` passes a CHECK) — every jsonb clause now `coalesce`d; the prescribed `ON CONFLICT DO NOTHING` step protocol aborted on the non-arbiter key index before its own compare — replaced by SELECT-then-INSERT under the admission advisory lock at `READ COMMITTED` with 23505s classified by constraint name. DCL completed to what actually runs: install-credential bootstrap, `CREATE SCHEMA sm_data` + `USAGE` grant, `public` CREATE revoked (PG-14 default let the runtime create tables — measured), `SET ROLE sm_dp_owner` migrations (membership ≠ ownership — measured), default read privileges, column-scoped `apply_request` grant (measured hazard: runtime re-tenanting/re-attributing audit rows) and the `apply_request_terminal_guard` trigger; advisory lock keyed tenant-aware. New/renamed artifacts: `staged_fence` (measured: 4 concurrent STAGED rows), `apply_generation_watermark` + admission monotonicity rule with its own obligation (the L19 gap on the rule everything else feeds), §2.2 rule 0 write-before-act (`architecture.md` §16.1 verbatim; a no-attempt intent now provably re-runs instead of falsely blocking as `APPLY_INDETERMINATE`), `step` restored to `apply_attempt` via `(intent_id, step)` FK with per-step CHECKs, `'@placement'` replica sentinel, strategy/outcome/timestamp/terminal/outage CHECKs, `restart_not_manual`. Wire honesty: terminal apply outcomes have NO v1 event — stated, apply screen polls, routed with the `{grantId}`→`sub` rename, per-binding ladder and strategy-assertion verbs as §8 rows 10–13; instance-scoped `config-state` emitted for instance-wide requests only. `SUPERSEDED`/replay flavours reconciled (agent replay = direct terminal transition, fields NULL); grant revocation restated DP-effective under the session branch (amendment note aligned); `sm_control` role/audit DCL pattern named with mirrored refusal tests; §7 rewritten off the two-table residue; §9 impact notices added (FE-D18 clause-A retraction-at-ratification; polling-primary apply screen; install credential). Review records: `.superpowers/sdd/design-fresh-adversarial-review.md`, `design-ddl-execution-review.md` (session artifacts). Obligations (a)–(r). |
| 2026-09-07 | **Codex confirmation review R7 (1 CRITICAL + 3 MAJOR) on the post-review-wave DDL, corrected in place.** (1) CRITICAL: the terminal-guard trigger forbade the very `STAGED → SUPERSEDED` transition the admission algorithm requires (a second manual apply was impossible: trigger-refused, then `staged_fence`-refused) — the trigger now sanctions exactly that one terminal transition and obligation (p) executes the real UPDATE through it, both directions. (2) The generation watermark was scope-blind across the nil-sentinel boundary (instance-wide `APPLIED` at 10 did not fence a later placement-scoped 9): replaced the watermark index with **maintained artifacts** — `apply_scope.instance_watermark` + `placement_watermark` rows updated in the landing transaction — and §2.2 rule 1 now takes cross-scope maxima in both directions, obligation (n) covering all four. (3) The DCL was mis-ordered (tables before their schema) and `ALTER DEFAULT PRIVILEGES FOR ROLE` was unexecutable by the installer (requires target-role membership, PG-14 docs): the block is now two ordered phases — Phase 0 installer bootstrap (roles → schema → schema grants), Phase 1 Flyway migrations opening with `SET ROLE sm_dp_owner`, where the default-privileges statement is legal — with new obligation (s): the exact script runs end to end on fresh PG 14 (the prior execution evidence proved a prior form; evidence does not transfer to text it never ran). (4) The advisory-lock algorithm was still "a hash of (tenant_id, instance_id)": replaced outright by the `apply_scope` **exact lock row** (`INSERT … ON CONFLICT DO NOTHING` on its single-arbiter PK, then `SELECT … FOR UPDATE`) — collision-free, namespace-free, tenant-keyed by shape — which also carries finding 2's watermarks; the step-intent race now rests on its UNIQUE arbiter with 23505-classified retry, no lock claimed. |
| 2026-09-07 | **Second commissioned PG-14 execution run — this time of the AMENDED sketch (obligation (s) honoured before claiming it): 91/91 substance legs green (all four monotonicity directions, the sanctioned trigger transition, the live two-session scope-row lock, 35 regression legs, 24 hostile probes), with 11 boundary findings, all corrected in place.** HIGH: `CREATE SCHEMA … AUTHORIZATION` requires executor membership (Phase 0 gains `GRANT sm_dp_owner TO CURRENT_USER`, measured green); the `public`-CREATE revoke is a **silent WARNING no-op** under a lesser installer (Phase 0 now requires the database-owner login and obligation (s) asserts `has_schema_privilege(...) = false` instead of trusting the statement). MED: the terminal guard made §3.3's compaction unimplementable — a second sanctioned write (attempt_summary-only, `to_jsonb(NEW) - 'attempt_summary'` row-compare) added with obligation (u); the runtime's table-wide watermark UPDATE could rewind any tenant's fence in one statement — `watermarks_only_rise` triggers added (obligation (t); residual mis-writes can only over-fence, fail-safe); `request_has_scope` FK added (the serialization point was convention-only); both phase rationales corrected to what PG 14 actually does (SET ROLE buys ownership-at-CREATE, not ALTER rights). LOW/INFO: supersession refreshes `terminal_at`; the nil sentinel is CHECK-barred from `placement_watermark`; rule 1's maxima are GREATEST/coalesce NULL-explicit; obligation (d)'s blocking point stated honestly (upsert or FOR UPDATE); LOGIN credentials named as runbook material. Review record: `.superpowers/sdd/design-ddl-execution-rereview.md` (session artifact). Obligations (a)–(u). |
| 2026-09-07 | **Codex confirmation review R8 (1 DESIGN DEFECT + 1 FALSE SETTLEMENT), corrected in place.** (1) The watermark tables carried a table-wide runtime `UPDATE` while `watermarks_only_rise` guards only the value: re-keying a row's `tenant_id`/`instance_id`/`placement_id` moves a fence to another scope without lowering any watermark, un-fencing the original — the grants are now **column-scoped** (`apply_scope`: `UPDATE (instance_watermark)`; `placement_watermark`: `UPDATE (generation)`), the trigger comment states the value/identity split, and obligation (t) gains the identity-column and `DELETE` refusals with the positive controls (upsert, `SELECT … FOR UPDATE`, raise). Checked: no engine path mutates watermark identity — scope rows are upserted by their own key and only raised; measured on PG 14 before writing (`FOR UPDATE` needs UPDATE on at least one column, which the scoped grant supplies). (2) The standing execution evidence was `adf8186`'s, while `b543aca` changed executable Phase 0/1 DDL, triggers, grants and FKs afterwards — a settlement L19 forbids: a fresh full run of obligations (a)–(u) against the DDL **extracted from the fix commit**, on PostgreSQL 14, is recorded as a checked-in evidence report — the two rows below. |
| 2026-09-07 | **First execution run against the R8 fix commit (`91182d0`): NOT green — 2 failures in text the fix did not touch, both reproduced and corrected in place.** F1 (BLOCKER): the shared `watermarks_only_rise()` branched on `TG_TABLE_NAME`, but PL/pgSQL resolves every field reference in an `IF` condition against the firing row's type — every rise on both tables and the placement decrease died with 42703 (only the apply_scope decrease fired correctly), so no watermark could ever be raised and the `INDETERMINATE_RESOLVED` resolve-and-raise transaction could not commit; the earlier run had read the 42703 as the refusal it was asserting (the harness rule's own trap). Replaced by one function per table (`instance_watermark_only_rises`, `placement_watermark_only_rises`; trigger names unchanged). F2 (HIGH): the standing account — "Phase 0 as the database owner" (the row two above) — was wrong on the pinned floor: on PG 14 schema `public` is owned by the bootstrap superuser (`=UC/postgres`) and only the grantor can revoke, so under a database owner the `REVOKE` was the same silent WARNING no-op as under a lesser installer (measured; `public.smuggled` created). Phase 0's executor is now the **bootstrap superuser**; obligation (s) asserts the outcome and carries the database-owner run as its negative control (PG 15's `pg_database_owner` change verified in the release notes; the floor stays 14). The column-scoped grant itself — this round's subject — was correct: all seven identity/`DELETE` refusals passed on the first run. Also: register body's stale "tenant-aware advisory lock" → the `apply_scope` lock row. |
| 2026-09-07 | **Second execution run (`aa5ac48`): green — 146 legs (99 asserted refusals, 47 positive controls) on PG 14.24, both phases verbatim, F1/F2 regression-proven; then an independent Opus 5 adversarial re-review (own extraction, own container) reproduced the identity-mutation refusals, all four monotonicity directions and both (s) controls, verified the two load-bearing PG-14 facts against the docs (grantor-only `REVOKE`; `FOR UPDATE` needs UPDATE on at least one column), and rated both Codex findings CLOSED with six MINOR clerical defects — corrected here:** the F1 claim "every update died" overstated (3 of 4 directions; the apply_scope decrease still fired) — design comment and `BE-D13` row corrected; obligation (s) gains the harness note that roles are cluster-scoped, so the negative control is the `REVOKE` in isolation or a second cluster; the dangling "see the following row" and the missing F2/F1 changelog account fixed by the row above; the evidence report's five (s) assertions had no archived transcript line (all independently re-measured true) — re-run and re-archived; its temp-table note reworded as proposed runbook hardening (`REVOKE TEMP ON DATABASE`), not a gap against a claim the design makes — the design claims nothing about temp objects. Because the F1 correction touches a comment inside the SQL block, the checked-in evidence is a **third full run against the commit carrying this row** — [`evidence/ddl-execution-pg14.md`](evidence/ddl-execution-pg14.md) names the commit and the extracted-script hashes. Review records (session artifacts): `.superpowers/sdd/design-ddl-execution-91182d0.md`, `design-r8-independent-rereview.md`. Obligations (a)–(u). |
| 2026-09-07 | **Plan 004 prototype (L15: the plan's code was executed before it was written) — three measured corrections to `BE-D13`'s text, none to its SQL.** (1) §2.2 rule 2 said a `23505` is classified *by constraint name*: measured false — a legitimate concurrent same-step create presents the same key, violates **both** intent UNIQUEs, and PostgreSQL names `apply_step_intent_idempotency_key_key` first; classification is now by the outcome of a re-read in a fresh transaction (row found → compare; none → key reused elsewhere), with both of rule 2's endings preserved. (2) `flyway_schema_history` is created before `V001`'s default-privilege grant and is therefore unreadable by `sm_dp_runtime` — now stated as the intended posture in §3.1 rather than left accidental. (3) Cross-scope supersession is undecided: an instance-wide apply leaves a placement-scoped `STAGED` row standing (measured) — routed as §8 row 14; v1 = exact-scope supersession, pinned by plan 004's tests. §9 notice 3 aligned with the superuser-executed Phase 0. The SQL block (Phase 0 md5 `768e195b…`, Phase 1 md5 `5c28fa14…`) is unchanged; the standing execution evidence stands. |
| 2026-09-07 | **Codex review of plan 004 (3 MAJOR + 1 MINOR) — two are design-level and are corrected here; the SQL block is untouched.** (1) Both database credentials were bound into the application process (§9 notice 3's `spring.flyway.user`): a compromised or careless in-process component could read the migrator secret and act as schema owner, so the DCL protected only against *accidental* use of the runtime datasource — Phase 1 now runs as a **separate one-shot migration process**; the application receives only `sm_dp_runtime` and verifies the schema's presence at startup. (2) `error_class`/`runtime_ref`/`pre_baseline`/`resolution_observed` were free strings/jsonb with I6 deferred to "engine classification": the residual table now requires the **shape to be typed at the persistence seam** (closed-grammar error class, identifier-only runtime refs, typed baseline and evidence) so a secret is unrepresentable before any SQL. (3) Landing did not hold the scope lock; the plan had claimed the placement-watermark FK's `KEY SHARE` serialized it — measured false on the upsert's `DO UPDATE` branch — so §2.2 rule 1 now states that the landing transaction **holds the `apply_scope` row lock**. (4, plan-only) `land()` mirrors `state_fits_operation` in Java before SQL. |
| 2026-09-07 | **Codex second pass on plan 004 (2 MAJOR I6 + 1 MINOR), corrected here and in the plan.** (1) The one-shot migration step printed exception text on failure, and Flyway's connection-failure message embeds the JDBC URL — a credential-bearing URL would have reached stderr: the step now prints only a stable classification (exception class + SQLSTATE), and **both** credentials refuse a URL carrying `user`/`password` parameters or userinfo before any connection, with sentinel tests capturing stdout/stderr. (2) The typed evidence seam had been described as making a secret unrepresentable; measured false for a bare identifier-shaped token — `RuntimeRef` now has a **closed key set** (design §2.3's runtime identifiers) and the residual table names the types as *shape controls*, with the content control an explicit per-adapter sentinel-test obligation for the adapter plan. (3) Javadoc/plan wording that still said "a token is unrepresentable" narrowed to "a `Bearer` header or any structured secret representation". SQL block untouched. |
| 2026-09-08 | **Codex third pass on plan 004 (2 MAJOR I6 + 1 MINOR), corrected here and in the plan.** (1) The persistence layer's reshaped refusal (`PgConstraintViolation`) had a classifier-only message but kept the PostgreSQL driver exception as its *cause*; Logback prints cause chains, so ordinary `log.error(msg, ex)` still emitted `DETAIL: Failing row contains (…)` — measured at the logger. No class-23 integrity refusal now crosses the persistence boundary with the driver exception attached: the reshaped refusal carries SQLSTATE, constraint, table and column only, and the plan's harness requires `getCause() == null` on every repository refusal (refusals in other SQLSTATE classes still propagate with the driver cause — their messages name objects, not row text — a qualification a focused re-review added after the first wording overstated it). The same measurement found Spring's `StatementCreatorUtils` logging every bind value at TRACE — pinned as a second reason the evidence columns carry no secrets. (2) The URL rule was a blocklist of three parameter names while Flyway logs the whole URL, so a value in any other pgjdbc property (`options=`) was admitted: §9 notice 3 now requires a **location grammar** — scheme, `host[:port]/database`, an allowlist of nine non-secret pgjdbc parameters with value grammars (each verified against `PGProperty` by test) — refusing everything else by position without echoing the URL, a value or an unknown parameter's name. (3) The migration entry point's comment no longer claims environment variables keep the password out of "a process table"; the separate process is the boundary. SQL block untouched. |
| 2026-09-08 | **Codex fourth pass on plan 004 (1 MAJOR I6 + 1 MINOR), corrected here and in the plan.** (1) `resolution_note` was the design's "one deliberately free-text column" — under `I6` that is a secret sink: an authenticated operator pastes a token or command output into the resolution comment during an incident, it is persisted, and Spring JDBC's `StatementCreatorUtils` prints every bound value the moment TRACE is enabled to diagnose that incident (measured). **v1 writes no free text:** the `Resolution` type has no note component (pinned by reflection), the resolving statement omits the column, and a test asserts it NULL after every resolution; the column and its grant remain in §3.1 as schema ahead of use, and a comments feature owes its own design (sensitive content, retention, access control, logging) before any path writes it — residual-table row added, §3.4 aligned. The bind-value TRACE surface is closed structurally rather than documented: every jsonb evidence bind goes through a `SqlValue` wrapper rendering as a length, so the TRACE line shows `[redacted text, N chars]` (measured; a plain-String bind still prints the value — the precondition). (2) `Credentials.toString` printed the role while the URL rule called a role name "half of a credential" — both halves are now redacted. SQL block untouched. |
| 2026-09-08 | **Codex fifth pass on plan 004 (1 MAJOR) — the SQL block changes, and the PG 14 evidence is re-run.** The fourth-pass fix removed the free-text resolution note from the Java seam but left the database able to reopen it: `sm_dp_runtime` still held `UPDATE` on `resolution_note` and table-level `INSERT`, so a legal resolution update carrying a note — or a row born with one — would be accepted. A Java-only control where `BE-D13`'s own rule makes the database the last line. §3.1's `apply_request` GRANT is now column-scoped for INSERT (every column but `resolution_note`) as well as UPDATE (six columns); the residual-table row is rewritten as DB-enforced with the Java side as mirror. Phase 1 md5 `5c28fa14…` → `2f36077c…` (Phase 0 unchanged), design commit `d87c2e7`. **Evidence re-run (run 4):** run 3's 168 legs reconstructed from their logs into a replay harness and executed in order against the new bytes — 0 status changes, 167/167 refusal-reason signatures identical — plus 11 new legs (`42501` on the legal-resolution-with-note UPDATE, on `resolution_note = NULL`, on the born-with-note INSERT and on the INSERT naming it as NULL, each with an owner control; the catalog leg showing `SELECT` as the runtime's only privilege on the column; fold-plus-note refused by the grant before the terminal guard); 179 legs, 107 refusals, 72 controls — [`evidence/ddl-execution-pg14.md`](evidence/ddl-execution-pg14.md) §0. **The replay harness is checked in** ([`evidence/ddl-replay/`](evidence/ddl-replay/): extracts the phases from this document by banner, starts PG 14, runs the 184-entry signature manifest including the live-session legs, exits 1 on drift) — a Codex sixth-pass finding: the evidence had called it re-runnable while it lived in a session scratchpad. An independent reproduction of the checked-in harness ran it clean, matched the reference run, and found its SQLSTATE pattern mis-anchored (52 refusals checked as "some error happened") — fixed and re-recorded: 107/107 refusals now carry a SQLSTATE, md5s and phase command tags are asserted, `--record` refuses a dirty design, both (d) directions are observed; three mutations pinned (widened grant → 9 differences, wrong SQLSTATE → 9, renamed trigger → 2). Codex's seventh pass then removed the harness's last footgun: an in-place `--record` could turn an unintended-but-committed DDL change into a green baseline in one command — replaced by `--record-candidate <dir>`, which writes a candidate manifest outside the tracked directory and exits 2 for review; the tracked baseline now changes only by a diff a human read and committed. Its eighth pass found the same class still open one layer down: `--out` was written before validation (so `--out reference-run` overwrote the tracked logs) and the candidate check was lexical (a symlink outside the harness pointing into it passed) — both output paths are now `resolve()`d and refused anywhere inside the repository before any mkdir/write/container, the candidate directory must not pre-exist, and `test_paths.py` (11 no-Docker tests) pins the attacks. |
| 2026-09-08 | **Plan 004 executed** ([`../../plans/backend/004-apply-persistence-and-admission.md`](../../plans/backend/004-apply-persistence-and-admission.md), Results). `BE-D13`'s §3.1 sketch now ships as `dp-core`'s Phase 0 bootstrap script (byte-identical — same md5 both sides) plus six Flyway migrations (a comment-stripped statement **multiset** match, deliberately not byte order: the split redistributes each table's GRANTs to the migration that creates that table, so file order is not design order — `SketchMirrorTest` locks both directions). Every **database-enforceable** obligation (a)–(u) is a repository test asserting the constraint/index/trigger/privilege **name**; three obligations have no database artifact by design — (n)'s watermark comparison and (q)'s terminal-request guard, proven by refusal-plus-accepts pairs instead, both engine-only, each with a raw write the database is measured to accept; and (r), stated N/A — it needs a `RuntimeAdapter` and the engine's step loop, both Plan 005's. `I5` is a failing guard over all 24 tenant-keyed statements (`TenantIsolationIT`), but not uniformly by the class's own assertions — 4 of the 5 mutated INSERT statements are refused by a composite FK before any `TenantIsolationIT` assertion runs, one is a genuine isolation collapse with no FK in play, and the class's real INSERT-side coverage is its owner-side positive controls (Results, `I5` row, for the exact split). Landing holds the same `apply_scope` lock as admission; the engine's transactions are its own (`REQUIRES_NEW`, proven by a `REQUIRED` mutation). Measured on execution (re-measured independently for this row, not copied from the plan's prototype-era predictions, stale in at least six per-class counts and two whole-run totals by the time Task 6 finished): surefire **268** (mesh-events 127 / dp-core 116 / arch-tests 25), failsafe **142 total (140 executed, 2 skipped)**, `./contracts/run-checks.sh --strict` ALL CHECKS PASSED, `git diff --check` clean. Design text unchanged by execution — the SQL block's two hashes (`768e195b…` Phase 0, `2f36077c…` Phase 1) are exactly the row above's, confirmed on every task's own re-verification and again here. Ten authorised deviations from the plan's printed code and tests, each a controller ruling made mid-execution for a stated reason (full list in the plan's Results section): the URL grammar's choke point moved to `SmDataMigrationMain.dataSource` and reordered grammar-first after a measured `java.util.logging` leak through `Driver.parseURL`; two more `@DependsOn("smDataSchemaReadiness")` edges; two lock pins added for `OutcomeService.start()`/`resolveIndeterminate()` plus obligation (n)'s database-accepts half; `AttemptRepository.close`'s SQL gained `coalesce(...)` (no longer erases what `open` wrote); its SQL separately gained `AND outcome IS NULL` (a second close no longer overwrites a resolved outcome); obligation (m)'s database-accepts half and `EngineTransactionBoundaryIT`'s third test (the `REQUIRES_NEW → REQUIRED` mutation now fails exactly 3, not 2); a fourth `ApplyPersistenceConfigurationIT` test measuring Boot's own bind-failure report for URL-sentinel leakage; `StateFitsOperationMirrorTest` added to close a Java↔DDL gap nothing else caught; `TenantIsolationIT` extended by 3 fixtures and 2 tests to close three tenant-predicate gaps the mutation sweep found; and `resolution_known` (the 23rd named CHECK, previously pinned by nothing) with the coverage matrix's double-counted (h) row corrected. Model ran on Sonnet with Opus review, per Parikshit's explicit execution instruction; every task worked in the existing checkout with no worktree — the execution lead's ruling under that instruction's delegation ("use separate worktrees only for tasks that the plan and actual file ownership prove independent"), because no task pair in this plan was independent by file ownership. |
| 2026-09-08 | **`BE-D16`/`BE-D17` added — R1 of the R18 repair wave (design-first amendment for two post-completion external-review findings on plan 004; design and decisions only, no code/migration/`contracts/` change).** An external review of the completed plan 004 found two design defects, not coding mistakes, both routed to the developer rather than fixed in that review's own wave. **`BE-D16`:** the request-state table's `STAGED` row read *"no lifecycle step was part of this request (`strategy = manual`) or it has not begun"* with `Terminal? = yes (for manual)`, but nothing enforced the qualifier — `terminal_has_timestamp` excludes `STAGED` from the non-terminal set unconditionally, `V006`'s trigger permits only `STAGED → SUPERSEDED`, and `RequestState.validFor(APPLY)` accepted `STAGED` for every strategy — so a non-manual apply's `OutcomeService.land(t, id, STAGED)` succeeded and froze immutably, with no path to `APPLIED`. `BE-D16` removes the ambiguous clause and states the plain implication `state = 'STAGED'` implies `strategy = 'manual'`; §3.2 is rewritten with an amendment callout, the rewritten `STAGED`/`RUNNING` rows, and the intended CHECK's exact name and shape for the implementing task (`staged_requires_manual`). **`BE-D17`:** `resolution_is_evidence` required only that `resolution_observed -> 'stagedGeneration'` be *some* number, never that it equal `apply_request.generation`, so evidence naming generation 21 could resolve a generation-20 request `applied` and raise the watermark to 20 while asserting 21; and the same shape was required on every `INDETERMINATE_RESOLVED` row regardless of `operation`, so a `restart` resolution — whose request carries no `generation` at all — had to manufacture a `stagedGeneration` that `raiseWatermark`'s own `operation = 'apply'` gate then ignored. `BE-D17` splits the evidence shape by `operation`: `apply` evidence's `stagedGeneration` must equal `generation` (a same-row CHECK comparison); `restart` evidence carries `restartsObserved` and must **not** carry a `stagedGeneration` key at all. §3.4 gains the amendment callout, the two shapes, the intended CHECK specification (coalesced, no-NULL-escape, matching §3.1's own discipline), the four required-rejectable classes, and an explicit reconciliation of §2.2 rule 1's existing "lands `STAGED`/`APPLIED`/resolved-`'applied'`" sentence (left as written per `I3`; read after this decision as applying to `resolved-'applied'` only when `operation = 'apply'`). Both decisions are **PROPOSED**, amend `BE-D12`/`BE-D13` by cross-reference rather than editing either in place (`I3`), and are design-only: the SQL block (lines 228–552; hashes `768e195b…`/`2f36077c…`, unchanged), the seven migration files (md5s unchanged) and `contracts/` are untouched, and no Java or test file changed. The Gate map (§6) and the request-state table's `INDETERMINATE_RESOLVED` row are cross-referenced to both. The next task in this repair wave implements the CHECKs, the migrations and the Java mirrors this design now specifies. |
| 2026-09-08 | **R1 fix round 1/5: an independent review built both specified CHECKs verbatim on a live PG 14.24 and ran 21 legs against them; the substance held (21 legs), the precision needed work — corrected in place, design and decisions only, the SQL block and all nine hashes unchanged.** CRITICAL: "biconditional" was the wrong word for `BE-D16`'s invariant in five places (design §3.2 ×2, this file's `BE-D16` body, its Rationale, and the prior changelog row) — the invariant is a one-directional implication, and the biconditional *form* (`CHECK ((state = 'STAGED') = (strategy = 'manual'))`) is measured to reject a manual apply at `REQUESTED`; all five reworded, with the design now stating why the two forms differ. IMPORTANT, all fixed: (1) `staged_requires_manual` had an unstated NULL escape (`('stop', NULL, 'STAGED')` measured accepted) — design §3.2 now gives the cross-constraint argument (`state_fits_operation`'s **stop** branch closes it, not its restart branch, which merely becomes redundant) and specifies the CHECK's self-defending coalesced text. (2) The specified `resolution_is_evidence` depended on undefined `AND`-clause evaluation order — the `::numeric` cast measured to throw `22P02` instead of refusing `23514` when evaluated before its type guard; replaced by a cast-free `resolution_observed -> 'stagedGeneration' = to_jsonb(generation)` jsonb comparison with no erroring operand. (3) The named Java mirror (`RequestState.validFor`) cannot express a strategy-dependent rule — decided in the design as `OutcomeService.land`'s own guard (it already holds `row.strategy()`), not left to the implementing task. (4) The landing mechanism was unstated while `SketchMirrorTest`'s hard-coded file/statement counts rule out a new migration — ruled, and written into both amendments: edit `V002__apply_request.sql` in place, with the plan's own "revisit obligation" (Prerequisites) repeated for both. (5) `restartsObserved`'s element shape was unspecified — now `{"ref", "restarted"}`, two fields, deliberately no `observedHash` (mirroring `ReplicaObservation`'s existing three-field shape for `replicas`, minus the field a restart has no generation to certify). (6) §2.2 rule 1's own sentence gained an inline forward pointer to §3.4's reconciliation (a cross-reference, not a meaning edit — permitted under `I3`). (7) Restart's reachability of `APPLY_INDETERMINATE`/`INDETERMINATE_RESOLVED` is now asserted explicitly (`state_fits_operation`'s restart branch, `RequestState.validFor(RESTART)`), so `BE-D17`'s restart branch rests on a stated fact rather than an implicit one. (8) `BE-D13`'s own register row (this file) now carries inline "superseded in part by `BE-D16`/`BE-D17`" markers at the two clauses those decisions narrow, mirroring the `FE-D5`/`FE-D26` precedent in `../DECISIONS.md`. MINOR, all fixed: the `RUNNING` row's "terminal outcome" phrasing corrected against its own `Terminal? = no (blocking)` column; the blockquote's non sequitur now cites `restart_not_manual` rather than `lifecycle_has_strategy`; both `BE-D17` evidence bullets now state that key-exactness is the Java seam's job, not the CHECK's; `?` replaced by `jsonb_exists(...)` throughout (JDBC parameter-marker collision); the residual-table row for per-element typing now names both `replicas` and `restartsObserved`; "must equal" clarified as numeric-equal (`20.0` accepted, `20.5` refused). The header's `BE-D12`…`BE-D17` range citation is unchanged (fixing it further would extend the exact staleness pattern `I3` warns against, not correct it) — noted, not touched. |
| 2026-09-08 | **R1 fix round 2/5: an independent re-review built both amended CHECKs on PG 14.24 across 30 legs on the real table plus 22 isolation legs, confirmed the Critical and all eight Important findings from round 1 closed (a manual apply is admitted at `REQUESTED`; all four non-manual strategies at `STAGED` refuse by name; the cast-free clause is order-independent across all six permutations), and found one new Important defect, one regression this round's own edit introduced, one Important gap, and the header fix round 1 had declined should in fact be made — corrected in place, design and register only, all nine hashes unchanged.** (A, Important, new) The categorical "no erroring operand" claim was false: `jsonb_array_length` throws `22023` on a non-array operand and sat behind the identical sibling-`jsonb_typeof`-across-`AND` pattern round 1's own cast fix had just rejected — measured `23514`×3/`22023`×3 across six permutations with a scalar `replicas` value. Both non-empty-array clauses (`replicas`, inherited, and `restartsObserved`, this amendment's own second copy of the pattern) now compare against `'[]'::jsonb` instead — jsonb inequality never throws — making the categorical claim true rather than narrowing it, per the ruling. (B, Important, new) The landing ruling ignored the tracked DDL-replay baseline (`evidence/ddl-replay/design-block.json`'s pinned md5s, `replay.py`'s drift failure) — both amendment callouts now record that obligation explicitly, point at `evidence/ddl-replay/README.md`'s own procedure rather than restating it, and state plainly that this task leaves that gate red for the next one to close. (C, Important — a regression) Round 1's edit had deleted `BE-D16`'s prove-it-fires-obligation sentence while inserting the landing ruling, leaving it asymmetric with `BE-D17`'s; restored, with the two obligations now stated as paired and neither optional relative to the other. (D, Minor) `BE-D17`'s Java-boundary guard for rejection class (i) had no named home; now `OutcomeService.resolveIndeterminate`, with the consequent split of `ResolutionEvidence`/`Resolution` into operation-typed shapes named as a requirement (exact Java shape left to the implementing task) and the current javadoc flagged as superseded prose rather than edited (`.java` out of scope). (E, trivial) `resolution_is_evidence`'s own SQL comment and §3.3's obligation-(j) prose, both inside or adjacent to material this design-only round cannot rewrite, now carry inline superseded-prose flags; the blockquote's `operation = 'apply'` parenthetical now cites both `lifecycle_has_strategy` (excludes `stop`, measured: `('stop','manual','STAGED')` refused by it alone) and `restart_not_manual` (excludes `restart`), stating both are load-bearing rather than one. (Header) The `BE-D12`…`BE-D17` range in the design's own header is now `BE-D*` — round 1 had declined this, reasoning it would extend `I3`'s staleness pattern; on reflection (and the coordinator's agreement) replacing a range with the family prefix is exactly what `I3` asks for, not an extension of the problem, so it is fixed here. |
| 2026-09-08 | **`BE-D16` executed — R2 of the R18 repair wave.** `staged_requires_manual` lands, verbatim, as one more inline table constraint inside `sm_data.apply_request`'s `CREATE TABLE` — in **both** `V002__apply_request.sql` and `eda-walking-skeleton.md` §3.1's SQL block, identical text, no seventh migration. `OutcomeService.land` gains the strategy-dependent guard beside its existing `state_fits_operation` mirror, refusing a non-manual `STAGED` walk in Java, naming `staged_requires_manual`, before any write. Test obligation discharged: `SchemaConstraintsIT` refuses each of the four non-manual ladder strategies at the raw-SQL layer by name, with a manual positive control; `OutcomeTransitionsIT` does the same through `OutcomeService.land`. Red-first: both new probes were run against the pre-amendment tree and failed with "nothing was thrown" (insert/land succeeded), confirming the tests fail for the CHECK's/guard's absence and not for some other reason; the prove-it-fires mutation (the CHECK removed from both `V002` and the design's mirrored block) then turned the four refusals red and left the manual positive control green, and was reverted against the committed state. **A finding, not a fixture tweak:** the pre-existing `SchemaConstraintsIT` probe `aStopThatClaimsToBeStagedIsRefusedByStateFitsOperation` used `state = 'STAGED'` as its illegal exemplar for a `stop` row; measured against the live CHECK, that row now also violates `staged_requires_manual` (`stop` forces `strategy` NULL via `lifecycle_has_strategy`, so `coalesce(strategy = 'manual', false)` is always false there), and PostgreSQL reports `staged_requires_manual`, not `state_fits_operation`, for the identical row — the probe is retargeted to `state = 'APPLIED'` (renamed `aStopThatClaimsToBeAppliedIsRefusedByStateFitsOperation`), which the same `stop` allow-list excludes without the second violation. The DDL-replay harness (`evidence/ddl-replay/`, a separate, tracked evidence set from the Java suite) independently measured the identical ambiguity on its own `restart`-in-`STAGED` leg — its baseline is deliberately left red, unre-recorded, per this task's explicit instruction; re-recording it is a separate later task. `RefusalShapesIT.theDesignsOwnNamedConstraintsAreAllPresent`'s named-CHECK count moved 23 → 24, the one new constraint. Phase 1 md5 `2f36077c…` → `a28564b7…` (Phase 0 unchanged at `768e195b…`); of the seven migration/bootstrap file md5s, only `V002__apply_request.sql`'s changed (to `d8b8d48b…`), the other six unchanged. `mvn verify`: surefire **268** (unchanged: mesh-events 127 / dp-core 116 / arch-tests 25), failsafe **147 total, 145 executed, 2 skipped** (baseline 143/141/2 plus the four new tests this task added); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. Also corrected in `decisions.md` and the design, both prose-only: `BE-D16`'s body no longer claims the DDL-replay baseline re-records "in the same commit as the CHECK" — `replay.py`'s `design_is_clean()` refuses `--record-candidate` against an uncommitted design file, so re-recording is necessarily a separate, later commit, per the harness README's own step 5; and §3.4's misattribution of "the only path to it" to "§3.4's own opening paragraph" is corrected to `OutcomeService`'s own javadoc, where the phrase actually lives. `BE-D16` stays **PROPOSED** — landing the CHECK and its Java mirror is not ratification. `BE-D17` untouched: no code, migration, or `resolution_is_evidence` change. |
| 2026-09-08 | **`BE-D17` executed — R3 of the R18 repair wave, plus four carry-ins from R2's review.** `resolution_is_evidence`'s body is replaced in place, verbatim from design §3.4, in **both** `V002__apply_request.sql` and `eda-walking-skeleton.md`'s §3.1 SQL block — an operation-discriminated `CASE`, no new migration, no statement-count change. **Java side:** `ResolutionEvidence` becomes a `sealed interface` permitting two new records, `ApplyResolutionEvidence` (the old shape, renamed) and `RestartResolutionEvidence` (`restartsObserved`, a new `RestartObservation(ReplicaRef, boolean)` element — two fields, no `observedHash`, per the design's own suggested names); `Evidence` dispatches `write`/`read` on the sealed type; `OutcomeService.resolveIndeterminate` gains `requireEvidenceFitsOperation`, checked before any write, beside the existing `state_fits_operation`/`staged_requires_manual` guards. **All four rejection classes proven at both boundaries:** (i) wrong-operation evidence, both directions — Java: two new `OutcomeTransitionsIT` tests; SQL: two new `SchemaConstraintsIT` tests, one using `GOOD_EVIDENCE` against a restart row and one using the new `GOOD_RESTART_EVIDENCE` constant against an apply row; (ii) a mismatched apply generation — Java: a new test asserting the exact generation values in the refusal message, paired with a same-generation positive control; SQL: a new test, plus a dedicated numeric-vs-textual-equality test (`1.0` accepted against `generation = 1`, `1.5` refused — jsonb-to-jsonb comparison, measured, not assumed); (iii) missing/empty/non-array observations — proven at the Java boundary by `ApplyResolutionEvidence`'s and `RestartResolutionEvidence`'s own constructors (`EvidenceTypesTest`, extended) and at raw SQL by three new `SchemaConstraintsIT` legs; (iv) an invented restart staged generation — proven structurally in Java (`RestartResolutionEvidence` has no such component to invent one in, asserted by reflection) and by one new raw-SQL leg, since the Java type cannot reach the database with the defect at all. A new `aFullyEvidencedRestartResolutionInsertsThePositiveControl` (SQL) and `resolveIndeterminateAcceptsAFullyEvidencedRestartResolutionThePositiveControl` (Java) prove the restart shape is satisfiable, not merely refusable. Red-first: each new refusal test was run against the pre-amendment tree first and failed for the CHECK's/guard's absence (insert/resolve succeeded where it should not). **Prove-it-fires, measured rather than assumed (revert `resolution_is_evidence` to its pre-`BE-D17` body in both `V002` and the design's mirrored block, re-run):** of `SchemaConstraintsIT`'s 29 and `OutcomeTransitionsIT`'s 18, exactly 5 flip — the three new SQL refusals that depend on the CASE (mismatched generation, the numeric-vs-textual leg, and apply-shaped evidence resolving a restart) stop refusing, as expected; and, separately informative, **both new restart positive controls (SQL and Java) also flip, but by being wrongly REFUSED** — the pre-amendment, operation-blind CHECK required `stagedGeneration` to be a number on every resolution, so a restart's `{"restartsObserved": […]}` (no such key) violates it too, for a reason unrelated to the new CASE. That is not a false positive: it is the mutation independently confirming what `BE-D17` fixes — a restart resolution was structurally unrepresentable before this amendment, not merely permissively typed. **One new test does not flip, and that is a limit of this experiment, not of the test:** `aRestartShapedEvidenceResolvingAnApplyRequestIsRefusedByResolutionIsEvidence` stays refused under the OLD check too (restart-shaped evidence has no `stagedGeneration` key at all, which the old, operation-blind CHECK also required) — the test still correctly proves the design's required obligation (rejection class (i), direction 2), it simply does not distinguish old from new CHECK on its own; the paired direction (apply-shaped evidence on a restart request) does distinguish them, and is the one that flips. The pre-existing, unrenamed-in-effect apply positive control (`aFullyEvidencedResolutionInsertsThePositiveControl`) stays green throughout, as it must. The four rejection-class-(i)/(ii) `OutcomeTransitionsIT` tests are unaffected by this SQL-only mutation, because `requireEvidenceFitsOperation` refuses before any SQL runs — confirming the Java guard, not the CHECK, is what they pin. **Separately, commenting out the call to `requireEvidenceFitsOperation` (CHECK left intact) turns exactly 3 of those four red, not 4** — the three refusal tests fail, but not with "nothing was thrown": each now throws `PgConstraintViolation` (23514 on `resolution_is_evidence`) instead of the expected `IllegalStateException`, because the CHECK is still the last line and still refuses the same malformed row — the mutation demonstrates precisely that these three tests pin the *Java* guard specifically (which layer refuses, and with what message), not "is this row refused at all" (which the CHECK alone still guarantees). The fourth, `resolveIndeterminateAcceptsAFullyEvidencedRestartResolutionThePositiveControl`, is a positive control — well-formed evidence that both the (disabled) guard and the CHECK accept either way — so it correctly stays green under this mutation; it was never going to flip, and expecting it to would have been the wrong claim. Both mutations reverted against the committed tree. **A pre-existing fixture forced to change, not merely relabelled:** `GOOD_EVIDENCE`'s `stagedGeneration` moves from an arbitrary `12` to `1`, to equal `legalRequest()`'s own `generation` — `BE-D17` is exactly the amendment that makes this equality load-bearing where before any positive number sufficed. **Carry-ins from R2's review, landed in the same task:** (1) `OutcomeService.land`'s `staged_requires_manual` refusal message no longer dereferences a null `Strategy` when `row.strategy()` is null (unreachable today behind `validFor`'s guard, but a readable message rather than a latent NPE for a future in-memory row). (2) Coverage restored: a new `SchemaConstraintsIT` test (`aStopThatClaimsToBeStagedIsRefusedByStagedRequiresManual`) exercises a `stop` + `STAGED` row by name again, closing the hole R2's retargeting of the `state_fits_operation` probe (to `APPLIED`) left open. (3) §3.2's NULL-safety paragraph corrected in two measured ways: "harmlessly" held only for enforcement, not diagnostics — a `('stop', NULL, 'STAGED')` row violates both `staged_requires_manual` and `state_fits_operation` at once, and PostgreSQL reports whichever constraint's name sorts first lexically (`staged_requires_manual` before `state_fits_operation`, measured, and the reason `SchemaConstraintsIT`'s own `stop`/`STAGED` probes name the CHECK they do); and the `stop` branch's `STAGED` exclusion is now exactly as redundant as the `restart` branch's always was, so "stays load-bearing, not redundant" was wrong for the `STAGED` case specifically — it remains load-bearing only for the other states `stop` may not hold. A pinning comment was added at `OutcomeTransitionsIT`'s `aRestartLandedOnStagedIsRefusedInJavaBecause…`, noting its `state_fits_operation` assertion stays correct only because `land()`'s `validFor` guard runs above the `staged_requires_manual` guard. (4) This row is that changelog entry; §3.1's own SQL comment and §3.3's obligation (j) prose (previously flagged "superseded, not touched by this round") are now rewritten to the two-shape, four-class reality, with a landed-note added where each flag stood. **Hashes:** Phase 0 unchanged (`768e195b…`, lines 228–265); Phase 1 (design §3.1's SQL block) `a28564b7…` → `6b50bf5f…` (lines 266–570, shifted by the added comment lines); of the seven migration/bootstrap files, only `V002__apply_request.sql` changed, `d8b8d48b…` → `d31bf87d…`; the other six unchanged. **The tracked DDL-replay baseline (`evidence/ddl-replay/design-block.json`) is deliberately left exactly as it was — still red from R2, now red for a second, additive reason — per this task's explicit instruction; re-recording both amendments together in one `--record-candidate` pass is the next task's.** `SketchMirrorTest`'s `6`/`24`/`7` counts and the `{1,6,9,2,3,2}` per-file vector are unaffected (an in-place clause edit, no statement added or removed) — reconfirmed green. `mvn verify`: surefire **271** (mesh-events 127 unchanged / dp-core **119** [+3: two new `EvidenceTypesTest` cases, one new `EvidenceJsonTest` round-trip] / arch-tests 25 unchanged), failsafe **159 total, 157 executed, 2 skipped** (baseline 147/145/2 plus **+8** `SchemaConstraintsIT` and **+4** `OutcomeTransitionsIT`); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D17` stays **PROPOSED** — landing the CHECK and its Java split is not ratification. |
| 2026-09-09 | **`BE-D17` R3 fix round 1: an independent review confirmed the CHECK itself against 840 legs across 60 clause permutations (23514 only) and confirmed the watermark defect structurally unrepeatable, then found one real regression in test strength and one incomplete audit — corrected in place, code and tests only, `contracts/` untouched.** Important 1: the apply branch's two `replicas` array clauses (non-empty, typeof-array) were pinned by nothing — `legalRequest()` fixes `generation = 1`, but `everyWorthlessResolutionIsRefusedByResolutionIsEvidence`'s three apply-side observation legs all carried `stagedGeneration: 12`, so post-`BE-D17` every one is already refused by the *equality* clause before the array clauses are ever reached; measured, deleting both array clauses left all eleven apply-side legs green. Fixed by adding `SchemaConstraintsIT.anEmptyOrNonArrayReplicasWithAMatchingStagedGenerationIsRefusedByResolutionIsEvidence` (two legs, `stagedGeneration: 1`, matching), verified by per-clause mutation (the two array clauses removed from both `V002` and the design's mirrored block, not the whole CHECK): both new legs flip to ACCEPTED independently, the old `12`-carrying legs do not flip, mutation reverted. Important 2: the R3 report's changed-test audit was complete in letter, incomplete in kind — it had no entry for the pre-existing test whose *proof* the amendment invalidated without a character changing (the same three legs above), and its closing sentence ("no existing test's meaning moved…") was false for that test; the report is corrected with an audit-table row, the missing question ("which pre-existing legs did this change the *reason* for?") and its answer, and a corrected closing sentence — no code change, since the legs are still correct refusals, just no longer diagnostic of the clause they were meant to isolate. Minor, all fixed: (1) a new latent NPE in `OutcomeService`'s apply-branch generation comparison (`apply.stagedGeneration() != row.generation()` auto-unboxing a boxed `Long` `ApplyRequestRow` does not require non-null for `APPLY`), same class of defect as the one the original commit fixed one line above, fixed the same way (an explicit null guard). (2) `V002`'s own comment (byte-identical in design §3.1) still read "stays load-bearing regardless" after carry-in 3 had corrected exactly that claim in §3.2's body in the same commit — both rewritten identically to say the `stop`/`restart` branches' `STAGED` exclusion is now exactly as redundant with `staged_requires_manual` as the other always was, load-bearing only for the *other* states `stop` excludes. (3) "a restart resolution raises no watermark" was pinned only incidentally (an accident of boxing that would NPE, not a proof) — new `MonotonicityIT.resolvingARestartIndeterminateAsAppliedRaisesNoWatermark` reads both watermarks after a fully-evidenced restart resolution with `outcome = applied` and asserts neither moved. (4) throw-freedom was weakly pinned — a regression to `jsonb_array_length` would be caught in only 2 of 6 permutations and a regression to the `::numeric` cast never, because the suite's only string leg (`"12"`) casts cleanly; new `SchemaConstraintsIT.aNonNumericStringStagedGenerationIsRefusedByResolutionIsEvidenceNotByAThrowingCast` (`stagedGeneration: "r1"`) closes the **cast** half completely — measured against a cast mutant across all six permutations: real CHECK refuses `23514` six times, mutant throws `22P02` six times, 0 of 6 → 6 of 6. **Correction (R4 of the repair wave): this entry originally claimed the same leg "closes it" for `jsonb_array_length` too — false.** That half's coverage moved from 2 of 6 to 3 of 6, and the move is a side effect of Important 1's own new leg (its matching-generation pair reaches the array clauses, for the first time, under one more permutation), not of this leg; it remains **undetected in the shipped clause order**, where `jsonb_typeof` short-circuits ahead of `jsonb_array_length` before the throw can occur. R4 closes that half separately, with a text-level assertion (`resolution_is_evidence`'s installed definition, read back via `pg_get_constraintdef`, asserted to never contain `jsonb_array_length`) rather than more permutation coverage. **Hashes:** Phase 0 unchanged (`768e195b…`); Phase 1 `6b50bf5f…` → `4ecc4470…` (lines 266–572, +2 lines from the Minor 2 comment fix); `V002__apply_request.sql` `d31bf87d…` → `f4a20e64…`; the other six files unchanged. The tracked DDL-replay baseline is left exactly as it was, now red for a third, additive reason; `--record-candidate` was not run. `mvn verify`: surefire **271** (unchanged — no surefire tests added this round), failsafe **162 total, 160 executed, 2 skipped** (baseline 159/157/2 plus **+2** `SchemaConstraintsIT` and **+1** `MonotonicityIT`); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D17` stays **PROPOSED**. |
| 2026-09-09 | **R4, the final task of the R18 repair wave: re-records the DDL-replay baseline against `BE-D16`/`BE-D17`, closes the throw-freedom gap's remaining half with a text-level assertion, and corrects two changelog over-claims from R3 fix round 1 — two commits, no production SQL change.** **Commit 1 (docs + one new test, all seven md5s and both design-block hashes unchanged):** the row above's Minor 4 is corrected in place (see its own inline correction) rather than restated here, to avoid a second, separately-stale claim; the (untracked, gitignored) R3 report's audit-table arithmetic slip ("the other eight apply-side legs sharing the same `12`") is fixed to the correct count of three; three stale `` `BE-D12`…`BE-D15` `` range citations (`eda-walking-skeleton.md` §6's gate-map row, `docs/design/README.md`, `docs/design/backend/design.md`) are swept to the `` `BE-D*` `` family prefix per `I3`, since `BE-D16`/`BE-D17` now also live in the range's own document. New: `SchemaConstraintsIT.resolutionIsEvidenceNeverCallsJsonbArrayLength` reads `resolution_is_evidence`'s installed definition back via `pg_get_constraintdef` and asserts it never contains `jsonb_array_length` — a text-level guarantee no amount of permutation coverage can substitute for, because `SketchMirrorTest` locks the design's SQL block and `V002` to each other as TEXT: reverting both array clauses to `jsonb_array_length(...) > 0` in *both* files passes that mirror and every existing behavioural test in `SchemaConstraintsIT`/`OutcomeTransitionsIT`/`PrivilegeTopologyIT`, reintroducing exactly the undefined-`AND`-order flakiness design §3.4 spent two review rounds eliminating. Prove-it-fires, measured: with that reversion applied to both files, `resolutionIsEvidenceNeverCallsJsonbArrayLength` alone goes red (`22023`-shaped operand now present in the catalog definition); nothing else in the three classes above flips; reverted against the committed green state before commit 2. **Commit 2 (the baseline re-record, `evidence/` and the evidence doc only):** `replay.py` reported five differences, all explained by `BE-D16`/`BE-D17` and nothing else — the `phase1` md5 (the two amendments' text); `(h) REFUSE: stop/restart in state STAGED -> state_fits_operation` both flipping to `staged_requires_manual` (both operations' `STAGED` rows now violate both constraints at once, PostgreSQL naming whichever sorts first lexically, per §3.2's own R3 measurement); and `(j) REFUSE: bogus resolution_outcome -> resolution_known` plus `(j) POS: a FULLY-EVIDENCED INDETERMINATE_RESOLVED succeeds` both flipping onto `resolution_is_evidence`, because both legs' shared exemplar carried `stagedGeneration: 7` against the row's own `generation = 1` — harmless before `BE-D17`, load-bearing after. The two `(h)` legs are **retargeted** (the decision the harness README flags as needing one, not left to accept a renamed signature): `stop` + `STAGED` → `stop` + `APPLIED`, `restart` + `STAGED` → `restart` + `STOPPED`, both now isolating `state_fits_operation` alone, mirroring the Java suite's own `aStopThatClaimsToBeAppliedIsRefusedByStateFitsOperation` precedent (R2). The two `(j)` legs are retargeted the same way `GOOD_EVIDENCE` was in R3 (`stagedGeneration` `7` → `1`, matching), restoring `resolution_known`'s isolation and the positive control's own satisfiability. 18 new legs close the coverage those retargets would otherwise have dropped and prove `BE-D16`/`BE-D17`'s remaining obligations by name, each with a positive control: `staged_requires_manual` refusing all four non-manual ladder strategies at `STAGED` plus a manual positive control, and `stop`/`restart` + `STAGED` restated under their correct name; `resolution_is_evidence`'s apply branch refusing a generation mismatch and, for the first time with a MATCHING generation, empty/non-array `replicas` (closing the Java suite's own Important-1 gap in this harness too); a new `restart`-operation `APPLY_INDETERMINATE` fixture and a second `apply`-operation one, against which the restart branch's all four rejection classes are proven (an invented `stagedGeneration`, missing/empty/non-array `restartsObserved`, and both cross-shape directions) alongside `BE-D17`'s own restart positive control. Every one of the 18 new/retargeted legs matched its expected status and signature on the first `--record-candidate` pass — `diff -u` against the tracked `legs.json` was empty; only `design-block.json`'s `phase1` entry changed. Re-run from the checked-in artefacts: `NO DRIFT`. **Hashes:** Phase 0 unchanged (`768e195b…`, lines 228–265); Phase 1 (design-block.json) `4ecc4470…` unchanged in text, lines 266–572 — the DDL-replay baseline was the only thing behind, not the SQL itself, which R3 fix round 1 had already left in its final form; the seven migration/bootstrap file md5s are unchanged by this task (no SQL touched). `legs.json`: 184 → 202 entries (179 → 197 SQL legs: 122 refused / 75 ok, up from 107/72); `design-block.json`'s `phase1` md5 `2f36077c…` → `4ecc4470…`, lines 266–552 → 266–572. `mvn verify`: surefire **271** (unchanged — one new test is failsafe, not surefire), failsafe **163 total, 161 executed, 2 skipped** (baseline 162/160/2 plus **+1** `SchemaConstraintsIT`); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py` 11 tests green; `git diff --check` clean. This closes the R18 repair wave's last open obligation: the tracked DDL-replay baseline no longer disagrees with the design it is evidence for. `BE-D16`/`BE-D17` stay **PROPOSED** — re-recording evidence is not ratification. |
| 2026-09-09 | **R5 of the R18 repair wave: a fourth external review measured that `resolution_is_evidence`, even after `BE-D17`, validates only the evidence container — `{"stagedGeneration": <matching>, "replicas": [{}]}` and `{"restartsObserved": [null]}` are both accepted — and priced the fix as a new decision, `BE-D18`; design and decisions only, the SQL block and both design-block hashes (`768e195b…` Phase 0, `4ecc4470…` Phase 1 at lines 266–572) unchanged, re-verified after this commit.** New: seven `jsonb_path_exists(…, silent => true)` JSONPath predicates (four apply-branch, three restart-branch — element well-formedness, element key-set, hash format apply-only, top-level key-set), specified in `eda-walking-skeleton.md` §3.4 and appended to the design's own residual table (§3.1's per-element-typing row, now marked DB-enforced) and to §3.3's obligation (j) (thirteen apply legs, eight restart legs, each a refusal by name with a positive control). Measured inputs, attributed to the developer and not re-derived: the immutability gate (`jsonb_path_exists`/`jsonb_path_match` and their operators are `provolatile = 'i'` in `pg_proc`; every `_tz` variant is `s`), the `.keyvalue()` throw on a non-object element even in `lax` mode (closed by the four-argument form's `silent => true`, not the two-argument form), and the Java shape facts the DDL must mirror (`Evidence.writeApply`'s explicit `putNull("observedHash")`; `ValueGrammar.HASH`'s `{16,128}`, not `+`; `ReplicaRef`'s non-blank-only check, deliberately not `ValueGrammar.VALUE`). **A mid-drafting correction, folded in before this row was written, not left standing:** an earlier pass of this same task treated an apply `replicas` element with `observedHash` entirely absent as a third legitimate shape (alongside present-null and present-valid); that was wrong and is not in the design as landed — `Evidence.writeApply` always emits the key, so an absent key is exactly the "missing required observation" this amendment exists to reject, and the well-formedness predicate now includes `exists(@.observedHash)` for the apply branch. Leg totals are unaffected (13/8), only the split between positive and hostile legs (2/11 for apply, not 3/10). **Ruled here, not left to the implementing task:** one Java-side change is required — `Evidence.readResolutionEvidence` must refuse (not null-coalesce) a `replicas` element whose `observedHash` key is entirely absent, while continuing to map a present `null` to Java `null`; three boundary negatives (a Java refusal test, a raw-SQL refusal-by-name with a positive control, and a DDL-replay leg) are named as obligations, none discharged by this task. Every other Java-side guard (`ReplicaRef`, `ValueGrammar.HASH`, the two evidence records' closed shapes, all landed under `BE-D17`) already enforces what these predicates add and needs no change. `BE-D17`'s register row is marked superseded in part (its "key-exactness is the Java seam's job" reading, for the raw-SQL boundary specifically) by cross-reference, per `I3` — its own body text is left as written. Landing ruled identical to `BE-D16`/`BE-D17`'s: an in-place edit to `V002__apply_request.sql`'s existing `resolution_is_evidence` clause and the design's mirrored block, no new migration, with the same revisit-once-live obligation; the tracked DDL-replay baseline is untouched by this task (no SQL changed) and will read red, expectedly, once the implementing task edits `V002`. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: surefire **271**, failsafe **163 total / 161 executed / 2 skipped** — unchanged, since this task changes no code; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D18` is **PROPOSED**, like every other `BE-D*` in this file. |
| 2026-09-09 | **R5 fix round 1/5: an independent review measured the specified CHECK against a live PG 14.24 and found the amendment does not close the hole it exists to close — two Critical findings, two Important corrections to this document's own stated reasoning, and three Minors — corrected in place, design and decisions only, the SQL block and all nine hashes unchanged.** **Critical 1 — a `lax`-mode escape survives the well-formedness filter:** a filter applied directly to an array-valued `replicas`/`restartsObserved` element auto-unwraps under `lax` mode rather than testing the filter against the array as one item; for an empty array or an array whose inner elements individually satisfy the (negated) predicate, this reports "no violation" even though the element itself is not an object. Measured accepted by the pre-fix-round CHECK: `{"replicas":[[]]}` (the pure zero-observation write this amendment exists to close, surviving it), `[[],[]]`, `[[{"ref":"r1","restarted":true,"observedHash":null}]]` (worse: `Evidence.readResolutionEvidence` cannot even read this back), `[{<well-formed>}, []]`, and the `restart` mirror. **Critical 2 — the identical escape defeats the hash-format predicate:** `observedHash: []`, `["sha256:0123456789abcdef"]`, `[null]` all measured accepted, none expressible in Java and all three making the reader throw. **The fix:** three new `jsonb_path_exists(…, silent => true)` predicates that test the candidate's own `.type()` **before** any filter runs — `.type()` reports an item's raw type, including `"array"`, and does not auto-unwrap, so filtering on that scalar string (`$.replicas[*].type() ? (@ != "object")`, its `observedHash` counterpart, and the `restart` equivalent) closes both escapes without the filter-on-array hazard. Predicate counts move from four/three to **six/four**; verified leg counts move from 13/8 to **53/20**, zero regressions. **A measured trap now stated in the design so a future editor does not walk into it: `strict` mode is not the fix.** Under `strict`, a missing `ref` on an object is a structural error rather than "no result", `silent => true` swallows that error into a NULL for the *whole* `jsonb_path_exists` call (not just the offending candidate), and `coalesce(…, false)` reads that as "no violation" — so `[{}]`, a missing `ref`/`restarted`, and an absent `observedHash` would *all* flip to ACCEPT under `strict`, a regression, not a fix. **Important 3 — this document's stated reason for the `coalesce` guard was wrong, and it is the root cause both Criticals share:** the text said the NULL `jsonb_path_exists` can return arises "when its `target` argument is itself NULL"; measured, the dominant source is `silent => true` suppressing an *internal structural error* (`jsonb_path_exists('{"replicas":[5]}', '$.replicas[*].keyvalue() ? (…)', '{}', true)` returns NULL; the same call without `silent` raises `jsonpath item method .keyvalue() can only be applied to an object`). `coalesce(…, false)` is therefore a **load-bearing fail-open** — "could not evaluate" becomes "accept" — defensible only because an independent clause rejects the same input by a path that cannot hit the same error; the design now states that **clause independence is a correctness requirement**, not implementation detail, naming which new predicate (the `.type()` checks, which cannot throw) underwrites which old one's (`.keyvalue()`'s) fail-open. **Important 4 — "measured equivalent" was asserted for a non-equivalence:** `ReplicaRef`'s `isBlank()` and the DDL's `like_regex "\\S"` are not equivalent; enumerated over U+0001–U+FFFD, Java's `Character.isWhitespace` recognizes PG's `\s` class plus four more characters (U+001C–U+001F), so a `ref` of solely one of those four is rejected by Java and accepted by the DDL — a one-sided containment, safe in the required direction (the DDL is never *stricter* than Java) but not an equivalence, and locale-dependent (PG's class resolves via the server collation; even under `C` locale it stays a subset of Java's). Corrected in both documents. **Minors, all fixed:** (1) the Java reader's required refusal now names its exact exception, `IllegalStateException("sm_data." + column + " does not hold JSON this build wrote")` (the same shape `Evidence.read()` already throws), so two implementers do not each invent a different one, with an explicit note that no existing test depends on the current coalescing (the change is additive); (2) "which well-formedness now refuses first" removed — `AND`-order is irrelevant to this CHECK, stated as such elsewhere in the same section, and there is no "first"; (3) the residual table's per-element-typing row, which answers the column heading "Why no DDL can hold it" with the predicates that now *do* hold it, gains a clarifying "Now it can, per `BE-D18`" lead-in (the `resolution_note` row above is the established precedent for this same repurposing). **Hashes, re-verified:** Phase 0 unchanged (`768e195b…`); Phase 1 design-block range unchanged in text (`4ecc4470…`, lines 266–572); all seven migration/bootstrap file md5s unchanged (no SQL touched by this task). `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: surefire **271**, failsafe **163 total / 161 executed / 2 skipped** — unchanged; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D18` stays **PROPOSED**. |
| 2026-09-09 | **`BE-D18` executed — R6 of the R18 repair wave.** `resolution_is_evidence` gains its ten `jsonb_path_exists(…, silent => true)` predicates (six `apply`, four `restart`) in place inside `apply_request`'s existing `CREATE TABLE`, in both `V002__apply_request.sql` and `eda-walking-skeleton.md`'s §3.1 SQL block, to identical text — no new migration. The predicates close the container-only escape the fourth review (R5) found (`{"replicas":[{}]}`, `{"restartsObserved":[null]}`), the missing-`observedHash`-key escape, both key-exactness levels (top-level and per-element), and the `lax`-mode array-unwrap escape R5 fix round 1 priced (a filter applied directly to an array-valued element auto-unwraps under `lax` mode; a dedicated `.type()` predicate, which never unwraps, closes it instead of reaching for `strict` mode, which reopens a worse hole). **Java side, the one change the design named and no other:** `Evidence.readResolutionEvidence` now splits the two cases the old code coalesced together — a `replicas` element's `observedHash` key entirely absent now throws `IllegalStateException("sm_data." + column + " does not hold JSON this build wrote")` (the same corruption-report shape `Evidence.read()` already uses elsewhere in this class), while a present JSON `null` continues to map to Java `null`. **Test obligations discharged:** `SchemaConstraintsIT` gains 13 new legs proving every one of the ten predicates' rejection classes by name (element malformation, absent `observedHash` key, unexpected element/top-level keys, hash-format bounds, and the array-unwrap escape for both `replicas` and `observedHash`, on both branches), plus a positive-control leg covering several replicas and a `ref` carrying quotes, a backslash, `=`, a space and a non-ASCII character; `EvidenceJsonTest` gains the matching Java-level negative and the present-null positive control. Two pre-existing fixtures (`GOOD_EVIDENCE` and the numeric-equality positive control) gained the now-mandatory `observedHash` key — fixture-only changes, neither test's meaning moved. **Two stale documentation spots corrected in the same edit, per the design's own instruction to do so:** `eda-walking-skeleton.md` now says "ten predicates" (not seven, a leftover from an earlier design round); the `strict`-mode trap paragraph's stated mechanism is corrected (measured on PG 14.24: a filter-predicate error yields `Unknown` and the item is simply not matched — `jsonb_path_exists` returns `false` outright, with or without `silent`; nothing is swallowed to NULL); and the two now-stale "evidence-shaped, not evidence-complete" claims in §3.1's SQL comment and §3.4's own `BE-D17` restatement are corrected to reflect that the CHECK is now element-complete. Every predicate and every new test leg was measured against a live PostgreSQL 14.24 container before landing, including a per-clause mutation table (each of the ten predicates removed in turn from a standalone copy of the CHECK, confirming which leg alone flips to ACCEPT when that predicate is missing). **The tracked DDL-replay baseline is deliberately left red** — `V002`'s CHECK text changed — per this task's own instruction and `evidence/ddl-replay/README.md` §"When the design's SQL changes"; re-recording it is a separate, later task. `mvn clean verify`: surefire **273** (was 271, **+2** new Java tests), failsafe **176 total / 174 executed / 2 skipped** (was 163/161/2, **+13** new `SchemaConstraintsIT` legs), zero failures/errors; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (untouched); `git diff --check` clean. `BE-D18` stays **PROPOSED** — landing the CHECK and its Java reader fix is not ratification. |
| 2026-09-09 | **R6 fix round 1: an external review mutated both the pre-`BE-D18` and the landed CHECK against every PRE-EXISTING clause, not only the ten new predicates, and found the same failure mode R3's Important 1 already fixed once — corrected in place, tests and prose only; `V002__apply_request.sql` and the design's §3.1 SQL block untouched.** Landing a stricter predicate beside older ones can make a legacy leg refused by the new predicate regardless of what the old clause says, silently leaving the old clause deletable with the whole reactor green. **Four clauses were unpinned this way:** apply's `stagedGeneration = to_jsonb(generation)` (`BE-D17`'s entire reason for existing — 4 legs, all carrying the element-incomplete `[{"ref":"r1"}]` the new well-formedness predicate now catches on its own); apply's `jsonb_typeof(replicas) = 'array'` and restart's `jsonb_typeof(restartsObserved) = 'array'` (1 leg each, a STRING `"r1"` value also caught by the new element-type-check predicate via `lax` mode's `[*]` auto-wrap); and restart's `NOT jsonb_exists(resolution_observed, 'stagedGeneration')` (1 leg, now **permanently** subsumed by the top-level key-set predicate — any restart document carrying that key necessarily carries a top-level key outside `{restartsObserved}`, so no leg can ever pin it again; kept as defence-in-depth, documented as such, not deleted). **Restoration:** six legs element-completed or changed from a bare string to a bare well-formed object (the latter auto-wraps to a single well-formed JSONPath item under `lax` mode, so none of the six new predicates touch it, while `jsonb_typeof` — which reads the raw value, never through `[*]` — still sees it is not an array), measured against a standalone table on a live PostgreSQL 14.24 container: each pre-existing clause removed in turn from the full CHECK, confirming the fixed leg (and only the fixed leg) flips ACCEPT, and confirming the restart `jsonb_exists` clause truly cannot be revived by any leg. Three of the four unpinned clauses are restored to their original pin counts; the fourth (restart's `jsonb_exists`) is left permanently unpinnable, documented rather than silently dead. **Also fixed:** a self-correction in `anApplyResolutionsStagedGenerationIsCheckedNumericallyNotTextually`'s own javadoc, which had stated the inverse of what this review found; a newline-carrying `ref` added to the positive-control legs (Minor 3 — a coverage gap given `like_regex "\\S"` and the design's own U+001C–U+001F discussion, measured accepted); one sentence in `eda-walking-skeleton.md` §3.4 noting the `jsonb_exists` clause is now subsumed, not merely untouched; this document's `BE-D18` row updated to say the CHECK and the Java change are landed (commit `56119a4`) rather than "not yet made." **Cross-cutting lesson recorded:** `docs/design/common/lessons.md` L20 — a new, stricter predicate can silently unpin the old ones beside it, and a green suite cannot tell you this happened; re-run the per-clause mutation over pre-existing clauses too, every time a CHECK gains one. No SQL changed: `V002` and the design's §3.1 SQL block are untouched by this commit (only test fixtures, `decisions.md`, prose and `lessons.md`). `mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — all unchanged from the R6 baseline, zero failures, zero errors; `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. DDL-replay baseline untouched, stays red for the next task. `BE-D18` stays **PROPOSED**. |
| 2026-09-09 | **R7, closing `BE-D18`'s repair wave: durable register history for R6 and its fix round, three residual fixture defects, then the DDL-replay baseline re-record — two commits, no production SQL change.** **Commit 1 (`3a5d423`, `decisions.md` and test fixtures/prose only):** the register had carried no changelog row for R6 (`BE-D18` executed, `56119a4`) or R6 fix round 1 (`22ebd61`) — every prior round of this repair wave had its own dated row, these two did not, and the register's only pointer to the per-clause-mutation evidence was `r6-report.md`, gitignored and not in the repository. Two changelog rows are added above, carrying the substance directly (the ten predicates landing in `V002`/design §3.1, the `Evidence` reader change, and, for the fix round, the four pre-existing clauses `BE-D18`'s own landing silently unpinned, which three were restored to and which one is permanently unpinnable), replacing the `r6-report.md` citation with the numbers themselves. Also: `jsonb_typeof(resolution_observed) = 'object'` is a second logically-dead clause — unpinnable in principle (a non-object document makes every `->` lookup NULL, which the apply generation clause or the restart is-array clause refuse anyway) and unrelated to `BE-D18` (it was unpinned before the amendment too) — given the same defence-in-depth note, in the same "Preserved semantics" idiom, as the restart `jsonb_exists` clause just received; `aRestartResolutionWithAnInventedStagedGenerationIsRefusedByResolutionIsEvidence`'s `[{"ref":"r1"}]` fixture, which the element well-formedness predicate refuses on its own regardless of `stagedGeneration`, is element-completed exactly as the other five `BE-D17`/`BE-D18` legacy legs were in R6 fix round 1 (changes no pin — the clause it targets is permanently unpinnable — but closes the same L20 tell); and R6 fix round 1's own retarget of restart's non-array `restartsObserved` leg (STRING `"r1"` → a bare well-formed object) had left the restart branch with no STRING-shaped leg at all, while the apply mirror kept its own — restored. No SQL changed: `V002__apply_request.sql`, the other five migrations, and both `design-block.json` hashes/line ranges are unchanged and reconfirmed. `mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — both unchanged from the R6 fix round baseline. **Commit 2 (`de8a6aa`, `evidence/ddl-replay/` and `ddl-execution-pg14.md` only):** re-records the baseline `BE-D18`'s landing (`56119a4`) and its fix round (`22ebd61`) had both deliberately left red. A plain run against the checked-in manifest reported **twelve differences**, all traced to `BE-D18` and nothing else: the `phase1` md5 (the ten predicates' text) and eleven behavioural differences tracing to five legacy legs whose shared exemplars carry bare-string `replicas`/`restartsObserved` elements (`["r0","r1"]`, `["r0"]`) — element-incomplete under the new well-formedness predicate. One of the five — `(j) REFUSE: bogus resolution_outcome` — had its violated constraint **name** actually change (`resolution_known` → `resolution_is_evidence`, PostgreSQL sorting the latter first): the one leg needing a retarget decision, resolved by element-completing it, restoring `resolution_known`'s isolation. The other four are fixture bugs, not retargets: two `(j/I6)` positive controls and the `(j) POS` leg flipped `OK` → `REFUSED`, and because the POS leg is what resolves `c0000001` out of `apply_inflight_fence`, its failure cascaded into six more differences (a SETUP leg newly refused by the fence, and five restart-branch REFUSE legs flipping `REFUSED` → `OK` because their target row, `c0000031`, was never inserted). All five root-cause legs element-completed; the cascade resolves once the root cause does — the same L20 pattern found here for the first time in the DDL-replay harness's own independently-tracked manifest. **37 new legs added:** a dedicated SETUP fixture pair (apply `c0000041`, restart `c0000042`), 19 apply-branch REFUSE legs plus a POS (element type-check, well-formedness, key-set at both levels, hash format, the array-unwrap escape for both `replicas` and `observedHash`), 11 restart-branch REFUSE legs plus a POS (the restart mirror, including `{"restartsObserved":[null]}` — the escape `BE-D18` exists to close), and 4 extra positive controls (valid 16-hex/128-hex hashes, multiple replicas, multiple restart observations). Every new/retargeted leg matched its expected value on the first `--record-candidate` pass — `diff -u` against the tracked `legs.json` was empty; only `design-block.json`'s `phase1` entry changed. Re-run from the checked-in artefacts: `NO DRIFT`. **Hashes:** Phase 0 unchanged (`768e195b…`, lines 228–265); Phase 1 `4ecc4470…` → `a4d51cf2…` (lines 266–572 → 266–610, the ten predicates' +38 lines); all seven migration/bootstrap file md5s unchanged (no SQL touched — `BE-D18`'s SQL landed in `56119a4`/`22ebd61`). `legs.json`: 202 → 239 entries (197 → 234 SQL legs: 152 refused / 82 ok, up from 122/75); SQLSTATE distribution recomputed from `legs.json`: 84× `23514` (was 54, +30 — exactly the new refusals), the other six SQLSTATEs unchanged. `ddl-execution-pg14.md` gains a new Run 6 section (Run 5 down through Verdict renumbered, matching this file's own established renumbering precedent); the harness README's leg/entry counts, SQLSTATE distribution and reference-run commit/date are updated to match. `mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — both unchanged. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `test_paths.py` 11 tests green; `git diff --check` clean. `BE-D18` stays **PROPOSED** — re-recording evidence is not ratification. |
| 2026-09-09 | **R8, closing the final independent review's remaining findings against this repair wave: two DDL-replay legs that had stopped isolating their named clause, found by a review the automated re-record could not have caught — no SQL change, evidence and this row only.** Two legs' `REFUSED` status and `resolution_is_evidence` signature never moved across `BE-D18`'s landing, so R7's `--record-candidate` diff had nothing to flag, yet each had quietly become multi-cause: **`(j) REFUSE: MISSING resolved_by`** carried `{"stagedGeneration": 7, "replicas": ["r0","r1"]}` against `c0000001` (`generation = 1`) — measured **triple-cause** (supplying `resolved_by` alone still refuses; additionally correcting `stagedGeneration` to `1` alone still refuses, because the bare-string `replicas` elements remain). **`(j) REFUSE: apply resolution with a MISMATCHED stagedGeneration, otherwise fully evidenced`** carried `{"stagedGeneration": 99, "replicas": ["r0","r1"]}` — measured **double-cause** (removing the mismatch alone still refuses, same reason); its label was the one this repair wave had already deferred once, on the grounds that fixing it needed a full `--record-candidate` cycle, and R7 performed that cycle without sweeping it. Both element-completed (well-formed `replicas`) while keeping the defect each names — measured, on a live PostgreSQL 14.24 container, to refuse solely for that defect and to succeed the moment it too is corrected; `(j) REFUSE: apply resolution with a MISMATCHED stagedGeneration…`'s label was already accurate once fixed and needed no wording change. **`(j) REFUSE: stagedGeneration as a STRING`** was checked for the identical pattern (`replicas: ["r0"]` alongside the string-typed generation it names) and found to have it — fixed the same way, keeping `stagedGeneration` as the string `"7"`. The three `resolution_note` privilege legs sharing this row were checked too: all three raise `42501` — a column-privilege check Postgres evaluates before any `CHECK` — regardless of `resolution_observed`'s shape, measured by fixing every other defect and confirming the identical `42501`; their recorded behaviour is unaffected, but their `replicas` fixtures were element-completed anyway since one label ("the LEGAL resolution UPDATE") described a row they did not actually contain. Followed by the harness's own re-record procedure (`evidence/ddl-replay/README.md` §"When the design's SQL changes"): `--record-candidate` reported **0 differences** — every one of the six legs' `status` and `signature` matched the tracked manifest on the first pass, confirming the fix changed no recorded value, only `sql` text; `legs.json` stays at 239 entries and `design-block.json` is untouched (no SQL changed — `V002__apply_request.sql` and the design's §3.1 SQL block are byte-identical to `de8a6aa`). `docs/design/backend/evidence/ddl-execution-pg14.md` gains an addendum under Run 6 recording the six legs' before/after and the measured proof; `reference-run/log-j.txt` is refreshed (its SQL/DETAIL text is the only tracked evidence that was stale — every other difference between successive runs of this harness is `now()`-derived timestamps, expected and not asserted upon). `mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — both unchanged, since no code or CHECK changed. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py` 11 tests green; `git diff --check` clean. `BE-D18` stays **PROPOSED** — a precision fix to an evidence fixture is not ratification. |
| 2026-09-09 | **R9, closing `BE-D18`'s repair wave for good: the two legs R8's own report found and flagged but left unfixed, plus a full sweep of `legs.json` for the same class, per `L20`'s instruction to sweep the class rather than the cited instances — no SQL change, evidence and this row only.** `docs/design/backend/evidence/ddl-execution-pg14.md`'s new "Addendum 2" carries the full sweep table (every leg examined, its refusing clause(s), single-cause or confounded); summarised here. **Closed, the two R8 flagged:** `(j) REFUSE: replicas as a NUMBER (not an array)` and `(j) REFUSE: replicas EMPTY array`, both carrying the identical `stagedGeneration: 7` filler against `c0000001` (`generation = 1`), the same row R8's own six did too. The EMPTY-array leg is fully fixable (`stagedGeneration` corrected to `1`, now single-cause — and, incidentally, a byte-identical duplicate of the adjacent BE-D17 leg already built for this exact proof). The NUMBER leg is **not**: any non-array, non-object JSON scalar auto-wraps under `lax` mode into one non-object element, which independently trips the element-type and well-formedness predicates regardless of the array-type clause the label names — the same structural shape as the already-documented restart `jsonb_exists` dead clause, not a fixable fixture defect. Its `stagedGeneration` filler is corrected to `1` (removing the *unrelated* equality-clause entanglement) and its label is rewritten to disclose the remaining, unavoidable array-shape-family entanglement rather than claim an isolation it cannot have. **Three more found by the sweep, outside the six R8 or its own report ever named:** (1) `(j) REFUSE: apply resolution, replicas NOT an array, stagedGeneration MATCHING generation` and its restart mirror `(j) REFUSE: restart resolution, restartsObserved NOT an array` were both confounded by a bare-STRING `"r1"` filler that this repair wave had already fixed once, in the **Java** `SchemaConstraintsIT` suite (R6 fix round 1's "changed from a bare string to a bare well-formed object") — but the parallel DDL-replay legs were never touched at that commit and stayed confounded. Fixed identically here: `replicas`/`restartsObserved` becomes a well-formed OBJECT, which `lax`-mode auto-wrap treats as a single well-formed element satisfying every element-level predicate, while `jsonb_typeof` still correctly reports it as not an array — both now isolate the array-type clause alone, measured. (2) `(j) REFUSE: restart resolution with an INVENTED stagedGeneration` is the specific leg R6 fix round 1's own text already named ("restart's `NOT jsonb_exists(...)` — 1 leg — permanently subsumed... kept as defence-in-depth, documented as such") — but that disclosure lived only in this register's prose, never in the harness's own evidence doc or the leg's own label, which still read as a clean isolation claim. Relabelled to say so directly; no fixture change, since the entanglement (any `stagedGeneration` key is itself a disallowed top-level key on a restart resolution) cannot be isolated by any input. (3) Outside group `(j)` entirely: `(h) REFUSE: state outside the enum -> state_known` also violates `terminal_has_timestamp` — `'TOTALLY_BOGUS'` sits outside both `state_known`'s enum *and* `terminal_has_timestamp`'s three-state exemption list, so the CHECK also demands a `terminal_at` the fixture never supplied; fixed by adding `terminal_at = now()`, the same repair every other terminal-state `(h)` leg already uses. And `(h) extra: op_known (unknown operation)` is refused by `apply_has_generation`, not `op_known` — `'nuke'` matches neither `'apply'` nor `'restart'`, but `generation`/`strategy` are still present, so `apply_has_generation` and `lifecycle_has_strategy` both fire too, and PostgreSQL reports whichever sorts first lexically (`apply_has_generation`); the recorded `status`/`signature` were **already correct** — this was a label-only defect. The adjacent leg (89) already supplies the clean, single-cause `op_known` exemplar, so 88 is relabelled to disclose the co-firing rather than duplicated. **No fourth batch:** every other leg in the manifest (all of groups `(t)`, `(t-owner)`, `(a)`–`(e)`, `(f)`, `(g)`, `(i)`, `(m)`, `(k)`, `(l)`, `(o)`, `(p)`, `(u)`, `(n)`, `(q)`, `P1`–`P30`, and every `(j)`/`(h)` leg not named above) either isolates its named clause cleanly, measured against a live PostgreSQL 14.24 container — batched boolean-expression evaluation for the plain scalar CHECKs, direct `jsonb_path_exists`/`jsonb_typeof` evaluation against the leg's own JSON literal for `resolution_is_evidence`'s sub-clauses, neither requiring a row-state experiment per clause — or was already honest about firing more than one (`(h)` 95/96's own label, the `BE-D18` element-family legs' three deliberately-overlapping pairs, and every leg whose label describes a compound scenario rather than claiming single-clause isolation). Seven legs changed in total (76, 88, 113, 114, 128, 131, 134): five by `sql` repair, two (88, 131) by label only. `--record-candidate` reported **0 differences** — every changed leg's `status`/`signature` matched the tracked manifest on the first pass, confirming the repairs moved no recorded value, only `sql`/`label` text; `legs.json` stays at 239 entries and `design-block.json` is untouched (byte-identical to `de8a6aa`). Re-run from the checked-in artefacts: `NO DRIFT`. `reference-run/log-h.txt` and `log-j.txt` refreshed, the only two tracked logs whose label/SQL text moved. `mvn clean verify`: surefire **273**, failsafe **176 total / 174 executed / 2 skipped** — both unchanged (one transient, unrelated race-assertion failure on the first run, green on immediate re-run, not one of the two named flakes). `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py` 11 tests green; `git diff --check` clean. `BE-D18` stays **PROPOSED** — a class sweep is not ratification. |
| 2026-09-09 | **`BE-D19`/`BE-D20` added (design-first repair, S1 of a bounded three-stage repair) — two MAJOR defects a post-handover external review found, both verified by measurement on live PostgreSQL 14.24 before drafting either decision.** `BE-D19`: `ReplicaRef`'s `isBlank()` and `resolution_is_evidence`'s `like_regex "\\S"` are not equivalent — PG's `\s` excludes U+001C–U+001F, Java's does not — so a raw write via `sm_dp_runtime`'s existing `UPDATE` grant could commit a `ref` of a lone such codepoint, which then throws on read; `BE-D18`'s own "one-sided containment is the safe direction" conclusion reasoned only about the write direction and is flagged, not edited in place, superseded in part. Both sides now share `ValueGrammar.VALUE` (`^[A-Za-z0-9._:/@+-]{1,128}$`), narrowing what `ref` accepts **[WITHDRAWN by the R1 fix round row below: `ValueGrammar.VALUE` failed two primary-source-verified disqualifying facts — Kubernetes names run to 253 characters past its 128-character cap, and systemd escaped unit names carry a literal backslash its class excludes — and was replaced by a new, dedicated grammar, `ValueGrammar.REPLICA_REF`]** — two literals in `SchemaConstraintsIT`'s existing hostile-ref positive control (a quotes/backslash/space/`=`/café `ref`, and a newline-carrying `ref`) move from accepted to refused; no CJK literal was found in the current suite, correcting a since-superseded description of the defect. `BE-D20`: `AttemptService.open`'s only database fence, `UNIQUE (intent_id, attempt_no)`, refuses a same-number collision only — once one attempt commits, a second opener computing a different number lands a second OPEN row with no violation, measured deterministically (two rows with `outcome IS NULL` for one intent, no race needed). A new, independent partial unique index, `attempt_open_fence ON apply_attempt (intent_id) WHERE outcome IS NULL`, closes it (measured: refuses the second open, still allows the legal retry once the prior attempt closes); the engine names both indexes explicitly rather than conflating them, and two non-deterministic barrier tests (`AttemptIT`, `EngineTransactionBoundaryIT`) are marked for deterministic replacement rather than continued "re-run if it doesn't collide" tolerance. Neither decision edits `BE-D13`/`BE-D17`/`BE-D18`'s text in place, per `I3`; both cross-reference and flag instead, with a bracketed pointer added to each predecessor's one-line register row. **This task touches design and the decision register only — no SQL, no Java, no test, no `contracts/` or `evidence/` file — and the design's protected §3.1 `` ```sql `` block, `V002__apply_request.sql` and `V005__apply_attempt.sql` are byte-for-byte unchanged**, verified: the seven tracked md5s (`768e195b…` phase0, `28c29fa5…` V001, `a4e9829d…` V002, `ea69e1fa…` V003, `5a1f041b…` V004, `04914626…` V005, `9d6da805…` V006) and both design-block hashes (phase0 `768e195b…` lines 228–265, phase1 `a4d51cf2…` lines 266–610) are unchanged from this task's base commit. `BE-D19`/`BE-D20` are **PROPOSED**; both amendments state their own exact DDL/Java text, executable-DDL and Java obligations, and DDL-replay/landing obligations for the next task (S2) to execute — this task leaves the DDL-replay gate exactly where it stood (green, `NO DRIFT`), not red, because no tracked SQL changed. |
| 2026-09-09 | **`BE-D19` R1 fix round: the developer withdrew the framing that named `ValueGrammar.VALUE` a "leading candidate" and required it be proven, not assumed, against current-and-near-term Docker/systemd/Kubernetes identifier rules before adoption — it failed on two independently disqualifying, primary-source-verified facts, corrected in place (design and decisions only; the SQL block and all nine hashes unchanged).** **Length:** Kubernetes RFC 1123 DNS subdomain names (Pod/Deployment/Service names) run to 253 characters, verified against [kubernetes.io/docs/concepts/overview/working-with-objects/names](https://kubernetes.io/docs/concepts/overview/working-with-objects/names/) — past `VALUE`'s 128-character cap, and `ReplicaRef`'s own javadoc already lists a pod name as a documented current use, not a hypothetical. **Backslash:** `systemd.unit(5)` states unit-name prefixes may contain a literal `\`, verified against [man7.org/linux/man-pages/man5/systemd.unit.5.html](https://man7.org/linux/man-pages/man5/systemd.unit.5.html) (which also gives the 255-character unit-name maximum this fix round's new bound adopts) — `systemd-escape`'s output for an arbitrary device/mount path legitimately carries one, and `VALUE`'s class has no `\` at all. **The withdrawn `VALUE` is replaced, not repaired: a new, dedicated constant, `ValueGrammar.REPLICA_REF = ^[A-Za-z0-9._:/@+\\-]{1,255}$`** (one escaped literal backslash, length to 255) — `VALUE`/`HASH` are untouched, preserving their backslash-excluding `I6` shape-control role for `RuntimeRef`/`ErrorClass`, which this fix round has no reason to weaken. Verified against primary sources for every runtime named: Docker container names (`moby/moby`'s `daemon/names/names.go`, fetched directly since Docker's own CLI reference states no grammar) and image digests/tags (`distribution/reference`'s `regexp.go`); systemd unit names as above; Kubernetes RFC 1123 subdomain/label names. **Empirically measured, not merely derived, in this session:** a throwaway `postgres:14` container (`PostgreSQL 14.24`, matching this project's pinned floor) and a standalone `java.util.regex.Pattern` were run against an identical 17-case matrix and produced identical accept/refuse results for every case, including the full apply-branch well-formedness predicate embedded with its surrounding type/restarted/observedHash conjunction, not merely the isolated bracket class — confirming both the two previously-disqualifying cases now accept (a >128-character Kubernetes-shaped identifier; a backslash-carrying systemd-escaped-name fragment) and every existing refusal still refuses, including both of `SchemaConstraintsIT`'s hostile-ref literals (unaffected — they carry other excluded characters besides the backslash the new grammar admits) and the U+001C/U+001F parity negatives this whole repair exists for. The exact SQL/jsonpath source text needs **four** backslash characters, not the two a naive read of the existing `\\S` precedent might suggest — verified via PostgreSQL's own documented rule that a `like_regex` pattern is a jsonpath string literal requiring backslashes doubled (one layer) *and* the regex engine's own bracket-escape (a second layer), with PostgreSQL's `standard_conforming_strings` default (`on` since 9.1) confirming the outer SQL string adds no third layer — both facts verified against `postgresql.org/docs/14/{sql-syntax-lexical,functions-json}.html`, not reasoned from the existing example alone. The Java source text needs the same four backslash characters, confirmed via a standalone `Pattern.compile` run. A standing rule was added to the design's obligations: a test result must never be filed as an environmental flake without first explaining why the observed production behavior could not have caused it. **Not touched:** `BE-D20`, which the grammar correction does not affect. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: unaffected (no code changed by this fix round either); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D19` stays **PROPOSED**. |
| 2026-09-09 | **Fix round on the `BE-D19`/`BE-D20` S1 draft: an independent review found six defects — a reversed factual claim, an unrecorded severity change, a documentation obligation short by one site in one case and understated in another, and a changelog row stating a withdrawn grammar in the present tense — corrected in place, design and decisions only; `contracts/`, `evidence/`, all Java/SQL and the protected §3.1 SQL block untouched.** **Important 1 (`BE-D19`):** the withdrawn draft's claim that `apply_step_intent.replica_ref`'s missing character-content CHECK is "a related but out-of-scope gap this repair does not widen into" was backwards — measured against `V004__apply_step_intent.sql:8` (`replica_ref text NOT NULL`, no content CHECK), `:26` (`GRANT SELECT, INSERT … TO sm_dp_runtime`) and `StepIntentRepository.java:74` (`new ReplicaRef(rs.getString("replica_ref"))`), tightening `ReplicaRef`'s constructor to `REPLICA_REF` enlarges that column's poison-row set from "blank only" to everything outside the new class (a space, non-ASCII, CJK, 256 characters) via the same read-back constructor `BE-D19` is tightening. The design now states this honestly — the repair **does widen** the sibling gap — and records it **sized and deliberately deferred** (out of `BE-D19`'s own binding criterion, `resolution_is_evidence`/`readResolutionEvidence`, and defensible because the column is INSERT-only for the runtime role with no UPDATE/DELETE grant, so the poison path needs a bypass insert rather than a standing rewrite surface) rather than resolved; a mirrored one-clause note lands on this file's own `BE-D19` row. **Important 2 (`BE-D20`):** a new watch item records that `AttemptService.open`/`close` stamp `started_at`/`ended_at` from the injected JVM `Clock`, not the database, and that cross-node wall-clock drift after a failover or a single-node clock step (`chronyc makestep`, `ntpd -g`, a snapshot restore) can make `ended_at < started_at`, refused by `attempt_time_ordered` (`V005__apply_attempt.sql:23`); today that failure leaves a self-healing wedged-open row (the next `open()` still lands, per defect B), but once `attempt_open_fence` lands the same failure makes every subsequent `open()` for that intent refuse permanently — a severity change the fence introduces, not a reason to withhold it. The candidate remedies (a `greatest()` guard, a DB-clock `ended_at`, or a relaxed `attempt_time_ordered`) are named as a future decision's, explicitly not S2's to improvise; `BE-D20` proceeds regardless — the fence is strictly better than no fence. **Minor 1:** `BE-D20`'s landing mechanism named only the index, the `Constraints` constant, `namesAnyOf` and the tests as going stale; three javadoc sites also do — `AttemptService.java:29-33`'s class javadoc (*"What does is `UNIQUE (intent_id, attempt_no)`… the guarantee"*), its `:71-72` `@throws ConcurrentAttempt` doc, and `ConcurrentAttempt`'s own `:127` javadoc — added as S2 obligations, matching `BE-D19`'s own "two"→"three" idiom; no Java edited by this task. **Minor 2:** the `24 → 25` instruction for `SketchMirrorTest` named only the one assertion that fails first; `24` actually appears **four** times (`SketchMirrorTest.java` lines 27, 56, 57, 58 — the class javadoc plus the assertion and its two-line failure message), all four now named; confirmed, not merely asserted, that `RefusalShapesIT.java:173`'s own, unrelated `24` (`pg_constraint`, `con.contype = 'c'`, CHECK constraints only) does not move, since a plain `CREATE UNIQUE INDEX … WHERE …` is recorded in `pg_index`/`pg_class`, never `pg_constraint`. **Minor 3:** this file's own `2026-09-09` row adding `BE-D19`/`BE-D20` (above) states the withdrawn `ValueGrammar.VALUE` claim in the present tense, ahead of the R1 fix round row that withdraws it; per this register's append-only practice that row is not rewritten — a bracketed forward pointer is inserted immediately after the withdrawn clause instead, the same device this file already uses for the `BE-D13`/`BE-D18` predecessor rows. **Item 6 — the measured build state, the strongest evidence either decision has:** the review's own `mvn clean verify` at this task's base commit (`e577f2a`) went **BUILD FAILURE** — `EngineTransactionBoundaryIT.aConcurrentOpenInsideTwoCallersTransactionsNeverSurfacesAsUnexpectedRollback` failed `expected: <1> but was: <2>` at line 185, both callers succeeding with no constraint violation at all (surefire 273/0/0/0; failsafe 176 total/174 executed/2 skipped/1 failure) — defect B reproducing live at HEAD, not noise, now recorded in `BE-D20`'s own body per its obligation 4 so it is never filed as a flake; the S1 report's earlier green run of the same test was a lucky interleaving, not a property. **Verified unchanged by this task:** the seven tracked migration/bootstrap md5s (`768e195b…` phase0, `28c29fa5…` V001, `a4e9829d…` V002, `ea69e1fa…` V003, `5a1f041b…` V004, `04914626…` V005, `9d6da805…` V006) and both design-block hashes (phase0 `768e195b…` lines 228–265, phase1 `a4d51cf2…` lines 266–610); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. `BE-D19`/`BE-D20` stay **PROPOSED** — a review found these six items; nothing here is an approval. |
| 2026-09-09 | **S2: `BE-D19` and `BE-D20` land in the migrations, the design's §3.1 SQL block and the Java — three commits, the first production-SQL change of this repair.** Every prior round of this wave carried its own dated row and the landing itself did not; recorded here rather than left to S3's evidence row to imply. **`4ecf5e7` (`BE-D19`):** `ValueGrammar.REPLICA_REF` (`^[A-Za-z0-9._:/@+\\-]{1,255}$`) replaces `ReplicaRef`'s `isBlank()`, and the matching element well-formedness predicate lands in both operation branches of `V002__apply_request.sql` (md5 `a4e9829d…` → `c02ca25e…`). The pattern's backslash count is context-dependent and load-bearing — **4** literal backslash bytes in the SQL/jsonpath source and **4** in the Java source, both verified byte-by-byte after landing, since a silent collapse to 2 drops only the systemd case while the hyphen keeps working and nothing else visibly breaks. Re-measured on a live PostgreSQL 14.24 container and a standalone `java.util.regex.Pattern` against the design's 17-case matrix: identical accept/refuse on every case. `SchemaConstraintsIT`'s two hostile-ref literals move from accepted to refused as the design predicted, and a search re-confirmed they are the only two that flip (the two whitespace literals are already refusal fixtures; no CJK literal exists anywhere in `backend/dp-core/src`). **`e1b8490` (`BE-D20`):** `CREATE UNIQUE INDEX attempt_open_fence ON sm_data.apply_attempt (intent_id) WHERE outcome IS NULL` in `V005__apply_attempt.sql` (md5 `04914626…` → `628453bd…`), `Constraints.ATTEMPT_OPEN_FENCE`, `AttemptService.open` classifying **both** fence names as `ConcurrentAttempt`, and the three javadocs that read the numbering UNIQUE as the guarantee (`AttemptService.java`'s class-level, its `@throws`, and `ConcurrentAttempt`'s own) corrected to name either index. **`0fd35c8`:** the identical mirror into the design's §3.1 SQL block (phase 1 `a4d51cf2…` lines 266–610 → `366c7d84…` lines 266–615; phase 0 unchanged), with `SketchMirrorTest`'s four `24` sites and its per-file vector `{1,6,9,2,3,2}` → `{1,6,9,2,4,2}` following the one new statement — the phase-1 count moves **24 → 25**, unlike `BE-D16`/`BE-D17`/`BE-D18`'s in-place edits, which all left it at 24. **Prove-it-fires, per guard, red then green:** removing `BE-D19` turns 5 of 14 `ReplicaRefTest` cases and 3 of 48 `SchemaConstraintsIT` cases red; removing `attempt_open_fence` turns exactly one test red — `AttemptIT.aSequentialSecondOpenIsRefusedByAttemptOpenFenceByNameAndARetryAfterCloseIsAccepted` — while **both concurrent-race tests stay green**, which is precisely why the defect survived: they cannot detect it. **`L20` unpinning audit** (57 live-suite fixtures × 19 clause-removed mutants on PostgreSQL 14.24, the trap that fired twice earlier in this wave): 17 of 19 clauses remain cleanly pinned and `BE-D19` introduced **zero** new unpinning. Two are permanently unpinnable, both pre-existing: restart's `NOT jsonb_exists(…,'stagedGeneration')` (already documented in R7) and — newly measured here — `jsonb_typeof(resolution_observed) = 'object'`, because any non-object value makes every `->` lookup return SQL NULL, which independently fails the apply generation/array clauses or the restart array clause, so no fixture can isolate it; structural to `BE-D17`/`BE-D18`'s CASE, not caused or fixable by `BE-D19`. **Determinism, stated precisely:** the two concurrent tests are now deterministic *by construction* — colliding openers are refused by the numbering UNIQUE, serializing openers by the fence — but on this machine the openers collided on every one of 16 observed runs, so the fence's serialize branch was never exercised *by those tests*; the sequential test, which needs no race, is what proves the fence, and both concurrent tests assert only that the loser's name is one of the two valid fences, never a fixed one. Three deviations, all declared: two `AttemptIT` fixtures that opened three attempts without ever closing one had their own setup refused by the fence (their setup *was* the defect class) and now close between opens, each test's original claim intact; `ConcurrentAttempt` gained a `constraintName()` accessor, required by the design's own instruction that the tests measure the observed name; and `SketchMirrorTest`'s per-file vector, a mechanical consequence covered by the design's own audit clause. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS**, surefire **287** (127 + 135 + 25, was 273), failsafe **180 total / 178 executed / 2 skipped** (was 176/174/2), zero failures — the increase is the new `ReplicaRefTest` plus four new integration methods, each accounted for. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean. This commit deliberately leaves the DDL-replay baseline **red** — `design-block.json` is read only by `replay.py`, never by Maven, so the build stays green while S3 re-records. `BE-D19` and `BE-D20` stay **PROPOSED** — implementation is not ratification. |
| 2026-09-09 | **S3: DDL-replay re-record for `BE-D19`/`BE-D20`'s landing (`4ecf5e7`/`e1b8490`/`0fd35c8`), which had deliberately left the baseline red — evidence only, no SQL/Java change.** A plain run against the checked-in manifest reported **eight differences**, all traced to the two amendments and nothing else: the `phase1` md5 (`a4d51cf2…` → `366c7d84…`, lines 266–610 → 266–615) and the phase-1 file leg's command-tag count (24 → 25, `BE-D20`'s one new statement); the `(s)` topology `expect_contains` fragment (`21 \| 21` → `22 \| 22`, the new index); and three `(i)` FK-refusal legs plus `(m) POS: attempt_no = 2 (a legal retry)` plus `(m) REFUSE: duplicate (intent_id, attempt_no)` all reporting `attempt_open_fence`/`23505` instead of their own named constraint. **The five `(i)`/`(m)` legs were pre-empted, not newly correct — repaired by inserting two SETUP legs, not by editing their own SQL or expectations, per `L20`.** Measured directly (isolated fresh-intent reproduction, then reconfirmed on the full manifest) that PostgreSQL evaluates a unique-index insertion before an `AFTER ROW` FK trigger fires, so the still-open `attempt_no=1` (never closed anywhere in the recorded sequence) pre-empted all three FK legs and the numbering-unique leg, and that the "legal retry" leg was never actually legal under the fence in the first place — `BE-D20`'s own obligation 2 requires it to be. Two `(m) SETUP: close attempt_no=N` legs, inserted around the affected legs, restore every one of the five to its *original*, byte-identical recorded `status`/`signature` — chosen over routing through a fresh intent because all five already share `e0000001`'s deliberate anti-collision numbering scheme (attempt_no 9/10/11/12–14), and a SETUP leg closing a fixture to a known state is this manifest's own established idiom. **`L20` sweep, run against the whole manifest, not only the five named:** every leg writing `apply_attempt` was traced — **22 at HEAD** (15 INSERT, 4 UPDATE, 2 DELETE and one TRUNCATE probe), a count an independent re-review re-derived and confirmed complete, correcting this row's own first figure of "twelve", which counted only the pre-repair INSERTs; one more — `P26 SETUP for P27` (`a0000030`, same intent, outcome unset) — was found unaffected for a *reason*, not by assumption: `(u) POS: the attempt-pruning DELETE half works under the runtime grant` (leg 200) deletes every `apply_attempt` row for tenant `1111…` before P26 ever runs. **The reason as first written named a row that is not open** — it said the DELETE clears "the still-open `a0000001`", but this repair's own inserted SETUP leg (98) closes `a0000001`, and leg 108 closes `a0000006`; corrected by an independent re-review's state simulation. The conclusion is unchanged and in fact holds for a stronger reason: after leg 108 nothing is ever open on intent `e0000001` again, so P26 is safe independently of the DELETE. Every other `ref`-content literal in the pre-existing manifest (`"r0"`/`"r1"`/`"r2"`, two blank values, one non-string `7` — five distinct literals, 61 occurrences) was checked against both the old `\S` pattern and the new `REPLICA_REF` class and found to sit outside the gap between them (blank/non-string cases are refused upstream of the regex by a different clause entirely) — confirmed both by this literal search and by the full run's own zero-drift result on every other `(j)`/`(j/I6)` leg. **20 new legs added**, both amendments' own stated obligations: 15 for `BE-D19` (a dedicated SETUP fixture pair, `c0000051`/`c0000052`; 7 apply-branch + 2 restart-branch `REFUSE`s — a lone U+001C, a lone U+001F, the hostile quotes/backslash/space/`=`/accented literal, a newline-carrying literal, the empty string, a CJK string, and the 256-character boundary — each `resolution_is_evidence`/`23514`; a chain-closing `POS` per branch; 3 standalone `POS` controls in the `(j/I6)` template — a 200-character Kubernetes-shaped ref, a systemd-escaped fragment carrying one literal backslash, and the 255-character boundary) and 5 for `BE-D20` (the two SETUP-close legs reused by the repair above, plus a dedicated fresh-intent `(m) REFUSE, NO RACE` leg proving `attempt_open_fence`/`23505` fires **by name** — not `apply_attempt_intent_id_attempt_no_key`, since the two numbers differ — and its `POS control` proving the retry-after-close case stays legal). Every one of the 20 new/repaired legs matched its expected value on the first `--record-candidate` pass (candidate written to `/private/tmp/be-d19-d20-candidate`, outside the repository); the only diff between the tracked and candidate `legs.json` was the phase-1 command-tag count, already expected. `legs.json`: 239 → 259 entries (234 → 254 SQL legs: 152/82 → 162/92); SQLSTATE distribution recomputed from `legs.json`: 93× `23514` (was 84, **+9** — `BE-D19`'s new refusals), 10× `23505` (was 9, **+1** — `BE-D20`'s dedicated fence-by-name leg; the five repaired legs restore their original names, net zero), the other five SQLSTATEs unchanged. `design-block.json`'s `phase1` entry and `docs/design/backend/evidence/ddl-execution-pg14.md` (new "Run 7" section, with the full sweep tables and the three pre-emption facts as measured; Run 6 down through Verdict renumbered, matching this file's own established renumbering precedent, including the two internal `§N` cross-references — `§8`→`§9`, `§2`→`§3` — that the renumbering itself would otherwise silently orphan) are updated to match; the harness README's leg/entry counts, SQLSTATE distribution and reference-run commit/date (now `0fd35c8`) are updated; `reference-run/log-im.txt`, `log-j.txt`, `log-phase1.txt` and `log-topology.txt` are refreshed (the only tracked logs whose content moved — every other cross-run difference is `now()`-derived timestamps, expected and not asserted upon). Re-run from the checked-in artefacts: `NO DRIFT`. `python3 -m unittest docs/design/backend/evidence/ddl-replay/test_paths.py`: 11 tests green. `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS** — surefire **287** (mesh-events 127 + dp-core 135 + arch-tests 25), failsafe **180 total / 178 executed / 2 skipped**, zero failures — unchanged from this task's base commit, since no code or migration changed. `./contracts/run-checks.sh --strict` ALL CHECKS PASSED. `git diff --check` clean; every touched file scanned for C0 control bytes (none found); no test container left running. **Not this task's to fix, flagged only:** `4ecf5e7`/`e1b8490`/`0fd35c8` — `BE-D19`/`BE-D20`'s own S2 landing commits — appear to carry no changelog row of their own recording what they landed, the same recurring gap this register's `evidence/ddl-replay/README.md` already names for `BE-D18`'s landing; this row covers only the re-record (S3's own charter), not a backfill of S2's obligation. `BE-D19`/`BE-D20` stay **PROPOSED** — re-recording evidence is not ratification. |
| 2026-09-10 | **`AttemptService.open` ordering defect fixed: the retry cap was checked BEFORE the intent's own open-attempt state, so a caller retrying against an intent whose FINAL allowed attempt was still open read `RetryCapExceeded` instead of `ConcurrentAttempt` — misclassifying a legitimate in-flight attempt as retry exhaustion, which can drive a step loop toward exhaustion while that attempt is still running.** Fixed with a new repository pre-check, `AttemptRepository.hasOpenAttempt` (an `EXISTS … WHERE outcome IS NULL`, tenant-scoped per `I5`), run in `open()` BEFORE the cap check against `AttemptRepository#count`; `RetryCapExceeded` is now raised only when the pre-check finds no open row AND the cap is genuinely spent. `attempt_open_fence` and `ATTEMPT_NO_UNIQUE` (`BE-D20`) are untouched and still the backstop for the race between the pre-check's own read and this call's insert — the pre-check is advisory, not a replacement for either index. `ConcurrentAttempt` gains a `Provenance` (`PRE_CHECK` vs `DATABASE_FENCE`) so a caller/test can tell "refused before any insert, no PostgreSQL name possible" from "refused by a named database fence"; `constraintName()` is `null` on the `PRE_CHECK` path rather than a fabricated or guessed name. **Text-only precision fix to `BE-D20`'s own body, and to `eda-walking-skeleton.md` §3.4 — `BE-D20` is not re-opened or re-decided:** both had framed the engine's classification as "both are named" / every loser's refusal carrying a `PgConstraintViolation`, true of `BE-D20`'s own two database fences but not of every `ConcurrentAttempt` the shipped method can now raise; both places now scope that language explicitly, with a forward pointer to this row, rather than being rewritten as if always true. No SQL, no migration, no new decision. **Tests:** two new `AttemptIT` cases (cap of one; cap of three with the final attempt left open, then closed) prove the corrected ordering, and the two existing non-deterministic concurrent-callers tests (`AttemptIT`, `EngineTransactionBoundaryIT`) are widened, not weakened, to accept either provenance for the loser while still asserting something concrete in each branch (a `null` name under `PRE_CHECK`, one of the two fence names under `DATABASE_FENCE`). **Prove-it-fires:** removing the pre-check's `throw` turned both new tests red with `RetryCapExceeded` where `ConcurrentAttempt` was expected (exact messages: *"an open attempt must be refused as ConcurrentAttempt even though the count (1) already equals the cap (1) — not RetryCapExceeded"* and the cap-of-three analogue), restoring it turned both green; verified by running the mutation and its reversion, not merely asserted. **Verification:** the focused `AttemptIT`/`EngineTransactionBoundaryIT` run (23/23, both provenances measured as `DATABASE_FENCE` on this run's concurrent races) and the full reactor `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS**, surefire **287** (mesh-events 127 + dp-core 135 + arch-tests 25, unchanged — the new coverage is integration-only), failsafe **182 total / 180 executed / 2 skipped** (was 180/178/2, **+2** — exactly the two new `AttemptIT` cases), zero failures. `./contracts/run-checks.sh --strict`: ALL CHECKS PASSED (unaffected — no SQL, no `contracts/` change). `git diff --check` clean; every touched file scanned for C0 control bytes (none found). The `I5` tenant-predicate guard (`TenantIsolationIT`) carries no hardcoded statement count to move, and none did. **[Corrected, not rewritten here, per this register's own append-only practice: this sentence answered only whether a hardcoded COUNT needed to move, a narrower question than whether the new query's tenant predicate was COVERED by any test at all — it was not, at this commit; `hasOpenAttempt`'s `tenant_id` predicate had no test protecting it, and the whole suite stayed green with it dropped. See the `2026-09-10` row below covering `979302a`, which found and closed exactly that gap.]** `BE-D20` stays **PROPOSED** — a wording precision fix is not a ratification. |
| 2026-09-10 | **`979302a` — the `I5` gap flagged in the row above, closed: `AttemptRepository.hasOpenAttempt`'s `tenant_id` predicate (added by `0b09be2`, above) had no test exercising its removal — the full dp-core suite stayed green with `tenant_id = :t` dropped from its `WHERE`, `TenantIsolationIT` included.** `TenantIsolationIT.tenantBCannotSeeCloseCountOrDeleteTenantAsAttempts` — the class's own dedicated cross-tenant probe on `apply_attempt`, whose fixture already gives tenant A a still-open attempt for exactly this purpose — is extended (and renamed `tenantBCannotSeeCloseCountDeleteOrDetectOpenTenantAsAttempts` to match) with `assertFalse(hasOpenAttempt(b, intentOfA.intentId()))` and the positive control `assertTrue(hasOpenAttempt(a, intentOfA.intentId()))`, so the method cannot pass the negative by always returning `false`. No new fixture; same idiom as the class's existing `count`/`latest` assertions beside it. **Prove-it-fires:** dropping `tenant_id = :t` from `hasOpenAttempt`'s `WHERE` turned this test red — `expected: <false> but was: <true>` at the new assertion — restoring it turned it green; measured, not merely asserted. **Verification:** focused `TenantIsolationIT` run 8/8 green before and after the mutation; full reactor `mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS**, surefire **287** (unchanged), failsafe **182 total / 180 executed / 2 skipped** (unchanged — a widened assertion, not a new test method); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED; `git diff --check` clean; C0 scan clean. `BE-D20` stays **PROPOSED** — untouched by this commit. |
| 2026-09-10 | **Independent review of `979302a` found three Important findings — the real one survives, narrowed rather than closed, plus two record/coverage gaps — and four cheap minors; all fixed together, this row.** **Important 1 — the two-statement snapshot window: the ordering fix in `0b09be2` was necessary but not sufficient.** `AttemptService.open` read `hasOpenAttempt` and `count` as TWO separate statements in one READ COMMITTED transaction; each takes its OWN fresh snapshot, so a transaction could be told "no open row" by the first and then count a row a concurrent committer landed in between, in the second — reaching `RetryCapExceeded` for an intent that, in fact, still had a legitimate attempt open, narrower than but the SAME species as the defect `0b09be2` set out to fix. Measured on live PostgreSQL 14: `SELECT EXISTS(...)` reads `false`, a concurrent session commits an open attempt, `SELECT count(*)` then reads the incremented total, while a single combined statement reads both facts consistently. **Closed by folding both facts into ONE statement, `AttemptRepository.openState`:** `SELECT count(*)::int AS spent, coalesce(bool_or(outcome IS NULL), false) AS has_open FROM sm_data.apply_attempt WHERE tenant_id = :t AND intent_id = :i`, returned as a new `AttemptRepository.OpenState(int spent, boolean hasOpen)` record; PostgreSQL takes one snapshot per STATEMENT, so both facts are now read at the same moment by construction, not by luck of timing. `hasOpenAttempt` is removed (its sole caller was `open()`, now replaced); `count()` itself is UNTOUCHED and still used directly by `TenantIsolationIT` and most of `AttemptIT`'s own assertions. One round trip fewer than the two-statement version. **Important 2 — the new query's `intent_id` predicate was itself unguarded, exactly as `tenant_id` was before `979302a`.** Pinned in `AttemptIT` (`anOpenAttemptOnOneIntentDoesNotFenceAFirstOpenOnAnUnrelatedIntentOfTheSameTenant`, a second intent under the SAME tenant/apply via the existing `lifecycleIntent()` fixture helper) and in `TenantIsolationIT` (extended with `openState(...).spent()`/`.hasOpen()` assertions on both sides, replacing the now-removed `hasOpenAttempt` calls). **Per-clause pin evidence for `openState`'s three clauses, each dropped in turn, red named, restored, green (inline here, per this row's own device, not left to an out-of-tree report):** dropping `tenant_id = :t` → `TenantIsolationIT.tenantBCannotSeeCloseCountDeleteOrDetectOpenTenantAsAttempts`: *"B's own read of openState.spent must be 0 too … ==> expected: <0> but was: <1>"*; dropping `intent_id = :i` → `AttemptIT.anOpenAttemptOnOneIntentDoesNotFenceAFirstOpenOnAnUnrelatedIntentOfTheSameTenant`: *"==> expected: <0> but was: <1>"*; dropping `outcome IS NULL` (replaced with `count(*) > 0`) → 5 errors in `AttemptIT` (`anOpenFinalAttemptIsConcurrentAttempt…`, `closeRecordsTheOutcomeAndLatestAndCountSeeIt`, `theCompactionPairFolds…`, `theFourthOpenUnderACapOfThree…`, `obligationMHasNoDatabaseArtifact…`), each *"ConcurrentAttempt intent … already has an open attempt; do not act — resume will read its row"* — matching the count the re-review's own measurement of the predecessor query reported. **Important 3 — this row, plus the `979302a` row above and the correction to `0b09be2`'s own row** (its closing sentence about the `I5` guard answered only whether a hardcoded COUNT needed to move, not whether the new query's predicate was COVERED — corrected in place per the bracketed-pointer device, not rewritten). **Four minors:** (a) `EngineTransactionBoundaryIT`'s comment on the reachable `setRollbackOnly` path narrowed to name it as `DATABASE_FENCE`-provenance-only — a `PRE_CHECK` loser never reaches `setRollbackOnly` at all, true before only by observed timing (12/12 runs), not by construction; (b) the "not vacuous either way" claim beside the widened concurrent-race assertions (`AttemptIT`, `EngineTransactionBoundaryIT`) corrected to say plainly that the `PRE_CHECK` branch's `assertNull` is guaranteed by `ConcurrentAttempt.preCheck`'s own construction and restates an invariant rather than exercising anything falsifiable, while the `DATABASE_FENCE` branch's `assertTrue` is the one live check; (c) a new deterministic test, `EngineTransactionBoundaryIT.aSequentialSecondOpenInsideACallersTransactionIsPreCheckAndTheCallerCommitsCleanly` — a caller transaction, `open()`, a SEQUENTIAL second `open()` caught inside, the caller committing cleanly — pins `PRE_CHECK` by construction, no race required, where before only the non-deterministic race tests ever exercised it; (d) one line added to `BE-D20`'s wedged-open watch item (`eda-walking-skeleton.md`) noting that a wedged intent at the cap boundary used to surface as `RetryCapExceeded` (pointing at `rollbackOrMarkIndeterminate`) and now always surfaces as `ConcurrentAttempt` — required, not a defect, but it removes the last classification by which such an intent could reach the exhaustion path. **Verification (this row's own, previously missing — the only row in this wave without one):** focused `AttemptIT`/`EngineTransactionBoundaryIT`/`TenantIsolationIT` run 33/33 green; full reactor `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS**, surefire **287** (mesh-events 127 + dp-core 135 + arch-tests 25, unchanged), failsafe **184 total / 182 executed / 2 skipped** (was 182/180/2, **+2** — the two new tests); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED in the working copy; `git diff --check` clean; every touched file scanned for C0 control bytes (none found); DDL-replay (`--out` outside the repository, no `--record-candidate`) `NO DRIFT` — this commit changes no SQL or migration. No SQL, no migration, no new `BE-D` decision; `BE-D20` stays **PROPOSED**. |
| 2026-09-10 | **Delta re-review of the row above: one Important remains — the fix to Important 1 had no guard — plus four cheap minors; fixed together, this row.** **Important — `openState`'s single-statement property was closed by construction and pinned by nothing:** the re-reviewer replaced `openState`'s body with the exact two-statement form it replaced (an `EXISTS` query plus a call to `count()`) and the full reactor stayed green (`surefire 127/135/25 (287)`, `failsafe 184 total, 2 skipped`, `BUILD SUCCESS`) — the fourth instance of the same species in this correction (`tenant_id` present but unpinned; `intent_id` present but unpinned; the pre-check present but not atomic; now atomicity restored but unpinned), each invisible to a green suite. **Closed with a structural guard, the same device `90cce61` used for the throw-freedom half** (`SchemaConstraintsIT.resolutionIsEvidenceNeverCallsJsonbArrayLength`, a text-level assertion against an installed definition, chosen because no behavioural permutation could catch a structural regression): here there is no installed database object to read, so the install-time text is swapped for `LogCapture` (this suite's own tool for "what did the JVM actually do", already used elsewhere in `AttemptIT`) — `AttemptIT.openStateIssuesExactlyOneSqlStatementSoItsTwoFactsCannotDriftApart` counts the `"Executing prepared SQL statement […]"` DEBUG lines `JdbcTemplate` logs for one `openState` call and asserts exactly one. Measured directly before writing it (not assumed): one such call logs exactly one such line, carrying `openState`'s own SQL verbatim. **Prove-it-fires:** reverting to the two-statement form turned it red — `"==> expected: <1> but was: <2>"`, naming both statements verbatim (the `EXISTS` and the `count(*)`); restoring turned it green. **Four minors, all substantiated and fixed:** (1) this row and the one above replace the circular citation to a non-existent report file with the per-clause red messages inline, matching the device the two rows above them already use; (2) the row above gained the Verification clause it was missing (see its own closing sentence, added by this row); (3) `count()`'s own `intent_id` predicate — pre-existing, not introduced by this wave, and, per a `grep` across `backend/*/src/main/java`, now a test-only query with zero production callers — is pinned rather than deleted, reusing the SAME fixture `anOpenAttemptOnOneIntentDoesNotFenceAFirstOpenOnAnUnrelatedIntentOfTheSameTenant` already built (intent 1 open, intent 2 unrelated, same tenant): `assertEquals(0, count(tenant, otherIntent))`, red `"==> expected: <0> but was: <1>"` when `intent_id` is dropped, green restored. Deletion was considered and rejected: `count()` is called from four test classes (dozens of sites), and removing test infrastructure on a reviewer's own judgment, inside a bounded correction whose theme is "add missing pins," is a larger and riskier change than adding the one predicate; a deliberate cleanup decision, if wanted, is separate work. (4) Two doc corrections in `AttemptRepository`: the `coalesce(bool_or(outcome IS NULL), false)` javadoc claimed the `coalesce` is what keeps `hasOpen` from reading NULL today — measured false (dropping the `coalesce` leaves the full module green, because `rs.getBoolean` already maps a SQL NULL column to `false` on its own); corrected to say the `coalesce` is kept anyway because it becomes load-bearing the moment the mapper ever moves to `rs.getObject(..., Boolean.class)`, which does not coerce NULL to `false`. And `count()`'s own javadoc said "the `bool_or` **this class** no longer needs" — `AttemptRepository` still needs `bool_or` in `openState`, thirty lines below; corrected to say `count()`'s own callers have no use for it, not the class. **Verification:** focused `AttemptIT`/`EngineTransactionBoundaryIT`/`TenantIsolationIT` run 34/34 green; full reactor `mvn -f backend/pom.xml clean verify`: **BUILD SUCCESS**, surefire **287** (unchanged), failsafe **185 total / 183 executed / 2 skipped** (was 184/182/2, **+1** — the new structural guard); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED in the working copy (a re-reviewer's bare-clone attempt could not reproduce this because `contracts/view-models/` had no installed `node_modules` there — environmental, not attributable to this commit; stated for the record, not silently omitted); `git diff --check` clean; C0 scan clean; DDL-replay untouched (no SQL changed by this row). No SQL, no migration, no new `BE-D` decision; `BE-D20` stays **PROPOSED** — a structural guard and four doc/record corrections are not a ratification. |
| 2026-09-11 | **`BE-D21` added (design-first, before the seam is built): the `RuntimeAdapter` signature `BE-D12` §2.1 prints is not implementable as written.** Plan 005's design review found both halves by attempting to write the first adapter. `ReplicaRef` cannot be widened to carry its `PlacementRef` — as built it is `record ReplicaRef(String value)`, its grammar mirrored into `resolution_is_evidence` by `BE-D19` and its `ref` key closed over by `BE-D18`'s per-element key-set predicates, so a second component would change an evidence wire shape the database now holds rows to — and the requirement it expressed (*adapters never see bare strings*) is met instead by `record TargetReplica(PlacementRef, ReplicaRef)`. And the rendered bytes have no parameter, so an adapter would have to render its own: a second renderer per runtime with no goldens, free to disagree with the `config_hash` the intent row bound before it was called under §2.2 rule 0 — closed by a caller-owned, self-checking `RenderedConfig`. Six operations, unchanged names, unchanged semantics table, unchanged no-`healthGate` ruling; parameter types and one parameter change. `BE-D12`'s text is flagged in §2.1, not rewritten (`I3`). The draft amendment policy would have permitted an in-place edit (nothing is built against §2.1 yet) and it is declined anyway: the seam is inherited by every future adapter and by the contract-test kit, so it needs an ID a reviewer can cite. Implementation is **not** gated on ratification — the same posture `BE-D16`–`BE-D18` carry; the gate that applies to plan 005 is G2, which this seam does not touch. Design-and-register only; not implemented. |
| 2026-09-11 | **`BE-D22` added (design-first): `BE-D12`'s sixth operation is two operations, and `BE-D13` §3.1's step enum already said so.** An external review of plan 005 measured that the plan's exhaustion path opened `ROLLBACK_ASSESS` with a null hash, called `rollbackOrMarkIndeterminate`, received a restorative result and landed `FAILED_ROLLED_BACK` — a restore journalled as an assessment, against §3.1's own *"a restore journals `ROLLBACK`, never `ROLLBACK_ASSESS`"* invariant. Every branch available to a single conflated operation is a defect: opening `ROLLBACK` after the destructive act breaks §2.2 rule 0 and leaves a crash unable to tell whether the restore happened, and inventing a `config_hash` puts a value in an audit column that describes nothing. The seam becomes seven operations — `assessRollback` (read-only, journals `ROLLBACK_ASSESS`, hashless) and `restore` (journals `ROLLBACK` with the target hash as `config_hash`, opened **before** the call) — with one `VERIFY` step per replica after a restore, which is where the per-replica restored evidence becomes durable; the values the two operations return are transient and no plan may call them the record. Resume is decidable at every boundary because the `ROLLBACK` intent carries the target hash. The one bounded case — a prior state of *absence*, which `hash_on_config_steps` makes unjournallable — is decided, not deferred: `Unknowable("rollback.target.absent")` → `APPLY_INDETERMINATE`. Amends `BE-D12` and `BE-D21` by cross-reference per `I3`. Design-and-register only; not implemented. |
| 2026-09-11 | **`BE-D23` added (design-first): the v1 apply engine is single-placement, because `apply_step_intent`'s identity is placement-blind.** `UNIQUE (tenant_id, apply_id, step, replica_ref)` with `placement_scoped_steps` pinning `replica_ref` to the literal `'@placement'` means one `apply_id` can hold at most one `PREFLIGHT` intent, so an instance-wide fan-out records the second placement's pre-flight as a retry of the first's; and replica-scoped steps keyed by `replica_ref` alone collapse two placements that share a label, raising `IntentMismatch` on a legitimate apply whose rendered configs differ. Measured against the shipped DDL, not inferred. An instance-wide apply is therefore **refused before admission**, persisting nothing — no request row, no intent, no attempt, no adapter call. Encoding a placement into `replica_ref` is explicitly forbidden: `BE-D19` grammar-locks that column on both sides and `BE-D18` closes over its values. The amendment that would fix it (a `placement_id` on the intent table, the UNIQUE and `placement_scoped_steps` widened, the fence and watermark interactions re-measured) is named and routed to the plan that revisits `sm_data`; it is not an adapter plan's to take. Amends `BE-D12` §2.1 and `BE-D21` by cross-reference per `I3`. Design-and-register only; not implemented. |
| 2026-09-14 | **`BE-D24` added (design-first): a remote config directory is not a DP-local `Path`.** The existing Docker representation collapsed workload-visible path and writer filesystem access. It now records the closed writer access mode: `LocalFilesystem(Path)` for Docker and `RemoteSftp(canonicalDirectory)` for SSH, with host identity and credentials confined to the adapter. The core receives neither a remote connection nor a path it can open locally. Amends `BE-D21` by cross-reference per `I3`; implementation is not ratification. |
| 2026-09-12 | **`BE-D22` corrected in place before any code was written (draft amendment policy: nothing is built against this family yet), on two defects an execution review found.** **(1) Classification.** The assessment returned `Diverged()` whenever replicas' hashes merely differed — which is the *ordinary* partial-materialize case (replica A on the new generation, replica B still on the old) and exactly what a rollback exists for. It now classifies against **journal-known values only**: the rollback target read back from the pre-image, and the `MATERIALIZE` intent's own `config_hash`, handed in as a new `intendedHash` parameter. A mixture of those two proceeds through the hash-bearing `ROLLBACK`; anything unreadable or a third unknown value is `APPLY_INDETERMINATE` with nothing overwritten. `Diverged()` leaves `RollbackAssessment` altogether — divergence is a lifecycle verdict the engine takes from its journal (`SM-D15`), never a config-hash comparison. **(2) Target enforcement.** `restore` now re-hashes the retained pre-image and compares it to `targetHash` **before writing**, reporting `rollback.preimage.vanished` / `rollback.preimage.altered` and writing nothing when the world moved between assess and restore; and resume may re-issue a `ROLLBACK` only when every observed value is one of the two row-held hashes — otherwise `APPLY_INDETERMINATE`, so a competing write during an outage is never silently reverted (`B/D12` 🔒). Design, register and plan corrected together, design-first, before Task 1. |
| 2026-09-14 | **Plan 005 executed** ([`../../plans/backend/005-runtime-adapter-and-docker-apply.md`](../../plans/backend/005-runtime-adapter-and-docker-apply.md), Results). `BE-D12`'s six operations — seven as `BE-D22` amends them — ship as `com.solace.sm.dp.apply.runtime.RuntimeAdapter` with a contract kit every implementation must pass, and `DockerAdapter` as its first implementation; the engine's step loop drives plan 004's journal under §2.2's rules 0–4, with `HealthGate` as `SM-D15`'s gate — a policy over observations, deliberately not an adapter operation — and `ResumePlanner` as §2.2 rule 3, a pure, total decision table. Resume routes by **phase**, not by the newest intent's step: an independent review measured that routing on the frontier re-materialized the abandoned generation over a rollback that had already completed, and that a `ROLLBACK` frontier with no open attempt was unrecoverable; both are fixed and pinned by regression tests. §2.3's ArchUnit obligation is discharged by `onlyTheDockerAdapterTouchesDockerJava`, plan 004's carried-forward `engine → persistence` watch item by `persistenceNeverReachesUpIntoTheEngine`, and the seam's direction by `theRuntimeSeamNeverReachesBackIntoTheEngine`; all three carry planted-violation proofs, and the runtime-adapter inventory carries its own. Plan 004's `ensureIntent` scope-lock watch item is RESOLVED by measurement (`EnsureIntentRaceIT`): the intent commits against a terminal request, so obligation (q) is engine-enforced and sequential-only exactly as recorded — no lock is added, and the executor re-reads before entering the exhaustion journal instead. The seam as built is `BE-D21` + `BE-D22`'s accepted block and `BE-D23`'s scope, all three recorded **before** Task 1 rather than reported after Task 13 — `BE-D12`'s text is flagged in [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §2.1 and §3.1, not rewritten (`I3`). All three stay **PROPOSED**; implementation is not ratification, the same sentence `BE-D16`, `BE-D17` and `BE-D18` each carry. **The stop half was closed by two further decisions, taken design-first after the review.** `BE-D25` adds a generation-free `replicas(PlacementRef)` observation — an eighth operation — because `prepare` is generation-bearing and `apply_has_generation` makes a stop's generation NULL, so no stop could learn which replicas to address; the seam's own operation-set guard went red when it was added and was changed only after the decision existed. `BE-D26` makes a stop **scope-granular** — one placement, or a whole instance — and records why instance-wide is admissible for a stop though not for an apply: `BE-D23`'s limit is a property of the placement-scoped steps (`PREFLIGHT`/`ROLLBACK`/`ROLLBACK_ASSESS`), and a stop journals none of them, so `UNIQUE (tenant_id, apply_id, step, replica_ref)` admits one `STOP` intent per replica across any number of placements under one `apply_id`. Instance-wide placements are enumerated from the instance's own `apply_request` history, not from `placement_watermark`, so a placement SM wrote config to and then failed on is still SM's to stop. `resume` is narrowed the same way (`resume(ScopeKey)`). **Obligation (r) is now PROVEN** (`StopDuringApplyIT`) in §3.3's own terms — the stop executes at the adapter, the apply is not cancelled in place (the engine reads no request row; an earlier revision that polled one was removed), and the apply's next step observes the stopped replica through `status` and its health gate and terminates `CONFIG_DIVERGED` with per-replica evidence and no torn write. **One v1 limit remains** and is not claimed away: the apply engine is single-placement and refuses an instance-wide *apply* before admission (`InstanceWideIdentityLimitIT` pins both halves of the identity limit against the shipped DDL and is expected to go red when a persistence plan lifts it). **This plan's own diff** adds no migration, no `contracts/` change, no `sm_control` object and no endpoint — the grep over it is in Results; the branch base separately carries the waived authenticated demo slice ([`../D10-G2-DEMO-WAIVER-2026-09-14.md`](../D10-G2-DEMO-WAIVER-2026-09-14.md)), which is not this plan's work. Measured on execution: surefire 396 (mesh-events 127 / dp-core 236 / arch-tests 33), failsafe 303 completed / 0 failures / 0 errors / 4 skipped, `./contracts/run-checks.sh --strict` ALL CHECKS PASSED, DDL-replay NO DRIFT, `git diff --check` clean. |
| 2026-09-14 | **`BE-D25` and `BE-D26` added (design-first), on the developer's direction that a stop must be able to address a particular instance or a number of them.** Plan 005 had reported the stop path as blocked by a contradiction — `prepare(placement, generation)` is the seam's only replica enumerator and `apply_has_generation` makes a stop's generation NULL — and recorded obligation (r) as unproven because of it. The direction dissolved it from the other end: the operator says what to stop, so the seam needs an enumerator that does not presuppose an apply. **`BE-D25`** adds `replicas(PlacementRef)`, a generation-free read-only observation beside `status` — an eighth operation, with `prepare` redefined as "`replicas` plus the per-apply validation" and the adapter contract kit pinning the two together. `RuntimeSeamTypesTest`'s operation-set guard went red on the addition, which is the guard working; it was changed only after this decision existed. **`BE-D26`** makes a stop **scope-granular** and records the measured reason instance-wide is admissible for a stop though not for an apply: `BE-D23`'s limit is a property of the placement-scoped steps (`PREFLIGHT`/`ROLLBACK`/`ROLLBACK_ASSESS`), and a stop journals none of them, so `UNIQUE (tenant_id, apply_id, step, replica_ref)` admits one `STOP` intent per replica across any number of placements under one `apply_id`. `BE-D23` is narrowed by cross-reference, not rewritten (`I3`), and an instance-wide **apply** stays refused. The placement enumerator is the instance's own `apply_request` history rather than `placement_watermark`, because a placement SM wrote config to and then failed on raises no watermark and would be invisible to a stop. `resume` is narrowed the same way — `resume(ScopeKey)` beside `resume(TenantId)` — because an operator recovering one instance should not sweep a tenant. **One clause was corrected in place before code was built against it** (the draft-amendment policy `BE-D22` used): a stop addressing no known placement was to land `FAILED_NO_EFFECT`, and `state_fits_operation` refused it by name — that CHECK admits only `REQUESTED`/`RUNNING`/`STOPPED`/`STOP_FAILED` for a stop, so it lands `STOP_FAILED`, which is also the honest one, since `STOPPED` would tell an operator the instance is down when SM never looked at anything. Both decisions are **PROPOSED**. Consequence: **obligation (r) is PROVEN** and the plan-005 row above is amended accordingly. Measured: surefire 396, failsafe 303 completed / 4 skipped. A second independent review of the implementation returned 1 BLOCKER + 4 MAJOR + 7 MINOR — the blocker being that this decision's first draft carried only half of `BE-D23` — all addressed, with twelve mutations across the two rounds and the two that stayed silent recorded as test gaps and then closed. |
| 2026-10-05 | **`BE-D27` added — records owner ruling `OR-35` (plan 010 T0.3).** `stateVersion` comes from one `sm_data` sequence stamped by trigger on every owning row (`instance_state`, `config_generation`, `placement_health`, `apply_request`, `fleet_action`), never on the immutable `placement` row, and a write that changes nothing observable does not stamp. Supersedes `design.md` §3's *"the config generation where one exists"* (measured defective: one generation emits several `instance.config-state` notices, and every notice after the first would be discarded by the envelope's own stale-discard rule). Adds a commit-order rule (every stamping transaction first takes the owning `instance_state` row lock) — flagged as this design's **reading** of `OR-35`, to be confirmed by T2.1's review — and fixes lock order `apply_scope → instance_state → (row)`. Design-and-register only; nothing built. |
| 2026-10-05 | **`BE-D28`–`BE-D31` registered (plan 010 integration; drafted as K-1 to K-4 in `secret-store.md` §12, T2.3).** The secret store's four decisions: two-level envelopes with a tenant/secret/purpose AAD; boot-time canary validation of the master key; rotation by an operator command holding both keys; the per-tenant versioned config hash key and the DP-computed `hmacsha256v<n>` hash. They implement `OR-30` and `OR-32` (the latter still flagged: it names `I6`); no earlier decision is edited. `BE-D32` follows in merge order. |
| 2026-10-05 | **Declare protocol recorded as `BE-D32` (plan 010 T4.1, docs only).** The five-step two-phase declare across the DP and CP (the DP mints the id, `OR-27`; the CP registers the governance node and enforces quota under the entitlement row lock, `OR-40`; the first declare in an environment creates the Solution node, `OR-41`), the sweep's two rules (a registered `pending` row is activated at any age; an unregistered one is abandoned only after the window), the per-tenant lazy sweep with no background loop and no tenant enumeration, the CP never deleting a governance node, and the acquisition mode chosen at declare with no host in the body (`OR-71`, `OR-82`). Cross-plane composition stays in the gateway (`I7`). The id was a placeholder until the integrator allocated it at merge. Nothing built. |
| 2026-10-05 | **`BE-D33` drafted (plan 010 T3.3, executing plan 007 Task 5a).** `cp-core` now depends on `mesh-events` and publishes `catalog.package` after its own commit through a control-plane `AfterCommitEventPublisher` over `CpTransactionBoundary` — a boundary that also requires the `sm_control` resource, because the synchronization registry is per thread — wrapping the data plane's ONE raw `meshEventPublisher`; the data plane imports `MeshBridgeConfiguration` with `sm.mesh.enabled` defaulting to `false` (counted drops, one WARN, never a refusal; a partial enabled configuration still refuses). Deviation from Task 5a: the launcher constructs nothing. |
| 2026-10-05 | **`BE-D«next»` drafted (plan 010 S2/S3 slice-review fix round).** Records T3.0's library choice (networknt 2.0.8, Apache-2.0, consumer-side `BE-D11`, `BE-D10` unaffected), the two library gaps the port closes, and T3.2's `stateVersion = max(load_seq)` rule. The integrator allocates the number at merge. |
| 2026-10-05 | **`BE-D34` and `BE-D35` drafted (plan 010 T6.8, verify-then-retry; allocated at the S6+S7 integration).** The reconciler for `APPLY_INDETERMINATE` (supersedes by owner ruling, not ratification, plan 005's "recovery never touches it" part of `BE-D12`/`BE-D16`; `OR-59`, `OR-63`, `OR-64`) and the ninth seam operation `rematerializeIfCurrent`. Step 0 measured: the `APPLY_INDETERMINATE → RUNNING` walk is already legal in the DDL, so `sm_data` V011 adds only `apply_reconciliation` and `apply_request.reconcile_class`. M18 filled locally; the demo-host part is pending for W9. |
| 2026-10-06 | **`BE-D36`, `BE-D37` and `BE-D38` registered (plan 010 T9.3; drafted as L-1..L-5 in [`log-levels.md`](log-levels.md), T9.2) under owner ruling `OR-103`.** The owner ruled option Rd: the log level is READ live over SSH from the placement's loopback and every CHANGE is configuration, with no management port, no actuator move, no live write and no journaled live-set operation (`BE-D36`; amends `OR-43` P1 for v1, `OR-43` unedited). Draft **L-1** (Ra's five prerequisites) and **L-3** (the journaled live set) are **not allocated**: Ra is not built and the journaled operation was decided against; **L-4** (the derived override report) is not allocated because `runtimeOverride` is always `false` under Rd. Draft **L-2** became `BE-D37` (the overlay as one function, entering a generation at the stage under the reserved key `@sm-overlay`) and **L-5** became `BE-D38` (`logs.loggers`, with two schema-level cross-field rules the draft did not carry: `loggerPrefix` and `baselineExposure` are required beside it). Ids are allocated in merge order by the integrator at merge (plan 010, Global Constraints); this lane took the next free three on its branch. |
| 2026-10-06 | **`BE-D39`–`BE-D42` allocated for plan 010 T10.4 (`accept-runtime`, D-ACCEPT, as ruled by `OR-104`).** The four drafts of [`accept-runtime.md`](accept-runtime.md) §8 (A-1 to A-4) become real ids, all **PROPOSED**: the tenth, read-only seam operation `readConfig` (`BE-D39`); the pre-scan that refuses what Jackson reads wrongly (`BE-D40`); the rule that a read-back secret is never stored but compared in memory and reported `same`/`differs`, amended by `V-4` (`BE-D41`); and the stage-only accept that reports what it did not adopt and whether the file's bytes still differ (`BE-D42`). Where the build departs from the drafts it says so at the top of the design note (the round trip runs in the gateway; a value equal to the effective one leaves the draft alone; `readConfig` is a `default` method; `SmOverlayKeys` is a stand-in for D-LOG's function; the contract is tightened by one). `Q-A6` (a `yaml` field) is not ruled. Drafted on its branch as `BE-D36`–`BE-D39`; renumbered to `BE-D39`–`BE-D42` at the S8-S11 merge, after plan 010 T9.3 (`OR-103`) took `BE-D36`–`BE-D38`. |
| 2026-10-06 | **`BE-D43` added (plan 011 P11-5.1; records `OR-107` OQ-3 (a)).** The broker credential of the push path is stored under a new secret purpose, `BROKER_CREDENTIAL`, which amends `BE-D28`'s closed purpose set; `BE-D28` itself is unedited (`I3`). The `sm_data` migration number is `V017` (the lane built it as the placeholder `V018`; the integrator renumbered it at the wave 3 merge). |
| 2026-10-07 | **`BE-D«next-or112b»` drafted (plan 011 follow-up; records `OR-112` (2), `W3-06`).** One live broker connection per (host, port, message VPN) across the whole install: refused by `BrokerConnectionMain` before any credential is sealed, guaranteed by the partial unique index `broker_connection_live_broker_once` (`sm_data` migration `V019`, a placeholder number). The uniqueness read is the operator command's own and install-wide by design (`I5` discussed in the entry). `BE-D43` is unedited (`I3`); the runbook `register-a-broker-connection.md` carries the operator-facing rule. |
| 2026-10-07 | **`BE-D44`–`BE-D65` allocated (integration of plans 008, 012, 013 and 014 under owner ruling `OR-121`).** The drafts of plan 008 (decisions 1–5), [`ops-501.md`](ops-501.md) (O-1–O-5), [`registration-api.md`](registration-api.md) (four drafts) and [`vm-adapter.md`](vm-adapter.md) (V-1–V-5, plus the Q-V3/Q-V6/Q-V11 drafts written after the ruling) take the next free ids in that order, all **PROPOSED**. `BE-D44` supersedes `BE-D23`'s identity statement; `BE-D45`/`BE-D46` amend `BE-D12` §2.1, `BE-D21` and `BE-D26` by cross-reference; nothing is edited in place (`I3`). The two older placeholders above (`BE-D«next»`, `BE-D«next-or112b»`) and `fleet-apply.md`'s F-1–F-3 are **not** allocated here. The execution order is [`../../plans/backend/combined-008-012-013.md`](../../plans/backend/combined-008-012-013.md). |
| 2026-10-07 | **`BE-D44`–`BE-D48` and `BE-D53` checked against plan 008's slices (plan 008 S1, wave 1).** Each decision's text was read against the plan's Draft decisions and the slices as ruled by `OR-121`: **no meaning changed, so no decision is edited** (`I3`). The statements S1 carries into other documents are the plan's end state and become true as the slices land: the live-placement stop and the placement-aware journal (`BE-D44`, `BE-D46`; S2, S3) in `console.yaml`'s `stopInstance` description and `console-integration.md` §5.3 row 52a; the fleet reading of "between placements" (`BE-D45`) in `fleet-apply.md` §5.1 and `submitFleetApply`'s description; the one-placement operations' note (plan 008 OQ-8.7 (a)); a dated note under `eda-walking-skeleton.md` §8 rows 13/14; and two procedures in [`../../runbooks/operations.md`](../../runbooks/operations.md). One reference correction: the plan's S1 file list names `console-integration.md` "§12 / §15.3", and neither section speaks of the stop (§12 is fixture mode, §15 has no §15.3); the stop is row 52a of §5.3, which is what was edited. |
| 2026-10-07 | **`BE-D44` rider (W1 review A-1/A-2; no meaning changed, `I3`).** The text above says the migration "refuses to run while an instance-wide stop is non-terminal". The built predicate is wider and exact: V021 refuses while **any** instance-wide request is open (`REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE`), after taking a `SHARE` lock on `apply_request` so none can be admitted between the check and the column; and the new binary's readiness check refuses to go Ready on a schema without `apply_step_intent.placement_id`. The operator procedure (drain with the old release, stop the application, re-drain, migrate, then start the new release; an `APPLY_INDETERMINATE` row is cleared by `resolveIndeterminate`) is in [`../../runbooks/operations.md`](../../runbooks/operations.md). |
| 2026-10-08 | **`BE-D47` and `BE-D53` rider (plan 008 S3 built; no meaning changed, `I3`).** `BE-D47`'s text says `addPlacement` holds the instance row `FOR SHARE`. As built it holds `FOR NO KEY UPDATE` (`InstanceRows.lockForPlacementChange`), and `retirePlacement` takes the **same** lock: both then stamp the row (a placement-set change is an observable change of the instance, `OR-35`), and two `FOR SHARE` holders that each upgrade to a write deadlock against each other, which two concurrent adds would do whenever they interleave (`W2 review W2A-6`; see the 2026-10-08 W2 review rider below for why two adds now also serialize on `apply_scope`). `FOR NO KEY UPDATE` still excludes plan 012's undeclare (`FOR UPDATE`), every other add and every retire, and does not wait on the `FOR KEY SHARE` a placement insert's foreign key takes. `BE-D53`'s "in flight" check is made under the instance's **`apply_scope`** row (one per instance, the lock every admission serializes on), taken **before** the instance row (admission's own order), so no request can be admitted between the check and the retirement; "in flight" is a `REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE` request on the placement **or on the whole instance** (an instance-wide stop addresses every live placement), or a fleet member that is `pending`, or `admitted` and unsettled. The push-metrics refusal (`OQ-8.9 (a)`) is the data plane's, decided from the metric definitions the gateway resolves from the instance's declared package (the data plane reads no package), before any row is read. `retirePlacement`'s response is `{placementId, workload: "left-running", instance}`; `ops-501.md`'s "and its last health" is not built (the placement's last persisted health is readable through `getHealth` until it is retired). |
| 2026-10-08 | **`BE-D47` and `BE-D53` rider 2 (W2 review of plan 008 S3: `W2A-1`, `W2A-2`, `W2A-3`, `W2A-6`; no meaning changed, `I3`).** (1) **No actor is recorded (`W2A-1`, needs the owner).** `addPlacement` and `retirePlacement` take the acting principal and write it nowhere: `placement` has no `created_by` or `retired_by`, there is no control-plane write, and no per-call audit line is written for either route, so `BE-D53`'s "no CP write beyond the authorization audit line" described a record that does not exist. Plan 008 S3 deviation (6) records it. **Options for the owner:** `created_by` / `retired_by` columns on `placement`, or an `sm_data` placement-event row, in a later migration (S3 was allocated none). Plan 012 T5 covers **retirement only** (`instance_retirement`), and only of an instance, so adds stay unrecorded even after it. (2) **`addPlacement` is fenced against an instance-wide request in flight (`W2A-2`).** It takes `apply_scope` first and then the instance row (retire's and admission's order, so no deadlock) and is refused `409` while a `REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE` instance-wide request exists. An instance-wide stop reads the live set once, when it starts running, so without this a placement added under it was left running by a stop that reported the instance stopped. `BE-D47`'s text "locks the instance row" is unchanged; the `apply_scope` step precedes it. Because two adds now also serialize on `apply_scope`, the `FOR NO KEY UPDATE` choice protects the add against a writer that stamps `instance_state` without `apply_scope` (a generation mint, a health stamp), not against another add. (3) **A window `BE-D53` does not close (`W2A-3`).** "Refused while a running fleet reservation names it" and "no request can be admitted between the check and the retirement" are both true of retire's own check, but `submitFleetApply` validates a member's liveness with a plain read outside `apply_scope`, and `submitApply` checks it before admission, which does not re-check it. A submit that validated the placement just before it was retired is admitted and **fails visibly at preflight** (a failed request; a fleet ending `COMPLETED_WITH_FAILURES` or halting), never silently and never against the host. Closing it means a liveness check inside `AdmissionService` (a new refusal reason and a placement port into the journal-only engine), which this fix round did not take on; routed to the plan that next touches admission. `console.yaml` says so. (4) **The stamp's effect on designers (`W2A-6`).** Both operations stamp the instance, so an open designer session's next `stageConfig` is refused `409` (a stale `baseStateVersion`), exactly as when a landing moves `reached_rung`; `console.yaml` now says so on both operations. |
| 2026-10-08 | **`BE-D47` and `BE-D53` rider 3 (`OR-125` (1); no meaning changed, `I3`): the actor is now recorded.** Rider 2 (1) left `W2A-1` to the owner; `OR-125` (1) rules that `addPlacement` and `retirePlacement` record their actor. Built as `sm_data` V026: two nullable columns on `placement`, `created_by` (written at insert, by `addPlacement`, and by the declare for its own placement) and `retired_by` (written with `retired_at` by `retirePlacement`), with `CHECK (retired_by IS NULL OR retired_at IS NOT NULL)` and the immutability trigger widened to allow exactly the one change "set `retired_at` and `retired_by` together, once". Columns rather than a placement-event row because each fact is a single value of a row that changes once; an actor **id**, so the agent gateway writes an agent's id into the same columns later (`OR-128` (5)) with no further migration. `NULL` means "not attributed": every pre-V026 row, and any retirement made by a sweep or by undeclaring an instance (`retireAll`), which is not one principal's act on one placement. |
| 2026-10-08 | **`BE-D66`–`BE-D71` allocated by plan 015 T0.1** from T0.3's reviewed texts (`agent-gateway/decisions-015-draft.md`; one adversarial review; dispositions in `agent-gateway/proto-amendments-015.md`): the blocking agent adapter and its routing, agent-path parity and refusals, transport `agent`, the launcher's three cross-plane adapters (`BE-D69` — ⚠️ a second composer beside `OR-10`'s, flagged against `I7`, not resolved), the agent as audit actor (`BE-D70` — **amends `OR-25` by name**, records `OR-128` (5)), and the agent target created at enrolment (`BE-D71`). All `PROPOSED`; no earlier row is edited (`I3`). **Link fix, no meaning changed:** the 2026-09-08 row's link to the gateway register pointed at `../agent-gateway/decisions.md`, which does not exist from this folder; it now reads `agent-gateway/decisions.md`. |
| 2026-10-09 | **W0 fix round (in place; `BE-D66`–`BE-D71` are unmerged and unratified, `I3`):** `BE-D67` — "draft finding D-C2, for the reviewer" is now "confirmed by the T0.3 review". `BE-D68` — names `V012` and `V027` as widened/constrained, and states that `ssh-docker` puts **no** constraint on `host_key_algorithm` (NULL is `V027`'s designed fallback); the agent leg requires it NULL. `BE-D70` — the no-principal exemptions include the operator-cli `HIERARCHY_NODE_RENAMED` on `organization` (`OR-131` Q1, `sm_control` V020); item 6 records that `OR-125` (1) is `V026`'s kind-less `created_by`/`retired_by uuid`. |
| 2026-10-11 | **Plan 015 W4 lane C clarifications (appended, no meaning changed, `I3`).** `BE-D70`: the system actor (`sm_control` `V022`) and the exact `OR-25` amendment for the two named job writes (`OR-140`). `BE-D71`: the enrolment sweep is a scheduled job under the system actor, not a lazy step of `listTargets`. `BE-D34`: a re-drive's succeeded re-write records the start baseline (W4 note (d); a deliberate `dp-remote` change). |
