# Backend Plan 003 - Publish-Path Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `EventPublisher` seam production-shaped without crossing any gate: enforce `BE-D1`'s
write-then-notify ordering with an after-commit decorator, resolve the mesh session's named open
bean-registration decision with a reconnecting transport that starts disconnected, wire both behind one
Spring configuration, and land the §5.2 executable publisher-inventory check that comes due with the
second `EventPublisher` implementation.

**Architecture:** `contracts/events/` stays untouched — this plan changes **zero contract files** and adds
**zero schema surface**. `mesh-events` gains the framework-free `TransactionBoundary` seam and the
`AfterCommitEventPublisher` decorator (plane-neutral, beside the `EventPublisher` seam, for the same reason
the seam lives there). `dp-core` gains the Spring adapter (`SpringTransactionBoundary`), the
`ReconnectingTransport` that starts disconnected and connects in the background, and the
`MeshBridgeConfiguration` that composes `AfterCommit(Jcsmp(Reconnecting))` into the one `EventPublisher`
bean. `arch-tests` gains two new executable gates: the publisher-inventory check (design §5.2's named
trigger) and the JCSMP-confinement rule (design §2's "no other module holds a broker session").

**Tech Stack:** Java 17 (no preview features — `instanceof` patterns yes, `switch` patterns no), Spring
Boot 3.x parent BOM (adds `spring-tx` and `spring-boot-starter-test` to `dp-core`, both BOM-managed),
Maven, Jackson via `EventJson` only, JUnit 5, ArchUnit, sol-jcsmp 10.30.1.

**Spec:** [`../../design/backend/design.md`](../../design/backend/design.md) §2/§3/§9 (`BE-D1`, `BE-D3`)
plus [`../../design/common/contracts/backend-consumption.md`](../../design/common/contracts/backend-consumption.md)
§5.2 (`BE-D10`) and [`../../design/backend/decisions.md`](../../design/backend/decisions.md). Executors
must read all three before starting.

## Why this slice, and what was rejected

The stable contract baseline (plans 001+002) left three named forward items that are **not** gated on the
architect group:

1. **`BE-D1` §3's ordering rule has no enforcement.** "A module publishes an event only *after* its
   PostgreSQL transaction commits" is design text; the `EventPublisher` javadoc even says callers "publish
   from inside their own transaction". Nothing today orders the notice after the commit or discards it on
   rollback. The after-commit decorator is the pre-outbox form of exactly the seam `BE-D1` reserved.
2. **The bean-registration decision is open by name.** `JcsmpEventPublisher`'s javadoc: the
   `MeshProperties` constructor connects eagerly and throws, which is "wrong for an eager Spring
   singleton… Whoever registers this bean must choose deliberately — lazy connect, or a reconnecting
   transport that starts disconnected." This plan makes that choice (reconnecting, starts disconnected)
   and updates the javadoc so the sentence stops dangling. That choice was role-assigned to "the
   walking-skeleton plan"; taking it early is deliberate and recorded in this plan's Deviations — the
   walking skeleton is gated on G2 and this decision is not.
3. **The §5.2 inventory check comes due here.** Design §5.2 and the kit javadoc both say: "the PR adding a
   second `EventPublisher` implementation must also add an executable inventory check." The decorator IS
   the second implementation, so this plan must deliver that check or break a recorded promise.

**Rejected alternatives** (recorded so the choice is reviewable): a backend notify *consumer/reader* —
no backend consumer exists in v1 and design §16 Q6 warns against speculative internal events; cp-core
package-upload validation (the `BE-D10` inverted model) — its only caller is an auth-bearing endpoint
gated on G2, and `contracts/validation/` parity is a far larger surface; AsyncAPI/generated types — gated
on the `BE-D4` amendment (the four Known gaps in [`contracts/events/README.md`](../../../contracts/events/README.md)).

## Gate map — what this plan may touch and what stays blocked

| Gate / decision | Status | Effect on this plan |
|---|---|---|
| G1 residue: open items 32/33 (`BE-D5` ACL shapes) | with the architect group | **No effect.** They gate *browser* subscribe authorization. This plan touches only the server's own publish identity (`MeshProperties`), which plan 001's IT already exercised. |
| G2: `D10` amendment (open item 24) | with the architect group | **No effect, enforced:** this plan contains no auth-bearing code, no endpoint, no session, no ticket. Every task's file list is auth-free. |
| `BE-D4` amendment (4 Known gaps) | with the architect group | **No effect:** zero contract-file changes in this plan; the lock tests stay byte-for-byte untouched. |
| TS IEEE-754 `Long.MAX_VALUE` watch item | routed to the TS-consumer work | **Named dependency check: this plan touches no TypeScript and no frontend**, so the item stays routed where it is. |
| `SM-D*`/`BE-D*` unratified (`I4`) | unratified | Same posture as plans 001/002: gate-independent seams and enforcement of already-written design text (`BE-D1` §3, design §2), no new architecture invented. |

**Nothing in this plan waits on a gate. Nothing in this plan closes a gate.** The walking skeleton
(gateway, login, tickets, real notify emission from product state) remains blocked on G2; chart tails on
`BE-D6`/`DM-D1`; generated types on the `BE-D4` amendment.

## Global Constraints

- **Zero changes under `contracts/`.** `./contracts/run-checks.sh --strict` must pass before and after
  every task for the trivial reason that nothing there moved. Single-writer hygiene: if a task appears to
  need a contract change, stop and report — the plan is wrong.
- **Zero changes to `SchemaMirrorLockTest`, `NotifyEventShapes`, `NotifyTopics`, `EventJson`,
  `EventEnvelope`, `EntityRef`.** All frozen: messages are pinned by 60+ tests and the lock is reviewed
  clean through R13. This plan only *composes* them.
- `EventPublisher.publish()` fails loud only for malformed/unaddressable events and never throws for
  broker or serialization failures — the decorator must preserve both halves, and must surface the loud
  half **in the caller's own frame** (inside its transaction), never inside an after-commit callback.
- No schema or fixture is committed under `backend/`; Java tests read `contracts/` only through
  `ContractArtifacts` (`BE-D11`).
- `console-gateway` must not import `mesh-events`; `cp-core` and `dp-core` must not import each other;
  `mesh-events` stays plane-neutral **and framework-free** (no Spring dependency may be added to it — the
  `TransactionBoundary` seam exists precisely so the Spring adapter lives in `dp-core`).
- Event emission serializes only through `EventJson.write()`/`writer()`; no second `ObjectMapper` in any
  publisher package (existing ArchUnit rule must stay green).
- **Out of scope, explicitly:** auth, sessions, mesh tickets, RBAC, websocket proxying, runtime schema
  validation, package-upload validation, real notify emission from product state, any database/schema
  work, any outbox table, Micrometer or any metrics dependency (the drop counter's exposure stays with
  **the metrics plan**), the spike ACL fixtures (stay with **the mesh-provisioning plan**), AsyncAPI
  catalog changes, anything TypeScript/frontend.
- `mvn -f backend/pom.xml clean verify` is the completion gate (use `clean` — a stale `target/` has
  false-passed a red-first step in this repo before). The R13 baseline of 149 Java tests
  (mesh-events 120 / dp-core 14 / arch-tests 15) only grows; reconcile counts at every task.
- Environment: `rg` is a broken shim — use `grep -rn`. Don't use `echo ===` in zsh. A test class not
  named `*Test` is silently skipped by surefire (lesson R5) — only `dp-core` binds failsafe, so `*IT`
  classes go there and nowhere else.
- Commits are per the task's commit steps, scoped to listed files only. Do not push. Do not merge.

## Code Execution Contract for Sub-Agents

Sub-agents implementing this plan must run on **Claude Opus 5** — every implementation and every review
sub-agent. If Opus 5 is unavailable, stop and report before proceeding. Model downgrades require
Parikshit's explicit approval.

Every sub-agent is bound by these rules:

- Implement exactly one task from this plan. Do not begin later tasks.
- Before editing, read `CLAUDE.md`, `docs/design/backend/design.md` §2–§3,
  `docs/design/common/contracts/backend-consumption.md` §5, `docs/design/backend/decisions.md`, this
  plan, and the files listed in your task.
- Use the package names, class names, and signatures specified here. Do not invent alternates.
- Write the failing test first where the task specifies one, run it, and confirm the failure **reason**,
  not just the color. Negative tests must fail for the intended reason.
- Where a step says "verify against the shipped jar / the installed Spring version," do it (`I2`) —
  javap, unzip, or reading the dependency's sources; never memory. If the verified fact contradicts this
  plan's snippet, stop and report before writing code.
- If a check is intentionally skipped, report it as skipped with its condition.
- Commit only the task's files, with an imperative commit subject, ending with
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Report changed files, commands run, failures observed before the fix, deviations, and the commit hash.

### Dispatch Template

> You are implementing Task N from `docs/plans/backend/003-publish-path-lifecycle.md`.
> Use Claude Opus 5.
> Implement only Task N. Do not start later tasks.
> Follow the Code Execution Contract in the plan.
> Read `CLAUDE.md`, `docs/design/backend/design.md` §2–§3,
> `docs/design/common/contracts/backend-consumption.md` §5, `docs/design/backend/decisions.md`, this
> plan, and the files listed under Task N.
> Preserve gates: no auth, tickets, RBAC, websocket proxying, runtime schema validation, metrics wiring,
> database work, or real notify emission from product state. Change nothing under `contracts/`.
> Run the task's verification commands and report real output.
> Commit only Task N files and report the commit hash.

---

### Task 1: `TransactionBoundary` and `AfterCommitEventPublisher` in `mesh-events`

The framework-free half of `BE-D1` §3's ordering rule. `mesh-events` stays Spring-free: the decorator
talks to an interface, and Task 4's `dp-core` adapter is the only class that knows Spring exists.

**Files:**
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/publisher/TransactionBoundary.java`
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/publisher/AfterCommitEventPublisher.java`
- Test: `backend/mesh-events/src/test/java/com/solace/sm/events/publisher/AfterCommitEventPublisherTest.java`

**Interfaces:**
- Consumes: `EventPublisher`, `NotifyTopics.topicFor(EventEnvelope)`, `NotifyEventShapes.validate(EventEnvelope)`,
  `EventJson.reader()`, `ContractArtifacts.dir("events/fixtures")` (test only).
- Produces (later tasks rely on these exact signatures):
  - `public interface TransactionBoundary { boolean inTransaction(); void afterCommit(Runnable action); }`
  - `public final class AfterCommitEventPublisher implements EventPublisher` with
    `public AfterCommitEventPublisher(EventPublisher delegate, TransactionBoundary boundary)` and
    `public EventPublisher delegate()`.

- [ ] **Step 1: Write the failing tests**

```java
package com.solace.sm.events.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The decorator's OWN behaviour — deferral, discard, and the eager loud guards. The seam-wide
 * contract (derive/serialize/swallow/count, over every fixture and negative) is NOT re-proven here:
 * that is the kit's job, and Task 2's dp-core subclass runs the kit over the composed
 * AfterCommit(Jcsmp) stack. Splitting it this way keeps each suite answering one question.
 */
class AfterCommitEventPublisherTest {

    /** A contract fixture, not a hand-built envelope — this suite consumes contracts/events/ too. */
    private static EventEnvelope healthFixture() throws IOException {
        Path fixtures = ContractArtifacts.dir("events/fixtures");
        return EventJson.reader().readValue(Files.readString(fixtures.resolve("health-transition.json")));
    }

    /** Hand-rolled boundary: inTransaction is a knob, commit/rollback fire or discard the deferred work. */
    private static final class RecordingBoundary implements TransactionBoundary {
        boolean inTransaction;
        final List<Runnable> deferred = new ArrayList<>();

        @Override
        public boolean inTransaction() {
            return inTransaction;
        }

        @Override
        public void afterCommit(Runnable action) {
            deferred.add(action);
        }

        void commit() {
            deferred.forEach(Runnable::run);
            deferred.clear();
        }

        void rollback() {
            deferred.clear();
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
        }
    }

    @Test
    void outsideATransactionThePublishIsImmediate() throws IOException {
        RecordingBoundary boundary = new RecordingBoundary();
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        p.publish(healthFixture());

        assertEquals(1, delegate.published.size(),
                "with no transaction there is nothing to order against — publish now");
        assertTrue(boundary.deferred.isEmpty(), "nothing may be deferred outside a transaction");
    }

    @Test
    void insideATransactionNothingReachesTheDelegateUntilCommit() throws IOException {
        RecordingBoundary boundary = new RecordingBoundary();
        boundary.inTransaction = true;
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        EventEnvelope event = healthFixture();
        p.publish(event);

        assertTrue(delegate.published.isEmpty(),
                "BE-D1 §3: the notice must not exist before the state it announces is committed");
        boundary.commit();
        assertEquals(List.of(event), delegate.published,
                "commit must release exactly the deferred event to the delegate");
    }

    @Test
    void aRolledBackTransactionPublishesNothingEver() throws IOException {
        RecordingBoundary boundary = new RecordingBoundary();
        boundary.inTransaction = true;
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        p.publish(healthFixture());
        boundary.rollback();

        assertTrue(delegate.published.isEmpty(),
                "a rolled-back write happened to nobody — announcing it would be the lie BE-D1 forbids");
    }

    @Test
    void severalEventsInOneTransactionAreReleasedInPublishOrder() throws IOException {
        RecordingBoundary boundary = new RecordingBoundary();
        boundary.inTransaction = true;
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        Path fixtures = ContractArtifacts.dir("events/fixtures");
        EventEnvelope health = healthFixture();
        EventEnvelope configState = EventJson.reader()
                .readValue(Files.readString(fixtures.resolve("config-state.json")));
        p.publish(health);
        p.publish(configState);
        boundary.commit();

        assertEquals(List.of(health, configState), delegate.published,
                "release order must be publish order — reordering inside one commit is gratuitous");
    }

    /**
     * The loud guards run NOW, in the caller's frame — not at commit time, when there is no caller
     * left to hear them. A malformed event is a caller bug (EventPublisher's contract); deferring
     * the refusal into an after-commit callback would convert fail-loud into fail-after-the-fact.
     * Both guard messages are the frozen NotifyTopics/NotifyEventShapes texts.
     */
    @Test
    void anUnaddressableEventIsRefusedEagerlyInsideTheTransaction() {
        RecordingBoundary boundary = new RecordingBoundary();
        boundary.inTransaction = true;
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        EventEnvelope bogus = new EventEnvelope(
                java.util.UUID.fromString("3f1c9a52-0000-4000-8000-0000000000aa"),
                java.time.Instant.parse("2026-08-28T10:00:00Z"),
                "dp-core", "acme",
                new com.solace.sm.events.EntityRef("instance", "cache-prod-01", null, null),
                1, "instance.not-a-real-event", Map.of());

        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> p.publish(bogus));
        assertTrue(boom.getMessage().contains("no topic rule for event"), boom.getMessage());
        assertTrue(boundary.deferred.isEmpty(), "a refused event must not leave a live callback behind");
        assertTrue(delegate.published.isEmpty(), "nothing may reach the delegate");
    }

    @Test
    void aShapeInvalidEventIsRefusedEagerlyInsideTheTransaction() throws IOException {
        RecordingBoundary boundary = new RecordingBoundary();
        boundary.inTransaction = true;
        RecordingPublisher delegate = new RecordingPublisher();
        AfterCommitEventPublisher p = new AfterCommitEventPublisher(delegate, boundary);

        EventEnvelope bad = EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/negative").resolve("health-without-placement.json")));

        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> p.publish(bad));
        assertTrue(boom.getMessage().contains("entity.placementId is required"), boom.getMessage());
        assertTrue(boundary.deferred.isEmpty(), "a refused event must not leave a live callback behind");
        assertTrue(delegate.published.isEmpty(), "nothing may reach the delegate");
    }

    @Test
    void nullConstructionArgumentsAreRefused() {
        RecordingPublisher delegate = new RecordingPublisher();
        RecordingBoundary boundary = new RecordingBoundary();
        assertThrows(NullPointerException.class, () -> new AfterCommitEventPublisher(null, boundary));
        assertThrows(NullPointerException.class, () -> new AfterCommitEventPublisher(delegate, null));
    }
}
```

- [ ] **Step 2: Run to red**

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: COMPILATION ERROR — `TransactionBoundary` and `AfterCommitEventPublisher` do not exist. Keep
the exact error in the task report.

- [ ] **Step 3: Implement the two types**

```java
package com.solace.sm.events.publisher;

/**
 * The transaction seam {@link AfterCommitEventPublisher} defers through — deliberately not a Spring
 * type, so that {@code mesh-events} stays framework-free and plane-neutral. The Spring adapter
 * ({@code SpringTransactionBoundary}, dp-core) is one small class; a second plane or a second
 * framework writes its own adapter instead of dragging a framework into the contract module.
 *
 * <p>Contract: {@link #afterCommit(Runnable)} may only be called while {@link #inTransaction()} is
 * true. The boundary runs the action exactly once, after the surrounding transaction commits
 * successfully, and never runs it if the transaction rolls back — a rolled-back write happened to
 * nobody, so no notice is owed ({@code BE-D1}).
 */
public interface TransactionBoundary {

    /** Whether the calling thread is inside a transaction whose completion can be hooked. */
    boolean inTransaction();

    /**
     * Runs {@code action} after the current transaction commits; discards it on rollback.
     * Only meaningful while {@link #inTransaction()} is true.
     */
    void afterCommit(Runnable action);
}
```

```java
package com.solace.sm.events.publisher;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.NotifyEventShapes;
import com.solace.sm.events.NotifyTopics;
import java.util.Objects;

/**
 * {@code BE-D1} §3 as a decorator: "a module publishes an event only <b>after</b> its PostgreSQL
 * transaction commits." Inside a transaction the delegate publish is deferred to the boundary's
 * after-commit hook; a rollback discards it (the write happened to nobody, so no notice is owed).
 * Outside a transaction there is nothing to order against and the publish is immediate.
 *
 * <p><b>The loud guards run eagerly, in the caller's frame.</b> An unaddressable or shape-invalid
 * event is a caller bug by {@link EventPublisher}'s contract, and the caller is only reachable
 * <i>now</i>, inside its own transaction — an after-commit callback has no caller left to throw at.
 * So topic derivation and shape validation run before anything is deferred; only the transport work
 * waits for the commit. The delegate re-runs both guards at fire time (every implementation does, by
 * the same contract) — harmless re-verification of an envelope that {@link EventEnvelope} made
 * immutable at construction, which is what makes validate-now-send-later sound.
 *
 * <p><b>This is the pre-outbox form of the upgrade path the seam was built for.</b> When a future
 * consumer needs at-least-once, "defer to after-commit" becomes "write to an outbox in the same
 * transaction, drain asynchronously" behind this same interface — the design's named bounded
 * upgrade. Until then, a crash between commit and publish loses only freshness (the fallback poll
 * picks it up), exactly the loss {@code BE-D1} accepts.
 *
 * <p>Not a drop site: this class swallows nothing and counts nothing. A rollback-discard is not a
 * drop (no notice was owed); transport drops belong to the delegate and are counted there.
 */
public final class AfterCommitEventPublisher implements EventPublisher {

    private final EventPublisher delegate;
    private final TransactionBoundary boundary;

    public AfterCommitEventPublisher(EventPublisher delegate, TransactionBoundary boundary) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override
    public void publish(EventEnvelope event) {
        // Eager loud guards — same two, same order, as every publisher: addressing first (I5),
        // then shape. The derived topic is discarded; the delegate re-derives at fire time.
        NotifyTopics.topicFor(event);
        NotifyEventShapes.validate(event);
        if (boundary.inTransaction()) {
            boundary.afterCommit(() -> delegate.publish(event));
        } else {
            delegate.publish(event);
        }
    }

    /** The wrapped publisher — for composition-aware wiring and tests (e.g. reading its drop counter). */
    public EventPublisher delegate() {
        return delegate;
    }
}
```

- [ ] **Step 4: Run to green**

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: BUILD SUCCESS; mesh-events test count grows from 120 to 127 (7 new). Read the `Tests run:`
line for `AfterCommitEventPublisherTest` — it must show 7, not be absent (lesson R5).

- [ ] **Step 5: Prove the eager-guard placement fires**

Temporarily move `NotifyEventShapes.validate(event);` inside the `if`'s else-branch only (so an in-tx
publish skips validation), rerun:

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: FAIL, exactly `aShapeInvalidEventIsRefusedEagerlyInsideTheTransaction` (and possibly the
unaddressable sibling stays green — topicFor still runs). The failure must be attributed to the moved
guard. Revert the mutation, rerun to green. Record both outputs in the task report.

