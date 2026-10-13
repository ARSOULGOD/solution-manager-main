package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceRequestInFlightException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.LastLivePlacementException;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementInFlightException;
import com.solace.sm.dp.api.inventory.PlacementSchedulePendingException;
import com.solace.sm.dp.api.inventory.PlacementNotLiveException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.PushMetricsPlacementRefusedException;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.LadderEvidence;
import com.solace.sm.dp.config.LadderEvidence.PlacementEvidence;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.LadderRows;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretStore;
import com.solace.sm.events.EventEnvelope;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 008 S3 ({@code BE-D47}, {@code BE-D53}) at the service, with no database: what {@link InventoryService#addPlacement}
 * and {@link InventoryService#retirePlacement} do, in which ORDER, and what they refuse BEFORE they write anything. The
 * database half (the lock actually excluding a lifecycle writer, the unique indexes, the endpoint triggers) is
 * {@code AddPlacementIT}, {@code AddPlacementLifecycleRaceIT} and {@code RetirePlacementIT}.
 *
 * <p>Every fake appends to one {@code calls} list, so an order the design depends on (the apply scope before the instance row,
 * the instance row before the insert, the stamp before the ladder settle) is an assertion over that list, not a hope.
 */
class InventoryPlacementOperationsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID TARGET_1 = UUID.randomUUID();
    private static final UUID TARGET_2 = UUID.randomUUID();
    private static final UUID CATALOG = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final String FP = "SHA256:" + "A".repeat(43);

    private final List<String> calls = new ArrayList<>();
    /** What the lock-time re-read of the target says (W4C-2); a retirement that commits after the pre-check makes it false. */
    private boolean targetLiveUnderLock = true;
    private final List<EventEnvelope> published = new ArrayList<>();
    private final List<UUID> retiredBy = new ArrayList<>();
    private int commits;
    private int rollbacks;

    private InstanceLifecycle lifecycle;
    private long stateVersion;
    private final List<PlacementRecord> rows = new ArrayList<>();
    private final List<Object[]> recorded = new ArrayList<>();
    private boolean recordRefused;
    private boolean inFlight;
    private boolean scheduled;
    private boolean instanceWideInFlight;
    private boolean collide;
    private LadderRung stored;
    private final List<LadderRung> storedAs = new ArrayList<>();
    private Optional<TargetConnectionRecord> target;

    private final PlatformTransactionManager manager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            calls.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    };

    private final InstanceRows instances = new InstanceRows() {
        private InstanceRecord row() {
            return new InstanceRecord(ORG, INSTANCE, UUID.randomUUID(), CATALOG, "cache", "2", "orders", lifecycle,
                    AcquisitionMode.DIRECT_HTTP, ACTOR, Instant.EPOCH, stateVersion);
        }

        @Override public void insert(InstanceRecord r) {
            throw new AssertionError();
        }

        @Override public Optional<InstanceRecord> find(UUID tenantId, UUID instanceId) {
            calls.add("find");
            return tenantId.equals(ORG) && instanceId.equals(INSTANCE) ? Optional.of(row()) : Optional.empty();
        }

        @Override public List<InstanceRecord> list(UUID t, InstanceLifecycle l, Optional<UUID> c) {
            throw new AssertionError();
        }

        @Override public List<UUID> pendingCreatedBefore(UUID t, Instant cutoff) {
            throw new AssertionError();
        }

        @Override public List<UUID> pendingIds(UUID t, int limit) {
            throw new AssertionError();
        }

        @Override public List<UUID> pendingCreatedBefore(UUID t, Instant cutoff, int limit) {
            throw new AssertionError();
        }

        @Override public int activate(UUID t, UUID i) {
            throw new AssertionError();
        }

        @Override public int abandon(UUID t, UUID i) {
            throw new AssertionError();
        }

        @Override public Optional<InstanceRecord> lockForPlacementChange(UUID tenantId, UUID instanceId) {
            calls.add("lock-instance");
            return tenantId.equals(ORG) && instanceId.equals(INSTANCE) ? Optional.of(row()) : Optional.empty();
        }

        @Override public int touch(UUID tenantId, UUID instanceId) {
            calls.add("touch");
            if (lifecycle != InstanceLifecycle.ACTIVE) {
                return 0;
            }
            stateVersion++;
            return 1;
        }
    };

    private final PlacementRows placements = new PlacementRows() {
        @Override public void insert(PlacementRecord row) {
            calls.add("insert-placement");
            if (collide) {
                throw new PlacementCollisionException(PlacementCollisionException.Kind.CONTAINER_NAME);
            }
            rows.add(row);
        }

        @Override public Optional<PlacementRecord> findLive(UUID t, UUID i, UUID p) {
            calls.add("find-live");
            return rows.stream().filter(r -> r.tenantId().equals(t) && r.instanceId().equals(i)
                    && r.placementId().equals(p)).findFirst();
        }

        @Override public Optional<PlacementRecord> find(UUID t, UUID i, UUID p) {
            throw new AssertionError();
        }

        @Override public List<PlacementRecord> live(UUID t, UUID i) {
            return rows.stream().filter(r -> r.tenantId().equals(t) && r.instanceId().equals(i)).toList();
        }

        @Override public int retireAll(UUID t, UUID i, Instant at) {
            throw new AssertionError();
        }

        @Override public int recordRetirement(UUID t, UUID retirement, UUID i, UUID p, UUID by, Instant at,
                UUID correlation) {
            calls.add("record-retirement");
            recorded.add(new Object[] {t, retirement, i, p, by, at, correlation});
            if (recordRefused) {
                return 0;
            }
            return rows.stream().anyMatch(r -> r.placementId().equals(p) && r.instanceId().equals(i)) ? 1 : 0;
        }

        @Override public int retire(UUID t, UUID i, UUID p, Instant at, UUID by) {
            calls.add("retire");
            retiredBy.add(by);
            return rows.removeIf(r -> r.placementId().equals(p)) ? 1 : 0;
        }

        @Override public boolean lockApplyScope(UUID t, UUID i) {
            calls.add("lock-apply-scope");
            return t.equals(ORG) && i.equals(INSTANCE);   // like the database: no such instance, no scope row, no lock
        }

        @Override public boolean inFlightOn(UUID t, UUID i, UUID p) {
            calls.add("in-flight?");
            return inFlight;
        }

        @Override public boolean scheduleReservesPlacement(UUID t, UUID i, UUID p) {
            calls.add("schedule-reserves?");
            return scheduled;
        }

        @Override public boolean instanceWideInFlight(UUID t, UUID i) {
            calls.add("instance-wide-in-flight?");
            return instanceWideInFlight;
        }
    };

    private final MetricDeclarationRows metrics = new MetricDeclarationRows() {
        @Override
        public int insertDefinitionsIfAbsent(UUID t, UUID catalog, String version, MetricsDeclaration d, Instant at) {
            calls.add("definitions:" + catalog + ":" + version);
            return 0;
        }

        @Override
        public void insertEndpoints(UUID t, UUID placement, UUID targetId, Map<Integer, Integer> ports, Instant at) {
            calls.add("endpoints:" + placement + ":" + targetId + ":" + ports);
        }

        @Override
        public void insertActiveMetrics(UUID t, UUID i, long generation, List<String> names, Instant at) {
            throw new AssertionError("addPlacement writes no generation's active list: that is the instance's, once");
        }

        @Override
        public Optional<List<String>> activeMetricsFor(UUID t, UUID i, long generation) {
            throw new AssertionError();
        }
    };

    /**
     * Evidence the way the journal reads for a placement no apply has touched: every live placement has no settled request,
     * so the lowest rung is {@code rendered} (OQ-8.10: "a placement with no evidence reads rendered").
     */
    private final LadderRows ladderRows = new LadderRows() {
        @Override
        public LadderEvidence evidence(TenantId tenant, InstanceId instance) {
            calls.add("ladder-evidence");
            return new LadderEvidence(7L, stored,
                    rows.stream().map(r -> new PlacementEvidence(r.placementId(), null)).toList());
        }

        @Override
        public OptionalLong storeReachedRung(TenantId tenant, InstanceId instance, LadderRung expected, LadderRung next) {
            calls.add("ladder-store:" + next.wire());
            storedAs.add(next);
            stored = next;
            return OptionalLong.of(1000 + storedAs.size());
        }
    };

    private final TargetConnectionRows connections = (TargetConnectionRows) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {TargetConnectionRows.class}, (proxy, method, args) -> {
                if (method.getName().equals("find")) {
                    calls.add("find-target");
                    return target;
                }
                if (method.getName().equals("holdLive")) {
                    calls.add("hold-target-live");
                    return targetLiveUnderLock;
                }
                if (method.getName().equals("transportsOf")) {
                    return Map.of();     // plan 015 T3.3: the view reads its placements' transports; none of these targets is an agent
                }
                throw new AssertionError(method.getName());
            });

    private InventoryService service;

    @BeforeEach
    void setUp() {
        lifecycle = InstanceLifecycle.ACTIVE;
        stateVersion = 5;
        stored = LadderRung.RESTART_OBSERVED;   // a stale cache: the instance reached restart-observed with one placement
        target = Optional.of(new TargetConnectionRecord(ORG, TARGET_2, "vm2", 22, "sm", FP, UUID.randomUUID(), "/srv/sm",
                "sm-", ACTOR, Instant.EPOCH, null));
        rows.add(existing());
        ApplyEventEmitter emitter = new ApplyEventEmitter(published::add, Clock.systemUTC(), UUID::randomUUID);
        service = new InventoryService(new TransactionTemplate(manager), instances, placements, connections,
                new SecretStore() {
                    @Override public UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor) {
                        throw new AssertionError("a placement operation stores no secret");
                    }

                    @Override public boolean exists(UUID tenantId, UUID secretId) {
                        throw new AssertionError();
                    }
                }, (tenant, id, purpose) -> {
                    throw new AssertionError();
                }, Clock.systemUTC(), metrics, new LadderRecorder(ladderRows), emitter);
    }

    private static PlacementRecord existing() {
        return new PlacementRecord(ORG, UUID.randomUUID(), INSTANCE, TARGET_1, "sm-cache", "/srv/sm/p1",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH);
    }

    private static PlacementSpec spec(String container, String dir) {
        return new PlacementSpec(container, dir, "/app/external/spring/config", "application.yml", null);
    }

    private static MetricDefinitionSpec definition(String kind) {
        return new MetricDefinitionSpec("cache.hits", "cache-engine", "counter", "1", kind, "{}", 15, true);
    }

    private static MetricsDeclaration declaration(String kind, Map<Integer, Integer> ports) {
        return new MetricsDeclaration("sha256:" + "a".repeat(64), List.of(definition(kind)), List.of(), ports, List.of());
    }

    private AddPlacementCommand add(MetricsDeclaration metrics) {
        return new AddPlacementCommand(TARGET_2, spec("sm-cache", "/srv/sm/p2"), ACTOR, metrics);
    }

    // ---- addPlacement ---------------------------------------------------------------------------------------

    @Test
    void aPackageDeclaringPushMetricsIsRefusedBeforeAnyRowIsReadOrWritten() {
        assertThrows(PushMetricsPlacementRefusedException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("solace_event", Map.of()))));

        assertEquals(List.of(), calls, "OQ-8.9 (a): fails closed and visibly, with no transaction, no lock, no read");
        assertEquals(1, rows.size());
    }

    @Test
    void aPullOnlyPackageIsAdmittedAndTheInstanceGainsASecondLivePlacement() {
        InstanceView view = service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081))));

        assertEquals(2, view.placements().size());
        assertEquals(List.of(TARGET_1, TARGET_2), view.placements().stream().map(PlacementView::targetId).toList());
        assertEquals(1, commits);
        assertEquals(0, rollbacks);
    }

    @Test
    void theTransactionTakesTheInstanceLockFirstThenInsertsThenTheEndpointsThenStampsThenSettlesTheLadder() {
        service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081))));

        UUID added = rows.get(1).placementId();
        assertEquals(List.of("find-target", "begin", "lock-apply-scope", "lock-instance", "instance-wide-in-flight?",
                "hold-target-live", "insert-placement",
                "definitions:" + CATALOG + ":2", "endpoints:" + added + ":" + TARGET_2 + ":{8081=28081}", "touch",
                "ladder-evidence", "ladder-store:rendered", "find"), calls,
                "apply_scope first (retire's and admission's order), then the instance row, then the instance-wide check, "
                        + "then the target held live (W4C-2); "
                        + "reserve's order (placement, target row, endpoints); then the stamp, the ladder and the view; "
                        + "nothing is written before the instance row is locked");
    }

    @Test
    void theNewPlacementDropsTheLadderToRenderedAndConfigStateIsAnnouncedWithTheStoresStamp() {
        service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of())));

        assertEquals(List.of(LadderRung.RENDERED), storedAs, "the stored rung follows the read (OQ-8.10 (a))");
        assertEquals(1, published.size());
        EventEnvelope event = published.get(0);
        assertEquals("instance.config-state", event.event());
        assertEquals(Map.of("reached", "rendered"), event.payload());
        assertEquals(1001L, event.stateVersion(), "the stamp the reached_rung write returned");
    }

    @Test
    void aLadderThatDoesNotMoveWritesAndAnnouncesNothing() {
        stored = LadderRung.RENDERED;

        service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of())));

        assertEquals(List.of(), storedAs);
        assertEquals(List.of(), published);
    }

    @Test
    void theInstanceIsStampedBecauseItsObservableViewChanged() {
        long before = stateVersion;

        InstanceView view = service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of())));

        assertEquals(before + 1, view.stateVersion());
    }

    @Test
    void anInstanceThatIsNotActiveIsRefusedUnderTheLockAndWritesNothing() {
        for (InstanceLifecycle state : List.of(InstanceLifecycle.PENDING, InstanceLifecycle.ABANDONED)) {
            calls.clear();
            lifecycle = state;

            assertThrows(InstanceNotActiveException.class,
                    () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of()))), state.name());

            assertFalse(calls.contains("insert-placement"), state.name());
            assertEquals(List.of("find-target", "begin", "lock-apply-scope", "lock-instance"), calls, state.name());
        }
        assertEquals(2, rollbacks);
        assertEquals(1, rows.size());
    }

    @Test
    void aTargetRetiredAfterThePreCheckIsRefusedUnderTheLockAndNothingIsWritten() {
        // W4C-2: the pre-check above reads the target outside the transaction; a retirement that commits after it must
        // not leave a live placement on a retired target. The re-read under the lock (FOR UPDATE, which a retirement in
        // flight holds) is what sees it.
        targetLiveUnderLock = false;
        long before = stateVersion;

        assertThrows(java.util.NoSuchElementException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081)))));

        assertEquals(List.of("find-target", "begin", "lock-apply-scope", "lock-instance", "instance-wide-in-flight?",
                "hold-target-live"), calls, "decided under the locks, before the insert");
        assertEquals(1, rows.size());
        assertEquals(before, stateVersion);
        assertEquals(1, rollbacks);
    }

    @Test
    void anInstanceWideRequestInFlightRefusesTheAddUnderTheLocksAndWritesNothing() {
        // W2 review W2A-2: a stop reads the live set once, when it starts running; a placement added under it would be
        // left running under a stop that then reports the instance stopped. The symmetric refusal of retirePlacement's.
        instanceWideInFlight = true;
        long before = stateVersion;

        assertThrows(InstanceRequestInFlightException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081)))));

        assertEquals(List.of("find-target", "begin", "lock-apply-scope", "lock-instance", "instance-wide-in-flight?"),
                calls, "decided under apply_scope and the instance row, before any write");
        assertEquals(1, rows.size());
        assertEquals(before, stateVersion);
        assertEquals(1, rollbacks);
        assertEquals(List.of(), storedAs);
        assertEquals(List.of(), published);
    }

    @Test
    void aRequestOnOnePlacementDoesNotRefuseTheAdd() {
        // Only the instance-wide shape names every live placement; a request on one placement is not the add's business.
        inFlight = true;

        assertEquals(2, service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081))))
                .placements().size());
    }

    @Test
    void anotherTenantsOrAnUnknownInstanceIsTheSameRefusal() {
        assertThrows(InstanceNotActiveException.class,
                () -> service.addPlacement(UUID.randomUUID(), INSTANCE, add(declaration("http_json", Map.of()))));
        assertThrows(InstanceNotActiveException.class,
                () -> service.addPlacement(ORG, UUID.randomUUID(), add(declaration("http_json", Map.of()))));
        assertEquals(1, rows.size(), "I5");
    }

    @Test
    void aTargetWithNoLiveRecordIsRefusedBeforeTheTransactionOpens() {
        target = Optional.empty();
        assertThrows(NoSuchElementException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of()))));
        target = Optional.of(new TargetConnectionRecord(ORG, TARGET_2, "vm2", 22, "sm", FP, UUID.randomUUID(), "/srv/sm",
                "sm-", ACTOR, Instant.EPOCH, Instant.EPOCH));
        assertThrows(NoSuchElementException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of()))), "retired");

        assertFalse(calls.contains("begin"));
    }

    @Test
    void aPlacementOutsideTheTargetsEnvelopeIsRefusedBeforeTheTransactionOpensNamingTheField() {
        PlacementOutsideEnvelopeException container = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> service.addPlacement(ORG, INSTANCE, new AddPlacementCommand(TARGET_2,
                        spec("other-cache", "/srv/sm/p2"), ACTOR, declaration("http_json", Map.of()))));
        PlacementOutsideEnvelopeException directory = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> service.addPlacement(ORG, INSTANCE, new AddPlacementCommand(TARGET_2,
                        spec("sm-cache", "/srv/other/p2"), ACTOR, declaration("http_json", Map.of()))));

        assertEquals("containerName", container.field());
        assertEquals("configDirectory", directory.field());
        assertFalse(calls.contains("begin"));
    }

    @Test
    void aCollisionRollsTheWholeAddBackAndStampsNothing() {
        collide = true;
        long before = stateVersion;

        assertThrows(PlacementCollisionException.class,
                () -> service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of()))));

        assertEquals(1, rollbacks);
        assertEquals(before, stateVersion);
        assertEquals(List.of(), storedAs);
        assertEquals(List.of(), published);
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("endpoints") || c.equals("touch")));
    }

    @Test
    void aDeclarationWithNoMetricsWritesNoEndpointsButStillAddsThePlacement() {
        service.addPlacement(ORG, INSTANCE, add(MetricsDeclaration.none()));

        assertEquals(2, rows.size());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("endpoints") || c.startsWith("definitions")));
    }

    // ---- retirePlacement ------------------------------------------------------------------------------------

    private UUID addSecond() {
        rows.add(new PlacementRecord(ORG, UUID.randomUUID(), INSTANCE, TARGET_2, "sm-cache", "/srv/sm/p2",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH));
        calls.clear();
        return rows.get(1).placementId();
    }

    @Test
    void theApplyScopeIsTakenBeforeTheInstanceRowAndTheRetirementFollowsTheChecks() {
        UUID second = addSecond();

        InstanceView view = service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION);

        assertEquals(1, view.placements().size());
        assertEquals(List.of("begin", "lock-apply-scope", "lock-instance", "find-live", "in-flight?", "schedule-reserves?", "record-retirement",
                "retire", "touch",
                "ladder-evidence", "ladder-store:rendered", "find"), calls,
                "apply_scope first (admission's order, and no admission can slip between the check and the retirement), "
                        + "then the instance row; the host is never in this list");
    }

    @Test
    void retiringTheLastLivePlacementIsRefusedAndRetiresNothing() {
        UUID only = rows.get(0).placementId();
        calls.clear();

        assertThrows(LastLivePlacementException.class, () -> service.retirePlacement(ORG, INSTANCE, only, ACTOR, CORRELATION));

        assertFalse(calls.contains("retire"));
        assertEquals(1, rows.size());
        assertEquals(1, rollbacks);
    }

    @Test
    void workInFlightOnThePlacementRefusesItAndRetiresNothing() {
        UUID second = addSecond();
        inFlight = true;

        assertThrows(PlacementInFlightException.class, () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION));

        assertFalse(calls.contains("retire"));
        assertEquals(2, rows.size());
    }

    @Test
    void aPendingScheduleOnThePlacementRefusesItAndRetiresNothing() {
        UUID second = addSecond();
        scheduled = true;
        calls.clear();

        assertThrows(PlacementSchedulePendingException.class,
                () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION));

        assertTrue(calls.contains("schedule-reserves?"));
        assertFalse(calls.contains("record-retirement"));
        assertFalse(calls.contains("retire"));
        assertEquals(2, rows.size());
    }

    @Test
    void aPlacementThatIsNotALivePlacementOfThisInstanceIsOneAnswerAndNeverReachesTheInFlightCheck() {
        addSecond();
        PlacementRecord foreign = new PlacementRecord(ORG, UUID.randomUUID(), UUID.randomUUID(), TARGET_2, "sm-other",
                "/srv/sm/other", "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH);
        rows.add(foreign);

        assertThrows(PlacementNotLiveException.class,
                () -> service.retirePlacement(ORG, INSTANCE, UUID.randomUUID(), ACTOR, CORRELATION), "unknown");
        assertThrows(PlacementNotLiveException.class,
                () -> service.retirePlacement(ORG, INSTANCE, foreign.placementId(), ACTOR, CORRELATION),
                "another instance's placement is not this instance's (I5)");
        assertThrows(InstanceNotActiveException.class,
                () -> service.retirePlacement(ORG, UUID.randomUUID(), rows.get(1).placementId(), ACTOR, CORRELATION),
                "and an unknown instance is the instance refusal");

        assertFalse(calls.contains("in-flight?"));
        assertFalse(calls.contains("retire"));
    }

    @Test
    void anInstanceThatIsNotActiveRefusesTheRetirementBeforeAnyPlacementIsRead() {
        UUID second = addSecond();
        lifecycle = InstanceLifecycle.ABANDONED;

        assertThrows(InstanceNotActiveException.class, () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION));

        assertFalse(calls.contains("find-live"));
    }

    @Test
    void retiringTheLaggingPlacementRaisesTheLadderAndAnnouncesIt() {
        UUID second = addSecond();
        stored = LadderRung.DESIRED;   // the stale cache says desired; the remaining placement's evidence reads rendered

        service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION);

        assertEquals(List.of(LadderRung.RENDERED), storedAs);
        assertEquals("instance.config-state", published.get(0).event());
    }

    @Test
    void theActorIsHandedToTheRowSoTheRetirementIsAttributed() {
        UUID second = addSecond();

        service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION);

        assertEquals(List.of(ACTOR), retiredBy, "OR-125 (1): the principal that retired it is written with retired_at");
    }

    @Test
    void anAddedPlacementIsStampedWithTheActorThatAddedIt() {
        service.addPlacement(ORG, INSTANCE, add(declaration("http_json", Map.of(8081, 28081))));

        assertEquals(ACTOR, rows.get(1).createdBy(), "OR-125 (1): created_by is the command's requestedBy");
    }

    @Test
    void aRetirementWritesItsRecordWithTheActorAndTheCorrelationIdInTheSameTransaction() {
        UUID second = addSecond();

        service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION);

        assertEquals(1, recorded.size(), "one record per retired placement (OPS-Q12 R-1, BE-D53)");
        Object[] row = recorded.get(0);
        assertEquals(ORG, row[0], "I5: the organization the caller named");
        assertEquals(INSTANCE, row[2]);
        assertEquals(second, row[3]);
        assertEquals(ACTOR, row[4], "who asked");
        assertEquals(CORRELATION, row[6], "the request it came from");
        assertTrue(calls.indexOf("record-retirement") < calls.indexOf("retire"), "both inside the one transaction");
        assertEquals(0, rollbacks);
    }

    @Test
    void aRefusedRetirementWritesNoRecord() {
        UUID only = rows.get(0).placementId();
        calls.clear();
        assertThrows(LastLivePlacementException.class, () -> service.retirePlacement(ORG, INSTANCE, only, ACTOR, CORRELATION));
        UUID second = addSecond();
        inFlight = true;
        assertThrows(PlacementInFlightException.class, () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION));
        assertThrows(PlacementNotLiveException.class,
                () -> service.retirePlacement(ORG, INSTANCE, UUID.randomUUID(), ACTOR, CORRELATION));

        assertEquals(0, recorded.size(), "a refusal leaves no record: " + calls);
    }

    @Test
    void aRecordThatCannotBeWrittenRollsTheRetirementBack() {
        UUID second = addSecond();
        recordRefused = true;

        assertThrows(IllegalStateException.class, () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, CORRELATION));

        assertFalse(calls.contains("retire"), "the placement is retired only after its record is written");
        assertEquals(2, rows.size());
        assertEquals(1, rollbacks);
    }

    @Test
    void aNullCorrelationIdIsRefused() {
        UUID second = addSecond();
        assertThrows(NullPointerException.class, () -> service.retirePlacement(ORG, INSTANCE, second, ACTOR, null));
        assertTrue(calls.isEmpty(), "before any transaction");
    }

    @Test
    void aNullActorIsRefused() {
        UUID second = addSecond();
        assertThrows(NullPointerException.class, () -> service.retirePlacement(ORG, INSTANCE, second, null, CORRELATION));
        assertTrue(calls.isEmpty(), "before any transaction");
    }
}
