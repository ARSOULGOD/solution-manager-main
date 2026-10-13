package com.solace.sm.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.cp.api.authz.ConsoleOperations;
import com.solace.sm.cp.api.authz.Permission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-1.2: the fourteen {@code /api/console/v1} operations (console-integration.md §5.4, rows 53-66)
 * against the server's classification of them.
 *
 * <p>{@code ConsoleOperationsMirrorTest} proves each carries the contract's verb and
 * {@code ReachOperationsMirrorTest} proves the reach set equals the contract's {@code x-sm-scope: reach} set.
 * Neither says WHICH operation ought to be which, so a wrong pair edited together (a contract and a registry that
 * both call {@code queryRange} {@code organization}) would pass both. This test pins the ruled classification of
 * each row (M-Q17 {@code OR-79}, M-Q16 {@code OR-80}, M-Q6 {@code OR-75}) from the plan, not from the files.
 *
 * <p>No handler exists yet (P11-7.2 serves them), so this reads the classification and the contract only.
 */
class MetricsOperationClassificationTest {

    private static final JsonNode SPEC = readSpec();

    /** operationId to {HTTP method, path (under the {@code /api} server), verb, scope}. Rows 53-66, in order. */
    private static final Map<String, String[]> ROWS = new LinkedHashMap<>();

    static {
        ROWS.put("getCapabilities", new String[] {"get", "/console/v1/capabilities", "none.authenticationOnly", "install"});
        ROWS.put("listEntityTypes", new String[] {"get", "/console/v1/entity-types", "metrics.query", "reach"});
        ROWS.put("listMetricInstances", new String[] {"get", "/console/v1/entity-types/{entityType}/instances",
            "metrics.query", "reach"});
        ROWS.put("getMetricsCatalog", new String[] {"get", "/console/v1/metrics/catalog", "metrics.query", "reach"});
        ROWS.put("getLabelValues", new String[] {"get", "/console/v1/labels/{labelKey}/values", "metrics.query", "reach"});
        ROWS.put("queryRange", new String[] {"post", "/console/v1/query/range", "metrics.query", "reach"});
        ROWS.put("queryInstant", new String[] {"post", "/console/v1/query/instant", "metrics.query", "reach"});
        ROWS.put("exportRange", new String[] {"get", "/console/v1/export/range", "metrics.export", "reach"});
        ROWS.put("declareMetricType", new String[] {"post", "/console/v1/metrics/catalog/{name}/type", "metric.declare",
            "organization"});
        ROWS.put("getDashboard", new String[] {"get", "/console/v1/dashboards/{dashboardId}", "dashboard.view", "instance"});
        ROWS.put("saveWidget", new String[] {"post", "/console/v1/dashboards/{dashboardId}/widgets", "dashboard.edit",
            "instance"});
        ROWS.put("setDashboardLayout", new String[] {"put", "/console/v1/dashboards/{dashboardId}/layout", "dashboard.edit",
            "instance"});
        ROWS.put("setWidgetHidden", new String[] {"patch", "/console/v1/dashboards/{dashboardId}/widgets/{widgetId}",
            "dashboard.edit", "instance"});
        ROWS.put("resetDashboard", new String[] {"post", "/console/v1/dashboards/{dashboardId}/reset", "dashboard.reset",
            "instance"});
    }

    @Test
    void theContractPublishesExactlyTheFourteenRowsAtTheirPathsVerbsAndScopes() {
        assertThat(ROWS).hasSize(14);
        List<String> wrong = new ArrayList<>();
        ROWS.forEach((operationId, row) -> {
            JsonNode operation = SPEC.at("/paths/" + pointer(row[1]) + "/" + row[0]);
            if (operation.isMissingNode()) {
                wrong.add(operationId + ": no " + row[0].toUpperCase() + " " + row[1]);
                return;
            }
            if (!operationId.equals(operation.path("operationId").asText())) {
                wrong.add(operationId + ": the operation at that path is " + operation.path("operationId").asText());
            }
            if (!row[2].equals(operation.path("x-sm-permission").asText())) {
                wrong.add(operationId + ": verb " + operation.path("x-sm-permission").asText() + ", expected " + row[2]);
            }
            if (!row[3].equals(operation.path("x-sm-scope").asText())) {
                wrong.add(operationId + ": scope " + operation.path("x-sm-scope").asText() + ", expected " + row[3]);
            }
        });
        assertThat(wrong).as("rows 53-66 of console-integration.md section 5.4, as the contract publishes them").isEmpty();
    }

    @Test
    void getCapabilitiesClassifiesAuthenticationOnly() {
        // Row 53: "authentication only", install scope. Any authenticated principal of any organization reads it,
        // which is why its description carries no byte figure (see theCapabilitiesSchemaCarriesNoByteFigure).
        assertThat(OperationPolicy.classify("getCapabilities"))
                .isInstanceOf(OperationClassification.AuthenticationOnly.class);
        assertThat(ConsoleOperations.permissionFor("getCapabilities"))
                .as("an authentication-only operation carries no catalog verb").isEmpty();
    }

