package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.home.AttentionCandidate;
import com.solace.sm.dp.api.home.AttentionReason;
import com.solace.sm.dp.api.home.InstanceHealthSummary;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.home.AttentionServiceImpl;
import com.solace.sm.dp.home.InventorySummaryServiceImpl;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T10.5 (OR-45, OR-60, OR-61): the data plane's two home reads against a real {@code sm_data} schema, as
 * {@code sm_dp_runtime}. What is proven is what the data plane owns: which instances and requests are listed, the
 * rollup it hands the gateway, each item's own stamp, and that nothing of another tenant, another lifecycle or an
 * older request is read. Who may SEE a row is the gateway's and is proven there.
 */
@Testcontainers(disabledWithoutDocker = true)
class HomeReadsIT {

    private static NamedParameterJdbcTemplate jdbc;
    private static PlacementHealthRows healthRows;
    private static InventorySummaryServiceImpl summary;
    private static AttentionServiceImpl attention;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        jdbc = new NamedParameterJdbcTemplate(SmDataPostgres.runtimeDataSource());
        healthRows = new JdbcPlacementHealthRepository(jdbc);
        JdbcInstanceStateRepository instances = new JdbcInstanceStateRepository(jdbc);
        summary = new InventorySummaryServiceImpl(instances, healthRows);
        attention = new AttentionServiceImpl(instances, healthRows, new JdbcApplyAttentionRepository(jdbc));
    }

    private static UUID instanceWithPlacement(UUID tenant, HealthState state, String probeKind) {
        UUID instance = HealthFixtures.activeInstance(jdbc, tenant, "via-ssh");
        UUID target = HealthFixtures.target(jdbc, tenant, "vm-" + UUID.randomUUID().toString().substring(0, 8)
                + ".corp.example");
        UUID placement = HealthFixtures.placement(jdbc, tenant, instance, target, probeKind,
                probeKind == null ? null : "/actuator/health", probeKind == null ? null : 8081);
        if (state != null) {
            healthRows.record(tenant, instance, placement, state, Instant.parse("2026-10-06T10:00:00Z"));
        }
        return instance;
    }

    private static UUID placementOf(UUID tenant, UUID instance) {
        return jdbc.queryForObject("SELECT placement_id FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), UUID.class);
    }

    private static Map<UUID, InstanceHealthSummary> summaryOf(UUID tenant) {
        return summary.summarize(tenant).stream()
                .collect(Collectors.toMap(InstanceHealthSummary::instanceId, s -> s));
    }

    // ---- the summary -----------------------------------------------------------------------------------

    @Test
    void theSummaryListsEveryActiveInstanceOfTheTenantWithItsCatalogSolutionAndRollup() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID green = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID red = instanceWithPlacement(tenant, HealthState.RED, "actuator-health");
        UUID noProbe = instanceWithPlacement(tenant, HealthState.NOT_APPLICABLE, null);
        UUID unpolled = instanceWithPlacement(tenant, null, "actuator-health");

        Map<UUID, InstanceHealthSummary> rows = summaryOf(tenant);

        assertEquals(4, rows.size());
        assertEquals(Optional.of(HealthState.GREEN), rows.get(green).rollup());
        assertEquals(Optional.of(HealthState.RED), rows.get(red).rollup());
        assertEquals(Optional.of(HealthState.NOT_APPLICABLE), rows.get(noProbe).rollup());
        assertEquals(Optional.empty(), rows.get(unpolled).rollup(), "nothing polled: declared, not invented");
        UUID catalogOfGreen = jdbc.queryForObject("SELECT catalog_solution_id FROM sm_data.instance_state"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", green), UUID.class);
        assertEquals(catalogOfGreen, rows.get(green).catalogSolutionId());
    }

    @Test
    void theSummaryRollsAnInstancesPlacementsUpTogether() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID second = HealthFixtures.placement(jdbc, tenant, instance, HealthFixtures.target(jdbc, tenant,
                "vm-two.corp.example"), "actuator-health", "/actuator/health", 8082);
        healthRows.record(tenant, instance, second, HealthState.UNREACHABLE, Instant.parse("2026-10-06T10:00:00Z"));

        assertEquals(Optional.of(HealthState.UNREACHABLE), summaryOf(tenant).get(instance).rollup());
    }

    @Test
    void theSummaryReadsOnlyThisTenantsActiveInstances() {
        UUID mine = SmDataPostgres.freshTenant();
        UUID theirs = SmDataPostgres.freshTenant();
        UUID own = instanceWithPlacement(mine, HealthState.GREEN, "actuator-health");
        UUID foreign = instanceWithPlacement(theirs, HealthState.RED, "actuator-health");
        UUID pending = HealthFixtures.instance(jdbc, mine, "via-ssh", "pending");

        Map<UUID, InstanceHealthSummary> rows = summaryOf(mine);

        assertEquals(java.util.Set.of(own), rows.keySet(), "no other tenant's row, and no pending instance");
        assertFalse(rows.containsKey(foreign));
        assertFalse(rows.containsKey(pending));
    }

    // ---- attention: health -----------------------------------------------------------------------------

    @Test
    void healthAttentionListsEveryPlacementThatIsNotHealthyEachWithItsOwnStamp() {
        UUID tenant = SmDataPostgres.freshTenant();
        Map<HealthState, UUID> byState = new java.util.EnumMap<>(HealthState.class);
        for (HealthState state : HealthState.values()) {
            byState.put(state, instanceWithPlacement(tenant, state, "actuator-health"));
        }

        List<AttentionCandidate> items = attention.attention(tenant);

        java.util.Set<HealthState> listed = items.stream().map(c -> c.state().orElseThrow())
                .collect(Collectors.toSet());
        assertEquals(java.util.EnumSet.of(HealthState.RED, HealthState.RESTART_EXPECTED, HealthState.UNREACHABLE,
                HealthState.NOT_EXPOSED, HealthState.MISSING, HealthState.BOOTSTRAP), listed,
                "GREEN, INACTIVE_REPLICA (a healthy standby) and NOT_APPLICABLE (OR-101) are not attention");
        for (AttentionCandidate c : items) {
            assertEquals(AttentionReason.HEALTH, c.reason());
            UUID instance = byState.get(c.state().get());
            assertEquals(instance, c.instanceId());
            assertEquals(Optional.of(placementOf(tenant, instance)), c.placementId());
            assertEquals(healthRows.find(tenant, placementOf(tenant, instance)).orElseThrow().stateVersion(),
                    c.stateVersion(), "the placement health row's OWN stamp (OR-60)");
        }
    }

    @Test
    void anInstanceNothingHasPolledIsNotListed() {
        UUID tenant = SmDataPostgres.freshTenant();
        instanceWithPlacement(tenant, null, "actuator-health");

        assertTrue(attention.attention(tenant).isEmpty());
    }

    // ---- attention: apply ------------------------------------------------------------------------------

    private static void request(UUID tenant, UUID instance, UUID placement, String state, OffsetDateTime at,
            boolean terminal) {
        jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
        jdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 1, 'manual', 'operator-1', :at, :s, :term)",
                new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", tenant)
                        .addValue("i", instance).addValue("p", placement).addValue("at", at).addValue("s", state)
                        .addValue("term", terminal ? at : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE));
    }

    private static long stampOf(UUID tenant, UUID instance, String state) {
        return jdbc.queryForObject("SELECT state_version FROM sm_data.apply_request WHERE tenant_id = :t"
                        + " AND instance_id = :i AND state = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("s", state),
                Long.class);
    }

    @Test
    void anIndeterminateRequestIsListedWithItsOwnStamp() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID placement = placementOf(tenant, instance);
        request(tenant, instance, placement, "APPLY_INDETERMINATE", OffsetDateTime.now(), false);

        List<AttentionCandidate> items = attention.attention(tenant);

        assertEquals(1, items.size(), "the placement is GREEN, so health lists nothing");
        AttentionCandidate item = items.get(0);
        assertEquals(AttentionReason.APPLY_INDETERMINATE, item.reason());
        assertEquals(instance, item.instanceId());
        assertEquals(Optional.of(placement), item.placementId());
        assertEquals(Optional.empty(), item.state(), "a state is a health reason's only");
        assertEquals(stampOf(tenant, instance, "APPLY_INDETERMINATE"), item.stateVersion());
    }

    @Test
    void aDivergedRequestIsListedWhileItIsTheLatestForItsPlacement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID placement = placementOf(tenant, instance);
        request(tenant, instance, placement, "CONFIG_DIVERGED", OffsetDateTime.now(), true);

        List<AttentionCandidate> items = attention.attention(tenant);

        assertEquals(1, items.size());
        assertEquals(AttentionReason.CONFIG_DIVERGED, items.get(0).reason());
        assertEquals(stampOf(tenant, instance, "CONFIG_DIVERGED"), items.get(0).stateVersion());
    }

    @Test
    void aDivergenceThatALaterRequestSupersededIsHistoryNotAttention() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID placement = placementOf(tenant, instance);
        OffsetDateTime earlier = OffsetDateTime.now().minusHours(2);
        request(tenant, instance, placement, "CONFIG_DIVERGED", earlier, true);
        request(tenant, instance, placement, "APPLIED", earlier.plusHours(1), true);

        assertTrue(attention.attention(tenant).isEmpty(), "the placement's latest request is APPLIED");
    }

    @Test
    void anIndeterminateApplyIsStillListedAfterALaterStopOnTheSamePlacement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        UUID placement = placementOf(tenant, instance);
        OffsetDateTime earlier = OffsetDateTime.now().minusHours(2);
        request(tenant, instance, placement, "APPLY_INDETERMINATE", earlier, false);
        // S811-4: admission does not refuse a stop on a scope holding an indeterminate apply, and the stop becomes the
        // latest request of any operation there - it must not hide the apply an operator still has to resolve
        jdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'stop', 'operator-1', :at, 'STOPPED', :at)",
                new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", tenant)
                        .addValue("i", instance).addValue("p", placement).addValue("at", earlier.plusHours(1)));

        List<AttentionCandidate> items = attention.attention(tenant);

        assertEquals(1, items.size(), "the indeterminate apply is listed although a stop is the latest request");
        assertEquals(AttentionReason.APPLY_INDETERMINATE, items.get(0).reason());
        assertEquals(stampOf(tenant, instance, "APPLY_INDETERMINATE"), items.get(0).stateVersion());
    }

    @Test
    void anotherTenantsOrAnInactiveInstancesRequestsAreNeverRead() {
        UUID mine = SmDataPostgres.freshTenant();
        UUID theirs = SmDataPostgres.freshTenant();
        UUID foreign = instanceWithPlacement(theirs, HealthState.GREEN, "actuator-health");
        request(theirs, foreign, placementOf(theirs, foreign), "APPLY_INDETERMINATE", OffsetDateTime.now(), false);
        UUID abandoned = HealthFixtures.instance(jdbc, mine, "via-ssh", "pending");
        UUID abandonedPlacement = InventoryFixtures.placement(jdbc, mine, abandoned, InventoryFixtures.target(jdbc, mine),
                UUID.randomUUID());   // V024: a request names a registered placement of its instance
        request(mine, abandoned, abandonedPlacement, "CONFIG_DIVERGED", OffsetDateTime.now(), true);
        jdbc.update("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", mine).addValue("i", abandoned));

        assertTrue(attention.attention(mine).isEmpty());
        assertEquals(1, attention.attention(theirs).size());
    }

    @Test
    void anInstanceWideRequestIsListedWithoutAPlacement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = instanceWithPlacement(tenant, HealthState.GREEN, "actuator-health");
        request(tenant, instance, ApplyRows.INSTANCE_WIDE, "APPLY_INDETERMINATE", OffsetDateTime.now(), false);

        List<AttentionCandidate> items = attention.attention(tenant);

        assertEquals(1, items.size());
        assertEquals(Optional.empty(), items.get(0).placementId(), "the nil sentinel is not a placement id");
    }
}
