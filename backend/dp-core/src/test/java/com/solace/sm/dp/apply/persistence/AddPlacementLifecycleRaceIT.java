package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.apply.persistence.PlacementItSupport.Declared;
import com.solace.sm.dp.inventory.InventoryService;
import java.util.List;
import java.util.UUID;
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
 * Plan 008 S3 Step 3 / {@code BE-D47}: <b>the lock is the guard.</b> No database rule ties a placement insert to an
 * {@code active} instance, so what keeps a placement off an instance that has just left {@code active} is that
 * {@code addPlacement} takes the instance row's lock and re-checks the lifecycle UNDER it.
 *
 * <p>Today nothing moves an active instance out of {@code active} (V007's lifecycle trigger allows only {@code pending}
 * forward); plan 012's undeclare will. This IT simulates that writer as plan 008 specifies
 * ({@link PlacementItSupport.LifecycleWriter}): a test transaction on its own connection holds the row and flips the
 * lifecycle. It holds {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}, so that only an explicit lock request makes the add
 * wait (W2 review W2A-4): {@code FOR UPDATE} would also block the placement insert's own foreign-key {@code FOR KEY SHARE}
 * and the test could not tell the two apart.
 *
 * <p><b>What the mutations do (corrected by W2 review W2A-4; none has been run, Docker being down).</b> Removing the lock
 * from {@code InventoryService.lockedActive} (a plain read in its place) makes the FIRST test fail on the exception TYPE
 * only: the read sees the old {@code active} value, the insert goes through, and the stamp's own {@code lifecycle = 'active'}
 * predicate then refuses, as an {@code IllegalStateException} where {@code InstanceNotActiveException} is expected. The
 * lock is therefore the guard that makes the refusal CLEAN and decided before any write; the stamp's predicate is a
 * backstop behind it. The SECOND test stays green under that mutation (the add merely waits at the stamp), so it is a
 * liveness test, not a proof of the lock. Since {@code addPlacement} took {@code apply_scope} first (W2 review W2A-2), two
 * adds serialize on that row, so the third test no longer fails if the instance lock is {@code FOR SHARE}: it proves that
 * queued adds both land, deterministically (see its javadoc), and the {@code FOR NO KEY UPDATE} choice is what protects the
 * add against a writer that stamps {@code instance_state} without {@code apply_scope} (a generation mint, a health stamp).
 */
@Testcontainers(disabledWithoutDocker = true)
class AddPlacementLifecycleRaceIT {

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

    private static AddPlacementCommand add(Declared d) {
        return addOn(InventoryFixtures.target(d.tenant()));
    }

    private static AddPlacementCommand addOn(UUID target) {
        return new AddPlacementCommand(target,
                PlacementItSupport.freshSpec(PlacementItSupport.freshPort()),
                PlacementItSupport.ACTOR, PlacementItSupport.declaration(PlacementItSupport.freshPort()));
    }

    private static void assertStillWaiting(Future<?> add) throws Exception {
        assertThrows(TimeoutException.class, () -> add.get(750, TimeUnit.MILLISECONDS),
                "the add must be WAITING on the instance row, not finished: the lock is the guard");
    }

    @Test
    void addPlacementWaitsForAConcurrentLifecycleWriterAndRefusesIfTheInstanceLeftActive() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        AddPlacementCommand command = add(d);

        try (PlacementItSupport.LifecycleWriter writer = new PlacementItSupport.LifecycleWriter(d.tenant(), d.instance(), true)) {
            Future<InstanceView> add = pool.submit(() -> inventory.addPlacement(d.tenant(), d.instance(), command));
            assertStillWaiting(add);
            assertEquals(1, PlacementItSupport.livePlacements(d.tenant(), d.instance()),
                    "nothing was inserted while the writer held the row");

            writer.commit();

            ExecutionException refused = assertThrows(ExecutionException.class, () -> add.get(15, TimeUnit.SECONDS));
            assertInstanceOf(InstanceNotActiveException.class, refused.getCause(),
                    "it woke, re-read the lifecycle UNDER the lock, and found the instance gone");
        }

        assertEquals(1, PlacementItSupport.count("placement", d.tenant()),
                "no placement was written onto an instance that is no longer active");
    }

    @Test
    void addPlacementWaitsForAConcurrentWriterAndThenSucceedsWhenTheInstanceStaysActive() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        AddPlacementCommand command = add(d);

        try (PlacementItSupport.LifecycleWriter writer = new PlacementItSupport.LifecycleWriter(d.tenant(), d.instance(), false)) {
            Future<InstanceView> add = pool.submit(() -> inventory.addPlacement(d.tenant(), d.instance(), command));
            assertStillWaiting(add);

            writer.commit();

            assertEquals(2, add.get(15, TimeUnit.SECONDS).placements().size());
        }
    }

    /**
     * Two adds of one instance on ONE target, both held at the placement insert by a lock on the target's row (V016's
     * trigger asks for it {@code FOR UPDATE}), and released only after BOTH are observed waiting: the interleaving is
     * produced, not hoped for. The first add holds {@code apply_scope} and the instance row and waits on the target; the
     * second waits on {@code apply_scope}. Released, they queue and both land, with no deadlock across
     * {@code apply_scope}, the instance row and the target row (the lock order of {@code addPlacement} and
     * {@code retirePlacement}).
     */
    @Test
    void twoAddsRacingOnOneTargetQueueOnTheScopeAndBothLandOnceTheTargetIsReleased() throws Exception {
        Declared d = Declared.fresh("cache.hit_rate");
        UUID shared = InventoryFixtures.target(d.tenant());
        AddPlacementCommand first = addOn(shared);
        AddPlacementCommand second = addOn(shared);

        try (PlacementItSupport.TargetGate gate = new PlacementItSupport.TargetGate(d.tenant(), shared)) {
            Future<InstanceView> one = pool.submit(() -> inventory.addPlacement(d.tenant(), d.instance(), first));
            Future<InstanceView> two = pool.submit(() -> inventory.addPlacement(d.tenant(), d.instance(), second));
            PlacementItSupport.awaitLockWaiters(2);
            assertEquals(1, PlacementItSupport.livePlacements(d.tenant(), d.instance()), "neither add has committed");

            gate.release();
            for (Future<InstanceView> result : List.of(one, two)) {
                result.get(30, TimeUnit.SECONDS);   // a deadlock victim would surface here as an ExecutionException
            }
        }
        assertEquals(3, PlacementItSupport.livePlacements(d.tenant(), d.instance()));
        assertTrue(PlacementItSupport.stateVersion(d.tenant(), d.instance()) > 0);
    }
}
