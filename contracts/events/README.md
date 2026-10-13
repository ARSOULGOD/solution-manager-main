# `contracts/events/` — the v1 mesh event contract

The executable half of **Gate G3** (the common event envelope, closed in-spec). Design and rationale live in
[`docs/design/backend/design.md`](../../docs/design/backend/design.md) §3 (envelope, event catalog, identity
scoping) and §4 (topic taxonomy), and in
[`docs/design/backend/decisions.md`](../../docs/design/backend/decisions.md) `BE-D4`. **This folder does not
restate the design — it makes it fail a build when violated.**

Both a Java producer (`dp-core`/`cp-core`) and a TypeScript consumer (the browser, via the WebApp SDK) parse
these events. That two-implementation drift is exactly what `UI-D15`/`DM-D20` exist to prevent elsewhere, so
the events get the same treatment as the Solution Package schema: fixtures, negative cases, and a check
wired into [`../run-checks.sh`](../run-checks.sh) (check 10).

## What is here

| File | Role |
|---|---|
| `envelope.schema.json` | `$id: sm/events/v1/envelope` — the common envelope, `additionalProperties: false`. |
| `notify-events.schema.json` | `$id: sm/events/v1/notify-events` — the seven v1 events, keyed by `event` + `entity.kind`, referencing the envelope. |
| `asyncapi.yaml` | The topic taxonomy (design §4), referencing the JSON Schemas rather than duplicating them. |
| `fixtures/*.json` | One valid event per catalog entry. **Task 9's Java golden tests consume these byte-for-byte** — do not reformat them casually. |
| `fixtures/expected-topics.json` | The topic manifest: the topic each fixture must be published on. Not an event. |
| `negative/*.json` | Documents the contract must reject, one per load-bearing constraint. |
| `negative/bad-topics.json` | Topics the deriver must never produce. Not an event. |
| `validate-events.py` | The four checks below. Exit 0 = the contract holds. |

## The four checks

Run them directly with `python3 validate-events.py`, or as part of `./contracts/run-checks.sh` (check 10).

1. **Every fixture validates** against both schemas. `expected-topics.json` is exempt — it is a manifest, not
   an event, and without the exemption the suite would be permanently red.
2. **Every negative is rejected — for the constraint it exists to exercise.** Mere rejection is not enough:
   `oneOf` reports all seven branches' failures, so almost any keyword can be found in the error set even after
   the constraint under test is deleted. The reason is therefore matched only against the envelope's errors
   plus those of the **one branch whose `event` const matches the document**. `bad-topics.json` is exempt —
   check 4 handles it.
3. **Topic derivation.** Design §4's rules are implemented in the script, each fixture's topic is derived from
   its body, and must equal its manifest entry **bidirectionally** (every fixture has an entry, every entry a
   fixture) and start with `sm/v1/`. The expectation is declared in a manifest rather than embedded in the
   fixtures because the envelope's `additionalProperties: false` would — correctly — reject a topic field.
4. **No `bad-topics.json` entry is derivable.** Proves the deriver cannot be talked into an `sm/v2` address or
   into dropping a topic level. A false green here is the class R6 finding 4 caught.

Plus: `asyncapi.yaml` parses as YAML and every channel `address` starts with `sm/v1/` — keeping the document
honest without an AsyncAPI toolchain in CI.

## Rules that are load-bearing, not stylistic

- **`v1` is in the topic from the first commit** (`BE-D4`). A breaking payload or taxonomy change is `sm/v2/…`
  with a compatibility window — never an edit here.
- **Ordering and stale-discard use `stateVersion` only, never wall-clock** (`B/D15` 🔒). `occurredAtUtc` is
  display and diagnostics; nothing may sort by it.
- **Notify payloads carry identifiers only** (`I6`). No config value, no secret, no metric value, no label
  value. A fully leaked notify subscription must reveal that instances exist and change — never what they
  contain. `negative/value-in-notify.json` is that rule as an executable check.
- **Identity scoping is the R5 identity fix made executable** (design §3). `instance.health`,
  `instance.rollout` and `instance.apply-outcome` are **placement-scoped** and require `placementId`, so a dual-bound instance's two sites
  never overwrite each other in a live console. `instance.config-state` and `instance.version` are
  deliberately coarse instance-scoped invalidations and **forbid** `placementId`. `catalog.package` is
  solution-scoped and forbids instance identity.