- [ ] **Step 6: Commit**

```bash
git add backend/mesh-events/src/main/java/com/solace/sm/events/publisher/TransactionBoundary.java \
        backend/mesh-events/src/main/java/com/solace/sm/events/publisher/AfterCommitEventPublisher.java \
        backend/mesh-events/src/test/java/com/solace/sm/events/publisher/AfterCommitEventPublisherTest.java
git commit -m "Add the after-commit publisher decorator behind a framework-free boundary seam"
```

---

### Task 2: The kit run over the composed stack (`dp-core`)

Design §5.2: every `EventPublisher` implementation extends the kit. The decorator's kit subclass runs
over the **composed production stack** — `AfterCommit(Jcsmp(transport))` with a no-transaction boundary —
so what the kit certifies is the exact object graph Task 6 wires into Spring, not a test-only delegate.

**Files:**
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/AfterCommitEventPublisherContractTest.java`

**Interfaces:**
- Consumes: `AbstractEventPublisherContractTest`, `CapturingTransport` (mesh-events test-jar),
  `JcsmpEventPublisher`'s package-private seam constructor (same package),
  `AfterCommitEventPublisher.delegate()`.
- Produces: nothing new — this is pure certification.

- [ ] **Step 1: Write the subclass (it is green by construction if Task 1 is right — the red case is Step 3)**

```java
package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.contracttest.AbstractEventPublisherContractTest;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import com.solace.sm.events.publisher.TransactionBoundary;

/**
 * The second {@link EventPublisher} implementation's half of the publisher contract (design §5.2) —
 * run over the COMPOSED stack {@code AfterCommit(Jcsmp(transport))}, because that is the object
 * graph {@code MeshBridgeConfiguration} wires: certifying the decorator around a toy delegate would
 * certify a graph production never builds.
 *
 * <p>The boundary reports no transaction, so every kit publish takes the immediate path — which is
 * the path whose wire output the kit can observe synchronously. The deferred path's ordering and
 * discard semantics are {@code AfterCommitEventPublisherTest}'s (mesh-events) and
 * {@code SpringTransactionBoundaryTest}'s (Task 4) job; the kit's job here is proving the decorator
 * launders none of the seam's guarantees: derive-and-serialize, both loud guards, the swallow-and-
 * count paths, and whole-body fixture equality all hold through the extra layer.
 *
 * <p>Lives in this package for the same reason {@code EventPublisherContractTest} does: the
 * {@code JcsmpEventPublisher} seam constructor is package-private.
 */
class AfterCommitEventPublisherContractTest extends AbstractEventPublisherContractTest {

    private static final TransactionBoundary NOT_IN_TRANSACTION = new TransactionBoundary() {
        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public void afterCommit(Runnable action) {
            throw new IllegalStateException("afterCommit outside a transaction is a decorator bug");
        }
    };

    @Override
    protected EventPublisher publisher(CapturingTransport transport) {
        return new AfterCommitEventPublisher(new JcsmpEventPublisher(transport), NOT_IN_TRANSACTION);
    }

