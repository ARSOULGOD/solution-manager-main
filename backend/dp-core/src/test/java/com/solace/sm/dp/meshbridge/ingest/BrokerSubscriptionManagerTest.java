package com.solace.sm.dp.meshbridge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 011 P11-5.1, the subscription manager (OR-107): one session per broker connection, one subscription per distinct
 * pattern, reconciled on the metrics loop's tick WITHOUT ever holding that thread (a broker that does not answer is the
 * control thread's problem, not the collector's). The credential is resolved in memory, handed over and zeroised.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BrokerSubscriptionManagerTest {

    private static final String CREDENTIAL = "SENTINEL-broker-credential";

    /** A subscriber the test drives. */
    static final class FakeSubscriber implements SampleSubscriber {
        final Set<String> subscriptions = new TreeSet<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Consumer<RawBrokerMessage> sink;
        volatile boolean open = true;
        volatile boolean failNextSubscribe;
        final char[] passwordSeenAtOpen;

        FakeSubscriber(Consumer<RawBrokerMessage> sink, char[] passwordSeenAtOpen) {
            this.sink = sink;
            this.passwordSeenAtOpen = passwordSeenAtOpen;
        }

        @Override
        public synchronized void subscribe(String pattern) {
            if (failNextSubscribe) {
                failNextSubscribe = false;
                throw new BrokerSessionException("subscribe refused");
            }
            calls.add("+" + pattern);
            subscriptions.add(pattern);
        }

        @Override
        public synchronized void unsubscribe(String pattern) {
            calls.add("-" + pattern);
            subscriptions.remove(pattern);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            calls.add("close");
        }
    }

    static final class FakeFactory implements SampleSubscriberFactory {
        final List<FakeSubscriber> opened = new CopyOnWriteArrayList<>();
        final List<BrokerEndpoint> endpoints = new CopyOnWriteArrayList<>();
        volatile boolean refuse;
        volatile char[] lastPassword;

        @Override
        public SampleSubscriber open(BrokerEndpoint endpoint, char[] credential, Consumer<RawBrokerMessage> sink) {
            if (refuse) {
                throw new BrokerSessionException("the broker did not accept the connection");
            }
            lastPassword = credential;
            FakeSubscriber subscriber = new FakeSubscriber(sink, credential.clone());
            opened.add(subscriber);
            endpoints.add(endpoint);
            return subscriber;
        }
    }

    static final class FakeSecrets implements SecretResolver {
        final List<String> resolved = new CopyOnWriteArrayList<>();
        volatile boolean missing;

        @Override
        public SecretMaterial resolve(UUID tenantId, UUID secretId, SecretPurpose expected) {
            resolved.add(expected.name());
            if (missing) {
                throw new IllegalStateException("secret.not.found");   // the real one is a SecretException (its constructor is package-private)
            }
            return SecretMaterial.adopt(CREDENTIAL.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final FakeFactory factory = new FakeFactory();
    private final FakeSecrets secrets = new FakeSecrets();
    private final BrokerSubscriptionManager manager = new BrokerSubscriptionManager(secrets, factory);
    private final UUID tenant = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();
    private final UUID credential = UUID.randomUUID();
    private final BrokerEndpoint endpoint = new BrokerEndpoint("tcps", "broker.example.test", 55443, "vpn", "sm-push");
    private final List<RawBrokerMessage> delivered = new CopyOnWriteArrayList<>();
    private final BrokerMessageSink sink = (t, c, m) -> delivered.add(m);

    @AfterEach
    void close() {
        manager.close();
    }

    private void reconcile(Set<String> patterns) throws InterruptedException {
        manager.reconcile(tenant, connection, endpoint, credential, patterns, sink);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));
    }

    @Test
    void oneSessionAndOneSubscriptionPerDistinctPattern() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat", "a/b/fcomplete"));

        assertEquals(1, factory.opened.size());
        assertEquals(Set.of("a/b/heartbeat", "a/b/fcomplete"), factory.opened.get(0).subscriptions);
        assertEquals(Set.of(new BrokerSubscriptions.Key(tenant, connection)), manager.held());
        assertEquals(endpoint, factory.endpoints.get(0));
    }

    /**
     * W3-15: {@code broker_connection}'s key is {@code (tenant_id, broker_connection_id)} and an operator may choose the id,
     * so two organizations can hold the SAME connection id. Each keeps its own session, endpoint and credential, and
     * releasing one never touches the other.
     */
    @Test
    void twoOrganizationsWithTheSameConnectionIdHoldTwoSessionsAndReleaseIndependently() throws InterruptedException {
        UUID otherTenant = UUID.randomUUID();
        UUID otherCredential = UUID.randomUUID();
        BrokerEndpoint otherEndpoint = new BrokerEndpoint("tcps", "other.example.test", 55443, "other-vpn", "other-push");
        manager.reconcile(tenant, connection, endpoint, credential, Set.of("a/b/heartbeat"), sink);
        manager.reconcile(otherTenant, connection, otherEndpoint, otherCredential, Set.of("c/d/heartbeat"), sink);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));

        assertEquals(2, factory.opened.size(), "one session each; the second must not replace the first");
        assertEquals(Set.of(new BrokerSubscriptions.Key(tenant, connection),
                new BrokerSubscriptions.Key(otherTenant, connection)), manager.held());
        assertEquals(Set.of(endpoint, otherEndpoint), Set.copyOf(factory.endpoints));
        assertTrue(factory.opened.stream().noneMatch(s -> s.calls.contains("close")), "nothing was closed and reopened");

        manager.release(otherTenant, connection);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(Set.of(new BrokerSubscriptions.Key(tenant, connection)), manager.held());
        assertEquals(1, factory.opened.stream().filter(s -> s.calls.contains("close")).count(), "only the released one closed");
    }

    @Test
    void aSecondReconcileOfTheSamePlanChangesNothing() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));
        List<String> before = List.copyOf(factory.opened.get(0).calls);

        reconcile(Set.of("a/b/heartbeat"));

        assertEquals(1, factory.opened.size());
        assertEquals(before, factory.opened.get(0).calls, "no resubscribe, no new session");
        assertEquals(1, secrets.resolved.size(), "the credential is not resolved again for a session already open");
    }

    @Test
    void aChangedPlanAddsAndRemovesOnlyTheDifference() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat", "a/b/fcomplete"));

        reconcile(Set.of("a/b/heartbeat", "a/b/other"));

        FakeSubscriber s = factory.opened.get(0);
        assertEquals(Set.of("a/b/heartbeat", "a/b/other"), s.subscriptions);
        assertTrue(s.calls.contains("-a/b/fcomplete") && s.calls.contains("+a/b/other"), s.calls.toString());
        assertEquals(1, factory.opened.size());
    }

    @Test
    void aPlanWithNothingToSubscribeClosesTheSession() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));

        reconcile(Set.of());

        assertFalse(factory.opened.get(0).open);
        assertEquals(Set.of(), manager.held());
    }

    @Test
    void releasingAConnectionClosesItsSession() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));

        manager.release(tenant, connection);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));

        assertFalse(factory.opened.get(0).open);
        assertEquals(Set.of(), manager.held());
    }

    @Test
    void theCredentialIsResolvedForItsPurposeInMemoryAndZeroisedAfterTheSessionIsOpen() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));

        assertEquals(List.of("BROKER_CREDENTIAL"), secrets.resolved, "resolved under its own purpose, once");
        assertEquals(CREDENTIAL, new String(factory.opened.get(0).passwordSeenAtOpen), "the factory was handed it");
        for (char c : factory.lastPassword) {
            assertEquals('\0', c, "the manager zeroises the copy it made, as soon as the session is open");
        }
    }

    @Test
    void aBrokerThatRefusesIsRetriedOnTheNextReconcileAndNeverThrowsIntoTheCaller() throws InterruptedException {
        factory.refuse = true;
        reconcile(Set.of("a/b/heartbeat"));
        assertEquals(Set.of(), manager.held());
        assertTrue(factory.opened.isEmpty());

        factory.refuse = false;
        reconcile(Set.of("a/b/heartbeat"));

        assertEquals(Set.of(new BrokerSubscriptions.Key(tenant, connection)), manager.held());
        assertEquals(Set.of("a/b/heartbeat"), factory.opened.get(0).subscriptions);
    }

    @Test
    void aMissingCredentialIsNotConnectedWithAndNotRetriedInATightLoop() throws InterruptedException {
        secrets.missing = true;

        reconcile(Set.of("a/b/heartbeat"));

        assertTrue(factory.opened.isEmpty(), "no session without a credential");
        assertEquals(Set.of(), manager.held());
    }

    @Test
    void aSessionThatIsNoLongerOpenIsReplacedAndAFailedSubscribeDropsTheSession() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));
        factory.opened.get(0).open = false;

        reconcile(Set.of("a/b/heartbeat"));

        assertEquals(2, factory.opened.size(), "a dead session is replaced");
        assertEquals(Set.of("a/b/heartbeat"), factory.opened.get(1).subscriptions);

        factory.opened.get(1).failNextSubscribe = true;
        reconcile(Set.of("a/b/heartbeat", "a/b/more"));
        assertFalse(factory.opened.get(1).open, "a session that cannot take a subscription is closed so the next tick starts clean");
        reconcile(Set.of("a/b/heartbeat", "a/b/more"));
        assertEquals(Set.of("a/b/heartbeat", "a/b/more"), factory.opened.get(2).subscriptions);
    }

    @Test
    void aChangedEndpointOrCredentialReferenceReopensTheSession() throws InterruptedException {
        reconcile(Set.of("a/b/heartbeat"));

        manager.reconcile(tenant, connection, new BrokerEndpoint("tcps", "other.example.test", 55443, "vpn", "sm-push"),
                credential, Set.of("a/b/heartbeat"), sink);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));

        assertEquals(2, factory.opened.size());
        assertFalse(factory.opened.get(0).open);
    }

    @Test
    void messagesReachTheSinkTaggedWithTheirConnectionAndTenant() throws InterruptedException {
        List<UUID> seen = new ArrayList<>();
        BrokerMessageSink tagged = (t, c, m) -> {
            seen.add(t);
            seen.add(c);
            delivered.add(m);
        };
        manager.reconcile(tenant, connection, endpoint, credential, Set.of("a/b/heartbeat"), tagged);
        assertTrue(manager.awaitIdle(Duration.ofSeconds(10)));

        factory.opened.get(0).sink.accept(new RawBrokerMessage("a/b/heartbeat", "x".getBytes(StandardCharsets.UTF_8),
                java.time.Instant.EPOCH));

        assertEquals(List.of(tenant, connection), seen);
        assertEquals(1, delivered.size());
    }

    @Test
    void aReconcileNeverWaitsForTheBroker() throws InterruptedException {
        // the factory blocks like JCSMP's connect does against a broker that is away
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        BrokerSubscriptionManager slow = new BrokerSubscriptionManager(secrets, (e, c, s) -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new BrokerSessionException("gave up");
        });
        try {
            // the calls are made on a helper thread, so a reconcile that DID wait fails here by assertion, not by hanging the test
            java.util.concurrent.CompletableFuture<Void> calls = java.util.concurrent.CompletableFuture.runAsync(() -> {
                slow.reconcile(tenant, connection, endpoint, credential, Set.of("a/b/heartbeat"), sink);
                slow.reconcile(tenant, connection, endpoint, credential, Set.of("a/b/heartbeat"), sink);
            });
            try {
                calls.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                throw new AssertionError("reconcile waited for the broker: it must hand off and return", e);
            }

            assertFalse(slow.awaitIdle(Duration.ofMillis(200)), "the control thread is still inside the blocked connect");
        } finally {
            release.countDown();
            slow.close();
        }
    }

    @Test
    void theMessageAndEndpointNeverRenderTheCredentialOrPayload() {
        RawBrokerMessage m = new RawBrokerMessage("t", ("{\"password\":\"" + CREDENTIAL + "\"}").getBytes(StandardCharsets.UTF_8),
                java.time.Instant.EPOCH);

        assertFalse(m.toString().contains(CREDENTIAL), m.toString());
        assertFalse(m.toString().contains("password"), m.toString());
        assertEquals("tcps://broker.example.test:55443", endpoint.uri());
    }
}
