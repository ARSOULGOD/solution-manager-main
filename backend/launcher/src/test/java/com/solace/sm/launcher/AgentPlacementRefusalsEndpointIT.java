package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.api.readback.RuntimeReadBackService;
import com.solace.sm.launcher.AgentTargetFixtures.AgentTarget;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 015 T3.3 (Global constraint 8, design D-C2): the operations that reach a host over SSH answer a coded
 * {@code 501 AGENT_OPERATION_UNSUPPORTED} on an AGENT placement - log level (read and change), accept-runtime, restore and
 * rollback - through the real application over real HTTP. The proof that nothing was attempted is a counting spy on the data
 * plane's host-reaching services (every SSH path starts in one of them): zero calls for the agent placement, and the same requests
 * on the SSH placement of the same instance DO reach them, so the spy is known to see.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentPlacementRefusalsEndpointIT.Spy.class)
class AgentPlacementRefusalsEndpointIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    /** Counts every call to the data plane's host-reaching services; the proxy forwards unchanged. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Spy {
        @Bean
        static BeanPostProcessor hostReachingServiceSpy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof LogService || bean instanceof LogLevelService || bean instanceof RuntimeReadBackService
                            || bean instanceof ApplyService) {
                        Class<?>[] interfaces = bean.getClass().getInterfaces();
                        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces, (proxy, method, args) -> {
                            CALLS.computeIfAbsent(method.getName(), k -> new AtomicInteger()).incrementAndGet();
                            try {
                                return method.invoke(bean, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                    }
                    return bean;
                }
            };
        }
    }

    private static UUID instance;
    private static UUID sshPlacement;
    private static UUID agentPlacement;

    @BeforeAll
    static void noop() {
    }

    private Client client() {
        return new Client(port);
    }

    private void declareOnce() throws Exception {
        if (instance != null) {
            return;
        }
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        UUID solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 8081));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode json = MAPPER.readTree(declared.body());
        instance = UUID.fromString(json.get("instanceId").asText());
        sshPlacement = UUID.fromString(json.at("/placements/0/placementId").asText());
        AgentTarget agent = AgentTargetFixtures.seed(ownerJdbc, world, "edge-host-refusals");
        agentPlacement = AgentTargetFixtures.placementOn(ownerJdbc, world.organization(), instance, agent.targetId(), "sm-edge-1");
    }

    private static int calls(String method) {
        AtomicInteger n = CALLS.get(method);
        return n == null ? 0 : n.get();
    }

    private void assertRefused(HttpResponse<String> response, String what) throws Exception {
        assertEquals(501, response.statusCode(), what + ": " + response.body());
        assertEquals("AGENT_OPERATION_UNSUPPORTED", MAPPER.readTree(response.body()).get("code").asText(), what);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), what);
    }

    private static ObjectNode placementBody(UUID placement) {
        return MAPPER.createObjectNode().put("placementId", placement.toString());
    }

    @Test
    void everyHostReachingOperationOnAnAgentPlacementAnswers501AndTouchesNoService() throws Exception {
        declareOnce();
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        CALLS.clear();

        assertRefused(http.get("/api/dp/instances/" + instance + "/log-levels/cache-engine?placementId=" + agentPlacement, admin),
                "getLogLevel");
        ObjectNode level = placementBody(agentPlacement).put("level", "DEBUG").put("mode", "config");
        assertRefused(http.post("/api/dp/instances/" + instance + "/log-levels/cache-engine", admin, level), "setLogLevel");
        assertRefused(http.post("/api/dp/instances/" + instance + "/config/accept-runtime", admin,
                placementBody(agentPlacement).put("baseStateVersion", 0)), "acceptRuntimeConfig");
        assertRefused(http.post("/api/dp/instances/" + instance + "/config/restore-runtime", admin,
                placementBody(agentPlacement)), "restoreRuntimeConfig");
        ObjectNode rollback = placementBody(agentPlacement).put("generation", 1).put("strategy", "manual");
        assertRefused(http.post("/api/dp/instances/" + instance + "/apply/rollback", admin, rollback), "rollbackApply");
        // the SCHEDULED rollback is a different route (ApplyController -> scheduleRollback): refused at admission too, nothing recorded
        ObjectNode scheduled = rollback.deepCopy();
        scheduled.putObject("schedule").put("notBefore", java.time.Instant.now().plus(java.time.Duration.ofHours(1)).toString())
                .put("notAfter", java.time.Instant.now().plus(java.time.Duration.ofHours(2)).toString());
        assertRefused(http.post("/api/dp/instances/" + instance + "/apply/rollback", admin, scheduled), "scheduled rollbackApply");
        assertEquals(0, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_schedule WHERE tenant_id = :t AND placement_id = :p",
                Map.of("t", world.organization(), "p", agentPlacement), Long.class), "no schedule was recorded");

        for (String method : List.of("read", "readBack", "setLogLevel", "restoreRuntime", "rollbackApply")) {
            assertEquals(0, calls(method), "no data-plane service was called for the agent placement: " + method);
        }
        assertEquals(0, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t AND placement_id = :p",
                Map.of("t", world.organization(), "p", agentPlacement), Long.class), "no doomed apply request was admitted");
    }

    @Test
    void readLogsOnAnAgentPlacementIsServedNotRefused() throws Exception {
        declareOnce();
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        HttpResponse<String> logs = http.get("/api/dp/instances/" + instance + "/logs/cache-engine?placementId=" + agentPlacement,
                admin);

        // plan 016: logs are served for an agent placement. No agent is connected in this fixture, so the page is a
        // declared 'unavailable' (not a 501, which would read as a refusal of the operation)
        assertEquals(200, logs.statusCode(), logs.body());
        assertEquals("unavailable", MAPPER.readTree(logs.body()).at("/availability/state").asText());
    }

    @Test
    void theSameRequestsOnTheSshPlacementOfTheSameInstanceStillReachTheServices() throws Exception {
        declareOnce();
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        CALLS.clear();

        HttpResponse<String> logs = http.get("/api/dp/instances/" + instance + "/logs/cache-engine?placementId=" + sshPlacement,
                admin);
        assertNotEquals(501, logs.statusCode(), "the SSH placement is untouched by the refusal");
        assertEquals(200, logs.statusCode(), logs.body());
        assertTrue(calls("read") >= 1, "the spy sees the host-reaching service when it is called");
        HttpResponse<String> level = http.get("/api/dp/instances/" + instance + "/log-levels/cache-engine?placementId="
                + sshPlacement, admin);
        assertNotEquals(501, level.statusCode(), level.body());
    }

    @Test
    void anAgentPlacementOfAnotherInstanceIsStillANotFoundNotARefusal() throws Exception {
        declareOnce();
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        HttpResponse<String> response = http.get("/api/dp/instances/" + instance + "/logs/cache-engine?placementId="
                + UUID.randomUUID(), admin);
        assertEquals(404, response.statusCode(), "a placement that is not this instance's is as absent as ever (I5)");
    }
}
