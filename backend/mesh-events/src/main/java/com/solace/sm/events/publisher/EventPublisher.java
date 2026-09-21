package com.solace.sm.events.publisher;

import com.solace.sm.events.EventEnvelope;

/**
 * The one seam every SM module publishes mesh events through (backend design §3, {@code BE-D1}).
 *
 * <p><b>Why an interface with a single method.</b> The design confines publish sites so that the
 * later transactional-outbox upgrade stays bounded: when "publish after commit" becomes "write to an
 * outbox in the same transaction, drain asynchronously", the change lands behind this type and
 * nowhere else. Modules therefore depend on {@code EventPublisher}, never on JCSMP.
 *
 * <p><b>It lives in {@code mesh-events}, not in a plane module.</b> The seam is plane-neutral by
 * construction — both planes publish through it, and a second implementation (the outbox) will not
 * be a data-plane class. Keeping it in {@code com.solace.sm.dp.meshbridge} would have made every
 * future publisher import a {@code dp} package, which is the {@code I7} conduit the plane rules
 * exist to prevent.
 *
 * <p><b>It throws only on a malformed event; never on a transport failure.</b> Those are the two
 * outcomes, and the split is the contract, not an implementation detail:
 *
 * <ul>
 *   <li><b>Never on a transport failure.</b> A broker outage, a dead session, a serialization
 *       defect — anything that means "the notice did not go out" — is a <i>freshness</i> problem,
 *       and implementations must log it and return ({@code BE-D1}: a broker outage degrades
 *       liveness of the console, it must never roll back or fail the caller's write). Callers
 *       publish from inside their own transaction and are entitled to ignore this method's
 *       failure modes.</li>
 *   <li><b>Only on a malformed event</b> — and "malformed" means both halves of the contract in
 *       {@code contracts/events/}, not just the addressable half:
 *       <ul>
 *         <li><b>Unaddressable</b> — an unknown {@code event} key, or a tenant/id carrying a topic
 *             separator or wildcard. A wildcard in a topic level is an {@code I5} isolation
 *             defect.</li>
 *         <li><b>Shape-invalid</b> — an event that violates its branch of
 *             {@code notify-events.schema.json}: the wrong {@code entity.kind} for its
 *             {@code event}, a missing or forbidden entity id, a payload key the catalog does not
 *             define, or a value outside a closed enum. A missing {@code placementId} on a
 *             placement-scoped event is an identity defect that makes two sites of one instance
 *             overwrite each other in every consumer; an undeclared payload key is an {@code I6}
 *             leak.</li>
 *       </ul>
 *       Both are <b>caller bugs</b>, both must fail closed and loud rather than be logged away,
 *       and neither ever reaches the wire.</li>
 * </ul>
 *
 * <p><b>The shape half of that guarantee is not free — it is enforced by each implementation calling
 * {@code NotifyEventShapes.validate(event)} before anything reaches a transport.</b> The rule lives
 * in {@code mesh-events} rather than here because this interface has no bytes to inspect; a second
 * implementation (the transactional outbox this seam exists to allow) must call it too, or the
 * contract above becomes one implementation's property rather than the interface's.
 *
 * <p><b>That is why every implementation must have a test class extending
 * {@code com.solace.sm.events.contracttest.AbstractEventPublisherContractTest}</b> (design §5.2).
 * A publisher-specific suite exercising only its own happy path is exactly how the next
 * implementation skips {@code validate} while every existing suite stays green; the kit is the
 * executable form of the paragraph above.
 *
 * <p>{@code JcsmpEventPublisher#publish(EventEnvelope)} argues that boundary in full. (Named rather
 * than {@code @link}ed: this module must not reference a {@code dp} type, even in a doc comment.)
 */
public interface EventPublisher {

    /**
     * Publishes one event on the topic its envelope addresses.
     *
     * @param event the event to publish; its topic is derived, never supplied by the caller, so
     *     that {@code sm/v1/…} addressing stays in one place
     */
    void publish(EventEnvelope event);
}
