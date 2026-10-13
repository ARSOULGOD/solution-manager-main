package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceRequestInFlightException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.PushMetricsPlacementRefusedException;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.PlacementItSupport.Declared;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.config.ConfigLadder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.metrics.collect.CollectionPlan;
import com.solace.sm.dp.metrics.collect.CollectionPlanBuilder;
import com.solace.sm.events.EventEnvelope;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 008 S3 ({@code BE-D47}) against a real PostgreSQL 14 as {@code sm_dp_runtime}: {@code addPlacement} over the
 * production inventory service, the real JDBC rows, the real ladder recorder and an emitter behind the data plane's
 * after-commit decorator. The service's ORDER of operations is {@code InventoryPlacementOperationsTest}'s (no database);
 * what only a database can show is here: the unique indexes and endpoint triggers refusing a collision and leaving NOTHING
 * behind, the lock actually held, the stored rung and the announcement agreeing with the read.
 *
 * <p>The lock against a lifecycle writer is {@code AddPlacementLifecycleRaceIT}; the HTTP half is
 * {@code AddPlacementEndpointIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class AddPlacementIT {

    private static InventoryService inventory;

    @BeforeAll
    static void wire() {
        inventory = PlacementItSupport.service();
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static AddPlacementCommand add(UUID target, PlacementSpec spec, MetricsDeclaration declaration) {
        return new AddPlacementCommand(target, spec, PlacementItSupport.ACTOR, declaration);
    }

    private static PlacementSpec onSameTargetAs(PlacementSpec taken, String container, String directory, int healthPort) {
        return new PlacementSpec(container == null ? "sm-" + UUID.randomUUID().toString().substring(0, 8) : container,
                directory == null ? "/srv/sm/" + UUID.randomUUID().toString().substring(0, 8) : directory,
                taken.containerConfigPath(), taken.fileName(),
                new HealthProbeSpec("actuator-health", "/actuator/health", healthPort, ProbeStrength.LIVENESS));
    }

    private record Snapshot(int placements, int live, int digests, int definitions, int endpoints, int activeLists,
            long stateVersion) {
        static Snapshot of(Declared d) {
            return new Snapshot(PlacementItSupport.count("placement", d.tenant()),
                    PlacementItSupport.livePlacements(d.tenant(), d.instance()),
                    PlacementItSupport.count("package_version_digest", d.tenant()),
                    PlacementItSupport.count("metric_definition", d.tenant()),
                    PlacementItSupport.count("placement_metrics_endpoint", d.tenant()),
                    PlacementItSupport.count("generation_active_metrics", d.tenant()),
                    PlacementItSupport.stateVersion(d.tenant(), d.instance()));
        }
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void anActiveInstanceGainsASecondLivePlacementOnAnotherTarget() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID second = com.solace.sm.dp.apply.persistence.InventoryFixtures.target(d.tenant());
        int metricsPort = PlacementItSupport.freshPort();
        Snapshot before = Snapshot.of(d);

        InstanceView view = inventory.addPlacement(d.tenant(), d.instance(), add(second,
                PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                PlacementItSupport.declaration(metricsPort)));

        assertEquals(2, view.placements().size());
        assertEquals(d.placement(), view.placements().get(0).placementId(), "the first stays first (oldest first)");
        assertEquals(second, view.placements().get(1).targetId());
        assertEquals(Optional.of(ProbeStrength.LIVENESS), view.placements().get(1).probeStrength());
        Snapshot after = Snapshot.of(d);
        assertEquals(before.placements() + 1, after.placements());
        assertEquals(2, after.live());
        assertEquals(before.digests(), after.digests(), "the version's definitions are copied once (M-Q5)");
        assertEquals(before.definitions(), after.definitions());
        assertEquals(before.endpoints() + 1, after.endpoints(), "the new placement's own endpoint row (M-Q14)");
        assertEquals(before.activeLists(), after.activeLists(),
                "the generation-0 list is the INSTANCE's, written once at its declare");
        assertEquals(Map.of(8081, metricsPort),
                PlacementItSupport.metrics().endpointsFor(d.tenant(), view.placements().get(1).placementId()));
        assertTrue(after.stateVersion() > before.stateVersion(), "the instance's observable view changed, so it is stamped");
        assertEquals(after.stateVersion(), view.stateVersion(), "the view carries the stamp the add wrote");
    }

    @Test
    void aSecondPlacementOnTheSameTargetNeedsItsOwnContainerDirectoryHealthAndMetricsPorts() {
        Declared d = Declared.fresh("cache.hit_rate");
        PlacementSpec taken = d.spec();
        int freshHealth = PlacementItSupport.freshPort();
        int freshMetrics = PlacementItSupport.freshPort();
        Snapshot before = Snapshot.of(d);

        PlacementCollisionException container = assertThrows(PlacementCollisionException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(taken, taken.containerName(), null, freshHealth),
                        PlacementItSupport.declaration(freshMetrics))));
        PlacementCollisionException directory = assertThrows(PlacementCollisionException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(taken, null, taken.configDirectory(), freshHealth),
                        PlacementItSupport.declaration(freshMetrics))));
        PlacementCollisionException health = assertThrows(PlacementCollisionException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(taken, null, null, taken.probe().hostPort()),
                        PlacementItSupport.declaration(freshMetrics))));
        MetricsPortCollisionException metricsPort = assertThrows(MetricsPortCollisionException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(taken, null, null, freshHealth),
                        PlacementItSupport.declaration(d.metricsHostPort()))));

        assertEquals(PlacementCollisionException.Kind.CONTAINER_NAME, container.kind());
        assertEquals(PlacementCollisionException.Kind.CONFIG_DIRECTORY, directory.kind());
        assertEquals(PlacementCollisionException.Kind.HEALTH_PORT, health.kind());
        assertEquals(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IN_USE, metricsPort.kind());
        assertEquals(before, Snapshot.of(d),
                "each refusal rolled the whole add back: no placement, no endpoint, no stamp");
        assertEquals(List.of(), PlacementItSupport.configStates(d.tenant()), "and announced nothing");

        InstanceView ok = inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                onSameTargetAs(taken, null, null, freshHealth), PlacementItSupport.declaration(freshMetrics)));
        assertEquals(2, ok.placements().size(), "its OWN container, directory, health port and metrics port: allowed");
    }

    @Test
    void aPlacementOutsideTheTargetsEnvelopeIsRefusedNamingTheFieldAndWritesNothing() {
        Declared d = Declared.fresh("cache.hit_rate");
        Snapshot before = Snapshot.of(d);

        PlacementOutsideEnvelopeException container = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(d.spec(), "other-cache", null, PlacementItSupport.freshPort()),
                        PlacementItSupport.declaration(PlacementItSupport.freshPort()))));
        PlacementOutsideEnvelopeException directory = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> inventory.addPlacement(d.tenant(), d.instance(), add(d.target(),
                        onSameTargetAs(d.spec(), null, "/srv/elsewhere/p2", PlacementItSupport.freshPort()),
                        PlacementItSupport.declaration(PlacementItSupport.freshPort()))));

        assertEquals("containerName", container.field());
        assertEquals("configDirectory", directory.field());
        assertEquals(before, Snapshot.of(d));
    }

    @Test
    void aPendingOrAbandonedInstanceIsRefusedAndGainsNoPlacement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID second = InventoryFixtures.target(tenant);
        UUID pending = UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();
        InventoryFixtures.declare(tenant, pending, "pending");
        InventoryFixtures.declare(tenant, abandoned, "abandoned");
        InventoryFixtures.placement(tenant, pending, target);

        assertThrows(InstanceNotActiveException.class, () -> inventory.addPlacement(tenant, pending,
                add(second, PlacementItSupport.freshSpec(PlacementItSupport.freshPort()), MetricsDeclaration.none())));
        assertThrows(InstanceNotActiveException.class, () -> inventory.addPlacement(tenant, abandoned,
                add(second, PlacementItSupport.freshSpec(PlacementItSupport.freshPort()), MetricsDeclaration.none())));
        assertThrows(InstanceNotActiveException.class, () -> inventory.addPlacement(tenant, UUID.randomUUID(),
                add(second, PlacementItSupport.freshSpec(PlacementItSupport.freshPort()), MetricsDeclaration.none())));

        assertEquals(1, PlacementItSupport.count("placement", tenant), "only the arranged one");
    }

    @Test
    void anotherTenantsInstanceOrTargetIsIndistinguishableFromNothing() {
        Declared mine = Declared.fresh("cache.hit_rate");
        Declared theirs = Declared.fresh("cache.hit_rate");
        Snapshot before = Snapshot.of(theirs);

        assertThrows(InstanceNotActiveException.class, () -> inventory.addPlacement(mine.tenant(), theirs.instance(),
                add(mine.target(), PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                        PlacementItSupport.declaration(PlacementItSupport.freshPort()))), "their instance, my tenant (I5)");
        assertThrows(NoSuchElementException.class, () -> inventory.addPlacement(mine.tenant(), mine.instance(),
                add(theirs.target(), PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                        PlacementItSupport.declaration(PlacementItSupport.freshPort()))), "their target, my tenant (I5)");

        assertEquals(before, Snapshot.of(theirs));
    }

    @Test
    void aPackageDeclaringSolaceEventMetricsIsRefusedAndNothingIsWritten() {
        Declared d = Declared.fresh("cache.hit_rate");
        Snapshot before = Snapshot.of(d);
        MetricsDeclaration push = new MetricsDeclaration(PlacementItSupport.DIGEST,
                List.of(PlacementItSupport.pulled("cache.hit_rate"), PlacementItSupport.pushed("cache.evictions")),
                List.of(), Map.of(8081, PlacementItSupport.freshPort()), List.of());

        assertThrows(PushMetricsPlacementRefusedException.class, () -> inventory.addPlacement(d.tenant(), d.instance(),
                add(InventoryFixtures.target(d.tenant()), PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                        push)));

        assertEquals(before, Snapshot.of(d));
    }

    /**
     * The collector reads {@code PlacementRows.live} and each live placement's endpoint rows, and the health poller visits
     * {@code PlacementRows.live}; the new placement is in both the moment the add commits. The poller's own write is
     * {@code HealthPollerIT}'s for any live placement; the HTTP-level view of the new placement is
     * {@code AddPlacementEndpointIT}'s.
     */
    @Test
    void theNewPlacementIsPolledAndItsPullMetricsCollected() {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID second = InventoryFixtures.target(d.tenant());
        int metricsPort = PlacementItSupport.freshPort();
        var jdbc = PlacementItSupport.runtime();
        CollectionPlanBuilder builder = new CollectionPlanBuilder(new JdbcInstanceStateRepository(jdbc),
                new JdbcPlacementRepository(jdbc), new MetricDefinitionCollectionRows(PlacementItSupport.metrics()),
                (tenant, instance) -> OptionalLong.empty(), 30);
        assertEquals(1, builder.build(new TenantId(d.tenant())).units().size(), "precondition: one placement, one unit");

        InstanceView view = inventory.addPlacement(d.tenant(), d.instance(), add(second,
                PlacementItSupport.freshSpec(PlacementItSupport.freshPort()), PlacementItSupport.declaration(metricsPort)));

        UUID added = view.placements().get(1).placementId();
        List<CollectionPlan.Unit> units = builder.build(new TenantId(d.tenant())).units();
        assertEquals(2, units.size(), "one collection unit per live placement");
        CollectionPlan.Unit unit = units.stream().filter(u -> u.placementId().equals(added)).findFirst().orElseThrow();
        assertEquals(second, unit.targetId(), "read on the NEW target's address");
        assertEquals(metricsPort, unit.sources().get(0).request().hostPort(), "at the host port the add mapped 8081 to");
        assertEquals(2, new JdbcPlacementRepository(jdbc).live(d.tenant(), d.instance()).size(),
                "and the poller's enumeration sees it");
    }

    @Test
    void theLadderDropsToRenderedAndConfigStateIsAnnounced() {
        Declared d = PlacementItSupport.atRestartObserved();
        JdbcLadderRepository ladderRows = new JdbcLadderRepository(PlacementItSupport.runtime());
        TenantId tenant = new TenantId(d.tenant());
        InstanceId instance = new InstanceId(d.instance());
        assertEquals("restart-observed", PlacementItSupport.storedRung(d.tenant(), d.instance()),
                "precondition: the apply's landing wrote the rung");
        assertEquals(LadderRung.RESTART_OBSERVED, ConfigLadder.instanceRung(ladderRows.evidence(tenant, instance)));
        int announced = PlacementItSupport.configStates(d.tenant()).size();

        inventory.addPlacement(d.tenant(), d.instance(), add(InventoryFixtures.target(d.tenant()),
                PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                PlacementItSupport.declaration(PlacementItSupport.freshPort())));

        assertEquals(LadderRung.RENDERED, ConfigLadder.instanceRung(ladderRows.evidence(tenant, instance)),
                "the read: a placement with no evidence is the lowest rung (OR-37)");
        assertEquals("rendered", PlacementItSupport.storedRung(d.tenant(), d.instance()),
                "the stored rung follows the read (OQ-8.10 (a))");
        List<EventEnvelope> states = PlacementItSupport.configStates(d.tenant());
        assertEquals(announced + 1, states.size(), "one config-state notice, after commit");
        EventEnvelope last = states.get(states.size() - 1);
        assertEquals(Map.of("reached", LadderRung.RENDERED.wire()), last.payload(), "and the event agrees");
        assertEquals(PlacementItSupport.stateVersion(d.tenant(), d.instance()), last.stateVersion(),
                "the notice carries the stamp the reached_rung write left on the instance");
    }

    /**
     * W2 review W2A-2: an instance-wide stop reads the live placement set once, when it starts running, so a placement
     * added under one in flight would be left running under a stop that then reports the instance stopped. The add takes
     * {@code apply_scope} first and refuses {@code 409} while a {@code REQUESTED} or {@code RUNNING} instance-wide request
     * exists, writing nothing; a landed one, and a request on ONE placement, do not hold it.
     */
    @Test
    void anInstanceWideRequestInFlightRefusesTheAddAndWritesNothing() {
        for (String state : List.of("REQUESTED", "RUNNING")) {
            Declared d = Declared.fresh("cache.hit_rate");
            PlacementItSupport.request(d.tenant(), d.instance(), ApplyRows.INSTANCE_WIDE, "stop", state);
            Snapshot before = Snapshot.of(d);

            assertThrows(InstanceRequestInFlightException.class, () -> inventory.addPlacement(d.tenant(), d.instance(),
                    add(InventoryFixtures.target(d.tenant()), PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                            PlacementItSupport.declaration(PlacementItSupport.freshPort()))), state);

            assertEquals(before, Snapshot.of(d), state + ": nothing written, nothing stamped");
        }
    }

    @Test
    void aLandedInstanceWideStopOrARequestOnOnePlacementDoesNotRefuseTheAdd() {
        Declared landed = Declared.fresh("cache.hit_rate");
        PlacementItSupport.request(landed.tenant(), landed.instance(), ApplyRows.INSTANCE_WIDE, "stop", "STOPPED");
        Declared onOne = Declared.fresh("cache.hit_rate");
        PlacementItSupport.request(onOne.tenant(), onOne.instance(), onOne.placement(), "apply", "RUNNING");

        for (Declared d : List.of(landed, onOne)) {
            assertEquals(2, inventory.addPlacement(d.tenant(), d.instance(), add(InventoryFixtures.target(d.tenant()),
                    PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                    PlacementItSupport.declaration(PlacementItSupport.freshPort()))).placements().size());
        }
    }

    @Test
    void aRefusedAddAnnouncesNothingAndLeavesTheStoredRungAlone() {
        Declared d = PlacementItSupport.atRestartObserved();
        int announced = PlacementItSupport.configStates(d.tenant()).size();

        assertThrows(PlacementCollisionException.class, () -> inventory.addPlacement(d.tenant(), d.instance(),
                add(d.target(), onSameTargetAs(d.spec(), d.spec().containerName(), null, PlacementItSupport.freshPort()),
                        PlacementItSupport.declaration(PlacementItSupport.freshPort()))));

        assertEquals("restart-observed", PlacementItSupport.storedRung(d.tenant(), d.instance()));
        assertEquals(announced, PlacementItSupport.configStates(d.tenant()).size());
        assertFalse(PlacementItSupport.configStates(d.tenant()).stream()
                .anyMatch(e -> e.payload().equals(Map.of("reached", "rendered"))));
    }
}
