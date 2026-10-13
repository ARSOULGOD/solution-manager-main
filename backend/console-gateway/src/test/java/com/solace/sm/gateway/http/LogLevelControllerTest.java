package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.api.logs.LogLevelView;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.api.logs.LoggerTarget;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeCodec;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeCatalog;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

/**
 * {@code getLogLevel} and {@code setLogLevel} (plan 010 T9.3, {@code OR-103} option Rd) over the real filter,
 * interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports FAKED. The SSH read
 * is {@code dp-core}'s ({@code LogLevelServiceImplTest}); pinned here: who may call, that the package (never the
 * browser) names the logger, that a package that declares nothing is told so, and that a level CHANGE is never
 * accepted: every mode is refused with a fixed detail, because under the ruling a change is configuration (T9.4).
 */
class LogLevelControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "com.solace.solutions.cache";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeInventory inventory;
    private FakeLogLevels logLevels;
    private ApplyControllerTest.FakeApply apply;
    private FakeCodec codec;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

    @Configuration
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeLogLevels logLevels(Calls calls) {
            return new FakeLogLevels(calls);
        }

        @Bean ApplyControllerTest.FakeApply apply(Calls calls) {
            return new ApplyControllerTest.FakeApply(calls);
        }

        @Bean LogLevelOrchestrator logLevelOrchestrator(DeclareOrchestrator instances,
                com.solace.sm.cp.api.catalog.SolutionCatalog catalog, com.solace.sm.cp.api.designer.DesignerCodec codec,
                LogLevelService logLevels, com.solace.sm.dp.api.apply.ApplyService apply) {
            return new LogLevelOrchestrator(instances, catalog, codec, logLevels, apply);
        }

        @Bean LogLevelController logLevelController(LogLevelOrchestrator orchestrator) {
            return new LogLevelController(orchestrator);
        }
    }

    static final class FakeLogLevels implements LogLevelService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<UUID> placementsSeen = new ArrayList<>();
        final List<LoggerTarget> targets = new ArrayList<>();
        LogLevelView.Availability availability = new LogLevelView.Availability(LogLevelView.State.LIVE, null);
        Optional<LoggerLevel> level = Optional.of(LoggerLevel.DEBUG);
        Optional<LoggerLevel> configured = Optional.empty();
        boolean missing;

        FakeLogLevels(Calls calls) {
            this.calls = calls;
        }

        @Override
        public LogLevelView read(UUID organizationId, UUID instanceId, UUID placementId, LoggerTarget target) {
            calls.add("dp.logLevel");
            organizationsSeen.add(organizationId);
            placementsSeen.add(placementId);
            targets.add(target);
            if (missing) {
                throw new NoSuchElementException("gone");
            }
            return new LogLevelView(placementId, target.component(), level, configured, false, availability);
        }
    }

    private static PackageModel.SolutionPackage packageWith(Map<String, Object> logs, String loggerPrefix) {
        return new PackageModel.SolutionPackage("cache", List.of(1), null, null, List.of(
                new PackageModel.PackageComponent("cache-engine", "exactly-one", null, "none", "cache-engine", null,
                        loggerPrefix, List.of(), List.of(), List.of(), null, logs, null)));
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
        logLevels = context.getBean(FakeLogLevels.class);
        apply = context.getBean(ApplyControllerTest.FakeApply.class);
        codec = context.getBean(FakeCodec.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        UUID catalogSolution = UUID.randomUUID();
        context.getBean(FakeCatalog.class).serve(catalogSolution, "1", "{\"solution\":\"cache\",\"packageVersion\":[1]}");
        codec.next = packageWith(Map.of("loggers", Map.of(), "baselineExposure", List.of("health")), PREFIX);
        putInstance(AcquisitionMode.VIA_SSH, catalogSolution, List.of(placementId));
    }

    private void putInstance(AcquisitionMode mode, UUID catalogSolution, List<UUID> placements) {
        inventory.rows.put(instanceId, new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), catalogSolution,
                "1", InstanceLifecycle.ACTIVE, mode,
                placements.stream().map(p -> new PlacementView(p, UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))).toList(), 41L));
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult read(String token, String query) throws Exception {
        return mvc.perform(get("/api/dp/instances/" + instanceId + "/log-levels/cache-engine" + query)
                .cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private MvcResult change(String token, String component, String body) throws Exception {
        return mvc.perform(post("/api/dp/instances/" + instanceId + "/log-levels/" + component)
                .cookie(new Cookie(COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-" + token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String changeBody(String level, String mode) {
        return "{\"placementId\":\"" + placementId + "\",\"level\":\"" + level + "\",\"mode\":\"" + mode + "\"}";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private Calls calls() {
        return context.getBean(Calls.class);
    }

    // ------------------------------------------------------------------------------------------ getLogLevel

    @Test
    void readRequiresLogsReadAtThePathsInstance() throws Exception {
        Session session = login("tok-verb");

        read("tok-verb", "");

        assertEquals(List.of(Permission.LOGS_READ), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNoPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, read("tok-deny", "").getResponse().getStatus());

        assertFalse(calls().has("dp.logLevel"));
    }

    @Test
    void anInstanceThatIsNotActiveIsTheSame404() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();

        MvcResult result = read("tok-missing", "");

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertFalse(calls().has("dp.logLevel"));
    }

    @Test
    void aComponentTheInstancesPackageDoesNotHaveIs404() throws Exception {
        login("tok-comp");

        MvcResult result = mvc.perform(get("/api/dp/instances/" + instanceId + "/log-levels/not-a-component")
                .cookie(new Cookie(COOKIE, "tok-comp"))).andReturn();

        assertEquals(404, result.getResponse().getStatus());
        assertFalse(calls().has("dp.logLevel"));
    }

    @Test
    void thePackageNamesTheLoggerAndThePathTheBrowserNamesNeitherAndTheOrganizationIsTheSessions() throws Exception {
        Session session = login("tok-wire");

        MvcResult result = mvc.perform(get("/api/dp/instances/" + instanceId + "/log-levels/cache-engine"
                + "?logger=ROOT&path=/x&organizationId=" + UUID.randomUUID())
                .cookie(new Cookie(COOKIE, "tok-wire")).header("X-Organization", UUID.randomUUID().toString()))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(new LoggerTarget("cache-engine", PREFIX, "/actuator/loggers")), logLevels.targets);
        assertEquals(List.of(session.organizationId()), logLevels.organizationsSeen);
    }

    @Test
    void aDeclaredLoggersPathIsPassedThroughForTheDataPlaneToValidate() throws Exception {
        login("tok-path");
        codec.next = packageWith(Map.of("loggers", Map.of("path", "/management/loggers"), "baselineExposure",
                List.of("health")), PREFIX);

        read("tok-path", "");

        assertEquals("/management/loggers", logLevels.targets.get(0).path());
    }

    @Test
    void aLiveReadHasTheContractsShapeAndOmitsAvailabilityWhenLive() throws Exception {
        login("tok-shape");

        JsonNode body = json(read("tok-shape", ""));

        assertEquals("cache-engine", body.get("component").asText());
        assertEquals(placementId.toString(), body.get("placementId").asText());
        assertEquals("DEBUG", body.get("liveLevel").asText());
        assertFalse(body.get("runtimeOverride").asBoolean(), "SM has no live write under OR-103");
        assertFalse(body.has("configuredLevel"), "nothing configured: absent, never null");
        assertFalse(body.has("availability"), "a live answer needs no availability object");
    }

    @Test
    void aDegradedViewCarriesItsStateAndReason() throws Exception {
        login("tok-degraded");
        logLevels.level = Optional.empty();
        logLevels.availability = new LogLevelView.Availability(LogLevelView.State.DEGRADED, "not exposed yet");

        JsonNode body = json(read("tok-degraded", ""));

        assertEquals("degraded", body.at("/availability/state").asText());
        assertEquals("not exposed yet", body.at("/availability/reason").asText());
        assertFalse(body.has("liveLevel"));
    }

    @Test
    void aPackageThatDeclaresNoLoggersIsToldSoAndTheDataPlaneIsNotAsked() throws Exception {
        login("tok-none");
        codec.next = packageWith(Map.of("logfile", Map.of("path", "/actuator/logfile")), PREFIX);

        MvcResult result = read("tok-none", "");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertFalse(body.has("liveLevel"));
        assertFalse(calls().has("dp.logLevel"), "nothing is inferred from loggerPrefix (OR-103, BE-D38)");
    }

    @Test
    void aSecondLivePlacementRequiresAPlacementIdAndAnUnknownOneIs404() throws Exception {
        login("tok-many");
        UUID second = UUID.randomUUID();
        putInstance(AcquisitionMode.VIA_SSH, inventory.rows.get(instanceId).catalogSolutionId(),
                List.of(placementId, second));

        assertEquals(400, read("tok-many", "").getResponse().getStatus());
        assertEquals(404, read("tok-many", "?placementId=" + UUID.randomUUID()).getResponse().getStatus());
        MvcResult chosen = read("tok-many", "?placementId=" + second);
        assertEquals(200, chosen.getResponse().getStatus());
        assertEquals(List.of(second), logLevels.placementsSeen);
    }

    @Test
    void aPlacementTheDataPlaneDoesNotKnowIs404() throws Exception {
        login("tok-gone");
        logLevels.missing = true;

        assertEquals(404, read("tok-gone", "").getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------------ setLogLevel

    @Test
    void changeRequiresLogsSetLevelAtThePathsInstance() throws Exception {
        login("tok-set");

        change("tok-set", "cache-engine", changeBody("DEBUG", "config"));

        assertEquals(List.of(Permission.LOGS_SET_LEVEL), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId)));
    }

    @Test
    void aConfigLevelChangeIs202ApplyAcceptedWithALocationToObserveIt() throws Exception {
        login("tok-config");

        MvcResult result = change("tok-config", "cache-engine", changeBody("DEBUG", "config"));

        assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals(apply.nextApplyId.toString(), body.get("applyId").asText());
        assertEquals(77, body.get("stateVersion").asLong());
        assertEquals("/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + apply.nextApplyId,
                result.getResponse().getHeader("Location"));
        assertTrue(calls().has("dp.setLogLevel"));
    }

    @Test
    void theDataPlaneIsAskedForThePackagesLoggerTheSessionsOrganizationAndPrincipalAndTheRequestsPlacementAndLevel()
            throws Exception {
        Session session = login("tok-cmd");

        change("tok-cmd", "cache-engine", changeBody("WARN", "config"));

        assertEquals(1, apply.levelSet.size());
        com.solace.sm.dp.api.apply.SetLogLevelCommand command = apply.levelSet.get(0);
        assertEquals(placementId, command.placementId());
        assertEquals(PREFIX, command.loggerPrefix(), "the package names the logger, never the browser");
        assertEquals(LoggerLevel.WARN, command.level());
        assertEquals(session.principal().id(), command.actor());
        assertEquals(List.of(session.organizationId()), apply.organizationsSeen, "I5: the organization is the session's");
    }

    @Test
    void everyContractLevelIsAdmittedThroughConfiguration() throws Exception {
        login("tok-levels");
        for (LoggerLevel level : LoggerLevel.values()) {
            assertEquals(202, change("tok-levels", "cache-engine", changeBody(level.name(), "config")).getResponse()
                    .getStatus(), level.name());
        }
        assertEquals(List.of(LoggerLevel.TRACE, LoggerLevel.DEBUG, LoggerLevel.INFO, LoggerLevel.WARN, LoggerLevel.ERROR),
                apply.levelSet.stream().map(com.solace.sm.dp.api.apply.SetLogLevelCommand::level).toList());
    }

    @Test
    void aBusyScopeIs409LikeEveryOtherApply() throws Exception {
        login("tok-busy");
        apply.failure = new com.solace.sm.dp.api.apply.ApplyRefusedException(
                com.solace.sm.dp.api.apply.ApplyRefusedException.Reason.SCOPE_BUSY);

        MvcResult busy = change("tok-busy", "cache-engine", changeBody("DEBUG", "config"));

        assertEquals(409, busy.getResponse().getStatus());
        assertEquals("CONFLICT", json(busy).get("code").asText());
    }

    @Test
    void anInstanceWithNothingStagedIs422AsTheApplyPathRefusesIt() throws Exception {
        login("tok-unstaged");
        apply.failure = new com.solace.sm.dp.api.apply.ApplyRefusedException(
                com.solace.sm.dp.api.apply.ApplyRefusedException.Reason.GENERATION_NOT_STAGED);

        MvcResult result = change("tok-unstaged", "cache-engine", changeBody("DEBUG", "config"));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
    }

    @Test
    void aCallerWithoutTheVerbCannotChangeALevelAndNothingIsMinted() throws Exception {
        login("tok-denyset");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, change("tok-denyset", "cache-engine", changeBody("DEBUG", "config")).getResponse().getStatus());

        assertFalse(calls().has("dp.setLogLevel"));
    }

    @Test
    void aPackageThatDeclaresNoLoggersCannotHaveALevelChangedAndTheDataPlaneIsNotAsked() throws Exception {
        login("tok-nosupport");
        codec.next = packageWith(Map.of("logfile", Map.of("path", "/actuator/logfile")), PREFIX);

        MvcResult result = change("tok-nosupport", "cache-engine", changeBody("DEBUG", "config"));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        assertFalse(calls().has("dp.setLogLevel"), "nothing is inferred from loggerPrefix (OR-103, BE-D38)");
    }

    @Test
    void aComponentWithNoLoggerPrefixCannotHaveALevelChanged() throws Exception {
        login("tok-noprefix");
        codec.next = packageWith(Map.of("loggers", Map.of(), "baselineExposure", List.of("health")), null);

        assertEquals(422, change("tok-noprefix", "cache-engine", changeBody("DEBUG", "config")).getResponse()
                .getStatus());
        assertFalse(calls().has("dp.setLogLevel"));
    }

    @Test
    void theConfiguredLevelIsReadBackAsTheContractsConfiguredLevel() throws Exception {
        login("tok-configured");
        logLevels.configured = Optional.of(LoggerLevel.ERROR);

        JsonNode body = json(read("tok-configured", ""));

        assertEquals("ERROR", body.get("configuredLevel").asText());
        assertEquals("DEBUG", body.get("liveLevel").asText());
    }

    @Test
    void aLiveLevelChangeIs501BecauseThereIsNoLiveWrite() throws Exception {
        login("tok-live");

        MvcResult result = change("tok-live", "cache-engine", changeBody("DEBUG", "live"));

        assertEquals(501, result.getResponse().getStatus());
        assertEquals("NOT_IMPLEMENTED", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("live"));
    }

    @Test
    void aLiveLevelChangeOnADirectHttpInstanceIs422AsTheContractStates() throws Exception {
        login("tok-direct");
        putInstance(AcquisitionMode.DIRECT_HTTP, inventory.rows.get(instanceId).catalogSolutionId(),
                List.of(placementId));

        MvcResult result = change("tok-direct", "cache-engine", changeBody("DEBUG", "live"));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
    }

    @Test
    void aLiveModeNeverYieldsA202BecauseNoLiveWriteExists() throws Exception {
        login("tok-never");
        for (String level : List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")) {
            int status = change("tok-never", "cache-engine", changeBody(level, "live")).getResponse().getStatus();
            assertNotEquals(202, status, level);
            assertNotEquals(200, status, level);
        }
        assertFalse(calls().has("dp.setLogLevel"));
    }

    @Test
    void aMalformedChangeIs400BeforeAnythingElse() throws Exception {
        login("tok-bad");
        String p = placementId.toString();
        for (String body : List.of(
                "{\"level\":\"DEBUG\",\"mode\":\"config\"}",
                "{\"placementId\":\"" + p + "\",\"mode\":\"config\"}",
                "{\"placementId\":\"" + p + "\",\"level\":\"DEBUG\"}",
                "{\"placementId\":\"" + p + "\",\"level\":\"LOUD\",\"mode\":\"config\"}",
                "{\"placementId\":\"" + p + "\",\"level\":\"FATAL\",\"mode\":\"config\"}",
                "{\"placementId\":\"" + p + "\",\"level\":\"DEBUG\",\"mode\":\"sideways\"}",
                "{\"placementId\":\"" + p + "\",\"level\":\"DEBUG\",\"mode\":\"config\",\"logger\":\"ROOT\"}",
                "{\"placementId\":\"not-a-uuid\",\"level\":\"DEBUG\",\"mode\":\"config\"}")) {
            assertEquals(400, change("tok-bad", "cache-engine", body).getResponse().getStatus(), body);
        }
        assertFalse(calls().has("dp.setLogLevel"));
    }

    @Test
    void aChangeNamingAPlacementOfAnotherInstanceOrAnUnknownComponentIs404() throws Exception {
        login("tok-404");

        assertEquals(404, change("tok-404", "cache-engine", "{\"placementId\":\"" + UUID.randomUUID()
                + "\",\"level\":\"DEBUG\",\"mode\":\"config\"}").getResponse().getStatus());
        assertEquals(404, change("tok-404", "not-a-component", changeBody("DEBUG", "config")).getResponse()
                .getStatus());
        assertFalse(calls().has("dp.setLogLevel"));
    }
}