    @Override
    protected long droppedCount(EventPublisher publisher) {
        // The decorator is not a drop site (its javadoc argues why); drops live on the wrapped
        // JCSMP publisher, reached through the decorator's own composition accessor.
        return ((JcsmpEventPublisher) ((AfterCommitEventPublisher) publisher).delegate()).droppedCount();
    }
}
```

- [ ] **Step 2: Run to green and reconcile the count**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: BUILD SUCCESS; dp-core grows from 14 to 26 (the kit contributes 6 plain tests + 6
parameterized fixture runs). Confirm `AfterCommitEventPublisherContractTest` shows `Tests run: 12`.

- [ ] **Step 3: Prove the eager guard is load-bearing, with the isolated proof**

Temporarily comment out the eager `NotifyEventShapes.validate(event);` line in
`AfterCommitEventPublisher` (mesh-events), then run **both** module suites:

```bash
mvn -f backend/pom.xml -pl mesh-events test
mvn -f backend/pom.xml -pl dp-core -am test -Dmaven.test.failure.ignore=true
```

The failure-ignore flag on the second command is load-bearing (measured at execution): `-am` rebuilds
mesh-events first, whose red halts the reactor before dp-core runs at all — so without the flag the
"still green" observation is unobservable (`dp-core: SKIPPED`). With it, failure *reporting* stays
intact (mesh-events still prints its `[ERROR] Tests run: … Failures: 1` lines) and only the halt is
suppressed, so read dp-core's own summary. `-fae` is NOT a substitute — dp-core is still SKIPPED
under it, also measured.

Expected, and each half teaches something different:

- `mesh-events`: FAIL — exactly `aShapeInvalidEventIsRefusedEagerlyInsideTheTransaction`, because
  `assertThrows` got no `IllegalArgumentException`: the invalid event was **accepted and deferred**
  (its test boundary captures callbacks, so the laundering is caught at the precise moment it happens
  — an invalid event sitting in the deferred list). This is the isolated proof; nothing else in that
  suite may fail.
- `dp-core` (the kit over the composed stack): **still green** — not a defect but the division of
  labour made visible: the kit runs with a no-transaction boundary, so every kit publish takes the
  immediate path and the *delegate* validates. The kit certifies the composed stack's wire behaviour;
  the deferred path's guard placement is `mesh-events`' test to own, which is why Step 3 runs both.

Revert, rerun both to green. Record all four outputs verbatim in the task report.

- [ ] **Step 4: Commit**

```bash
git add backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/AfterCommitEventPublisherContractTest.java
git commit -m "Certify the composed after-commit stack against the publisher contract kit"
```

---

### Task 3: The two new executable gates in `arch-tests`, and §5.2's honesty update

Two promises come due: design §5.2's inventory check ("the PR adding a second `EventPublisher`
implementation must also add an executable inventory check"), and design §2's "no other module holds a
broker session", which was prose until now.

**Files:**
- Modify: `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/events/archfixture/BadUnkittedPublisherClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/cp/archfixture/BadCpJcsmpClass.java`
- Modify: `docs/design/common/contracts/backend-consumption.md` (§5.2 enforcement-honesty paragraph)
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/AbstractEventPublisherContractTest.java`
  (the class javadoc's "Enforcement honesty" paragraph only — no code)

**Interfaces:**
- Consumes: ArchUnit `ClassFileImporter`, `ExcludeArchFixtures`, `EventPublisher` (mesh-events main),
  `com.solacesystems..` (on the test classpath transitively via dp-core).
- Produces: the inventory constant later publisher work must extend:
  `PUBLISHER_IMPLEMENTATIONS` in `PlaneBoundaryTest`.

**Classpath fact, verified while planning:** `arch-tests` depends on every module's **main** jar only —
the mesh-events test-jar (kit, self-test's non-validating publisher) is NOT on its classpath, so the
inventory scan sees production classes plus arch-tests' own fixtures and nothing else. If an implementer
finds kit classes in the scan, stop and report — the classpath changed and the check needs a location
filter it was designed not to need.

- [ ] **Step 1: Write the two planted fixtures**

```java
package com.solace.sm.events.archfixture;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.EventPublisher;

/**
 * Planted violation for the publisher-inventory check: an {@link EventPublisher} implementation the
 * inventory does not list. If the inventory cannot flag this class, it cannot flag the real thing —
 * a future publisher added without a contract-kit subclass (design §5.2).
 */
public final class BadUnkittedPublisherClass implements EventPublisher {

    @Override
    public void publish(EventEnvelope event) {
        // Planted violation — never called.
    }
}
```

```java
package com.solace.sm.cp.archfixture;

import com.solacesystems.jcsmp.JCSMPFactory;

/**
 * Planted violation for the JCSMP-confinement rule: a class outside {@code com.solace.sm.dp.meshbridge}
 * touching the broker SDK. Design §2: "All mesh I/O for the backend goes through [mesh-bridge]; no
 * other module holds a broker session."
 */
public final class BadCpJcsmpClass {

    Object plantedViolation() {
        return JCSMPFactory.onlyInstance();
    }
}
```

- [ ] **Step 2: Add the rules and their prove-it-fires tests to `PlaneBoundaryTest`**

Add one `@ArchTest` rule, one inventory helper + `@Test`, and three proof `@Test`s (imports:
`com.solace.sm.events.publisher.EventPublisher`, `java.util.Set`, `java.util.TreeSet`,
`java.util.stream.Collectors` as needed):

```java
    // Design §2: "no other module holds a broker session" — the broker SDK is meshbridge-internal.
    // Package-based (com.solacesystems..) rather than class-based so it covers the whole SDK
    // surface, sessions and factories and properties alike.
    @ArchTest
    static final ArchRule onlyMeshbridgeTouchesJcsmp =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.meshbridge..")
                    .should().dependOnClassesThat().resideInAPackage("com.solacesystems..");

    // ---------------------------------------------------------------------------------------
    // Publisher inventory (design §5.2). Every concrete EventPublisher implementation must be
    // listed here, and listing one is a PROMISE that its module carries a test class extending
    // AbstractEventPublisherContractTest over it. arch-tests cannot see other modules' test
    // classes, so the pairing itself is reviewed at the moment this set is edited — which is
    // exactly what this check exists to force: adding an implementation without touching this
    // file is impossible, and touching this file is what puts the kit obligation in front of
    // the reviewer.
    // ---------------------------------------------------------------------------------------

    /** Kit-certified implementations. Extend ONLY together with a new kit subclass (design §5.2). */
    private static final Set<String> PUBLISHER_IMPLEMENTATIONS = Set.of(
            "com.solace.sm.dp.meshbridge.JcsmpEventPublisher",
            "com.solace.sm.events.publisher.AfterCommitEventPublisher");

    private static Set<String> concretePublisherImplementations(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.isAssignableTo(EventPublisher.class))
                .filter(c -> !c.isInterface() && !c.getModifiers().contains(
                        com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                .map(c -> c.getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyEventPublisherImplementationIsInTheCertifiedInventory() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertEquals(new TreeSet<>(PUBLISHER_IMPLEMENTATIONS),
                concretePublisherImplementations(production),
                "the EventPublisher implementation set moved. A new implementation ships WITH a test "
                        + "class extending AbstractEventPublisherContractTest in its own module "
                        + "(design §5.2) — add both, then list the class here. Removing one: delist "
                        + "it and delete its kit subclass in the same change.");
    }

    @Test
    void inventoryCatchesAPlantedUnkittedPublisher() {
        Set<String> withFixture = concretePublisherImplementations(withPlantedViolations());
        assertTrue(withFixture.contains("com.solace.sm.events.archfixture.BadUnkittedPublisherClass"),
                "the scan must SEE the planted implementation, or the inventory can be grown "
                        + "invisibly. Saw: " + withFixture);
        assertFalse(PUBLISHER_IMPLEMENTATIONS.contains(
                        "com.solace.sm.events.archfixture.BadUnkittedPublisherClass"),
                "the planted class must not be in the certified list — that would defeat the demo");
    }

    @Test
    void jcsmpRuleCatchesAPlantedViolation() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyMeshbridgeTouchesJcsmp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.cp.archfixture.BadCpJcsmpClass"),
                "onlyMeshbridgeTouchesJcsmp must fail because of the planted fixture, not for some "
                        + "other reason. Was: " + failure.getMessage());
    }
```

Note the inventory equality assertion imports: use JUnit's `assertEquals`; the existing static imports
in the file already cover `assertTrue`/`assertFalse`/`assertThrows`. Add
`import static org.junit.jupiter.api.Assertions.assertEquals;` and the `java.util`/ArchUnit imports the
snippet needs. The `assertEquals(expected, actual)` on two `TreeSet`s makes a diff readable and salted
`Set.of` iteration order irrelevant.

- [ ] **Step 3: Run to red-then-green, both directions**

First prove the inventory fires on the real tree: temporarily remove
`"com.solace.sm.events.publisher.AfterCommitEventPublisher"` from `PUBLISHER_IMPLEMENTATIONS`, run:

```bash
mvn -f backend/pom.xml -pl arch-tests -am test
```

Expected: FAIL — `everyEventPublisherImplementationIsInTheCertifiedInventory`, message naming
`AfterCommitEventPublisher` as unlisted. Restore the entry, rerun: BUILD SUCCESS, arch-tests grows from
15 to 19 (1 new `@ArchTest` + 3 new `@Test`). Record both outputs.

- [ ] **Step 4: Update the two honesty paragraphs**

In `docs/design/common/contracts/backend-consumption.md` §5.2, replace the "Enforcement honesty"
paragraph's forward-looking half with the delivered state — keep the history, change the tense. New text
(adjust surrounding prose minimally):

> **Enforcement honesty:** "every implementation extends the kit" became executable when the second
> implementation landed (plan 003): `arch-tests` pins the concrete `EventPublisher` implementation set
> (`PlaneBoundaryTest.PUBLISHER_IMPLEMENTATIONS`), so an implementation cannot be added without editing
> the pinned inventory, and editing the inventory is the reviewed moment at which the kit-subclass
> pairing is checked — `arch-tests` still cannot see other modules' test classes, so the pairing itself
> remains a review obligation, now with a forced review point instead of a hoped-for one.

In `AbstractEventPublisherContractTest`'s class javadoc, replace the "Enforcement honesty (design §5.2)"
paragraph the same way (the kit-inventory check now exists in `arch-tests`; the pairing is checked at
the forced inventory edit). Javadoc only — no code change in that file.

- [ ] **Step 5: Full-module green and commit**

```bash
mvn -f backend/pom.xml clean verify
git add backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java \
        backend/arch-tests/src/test/java/com/solace/sm/events/archfixture/BadUnkittedPublisherClass.java \
        backend/arch-tests/src/test/java/com/solace/sm/cp/archfixture/BadCpJcsmpClass.java \
        docs/design/common/contracts/backend-consumption.md \
        backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/AbstractEventPublisherContractTest.java
git commit -m "Land the publisher-inventory and JCSMP-confinement gates the design promised"
```

Expected verify: BUILD SUCCESS, 172 tests (mesh-events 127 / dp-core 26 / arch-tests 19 — the IT reports
skipped-with-condition). Reconcile; if the arithmetic differs, explain why in the report before
committing.

---

### Task 4: `SpringTransactionBoundary` in `dp-core`

The one class that knows Spring transactions exist. Tested against a **real**
`AbstractPlatformTransactionManager` subclass driven by `TransactionTemplate` — not by poking
`TransactionSynchronizationManager` statics — so what is under test is the adapter inside Spring's
actual synchronization dispatch, including rollback discard.

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundary.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundaryTest.java`
- Modify: `backend/dp-core/pom.xml` (add `spring-tx`)

**Interfaces:**
- Consumes: `TransactionBoundary` (Task 1), `org.springframework.transaction.support.*`.
- Produces: `public final class SpringTransactionBoundary implements TransactionBoundary` (no-arg
  constructor) — Task 6 wires it.

- [ ] **Step 1: Add the dependency and verify it resolves**

`spring-boot-starter` does not bring `spring-tx`; add it explicitly (version from the Boot BOM):

```xml
<dependency>
  <groupId>org.springframework</groupId>
  <artifactId>spring-tx</artifactId>
</dependency>
```

Verify (`I2`, not memory):

```bash
mvn -f backend/pom.xml -pl dp-core dependency:tree | grep spring-tx
```

Expected: one managed `spring-tx` line (6.x from the 3.5.16 BOM). If `spring-tx` was already present
transitively, say so in the report and keep the explicit declaration anyway — this module now compiles
against it directly.

- [ ] **Step 2: Write the failing tests**

```java
package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spring adapter under Spring's REAL synchronization dispatch: a minimal
 * {@link AbstractPlatformTransactionManager} (no datastore — begin/commit/rollback are recorded, and
 * the abstract base runs the genuine synchronization machinery) driven through
 * {@link TransactionTemplate}. Poking TransactionSynchronizationManager statics by hand would test
 * our understanding of that class, not the adapter's behaviour inside a managed transaction.
 */
class SpringTransactionBoundaryTest {

    /** Real AbstractPlatformTransactionManager, no resource — commit flips a flag the tests read. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        volatile boolean committed;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource to bind — the base class runs the synchronization lifecycle regardless.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to undo.
        }
    }

    /** Records each publish together with whether the transaction had committed at that moment. */
    static final class CommitAwarePublisher implements EventPublisher {
        final RecordingTransactionManager ptm;
        final List<Boolean> committedAtPublishTime = new ArrayList<>();

        CommitAwarePublisher(RecordingTransactionManager ptm) {
            this.ptm = ptm;
        }

        @Override
        public void publish(EventEnvelope event) {
            committedAtPublishTime.add(ptm.committed);
        }
    }

    private static EventEnvelope healthFixture() throws IOException {
        return EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));
    }

    @Test
    void insideAManagedTransactionThePublishLandsAfterDoCommit() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope event = healthFixture();

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            p.publish(event);
            assertTrue(delegate.committedAtPublishTime.isEmpty(),
                    "nothing may reach the delegate while the transaction is still open");
        });

        assertEquals(List.of(Boolean.TRUE), delegate.committedAtPublishTime,
                "the delegate publish must observe an already-committed transaction — that ordering "
                        + "IS BE-D1 §3");
    }

    @Test
    void aRolledBackTransactionPublishesNothing() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope event = healthFixture();

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(ptm).executeWithoutResult(status -> {
                    p.publish(event);
                    throw new IllegalStateException("business write failed");
                }));

        assertFalse(ptm.committed, "precondition: the transaction must really have rolled back");
        assertTrue(delegate.committedAtPublishTime.isEmpty(),
                "a rolled-back write happened to nobody — no notice may go out");
    }

    @Test
    void outsideAnyTransactionThePublishIsImmediate() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());

        p.publish(healthFixture());

        assertEquals(1, delegate.committedAtPublishTime.size(),
                "no transaction on this thread — the boundary must not defer into nowhere");
    }

    @Test
    void twoEventsInOneTransactionArriveInOrderAfterCommit() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        RecordingListPublisher delegate = new RecordingListPublisher();
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope health = healthFixture();
        EventEnvelope configState = EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("config-state.json")));

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            p.publish(health);
            p.publish(configState);
        });

        assertEquals(List.of(health, configState), delegate.published,
                "Spring runs registered synchronizations in registration order; the adapter must "
                        + "not reorder them");
    }

    static final class RecordingListPublisher implements EventPublisher {
        final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
        }
    }
}
```

- [ ] **Step 3: Run to red**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: COMPILATION ERROR — `SpringTransactionBoundary` does not exist.

- [ ] **Step 4: Implement the adapter**

```java
package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.publisher.TransactionBoundary;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TransactionBoundary} over Spring's per-thread transaction synchronization — the only class
 * in the backend that knows the decorator's "after commit" is Spring-managed.
 *
 * <p><b>Why the conjunction.</b> {@code inTransaction()} requires BOTH an actual transaction and
 * active synchronization: registration is only legal while synchronization is active, and deferral
 * is only <i>meaningful</i> while a real transaction's commit is pending. In an empty synchronized
 * scope (e.g. {@code PROPAGATION_SUPPORTS} with no transaction) there is no commit to order against,
 * so publishing immediately is correct, not a shortcut. The exotic inverse — an actual transaction
 * with synchronization disabled ({@code SYNCHRONIZATION_NEVER}) — degrades to immediate publish,
 * which is exactly today's pre-decorator behaviour: stated, not silent.
 *
 * <p><b>Rollback discard is inherited, not implemented:</b> only {@code afterCommit} is overridden,
 * so a rolled-back transaction simply never invokes the action — no compensation logic to get wrong.
 */