    @Test
    void queryRangeIsReachClassified() {
        // M-Q17 (OR-79): OR-51's reach filtering governs metrics queries, rows 54-60.
        Map<String, Permission> reachRows = new LinkedHashMap<>();
        reachRows.put("listEntityTypes", Permission.METRICS_QUERY);
        reachRows.put("listMetricInstances", Permission.METRICS_QUERY);
        reachRows.put("getMetricsCatalog", Permission.METRICS_QUERY);
        reachRows.put("getLabelValues", Permission.METRICS_QUERY);
        reachRows.put("queryRange", Permission.METRICS_QUERY);
        reachRows.put("queryInstant", Permission.METRICS_QUERY);
        reachRows.put("exportRange", Permission.METRICS_EXPORT);
        reachRows.forEach((operationId, permission) -> assertThat(OperationPolicy.classify(operationId))
                .as(operationId + " is row 54-60: reach-classified at its own verb")
                .isEqualTo(new OperationClassification.Reach(permission)));
        assertThat(OperationPolicy.reachOperations()).containsAll(reachRows.keySet());
    }

    @Test
    void metricDeclareIsCatalogClassifiedAtTheOrganization() {
        // Row 61: `metric.declare` at the organization (a single resource), and a contracted refusal (M-Q6, OR-75).
        assertThat(OperationPolicy.classify("declareMetricType"))
                .isEqualTo(new OperationClassification.Catalog(Permission.METRIC_DECLARE));
        assertThat(OperationPolicy.reachOperations()).doesNotContain("declareMetricType");
        JsonNode operation = SPEC.at("/paths/" + pointer("/console/v1/metrics/catalog/{name}/type") + "/post");
        assertThat(operation.path("x-sm-scope").asText()).isEqualTo("organization");
        assertThat(operation.path("responses").has("501"))
                .as("M-Q6: the typing route is a contracted refusal, 501 NOT_IMPLEMENTED, reason metric-declare-deferred")
                .isTrue();
        assertThat(operation.path("responses").path("501").path("description").asText())
                .contains("metric-declare-deferred");
    }

    @Test
    void everyDashboardOperationIsCatalogClassified() {
        // Rows 62-66: authorized at the dashboard's instance, resolved from the dashboardId path parameter.
        Map<String, Permission> dashboardRows = new LinkedHashMap<>();
        dashboardRows.put("getDashboard", Permission.DASHBOARD_VIEW);
        dashboardRows.put("saveWidget", Permission.DASHBOARD_EDIT);
        dashboardRows.put("setDashboardLayout", Permission.DASHBOARD_EDIT);
        dashboardRows.put("setWidgetHidden", Permission.DASHBOARD_EDIT);
        dashboardRows.put("resetDashboard", Permission.DASHBOARD_RESET);
        dashboardRows.forEach((operationId, permission) -> {
            assertThat(OperationPolicy.classify(operationId))
                    .as(operationId).isEqualTo(new OperationClassification.Catalog(permission));
            assertThat(OperationPolicy.reachOperations()).as(operationId + " is not reach").doesNotContain(operationId);
        });
    }

    @Test
    void row55CarriesMetricsQueryAndInstanceView() {
        // Row 55 carries two verbs (console-integration.md:397), and Reach(Permission) carries one: the classification
        // is the first, the contract names the second so the handler (P11-7.2) intersects the two reachable sets.
        assertThat(OperationPolicy.classify("listMetricInstances"))
                .isEqualTo(new OperationClassification.Reach(Permission.METRICS_QUERY));
        JsonNode operation = SPEC.at("/paths/" + pointer("/console/v1/entity-types/{entityType}/instances") + "/get");
        assertThat(operation.path("x-sm-permission").asText()).isEqualTo("metrics.query");
        assertThat(texts(operation.path("x-sm-permission-also"))).containsExactly("instance.view");
    }

    @Test
    void theForceRawAndExportVerbsAreNamedWhereTheyBind() {
        // Row 58: `metrics.forceRaw` when the request forces the raw tier. Rows 59-60 carry no second verb.
        assertThat(texts(SPEC.at("/paths/" + pointer("/console/v1/query/range") + "/post/x-sm-permission-also")))
                .containsExactly("metrics.forceRaw");
        assertThat(SPEC.at("/paths/" + pointer("/console/v1/query/instant") + "/post").has("x-sm-permission-also")).isFalse();
        assertThat(SPEC.at("/paths/" + pointer("/console/v1/export/range") + "/get").has("x-sm-permission-also")).isFalse();
        assertThat(OperationPolicy.classify("exportRange"))
                .as("OR-80 gives the Operator metrics.export, and it stays a high-consequence verb")
                .isEqualTo(new OperationClassification.Reach(Permission.METRICS_EXPORT));
    }

