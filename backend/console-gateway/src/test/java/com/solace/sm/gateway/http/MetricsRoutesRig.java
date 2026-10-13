package com.solace.sm.gateway.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.dashboard.DashboardStore;
import com.solace.sm.cp.api.dashboard.DashboardView;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.WidgetSubmission;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.ExportPlan;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.dp.api.metrics.query.ObservedMetric;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeConnections;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeGovernance;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import com.solace.sm.gateway.http.metrics.CapabilitiesController;
import com.solace.sm.gateway.http.metrics.CatalogComposer;
import com.solace.sm.gateway.http.metrics.DashboardController;
import com.solace.sm.gateway.http.metrics.DashboardResourceResolver;
import com.solace.sm.gateway.http.metrics.DataPlaneQueryLimits;
import com.solace.sm.gateway.http.metrics.ExportController;
import com.solace.sm.gateway.http.metrics.MetricsCatalogController;
import com.solace.sm.gateway.http.metrics.MetricsErrorAdvice;
import com.solace.sm.gateway.http.metrics.MetricsGatewayProperties;
import com.solace.sm.gateway.http.metrics.MetricsRateGate;
import com.solace.sm.gateway.http.metrics.MonitoringInstances;
import com.solace.sm.gateway.http.metrics.QueryComposer;
import com.solace.sm.gateway.http.metrics.QueryController;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
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
 * The rig the monitoring route tests (plan 011 P11-7.2) share: the real filter, interceptor, argument resolver, controllers
 * and advice, with every cp-api and dp-api port FAKED. What the tests pin is the gateway's: who may call, what is filtered,
 * in what order the planes are read, what an unreachable id looks like, and the wire.
 *
 * <p>The tests live in {@code com.solace.sm.gateway.http} (not beside the controllers in {@code http.metrics}) because the
 * fakes they reuse ({@link GatewayTestSupport}, {@link InstanceControllerTest}) are package-private.
 */
abstract class MetricsRoutesRig {

    static final String COOKIE = "__Host-SM_SESSION";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String V1 = "/api/console/v1";
    static final String SOLUTION = "cache";

    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    FakeAuthorizationService authorization;
    FakeInventory inventory;
    FakeCatalog catalog;
    FakeQuery query;
    FakeExport export;
    FakeAudit audit;
    FakeStore store;
    FakeCodec codec;
    MetricsRateGate gate;
    GatewayTestSupport.MutableClock clock;
    UUID organization;
    UUID catalogSolution;
    UUID instanceA;
    UUID instanceB;
    Session session;

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean Calls calls() {
            return new Calls();
        }

        @Bean FakeInventory inventory(Calls calls) {
            return new FakeInventory(calls);
        }

        @Bean FakeConnections connections() {
            return new FakeConnections();
        }

        @Bean FakeGovernance governance(Calls calls) {
            return new FakeGovernance(calls);
        }

        @Bean FakeCatalog catalog() {
            return new FakeCatalog();
        }

        @Bean FakeCodec codec() {
            return new FakeCodec();
        }

        @Bean MetricsFakes.RecordingApplicability applicability() {
            return new MetricsFakes.RecordingApplicability();
        }

        @Bean FakeQuery query() {
            return new FakeQuery();
        }

        @Bean FakeExport export() {
            return new FakeExport();
        }

        @Bean FakeAudit audit() {
            return new FakeAudit();
        }

        @Bean FakeStore store() {
            return new FakeStore();
        }

        @Bean MetricsRateGate rateGate(
                @org.springframework.beans.factory.annotation.Qualifier("authRateClock") GatewayTestSupport.MutableClock rateClock) {
            return new MetricsRateGate(10, () -> rateClock.millis() * 1_000_000L);
        }

        @Bean MetricsGatewayProperties properties() {
            return MetricsGatewayProperties.defaults();
        }

        @Bean DataPlaneQueryLimits limits() {
            return DataPlaneQueryLimits.defaults();
        }

        @Bean InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean SessionOrganizationResourceResolver sessionOrganizationResourceResolver() {
            return new SessionOrganizationResourceResolver();
        }

        @Bean DashboardResourceResolver dashboardResourceResolver() {
            return new DashboardResourceResolver();
        }