public final class SpringTransactionBoundary implements TransactionBoundary {

    @Override
    public boolean inTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
```

`I2` note for the implementer: `TransactionSynchronization` has been an interface with default methods
since Spring 5 — confirm against the resolved spring-tx sources (one `javap` or a jar peek), and confirm
`AbstractPlatformTransactionManager`'s default synchronization mode runs synchronizations for the fake
manager (the Step 2 tests prove this empirically — if they fail on machinery rather than on the missing
class, stop and report the measured behaviour before changing the design).

- [ ] **Step 5: Run to green**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: BUILD SUCCESS; dp-core 26 → 30. Confirm `SpringTransactionBoundaryTest` ran 4.

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core/pom.xml \
        backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundary.java \
        backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/SpringTransactionBoundaryTest.java
git commit -m "Adapt the transaction boundary to Spring's synchronization dispatch"
```

---

### Task 5: `ReconnectingTransport` — the mesh session that starts disconnected

This task decides the javadoc's named open question: the Spring path gets **a reconnecting transport
that starts disconnected**. A broker that is down at startup, or down for an hour mid-run, costs
freshness (counted drops, WARN lines) and never availability (`BE-D1`, design §9 row 1).

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/ReconnectingTransport.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisher.java`
  (new package-private `(BiConsumer, AtomicLong)` constructor; new
  `connectingInBackground(MeshProperties)` factory; JCSMP channel reconnect properties in
  `JcsmpTransport`'s `MeshProperties` constructor; the bean-registration javadoc paragraph rewritten)
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/ReconnectingTransportTest.java`

**Interfaces:**
- Consumes: `JcsmpEventPublisher.JcsmpTransport(MeshProperties, AtomicLong)` (existing, same package).
- Produces:
  - `final class ReconnectingTransport implements BiConsumer<String, String>, AutoCloseable`
    (package-private) with
    `ReconnectingTransport(Supplier<BiConsumer<String, String>> connectFactory, Duration initialBackoff, Duration maxBackoff)`,
    `boolean isConnected()`, `boolean awaitConnected(Duration timeout)` (package-private, for tests/IT).
  - `public static JcsmpEventPublisher connectingInBackground(MeshProperties props)` — Task 6 wires it.

- [ ] **Step 1: Write the failing tests**

```java
package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class ReconnectingTransportTest {

    private static final Duration TICK = Duration.ofMillis(10);
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Test
    void startsDisconnectedAndRefusesLoudlyUntilConnected() throws Exception {
        CapturingTransport real = new CapturingTransport();
        AtomicInteger failuresLeft = new AtomicInteger(3);
        try (ReconnectingTransport t = new ReconnectingTransport(() -> {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("broker not reachable yet");
            }
            return real;
        }, TICK, TICK)) {

            // Whether the background thread has already won the race is timing; what is NOT timing
            // is the contract: before connection, accept() must throw so the publisher's swallow
            // site counts the drop — never a silent no-op that looks like a delivery.
            if (!t.isConnected()) {
                assertThrows(IllegalStateException.class,
                        () -> t.accept("sm/v1/acme/notify/instance/i-1/health", "{}"),
                        "a disconnected transport must throw into the publisher's counting catch");
            }

            assertTrue(t.awaitConnected(PATIENCE),
                    "three factory failures at a 10ms backoff must connect well within patience");
            t.accept("sm/v1/acme/notify/instance/i-1/health", "{}");
            assertEquals("sm/v1/acme/notify/instance/i-1/health", real.sent().getKey(),
                    "once connected, accept() must delegate to the real transport untouched");
        }
    }

    @Test
    void aPermanentlyUnreachableBrokerCostsCountedDropsNeverAThrowAtTheCaller() throws IOException {
        // Composed with the real publisher: the ISE from the disconnected transport must land in
        // JcsmpEventPublisher's existing RuntimeException catch — counted and swallowed, per BE-D1.
        ReconnectingTransport never = new ReconnectingTransport(() -> {
            throw new IllegalStateException("nothing listens here");
        }, Duration.ofMinutes(10), Duration.ofMinutes(10));
        try (never) {
            JcsmpEventPublisher p = new JcsmpEventPublisher(never);
            EventEnvelope event = EventJson.reader().readValue(Files.readString(
                    ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));

            p.publish(event);
            p.publish(event);

            assertEquals(2L, p.droppedCount(),
                    "broker-down publishes are freshness loss: counted, warned, never thrown");
            assertFalse(never.isConnected());
        }
    }

    @Test
    void closeBeforeEverConnectingStopsTheRetryThread() throws Exception {
        ReconnectingTransport t = new ReconnectingTransport(() -> {
            throw new IllegalStateException("still down");
        }, TICK, TICK);
        t.close();
        assertTrue(t.awaitRetirement(PATIENCE),
                "close() must end the background retry thread, or every context restart leaks one");
    }

    @Test
    void closeAfterConnectingClosesTheDelegate() throws Exception {
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> session, TICK, TICK);
        assertTrue(t.awaitConnected(PATIENCE));
        t.close();
        assertEquals(1, session.closedCount.get(), "the owned session must be closed exactly once");
    }

    /**
     * The context-restart leak: a JCSMP connect is NOT interruptible, so close() can run while
     * connectFactory.get() is still in flight — and the session the factory then produces belongs
     * to a dead context. The handoff must close it; without the post-install re-check it runs on
     * forever, holding a broker session nobody owns.
     */
    @Test
    void closeDuringAnInFlightConnectClosesTheSessionThatConnectProduces() throws Exception {
        CountDownLatch connectStarted = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> {
            connectStarted.countDown();
            awaitUninterruptibly(releaseConnect);   // models JCSMP: connect ignores Thread.interrupt
            return session;
        }, TICK, TICK);

        assertTrue(connectStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS),
                "precondition: the connect attempt must actually be in flight before close() runs");
        t.close();                                  // context shutdown mid-connect
        releaseConnect.countDown();                 // the connect now completes into a dead context

        assertTrue(t.awaitRetirement(PATIENCE),
                "the connector thread must retire after handing its late session to the closer");
        assertEquals(1, session.closedCount.get(),
                "a session produced by an in-flight connect after close() must be closed, never "
                        + "leaked to outlive its context");
        assertFalse(t.isConnected(), "a closed transport must never present as connected");
    }

    @Test
    void closeIsIdempotentAndClosesTheDelegateExactlyOnce() throws Exception {
        CountingCloseTransport session = new CountingCloseTransport();
        ReconnectingTransport t = new ReconnectingTransport(() -> session, TICK, TICK);
        assertTrue(t.awaitConnected(PATIENCE));
        t.close();
        t.close();   // manual close followed by the context destroyMethod is a real Task 6 sequence
        assertEquals(1, session.closedCount.get(),
                "double close must not close the session twice");
    }

    /** A delegate that counts its closes — the observable for every ownership assertion above. */
    static final class CountingCloseTransport implements BiConsumer<String, String>, AutoCloseable {
        final AtomicInteger closedCount = new AtomicInteger();

        @Override
        public void accept(String topic, String body) { }

        @Override
        public void close() {
            closedCount.incrementAndGet();
        }
    }

    /** A latch-wait that survives interruption, the way a real JCSMP connect does. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: Run to red**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: COMPILATION ERROR — `ReconnectingTransport` does not exist.

- [ ] **Step 3: Implement `ReconnectingTransport`**

```java
package com.solace.sm.dp.meshbridge;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A mesh transport that STARTS DISCONNECTED and connects in the background with capped exponential
 * backoff — the deliberate answer to the bean-registration question {@code JcsmpEventPublisher}'s
 * javadoc left open: an eager singleton must never let a down broker block application boot
 * ({@code BE-D1}: broker down degrades freshness, never availability — design §9 row 1).
 *
 * <p><b>While disconnected, {@code accept} THROWS {@link IllegalStateException}</b> rather than
 * dropping silently. That is not a violation of the never-throw rule — this class sits on the
 * transport side of {@code JcsmpEventPublisher}'s seam, whose {@code publish} catch is the existing
 * counting swallow site. Throwing into it means every pre-connection publish is counted on the one
 * drop counter and WARN-logged with its eventId, with zero counter plumbing added here.
 *
 * <p><b>Division of labour with JCSMP:</b> this class owns the initial connection only. Once
 * {@code connectFactory} succeeds, mid-session outages are the session's own problem — the
 * {@code JcsmpTransport} constructor configures JCSMP channel reconnection, and a send that fails
 * anyway is counted by the existing {@code accept} catch. This class never tears down and re-creates
 * a session it has handed out.
 *
 * <p><b>Close/connect race, owned explicitly.</b> A JCSMP connect is not interruptible: if the
 * context shuts down while {@code connectFactory.get()} is in flight, the factory can still return a
 * live session into a dead context. So installation is an ownership handoff: the connector installs
 * the fresh delegate, then re-checks {@code closed}; {@code close()} flips {@code closed}, then
 * takes the delegate. Whichever side wins the atomic {@code getAndSet(null)} owns the close — never
 * both (no double-close), never neither (no leaked session outliving its context). {@code close()}
 * itself is idempotent behind a CAS, because Task 6's context {@code destroyMethod} may run after a
 * manual close.
 */
final class ReconnectingTransport implements BiConsumer<String, String>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingTransport.class);

    private final AtomicReference<BiConsumer<String, String>> delegate = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Thread connector;

    ReconnectingTransport(Supplier<BiConsumer<String, String>> connectFactory,
            Duration initialBackoff, Duration maxBackoff) {
        this.connector = new Thread(() -> {
            long waitMillis = initialBackoff.toMillis();
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                BiConsumer<String, String> fresh;
                try {
                    fresh = connectFactory.get();
                } catch (RuntimeException e) {
                    log.warn("mesh connect failed — retrying in {} ms (publishes drop until then)",
                            waitMillis, e);
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    waitMillis = Math.min(waitMillis * 2, maxBackoff.toMillis());
                    continue;
                }
                // Ownership handoff — see the class javadoc. Install, then re-check `closed`:
                // whichever of this thread and close() wins the getAndSet(null) closes the session.
                delegate.set(fresh);
                if (closed.get()) {
                    closeQuietly(delegate.getAndSet(null));
                    return;
                }
                connected.countDown();
                log.info("mesh session established");
                return;
            }
        }, "sm-mesh-connect");
        this.connector.setDaemon(true);
        this.connector.start();
    }

    @Override
    public void accept(String topic, String body) {
        BiConsumer<String, String> d = delegate.get();
        if (d == null) {
            // Lands in JcsmpEventPublisher.publish's counting catch — see the class javadoc.
            throw new IllegalStateException("mesh session not yet established — still connecting");
        }
        d.accept(topic, body);
    }

    boolean isConnected() {
        return delegate.get() != null;
    }

    /** Test/IT seam: true once the background connect has succeeded within {@code timeout}. */
    boolean awaitConnected(Duration timeout) throws InterruptedException {
        return connected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Test seam: true once the retry thread has ended (connected, interrupted, or closed). */
    boolean awaitRetirement(Duration timeout) throws InterruptedException {
        connector.join(timeout.toMillis());
        return !connector.isAlive();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;   // idempotent: a second closer must not close a session the first already did
        }
        connector.interrupt();
        closeQuietly(delegate.getAndSet(null));
    }

    private static void closeQuietly(BiConsumer<String, String> transport) {
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("error closing mesh transport", e);
            }
        }
    }
}
```

The four close/connect interleavings the handoff must survive (close fully-before-install, close
fully-after-install, close between factory-return and install, close between install and re-check) all
resolve to exactly one `getAndSet(null)` winner; the reviewer should walk them against the code.

- [ ] **Step 4: Extend `JcsmpEventPublisher` — counter-sharing constructor and the factory**

Add the counter-sharing constructor and re-point the existing seam constructor at it (the public
surface of the existing constructors is unchanged):

```java
    /**
     * Test seam: hands (topic, body) to an arbitrary transport. Package-private on purpose — the
     * supported production entry points are {@link #JcsmpEventPublisher(MeshProperties)} (eager,
     * caller-constructed) and {@link #connectingInBackground(MeshProperties)} (the Spring path).
     */
    JcsmpEventPublisher(BiConsumer<String, String> transport) {
        this(transport, new AtomicLong());
    }

    /**
     * Shares the drop counter with a transport whose internals are themselves drop sites — the
     * {@link MeshProperties} constructor and {@link #connectingInBackground} both need this shape.
     */
    JcsmpEventPublisher(BiConsumer<String, String> transport, AtomicLong dropped) {
        this.dropped = dropped;
        this.transport = transport;
    }
