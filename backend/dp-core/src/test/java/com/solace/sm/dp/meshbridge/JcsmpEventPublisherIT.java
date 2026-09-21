package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
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
 * End-to-end proof that {@link JcsmpEventPublisher} puts the contract's bytes on the contract's
 * topic, against a real Solace broker — no mocks anywhere in this test.
 *
 * <h2>How it is gated</h2>
 *
 * <p>It needs the Gate-G1 spike stack running, so it is tagged {@code broker} and enabled only when
 * the {@code broker.host} system property is set. Without the flag the test reports as
 * <b>skipped</b> — visibly, in the failsafe summary — and the build stays green; it is never
 * silently absent. Failsafe hands the property to the forked JVM via {@code systemPropertyVariables}
 * (see this module's POM): Maven's {@code -D} alone lands in the Maven JVM, not the test JVM.
 *
 * <pre>
 * docker compose -f backend/spikes/mesh-authz/docker-compose.yml up -d
 * mvn -f backend/pom.xml -pl dp-core -am verify -Dbroker.host=localhost
 * </pre>
 *
 * <h2>Which port, and which identity</h2>
 *
 * <p>The spike compose publishes exactly three broker ports — 5550 (health), 8080 (SEMP) and
 * <b>8008 (SMF WebSocket)</b>. There is deliberately no 55555 mapping, so the publisher reaches the
 * broker over {@code ws://}, which sol-jcsmp supports natively:
 * {@code com.solacesystems.common.HostInfo} declares {@code WS_SCHEME}/{@code WSS_SCHEME} and the jar
 * ships {@code NettySolWebSocketTransport} (read out of the 10.30.1 jar, not recalled — {@code I2}).
 *
 * <p>The identity is {@code sm-internal}, basic auth, created by the committed
 * {@code backend/spikes/mesh-authz/provision.sh}. It is the only identity in the spike with publish
 * rights on {@code sm/v1/>}, and its {@code acl-internal} profile allows subscribe as well, so one
 * identity serves both ends of this test. Browser/OAuth identities are subscribe-only by design and
 * could not run the publish half — a publisher is server-side ({@code BE-D5}, design §3). Plaintext
 * {@code ws} and a throwaway committed credential are spike-harness facts, not the production shape:
 * production is {@code tcps://} with a secret-backend credential.
 */
@Tag("broker")
@EnabledIfSystemProperty(named = "broker.host", matches = ".+",
        disabledReason = "needs the G1 spike broker: run with -Dbroker.host=localhost")
class JcsmpEventPublisherIT {

    /** The SMF-WebSocket port {@code backend/spikes/mesh-authz/docker-compose.yml} maps. Not 55555. */
    private static final int SMF_WEBSOCKET_PORT = 8008;

    private static final String VPN = "sm-spike";
    /** Spike-local throwaway credential, committed in provision.sh. Never a production secret. */
    private static final String INTERNAL_USER = "sm-internal";
    private static final String INTERNAL_PASS = "sm-internal";

    private static final String SUBSCRIPTION = "sm/v1/acme/notify/>";
    private static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");

    /** What the consumer saw, copied off JCSMP's pooled message on the callback thread. */
    private record Delivered(String topic, String body) {
    }

    @Test
    void publishesTheContractBytesOnTheContractTopic() throws Exception {
        String host = "ws://" + System.getProperty("broker.host") + ":" + SMF_WEBSOCKET_PORT;

        String fixtureText = Files.readString(FIXTURES.resolve("health-transition.json"));
        EventEnvelope health = EventJson.reader().readValue(fixtureText);
        String expectedTopic = EventJson.mapper()
                .readTree(FIXTURES.resolve("expected-topics.json").toFile())
                .get("health-transition.json").asText();
        String expectedBody = EventJson.write(health);

        BlockingQueue<Delivered> received = new ArrayBlockingQueue<>(8);

        JCSMPSession consumerSession = openSession(host);
        try (JcsmpEventPublisher publisher =
                new JcsmpEventPublisher(new MeshProperties(host, VPN, INTERNAL_USER, INTERNAL_PASS))) {

            XMLMessageConsumer consumer = consumerSession.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    // JCSMP hands the listener a pooled message that it may recycle once this method
                    // returns, so both fields are copied out here rather than on the test thread.
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

            publisher.publish(health);

            Delivered got = received.poll(15, TimeUnit.SECONDS);
            assertNotNull(got, "no message arrived on " + SUBSCRIPTION + " within 15s");

            assertEquals(expectedTopic, got.topic(),
                    "must publish on the topic contracts/events/fixtures/expected-topics.json declares");
            assertEquals(expectedBody, got.body(),
                    "consumed body must be exactly what EventJson.write emitted");
            assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8),
                    got.body().getBytes(StandardCharsets.UTF_8),
                    "bytes on the wire must be identical");
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
