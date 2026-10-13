package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.6 / plan 007 Task 9: the backend resilience scenarios that settle the "event-driven with REST healing"
 * claim, driven against the same stack as the walkthrough: scenarios 1-3 (broker down before an apply, mid-apply, one
 * dropped notice), 11 (kill mid-apply, restart, the startup sweep lands it - against the real SSH-Docker adapter, which
 * closes plan 005's third routed watch item) and R1 (verify-then-retry on the real host).
 *
 * <p>One instance on the walkthrough target serves every scenario, applied one after another (a terminal request frees
 * the scope; R1 leaves none open because the reconciler resolves it). Each scenario that needs a clean publisher boots a
 * fresh application against the SAME database, which is also what a restart is.
 *
 * <p>Scenarios 4-10 are the frontend's (plan 007 Task 9's own handoff) and are not backend-plan completion evidence.
 */
abstract class AbstractMeshResilience extends WalkthroughSupport {

    private long floor;
    /** The request the instance's last stage-and-submit created: the next admission supersedes it and says so. */
    private UUID lastApply;

    @BeforeAll
    void declareTheInstanceAndCaptureTheOriginal() throws Exception {
        solutionId = UUID.fromString(app.loadPackageAsAdmin().get("solutionId").asText());
        declared = app.declare(solutionId, "e2e-resilience-cache");
        floor = stack.raiseGenerationFloor(declared.instance());
        assertTrue(floor > 1_000_000_000L, "the floor is per-run (time based): " + floor);
        ref = stack.placementRef(declared.instance(), declared.placement());
        original = stack.captureOriginal(app, ref);
        assertTrue(original.running(), "the Cache container must be running before the scenarios start");
        evidence.record("Setup", "instance " + declared.instance() + ", placement " + declared.placement()
                + "; generation floor " + floor + "; the original host file's keyed hash is recorded above");
    }

    @Override
    List<String[]> skips() {
        return List.of(new String[] {"scenario 3's exported-metric assertion (the counter AND the exported metric both move)",
                "no exported metric exists in this build: JcsmpEventPublisher.droppedCount() is a counter and its own "
                        + "javadoc says the metrics plan must expose it; only the counter is asserted"},
                new String[] {"scenarios 4-10 (browser reconnect, ticket expiry, stateVersion ordering, degraded banner, "
                        + "recovery, query retry)", "frontend scenarios: plan 007 Task 9's own handoff, owned by "
                        + "frontend/src/shared/mesh/resilience.test.ts, not backend-plan completion evidence"});
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Closes the running application and boots a fresh one against the same database: what a restart is. */
    private void rebootApp(boolean throwOnceMesh) throws Exception {
        app.close();
        app = stack.boot(Map.of(), throwOnceMesh);
    }

    /** The observer of the broker that was just restarted: its old session died with the broker. */
    private void reopenTheObserver() throws Exception {
        events.close();
        events = observeOrganization();
    }

    private UUID stageAndSubmit(String tag, String strategy, UUID brokerSecret) throws Exception {
        long generation = app.stage(declared.instance(), stack.target.namespace() + "-" + tag, brokerSecret);
        UUID applyId = app.submit(declared.instance(), declared.placement(), generation, strategy);
        lastApply = applyId;
        return applyId;
    }

    private static long awaitCounterAbove(java.util.function.LongSupplier counter, long above, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        long now = counter.getAsLong();
        while (now <= above && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            now = counter.getAsLong();
        }
        return now;
    }

    private void assertTheEngineNeverHeardOfAnyNoticeFailure() {
        assertEquals(0L, app.logs.countContaining(Level.WARN, "did not land cleanly"),
                "no exception reached the engine or the worker from a lost notice");
        assertEquals(0L, app.logs.countContaining(Level.ERROR, "notice not published"),
                "the emitter's call-site guard was never needed");
    }

    // ---- scenario 1 ---------------------------------------------------------------------------------------------

    /**
     * "Broker down BEFORE an apply": the broker is already gone when the application starts, so SM never held a session.
     * That is {@code ReconnectingTransport}'s path (it throws while disconnected and the publisher counts the drop), and it
     * is the one half of "the broker is down" that the production code handles as the design says.
     */
    @Test
    @Order(1)
    void scenario1_theBrokerIsDownBeforeAnApplyAndTheApplyStillCompletesAndCommits() throws Exception {
        app.close();   // while the broker is up: a context closing against a dead broker can park in JCSMP's reconnect wait
        stack.broker.stopBroker();
        try {
            app = stack.boot();
            long droppedBefore = app.mesh().droppedCount();
            UUID applyId = stageAndSubmit("s1", "manual", null);
            JsonNode landed = app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);

            assertEquals("STAGED", landed.get("state").asText(), landed.toString());
            assertMaterializedWithTheKeyedHash(applyId);
            long droppedAfter = awaitCounterAbove(() -> app.mesh().droppedCount(), droppedBefore, Duration.ofSeconds(10));
            assertTrue(droppedAfter > droppedBefore, "the counter rose: notices went nowhere and were counted ("
                    + droppedBefore + " -> " + droppedAfter + ")");
            assertEquals("persisted", app.ladder(declared.instance()).get("reached").asText(),
                    "the console reaches the right state by observing");
            assertTheEngineNeverHeardOfAnyNoticeFailure();
            evidence.record("Scenario 1 (broker down before an apply)", "the broker was stopped, THEN the application "
                    + "started (SM never held a session); applyId " + applyId + " landed STAGED through REST; journal: "
                    + journalLine(applyId) + "; droppedCount " + droppedBefore + " -> " + droppedAfter
                    + "; no worker or emitter complaint; ladder persisted");
        } finally {
            stack.broker.startBroker();
            reopenTheObserver();
        }
    }

