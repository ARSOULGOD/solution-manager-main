package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.health.HealthView;
import com.solace.sm.dp.api.health.PlacementHealthView;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.health.HealthPoller;
import com.solace.sm.dp.health.HealthPollerProperties;
import com.solace.sm.dp.health.HealthProber;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.dp.health.HealthServiceImpl;
import com.solace.sm.dp.health.PlacementHealthRecord;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T7.2 (OR-39, OR-71, OR-82): the health poller against a real {@code sm_data} schema, as
 * {@code sm_dp_runtime}. The probes are scripted - there is no SSH server or HTTP target in this module - so what is
 * proven is the poller's own policy: which tenants it visits, which mode it follows, what it persists and when it
 * stamps, what it publishes and when, and how it backs off.
 *
 * <p>The database is shared by every IT in this JVM, so the poller visits other tests' tenants too; the scripted
 * probes answer for them harmlessly, and every assertion here names only its own tenant, instance and placements.
 */
@Testcontainers(disabledWithoutDocker = true)
class HealthPollerIT {

    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static InstanceRows instances;
    private static PlacementRows placements;
    private static TargetConnectionRows targets;
    private static PlacementHealthRows healthRows;
    private static TenantEnumerationRepository tenants;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        jdbc = new NamedParameterJdbcTemplate(SmDataPostgres.runtimeDataSource());
        tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        instances = new JdbcInstanceStateRepository(jdbc);
        placements = new JdbcPlacementRepository(jdbc);
        targets = new JdbcTargetConnectionRepository(jdbc);
        healthRows = new JdbcPlacementHealthRepository(jdbc);
        tenants = new TenantEnumerationRepository(jdbc);
    }

    // ---- scripted probes ----------------------------------------------------------------------------------

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** Over SSH: answers by the placement's container name; records what was asked. */
    private static final class ScriptedSsh implements HostProbe {
        final Map<String, Function<Integer, Object>> script = new HashMap<>();
        final List<String> asked = new ArrayList<>();           // container|port|path

        @Override
        public ProbeResult probe(SshDockerPlacement placement, int port, String path, Duration timeout) {
            synchronized (asked) {
                asked.add(placement.containerName() + "|" + port + "|" + path);
            }
            Object answer = script.getOrDefault(placement.containerName(), p -> up()).apply(port);
            if (answer instanceof RuntimeAdapterException e) {
                throw e;
            }
            return (ProbeResult) answer;
        }

        long askedAbout(String container) {
            synchronized (asked) {
                return asked.stream().filter(a -> a.startsWith(container + "|")).count();
            }
        }
    }

    private static ProbeResult up() {
        return new ProbeResult(0, 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static ProbeResult down() {
        return new ProbeResult(0, 503, "{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static ProbeResult refused() {
        return new ProbeResult(7, 0, new byte[0]);
    }

    private static RuntimeAdapterException sshFailure() {
        return new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));
    }

    /** Direct HTTP: records (host, port, path); answers by host. */
    private static final class ScriptedDirect implements DirectHttpProbe {
        final Map<String, ProbeResult> byHost = new HashMap<>();
        final List<String> asked = new ArrayList<>();           // host|port|path

        @Override
        public ProbeResult get(String host, int port, String path, Duration timeout) {
            synchronized (asked) {
                asked.add(host + "|" + port + "|" + path);
            }
            return byHost.getOrDefault(host, new ProbeResult(0, 200,
                    "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8)));
        }

        List<String> askedAbout(String host) {
            synchronized (asked) {
                return asked.stream().filter(a -> a.startsWith(host + "|")).toList();
            }
        }
    }

    private MutableClock clock;
    private ScriptedSsh ssh;
    private ScriptedDirect direct;
    private final List<EventEnvelope> published = new ArrayList<>();
    private final List<Long> visibleAtPublish = new ArrayList<>();
    private HealthPoller poller;
    private HealthService service;
    private HealthProber prober;
    private HealthRecorder recorder;
    private HealthPollerProperties props;

    @BeforeEach
    void harness() {
        clock = new MutableClock();
        ssh = new ScriptedSsh();
        direct = new ScriptedDirect();
        published.clear();
        visibleAtPublish.clear();
        EventPublisher capture = event -> {
            // Runs in the AFTER-COMMIT hook: the row must already be visible to a different connection
            Long seen = SmDataPostgres.superuserJdbc().queryForList(
                    "SELECT state_version FROM sm_data.placement_health WHERE tenant_id = :t AND placement_id = :p",
                    new MapSqlParameterSource().addValue("t", UUID.fromString(event.organization()))
                            .addValue("p", UUID.fromString(event.entity().placementId())), Long.class)
                    .stream().findFirst().orElse(-1L);
            synchronized (published) {
                published.add(event);
                visibleAtPublish.add(seen);
            }
        };
        EventPublisher afterCommit = new AfterCommitEventPublisher(capture, new SpringTransactionBoundary());
        ApplyEventEmitter emitter = new ApplyEventEmitter(afterCommit, clock, UUID::randomUUID);
        recorder = new HealthRecorder(tx, healthRows, emitter, clock);
        prober = new HealthProber(ssh, new TablePlacementCatalog(placements, targets, instances), targets, direct,
                TIMEOUT);
        props = new HealthPollerProperties(true, INTERVAL, TIMEOUT, 0.0,
                Duration.ofMinutes(5), 1);
        poller = new HealthPoller(tenants, instances, placements, healthRows, prober, recorder, props, clock,
                Runnable::run, () -> { });
        service = new HealthServiceImpl(placements, healthRows);
    }

    private List<EventEnvelope> eventsOf(HealthFixtures.Live f) {
        synchronized (published) {
            return published.stream().filter(e -> e.organization().equals(f.tenant().toString())).toList();
        }
    }

    private PlacementHealthRecord row(HealthFixtures.Live f) {
        return healthRows.find(f.tenant(), f.placement()).orElseThrow();
    }

    private String container(HealthFixtures.Live f) {
        return "sm-" + f.placement();
    }

    private HealthFixtures.Live viaSsh() {
        return HealthFixtures.livePlacement(jdbc, "via-ssh", "actuator-health", "/actuator/health", 8081);
    }

    // ---- change-only persistence ----------------------------------------------------------------------------

    @Test
    void anUnchangedStateUpdatesCheckedAtAndStampsNothing() {
        HealthFixtures.Live f = viaSsh();
        poller.pollOnce();
        PlacementHealthRecord first = row(f);

        clock.advance(INTERVAL);
        poller.pollOnce();

        PlacementHealthRecord second = row(f);
        assertEquals(HealthState.GREEN, second.state());
        assertEquals(first.stateVersion(), second.stateVersion(), "OR-39: only changes are persisted");
        assertEquals(first.changedAt(), second.changedAt());
        assertEquals(first.checkedAt().plus(INTERVAL), second.checkedAt(), "checked_at moves on every probe");
        assertEquals(1, eventsOf(f).size(), "no change, no event");
    }

    @Test
    void aChangedStateStampsAndPublishesOneInstanceHealthAfterCommit() {
        HealthFixtures.Live f = viaSsh();
        poller.pollOnce();                                              // GREEN
        PlacementHealthRecord green = row(f);
        assertEquals(1, eventsOf(f).size());

        ssh.script.put(container(f), p -> down());
        clock.advance(INTERVAL);
        poller.pollOnce();                                              // RED

        PlacementHealthRecord red = row(f);
        assertEquals(HealthState.RED, red.state());
        assertTrue(red.stateVersion() > green.stateVersion());
        List<EventEnvelope> events = eventsOf(f);
        assertEquals(2, events.size());
        EventEnvelope event = events.get(1);
        assertEquals("instance.health", event.event());
        assertEquals("dp-core", event.producer());
        assertEquals("placement", event.entity().kind());
        assertEquals(f.instance().toString(), event.entity().instanceId());
        assertEquals(f.placement().toString(), event.entity().placementId());
        assertEquals(Map.of("state", "RED"), event.payload(), "identifiers only (I6)");
        assertEquals(red.stateVersion(), event.stateVersion(), "the watermark is the row's own stamp");
        int at = eventsOf(f).indexOf(event);
        synchronized (published) {
            assertEquals(red.stateVersion(), visibleAtPublish.get(published.indexOf(event)),
                    "published AFTER commit: a second connection already sees the new row");
        }
        assertNotEquals(-1, at);
    }

    @Test
    void aBrokenPathReadsUnreachableNotRed() {
        HealthFixtures.Live f = viaSsh();
        ssh.script.put(container(f), p -> sshFailure());

        poller.pollOnce();

        assertEquals(HealthState.UNREACHABLE, row(f).state(), "OR-39: UNKNOWN, not DOWN (Review Focus 3)");
        assertEquals(Map.of("state", "unreachable"), eventsOf(f).get(0).payload());
    }

    @Test
    void aRefusedConnectionOnTheDeclaredPortReadsNotExposed() {
        HealthFixtures.Live f = viaSsh();
        ssh.script.put(container(f), p -> refused());

        poller.pollOnce();

        assertEquals(HealthState.NOT_EXPOSED, row(f).state());
    }

    @Test
    void aServiceDyingBetweenPollsIsSeenWithinIntervalPlusTimeout() {
        HealthFixtures.Live f = viaSsh();
        poller.pollOnce();
        Instant diedAt = clock.instant().plusSeconds(1);                 // dies just after a successful poll
        ssh.script.put(container(f), p -> down());

        clock.advance(INTERVAL);                                         // the next scheduled round
        poller.pollOnce();

        PlacementHealthRecord seen = row(f);
        assertEquals(HealthState.RED, seen.state());
        assertTrue(Duration.between(diedAt, seen.changedAt()).compareTo(INTERVAL.plus(TIMEOUT)) <= 0,
                "the honest bound (OR-39) is interval + timeout; there is no push from the MI (I1)");
    }

    // ---- a placement added to an existing instance (plan 008 S3, W2 review W2A-5) ---------------------------------

    /**
     * {@code addPlacement}'s contract: the new placement reads unpolled until the poller has visited it, and then, if its
     * container is not deployed yet (the customer's CI/CD deploys it; the operator only names it, {@code OR-29}), reads
     * {@code unreachable}, which is true, and the instance is listed for attention for that placement alone.
     */
    @Test
    void anUndeployedAddedPlacementReadsUnpolledThenUnreachableAndRaisesAttention() {
        PlacementItSupport.Declared d = PlacementItSupport.Declared.fresh("cache.hit_rate");
        com.solace.sm.dp.api.inventory.PlacementSpec spec = PlacementItSupport.freshSpec(PlacementItSupport.freshPort());
        // The new target has a host of its own, so the scripted direct probe can answer for it alone: the instance is
        // direct-http (Declared.fresh), the declared placement's host answers UP, and the new one's does not answer.
        String newHost = "vm-new-" + UUID.randomUUID().toString().substring(0, 8) + ".corp.example";
        UUID newTarget = HealthFixtures.target(jdbc, d.tenant(), newHost);
        com.solace.sm.dp.api.inventory.InstanceView view = PlacementItSupport.service().addPlacement(d.tenant(),
                d.instance(), new com.solace.sm.dp.api.inventory.AddPlacementCommand(newTarget, spec,
                        PlacementItSupport.ACTOR, PlacementItSupport.declaration(PlacementItSupport.freshPort())));
        UUID added = view.placements().get(1).placementId();
        com.solace.sm.dp.home.AttentionServiceImpl attention = new com.solace.sm.dp.home.AttentionServiceImpl(instances,
                healthRows, new JdbcApplyAttentionRepository(jdbc));

        HealthView before = service.health(d.tenant(), d.instance());
        assertEquals(0, before.placements().size());
        assertEquals(2, before.unpolledPlacements(), "both placements: nothing has polled either, and the count says so");
        assertTrue(attention.attention(d.tenant()).isEmpty(), "unpolled is declared, not invented, and is not attention");

        // the container is not deployed yet: nothing answers on the new host (direct-http: no fallback to SSH, OR-71)
        direct.byHost.put(newHost, new ProbeResult(DirectHttpProbe.EXIT_UNREACHABLE, 0, new byte[0]));
        poller.pollOnce();

        HealthView after = service.health(d.tenant(), d.instance());
        assertEquals(0, after.unpolledPlacements());
        assertEquals(HealthState.GREEN, after.placements().stream().filter(p -> p.placementId().equals(d.placement()))
                .findFirst().orElseThrow().state(), "the declared placement is untouched by the new one's state");
        assertEquals(HealthState.UNREACHABLE, after.placements().stream().filter(p -> p.placementId().equals(added))
                .findFirst().orElseThrow().state());
        assertEquals(List.of(added), attention.attention(d.tenant()).stream()
                .filter(c -> c.reason() == com.solace.sm.dp.api.home.AttentionReason.HEALTH
                        && c.state().equals(java.util.Optional.of(HealthState.UNREACHABLE)))
                .map(c -> c.placementId().orElseThrow()).toList(), "listed for the placement that is unreachable");
    }

    // ---- backoff --------------------------------------------------------------------------------------------

    @Test
    void backoffSpacesProbesToAFailingHostAndResetsOnSuccess() {
        HealthFixtures.Live f = viaSsh();
        ssh.script.put(container(f), p -> sshFailure());

        List<Long> asked = new ArrayList<>();
        for (int round = 0; round < 7; round++) {
            poller.pollOnce();
            asked.add(ssh.askedAbout(container(f)));
            clock.advance(INTERVAL);
        }
        // failure 1 -> wait 2 intervals; failure 2 -> 4 intervals; the host is NOT probed every round
        assertEquals(List.of(1L, 1L, 2L, 2L, 2L, 2L, 3L), asked,
                "probes at rounds 0, 2 and 6: exponentially spaced while the host keeps failing");
        assertEquals(HealthState.UNREACHABLE, row(f).state());

        // the host recovers: the first probe that gets through resets the spacing
        ssh.script.put(container(f), p -> up());
        clock.advance(Duration.ofMinutes(10));
        poller.pollOnce();
        assertEquals(HealthState.GREEN, row(f).state());
        long before = ssh.askedAbout(container(f));
        clock.advance(INTERVAL);
        poller.pollOnce();
        assertEquals(before + 1, ssh.askedAbout(container(f)), "reset: probed again on the very next round");
    }

    // ---- what is probed, and how ----------------------------------------------------------------------------

    @Test
    void theProbeUsesThePlacementsKindPathAndPort() {
        HealthFixtures.Live f = HealthFixtures.livePlacement(jdbc, "via-ssh", "http-status", "/ping", 9191);

        poller.pollOnce();

        assertEquals(List.of(container(f) + "|9191|/ping"), ssh.asked.stream()
                .filter(a -> a.startsWith(container(f))).toList());
        assertEquals(HealthState.GREEN, row(f).state(), "an http-status probe is green on a 2xx");
    }

    @Test
    void thePollerFollowsTheInstancesAcquisitionModeAndNeverFallsBack() {
        HealthFixtures.Live viaSsh = viaSsh();
        HealthFixtures.Live directHttp = HealthFixtures.livePlacement(jdbc, "direct-http", "actuator-health",
                "/actuator/health", 8081);
        String directHost = jdbc.queryForObject("SELECT host FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", new MapSqlParameterSource().addValue("t", directHttp.tenant())
                .addValue("x", directHttp.target()), String.class);
        // direct-http fails: it reads unreachable and is NOT retried over SSH; via-ssh is never probed directly
        direct.byHost.put(directHost, new ProbeResult(DirectHttpProbe.EXIT_UNREACHABLE, 0, new byte[0]));
        ssh.script.put(container(viaSsh), p -> down());

        poller.pollOnce();

        assertEquals(HealthState.UNREACHABLE, row(directHttp).state());
        assertEquals(0, ssh.askedAbout(container(directHttp)), "no automatic fallback to SSH (OR-71)");
        assertEquals(1, direct.askedAbout(directHost).size());
        assertEquals(HealthState.RED, row(viaSsh).state());
        String viaSshHost = jdbc.queryForObject("SELECT host FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", new MapSqlParameterSource().addValue("t", viaSsh.tenant())
                .addValue("x", viaSsh.target()), String.class);
        assertEquals(0, direct.askedAbout(viaSshHost).size(), "a via-ssh instance is never probed directly");
    }

    @Test
    void aDirectHttpInstanceIsProbedAtTheTargetsRegisteredAddress() {
        HealthFixtures.Live f = HealthFixtures.livePlacement(jdbc, "direct-http", "actuator-health",
                "/actuator/health", 8081);
        String host = jdbc.queryForObject("SELECT host FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", new MapSqlParameterSource().addValue("t", f.tenant())
                .addValue("x", f.target()), String.class);

        poller.pollOnce();

        assertEquals(List.of(host + "|8081|/actuator/health"), direct.askedAbout(host),
                "target_connection.host + the placement's health_port and health_path; nothing from a request");
        assertEquals(HealthState.GREEN, row(f).state());
    }

    @Test
    void aReTargetedDirectHttpInstanceIsProbedAtTheNewTargetsAddress() {
        HealthFixtures.Live f = HealthFixtures.livePlacement(jdbc, "direct-http", "actuator-health",
                "/actuator/health", 8081);
        poller.pollOnce();
        // re-target: the old placement is retired and a NEW placement on a NEW target is minted (OR-29)
        jdbc.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", f.tenant()).addValue("p", f.placement()));
        String newHost = "vm-new-" + UUID.randomUUID().toString().substring(0, 8) + ".corp.example";
        UUID newTarget = HealthFixtures.target(jdbc, f.tenant(), newHost);
        UUID newPlacement = HealthFixtures.placement(jdbc, f.tenant(), f.instance(), newTarget, "actuator-health",
                "/actuator/health", 8081);

        clock.advance(INTERVAL);
        poller.pollOnce();

        assertEquals(List.of(newHost + "|8081|/actuator/health"), direct.askedAbout(newHost),
                "no carried-forward address: the new target's host, read fresh");
        assertEquals(HealthState.GREEN, healthRows.find(f.tenant(), newPlacement).orElseThrow().state());
    }

    @Test
    void aPlacementWithNoProbeReadsNotApplicableAndIsNeverProbed() {
        // OR-101: a missing probe is a package property, not a placement fault - the contract's value, not not_exposed
        HealthFixtures.Live f = HealthFixtures.livePlacement(jdbc, "via-ssh", null, null, null);

        poller.pollOnce();
        PlacementHealthRecord first = row(f);
        clock.advance(INTERVAL);
        poller.pollOnce();

        assertEquals(HealthState.NOT_APPLICABLE, first.state());
        assertEquals(0, ssh.askedAbout(container(f)));
        assertEquals(first, row(f), "recorded once: not even checked_at moves (there is nothing to check)");
        assertEquals(1, eventsOf(f).size());
        assertEquals(Map.of("state", "NOT_APPLICABLE"), eventsOf(f).get(0).payload());
        HealthView served = service.health(f.tenant(), f.instance());
        assertEquals(List.of(HealthState.NOT_APPLICABLE), served.placements().stream().map(PlacementHealthView::state).toList(),
                "getHealth serves the persisted value");
    }

    @Test
    void aNoProbePlacementRecordedNotExposedBeforeOr101IsCorrectedOnTheNextRound() {
        // rows written under T7.2's original text carry not_exposed; the poller must not leave them reading as a fault
        HealthFixtures.Live f = HealthFixtures.livePlacement(jdbc, "via-ssh", null, null, null);
        healthRows.record(f.tenant(), f.instance(), f.placement(), HealthState.NOT_EXPOSED, clock.instant());

        poller.pollOnce();

        assertEquals(HealthState.NOT_APPLICABLE, row(f).state());
        assertEquals(0, ssh.askedAbout(container(f)));
    }

    // ---- who is visited -------------------------------------------------------------------------------------

    @Test
    void anIdleTenantIsPolled() {
        // B4: a tenant with no apply of any kind, ever, is still polled - it is on the second tenant list
        HealthFixtures.Live f = viaSsh();
        assertEquals(0, jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t",
                new MapSqlParameterSource("t", f.tenant()), Integer.class));

        poller.pollOnce();

        assertEquals(HealthState.GREEN, row(f).state());
    }

    @Test
    void aPendingOrAbandonedInstanceIsNotPolled() {
        HealthFixtures.Live f = viaSsh();
        UUID tenant = f.tenant();
        UUID pending = HealthFixtures.instance(jdbc, tenant, "via-ssh", "pending");
        UUID abandoned = HealthFixtures.instance(jdbc, tenant, "via-ssh", "pending");
        jdbc.update("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", abandoned));
        UUID p1 = HealthFixtures.placement(jdbc, tenant, pending,
                HealthFixtures.target(jdbc, tenant, "vm-x1.corp.example"), "actuator-health", "/h", 8081);
        UUID p2 = HealthFixtures.placement(jdbc, tenant, abandoned,
                HealthFixtures.target(jdbc, tenant, "vm-x2.corp.example"), "actuator-health", "/h", 8081);

        poller.pollOnce();

        assertTrue(healthRows.find(tenant, p1).isEmpty(), "pending: not declared yet");
        assertTrue(healthRows.find(tenant, p2).isEmpty(), "abandoned: never completed");
        assertEquals(HealthState.GREEN, row(f).state(), "the active one beside them is polled");
    }

    @Test
    void thePollerReadsNoTenantOutsideTheTwoRuledLists() throws Exception {
        // The only tenant source is OR-49's list, called by name; the poller holds no SQL of its own (JDBC is confined
        // to the persistence packages by an architecture rule), so there is no third cross-tenant read to find.
        String constants = constantPoolOf(HealthPoller.class);
        assertTrue(constants.contains("tenantsWithActiveInstances"), "the OR-49 list");
        assertFalse(constants.contains("tenantsWithNonTerminalWork"), "OR-44's list is the apply worker's");
        assertFalse(constants.contains("DISTINCT"), "no SQL in the poller");
        assertFalse(constants.contains("tenant_id"), "no SQL in the poller");
    }

    // ---- getHealth ------------------------------------------------------------------------------------------

    @Test
    void getHealthReturnsPerPlacementItemsEachWithItsOwnStateVersion() {
        HealthFixtures.Live a = viaSsh();
        UUID second = HealthFixtures.placement(jdbc, a.tenant(), a.instance(),
                HealthFixtures.target(jdbc, a.tenant(), "vm-second.corp.example"), "actuator-health",
                "/actuator/health", 8081);
        ssh.script.put("sm-" + second, p -> down());

        poller.pollOnce();
        HealthView view = service.health(a.tenant(), a.instance());

        assertEquals(2, view.placements().size());
        assertEquals(0, view.unpolledPlacements());
        PlacementHealthView first = view.placements().stream().filter(p -> p.placementId().equals(a.placement()))
                .findFirst().orElseThrow();
        PlacementHealthView other = view.placements().stream().filter(p -> p.placementId().equals(second))
                .findFirst().orElseThrow();
        assertEquals(HealthState.GREEN, first.state());
        assertEquals(HealthState.RED, other.state());
        assertNotEquals(first.stateVersion(), other.stateVersion(), "OR-55: a version per item, never one for the set");
        assertEquals(row(a).stateVersion(), first.stateVersion());
        assertEquals(clock.instant(), first.checkedAt());
    }

    @Test
    void getHealthSaysSoWhenAPlacementHasNotBeenPolledAndHidesARetiredOne() {
        HealthFixtures.Live f = viaSsh();
        HealthView before = service.health(f.tenant(), f.instance());
        assertEquals(0, before.placements().size());
        assertEquals(1, before.unpolledPlacements(), "a degraded surface is declared, never silently empty");

        poller.pollOnce();
        jdbc.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", f.tenant()).addValue("p", f.placement()));
        HealthView after = service.health(f.tenant(), f.instance());
        assertTrue(after.placements().isEmpty(), "a retired placement's health is not the instance's");
        assertEquals(0, after.unpolledPlacements());
    }

    @Test
    void getHealthIsScopedToTheCallersOrganization() {
        HealthFixtures.Live f = viaSsh();
        poller.pollOnce();

        HealthView other = service.health(UUID.randomUUID(), f.instance());

        assertTrue(other.placements().isEmpty(), "I5: another organization reads nothing of this instance");
        assertEquals(0, other.unpolledPlacements());
    }

    /**
     * Plan 015 T3.1 (HF19): a placement on an AGENT target is never probed and never written here. The agent reports its own
     * health and the data plane maps it (T3.3); a second writer would race it, and SSH to an agent target has nothing to
     * connect to. The control is a dp-remote placement of another tenant, polled in the same round.
     */
    @Test
    void skipsAgentPlacements() {
        HealthFixtures.Live control = viaSsh();
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = HealthFixtures.activeInstance(jdbc, tenant, "via-ssh");
        UUID target = InventoryFixtures.agentTarget(jdbc, tenant, UUID.randomUUID());
        UUID placement = HealthFixtures.placement(jdbc, tenant, instance, target, "actuator-health", "/actuator/health", 8081);
        HealthPoller withTransports = new HealthPoller(tenants, instances, placements, healthRows, prober, recorder, props, clock,
                Runnable::run, () -> { }, null,
                new com.solace.sm.dp.apply.persistence.agent.JdbcAgentPlacementCatalog(jdbc));

        withTransports.pollOnce();

        assertTrue(healthRows.find(tenant, placement).isEmpty(), "nothing writes an agent placement's health row (without the skip an"
                + " UNREACHABLE row is recorded: SSH has nothing to connect to)");
        synchronized (published) {
            assertTrue(published.stream().noneMatch(e -> e.organization().equals(tenant.toString())),
                    "…and no instance.health notice is published for it");
        }
        assertEquals(1, ssh.askedAbout(container(control)), "a dp-remote placement is still polled in the same round");
        assertEquals(HealthState.GREEN, row(control).state());
    }

    private static String constantPoolOf(Class<?> type) throws java.io.IOException {
        try (java.io.InputStream in = type.getClassLoader()
                .getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
