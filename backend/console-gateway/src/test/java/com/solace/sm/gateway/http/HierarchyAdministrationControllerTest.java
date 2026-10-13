package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The five hierarchy writes (plan 013 T2.3; D-REG 5.3 H1 to H5: {@code createSubOrganization}, {@code createEnvironment},
 * {@code renameOrganization}, {@code renameSubOrganization}, {@code renameEnvironment}) over the real filter, interceptor,
 * resolver, controller and advice pipeline, with {@link HierarchyAdministration} faked.
 *
 * <p>What this proves that the {@code cp-core} tests cannot: that the authorization is the INTERCEPTOR's, at the right node
 * kind and id and with the right verb, before the body is read; that the organization the service is handed is the
 * session's (a path organization that is not the session's is {@code 404}, whatever the authorizer says: {@code I5}); that
 * a bad name is {@code 400} and reaches no port; and how the service's answers become status codes, in particular
 * {@code OR-123}'s {@code 409} for a case-only sibling clash and {@code 200} for the exact-name replay.
 */
class HierarchyAdministrationControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/cp/hierarchy/organizations/";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAdministration admin;
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
        admin = context.getBean(FakeAdministration.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
    }

    // ---------------------------------------------------------------------------------------------- the rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        FakeAdministration fakeAdministration() {
            return new FakeAdministration();
        }

        @Bean
        OrganizationNodeResolver organizationNodeResolver() {
            return new OrganizationNodeResolver();
        }

        @Bean
        InstallOrganizationResolver installOrganizationResolver() {
            return new InstallOrganizationResolver();
        }

        @Bean
        SubOrganizationNodeResolver subOrganizationNodeResolver() {
            return new SubOrganizationNodeResolver();
        }

        @Bean
        EnvironmentNodeResolver environmentNodeResolver() {
            return new EnvironmentNodeResolver();
        }

        @Bean
        HierarchyAdministrationController hierarchyAdministrationController(FakeAdministration administration,
                RequestOriginFactory origins) {
            return new HierarchyAdministrationController(administration, origins);
        }

        @Bean
        HierarchyErrorAdvice hierarchyErrorAdvice() {
            return new HierarchyErrorAdvice();
        }
    }

    /** One call as the port saw it. */
    record Call(String method, PrincipalRef actor, RequestOrigin origin, List<UUID> ids, String name) {
    }

    static final class FakeAdministration implements HierarchyAdministration {
        final List<Call> calls = new ArrayList<>();
        /** What a create answers: {@code true} a new node, {@code false} the identical replay. */
        boolean created = true;
        RuntimeException failure;

        private Created create(String method, PrincipalRef actor, RequestOrigin origin, String name, UUID... ids) {
            calls.add(new Call(method, actor, origin, List.of(ids), name));
            if (failure != null) {
                throw failure;
            }
            return new Created(new ScopeNode(UUID.nameUUIDFromBytes(name.getBytes()), name.strip()), created);
        }

        private ScopeNode rename(String method, PrincipalRef actor, RequestOrigin origin, String name, UUID... ids) {
            calls.add(new Call(method, actor, origin, List.of(ids), name));
            if (failure != null) {
                throw failure;
            }
            return new ScopeNode(ids[ids.length - 1], name.strip());
        }

        @Override
        public Created createSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                String name) {
            return create("createSubOrganization", actor, origin, name, organizationId);
        }

        @Override
        public Created createEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                UUID subOrganizationId, String name) {
            return create("createEnvironment", actor, origin, name, organizationId, subOrganizationId);
        }

        @Override
        public ScopeNode renameOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                String name) {
            return rename("renameOrganization", actor, origin, name, organizationId);
        }

        @Override
        public ScopeNode renameSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                UUID subOrganizationId, String name) {
            return rename("renameSubOrganization", actor, origin, name, organizationId, subOrganizationId);
        }

        @Override
        public ScopeNode renameEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                UUID subOrganizationId, UUID environmentId, String name) {
            return rename("renameEnvironment", actor, origin, name, organizationId, subOrganizationId, environmentId);
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mvc.perform(request.cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String name(String value) {
        return "{\"name\":" + MAPPER.valueToTree(value) + "}";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String subOrgs(UUID organizationId) {
        return BASE + organizationId + "/sub-orgs";
    }

    private static String subOrg(UUID organizationId, UUID subOrganizationId) {
        return subOrgs(organizationId) + "/" + subOrganizationId;
    }

    private static String environments(UUID organizationId, UUID subOrganizationId) {
        return subOrg(organizationId, subOrganizationId) + "/environments";
    }

    private static String environment(UUID organizationId, UUID subOrganizationId, UUID environmentId) {
        return environments(organizationId, subOrganizationId) + "/" + environmentId;
    }

    /** The five operations, each as a request builder for the session's organization (and fresh sub and env ids). */
    private List<MockHttpServletRequestBuilder> allFive(UUID sub, UUID env) {
        return List.of(post(subOrgs(organization)), post(environments(organization, sub)), patch(BASE + organization),
                patch(subOrg(organization, sub)), patch(environment(organization, sub, env)));
    }

    // -------------------------------------------------------------------------------------- authentication

    @Test
    void everyWriteWithoutASessionIsUnauthenticatedAndReachesNoPort() throws Exception {
        UUID sub = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : allFive(sub, UUID.randomUUID())) {
            MvcResult result = mvc.perform(request.header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(name("X"))).andReturn();
            assertEquals(401, result.getResponse().getStatus());
        }
        assertTrue(admin.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void everyWriteWithoutACsrfTokenIsRefusedBeforeAuthorizationOrThePort() throws Exception {
        login("tok-csrf");
        UUID sub = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : allFive(sub, UUID.randomUUID())) {
            MvcResult result = mvc.perform(request.cookie(new Cookie(COOKIE, "tok-csrf"))
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(name("X"))).andReturn();
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("CSRF_REJECTED", json(result).get("code").asText());
        }
        assertTrue(admin.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void everyWriteWhileMustRotateIsRefused428() throws Exception {
        Session rotating = GatewayTestSupport.newSession(UUID.randomUUID(), organization, true);
        context.getBean(FakeSessionAuthentication.class).register("tok-rot", rotating, "csrf-tok-rot");
        UUID sub = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : allFive(sub, UUID.randomUUID())) {
            assertEquals(428, send(request, "tok-rot", name("X")).getResponse().getStatus());
        }
        assertTrue(admin.calls.isEmpty());
    }

    // ------------------------------------------------------------------ authorization: verb, node, order

    @Test
    void eachWriteIsAuthorizedAsHierarchyEditAtTheNodeTheContractNamesBeforeThePortIsCalled() throws Exception {
        login("tok-authz");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        send(post(subOrgs(organization)), "tok-authz", name("Platform"));
        send(post(environments(organization, sub)), "tok-authz", name("Dev"));
        send(patch(BASE + organization), "tok-authz", name("ACME"));
        send(patch(subOrg(organization, sub)), "tok-authz", name("Platform"));
        send(patch(environment(organization, sub, env)), "tok-authz", name("Dev"));

        assertEquals(List.of(Permission.HIERARCHY_EDIT, Permission.HIERARCHY_EDIT, Permission.HIERARCHY_EDIT,
                Permission.HIERARCHY_EDIT, Permission.HIERARCHY_EDIT), authorization.permissions);
        // a create is authorized at the PARENT, a rename at the node; an ORGANIZATION rename is the exception, authorized
        // at the INSTALL anchor (OR-125 (3)), where the service then also requires the named organization
        assertEquals(List.of(
                ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization)),
                ResourceRef.of(ResourceKind.SUB_ORGANIZATION, sub, new TenantId(organization)),
                ResourceRef.of(ResourceKind.ROLE_DEFINITION, EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                        new TenantId(organization)),
                ResourceRef.of(ResourceKind.SUB_ORGANIZATION, sub, new TenantId(organization)),
                ResourceRef.of(ResourceKind.ENVIRONMENT, env, new TenantId(organization))), authorization.resources);
        assertEquals(5, admin.calls.size());
    }

    @Test
    void aDeniedVerbIs403AndANodeTheCallerCannotSeeIs404AndNeitherReachesThePort() throws Exception {
        login("tok-deny");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        for (MockHttpServletRequestBuilder request : allFive(sub, env)) {
            MvcResult result = send(request, "tok-deny", name("X"));
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("FORBIDDEN", json(result).get("code").asText());
        }
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;
        for (MockHttpServletRequestBuilder request : allFive(sub, env)) {
            MvcResult result = send(request, "tok-deny", name("X"));
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertTrue(admin.calls.isEmpty(), "a refusal reaches no port");
    }

    @Test
    void aPathOrganizationThatIsNotTheSessionsIs404EvenWhenTheAuthorizerWouldAllowIt() throws Exception {
        login("tok-foreign");
        UUID foreign = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> requests = List.of(post(subOrgs(foreign)),
                post(environments(foreign, sub)), patch(subOrg(foreign, sub)),
                patch(environment(foreign, sub, env)));
        for (MockHttpServletRequestBuilder request : requests) {
            MvcResult result = send(request, "tok-foreign", name("X"));
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertTrue(admin.calls.isEmpty(), "I5: the organization the service is given is the session's");
        assertTrue(authorization.permissions.isEmpty(),
                "the id comparison happens in the resolver, before the authorizer is asked about a foreign tenant");
    }

    @Test
    void anOrganizationRenameNamingAForeignOrganizationAsksTheInstallAnchorThenIs404AndReachesNoPort() throws Exception {
        // OR-125 (3): the resolver is the install anchor and reads nothing from the path, so the authorizer is asked about
        // the anchor (and only the anchor); the handler's own check then makes a foreign id as absent as a nonexistent one
        login("tok-foreign-org");
        MvcResult result = send(patch(BASE + UUID.randomUUID()), "tok-foreign-org", name("X"));
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertTrue(admin.calls.isEmpty(), "I5: the organization the service is given is the session's");
        assertEquals(List.of(Permission.HIERARCHY_EDIT), authorization.permissions);
        assertEquals(List.of(ResourceRef.of(ResourceKind.ROLE_DEFINITION, EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                new TenantId(organization))), authorization.resources);
    }

    @Test
    void anOrganizationBoundCallerIsRefused403ByTheInstallAnchorWhateverOrganizationItNamesSoNoExistenceLeaks()
            throws Exception {
        // OR-125 (3): the refusal is decided before the path id is read, so own, foreign and nonexistent ids answer the
        // same status and body, and the 404 convention is never reached by a caller who may not rename at all
        login("tok-org-bound");
        authorization.outcomeByResource.put(EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                java.util.Map.of(Permission.HIERARCHY_EDIT, FakeAuthorizationService.Outcome.DENY));
        List<String> bodies = new ArrayList<>();
        for (UUID named : List.of(organization, UUID.randomUUID(), UUID.randomUUID())) {
            MvcResult result = send(patch(BASE + named), "tok-org-bound", name("Whatever"));
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("FORBIDDEN", json(result).get("code").asText());
            JsonNode body = json(result);
            bodies.add(body.get("code").asText() + "|" + body.path("title").asText() + "|" + body.path("detail").asText());
        }
        assertEquals(1, new java.util.HashSet<>(bodies).size(), "the same refusal for every id: " + bodies);
        assertTrue(admin.calls.isEmpty());

        // the other four writes are not install-scoped: the same principal still creates and renames below
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(post(subOrgs(organization)),
                post(environments(organization, sub)), patch(subOrg(organization, sub)),
                patch(environment(organization, sub, env)))) {
            assertTrue(send(request, "tok-org-bound", name("Ok")).getResponse().getStatus() < 300);
        }
        assertEquals(4, admin.calls.size());
    }

    @Test
    void aPathIdThatIsNotAUuidNamesNothing() throws Exception {
        login("tok-bad-id");
        MvcResult result = send(post(BASE + "not-a-uuid/sub-orgs"), "tok-bad-id", name("X"));
        assertTrue(result.getResponse().getStatus() == 404 || result.getResponse().getStatus() == 400,
                "a malformed id is a refusal, never a 500: " + result.getResponse().getStatus());
        assertTrue(admin.calls.isEmpty());
    }

    @Test
    void aNonUuidIdInAnyPositionOfTheRenameEnvironmentPathIs404BeforeTheAuthorizer() throws Exception {
        // W4 review W4D-4: the sub-organization position used to be read only by @PathVariable conversion (400, after
        // authorization); every position is a 404 that reaches neither the authorizer nor the port
        login("tok-bad-ids");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        for (String path : List.of(BASE + organization + "/sub-orgs/not-a-uuid/environments/" + env,
                BASE + organization + "/sub-orgs/" + sub + "/environments/not-a-uuid",
                BASE + "not-a-uuid/sub-orgs/" + sub + "/environments/" + env)) {
            MvcResult result = send(patch(path), "tok-bad-ids", name("X"));
            assertEquals(404, result.getResponse().getStatus(), path);
            assertEquals("NOT_FOUND", json(result).get("code").asText(), path);
        }
        assertTrue(admin.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty(), "found absent before anyone is asked whether the caller may");
    }

    @Test
    void aNonUuidOrganizationIdOnRenameOrganizationIs404BeforeTheAuthorizerForEveryCaller() throws Exception {
        // W5 review W5C-3: renameOrganization's resolver (the install anchor) used to read nothing, so a malformed id met
        // @PathVariable conversion (400 after the authorization decision for an install-bound caller, 403 for an
        // organization-bound one); the other four write paths answer 404 before anyone is asked. The id is parsed first,
        // and the 404 is the same for both callers, so it distinguishes nothing about any organization.
        login("tok-bad-org-id");
        MvcResult result = send(patch(BASE + "not-a-uuid"), "tok-bad-org-id", name("X"));
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        login("tok-bad-org-id-bound");
        authorization.outcomeByResource.put(EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                java.util.Map.of(Permission.HIERARCHY_EDIT, FakeAuthorizationService.Outcome.DENY));
        MvcResult bound = send(patch(BASE + "not-a-uuid"), "tok-bad-org-id-bound", name("X"));
        assertEquals(404, bound.getResponse().getStatus(), "a malformed id is 404 even for a caller who may not rename");
        assertEquals("NOT_FOUND", json(bound).get("code").asText());
        assertTrue(admin.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty(), "found absent before anyone is asked whether the caller may");
    }

    // ------------------------------------------------------------------------------------ the happy paths

    @Test
    void createSubOrganizationIs201WithTheNodeAndThePortGetsTheSessionsPrincipalOriginAndOrganization()
            throws Exception {
        Session session = login("tok-create");
        MvcResult result = send(post(subOrgs(organization)), "tok-create", name("  Platform  "));

        assertEquals(201, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("Platform", body.get("name").asText(), "the stored (stripped) name is what is returned");
        assertNotNull(UUID.fromString(body.get("id").asText()));
        assertEquals(2, body.size(), "a ScopeNode is {id, name}");
        Call call = admin.calls.get(0);
        assertEquals("createSubOrganization", call.method());
        assertEquals(session.principal(), call.actor());
        assertNotNull(call.origin());
        assertNotNull(call.origin().correlationId());
        assertEquals(List.of(organization), call.ids());
        assertEquals("  Platform  ", call.name(), "the service strips; the gateway does not rewrite the name");
    }

    @Test
    void anIdenticalReplayOfACreateIs200WithTheSameNodeShape() throws Exception {
        login("tok-replay");
        admin.created = false;
        UUID sub = UUID.randomUUID();
        MvcResult first = send(post(subOrgs(organization)), "tok-replay", name("Platform"));
        MvcResult second = send(post(environments(organization, sub)), "tok-replay", name("Dev"));
        assertEquals(200, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals("Platform", json(first).get("name").asText());
        assertEquals("Dev", json(second).get("name").asText());
    }

    @Test
    void createEnvironmentPassesBothPathIdsAndAnswers201() throws Exception {
        login("tok-env");
        UUID sub = UUID.randomUUID();
        MvcResult result = send(post(environments(organization, sub)), "tok-env", name("Dev"));
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(List.of(organization, sub), admin.calls.get(0).ids());
    }

    @Test
    void theThreeRenamesAnswer200WithTheNodeAndPassTheirPathIdsInOrder() throws Exception {
        login("tok-rename");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        MvcResult org = send(patch(BASE + organization), "tok-rename", name("ACME"));
        MvcResult unit = send(patch(subOrg(organization, sub)), "tok-rename", name("Platform"));
        MvcResult node = send(patch(environment(organization, sub, env)), "tok-rename", name("Prod"));
        assertEquals(200, org.getResponse().getStatus());
        assertEquals(200, unit.getResponse().getStatus());
        assertEquals(200, node.getResponse().getStatus());
        assertEquals(organization.toString(), json(org).get("id").asText());
        assertEquals(sub.toString(), json(unit).get("id").asText());
        assertEquals(env.toString(), json(node).get("id").asText());
        assertEquals(List.of(organization), admin.calls.get(0).ids());
        assertEquals(List.of(organization, sub), admin.calls.get(1).ids());
        assertEquals(List.of(organization, sub, env), admin.calls.get(2).ids());
    }

    // ---------------------------------------------------------------------- OR-123: the 409

    @Test
    void aSiblingNameClashIsA409ConflictOnEveryWriteAndTheResponseNeverEchoesTheName() throws Exception {
        login("tok-clash");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        String secretLooking = "Clash-4f9c2a7e-do-not-echo";
        for (ResourceKind kind : new ResourceKind[] {ResourceKind.SUB_ORGANIZATION, ResourceKind.ENVIRONMENT,
                ResourceKind.ORGANIZATION}) {
            admin.failure = new HierarchyNameConflictException(kind);
            for (MockHttpServletRequestBuilder request : allFive(sub, env)) {
                MvcResult result = send(request, "tok-clash", name(secretLooking));
                assertEquals(409, result.getResponse().getStatus());
                JsonNode body = json(result);
                assertEquals("CONFLICT", body.get("code").asText());
                assertFalse(result.getResponse().getContentAsString().contains(secretLooking),
                        "the name a caller sent is not echoed");
                assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        result.getResponse().getContentType().replace(";charset=UTF-8", ""));
            }
        }
    }

    @Test
    void theOrganizationRenamesConflictDetailDoesNotClaimTheClashIsWithASibling() throws Exception {
        login("tok-org-409");
        admin.failure = new HierarchyNameConflictException(ResourceKind.ORGANIZATION);
        MvcResult result = send(patch(BASE + organization), "tok-org-409", name("Other"));
        String detail = json(result).path("detail").asText("");
        assertFalse(detail.toLowerCase().contains("sibling"),
                "organization names are unique across the install, so this 409 is not a sibling clash: " + detail);
        admin.failure = new HierarchyNameConflictException(ResourceKind.SUB_ORGANIZATION);
        MvcResult sibling = send(patch(subOrg(organization, UUID.randomUUID())), "tok-org-409", name("Other"));
        assertTrue(json(sibling).path("detail").asText("").toLowerCase().contains("sibling"));
    }

    // ----------------------------------------------------------------------------- the body is a name

    @Test
    void aBadNameOrBodyIs400AndReachesNoPort() throws Exception {
        login("tok-bad");
        UUID sub = UUID.randomUUID();
        List<String> bodies = List.of(
                "{}",
                "{\"name\":null}",
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "x".repeat(101) + "\"}",
                "{\"name\":\"tab\\there\"}",
                "{\"name\":\"nul\\u0000here\"}",
                "{\"name\":\"del\\u007fhere\"}",
                "{\"name\":\"c1\\u0085here\"}",
                "{\"name\":7}",
                "{\"name\":[\"a\"]}",
                "{\"name\":\"ok\",\"organizationId\":\"" + organization + "\"}",
                "{\"name\":\"ok\",\"extra\":1}",
                "[]",
                "\"Platform\"",
                "not json",
                "");
        for (String body : bodies) {
            for (MockHttpServletRequestBuilder request : allFive(sub, UUID.randomUUID())) {
                MvcResult result = send(request, "tok-bad", body);
                assertEquals(400, result.getResponse().getStatus(), body);
                assertEquals("BAD_REQUEST", json(result).get("code").asText());
            }
        }
        assertTrue(admin.calls.isEmpty());
    }

    @Test
    void aHundredCharacterNameIsAccepted_andTheLimitIsCountedInCodePointsNotUtf16Units() throws Exception {
        login("tok-limit");
        assertEquals(201, send(post(subOrgs(organization)), "tok-limit", name("x".repeat(100)))
                .getResponse().getStatus());
        // 100 supplementary characters are 200 UTF-16 units but 100 characters
        assertEquals(201, send(post(subOrgs(organization)), "tok-limit", name("😀".repeat(100)))
                .getResponse().getStatus());
        assertEquals(400, send(post(subOrgs(organization)), "tok-limit", name("😀".repeat(101)))
                .getResponse().getStatus());
        assertEquals(2, admin.calls.size());
    }

    @Test
    void theRequestBodyNeverCarriesAnOrganizationAndThePortIsGivenTheSessionsNotAClientsClaim() throws Exception {
        login("tok-i5");
        UUID claimed = UUID.randomUUID();
        MvcResult result = send(post(subOrgs(organization)), "tok-i5",
                "{\"name\":\"ok\",\"organizationId\":\"" + claimed + "\"}");
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(admin.calls.isEmpty());
    }

    // ------------------------------------------------------------------------------------ other methods

    @Test
    void theWriteRoutesAnswerNothingToTheWrongMethod() throws Exception {
        login("tok-method");
        MvcResult put = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(BASE + organization), "tok-method", name("X"));
        assertEquals(405, put.getResponse().getStatus());
        MvcResult delete = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(subOrg(organization, UUID.randomUUID())).cookie(new Cookie(COOKIE, "tok-method"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-tok-method")).andReturn();
        assertEquals(405, delete.getResponse().getStatus());
        assertTrue(admin.calls.isEmpty());
    }

    @Test
    void aMissingResourceTheServiceFindsAbsentIs404() throws Exception {
        login("tok-absent");
        admin.failure = new ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
        MvcResult result = send(post(environments(organization, UUID.randomUUID())), "tok-absent", name("Dev"));
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
    }
}
