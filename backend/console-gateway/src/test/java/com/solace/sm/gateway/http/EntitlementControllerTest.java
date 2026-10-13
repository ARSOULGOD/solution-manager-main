package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code listEntitlements} and {@code setEntitlement} (plan 013 T2.3; D-REG 5.2 E1 and E2) over the real pipeline with
 * {@link EntitlementAdministration} faked.
 *
 * <p>What this proves: that {@code setEntitlement} is authorized by the interceptor as {@code entitlement.set} at the
 * INSTALL anchor (the same resource the service re-requires), that the organization handed to the service is the
 * session's (no request field names one: {@code I5}), that a malformed solution name or limit is {@code 400} and reaches
 * no port, and that the service's refusal below use is the contract's {@code 409 ENTITLEMENT_REFUSED}.
 */
class EntitlementControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/api/cp/entitlements";
    private static final Instant SET_AT = Instant.parse("2026-10-08T01:02:03Z");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeEntitlements entitlements;
    private FakeAuthorizationService authorization;
    private UUID organization;

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
        entitlements = context.getBean(FakeEntitlements.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        FakeEntitlements fakeEntitlements() {
            return new FakeEntitlements();
        }

        @Bean
        InstallResourceResolver installResourceResolver() {
            return new InstallResourceResolver();
        }

        @Bean
        SessionOrganizationResourceResolver sessionOrganizationResourceResolver() {
            return new SessionOrganizationResourceResolver();
        }

        @Bean
        EntitlementController entitlementController(FakeEntitlements administration, RequestOriginFactory origins) {
            return new EntitlementController(administration, origins);
        }
    }

    record SetCall(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String solution, int maxInstances) {
    }

    static final class FakeEntitlements implements EntitlementAdministration {
        final List<UUID> listed = new ArrayList<>();
        final List<SetCall> sets = new ArrayList<>();
        List<Entitlement> toList = List.of();
        RuntimeException failure;

        @Override
        public List<Entitlement> list(PrincipalRef actor, UUID organizationId) {
            listed.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            return toList;
        }

        @Override
        public Entitlement set(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String solution,
                int maxInstances) {
            sets.add(new SetCall(actor, origin, organizationId, solution, maxInstances));
            if (failure != null) {
                throw failure;
            }
            return new Entitlement(solution, maxInstances, 2L, SET_AT);
        }
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult set(String token, String solution, String body) throws Exception {
        return mvc.perform(put(PATH + "/" + solution).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------------------------ listEntitlements

    @Test
    void listWithoutASessionIs401AndReachesNoPort() throws Exception {
        assertEquals(401, mvc.perform(get(PATH)).andReturn().getResponse().getStatus());
        assertTrue(entitlements.listed.isEmpty());
    }

    @Test
    void listIsSolutionViewAtTheSessionsOwnOrganizationAndShowsTheItemsWithTheirDeclaredCount() throws Exception {
        Session session = login("tok-list");
        entitlements.toList = List.of(
                new EntitlementAdministration.Entitlement("cache", 5, 2L, SET_AT),
                new EntitlementAdministration.Entitlement("file-mesh", 1, 0L, SET_AT));

        MvcResult result = mvc.perform(get(PATH).cookie(new Cookie(COOKIE, "tok-list"))).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        JsonNode items = json(result).get("items");
        assertEquals(2, items.size());
        assertEquals("cache", items.get(0).get("solution").asText());
        assertEquals(5, items.get(0).get("maxInstances").asInt());
        assertEquals(2, items.get(0).get("declaredInstances").asInt());
        assertEquals("2026-10-08T01:02:03Z", items.get(0).get("setAt").asText());
        assertEquals(4, items.get(0).size(), "{solution, maxInstances, declaredInstances, setAt}");
        assertEquals(List.of(Permission.SOLUTION_VIEW), authorization.permissions);
        assertEquals(List.of(ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization))),
                authorization.resources);
        assertEquals(List.of(organization), entitlements.listed, "the session's organization, never a request value");
        assertEquals(session.organizationId(), entitlements.listed.get(0));
    }

    @Test
    void listWithNoEntitlementsIsAnEmptyItemsArrayNotNull() throws Exception {
        login("tok-empty");
        MvcResult result = mvc.perform(get(PATH).cookie(new Cookie(COOKIE, "tok-empty"))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(json(result).get("items").isArray());
        assertEquals(0, json(result).get("items").size());
    }

    @Test
    void listWithoutTheVerbIs403() throws Exception {
        login("tok-list-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        assertEquals(403, mvc.perform(get(PATH).cookie(new Cookie(COOKIE, "tok-list-deny"))).andReturn()
                .getResponse().getStatus());
        assertTrue(entitlements.listed.isEmpty());
    }

    // ------------------------------------------------------------------------------------ setEntitlement

    @Test
    void setWithoutASessionIs401AndWithoutCsrfIs403AndNeitherAuthorizesOrWrites() throws Exception {
        assertEquals(401, mvc.perform(put(PATH + "/cache").header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxInstances\":3}")).andReturn()
                .getResponse().getStatus());
        login("tok-nocsrf");
        MvcResult result = mvc.perform(put(PATH + "/cache").cookie(new Cookie(COOKIE, "tok-nocsrf"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxInstances\":3}")).andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("CSRF_REJECTED", json(result).get("code").asText());
        assertTrue(entitlements.sets.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void setIsEntitlementSetAtTheInstallAnchorAndTheServiceGetsTheSessionsOrganization() throws Exception {
        Session session = login("tok-set");
        MvcResult result = set("tok-set", "cache", "{\"maxInstances\":7}");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("cache", body.get("solution").asText());
        assertEquals(7, body.get("maxInstances").asInt());
        assertEquals(2, body.get("declaredInstances").asInt());
        assertEquals("2026-10-08T01:02:03Z", body.get("setAt").asText());
        assertEquals(List.of(Permission.ENTITLEMENT_SET), authorization.permissions);
        assertEquals(List.of(ResourceRef.of(ResourceKind.ROLE_DEFINITION,
                        EntitlementAdministration.INSTALL_ANCHOR_ROLE, new TenantId(organization))),
                authorization.resources, "evaluated at INSTALL scope, as the service re-requires it");
        SetCall call = entitlements.sets.get(0);
        assertEquals(session.principal(), call.actor());
        assertEquals(organization, call.organizationId());
        assertEquals("cache", call.solution());
        assertEquals(7, call.maxInstances());
        assertTrue(call.origin() != null && call.origin().correlationId() != null);
    }

    @Test
    void aReplayOfTheSameLimitIsAnotherSuccessfulPut() throws Exception {
        login("tok-put-twice");
        assertEquals(200, set("tok-put-twice", "cache", "{\"maxInstances\":7}").getResponse().getStatus());
        assertEquals(200, set("tok-put-twice", "cache", "{\"maxInstances\":7}").getResponse().getStatus());
        assertEquals(2, entitlements.sets.size());
    }

    @Test
    void anOrganizationBoundCallerThePolicyRefusesIs403AndNothingIsWritten() throws Exception {
        login("tok-set-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        MvcResult result = set("tok-set-deny", "cache", "{\"maxInstances\":7}");
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(result).get("code").asText());
        assertTrue(entitlements.sets.isEmpty());
    }

    @Test
    void aLimitBelowUseIsTheContractsEntitlementRefused409() throws Exception {
        login("tok-below");
        entitlements.failure = new EntitlementRefusedException(EntitlementRefusedException.Reason.BELOW_DECLARED);
        MvcResult result = set("tok-below", "cache", "{\"maxInstances\":1}");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("ENTITLEMENT_REFUSED", json(result).get("code").asText());
    }

    @Test
    void anOrganizationTheServiceFindsAbsentIs404() throws Exception {
        login("tok-absent");
        entitlements.failure = new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.ORGANIZATION);
        assertEquals(404, set("tok-absent", "cache", "{\"maxInstances\":2}").getResponse().getStatus());
    }

    @Test
    void aBadLimitOrBodyIs400AndReachesNoPort() throws Exception {
        login("tok-bad-limit");
        for (String body : List.of("{}", "{\"maxInstances\":null}", "{\"maxInstances\":0}", "{\"maxInstances\":-1}",
                "{\"maxInstances\":1000001}", "{\"maxInstances\":2147483648}", "{\"maxInstances\":1.5}",
                "{\"maxInstances\":\"3\"}", "{\"maxInstances\":[3]}", "{\"maxInstances\":3,\"extra\":true}",
                "{\"maxInstances\":3,\"organizationId\":\"" + organization + "\"}", "[]", "3", "not json", "")) {
            MvcResult result = set("tok-bad-limit", "cache", body);
            assertEquals(400, result.getResponse().getStatus(), body);
            assertEquals("BAD_REQUEST", json(result).get("code").asText());
        }
        assertTrue(entitlements.sets.isEmpty());
    }

    @Test
    void theLimitsEndsAreAccepted() throws Exception {
        login("tok-ends");
        assertEquals(200, set("tok-ends", "cache", "{\"maxInstances\":1}").getResponse().getStatus());
        assertEquals(200, set("tok-ends", "cache", "{\"maxInstances\":1000000}").getResponse().getStatus());
    }

    @Test
    void aSolutionNameOutsideTheCatalogGrammarIs400NotAnIllegalArgumentFromTheService() throws Exception {
        login("tok-solution");
        for (String solution : List.of("Cache", "1cache", "-cache", "cache_x", "cache.x", "a".repeat(65), "caché")) {
            MvcResult result = set("tok-solution", solution, "{\"maxInstances\":3}");
            assertEquals(400, result.getResponse().getStatus(), solution);
            assertEquals("BAD_REQUEST", json(result).get("code").asText(), solution);
        }
        assertTrue(entitlements.sets.isEmpty());
        assertEquals(200, set("tok-solution", "a".repeat(64), "{\"maxInstances\":3}").getResponse().getStatus());
        assertEquals(200, set("tok-solution", "file-mesh", "{\"maxInstances\":3}").getResponse().getStatus());
    }

    @Test
    void thereIsNoDeleteAndNoPostOnAnEntitlement() throws Exception {
        login("tok-methods");
        for (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PATH + "/cache"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH + "/cache"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(PATH + "/cache"))) {
            MvcResult result = mvc.perform(request.cookie(new Cookie(COOKIE, "tok-methods"))
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-tok-methods")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"maxInstances\":3}")).andReturn();
            assertEquals(405, result.getResponse().getStatus());
        }
        assertFalse(!entitlements.sets.isEmpty());
    }
}
