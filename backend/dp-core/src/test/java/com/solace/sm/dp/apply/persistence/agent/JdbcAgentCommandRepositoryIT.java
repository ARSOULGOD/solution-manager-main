package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What only the JDBC repository can show about {@code agent_command} (plan 015 T2.1b): the cases that need the database's own
 * concurrency or physical size. The port-level cases of the same plan step ({@code pullStoresNothing},
 * {@code enqueueReturnsTheLiveCommandForTheSameIntentKey}, {@code enqueueReturnsTheSucceededCommandRatherThanMintingAnother},
 * {@code aRetryAfterAFailedAckMintsANewGeneration}, {@code supersedeLeavesDeliveredCommandsAlone}) live in
 * {@code AbstractAgentCommandPortContractTest} under those names, so they bind every implementation, and run here through
 * {@link JdbcAgentCommandPortContractIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentCommandRepositoryIT {

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    @Test
    void eightConcurrentEnqueuesOfOneIntentMintExactlyOneCommand() throws Exception {
        AgentKitWorld.Agent agent = world.enrol();
        AgentCommandIntent intent = world.intent(agent, UUID.randomUUID() + ":APPLY:r0", AgentOperation.APPLY);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AgentCommandRef>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<AgentCommandRef> call = () -> world.commands().enqueue(intent);
                futures.add(pool.submit(call));
            }
            List<AgentCommandRef> refs = new ArrayList<>();
            for (Future<AgentCommandRef> f : futures) {
                refs.add(f.get());
            }
            assertEquals(1, refs.stream().map(AgentCommandRef::commandId).distinct().count(), "one command, however many callers");
            assertEquals(1, refs.stream().filter(AgentCommandRef::minted).count(), "exactly one caller minted it");
            assertEquals(1, world.rows("agent_command", agent));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentEnqueuesOfDifferentIntentsOnOnePlacementGetDistinctAscendingGenerations() throws Exception {
        AgentKitWorld.Agent agent = world.enrol();
        List<AgentCommandIntent> intents = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            intents.add(world.intent(agent, UUID.randomUUID() + ":APPLY:r" + i, AgentOperation.APPLY));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<AgentCommandRef>> futures = new ArrayList<>();
            for (AgentCommandIntent intent : intents) {
                Callable<AgentCommandRef> call = () -> world.commands().enqueue(intent);
                futures.add(pool.submit(call));
            }
            List<Long> generations = new ArrayList<>();
            for (Future<AgentCommandRef> f : futures) {
                generations.add(f.get().generation());
            }
            assertEquals(6, generations.stream().distinct().count(), "the agent's journal refuses a repeated generation");
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), generations.stream().sorted().toList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aMegabytePayloadLeavesTheRowTheSamePhysicalSize() {
        AgentKitWorld.Agent agent = world.enrol();
        AgentSessionRef session = world.ready(agent);
        AgentCommandRef ref = world.commands().enqueue(world.intent(agent, UUID.randomUUID() + ":APPLY:r0", AgentOperation.APPLY));
        CommandNotice notice = world.commands().pendingFor(session, 10, java.time.Duration.ofSeconds(15)).get(0);
        byte[] megabyte = new byte[1 << 20];
        java.util.Arrays.fill(megabyte, (byte) 'x');
        world.payload(ref.commandId(), megabyte, "c".repeat(64));
        String before = world.commandFingerprint(ref.commandId());
        assertEquals(megabyte.length, world.commands().pull(session, notice.commandId()).payload().bytes().length);
        assertEquals(before, world.commandFingerprint(ref.commandId()));
        int size = Integer.parseInt(before.substring(before.lastIndexOf('|') + 1));
        assertTrue(size < 1024, "the whole agent_command row is " + size + " bytes: it cannot be holding a megabyte");
        assertFalse(world.anyAgentRowContains(agent, "xxxxxxxxxxxxxxxx"));
    }
}
