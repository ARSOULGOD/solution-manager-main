package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.identity.AuthenticationUnavailableException;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.Harness;
import jakarta.servlet.http.Cookie;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests of the authenticated HTTP boundary — the real filter, interceptor, argument
 * resolver, controllers and advice, wired exactly as {@link GatewayWebConfiguration} wires
 * production, with fakes standing in only for the cp-api ports.
 */
@ExtendWith(OutputCaptureExtension.class)
class GatewayHttpBoundaryTest {

    private static final String LOGIN = "/api/cp/auth/login";
    /** What the user typed for the organization (OR-125 (4)): an id or a name, passed to the service unresolved. */
    private static final String ORGANIZATION = "Acme";
    private static final String LOGOUT = "/api/cp/auth/logout";
    private static final String IDENTITY = "/api/cp/auth/identity";
    private static final String CREDENTIAL = "/api/cp/auth/credential";
    private static final String CSRF_HEADER = "X-SM-CSRF";

    private final ObjectMapper mapper = new ObjectMapper();

    private Harness harness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        harness = GatewayTestSupport.newHarness();
        mvc = harness.mockMvc();
    }

    // ── login ────────────────────────────────────────────────────────────────────────────────

    @Test
    void loginSuccessSetsBothCookiesWithExactAttributesAndNeedsNoCsrf() throws Exception {
        UUID principalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        Session session = GatewayTestSupport.newSession(principalId, organizationId, false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("session-token-value"),
                        new CsrfToken("csrf-token-value"), session));

        MvcResult result = mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content(loginBody("operator", "correct-password")))
                .andExpect(status().isOk())
                .andReturn();

        String sessionCookie = firstHeaderStartingWith(result, "__Host-SM_SESSION=");
        String csrfCookie = firstHeaderStartingWith(result, "__Host-SM_CSRF=");
        assertTrue(sessionCookie.contains("HttpOnly"), "session cookie must be HttpOnly: " + sessionCookie);
        assertTrue(sessionCookie.contains("SameSite=Strict"), sessionCookie);
        assertTrue(sessionCookie.contains("Secure"), sessionCookie);
        // __Host- requires Path=/ (OR-21) — both cookies share it now, there is no separate
        // /api-scoped session-cookie path any more.
        assertExactCookiePath(sessionCookie, "/");
        // __Host- also FORBIDS a Domain attribute (m11) — a Domain=, even one matching this same
        // host, is what the __Host- prefix exists to rule out entirely.
        assertNoDomainAttribute(sessionCookie);

        assertFalse(csrfCookie.contains("HttpOnly"), "csrf cookie must NOT be HttpOnly: " + csrfCookie);
        assertTrue(csrfCookie.contains("SameSite=Strict"), csrfCookie);
        assertTrue(csrfCookie.contains("Secure"), csrfCookie);
        // __Host-SM_CSRF must be Path=/: a console served at / cannot read a cookie scoped to a
        // narrower path from document.cookie, defeating the "script-readable" CSRF-double-submit design.
        assertExactCookiePath(csrfCookie, "/");
        assertNoDomainAttribute(csrfCookie);

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("csrf-token-value", body.get("csrfToken").asText());
    }

    /** OR-125 (4): the typed organization reaches the service exactly as typed, beside the username and credential. */
    @Test
    void theTypedOrganizationReachesTheServiceUnresolvedBesideTheUsername() throws Exception {
        mvc.perform(loginAttempt("operator", "whatever")).andExpect(status().isUnauthorized());

        assertEquals(List.of("Acme"), harness.localAuthenticationService().organizations);
        assertEquals(List.of("operator"), harness.localAuthenticationService().attempts);
    }

    /**
     * OR-125 (4) / OR-131 Q3: the organization is OPTIONAL, but when present it is bounded like the username: an empty one,
     * one past 256 characters or one with surrounding whitespace (never trimmed, W5D-7) is a malformed request (400), which
     * says nothing about any organization. An organization that is well-formed but names nothing is the service's business
     * and is refused as a wrong credential is.
     */
    @Test
    void aLoginBodyWithAnUnusableOrganizationIsMalformedNotUnauthorized() throws Exception {
        for (String body : new String[] {
                "{\"organization\":\"\",\"username\":\"operator\",\"credential\":\"whatever\"}",
                "{\"organization\":\" acme\",\"username\":\"operator\",\"credential\":\"whatever\"}",
                "{\"organization\":\"acme \",\"username\":\"operator\",\"credential\":\"whatever\"}",
                "{\"organization\":\"" + "x".repeat(257) + "\",\"username\":\"operator\",\"credential\":\"whatever\"}"}) {
            mvc.perform(post(LOGIN).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(List.of(), harness.localAuthenticationService().attempts, "no malformed body reached the service");
    }

    /** OR-131 Q3: a body that omits the organization (absent or null) reaches the service with none; the service decides. */
    @Test
    void aLoginBodyWithoutAnOrganizationReachesTheServiceWithNone() throws Exception {
        for (String body : new String[] {
                "{\"username\":\"operator\",\"credential\":\"whatever\"}",
                "{\"organization\":null,\"username\":\"operator\",\"credential\":\"whatever\"}"}) {
            mvc.perform(post(LOGIN).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                            .contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(java.util.Arrays.asList(null, null), harness.localAuthenticationService().organizations);
    }

    /** OR-131 Q3: the service's "several organizations, none named" is a 400 BAD_REQUEST, not a 401. */
    @Test
    void anOmittedOrganizationOnAnInstallWithSeveralIsABadRequest() throws Exception {
        harness.localAuthenticationService().onAuthenticate("several", () -> {
            throw new com.solace.sm.cp.api.identity.LoginOrganizationRequiredException();
        });

        mvc.perform(post(LOGIN).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content("{\"username\":\"several\",\"credential\":\"whatever\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(problemCode("BAD_REQUEST"));
    }

    @Test
    void loginResponseCarriesTheCurrentIdentityShape() throws Exception {
        UUID principalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        Session session = GatewayTestSupport.newSession(principalId, organizationId, false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("session-token-value"),
                        new CsrfToken("csrf-token-value"), session));
        harness.identityDirectory().registerRoleNames(principalId, List.of("Administrator"));

        MvcResult result = mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content(loginBody("operator", "correct-password")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(principalId.toString(), body.get("principalId").asText());
        assertEquals("operator", body.get("username").asText());
        assertEquals("local", body.get("source").asText());
        assertFalse(body.get("mustRotate").asBoolean());
        assertTrue(body.get("permissions").isArray());
        assertTrue(body.get("organizations").isArray());
        assertEquals("csrf-token-value", body.get("csrfToken").asText());
    }

    @Test
    void loginResponseHasEmptyIdentityCollectionsWhenUnbound() throws Exception {
        UUID principalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        Session session = GatewayTestSupport.newSession(principalId, organizationId, false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("session-token-value"),
                        new CsrfToken("csrf-token-value"), session));
        // no registerRoleNames call — matches a principal with no role bindings.

        MvcResult result = mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content(loginBody("operator", "correct-password")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("permissions").isEmpty());
        assertTrue(body.get("organizations").isEmpty());
    }

    @Test
    void loginFromAForeignOriginIsRefused() throws Exception {
        mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.FOREIGN_ORIGIN)
                        .contentType("application/json")
                        .content(loginBody("operator", "whatever")))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("ORIGIN_REJECTED"));
    }

    @Test
    void loginWithNoOriginAndNoRefererIsRefused() throws Exception {
        mvc.perform(post(LOGIN)
                        .contentType("application/json")
                        .content(loginBody("operator", "whatever")))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("ORIGIN_REJECTED"));
    }

    @Test
    void loginWithATrustedRefererAndNoOriginIsAccepted() throws Exception {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("tok"), new CsrfToken("csrf"), session));

        mvc.perform(post(LOGIN)
                        .header("Referer", GatewayTestSupport.TRUSTED_ORIGIN + "/console/login")
                        .contentType("application/json")
                        .content(loginBody("operator", "whatever")))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithARefererCarryingAnExplicitDefaultPortMatchesATrustedOriginWithNone() throws Exception {
        // TRUSTED_ORIGIN is "https://console.example.test" (no port). A browser never sends an
        // explicit :443 for https, but the Referer normalization must still treat the two as the
        // same origin rather than rejecting on a literal string mismatch.
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("tok-443"), new CsrfToken("csrf-443"), session));

        mvc.perform(post(LOGIN)
                        .header("Referer", GatewayTestSupport.TRUSTED_ORIGIN + ":443/console/login")
                        .contentType("application/json")
                        .content(loginBody("operator", "whatever")))
                .andExpect(status().isOk());
    }

    @Test
    void unknownWrongInactiveAndLockedLoginsAllReturnByteIdenticalBodiesExceptCorrelationId() throws Exception {
        // The fake throws LoginRefusedException for any username with no configured outcome —
        // exactly what unknown/wrong/inactive/locked collapse to in production.
        MvcResult first = mvc.perform(loginAttempt("nobody", "whatever")).andExpect(status().isUnauthorized()).andReturn();
        MvcResult second = mvc.perform(loginAttempt("also-nobody", "whatever")).andExpect(status().isUnauthorized()).andReturn();

        JsonNode a = mapper.readTree(first.getResponse().getContentAsString());
        JsonNode b = mapper.readTree(second.getResponse().getContentAsString());
        assertEquals(strip(a, "correlationId"), strip(b, "correlationId"));
        assertNotEquals(a.get("correlationId").asText(), b.get("correlationId").asText());
        assertEquals("AUTHENTICATION_FAILED", a.get("code").asText());
    }

    @Test
    void aRuntimeExceptionDuringLoginIsFiveHundredNeverFourOhOne() throws Exception {
        harness.localAuthenticationService().onAuthenticate("operator", () -> {
            throw new IllegalStateException("boom");
        });

        mvc.perform(loginAttempt("operator", "whatever"))
                .andExpect(status().isInternalServerError())
                .andExpect(problemCode("INTERNAL_ERROR"));
    }

    @Test
    void authenticationUnavailableDuringLoginIsFiveOhThree() throws Exception {
        harness.localAuthenticationService().onAuthenticate("operator", () -> {
            throw new AuthenticationUnavailableException();
        });

        mvc.perform(loginAttempt("operator", "whatever"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(problemCode("SERVICE_UNAVAILABLE"));
    }

    // ── OPTIONS ──────────────────────────────────────────────────────────────────────────────

    @Test
    void optionsOnAMappedRouteIsMethodNotAllowedNotFiveHundred() throws Exception {
        // Spring supplies a synthetic HandlerMethod (its private HttpOptionsHandler) for OPTIONS
        // on a mapped route; it carries no @ConsoleOperation, and the console is same-origin only
        // with no CORS preflight support, so every OPTIONS request must be a flat 405 — never the
        // 500 that falls out of treating "no @ConsoleOperation" as an internal error.
        MvcResult result = mvc.perform(options(LOGIN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(problemCode("METHOD_NOT_ALLOWED"))
                .andReturn();
        // m9: an OPTIONS 405 is still a session/authorization-bearing API response, never a static
        // asset — it must carry the same no-store guarantee as every other response.
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    }

    // ── identity ─────────────────────────────────────────────────────────────────────────────

    @Test
    void getIdentityWithNoCookieIsUnauthenticated() throws Exception {
        mvc.perform(get(IDENTITY))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("UNAUTHENTICATED"));
    }

    @Test
    void getIdentityWithAValidSessionAndNoCsrfSucceeds() throws Exception {
        Session session = harness.withSession("tok-identity", "csrf-identity", UUID.randomUUID(), false);

        mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-identity")))
                .andExpect(status().isOk())
                .andExpect(problemNone());
    }

    // ── coordinator review additions ─────────────────────────────────────────────────────────

    @Test
    void twoSessionCookiesAreRefusedRatherThanFirstWins() throws Exception {
        // Defence in depth (AuthController's javadoc explains why __Host- already makes the
        // sibling-domain plant this once guarded against unreachable for a real browser): two
        // candidates is ambiguous either way, so this interceptor trusts neither rather than
        // silently picking one.
        harness.withSession("tok-victim", "csrf-victim", UUID.randomUUID(), false);
        harness.withSession("tok-planted", "csrf-planted", UUID.randomUUID(), false);
        mvc.perform(get(IDENTITY)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-planted"), new Cookie("__Host-SM_SESSION", "tok-victim")))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("UNAUTHENTICATED"));
    }

    @Test
    void aWrongContentTypeOnLoginIsBadRequestNotInternalError() throws Exception {
        mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("text/plain")
                        .content("username=operator"))
                .andExpect(status().isBadRequest())
                .andExpect(problemCode("BAD_REQUEST"));
    }

    @Test
    void aProblemBodyOmitsAbsentOptionalFieldsInsteadOfSendingNull() throws Exception {
        // Problem.detail and Problem.instance are `type: string`, not nullable. A literal null
        // violates the published schema, so absent means absent.
        MvcResult result = mvc.perform(get(IDENTITY)).andExpect(status().isUnauthorized()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(!body.has("detail") && !body.has("instance"), body.toString());
    }

    // ── logout ───────────────────────────────────────────────────────────────────────────────

    @Test
    void logoutRevokesAndReturnsNoContentWithExpiredCookies() throws Exception {
        Session session = harness.withSession("tok-logout", "csrf-logout", UUID.randomUUID(), false);

        MvcResult result = mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-logout")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-logout")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertEquals(1, harness.sessionAuthentication().revokeCalls.size());
        assertEquals(session.id(), harness.sessionAuthentication().revokeCalls.get(0));

        String sessionCookie = firstHeaderStartingWith(result, "__Host-SM_SESSION=");
        String csrfCookie = firstHeaderStartingWith(result, "__Host-SM_CSRF=");
        assertTrue(sessionCookie.contains("Max-Age=0"), sessionCookie);
        assertTrue(csrfCookie.contains("Max-Age=0"), csrfCookie);
    }

    @Test
    void logoutWithoutCsrfIsRejected() throws Exception {
        harness.withSession("tok-logout-nocsrf", "csrf-logout-nocsrf", UUID.randomUUID(), false);

        mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-logout-nocsrf")))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("CSRF_REJECTED"));
    }

    @Test
    void logoutWithAnotherSessionsCsrfTokenIsRejected() throws Exception {
        harness.withSession("tok-logout-a", "csrf-a", UUID.randomUUID(), false);
        harness.withSession("tok-logout-b", "csrf-b", UUID.randomUUID(), false);

        mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-b")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-logout-a")))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("CSRF_REJECTED"));
    }

    @Test
    void unauthenticatedLogoutWithNoCsrfIsUnauthenticatedNotCsrfRejected() throws Exception {
        // Order proof: session resolution runs before CSRF, so a request with NEITHER a valid
        // session NOR a CSRF token must reveal "no session", never "no csrf".
        mvc.perform(post(LOGOUT).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("UNAUTHENTICATED"));
    }

    @Test
    void logoutPresentingTwoSessionCookiesIsUnauthenticatedFailClosed() throws Exception {
        harness.withSession("tok-logout-victim", "csrf-logout-victim", UUID.randomUUID(), false);
        harness.withSession("tok-logout-planted", "csrf-logout-planted", UUID.randomUUID(), false);

        mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-logout-victim")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-logout-planted"),
                                new Cookie("__Host-SM_SESSION", "tok-logout-victim")))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("UNAUTHENTICATED"));
        assertEquals(0, harness.sessionAuthentication().revokeCalls.size(), "neither session may be revoked");
    }

    @Test
    void aLegacySmSessionCookieNameIsNeverHonoured() throws Exception {
        // OR-21: read ONLY __Host-SM_SESSION, with no fallback to the retired plain name.
        harness.withSession("tok-legacy-name", "csrf-legacy-name", UUID.randomUUID(), false);

        mvc.perform(get(IDENTITY).cookie(new Cookie("SM_SESSION", "tok-legacy-name")))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("UNAUTHENTICATED"));
    }

    // ── credential rotation ──────────────────────────────────────────────────────────────────

    @Test
    void rotationSucceedsWithNoContent() throws Exception {
        harness.withSession("tok-rotate", "csrf-rotate", UUID.randomUUID(), false);

        mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-rotate")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-rotate"))
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isNoContent());

        assertEquals(1, harness.credentialRotation().rotateCalls.size());
    }

    @Test
    void rotationRefusalIsFourHundredCredentialRotationRefused() throws Exception {
        harness.withSession("tok-rotate-refuse", "csrf-rotate-refuse", UUID.randomUUID(), false);
        harness.credentialRotation().refuseNext = true;

        mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-rotate-refuse")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-rotate-refuse"))
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isBadRequest())
                .andExpect(problemCode("CREDENTIAL_ROTATION_REFUSED"));
    }

    @Test
    void aTooShortNewCredentialIsRejectedWithoutCallingThePort() throws Exception {
        harness.withSession("tok-rotate-short", "csrf-rotate-short", UUID.randomUUID(), false);

        mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-rotate-short")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-rotate-short"))
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(problemCode("BAD_REQUEST"));

        assertEquals(0, harness.credentialRotation().rotateCalls.size());
    }

    // ── must-rotate ──────────────────────────────────────────────────────────────────────────

    @Test
    void mustRotateSessionMayStillReadIdentityLogoutAndRotate() throws Exception {
        Session session = harness.withSession("tok-mr-allowed", "csrf-mr-allowed", UUID.randomUUID(), true);

        mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-mr-allowed")))
                .andExpect(status().isOk());
        mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-mr-allowed")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-mr-allowed"))
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isNoContent());

        Session freshSession = harness.withSession("tok-mr-logout", "csrf-mr-logout", UUID.randomUUID(), true);
        mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-mr-logout")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-mr-logout")))
                .andExpect(status().isNoContent());
    }

    @Test
    void mustRotateBlocksTheCatalogGetEndpoint() throws Exception {
        harness.withSession("tok-mr-get", "csrf-mr-get", UUID.randomUUID(), true);

        mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-mr-get")))
                .andExpect(status().is(428))
                .andExpect(problemCode("MUST_ROTATE"));
    }

    @Test
    void mustRotateBlocksTheCatalogPostEndpointEvenWithoutACsrfToken() throws Exception {
        // Order proof: the must-rotate gate runs before CSRF, so a POST with no CSRF token still
        // reports MUST_ROTATE, not CSRF_REJECTED.
        harness.withSession("tok-mr-post", "csrf-mr-post", UUID.randomUUID(), true);

        mvc.perform(post(GatewayTestSupport.CatalogFixtureController.STAGE_PATH)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-mr-post")))
                .andExpect(status().is(428))
                .andExpect(problemCode("MUST_ROTATE"));
    }

    // ── authorization ────────────────────────────────────────────────────────────────────────

    @Test
    void authorizationAllowingReturnsTwoHundredAndCallsRequireWithTheOperationsPermission() throws Exception {
        harness.withSession("tok-authz-allow", "csrf-authz-allow", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.ALLOW;

        mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-authz-allow")))
                .andExpect(status().isOk());

        assertEquals(1, harness.authorizationService().permissions.size());
        assertEquals("health.view", harness.authorizationService().permissions.get(0).id());
        assertEquals(1, harness.fixtureInvocationCount(), "an ALLOWed request must reach the handler exactly once");
    }

    @Test
    void authorizationDenyingReturnsFourOhThreeForbiddenAndNeverInvokesTheHandler() throws Exception {
        harness.withSession("tok-authz-deny", "csrf-authz-deny", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.DENY;

        mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-authz-deny")))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("FORBIDDEN"));

        assertEquals(0, harness.fixtureInvocationCount(),
                "authorization runs BEFORE the handler; a denial must never reach the handler body");
    }

    @Test
    void authorizationResourceNotFoundReturnsFourOhFourAndNeverInvokesTheHandler() throws Exception {
        harness.withSession("tok-authz-404", "csrf-authz-404", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.NOT_FOUND;

        mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-authz-404")))
                .andExpect(status().isNotFound())
                .andExpect(problemCode("NOT_FOUND"));

        assertEquals(0, harness.fixtureInvocationCount());
    }

    // ── distinct codes, correlation id, unmapped routes ─────────────────────────────────────

    @Test
    void fourOhOneFourOhThreeFourOhFourAndFourTwoEightBodiesHaveDistinctCodes() throws Exception {
        Set<String> codes = new HashSet<>();
        codes.add(codeOf(mvc.perform(get(IDENTITY))));

        harness.withSession("tok-distinct-403", "csrf-distinct-403", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.DENY;
        codes.add(codeOf(mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                .cookie(new Cookie("__Host-SM_SESSION", "tok-distinct-403")))));

        codes.add(codeOf(mvc.perform(get("/api/no/such/route"))));

        harness.withSession("tok-distinct-428", "csrf-distinct-428", UUID.randomUUID(), true);
        codes.add(codeOf(mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                .cookie(new Cookie("__Host-SM_SESSION", "tok-distinct-428")))));

        assertEquals(4, codes.size(), codes.toString());
        assertTrue(codes.containsAll(Set.of("UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND", "MUST_ROTATE")));
    }

    @Test
    void everyResponseCarriesAServerGeneratedUuidCorrelationId() throws Exception {
        assertCorrelationIdPresentAndUuid(mvc.perform(get(IDENTITY).header("X-Correlation-Id", "caller-supplied-value")), 401);

        Session ok = harness.withSession("tok-corr-ok", "csrf-corr-ok", UUID.randomUUID(), false);
        assertCorrelationIdPresentAndUuid(mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-corr-ok"))), 200);

        harness.withSession("tok-corr-logout", "csrf-corr-logout", UUID.randomUUID(), false);
        assertCorrelationIdPresentAndUuid(mvc.perform(post(LOGOUT)
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header(CSRF_HEADER, "csrf-corr-logout")
                .cookie(new Cookie("__Host-SM_SESSION", "tok-corr-logout"))), 204);

        assertCorrelationIdPresentAndUuid(mvc.perform(loginAttempt("nobody", "whatever")), 401);

        harness.localAuthenticationService().onAuthenticate("boom-user", () -> {
            throw new IllegalStateException("boom");
        });
        assertCorrelationIdPresentAndUuid(mvc.perform(loginAttempt("boom-user", "whatever")), 500);

        harness.localAuthenticationService().onAuthenticate("unavailable-user", () -> {
            throw new AuthenticationUnavailableException();
        });
        assertCorrelationIdPresentAndUuid(mvc.perform(loginAttempt("unavailable-user", "whatever")), 503);

        harness.withSession("tok-corr-mr", "csrf-corr-mr", UUID.randomUUID(), true);
        assertCorrelationIdPresentAndUuid(mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                .cookie(new Cookie("__Host-SM_SESSION", "tok-corr-mr"))), 428);

        assertCorrelationIdPresentAndUuid(mvc.perform(get("/api/no/such/route")), 404);

        assertCorrelationIdPresentAndUuid(mvc.perform(post(IDENTITY)), 405);
    }

    private void assertCorrelationIdPresentAndUuid(org.springframework.test.web.servlet.ResultActions actions, int expectedStatus)
            throws Exception {
        MvcResult result = actions.andExpect(status().is(expectedStatus)).andReturn();
        String header = result.getResponse().getHeader("X-Correlation-Id");
        assertNotEquals("caller-supplied-value", header);
        UUID.fromString(header); // throws if not a UUID
        if (result.getResponse().getContentAsString() != null && !result.getResponse().getContentAsString().isBlank()) {
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            if (body.has("correlationId")) {
                assertEquals(header, body.get("correlationId").asText());
            }
        }
    }

    // ── cache-control ────────────────────────────────────────────────────────────────────────

    @Test
    void everyResponseUnderApiCarriesCacheControlNoStoreAndPragmaNoCache() throws Exception {
        assertNoStore(mvc.perform(get(IDENTITY)), 401); // 401
        assertNoStore(mvc.perform(get("/api/no/such/route")), 404); // 404

        harness.localAuthenticationService().onAuthenticate("cc-user",
                () -> new LocalLoginResult(new SessionToken("tok-cc-login-value"), new CsrfToken("csrf-cc-login-value"),
                        GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false)));
        assertNoStore(mvc.perform(loginAttempt("cc-user", "whatever")), 200); // login 200

        harness.withSession("tok-cc-identity", "csrf-cc-identity", UUID.randomUUID(), false);
        assertNoStore(mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-cc-identity"))), 200); // identity 200

        harness.withSession("tok-cc-logout", "csrf-cc-logout", UUID.randomUUID(), false);
        assertNoStore(mvc.perform(post(LOGOUT)
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header(CSRF_HEADER, "csrf-cc-logout")
                .cookie(new Cookie("__Host-SM_SESSION", "tok-cc-logout"))), 204); // logout 204

        harness.withSession("tok-cc-credential", "csrf-cc-credential", UUID.randomUUID(), false);
        assertNoStore(mvc.perform(post(CREDENTIAL)
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header(CSRF_HEADER, "csrf-cc-credential")
                .cookie(new Cookie("__Host-SM_SESSION", "tok-cc-credential"))
                .contentType("application/json")
                .content(rotationBody("current-secret-value", "replacement-secret-value"))), 204); // credential 204

        harness.withSession("tok-cc-403", "csrf-cc-403", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.DENY;
        assertNoStore(mvc.perform(get(GatewayTestSupport.CatalogFixtureController.HEALTH_PATH)
                .cookie(new Cookie("__Host-SM_SESSION", "tok-cc-403"))), 403); // 403
    }

    private static void assertNoStore(org.springframework.test.web.servlet.ResultActions actions, int expectedStatus)
            throws Exception {
        MvcResult result = actions.andExpect(status().is(expectedStatus)).andReturn();
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"), "Cache-Control on " + expectedStatus);
        assertEquals("no-cache", result.getResponse().getHeader("Pragma"), "Pragma on " + expectedStatus);
    }

    // ── request origin ───────────────────────────────────────────────────────────────────────

    @Test
    void loginLogoutAndRotationBuildRequestOriginFromTheServerCorrelationIdAndThePeerAddress() throws Exception {
        harness.localAuthenticationService().onAuthenticate("origin-user",
                () -> new LocalLoginResult(new SessionToken("tok-origin-login"), new CsrfToken("csrf-origin-login"),
                        GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false)));

        MvcResult loginResult = mvc.perform(loginAttempt("origin-user", "whatever")).andExpect(status().isOk()).andReturn();
        String loginCorrelationId = loginResult.getResponse().getHeader("X-Correlation-Id");
        assertEquals(1, harness.localAuthenticationService().origins.size());
        assertEquals(UUID.fromString(loginCorrelationId), harness.localAuthenticationService().origins.get(0).correlationId());
        assertEquals("127.0.0.1", harness.localAuthenticationService().origins.get(0).clientAddress());

        harness.withSession("tok-origin-logout", "csrf-origin-logout", UUID.randomUUID(), false);
        MvcResult logoutResult = mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-origin-logout")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-origin-logout")))
                .andExpect(status().isNoContent())
                .andReturn();
        String logoutCorrelationId = logoutResult.getResponse().getHeader("X-Correlation-Id");
        assertEquals(1, harness.sessionAuthentication().revokeOrigins.size());
        assertEquals(UUID.fromString(logoutCorrelationId), harness.sessionAuthentication().revokeOrigins.get(0).correlationId());
        assertEquals("127.0.0.1", harness.sessionAuthentication().revokeOrigins.get(0).clientAddress());

        harness.withSession("tok-origin-rotate", "csrf-origin-rotate", UUID.randomUUID(), false);
        MvcResult rotateResult = mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, "csrf-origin-rotate")
                        .cookie(new Cookie("__Host-SM_SESSION", "tok-origin-rotate"))
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isNoContent())
                .andReturn();
        String rotateCorrelationId = rotateResult.getResponse().getHeader("X-Correlation-Id");
        assertEquals(1, harness.credentialRotation().rotateOrigins.size());
        assertEquals(UUID.fromString(rotateCorrelationId), harness.credentialRotation().rotateOrigins.get(0).correlationId());
        assertEquals("127.0.0.1", harness.credentialRotation().rotateOrigins.get(0).clientAddress());
    }

    @Test
    void aTrustedProxysForwardedForIsHonouredWhenBuildingRequestOrigin() throws Exception {
        // The default harness's peer is MockHttpServletRequest's default remote address,
        // 127.0.0.1; declaring it a trusted proxy is what makes the interceptor consult XFF here.
        Harness trustingHarness = GatewayTestSupport.newHarness(List.of("127.0.0.1"));
        trustingHarness.localAuthenticationService().onAuthenticate("proxied-user",
                () -> new LocalLoginResult(new SessionToken("tok-proxied-login"), new CsrfToken("csrf-proxied-login"),
                        GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false)));

        trustingHarness.mockMvc().perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType("application/json")
                        .content(loginBody("proxied-user", "whatever")))
                .andExpect(status().isOk());

        assertEquals(1, trustingHarness.localAuthenticationService().origins.size());
        assertEquals("203.0.113.9", trustingHarness.localAuthenticationService().origins.get(0).clientAddress());
    }

    @Test
    void anUntrustedPeersForwardedForIsIgnoredWhenBuildingRequestOrigin() throws Exception {
        // No trusted-proxies configured (the default harness) — the peer is used as-is even
        // though a forwarded header is present.
        harness.localAuthenticationService().onAuthenticate("unproxied-user",
                () -> new LocalLoginResult(new SessionToken("tok-unproxied-login"), new CsrfToken("csrf-unproxied-login"),
                        GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false)));

        mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType("application/json")
                        .content(loginBody("unproxied-user", "whatever")))
                .andExpect(status().isOk());

        assertEquals(1, harness.localAuthenticationService().origins.size());
        assertEquals("127.0.0.1", harness.localAuthenticationService().origins.get(0).clientAddress());
    }

    // ── redaction ────────────────────────────────────────────────────────────────────────────

    @Test
    void loginIdentityRotationAndLogoutNeverLeakSecretsInLogsOrBodies(CapturedOutput output) throws Exception {
        String loginSecret = "login-secret-sentinel-value";
        String sessionTokenSentinel = "session-token-sentinel-value";
        String currentSecret = "current-secret-sentinel-value";
        String replacementSecret = "replacement-secret-sentinel-value";
        String csrfSentinel = "csrf-sentinel-value";

        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false);
        harness.localAuthenticationService().onAuthenticate("redaction-user",
                () -> new LocalLoginResult(new SessionToken(sessionTokenSentinel), new CsrfToken(csrfSentinel), session));
        harness.sessionAuthentication().register(sessionTokenSentinel, session, csrfSentinel);

        MvcResult loginResult = mvc.perform(loginAttempt("redaction-user", loginSecret))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult identityResult = mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", sessionTokenSentinel)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult rotationResult = mvc.perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, csrfSentinel)
                        .cookie(new Cookie("__Host-SM_SESSION", sessionTokenSentinel))
                        .contentType("application/json")
                        .content(rotationBody(currentSecret, replacementSecret)))
                .andExpect(status().isNoContent())
                .andReturn();

        MvcResult logoutResult = mvc.perform(post(LOGOUT)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .header(CSRF_HEADER, csrfSentinel)
                        .cookie(new Cookie("__Host-SM_SESSION", sessionTokenSentinel)))
                .andExpect(status().isNoContent())
                .andReturn();

        for (String secret : Set.of(loginSecret, sessionTokenSentinel, currentSecret, replacementSecret)) {
            assertFalse(loginResult.getResponse().getContentAsString().contains(secret), secret);
            assertFalse(identityResult.getResponse().getContentAsString().contains(secret), secret);
            assertFalse(rotationResult.getResponse().getContentAsString().contains(secret), secret);
            assertFalse(logoutResult.getResponse().getContentAsString().contains(secret), secret);
            assertFalse(output.getAll().contains(secret), "log output must not contain: " + secret);
        }
        // csrfToken IS contracted to appear in the login/identity body — it is not a secret leak.
        assertTrue(loginResult.getResponse().getContentAsString().contains(csrfSentinel));
    }

    // ── OR-24: pre-authentication rate gate ─────────────────────────────────────────────────────

    @Test
    void aBurstBeyondCapacityOnLoginIsRateLimitedWithRetryAfterNoStoreAndCorrelationId() throws Exception {
        Harness rateLimited = GatewayTestSupport.newHarness(List.of(), new GatewayTestSupport.AuthRateConfig(2, 60, 100_000));

        rateLimited.mockMvc().perform(loginAttempt("nobody-1", "whatever")).andExpect(status().isUnauthorized());
        rateLimited.mockMvc().perform(loginAttempt("nobody-2", "whatever")).andExpect(status().isUnauthorized());

        MvcResult limited = rateLimited.mockMvc().perform(loginAttempt("nobody-3", "whatever"))
                .andExpect(status().isTooManyRequests())
                .andExpect(problemCode("RATE_LIMITED"))
                .andReturn();
        assertEquals("1", limited.getResponse().getHeader("Retry-After"));
        assertEquals("no-store", limited.getResponse().getHeader("Cache-Control"));

        // The third username was never even attempted against the port — the gate refused it
        // before login reached the fake at all.
        assertFalse(rateLimited.localAuthenticationService().attempts.contains("nobody-3"));
    }

    @Test
    void theRateGateIsNeverConsultedForGetIdentityOrLogout() throws Exception {
        Harness tiny = GatewayTestSupport.newHarness(List.of(), new GatewayTestSupport.AuthRateConfig(1, 60, 100_000));
        Session session = tiny.withSession("tok-not-gated", "csrf-not-gated", UUID.randomUUID(), false);

        // Exhaust whatever capacity a shared per-peer bucket would have, well past 1, on both
        // operations the gate must never apply to.
        for (int i = 0; i < 5; i++) {
            tiny.mockMvc().perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-not-gated")))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            Session fresh = tiny.withSession("tok-not-gated-" + i, "csrf-not-gated-" + i, UUID.randomUUID(), false);
            tiny.mockMvc().perform(post(LOGOUT)
                            .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                            .header(CSRF_HEADER, "csrf-not-gated-" + i)
                            .cookie(new Cookie("__Host-SM_SESSION", "tok-not-gated-" + i)))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void aGatedRotateOwnCredentialNeverResolvesTheSessionOrCallsThePort() throws Exception {
        // capacity 1: the FIRST request to /credential consumes the token (even with no session
        // cookie at all — the gate must run before session resolution), the SECOND is gated.
        Harness tiny = GatewayTestSupport.newHarness(List.of(), new GatewayTestSupport.AuthRateConfig(1, 60, 100_000));

        tiny.mockMvc().perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isUnauthorized()); // no session — but the token was still consumed

        MvcResult gated = tiny.mockMvc().perform(post(CREDENTIAL)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content(rotationBody("current-secret-value", "replacement-secret-value")))
                .andExpect(status().isTooManyRequests())
                .andExpect(problemCode("RATE_LIMITED"))
                .andReturn();
        assertNotNull(gated.getResponse().getHeader("Retry-After"));

        assertEquals(0, tiny.sessionAuthentication().resolveCalls.get(),
                "the gate must run BEFORE session resolution — a cp-api port call — for rotateOwnCredential too");
        assertEquals(0, tiny.credentialRotation().rotateCalls.size());
    }

    @Test
    void originValidationRunsBeforeTheRateGateSoAForeignOriginNeverConsumesAToken() throws Exception {
        Harness tiny = GatewayTestSupport.newHarness(List.of(), new GatewayTestSupport.AuthRateConfig(1, 60, 100_000));

        // Two foreign-origin attempts — if these consumed the single token, the trusted-origin
        // attempt below would be rate-limited instead of merely unauthenticated.
        tiny.mockMvc().perform(loginAttempt(GatewayTestSupport.FOREIGN_ORIGIN, "nobody", "whatever"))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("ORIGIN_REJECTED"));
        tiny.mockMvc().perform(loginAttempt(GatewayTestSupport.FOREIGN_ORIGIN, "nobody", "whatever"))
                .andExpect(status().isForbidden())
                .andExpect(problemCode("ORIGIN_REJECTED"));

        tiny.mockMvc().perform(loginAttempt(GatewayTestSupport.TRUSTED_ORIGIN, "nobody", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(problemCode("AUTHENTICATION_FAILED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginAttempt(
            String username, String credential) {
        return loginAttempt(GatewayTestSupport.TRUSTED_ORIGIN, username, credential);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginAttempt(
            String origin, String username, String credential) {
        return post(LOGIN)
                .header("Origin", origin)
                .contentType("application/json")
                .content(loginBody(username, credential));
    }

    private static String loginBody(String username, String credential) {
        return "{\"organization\":\"" + ORGANIZATION + "\",\"username\":\"" + username + "\",\"credential\":\""
                + credential + "\"}";
    }

    private static String rotationBody(String current, String replacement) {
        return "{\"currentCredential\":\"" + current + "\",\"newCredential\":\"" + replacement + "\"}";
    }

    private String codeOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }

    /**
     * Asserts the cookie's {@code Path} attribute is EXACTLY {@code expectedPath} — a substring
     * check (e.g. {@code contains("Path=/")}) would false-positive against {@code Path=/api}.
     */
    private static void assertExactCookiePath(String cookie, String expectedPath) {
        String prefix = "Path=";
        boolean found = false;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                assertEquals(expectedPath, trimmed.substring(prefix.length()), cookie);
                found = true;
            }
        }
        assertTrue(found, "no Path attribute in " + cookie);
    }

    /**
     * m11: asserts NO {@code Domain} attribute is present at all — a substring check for
     * {@code "Domain="} would already catch this, but a dedicated per-attribute scan (matching
     * {@link #assertExactCookiePath}'s own approach) is unambiguous even if a future attribute
     * name were ever added that merely contains that text.
     */
    private static void assertNoDomainAttribute(String cookie) {
        String prefix = "Domain=";
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            assertFalse(trimmed.regionMatches(true, 0, prefix, 0, prefix.length()),
                    "the __Host- prefix forbids a Domain attribute: " + cookie);
        }
    }

    private static String firstHeaderStartingWith(MvcResult result, String prefix) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Set-Cookie header starts with " + prefix));
    }

    private org.springframework.test.web.servlet.ResultMatcher problemCode(String expectedCode) {
        return result -> {
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            assertEquals(expectedCode, body.get("code").asText());
            assertEquals(result.getResponse().getHeader("X-Correlation-Id"), body.get("correlationId").asText());
            assertEquals("application/problem+json", result.getResponse().getContentType());
        };
    }

    private org.springframework.test.web.servlet.ResultMatcher problemNone() {
        return result -> assertNotEquals("application/problem+json", result.getResponse().getContentType());
    }

    private static JsonNode strip(JsonNode node, String field) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) node).deepCopy();
        copy.remove(field);
        return copy;
    }
}
