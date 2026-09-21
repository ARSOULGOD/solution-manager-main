# Proposal — one amendment to `BE-D4`, to close the eight recorded event-contract gaps

| | |
|---|---|
| **Status** | ⛔ **PROPOSAL — still not approved as a whole.** ✅ **Gap 6's shape IS ruled** (owner, 2026-09-16): candidate **(b)**, a new **`instance.apply-outcome`** notice with the payload §2.1 fixes. The other seven gaps, and the reviewed `contracts/events/` change that lands any of them, remain **unapproved** — so **no event may be implemented yet**. |
| **Amends** | `BE-D4` (the v1 mesh event contract — envelope, catalog, topic taxonomy) |
| **Resolves** | [`contracts/events/README.md`](../../../contracts/events/README.md) Known gaps 1–4 · [`eda-walking-skeleton.md`](eda-walking-skeleton.md) §8 rows 8, 10, 11, 13 |
| **Blocks** | ⛔ The **event-driven apply walking skeleton's completion** ([plan 007](../../plans/backend/007-production-api-and-event-driven-walking-skeleton.md)) · generated browser `NotifyEvent` types · wire-visible rollback progress |
| **Prepared** | 2026-09-15 |

⚠️ **This document decides nothing.** `contracts/` is normative and **single-writer**: an event gains a catalog row,
a schema branch, a fixture, a manifest entry, a topic rule and — for a new constraint — a negative, all as one
reviewed change. Until this proposal is approved, **no new event may be implemented**, and plan 007 may not claim
the EDA skeleton complete.

---

## 1. Why this is urgent rather than merely open

`BE-D1` makes REST authoritative and events the change notices. `BE-D13` gives an apply seven terminal request
states. **The event catalog carries a notice for none of them.**

So the one surface an operator watches most closely — *did my apply succeed?* — has no live path. The current
consequence, recorded honestly in
[`console-integration.md`](../common/contracts/console-integration.md) §9: the apply screen **polls** and the live
indicator must say **degraded**. That is the correct behaviour for a missing contract and the wrong architecture to
keep. A console whose most-watched surface is permanently degraded is not an event-driven console.

## 2. Gap 6 — the terminal apply outcome (the one that blocks a plan)

### 2.1 What it must carry, and what it must not

| Rule | Statement |
|---|---|
| **Identifiers and state only** | `I6`. Enough identity to invalidate the authoritative REST query, and nothing more |
| **Not authoritative** | The outcome is read from the journal over REST. The event says *look again* |
| **Placement-scoped** | An apply acts on a placement; a placement-scoped fact may not move an instance-scoped ladder (`eda-walking-skeleton.md` §3.5) |
| **`stateVersion`-ordered** | As every other event. Never `occurredAtUtc` (`B/D15` 🔒) |
| **No error detail** | A terminal state key, never a message, never a remote error, never an `ErrorClass` string that might embed a host or container identifier |

**The minimum identity set** — derived from what the console must invalidate, not from what is convenient to emit:
`instanceId` · `placementId` · `applyId` · the terminal `state` key · `stateVersion`.

### 2.2 Two candidate shapes, priced — ✅ **(b) chosen, 2026-09-16**

**Candidate (a) — extend `instance.rollout`'s `step` vocabulary with terminal keys.**

- **For:** no new event, no new topic, no new schema branch; the existing `rollout/{applyId}` topic already carries
  `applyId` and requires `placementId`; one consumer, one handler, one invalidation rule.
- **Against:** `step` becomes two things — a *progress* ladder and an *outcome* — and Known gap 2 already records
  that this vocabulary has **no canonical home**. Widening an enum that is itself flagged as un-homed compounds the
  problem. A consumer must then distinguish "still going" from "finished" by enum membership.

**Candidate (b) — a new `instance.apply-outcome` event on its own aspect topic.**

- **For:** an outcome is a different fact from a progress step, and the closed vocabularies rule wants each fact to
  carry its own; the terminal state key can be sourced canonically from `BE-D13`'s request-state enum rather than
  invented; `step` stays a progress ladder.
- **Against:** a seventh event — a new schema branch, fixture, manifest entry, topic rule, negative, Java shape and
  TypeScript type; and the taxonomy grows an aspect.

