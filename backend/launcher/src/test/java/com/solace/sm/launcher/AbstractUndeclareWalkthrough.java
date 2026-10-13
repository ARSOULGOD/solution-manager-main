package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.DeclareFixtures.Login;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plan 012 T5, live gate {@code L5} ({@code OR-110}: the E2E host's second Cache): declare an instance on the SECOND Cache
 * container, write and restore its configuration once (so SM's hidden {@code .sm-preimage-*} and {@code .sm-fp-*} files
 * exist beside it), then <b>undeclare it</b> and prove SM touched nothing on the host. The first Cache is never declared,
 * written, stopped or restarted.
 *
 * <p>What this class proves, through the real REST surface and the real rows: the DELETE answers {@code 200} with
 * {@code workload: left-running}; the instance is {@code retired}, its placement retired and its record closed and attributed;
 * the control plane's node is retired and audited; a repeated DELETE and every read is {@code 404}; and the host-side counters
 * of the production adapter (config writes, restarts, stops) and the request table did not move across the DELETE, so SM made
 * no host call at all. <b>What it cannot show is Docker's own view</b> - that the container's id and start time are the same
 * afterwards - because once the placement is retired SM can no longer resolve it ({@code placement.unresolvable}); that is what
 * {@code run-e2e-undeclare.sh} measures independently, with {@code docker inspect} over its own SSH session before and after,
 * and the cleanup of the hidden files (the runbook's commands) with a listing before and after.
 *
 * <h2>What it writes, and what it puts back</h2>
 * The second Cache's configuration file is overwritten once (its directory also receives the hidden retained files) and put
 * back through the engine's own restore BEFORE the undeclare, because SM cannot act on the placement afterwards. The ORIGINAL
 * keyed hash is read first and held in memory and in the evidence. If anything fails before the undeclare, the base class's
 * tear-down restores it; if the undeclare happened, the file was already restored. The hidden files stay until the script's
 * cleanup (or the operator's), by design (U-C).
 */
@Timeout(value = 30, unit = java.util.concurrent.TimeUnit.MINUTES)
abstract class AbstractUndeclareWalkthrough extends WalkthroughSupport {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(DeclareFixtures.Client.REQUEST_TIMEOUT).build();

    UndeclareTarget undeclare;
    private long generation;
    private UUID applyId;
    private RuntimeStatus beforeUndeclare;
    private PlacementRef undeclaredRef;
    private boolean putBack;
    private boolean undeclared;

    /** The second Cache this run declares, validated against the first. Live: from properties. Rehearsal: synthetic. */
    abstract UndeclareTarget undeclareTarget(WalkthroughTarget base);

    @Test
    @Order(1)
    void theBootstrapAdminLoadsThePackageAndTheSecondCachesTargetIsValidBeforeAnythingTouchesTheHost() throws Exception {
        // A malformed second Cache is refused HERE, once; every later step is skipped rather than run against a host it was
        // never validated for.
        undeclare = undeclareTarget(stack.target);
        JsonNode loaded = app.loadPackageAsAdmin();
        solutionId = UUID.fromString(loaded.get("solutionId").asText());
        evidence.record("Setup", "L5: " + undeclare + "; the first Cache (" + stack.target.container() + ") is not touched");
        evidence.record("1. Package", "the cache package is loaded (solution " + solutionId + ")");
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredOnTheSecondCacheItsOriginalIsCapturedAndAGenerationIsStaged() throws Exception {
        requireValidatedTarget();
        UndeclareTarget.Second second = undeclare.second();
        ObjectNode body = app.declareBody(solutionId, "e2e-undeclare", second.configDirectory());
        ObjectNode placement = (ObjectNode) body.get("placement");
        placement.put("containerName", second.container());
        placement.put("healthPort", second.healthPort());
        ObjectNode ports = placement.putObject("metricsPorts");
        second.metricsPorts().forEach(ports::put);

        HttpResponse<String> response = app.declareRaw(DeclareFixtures.OPERATOR_ONE, body);

        assertEquals(201, response.statusCode(), response.body());
        JsonNode instance = DeclareFixtures.MAPPER.readTree(response.body());
        declared = new Declared(UUID.fromString(instance.get("instanceId").asText()),
                UUID.fromString(instance.at("/placements/0/placementId").asText()), instance.get("stateVersion").asLong());
        long floor = stack.raiseGenerationFloor(declared.instance());
        ref = stack.placementRef(declared.instance(), declared.placement());
        original = stack.captureOriginal(app, ref);
        assertTrue(original.running(), "the second Cache container must be running before the run starts: "
                + second.container() + " (SM deploys nothing; the customer's CI/CD started it)");
        assertNotNull(original.keyedHash(), "the second Cache must have its own " + stack.target.fileName() + " in "
                + second.configDirectory() + " before the run: the restore returns to it");

        generation = app.stage(declared.instance(), stack.target.namespace() + "-undeclare", null);

        assertEquals(floor + 1, generation, "this run's first generation sits above its floor");
        evidence.record("2. Declare and stage", "instance " + declared.instance() + ", placement " + declared.placement()
                + " on the registered target, container " + second.container() + ", directory " + second.configDirectory()
                + ", host port " + second.hostPort() + "; generation floor " + floor + ", staged generation " + generation);
    }

    @Test
    @Order(3)
    void aManualApplyWritesTheSecondCachesFileSoSmsHiddenFilesExistBesideIt() throws Exception {
        requireValidatedTarget();
        requireOriginal("write the file");
        applyId = app.submit(declared.instance(), declared.placement(), generation, "manual");

        JsonNode landed = app.awaitTerminal(declared.instance(), applyId, MANUAL_APPLY);

        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        Map<String, Object> materialize = assertMaterializedWithTheKeyedHash(applyId);
        RuntimeStatus status = stack.hostStatus(app, ref);
        assertEquals(materialize.get("config_hash"), status.observedHash(), "the host holds the intended file right now");
        assertEquals(Liveness.RUNNING, status.liveness(), "a manual apply restarts nothing");
        evidence.record("3. Manual apply", "applyId " + applyId + " landed STAGED; the host file holds the intended keyed hash; "
                + "SM now keeps .<file>.sm-preimage-" + generation + " and .<file>.sm-fp-" + generation + " beside it");
    }

    @Test
    @Order(4)
    void theOriginalFileIsRestoredBeforeTheUndeclareBecauseSmCannotActOnAReleasedPlacement() throws Exception {
        requireValidatedTarget();
        requireOriginal("restore the file");

        List<String> report = stack.restoreOriginal(app, ref, original, false);

        for (String line : report) {
            assertFalse(line.startsWith("MANUAL") || line.contains("DIFFERS from the original"), line);
        }
        putBack = true;
        beforeUndeclare = stack.hostStatus(app, ref);
        assertEquals(Liveness.RUNNING, beforeUndeclare.liveness());
        assertEquals(original.keyedHash(), beforeUndeclare.observedHash());
        evidence.record("4. Restore", "the second Cache's file holds its ORIGINAL keyed hash again (the engine's own restore "
                + "from the pre-image of generation " + generation + "); container start baseline seen by SM just before the "
                + "undeclare: " + (beforeUndeclare.startBaseline().isPresent()
                        ? beforeUndeclare.startBaseline().get().startedAt() : "unreadable"));
    }

    @Test
    @Order(5)
    void undeclareRefusesWithoutTheWorkloadWordAndThenReleasesTheInstanceWithoutTouchingTheHost() throws Exception {
        requireValidatedTarget();
        if (!putBack) {
            evidence.skip("5. undeclareInstance", "NOT RUN: the second Cache's file was not put back (step 4) and SM cannot act "
                    + "on the placement once it is released. The end-of-run safety net restores what it can; read the "
                    + "evidence's MANUAL lines");
            return;
        }
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);
        String path = "/api/dp/instances/" + declared.instance();
        int writes = app.adapter.configWrites();
        int restarts = app.adapter.restarts();
        int stops = app.adapter.stops();
        long requests = stack.count("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i",
                Map.of("t", stack.organization(), "i", declared.instance()));
        PlacementRef placementBefore = ref;

        assertEquals(400, delete(path, operator).statusCode(), "the request must say what happens to the container");
        assertEquals(400, delete(path + "?workload=stop", operator).statusCode());
        HttpResponse<String> response = delete(path + "?workload=leave-running", operator);

        assertEquals(200, response.statusCode(), response.body());
        undeclared = true;
        undeclaredRef = placementBefore;
        ref = null;            // SM cannot resolve a released placement: the base class's tear-down must not try to restore it
        original = null;
        JsonNode body = DeclareFixtures.MAPPER.readTree(response.body());
        assertEquals(declared.instance().toString(), body.get("instanceId").asText());
        assertEquals("left-running", body.get("workload").asText());
        assertEquals(1, body.get("placements").size());
        assertEquals(declared.placement().toString(), body.at("/placements/0/placementId").asText());
        assertTrue(body.get("stateVersion").asLong() > declared.version());
        // SM made no host call: the production adapter's counters and the request table did not move
        assertEquals(writes, app.adapter.configWrites(), "undeclare wrote nothing to any host");
        assertEquals(restarts, app.adapter.restarts(), "and restarted nothing");
        assertEquals(stops, app.adapter.stops(), "and stopped nothing: the container is the customer's");
        assertEquals(requests, stack.count("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", stack.organization(), "i", declared.instance())),
                "no stop and no apply was admitted");
        // the rows: nothing deleted, everything in a terminal state, attributed
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i"
                + " AND lifecycle = 'retired'", Map.of("t", stack.organization(), "i", declared.instance())));
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND placement_id = :p"
                + " AND retired_at IS NOT NULL", Map.of("t", stack.organization(), "p", declared.placement())),
                "the row is kept, marked retired");
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND instance_id = :i AND placement_id IS NULL AND outcome = 'retired'"
                + " AND workload_disposition = 'leave-running'", Map.of("t", stack.organization(), "i", declared.instance())),
                "one closed, attributed record");
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_control.instance WHERE instance_id = :i"
                + " AND retired_at IS NOT NULL", Map.of("i", declared.instance())), "the control plane's node is retired");
        assertEquals(1L, stack.count("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'instance'"
                + " AND audit_operation = 'INSTANCE_UNDECLARED' AND row_key ->> 'instance_id' = :i",
                Map.of("i", declared.instance().toString())), "and audited once");
        evidence.record("5. undeclareInstance", "200 with workload left-running; instance retired, its placement retired, the "
                + "record closed, the control plane's node retired and audited; host-side counters unchanged (config writes "
                + writes + ", restarts " + restarts + ", stops " + stops + ", requests " + requests + "). The container is "
                + undeclare.second().container() + ": its id and start time are checked by run-e2e-undeclare.sh with "
                + "docker inspect, before and after");
    }

    @Test
    @Order(6)
    void aReleasedInstanceIsNotFoundAnywhereAndSmCanNoLongerResolveItsPlacement() throws Exception {
        requireValidatedTarget();
        assumeTrue(undeclared, "the instance was not undeclared");
        Login operator = app.login(DeclareFixtures.OPERATOR_ONE);
        String path = "/api/dp/instances/" + declared.instance();

        assertEquals(404, delete(path + "?workload=leave-running", operator).statusCode(), "a repeated DELETE is 404");
        assertEquals(404, app.http.get(path, operator).statusCode());
        assertEquals(404, app.http.get(path + "/health", operator).statusCode());
        String resolution;
        try {
            stack.hostStatus(app, undeclaredRef);
            resolution = "SM still resolved the placement (an in-memory host); the live host is checked by the script";
        } catch (RuntimeException e) {
            resolution = "SM can no longer resolve the placement (" + e.getClass().getSimpleName() + "), as designed";
        }
        evidence.record("6. After", "repeated DELETE 404, getInstance 404, getHealth 404; " + resolution);
    }

    @Test
    @Order(7)
    void theSummary() {
        requireValidatedTarget();
        assumeTrue(declared != null, "no instance was declared");
        evidence.record("7. Summary", "instance " + declared.instance() + " on the second Cache " + undeclare.second().container()
                + (undeclared ? " undeclared" : " NOT undeclared: see step 5") + "; generation " + generation
                + "; hidden files left beside " + stack.target.fileName() + " in " + undeclare.second().configDirectory()
                + ": .<file>.sm-preimage-" + generation + " and .<file>.sm-fp-" + generation + " (the runbook's cleanup removes "
                + "them; run-e2e-undeclare.sh lists them before and after)");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void requireValidatedTarget() {
        assumeTrue(undeclare != null, "step 1 refused the second Cache's target: nothing else runs");
    }

    /** A step that would write must not run unless the second Cache's original was captured and its file exists. */
    private void requireOriginal(String what) {
        if (original == null || original.keyedHash() == null) {
            org.junit.jupiter.api.Assertions.fail("refusing to " + what + ": the second Cache's original must be captured "
                    + "before anything is written, and step 2 did not capture it. Nothing was written");
        }
    }

    /** {@code DeclareFixtures.Client} has no DELETE (and is shared); this is the one the undeclare needs. */
    private HttpResponse<String> delete(String path, Login login) throws java.io.IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(DeclareFixtures.Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}
