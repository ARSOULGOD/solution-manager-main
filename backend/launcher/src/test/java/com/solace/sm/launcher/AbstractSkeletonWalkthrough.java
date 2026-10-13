package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.launcher.DeclareFixtures.Login;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.6, the first end-to-end walkthrough: declare, stage, apply, observe and the apply-outcome on a local broker,
 * as two actors and the operator commands (plan 007 Task 11).
 *
 * <p>The installing operator ran {@code EntitlementMain} and {@code TargetConnectionMain} and the bootstrap admin ran
 * {@code TargetRegistrationMain} in {@link WalkthroughSupport#bringUp()}; this class is the rest. The bootstrap admin loads
 * the package over HTTP (it is the admin's leg: an environment-bound Operator is refused, asserted); {@code demo-operator},
 * bound {@code Operator} at {@code [org, unit, env]}, lists solutions and targets (the reach classification, S0 point 24),
 * declares one instance, stages a secret-free change, applies with {@code manual} and observes it to {@code STAGED} by REST
 * while {@code instance.apply-outcome} for that {@code applyId} is received on the local broker.
 *
 * <p>Then the negatives: the sibling environment's instance is invisible over REST; a declare naming a directory outside the
 * envelope is {@code 422} and writes nothing (B2); and one {@code rolling-health-gated} apply records the
 * {@code gateProved} value the API reports. The live notify for the browser is skipped with its reason recorded.
 *
 * <p>Applied to the production {@code SshjDockerTransport}, this is also the end-to-end proof of B1's fix: the observed hash
 * of the apply is spelt {@code hmacsha256v<n>:} and equals the intent's {@code config_hash}.
 */
abstract class AbstractSkeletonWalkthrough extends WalkthroughSupport {

    private long floor;
    private long manualGeneration;
    private long gatedGeneration;
    private UUID manualApply;
    private UUID gatedApply;
    private JsonNode manualLanded;

    @Test
    @Order(1)
    void theBootstrapAdminLoadsThePackageAndAnEnvironmentBoundOperatorCannot() throws Exception {
        JsonNode loaded = app.loadPackageAsAdmin();
        solutionId = UUID.fromString(loaded.get("solutionId").asText());

        HttpResponse<String> byOperator = app.loadPackageAs(DeclareFixtures.OPERATOR_ONE);

        assertEquals(403, byOperator.statusCode(), "package.load is the admin's leg, at organization scope (OR-14)");
        MeshEventLog.Received notice = events.awaitOne(
                r -> r.topic().equals("sm/v1/" + stack.organization() + "/notify/catalog/package"), NOTICE);
        assertNotNull(notice, "the catalog.package notice reached the local broker after the package's commit");
        evidence.record("1. Bootstrap admin", "loaded the cache package over HTTP (solution " + solutionId
                + "); demo-operator's attempt to load it: " + byOperator.statusCode());
        evidence.record("1. Bootstrap admin", "notice on " + notice.topic() + ", eventId "
                + json(notice).get("eventId").asText() + ", stateVersion " + json(notice).get("stateVersion").asText());
    }

    @Test
    @Order(2)
    void theOperatorListsTheSolutionAndTheRegisteredTargetInsideItsEnvelopeAndNothingBeyondItsReach() throws Exception {
        JsonNode solutions = app.get("/api/cp/solutions", DeclareFixtures.OPERATOR_ONE);
        JsonNode targets = app.get("/api/cp/targets", DeclareFixtures.OPERATOR_ONE);

        boolean solutionListed = false;
        for (JsonNode item : solutions.get("items")) {
            solutionListed |= item.get("solutionId").asText().equals(solutionId.toString());
        }
        assertTrue(solutionListed, "the loaded solution is listed to the operator: " + solutions);
        JsonNode mine = null;
        for (JsonNode item : targets.get("items")) {
            String id = item.get("targetId").asText();
            assertFalse(id.equals(stack.world.targetTwo().toString()), "the sibling environment's target is not reachable");
            assertFalse(id.equals(stack.world.otherTarget().toString()), "another organization's target is not reachable");
            if (id.equals(stack.registeredTargetId().toString())) {
                mine = item;
            }
        }
        assertNotNull(mine, "the registered target is listed: " + targets);
        assertEquals(stack.target.allowedConfigRoot(), mine.get("allowedConfigRoot").asText());
        assertEquals(stack.target.allowedContainerPrefix(), mine.get("allowedContainerPrefix").asText());
        assertTrue(mine.get("ownershipScope").toString().contains(stack.world.envOne().toString()),
                "the target is owned at the walkthrough environment (S0 point 22): " + mine.get("ownershipScope"));
        evidence.record("2. Reach", "demo-operator lists " + solutions.get("items").size() + " solution(s) and "
                + targets.get("items").size() + " target(s); the registered target " + stack.registeredTargetId()
                + " is among them with envelope root " + mine.get("allowedConfigRoot").asText() + " and prefix "
                + mine.get("allowedContainerPrefix").asText() + "; the sibling environment's target and the other "
                + "organization's are not");
    }

    @Test
    @Order(3)
    void aDeclareOutsideTheEnvelopeIs422AndWritesNothing() throws Exception {
        java.util.Map<String, Object> params = java.util.Map.of("t", stack.organization());
        long placements = stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", params);
        long instances = stack.count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", params);

        HttpResponse<String> outsideDirectory = app.declareRaw(DeclareFixtures.OPERATOR_ONE,
                app.declareBody(solutionId, "outside-directory", stack.target.configDirectory() + "-outside"));
        ObjectNode wrongContainer = app.declareBody(solutionId, "outside-container", stack.target.configDirectory());
        ((ObjectNode) wrongContainer.get("placement")).put("containerName", "not-" + stack.target.container());
        HttpResponse<String> outsideContainer = app.declareRaw(DeclareFixtures.OPERATOR_ONE, wrongContainer);

        assertEquals(422, outsideDirectory.statusCode(), outsideDirectory.body());
        assertTrue(outsideDirectory.body().contains("configDirectory"), "the refusal names the field");
        assertEquals(422, outsideContainer.statusCode(), outsideContainer.body());
        assertTrue(outsideContainer.body().contains("containerName"), "the refusal names the field");
        assertEquals(placements, stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", params),
                "no placement was written");
        assertEquals(instances, stack.count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", params),
                "no instance was written");
        evidence.record("3. B2 against the target's envelope", "a declare naming " + stack.target.configDirectory()
                + "-outside: " + outsideDirectory.statusCode() + " naming configDirectory; a container named "
                + "not-<container>: " + outsideContainer.statusCode() + " naming containerName; placement rows "
                + placements + " before and after, instance rows " + instances + " before and after (nothing written)");
    }

    @Test
    @Order(4)
    void theOperatorDeclaresOneInstanceCapturesTheHostsOriginalAndStagesASecretFreeChange() throws Exception {
        declared = app.declare(solutionId, "e2e-walkthrough-cache");
        floor = stack.raiseGenerationFloor(declared.instance());
        assertTrue(floor > 1_000_000_000L, "the floor is per-run (time based) and far above any counter a previous run left "
                + "pre-images for on the host: " + floor);
        ref = stack.placementRef(declared.instance(), declared.placement());
        original = stack.captureOriginal(app, ref);
        assertTrue(original.running(), "the Cache container must be running before the walkthrough starts: a rolling-"
                + "health-gated apply restarts it and the original state is what the final restore returns to");

        manualGeneration = app.stage(declared.instance(), stack.target.namespace() + "-staged", null);

        assertEquals(floor + 1, manualGeneration, "the first generation of this run sits above this run's floor, so "
                + "no pre-image a previous run left on the host can be mistaken for this run's");
        evidence.record("4. Declare and stage", "instance " + declared.instance() + ", placement "
                + declared.placement() + " (201); generation floor " + floor + "; staged secret-free generation "
                + manualGeneration + " (solace.enabled=false, namespace " + stack.target.namespace() + "-staged)");
    }

    @Test
    @Order(5)
    void aManualApplyReturns202AndLandsStagedAndItsOutcomeIsReceivedOnTheLocalBroker() throws Exception {
        manualApply = app.submit(declared.instance(), declared.placement(), manualGeneration, "manual");
        JsonNode first = app.observe(declared.instance(), manualApply);

        manualLanded = app.awaitTerminal(declared.instance(), manualApply, MANUAL_APPLY);

        assertEquals("STAGED", manualLanded.get("state").asText(), manualLanded.toString());
        JsonNode outcome = assertApplyOutcomeNotice(events, declared.instance(), manualApply, "STAGED", manualLanded);
        long outcomes = events.matching(r -> r.topic().equals(topic(declared.instance(), "apply-outcome", manualApply)))
                .size();
        assertEquals(1, outcomes, "one landing is exactly one apply-outcome notice");
        JsonNode staged = json(awaitNotices(events, topic(declared.instance(), "rollout", manualApply), 1).get(0));
        assertEquals("staged", staged.at("/payload/step").asText());
        assertEquals(stack.expectedReplicaRef(), staged.at("/payload/replicaId").asText());
        JsonNode configState = json(awaitNotices(events, topic(declared.instance(), "config-state", null), 1).get(0));
        assertEquals("persisted", configState.at("/payload/reached").asText());
        assertEquals(0L, app.logs.countContaining(Level.WARN, "did not land cleanly"), "the worker landed it cleanly");

        evidence.record("5. Manual apply", "submitApply 202 with applyId " + manualApply + " (state at the first "
                + "observation: " + first.get("state").asText() + "); observed to STAGED by REST, stateVersion "
                + manualLanded.get("stateVersion").asLong());
        evidence.record("5. Notices on the local broker", "instance.apply-outcome on " + topic(declared.instance(),
                "apply-outcome", manualApply) + " eventId " + outcome.get("eventId").asText() + " state STAGED, stateVersion "
                + outcome.get("stateVersion").asLong() + " (= the request's own stamp); instance.rollout step staged "
                + "for replica " + stack.expectedReplicaRef() + "; instance.config-state reached persisted");
    }

    @Test
    @Order(6)
    void theObservedHashIsTheKeyedSpellingAndEqualsTheIntentAndTheLadderReportsPersisted() throws Exception {
        java.util.Map<String, Object> row = assertMaterializedWithTheKeyedHash(manualApply);
        RuntimeStatus live = stack.hostStatus(app, ref);
        JsonNode ladder = app.ladder(declared.instance());

        assertEquals(row.get("config_hash"), live.observedHash(), "the host holds the intended file right now");
        assertEquals("persisted", ladder.get("reached").asText());
        for (JsonNode rung : ladder.get("rungs")) {
            if (rung.get("rung").asText().equals("restart-observed")) {
                assertFalse(rung.get("present").asBoolean(), "a manual apply restarts nothing, so the journal proves no "
                        + "restart-observed");
            }
        }
        evidence.record("6. B1 on the production transport", "apply_attempt.observed_hash for the MATERIALIZE of "
                + manualApply + " is " + row.get("observed_hash") + ", spelt hmacsha256v<n>:<hex> and EQUAL to the "
                + "intent's config_hash; the host read right now returns the same keyed hash");
        evidence.record("6. M4 (does Cache re-read application.yml without a restart?)", "after the manual apply the "
                + "ladder reports reached=" + ladder.get("reached").asText() + " and restart-observed absent: the journal "
                + "proves the file was persisted and nothing more. Whether the RUNNING Cache re-read it is not provable "
                + "from the journal and is the operator's observation (see the evidence template, M4)");
    }

    @Test
    @Order(7)
    void theSiblingEnvironmentsInstanceIsInvisibleOverRestAndSoIsTheirsToUs() throws Exception {
        ObjectNode sibling = DeclareFixtures.declareBody(solutionId, stack.world.targetTwo(), "sibling-cache",
                "sm-sibling", 9102);
        HttpResponse<String> declaredSibling = app.http.post("/api/dp/environments/" + stack.world.envTwo() + "/instances",
                app.login(DeclareFixtures.OPERATOR_TWO), sibling);
        assertEquals(201, declaredSibling.statusCode(), declaredSibling.body());
        UUID siblingInstance = UUID.fromString(DeclareFixtures.MAPPER.readTree(declaredSibling.body()).get("instanceId")
                .asText());
        Login one = app.login(DeclareFixtures.OPERATOR_ONE);
        Login two = app.login(DeclareFixtures.OPERATOR_TWO);

        assertEquals(404, app.http.get("/api/dp/instances/" + siblingInstance, one).statusCode(),
                "operator one cannot see the sibling environment's instance");
        assertEquals(404, app.http.get("/api/dp/instances/" + declared.instance(), two).statusCode(),
                "and operator two cannot see ours");
        JsonNode listed = DeclareFixtures.MAPPER.readTree(app.http.get("/api/dp/solutions/" + solutionId + "/instances",
                one).body());
        boolean ours = false;
        for (JsonNode item : listed.get("items")) {
            String id = item.get("instanceId").asText();
            assertFalse(id.equals(siblingInstance.toString()), "the sibling's instance is not in operator one's list");
            ours |= id.equals(declared.instance().toString());
        }
        assertTrue(ours, "our instance is in operator one's list");
        assertEquals(404, app.http.post("/api/dp/environments/" + stack.world.envTwo() + "/instances", one,
                app.declareBody(solutionId, "into-sibling", stack.target.configDirectory())).statusCode(),
                "operator one cannot declare into the sibling environment");
        evidence.record("7. Scope", "the sibling environment's instance " + siblingInstance + " (declared by its own "
                + "operator): GET by operator one is 404, absent from operator one's list; our instance is 404 for "
                + "operator two; operator one's declare into the sibling environment is 404");
    }

    @Test
    @Order(8)
    void aRollingHealthGatedApplyRestartsTheCacheAndReportsWhatTheGateProved() throws Exception {
        UUID brokerSecret = app.putCacheBrokerSecret();
        gatedGeneration = app.stage(declared.instance(), stack.target.namespace() + "-gated", brokerSecret);
        containerRestarted = true;   // set before the submit: even a failed gate leaves a restarted container to restore

        gatedApply = app.submit(declared.instance(), declared.placement(), gatedGeneration, "rolling-health-gated");
        JsonNode landed = app.awaitTerminal(declared.instance(), gatedApply, GATED_APPLY);

        assertEquals("APPLIED", landed.get("state").asText(), landed.toString());
        String gateProved = null;
        for (JsonNode step : landed.get("steps")) {
            assertEquals("succeeded", step.get("outcome").asText(), "every step of the gated apply: " + step);
            if (step.get("step").asText().equals("lifecycle")) {
                gateProved = step.get("gateProved").asText();
            }
        }
        assertEquals("liveness", gateProved, "the package declares a liveness probe for Cache (T0.1a)");
        assertApplyOutcomeNotice(events, declared.instance(), gatedApply, "APPLIED", landed);
        for (String step : new String[] {"staged", "restart-requested", "restart-observed"}) {
            assertEquals(1, rolloutNotices(declared.instance(), gatedApply, step),
                    "exactly one rollout notice for step " + step);
        }
        JsonNode ladder = app.ladder(declared.instance());
        assertEquals("restart-observed", ladder.get("reached").asText());
        assertMaterializedWithTheKeyedHash(gatedApply);
        evidence.record("8. rolling-health-gated", "applyId " + gatedApply + " (generation " + gatedGeneration
                + (brokerSecret == null ? ", solace.enabled=false staged" : ", the Cache's own broker staged with its "
                + "password as a secret reference") + ") landed APPLIED; the API reported gateProved=" + gateProved
                + "; ladder reached " + ladder.get("reached").asText() + "; notices staged, restart-requested and "
                + "restart-observed once each, then the apply-outcome (APPLIED)");
        assertEquals(0L, app.logs.countContaining(Level.WARN, "did not land cleanly"));
        assertEquals(0L, app.logs.countContaining(Level.ERROR, "notice not published"));
    }

    /** Reported as skipped with its reason by Maven, and recorded in the evidence by {@link WalkthroughSupport#skips()}. */
    @Disabled("live notify for the browser: open items 32/33/35 (a notify ticket grants organization-wide subscription, "
            + "which an environment-scoped principal's REST scope forbids); plan 007 Task 10 is hard-gated")
    @Test
    @Order(9)
    void liveNotifyForTheBrowserIsNotDemonstrated() {
    }
}