- **Closed vocabularies are copied, never invented** (`UI-D14`). The health enum is the SCREAMING rollup keys
  plus the four `NOTFOUND` sub-states as distinct keys, from
  [`STATE-VOCABULARY.md`](../../docs/design/frontend/reference/STATE-VOCABULARY.md) §2 and §5; the version
  enum is `VersionState` from [`../view-models/src/types.ts`](../view-models/src/types.ts). Collapsing the four
  `NOTFOUND` states into one "missing" badge is what the source comment forbids.

## `instance.apply-outcome` — the seventh event (`OR-4`, landed by `OR-46`)

A **placement-scoped invalidation notice**, not an outcome record: **REST is the outcome** — `observeApply`
(`GET …/apply/observe`) is the authority, and this event only tells a console to look again.

- **Shape (copied from `OR-4`, not re-derived).** `event = "instance.apply-outcome"`;
  `entity.kind = "placement"` (a `const`) with `instanceId` and a **required** `placementId`;
  `payload = {applyId, state}` with `additionalProperties: false`. Emitting `kind = "instance"` with a
  `placementId` is the shape the gate rejects for the sibling events, and is rejected here too
  (`negative/apply-outcome-instance-kind.json`).
- **`state` is the closed set of the `apply` operation's outcomes only** — `STAGED`, `APPLIED`,
  `FAILED_NO_EFFECT`, `FAILED_ROLLED_BACK`, `CONFIG_DIVERGED`, `SUPERSEDED`, `INDETERMINATE_RESOLVED` (the
  `RequestState.isTerminal()` set minus the two stop states) **plus `APPLY_INDETERMINATE`** (owner ruling
  2026-09-29 (T0.2), `OR-58`). `APPLY_INDETERMINATE` is not terminal, but it is where the engine stops and —
  after the reconciler has confirmed, retried or given up — where a human must act, so a consumer must be told.
  The event is published **once per stamp**: on a first landing on `APPLY_INDETERMINATE`, and again when the
  reconciler's give-up moves the request's `stateVersion`.
- **No error detail** (`OR-4`, `I6`): no message, no `ErrorClass` token — a classifier can embed a host or
  container identifier. A consumer that needs *why* reads the journal over REST
  (`negative/apply-outcome-with-error-detail.json`).
- **Topic: today's gated shape** (owner ruling 2026-09-29 (T0.2)) —
  `sm/v1/<organization>/notify/instance/<instanceId>/apply-outcome/<applyId>`, exactly as its placement-scoped
  sibling `instance.rollout` is addressed. `OR-6`'s re-addressing of the taxonomy is **not** landed here and
  must move all seven events together later.
- **Browser delivery stays gated** by open items 32/33/35: the event is emitted after commit and verified
  against a local broker; nothing here enables a browser subscription or a mesh ticket.

### Stops are REST-observed (`M10`, owner ruling 2026-09-29 (T0.2))

