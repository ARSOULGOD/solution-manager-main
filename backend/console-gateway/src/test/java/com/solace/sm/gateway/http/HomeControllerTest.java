package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.home.AttentionCandidate;
import com.solace.sm.dp.api.home.AttentionReason;
import com.solace.sm.dp.api.home.AttentionService;
import com.solace.sm.dp.api.home.InstanceHealthSummary;
import com.solace.sm.dp.api.home.InventorySummaryService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * {@code getHomeSummary} and {@code getHomeAttention} (plan 010 T10.5, {@code OR-45}, {@code OR-55}, {@code OR-60},
 * {@code OR-61}) over the real filter, interceptor, argument-resolver, controller and advice pipeline, with the cp-api
 * and dp-api ports FAKED. The rollup and the reads are {@code dp-core}'s ({@code HomeRollupTest}, {@code HomeReadsIT});
 * pinned here is the composition: who may see which instance, that the two planes are read together, that a data-plane
 * failure degrades the counts and not the catalog, and the wire shape.
 */
class HomeControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final String SUMMARY = "/api/cp/home/summary";
    private static final String ATTENTION = "/api/cp/home/attention";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeCatalog catalog;
    private FakeSummary summaries;
    private FakeAttention attention;
    private UUID organization;

    @Configuration
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean FakeCatalog catalog() {
            return new FakeCatalog();
        }

        @Bean FakeSummary summaries() {
            return new FakeSummary();
        }

        @Bean FakeAttention attention() {
            return new FakeAttention();
        }

        @Bean HomeOrchestrator homeOrchestrator(SolutionCatalog catalog, InventorySummaryService summaries,
                AttentionService attention, ReachAuthorization reach) {
            return new HomeOrchestrator(catalog, summaries, attention, reach);
        }

        @Bean HomeController homeController(HomeOrchestrator orchestrator) {
            return new HomeController(orchestrator);
        }
    }

    static final class FakeCatalog implements SolutionCatalog {
        List<CatalogEntry> entries = List.of();
        final List<UUID> organizationsSeen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch iAmReading;
        CountDownLatch waitForTheOther;
        final AtomicBoolean sawTheOtherPlaneInFlight = new AtomicBoolean();

        @Override
        public PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson) {
            throw new AssertionError("a home read never loads a package");
        }

        @Override
        public List<CatalogEntry> list(UUID organizationId) {
            organizationsSeen.add(organizationId);
            if (iAmReading != null) {
                iAmReading.countDown();
                try {
                    sawTheOtherPlaneInFlight.set(waitForTheOther.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return entries;
        }

        @Override
        public Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            throw new AssertionError("a home read never resolves a package");
        }

        @Override
        public Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            throw new AssertionError("a home read never reads a package document");
        }
    }

    static final class FakeSummary implements InventorySummaryService {
        List<InstanceHealthSummary> rows = List.of();
        RuntimeException failure;
        final List<UUID> organizationsSeen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch iAmReading;
        CountDownLatch waitForTheOther;
        final AtomicBoolean sawTheOtherPlaneInFlight = new AtomicBoolean();

        @Override
        public List<InstanceHealthSummary> summarize(UUID organizationId) {
            organizationsSeen.add(organizationId);
            if (iAmReading != null) {
                iAmReading.countDown();
                try {
                    sawTheOtherPlaneInFlight.set(waitForTheOther.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return rows;
        }
    }

    static final class FakeAttention implements AttentionService {
        List<AttentionCandidate> rows = List.of();
        RuntimeException failure;
        final List<UUID> organizationsSeen = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<AttentionCandidate> attention(UUID organizationId) {
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            return rows;
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
        catalog = context.getBean(FakeCatalog.class);
        summaries = context.getBean(FakeSummary.class);
        attention = context.getBean(FakeAttention.class);
        organization = UUID.randomUUID();
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult read(String path, String token) throws Exception {
        return mvc.perform(get(path).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static CatalogEntry entry(UUID id, String name, long version) {
        return new CatalogEntry(id, name, name, List.of(PackageVersionId.of(1)), PackageVersionId.of(1), "unsigned",
                version);
    }

    private static InstanceHealthSummary instance(UUID id, UUID catalogSolution, HealthState rollup) {
        return new InstanceHealthSummary(id, catalogSolution, Optional.ofNullable(rollup));
    }

    private static JsonNode row(JsonNode body, UUID solution) {
        for (JsonNode row : body.get("solutions")) {
            if (row.get("solutionId").asText().equals(solution.toString())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + solution);
    }

    // ---- getHomeSummary: who may see what -------------------------------------------------------------------

    @Test
    void theSummaryNeverCountsAnInvisibleInstance() throws Exception {
        login("tok-invisible");
        UUID cache = UUID.randomUUID();
        UUID seen = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 4));
        summaries.rows = List.of(instance(seen, cache, HealthState.GREEN), instance(hidden, cache, HealthState.RED));
        authorization.authorizedIds.add(seen);

        JsonNode body = json(read(SUMMARY, "tok-invisible"));

        assertEquals(1, row(body, cache).get("instanceCount").asInt(), "an instance the caller cannot see is not counted");
        assertEquals(1, row(body, cache).at("/byHealth/GREEN").asInt());
        assertTrue(row(body, cache).at("/byHealth/RED").isMissingNode(), "nor in any health bucket: " + body);
    }

    @Test
    void anEnvironmentBoundOperatorGetsItsOwnCountsNot403() throws Exception {
        // OR-51: the pre-check is "held at one or more grants", so an operator bound to one environment reaches the
        // handler, and only its instances are counted. The organization-scope the contract used to declare would 403.
        login("tok-env");
        UUID cache = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        summaries.rows = List.of(instance(mine, cache, HealthState.GREEN), instance(theirs, cache, HealthState.GREEN));
        authorization.authorizedIds.add(mine);

        MvcResult result = read(SUMMARY, "tok-env");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(Permission.SOLUTION_VIEW), authorization.reachChecks, "the pre-check is the operation's verb");
        assertTrue(authorization.resources.isEmpty(), "a reach operation resolves no single resource");
        assertEquals(1, row(json(result), cache).get("instanceCount").asInt());
    }

    @Test
    void aPrincipalHoldingTheVerbNowhereIs403AndNeitherPlaneIsRead() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, read(SUMMARY, "tok-deny").getResponse().getStatus());

        assertTrue(summaries.organizationsSeen.isEmpty());
        assertTrue(catalog.organizationsSeen.isEmpty());
    }

    @Test
    void instanceCountIsFilteredByInstanceViewAtEachInstancesOwnScopeAsAnInstanceKind() throws Exception {
        Session session = login("tok-verbs");
        UUID cache = UUID.randomUUID();
        UUID one = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        summaries.rows = List.of(instance(one, cache, HealthState.GREEN));
        authorization.authorizedIds.add(one);

        read(SUMMARY, "tok-verbs");

        FakeAuthorizationService.SubsetCall first = authorization.subsetCalls.get(0);
        assertEquals(Permission.INSTANCE_VIEW, first.permission());
        assertEquals(ResourceKind.INSTANCE, first.kind());
        assertEquals(Set.of(one), first.ids());
        assertEquals(session.principal(), first.principal(), "I5: the session's principal, never a request field");
    }

    @Test
    void byHealthCountsOnlyInstancesWhereHealthViewIsAlsoHeld() throws Exception {
        login("tok-health");
        UUID cache = UUID.randomUUID();
        UUID seenWithHealth = UUID.randomUUID();
        UUID seenWithoutHealth = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        summaries.rows = List.of(instance(seenWithHealth, cache, HealthState.GREEN),
                instance(seenWithoutHealth, cache, HealthState.RED));
        authorization.authorizedIds.addAll(Set.of(seenWithHealth, seenWithoutHealth));
        authorization.authorizedIdsByPermission.put(Permission.HEALTH_VIEW, Set.of(seenWithHealth));

        JsonNode cacheRow = row(json(read(SUMMARY, "tok-health")), cache);

        assertEquals(2, cacheRow.get("instanceCount").asInt(), "instance.view alone counts toward instanceCount");
        assertEquals(1, cacheRow.at("/byHealth/GREEN").asInt());
        assertTrue(cacheRow.at("/byHealth/RED").isMissingNode(),
                "an instance outside health.view's reach contributes to no bucket");
    }

    @Test
    void aSolutionNoneOfWhoseVisibleInstancesClearHealthViewCarriesNoByHealthKeyAtAll() throws Exception {
        login("tok-nohealth");
        UUID cache = UUID.randomUUID();
        UUID only = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        summaries.rows = List.of(instance(only, cache, HealthState.RED));
        authorization.authorizedIds.add(only);
        authorization.authorizedIdsByPermission.put(Permission.HEALTH_VIEW, Set.of());

        JsonNode cacheRow = row(json(read(SUMMARY, "tok-nohealth")), cache);

        assertEquals(1, cacheRow.get("instanceCount").asInt());
        assertFalse(cacheRow.has("byHealth"), "never a zeroed one: " + cacheRow);
    }

    @Test
    void everyCatalogSolutionIsARowEvenWhenNoInstanceIsVisible() throws Exception {
        login("tok-empty");
        UUID cache = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 9), entry(other, "db", 2));

        JsonNode body = json(read(SUMMARY, "tok-empty"));

        assertEquals(2, body.get("solutions").size(), "exactly as listSolutions returns them");
        assertEquals(0, row(body, cache).get("instanceCount").asInt());
        assertEquals(9, row(body, cache).get("stateVersion").asLong(), "the catalog row's own stamp");
        assertEquals("cache", row(body, cache).get("name").asText());
        assertFalse(row(body, cache).has("byHealth"));
    }

    // ---- getHomeSummary: the vocabulary -------------------------------------------------------------------

    @Test
    void byHealthUsesTheNineKeyVocabularyAsTheContractSpellsIt() throws Exception {
        login("tok-nine");
        UUID cache = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        List<InstanceHealthSummary> rows = new ArrayList<>();
        for (HealthState state : HealthState.values()) {
            UUID id = UUID.randomUUID();
            rows.add(instance(id, cache, state));
            authorization.authorizedIds.add(id);
        }
        rows.add(instance(UUID.randomUUID(), cache, HealthState.GREEN));
        authorization.authorizedIds.add(rows.get(rows.size() - 1).instanceId());
        summaries.rows = rows;

        JsonNode byHealth = row(json(read(SUMMARY, "tok-nine")), cache).get("byHealth");

        Set<String> keys = new TreeSet<>();
        byHealth.fieldNames().forEachRemaining(keys::add);
        assertEquals(new TreeSet<>(Set.of("GREEN", "RED", "RESTART_EXPECTED", "INACTIVE_REPLICA", "NOT_APPLICABLE",
                "unreachable", "not_exposed", "missing", "bootstrap")), keys);
        assertEquals(2, byHealth.get("GREEN").asInt());
        assertEquals(1, byHealth.get("unreachable").asInt());
        assertEquals(1, byHealth.get("NOT_APPLICABLE").asInt(), "OR-101: a no-probe instance is its own bucket, not RED");
        assertEquals(1, byHealth.get("RED").asInt());
    }

    @Test
    void anInstanceNothingHasPolledIsCountedButInNoBucketAndTheSurfaceSaysSo() throws Exception {
        login("tok-unpolled");
        UUID cache = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 1));
        summaries.rows = List.of(instance(fresh, cache, null));
        authorization.authorizedIds.add(fresh);
        authorization.authorizedIdsByPermission.put(Permission.HEALTH_VIEW, Set.of(fresh));

        JsonNode body = json(read(SUMMARY, "tok-unpolled"));

        assertEquals(1, row(body, cache).get("instanceCount").asInt());
        assertFalse(row(body, cache).has("byHealth"));
        assertEquals("degraded", body.at("/availability/state").asText());
        assertEquals("polling", body.at("/availability/mode").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
    }

    @Test
    void theSummaryCarriesNoTopLevelStateVersionOrState() throws Exception {
        login("tok-shape");
        catalog.entries = List.of(entry(UUID.randomUUID(), "cache", 1));

        JsonNode body = json(read(SUMMARY, "tok-shape"));

        Set<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("solutions"), names, "OR-55: per-row versions only; a live surface has no availability key");
    }

    // ---- getHomeSummary: the two planes -------------------------------------------------------------------

    @Test
    void aDpOutageDegradesTheCountsNotTheCatalog() throws Exception {
        login("tok-outage");
        UUID cache = UUID.randomUUID();
        catalog.entries = List.of(entry(cache, "cache", 5));
        summaries.failure = new IllegalStateException("connection refused to 10.0.0.7:5432 as sm_dp_runtime");

        MvcResult result = read(SUMMARY, "tok-outage");

        assertEquals(200, result.getResponse().getStatus(), "a DP exception never escapes (OR-10)");
        JsonNode body = json(result);
        assertEquals(1, body.get("solutions").size(), "the catalog list is intact");
        assertEquals(5, row(body, cache).get("stateVersion").asLong());
        assertEquals("degraded", body.at("/availability/state").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertFalse(result.getResponse().getContentAsString().contains("10.0.0.7"),
                "the failure's message (a host, a role) is never echoed");
    }

    @Test
    void theTwoPlaneReadsRunConcurrently() throws Exception {
        login("tok-concurrent");
        catalog.entries = List.of(entry(UUID.randomUUID(), "cache", 1));
        CountDownLatch catalogStarted = new CountDownLatch(1);
        CountDownLatch dpStarted = new CountDownLatch(1);
        catalog.iAmReading = catalogStarted;
        catalog.waitForTheOther = dpStarted;
        summaries.iAmReading = dpStarted;
        summaries.waitForTheOther = catalogStarted;

        MvcResult result = read(SUMMARY, "tok-concurrent");

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(catalog.sawTheOtherPlaneInFlight.get(), "the catalog read saw the data-plane read in flight");
        assertTrue(summaries.sawTheOtherPlaneInFlight.get(), "the data-plane read saw the catalog read in flight");
    }

    @Test
    void theOrganizationIsTheSessionsOnBothPlanesNeverARequestField() throws Exception {
        Session session = login("tok-i5");
        catalog.entries = List.of(entry(UUID.randomUUID(), "cache", 1));

        mvc.perform(get(SUMMARY + "?organizationId=" + UUID.randomUUID()).cookie(new Cookie(COOKIE, "tok-i5"))
                .header("X-Organization", UUID.randomUUID().toString())).andReturn();

        assertEquals(List.of(session.organizationId()), catalog.organizationsSeen);
        assertEquals(List.of(session.organizationId()), summaries.organizationsSeen);
    }

    // ---- getHomeAttention -----------------------------------------------------------------------------------

    private static AttentionCandidate health(UUID instance, UUID placement, HealthState state, long version) {
        return new AttentionCandidate(instance, Optional.of(placement), AttentionReason.HEALTH, Optional.of(state),
                version);
    }

    private static AttentionCandidate apply(UUID instance, AttentionReason reason, long version) {
        return new AttentionCandidate(instance, Optional.empty(), reason, Optional.empty(), version);
    }

    @Test
    void attentionListsIndeterminateAndDivergedAndUnhealthy() throws Exception {
        login("tok-list");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        attention.rows = List.of(health(a, placement, HealthState.UNREACHABLE, 11),
                apply(b, AttentionReason.APPLY_INDETERMINATE, 22), apply(c, AttentionReason.CONFIG_DIVERGED, 33));
        authorization.authorizedIds.addAll(Set.of(a, b, c));

        MvcResult result = read(ATTENTION, "tok-list");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(Permission.HEALTH_VIEW), authorization.reachChecks, "the pre-check is the operation's verb");
        JsonNode items = json(result).get("items");
        assertEquals(3, items.size());
        JsonNode unhealthy = byReason(items, "health");
        assertEquals(a.toString(), unhealthy.get("instanceId").asText());
        assertEquals(placement.toString(), unhealthy.get("placementId").asText());
        assertEquals("unreachable", unhealthy.get("state").asText(), "the notify vocabulary, never the retired REST one");
        JsonNode indeterminate = byReason(items, "apply-indeterminate");
        assertEquals(b.toString(), indeterminate.get("instanceId").asText());
        assertFalse(indeterminate.has("state"), "a state is a health reason's only");
        assertFalse(indeterminate.has("placementId"), "an instance-wide reason has no placement");
        assertEquals(c.toString(), byReason(items, "config-diverged").get("instanceId").asText());
    }

    private static JsonNode byReason(JsonNode items, String reason) {
        for (JsonNode item : items) {
            if (item.get("reason").asText().equals(reason)) {
                return item;
            }
        }
        throw new AssertionError("no item for " + reason);
    }

    @Test
    void eachAttentionReasonIsFilteredByItsSourcesVerb() throws Exception {
        login("tok-source");
        UUID healthOnly = UUID.randomUUID();
        UUID configOnly = UUID.randomUUID();
        UUID bothVerbs = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        attention.rows = List.of(
                health(healthOnly, placement, HealthState.RED, 1), apply(healthOnly, AttentionReason.CONFIG_DIVERGED, 2),
                health(configOnly, placement, HealthState.RED, 3), apply(configOnly, AttentionReason.APPLY_INDETERMINATE, 4),
                health(bothVerbs, placement, HealthState.RED, 5), apply(bothVerbs, AttentionReason.CONFIG_DIVERGED, 6));
        authorization.authorizedIds.addAll(Set.of(healthOnly, configOnly, bothVerbs));        // instance.view
        authorization.authorizedIdsByPermission.put(Permission.INSTANCE_VIEW, Set.of(healthOnly, configOnly, bothVerbs));
        authorization.authorizedIdsByPermission.put(Permission.HEALTH_VIEW, Set.of(healthOnly, bothVerbs));
        authorization.authorizedIdsByPermission.put(Permission.CONFIG_VIEW, Set.of(configOnly, bothVerbs));

        JsonNode items = json(read(ATTENTION, "tok-source")).get("items");

        Set<String> seen = new TreeSet<>();
        for (JsonNode item : items) {
            seen.add(item.get("reason").asText() + "@" + item.get("instanceId").asText());
        }
        assertEquals(new TreeSet<>(Set.of(
                "health@" + healthOnly,                       // health.view held, config.view not: only the health reason
                "apply-indeterminate@" + configOnly,          // config.view held, health.view not: only the apply reason
                "health@" + bothVerbs, "config-diverged@" + bothVerbs)), seen);
    }

    @Test
    void anInstanceTheCallerCannotViewHasNoAttentionItemWhateverVerbItHolds() throws Exception {
        login("tok-view");
        UUID hidden = UUID.randomUUID();
        attention.rows = List.of(health(hidden, UUID.randomUUID(), HealthState.RED, 1));
        authorization.authorizedIdsByPermission.put(Permission.HEALTH_VIEW, Set.of(hidden));
        authorization.authorizedIdsByPermission.put(Permission.INSTANCE_VIEW, Set.of());

        assertTrue(json(read(ATTENTION, "tok-view")).get("items").isEmpty(),
                "the operation requires instance.view as well (x-sm-permission-also)");
    }

    @Test
    void everyAttentionItemCarriesItsOwnStateVersion() throws Exception {
        login("tok-versions");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        attention.rows = List.of(health(a, UUID.randomUUID(), HealthState.RED, 17),
                apply(b, AttentionReason.APPLY_INDETERMINATE, 29));
        authorization.authorizedIds.addAll(Set.of(a, b));

        JsonNode body = json(read(ATTENTION, "tok-versions"));

        assertEquals(17, byReason(body.get("items"), "health").get("stateVersion").asLong());
        assertEquals(29, byReason(body.get("items"), "apply-indeterminate").get("stateVersion").asLong());
        assertFalse(body.has("stateVersion"), "a filtered collection has no version of its own (OR-38, OR-55)");
    }

    @Test
    void aDpOutageMakesAttentionUnavailableNotSilentlyEmpty() throws Exception {
        login("tok-attn-outage");
        attention.failure = new IllegalStateException("password authentication failed for user sm_dp_runtime");

        MvcResult result = read(ATTENTION, "tok-attn-outage");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertTrue(body.get("items").isEmpty());
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertFalse(result.getResponse().getContentAsString().contains("sm_dp_runtime"));
    }

    @Test
    void attentionOrganizationIsTheSessionsNeverARequestField() throws Exception {
        Session session = login("tok-attn-i5");

        mvc.perform(get(ATTENTION + "?organizationId=" + UUID.randomUUID()).cookie(new Cookie(COOKIE, "tok-attn-i5")))
                .andReturn();

        assertEquals(List.of(session.organizationId()), attention.organizationsSeen);
        assertNotNull(context.getBean(HomeController.class));
    }
}