```

(Do NOT rewrite the `MeshProperties` constructor as `this(...)` delegation — it must build the counter
before the transport, which `this(...)` cannot express, exactly as its existing comment explains. Keep
its body as-is; it already conforms to the two-field shape.)

Add the factory:

```java
    /**
     * The Spring-registration entry point: a publisher over a {@link ReconnectingTransport} that
     * starts disconnected and connects in the background — a broker that is down at startup costs
     * counted drops, never a failed application boot. This resolves the bean-registration question
     * the class javadoc used to leave open.
     */
    public static JcsmpEventPublisher connectingInBackground(MeshProperties props) {
        AtomicLong dropped = new AtomicLong();
        ReconnectingTransport transport = new ReconnectingTransport(
                () -> new JcsmpTransport(props, dropped),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(30));
        return new JcsmpEventPublisher(transport, dropped);
    }
```

Rewrite the class javadoc's "Bean-registration decision, deliberately left open here" paragraph: the
decision is now made — the `MeshProperties` constructor remains the eager, caller-constructed path
(right for tools and ITs that want fail-fast); Spring registration goes through
`connectingInBackground` via `MeshBridgeConfiguration` (Task 6), which starts disconnected and retries
with backoff. Keep the paragraph's *reasoning* (why eager-in-a-singleton was wrong); change its tense
from open question to made decision.

- [ ] **Step 5: JCSMP channel reconnection for mid-session outages (`I2`-verified)**

In `JcsmpTransport`'s `MeshProperties` constructor, after building `JCSMPProperties`, configure the
SDK's own mid-session reconnection:

```java
            JCSMPChannelProperties channel = (JCSMPChannelProperties)
                    jcsmp.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
            channel.setReconnectRetries(-1);              // mid-session: retry until told to stop
            channel.setReconnectRetryWaitInMillis(3000);  // matches the SMF keepalive cadence G1 measured
```

**Mandatory verification before writing this:** read the property and setter names out of the shipped
`sol-jcsmp-10.30.1.jar` (`javap com.solacesystems.jcsmp.JCSMPChannelProperties` against the jar on the
local Maven repo path, or unzip and read), the same way the reactor pom's netty note was verified. If
the names or the `-1 = forever` semantics differ from this snippet, stop and report the measured facts
— do not improvise a variant. `connectRetries` stays at its default: the *initial* connection belongs
to `ReconnectingTransport`, and giving it to JCSMP too would make `connectFactory.get()` block
indefinitely inside the retry thread's first attempt, which turns our backoff into dead code.

- [ ] **Step 6: Run to green**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: BUILD SUCCESS; dp-core 30 → 36 (`ReconnectingTransportTest` ran 6). All pre-existing tests
untouched and green — the constructor chain refactor must not move any message or behaviour (the kit
and `EventPublisherContractTest` prove it). The two close-race tests are themselves prove-it-fires
material: as a one-off demo, delete the connector's post-install `if (closed.get())` re-check and rerun
— `closeDuringAnInFlightConnectClosesTheSessionThatConnectProduces` must go red on the leaked session
(closedCount 0), attributed; restore and rerun to green. Record both outputs.

- [ ] **Step 7: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/ReconnectingTransport.java \
        backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisher.java \
        backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/ReconnectingTransportTest.java
git commit -m "Give the mesh session a start-disconnected reconnecting lifecycle"
```

---

### Task 6: `MeshBridgeConfiguration` — one bean graph, context-proven

The composition Spring will own: `EventPublisher` = `AfterCommit(SpringBoundary, Jcsmp(Reconnecting))`.
No application class exists yet and none is created here — the future walking-skeleton app `@Import`s
this configuration; until then the context tests are its consumer.

**Files:**
- Create: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/MeshBridgeConfiguration.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/MeshBridgeConfigurationTest.java`
- Modify: `backend/dp-core/pom.xml` (add `spring-boot-starter-test`, test scope)

**Interfaces:**
- Consumes: `MeshProperties`, `JcsmpEventPublisher.connectingInBackground`, `SpringTransactionBoundary`,
  `AfterCommitEventPublisher`.
- Produces: beans `meshEventPublisher` (`JcsmpEventPublisher`) and `eventPublisher` (`EventPublisher`) —
  the names future domain code injects.

- [ ] **Step 1: Add the test dependency**

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

Conventional and BOM-managed; wanted for `ApplicationContextRunner`, which handles property-source
plumbing and guaranteed context cleanup. The transitively included libraries this module does not use
(Mockito, AssertJ) stay unused — do not start using them; JUnit assertions remain the house style.

- [ ] **Step 2: Write the failing tests**

```java
package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The bean graph under a real context, against a deliberately unreachable broker (port 1) — proving
 * the property that made this composition worth designing: a down broker costs counted drops, never
 * a failed boot and never an exception in a caller (BE-D1, design §9 row 1).
 */
class MeshBridgeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MeshBridgeConfiguration.class)
            .withPropertyValues(
                    "sm.mesh.host=ws://127.0.0.1:1",   // nothing listens on port 1 — connect fails fast
                    "sm.mesh.vpn=sm-vpn",
                    "sm.mesh.client-username=sm-backend");

    private static EventEnvelope healthFixture() throws IOException {
        return EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));
    }

    @Test
    void aDownBrokerDoesNotFailContextStartupAndPublishesBecomeCountedDrops() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(),
                    "BE-D1: a broker that is down at startup must never block boot");

            EventPublisher p = context.getBean(EventPublisher.class);
            assertInstanceOf(AfterCommitEventPublisher.class, p,
                    "the injected seam must be the after-commit composition, not the raw transport");

            EventEnvelope event = healthFixture();
            assertDoesNotThrow(() -> p.publish(event),
                    "a publish against a dead broker is freshness loss, never a caller failure");
            assertEquals(1L, context.getBean(JcsmpEventPublisher.class).droppedCount(),
                    "the drop must be counted on the one shared counter");
        });
    }

    @Test
    void thePropertiesBindFromSmMeshAndKeepTheirValidation() {
        runner.run(context -> {
            MeshProperties props = context.getBean(MeshProperties.class);
            assertEquals("sm-vpn", props.vpn());
            assertEquals("", props.password(), "an absent password binds to empty, never null");
        });
        new ApplicationContextRunner()
                .withUserConfiguration(MeshBridgeConfiguration.class)
                .withPropertyValues("sm.mesh.host=ws://127.0.0.1:1")   // vpn + username missing
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "MeshProperties' own requireText must still refuse an incomplete binding"));
    }

    @Test
    void contextCloseRetiresTheConnectThread() throws InterruptedException {
        runner.run(context -> assertNotNull(context.getBean(JcsmpEventPublisher.class),
                "the bean must exist before the context closes around it"));
        // The runner closed the context on lambda exit — and NOTHING in this test closed the
        // publisher by hand, deliberately: retirement below is therefore proof of the CONTEXT's
        // destroy hook (Spring's AutoCloseable inference on the @Bean), not of a close() the test
        // itself made. An earlier draft called publisher.close() inside the lambda and measurably
        // masked that proof — with @Bean(destroyMethod = "") it stayed green. (Manual-close
        // idempotency is ReconnectingTransportTest's unit-level property, not re-proven here.)
        // The interrupt only lands once any in-flight connect attempt returns, so give it a
        // patience window rather than racing it.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (connectThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!connectThreadAlive(),
                "context shutdown must retire the background connect thread — a survivor here leaks "
                        + "one thread per context restart");
    }

    private static boolean connectThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && "sm-mesh-connect".equals(t.getName()));
    }
}
```

- [ ] **Step 3: Run to red**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: COMPILATION ERROR — `MeshBridgeConfiguration` does not exist.

- [ ] **Step 4: Implement the configuration**

```java
package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The mesh publish path as one bean graph (backend design §2/§3, {@code BE-D1}):
 * {@code EventPublisher} = after-commit ordering over JCSMP over a reconnecting session that starts
 * disconnected. Domain code injects {@link EventPublisher} and nothing else; the
 * {@link JcsmpEventPublisher} bean exists separately so its drop counter stays reachable for the
 * metrics plan without widening the seam.
 *
 * <p>Not auto-configured: the future application class {@code @Import}s this deliberately. Nothing
 * here is auth-bearing — this is the server's own publish identity, not the browser path.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeshProperties.class)
public class MeshBridgeConfiguration {

    /** Closed by the context on shutdown ({@code AutoCloseable} — retires the connect thread too). */
    @Bean
    JcsmpEventPublisher meshEventPublisher(MeshProperties props) {
        return JcsmpEventPublisher.connectingInBackground(props);
    }

    /**
     * {@code @Primary} is load-bearing, not decoration (measured at execution):
     * {@code JcsmpEventPublisher} is itself an {@code EventPublisher}, so without it BOTH beans
     * answer a by-type injection and the context throws {@code NoUniqueBeanDefinitionException} —
     * and the class javadoc's contract ("domain code injects {@code EventPublisher} and nothing
     * else") would be false. The primary is the composed, after-commit-ordered graph; injecting
     * the raw transport must be a deliberate by-type-and-name act.
     */
    @Bean
    @Primary
    EventPublisher eventPublisher(JcsmpEventPublisher meshEventPublisher) {
        return new AfterCommitEventPublisher(meshEventPublisher, new SpringTransactionBoundary());
    }
}
```

`I2` note: `@ConfigurationProperties` on a record binds by constructor in Boot 3.x with
`@EnableConfigurationProperties` — the Step 2 binding test is the proof; if it fails on binding
machinery rather than on the missing class, stop and report before changing `MeshProperties`.

- [ ] **Step 5: Run to green**

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: BUILD SUCCESS; dp-core 36 → 39. Confirm the three new tests ran and the drop assertion passed
against the real composed graph. Prove-it-fires for the destroy hook (required): as a one-off demo,
change the bean to `@Bean(destroyMethod = "")`, rerun — `contextCloseRetiresTheConnectThread` must go
red at its full patience budget (~10s); restore, rerun to green. A retirement test that stays green
with destruction disabled is testing its own close call, not the context's.

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core/pom.xml \
        backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/MeshBridgeConfiguration.java \
        backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/MeshBridgeConfigurationTest.java
git commit -m "Wire the after-commit mesh publish path as one Spring bean graph"
```

---

### Task 7: Compose-gated IT, documentation sweep, final gates

**Files:**
- Create: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/MeshLifecycleIT.java`
- Modify: `backend/README.md` (status paragraph: plan 003 executed — what now exists)
- Modify: `docs/design/backend/decisions.md` (changelog row only — no decision text changes)
- Modify: this plan (Results section)

**Interfaces:** consumes everything built above; produces the plan's completion record.

- [ ] **Step 1: Write the IT**

The full class. Its broker constants (port, VPN, identity, subscription) are deliberately the same
spike-harness facts `JcsmpEventPublisherIT` documents at length — read that file's class javadoc for
their provenance (committed `provision.sh`, `ws://` support verified in the 10.30.1 jar), which is not
restated here; the values themselves are:

