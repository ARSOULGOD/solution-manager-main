package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.apply.persistence.PlacementItSupport.Declared;
import com.solace.sm.dp.inventory.InventoryService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Review {@code W4C-2} (plan 013 T1.5): a target is never retired under a live placement, and a placement is never added
 * to a retired target, however the two requests interleave.
 *
 * <p><b>Why it needed a fix.</b> V016's {@code BEFORE INSERT} trigger on {@code placement} locks the target row
 * {@code FOR UPDATE}, but only AFTER V007's envelope trigger has read {@code retired_at} without a lock. So a retirement already
 * in flight made the placement WAIT and then proceed without looking again: the retirement committed (V007's guard having seen
 * no committed placement), the placement's trigger then got its lock, and the placement landed on a retired target.
 * (Reproduced on PostgreSQL 16 with two {@code psql} sessions replaying the original statements: {@code retired = true, live
 * placements = 1}.) The fix that closes it is on the placement side: {@code addPlacement} and {@code reserve} hold the record
 * live ({@code holdLive}: {@code FOR UPDATE}, re-read of {@code retired_at}) before the insert. Replaying the retirement as a
 * plain {@code UPDATE} with that in place is safe in both directions (measured), so {@code retireConnection}'s own
 * {@code FOR UPDATE} is defence in depth; it is proven by the unit test's lock-order assertion, and the first test below does
 * NOT depend on it (it replays the retirement's statements itself). Not {@code FOR KEY SHARE} for {@code holdLive}: that
 * deadlocks two placements on one target against V016's {@code FOR UPDATE} (also reproduced on PostgreSQL 16).
 *
 * <p><b>Each direction is produced, not hoped for.</b> The first two tests hold one side open on its own connection
 * (statements the service issues, replayed on a superuser connection) and release it only after the other is observed
 * waiting on a lock. The third releases the two real operations together, many times, and asserts the invariant and that
 * exactly one won.
 *
 * <p><b>Written, NOT YET RUN on the {@code postgres:14} container</b> (Docker was down the night it was written). The
 * statements these tests replay, and the two outcomes, were run in {@code psql} against a PostgreSQL 16 built from the
 * repository's own migrations (indicative; see plan 013 T1.5's Results).
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetRetirePlacementRaceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "198.51.100.7");

    private static InventoryService inventory;
    private static ExecutorService pool;

    @BeforeAll
    static void wire() {
        inventory = PlacementItSupport.service();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void close() {
        pool.shutdownNow();
    }

    private static AddPlacementCommand addOn(UUID target) {
        return new AddPlacementCommand(target, PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                PlacementItSupport.ACTOR, PlacementItSupport.declaration(PlacementItSupport.freshPort()));
    }

    private static void assertStillWaiting(Future<?> operation, String why) {
        assertThrows(TimeoutException.class, () -> operation.get(750, TimeUnit.MILLISECONDS), why);
    }

    private static boolean retired(UUID tenant, UUID target) throws SQLException {
        try (Connection c = SmDataPostgres.superuser().getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT retired_at IS NOT NULL FROM sm_data.target_connection"
                        + " WHERE tenant_id = '" + tenant + "' AND target_id = '" + target + "'")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static int livePlacementsOn(UUID tenant, UUID target) throws SQLException {
        try (Connection c = SmDataPostgres.superuser().getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM sm_data.placement WHERE tenant_id = '" + tenant
                        + "' AND target_id = '" + target + "' AND retired_at IS NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A retirement in flight (the service's two statements, uncommitted): the add must wait for it and then refuse. */
    @Test
    void anAddThatMeetsARetirementInFlightWaitsForItAndIsRefusedBecauseTheTargetIsGone() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID spare = InventoryFixtures.target(d.tenant());          // a live target with no placement: retirable

        try (Connection retirement = SmDataPostgres.superuser().getConnection()) {
            retirement.setAutoCommit(false);
            try (Statement s = retirement.createStatement()) {
                s.execute("SELECT 1 FROM sm_data.target_connection WHERE tenant_id = '" + d.tenant() + "' AND target_id = '"
                        + spare + "' FOR UPDATE");
                s.executeUpdate("UPDATE sm_data.target_connection SET retired_at = now() WHERE tenant_id = '" + d.tenant()
                        + "' AND target_id = '" + spare + "'");
            }
            AddPlacementCommand command = addOn(spare);
            Future<?> add = pool.submit(() -> inventory.addPlacement(d.tenant(), d.instance(), command));
            PlacementItSupport.awaitLockWaiters(1);
            assertStillWaiting(add, "the add must be WAITING for the retirement's row lock, not finished");

            retirement.commit();

            ExecutionException refused = assertThrows(ExecutionException.class, () -> add.get(15, TimeUnit.SECONDS));
            assertInstanceOf(NoSuchElementException.class, refused.getCause(),
                    "it woke, re-read the record under the lock, and found the target retired");
        }
        assertTrue(retired(d.tenant(), spare));
        assertEquals(0, livePlacementsOn(d.tenant(), spare), "no placement was written onto a retired target");
    }

    /** An add in flight (the placement writer's lock and insert, uncommitted): the retirement must wait and then refuse. */
    @Test
    void aRetirementThatMeetsAnAddInFlightWaitsForItAndIsRefusedBecauseThePlacementIsLive() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID spare = InventoryFixtures.target(d.tenant());

        try (Connection add = SmDataPostgres.superuser().getConnection()) {
            add.setAutoCommit(false);
            UUID placement = UUID.randomUUID();
            try (Statement s = add.createStatement()) {
                s.execute("SELECT 1 FROM sm_data.target_connection WHERE tenant_id = '" + d.tenant() + "' AND target_id = '"
                        + spare + "' AND retired_at IS NULL FOR UPDATE");
                s.executeUpdate("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id,"
                        + " container_name, remote_config_directory, container_config_path, file_name, mechanism,"
                        + " retained_generations, created_at) VALUES ('" + d.tenant() + "', '" + placement + "', '"
                        + d.instance() + "', '" + spare + "', 'sm-" + placement + "', '/srv/sm/" + placement
                        + "', '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, now())");
            }
            Future<ConnectionRetirement> retire = pool
                    .submit(() -> inventory.retireConnection(d.tenant(), spare, ACTOR, ORIGIN));
            PlacementItSupport.awaitLockWaiters(1);
            assertStillWaiting(retire, "the retirement must be WAITING for the add's row lock, not finished");

            add.commit();

            ExecutionException refused = assertThrows(ExecutionException.class, () -> retire.get(15, TimeUnit.SECONDS));
            assertInstanceOf(TargetHasLivePlacementsException.class, refused.getCause(),
                    "it woke, and V007's guard now sees the committed placement");
        }
        assertEquals(false, retired(d.tenant(), spare));
        assertEquals(1, livePlacementsOn(d.tenant(), spare));
    }

    /** The real operations, released together, many times: exactly one wins every round and the two never both land. */
    @Test
    void retireAndAddReleasedTogetherNeverLeaveARetiredTargetWithALivePlacement() throws Exception {
        for (int round = 0; round < 25; round++) {
            Declared d = Declared.fresh("cache.hit_rate");
            UUID spare = InventoryFixtures.target(d.tenant());
            AddPlacementCommand command = addOn(spare);
            CyclicBarrier together = new CyclicBarrier(2);

            Future<Boolean> add = pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                try {
                    inventory.addPlacement(d.tenant(), d.instance(), command);
                    return true;
                } catch (NoSuchElementException retiredFirst) {
                    return false;
                }
            });
            Future<Boolean> retire = pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                try {
                    return inventory.retireConnection(d.tenant(), spare, ACTOR, ORIGIN) == ConnectionRetirement.RETIRED;
                } catch (TargetHasLivePlacementsException placedFirst) {
                    return false;
                }
            });

            boolean added = add.get(30, TimeUnit.SECONDS);
            boolean retiredNow = retire.get(30, TimeUnit.SECONDS);

            assertTrue(added ^ retiredNow, "round " + round + ": exactly one wins (added=" + added + ", retired="
                    + retiredNow + ")");
            assertEquals(retiredNow, retired(d.tenant(), spare), "round " + round);
            assertEquals(added ? 1 : 0, livePlacementsOn(d.tenant(), spare), "round " + round);
        }
    }
}
