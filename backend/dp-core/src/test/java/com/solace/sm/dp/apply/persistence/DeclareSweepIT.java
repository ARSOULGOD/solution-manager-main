package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceNameTakenException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T4.2 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the DP's half of the declare sweep
 * ({@code pending} and {@code settlePending}, BE-D32). (In the persistence test package, like
 * {@code InventoryServiceIT}: the Testcontainers harness {@code SmDataPostgres} is package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class DeclareSweepIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final AtomicInteger PORTS = new AtomicInteger(30000);

    private static NamedParameterJdbcTemplate superuser;
    private static InventoryService service;
    private static JdbcInstanceStateRepository rows;
    private static final InventoryServiceIT.SkewClock CLOCK = new InventoryServiceIT.SkewClock();

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        rows = new JdbcInstanceStateRepository(jdbc);
        service = new InventoryService(tx, rows, new JdbcPlacementRepository(jdbc),
                new JdbcTargetConnectionRepository(jdbc),
                SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 22), null)),
                CLOCK);
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static UUID registerTarget(UUID tenant) {
        UUID target = UUID.randomUUID();
        String fp = "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        service.register(tenant, target,
                new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", fp, "/srv/sm", "sm-"),
                SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair()).chars(), ACTOR);
        return target;
    }

    private static PlacementSpec freshSpec() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return new PlacementSpec("sm-" + u, "/srv/sm/" + u, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", PORTS.incrementAndGet(),
                        ProbeStrength.READINESS));
    }

    private static InstanceView reserve(UUID tenant, UUID target, PlacementSpec placement) {
        return reserveNamed(tenant, target, placement, UUID.randomUUID(),
                "inst-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static InstanceView reserveNamed(UUID tenant, UUID target, PlacementSpec placement, UUID environment,
            String name) {
        return service.reserve(tenant, new DeclareInstanceCommand(environment, UUID.randomUUID(), "cache", "2.4",
                name, target, AcquisitionMode.DIRECT_HTTP, placement, ACTOR));
    }

    private static String lifecycle(UUID tenant, UUID instance) {
        return superuser.queryForObject(
                "SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), String.class);
    }

    private static long stateVersion(UUID tenant, UUID instance) {
        Long v = superuser.queryForObject(
                "SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Long.class);
        return v == null ? -1 : v;
    }

    private static long livePlacements(UUID tenant, UUID instance) {
        Long n = superuser.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                        + " AND instance_id = :i AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Long.class);
        return n == null ? 0 : n;
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void anUnregisteredPendingRowOlderThanTheWindowIsAbandonedAndItsPlacementsRetired() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        PlacementSpec held = freshSpec();
        InstanceView stale = reserve(tenant, target, held);
        CLOCK.advance(WINDOW.plusSeconds(1));

        service.settlePending(tenant, Set.of(), WINDOW);

        assertEquals("abandoned", lifecycle(tenant, stale.instanceId()));
        assertEquals(0, livePlacements(tenant, stale.instanceId()), "an abandoned declare must free its names");
        assertEquals(1, reserve(tenant, target, held).placements().size(), "the container name is reusable at once");
    }

    /** OR-88: the sweep's abandon frees the instance NAME as well as the placement's container. */
    @Test
    void aRetriedDeclareUnderAnAbandonedNameSucceedsWhileTwoLiveInstancesStillCollide() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        UUID environment = UUID.randomUUID();
        InstanceView stale = reserveNamed(tenant, target, freshSpec(), environment, "orders-cache");
        CLOCK.advance(WINDOW.plusSeconds(1));
        service.settlePending(tenant, Set.of(), WINDOW);
        assertEquals("abandoned", lifecycle(tenant, stale.instanceId()));

        InstanceView retried = reserveNamed(tenant, target, freshSpec(), environment, "orders-cache");

        assertEquals("pending", lifecycle(tenant, retried.instanceId()), "the retry holds the name now");
        assertEquals("abandoned", lifecycle(tenant, stale.instanceId()), "the abandoned row is kept, never deleted");
        assertThrows(InstanceNameTakenException.class,
                () -> reserveNamed(tenant, target, freshSpec(), environment, "orders-cache"),
                "two live instances with one name in one environment still collide");
        service.activate(tenant, retried.instanceId());
        assertThrows(InstanceNameTakenException.class,
                () -> reserveNamed(tenant, target, freshSpec(), environment, "orders-cache"),
                "an active instance holds the name too");
    }

    @Test
    void aRegisteredPendingRowIsActivatedAtAnyAge() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView old = reserve(tenant, target, freshSpec());
        CLOCK.advance(WINDOW.multipliedBy(5));
        InstanceView fresh = reserve(tenant, target, freshSpec());

        service.settlePending(tenant, Set.of(old.instanceId(), fresh.instanceId()), WINDOW);

        assertEquals("active", lifecycle(tenant, old.instanceId()), "registered and ancient: activated, not abandoned");
        assertEquals("active", lifecycle(tenant, fresh.instanceId()), "registered and fresh: activated at once");
        assertEquals(1, livePlacements(tenant, old.instanceId()));
    }

    @Test
    void aFreshUnregisteredPendingRowIsLeftAlone() {
        UUID tenant = SmDataPostgres.freshTenant();
        InstanceView inFlight = reserve(tenant, registerTarget(tenant), freshSpec());
        long before = stateVersion(tenant, inFlight.instanceId());
        CLOCK.advance(WINDOW.minusSeconds(30));

        service.settlePending(tenant, Set.of(), WINDOW);

        assertEquals("pending", lifecycle(tenant, inFlight.instanceId()));
        assertEquals(before, stateVersion(tenant, inFlight.instanceId()), "left alone means not written");
        assertEquals(1, livePlacements(tenant, inFlight.instanceId()));
    }

    @Test
    void everySweepStatementBindsTheTenant() {
        UUID a = SmDataPostgres.freshTenant();
        UUID b = SmDataPostgres.freshTenant();
        InstanceView ofB = reserve(b, registerTarget(b), freshSpec());
        InstanceView staleOfB = reserve(b, registerTarget(b), freshSpec());
        CLOCK.advance(WINDOW.plusSeconds(1));
        long before = stateVersion(b, staleOfB.instanceId());

        // A's settle names B's ids as "registered", and B's stale row is past the window: neither may reach B.
        assertEquals(List.of(), service.pending(a, 100), "A's list does not contain B's rows");
        service.settlePending(a, Set.of(ofB.instanceId()), WINDOW);

        assertEquals("pending", lifecycle(b, ofB.instanceId()), "A cannot activate B's row");
        assertEquals("pending", lifecycle(b, staleOfB.instanceId()), "A cannot abandon B's stale row");
        assertEquals(before, stateVersion(b, staleOfB.instanceId()));
        assertEquals(1, livePlacements(b, staleOfB.instanceId()));
        assertTrue(Set.copyOf(service.pending(b, 100)).containsAll(
                Set.of(ofB.instanceId(), staleOfB.instanceId())), "B's own list has them");
    }

    @Test
    void theRepositoryQueriesNeverReturnAnotherTenantsRows() {
        // The service's UPDATE is tenant-bound too, so an unbound SELECT would still be harmless to B's data; it would
        // starve A's bounded batch with B's ids. The repository is therefore asserted directly.
        UUID a = SmDataPostgres.freshTenant();
        UUID b = SmDataPostgres.freshTenant();
        InstanceView ofB = reserve(b, registerTarget(b), freshSpec());
        CLOCK.advance(WINDOW.plusSeconds(1));

        assertEquals(List.of(), rows.pendingCreatedBefore(a, CLOCK.instant(), 100));
        assertEquals(List.of(), rows.pendingIds(a, 100));
        assertEquals(List.of(ofB.instanceId()), rows.pendingCreatedBefore(b, CLOCK.instant(), 100));
        assertEquals(0, rows.activate(a, ofB.instanceId()));
        assertEquals(0, rows.abandon(a, ofB.instanceId()));
        assertEquals("pending", lifecycle(b, ofB.instanceId()));
    }

    @Test
    void settleIsIdempotent() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView toActivate = reserve(tenant, target, freshSpec());
        InstanceView toAbandon = reserve(tenant, target, freshSpec());
        CLOCK.advance(WINDOW.plusSeconds(1));

        service.settlePending(tenant, Set.of(toActivate.instanceId()), WINDOW);
        long activated = stateVersion(tenant, toActivate.instanceId());
        long abandoned = stateVersion(tenant, toAbandon.instanceId());
        service.settlePending(tenant, Set.of(toActivate.instanceId()), WINDOW);

        assertEquals("active", lifecycle(tenant, toActivate.instanceId()));
        assertEquals("abandoned", lifecycle(tenant, toAbandon.instanceId()));
        assertEquals(activated, stateVersion(tenant, toActivate.instanceId()), "a repeat settle writes nothing");
        assertEquals(abandoned, stateVersion(tenant, toAbandon.instanceId()));
    }

    @Test
    void pendingListsOnlyThisTenantsPendingRowsOldestFirstAndBoundedByTheLimit() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView first = reserve(tenant, target, freshSpec());
        CLOCK.advance(Duration.ofSeconds(1));
        InstanceView second = reserve(tenant, target, freshSpec());
        CLOCK.advance(Duration.ofSeconds(1));
        InstanceView third = reserve(tenant, target, freshSpec());
        InstanceView activated = reserve(tenant, target, freshSpec());
        service.activate(tenant, activated.instanceId());
        InstanceView gone = reserve(tenant, target, freshSpec());
        service.abandon(tenant, gone.instanceId());

        assertEquals(List.of(first.instanceId(), second.instanceId(), third.instanceId()), service.pending(tenant, 100),
                "pending only, oldest first; not the active or the abandoned row");
        assertEquals(List.of(first.instanceId(), second.instanceId()), service.pending(tenant, 2));
        assertThrows(IllegalArgumentException.class, () -> service.pending(tenant, 0));
    }

    @Test
    void aRegisteredIdThatIsNoLongerPendingIsIgnoredNotFatal() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView gone = reserve(tenant, target, freshSpec());
        service.abandon(tenant, gone.instanceId());
        InstanceView live = reserve(tenant, target, freshSpec());
        service.activate(tenant, live.instanceId());
        InstanceView pending = reserve(tenant, target, freshSpec());
        long abandonedVersion = stateVersion(tenant, gone.instanceId());
        long liveVersion = stateVersion(tenant, live.instanceId());

        Set<UUID> registered = new HashSet<>(List.of(gone.instanceId(), live.instanceId(), pending.instanceId(),
                UUID.randomUUID()));
        service.settlePending(tenant, registered, WINDOW);

        assertEquals("abandoned", lifecycle(tenant, gone.instanceId()), "an abandoned row is not resurrected");
        assertEquals(abandonedVersion, stateVersion(tenant, gone.instanceId()));
        assertEquals(liveVersion, stateVersion(tenant, live.instanceId()));
        assertEquals("active", lifecycle(tenant, pending.instanceId()), "the pending one in the set is still activated");
        assertThrows(IllegalArgumentException.class, () -> service.settlePending(tenant, Set.of(), Duration.ZERO));
    }

    @Test
    void aSettleThatFailsMidwayLeavesNeitherHalf() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView toActivate = reserve(tenant, target, freshSpec());
        InstanceView toAbandon = reserve(tenant, target, freshSpec());
        CLOCK.advance(WINDOW.plusSeconds(1));
        // a null element makes the set unusable after the first statement could already have run
        Set<UUID> poisoned = new java.util.AbstractSet<>() {
            @Override public java.util.Iterator<UUID> iterator() {
                return List.of(toActivate.instanceId()).iterator();
            }

            @Override public int size() {
                return 1;
            }

            @Override public boolean contains(Object o) {
                throw new IllegalStateException("boom");
            }
        };

        assertThrows(IllegalStateException.class, () -> service.settlePending(tenant, poisoned, WINDOW));

        assertEquals("pending", lifecycle(tenant, toActivate.instanceId()), "the activation rolled back");
        assertEquals("pending", lifecycle(tenant, toAbandon.instanceId()));
        assertFalse(service.pending(tenant, 10).isEmpty());
        assertEquals(Optional.empty(), service.findActive(tenant, toActivate.instanceId()));
    }
}