✅ **RULED: (b).** A new **`instance.apply-outcome`** event, for the reason that outweighs the extra artifacts —
under (a) the terminal state key would have to be merged into an enum the contract already admits has **no
canonical home** (gap 2), while under (b) it is copied from `BE-D13`'s request states, which is the *"closed
vocabularies are copied, never invented"* rule applied rather than bent.

**The ruled shape, in full:**

| | |
|---|---|
| `event` | `instance.apply-outcome` |
| `entity.kind` | `placement` — it is **placement-scoped**, so `placementId` is **required**, exactly as `instance.rollout` is |
| `entity` | `kind`, `instanceId`, `placementId` |
| `payload` | `applyId`, and the **terminal `state`** key, copied from `BE-D13`'s request states |
| envelope | `stateVersion` as every event, monotonic per entity; ordering by `stateVersion` only, never `occurredAtUtc` |
| ⛔ **forbidden** | **no error details.** No message, no remote error, no `ErrorClass` string — a classifier can embed a host or container identifier, and `I6` bars it. A consumer that needs *why* reads the journal over REST |
| topic | ⚠️ **the OR-6 shape of gap 9**, not the existing one-level form: `…/{environmentId}/notify/solution/{solutionId}/instance/{instanceId}/placement/{placementId}/apply-outcome`. Gaps 6 and 9 therefore land **together** — a new placement-scoped event addressed on the old taxonomy would have nowhere to put `placementId` |

⚠️ **Ruling the shape is not approving the amendment.** The schema branch, fixture, manifest entry, topic rule and
negative are a **single-writer reviewed change to `contracts/`** that has not happened, and the other seven gaps
are still open. So plan 007 still **may not implement this event**, and still may not claim the EDA skeleton
complete.

## 3. The other seven gaps, restated so one amendment closes all eight

| # | Gap | Source | Proposed resolution |
|---|---|---|---|
| 1 | The `metrics.quarantine` **topic is absent from `BE-D4`'s decision text**, though the deriver, manifest and AsyncAPI all carry it | Known gap 1 | Amend the decision text to list it. No schema change; the gate is already right |
| 2 | The rollout **`step` vocabulary has no canonical home** — of `staged`/`restart-requested`/`restart-observed`, only `restart-observed` has one | Known gap 2 | Give it a home: `STATE-VOCABULARY.md` alongside the other closed sets, **or** enumerate it in design §3's catalog row. Pick one — two homes is the same defect |
| 3 | `instance.rollout` **requires `entity.placementId`, which §3's catalog row does not list** — a producer written from the row alone emits a rejected event | Known gap 3 | Add `placementId` to the row. Text follows the gate |
| 4 | `catalog.package`'s **`packageVersion` is untyped** (`{}`) — and Python and Java **deliberately disagree** on a JSON `null` | Known gap 4 | Decide the grammar (the Solution Package's own `version` string? semver? opaque?) and where it canonically lives, then type it. ⛔ **Do not "fix" either implementation before the decision** |
| 5 | The **stream level is named `{grantId}`** in `BE-D4`'s body and `asyncapi.yaml`, but `BE-D14` §4.5 carries the session's `sub` | §8 row 10 | Rename the level. Nothing breaks today — check 10 does not lock the address string — but `contracts/` is single-writer, so it lands as a reviewed item |
| 6 | **No terminal apply outcome** | §8 row 11 | §2 of this proposal |
| 7 | **No wire-visible rollback/verify progress**, if the console needs it | §8 row 8 | Decide whether the console needs it. If yes, it rides gap 2's vocabulary decision |
| 8 | **Per-binding config-state ladder refinement**, and a referential guard for the nil-UUID placement sentinel | §8 row 13 | The ladder question is this amendment's (entity kinds); the sentinel guard is [plan 008](../../plans/backend/008-placement-registry-and-apply-journal-identity.md) Task 3's |
| **9** | ⛔ **The OR-6 topic taxonomy — the largest change here, and until now it had no owning artifact.** Ruling **OR-6** (2026-09-16) makes the canonical instance prefix `sm/v1/{organizationId}/{subOrganizationId}/{environmentId}/notify/solution/{solutionId}/instance/{instanceId}/{aspect}`, with placement facts at `…/placement/{placementId}/{aspect}` — **three id levels where the gated taxonomy has one** | ruling **OR-6**; [`common/contracts/console-integration.md`](../common/contracts/console-integration.md) §11.2 | **This amendment owns it.** §3.1 below lists every artifact it touches. ⚠️ An earlier draft of §5 said this proposal *"does not touch"* the isolation work and `console-integration.md` said the change *"rides the same amendment"* — the two could not both be true, and this row is which |

