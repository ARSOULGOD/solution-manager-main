package com.solace.sm.dp.meshbridge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solacesystems.jcsmp.DeliveryMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 011 P11-5.1 against a LOCAL broker (the {@code solace-pubsub-enterprise:10.25.0.177} image, started here, never pulled,
 * never a remote broker, never the {@code oms-*} containers): the JCSMP receive side, measured. Without the image locally it is
 * SKIPPED with its reason, never silently green (L19): P11-10.1's Results must say whether it ran.
 *
 * <p>What it proves: bytes and a topic cross out and nothing else; a subscription naming a state never delivers another state (a
 * {@code start} event, which may carry a plaintext broker password, is never delivered); matching is case-sensitive (MF26,
 * measured again here); a PERSISTENT-mode publisher (File Mesh's default) reaches a direct subscription; an outage is a gap and the
 * subscriptions come back by themselves; a wrong credential is refused without echoing anything.
 */
@Tag("broker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class JcsmpSampleSubscriberIT {

    private static LocalPushBroker broker;

    @BeforeAll
    static void startTheBroker() {
        assumeTrue(LocalPushBroker.imageAvailable(), "the local image " + LocalPushBroker.IMAGE
                + " is not in this Docker daemon (it is loaded from the Solace support-portal tarball, never pulled)");
        broker = LocalPushBroker.start();
    }

    @AfterAll
    static void removeTheBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    /** What a subscriber was delivered, in order. */
    private static final class Received {
        final List<RawBrokerMessage> messages = new CopyOnWriteArrayList<>();

        List<RawBrokerMessage> await(Predicate<List<RawBrokerMessage>> done, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!done.test(messages) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            return List.copyOf(messages);
        }
    }

    private static SampleSubscriber open(Received into) {
        return JcsmpSampleSubscriber.open(broker.endpoint(), broker.subscriberPassword(), into.messages::add, Clock.systemUTC());
    }

    private static final String HEARTBEAT = "*/*/*/*/*/*/*/*/heartbeat";
    private static final String FCOMPLETE = "*/*/*/*/*/*/*/*/fcomplete";

    private static String topic(String adapter, String state) {
        return "DEFAULT/NONE/Alice/SE/FE/SOURCE/NWFILE/" + adapter + "/" + state;
    }

    @Test
    @Order(1)
    void aSubscriberReceivesTheBytesAndTheTopicOfMatchingEventsOnly() throws Exception {
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received); LocalPushBroker.Publisher publisher = broker.publisher()) {
            subscriber.subscribe(HEARTBEAT);
            assertTrue(subscriber.isOpen());
            Instant before = Instant.now();

            publisher.publish(topic("A1", "fcomplete"), "{\"not\":\"subscribed\"}");
            publisher.publish(topic("A1", "heartbeat"), "{\"header\":{\"adapterID\":\"A1\"}}");

            List<RawBrokerMessage> got = received.await(m -> m.size() >= 1, Duration.ofSeconds(20));
            Thread.sleep(500);   // a second delivery, if it were coming, would have arrived
            assertEquals(1, received.messages.size(), "only the subscribed state is delivered");
            assertEquals(topic("A1", "heartbeat"), got.get(0).topic());
            assertEquals("{\"header\":{\"adapterID\":\"A1\"}}", new String(got.get(0).body(), StandardCharsets.UTF_8));
            assertFalse(got.get(0).receivedAt().isBefore(before.minusSeconds(1)), "the receipt instant is the Data Plane's clock");
        }
    }

    @Test
    @Order(2)
    void aStartEventIsNeverDeliveredWhenOnlyItsStatesAreSubscribed() throws Exception {
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received); LocalPushBroker.Publisher publisher = broker.publisher()) {
            subscriber.subscribe(FCOMPLETE);
            subscriber.subscribe(HEARTBEAT);

            publisher.publish(topic("A1", "start"), "{\"solace\":{\"password\":\"SENTINEL-start-password\"}}");
            publisher.publish(topic("A1", "complete"), "{\"x\":1}");
            publisher.publish(topic("A1", "fcomplete"), "{\"fileStateEvent\":{\"runTimeMs\":7}}");

            received.await(m -> m.size() >= 1, Duration.ofSeconds(20));
            Thread.sleep(500);
            assertEquals(List.of(topic("A1", "fcomplete")), received.messages.stream().map(RawBrokerMessage::topic).toList(),
                    "a start event never reaches the Data Plane: the broker does not deliver it");
            for (RawBrokerMessage m : received.messages) {
                assertFalse(new String(m.body(), StandardCharsets.UTF_8).contains("SENTINEL"));
            }
        }
    }

    @Test
    @Order(3)
    void topicMatchingIsCaseSensitive() throws Exception {
        // MF26, measured 2026-10-06 and again here on the same image: two adapter ids that differ only by case are two topics
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received); LocalPushBroker.Publisher publisher = broker.publisher()) {
            subscriber.subscribe("a/B/c");

            publisher.publish("a/b/c", "lower");
            publisher.publish("a/B/c", "exact");

            received.await(m -> m.size() >= 1, Duration.ofSeconds(20));
            Thread.sleep(500);
            assertEquals(List.of("a/B/c"), received.messages.stream().map(RawBrokerMessage::topic).toList());
            assertEquals("exact", new String(received.messages.get(0).body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @Order(4)
    void aPersistentModePublisherReachesTheDirectSubscription() throws Exception {
        // File Mesh publishes with solace_messaging_mode PERSISTENT by default (fixture: SOLACE_MESSAGING_MODE)
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received); LocalPushBroker.Publisher publisher = broker.publisher()) {
            subscriber.subscribe(HEARTBEAT);

            publisher.publish(topic("P1", "heartbeat"), "{\"header\":{\"adapterID\":\"P1\"}}", DeliveryMode.PERSISTENT);

            List<RawBrokerMessage> got = received.await(m -> m.size() >= 1, Duration.ofSeconds(20));
            assertEquals(1, got.size(), "the persistent message is delivered to the topic subscription");
            assertEquals(topic("P1", "heartbeat"), got.get(0).topic());
        }
    }

    @Test
    @Order(5)
    void anUnsubscribedPatternStopsDeliveringAndSubscribeIsIdempotent() throws Exception {
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received); LocalPushBroker.Publisher publisher = broker.publisher()) {
            subscriber.subscribe(HEARTBEAT);
            subscriber.subscribe(HEARTBEAT);
            publisher.publish(topic("U1", "heartbeat"), "1");
            received.await(m -> m.size() >= 1, Duration.ofSeconds(20));

            subscriber.unsubscribe(HEARTBEAT);
            publisher.publish(topic("U1", "heartbeat"), "2");
            Thread.sleep(800);

            assertEquals(1, received.messages.size(), "nothing is delivered after the subscription is removed");
        }
    }

    @Test
    @Order(6)
    void aWrongCredentialIsRefusedWithAFixedSentenceThatEchoesNothing() {
        char[] wrong = "SENTINEL-wrong-credential".toCharArray();
        BrokerSessionException refused = assertThrows(BrokerSessionException.class, () -> JcsmpSampleSubscriber.open(
                broker.endpoint(), wrong, m -> { }, Clock.systemUTC()));

        assertEquals("the broker session could not be opened", refused.getMessage());
        assertEquals(null, refused.getCause(), "the SDK's exception (which names the host and VPN) is dropped");
        assertFalse(refused.toString().contains("SENTINEL"));
        assertFalse(refused.toString().contains(LocalPushBroker.VPN));
    }

    @Test
    @Order(7)
    void aClosedSubscriberReportsItAndDeliversNothingMore() throws Exception {
        Received received = new Received();
        SampleSubscriber subscriber = open(received);
        subscriber.subscribe(HEARTBEAT);

        subscriber.close();

        assertFalse(subscriber.isOpen());
        assertThrows(BrokerSessionException.class, () -> subscriber.subscribe("a/b"));
        try (LocalPushBroker.Publisher publisher = broker.publisher()) {
            publisher.publish(topic("C1", "heartbeat"), "x");
            Thread.sleep(500);
        }
        assertTrue(received.messages.isEmpty());
        assertNotNull(subscriber.toString());
    }

    @Test
    @Order(99)
    void aBrokerOutageIsAGapAndReconnectResubscribes() throws Exception {
        Received received = new Received();
        try (SampleSubscriber subscriber = open(received)) {
            subscriber.subscribe(HEARTBEAT);
            try (LocalPushBroker.Publisher publisher = broker.publisher()) {
                publisher.publish(topic("O1", "heartbeat"), "before");
            }
            received.await(m -> m.size() >= 1, Duration.ofSeconds(20));
            assertEquals(1, received.messages.size());

            broker.stopBroker();
            Thread.sleep(2000);
            assertTrue(subscriber.isOpen(), "a session that is reconnecting is still open: the SDK retries for ever (-1)");
            broker.startBroker();

            // no subscribe call is made from here: the SDK applies the session's subscriptions again after it reconnects
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            int delivered = 1;
            while (received.messages.size() <= delivered && System.nanoTime() < deadline) {
                try (LocalPushBroker.Publisher publisher = broker.publisher()) {
                    publisher.publish(topic("O1", "heartbeat"), "after");
                } catch (Exception e) {
                    // the broker is still coming back
                }
                Thread.sleep(1000);
            }
            assertTrue(received.messages.size() > delivered, "an event published after the broker returned is delivered");
            assertEquals("after", new String(received.messages.get(delivered).body(), StandardCharsets.UTF_8));
            assertTrue(subscriber.isOpen());
        }
    }
}
