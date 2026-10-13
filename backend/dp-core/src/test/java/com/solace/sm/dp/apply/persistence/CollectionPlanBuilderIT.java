package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.collect.CollectionPlan;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Metric;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Source;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import com.solace.sm.dp.metrics.collect.CollectionPlanBuilder;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-4.3: what the collector decides to read, from DP rows only, over a real {@code sm_data} (a database of its
 * own, as the runtime credential). The restart-observed generation is scripted here (its SQL is
 * {@code RestartObservedGenerationIT}'s); everything else is the production adapters.
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectionPlanBuilderIT {

    private static IngestFixture ingest;
    private static CollectFixture fx;
    private static final Map<UUID, Long> RESTART_OBSERVED = new HashMap<>();

    @BeforeAll
    static void database() {
        ingest = IngestFixture.create("p43_plan");
        fx = new CollectFixture(ingest);
    }

    @AfterAll
    static void close() {
        ingest.close();
    }

    private static CollectionPlanBuilder builder() {
        return new CollectionPlanBuilder(fx.instances, fx.placements,
                new MetricDefinitionCollectionRows(fx.definitions),
                (tenant, instance) -> {
                    Long g = RESTART_OBSERVED.get(instance.value());
                    return g == null ? OptionalLong.empty() : OptionalLong.of(g);
                }, 30);
    }

    private static CollectionPlanBuilder builderWithTransports(
            com.solace.sm.dp.apply.runtime.PlacementTransports transports) {
        return new CollectionPlanBuilder(fx.instances, fx.placements, new MetricDefinitionCollectionRows(fx.definitions),
                (tenant, instance) -> OptionalLong.empty(), 30, transports);
    }

    private static CollectionPlan plan(UUID tenant) {
        return builder().build(new TenantId(tenant));
    }

    private static Unit onlyUnit(CollectionPlan plan) {
        assertEquals(1, plan.units().size(), "units: " + plan.units());
        return plan.units().get(0);
    }

    private static Set<String> names(Unit unit) {
        return unit.sources().stream().flatMap(s -> s.metrics().stream()).map(Metric::name)
                .collect(Collectors.toSet());
    }

    // ---- the shape of a Cache's plan ---------------------------------------------------------------------------

    @Test
    void metricsSharingAUrlShareOneSourceAndTheHostPortIsTheMappedOne() {
        CollectFixture.Cache cache = fx.cache("direct-http", "vm-1.corp.example", true);

        Unit unit = onlyUnit(plan(cache.instance().tenant()));

        assertEquals(cache.placement(), unit.placementId());
        assertEquals(cache.target(), unit.targetId());
        assertEquals(30, unit.intervalSeconds(), "the interval a metric that declares none is read at");
        assertEquals(2, unit.sources().size(), "four metrics, two URLs");
        Source stats = unit.sources().stream().filter(s -> s.request().path().equals("/api/cache/stats")).findFirst()
                .orElseThrow();
        assertEquals(3, stats.metrics().size());
        assertEquals(18081, stats.request().hostPort(), "the HOST port the endpoint row maps 8081 to, never 8081");
        assertTrue(stats.request().queryParams().isEmpty());
        Source size = unit.sources().stream().filter(s -> s.request().path().equals("/api/cache/size")).findFirst()
                .orElseThrow();
        assertEquals(Map.of("filterByNamespace", "false"), size.request().queryParams());
        assertEquals(new Metric("cache.size", "cache-engine", null), size.metrics().get(0), "no pointer: the whole body");
        assertEquals(Set.of("cache.hit_rate", "cache.hit_count", "cache.miss_count", "cache.size"), names(unit));
    }

    @Test
    void aDirectInstanceCarriesItsDeclaredRestartSignalOnTheHostPortAndAnSshOneCarriesNone() {
        CollectFixture.Cache direct = fx.cache("direct-http", "vm-1.corp.example", true);
        CollectFixture.Cache ssh = fx.cache("via-ssh", "vm-2.corp.example", true);

        Unit d = onlyUnit(plan(direct.instance().tenant()));
        Unit s = onlyUnit(plan(ssh.instance().tenant()));

        assertEquals(new RestartRequest.Signal(18081, "/actuator/metrics/process.start.time", "/measurements/0/value"),
                d.signals().get("cache-engine"));
        assertTrue(s.signals().isEmpty(), "SSH mode's signal is the container's own: nothing to read over HTTP");
    }

    @Test
    void aSignalOnAPortNobodyMappedIsNotRead() {
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(),
                List.of(CollectFixture.startTimeSignal("cache-engine", 9099)));
        CollectFixture.Instance instance = fx.instance(tenant, solution, "2.4", "direct-http", "active");
        fx.placement(instance, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(instance, 0, fx.cacheMetricNames().toArray(String[]::new));

        assertTrue(onlyUnit(plan(tenant)).signals().isEmpty());
    }

    @Test
    void aMetricWithItsOwnIntervalIsReadOnItsOwnCadence() {
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "1.0", List.of(
                CollectFixture.http("a.fast", "gauge", "svc", "/a", 8081, "/v", Map.of(), 10),
                CollectFixture.http("a.slow", "gauge", "svc", "/b", 8081, "/v", Map.of(), 120),
                CollectFixture.http("a.default", "gauge", "svc", "/c", 8081, "/v", Map.of(), null)), List.of());
        CollectFixture.Instance instance = fx.instance(tenant, solution, "1.0", "via-ssh", "active");
        fx.placement(instance, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(instance, 0, "a.fast", "a.slow", "a.default");

        List<Integer> intervals = plan(tenant).units().stream().map(Unit::intervalSeconds).sorted().toList();

        assertEquals(List.of(10, 30, 120), intervals);
    }

    // ---- S-G: two placements on one target ---------------------------------------------------------------------

    @Test
    void twoPlacementsOnOneTargetAreEachScrapedOnTheirOwnHostPort() {
        for (String mode : List.of("direct-http", "via-ssh")) {
            CollectFixture.Cache first = fx.cache(mode, "vm-1.corp.example", false);
            CollectFixture.Cache second = fx.anotherCacheOn(first, 28081);

            CollectionPlan plan = plan(first.instance().tenant());

            assertEquals(2, plan.units().size(), mode);
            Map<UUID, Set<Integer>> portsByPlacement = plan.units().stream().collect(Collectors.toMap(Unit::placementId,
                    u -> u.sources().stream().map(s -> s.request().hostPort()).collect(Collectors.toSet())));
            assertEquals(Set.of(18081), portsByPlacement.get(first.placement()), mode);
            assertEquals(Set.of(28081), portsByPlacement.get(second.placement()), mode);
            assertEquals(1, plan.units().stream().map(Unit::targetId).distinct().count(), "one target, two placements");
        }
    }

    // ---- the mode, the re-target (M-Q2, OR-82) -----------------------------------------------------------------

    @Test
    void theModeIsTheInstancesAndSurvivesARetarget() {
        for (String mode : List.of("direct-http", "via-ssh")) {
            CollectFixture.Cache cache = fx.cache(mode, "vm-1.corp.example", false);
            assertEquals(AcquisitionMode.fromWire(mode), onlyUnit(plan(cache.instance().tenant())).mode());

            fx.retire(cache.instance().tenant(), cache.placement());
            UUID newTarget = fx.target(cache.instance().tenant(), "vm-9.corp.example");
            fx.placement(cache.instance(), newTarget, Map.of(8081, 18081));

            assertEquals(AcquisitionMode.fromWire(mode), onlyUnit(plan(cache.instance().tenant())).mode(),
                    "the mode is the instance's, whichever target it is now on: " + mode);
        }
    }

    @Test
    void aReTargetedDirectInstanceIsPolledAtTheNewTargetsAddress() {
        CollectFixture.Cache cache = fx.cache("direct-http", "vm-old.corp.example", false);
        Unit before = onlyUnit(plan(cache.instance().tenant()));
        assertEquals(cache.target(), before.targetId());

        fx.retire(cache.instance().tenant(), cache.placement());
        UUID newTarget = fx.target(cache.instance().tenant(), "vm-new.corp.example");
        UUID newPlacement = fx.placement(cache.instance(), newTarget, Map.of(8081, 18081));

        Unit after = onlyUnit(plan(cache.instance().tenant()));

        assertEquals(newTarget, after.targetId(), "nothing is carried forward: the unit names the NEW target");
        assertEquals(newPlacement, after.placementId());
        assertTrue(after.sources().stream().allMatch(s -> s.request().targetId().equals(newTarget)),
                "every fetch request names the new target, whose registered address the fetcher reads fresh");
        assertFalse(after.sources().stream().anyMatch(s -> s.request().targetId().equals(cache.target())));
    }

    // ---- lifecycle filters (BE-D32, S-K) -------------------------------------------------------------------------

    @Test
    void aPendingInstanceIsNeverPlanned() {
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
        CollectFixture.Instance pending = fx.instance(tenant, solution, "2.4", "via-ssh", "pending");
        fx.placement(pending, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(pending, 0, fx.cacheMetricNames().toArray(String[]::new));

        assertTrue(plan(tenant).units().isEmpty(), "between reserve and activate nothing is read");

        fx.superuser.update("UPDATE sm_data.instance_state SET lifecycle = 'active' WHERE tenant_id = :t",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", tenant));
        assertEquals(1, plan(tenant).units().size(), "and once activated it is");
    }

    @Test
    void aRetiredPlacementAndAnAbandonedInstanceAreNeverPlanned() {
        CollectFixture.Cache retired = fx.cache("via-ssh", "vm-1.corp.example", false);
        fx.retire(retired.instance().tenant(), retired.placement());
        assertTrue(plan(retired.instance().tenant()).units().isEmpty(), "a retired placement");

        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
        CollectFixture.Instance abandoned = fx.instance(tenant, solution, "2.4", "via-ssh", "pending");
        fx.placement(abandoned, fx.target(tenant, "vm-2.corp.example"), Map.of(8081, 18081));
        fx.activeList(abandoned, 0, fx.cacheMetricNames().toArray(String[]::new));
        fx.lifecycle(abandoned, "abandoned");             // the only way out of pending besides active (BE-D32)
        assertTrue(plan(tenant).units().isEmpty(), "an abandoned instance");
    }

    // ---- what is collected at all -------------------------------------------------------------------------------

    @Test
    void histogramsUncollectedKindsAndPortlessOrUnmappedSourcesAreNeverPlanned() {
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "1.0", List.of(
                CollectFixture.http("ok.gauge", "gauge", "svc", "/g", 8081, "/v", Map.of(), null),
                CollectFixture.http("bad.histogram", "histogram", "svc", "/h", 8081, "/v", Map.of(), null),
                CollectFixture.otherKind("bad.prometheus", "prometheus"),
                CollectFixture.otherKind("bad.exec", "exec"),
                CollectFixture.http("bad.portless", "gauge", "svc", "/p", null, "/v", Map.of(), null),
                CollectFixture.http("bad.unmapped", "gauge", "svc", "/u", 9999, "/v", Map.of(), null)), List.of());
        CollectFixture.Instance instance = fx.instance(tenant, solution, "1.0", "via-ssh", "active");
        fx.placement(instance, fx.target(tenant, "vm-1.corp.example"), Map.of(8081, 18081));
        fx.activeList(instance, 0, "ok.gauge", "bad.histogram", "bad.prometheus", "bad.exec", "bad.portless",
                "bad.unmapped");

        assertEquals(Set.of("ok.gauge"), names(onlyUnit(plan(tenant))));
    }

    // ---- M-Q15: the active list --------------------------------------------------------------------------------

    @Test
    void theActiveListSwitchesOnlyAtRestartObserved() {
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        fx.activeList(cache.instance(), 2, "cache.hit_rate");       // staged / applied, not yet restarted: no switch
        UUID instance = cache.instance().instance();

        RESTART_OBSERVED.remove(instance);
        assertEquals(4, names(onlyUnit(plan(cache.instance().tenant()))).size(),
                "no generation reached restart-observed: generation 0's list");

        RESTART_OBSERVED.put(instance, 2L);
        assertEquals(Set.of("cache.hit_rate"), names(onlyUnit(plan(cache.instance().tenant()))),
                "generation 2 observed its restart: its list is now the one read");

        RESTART_OBSERVED.remove(instance);
        assertEquals(4, names(onlyUnit(plan(cache.instance().tenant()))).size(),
                "the staged list is never read before the switch");
    }

    @Test
    void aGenerationWithNoRecordedListCollectsNothingRatherThanGuessing() {
        CollectFixture.Cache cache = fx.cache("via-ssh", "vm-1.corp.example", false);
        RESTART_OBSERVED.put(cache.instance().instance(), 7L);          // no list was ever written for generation 7

        assertTrue(plan(cache.instance().tenant()).units().isEmpty(),
                "every generation is born with a list: its absence is a defect, and a defect collects nothing");
    }

    @Test
    void aTenantsPlanNeverContainsAnotherTenantsPlacement() {
        CollectFixture.Cache mine = fx.cache("via-ssh", "vm-1.corp.example", false);
        CollectFixture.Cache theirs = fx.cache("via-ssh", "vm-2.corp.example", false);

        CollectionPlan plan = plan(mine.instance().tenant());

        assertEquals(List.of(mine.placement()), plan.units().stream().map(Unit::placementId).toList());
        assertFalse(plan.units().stream().anyMatch(u -> u.placementId().equals(theirs.placement())));
        assertEquals(mine.instance().tenant(), plan.tenant().value());
        assertEquals(new InstanceId(mine.instance().instance()), onlyUnit(plan).placement().instance());
    }

    /**
     * Plan 015 T3.1 (HF19): an agent placement is never planned. The agent delivers its own telemetry (T3.4), and a collector
     * fetch to an agent target has no address to reach. The control is the same instance shape on a dp-remote target.
     */
    @Test
    void excludesAgentPlacements() {
        CollectFixture.Cache sshCache = fx.cache("direct-http", "vm-1.corp.example", true);
        // the same Cache-shaped instance, but its placement sits on an AGENT target (an agent target carries no host)
        UUID tenant = fx.tenant();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(),
                List.of(CollectFixture.startTimeSignal("cache-engine", 8081)));
        CollectFixture.Instance instance = fx.instance(tenant, solution, "2.4", "direct-http", "active");
        UUID agentTarget = InventoryFixtures.agentTarget(ingest.jdbc, tenant, UUID.randomUUID());
        fx.placement(instance, agentTarget, Map.of(8081, 18081));
        fx.activeList(instance, 0, fx.cacheMetricNames().toArray(String[]::new));
        CollectFixture.Cache agentCache = new CollectFixture.Cache(instance, agentTarget, UUID.randomUUID());
        var transports = new com.solace.sm.dp.apply.persistence.agent.JdbcAgentPlacementCatalog(ingest.jdbc);

        assertEquals(0, builderWithTransports(transports).build(new TenantId(agentCache.instance().tenant())).units().size(),
                "nothing of an agent placement is planned");
        assertEquals(1, builderWithTransports(transports).build(new TenantId(sshCache.instance().tenant())).units().size(),
                "a dp-remote placement still is");
    }
}
