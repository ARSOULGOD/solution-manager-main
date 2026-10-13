package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.health.HealthView;
import com.solace.sm.dp.api.health.PlacementHealthView;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@code getHealth} (plan 010 T7.2) over the real filter, interceptor, argument-resolver, controller and advice
 * pipeline with the cp-api and dp-api ports FAKED. The polling and the persistence are {@code dp-core}'s
 * ({@code HealthPollerIT}); pinned here: who may call, that an instance that is not visible is the same 404, that the
 * organization is the session's, and the wire shape.
 */
class HealthControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeInventory inventory;
    private FakeHealth health;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

    @Configuration
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeHealth health(Calls calls) {
            return new FakeHealth(calls);
        }

        @Bean HealthController healthController(DeclareOrchestrator orchestrator, HealthService health) {
            return new HealthController(orchestrator, health);
        }
    }

    static final class FakeHealth implements HealthService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        HealthView next = new HealthView(List.of(), 0);

        FakeHealth(Calls calls) {
            this.calls = calls;
        }

        @Override
        public HealthView health(UUID organizationId, UUID instanceId) {
            calls.add("dp.health");
            organizationsSeen.add(organizationId);
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true",
                "sm.gateway.auth-rate.capacity=10", "sm.gateway.auth-rate.refill-per-minute=10",
                "sm.gateway.auth-rate.max-tracked-keys=100000");
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        authorization = context.getBean(FakeAuthorizationService.class);
        inventory = context.getBean(FakeInventory.class);
        health = context.getBean(FakeHealth.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        inventory.rows.put(instanceId, new InstanceView(instanceId, "orders-cache", UUID.randomUUID(),
                UUID.randomUUID(), "1", InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))), 41L));
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult read(String token) throws Exception {
        return mvc.perform(get("/api/dp/instances/" + instanceId + "/health").cookie(new Cookie(COOKIE, token)))
                .andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void itRequiresHealthViewAtThePathsInstance() throws Exception {
        Session session = login("tok-verb");

        read("tok-verb");

        assertEquals(List.of(Permission.HEALTH_VIEW), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNoPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, read("tok-deny").getResponse().getStatus());

        assertFalse(context.getBean(Calls.class).has("dp.health"));
    }

    @Test
    void anInstanceThatIsNotActiveIsTheSame404AndHealthIsNotRead() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();

        MvcResult result = read("tok-missing");

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertFalse(context.getBean(Calls.class).has("dp.health"));
    }

    @Test
    void theOrganizationIsTheSessionsNeverARequestField() throws Exception {
        Session session = login("tok-i5");

        mvc.perform(get("/api/dp/instances/" + instanceId + "/health?organizationId=" + UUID.randomUUID())
                .cookie(new Cookie(COOKIE, "tok-i5")).header("X-Organization", UUID.randomUUID().toString()))
                .andReturn();

        assertFalse(health.organizationsSeen.isEmpty());
        assertTrue(health.organizationsSeen.stream().allMatch(session.organizationId()::equals));
    }

    @Test
    void eachPlacementCarriesItsOwnStateAndVersionAndThereIsNoTopLevelStateOrVersion() throws Exception {
        login("tok-wire");
        UUID second = UUID.randomUUID();
        health.next = new HealthView(List.of(
                new PlacementHealthView(placementId, HealthState.GREEN, Instant.parse("2026-10-05T10:00:00Z"), 11L),
                new PlacementHealthView(second, HealthState.UNREACHABLE, Instant.parse("2026-10-05T10:00:30Z"), 17L)),
                0);

        MvcResult result = read("tok-wire");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals(Set.of("placements"), fieldNames(body), "OR-39/OR-55: no top-level state or stateVersion");
        assertEquals(2, body.get("placements").size());
        JsonNode first = body.get("placements").get(0);
        assertEquals(placementId.toString(), first.get("placementId").asText());
        assertEquals("GREEN", first.get("state").asText());
        assertEquals("2026-10-05T10:00:00Z", first.get("checkedAt").asText());
        assertEquals(11, first.get("stateVersion").asLong());
        JsonNode other = body.get("placements").get(1);
        assertEquals("unreachable", other.get("state").asText(), "OR-39: UNKNOWN, not DOWN - the sub-state, spelt as the contract spells it");
        assertEquals(17, other.get("stateVersion").asLong());
    }

    @Test
    void aPlacementNothingHasPolledYetIsDeclaredNotSilentlyMissing() throws Exception {
        login("tok-unpolled");
        health.next = new HealthView(List.of(), 1);

        JsonNode body = json(read("tok-unpolled"));

        assertEquals(Set.of("availability", "placements"), fieldNames(body));
        assertEquals("degraded", body.at("/availability/state").asText());
        assertEquals("polling", body.at("/availability/mode").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertTrue(body.get("placements").isEmpty());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
