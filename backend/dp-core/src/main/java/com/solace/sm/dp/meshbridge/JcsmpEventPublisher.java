package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyEventShapes;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.publisher.EventPublisher;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JCSMP implementation of {@link EventPublisher} — DIRECT (non-persistent) delivery of notify
 * events onto the customer's event mesh (backend design §3, {@code BE-D1}).
 *
 * <h2>Why DIRECT and not persistent</h2>
 *
 * <p>Notify events are <b>hints, not truth</b>: Postgres stays the sole source of truth, and a
 * browser that misses a notice re-reads state on the next tick or on reconnect. Paying for
 * guaranteed delivery — a spool, acks, a publisher window — would buy durability for data that is
 * already durable elsewhere, and would couple a caller's write latency to broker persistence.
 *
 * <h2>The transport seam</h2>
 *
 * <p>The private transport is a {@code BiConsumer<String,String>} of <b>(derived topic, serialized
 * body)</b>, so {@link #publish} itself does the deriving and the serializing. That placement is
 * deliberate: a seam that took the raw {@link EventEnvelope} would let a unit test's own stub do the
 * topic derivation, and the test would pass however wrong this class was. With the seam here, the
 * unit test needs no broker and the integration test needs no mocks.
 *
 * <h2>Failure semantics — a deliberate asymmetry, and where exactly the line falls</h2>
 *
 * <p>The line is <b>not</b> "our bug vs their bug". It is <b>security-bearing vs not</b>, because
 * that is the only distinction that survives {@code BE-D1}: a caller publishes from inside its own
 * transaction, so anything this method throws can roll back a customer's business write.
 *
 * <ul>
 *   <li><b>Topic derivation propagates</b> ({@link NotifyTopics#topicFor} is called <i>outside</i>
 *       the try). An unknown {@code event} key means the event would be published somewhere the
 *       taxonomy does not describe; a tenant or id carrying {@code /}, {@code *} or {@code >} means
 *       the addressed topic is not the topic the ACLs were reasoned about — an {@code I5} isolation
 *       defect. Both must <b>fail closed and loud</b>. A security guard that logs and continues is
 *       not a guard, and the wildcard case is exactly the one where a quiet drop would be worst.</li>
 *   <li><b>Shape validation propagates too</b> ({@link NotifyEventShapes#validate} is called
 *       <i>outside</i> the try, immediately after the topic). A shape-invalid event — the wrong
 *       {@code entity.kind} for its {@code event}, a missing or forbidden entity id, a payload key
 *       the catalog does not define, a value outside a closed enum — is a <b>caller bug</b>, and
 *       one with two consequences that both land on the loud side of the line above. It is an
 *       <i>identity</i> defect: an {@code instance.health} without {@code placementId} makes a
 *       dual-bound instance's two sites overwrite each other in every consumer, which is the R5
 *       fix silently undone. And an extra payload key is an {@code I6} leak — a value on a notify
 *       topic that a fully leaked subscription must never reveal. Neither may be degraded into a
 *       dropped notice: a caller cannot fix what it is never told about, and the consumer would
 *       be the one paying. So the invalid event never reaches the wire and the caller hears about
 *       it. (The rules themselves are hand-mirrored from
 *       {@code contracts/events/notify-events.schema.json} and golden-tested against the same
 *       fixtures the Python validator uses — see {@code NotifyEventShapes}.)</li>
 *   <li><b>Serialization is swallowed</b> ({@link EventJson#write} is called <i>inside</i> the try).
 *       Its {@link IllegalStateException} is, by {@code EventJson}'s own contract, "a bug in this
 *       module's configuration, not a condition a caller can act on" — an SM-internal defect. It
 *       carries no isolation consequence, so aborting a customer's transaction over it would be
 *       precisely the correctness damage {@code BE-D1} forbids. It is dropped, but never silently:
 *       the WARN line names the {@code eventId} and carries the full cause chain.</li>
 *   <li><b>Transport failures are swallowed at both layers.</b> The real JCSMP transport catches
 *       the checked {@link JCSMPException} from {@code send}, and {@link #publish} additionally
 *       catches any {@link RuntimeException} the transport lets escape (a netty/JCSMP unchecked
 *       error, a closed session).</li>
 * </ul>
 *
 * <h2>Counting what is dropped</h2>
 *
 * <p>Everything on the swallow side increments {@link #droppedCount()} — the transport catch here,
 * the JCSMP catch in {@code JcsmpTransport.accept}, and the asynchronous error handler, which drops
 * a message that {@code send} had already accepted. It is a plain {@link AtomicLong} rather than a
 * Micrometer counter on purpose: this module has no metrics dependency and the metrics plan is
 * where the backend's metrics stack is chosen and wired. Until then the operator's signal is the
 * WARN line plus this counter, and <b>the metrics plan must expose it</b> — a log-and-drop
 * contract with nothing counting the drops is a silent failure mode, and this is the smallest
 * thing that stops it being one. It is deliberately not on {@link EventPublisher}: the interface
 * is the seam the outbox upgrade replaces, and a counter is an implementation's observability,
 * not the contract.
 *
 * <p><b>Bean-registration decision, now made:</b> the {@link MeshProperties} constructor
 * <b>connects eagerly and throws</b> if the broker is unreachable, and <b>stays</b> the
 * caller-constructed fail-fast path — right for tools and integration tests, because handing back
 * an object that silently cannot publish would be worse. It is still <b>wrong for an eager Spring
 * singleton</b>: registered that way, a broker that is down at startup would block application
 * boot, which inverts {@code BE-D1} from "degrades freshness" into "degrades availability". So
 * Spring registration does not use it: {@code MeshBridgeConfiguration} builds the bean through
 * {@link #connectingInBackground(MeshProperties)}, over a {@link ReconnectingTransport} that starts
 * disconnected and retries with capped backoff — a down broker costs counted drops, never a failed
 * boot. Nothing inherits this constructor's behaviour by accident.
 *
 * <p>Not thread-confined: a JCSMP {@link XMLMessageProducer} is safe for concurrent {@code send}.
 */
public final class JcsmpEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JcsmpEventPublisher.class);

    private final BiConsumer<String, String> transport;

    /** Every swallowed drop, from all three swallow sites. See the class javadoc. */
    private final AtomicLong dropped;

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

    /** Connects to the mesh and publishes through a JCSMP direct producer. */
    public JcsmpEventPublisher(MeshProperties props) {
        // The counter is built before the transport rather than by delegating to the seam
        // constructor: JcsmpTransport and its async error handler are drop sites too and need the
        // same counter, and `this(...)` cannot hand them a field that does not exist yet.
        AtomicLong counter = new AtomicLong();
        this.dropped = counter;
        this.transport = new JcsmpTransport(props, counter);
    }

    /**
     * The Spring-registration entry point: a publisher over a {@link ReconnectingTransport} that
     * starts disconnected and connects in the background — a broker that is down at startup costs
     * counted drops, never a failed application boot. This resolves the bean-registration question
     * the class javadoc used to leave open.
     *
     * @param props the mesh connection properties handed to each connect attempt
     * @return a publisher that is usable immediately and connected shortly afterwards
     */
    public static JcsmpEventPublisher connectingInBackground(MeshProperties props) {
        AtomicLong dropped = new AtomicLong();
        ReconnectingTransport transport = new ReconnectingTransport(
                () -> new JcsmpTransport(props, dropped),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(30));
        return new JcsmpEventPublisher(transport, dropped);
    }

    @Override
    public void publish(EventEnvelope event) {
        // BOTH guards are OUTSIDE the try, on purpose. An underivable topic is an addressing /
        // isolation failure; a shape-invalid event is an identity or I6 defect in the caller. Both
        // must reach the caller. Everything below them is a freshness failure. See the class javadoc.
        String topic = NotifyTopics.topicFor(event);
        NotifyEventShapes.validate(event);
        try {
            transport.accept(topic, EventJson.write(event));
        } catch (RuntimeException e) {
            // BE-D1: never throw into the caller's transaction path.
            dropped.incrementAndGet();
            log.warn("dropping event {} on {} — mesh publish failed", event.eventId(), topic, e);
        }
    }

    /**
     * How many events this publisher has swallowed since construction — transport failures at
     * either layer, asynchronous publish errors, and serialization failures. Never counts an event
     * rejected by the two loud guards: those did not "drop", they were refused.
     *
     * @return the running drop count; monotonic, never reset
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Closes the underlying JCSMP session, if this publisher owns one. A no-op for the test seam.
     */
    @Override
    public void close() {
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("error closing mesh transport", e);
            }
        }
    }

    /**
     * The real transport: one session, one direct producer, one {@code TextMessage} per event.
     *
     * <p>It catches {@link JCSMPException} itself rather than relying on {@link #publish}'s
     * {@code RuntimeException} net, because {@code send} throws a <i>checked</i> exception — wrapping
     * it into a runtime just so the outer catch could swallow it would be a longer road to the same
     * place, and would lose the cause in the log.
     *
     * <p><b>Package-private, with a seam constructor, so this catch is reachable from a unit test.</b>
     * It was not: an R8 mutation run deleted {@code dropped.incrementAndGet()} from
     * {@link #accept(String, String)} and the whole build stayed green, because the only test that
     * could reach the JCSMP layer was the compose-gated IT, which skips. A drop counter that is
     * documented as covering "every swallow site" and is provably deletable at two of three is a
     * claim, not a guarantee. The seam takes the <i>send behaviour</i> and the shared counter; the
     * production constructor wires the real session/producer into the same shape, so what the test
     * exercises is this class's own catch, not a stub of it.
     */
    static final class JcsmpTransport implements BiConsumer<String, String>, AutoCloseable {

        /**
         * One send attempt. Checked {@link JCSMPException} on purpose — throwing the exception the
         * real {@code producer.send} throws is the whole point of the seam.
         */
        @FunctionalInterface
        interface Send {
            void send(String topic, String body) throws JCSMPException;
        }

        private final Send send;
        private final Runnable closer;
        private final AtomicLong dropped;

        /** Test seam. Package-private: production goes through the {@link MeshProperties} ctor. */
        JcsmpTransport(Send send, Runnable closer, AtomicLong dropped) {
            this.send = send;
            this.closer = closer;
            this.dropped = dropped;
        }

        JcsmpTransport(MeshProperties props, AtomicLong dropped) {
            this.dropped = dropped;
            JCSMPProperties jcsmp = new JCSMPProperties();
            jcsmp.setProperty(JCSMPProperties.HOST, props.host());
            jcsmp.setProperty(JCSMPProperties.VPN_NAME, props.vpn());
            jcsmp.setProperty(JCSMPProperties.USERNAME, props.clientUsername());
            jcsmp.setProperty(JCSMPProperties.PASSWORD, props.password());
            // Mid-session outages are JCSMP's own problem, not ReconnectingTransport's: that class
            // owns only the INITIAL connect. Verified against sol-jcsmp-10.30.1 (I2): a fresh
            // JCSMPProperties already holds a JCSMPChannelProperties under CLIENT_CHANNEL_PROPERTIES
            // (defaults reconnectRetries=3, reconnectRetryWaitInMillis=3000), and -1 is the SDK's
            // documented "retry forever" — -2 is rejected with IllegalArgumentException.
            // connectRetries stays at its default 0 ("try once and give up") on purpose: giving the
            // initial connect to JCSMP too would block connectFactory.get() inside the retry
            // thread's first attempt and turn ReconnectingTransport's backoff into dead code.
            JCSMPChannelProperties channel = (JCSMPChannelProperties)
                    jcsmp.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
            channel.setReconnectRetries(-1);              // mid-session: retry until told to stop
            channel.setReconnectRetryWaitInMillis(3000);  // matches the SMF keepalive cadence G1 measured
            JCSMPSession session;
            XMLMessageProducer producer;
            try {
                session = JCSMPFactory.onlyInstance().createSession(jcsmp);
                session.connect();
                producer = session.getMessageProducer(new DirectPublishEvents(dropped));
            } catch (JCSMPException e) {
                // Construction failure is a startup/config problem, not a per-publish one: a
                // publisher that could not connect must not be handed out as if it worked.
                throw new IllegalStateException(
                        "cannot open mesh session to " + props.host() + " vpn " + props.vpn(), e);
            }
            // The real send, unchanged: one TextMessage per event, DIRECT, on the derived topic.
            // Nothing here throws a checked exception except `send` itself, which is why `accept`'s
            // catch is still the only place a JCSMP failure is handled.
            this.send = (topic, body) -> {
                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(body);
                msg.setDeliveryMode(DeliveryMode.DIRECT);
                producer.send(msg, JCSMPFactory.onlyInstance().createTopic(topic));
            };
            this.closer = () -> {
                producer.close();
                session.closeSession();
            };
        }

        @Override
        public void accept(String topic, String body) {
            try {
                send.send(topic, body);
            } catch (JCSMPException e) {
                // BE-D1 again, at the layer that actually knows the broker failed — and the layer
                // that still has the cause chain, which is why the catch is here and not upstairs.
                dropped.incrementAndGet();
                log.warn("dropping event on {} — JCSMP send failed", topic, e);
            }
        }

        @Override
        public void close() {
            closer.run();
        }
    }

    /**
     * Direct delivery gets no per-message acknowledgement, so {@code responseReceivedEx} is never
     * called; {@code handleErrorEx} carries asynchronous transport errors that surface after
     * {@code send} has already returned. Logged, never rethrown — there is no caller left to throw
     * at, which is precisely why the log line and the drop counter matter: this is the one drop
     * site with no synchronous trace at all.
     *
     * <p>Package-private for the same reason as {@link JcsmpTransport}: the broker calls this back,
     * so nothing but the skipped IT could reach it and its increment was provably deletable. The
     * callback takes no dependency on a live session, so a unit test invokes it directly.
     */
    static final class DirectPublishEvents
            implements JCSMPStreamingPublishCorrelatingEventHandler {

        private final AtomicLong dropped;

        DirectPublishEvents(AtomicLong dropped) {
            this.dropped = dropped;
        }

        @Override
        public void responseReceivedEx(Object key) {
            // Unreachable for DIRECT messages.
        }

        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            dropped.incrementAndGet();
            log.warn("asynchronous mesh publish error", cause);
        }
    }
}
