package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.1: what the gateway resolves from the control plane's package at declare (M-Q5, M-Q14): a definition
 * for every declared metric, grammar-rechecked; a restart signal per component; and an endpoint row for every distinct
 * port the collector will call, each defaulting to itself. The data plane stores what this returns and reads no package.
 */
class MetricsDeclarationBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIGEST = "sha256:" + "c".repeat(64);

    private static Map<String, Object> http(int port, Integer interval) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "http_json");
        source.put("path", "/api/cache/stats");
        source.put("port", port);
        source.put("pointer", "/hitRate");
        if (interval != null) {
            source.put("intervalSeconds", interval);
        }
        source.put("onUnavailable", "degrade");
        return source;
    }

    private static PackageModel.PackageMetric metric(String name, Map<String, Object> source, Boolean defaultChart) {
        return new PackageModel.PackageMetric(name, "gauge", "ratio", "package-baseline", null, source, defaultChart);
    }

    private static PackageModel.PackageMetric metric(String name, int port) {
        return metric(name, http(port, null), null);
    }

    private static Map<String, Object> signal(int port) {
        return Map.of("kind", "http_json", "path", "/actuator/info", "port", port, "pointer", "/x",
                "onUnavailable", "degrade");
    }

    private static PackageModel.PackageComponent component(String type, List<Object> version,
            List<PackageModel.PackageMetric> metrics, Map<String, Object> restartSignal) {
        return new PackageModel.PackageComponent(type, "exactly-one", version, "none", type, null, null, List.of(), null,
                metrics, null, null, restartSignal);
    }

    private static PackageModel.SolutionPackage pkg(PackageModel.PackageComponent... components) {
        return new PackageModel.SolutionPackage("cache", List.of(1, 2), ">=1.0", null, List.of(components));
    }

    private static MetricsDeclaration build(PackageModel.SolutionPackage pkg, Map<String, Integer> ports,
            String... active) {
        return MetricsDeclarationBuilder.build(DIGEST, pkg, 1L, ports, List.of(active));
    }

    // ---- definitions -------------------------------------------------------------------------------------

    @Test
    void everyDeclaredMetricBecomesADefinitionCarryingItsSourceAndTheDigestPassesThroughUnchanged() throws Exception {
        PackageModel.SolutionPackage p = pkg(component("cache-engine", null,
                List.of(metric("cache.hit_rate", http(8081, 30), false), metric("cache.size", http(8081, null), null)),
                null));

        MetricsDeclaration d = build(p, null, "cache.size");

        assertEquals(DIGEST, d.packageDigest(), "the CP's stored digest, never recomputed");
        assertEquals(2, d.definitions().size());
        MetricDefinitionSpec rate = d.definitions().get(0);
        assertEquals("cache.hit_rate", rate.metricName());
        assertEquals("cache-engine", rate.componentType());
        assertEquals("gauge", rate.metricType());
        assertEquals("ratio", rate.unit());
        assertEquals("http_json", rate.sourceKind());
        assertEquals(30, rate.intervalSeconds());
        assertFalse(rate.defaultChart(), "defaultChart:false opts the metric out of the derived layer");
        JsonNode source = JSON.readTree(rate.sourceJson());
        assertEquals("/api/cache/stats", source.get("path").asText());
        assertEquals(8081, source.get("port").asInt());
        MetricDefinitionSpec size = d.definitions().get(1);
        assertNull(size.intervalSeconds(), "an absent interval is stored absent (the data plane default applies)");
        assertTrue(size.defaultChart(), "an absent defaultChart means true (OQ-7 (b))");
        assertEquals(List.of("cache.size"), d.activeMetricNames());
    }

    @Test
    void aPackageWithNoMetricsDeclaresNothingButItsDigestAndAnEmptyList() {
        MetricsDeclaration d = build(pkg(component("widget", null, null, null)), null);

        assertEquals(DIGEST, d.packageDigest());
        assertTrue(d.definitions().isEmpty());
        assertTrue(d.metricsPorts().isEmpty());
        assertFalse(d.isNone(), "a package with no metrics is a recorded declaration, not 'none'");
    }

    @Test
    void aSourceThatIsNotCollectedInV1IsStillADefinitionButNamesNoEndpoint() {
        Map<String, Object> prom = Map.of("kind", "prometheus", "path", "/metrics", "port", 9100, "onUnavailable", "degrade");
        MetricsDeclaration d = build(pkg(component("cache-engine", null, List.of(metric("cache.prom", prom, null)), null)),
                null);

        assertEquals("prometheus", d.definitions().get(0).sourceKind());
        assertTrue(d.metricsPorts().isEmpty(), "only http_json sources are called by the collector");
    }

    @Test
    void aSolaceEventSourceReachesTheDataPlaneWholeAndNamesNoEndpoint() throws Exception {
        // plan 011 P11-5.1 (OR-106, OR-107): the push path's definition is stored exactly as the package declares it, because
        // the data plane re-parses it ({@code PushMetric.of}) and reads no package: the key, the pointer and the extraction
        // must survive the gateway, and a push metric has no interval and calls no port.
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("configField", "file-events.source.general.adapter_id");
        key.put("pointer", "/header/adapterID");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "solace_event");
        source.put("topicPattern", "*/*/*/*/*/*/*/*/heartbeat");
        source.put("instanceKey", key);
        source.put("extract", "count");
        source.put("onUnavailable", "degrade");
        PackageModel.PackageMetric heartbeats = new PackageModel.PackageMetric("filemesh.heartbeats", "counter", "events",
                "package-baseline", null, source, null);

        MetricsDeclaration d = build(pkg(component("COMP_SOURCE_FTP@FILEEVENTS", null, List.of(heartbeats), null)), null,
                "filemesh.heartbeats");

        MetricDefinitionSpec spec = d.definitions().get(0);
        assertEquals("solace_event", spec.sourceKind());
        assertNull(spec.intervalSeconds(), "a pushed metric is not polled");
        JsonNode stored = JSON.readTree(spec.sourceJson());
        assertEquals("*/*/*/*/*/*/*/*/heartbeat", stored.get("topicPattern").asText());
        assertEquals("file-events.source.general.adapter_id", stored.at("/instanceKey/configField").asText());
        assertEquals("/header/adapterID", stored.at("/instanceKey/pointer").asText());
        assertEquals("count", stored.get("extract").asText());
        assertTrue(d.metricsPorts().isEmpty(), "no port is called for a push source");
    }

    // ---- endpoints (M-Q14) ---------------------------------------------------------------------------------

    @Test
    void anAbsentMetricsPortDefaultsToTheSourcePort() {
        MetricsDeclaration d = build(pkg(component("cache-engine", null, List.of(metric("a", 8081), metric("b", 8082)),
                null)), null);

        assertEquals(Map.of(8081, 8081, 8082, 8082), d.metricsPorts());
    }

    @Test
    void aMappedPortUsesTheHostPortAndTheRestDefault() {
        MetricsDeclaration d = build(pkg(component("cache-engine", null, List.of(metric("a", 8081), metric("b", 8082)),
                null)), Map.of("8081", 28081));

        assertEquals(Map.of(8081, 28081, 8082, 8082), d.metricsPorts());
    }

    @Test
    void aRestartSignalPortWithNoMetricSourceStillGetsAnEndpoint() {
        // 9091 is declared only as the restart signal's port: without a row, a direct-mode fetch of the signal has no host
        // port and a component declared DECLARED would always read as a failed signal
        MetricsDeclaration d = build(pkg(component("cache-engine", null, List.of(metric("a", 8081)), signal(9091))), null);

        assertEquals(Map.of(8081, 8081, 9091, 9091), d.metricsPorts());
        assertEquals(List.of("cache-engine"), d.restartSignals().stream().map(RestartSignalSpec::componentType).toList());
    }

    @Test
    void theUnionOfSourcePortsAndSignalPortsIsEachRowOnceAndTheSignalMayBeMapped() {
        MetricsDeclaration d = build(pkg(component("cache-engine", null, List.of(metric("a", 8081), metric("b", 8081)),
                signal(8081))), Map.of("8081", 28081));

        assertEquals(Map.of(8081, 28081), d.metricsPorts(), "one source port is one row, whoever names it");
    }

    @Test
    void aMetricsPortKeyNoSourceUsesIsRefusedNotSilentlyKept() {
        DeclareValidationException e = assertThrows(DeclareValidationException.class, () -> build(
                pkg(component("cache-engine", null, List.of(metric("a", 8081)), null)), Map.of("8082", 28082)));

        assertEquals("placement.metricsPorts", e.field(), "a mistyped source port would otherwise fall back to the default");
    }

    // ---- the grammar recheck: a package stored before the grammar existed is refused here, not by a CHECK ----------

    @Test
    void aMetricThatBreaksTheGrammarIsRefusedAs422NamingTheVersionField() {
        for (PackageModel.PackageMetric bad : List.of(
                new PackageModel.PackageMetric("Cache.Upper", "gauge", "ratio", null, null, http(8081, null), null),
                new PackageModel.PackageMetric("a".repeat(129), "gauge", "ratio", null, null, http(8081, null), null),
                new PackageModel.PackageMetric("cache.x", "summary", "ratio", null, null, http(8081, null), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "", null, null, http(8081, null), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "u".repeat(65), null, null, http(8081, null), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, http(8081, 9), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, http(8081, 3601), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, http(0, null), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, http(65536, null), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, Map.of("kind", "carrier-pigeon"), null),
                new PackageModel.PackageMetric("cache.x", "gauge", "ratio", null, null, null, null))) {
            DeclareValidationException e = assertThrows(DeclareValidationException.class,
                    () -> build(pkg(component("cache-engine", null, List.of(bad), null)), null),
                    "name=" + (bad.name().length() > 20 ? "long" : bad.name()));
            assertEquals("declaredPackageVersion", e.field());
        }
    }

    @Test
    void aRestartSignalThatIsNotAnHttpJsonReadIsRefused() {
        assertThrows(DeclareValidationException.class, () -> build(pkg(component("cache-engine", null,
                List.of(metric("a", 8081)), Map.of("kind", "exec", "path", "/x", "onUnavailable", "degrade"))), null));
    }

    @Test
    void theSameMetricNameInTwoComponentsIsRefused() {
        assertThrows(DeclareValidationException.class, () -> build(pkg(
                component("one", null, List.of(metric("dup", 8081)), null),
                component("two", null, List.of(metric("dup", 8082)), null)), null));
    }

    @Test
    void aComponentOutsideTheDeclaredVersionContributesNoDefinitionNoSignalAndNoPort() {
        PackageModel.SolutionPackage p = pkg(
                component("cache-engine", null, List.of(metric("a", 8081)), null),
                component("v2-only", List.of(2), List.of(metric("b", 8082)), signal(9092)));

        MetricsDeclaration v1 = MetricsDeclarationBuilder.build(DIGEST, p, 1L, null, List.of());
        MetricsDeclaration v2 = MetricsDeclarationBuilder.build(DIGEST, p, 2L, null, List.of());

        assertEquals(List.of("a"), v1.definitions().stream().map(MetricDefinitionSpec::metricName).toList());
        assertEquals(Map.of(8081, 8081), v1.metricsPorts());
        assertTrue(v1.restartSignals().isEmpty());
        assertEquals(List.of("a", "b"), v2.definitions().stream().map(MetricDefinitionSpec::metricName).toList());
        assertEquals(Map.of(8081, 8081, 8082, 8082, 9092, 9092), v2.metricsPorts());
    }


    // ---- the generation-0 selection --------------------------------------------------------------------------

    private static PackageModel.PackageComponent withCardinality(String type, String cardinality, List<Object> version) {
        return new PackageModel.PackageComponent(type, cardinality, version, "none", type, null, null, List.of(), null,
                List.of(), null, null, null);
    }

    @Test
    void theDeclareTimeSelectionIsOneInstanceOfEachExactlyOneComponentThatAppliesToTheVersion() {
        PackageModel.SolutionPackage p = pkg(
                withCardinality("always", "exactly-one", null),
                withCardinality("a-choice", "one-of-many", null),
                withCardinality("optional", "zero-or-more", null),
                withCardinality("v2-only", "exactly-one", List.of(2)));

        var v1 = MetricsDeclarationBuilder.declareTimeSelection(p, 1L);
        var v2 = MetricsDeclarationBuilder.declareTimeSelection(p, 2L);

        assertEquals(List.of("always"), v1.instances().stream().map(i -> i.type()).toList(),
                "a choice the operator has not made contributes nothing until a stage selects it");
        assertEquals(List.of("always", "v2-only"), v2.instances().stream().map(i -> i.type()).toList());
        assertEquals("always", v1.instances().get(0).instanceKey());
    }

    @Test
    void aDigestThatIsNotTheStoredShapeIsAnInternalFaultNotAUserError() {
        assertThrows(IllegalStateException.class, () -> MetricsDeclarationBuilder.build("md5:abc",
                pkg(component("cache-engine", null, List.of(), null)), 1L, null, List.of()));
    }
}
