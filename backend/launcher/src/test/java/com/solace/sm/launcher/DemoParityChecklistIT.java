package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.NoResource;
import com.solace.sm.gateway.http.OperationClassification;
import com.solace.sm.gateway.http.OperationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Plan 007 Task 13a Step 1's parity checklist, as a test (plan 010 T12.1 Step 2): every capability the retained demo
 * served that the production API also owns has an AUTHORIZED production endpoint -- a route registered in the real
 * booted {@code SolutionManagerApplication}'s {@link RequestMappingHandlerMapping}, with the HTTP method and path the
 * contract names, carrying a {@link ConsoleOperation} whose {@link OperationPolicy} classification is exactly the one
 * the checklist expects (the permission, or the session-only kind), and -- for a catalog operation -- a resource
 * resolver, so it is authorized BEFORE the handler runs.
 *
 * <p>Removing or re-routing any one handler, or weakening its classification, fails the row that names it.
 *
 * <p>Metrics, dashboards and Explore are INCLUDED since plan 011 P11-9.1 (the demo's monitoring surface is retired in
 * the same change, so its parity is part of the gate). The demo capabilities with NO production endpoint, by design, are listed in
 * {@link #DEMO_CAPABILITIES_WITHOUT_A_PRODUCTION_ENDPOINT}; they are recorded, not asserted.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoParityChecklistIT {

    /** What a row expects of the operation's classification. */
    private sealed interface Expect {
        record SessionEstablishing() implements Expect { }
        record Local(LocalOperation operation) implements Expect { }
        record Catalog(Permission permission) implements Expect { }
        record Reach(Permission permission) implements Expect { }
        record AuthenticationOnly() implements Expect { }
    }

    private record Row(String capability, String operationId, RequestMethod method, String path, Expect expect) { }

    private static Row row(String capability, String operationId, RequestMethod method, String path, Expect expect) {
        return new Row(capability, operationId, method, path, expect);
    }

    private static final String CP = "/api/cp";
    private static final String DP = "/api/dp";
    private static final String CONSOLE = "/api/console/v1";

    /**
     * The checklist: 13a's ten capabilities, then plan 011 P11-9.1's three (metrics, dashboards, Explore): the demo's
     * twelve {@code DemoMonitoringController} operations plus the two it never served ({@code exportRange},
     * {@code declareMetricType}).
     */
    static final List<Row> CHECKLIST = List.of(
            // auth
            row("auth", "login", RequestMethod.POST, CP + "/auth/login", new Expect.SessionEstablishing()),
            row("auth", "logout", RequestMethod.POST, CP + "/auth/logout", new Expect.Local(LocalOperation.LOGOUT)),
            row("auth", "getIdentity", RequestMethod.GET, CP + "/auth/identity",
                    new Expect.Local(LocalOperation.READ_OWN_IDENTITY)),
            row("auth", "rotateOwnCredential", RequestMethod.POST, CP + "/auth/credential",
                    new Expect.Local(LocalOperation.ROTATE_OWN_CREDENTIAL)),
            // solutions
            row("solutions", "listSolutions", RequestMethod.GET, CP + "/solutions",
                    new Expect.Reach(Permission.SOLUTION_VIEW)),
            row("solutions", "loadPackage", RequestMethod.POST, CP + "/solutions/load",
                    new Expect.Catalog(Permission.PACKAGE_LOAD)),
            // instances
            row("instances", "listInstances", RequestMethod.GET, DP + "/solutions/{solutionId}/instances",
                    new Expect.Reach(Permission.INSTANCE_VIEW)),
            row("instances", "declareInstance", RequestMethod.POST, DP + "/environments/{environmentId}/instances",
                    new Expect.Catalog(Permission.INSTANCE_CREATE)),
            row("instances", "getInstance", RequestMethod.GET, DP + "/instances/{instanceId}",
                    new Expect.Catalog(Permission.INSTANCE_VIEW)),
            // designer
            row("designer", "getDesigner", RequestMethod.GET, DP + "/instances/{instanceId}/designer",
                    new Expect.Catalog(Permission.CONFIG_VIEW)),
            // config
            row("config", "stageConfig", RequestMethod.POST, DP + "/instances/{instanceId}/config",
                    new Expect.Catalog(Permission.CONFIG_EDIT)),
            row("config", "getConfigHistory", RequestMethod.GET, DP + "/instances/{instanceId}/config/history",
                    new Expect.Catalog(Permission.CONFIG_VIEW)),
            // apply
            row("apply", "submitApply", RequestMethod.POST, DP + "/instances/{instanceId}/apply",
                    new Expect.Catalog(Permission.CONFIG_APPLY)),
            row("apply", "observeApply", RequestMethod.GET, DP + "/instances/{instanceId}/apply/observe",
                    new Expect.Catalog(Permission.CONFIG_VIEW)),
            // restart: the demo's POST /instances/{id}/restart is, in production, an apply whose strategy restarts
            // (instance.restart is then required in-handler) -- there is no standalone restart route -- plus the stop
            row("restart", "submitApply", RequestMethod.POST, DP + "/instances/{instanceId}/apply",
                    new Expect.Catalog(Permission.CONFIG_APPLY)),
            row("restart", "stopInstance", RequestMethod.POST, DP + "/instances/{instanceId}/stop",
                    new Expect.Catalog(Permission.INSTANCE_STOP)),
            // health
            row("health", "getHealth", RequestMethod.GET, DP + "/instances/{instanceId}/health",
                    new Expect.Catalog(Permission.HEALTH_VIEW)),
            // ladder
            row("ladder", "getConfigLadder", RequestMethod.GET, DP + "/instances/{instanceId}/config-ladder",
                    new Expect.Catalog(Permission.CONFIG_VIEW)),
            // logs
            row("logs", "readLogs", RequestMethod.GET, DP + "/instances/{instanceId}/logs/{component}",
                    new Expect.Catalog(Permission.LOGS_READ)),
            row("logs", "getLogLevel", RequestMethod.GET, DP + "/instances/{instanceId}/log-levels/{component}",
                    new Expect.Catalog(Permission.LOGS_READ)),
            row("logs", "setLogLevel", RequestMethod.POST, DP + "/instances/{instanceId}/log-levels/{component}",
                    new Expect.Catalog(Permission.LOGS_SET_LEVEL)),
            // metrics (plan 011 P11-9.1 Step 1)
            row("metrics", "getCapabilities", RequestMethod.GET, CONSOLE + "/capabilities",
                    new Expect.AuthenticationOnly()),
            row("metrics", "listEntityTypes", RequestMethod.GET, CONSOLE + "/entity-types",
                    new Expect.Reach(Permission.METRICS_QUERY)),
            row("metrics", "listMetricInstances", RequestMethod.GET, CONSOLE + "/entity-types/{entityType}/instances",
                    new Expect.Reach(Permission.METRICS_QUERY)),
            row("metrics", "getMetricsCatalog", RequestMethod.GET, CONSOLE + "/metrics/catalog",
                    new Expect.Reach(Permission.METRICS_QUERY)),
            row("metrics", "getLabelValues", RequestMethod.GET, CONSOLE + "/labels/{labelKey}/values",
                    new Expect.Reach(Permission.METRICS_QUERY)),
            row("metrics", "queryInstant", RequestMethod.POST, CONSOLE + "/query/instant",
                    new Expect.Reach(Permission.METRICS_QUERY)),
            // never served by the demo
            row("metrics", "exportRange", RequestMethod.GET, CONSOLE + "/export/range",
                    new Expect.Reach(Permission.METRICS_EXPORT)),
            row("metrics", "declareMetricType", RequestMethod.POST, CONSOLE + "/metrics/catalog/{name}/type",
                    new Expect.Catalog(Permission.METRIC_DECLARE)),
            // dashboards
            row("dashboards", "getDashboard", RequestMethod.GET, CONSOLE + "/dashboards/{dashboardId}",
                    new Expect.Catalog(Permission.DASHBOARD_VIEW)),
            row("dashboards", "saveWidget", RequestMethod.POST, CONSOLE + "/dashboards/{dashboardId}/widgets",
                    new Expect.Catalog(Permission.DASHBOARD_EDIT)),
            row("dashboards", "setDashboardLayout", RequestMethod.PUT, CONSOLE + "/dashboards/{dashboardId}/layout",
                    new Expect.Catalog(Permission.DASHBOARD_EDIT)),
            row("dashboards", "setWidgetHidden", RequestMethod.PATCH,
                    CONSOLE + "/dashboards/{dashboardId}/widgets/{widgetId}",
                    new Expect.Catalog(Permission.DASHBOARD_EDIT)),
            row("dashboards", "resetDashboard", RequestMethod.POST, CONSOLE + "/dashboards/{dashboardId}/reset",
                    new Expect.Catalog(Permission.DASHBOARD_RESET)),
            // Explore is the ad-hoc range query; its drafts stay in the browser's sessionStorage (M-Q23)
            row("explore", "queryRange", RequestMethod.POST, CONSOLE + "/query/range",
                    new Expect.Reach(Permission.METRICS_QUERY)));

    /**
     * Demo capabilities with no production endpoint, each by a recorded decision (not asserted -- a finding list).
     */
    static final Map<String, String> DEMO_CAPABILITIES_WITHOUT_A_PRODUCTION_ENDPOINT = Map.of(
            "DELETE /instances/{id} (undeclare)", "no verb exists (console-integration.md 5.4 row 37, open item 39d)",
            "POST /solutions/load/confirm and /discard", "production loadPackage is one gated step (201 LoadPackageResult)",
            "POST /setup, GET /setup-status, /ssh-keys, /ssh-host-key", "the setup command is the v1 path (plan 010"
                    + " non-goals: target creation over HTTP)",
            "POST /packages (multipart)", "loadPackage takes the package in its body",
            "POST /instance/config/ttl, /instance/restart, /instance, GET /instance/*", "the demo's single-instance"
                    + " legacy surface; superseded by the instance-scoped routes above");

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.provision(registry);
    }

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyOverlappingDemoCapabilityHasAnAuthorizedProductionEndpoint() {
        Map<String, List<Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod>>>
                byOperation = handlerMapping.getHandlerMethods().entrySet().stream()
                        .collect(Collectors.groupingBy(entry -> {
                            ConsoleOperation annotation = AnnotatedElementUtils
                                    .findMergedAnnotation(entry.getValue().getMethod(), ConsoleOperation.class);
                            return annotation == null ? "<none>" : annotation.value();
                        }));
        List<String> failures = new ArrayList<>();
        for (Row row : CHECKLIST) {
            String label = row.capability() + " / " + row.operationId();
            var routes = byOperation.get(row.operationId());
            if (routes == null || routes.size() != 1) {
                failures.add(label + ": expected exactly one registered route, found "
                        + (routes == null ? 0 : routes.size()));
                continue;
            }
            var info = routes.get(0).getKey();
            HandlerMethod handler = routes.get(0).getValue();
            var patterns = info.getPathPatternsCondition() == null ? java.util.Set.<String>of()
                    : info.getPathPatternsCondition().getPatternValues();
            if (!patterns.contains(row.path())) {
                failures.add(label + ": path " + row.path() + " not registered (found " + patterns + ")");
            }
            if (!info.getMethodsCondition().getMethods().contains(row.method())) {
                failures.add(label + ": method " + row.method() + " not registered (found "
                        + info.getMethodsCondition().getMethods() + ")");
            }
            OperationClassification actual = OperationPolicy.classify(row.operationId());
            if (!matches(row.expect(), actual)) {
                failures.add(label + ": classification " + actual + " is not the expected " + row.expect());
            }
            if (row.expect() instanceof Expect.Catalog) {
                ConsoleOperation annotation = AnnotatedElementUtils
                        .findMergedAnnotation(handler.getMethod(), ConsoleOperation.class);
                assertNotNull(annotation);
                if (annotation.resource() == NoResource.class) {
                    failures.add(label + ": a catalog operation names no resource resolver, so it is not"
                            + " authorized before the handler runs");
                }
            }
        }
        assertTrue(failures.isEmpty(), "demo parity checklist failures:\n  " + String.join("\n  ", failures));
        assertNotEquals(0, CHECKLIST.size());
        assertEquals(13, CHECKLIST.stream().map(Row::capability).distinct().count(),
                "plan 007 13a names ten overlapping capabilities; plan 011 P11-9.1 adds metrics, dashboards, explore");
        assertEquals(14, CHECKLIST.stream().filter(r -> r.path().startsWith(CONSOLE)).count(),
                "the demo's twelve monitoring operations plus the two it never served");
    }

    private static boolean matches(Expect expect, OperationClassification actual) {
        if (expect instanceof Expect.SessionEstablishing) {
            return actual instanceof OperationClassification.SessionEstablishing;
        }
        if (expect instanceof Expect.Local local) {
            return actual instanceof OperationClassification.Local found && found.operation() == local.operation();
        }
        if (expect instanceof Expect.Catalog catalog) {
            return actual instanceof OperationClassification.Catalog found && found.permission() == catalog.permission();
        }
        if (expect instanceof Expect.AuthenticationOnly) {
            return actual instanceof OperationClassification.AuthenticationOnly;
        }
        if (expect instanceof Expect.Reach reach) {
            return actual instanceof OperationClassification.Reach found && found.permission() == reach.permission();
        }
        return false;
    }
}
