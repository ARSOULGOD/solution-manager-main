package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.launcher.DeclareFixtures.Login;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 008 S5 Step 2, live gate {@code L2} ({@code OR-110}: "a second Cache: you can do that if needed"): one instance, TWO
 * placements on ONE target, each its own container, directory and ports, walked end to end through the real REST surface
 * against a real host (live) or a certified in-memory one (the rehearsal).
 *
 * <p>The scenario is the plan's: declare the instance (placement P1, the Cache the host already runs) and capture its
 * original file; add P2 ({@code addPlacement}, the customer's second container, which SM does not deploy, {@code OR-29});
 * a {@code manual} fleet over both writes the two files; with {@code disruptive=yes}, stop P2, stop the whole instance
 * (a step per live placement), and restart both with a {@code restart-all} fleet; then <b>restore both files by keyed
 * hash</b> through the engine's own restore, and finally retire P2 ({@code retirePlacement?workload=leave-running}), which
 * must touch nothing on the host.
 *
 * <h2>What it writes, and what it puts back</h2>
 * Both configuration files, and (with {@code disruptive=yes}) it stops and restarts both containers. The ORIGINAL keyed
 * hash of each file is read before any write ({@link E2eStack#captureOriginal}) and held in memory and in the evidence; the
 * restore step puts each file back and, when a run restarted the containers, restarts them so they RUN the original
 * configuration again. A container this run stopped is started again in {@link #tearDownSecondPlacement()} whatever else
 * failed, and the run FAILS LOUDLY if a file or a container could not be put back.
 *
 * <h2>What a rehearsal cannot show</h2>
 * Docker's view: two real containers sharing a host, their health and metrics ports, {@code docker inspect}'s start time,
 * and a retired placement's container carrying on. Only {@code MultiPlacementLiveIT} can support that claim.
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.MINUTES)
abstract class AbstractMultiPlacementWalkthrough extends WalkthroughSupport {

    private static final Duration FLEET = Duration.ofMinutes(15);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(DeclareFixtures.Client.REQUEST_TIMEOUT).build();

    MultiPlacementTarget multi;
    private long generation;
    private long restartGeneration;
    private UUID secondPlacement;
    private PlacementRef secondRef;
    private E2eStack.OriginalConfig secondOriginal;
    private boolean secondRestored;
    /** Order 8 put P2's file back with no MANUAL or DIFFERS line: the only state in which P2 may be retired (W4A-3). */
    private boolean secondPutBack;
    private boolean secondRetired;
    private boolean restartedBoth;
    /** What this run stopped and has not started again. */
    private final List<PlacementRef> stopped = new ArrayList<>();
    /** The real container name of each placement in {@link #stopped}, for the MANUAL instruction (never a placeholder). */
    private final Map<PlacementRef, String> stoppedContainers = new LinkedHashMap<>();
    private final Map<String, UUID> members = new LinkedHashMap<>();

    /** The second Cache this run adds, validated against the first. Live: from properties. Rehearsal: synthetic. */
    abstract MultiPlacementTarget multiTarget(WalkthroughTarget base);

    // ------------------------------------------------------------------------------------------------ the scenario

    @Test
    @Order(1)
    void theBootstrapAdminLoadsThePackageAndTheSecondCachesTargetIsValidBeforeAnythingTouchesTheHost() throws Exception {
        // A malformed second Cache is refused HERE, once; every later step is skipped (assumeTrue) rather than run against a
        // host it was never validated for, so a misspelt property is one clean refusal and not an SSH session and a cascade.
        multi = multiTarget(stack.target);
        JsonNode loaded = app.loadPackageAsAdmin();
        solutionId = UUID.fromString(loaded.get("solutionId").asText());
        evidence.record("Setup", "second placement: " + multi + "; the run is "
                + (multi.disruptive() ? "DISRUPTIVE (stops and restarts containers, then restores)"
                        : "NOT disruptive (it writes two configuration files, restores them, and retires; no container is stopped)"));
        evidence.record("1. Package", "the cache package is loaded (solution " + solutionId + ")");
    }

    @Test
    @Order(2)
    void theOperatorDeclaresTheInstanceCapturesTheFirstCachesOriginalAndStagesASecretFreeGeneration() throws Exception {
        requireValidatedTarget();
        declared = app.declare(solutionId, "e2e-multi-placement");
        long floor = stack.raiseGenerationFloor(declared.instance());
        ref = stack.placementRef(declared.instance(), declared.placement());
        original = stack.captureOriginal(app, ref);
        assertTrue(original.running(), "the first Cache must be running before the run starts: the restore returns to it");

        generation = app.stage(declared.instance(), stack.target.namespace() + "-multi", null);

        assertEquals(floor + 1, generation, "this run's first generation sits above its floor");
        evidence.record("2. Declare and stage", "instance " + declared.instance() + ", placement P1 " + declared.placement()
                + " on target " + stack.registeredTargetId() + "; generation floor " + floor + "; staged generation "
                + generation + " (solace.enabled=false)");
    }

    @Test
    @Order(3)
    void aSecondPlacementOnTheSameTargetNeedsItsOwnContainerAndIsRefusedOutsideTheEnvelope() throws Exception {
        requireValidatedTarget();
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);
        String path = "/api/dp/instances/" + declared.instance() + "/placements";
        long placementsBefore = placements();
        MultiPlacementTarget.Second second = multi.second();

        HttpResponse<String> outside = app.http.post(path, operator,
                addBody("not-" + stack.target.container(), second.configDirectory(), second));
        HttpResponse<String> sameContainer = app.http.post(path, operator,
                addBody(stack.target.container(), second.configDirectory(), second));

        assertEquals(422, outside.statusCode(), outside.body());
        assertTrue(outside.body().contains("containerName"), "the refusal names the field: " + outside.body());
        assertEquals(409, sameContainer.statusCode(), "the first Cache's container name is taken on this target: "
                + sameContainer.body());
        assertEquals(placementsBefore, placements(), "neither refusal wrote a placement");

        HttpResponse<String> added = app.http.post(path, operator,
                addBody(second.container(), second.configDirectory(), second));

        assertEquals(201, added.statusCode(), added.body());
        JsonNode instance = DeclareFixtures.MAPPER.readTree(added.body());
        secondPlacement = UUID.fromString(instance.at("/placements/1/placementId").asText());
        // W4A-1: the second Cache's ORIGINAL is captured the moment P2 exists and BEFORE any other assertion, so a failure
        // below can never leave P2 without a captured original. Nothing has written to P2's host yet.
        secondRef = stack.placementRef(declared.instance(), secondPlacement);
        secondOriginal = stack.captureOriginal(app, secondRef);
        assertEquals(2, instance.get("placements").size(), instance.toString());
        assertEquals(declared.placement().toString(), instance.at("/placements/0/placementId").asText());
        assertEquals(second.container(), instance.at("/placements/1/containerName").asText());
        assertEquals(stack.registeredTargetId().toString(), instance.at("/placements/1/targetId").asText(),
                "the same target as P1: one host, two containers");
        assertEquals(placementsBefore + 1, placements());
        assertEquals("rendered", app.ladder(declared.instance()).get("reached").asText(),
                "the new placement holds nothing, so the instance reads the lowest rung (OQ-8.10 (a); the drop from persisted "
                        + "is MultiPlacementEndToEndIT's, in process)");
        assertTrue(secondOriginal.running(), "the second Cache container must be running before the run starts: "
                + second.container() + " (SM deploys nothing; the customer's CI/CD started it)");
        assertNotNull(secondOriginal.keyedHash(), "the second Cache must have its own " + stack.target.fileName() + " in "
                + second.configDirectory() + " before the run: the restore returns to it, and the engine cannot restore an "
                + "absent file. Nothing has been written to either host.");
        evidence.record("3. addPlacement", "P2 " + secondPlacement + " on the same target: container " + second.container()
                + ", directory " + second.configDirectory() + ", host port " + second.hostPort() + " (health and metrics); "
                + "refused: a container outside the envelope " + outside.statusCode() + " naming containerName, the first "
                + "Cache's own container name " + sameContainer.statusCode() + "; the ladder reads rendered (nothing is applied "
                + "yet, and the new placement holds nothing)");
    }

    @Test
    @Order(4)
    void aManualFleetOverBothPlacementsWritesBothFilesAndEachMemberHasItsOwnRequestAndNotices() throws Exception {
        requireBothOriginals("write the two files");
        stack.noteWriteGeneration(generation);   // a fleet does not: the restore reads this generation's retained pre-image
        JsonNode landed = fleet("manual", generation, null);

        assertEquals("SUCCEEDED", landed.get("state").asText(), landed.toString());
        Set<UUID> applyIds = new LinkedHashSet<>();
        int ordinal = 0;
        for (UUID placement : List.of(declared.placement(), secondPlacement)) {
            JsonNode member = landed.get("members").get(ordinal++);
            assertEquals(placement.toString(), member.get("placementId").asText());
            assertEquals("admitted", member.get("disposition").asText());
            assertEquals("succeeded", member.get("settled").asText());
            assertEquals("STAGED", member.get("requestState").asText());
            UUID applyId = UUID.fromString(member.get("applyId").asText());
            applyIds.add(applyId);
            members.put(placement.equals(declared.placement()) ? "P1" : "P2", applyId);

            Map<String, Object> materialize = assertMaterializedWithTheKeyedHash(applyId);
            PlacementRef memberRef = stack.placementRef(declared.instance(), placement);
            assertEquals(materialize.get("config_hash"), stack.hostStatus(app, memberRef).observedHash(),
                    "the host of " + placement + " holds the intended file right now");
            JsonNode observation = app.observe(declared.instance(), applyId);
            assertEquals(Set.of(placement), stepPlacements(observation), "the member's steps carry its own placement");
            JsonNode outcome = assertApplyOutcomeNotice(events, declared.instance(), applyId, "STAGED", observation);
            assertEquals(placement.toString(), outcome.at("/entity/placementId").asText(),
                    "the notice names the member's own placement");
            assertEquals(1L, rolloutNotices(declared.instance(), applyId, "staged"), "one staged rollout per member");
        }
        assertEquals(2, applyIds.size(), "two members, two requests");
        assertEquals("persisted", app.ladder(declared.instance()).get("reached").asText(),
                "every live placement now holds generation " + generation);
        evidence.record("4. Fleet over both placements", "manual fleet " + landed.get("fleetId").asText()
                + " SUCCEEDED: " + members + " each STAGED, keyed hash on its own host equal to its intent's, one "
                + "apply-outcome and one staged rollout notice per member naming its own placement; the ladder reports "
                + "persisted. Both replicas are called " + stack.expectedReplicaRef() + ": the journal told them apart by "
                + "placement (BE-D44)");
    }

    @Test
    @Order(5)
    void stoppingTheSecondPlacementStopsOnlyItsContainer() throws Exception {
        if (skipUnlessDisruptive("stop P2 only")) {
            return;
        }
        requireBothOriginals("stop P2");
        stopped.add(secondRef);
        stoppedContainers.put(secondRef, multi.second().container());
        JsonNode landed = stop(secondPlacement);

        assertEquals("STOPPED", landed.get("state").asText(), landed.toString());
        assertEquals(Set.of(secondPlacement), stepPlacements(landed));
        assertEquals(Liveness.STOPPED, stack.hostStatus(app, secondRef).liveness(), "P2's container is down");
        assertEquals(Liveness.RUNNING, stack.hostStatus(app, ref).liveness(), "P1's container was not touched");
        evidence.record("5. Stop one placement", "stopInstance with placementId " + secondPlacement + " landed STOPPED; "
                + "its container is down, the first Cache still runs");
    }

    @Test
    @Order(6)
    void theInstanceWideStopHasAStepForEachLivePlacementWithItsOwnId() throws Exception {
        if (skipUnlessDisruptive("stop the whole instance")) {
            return;
        }
        requireBothOriginals("stop the whole instance");
        stopped.add(ref);
        stoppedContainers.put(ref, stack.target.container());
        JsonNode landed = stop(null);

        assertEquals("STOPPED", landed.get("state").asText(), landed.toString());
        assertFalse(landed.hasNonNull("placementId"), "instance-wide: " + landed);
        assertEquals(Set.of(declared.placement(), secondPlacement), stepPlacements(landed),
                "a STOP step for each live placement, each with its own placementId (BE-D44, BE-D46)");
        assertEquals(Liveness.STOPPED, stack.hostStatus(app, ref).liveness());
        assertEquals(Liveness.STOPPED, stack.hostStatus(app, secondRef).liveness());
        evidence.record("6. Stop the instance", "stopInstance with no placementId landed STOPPED with a step for each of "
                + "the two live placements; both containers are down");
    }

    @Test
    @Order(7)
    void aRestartAllFleetBringsBothContainersBackOnTheNewGeneration() throws Exception {
        if (skipUnlessDisruptive("restart both containers")) {
            return;
        }
        requireBothOriginals("restart both containers");
        restartedBoth = true;
        containerRestarted = true;   // set before the submit: even a failed restart leaves containers to put back
        restartGeneration = app.stage(declared.instance(), stack.target.namespace() + "-restart", null);

        JsonNode landed = fleet("restart-all", restartGeneration, "L2 live gate");

        assertEquals("SUCCEEDED", landed.get("state").asText(), landed.toString());
        for (JsonNode member : landed.get("members")) {
            assertEquals("APPLIED", member.get("requestState").asText(), member.toString());
            UUID applyId = UUID.fromString(member.get("applyId").asText());
            Map<String, Object> materialize = assertMaterializedWithTheKeyedHash(applyId);
            PlacementRef memberRef = stack.placementRef(declared.instance(), UUID.fromString(member.get("placementId").asText()));
            RuntimeStatus status = stack.hostStatus(app, memberRef);
            assertEquals(Liveness.RUNNING, status.liveness(), "restarted: running again");
            assertEquals(materialize.get("config_hash"), status.observedHash());
        }
        stopped.clear();
        evidence.record("7. Restart both", "restart-all fleet " + landed.get("fleetId").asText() + " over generation "
                + restartGeneration + " SUCCEEDED, both members APPLIED; both containers run, each file equal to its "
                + "intent's keyed hash");
    }

    @Test
    @Order(8)
    void bothFilesAreRestoredToTheirOriginalKeyedHashesBeforeP2IsRetired() throws Exception {
        requireValidatedTarget();
        List<String> first = stack.restoreOriginal(app, ref, original, restartedBoth);
        List<String> second = restoreSecond();
        containerRestarted = false;   // restored and restarted here: the base class's final restore need not restart again
        secondPutBack = second.stream().noneMatch(l -> l.startsWith("MANUAL") || l.contains("DIFFERS from the original"));

        for (String line : first) {
            assertFalse(line.startsWith("MANUAL") || line.contains("DIFFERS from the original"), "P1: " + line);
        }
        for (String line : second) {
            assertFalse(line.startsWith("MANUAL") || line.contains("DIFFERS from the original"), "P2: " + line);
        }
        evidence.record("8. Restore", "P1 and P2 each hold their ORIGINAL keyed hash again (the engine's own restore from "
                + "the retained pre-image of generation " + generation + ")" + (restartedBoth
                        ? ", and both containers were restarted so they RUN the original configuration" : ""));
    }

    @Test
    @Order(9)
    void retiringTheAddedPlacementLeavesItsContainerAloneAndTheInstanceWithOneLivePlacement() throws Exception {
        requireValidatedTarget();
        // W4A-3: once P2 is retired SM can no longer resolve it ('placement.unresolvable'), so it can no longer start or
        // restore it. The runbook's rule (a restore through SM happens BEFORE the retire) is the harness's too.
        if (!secondPutBack || !stopped.isEmpty()) {
            evidence.skip("9. retirePlacement", "NOT RUN: P2 must be restored and started before it is retired, and "
                    + (secondPutBack ? "" : "its file was not put back (step 8); ")
                    + (stopped.isEmpty() ? "" : "this run's stopped containers " + stoppedContainers.values()
                            + " are not running again; ")
                    + "retiring would leave SM unable to act on it. The end-of-run safety net restores what it can; "
                    + "read the evidence's MANUAL lines");
            return;
        }
        int writes = app.adapter.configWrites();
        int restarts = app.adapter.restarts();
        int stops = app.adapter.stops();
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> retired = delete("/api/dp/instances/" + declared.instance() + "/placements/"
                + secondPlacement + "?workload=leave-running", operator);

        assertEquals(200, retired.statusCode(), retired.body());
        secondRetired = true;
        JsonNode body = DeclareFixtures.MAPPER.readTree(retired.body());
        assertEquals("left-running", body.get("workload").asText());
        assertEquals(1, body.at("/instance/placements").size());
        assertEquals(declared.placement().toString(), body.at("/instance/placements/0/placementId").asText());
        assertEquals(writes, app.adapter.configWrites(), "retiring wrote nothing to any host");
        assertEquals(restarts, app.adapter.restarts(), "and restarted nothing");
        assertEquals(stops, app.adapter.stops(), "and stopped nothing: the container is the customer's");
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND placement_id = :p"
                + " AND retired_at IS NOT NULL", Map.of("t", stack.organization(), "p", secondPlacement)),
                "the row is kept forever");
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i"
                + " AND retired_at IS NULL", Map.of("t", stack.organization(), "i", declared.instance())),
                "the database holds one live placement again");
        evidence.record("9. retirePlacement", "P2 retired with workload=leave-running: 200, the instance has one live "
                + "placement; host-side counters unchanged (config writes " + writes + ", restarts " + restarts
                + ", stops " + stops + "). Remove the second container by your own means; SM never does");
    }

    @Test
    @Order(10)
    void theSummary() {
        requireValidatedTarget();
        assumeTrue(declared != null && secondPlacement != null, "no second placement was added");
        evidence.record("10. Summary", "instance " + declared.instance() + ": P1 " + declared.placement() + ", P2 "
                + secondPlacement + (secondRetired ? " (retired)" : " (NOT retired: see step 9)") + "; generations " + generation
                + (restartedBoth ? " and " + restartGeneration : "") + "; request ids " + members);
    }

    // ----------------------------------------------------------------------------------------------- the safety net

    /** Starts what this run stopped and restores a second file the scenario did not reach, whatever else failed. */
    @AfterAll
    void tearDownSecondPlacement() {
        List<String> problems = new ArrayList<>();
        if (app != null) {
            // First, before anything else can make a placement unresolvable: start what this run stopped (W4A-3).
            for (PlacementRef target : new ArrayList<>(stopped)) {
                String container = stoppedContainers.getOrDefault(target, "<unknown container>");
                try {
                    RuntimeAdapter adapter = app.adapter.delegate();
                    TargetReplica replica = adapter.replicas(target).get(0);
                    adapter.startOrUpdate(replica, 0);   // a plain start of a container this run stopped
                    stopped.remove(target);
                    evidence.record("Restore", "container " + container + " (placement " + target.placement().value()
                            + ") was started again");
                } catch (RuntimeException e) {
                    String line = "MANUAL: this run stopped the container " + container + " (placement "
                            + target.placement().value() + ") and could not start it again (" + e.getClass().getSimpleName()
                            + "): run `docker start " + container + "` on the host";
                    evidence.record("Restore", line);
                    problems.add(line);
                }
            }
            if (!secondRestored) {
                for (String line : restoreSecond()) {
                    if (line.startsWith("MANUAL") || line.contains("DIFFERS from the original")) {
                        problems.add(line);
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("THIS RUN LEFT THE SECOND PLACEMENT'S HOST UNRESTORED: " + problems
                    + " (the evidence file has the instruction)");
        }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * Restores P2's file (once) and, when the run restarted the containers, restarts P2's. Its recovery instructions name
     * P2's OWN directory and file (W4A-2), never the first Cache's. A write that happened with no captured original is a
     * MANUAL line, never "nothing was written" (W4A-1).
     */
    private List<String> restoreSecond() {
        secondRestored = true;
        if (secondRef == null) {
            List<String> none = List.of("no second placement was added, so nothing was written to a second host");
            none.forEach(l -> evidence.record("Restore of the second placement's configuration", l));
            return none;
        }
        if (secondOriginal == null) {
            String line = stack.hasWriteGeneration()
                    ? "MANUAL: P2's original was never captured, yet this run recorded a write; check "
                            + multi.second().configDirectory() + "/" + stack.target.fileName() + " against the oldest ."
                            + stack.target.fileName() + ".sm-preimage-<generation> in that directory"
                    : "no original was captured and this run recorded no write, so nothing was written to the second host";
            evidence.record("Restore of the second placement's configuration", line);
            return List.of(line);
        }
        return stack.restoreOriginal(app, secondRef, secondOriginal, restartedBoth, multi.second().configDirectory(),
                stack.target.fileName());
    }

    /** Skips (not fails) a step when step 1 refused the second Cache's target: that one failure is the whole story. */
    private void requireValidatedTarget() {
        assumeTrue(multi != null, "step 1 refused the second Cache's target: nothing else runs");
    }

    /** A step that would write or stop must not run unless BOTH hosts' originals were captured and exist (W4A-1, W4A-2). */
    private void requireBothOriginals(String what) {
        requireValidatedTarget();
        if (original == null || secondOriginal == null || secondOriginal.keyedHash() == null) {
            fail("refusing to " + what + ": the originals of both Caches must be captured before anything is written, and "
                    + "step 2 or 3 did not capture them (first: " + (original == null ? "missing" : "captured")
                    + ", second: " + (secondOriginal == null ? "missing" : secondOriginal.keyedHash() == null
                            ? "its file is absent" : "captured") + "). Nothing was written or stopped");
        }
    }

    private boolean skipUnlessDisruptive(String what) {
        requireValidatedTarget();
        if (multi.disruptive()) {
            return false;
        }
        evidence.skip(what, "this run does not stop or restart a container: that needs sm.walkthrough.multi.disruptive=yes "
                + "(SM_E2E_CONFIRM_DISRUPT=yes in the script)");
        return true;
    }

    private long placements() {
        return stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i",
                Map.of("t", stack.organization(), "i", declared.instance()));
    }

    private ObjectNode addBody(String container, String directory, MultiPlacementTarget.Second second) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("targetId", stack.registeredTargetId().toString());
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", container);
        placement.put("configDirectory", directory);
        placement.put("containerConfigPath", stack.target.containerConfigPath());
        placement.put("fileName", stack.target.fileName());
        placement.put("healthPort", second.healthPort());
        ObjectNode ports = placement.putObject("metricsPorts");
        second.metricsPorts().forEach(ports::put);
        return body;
    }

    /** A fleet whose members are the instance's two placements, in order P1, P2; waits for it to leave {@code RUNNING}. */
    private JsonNode fleet(String strategy, long gen, String outageConfirmedBy) throws Exception {
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("solutionId", solutionId.toString());
        ArrayNode list = body.putArray("members");
        for (UUID placement : List.of(declared.placement(), secondPlacement)) {
            list.addObject().put("instanceId", declared.instance().toString()).put("placementId", placement.toString())
                    .put("generation", gen);
        }
        body.put("strategy", strategy);
        body.put("batchSize", 1);
        body.put("onFailure", "halt-remaining");
        if (outageConfirmedBy != null) {
            body.put("outageConfirmedBy", outageConfirmedBy);
        }
        HttpResponse<String> accepted = app.http.post("/api/dp/fleet-applies", operator, body);
        assertEquals(202, accepted.statusCode(), "submitFleetApply: " + accepted.statusCode() + " " + accepted.body());
        String fleetId = DeclareFixtures.MAPPER.readTree(accepted.body()).get("fleetId").asText();
        Instant deadline = Instant.now().plus(FLEET);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = app.http.get("/api/dp/fleet-applies/" + fleetId, operator);
            assertEquals(200, response.statusCode(), "observeFleetApply: " + response.statusCode());
            last = DeclareFixtures.MAPPER.readTree(response.body());
            if (!last.get("state").asText().equals("RUNNING")) {
                return last;
            }
            Thread.sleep(500);
        }
        return fail("fleet " + fleetId + " never left RUNNING within " + FLEET + "; last seen " + last);
    }

    /** {@code stopInstance}, for one placement or (null) the whole instance; waits for the terminal state. */
    private JsonNode stop(UUID placement) throws Exception {
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        if (placement != null) {
            body.put("placementId", placement.toString());
        }
        HttpResponse<String> accepted = app.http.post("/api/dp/instances/" + declared.instance() + "/stop", operator, body);
        assertEquals(202, accepted.statusCode(), "stopInstance: " + accepted.statusCode() + " " + accepted.body());
        UUID applyId = UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText());
        return app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);
    }

    private static Set<UUID> stepPlacements(JsonNode observation) {
        Set<UUID> out = new LinkedHashSet<>();
        observation.get("steps").forEach(s -> {
            if (s.hasNonNull("placementId")) {
                out.add(UUID.fromString(s.get("placementId").asText()));
            }
        });
        return out;
    }

    /** {@code DeclareFixtures.Client} has no DELETE (and is shared); this is the one the retire needs. */
    private HttpResponse<String> delete(String path, Login login) throws java.io.IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(DeclareFixtures.Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}
