package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 010 T4.4, the crash windows of the two-phase declare (BE-D32), across a REAL restart.
 *
 * <p>Context A boots against the database, loads the package, and leaves three half-finished declares behind by
 * calling the same {@code dp-api} / {@code cp-api} ports the orchestrator calls, one step at a time. Then A is CLOSED:
 * no sweep ran, nothing in memory survives. Context B boots against the same database and serves the requests under
 * test. The sweep holds no state of its own (no background loop, no in-process queue), so what B can finish is exactly
 * what the rows say.
 *
 * <ul>
 *   <li><b>stale</b> - crashed between {@code reserve} and {@code register}: a {@code pending} DP row, older than the
 *       window, with no CP node. The next declare in that tenant abandons it (freeing its names) - proved by that
 *       declare reusing the stale row's name, container, directory and health port and succeeding.</li>
 *   <li><b>fresh</b> - the same shape but YOUNGER than the window: it may be a declare still in flight, so no request
 *       may touch it.</li>
 *   <li><b>registered</b> - crashed between {@code register} and {@code activate}: a {@code pending} DP row and a CP
 *       node. The next list or get in that tenant activates it.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeclareCrashWindowIT {

    private static TwoSchemaTestDatabase db;
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID stale;
    private static UUID fresh;
    private static UUID registeredPending;
    private static ConfigurableApplicationContext contextB;
    private static Client http;

    @BeforeAll
    static void crashThenRestart() throws Exception {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        db = provisioned.db();
        ownerJdbc = db.ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", provisioned.properties().get("sm.control.runtime.password"),
                world.organization(), "cache", 10, DeclareFixtures.ADMIN);

        Map<String, Object> properties = new LinkedHashMap<>(provisioned.properties());
        properties.put("server.port", "0");
        properties.put("sm.gateway.auth-rate.capacity", "1000");
        properties.put("sm.gateway.auth-rate.refill-per-minute", "6000");

        ConfigurableApplicationContext contextA = start(properties);
        try {
            Client first = new Client(portOf(contextA));
            ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = first.post("/api/cp/solutions/load", first.login(DeclareFixtures.ADMIN), load);
            assertEquals(201, loaded.statusCode(), loaded.body());
            solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(loaded.body()).get("solutionId").asText());

            InstanceInventoryService inventory = contextA.getBean(InstanceInventoryService.class);
            InstanceGovernance governance = contextA.getBean(InstanceGovernance.class);
            UUID actor = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                    Map.of("u", DeclareFixtures.OPERATOR_ONE), UUID.class);
            RequestOrigin origin = new RequestOrigin("127.0.0.1", UUID.randomUUID());

            // crash 1: reserved, never registered - and old enough for the sweep to abandon
            stale = reserve(inventory, actor, "stale-cache", "sm-stale", 9201).instanceId();
            ownerJdbc.update("UPDATE sm_data.instance_state SET created_at = now() - interval '1 hour'"
                    + " WHERE tenant_id = :t AND instance_id = :i", Map.of("t", world.organization(), "i", stale));
            // crash 1b: the same, but FRESH - a declare that may still be in flight
            fresh = reserve(inventory, actor, "fresh-cache", "sm-fresh", 9202).instanceId();
            // crash 2: reserved AND registered, never activated
            registeredPending = reserve(inventory, actor, "registered-cache", "sm-registered", 9203).instanceId();
            governance.register(new PrincipalRef(actor), origin, world.organization(), world.envOne(), solutionId,
                    registeredPending);
            assertEquals("pending", lifecycle(stale));
            assertEquals("pending", lifecycle(fresh));
            assertEquals("pending", lifecycle(registeredPending));
        } finally {
            contextA.close();   // the "crash": no sweep has run, and nothing in memory survives
        }

        contextB = start(properties);
        http = new Client(portOf(contextB));
    }

    @AfterAll
    static void stop() {
        if (contextB != null) {
            contextB.close();
        }
    }

    private static ConfigurableApplicationContext start(Map<String, Object> properties) {
        return new SpringApplicationBuilder(SolutionManagerApplication.class).web(WebApplicationType.SERVLET)
                .properties(properties).run();
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static InstanceView reserve(InstanceInventoryService inventory, UUID actor, String name, String container,
            int port) {
        return inventory.reserve(world.organization(), new DeclareInstanceCommand(world.envOne(), solutionId, "cache",
                "1", name, world.targetOne(), AcquisitionMode.DIRECT_HTTP,
                new PlacementSpec(container, "/srv/sm/" + container, "/app/external/spring/config", "application.yml",
                        new HealthProbeSpec("actuator-health", "/actuator/health", port, ProbeStrength.LIVENESS)),
                actor));
    }

    private static String lifecycle(UUID instance) {
        return ownerJdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND"
                + " instance_id = :i", Map.of("t", world.organization(), "i", instance), String.class);
    }

    private static long livePlacements(UUID instance) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND"
                + " instance_id = :i AND retired_at IS NULL", Map.of("t", world.organization(), "i", instance),
                Long.class);
    }

    private static boolean cpHasNode(UUID instance) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o AND"
                + " instance_id = :i", Map.of("o", world.organization(), "i", instance), Long.class) == 1L;
    }

    private static UUID operatorId() {
        return ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), UUID.class);
    }

    /** A declare that crashed between steps 4 and 5, made in the RUNNING context through the same ports. */
    private static UUID crashedAfterRegister(String name, String container, int port) {
        UUID actor = operatorId();
        UUID id = reserve(contextB.getBean(InstanceInventoryService.class), actor, name, container, port).instanceId();
        contextB.getBean(InstanceGovernance.class).register(new PrincipalRef(actor),
                new RequestOrigin("127.0.0.1", UUID.randomUUID()), world.organization(), world.envOne(), solutionId, id);
        assertEquals("pending", lifecycle(id));
        return id;
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void nothingWasSweptByTheRestartItself() {
        assertEquals("pending", lifecycle(stale), "there is no background loop: a restart finishes nothing by itself");
        assertEquals("pending", lifecycle(registeredPending));
    }

    @Test
    @Order(2)
    void aCrashBetweenReserveAndRegisterIsFinishedByTheNextDeclareInThatTenant() throws Exception {
        assertEquals(1L, livePlacements(stale));
        assertFalse(cpHasNode(stale), "the crash left a DP row and no CP node");

        // The very names the crashed declare holds - instance name, container, directory, health port.
        HttpResponse<String> response = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "stale-cache", "sm-stale", 9201));

        assertEquals(201, response.statusCode(), "the sweep abandoned the stale row first: " + response.body());
        assertEquals("abandoned", lifecycle(stale));
        assertEquals(0L, livePlacements(stale), "an abandoned declare frees its container, directory and port");
        assertFalse(cpHasNode(stale), "the CP never gained a node for the abandoned declare");
        UUID declared = UUID.fromString(DeclareFixtures.MAPPER.readTree(response.body()).get("instanceId").asText());
        assertEquals("active", lifecycle(declared));
        assertTrue(cpHasNode(declared));
        // the same sweep, same tenant, same request: the other interrupted declare is finished too...
        assertEquals("active", lifecycle(registeredPending), "a registered pending row is activated at any age");
        // ...and a fresh unregistered row may be a declare still in flight, so it is left alone
        assertEquals("pending", lifecycle(fresh), "younger than the window: no request may abandon it");
        assertEquals(1L, livePlacements(fresh));
    }

    @Test
    @Order(3)
    void aCrashBetweenRegisterAndActivateIsFinishedByTheNextListInThatTenant() throws Exception {
        UUID crashed = crashedAfterRegister("listed-registered", "sm-listed", 9204);
        assertTrue(cpHasNode(crashed), "the crash left a CP node");

        JsonNode listed = DeclareFixtures.MAPPER.readTree(http.get("/api/dp/solutions/" + solutionId + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE)).body());

        boolean visible = false;
        for (JsonNode item : listed.get("items")) {
            visible |= item.get("instanceId").asText().equals(crashed.toString());
        }
        assertTrue(visible, "the registered-but-pending instance is visible once the list swept: " + listed);
        assertEquals("active", lifecycle(crashed));
        assertEquals("pending", lifecycle(fresh), "the sweep still leaves a fresh unregistered row alone");
    }

    @Test
    @Order(4)
    void aRegisteredButPendingInstanceBecomesFetchableThroughGetInstanceToo() throws Exception {
        UUID crashed = crashedAfterRegister("fetched-registered", "sm-fetched", 9205);

        HttpResponse<String> fetched = http.get("/api/dp/instances/" + crashed, http.login(DeclareFixtures.ADMIN));

        assertEquals(200, fetched.statusCode(), fetched.body());
        assertEquals("active", lifecycle(crashed));
    }
}
