package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.PrincipalAdministration;
import com.solace.sm.cp.api.identity.PrincipalConflictException;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The four user operations (plan 013 T3.2; D-REG 5.4 U1 to U3: {@code listUsers}, {@code createUser},
 * {@code deactivateUser}, {@code resetUserCredential}) over the real filter, interceptor, resolver, controller and advice
 * pipeline, with {@link PrincipalAdministration} faked.
 *
 * <p>What this proves that the {@code cp-core} tests cannot: that the verb is the INTERCEPTOR's, at the right resource,
 * before the body is read; that the organization the service is handed is the session's and no request field can name
 * another ({@code I5}); that a bad body is {@code 400} and reaches no port; that a credential is zeroised after the
 * call and appears in no response or message ({@code I6}); that the two credential-bearing routes refuse an insecure
 * channel before anyone looks at them (OQ-14 (b)); and how the service's refusals become status codes.
 */
class UserAdministrationControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/cp/admin/users";
    /** A recognisable marker: if any byte of it shows up in a response or a captured line, the credential leaked. */
    private static final String SECRET = "SECRETMARKER-7d31-do-not-leak";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakePrincipals users;
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
        users = context.getBean(FakePrincipals.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
    }

    // ---------------------------------------------------------------------------------------------- the rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        FakePrincipals fakePrincipals() {
            return new FakePrincipals();
        }

        @Bean
        SessionOrganizationResourceResolver sessionOrganizationResourceResolver() {
            return new SessionOrganizationResourceResolver();
        }

        @Bean
        UserResolver userResolver() {
            return new UserResolver();
        }

        @Bean
        UserAdministrationController userAdministrationController(FakePrincipals principals,
                RequestOriginFactory origins) {
            return new UserAdministrationController(principals, origins);
        }

        @Bean
        UserAdministrationErrorAdvice userAdministrationErrorAdvice() {
            return new UserAdministrationErrorAdvice();
        }
    }

    /** One call as the port saw it. */
    record Call(String method, PrincipalRef actor, RequestOrigin origin, UUID organization, UUID user, String username,
            char[] credential) {
    }

    static final class FakePrincipals implements PrincipalAdministration {
        final List<Call> calls = new ArrayList<>();
        final List<UserView> rows = new ArrayList<>();
        RuntimeException failure;

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }

        static UserView view(UUID id, String username, UserView.Status status, boolean mustRotate) {
            return new UserView(id, username, PrincipalSource.LOCAL, status, mustRotate,
                    Instant.parse("2026-10-08T10:15:30Z"));
        }

        @Override
        public List<UserView> list(PrincipalRef actor, UUID organizationId) {
            calls.add(new Call("list", actor, null, organizationId, null, null, null));
            fail();
            return List.copyOf(rows);
        }

        @Override
        public UserView create(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String username,
                char[] initialCredential) {
            calls.add(new Call("create", actor, origin, organizationId, null, username, initialCredential));
            fail();
            UserView created = view(UUID.randomUUID(), username, UserView.Status.ACTIVE, true);
            rows.add(created);
            return created;
        }

        @Override
        public UserView deactivate(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId) {
            calls.add(new Call("deactivate", actor, origin, organizationId, userId, null, null));
            fail();
            return view(userId, "someone", UserView.Status.DEACTIVATED, false);
        }

        @Override
        public UserView resetCredential(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID userId,
                char[] resetCredential) {
            calls.add(new Call("reset", actor, origin, organizationId, userId, null, resetCredential));
            fail();
            return view(userId, "someone", UserView.Status.ACTIVE, true);
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

    private MvcResult read(String token) throws Exception {
        return mvc.perform(get(BASE).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private static String createBody(String username, String credential) {
        return "{\"username\":" + MAPPER.valueToTree(username) + ",\"initialCredential\":"
                + MAPPER.valueToTree(credential) + "}";
    }

    private static String resetBody(String credential) {
        return "{\"resetCredential\":" + MAPPER.valueToTree(credential) + "}";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static ResourceRef organizationOf(UUID organization) {
        return ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization));
    }

    /** The four operations, each as a request builder (a fresh user id for the two that name one). */
    private List<MockHttpServletRequestBuilder> allFour(UUID user) {
        return List.of(get(BASE), post(BASE), post(BASE + "/" + user + "/deactivate"),
                put(BASE + "/" + user + "/credential"));
    }

    private static String bodyFor(MockHttpServletRequestBuilder request, int index) {
        return switch (index) {
            case 1 -> createBody("operator-1", SECRET + "-0123456789");
            case 3 -> resetBody(SECRET + "-0123456789");
            default -> "";
        };
    }

    // -------------------------------------------------------------------------------------- authentication

    @Test
    void everyOperationWithoutASessionIsUnauthenticatedAndReachesNoPort() throws Exception {
        List<MockHttpServletRequestBuilder> requests = allFour(UUID.randomUUID());
        for (int i = 0; i < requests.size(); i++) {
            MvcResult result = mvc.perform(requests.get(i).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(bodyFor(requests.get(i), i))).andReturn();
            assertEquals(401, result.getResponse().getStatus());
        }
        assertTrue(users.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void everyWriteWithoutACsrfTokenIsRefusedBeforeAuthorizationOrThePort() throws Exception {
        login("tok-csrf");
        List<MockHttpServletRequestBuilder> requests = allFour(UUID.randomUUID());
        for (int i = 1; i < requests.size(); i++) {
            MvcResult result = mvc.perform(requests.get(i).cookie(new Cookie(COOKIE, "tok-csrf"))
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).content(bodyFor(requests.get(i), i))).andReturn();
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("CSRF_REJECTED", json(result).get("code").asText());
        }
        assertTrue(users.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    @Test
    void everyOperationWhileMustRotateIsRefused428() throws Exception {
        Session rotating = GatewayTestSupport.newSession(UUID.randomUUID(), organization, true);
        context.getBean(FakeSessionAuthentication.class).register("tok-rot", rotating, "csrf-tok-rot");
        List<MockHttpServletRequestBuilder> requests = allFour(UUID.randomUUID());
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(428, send(requests.get(i), "tok-rot", bodyFor(requests.get(i), i)).getResponse().getStatus());
        }
        assertTrue(users.calls.isEmpty());
    }

    // ------------------------------------------------------------------ authorization: verb, resource, order

    @Test
    void eachOperationIsAuthorizedWithItsOwnVerbAtTheResourceTheContractNamesBeforeThePortIsCalled() throws Exception {
        login("tok-authz");
        UUID user = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> requests = allFour(user);
        for (int i = 0; i < requests.size(); i++) {
            send(requests.get(i), "tok-authz", bodyFor(requests.get(i), i));
        }

        assertEquals(List.of(Permission.USER_VIEW, Permission.USER_CREATE, Permission.USER_DEACTIVATE,
                Permission.USER_EDIT), authorization.permissions);
        assertEquals(List.of(organizationOf(organization), organizationOf(organization),
                ResourceRef.of(ResourceKind.PRINCIPAL, user, new TenantId(organization)),
                ResourceRef.of(ResourceKind.PRINCIPAL, user, new TenantId(organization))), authorization.resources);
        assertEquals(4, users.calls.size());
    }

    @Test
    void aDeniedVerbIs403AndAResourceTheCallerCannotSeeIs404AndNeitherReachesThePort() throws Exception {
        login("tok-deny");
        List<MockHttpServletRequestBuilder> requests = allFour(UUID.randomUUID());
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;
        for (int i = 0; i < requests.size(); i++) {
            MvcResult result = send(requests.get(i), "tok-deny", bodyFor(requests.get(i), i));
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("FORBIDDEN", json(result).get("code").asText());
        }
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;
        // fresh builders: a builder remembers the cookie it was given, and two session cookies are refused outright
        List<MockHttpServletRequestBuilder> again = allFour(UUID.randomUUID());
        for (int i = 2; i < again.size(); i++) {
            MvcResult result = send(again.get(i), "tok-deny", bodyFor(again.get(i), i));
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertTrue(users.calls.isEmpty(), "a refusal reaches no port");
    }

    @Test
    void aUserIdThatIsNotAUuidNamesNothingAndIsNotEvenAuthorized() throws Exception {
        login("tok-bad-id");
        for (MockHttpServletRequestBuilder request : List.of(post(BASE + "/not-a-uuid/deactivate"),
                put(BASE + "/not-a-uuid/credential"))) {
            MvcResult result = send(request, "tok-bad-id", resetBody(SECRET + "-0123456789"));
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertTrue(users.calls.isEmpty());
        assertTrue(authorization.permissions.isEmpty());
    }

    // ----------------------------------------------------------------------------------------------- reads

    @Test
    void listUsersPassesTheSessionsOrganizationAndNeverCarriesACredential() throws Exception {
        Session session = login("tok-list");
        UUID id = UUID.randomUUID();
        users.rows.add(FakePrincipals.view(id, "alice", UserView.Status.ACTIVE, false));
        users.rows.add(FakePrincipals.view(UUID.randomUUID(), "bob", UserView.Status.DEACTIVATED, true));

        MvcResult result = read("tok-list");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode items = json(result).get("items");
        assertEquals(2, items.size());
        assertEquals(id.toString(), items.get(0).get("id").asText());
        assertEquals("alice", items.get(0).get("username").asText());
        assertEquals("local", items.get(0).get("source").asText());
        assertEquals("active", items.get(0).get("status").asText());
        assertFalse(items.get(0).get("mustRotate").asBoolean());
        assertEquals("2026-10-08T10:15:30Z", items.get(0).get("createdAt").asText());
        assertEquals("deactivated", items.get(1).get("status").asText());
        for (JsonNode item : items) {
            assertEquals(java.util.Set.of("id", "username", "source", "status", "mustRotate", "createdAt"),
                    fieldNames(item), "no credential, hash or organization on a user (I6, I5)");
        }
        Call call = users.calls.get(0);
        assertEquals(session.principal(), call.actor());
        assertEquals(organization, call.organization());
    }

    // ---------------------------------------------------------------------------------------------- create

    @Test
    void createUserPassesTheSessionsPrincipalOrganizationAndOriginAnd201sTheUserWithoutTheCredential() throws Exception {
        Session session = login("tok-create");

        MvcResult result = send(post(BASE), "tok-create", createBody("Operator.One", SECRET + "-0123456789"));

        assertEquals(201, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("Operator.One", body.get("username").asText(), "kept exactly as given");
        assertEquals("active", body.get("status").asText());
        assertTrue(body.get("mustRotate").asBoolean(), "a provisional credential: the first sign-in rotates it");
        assertFalse(result.getResponse().getContentAsString().contains(SECRET));
        Call call = users.calls.get(0);
        assertEquals("create", call.method());
        assertEquals(session.principal(), call.actor());
        assertEquals(organization, call.organization());
        assertNotNull(call.origin().correlationId());
        assertEquals("Operator.One", call.username());
    }

    @Test
    void theCredentialIsZeroisedAfterTheCallWhateverTheOutcome() throws Exception {
        login("tok-zero");
        send(post(BASE), "tok-zero", createBody("operator-1", SECRET + "-0123456789"));
        users.failure = new PrincipalConflictException(PrincipalConflictException.Reason.USERNAME_TAKEN);
        send(post(BASE), "tok-zero", createBody("operator-2", SECRET + "-0123456789"));
        users.failure = null;
        send(put(BASE + "/" + UUID.randomUUID() + "/credential"), "tok-zero", resetBody(SECRET + "-0123456789"));
        users.failure = new ResourceNotFoundException(ResourceKind.PRINCIPAL);
        send(put(BASE + "/" + UUID.randomUUID() + "/credential"), "tok-zero", resetBody(SECRET + "-0123456789"));

        assertEquals(4, users.calls.size());
        for (Call call : users.calls) {
            assertTrue(new String(call.credential()).chars().allMatch(c -> c == 0),
                    call.method() + ": the array the service was given is cleared once it returns");
        }
    }

    @Test
    void aMalformedCreateBodyIs400AndReachesNoPort() throws Exception {
        login("tok-bad-body");
        String twelve = "x".repeat(12);
        for (String body : new String[] {
                "", "not json", "[]", "{}",
                "{\"username\":\"operator\"}",
                "{\"initialCredential\":\"" + twelve + "\"}",
                createBody("", twelve),
                createBody("x".repeat(65), twelve),
                createBody("operator", "x".repeat(11)),
                createBody("operator", "x".repeat(1025)),
                "{\"username\":\"operator\",\"initialCredential\":123456789012}",
                "{\"username\":12345,\"initialCredential\":\"" + twelve + "\"}",
                "{\"username\":null,\"initialCredential\":\"" + twelve + "\"}",
                // I5: nothing in the body may name an organization, and nothing stray is silently ignored
                "{\"username\":\"operator\",\"initialCredential\":\"" + twelve + "\",\"organizationId\":\""
                        + UUID.randomUUID() + "\"}",
                "{\"username\":\"operator\",\"initialCredential\":\"" + twelve + "\",\"organization\":\"x\"}"}) {
            MvcResult result = send(post(BASE), "tok-bad-body", body);
            assertEquals(400, result.getResponse().getStatus(), body);
            assertEquals("BAD_REQUEST", json(result).get("code").asText(), body);
        }
        assertTrue(users.calls.isEmpty(), "a malformed body reaches no port");
    }

    @Test
    void aUsernameTheServiceWillNotStoreIs400WithoutEchoingIt() throws Exception {
        login("tok-shape");
        users.failure = new IllegalArgumentException("a username is 1 to 64 characters: SHAPEMARKER");

        MvcResult result = send(post(BASE), "tok-shape", createBody("has space", SECRET + "-0123456789"));

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(result).get("code").asText());
        String text = result.getResponse().getContentAsString();
        assertFalse(text.contains("SHAPEMARKER"), text);
        assertFalse(text.contains("has space"), text);
        assertFalse(text.contains(SECRET), text);
    }

    @Test
    void aTakenUsernameIs409WithAFixedMessageThatNamesNoUsernameAndNoOrganization() throws Exception {
        login("tok-taken");
        users.failure = new PrincipalConflictException(PrincipalConflictException.Reason.USERNAME_TAKEN);

        MvcResult result = send(post(BASE), "tok-taken", createBody("operator-taken", SECRET + "-0123456789"));

        assertEquals(409, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("CONFLICT", body.get("code").asText());
        String text = result.getResponse().getContentAsString();
        assertFalse(text.contains("operator-taken"), text);
        assertFalse(text.contains(organization.toString()), text);
        assertFalse(text.contains(SECRET), text);
        assertEquals("that username is already taken", body.get("detail").asText());
    }

    // ------------------------------------------------------------------------------- deactivate and reset

    @Test
    void deactivateAndResetPassTheSessionsOrganizationAndThePathsUserAndAnswerTheUser() throws Exception {
        Session session = login("tok-act");
        UUID user = UUID.randomUUID();

        MvcResult deactivated = send(post(BASE + "/" + user + "/deactivate"), "tok-act", "");
        MvcResult reset = send(put(BASE + "/" + user + "/credential"), "tok-act", resetBody(SECRET + "-0123456789"));

        assertEquals(200, deactivated.getResponse().getStatus());
        assertEquals("deactivated", json(deactivated).get("status").asText());
        assertEquals(200, reset.getResponse().getStatus());
        assertTrue(json(reset).get("mustRotate").asBoolean());
        assertFalse(reset.getResponse().getContentAsString().contains(SECRET));
        assertEquals("deactivate", users.calls.get(0).method());
        assertEquals("reset", users.calls.get(1).method());
        for (Call call : users.calls) {
            assertEquals(session.principal(), call.actor());
            assertEquals(organization, call.organization());
            assertEquals(user, call.user());
        }
    }

    @Test
    void aMalformedResetBodyIs400AndReachesNoPort() throws Exception {
        login("tok-bad-reset");
        UUID user = UUID.randomUUID();
        for (String body : new String[] {"", "{}", "{\"resetCredential\":\"short\"}", "{\"resetCredential\":123456789012}",
                "{\"resetCredential\":\"" + "x".repeat(1025) + "\"}",
                "{\"resetCredential\":\"" + "x".repeat(12) + "\",\"status\":\"deactivated\"}"}) {
            assertEquals(400, send(put(BASE + "/" + user + "/credential"), "tok-bad-reset", body).getResponse()
                    .getStatus(), body);
        }
        assertTrue(users.calls.isEmpty());
    }

    @Test
    void theServicesConflictsAndAbsencesBecome409And404() throws Exception {
        login("tok-svc");
        UUID user = UUID.randomUUID();
        for (PrincipalConflictException.Reason reason : PrincipalConflictException.Reason.values()) {
            users.failure = new PrincipalConflictException(reason);
            MvcResult result = send(post(BASE + "/" + user + "/deactivate"), "tok-svc", "");
            assertEquals(409, result.getResponse().getStatus(), reason.name());
            assertEquals("CONFLICT", json(result).get("code").asText(), reason.name());
        }
        users.failure = new ResourceNotFoundException(ResourceKind.PRINCIPAL);
        assertEquals(404, send(post(BASE + "/" + user + "/deactivate"), "tok-svc", "").getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------ OQ-14 (b): the transport

    @Test
    void aCredentialBearingRouteOverPlainHttpFromAnotherHostIsRefusedBeforeAnyoneLooksAtIt() throws Exception {
        login("tok-plain");
        String secret = SECRET + "-0123456789";
        for (Object[] route : new Object[][] {{post(BASE), createBody("operator", secret)},
                {put(BASE + "/" + UUID.randomUUID() + "/credential"), resetBody(secret)}}) {
            MvcResult result = mvc.perform(((MockHttpServletRequestBuilder) route[0]).with(r -> {
                r.setRemoteAddr("203.0.113.9");
                return r;
            }).cookie(new Cookie(COOKIE, "tok-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .header("X-SM-CSRF", "csrf-tok-plain").contentType(MediaType.APPLICATION_JSON)
                    .content((String) route[1])).andReturn();

            assertEquals(403, result.getResponse().getStatus());
            assertEquals("INSECURE_TRANSPORT", json(result).get("code").asText());
            assertFalse(result.getResponse().getContentAsString().contains(SECRET));
        }
        assertTrue(authorization.permissions.isEmpty(), "before the session, the CSRF check or the body");
        assertTrue(users.calls.isEmpty());
    }

    @Test
    void aSecureChannelFromAnywhereAndLoopbackBothPass() throws Exception {
        login("tok-secure");
        MvcResult secure = mvc.perform(post(BASE).with(r -> {
            r.setRemoteAddr("203.0.113.9");
            r.setSecure(true);
            return r;
        }).cookie(new Cookie(COOKIE, "tok-secure")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-secure").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("operator-a", SECRET + "-0123456789"))).andReturn();
        assertEquals(201, secure.getResponse().getStatus());

        MvcResult loopback = send(post(BASE), "tok-secure", createBody("operator-b", SECRET + "-0123456789"));
        assertEquals(201, loopback.getResponse().getStatus(), "MockMvc's default peer is 127.0.0.1");
    }

    @Test
    void theReadsAndDeactivateCarryNoCredentialSoTheyAreNotTransportGated() throws Exception {
        login("tok-clear");
        UUID user = UUID.randomUUID();
        MvcResult list = mvc.perform(get(BASE).with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        }).cookie(new Cookie(COOKIE, "tok-clear"))).andReturn();
        MvcResult deactivate = mvc.perform(post(BASE + "/" + user + "/deactivate").with(r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        }).cookie(new Cookie(COOKIE, "tok-clear")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-clear")).andReturn();
        assertEquals(200, list.getResponse().getStatus());
        assertEquals(200, deactivate.getResponse().getStatus());
    }

    // -------------------------------------------------------------------------------------- redaction (I6)

    @Test
    void theRequestRecordsNeverPrintACredential() {
        CreateUserRequest create = new CreateUserRequest(MAPPER.valueToTree("operator"),
                MAPPER.valueToTree(SECRET + "-0123456789"));
        ResetUserCredentialRequest reset = new ResetUserCredentialRequest(MAPPER.valueToTree(SECRET + "-0123456789"));

        assertFalse(create.toString().contains(SECRET), create.toString());
        assertTrue(create.toString().contains("[REDACTED]"));
        assertFalse(reset.toString().contains(SECRET), reset.toString());
        assertTrue(reset.toString().contains("[REDACTED]"));
        char[] fresh = create.credential();
        java.util.Arrays.fill(fresh, '\0');
        assertEquals(SECRET + "-0123456789", new String(create.credential()),
                "a fresh array each call: clearing one does not clear the record's value");
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