`STOPPED` and `STOP_FAILED` are **not** in the `state` enum, by design. A stop may be instance-wide
(`ApplyExecutor.admit` applies `BE-D23`'s instance-wide refusal to `apply` only), but this event needs a **real,
required** `placementId`, and the instance-wide sentinel is not a storable placement — so an instance-wide stop
outcome has no legal envelope, and `OR-4` approves an **apply** outcome, not a stop outcome.

**The declared behaviour:** after a `stop` returns `202 {applyId}`, the browser polls
`GET …/apply/observe?applyId=<id>` about **every 2 seconds** until the item is terminal (`STOPPED` /
`STOP_FAILED`), and `instance.health` reflects the stopped container within one poll interval. This is a
**declared** poll, not a degraded mode hidden behind a live claim; `observeApply`'s description in
[`../openapi/console.yaml`](../openapi/console.yaml) says the same. A stop notice is a separate owner question
if one is ever wanted.

### Config-state firing rule (`OR-37`)

Documented here, not encoded in the schema: v1 emits `instance.config-state` **only for an instance with exactly
one live placement**. With more than one, the instance rung is the **lowest across placements**, and the event
fires only when that lowest rung moves.

### `stateVersion` (`OR-35`)

One `sm_data` sequence, stamped on each change to an owning row, so per-entity commit order equals stamp order.
The envelope's description now says so; the earlier "config generation where one exists" rule is corrected by the
decision plan 010 T0.3 records. Ordering and stale-discard still use `stateVersion` only (`B/D15`).

## Changing anything here

Contracts are in-tree but **single-writer**: a change here is a deliberate reviewed change, never a
convenience edit. Run `./contracts/run-checks.sh` before **and** after. Adding an event means: a catalog row
in design §3, a branch in `notify-events.schema.json`, a fixture, a manifest entry, a topic rule in
`derive_topic()`, and — if it introduces a new constraint — a negative with its reason declared in
`NEGATIVE_REASON`. When a branch adds or changes a **closed enum, a primitive type, or a numeric/string
bound**, at least one `negative/*.json` must exercise that constraint class. The planned backend
`SchemaMirrorLockTest` must lock the declarations equal once backend implementation is authorized; until
then, the contract validator and its negatives are the executable authority in this repository.

## Known gaps

Four places where this folder is ahead of the design text. All four want the **same `BE-D4` amendment**;
none is resolved here, because a decision's meaning is never edited in place (`I3`).

1. **The `metrics.quarantine` topic is not in the taxonomy.** `BE-D4`'s block in `decisions.md` lists
   health, config-state, version, rollout and catalog, though design §3's event catalog carries the event.
   The deriver, the manifest and `asyncapi.yaml` all use
   `sm/v1/<organization>/notify/instance/<instanceId>/quarantine`, consistent with the other instance aspects.
   **The decision text should be amended to list it.**

2. **The rollout `step` vocabulary has no canonical home.** `notify-events.schema.json` closes
   `payload.step` to `[staged, restart-requested, restart-observed]`, which came from the implementation
   plan and is reasonable — but it is the one enum here that is *not* copied from a canonical source, which
   sits awkwardly under the "closed vocabularies are copied, never invented" rule above. Of the three,
   only `restart-observed` has a home (the `SM-D22` ladder rung); `staged` is a `CONFIG` state key borrowed
   from a different key-space; `restart-requested` appears nowhere else in the repo. Design §3's rollout
   row names a `step` field without enumerating it. **The amendment should give this vocabulary a canonical
   home** — either in `STATE-VOCABULARY.md` alongside the other closed sets, or enumerated in §3's catalog.

3. **`instance.rollout` requires `entity.placementId`, which §3's catalog row does not list.** The identity
   scoping paragraph says rollout is placement-scoped, but the catalog row's payload column lists only
   instanceId, applyId, replicaId, step. A producer written from that row alone emits an event this gate
   rejects. **The row should name `placementId`**, matching the paragraph and this schema.

4. **`catalog.package`'s `packageVersion` is unconstrained.** `notify-events.schema.json` declares it
   `"packageVersion": {}` — required, but of **any JSON type**, so `42`, `null`, `{}` and `""` all validate.
   Every other payload field here is typed or enumerated; this one is the hole. It is left open rather than
   guessed because the right answer is a vocabulary question, not a formatting one: whether a package version
   is the Solution Package's own `version` string, what its grammar is (semver? opaque?), and where that
   grammar canonically lives. **The same `BE-D4` amendment should give it a type and, if it has one, a
   canonical home** — the schema is deliberately unchanged until it does (`I3`; contracts are single-writer).

   ⚠️ **This gap is the one place the two implementations deliberately disagree.** A JSON `null` written
   literally on the wire *validates* in Python (the schema permits any type), but Java's
   `NotifyEventShapes` **rejects a null-valued required payload key**, because `EventJson`'s `NON_NULL`
   content inclusion would strip it and emit a document missing a required property — Java refuses to
   construct what it cannot faithfully serialize (wire-safety). The asymmetry is narrower than letting a
   required key silently vanish, and it disappears once the `BE-D4` amendment settles `packageVersion`'s
   type. Until then, do not "fix" either side to match the other.
