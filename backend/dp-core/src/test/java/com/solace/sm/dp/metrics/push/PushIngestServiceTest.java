package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.meshbridge.ingest.BrokerEndpoint;
import com.solace.sm.dp.meshbridge.ingest.BrokerMessageSink;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptions;
import com.solace.sm.dp.meshbridge.ingest.RawBrokerMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1, the push service end to end WITHOUT a broker or a database: the plan drives the subscriptions, an event
 * becomes one sample on one placement through the one ingest seam, and every drop is counted on the broker-connection-level
 * counter. Time is the test's.
 */
class PushIngestServiceTest {

    /** A {@link BrokerSubscriptions} that records what it was told. */
    static final class RecordingSubscriptions implements BrokerSubscriptions {
        record Call(UUID tenant, UUID connection, BrokerEndpoint endpoint, UUID credential, Set<String> patterns) {
        }

        final Map<UUID, Call> current = new HashMap<>();
        final List<UUID> released = new ArrayList<>();
        final List<BrokerMessageSink> sinks = new ArrayList<>();

        @Override
        public void reconcile(UUID tenantId, UUID connectionId, BrokerEndpoint endpoint, UUID credentialRef,
                Set<String> patterns, BrokerMessageSink sink) {
            current.put(connectionId, new Call(tenantId, connectionId, endpoint, credentialRef, patterns));
            sinks.add(sink);
        }

        @Override
        public void release(UUID tenantId, UUID connectionId) {
            Call gone = current.get(connectionId);
            if (gone != null && gone.tenant().equals(tenantId)) {
                current.remove(connectionId);
                released.add(connectionId);
            }
        }

        @Override
        public Set<Key> held() {
            Set<Key> out = new java.util.HashSet<>();
            current.values().forEach(c -> out.add(new Key(c.tenant(), c.connection())));
            return out;
        }
    }

    static final class RecordingIngest implements TelemetryIngestPort {
        final List<IngestBatch> batches = new CopyOnWriteArrayList<>();

        @Override
        public IngestResult ingest(IngestBatch batch) {
            batches.add(batch);
            return new IngestResult.Committed(batch.samples().size(), Map.of());
        }
    }

    static final class RecordingCounters implements BrokerCounterRows {
        final Map<String, Long> rows = new HashMap<>();
        boolean failing;

        @Override
        public void add(UUID tenantId, UUID brokerConnectionId, CollectorReason reason, LocalDate day, long n) {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            rows.merge(tenantId + "/" + brokerConnectionId + "/" + reason.wire() + "/" + day, n, Long::sum);
        }

