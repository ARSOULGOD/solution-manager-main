package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.3 ({@code AGW-D28}, {@code AGW-D25}): what an accepted, refused, stale and untrusted report writes, over the real
 * repositories as the runtime role on PostgreSQL 14. The ladder half is {@code AgentReportLadderIT}; the port contract is the kit.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentReportIntakeIT {

    private static final Instant STARTED = Instant.parse("2026-10-07T00:00:00Z");
    private static final String PLAIN = "sha256:" + "ab".repeat(32);

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static AgentHealthObservation up() {
        return new AgentHealthObservation(AgentHealthObservation.State.HEALTHY, STARTED,
                AgentHealthObservation.Strength.READINESS, 200, "UP", AgentHealthObservation.Failure.NONE);
    }

    private static AgentHealthObservation down() {
        return new AgentHealthObservation(AgentHealthObservation.State.UNHEALTHY, STARTED,
                AgentHealthObservation.Strength.READINESS, 503, "DOWN", AgentHealthObservation.Failure.NONE);
    }

    private static ReportItem item(long sequence, UUID placement, AgentHealthObservation health, Instant startedAt) {
        return new ReportItem(id(), sequence, placement.toString(), "sm-" + placement, "c0ffee01", 7, null, PLAIN, "image-digest",
                "sha256:abc", "low", null, health, startedAt);
    }

    private List<ReportResult> report(AgentSessionRef session, ReportItem... items) {
        return world.reports().report(session, List.of(items), world.origin());
    }

    /** A second live placement of the agent's instance that DECLARES an actuator probe, named in the lease the agent is granted. */
    private record Probed(AgentKitWorld.Agent agent, AgentSessionRef session, UUID placement) {
    }

    private Probed probed() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.open(agent);
        UUID placement = UUID.randomUUID();
        world.ownerJdbc().update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " health_kind, health_path, health_port, health_strength, created_at) VALUES (:t, :p, :i, :x, :c, :d,"
                        + " '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, 'actuator-health',"
                        + " '/actuator/health', 18080, 'readiness', :now)",
                new MapSqlParameterSource().addValue("t", agent.tenantId()).addValue("p", placement)
                        .addValue("i", agent.instanceId()).addValue("x", agent.targetId()).addValue("c", "sm-" + placement)
                        .addValue("d", "/srv/sm/" + placement).addValue("now", OffsetDateTime.now()));
        world.issuerAlsoNames(placement);
        world.lease(agent, session);
        world.issuerAlsoNames(null);          // the world's issuer is sticky: it must not add this placement to the next agent's lease
        return new Probed(agent, session, placement);
    }

    private String healthState(UUID tenant, UUID placement) {
        List<String> rows = world.ownerJdbc().queryForList("SELECT state FROM sm_data.placement_health WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long healthVersion(UUID tenant, UUID placement) {
        return world.ownerJdbc().queryForObject("SELECT state_version FROM sm_data.placement_health WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement), Long.class);
    }

    private long events(UUID tenant, String code) {
        return world.ownerJdbc().queryForObject("SELECT count(*) FROM sm_data.agent_event WHERE tenant_id = :t AND code = :c",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", code), Long.class);
    }

    @Test
    void aHealthyReportWritesOnePlacementHealthRowAndTheSameAgainWritesNone() {
        Probed p = probed();
        report(p.session(), item(1, p.placement(), up(), STARTED));
        assertEquals("GREEN", healthState(p.agent().tenantId(), p.placement()));
        long version = healthVersion(p.agent().tenantId(), p.placement());
        long published = world.published().stream().filter(e -> e.event().equals("instance.health")).count();
        assertEquals(1, published, "the first verdict is a change");

        report(p.session(), item(2, p.placement(), up(), STARTED));

        assertEquals(version, healthVersion(p.agent().tenantId(), p.placement()), "OR-39: only changes are persisted");
        assertEquals(published, world.published().stream().filter(e -> e.event().equals("instance.health")).count());
    }

    @Test
    void theDeclaredProbesFactsAreMappedByTheSameMapperAsDpRemote() {
        Probed p = probed();
        report(p.session(), item(1, p.placement(), down(), STARTED));
        assertEquals("RED", healthState(p.agent().tenantId(), p.placement()), "503 / DOWN is RED, as for dp-remote");
        report(p.session(), item(2, p.placement(), up(), STARTED));
        assertEquals("GREEN", healthState(p.agent().tenantId(), p.placement()));
    }

    @Test
    void anOlderSequenceIsIgnoredAndNeverOverwritesNewerHealth() {
        Probed p = probed();
        report(p.session(), item(10, p.placement(), up(), STARTED));
        List<ReportResult> stale = report(p.session(), item(5, p.placement(), down(), STARTED));
        assertEquals("STALE", stale.get(0).disposition());
        assertEquals("GREEN", healthState(p.agent().tenantId(), p.placement()));
    }

    @Test
    void aReplayedItemAppliesNothingASecondTime() {
        Probed p = probed();
        ReportItem first = item(1, p.placement(), up(), STARTED);
        report(p.session(), first);
        long version = healthVersion(p.agent().tenantId(), p.placement());
        world.ownerJdbc().update("UPDATE sm_data.placement_health SET state = 'RED' WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", p.agent().tenantId()).addValue("p", p.placement()));
        long afterTamper = healthVersion(p.agent().tenantId(), p.placement());
        assertTrue(afterTamper > version, "the owner's change was a change");
        report(p.session(), first);
        assertEquals("RED", healthState(p.agent().tenantId(), p.placement()), "a replay returns the verdict, it is not a new observation");
        assertEquals(afterTamper, healthVersion(p.agent().tenantId(), p.placement()), "and writes nothing");
    }

    @Test
    void aPlacementWithNoDeclaredProbeIsNotApplicableWhateverTheAgentReports() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        report(session, item(1, agent.placementId(), up(), STARTED));
        assertEquals("NOT_APPLICABLE", healthState(agent.tenantId(), agent.placementId()), "OR-101");
    }

    /**
     * "Persisted on change" is the V012 trigger's guarantee for a probed placement (an unchanged state keeps its version, while
     * checked_at moves, as for the dp-remote poller). For a placement with NO probe the intake itself must not write after the first
     * time: a second report leaves checked_at where it was.
     */
    @Test
    void aSecondReportForAPlacementWithNoProbeWritesNothingAtAll() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        report(session, item(1, agent.placementId(), up(), STARTED));
        OffsetDateTime first = world.ownerJdbc().queryForObject("SELECT checked_at FROM sm_data.placement_health WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", agent.tenantId())
                .addValue("p", agent.placementId()), OffsetDateTime.class);
        world.advance(Duration.ofSeconds(30));

        report(session, item(2, agent.placementId(), up(), STARTED));

        assertEquals(first, world.ownerJdbc().queryForObject("SELECT checked_at FROM sm_data.placement_health WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", agent.tenantId())
                .addValue("p", agent.placementId()), OffsetDateTime.class));
    }

    @Test
    void aReportForAPlacementTheLeaseDoesNotHoldIsRefusedAndAudited() {
        Probed p = probed();
        AgentKitWorld.Agent stranger = world.enrol();
        List<ReportResult> refused = report(p.session(), item(1, stranger.placementId(), up(), STARTED));
        assertEquals(GatewayCode.BINDING_NOT_IN_LEASE, refused.get(0).refusedReason());
        assertEquals(1, events(p.agent().tenantId(), "agent.report.refused"));
        assertEquals(0, events(stranger.tenantId(), "agent.report.refused"), "the other organization sees nothing");
        assertEquals(null, healthState(stranger.tenantId(), stranger.placementId()));
        assertEquals(0, world.rows("agent_report_watermark", stranger));
    }

    /**
     * Defence in depth for the same-host clock rule (OR-112, plan 015 P15-22): a lease row that names a placement of ANOTHER host
     * of the same organization (a stale or tampered row) is not a lease over it. Nothing is stored for it, so a start observed on
     * one host is never compared with a baseline taken on another.
     */
    @Test
    void aPlacementOnAnotherHostIsRefusedEvenWhenTheLeaseRowNamesIt() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        UUID sshTarget = com.solace.sm.dp.apply.persistence.AgentItSupport.sshTarget(world.ownerJdbc(), agent.tenantId());
        UUID elsewhere = UUID.randomUUID();
        com.solace.sm.dp.apply.persistence.AgentItSupport.placement(world.ownerJdbc(), agent.tenantId(), agent.instanceId(),
                sshTarget, elsewhere);
        world.ownerJdbc().update("UPDATE sm_data.agent_lease SET placement_ids = placement_ids || CAST(:p AS uuid)"
                + " WHERE tenant_id = :t AND agent_id = :a AND state = 'ACTIVE'", new MapSqlParameterSource()
                .addValue("p", elsewhere).addValue("t", agent.tenantId()).addValue("a", agent.agentId()));

        ReportResult result = report(session, item(1, elsewhere, up(), STARTED.plusSeconds(10))).get(0);

        assertEquals(GatewayCode.BINDING_NOT_IN_LEASE, result.refusedReason());
        assertEquals(0, world.ownerJdbc().queryForObject("SELECT count(*) FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t", new MapSqlParameterSource().addValue("t", agent.tenantId()), Long.class));
    }

    @Test
    void aWrongReplicaRefIsRefusedAndAuditedAndWritesNoHealthAndNoStart() {
        Probed p = probed();
        ReportItem wrong = new ReportItem(id(), 1, p.placement().toString(), "sm-another", "c0ffee01", 7, null, PLAIN, null, null,
                null, null, up(), STARTED);
        List<ReportResult> refused = report(p.session(), wrong);
        assertEquals(GatewayCode.REPLICA_REF_INVALID, refused.get(0).refusedReason());
        assertEquals(1, events(p.agent().tenantId(), "agent.report.refused"));
        assertEquals(null, healthState(p.agent().tenantId(), p.placement()));
        assertEquals(0, world.ownerJdbc().queryForObject("SELECT count(*) FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t", new MapSqlParameterSource().addValue("t", p.agent().tenantId()), Long.class));
    }

    @Test
    void theStartIsRecordedForTheReportedPlacementOnlyAndMovesForwardOnly() {
        Probed p = probed();
        report(p.session(), item(1, p.placement(), up(), STARTED.plusSeconds(100)));
        report(p.session(), item(2, p.placement(), up(), STARTED));
        List<OffsetDateTime> rows = world.ownerJdbc().queryForList("SELECT started_at FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t", new MapSqlParameterSource().addValue("t", p.agent().tenantId()), OffsetDateTime.class);
        assertEquals(1, rows.size(), "the sibling placement on the same host has no observation of its own");
        assertEquals(STARTED.plusSeconds(100), rows.get(0).toInstant());
    }

    @Test
    void anUntrustedReportAfterTheLeaseExpiredWritesNoHealthAndNoStart() {
        Probed p = probed();
        world.advance(Duration.ofMinutes(30));
        List<ReportResult> r = report(p.session(), item(1, p.placement(), up(), STARTED));
        assertEquals("UNTRUSTED", r.get(0).trust());
        assertEquals("APPLIED", r.get(0).disposition());
        assertEquals(null, healthState(p.agent().tenantId(), p.placement()));
        assertEquals(Optional.empty(), world.observedState().latest(new AgentPlacementRef(p.agent().tenantId(),
                p.agent().instanceId(), p.placement())));
    }

    @Test
    void latestReturnsWhatTheLastAcceptedReportSaidAndLivenessIsReadFresh() {
        Probed p = probed();
        AgentPlacementRef ref = new AgentPlacementRef(p.agent().tenantId(), p.agent().instanceId(), p.placement());
        assertEquals(Optional.empty(), world.observedState().latest(ref), "nothing reported yet is unknown, never healthy");
        report(p.session(), item(1, p.placement(), up(), STARTED));

        AgentObservation seen = world.observedState().latest(ref).orElseThrow();

        assertTrue(seen.live());
        assertEquals(PLAIN, seen.reportedSha256());
        assertEquals(7L, seen.persistedGeneration());
        assertEquals(STARTED, seen.startedAt());
        assertEquals(AgentHealthObservation.State.HEALTHY, seen.health().state());

        world.advance(Duration.ofMinutes(30));
        assertFalse(world.observedState().latest(ref).orElseThrow().live(), "the lease expired: no longer live");
    }

    @Test
    void anotherOrganizationsRefReadsNothing() {
        Probed p = probed();
        report(p.session(), item(1, p.placement(), up(), STARTED));
        AgentPlacementRef foreign = new AgentPlacementRef(UUID.randomUUID(), p.agent().instanceId(), p.placement());
        assertEquals(Optional.empty(), world.observedState().latest(foreign));
    }

    @Test
    void noPlainDigestIsStoredInAnyTable() {
        Probed p = probed();
        report(p.session(), item(1, p.placement(), up(), STARTED));
        assertFalse(world.anyAgentRowContains(p.agent(), "ab".repeat(32)), "OR-32 / I6: the plain digest lives in memory only");
    }

    // ---- fix round: the observation is merged, never replaced -------------------------------------------------------------

    private static ReportItem periodic(long sequence, UUID placement) {
        // what the real agent's periodic report carries: no effective hash, no generation; health only when a probe ran
        return new ReportItem(id(), sequence, placement.toString(), "sm-" + placement, "c0ffee01", 0, null, null, null, null, null,
                null, null, null);
    }

    @Test
    void aPeriodicReportWithNoHashOrHealthDoesNotEraseWhatTheLastReportObserved() {
        Probed p = probed();
        AgentPlacementRef ref = new AgentPlacementRef(p.agent().tenantId(), p.agent().instanceId(), p.placement());
        report(p.session(), item(1, p.placement(), up(), STARTED));

        report(p.session(), periodic(2, p.placement()));

        AgentObservation seen = world.observedState().latest(ref).orElseThrow();
        assertEquals(PLAIN, seen.reportedSha256(), "the read-back waits for this hash within one report interval");
        assertEquals(7L, seen.persistedGeneration());
        assertEquals(STARTED, seen.startedAt());
        assertEquals(AgentHealthObservation.State.HEALTHY, seen.health().state(), "an op-finished report with no health keeps the last");
        // and a NEW value does replace the old one
        report(p.session(), new ReportItem(id(), 3, p.placement().toString(), "sm-" + p.placement(), "c0ffee01", 8, null,
                "sha256:" + "cd".repeat(32), null, null, null, null, down(), null));
        AgentObservation newer = world.observedState().latest(ref).orElseThrow();
        assertEquals("sha256:" + "cd".repeat(32), newer.reportedSha256());
        assertEquals(8L, newer.persistedGeneration());
        assertEquals(AgentHealthObservation.State.UNHEALTHY, newer.health().state());
    }

    @Test
    void anObservationOfAnOlderSequenceNeverOverwritesANewerOne() {
        Probed p = probed();
        AgentPlacementRef ref = new AgentPlacementRef(p.agent().tenantId(), p.agent().instanceId(), p.placement());
        report(p.session(), item(5, p.placement(), up(), STARTED));
        // two commits whose after-commit callbacks interleave: the older one runs last
        world.observedState().remember(p.agent().tenantId(), p.placement(), 4, null, "sha256:" + "ee".repeat(32), 1L, null,
                world.now());
        assertEquals(PLAIN, world.observedState().latest(ref).orElseThrow().reportedSha256());
    }

    @Test
    void aRolledBackReportLeavesNothingRemembered() {
        Probed p = probed();
        AgentPlacementRef ref = new AgentPlacementRef(p.agent().tenantId(), p.agent().instanceId(), p.placement());
        world.transactions().execute(status -> {
            report(p.session(), item(1, p.placement(), up(), STARTED));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(Optional.empty(), world.observedState().latest(ref), "remembered only after COMMIT");
        assertEquals(null, healthState(p.agent().tenantId(), p.placement()));
    }

    // ---- fix round: the item grammar and the start bound -------------------------------------------------------------------

    @Test
    void anItemIdOutsideItsGrammarAndABlankBindingAreRefusedByThemselvesAndTheBatchContinues() {
        Probed p = probed();
        ReportItem control = new ReportItem("bad\u0000id", 1, p.placement().toString(), "sm-" + p.placement(), "c0ffee01", 0,
                null, null, null, null, null, null, null, null);
        ReportItem tooLong = new ReportItem("x".repeat(65), 2, p.placement().toString(), "sm-" + p.placement(), "c0ffee01", 0,
                null, null, null, null, null, null, null, null);
        ReportItem blank = new ReportItem(id(), 3, "", "sm-" + p.placement(), "c0ffee01", 0, null, null, null, null, null, null,
                null, null);
        List<ReportResult> results = report(p.session(), control, tooLong, blank, item(4, p.placement(), up(), STARTED));
        for (int i = 0; i < 3; i++) {
            assertEquals(GatewayCode.LABEL_INVALID, results.get(i).refusedReason(), "item " + i);
            assertEquals("UNTRUSTED", results.get(i).trust());
        }
        assertEquals("APPLIED", results.get(3).disposition(), "one malformed item never poisons the report stream");
        assertEquals(3, events(p.agent().tenantId(), "agent.report.refused"),
                "each is audited, under a hash of its id: an agent-supplied id outside the grammar is never echoed into agent_event");
    }

    @Test
    void aStartLaterThanReceiptPlusTheClockWindowIsClockSuspectAndWritesNothing() {
        Probed p = probed();
        ReportItem future = item(1, p.placement(), up(), world.now().plus(Duration.ofMinutes(6)));
        ReportResult refused = report(p.session(), future).get(0);
        assertEquals(GatewayCode.CLOCK_SUSPECT, refused.refusedReason());
        assertEquals(0, world.ownerJdbc().queryForObject("SELECT count(*) FROM sm_data.placement_start_observation WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", p.agent().tenantId()), Long.class));
        assertEquals(null, healthState(p.agent().tenantId(), p.placement()), "the whole item is refused");
        // inside the window it is taken: the host's clock may run a little ahead
        assertEquals("APPLIED", report(p.session(), item(2, p.placement(), up(), world.now().plus(Duration.ofMinutes(4))))
                .get(0).disposition());
    }

    // ---- fix round: AGW-D28 item 1, an agent that stopped reporting is unreachable -----------------------------------------

    @Test
    void anAgentSilentPastTheWindowIsRecordedUnreachableOnceAndAFreshReportRestoresIt() {
        Probed p = probed();
        UUID tenant = p.agent().tenantId();
        report(p.session(), item(1, p.placement(), up(), STARTED));
        assertEquals("GREEN", healthState(tenant, p.placement()));

        world.advance(Duration.ofMinutes(4));
        world.staleness().markStale(tenant, p.agent().instanceId());
        assertEquals("GREEN", healthState(tenant, p.placement()), "inside the window nothing changes");

        world.advance(Duration.ofMinutes(2));
        world.staleness().markStale(tenant, p.agent().instanceId());
        assertEquals("unreachable", healthState(tenant, p.placement()));
        long version = healthVersion(tenant, p.placement());
        long notices = world.published().stream().filter(e -> e.event().equals("instance.health")).count();

        world.staleness().markStale(tenant, p.agent().instanceId());
        assertEquals(version, healthVersion(tenant, p.placement()), "written on change only");
        assertEquals(notices, world.published().stream().filter(e -> e.event().equals("instance.health")).count());

        // the lease (15 minutes) is still active, so the agent's next report is trusted and overwrites the verdict
        report(p.session(), item(2, p.placement(), up(), STARTED));
        assertEquals("GREEN", healthState(tenant, p.placement()));
    }

    /** W3 integration nit 8: the writer re-reads the watermark right before it records, so a report that landed meanwhile wins. */
    @Test
    void theStalenessWriterRechecksTheWatermarkAndSkipsAPlacementThatReportedSinceTheListing() {
        Probed p = probed();
        UUID tenant = p.agent().tenantId();
        report(p.session(), item(1, p.placement(), up(), STARTED));
        world.advance(Duration.ofMinutes(6));
        java.time.Instant cutoff = world.now().minus(Duration.ofMinutes(5));
        assertTrue(world.reportRepository().stillStale(tenant, p.placement(), cutoff), "silent past the window");
        report(p.session(), item(2, p.placement(), up(), STARTED));                        // a report lands after the listing
        assertFalse(world.reportRepository().stillStale(tenant, p.placement(), cutoff), "now it is not stale: do not overwrite");
        assertFalse(world.reportRepository().stillStale(tenant, UUID.randomUUID(), cutoff), "never reported is not stale");
    }

    @Test
    void theStalenessIsScopedByOrganizationInstanceAndProbeAndIgnoresAPlacementNeverReported() {
        Probed p = probed();
        AgentKitWorld.Agent neverReported = world.enrol();         // no probe, never reported
        world.ready(neverReported);
        report(p.session(), item(1, p.placement(), up(), STARTED));
        AgentKitWorld.Agent noProbe = world.enrol();
        AgentSessionRef session3 = world.ready(noProbe);
        report(session3, item(1, noProbe.placementId(), up(), STARTED));        // NOT_APPLICABLE
        world.advance(Duration.ofMinutes(10));

        world.staleness().markStale(UUID.randomUUID(), p.agent().instanceId());                 // another organization's id
        assertEquals("GREEN", healthState(p.agent().tenantId(), p.placement()), "bound by organization (I5)");
        world.staleness().markStale(noProbe.tenantId(), noProbe.instanceId());
        assertEquals("NOT_APPLICABLE", healthState(noProbe.tenantId(), noProbe.placementId()), "OR-101 stands");
        world.staleness().markStale(neverReported.tenantId(), neverReported.instanceId());
        assertEquals(null, healthState(neverReported.tenantId(), neverReported.placementId()), "never reported is not unreachable");
    }

    // ---- OR-140 (plan 015 T4.6): the verdict is a scheduled job, not a read -----------------------------------------------

    /** The instance.health notices for these placements only: earlier tests in this class leave other silent agents behind. */
    private long healthNotices(UUID... placements) {
        java.util.Set<String> wanted = java.util.Arrays.stream(placements).map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        return world.published().stream().filter(e -> e.event().equals("instance.health"))
                .filter(e -> wanted.contains(e.entity().placementId())).count();
    }

    @Test
    void theJobMarksEveryOrganizationsSilentAgentAndFiresInstanceHealthWithNoRead() {
        Probed a = probed();
        Probed b = probed();                  // another organization: the job enumerates, no caller names one
        report(a.session(), item(1, a.placement(), up(), STARTED));
        report(b.session(), item(1, b.placement(), up(), STARTED));
        assertEquals("GREEN", healthState(a.agent().tenantId(), a.placement()));
        world.advance(Duration.ofMinutes(6));
        long before = healthNotices(a.placement(), b.placement());
        world.stalenessJob().runOnce();                      // NO health read of any kind

        assertEquals("unreachable", healthState(a.agent().tenantId(), a.placement()));
        assertEquals("unreachable", healthState(b.agent().tenantId(), b.placement()));
        assertEquals(before + 2, healthNotices(a.placement(), b.placement()),
                "instance.health fired once per placement, for instances nobody read");

        long version = healthVersion(a.agent().tenantId(), a.placement());
        world.stalenessJob().runOnce();
        assertEquals(version, healthVersion(a.agent().tenantId(), a.placement()), "written on change only");
        assertEquals(before + 2, healthNotices(a.placement(), b.placement()), "a second pass fires nothing");
    }

    @Test
    void theJobLeavesAFreshAgentAnUnprobedPlacementAndANeverReportedOneAlone() {
        Probed fresh = probed();
        AgentKitWorld.Agent noProbe = world.enrol();
        AgentSessionRef session = world.ready(noProbe);
        AgentKitWorld.Agent neverReported = world.enrol();
        world.ready(neverReported);
        report(fresh.session(), item(1, fresh.placement(), up(), STARTED));
        report(session, item(1, noProbe.placementId(), up(), STARTED));
        world.advance(Duration.ofMinutes(4));
        report(fresh.session(), item(2, fresh.placement(), up(), STARTED));          // fresh again, inside the window
        world.advance(Duration.ofMinutes(2));                                         // noProbe is 6 minutes silent

        world.stalenessJob().runOnce();

        assertEquals("GREEN", healthState(fresh.agent().tenantId(), fresh.placement()));
        assertEquals("NOT_APPLICABLE", healthState(noProbe.tenantId(), noProbe.placementId()), "OR-101 stands");
        assertEquals(null, healthState(neverReported.tenantId(), neverReported.placementId()), "never reported is not unreachable");
    }

    @Test
    void theTenantListIsOnlyOrganizationsWithASilentAgentAndTheInstanceListIsBoundByTenant() {
        Probed silent = probed();
        Probed fresh = probed();
        report(silent.session(), item(1, silent.placement(), up(), STARTED));
        world.advance(Duration.ofMinutes(6));
        report(fresh.session(), item(1, fresh.placement(), up(), STARTED));
        java.time.Instant cutoff = world.now().minus(Duration.ofMinutes(5));

        List<UUID> tenants = world.reportRepository().tenantsWithStaleProbedPlacements(cutoff);

        assertTrue(tenants.contains(silent.agent().tenantId()));
        assertFalse(tenants.contains(fresh.agent().tenantId()), "an organization whose agents are all fresh is not enumerated");
        assertEquals(List.of(silent.agent().instanceId()),
                world.reportRepository().staleProbedInstances(silent.agent().tenantId(), cutoff), "bound by tenant");
        assertEquals(List.of(), world.reportRepository().staleProbedInstances(fresh.agent().tenantId(), cutoff));
    }
}
