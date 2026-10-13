package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.6 (MINOR 16): the in-process decorator that makes a REAL adapter lose its observations after a REAL write.
 * Pure: the host underneath is the certified in-memory adapter, so the semantics of the window are measured here and a
 * live run only has to trust them.
 */
class FaultInjectingRuntimeAdapterTest {

    private InMemoryRuntimeAdapter host;
    private FaultInjectingRuntimeAdapter adapter;
    private PlacementRef placement;
    private TargetReplica replica;
    private RenderedConfig config;

    @BeforeEach
    void aHostAndADecorator() {
        placement = new PlacementRef(TestHashers.TENANT, new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
        host = new InMemoryRuntimeAdapter(placement, true, "cache-engine");
        adapter = new FaultInjectingRuntimeAdapter(host);
        replica = adapter.replicas(placement).get(0);
        config = TestHashers.rendered("namespace: walkthrough\n");
    }

    @Test
    void unarmedItIsTransparentAndCountsExactlyTheOperationsThatMutateTheHost() {
        adapter.prepare(placement, 1);
        adapter.materializeConfig(replica, 1, config);
        adapter.rematerializeIfCurrent(replica, 1, config, config.hash());
        adapter.startOrUpdate(replica, 1);
        adapter.status(replica);
        adapter.assessRollback(placement, 1, config.hash());
        adapter.stop(replica);

        assertEquals(config.hash(), host.hashOf(replica), "the write reached the host underneath");
        assertEquals(1, adapter.materializes());
        assertEquals(1, adapter.rematerializes());
        assertEquals(0, adapter.restores());
        assertEquals(1, adapter.restarts());
        assertEquals(1, adapter.stops());
        assertEquals(2, adapter.configWrites(), "a config write is a materialize, a rematerialize or a restore");
        assertFalse(adapter.connectionLossOpen());
        assertFalse(adapter.killed());
    }

    @Test
    void theConnectionLossWindowOpensOnlyWhenTheNextWriteHasReturnedAndLosesTheObservationsNotTheWrite() {
        adapter.armConnectionLossAfterNextWrite();

        assertNull(adapter.status(replica).observedHash(), "armed is not open: reads still work before the write");
        assertFalse(adapter.connectionLossOpen());
        adapter.materializeConfig(replica, 1, config);

        assertTrue(adapter.connectionLossOpen(), "the window opened when materializeConfig returned");
        RuntimeAdapterException lost = assertThrows(RuntimeAdapterException.class, () -> adapter.status(replica));
        assertEquals(FaultInjectingRuntimeAdapter.CONNECTION_LOST.token(), lost.errorClass().token(),
                "a transport classifier, as a dropped SSH session produces");
        assertEquals(config.hash(), host.hashOf(replica), "the write landed on the host; only the observation is lost");
        assertTrue(adapter.connectionLossOpen(), "it stays open across any number of lost reads");
        assertThrows(RuntimeAdapterException.class, () -> adapter.status(replica));
    }

    @Test
    void theEnginesRetryWriteInsideTheWindowReallyHappensAndIsCounted() {
        adapter.armConnectionLossAfterNextWrite();
        adapter.materializeConfig(replica, 1, config);

        adapter.materializeConfig(replica, 1, config);   // the engine's bounded retry of the same intent

        assertEquals(2, adapter.materializes());
        assertEquals(config.hash(), host.hashOf(replica));
        assertTrue(adapter.connectionLossOpen());
    }

    @Test
    void theFirstAssessmentInTheWindowIsLostAndClosesItRecordingTheWriteCountAtThatMoment() {
        host.setFile(replica, "sha256:" + "a".repeat(64));   // a prior file, so the pre-image the write retains is restorable
        adapter.armConnectionLossAfterNextWrite();
        adapter.materializeConfig(replica, 1, config);
        adapter.materializeConfig(replica, 1, config);
        assertEquals(-1, adapter.configWritesWhenConnectionLossClosed(), "not closed yet");

        RuntimeAdapterException lost = assertThrows(RuntimeAdapterException.class,
                () -> adapter.assessRollback(placement, 1, config.hash()));

        assertEquals(FaultInjectingRuntimeAdapter.CONNECTION_LOST.token(), lost.errorClass().token());
        assertFalse(adapter.connectionLossOpen(), "the connection is restored");
        assertTrue(adapter.connectionLossClosed());
        assertEquals(2, adapter.configWritesWhenConnectionLossClosed());
        assertNotNull(adapter.status(replica).observedHash(), "reads work again, as the reconciler's inspection needs");
        assertInstanceOf(RollbackAssessment.Restorable.class, adapter.assessRollback(placement, 1, config.hash()),
                "and so does the assessment it was lost for");
    }

    @Test
    void aWriteThatFailsDoesNotOpenTheWindowSoItStaysArmedForTheNextOne() {
        host.failMaterializeOn("cache-engine");
        adapter.armConnectionLossAfterNextWrite();

        assertThrows(RuntimeAdapterException.class, () -> adapter.materializeConfig(replica, 1, config));

        assertFalse(adapter.connectionLossOpen(), "nothing was written, so there is no write to lose the answer of");
        assertEquals(1, adapter.materializes(), "the attempt is still counted");
    }

    @Test
    void theKillMakesTheNextCallAfterTheWriteThrowAnErrorTheEngineCannotCatchAndEveryCallAfterThat() {
        adapter.armKillAfterNextWrite();
        adapter.materializeConfig(replica, 1, config);

        assertFalse(adapter.killed(), "the process dies at the NEXT call, not inside the write that returned");
        Error dead = assertThrows(FaultInjectingRuntimeAdapter.SimulatedKill.class, () -> adapter.status(replica));
        assertFalse(RuntimeException.class.isInstance(dead), "an Error: no catch (RuntimeException) in the engine can survive it");
        assertTrue(adapter.killed());
        assertThrows(FaultInjectingRuntimeAdapter.SimulatedKill.class, () -> adapter.prepare(placement, 1));
        assertThrows(FaultInjectingRuntimeAdapter.SimulatedKill.class,
                () -> adapter.materializeConfig(replica, 2, config));
        assertEquals(config.hash(), host.hashOf(replica), "what the dead process wrote stays on the host");
        assertEquals(1, adapter.materializes(), "a dead process makes no further write");
    }

    @Test
    void theBeforeRestartHookRunsOnceAndBeforeTheVerbReachesTheHost() {
        List<String> order = new ArrayList<>();
        AtomicInteger restartsSeenByTheHook = new AtomicInteger(-1);
        adapter.beforeNextRestart(() -> {
            order.add("hook");
            restartsSeenByTheHook.set(host.restarts());
        });

        adapter.startOrUpdate(replica, 1);
        adapter.startOrUpdate(replica, 1);

        assertEquals(List.of("hook"), order, "once");
        assertEquals(0, restartsSeenByTheHook.get(), "the host had not restarted when the hook ran");
        assertEquals(2, host.restarts());
        assertEquals(2, adapter.restarts());
    }

    @Test
    void aRestoreIsACountedConfigWriteTooBecauseItWritesTheFile() {
        host.retainPreImage(1, replica, config.hash());
        adapter.restore(placement, 1, config.hash());

        assertEquals(1, adapter.restores());
        assertEquals(1, adapter.configWrites());
    }
}
