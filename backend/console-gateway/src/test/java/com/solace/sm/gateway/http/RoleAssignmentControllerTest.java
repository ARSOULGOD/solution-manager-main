package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentConflictException;
import com.solace.sm.cp.api.identity.AssignmentInvalidException;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The four role operations (plan 013 T3.2; D-REG 5.4 U4 to U7: {@code listRoles}, {@code listAssignments},
 * {@code grantAssignment}, {@code revokeAssignment}) over the real filter, interceptor, resolver, controller and advice
 * pipeline, with {@link AssignmentAdministration} faked.
 *
 * <p>What this proves that the {@code cp-core} tests cannot: that {@code role.assign} is the INTERCEPTOR's, evaluated at
 * the SCOPE (the body's for a grant, the stored binding's own for a revoke) and not at the organization, before the
 * handler runs; that the scope the handler acts on is the one that was authorized; that nothing in a request can name
 * another organization ({@code I5}); and how the service's refusals become status codes.
 */
class RoleAssignmentControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/cp/admin";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAssignments assignments;
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
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*")
                .addFilter(new PeekableBodyFilter(), "/*").build();
        assignments = context.getBean(FakeAssignments.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------------------------------------------------------------------------------------------- the rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        FakeAssignments fakeAssignments() {
            return new FakeAssignments();
        }

        @Bean
        SessionOrganizationResourceResolver sessionOrganizationResourceResolver() {
            return new SessionOrganizationResourceResolver();
        }

        @Bean
        GrantedScopeResolver grantedScopeResolver() {
            return new GrantedScopeResolver();
        }

        @Bean
        AssignmentResolver assignmentResolver(FakeAssignments assignments) {
            return new AssignmentResolver(assignments);
        }

        @Bean
        RoleAssignmentController roleAssignmentController(FakeAssignments assignments, RequestOriginFactory origins) {
            return new RoleAssignmentController(assignments, origins);
        }

        @Bean
        UserAdministrationErrorAdvice userAdministrationErrorAdvice() {
            return new UserAdministrationErrorAdvice();
        }
    }

    /** One call as the port saw it. */
    record Call(String method, PrincipalRef actor, RequestOrigin origin, UUID organization, UUID principal, UUID role,
            ScopePath scope, UUID assignment, Optional<UUID> filter) {
    }

    static final class FakeAssignments implements AssignmentAdministration {
        final List<Call> calls = new ArrayList<>();
        final List<AssignmentView> bindings = new ArrayList<>();
        final List<RoleView> roles = new ArrayList<>();
        /** The organizations {@link #find} was asked in (the resolver's lookup). */
        final List<UUID> findOrganizations = new ArrayList<>();
        boolean created = true;
        RuntimeException failure;

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<RoleView> listRoles(PrincipalRef actor, UUID organizationId) {
            calls.add(new Call("listRoles", actor, null, organizationId, null, null, null, null, null));
            fail();
            return List.copyOf(roles);
        }

        @Override
        public List<AssignmentView> list(PrincipalRef actor, UUID organizationId, Optional<UUID> principalId) {
            calls.add(new Call("list", actor, null, organizationId, null, null, null, null, principalId));
            fail();
            return List.copyOf(bindings);
        }

        @Override
        public Optional<AssignmentView> find(UUID organizationId, UUID assignmentId) {
            findOrganizations.add(organizationId);
            return bindings.stream().filter(b -> b.assignmentId().equals(assignmentId)).findFirst();
        }

        @Override
        public Granted grant(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID principalId,
                UUID roleId, ScopePath scope) {
            calls.add(new Call("grant", actor, origin, organizationId, principalId, roleId, scope, null, null));
            fail();
            return new Granted(new AssignmentView(UUID.randomUUID(), principalId, roleId, scope), created);
        }

        @Override
        public void revoke(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID assignmentId) {
            calls.add(new Call("revoke", actor, origin, organizationId, null, null, null, assignmentId, null));
            fail();
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

    private MvcResult read(String path, String token) throws Exception {
        return mvc.perform(get(path).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private static String grantBody(UUID principal, UUID role, String scopeJson) {
        return "{\"principalId\":\"" + principal + "\",\"roleId\":\"" + role + "\",\"scope\":" + scopeJson + "}";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private ResourceRef node(ResourceKind kind, UUID id) {
        return ResourceRef.of(kind, id, new TenantId(organization));
    }

    private static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ADMINISTRATOR = UUID.fromString("00000000-0000-4000-8000-000000000001");

    // -------------------------------------------------------------------------------------- authentication

    @Test
    void everyOperationWithoutASessionIsUnauthenticatedAndReachesNoPort() throws Exception {
        UUID id = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(get(BASE + "/roles"), get(BASE + "/assignments"),
                post(BASE + "/assignments"), delete(BASE + "/assignments/" + id))) {
            MvcResult result = mvc.perform(request.header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(grantBody(id, OPERATOR, "{}"))).andReturn();
            assertEquals(401, result.getResponse().getStatus());
        }
        assertTrue(assignments.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
        assertTrue(assignments.findOrganizations.isEmpty(), "not even the resolver's lookup runs unauthenticated");
    }

    @Test
    void theTwoWritesWithoutACsrfTokenAreRefusedBeforeAuthorizationOrThePort() throws Exception {
        login("tok-csrf");
        UUID id = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(post(BASE + "/assignments"),
                delete(BASE + "/assignments/" + id))) {
            MvcResult result = mvc.perform(request.cookie(new Cookie(COOKIE, "tok-csrf"))
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(grantBody(id, OPERATOR, "{}"))).andReturn();
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("CSRF_REJECTED", json(result).get("code").asText());
        }
        assertTrue(assignments.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void everyOperationWhileMustRotateIsRefused428() throws Exception {
        Session rotating = GatewayTestSupport.newSession(UUID.randomUUID(), organization, true);
        context.getBean(FakeSessionAuthentication.class).register("tok-rot", rotating, "csrf-tok-rot");
        UUID id = UUID.randomUUID();
        assertEquals(428, send(get(BASE + "/roles"), "tok-rot", "").getResponse().getStatus());
        assertEquals(428, send(post(BASE + "/assignments"), "tok-rot", grantBody(id, OPERATOR, "{}")).getResponse()
                .getStatus());
        assertEquals(428, send(delete(BASE + "/assignments/" + id), "tok-rot", "").getResponse().getStatus());
        assertTrue(assignments.calls.isEmpty());
    }

    // ----------------------------------------------------------------------------------------------- reads

    @Test
    void theReadsAreRoleViewAtTheSessionsOrganizationAndPassItWithTheOptionalFilter() throws Exception {
        Session session = login("tok-read");
        UUID role = UUID.randomUUID();
        assignments.roles.add(new RoleView(role, "Viewer", List.of("config.view", "health.view")));
        UUID principal = UUID.randomUUID();
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), principal, role,
                ScopePath.environment(organization, UUID.randomUUID(), UUID.randomUUID()));
        assignments.bindings.add(binding);

        MvcResult roles = read(BASE + "/roles", "tok-read");
        MvcResult all = read(BASE + "/assignments", "tok-read");
        MvcResult filtered = read(BASE + "/assignments?principalId=" + principal, "tok-read");

        assertEquals(200, roles.getResponse().getStatus());
        JsonNode first = json(roles).get("items").get(0);
        assertEquals(role.toString(), first.get("id").asText());
        assertEquals("Viewer", first.get("name").asText());
        assertEquals("config.view", first.get("permissions").get(0).asText());
        JsonNode item = json(all).get("items").get(0);
        assertEquals(binding.assignmentId().toString(), item.get("assignmentId").asText());
        assertEquals(principal.toString(), item.get("principalId").asText());
        assertEquals(role.toString(), item.get("roleId").asText());
        assertEquals(binding.scope().environment().toString(), item.at("/scope/environmentId").asText());
        assertEquals(organization.toString(), item.at("/scope/organizationId").asText(), "responses may carry it");
        assertEquals(List.of(Permission.ROLE_VIEW, Permission.ROLE_VIEW, Permission.ROLE_VIEW), authorization.permissions);
        assertEquals(List.of(node(ResourceKind.ORGANIZATION, organization), node(ResourceKind.ORGANIZATION, organization),
                node(ResourceKind.ORGANIZATION, organization)), authorization.resources);
        assertEquals(session.principal(), assignments.calls.get(0).actor());
        assertEquals(organization, assignments.calls.get(0).organization());
        assertEquals(Optional.empty(), assignments.calls.get(1).filter());
        assertEquals(Optional.of(principal), assignments.calls.get(2).filter());
        assertEquals(200, filtered.getResponse().getStatus());
    }

    @Test
    void aMalformedPrincipalFilterIs400() throws Exception {
        login("tok-filter");
        MvcResult result = read(BASE + "/assignments?principalId=not-a-uuid", "tok-filter");
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(result).get("code").asText());
        assertTrue(assignments.calls.isEmpty());
    }

    // ----------------------------------------------------------------------------------------------- grant

    @Test
    void aGrantIsAuthorizedAsRoleAssignAtTheNodeTheBodyNamesNotAtTheOrganization() throws Exception {
        login("tok-grant");
        UUID principal = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        send(post(BASE + "/assignments"), "tok-grant", grantBody(principal, OPERATOR, "{}"));
        send(post(BASE + "/assignments"), "tok-grant", grantBody(principal, OPERATOR,
                "{\"subOrganizationId\":\"" + sub + "\"}"));
        send(post(BASE + "/assignments"), "tok-grant", grantBody(principal, OPERATOR,
                "{\"subOrganizationId\":\"" + sub + "\",\"environmentId\":\"" + env + "\"}"));
        send(post(BASE + "/assignments"), "tok-grant", grantBody(principal, OPERATOR,
                "{\"subOrganizationId\":\"" + sub + "\",\"environmentId\":\"" + env + "\",\"solutionId\":\""
                        + solution + "\"}"));
        send(post(BASE + "/assignments"), "tok-grant", grantBody(principal, OPERATOR,
                "{\"subOrganizationId\":\"" + sub + "\",\"environmentId\":\"" + env + "\",\"solutionId\":\""
                        + solution + "\",\"instanceId\":\"" + instance + "\"}"));

        assertEquals(List.of(Permission.ROLE_ASSIGN, Permission.ROLE_ASSIGN, Permission.ROLE_ASSIGN,
                Permission.ROLE_ASSIGN, Permission.ROLE_ASSIGN), authorization.permissions);
        assertEquals(List.of(node(ResourceKind.ORGANIZATION, organization), node(ResourceKind.SUB_ORGANIZATION, sub),
                node(ResourceKind.ENVIRONMENT, env), node(ResourceKind.SOLUTION, solution),
                node(ResourceKind.INSTANCE, instance)), authorization.resources);
        assertEquals(5, assignments.calls.size());
    }

    @Test
    void aGrantPassesTheSessionsPrincipalAndOrganizationAndTheFullPathAndAnswers201OrTheReplays200() throws Exception {
        Session session = login("tok-path");
        UUID principal = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        String scope = "{\"subOrganizationId\":\"" + sub + "\",\"environmentId\":\"" + env + "\"}";

        MvcResult created = send(post(BASE + "/assignments"), "tok-path", grantBody(principal, OPERATOR, scope));
        assignments.created = false;
        MvcResult replay = send(post(BASE + "/assignments"), "tok-path", grantBody(principal, OPERATOR, scope));

        assertEquals(201, created.getResponse().getStatus());
        assertEquals(200, replay.getResponse().getStatus());
        JsonNode body = json(created);
        assertEquals(principal.toString(), body.get("principalId").asText());
        assertEquals(OPERATOR.toString(), body.get("roleId").asText());
        assertEquals(env.toString(), body.at("/scope/environmentId").asText());
        assertEquals(organization.toString(), body.at("/scope/organizationId").asText());
        Call call = assignments.calls.get(0);
        assertEquals(session.principal(), call.actor());
        assertEquals(organization, call.organization());
        assertEquals(ScopePath.environment(organization, sub, env), call.scope());
        assertEquals(principal, call.principal());
        assertEquals(OPERATOR, call.role());
        assertTrue(call.origin().correlationId() != null);
    }

    @Test
    void aDeniedGrantIs403AndANodeTheCallerCannotSeeIs404AndNeitherReachesThePort() throws Exception {
        login("tok-grant-deny");
        String body = grantBody(UUID.randomUUID(), OPERATOR, "{\"subOrganizationId\":\"" + UUID.randomUUID() + "\"}");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        MvcResult denied = send(post(BASE + "/assignments"), "tok-grant-deny", body);
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;
        MvcResult hidden = send(post(BASE + "/assignments"), "tok-grant-deny", body);

        assertEquals(403, denied.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(denied).get("code").asText());
        assertEquals(404, hidden.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(hidden).get("code").asText());
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void aMalformedGrantBodyIs400BeforeAnyAuthorizationAndReachesNoPort() throws Exception {
        login("tok-grant-bad");
        UUID p = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        for (String body : new String[] {
                "", "not json", "[]", "{}",
                "{\"principalId\":\"" + p + "\",\"roleId\":\"" + OPERATOR + "\"}",                      // no scope
                "{\"principalId\":\"" + p + "\",\"roleId\":\"" + OPERATOR + "\",\"scope\":null}",
                "{\"principalId\":\"" + p + "\",\"roleId\":\"" + OPERATOR + "\",\"scope\":\"x\"}",
                grantBody(p, OPERATOR, "{\"subOrganizationId\":\"nope\"}"),
                // I5: a scope may not name an organization
                grantBody(p, OPERATOR, "{\"organizationId\":\"" + UUID.randomUUID() + "\"}"),
                grantBody(p, OPERATOR, "{\"bogus\":1}"),
                // a hole: a solution with no environment under it is not a position in the tree
                grantBody(p, OPERATOR, "{\"solutionId\":\"" + s + "\"}"),
                grantBody(p, OPERATOR, "{\"subOrganizationId\":\"" + s + "\",\"solutionId\":\"" + s + "\"}"),
                "{\"principalId\":\"nope\",\"roleId\":\"" + OPERATOR + "\",\"scope\":{}}",
                "{\"principalId\":\"" + p + "\",\"scope\":{}}",
                "{\"principalId\":\"" + p + "\",\"roleId\":\"" + OPERATOR + "\",\"scope\":{},\"organizationId\":\""
                        + UUID.randomUUID() + "\"}"}) {
            MvcResult result = send(post(BASE + "/assignments"), "tok-grant-bad", body);
            assertEquals(400, result.getResponse().getStatus(), body);
            assertEquals("BAD_REQUEST", json(result).get("code").asText(), body);
        }
        assertTrue(assignments.calls.isEmpty(), "a malformed body reaches no port");
    }

    @Test
    void aBodyThatIsNotAScopeIsRefusedBeforeTheVerbIsAsked() throws Exception {
        login("tok-grant-noscope");
        UUID p = UUID.randomUUID();
        for (String body : new String[] {"{}", "not json", "{\"principalId\":\"" + p + "\",\"roleId\":\"" + OPERATOR + "\"}",
                grantBody(p, OPERATOR, "{\"organizationId\":\"" + UUID.randomUUID() + "\"}"),
                grantBody(p, OPERATOR, "{\"solutionId\":\"" + UUID.randomUUID() + "\"}")}) {
            assertEquals(400, send(post(BASE + "/assignments"), "tok-grant-noscope", body).getResponse().getStatus(), body);
        }
        assertTrue(authorization.permissions.isEmpty(), "the resolver cannot name a node, so no verb is evaluated");
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void theServicesGrantRefusalsBecome422And409And404() throws Exception {
        login("tok-grant-svc");
        String body = grantBody(UUID.randomUUID(), ADMINISTRATOR, "{}");

        assignments.failure = new AssignmentInvalidException(AssignmentInvalidException.Reason.ADMINISTRATOR_NOT_GRANTABLE);
        MvcResult administrator = send(post(BASE + "/assignments"), "tok-grant-svc", body);
        assertEquals(422, administrator.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(administrator).get("code").asText());
        assertEquals("the Administrator role is not granted through this surface",
                json(administrator).get("detail").asText());

        assignments.failure = new AssignmentInvalidException(AssignmentInvalidException.Reason.UNKNOWN_ROLE);
        assertEquals(422, send(post(BASE + "/assignments"), "tok-grant-svc", body).getResponse().getStatus());

        assignments.failure = new AssignmentConflictException(AssignmentConflictException.Reason.PRINCIPAL_DEACTIVATED);
        MvcResult deactivated = send(post(BASE + "/assignments"), "tok-grant-svc", body);
        assertEquals(409, deactivated.getResponse().getStatus());
        assertEquals("CONFLICT", json(deactivated).get("code").asText());

        assignments.failure = new ResourceNotFoundException(ResourceKind.PRINCIPAL);
        assertEquals(404, send(post(BASE + "/assignments"), "tok-grant-svc", body).getResponse().getStatus());
    }

    @Test
    void theHandlerRefusesToGrantAtAScopeOtherThanTheOneThatWasAuthorized() {
        UUID env = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", BASE + "/assignments");
        raw.setContent(("{\"scope\":{\"subOrganizationId\":\"" + sub + "\",\"environmentId\":\"" + env + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
        PeekableBodyRequest request = new PeekableBodyRequest(raw);
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        ResourceRef authorized = new GrantedScopeResolver().resolve(request, session);
        assertEquals(node(ResourceKind.ENVIRONMENT, env), authorized);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        GrantedScopeResolver.requireAuthorized(ScopePath.environment(organization, sub, env), organization);
        // a parser differential (or a mis-wired route) that hands the handler a different node is refused
        assertThrows(IllegalStateException.class, () -> GrantedScopeResolver.requireAuthorized(
                ScopePath.environment(organization, sub, UUID.randomUUID()), organization));
        assertThrows(IllegalStateException.class, () -> GrantedScopeResolver.requireAuthorized(
                ScopePath.organization(organization), organization));
    }

    /** The handler itself must make the check: a grant whose bound scope differs from the authorized one reaches no port. */
    @Test
    void theGrantHandlerRefusesWhenTheBoundScopeIsNotTheAuthorizedNodeAndReachesNoPort() {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", BASE + "/assignments");
        raw.setAttribute(GrantedScopeResolver.AUTHORIZED, node(ResourceKind.ENVIRONMENT, UUID.randomUUID()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(raw));
        RoleAssignmentController controller = context.getBean(RoleAssignmentController.class);
        GrantAssignmentRequest bound = new GrantAssignmentRequest(UUID.randomUUID(), OPERATOR, ScopeRef.ORGANIZATION);

        raw.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, UUID.randomUUID());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> controller.grantAssignment(bound, session));
        assertTrue(refused.getMessage().contains("not the scope that was authorized"), refused.getMessage());
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void aRequestThatDidNotComeThroughTheFilterIsNeverAuthorizedAtAGuess() {
        MockHttpServletRequest unwrapped = new MockHttpServletRequest("POST", BASE + "/assignments");
        unwrapped.setContent("{\"scope\":{}}".getBytes(StandardCharsets.UTF_8));
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);

        assertThrows(IllegalStateException.class, () -> new GrantedScopeResolver().resolve(unwrapped, session));
    }

    @Test
    void withNoAuthorizationRecordedTheGrantHandlerRefuses() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", BASE + "/assignments")));
        assertThrows(IllegalStateException.class,
                () -> GrantedScopeResolver.requireAuthorized(ScopePath.organization(organization), organization));
    }

    // ---------------------------------------------------------------------------------------------- revoke

    @Test
    void aRevokeIsAuthorizedAsRoleAssignAtTheBindingsOwnScopeAndTheResolverLooksInTheSessionsOrganization() throws Exception {
        Session session = login("tok-revoke");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        AssignmentView atEnvironment = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), OPERATOR,
                ScopePath.environment(organization, sub, env));
        AssignmentView atOrganization = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), OPERATOR,
                ScopePath.organization(organization));
        AssignmentView atInstall = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), OPERATOR, ScopePath.INSTALL);
        assignments.bindings.addAll(List.of(atEnvironment, atOrganization, atInstall));

        for (AssignmentView binding : List.of(atEnvironment, atOrganization, atInstall)) {
            MvcResult result = send(delete(BASE + "/assignments/" + binding.assignmentId()), "tok-revoke", "");
            assertEquals(204, result.getResponse().getStatus());
            assertEquals("", result.getResponse().getContentAsString());
        }

        assertEquals(List.of(Permission.ROLE_ASSIGN, Permission.ROLE_ASSIGN, Permission.ROLE_ASSIGN),
                authorization.permissions);
        assertEquals(List.of(node(ResourceKind.ENVIRONMENT, env), node(ResourceKind.ORGANIZATION, organization),
                node(ResourceKind.ROLE_DEFINITION, OPERATOR)), authorization.resources,
                "an install-scope binding names no node, so it is authorized through its role definition");
        assertEquals(List.of(organization, organization, organization), assignments.findOrganizations);
        for (Call call : assignments.calls) {
            assertEquals("revoke", call.method());
            assertEquals(session.principal(), call.actor());
            assertEquals(organization, call.organization());
        }
        assertEquals(List.of(atEnvironment.assignmentId(), atOrganization.assignmentId(), atInstall.assignmentId()),
                assignments.calls.stream().map(Call::assignment).toList());
    }

    @Test
    void anAssignmentThatIsNotThereOrNotAUuidIs404AndNothingIsAuthorizedOrRevoked() throws Exception {
        login("tok-revoke-none");
        for (String id : new String[] {UUID.randomUUID().toString(), "not-a-uuid"}) {
            MvcResult result = send(delete(BASE + "/assignments/" + id), "tok-revoke-none", "");
            assertEquals(404, result.getResponse().getStatus(), id);
            assertEquals("NOT_FOUND", json(result).get("code").asText(), id);
        }
        assertTrue(authorization.permissions.isEmpty());
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void aRevokeTheCallerMayNotMakeIs403OrAnAbsent404AndNeverReachesThePort() throws Exception {
        login("tok-revoke-deny");
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), OPERATOR,
                ScopePath.organization(organization));
        assignments.bindings.add(binding);
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        assertEquals(403, send(delete(BASE + "/assignments/" + binding.assignmentId()), "tok-revoke-deny", "")
                .getResponse().getStatus());
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;
        assertEquals(404, send(delete(BASE + "/assignments/" + binding.assignmentId()), "tok-revoke-deny", "")
                .getResponse().getStatus());
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void anAdministratorBindingIs409AndTheServicesAbsenceIs404() throws Exception {
        login("tok-revoke-svc");
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), ADMINISTRATOR,
                ScopePath.organization(organization));
        assignments.bindings.add(binding);

        assignments.failure = new AssignmentConflictException(AssignmentConflictException.Reason.ADMINISTRATOR_BINDING);
        MvcResult conflict = send(delete(BASE + "/assignments/" + binding.assignmentId()), "tok-revoke-svc", "");
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("CONFLICT", json(conflict).get("code").asText());
        assertFalse(conflict.getResponse().getContentAsString().contains(binding.assignmentId().toString()));

        assignments.failure = new ResourceNotFoundException("role assignment");
        assertEquals(404, send(delete(BASE + "/assignments/" + binding.assignmentId()), "tok-revoke-svc", "")
                .getResponse().getStatus());
    }
}