```java
package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import com.solace.sm.events.publisher.TransactionBoundary;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The composed publish path — {@code AfterCommit(Jcsmp(Reconnecting))}, the exact graph
 * {@link MeshBridgeConfiguration} wires — against a live broker (the G1 spike compose stack).
 *
 * <p>What it proves that no unit test can: with a real session establishing in the background,
 * <b>every publish attempt is exactly one of counted-drop or delivered</b>. Attempts made before the
 * background connect completes throw into the counting swallow site ({@code BE-D1} freshness loss);
 * attempts made after it reach the broker and arrive on the manifest topic with the exact contract
 * bytes. An attempt that is neither — vanished without a count — is the silent failure mode this
 * whole lifecycle exists to prevent.
 *
 * <p>Gated exactly like {@link JcsmpEventPublisherIT}: skipped, visibly, unless {@code broker.host}
 * is set (lesson R5 — confirm the failsafe summary says Skipped rather than the class being absent).
 * Broker constants are the same committed spike-harness facts that class documents; plaintext
 * {@code ws} and a throwaway credential are spike facts, not the production shape.
 */
@Tag("broker")
@EnabledIfSystemProperty(named = "broker.host", matches = ".+",
        disabledReason = "needs the G1 spike broker: run with -Dbroker.host=localhost")
class MeshLifecycleIT {

    /** The SMF-WebSocket port {@code backend/spikes/mesh-authz/docker-compose.yml} maps. Not 55555. */
    private static final int SMF_WEBSOCKET_PORT = 8008;

    private static final String VPN = "sm-spike";
    /** Spike-local throwaway credential, committed in provision.sh. Never a production secret. */
    private static final String INTERNAL_USER = "sm-internal";
    private static final String INTERNAL_PASS = "sm-internal";

    private static final String SUBSCRIPTION = "sm/v1/acme/notify/>";
    private static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");

    /** No transaction in this IT: the deferred path is unit-proven; here the wire is the subject. */
    private static final TransactionBoundary NOT_IN_TRANSACTION = new TransactionBoundary() {
        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public void afterCommit(Runnable action) {
            throw new IllegalStateException("afterCommit outside a transaction is a decorator bug");
        }
    };

    /** What the consumer saw, copied off JCSMP's pooled message on the callback thread. */
    private record Delivered(String topic, String body) {
    }

    @Test
    void everyPublishAttemptIsExactlyOneOfCountedDropOrDelivered() throws Exception {
        String host = "ws://" + System.getProperty("broker.host") + ":" + SMF_WEBSOCKET_PORT;

        String fixtureText = Files.readString(FIXTURES.resolve("health-transition.json"));
        EventEnvelope health = EventJson.reader().readValue(fixtureText);
        String expectedTopic = EventJson.mapper()
                .readTree(FIXTURES.resolve("expected-topics.json").toFile())
                .get("health-transition.json").asText();
        String expectedBody = EventJson.write(health);

        BlockingQueue<Delivered> received = new ArrayBlockingQueue<>(64);

        JCSMPSession consumerSession = openSession(host);
        try (JcsmpEventPublisher meshPublisher = JcsmpEventPublisher.connectingInBackground(
                new MeshProperties(host, VPN, INTERNAL_USER, INTERNAL_PASS))) {

            XMLMessageConsumer consumer = consumerSession.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    // JCSMP hands the listener a pooled message that it may recycle once this
                    // method returns, so both fields are copied out here, on the callback thread.
                    String body = message instanceof TextMessage text
                            ? text.getText()
                            : new String(message.getBytes(), StandardCharsets.UTF_8);
                    received.offer(new Delivered(message.getDestination().getName(), body));
                }

                @Override
                public void onException(JCSMPException e) {
                    // Surfaces below as a poll timeout; this line says why.
                    System.err.println("consumer error: " + e);
                }
            });
            consumerSession.addSubscription(
                    JCSMPFactory.onlyInstance().createTopic(SUBSCRIPTION), true);
            consumer.start();

            // Publish through the composed seam while the background connect is (possibly still)
            // in flight — no awaitConnected: the point is that the CALLER needs no readiness
            // protocol. Whether attempt 1 lands pre- or post-connect is timing; the invariant
            // asserted below is not. Stop at first receipt so the arithmetic stays exact.
            EventPublisher publisher =
                    new AfterCommitEventPublisher(meshPublisher, NOT_IN_TRANSACTION);
            int attempts = 0;
            Delivered got = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (got == null && System.nanoTime() < deadline) {
                publisher.publish(health);   // must never throw, connected or not (BE-D1)
                attempts++;
                got = received.poll(200, TimeUnit.MILLISECONDS);
            }
            assertNotNull(got, "no publish attempt got through within 30s of background connecting");

            assertEquals(expectedTopic, got.topic(),
                    "must publish on the topic contracts/events/fixtures/expected-topics.json declares");
            assertEquals(expectedBody, got.body(),
                    "consumed body must be exactly what EventJson.write emitted");

            // Drain attempts that were sent in the same window as the first receipt, then balance
            // the books. Every attempt either threw into the counting catch (a drop) or was sent;
            // over loopback with the subscription established before the first publish, a sent
            // DIRECT message arrives — the same delivery assumption JcsmpEventPublisherIT already
            // makes. A send failure after connect stays balanced too: counted AND undelivered.
            int delivered = 1;
            while (received.poll(2, TimeUnit.SECONDS) != null) {
                delivered++;
            }
            assertEquals((long) (attempts - delivered), meshPublisher.droppedCount(),
                    "attempts=" + attempts + " delivered=" + delivered
                            + ": every attempt must be exactly one of counted-drop or delivered — "
                            + "one that is neither vanished silently, the failure mode BE-D1 forbids");
        } finally {
            consumerSession.closeSession();
        }
    }

    private static JCSMPSession openSession(String host) throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, host);
        props.setProperty(JCSMPProperties.VPN_NAME, VPN);
        props.setProperty(JCSMPProperties.USERNAME, INTERNAL_USER);
        props.setProperty(JCSMPProperties.PASSWORD, INTERNAL_PASS);
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props);
        session.connect();
        return session;
    }
}
```

Run it live if the compose stack is up:

```bash
docker compose -f backend/spikes/mesh-authz/docker-compose.yml up -d
mvn -f backend/pom.xml -pl dp-core -am verify -Dbroker.host=localhost
```

Expected: failsafe reports `MeshLifecycleIT` ran and passed (and `JcsmpEventPublisherIT` alongside it).
If the stack is not up, report both as skipped-with-condition and say so — a skipped IT is never
counted as proof.

- [ ] **Step 2: Documentation sweep**

- `backend/README.md`: extend the status paragraph — plan 003 executed: after-commit ordering enforced
  at the seam, mesh session starts disconnected and reconnects, `MeshBridgeConfiguration` is the bean
  graph, publisher inventory + JCSMP confinement enforced in arch-tests.
- `docs/design/backend/decisions.md`: one changelog row, dated — plan 003 delivered `BE-D1` §3's
  after-commit ordering as `AfterCommitEventPublisher` + `TransactionBoundary` (mesh-events) with the
  Spring adapter in dp-core; resolved `JcsmpEventPublisher`'s open bean-registration question
  (reconnecting transport, starts disconnected); landed §5.2's executable publisher inventory and design
  §2's JCSMP confinement as ArchUnit gates. No decision text changed; no new decision needed — all four
  are implementations of already-written design sentences.
- Sweep for stale sentences this plan obsoletes (lesson R6):
  `grep -rn "walking-skeleton plan" backend/ docs/` — after Task 5's javadoc rewrite the only survivors
  must be ones that still tell the truth (the walking skeleton still owns the app class, login, tickets).
  `grep -rn "the metrics plan" backend/` — must still stand (this plan added no metrics).

- [ ] **Step 3: Final gates**

```bash
./contracts/run-checks.sh --strict        # ALL CHECKS PASSED — contracts/ untouched by this plan
mvn -f backend/pom.xml clean verify       # BUILD SUCCESS; reconcile the final count line by line
git diff --check                          # clean
git log --oneline <base>..HEAD            # one commit per task boundary
```

Expected final counts: mesh-events 127 / dp-core 39 / arch-tests 19 = **185**, with `MeshLifecycleIT`
and `JcsmpEventPublisherIT` reported skipped-with-condition (or run live if the stack is up). If any
number differs, reconcile line-by-line in the Results section before declaring done.

- [ ] **Step 4: Results section + commit**

Fill in this plan's Results section: per-task completion, deviations, prove-it-fires transcript
references, final gate outputs. Commit the IT and docs:

```bash
git add backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/MeshLifecycleIT.java \
        backend/README.md docs/design/backend/decisions.md docs/plans/backend/003-publish-path-lifecycle.md
git commit -m "Prove the composed publish path live and record plan 003's completion"
```

---

## Out of scope — explicit boundaries

- **No auth, no session, no ticket, no RBAC, no endpoint.** G2 (`D10` amendment, open item 24) still
  gates all of it. This plan adds no HTTP surface of any kind.
- **No browser/mesh-access work.** Open items 32/33 still gate `BE-D5` ratification; nothing here
  touches subscribe authorization, ACLs, JWKS, or the gateway.
- **No real notify emission from product state.** There is no product state. The publish path is built
  and certified; its first real caller arrives with the (G2-gated) walking skeleton.
- **No database, no schema, no outbox table.** The after-commit decorator is transaction-*aware*, not
  database-dependent — its tests run on a resourceless transaction manager. The outbox stays the named
  future upgrade behind the same seam.
- **No metrics.** `droppedCount()` exposure stays with the metrics plan; no Micrometer dependency.
- **No contract changes.** Zero files under `contracts/` move; the schema locks stay byte-identical.
- **No TypeScript/frontend.** The IEEE-754 `Long.MAX_VALUE` watch item stays routed to the TS-consumer
  work, untouched.
- **No consumer/subscriber path.** Backend event *consumption* (internal events, MI telemetry ingest)
  waits for a real consumer: design §16 Q6 (internal namespace) and the `BE-D9` schema extension both
  sit with later decisions.

## Deviations from the requested candidate scope, made deliberately

1. The requested candidate named "event consumption/publishing integration". This plan is
   **publish-side only** — the consumption side has no non-speculative backend consumer in v1 (design
   §16 Q6), and building a reader for the browser's benefit belongs to the TS/AsyncAPI work behind the
   `BE-D4` amendment.
2. The bean-registration decision was role-assigned to "the walking-skeleton plan" by
   `JcsmpEventPublisher`'s javadoc. This plan takes it over because the decision is gate-independent
   while the walking skeleton is G2-gated; the javadoc sentence is rewritten in Task 5 so no stale claim
   survives (lesson R6).

## Final self-review checklist (run before the plan is declared done — and re-run at execution end)

- [ ] **Spec coverage:** `BE-D1` §3 ordering → Tasks 1/4; design §9 row 1 (broker down ≠ boot failure)
      → Tasks 5/6; design §2 "no other module holds a broker session" → Task 3; design §5.2 inventory
      trigger → Tasks 2/3. No other spec sentence is claimed.
- [ ] **Dependency/gate coverage:** every task re-checked against G1-residue/G2/`BE-D4` — none touches
      auth, browser mesh access, contracts, or TS. `I5`: no tenant enters from any client anywhere in
      this plan (the only tenant values are fixture constants). `I6`: no secret in any event payload,
      topic, fixture, log assertion, or test property (`MeshProperties.toString` redaction untouched;
      test contexts set no password). `I7`: new mesh-events code imports no plane package; new dp-core
      code imports no cp package; existing ArchUnit rules stay green and two new ones land.
- [ ] **Placeholder scan:** no TBD/TODO/"similar to Task N", and no `...` inside any code block —
      every class and test in this plan, Task 7's IT included, is complete compilable code. (The IT's
      broker constants are stated in full; only their *provenance story* points at
      `JcsmpEventPublisherIT`'s javadoc, which is a reading pointer, not a code gap.)
- [ ] **Type/signature consistency:** `TransactionBoundary` (Task 1) is what Task 4 implements and
      Task 2/6 consume; `delegate()` (Task 1) is what Tasks 2/6 unwrap;
      `connectingInBackground(MeshProperties)` (Task 5) is what Task 6 wires and Task 7's IT
      constructs; `awaitConnected(Duration)`/`awaitRetirement(Duration)` (Task 5) are used by Task 5's
      tests only — Task 7's IT deliberately verifies readiness behaviorally, by publish/receive.
- [ ] **Stale-wording scan:** Task 5 rewrites the bean-registration javadoc paragraph; Task 3 rewrites
      both §5.2 "enforcement honesty" paragraphs (design + kit javadoc); Task 7 greps for survivors of
      "walking-skeleton plan"/"the metrics plan" and reconciles each.
- [ ] **No auth-bearing or mesh-access work scheduled before gates:** confirmed per task; the plan's
      Gate map states it and the Out-of-scope section enforces it.
- [ ] **No new schema keyword or event-contract surface without a lock update:** trivially satisfied —
      zero contract files change; if any task discovers it needs one, the plan is wrong and execution
      stops.

