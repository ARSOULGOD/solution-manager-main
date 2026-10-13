package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter.Fault;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.8 in the production boot, over real HTTP: the DURABLE WORKER's poll runs the reconciler, so an apply that was
 * cut off is reconciled with nobody calling anything. Only the host is local (an in-memory runtime that can be made to lose
 * its start baseline, which is how a restart-strategy apply lands {@code APPLY_INDETERMINATE} before the verb is issued).
 *
 * <p>Two instances, two outcomes: the file is the intended one and the replica was not restarted, so the reconciler
 * restarts it once, gates it, verifies it and lands the request {@code APPLIED}; and a file someone else wrote, which the
 * reconciler never touches, gives up, and says so through {@code observeApply} as {@code reconcileReason}.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
class IndeterminateReconciliationIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String FOREIGN = "sha256:" + "f".repeat(64);

    private static World world;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        registry.add("sm.dp.apply.reconcile.initial-backoff", () -> "1s");
        registry.add("sm.dp.apply.reconcile.max-backoff", () -> "2s");
        world = DeclareFixtures.seed(provisioned.db().ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static ObjectNode submitBody(long generation, UUID placementId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", "rolling-health-gated");
        body.put("placementId", placementId.toString());
        return body;
    }

    private static JsonNode stepNamed(JsonNode observation, String name) {
        for (JsonNode step : observation.get("steps")) {
            if (step.get("step").asText().equals(name)) {
                return step;
            }
        }
        throw new AssertionError("no " + name + " step in " + observation);
    }

    private JsonNode observe(Client http, Login login, UUID instance, String applyId) throws Exception {
        HttpResponse<String> response = http.get("/api/dp/instances/" + instance + "/apply/observe?applyId=" + applyId, login);
        assertEquals(200, response.statusCode(), response.body());
        return body(response).get("items").get(0);
    }

    private JsonNode await(Client http, Login login, UUID instance, String applyId, Predicate<JsonNode> done)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = observe(http, login, instance, applyId);
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        fail("never reached the expected state for " + applyId + "; last seen " + last);
        throw new AssertionError("unreachable");
    }

    /** Declares, stages and submits a gated apply that lands APPLY_INDETERMINATE because the start baseline is unreadable. */
    private String cutOffApply(Client http, Login operator, String name, String container, int port) throws Exception {
        adapter.healthSignal = true;
        adapter.newHostFault = Fault.BASELINE_UNREADABLE;
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, name, container, port);
        long generation = ApplyItSupport.stage(http, declared.instance(), declared.version(), "orders");
        HttpResponse<String> accepted = http.post("/api/dp/instances/" + declared.instance() + "/apply", operator,
                submitBody(generation, declared.placement()));
        assertEquals(202, accepted.statusCode(), accepted.body());
        String applyId = body(accepted).get("applyId").asText();
        pending = declared;
        JsonNode indeterminate = await(http, operator, declared.instance(), applyId,
                o -> "APPLY_INDETERMINATE".equals(o.get("state").asText()) && !o.get("steps").isEmpty());
        assertFalse(indeterminate.get("terminal").asBoolean(), "APPLY_INDETERMINATE is not terminal");
        return applyId;
    }

    private ApplyItSupport.Declared pending;

    @Test
    void theWorkersPollReconcilesAnIntendedFileThatWasNeverRestartedAndLandsItApplied() throws Exception {
        Client http = new Client(port);
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String applyId = cutOffApply(http, operator, "reconciled-cache", "sm-reconciled", 9301);
        InMemoryRuntimeAdapter host = adapter.host(pending.placement());
        TargetReplica replica = host.replicas().get(0);
        assertEquals(0, host.restarts(), "the cut: the file was written and nothing restarted");
        assertTrue(host.hashOf(replica) != null, "and the intended file is on the host");

        host.fault(Fault.NONE);   // the host recovers; the worker's poll does the rest

        JsonNode landed = await(http, operator, pending.instance(), applyId, o -> o.get("terminal").asBoolean());

        assertEquals("APPLIED", landed.get("state").asText(), landed.toString());
        assertEquals(1, host.restarts(), "exactly one restart: the reconciler re-ran the lifecycle step only");
        assertEquals(0, host.rematerializeCalls(), "and wrote nothing");
        assertEquals("succeeded", stepNamed(landed, "lifecycle").get("outcome").asText());
        assertEquals("liveness", stepNamed(landed, "lifecycle").get("gateProved").asText(),
                "the re-run restart was gated by the package's declared probe");
        assertEquals("succeeded", stepNamed(landed, "verify").get("outcome").asText());
        assertFalse(landed.has("reconcileReason"), "no give-up to report");
    }

    @Test
    void aFileSomebodyElseWroteIsNeverTouchedAndTheGiveUpIsObservable() throws Exception {
        Client http = new Client(port);
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String applyId = cutOffApply(http, operator, "foreign-cache", "sm-foreign", 9302);
        InMemoryRuntimeAdapter host = adapter.host(pending.placement());
        TargetReplica replica = host.replicas().get(0);
        host.competingWrite(replica, FOREIGN);
        host.fault(Fault.NONE);

        JsonNode given = await(http, operator, pending.instance(), applyId, o -> o.has("reconcileReason"));

        assertEquals("reconcile.foreign.content", given.get("reconcileReason").asText());
        assertEquals("APPLY_INDETERMINATE", given.get("state").asText(), "it stays indeterminate: an operator resolves it");
        assertEquals(FOREIGN, host.hashOf(replica), "a human's file is never reverted");
        assertEquals(0, host.restarts());
        assertEquals(0, host.rematerializeCalls());
    }
}
