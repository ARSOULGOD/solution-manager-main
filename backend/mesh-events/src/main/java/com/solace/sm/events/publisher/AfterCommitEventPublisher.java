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
