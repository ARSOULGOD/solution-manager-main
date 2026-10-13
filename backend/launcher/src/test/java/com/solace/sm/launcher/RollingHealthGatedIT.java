package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.9: a {@code rolling-health-gated} apply is SERVED in the production boot, end to end through the real
 * HTTP layer, gateway, data plane and durable worker. Only the host is local (an in-memory runtime that publishes a
 * health signal, as the placement's declared probe would). The declared instance is Cache's, whose package declares an
 * {@code actuator-health} probe of strength {@code liveness}: the gated apply passes its gate, its post-restart
 * read-back is journalled as a {@code VERIFY} step, and the observation says which strength the gate proved - and
 * says nothing of the kind for a manual apply of the same instance.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
class RollingHealthGatedIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

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
        world = DeclareFixtures.seed(provisioned.db().ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static ObjectNode submitBody(long generation, String strategy, UUID placementId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", strategy);
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

    @Test
    void rollingHealthGatedIsServedInTheProductionBootAndReportsTheStrengthItProved() throws Exception {
        Client http = new Client(port);
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        adapter.healthSignal = true;   // the declared probe publishes a signal for this instance's placement

        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "gated-cache", "sm-gated", 9201);
        long first = ApplyItSupport.stage(http, declared.instance(), declared.version(), "orders");
        String path = "/api/dp/instances/" + declared.instance();

        // ---- the gated apply: gate passes, read-back verified, strength reported ------------------------------------
        HttpResponse<String> accepted = http.post(path + "/apply", operator,
                submitBody(first, "rolling-health-gated", declared.placement()));
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, declared.instance(),
                body(accepted).get("applyId").asText(), Duration.ofSeconds(30));

        assertEquals("APPLIED", landed.get("state").asText(), landed.toString());
        JsonNode lifecycle = stepNamed(landed, "lifecycle");
        assertEquals("succeeded", lifecycle.get("outcome").asText());
        assertEquals("liveness", lifecycle.get("gateProved").asText(),
                "Cache's package declares liveness; that is what the passed gate is reported to have proved");
        JsonNode verify = stepNamed(landed, "verify");
        assertEquals("succeeded", verify.get("outcome").asText(), "the post-restart read-back was journalled");
        for (String other : new String[] {"preflight", "materialize", "verify"}) {
            assertFalse(stepNamed(landed, other).has("gateProved"), other + " proves no gate");
        }

        // ---- the same instance under manual: nothing restarted, nothing proved --------------------------------------
        long second = ApplyItSupport.stage(http, declared.instance(),
                body(http.get(path + "/designer", operator)).get("stateVersion").asLong(), "billing");
        HttpResponse<String> manual = http.post(path + "/apply", operator,
                submitBody(second, "manual", declared.placement()));
        assertEquals(202, manual.statusCode(), manual.body());
        JsonNode staged = ApplyItSupport.awaitTerminal(http, operator, declared.instance(),
                body(manual).get("applyId").asText(), Duration.ofSeconds(30));

        assertEquals("STAGED", staged.get("state").asText(), staged.toString());
        for (JsonNode step : staged.get("steps")) {
            assertFalse(step.has("gateProved"), "a manual apply has no gate: " + step);
        }
        assertTrue(staged.get("steps").size() >= 2);
    }
}
