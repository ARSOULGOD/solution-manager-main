package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.events.EntityRef;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyTopics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Plan 010 T6.6: the SM mesh events of a walkthrough go to a LOCAL Solace broker that this build starts itself from the
 * image {@value LocalMeshBroker#IMAGE} (never a remote broker, never a shared one), so the walkthrough's receive-side
 * assertion has a broker whose identities, ACLs and lifecycle the test owns.
 *
 * <p>This IT proves that harness on its own, with no SSH host and no SM application: the broker starts and provisions
 * itself over SEMP, the internal identity publishes on {@code sm/v1/>} and a separate observer identity receives, the
 * ports survive a stop and a start (the resilience scenarios take the broker down and bring it back), and the
 * publisher's session can be severed without stopping the broker. Without the image locally it is SKIPPED with its
 * reason, never silently green.
 */
@Tag("broker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalMeshBrokerIT {

    private static LocalMeshBroker broker;

    @BeforeAll
    static void startTheBroker() {
        assumeTrue(LocalMeshBroker.imageAvailable(), "the local image " + LocalMeshBroker.IMAGE
                + " is not in this Docker daemon (it is loaded from the Solace support-portal tarball, never pulled)");
        broker = LocalMeshBroker.start();
    }

    @AfterAll
    static void removeTheBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    private static EventEnvelope catalogEvent(UUID tenant) {
        return new EventEnvelope(UUID.randomUUID(), Instant.now(), "cp-core", tenant.toString(),
                new EntityRef("solution", null, null, "cache"), 7L, "catalog.package",
                Map.of("solution", "cache", "packageVersion", "1"));
    }

    @Test
    @Order(1)
    void theInternalIdentityPublishesAndTheObserverReceivesTheDerivedTopicWithEnvelopeBytes() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (MeshEventLog log = broker.observe("sm/v1/" + tenant + "/notify/>");
                JcsmpEventPublisher publisher = broker.publisher()) {
            EventEnvelope event = catalogEvent(tenant);

            publisher.publish(event);

            MeshEventLog.Received got = log.awaitOne(r -> r.topic().endsWith("/notify/catalog/package"),
                    java.time.Duration.ofSeconds(20));
            assertNotNull(got, "nothing arrived on the local broker");
            assertEquals(NotifyTopics.topicFor(event), got.topic());
            assertEquals(EventJson.write(event), got.body(), "the bytes on the wire are EventJson's");
            assertEquals(0L, publisher.droppedCount(), "a connected publisher drops nothing");
        }
    }

    @Test
    @Order(2)
    void aStoppedBrokerComesBackOnTheSamePortsWithItsConfigurationAndTheObserverReconnects() throws Exception {
        String hostBefore = broker.smfHost();

        broker.stopBroker();
        assertFalse(broker.isRunning(), "stopped");
        broker.startBroker();

        assertTrue(broker.isRunning());
        assertEquals(hostBefore, broker.smfHost(), "the mapped SMF port is stable across a stop and a start");
        UUID tenant = UUID.randomUUID();
        try (MeshEventLog log = broker.observe("sm/v1/" + tenant + "/notify/>");
                JcsmpEventPublisher publisher = broker.publisher()) {
            publisher.publish(catalogEvent(tenant));

            assertNotNull(log.awaitOne(r -> r.topic().endsWith("/notify/catalog/package"),
                    java.time.Duration.ofSeconds(20)), "the VPN, the identities and their ACLs survived the restart");
        }
    }

    @Test
    @Order(3)
    void thePublishersSessionCanBeSeveredWithoutStoppingTheBrokerAndTheDropIsCounted() throws Exception {
        UUID tenant = UUID.randomUUID();
        JcsmpEventPublisher publisher = broker.publisher();
        try (MeshEventLog log = broker.observe("sm/v1/" + tenant + "/notify/>")) {
            publisher.publish(catalogEvent(tenant));
            assertNotNull(log.awaitOne(r -> true, java.time.Duration.ofSeconds(20)), "connected before the cut");

            assertTrue(broker.connectedClients(LocalMeshBroker.PUBLISHER_USERNAME) >= 1, "the publisher is connected");

            int severed = broker.disconnectPublishers();

            assertTrue(severed >= 1, "SEMP found the publisher's client to disconnect: " + severed);
            assertTrue(broker.connectedClients(LocalMeshBroker.OBSERVER_USERNAME) >= 1,
                    "only the publisher's identity was cut: the observer is a different username and is still there");
        } finally {
            // A session JCSMP has just begun to reconnect can park a synchronous close (the same family as FLAG T6.6-1,
            // measured: one run in two), so this one is closed off the test thread and abandoned after a bound.
            Thread closer = new Thread(publisher::close, "severed-publisher-close");
            closer.setDaemon(true);
            closer.start();
            closer.join(15_000);
        }
    }
}
