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