        long of(UUID tenant, UUID connection, CollectorReason reason) {
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(tenant + "/" + connection + "/" + reason.wire() + "/"))
                    .mapToLong(Map.Entry::getValue).sum();
        }
    }

    private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");

    private final FakePushRows rows = new FakePushRows();
    private final RecordingSubscriptions subscriptions = new RecordingSubscriptions();
    private final RecordingIngest ingest = new RecordingIngest();
    private final RecordingCounters counters = new RecordingCounters();
    private final UUID environment = UUID.randomUUID();
    private PushIngestService service;

    private PushIngestService service(int queueCapacity) {
        service = new PushIngestService(new PushSubscriptionPlan(rows), subscriptions,
                new PushAttribution(new BrokerSampleDecoder(64 * 1024)), ingest, counters,
                new PushProperties(true, queueCapacity, 64 * 1024, 100), Clock.fixed(T0, ZoneOffset.UTC));
        return service;
    }

    private void arrange(String adapterId, UUID... placements) {
        rows.definitions = List.of(PushFixtures.heartbeats(), PushFixtures.runtime());
        rows.instance(environment, adapterId, placements);
    }

    private void deliver(String adapterId, String state, byte[] body, int times) {
        UUID connection = subscriptions.current.keySet().iterator().next();
        for (int i = 0; i < times; i++) {
            service.accept(rows.tenant, connection, new RawBrokerMessage(PushFixtures.topic(adapterId, state), body,
                    T0.plusSeconds(i)));
        }
    }

    @Test
    void reconcileSubscribesTheDistinctPatternsOfTheEnvironmentsConnectionAndNothingElse() {
        UUID placement = UUID.randomUUID();
        arrange("ADAPTER_A", placement);
        UUID connection = rows.connection(environment);
        service(100);

        service.reconcile(new TenantId(rows.tenant));

        RecordingSubscriptions.Call call = subscriptions.current.get(connection);
        assertEquals(Set.of(PushFixtures.HEARTBEAT_PATTERN, PushFixtures.FCOMPLETE_PATTERN), call.patterns());
        assertEquals(rows.connections.get(environment).endpoint(), call.endpoint());
        assertEquals(rows.connections.get(environment).credentialRef(), call.credential());
        assertEquals(1, subscriptions.current.size());
    }

    @Test
    void anEnvironmentWithNoConnectionSubscribesNothing() {
        arrange("ADAPTER_A", UUID.randomUUID());
        service(100);

        service.reconcile(new TenantId(rows.tenant));

        assertTrue(subscriptions.current.isEmpty());
    }

    @Test
    void aConnectionThatLeavesThePlanIsReleased() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100);
        service.reconcile(new TenantId(rows.tenant));
        assertEquals(Set.of(new BrokerSubscriptions.Key(rows.tenant, connection)), subscriptions.held());

        rows.instances.clear();
        service.reconcile(new TenantId(rows.tenant));

        assertEquals(List.of(connection), subscriptions.released);
        assertTrue(subscriptions.current.isEmpty());
    }

    @Test
    void aTenantThatLeavesTheActiveListHasItsConnectionsReleased() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100);
        service.reconcile(new TenantId(rows.tenant));

        service.retainTenants(List.of(new TenantId(UUID.randomUUID())));

        assertEquals(List.of(connection), subscriptions.released);
    }

    /**
     * W3-15: {@code broker_connection}'s key is {@code (tenant_id, broker_connection_id)} and an operator may choose the id, so two
     * organizations can hold the same connection id. Each keeps its own routing and its own subscription, an event is attributed
     * only inside the organization it was delivered for, and one organization leaving never drops the other's subscription.
     */
    @Test
    void twoOrganizationsWithTheSameConnectionIdAreKeptApartEndToEnd() {
        FakePushRows a = rows;
        FakePushRows b = new FakePushRows();
        UUID sharedId = UUID.randomUUID();
        UUID environmentB = UUID.randomUUID();
        UUID placementA = UUID.randomUUID();
        UUID placementB = UUID.randomUUID();
        a.definitions = List.of(PushFixtures.heartbeats(), PushFixtures.runtime());
        b.definitions = a.definitions;
        a.instance(environment, "ADAPTER_A", placementA);
        b.instance(environmentB, "ADAPTER_B", placementB);
        for (Map.Entry<FakePushRows, UUID> e : List.of(Map.entry(a, environment), Map.entry(b, environmentB))) {
            e.getKey().connections.put(e.getValue(), new BrokerConnectionRecord(e.getKey().tenant, e.getValue(), sharedId, "tcps",
                    "broker.example.test", 55443, "vpn", "sm-push", UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH, null));
        }
        PushRows both = (PushRows) java.lang.reflect.Proxy.newProxyInstance(PushRows.class.getClassLoader(),
                new Class<?>[] {PushRows.class}, (proxy, method, args) -> {
                    FakePushRows target = args[0].equals(a.tenant) ? a : b;
                    return method.invoke(target, args);
                });
        Map<BrokerSubscriptions.Key, Set<String>> held = new HashMap<>();
        BrokerSubscriptions byKey = new BrokerSubscriptions() {
            @Override
            public void reconcile(UUID tenantId, UUID connectionId, BrokerEndpoint endpoint, UUID credentialRef,
                    Set<String> patterns, BrokerMessageSink sink) {
                held.put(new Key(tenantId, connectionId), patterns);
            }

            @Override
            public void release(UUID tenantId, UUID connectionId) {
                held.remove(new Key(tenantId, connectionId));
            }

            @Override
            public Set<Key> held() {
                return Set.copyOf(held.keySet());
            }
        };
        PushIngestService both2 = new PushIngestService(new PushSubscriptionPlan(both), byKey,
                new PushAttribution(new BrokerSampleDecoder(64 * 1024)), ingest, counters,
                new PushProperties(true, 100, 64 * 1024, 100), Clock.fixed(T0, ZoneOffset.UTC));
        both2.reconcile(new TenantId(a.tenant));
        both2.reconcile(new TenantId(b.tenant));
        assertEquals(Set.of(new BrokerSubscriptions.Key(a.tenant, sharedId), new BrokerSubscriptions.Key(b.tenant, sharedId)),
                byKey.held(), "the second organization's reconcile did not replace the first's");

        both2.accept(a.tenant, sharedId, new RawBrokerMessage(PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"), T0));
        both2.accept(b.tenant, sharedId, new RawBrokerMessage(PushFixtures.topic("ADAPTER_B", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_B"), T0));
        assertEquals(2, both2.drainNow());
        assertEquals(Set.of(placementA, placementB), ingest.batches.stream().map(IngestBatch::placementId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Map.of(placementA, a.tenant, placementB, b.tenant), ingest.batches.stream()
                .collect(java.util.stream.Collectors.toMap(IngestBatch::placementId, IngestBatch::organizationId)),
                "each event lands on its own organization's placement");

        both2.retainTenants(List.of(new TenantId(b.tenant)));       // organization A leaves the active list
        assertEquals(Set.of(new BrokerSubscriptions.Key(b.tenant, sharedId)), byKey.held(),
                "A's release must not drop B's subscription");
        ingest.batches.clear();
        both2.accept(a.tenant, sharedId, new RawBrokerMessage(PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"), T0));
        both2.accept(b.tenant, sharedId, new RawBrokerMessage(PushFixtures.topic("ADAPTER_B", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_B"), T0));
        both2.drainNow();
        assertEquals(List.of(placementB), ingest.batches.stream().map(IngestBatch::placementId).toList(),
                "an event for the released organization is dropped, B's still flows");
    }

    @Test
    void aCountEventBecomesOneSampleOnTheSinglePlacementWithTheDpsReceiptInstantAndACumulativeValue() {
        UUID placement = UUID.randomUUID();
        arrange("ADAPTER_A", placement);
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "heartbeat", PushFixtures.heartbeat("ADAPTER_A"), 3);
        assertEquals(3, service.drainNow());

        assertEquals(1, ingest.batches.size(), "three events for one placement are one batch");
        IngestBatch batch = ingest.batches.get(0);
        assertEquals(rows.tenant, batch.organizationId());
        assertEquals(connection, batch.bindingId(), "bindingId is the broker connection (OQ-3 (a), OQ-15)");
        assertEquals(placement, batch.placementId());
        assertEquals(placement.toString(), batch.sourceKey(), "sourceKey is the placement");
        assertEquals(SourceDiscriminator.BROKER_PUSH, batch.discriminator());
        assertTrue(batch.gapMarkers().isEmpty());
        List<IngestSample> samples = batch.samples();
        assertEquals(List.of(1.0, 2.0, 3.0), samples.stream().map(IngestSample::value).toList(),
                "a DP-synthesised cumulative value (OR-106)");
        assertEquals(List.of(T0, T0.plusSeconds(1), T0.plusSeconds(2)), samples.stream().map(IngestSample::ts).toList(),
                "ts is the DP's receipt instant (OQ-4 (a))");
        assertTrue(samples.stream().allMatch(s -> s.metricName().equals("filemesh.heartbeats") && s.restartToken() == null));
    }

    @Test
    void aValueExtractionCarriesTheNumberAndANonNumberCarriesNaNSoTheIngestPathCountsItAgainstThePlacement() {
        UUID placement = UUID.randomUUID();
        arrange("ADAPTER_A", placement);
        rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "fcomplete", PushFixtures.fileComplete("ADAPTER_A", "1234"), 1);
        deliver("ADAPTER_A", "fcomplete", PushFixtures.fileComplete("ADAPTER_A", "\"SENTINEL-text\""), 1);
        service.drainNow();

        List<IngestSample> samples = ingest.batches.get(0).samples();
        assertEquals(1234.0, samples.get(0).value());
        assertTrue(Double.isNaN(samples.get(1).value()), "not a finite number: the ingest path counts parse_failed and stores nothing");
        assertFalse(samples.toString().contains("SENTINEL"), samples.toString());
    }

    @Test
    void anEventForNoKnownInstanceStoresNothingAndIsCountedAtTheConnection() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("UNMANAGED", "heartbeat", PushFixtures.heartbeat("UNMANAGED"), 5);
        service.drainNow();
        service.flushCounts();

        assertTrue(ingest.batches.isEmpty(), "nothing reaches the ingest seam");
        assertEquals(5, counters.of(rows.tenant, connection, CollectorReason.UNDECLARED_DROPPED));
    }

    @Test
    void anInstanceWithTwoLivePlacementsHasItsEventDroppedAndCountedAmbiguous() {
        arrange("ADAPTER_A", UUID.randomUUID(), UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "heartbeat", PushFixtures.heartbeat("ADAPTER_A"), 2);
        service.drainNow();
        service.flushCounts();

        assertTrue(ingest.batches.isEmpty());
        assertEquals(2, counters.of(rows.tenant, connection, CollectorReason.AMBIGUOUS_PLACEMENT));
    }

    @Test
    void anUnparsableBodyIsParseFailedAtTheConnectionAndNeverLogged() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "heartbeat", "SENTINEL-garbage".getBytes(StandardCharsets.UTF_8), 1);
        service.drainNow();
        service.flushCounts();

        assertEquals(1, counters.of(rows.tenant, connection, CollectorReason.PARSE_FAILED));
    }

    @Test
    void anEventForAnotherTenantsTopicNeverReachesThisTenant() {
        // tenant A's instance has adapter id SHARED; tenant B's connection delivers an event naming SHARED: B has no such instance
        arrange("SHARED", UUID.randomUUID());
        UUID connectionA = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));
        UUID tenantB = UUID.randomUUID();
        UUID connectionB = UUID.randomUUID();

        service.accept(tenantB, connectionB, new RawBrokerMessage(PushFixtures.topic("SHARED", "heartbeat"),
                PushFixtures.heartbeat("SHARED"), T0));
        service.accept(tenantB, connectionA, new RawBrokerMessage(PushFixtures.topic("SHARED", "heartbeat"),
                PushFixtures.heartbeat("SHARED"), T0));
        service.drainNow();

        assertTrue(ingest.batches.isEmpty(), "a connection that is not routed, or a tenant that does not own it, attributes nothing");
    }

    @Test
    void anEventOverTheBodyBoundIsCountedParseFailedAndNeverQueued() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "heartbeat", new byte[64 * 1024 + 1], 3);
        service.flushCounts();

        assertEquals(3, counters.of(rows.tenant, connection, CollectorReason.PARSE_FAILED));
        assertEquals(0, service.drainNow(), "nothing over the bound is held: the queue's memory is capacity x the bound");
    }

    @Test
    void aFullQueueDropsTheNewestAndCountsBufferDropped() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(2).reconcile(new TenantId(rows.tenant));

        deliver("ADAPTER_A", "heartbeat", PushFixtures.heartbeat("ADAPTER_A"), 5);
        service.flushCounts();

        assertEquals(3, counters.of(rows.tenant, connection, CollectorReason.BUFFER_DROPPED));
        assertEquals(2, service.drainNow());
    }

    @Test
    void aCounterWriteThatFailsIsKeptForTheNextFlushAndNeverThrown() {
        arrange("ADAPTER_A", UUID.randomUUID());
        UUID connection = rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));
        deliver("NOBODY", "heartbeat", PushFixtures.heartbeat("NOBODY"), 2);
        service.drainNow();

        counters.failing = true;
        service.flushCounts();
        assertEquals(0, counters.of(rows.tenant, connection, CollectorReason.UNDECLARED_DROPPED));

        counters.failing = false;
        service.flushCounts();
        assertEquals(2, counters.of(rows.tenant, connection, CollectorReason.UNDECLARED_DROPPED));
    }

    @Test
    void aRestartOfTheServiceStartsTheCountOver() {
        UUID placement = UUID.randomUUID();
        arrange("ADAPTER_A", placement);
        rows.connection(environment);
        service(100).reconcile(new TenantId(rows.tenant));
        deliver("ADAPTER_A", "heartbeat", PushFixtures.heartbeat("ADAPTER_A"), 2);
        service.drainNow();
        int firstBatchSamples = ingest.batches.get(0).samples().size();
        assertEquals(2, firstBatchSamples);

        PushIngestService restarted = new PushIngestService(new PushSubscriptionPlan(rows), subscriptions,
                new PushAttribution(new BrokerSampleDecoder(64 * 1024)), ingest, counters,
                new PushProperties(true, 100, 64 * 1024, 100), Clock.fixed(T0, ZoneOffset.UTC));
        restarted.reconcile(new TenantId(rows.tenant));
        UUID connection = subscriptions.current.keySet().iterator().next();
        restarted.accept(rows.tenant, connection, new RawBrokerMessage(PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"), T0.plusSeconds(60)));
        restarted.drainNow();

        assertEquals(1.0, ingest.batches.get(1).samples().get(0).value(),
                "the cumulative value restarts at one: the ingest path reads that as an inferred reset (M-Q9)");
    }
}