    // ---- the flagged half of scenarios 1 and 2 ------------------------------------------------------------------

    /**
     * Plan 007 Task 9 scenario 2, as designed: the broker dies mid-apply, after {@code staged} and before
     * {@code restart-observed}. It was gated behind FLAG T6.6-1 (a publish parked the calling thread in JCSMP's reconnect
     * wait) until the transport bounded every send; it now runs on every build.
     */
    @Test
    @Order(6)
    void scenario2_theBrokerDiesMidApplyAfterStagedAndBeforeRestartObservedAndRestHeals() throws Exception {
        rebootApp(false);
        reopenTheObserver();   // a broker this scenario kills must have an observer that saw the notices BEFORE the kill
        long droppedBefore = app.mesh().droppedCount();
        app.adapter.beforeNextRestart(stack.broker::stopBroker);
        UUID brokerSecret = app.putCacheBrokerSecret();
        containerRestarted = true;
        try {
            UUID applyId = stageAndSubmit("s2", "rolling-health-gated", brokerSecret);
            JsonNode landed = app.awaitTerminal(declared.instance(), applyId, GATED_APPLY);

            assertEquals("APPLIED", landed.get("state").asText(), landed.toString());
            assertEquals(1, rolloutNotices(declared.instance(), applyId, "staged"),
                    "'staged' was published and delivered BEFORE the broker was taken down");
            assertTrue(events.matching(r -> r.topic().equals(topic(declared.instance(), "apply-outcome", applyId)))
                    .isEmpty(), "the apply-outcome could not be delivered: the broker was already gone");
            long droppedAfter = awaitCounterAbove(() -> app.mesh().droppedCount(), droppedBefore, Duration.ofSeconds(10));
            assertTrue(droppedAfter > droppedBefore, "later notices were counted as drops: " + droppedBefore + " -> "
                    + droppedAfter);
            assertEquals("restart-observed", app.ladder(declared.instance()).get("reached").asText(), "REST heals");
            assertMaterializedWithTheKeyedHash(applyId);
            assertTheEngineNeverHeardOfAnyNoticeFailure();
            evidence.record("Scenario 2 (broker dies mid-apply)", "gated applyId " + applyId + ": 'staged' delivered once "
                    + "before the broker stopped; the rest dropped and counted (" + droppedBefore + " -> " + droppedAfter
                    + "); the request landed APPLIED, ladder restart-observed, journal: " + journalLine(applyId));
        } finally {
            stack.broker.startBroker();
            reopenTheObserver();
        }
    }