## Results

**Status: EXECUTED.** All seven tasks implemented and independently reviewed (Opus 5 throughout,
implementer and reviewer separate) and integrated on `contracts-backend-consumption` — Task 7's own
review closed last, and its verdict plus the two doc-line fixes it required landed in the closing fix
commit, so the table row below carries evidence, not anticipation. Commit range
`1dedc47..` (plan review-ready) → the Task 7 commit; not pushed, not merged. The authoritative
blow-by-blow — every dispatch, verdict, ruling and measurement — is the execution ledger at
`.superpowers/sdd/003-publish-path-lifecycle/progress.md`; this section is its distillation.

### Per-task completion record

| Task | Commit(s) | Red | Green | Prove-it-fires | Review verdict | Fix waves |
|---|---|---|---|---|---|---|
| 1 — `TransactionBoundary` + `AfterCommitEventPublisher` (mesh-events) | `c7f5781` | compile error: both types absent | mesh-events 120 → **127**; `AfterCommitEventPublisherTest` ran 7 | eager `validate` moved into the else-branch → exactly `aShapeInvalidEventIsRefusedEagerlyInsideTheTransaction` red ("nothing was thrown"); reverted, green | SPEC PASS (three files byte-identical, diffed not read) · QUALITY APPROVED · 0 MAJOR / 2 MINOR / 2 INFO; reviewer independently reproduced the mutation **and** added a guard-order mutation proving `topicFor`-first is pinned | none |
| 2 — kit run over the composed stack (dp-core) | `7d2559e` (worktree) → **`bb43eec`** | n/a (green by construction; the red case is Step 3) | dp-core 14 → **26**; `AfterCommitEventPublisherContractTest` ran 12 | eager `validate` commented out → mesh-events red on the one deferred-path test; dp-core **still 26/0 green** under `-Dmaven.test.failure.ignore=true`, proving the division of labour rather than a hole | SPEC PASS 11/11 (md5-identical plan → brief → file) · QUALITY PASS · 0 blocking / 0 MAJOR / 0 MINOR / 4 INFO | none |
| 3 — publisher-inventory + JCSMP-confinement gates (arch-tests) + §5.2 honesty | `223346b` (worktree) → **`9da747a`** | inventory entry delisted → `everyEventPublisherImplementationIsInTheCertifiedInventory` red, naming `AfterCommitEventPublisher` in `but was:` | arch-tests 15 → **19** | both planted fixtures verified against the **real** messages: the JCSMP rule fails on exactly `BadCpJcsmpClass.plantedViolation()`; the unfiltered scan sees exactly 3 implementations, the filtered scan exactly 2 | SPEC PASS (5 files; the kit edit proved javadoc-only mechanically; §5.2 blockquote byte-identical) · QUALITY PASS · 0 Critical/High/Medium, 2 Low, 1 Nit | none |
| 4 — `SpringTransactionBoundary` (dp-core) | `2b72b66` (worktree) → **`707d32b`** | 4× cannot-find-symbol, on the missing class only | dp-core 26 → **30**; `SpringTransactionBoundaryTest` ran 4 | reviewer's own mutation pass in a throwaway copy killed both alternatives: publishing from `afterCompletion` (the rollback test bites) and removing deferral (the in-lambda assertion propagates — the vacuity hypothesis was investigated and cleared) | SPEC PASS (md5-identical; `spring-tx` BOM-managed 6.2.19) · QUALITY PASS · 0 MAJOR / 2 MINOR / 2 INFO | none |
| 5 — `ReconnectingTransport` + `connectingInBackground` (dp-core) | `e2f0992` (worktree) → **`5679a3c`** | compile error: `ReconnectingTransport` absent | dp-core 30 → **36**; `ReconnectingTransportTest` ran 6 | the connector's post-install `if (closed.get())` re-check deleted → `closeDuringAnInFlightConnectClosesTheSessionThatConnectProduces` red (`expected 1 but was 0`), sole failure; restored sha-identical, green. 12/12 repeat runs, no flake | SPEC PASS (both new files sha-identical; exactly six `-U0` hunks in `JcsmpEventPublisher`) · QUALITY PASS · 0 MAJOR / 2 MINOR / 7 NOTE; reviewer hunted a fifth close/connect interleaving and found none | none |
| 6 — `MeshBridgeConfiguration` (dp-core) | `b0a20b8` + fix `f936cb7` | cannot-find-symbol at both `withUserConfiguration` sites | dp-core 36 → **39**; `MeshBridgeConfigurationTest` ran 3 | `@Bean(destroyMethod = "")` → `contextCloseRetiresTheConnectThread` red at budget exhaustion (10.22 s), sole failure; restored green in 1.458 s. Reviewer reproduced: red 10.187 s / green 1.448 s; flake probe 8/8 green (~1.0–1.3 s against a 10 s budget) | SPEC PASS (3 files, 149 insertions; test byte-identical) · QUALITY PASS · 1 MEDIUM / 3 LOW / 2 INFO — APPROVED | **1** (F1, below) |
| 7 — `MeshLifecycleIT`, documentation sweep, final gates | this commit | n/a (the IT is the plan's final proof, not a red-first step) | failsafe **2/2 live, 0 failures, 0 skipped**: `JcsmpEventPublisherIT` **and** `MeshLifecycleIT` both ran against the G1 spike broker | the live run is itself the proof: attempt 1 landed pre-connect and was WARN-logged and counted (`IllegalStateException: mesh session not yet established — still connecting`, through `ReconnectingTransport.accept` → `JcsmpEventPublisher.publish`'s counting catch), attempt 2 landed post-connect and arrived on the manifest topic with byte-exact contract bytes | SPEC PASS · QUALITY PASS, 0 MAJOR / 2 MINOR; Results audit 43/44 claims verified against ledger+git; reviewer independently re-ran the live IT (attempts 2 / delivered 1 / dropped 1, balance exact) and all three gates | the closing fix commit: the two MINOR doc lines (this table row + the Results headline; the README's §5.2 citation re-homed to `backend-consumption.md`) |

**Task 6's fix wave (F1, MEDIUM).** The reviewer measured that the retirement test's in-lambda manual
`publisher.close()` masked what the test claimed to prove: it stayed **green with
`@Bean(destroyMethod = "")`** (2×2 runs), i.e. it was testing its own close call, not the context's
destroy hook. The plan was amended **first** (`8052a40`), then the implementer resumed and re-landed the
test (`f936cb7`); the re-review reproduced red-then-green independently and closed F1. F2/F3 (LOW) were
accepted with the reviewer's own measurements (property reordering passes; the failure chain terminates
at `requireText`) and needed no code change. F4/F5 (INFO) became watch items, below.

### Plan amendments made during execution — four, each with its measured reason

| Commit | Amendment | Measured reason |
|---|---|---|
| `7aee886` | Task 3 Step 5's expected verify total corrected from 158 to **172** | Arithmetic slip found in the pre-flight scan: 127 + 26 + 19 = 172. Committed **before** the wave branched, so all four worktrees carried the corrected plan. Cost if wrong: none — number only. |
| `130bf9d` | Task 2 Step 3's second command gains `-Dmaven.test.failure.ignore=true`, with the reactor-halt note | The implementer measured that the step as written was **unobservable**: `-am` rebuilds mesh-events first, whose (intended) red halts the reactor before dp-core runs at all — dp-core reports `SKIPPED`, so "dp-core still green" cannot be seen. With the flag, mesh-events' `[ERROR] Tests run: … Failures: 1` lines survive in the same output and only the halt is suppressed. `-fae` was measured **not** a substitute (dp-core still `SKIPPED`). The reviewer upheld the flag run and proved the mutation was live in the run by disassembling `target/classes`. |
| `c0c6c60` | Task 6's configuration snippet gains `@Primary` on the composed `eventPublisher` bean | Measured snippet defect: `JcsmpEventPublisher` **is itself** an `EventPublisher`, so both beans answer a by-type lookup and `getBean(EventPublisher.class)` threw `NoUniqueBeanDefinitionException`. The reviewer found the hazard is worse than the exception: a parameter *named* `meshEventPublisher` typed `EventPublisher` resolves **silently to the raw transport**, bypassing after-commit ordering. `@Primary` is the only candidate that keeps the class javadoc's contract true *and* removes the silent-wrong-bean path; reversible in one line. |
| `8052a40` | Task 6's test snippet loses the in-lambda manual close; Step 5 gains a **required** `destroyMethod = ""` prove-it-fires | See F1 above — measured green with destruction disabled, 2×2 runs. A retirement test that survives its own subject being switched off is proving nothing. |

### Deviation: Tasks 2–5 executed as a parallel wave in isolated worktrees

At the user's instruction, Tasks 2–5 ran **concurrently** rather than sequentially — four implementers
plus per-task reviewers, all Opus 5. Mechanics and consequences, recorded because they change how the
plan's own numbers read:

- **Isolation.** Worktrees at `<session scratchpad>/worktrees/plan003-t{2,3,4,5}` (deliberately not
  `.worktrees/`, which is not gitignored in this repo), branches `plan003-task{2,3,4,5}`, all based at
  `7aee886`. A file-ownership re-check confirmed the four tasks share **no file** (only Task 4 touches
  `dp-core/pom.xml`; Task 2's mutation is temporary and reverted inside its own worktree).
- **Counts are sequential-baseline in this plan and had to be re-derived per worktree.** Each dispatch
  carried its own expectation: T2 dp-core 14 → 26; T3 arch-tests 15 → 19 with a worktree full verify of
  **160**, not 172; T4 dp-core 14 → 18 (invariant: `SpringTransactionBoundaryTest` ran 4); T5 dp-core
  14 → 20 (invariant: `ReconnectingTransportTest` ran 6). The plan's written cumulative numbers were
  restored by integration order, not by any worktree.
- **Integration.** Cherry-picked **sequentially, 2 → 3 → 4 → 5**, each with its own verification:
  `bb43eec` (dp-core 26) → `9da747a` (clean verify 172 = 127/26/19; the inventory ↔ kit-subclass pairing
  re-verified on-branch, since Task 3 lists a class whose kit subclass is Task 2's) → `707d32b`
  (dp-core 30) → `5679a3c` (dp-core 36). All four conflict-free.
- **Wave gates, all green:** `./contracts/run-checks.sh --strict` → ALL CHECKS PASSED ·
  `mvn -f backend/pom.xml clean verify` → BUILD SUCCESS, **182** (127/36/19, both ITs
  skipped-with-condition) · `git diff --check` clean. Worktrees removed and task branches deleted after
  integration.
- **One worktree-only environment note, adjudicated not-a-blocker:** `run-checks.sh --strict` exits 1 in
  a fresh worktree because `contracts/view-models/node_modules` is gitignored and absent. Zero contract
  files were modified by any task; the strict gate runs in the main checkout, where it passes.

### Watch items carried forward — routed, not dropped

| Item | Origin | Home |
|---|---|---|
| `awaitConnected` can report **connected on a closed transport**: the connector may `countDown()` on an interleaving where `close()` has already taken the delegate | T5 review, MINOR (a finding against the byte-mandated snippet, so deliberately not fixed in that diff) | The **walking-skeleton plan** — first owner of a real application lifecycle around this bean. No production caller uses the seam today; it is test/IT-only. |
| `close()` interrupts the connector but **never joins** it, so teardown can complete after `close()` returns | T5 review, MINOR | Same — **walking-skeleton plan** / SM packaging shutdown work, where a bounded join has an owner and a budget. |
| A **lambda or anonymous** `EventPublisher` would slip the inventory check — ArchUnit sees no class file for a SAM instance | T3 review, Low (b) | The design's §5.2 wording in [`../../design/common/contracts/backend-consumption.md`](../../design/common/contracts/backend-consumption.md) — a wording question about what the inventory promises, not a defect in the gate as specified. |
| `SpringTransactionBoundary`'s **degradation paths are prose-only**: `SYNCHRONIZATION_NEVER` (real transaction, no synchronization) and the empty synchronized scope are stated in the javadoc, untested; and the first conjunct is redundant under `AbstractPlatformTransactionManager` (defensive by choice) | T4 review, 2 MINORs | The first **persistence/database plan** — the paths need a real `PlatformTransactionManager` with a resource to be worth exercising. |
| ~~`@Primary` **does not disambiguate** `List<EventPublisher>` / `ObjectProvider` injection — a collection injection point would receive both beans and double-publish~~ **CLOSED in the R1 fix wave** (below). The routing to the metrics plan was **wrong and is overturned**: the external review re-homed it to *this* plan, which created the bean graph and therefore owns the hazard. | T6, implementer + review F4 (INFO) → **R1 external review, re-scored MAJOR** | ~~metrics plan~~ → **fixed here**: `@Bean(defaultCandidate = false)` + `@Qualifier`, guarded by `collectionInjectionSeesExactlyTheComposedPublisher`. |
| **Deliberate** qualifier bypass — the residual the accepted mechanism leaves: `defaultCandidate = false` bars *unqualified* injection, so a future service could still reach the raw pre-commit transport by explicitly writing `@Qualifier("meshEventPublisher") EventPublisher`. Accepted as a **stated limit** (greppable, deliberate, review-policeable), not a defect | R1 external review fix wave, coordinator ruling | **Review discipline for now.** Candidate for an **ArchUnit rule** — *no type outside `meshbridge` may declare a `@Qualifier("meshEventPublisher")` injection point* — if the consumer surface grows — the arch-tests module already exists and enforces `I7`, so the rule is cheap the day it is warranted. |
| The second bean (`meshEventPublisher`) may be **removable** — `AfterCommitEventPublisher.delegate()` already reaches the drop counter | T6 review F5 (INFO) | The **metrics plan**, same reason: it is the change that decides whether the concrete type needs a bean at all. |
| The retirement test's non-vacuity **rests on the unreachable `ws://127.0.0.1:1` host** (an in-flight connect is what makes the thread observable); a transport-scoped `awaitRetirement` would decouple it | T6 fix re-review, recorded as a note, not a finding | The **walking-skeleton plan** — the next change to touch `MeshBridgeConfiguration`'s lifecycle; pairs with the two T5 lifecycle items above. |

### Final gate outputs

- **Live IT (Task 7, the plan's headline proof)** — `mvn -f backend/pom.xml -pl dp-core -am verify
  -Dbroker.host=localhost` against the running G1 spike stack (`sm-spike-broker`, port 8008 mapped):

  ```
  [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.303 s -- in com.solace.sm.dp.meshbridge.JcsmpEventPublisherIT
  [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.253 s -- in com.solace.sm.dp.meshbridge.MeshLifecycleIT
  [INFO] Results:
  [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  ```

  The balance the IT asserts, as the run actually played out: **attempts = 2, delivered = 1,
  droppedCount() = 1** — attempt 1 at `23:58:43.597` threw into the counting swallow site
  (`WARN … dropping event 3f1c9a52-…-000000000001 on sm/v1/acme/notify/instance/cache-prod-01/health —
  mesh publish failed`, caused by `IllegalStateException: mesh session not yet established — still
  connecting`), the session established 5 ms later, and attempt 2 arrived on the expected topic with
  `EventJson.write`'s exact bytes. `attempts − delivered == droppedCount()`: **no attempt vanished
  uncounted**, which is the whole point of the composition.

- **`./contracts/run-checks.sh --strict`** → `== RESULT: ALL CHECKS PASSED` (all 10 checks; check 10's
  6 fixtures, 9 negatives, level-safety guard and topic derivation all green). Zero files under
  `contracts/` changed by this plan, as required.

- **`mvn -f backend/pom.xml clean verify`** → **BUILD SUCCESS**, **185 surefire tests**, reconciled
  line by line against the plan's expectation of 127 / 39 / 19:

  | Module | Expected | Measured | Breakdown |
  |---|---|---|---|
  | mesh-events | 127 | **127** | `NotifyEventShapesTest` 65 · `NotifyTopicsTest` 24 · `EnvelopeGoldenTest` 17 · `SchemaMirrorLockTest` 8 · **`AfterCommitEventPublisherTest` 7** · `ContractArtifactsTest` 5 · `PublisherContractKitSelfTest` 1 |
  | dp-core | 39 | **39** | `EventPublisherContractTest` 14 · **`AfterCommitEventPublisherContractTest` 12** · **`ReconnectingTransportTest` 6** · **`SpringTransactionBoundaryTest` 4** · **`MeshBridgeConfigurationTest` 3** |
  | arch-tests | 19 | **19** | `PlaneBoundaryTest` 8 (`@ArchTest`) + 11 (`@Test`) |
  | **Total** | **185** | **185** | |

  Failsafe in this run: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 2` — `JcsmpEventPublisherIT` and
  `MeshLifecycleIT` both **skipped-with-condition** and visibly named, because this invocation carries no
  `-Dbroker.host` (lesson R5: a skipped IT must be visible, never absent). The live evidence is the
  separate `-Dbroker.host=localhost` run above.

- **`git diff --check`** → clean.

- **Commits, one per task boundary** (`1dedc47..HEAD`, plus this commit): `c7f5781` (T1) · `7aee886`,
  `130bf9d` (plan amendments) · `bb43eec` (T2) · `9da747a` (T3) · `707d32b` (T4) · `5679a3c` (T5) ·
  `b0a20b8` (T6) · `c0c6c60`, `8052a40` (plan amendments) · `f936cb7` (T6 fix wave) · this commit (T7).

### Documentation sweep (Task 7 Step 2)

- `backend/README.md`: status paragraph extended with what plan 003 delivered, and the plans row
  corrected — 003 is no longer "WRITTEN, not executed".
- `docs/design/backend/decisions.md`: one dated changelog row. **No decision text changed and no new
  decision was needed** — all four deliverables implement design sentences that were already written
  (`BE-D1` §3, design §2, design §9 row 1, `backend-consumption.md` §5.2).
- **Stale-wording sweep (lesson R6), both greps run and every hit adjudicated:**
  - `grep -rn "walking-skeleton plan" backend/ docs/` → **zero hits under `backend/`**: Task 5's javadoc
    rewrite removed the only dangling role assignment (`JcsmpEventPublisher`'s paragraph now reads
    "Bean-registration decision, **now made**"). Every surviving hit is in `docs/plans/` and every one is
    still true: plan 002's lesson about forward references (historical record); this plan's "Why this
    slice" note, Deviations entry, sweep instruction and checklist line; and the three watch items this
    Results section routes to that plan. The walking skeleton still owns the app class, login and
    tickets, so each survivor names work that genuinely still lives there.
  - `grep -rn "the metrics plan" backend/` → 3 hits, all standing: `JcsmpEventPublisher`'s two lines
    saying the drop counter has no Micrometer counter and the metrics plan must expose it, and
    `MeshBridgeConfiguration`'s line keeping the raw transport injectable "for the metrics plan's drop
    counter". This plan added no metrics and no Micrometer dependency (verified: no `micrometer` in any
    backend POM), so all three remain future-tense and correct.
  - No now-false sentence was found; no fix beyond the two greps' expectations was needed.

### Self-review checklist, re-run at execution end

Every box holds as written. Spec coverage delivered exactly as scoped (`BE-D1` §3 → T1/T4; design §9
row 1 → T5/T6; design §2 → T3; §5.2 trigger → T2/T3, and nothing else claimed). Gate posture unchanged:
no auth, no session, no ticket, no RBAC, no endpoint, no browser/mesh-access work, no TypeScript, no
database, no metrics dependency — G1 residue (open items 32/33), G2 (`D10` amendment) and the `BE-D4`
amendment are all exactly where they were before this plan. `I5`: the only tenant values anywhere in the
plan are fixture constants. `I6`: no secret in any payload, topic, fixture, log assertion or test
property. `I7`: enforced and green, now with two additional executable rules. **Zero files under
`contracts/` changed** — the strict check passes for the trivial reason that nothing moved.

### Post-completion external review (R1) fix wave

An independent external review of the completed plan raised two findings against the merged publish
path. Both are real, both are fixed, and one of them **overturned a routing decision this Results
section had already made**. One commit, `dp-core` only plus this addendum; nothing under `contracts/`.

**The two findings**

- **MAJOR — the seam is bypassable by collection injection.** `meshEventPublisher` is
  `EventPublisher`-assignable, so a `List<EventPublisher>` / `ObjectProvider<EventPublisher>`
  injection point received **both** beans; a future fan-out service injecting the list would
  double-publish, and the second copy would go through the raw transport **before commit** — defeating
  the after-commit ordering the whole graph exists to provide. `@Primary` settles only *single*-bean
  injection.
- **MINOR — a post-close send is refused with the wrong reason.** `ReconnectingTransport.accept()`
  tested only delegate-nullness. In the close-before-install window the connector can briefly install
  a session after `close()` returned (the handoff then reclaims and closes it), so a concurrent
  post-close `accept()` could observe that doomed session and send on it.

#### Finding 1's fix odyssey — stated honestly, because the review's prescription did not work

The review prescribed `@Bean(autowireCandidate = false)`. **Measured, that attribute is absolute**: it
removes the bean from dependency resolution *entirely*, including `@Bean` **method-parameter**
resolution. `eventPublisher(JcsmpEventPublisher)` could then no longer be satisfied and the context
failed to refresh with `UnsatisfiedDependencyException … expected at least 1 bean which qualifies as
autowire candidate` — taking the `BE-D1` broker-down-still-boots guarantee down with it. Adding an
explicit `@Qualifier` **does not** recover it (measured separately; the qualifier appears in the
failure message and is ignored).

The landed mechanism is therefore **`@Bean(defaultCandidate = false)` on the raw bean plus
`@Qualifier("meshEventPublisher")` on the one intended consumer's parameter**. `defaultCandidate` is
the narrower tool that fits the actual hazard: the bean is excluded from *unqualified* injection
points — which is exactly what an accidental `List<EventPublisher>` grab is — while the deliberate,
explicitly-qualified consumer still resolves. `@Primary` is retained: it costs nothing and documents
the single-injection contract at the bean that wins.

**The residual, stated as a limit rather than papered over:** a future service *can* still reach the
raw pre-commit transport by deliberately writing `@Qualifier("meshEventPublisher")`. That is strictly
weaker than absolute exclusion. It was accepted by coordinator ruling on the grounds that the finding's
failure scenario was *accidental* fan-out injection — which `defaultCandidate` makes impossible — while
the residual is deliberate, greppable and review-policeable, and the absolute alternatives
(`proxyBeanMethods = true` plus a direct method call; `ApplicationContext.getBean` inside the
configuration) cost more than the residual is worth. It is carried as a new watch item above, with an
ArchUnit rule named as the escalation if the consumer surface grows.

**`getBeansOfType` would not have worked**, and the test does not use it: `getBeansOfType` ignores
autowire-candidate status and reports two beans no matter which attribute is set. The guard is a
**real injection point** — a `@Configuration(proxyBeanMethods = false)` probe with a constructor
`List<EventPublisher>` parameter, registered through `withUserConfiguration`, asserting the list has
exactly one element and that it is the `AfterCommitEventPublisher`.

**`I2` correction:** the external review cited spring-context **6.2.6**. The version actually resolved
for `dp-core` is **6.2.19** (Spring Boot parent 3.5.16; `mvn -pl dp-core dependency:tree`). `javap`
against *that* jar confirms both `autowireCandidate()` and `defaultCandidate()` exist — the attribute's
existence was never in question, its semantics were, and only the measurement settled them.

#### Finding 2's fix

`accept()` now checks `closed` **first and independently of the delegate**, throwing
`IllegalStateException("mesh transport closed")`; the delegate-null path keeps its distinct "still
connecting" message. Both land in `JcsmpEventPublisher.publish`'s counting catch, so the drop
accounting is unchanged. The test discriminates on the **message**, not merely the exception type —
the finding is precisely that the two lifecycle states were being conflated, and a bare
`assertThrows(IllegalStateException.class)` would have passed before the fix.

#### Prove-it-fires — three, all measured, all recorded

| # | Mutation | Measured result |
|---|---|---|
| 1 | New probe test present, `defaultCandidate` absent | `collectionInjectionSeesExactlyTheComposedPublisher … expected: <1> but was: <2>` — `Tests run: 40, Failures: 1`. Fires on exactly the double-publish hazard. |
| 2 | New post-close test present, closed-check removed | `… Was: mesh session not yet established — still connecting ==> expected: <true> but was: <false>` — `Tests run: 41, Failures: 1`. Fires on the message assertion, i.e. the conflation itself. |
| 3 | `@Qualifier` removed (tidy-up simulation), `defaultCandidate` kept | **All four** `MeshBridgeConfigurationTest` cases red — `Tests run: 41, Failures: 2, Errors: 2`, every one `UnsatisfiedDependencyException`. No lazy-init escape. |

Prove-it-fires 3 answers the "someone tidies the qualifier away" concern by measurement: the qualifier
is **already guard-tested by the existing context tests**, which fail loudly and immediately rather
than degrading quietly, so **no additional guard test was added** — one would have been redundant.
Each mutation was reverted and the suite re-run green before the next.

#### Result

`dp-core` **39 → 41** — `MeshBridgeConfigurationTest` 3 → 4, `ReconnectingTransportTest` 6 → 7.
Full reactor **185 → 187** (mesh-events 127 · dp-core 41 · arch-tests 19), ITs skipped-with-condition
and visibly named, `./contracts/run-checks.sh --strict` ALL CHECKS PASSED, `git diff --check` clean.
Four `dp-core` files plus this plan; `contracts/` untouched.
