package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryUnavailableException;
import com.solace.sm.dp.api.metrics.query.ObservedMetric;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Plan 011 P11-7.2: rows 54-57 and 61 over the real pipeline with the ports faked: the entity types, the instances behind
 * them (two verbs), the catalog composed from two planes (concurrently, each degrading on its own), the empty label list
 * and the contracted {@code 501}.
 */
class MetricsCatalogRoutesTest extends MetricsRoutesRig {

    private JsonNode catalog(String path) throws Exception {
        return json(getAs("token", path));
    }

    private static Map<String, JsonNode> byName(JsonNode catalog) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        catalog.forEach(entry -> out.put(entry.get("name").asText(), entry));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ 54 and 55

    @Test
    void entityTypesAreOnlyThoseOfInstancesTheCallerReaches() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of(instanceA));
        JsonNode types = catalog("/entity-types");
        assertEquals(1, types.size());
        assertEquals("cache-engine", types.get(0).get("id").asText());
        assertEquals("cache-engine", types.get(0).get("label").asText());
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of());
        assertEquals(0, catalog("/entity-types").size(), "reach nothing, see no type; never a 403");
        authorization.outcome = Outcome.DENY;
        assertEquals(403, getAs("token", "/entity-types").getResponse().getStatus());
    }

    @Test
    void aCallerWithMetricsQueryButNotInstanceViewSeesNoInstances() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of(instanceA, instanceB));
        authorization.authorizedIdsByPermission.put(Permission.INSTANCE_VIEW, Set.of());
        JsonNode none = catalog("/entity-types/cache-engine/instances");
        assertEquals(0, none.get("total").asInt(), "row 55 is metrics.query AND instance.view");
        assertEquals(0, none.get("items").size());
        authorization.authorizedIdsByPermission.put(Permission.INSTANCE_VIEW, Set.of(instanceB));
        JsonNode one = catalog("/entity-types/cache-engine/instances");
        assertEquals(1, one.get("total").asInt(), "the intersection: only B holds both");
        assertEquals(instanceB.toString(), one.at("/items/0/id").asText());
        assertEquals("mon-b", one.at("/items/0/label").asText());
        assertEquals(1, one.at("/items/0/replicaCount").asInt());
    }

    @Test
    void theInstanceSearchIsACaseInsensitiveSubstringAndAnUnknownTypeIsEmptyNever404() throws Exception {
        authorization.authorizedIds.add(instanceA);
        authorization.authorizedIds.add(instanceB);
        assertEquals(2, catalog("/entity-types/cache-engine/instances").get("total").asInt());
        assertEquals(1, catalog("/entity-types/cache-engine/instances?search=MON-A").get("total").asInt());
        MvcResult unknown = getAs("token", "/entity-types/nothing/instances");
        assertEquals(200, unknown.getResponse().getStatus());
        assertEquals(0, json(unknown).get("total").asInt());
        assertEquals(400, getAs("token", "/entity-types/cache-engine/instances?search=" + "x".repeat(129)).getResponse()
                .getStatus());
    }

    @Test
    void anInstanceTheDataPlaneDoesNotReportActiveIsNeverListed() throws Exception {
        UUID pending = UUID.randomUUID();
        putInstance(pending, "mon-pending", InstanceLifecycle.PENDING, AcquisitionMode.VIA_SSH);
        authorization.authorizedIds.add(pending);
        authorization.authorizedIds.add(instanceA);
        JsonNode items = catalog("/entity-types/cache-engine/instances");
        assertEquals(1, items.get("total").asInt());
        assertEquals(instanceA.toString(), items.at("/items/0/id").asText());
    }

    // ------------------------------------------------------------------------------------------------- 56 catalog

    @Test
    void theCatalogStatesEachMetricByWhatItsPackageDeclares() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of(instanceA));
        Map<String, JsonNode> entries = byName(catalog("/metrics/catalog?entityType=cache-engine"));
        assertEquals(List.of("cache.size", "cache.hit_count", "cache.latency", "cache.pushed", "cache.portless"),
                List.copyOf(entries.keySet()), "declared order, one entry per name");
        assertEquals("plottable", entries.get("cache.size").get("state").asText());
        assertEquals("package-baseline", entries.get("cache.size").get("layer").asText());
        assertEquals("unsupported", entries.get("cache.latency").get("state").asText());
        assertEquals("histogram", entries.get("cache.latency").get("unsupportedReason").asText());
        assertEquals("unsupported", entries.get("cache.portless").get("state").asText());
        assertEquals("no-port-declared", entries.get("cache.portless").get("unsupportedReason").asText());
        assertFalse(entries.get("cache.size").has("unsupportedReason"));
        assertEquals("durable", entries.get("cache.size").get("durability").asText());
        assertEquals("best-effort", entries.get("cache.pushed").get("durability").asText(), "a broker-pushed source");
        assertEquals("degrade", entries.get("cache.size").get("onUnavailable").asText());
        assertEquals(0, entries.get("cache.size").get("labelKeys").size());
        assertEquals("docker", entries.get("cache.hit_count").get("restartDetection").asText(),
                "A is declared via-ssh: the docker inspect is its signal");
        assertFalse(entries.get("cache.size").has("restartDetection"), "a counter's property");
    }

    @Test
    void theCatalogReportsTheMoreCautiousRestartDetectionWhenInstancesDisagree() throws Exception {
        authorization.authorizedIds.add(instanceA);
        authorization.authorizedIds.add(instanceB);   // B is direct-http with no restart signal: inferred
        assertEquals("inferred", byName(catalog("/metrics/catalog")).get("cache.hit_count").get("restartDetection").asText());
    }

    @Test
    void theCatalogJoinsWhatIsObservedAndSaysWhenNothingWas() throws Exception {
        authorization.authorizedIds.add(instanceA);
        Instant seen = Instant.parse("2026-10-06T00:30:00Z");
        query.observed = List.of(new ObservedMetric("cache.size", RangeResult.MetricType.GAUGE, "count", seen,
                List.of(new CollectorHealthView.ReasonCount(CollectorReason.SCRAPE_FAILED, 3))));
        Map<String, JsonNode> entries = byName(catalog("/metrics/catalog"));
        JsonNode size = entries.get("cache.size");
        assertEquals(seen.toEpochMilli(), size.get("lastSeen").asLong(), "epoch milliseconds");
        assertEquals("placements-serving-this-metric", size.at("/collector/scope").asText());
        assertEquals("scrape_failed", size.at("/collector/reasons/0/reason").asText());
        assertEquals("live", size.at("/observedAvailability/state").asText());
        JsonNode never = entries.get("cache.hit_count");
        assertFalse(never.has("lastSeen"));
        assertFalse(never.has("collector"));
        assertEquals("live", never.at("/observedAvailability/state").asText(), "never observed is live and empty");
        assertEquals(Set.of(instanceA), query.observedFor.get(0), "observed is asked of the instances the caller reaches");
    }

    @Test
    void aFailedPlaneIsDegradedNeverAnEmptyCatalog() throws Exception {
        // W2-06: the data plane down means every entry says so; never a list that looks like "nothing observed".
        authorization.authorizedIds.add(instanceA);
        query.observedFailure = new MetricsQueryUnavailableException(MetricsQueryUnavailableException.Reason.BUSY);
        JsonNode catalog = catalog("/metrics/catalog");
        assertEquals(5, catalog.size(), "the declared half still answers");
        for (JsonNode entry : catalog) {
            assertEquals("unavailable", entry.at("/observedAvailability/state").asText(), entry.get("name").asText());
            assertFalse(entry.at("/observedAvailability/reason").asText().isBlank());
            assertFalse(entry.has("lastSeen"));
            assertFalse(entry.has("collector"));
        }
        // The control plane's half failing is a 503, never an empty array.
        query.observedFailure = null;
        this.catalog.documentFailure = new IllegalStateException("catalog database down");
        MvcResult cpDown = getAs("token", "/metrics/catalog");
        assertEquals(503, cpDown.getResponse().getStatus());
        assertEquals("SERVICE_UNAVAILABLE", json(cpDown).get("code").asText());
    }

    @Test
    void theCatalogComposesBothPlanesConcurrently() throws Exception {
        authorization.authorizedIds.add(instanceA);
        this.catalog.documentDelayMillis = 300;
        query.observedDelayMillis = 300;
        long started = System.nanoTime();
        MvcResult result = getAs("token", "/metrics/catalog?entityType=cache-engine");
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(millis >= 300, "each read takes 300 ms: " + millis);
        assertTrue(millis < 560, "two 300 ms reads in parallel are about 300 ms, never 600 ms: " + millis);
    }

    @Test
    void theCatalogReadsNothingAboutInstancesOutOfReach() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of());
        assertEquals(0, catalog("/metrics/catalog").size());
        assertEquals(List.of(), query.observedFor, "nothing reachable, nothing asked of the data plane");
        authorization.outcome = Outcome.DENY;
        assertEquals(403, getAs("token", "/metrics/catalog").getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------------ 57 labels and 61

    @Test
    void theLabelAllowlistIsEmptyInV1() throws Exception {
        MvcResult result = getAs("token", "/labels/region/values?entityType=cache-engine");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("[]", result.getResponse().getContentAsString());
        authorization.outcome = Outcome.DENY;
        assertEquals(403, getAs("token", "/labels/region/values").getResponse().getStatus());
    }

    @Test
    void metricDeclareIs501() throws Exception {
        MvcResult result = postAs("token", "/metrics/catalog/cache.size/type", Map.of("metricType", "gauge", "unit", "count"));
        assertEquals(501, result.getResponse().getStatus());
        JsonNode problem = json(result);
        assertEquals("NOT_IMPLEMENTED", problem.get("code").asText());
        assertEquals("metric-declare-deferred", problem.get("reason").asText());
        assertEquals(Permission.METRIC_DECLARE, authorization.permissions.get(0), "the verb is metric.declare");
        assertEquals(com.solace.sm.cp.api.authz.ResourceKind.ORGANIZATION, authorization.resources.get(0).kind());
        assertEquals(session.organizationId(), authorization.resources.get(0).id(), "at the caller's own organization");
        // Never 403 for a caller who holds the verb; a caller who does not is refused as for any verb.
        authorization.outcome = Outcome.DENY;
        assertEquals(403, postAs("token", "/metrics/catalog/cache.size/type", Map.of("metricType", "gauge", "unit", "count"))
                .getResponse().getStatus());
        authorization.outcome = Outcome.ALLOW;
        assertEquals(400, postAs("token", "/metrics/catalog/cache.size/type", Map.of("metricType", "histogram", "unit", "x"))
                .getResponse().getStatus());
        assertEquals(400, postAs("token", "/metrics/catalog/Bad%20Name/type", Map.of("metricType", "gauge", "unit", "x"))
                .getResponse().getStatus());
        assertNull(json(getAs("token", "/capabilities")).get("reason"));
    }
}