    /**
     * Scenario 1's other half, as designed: the broker dies AFTER SM connected and an apply is submitted while it is gone.
     * Gated behind FLAG T6.6-1 alongside scenario 2 until the transport bounded every send; now on every build.
     */
    @Test
    @Order(7)
    void scenario1b_theBrokerDiesWhileSmIsConnectedAndTheNextApplyStillCompletes() throws Exception {
        rebootApp(false);
        long droppedBefore = app.mesh().droppedCount();
        stack.broker.stopBroker();
        try {
            UUID applyId = stageAndSubmit("s1b", "manual", null);
            JsonNode landed = app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);

            assertEquals("STAGED", landed.get("state").asText(), landed.toString());
            long droppedAfter = awaitCounterAbove(() -> app.mesh().droppedCount(), droppedBefore, Duration.ofSeconds(10));
            assertTrue(droppedAfter > droppedBefore, "the counter rose: " + droppedBefore + " -> " + droppedAfter);
            assertTheEngineNeverHeardOfAnyNoticeFailure();
            evidence.record("Scenario 1b (broker dies while SM is connected)", "applyId " + applyId + " landed STAGED with "
                    + "the broker down; droppedCount " + droppedBefore + " -> " + droppedAfter);
        } finally {
            stack.broker.startBroker();
            reopenTheObserver();
        }
    }

    /**
     * FLAG T6.6-1, FIXED (this was the characterization of the defect and went red, as designed, the day the publisher
     * stopped blocking): when the broker dies while SM holds a session, JCSMP's transport (reconnectRetries=-1) used to
     * park {@code producer.send} - and with it the REST request thread that publishes after its commit, and the apply
     * worker - for the whole outage. Every send is now bounded ({@code sm.mesh.send-timeout}): the request answers, the
     * notice is counted as a drop, and no request thread is parked in the reconnect wait. The admitted apply lands through
     * REST, and publishing resumes by itself when the broker returns.
     */
    @Test
    @Order(2)
    void aMidSessionBrokerOutageNeverParksTheRequestThreadAndPublishingResumesWhenTheBrokerReturns()
            throws Exception {
        rebootApp(false);
        long droppedBefore = app.mesh().droppedCount();
        long generation = app.stage(declared.instance(), stack.target.namespace() + "-flag1", null);
        stack.broker.stopBroker();
        UUID applyId;
        long answeredInMillis;
        try {
            long start = System.nanoTime();
            java.util.concurrent.CompletableFuture<UUID> submission = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return app.submit(declared.instance(), declared.placement(), generation, "manual");
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            // The broker's death is only noticed by JCSMP after its keepalive window, so the first publishes may still
            // succeed; the request must answer in every case. A parked request thread would never answer.
            applyId = submission.get(60, java.util.concurrent.TimeUnit.SECONDS);
            answeredInMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            JsonNode landed = app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);
            assertEquals("STAGED", landed.get("state").asText(), landed.toString());
            // Give the apply's notices time to be offered into the dead session, then look at who is parked.
            Thread.sleep(2_000);
            List<String> parked = threadsParkedInTheReconnectWait();
            assertFalse(parked.stream().anyMatch(name -> name.startsWith("http-nio") || name.startsWith("apply-worker")),
                    "no REST request thread and no apply worker is parked in JCSMP's reconnect wait: " + parked);
            long droppedAfter = awaitCounterAbove(() -> app.mesh().droppedCount(), droppedBefore, Duration.ofSeconds(60));
            assertTrue(droppedAfter > droppedBefore, "the notices that went nowhere were counted as drops: "
                    + droppedBefore + " -> " + droppedAfter);
            assertTheEngineNeverHeardOfAnyNoticeFailure();
            evidence.record("FLAG T6.6-1 (fixed: bounded publish)", "with the broker stopped while SM held a session, "
                    + "submitApply for generation " + generation + " answered in " + answeredInMillis + " ms (applyId "
                    + applyId + "); the request landed STAGED through REST; threads parked in "
                    + "waitUntilSessionReconnectDone: " + parked + "; droppedCount " + droppedBefore + " -> " + droppedAfter);
        } finally {
            stack.broker.startBroker();
        }
        reopenTheObserver();
        // Publishing resumes by itself: once JCSMP has reconnected, a later apply's outcome reaches the observer.
        Instant until = Instant.now().plusSeconds(120);
        UUID later = null;
        boolean delivered = false;
        int attempt = 0;
        while (!delivered && Instant.now().isBefore(until)) {
            // A DISTINCT namespace per attempt: staging the same content again returns the generation the previous attempt
            // already landed STAGED, which sits at the fence, so the retry itself was refused 409
            // (GENERATION_NOT_ABOVE_WATERMARK) whenever the session had not yet reconnected for the first attempt.
            later = stageAndSubmit("flag1-resume-" + (++attempt), "manual", null);
            app.awaitTerminal(declared.instance(), later, MANUAL_APPLY);
            delivered = awaitMatching("apply-outcome " + later + " STAGED");
        }
        assertTrue(delivered, "publishing resumed: an apply submitted after the broker returned had its outcome delivered");
        evidence.record("FLAG T6.6-1 (fixed: bounded publish)", "after the broker returned publishing resumed by itself: the "
                + "outcome of applyId " + later + " reached the observer; journal: " + journalLine(later));
    }

    /** The names of the threads whose stack is inside JCSMP's reconnect wait. */
    private static List<String> threadsParkedInTheReconnectWait() {
        List<String> names = new java.util.ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            for (StackTraceElement frame : entry.getValue()) {
                if (frame.getMethodName().equals("waitUntilSessionReconnectDone")) {
                    names.add(entry.getKey().getName());
                    break;
                }
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    // ---- scenario 3 ---------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void scenario3_oneNoticeIsDroppedBecauseTheTransportThrowsOnceAndNothingReachesTheEngine() throws Exception {
        rebootApp(true);
        assertNotNull(app.meshFault, "this boot carries the throw-once publisher");
        long droppedBefore = app.mesh().droppedCount();
        UUID prior = lastApply;
        app.meshFault.armThrowOnce();

        UUID applyId = stageAndSubmit("s3", "manual", null);
        JsonNode landed = app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);

        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        assertEquals(1, app.meshFault.injectedThrows(), "the transport threw exactly once");
        // What this apply owes the mesh, in the order the engine offers it. Admitting it SUPERSEDES the previous request on
        // the scope and says so first (an apply-outcome for THAT request); then the staged rollout; then this request's own
        // outcome. (No config-state notice: the ladder was already at persisted, so its rung did not move.)
        java.util.List<String> owed = new java.util.ArrayList<>();
        if (prior != null) {
            owed.add("apply-outcome " + prior + " SUPERSEDED");
        }
        owed.add("rollout " + applyId + " staged");
        owed.add("apply-outcome " + applyId + " STAGED");
        java.util.List<String> delivered = new java.util.ArrayList<>();
        for (String notice : owed.subList(1, owed.size())) {
            assertTrue(awaitMatching(notice), "the notice after the lost one was delivered: " + notice);
        }
        Thread.sleep(500);
        for (String notice : owed) {
            if (matching(notice)) {
                delivered.add(notice);
            }
        }
        assertEquals(owed.size() - 1, delivered.size(), "exactly one notice is missing from " + owed + ", delivered "
                + delivered);
        assertFalse(delivered.contains(owed.get(0)), "the notice that was lost is the FIRST one offered: " + owed.get(0));
        assertEquals(droppedBefore + 1, app.mesh().droppedCount(), "exactly the one notice was counted");
        assertEquals(1L, app.logs.countContaining(Level.WARN, "dropping event"), "one counted-drop warning");
        assertTheEngineNeverHeardOfAnyNoticeFailure();
        evidence.record("Scenario 3 (one notice dropped)", "applyId " + applyId + ": the transport threw once (injected "
                + "in front of the real JCSMP transport); owed " + owed + "; delivered " + delivered + "; lost: "
                + owed.get(0) + "; droppedCount " + droppedBefore + " -> " + app.mesh().droppedCount()
                + "; the apply landed STAGED; no exception reached the engine or the worker");
    }

    /** Whether the observer has the described notice: {@code "<aspect> <applyId> <state-or-step>"}. */
    private boolean matching(String notice) {
        String[] parts = notice.split(" ");
        UUID applyId = UUID.fromString(parts[1]);
        return events.matching(r -> r.topic().equals(topic(declared.instance(), parts[0], applyId))
                && parts[2].equals(parts[0].equals("rollout") ? json(r).at("/payload/step").asText()
                        : json(r).at("/payload/state").asText())).size() >= 1;
    }

    private boolean awaitMatching(String notice) throws InterruptedException {
        Instant deadline = Instant.now().plus(NOTICE);
        while (Instant.now().isBefore(deadline)) {
            if (matching(notice)) {
                return true;
            }
            Thread.sleep(100);
        }
        return matching(notice);
    }

    // ---- scenario R1 --------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void scenarioR1_aConnectionLostAfterTheWriteIsReconciledAndConfirmedWithNoSecondHostWrite() throws Exception {
        rebootApp(false);
        app.adapter.armConnectionLossAfterNextWrite();

        UUID applyId = stageAndSubmit("r1", "manual", null);
        Instant deadline = Instant.now().plusSeconds(90);
        while (!app.adapter.connectionLossClosed() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        // The reconciler's first poll can resolve the request within one REST poll of its landing, so the transient
        // APPLY_INDETERMINATE is proved from what the landing WRITES (the reconciliation ledger row, in the landing's own
        // transaction) and from the notice it published (OR-58), not from catching the state in flight.
        assertTrue(app.adapter.connectionLossClosed(), "the engine reached its exhaustion path and its assessment was "
                + "lost (journal: " + journalLine(applyId) + ")");
        JsonNode resolved = app.awaitTerminal(declared.instance(), applyId, Duration.ofSeconds(90));
        long ledger = stack.count("SELECT count(*) FROM sm_data.apply_reconciliation WHERE tenant_id = :t AND apply_id = :a",
                Map.of("t", stack.organization(), "a", applyId));
        if (ledger == 0) {
            evidence.record("Scenario R1 (verify-then-retry)", "SKIPPED: the request never landed APPLY_INDETERMINATE (no "
                    + "reconciliation ledger row); it landed " + resolved.get("state").asText() + ", journal: "
                    + journalLine(applyId));
            abort("R1 skipped: the observed landing was " + resolved.get("state").asText() + ", not APPLY_INDETERMINATE");
        }
        int writesAtClose = app.adapter.configWritesWhenConnectionLossClosed();
        assertTrue(writesAtClose >= 1, "the write that was cut off really happened");

        assertEquals("INDETERMINATE_RESOLVED", resolved.get("state").asText(), resolved.toString());
        assertEquals(1L, ledger, "the landing handed the request to the reconciler");
        assertTrue(journalLine(applyId).contains("ROLLBACK_ASSESS#1:FAILED(ssh.connection.failed)"),
                "the exhaustion path's assessment is the answer that was lost: " + journalLine(applyId));
        assertEquals(writesAtClose, app.adapter.configWrites(),
                "the SFTP-writing operations (materialize, rematerialize, restore) are unchanged across the reconciliation");
        assertEquals(0, app.adapter.rematerializes(), "confirmed, not re-applied");
        assertEquals(0, app.adapter.restores(), "and not rolled back");
        assertEquals(0, app.adapter.restarts(), "and under manual nothing restarts");
        Map<String, Object> intended = stack.journal(applyId).stream().filter(r -> "MATERIALIZE".equals(r.get("step")))
                .findFirst().orElseThrow();
        RuntimeStatus onHost = stack.hostStatus(app, ref);
        assertEquals(intended.get("config_hash"), onHost.observedHash(), "the host still holds the intended file");
        assertEquals("persisted", app.ladder(declared.instance()).get("reached").asText());
        assertTrue(awaitMatching("apply-outcome " + applyId + " APPLY_INDETERMINATE"),
                "the APPLY_INDETERMINATE landing published its outcome (OR-58)");
        assertTrue(awaitMatching("apply-outcome " + applyId + " INDETERMINATE_RESOLVED"),
                "and so did the reconciler's confirmation");
        assertTheEngineNeverHeardOfAnyNoticeFailure();
        evidence.record("Scenario R1 (verify-then-retry on the host)", "applyId " + applyId + " went APPLY_INDETERMINATE "
                + "after its write landed (a decorator over the production adapter lost the observations, MINOR 16; "
                + "reconciliation ledger rows: " + ledger + "); the reconciler inspected the host, found the intended file "
                + "(keyed hash " + onHost.observedHash() + ") and under manual CONFIRMED it: INDETERMINATE_RESOLVED. "
                + "Config-writing adapter operations at the moment the connection was restored: " + writesAtClose
                + "; after the reconciliation: " + app.adapter.configWrites() + " (unchanged); rematerialize 0, restore 0, "
                + "restart 0. Notices: apply-outcome APPLY_INDETERMINATE then INDETERMINATE_RESOLVED. Journal: "
                + journalLine(applyId));
    }

    // ---- scenario 11 --------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    void scenario11_theProcessIsKilledMidApplyAndTheStartupSweepOfTheRestartLandsIt() throws Exception {
        rebootApp(false);
        E2eStack.App killed = app;
        killed.adapter.armKillAfterNextWrite();

        UUID applyId = stageAndSubmit("s11", "manual", null);
        Instant deadline = Instant.now().plusSeconds(120);
        while (!killed.adapter.killed() && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertTrue(killed.adapter.killed(), "the worker reached the adapter after the write and the process 'died'");
        String atTheKill = stack.requestState(applyId);
        long open = stack.count("SELECT count(*) FROM sm_data.apply_attempt WHERE tenant_id = :t AND apply_id = :a AND"
                + " outcome IS NULL", Map.of("t", stack.organization(), "a", applyId));
        assertEquals("RUNNING", atTheKill, "killed mid-apply: the request is claimed and unlanded");
        assertTrue(open >= 1, "and an attempt is still open in the journal");
        String journalAtKill = journalLine(applyId);
        int writesByTheDeadProcess = killed.adapter.configWrites();

        killed.close();                 // the process is gone
        app = stack.boot();             // the restart: its startup sweep runs before the connector opens

        assertEquals("STAGED", stack.requestState(applyId), "the sweep drove the request to a terminal state");
        assertEquals(0L, stack.count("SELECT count(*) FROM sm_data.apply_attempt WHERE tenant_id = :t AND apply_id = :a"
                + " AND outcome IS NULL", Map.of("t", stack.organization(), "a", applyId)), "no attempt is left open");
        int cap = app.bean(AttemptPolicy.class).maxAttempts();
        for (String step : new String[] {"PREFLIGHT", "MATERIALIZE"}) {
            long attempts = stack.count("SELECT max(a.attempt_no) FROM sm_data.apply_attempt a JOIN"
                    + " sm_data.apply_step_intent i ON i.intent_id = a.intent_id WHERE i.tenant_id = :t AND i.apply_id"
                    + " = :a AND i.step = :s", Map.of("t", stack.organization(), "a", applyId, "s", step));
            assertTrue(attempts >= 1 && attempts <= cap, step + " spent " + attempts + " attempt(s), the budget is " + cap);
        }
        JsonNode landed = app.observe(declared.instance(), applyId);
        assertEquals("STAGED", landed.get("state").asText());
        Map<String, Object> row = assertMaterializedWithTheKeyedHash(applyId);
        assertEquals(row.get("config_hash"), stack.hostStatus(app, ref).observedHash(), "the host holds the intended file");
        assertEquals(1, awaitNotices(events, topic(declared.instance(), "apply-outcome", applyId), 1).size(),
                "the restarted process published the one apply-outcome");
        assertEquals("persisted", app.ladder(declared.instance()).get("reached").asText());
        evidence.record("Scenario 11 (kill mid-apply, restart, startup sweep)", "applyId " + applyId + ": at the kill the "
                + "request was " + atTheKill + " with " + open + " open attempt(s) (journal: " + journalAtKill + ", "
                + writesByTheDeadProcess + " config write(s) by the dead process); after the restart's sweep it is "
                + landed.get("state").asText() + " (journal: " + journalLine(applyId) + "); per-step attempts within the "
                + "budget of " + cap + "; the host holds the intended keyed hash; one apply-outcome notice");
    }
}
