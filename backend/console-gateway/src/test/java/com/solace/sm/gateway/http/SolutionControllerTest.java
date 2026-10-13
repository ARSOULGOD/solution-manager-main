package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageRejectedException;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeForcedRotationGate;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.GatewayTestSupport.MutableClock;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * {@code loadPackage} and {@code listSolutions} over the real filter, interceptor, argument-resolver, controller
 * and advice pipeline, with a recording {@link SolutionCatalog} behind them. A self-contained context rather than
 * an addition to {@link GatewayTestSupport}'s shared one: it reuses that class's fakes and adds only the catalog.
 */
class SolutionControllerTest {

    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String LIST = "/api/cp/solutions";
    private static final String LOAD = "/api/cp/solutions/load";
    private static final String PACKAGE_BODY = "{\"package\":{\"solution\":\"cache\",\"packageVersion\":[1],"
            + "\"appliesTo\":\">=2.2 <2.5\"}}";

    private final ObjectMapper mapper = new ObjectMapper();

    private MockMvc mvc;
    private RecordingCatalog catalog;
    private FakeAuthorizationService authorization;
    private FakeForcedRotationGate rotationGate;
    private FakeSessionAuthentication sessions;
    private UUID organization;

    @BeforeEach
    void setUp() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true",
                "sm.gateway.auth-rate.capacity=10", "sm.gateway.auth-rate.refill-per-minute=10",
                "sm.gateway.auth-rate.max-tracked-keys=100");
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        catalog = context.getBean(RecordingCatalog.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        rotationGate = context.getBean(FakeForcedRotationGate.class);
        sessions = context.getBean(FakeSessionAuthentication.class);
        organization = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------------- listSolutions

    @Test
    void listSolutionsWithNoSessionIsUnauthenticatedAndNeverReachesTheCatalog() throws Exception {
        assertEquals(401, mvc.perform(get(LIST)).andReturn().getResponse().getStatus());
        assertTrue(catalog.listCalls.isEmpty());
    }

    @Test
    void listSolutionsWhileMustRotateIsRefused() throws Exception {
        sessions.register("tok-rotate", GatewayTestSupport.newSession(UUID.randomUUID(), organization, true), "csrf");
        assertEquals(428, mvc.perform(get(LIST).cookie(new Cookie(SESSION_COOKIE, "tok-rotate")))
                .andReturn().getResponse().getStatus());
        assertTrue(catalog.listCalls.isEmpty());
    }

    @Test
    void listSolutionsIsAReachOperationCheckedBeforeTheHandlerAndFilteredByNoItem() throws Exception {
        Session session = login("tok-list");
        UUID entryId = UUID.randomUUID();
        catalog.entries = List.of(entry(entryId, "cache", List.of(PackageVersionId.of(1), PackageVersionId.of("v2")),
                PackageVersionId.of("v2"), 7));

        MvcResult result = mvc.perform(get(LIST).cookie(new Cookie(SESSION_COOKIE, "tok-list"))).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(Permission.SOLUTION_VIEW), authorization.reachChecks,
                "the pre-check is the operation's own verb");
        assertTrue(authorization.resources.isEmpty(), "a reach operation resolves no single resource");
        assertEquals(List.of(session.organizationId()), catalog.listCalls,
                "I5: the catalog is asked for the SESSION's organization");
        assertTrue(authorization.subsetCalls.isEmpty(),
                "OR-51: listSolutions has no per-item filter - a catalog entry has no scope path of its own, and an "
                        + "organization-level subset would empty the list for an environment-bound operator");
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, body.get("items").size(), "an entry is returned although authorizedSubset would admit none");
        JsonNode item = body.at("/items/0");
        assertEquals(entryId.toString(), item.get("solutionId").asText());
        assertEquals("cache", item.get("solution").asText());
        assertEquals("cache", item.get("name").asText());
        assertEquals(1, item.at("/packageVersions/0").asInt());
        assertEquals("v2", item.at("/packageVersions/1").asText());
        assertEquals("v2", item.get("latestPackageVersion").asText());
        assertEquals("unsigned", item.get("signature").asText());
        assertEquals(7, item.get("stateVersion").asLong());
        assertFalse(body.has("stateVersion"), "a collection carries versions per item, never one for the whole");
    }

    @Test
    void aPrincipalHoldingSolutionViewNowhereGets403AndTheCatalogIsNeverAsked() throws Exception {
        login("tok-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        MvcResult result = mvc.perform(get(LIST).cookie(new Cookie(SESSION_COOKIE, "tok-deny"))).andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FORBIDDEN", mapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertTrue(catalog.listCalls.isEmpty());
    }

    // --------------------------------------------------------------------------------- loadPackage

    @Test
    void loadPackageRequiresPackageLoadAtTheCallersOwnOrganization() throws Exception {
        Session session = login("tok-load");
        catalog.nextLoad = new PackageLoad(loaded(UUID.randomUUID(), 3), true);

        mvc.perform(load("tok-load", PACKAGE_BODY)).andReturn();

        assertEquals(List.of(Permission.PACKAGE_LOAD), authorization.permissions);
        assertEquals(1, authorization.resources.size());
        assertEquals(ResourceKind.ORGANIZATION, authorization.resources.get(0).kind());
        assertEquals(session.organizationId(), authorization.resources.get(0).id(),
                "I5: the resource is the session's own organization, resolved server-side");
        assertEquals(session.organizationId(), authorization.resources.get(0).tenant().value());
        assertTrue(authorization.reachChecks.isEmpty(), "loadPackage is a catalog operation, not a reach one");
    }

    @Test
    void aDeniedLoadIs403AndNothingIsLoaded() throws Exception {
        login("tok-load-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        MvcResult result = mvc.perform(load("tok-load-deny", PACKAGE_BODY)).andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertTrue(catalog.loadCalls.isEmpty(), "authorization precedes the handler");
    }

    @Test
    void aLoadWithoutACsrfTokenOrFromAForeignOriginIsRefusedBeforeTheCatalog() throws Exception {
        login("tok-load-csrf");

        MvcResult noCsrf = mvc.perform(post(LOAD).cookie(new Cookie(SESSION_COOKIE, "tok-load-csrf"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(PACKAGE_BODY)).andReturn();
        MvcResult foreign = mvc.perform(post(LOAD).cookie(new Cookie(SESSION_COOKIE, "tok-load-csrf"))
                .header("Origin", GatewayTestSupport.FOREIGN_ORIGIN).header("X-SM-CSRF", "csrf")
                .contentType(MediaType.APPLICATION_JSON).content(PACKAGE_BODY)).andReturn();

        assertEquals("CSRF_REJECTED", mapper.readTree(noCsrf.getResponse().getContentAsString()).get("code").asText());
        assertEquals("ORIGIN_REJECTED",
                mapper.readTree(foreign.getResponse().getContentAsString()).get("code").asText());
        assertTrue(catalog.loadCalls.isEmpty());
    }

    @Test
    void aFirstLoadIs201WithTheCatalogIdAndUnsignedAndTheCatalogGetsTheSessionsIdentity() throws Exception {
        Session session = login("tok-201");
        UUID entryId = UUID.randomUUID();
        catalog.nextLoad = new PackageLoad(new LoadedPackage(entryId, "cache",
                List.of(PackageVersionId.of(1)), "sha256:" + "a".repeat(64), "unsigned", 12), true);

        MvcResult result = mvc.perform(load("tok-201", PACKAGE_BODY)).andReturn();

        assertEquals(201, result.getResponse().getStatus());
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(entryId.toString(), body.get("solutionId").asText());
        assertEquals(1, body.at("/packageVersion/0").asInt());
        assertEquals("unsigned", body.get("signature").asText());
        assertEquals(12, body.get("stateVersion").asLong());
        assertEquals(List.of(session.principal()), catalog.loadActors);
        assertEquals(List.of(session.organizationId()), catalog.loadOrganizations);
        RequestOrigin origin = catalog.loadOrigins.get(0);
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"), origin.correlationId().toString(),
                "the audit row joins to the response that caused it");
        assertEquals(mapper.readTree(PACKAGE_BODY).get("package"), mapper.readTree(catalog.loadJson.get(0)));
    }

    @Test
    void anIdempotentReloadIs200WithTheSameBody() throws Exception {
        login("tok-200");
        catalog.nextLoad = new PackageLoad(loaded(UUID.randomUUID(), 5), false);

        MvcResult result = mvc.perform(load("tok-200", PACKAGE_BODY)).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("unsigned", mapper.readTree(result.getResponse().getContentAsString()).get("signature").asText());
    }

    @Test
    void theOrganizationIsNeverTakenFromTheBodyAndAStrayKeyIs400() throws Exception {
        login("tok-body");
        String named = "{\"organizationId\":\"" + UUID.randomUUID() + "\",\"package\":{\"solution\":\"cache\"}}";

        MvcResult result = mvc.perform(load("tok-body", named)).andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", mapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertTrue(catalog.loadCalls.isEmpty());
    }

    @Test
    void aBodyWithoutAPackageObjectIs400() throws Exception {
        login("tok-shape");
        for (String body : new String[] {"{}", "{\"package\":null}", "{\"package\":[1]}", "{\"package\":\"x\"}", "[]"}) {
            assertEquals(400, mvc.perform(load("tok-shape", body)).andReturn().getResponse().getStatus(), body);
        }
        assertTrue(catalog.loadCalls.isEmpty());
    }

    @Test
    void aGateFailureIs422ValidationFailedNamingTheGatesAndNoValue() throws Exception {
        login("tok-422");
        catalog.nextFailure = new PackageRejectedException(List.of("1", "5b"));

        MvcResult result = mvc.perform(load("tok-422", PACKAGE_BODY)).andReturn();

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"));
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertEquals("the package was refused by load gate(s): 1, 5b", body.get("detail").asText());
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"), body.get("correlationId").asText());
    }

    @Test
    void aVersionCollisionIs422AndSaysSo() throws Exception {
        login("tok-version");
        catalog.nextFailure = PackageRejectedException.versionAlreadyLoaded();

        MvcResult result = mvc.perform(load("tok-version", PACKAGE_BODY)).andReturn();

        assertEquals(422, result.getResponse().getStatus());
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertTrue(body.get("detail").asText().contains("already loaded"));
    }

    @Test
    void anUnentitledSolutionIs409EntitlementRefusedNotForbidden() throws Exception {
        login("tok-409");
        catalog.nextFailure = new EntitlementRefusedException();

        MvcResult result = mvc.perform(load("tok-409", PACKAGE_BODY)).andReturn();

        assertEquals(409, result.getResponse().getStatus());
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("ENTITLEMENT_REFUSED", body.get("code").asText());
        assertNull(body.get("detail"), "no detail: nothing about the entitlement table leaks");
    }

    @Test
    void anAdviceNeverEchoesAPoisonedGateIdentifier() throws Exception {
        login("tok-poison");
        catalog.nextFailure = new PackageRejectedException(List.of("1", "secret-default: hunter2"));

        MvcResult result = mvc.perform(load("tok-poison", PACKAGE_BODY)).andReturn();

        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(text.contains("hunter2"), text);
        assertEquals("the package was refused by load gate(s): 1",
                mapper.readTree(text).get("detail").asText());
    }

    // ------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        sessions.register(token, session, "csrf");
        return session;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder load(String token,
            String body) {
        return post(LOAD).cookie(new Cookie(SESSION_COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static CatalogEntry entry(UUID id, String solution, List<PackageVersionId> versions,
            PackageVersionId latest, long stateVersion) {
        return new CatalogEntry(id, solution, solution, versions, latest, "unsigned", stateVersion);
    }

    private static LoadedPackage loaded(UUID id, long stateVersion) {
        return new LoadedPackage(id, "cache", List.of(PackageVersionId.of(1)), "sha256:" + "b".repeat(64),
                "unsigned", stateVersion);
    }

    /** Records what the controller hands the catalog and answers from what the test programmed. */
    static final class RecordingCatalog implements SolutionCatalog {

        final List<UUID> listCalls = new ArrayList<>();
        final List<PrincipalRef> loadActors = new ArrayList<>();
        final List<RequestOrigin> loadOrigins = new ArrayList<>();
        final List<UUID> loadOrganizations = new ArrayList<>();
        final List<String> loadJson = new ArrayList<>();
        final List<Object> loadCalls = new ArrayList<>();
        List<CatalogEntry> entries = List.of();
        PackageLoad nextLoad;
        RuntimeException nextFailure;

        @Override
        public PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson) {
            loadCalls.add(packageJson);
            loadActors.add(actor);
            loadOrigins.add(origin);
            loadOrganizations.add(organizationId);
            loadJson.add(packageJson);
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextLoad;
        }

        @Override
        public List<CatalogEntry> list(UUID organizationId) {
            listCalls.add(organizationId);
            return entries;
        }

        @Override
        public Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            return Optional.empty();
        }

        @Override
        public Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            return Optional.empty();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class Config {

        @Bean
        FakeSessionAuthentication sessionAuthentication() {
            return new FakeSessionAuthentication();
        }

        @Bean
        FakeForcedRotationGate forcedRotationGate() {
            return new FakeForcedRotationGate();
        }

        @Bean
        FakeAuthorizationService authorizationService() {
            return new FakeAuthorizationService();
        }

        @Bean(name = "authRateClock")
        MutableClock authRateClock() {
            return new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
        }

        @Bean
        GatewayErrorAdvice gatewayErrorAdvice() {
            return new GatewayErrorAdvice();
        }

        @Bean
        RecordingCatalog recordingCatalog() {
            return new RecordingCatalog();
        }

        @Bean
        SessionOrganizationResourceResolver sessionOrganizationResourceResolver() {
            return new SessionOrganizationResourceResolver();
        }

        @Bean
        SolutionController solutionController(SolutionCatalog catalog, RequestOriginFactory requestOriginFactory) {
            return new SolutionController(catalog, requestOriginFactory);
        }
    }
}
