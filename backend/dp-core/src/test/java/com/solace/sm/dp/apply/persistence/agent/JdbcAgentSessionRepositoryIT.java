package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T2.1b fix round, findings 1 and 7: the standing check and the write are ONE transaction holding the session and identity
 * rows {@code FOR SHARE}, so a {@code claim} (or a revocation) cannot land between "this epoch is current" and the write. Proved with
 * latches against the real database: the writer's transaction is held open inside the check, a competing claim is started, and the
 * claim must WAIT for it; afterwards the superseded stream's next write is refused.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentSessionRepositoryIT {

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    @Test
    void aClaimCannotLandBetweenTheEpochCheckAndTheWrite() throws Exception {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef old = world.ready(agent);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> world.authority().underSession(old, standing -> {
                inside.countDown();
                await(release);
                return world.runtimeJdbc().update("UPDATE sm_data.agent_session SET last_seen_at = :now WHERE tenant_id = :t"
                        + " AND agent_id = :a AND fencing_epoch = :e", new MapSqlParameterSource().addValue("now", OffsetDateTime.now())
                        .addValue("t", standing.tenantId()).addValue("a", old.agentId()).addValue("e", old.fencingEpoch()));
            }));
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            Future<AgentSessionRef> claimer = pool.submit(() -> world.open(agent));
            Thread.sleep(800);
            assertFalse(claimer.isDone(), "the claim must wait for the write's transaction: the check and the write are atomic");
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            AgentSessionRef newer = claimer.get(10, TimeUnit.SECONDS);
            assertEquals(old.fencingEpoch() + 1, newer.fencingEpoch());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        GatewayPortException refused = assertThrows(GatewayPortException.class,
                () -> world.commands().pendingFor(old, 10, Duration.ofSeconds(15)));
        assertEquals(GatewayCode.STALE_EPOCH, refused.code(), "and the superseded stream's NEXT write is refused");
    }

    @Test
    void aClaimIsAtomicWithARevocationThatLandsFirst() throws Exception {
        AgentKitWorld.Agent agent = world.enrol();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoker = pool.submit(() -> world.runtimeTx().executeWithoutResult(status -> {
                world.runtimeJdbc().update("UPDATE sm_data.agent_identity SET revoked_at = :now WHERE agent_id = :a",
                        new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("a", agent.agentId()));
                locked.countDown();
                await(commit);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<AgentSessionRef> claimer = pool.submit(() -> world.open(agent));
            Thread.sleep(800);
            assertFalse(claimer.isDone(), "the claim waits on the identity row the revocation holds");
            commit.countDown();
            revoker.get(10, TimeUnit.SECONDS);
            ExecutionException e = assertThrows(ExecutionException.class, () -> claimer.get(10, TimeUnit.SECONDS));
            assertEquals(GatewayCode.IDENTITY_REVOKED, ((GatewayPortException) e.getCause()).code());
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        assertEquals(0, world.rows("agent_session", agent), "no session was opened for a revoked identity");
    }

    @Test
    void anAckFromASessionSupersededAfterTheOfferIsRefusedAndChangesNothing() {
        // (The ack UPDATE also joins the session table on the epoch, as defence in depth; this test goes through the standing check.)
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef stale = world.ready(agent);
        var ref = world.commands().enqueue(world.intent(agent, UUID.randomUUID() + ":APPLY:r0", AgentOperation.APPLY));
        world.commands().pendingFor(stale, 10, Duration.ofSeconds(15));
        // the new owner bumps the epoch directly, as a claim would, then the old stream tries to write
        world.ownerJdbc().update("UPDATE sm_data.agent_session SET fencing_epoch = fencing_epoch + 1 WHERE agent_id = :a",
                new MapSqlParameterSource("a", agent.agentId()));
        assertThrows(GatewayPortException.class, () -> world.commands().ack(stale, ref.commandId(),
                new com.solace.sm.dp.api.agw.AgentAck("ack-late", ref.generation(),
                        com.solace.sm.dp.api.agw.AgentOutcome.APPLIED, "", "", null)));
        assertEquals("OFFERED", world.commandState(ref.commandId()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