        @Bean DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory,
                TargetConnectionService connections, InstanceGovernance governance, SolutionCatalog catalog,
                AuthorizationService authorization, ReachAuthorization reach, DesignerCodec codec,
                MetricApplicability applicability) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10));
        }

        @Bean MonitoringInstances monitoringInstances(SolutionCatalog catalog, InstanceInventoryService inventory,
                ReachAuthorization reach, DesignerCodec codec) {
            return new MonitoringInstances(catalog, inventory, reach, codec);
        }

        @Bean CatalogComposer catalogComposer(MonitoringInstances instances, MetricsQueryService query) {
            return new CatalogComposer(instances, query);
        }

        @Bean QueryComposer queryComposer(MonitoringInstances instances, ReachAuthorization reach,
                MetricsQueryService query) {
            return new QueryComposer(instances, reach, query);
        }

        @Bean CapabilitiesController capabilitiesController(MetricsQueryService query, MetricsGatewayProperties properties,
                DataPlaneQueryLimits limits) {
            return new CapabilitiesController(query, properties, limits);
        }

        @Bean MetricsCatalogController metricsCatalogController(MonitoringInstances instances, CatalogComposer catalog) {
            return new MetricsCatalogController(instances, catalog);
        }

        @Bean QueryController queryController(QueryComposer composer, MetricsRateGate gate) {
            return new QueryController(composer, gate);
        }

        @Bean ExportController exportController(MonitoringInstances instances, MetricsExportService export,
                ExportAudit audit, MetricsRateGate gate, RequestOriginFactory origin, MetricsGatewayProperties properties,
                @org.springframework.beans.factory.annotation.Qualifier("authRateClock") GatewayTestSupport.MutableClock rateClock) {
            return new ExportController(instances, export, audit, gate, origin, properties,
                    () -> rateClock.millis() * 1_000_000L);
        }

        @Bean DashboardController dashboardController(DeclareOrchestrator instances, MonitoringInstances monitoring,
                MetricsQueryService query, DashboardStore store, RequestOriginFactory origin) {
            return new DashboardController(instances, monitoring, query, store, origin);
        }

        /** W4-09: the MVC async executor a test can make refuse (a stand-in for every bounded export thread being busy). */
        @Bean RefusableExecutor refusableExecutor() {
            return new RefusableExecutor();
        }

        @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer rigAsyncSupport(RefusableExecutor executor) {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer c) {
                    c.setTaskExecutor(executor);
                }
            };
        }

        @Bean com.solace.sm.gateway.http.metrics.ExportRefusalAuditInterceptor exportRefusalAuditInterceptor(
                ExportAudit audit, RequestOriginFactory origin) {
            return new com.solace.sm.gateway.http.metrics.ExportRefusalAuditInterceptor(audit, origin);
        }

        @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer exportRefusalAuditRegistration(
                com.solace.sm.gateway.http.metrics.ExportRefusalAuditInterceptor interceptor) {
            return com.solace.sm.gateway.http.metrics.ExportRefusalAuditInterceptor.registration(interceptor);
        }

        @Bean MetricsErrorAdvice metricsErrorAdvice() {
            return new MetricsErrorAdvice();
        }
    }

    @BeforeEach
    void setUpRig() {
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
        catalog = context.getBean(FakeCatalog.class);
        query = context.getBean(FakeQuery.class);
        export = context.getBean(FakeExport.class);
        audit = context.getBean(FakeAudit.class);
        store = context.getBean(FakeStore.class);
        codec = context.getBean(FakeCodec.class);
        gate = context.getBean(MetricsRateGate.class);
        clock = context.getBean("authRateClock", GatewayTestSupport.MutableClock.class);
        organization = UUID.randomUUID();
        catalogSolution = UUID.randomUUID();
        instanceA = UUID.randomUUID();
        instanceB = UUID.randomUUID();
        catalog.entries.add(new CatalogEntry(catalogSolution, SOLUTION, SOLUTION, List.of(PackageVersionId.of(1)),
                PackageVersionId.of(1), "unsigned", 3));
        catalog.serve(catalogSolution, "1", "pkg-1");
        codec.packages.put("pkg-1", cachePackage());
        putInstance(instanceA, "mon-a", InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH);
        putInstance(instanceB, "mon-b", InstanceLifecycle.ACTIVE, AcquisitionMode.DIRECT_HTTP);
        session = login("token");
    }

    void putInstance(UUID id, String name, InstanceLifecycle lifecycle, AcquisitionMode mode) {
        inventory.put(new InstanceView(id, name, UUID.randomUUID(), catalogSolution, "1", lifecycle, mode,
                List.of(new PlacementView(UUID.randomUUID(), UUID.randomUUID(), "sm-" + name,
                        Optional.of(ProbeStrength.LIVENESS))), 5L));
    }

    Session login(String token) {
        Session created = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, created, "csrf-" + token);
        return created;
    }

    // ---- the package -----------------------------------------------------------------------------------------------

    static PackageModel.PackageMetric metric(String name, String type, String unit, String kind, Integer port,
            Boolean defaultChart) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", kind);
        if (port != null) {
            source.put("port", port);
        }
        source.put("onUnavailable", "degrade");
        return new PackageModel.PackageMetric(name, type, unit, "package-baseline", null, source, defaultChart);
    }

    /** One component {@code cache-engine}: a gauge, a counter, a histogram, a push-sourced gauge and a portless one. */
    static PackageModel.SolutionPackage cachePackage() {
        PackageModel.PackageComponent component = new PackageModel.PackageComponent("cache-engine", "exactly-one", null,
                "none", null, null, null, List.of(), List.of(), List.of(
                        metric("cache.size", "gauge", "count", "http_json", 8081, null),
                        metric("cache.hit_count", "counter", "events", "http_json", 8081, null),
                        metric("cache.latency", "histogram", "ms", "http_json", 8081, null),
                        metric("cache.pushed", "gauge", "count", "solace_event", null, false),
                        metric("cache.portless", "gauge", "count", "http_json", null, null)),
                null, Map.of(), null);
        return new PackageModel.SolutionPackage(SOLUTION, List.of(1), null, null, List.of(component));
    }

    // ---- requests --------------------------------------------------------------------------------------------------

    MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.cookie(new Cookie(COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-" + token);
    }

    MvcResult getAs(String token, String path) throws Exception {
        return mvc.perform(get(V1 + path).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    MvcResult postAs(String token, String path, Object body) throws Exception {
        return mvc.perform(authed(post(V1 + path), token).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String text ? text : MAPPER.writeValueAsString(body))).andReturn();
    }

    MvcResult sendAs(String method, String token, String path, String body, Map<String, String> headers)
            throws Exception {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "PUT" -> put(V1 + path);
            case "PATCH" -> patch(V1 + path);
            default -> post(V1 + path);
        };
        authed(builder, token);
        headers.forEach(builder::header);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder).andReturn();
    }

    static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    static Map<String, Object> queryBody(String metric, String resolution, String across, UUID... ids) {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("entityType", "cache-engine");
        List<String> list = new ArrayList<>();
        for (UUID id : ids) {
            list.add(id.toString());
        }
        selector.put("instanceIds", list);
        selector.put("labels", Map.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryVersion", 1);
        body.put("metric", metric);
        body.put("selector", selector);
        body.put("range", Map.of("from", "2026-10-06T00:00:00Z", "to", "2026-10-06T01:00:00Z"));
        body.put("resolution", resolution);
        body.put("seriesBy", "instance");
        body.put("aggregation", Map.of("acrossReplicas", "auto", "acrossInstances", across));
        return body;
    }

    /** A result for {@code cache.size} with one empty series per id. */
    static RangeResult resultFor(List<UUID> ids) {
        List<com.solace.sm.dp.api.metrics.query.SeriesView> series = new ArrayList<>();
        for (UUID id : ids) {
            series.add(new com.solace.sm.dp.api.metrics.query.SeriesView(
                    new com.solace.sm.dp.api.metrics.query.SeriesView.SeriesRef(id, null, null), "i-" + id.toString().substring(0, 4),
                    1, 0, com.solace.sm.dp.api.metrics.query.SeriesView.State.OK,
                    List.of(new com.solace.sm.dp.api.metrics.query.SeriesView.Point(Instant.parse("2026-10-06T00:30:00Z"),
                            4.0, 2, 2)),
                    List.of()));
        }
        return new RangeResult(new RangeResult.Metric("cache.size", RangeResult.MetricType.GAUGE, "count"),
                new RangeResult.Render(RangeResult.Transform.NONE, null),
                new RangeResult.Served(RangeQuery.Resolution.AUTO, RangeResult.Tier.RAW, 30),
                new RangeResult.Coverage(Instant.parse("2026-10-06T00:00:00Z"), Instant.parse("2026-10-06T00:00:00Z"), false,
                        null),
                60, series, new RangeResult.Partial(false, List.of()));
    }

    // ---- fakes -----------------------------------------------------------------------------------------------------

    /** An executor that runs on a thread per task, or refuses like a bounded pool whose threads are all busy. */
    static final class RefusableExecutor extends org.springframework.core.task.SimpleAsyncTaskExecutor {
        volatile boolean refuse;

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            refuseIfAsked();
            return super.submit(task);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            refuseIfAsked();
            return super.submit(task);
        }

        @Override
        public void execute(Runnable task) {
            refuseIfAsked();
            super.execute(task);
        }

        private void refuseIfAsked() {
            if (refuse) {
                throw new org.springframework.core.task.TaskRejectedException("every export thread is busy");
            }
        }
    }

    static final class FakeCatalog implements SolutionCatalog {
        final List<CatalogEntry> entries = new ArrayList<>();
        final Map<String, String> documents = new HashMap<>();
        volatile long documentDelayMillis;
        volatile RuntimeException documentFailure;

        void serve(UUID solution, String version, String document) {
            documents.put(solution + "|" + version, document);
        }

        @Override
        public PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson) {
            throw new AssertionError("a monitoring route never loads a package");
        }

        @Override
        public List<CatalogEntry> list(UUID organizationId) {
            return entries;
        }

        @Override
        public Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            throw new AssertionError("a monitoring route reads documentOf, not find");
        }

        @Override
        public Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            if (documentDelayMillis > 0) {
                try {
                    Thread.sleep(documentDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (documentFailure != null) {
                throw documentFailure;
            }
            return Optional.ofNullable(documents.get(catalogSolutionId + "|" + packageVersion));
        }
    }

    static final class FakeCodec implements DesignerCodec {
        final Map<String, PackageModel.SolutionPackage> packages = new HashMap<>();

        @Override
        public PackageModel.SolutionPackage readPackage(String packageDocumentJson) {
            PackageModel.SolutionPackage found = packages.get(packageDocumentJson);
            if (found == null) {
                throw new IllegalArgumentException("not a package");
            }
            return found;
        }

        @Override
        public Map<String, Object> viewModelTree(ViewModel viewModel) {
            throw new AssertionError("a monitoring route never projects a view model");
        }
    }

    static final class FakeQuery implements MetricsQueryService {
        final List<UUID> organizations = Collections.synchronizedList(new ArrayList<>());
        final List<RangeQuery> ranges = Collections.synchronizedList(new ArrayList<>());
        final List<RangeQuery> instants = Collections.synchronizedList(new ArrayList<>());
        final List<Set<UUID>> observedFor = Collections.synchronizedList(new ArrayList<>());
        final List<String> observedEntityTypes = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException failure;
        volatile RangeResult next;
        volatile List<ObservedMetric> observed = List.of();
        volatile RuntimeException observedFailure;
        volatile long observedDelayMillis;
        volatile List<String> active = List.of("cache.size", "cache.hit_count");
        volatile CollectorHealthView health = new CollectorHealthView(CollectorHealthView.State.LIVE,
                java.time.LocalDate.parse("2027-01-01"), 0, List.of(), new DiskAlarmView(DiskAlarmView.State.OK));
        final AtomicLong activeCalls = new AtomicLong();

        @Override
        public RangeResult range(UUID organizationId, RangeQuery query) {
            organizations.add(organizationId);
            ranges.add(query);
            if (failure != null) {
                throw failure;
            }
            return next != null ? next : resultFor(query.instanceIds());
        }

        @Override
        public RangeResult instant(UUID organizationId, RangeQuery query) {
            organizations.add(organizationId);
            instants.add(query);
            if (failure != null) {
                throw failure;
            }
            return next != null ? next : resultFor(query.instanceIds());
        }

        @Override
        public List<ObservedMetric> observed(UUID organizationId, String entityType, Set<UUID> instanceIds) {
            organizations.add(organizationId);
            observedFor.add(instanceIds);
            observedEntityTypes.add(entityType);
            if (observedDelayMillis > 0) {
                try {
                    Thread.sleep(observedDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (observedFailure != null) {
                throw observedFailure;
            }
            return observed;
        }

        @Override
        public List<String> activeMetrics(UUID organizationId, UUID instanceId) {
            organizations.add(organizationId);
            activeCalls.incrementAndGet();
            return active;
        }

        @Override
        public CollectorHealthView collectorHealth(UUID organizationId) {
            organizations.add(organizationId);
            return health;
        }
    }

    static final class FakeExport implements MetricsExportService {
        final List<UUID> planned = Collections.synchronizedList(new ArrayList<>());
        final List<ExportCommand> streamed = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException planFailure;
        /** Thrown by {@code stream} after {@link #writeBeforeFailing} bytes-worth of the body was written. */
        volatile Exception streamFailure;
        volatile boolean writeBeforeFailing;
        /** Runs between the body's two halves: a test moves the clock, or fails, in the middle of a stream. */
        volatile Runnable midStream;
        volatile String header = "instance_id,placement_id,timestamp,value\r\n";
        volatile String rowsText = "row\r\nrow\r\n";
        volatile long rows = 2;

        @Override
        public ExportPlan plan(UUID organizationId, ExportCommand command) {
            planned.add(organizationId);
            if (planFailure != null) {
                throw planFailure;
            }
            return new ExportPlan(rows, 20_000_000);
        }

        @Override
        public long stream(UUID organizationId, ExportCommand command, OutputStream out) throws IOException {
            streamed.add(command);
            if (streamFailure != null) {
                if (writeBeforeFailing) {
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                }
                if (streamFailure instanceof IOException io) {
                    throw io;
                }
                throw (RuntimeException) streamFailure;
            }
            out.write(header.getBytes(StandardCharsets.UTF_8));
            if (midStream != null) {
                midStream.run();
            }
            out.write(rowsText.getBytes(StandardCharsets.UTF_8));
            return rows;
        }
    }

    static final class FakeAudit implements ExportAudit {
        record Row(PrincipalRef actor, TenantId organization, UUID correlationId, long rows, ExportResult result) {
        }

        record Denial(PrincipalRef actor, TenantId organization, UUID correlationId, RefusalReason reason) {
        }

        final List<Row> rows = Collections.synchronizedList(new ArrayList<>());
        /** The DENY rows of refused exports (OR-112 (6), W4-04): never mixed with {@link #rows}, which are exports. */
        final List<Denial> denials = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException failure;

        @Override
        public void refused(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId,
                RefusalReason reason) {
            if (failure != null) {
                throw failure;
            }
            this.denials.add(new Denial(actor, organization, correlationId, reason));
        }

        @Override
        public void record(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId, long rows,
                ExportResult result) {
            if (failure != null) {
                throw failure;
            }
            this.rows.add(new Row(actor, organization, correlationId, rows, result));
        }
    }

    /** A dashboard store that records every call and FAILS the test if it is reached when it must not be. */
    static final class FakeStore implements DashboardStore {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Long> revisions = Collections.synchronizedList(new ArrayList<>());
        final List<DefaultLayerInput> inputs = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException failure;
        volatile String savedId = "w_0123456789ab";

        private DashboardView view(UUID instanceId, long revision, String savedWidgetId) {
            String widgets = savedWidgetId == null ? "[]" : "[{\"id\":\"" + savedWidgetId + "\",\"layer\":\"shared\"}]";
            return new DashboardView("db_" + instanceId, instanceId, revision, "{\"id\":\"db_" + instanceId
                    + "\",\"schemaVersion\":1,\"widgets\":" + widgets + ",\"layout\":[],\"range\":{\"preset\":\"24h\"},"
                    + "\"refresh\":{\"intervalSeconds\":null}}", savedWidgetId);
        }

        private void record(String call, UUID instance, long revision, DefaultLayerInput input) {
            calls.add(call + ":" + instance);
            revisions.add(revision);
            inputs.add(input);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public DashboardView get(TenantId organization, UUID instanceId, DefaultLayerInput input) {
            record("get", instanceId, 0, input);
            return view(instanceId, 4, null);
        }

        @Override
        public DashboardView saveWidget(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
                long expectedRevision, WidgetSubmission widget, DefaultLayerInput input) {
            record("saveWidget", instanceId, expectedRevision, input);
            return view(instanceId, expectedRevision + 1, savedId);
        }

        @Override
        public DashboardView setLayout(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
                long expectedRevision, String layoutJson, DefaultLayerInput input) {
            record("setLayout", instanceId, expectedRevision, input);
            return view(instanceId, expectedRevision + 1, null);
        }

        @Override
        public DashboardView setHidden(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
                long expectedRevision, String widgetId, boolean hidden, DefaultLayerInput input) {
            record("setHidden", instanceId, expectedRevision, input);
            return view(instanceId, expectedRevision + 1, null);
        }

        @Override
        public DashboardView reset(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
                long expectedRevision, DefaultLayerInput input) {
            record("reset", instanceId, expectedRevision, input);
            return view(instanceId, expectedRevision + 1, null);
        }
    }
}
