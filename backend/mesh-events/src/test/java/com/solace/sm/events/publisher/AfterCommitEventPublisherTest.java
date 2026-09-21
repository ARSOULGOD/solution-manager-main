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