    @Test
    void theCapabilitiesSchemaCarriesNoByteFigure() {
        // OQ-16 (a), OR-92: row 53 is authentication-only at install scope, so any principal of any organization reads
        // it. Byte figures are install-wide totals across every tenant; they are printed only by
        // `MetricsPartitionMain status` (an operator command), never by the API.
        JsonNode capabilities = SPEC.at("/components/schemas/Capabilities");
        assertThat(capabilities.isMissingNode()).as("components.schemas.Capabilities").isFalse();
        List<String> names = new ArrayList<>();
        collectPropertyNames(capabilities, names, new TreeSet<>());
        assertThat(names).as("every property name reachable from Capabilities").isNotEmpty()
                .noneMatch(name -> name.toLowerCase().contains("bytes"));
        JsonNode disk = capabilities.at("/properties/collector/properties/disk");
        assertThat(disk.isMissingNode()).as("capabilities.collector.disk").isFalse();
        assertThat(toList(disk.path("properties").fieldNames())).containsExactly("state");
        assertThat(texts(disk.at("/properties/state/enum")))
                .containsExactly("ok", "warning", "critical", "unconfigured");
    }

    @Test
    void theCapabilitiesCollectorMayLackItsRunwayEndAndDiskAndSaysUnknown() {
        // OR-112 (6), W4-05
        JsonNode collector = SPEC.at("/components/schemas/Capabilities/properties/collector");
        assertThat(texts(collector.get("required")))
                .as("runwayEnd and disk are optional: before the first partition keep, or before a disk measurement, they are absent")
                .containsExactlyInAnyOrder("state", "unfrozenClosedPartitions", "reasons");
        assertThat(collector.at("/properties/runwayEnd").isMissingNode()).isFalse();
        assertThat(collector.at("/properties/disk").isMissingNode()).isFalse();
        assertThat(texts(collector.at("/properties/state/enum"))).containsExactly("live", "degraded", "unknown");
    }

    @Test
    void anAggregateSeriesRefMayLackItsInstanceId() {
        // OR-112 (6), W4-06: nullable (and still required, as placementId and sourceKey are), no nil-UUID sentinel
        JsonNode ref = SPEC.at("/components/schemas/MetricQueryResult/properties/series/items/properties/seriesRef");
        assertThat(ref.isMissingNode()).isFalse();
        assertThat(texts(ref.get("required"))).containsExactlyInAnyOrder("instanceId", "placementId", "sourceKey");
        assertThat(texts(ref.at("/properties/instanceId/type"))).containsExactly("string", "null");
        assertThat(ref.at("/properties/instanceId/format").asText()).isEqualTo("uuid");
        assertThat(ref.toString()).as("the nil UUID is no longer a wire value").doesNotContain("00000000-0000");
    }

    @Test
    void theNewErrorCodesCarryTheirStatuses() {
        // The 422 and 409 codes the metrics surface adds (plan 011 P11-1.2, item 6).
        Map<ErrorCode, Integer> expected = new LinkedHashMap<>();
        expected.put(ErrorCode.valueOf("SERIES_CAP_EXCEEDED"), 422);
        expected.put(ErrorCode.valueOf("AGGREGATION_NOT_APPLICABLE"), 422);
        expected.put(ErrorCode.valueOf("GROUP_BY_REQUIRES_AGGREGATION"), 422);
        expected.put(ErrorCode.valueOf("STAT_REQUIRES_AGGREGATION"), 422);
        expected.put(ErrorCode.valueOf("METRIC_UNTYPED"), 422);
        expected.put(ErrorCode.valueOf("EXPORT_TOO_LARGE"), 422);
        expected.put(ErrorCode.valueOf("PACKAGE_VERSION_MISMATCH"), 409);
        expected.put(ErrorCode.valueOf("METRICS_PORT_CONFLICT"), 409);
        expected.forEach((code, status) -> assertThat(code.status()).as(code.name()).isEqualTo(status));
    }

    private static void collectPropertyNames(JsonNode node, List<String> out, java.util.Set<String> seenRefs) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(out::add);
            }
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && seenRefs.add(ref.asText()) && ref.asText().startsWith("#/")) {
                collectPropertyNames(SPEC.at(ref.asText().substring(1)), out, seenRefs);
            }
            node.elements().forEachRemaining(child -> collectPropertyNames(child, out, seenRefs));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectPropertyNames(child, out, seenRefs));
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<String> toList(java.util.Iterator<String> names) {
        List<String> result = new ArrayList<>();
        names.forEachRemaining(result::add);
        return result;
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static JsonNode readSpec() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/openapi/console.yaml");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ObjectMapper(new YAMLFactory()).readTree(candidate.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException("cannot parse " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml from "
                + Path.of("").toAbsolutePath());
    }
}