### 3.1 Gap 9's artifact list — the one that is bigger than it looks

A topic-shape change is not a schema edit. Measured against the tree, **eight** artifacts move, and two of them are
closed enums that every other artifact list in this proposal forgot:

| Artifact | What changes |
|---|---|
| `contracts/events/asyncapi.yaml` | every channel `address`; ⚠️ **and the `aspect` channel-parameter `enum`**, which is closed to `[health, config-state, version, quarantine]` — a new aspect is an enum change, not just an address change |
| `contracts/events/envelope.schema.json` | ⚠️ **the `event` enum is CLOSED** to the six. A seventh event (gap 6's `instance.apply-outcome`) fails envelope validation until it is a member — so **every** "add an event" list in this document must include this file |
| `contracts/events/notify-events.schema.json` | the `oneOf` branch per event, and each branch's `entity.kind` `const` |
| `contracts/events/validate-events.py` | ⚠️ **the Python deriver hard-codes the one-level shape** (`f"sm/v1/{tenant}/notify/instance/{instance_id}/…"`), and its path-traversal guard checks **one** id segment (*"a tenant of `acme/../evil`"*) — under OR-6 that guard must cover **three** id levels |
| `backend/mesh-events/.../NotifyTopics.java` | the **Java** deriver — there are **two** derivers and both must agree byte-for-byte, which is what check 10's bidirectional topic test exists to prove |
| `contracts/events/fixtures/expected-topics.json` | every expected topic |
| `contracts/events/fixtures/*.json` | every fixture's identity fields, since the address is derived from the body |
| `contracts/events/negative/bad-topics.json` | new forbidden shapes — at minimum a topic with a **fabricated lower scope**, which OR-6 rule 3 forbids and nothing currently rejects |

## 4. What approval unblocks, exactly

1. **Plan 007 may implement the terminal notice** and the apply surface leaves degraded mode — the last surface
   where polling is the live path.
2. **Browser `NotifyEvent` types may be *generated*** rather than hand-written against the schemas. (⚠️ They are
   buildable *today*, hand-written under `BE-D10`'s mirror-and-lock discipline — `FE-D7` permits exactly that until
   the contract exists. This amendment changes how they are produced, not whether the mesh path can be built.)
3. **The `BE-D5` ratification residue shrinks** by one: G3's known-gaps rider closes.

## 5. What this proposal explicitly does NOT do

- ~~It does not choose the terminal event's **name** or its **schema**.~~ ✅ **Ruled 2026-09-16** — §2.2. What it still does not do is **land** that event: the `contracts/events/` change is reviewed, single-writer, and unmade.
- It does not type `packageVersion`, because that is a vocabulary question, not a formatting one.
- It does not touch `BE-D5`'s broker **ACL mechanism** (open items 32/33) or the **measurement** of environment-level
  notify isolation (open item 35). Those gate mesh **delivery**; this proposal gates event **contracts** — including
  gap 9's topic *shape*, which is a contract and is owned here. ⚠️ The distinction matters: OR-6 decides what the
  addresses **are**; items 32/33/35 decide whether an ACL can **confine a subscriber to them**, and that is
  unmeasured.
- It does not amend `BE-D4`'s meaning in place. When approved, it lands as a reviewed single-writer change to
  `contracts/events/` **and** a new decision that supersedes the amended clauses (`I3`).

## 6. What is needed

1. ✅ **Done, 2026-09-16:** candidate **(b)**, `instance.apply-outcome`, with §2.2's payload. What remains is the reviewed `contracts/` change that lands it — **together with gap 9's taxonomy**, per §2.2's topic row.
2. **Give the rollout `step` vocabulary one canonical home** (gap 2).
3. **Type `packageVersion`** and say where its grammar lives (gap 4).
4. **Confirm the stream-level rename** (gap 5).
5. **Rule on wire-visible rollback progress** (gap 7) — needed or not.
6. Approve gaps 1, 3 and 8's text corrections, which are editorial and follow the gate.
