package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.gateway.http.metrics.DeclareMetricTypeRequest;
import com.solace.sm.gateway.http.metrics.HiddenRequest;
import com.solace.sm.gateway.http.metrics.MetricQueryRequest;
import com.solace.sm.gateway.http.metrics.MetricsWire;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-7.2, the BE-D10 lock for the monitoring wire: each response record has exactly the contract's properties
 * and exactly its required set, in both directions, so a field added to {@code console.yaml} and not here (or the reverse)
 * is a red build and not a client that meets a surprise. Each request record also refuses any other key, as the contract's
 * {@code additionalProperties: false} says.
 *
 * <p>No byte figure may be a component of the capabilities' collector or disk: that is OQ-16's scope rule (row 53 is
 * readable by every principal of every organization), so it is pinned on its own, apart from the contract's own list.
 */
class MetricsSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();

    private static JsonNode schema(String pointer) {
        JsonNode node = SPEC.at("/components/schemas" + pointer);
        assertFalse(node.isMissingNode(), pointer);
        return node;
    }

    private static Map<String, Class<?>> responses() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("/Capabilities", MetricsWire.Capabilities.class);
        m.put("/Capabilities/properties/tiers/items", MetricsWire.Tier.class);
        m.put("/Capabilities/properties/features", MetricsWire.Features.class);
        m.put("/Capabilities/properties/collector", MetricsWire.Collector.class);
        m.put("/Capabilities/properties/collector/properties/reasons/items", MetricsWire.CollectorReasonCount.class);
        m.put("/Capabilities/properties/collector/properties/disk", MetricsWire.Disk.class);
        m.put("/EntityType", MetricsWire.EntityType.class);
        m.put("/MetricInstance", MetricsWire.MetricInstance.class);
        m.put("/MetricInstanceList", MetricsWire.MetricInstanceList.class);
        m.put("/MetricCatalogEntry", MetricsWire.MetricCatalogEntry.class);
        m.put("/MetricCatalogEntry/properties/collector", MetricsWire.EntryCollector.class);
        m.put("/MetricQueryResult", MetricsWire.QueryResult.class);
        m.put("/MetricQueryResult/properties/metric", MetricsWire.ResultMetric.class);
        m.put("/MetricQueryResult/properties/render", MetricsWire.Render.class);
        m.put("/MetricQueryResult/properties/resolution", MetricsWire.Resolution.class);
        m.put("/MetricQueryResult/properties/coverage", MetricsWire.Coverage.class);
        m.put("/MetricQueryResult/properties/series/items", MetricsWire.ResultSeries.class);
        m.put("/MetricQueryResult/properties/series/items/properties/seriesRef", MetricsWire.SeriesRef.class);
        m.put("/MetricQueryResult/properties/series/items/properties/replicaComposition", MetricsWire.ReplicaComposition.class);
        m.put("/MetricQueryResult/properties/series/items/properties/gaps/items", MetricsWire.Gap.class);
        m.put("/MetricQueryResult/properties/partial", MetricsWire.Partial.class);
        m.put("/MetricQueryResult/properties/partial/properties/unavailable/items", MetricsWire.Unavailable.class);
        return m;
    }

    @Test
    void everyResponseRecordHasExactlyTheContractsPropertiesAndRequiredSet() {
        responses().forEach((pointer, type) -> {
            JsonNode schema = schema(pointer);
            assertEquals(names(schema.get("properties")), components(type), pointer + " properties");
            assertEquals(textSet(schema.get("required")), required(type), pointer + " required");
        });
    }

    @Test
    void everyRequestRecordHasExactlyTheContractsPropertiesRequiredSetAndForbidsTheRest() {
        Map<String, Class<?>> requests = new LinkedHashMap<>();
        requests.put("/MetricQueryRequest", MetricQueryRequest.class);
        requests.put("/MetricSelector", MetricQueryRequest.Selector.class);
        requests.put("/MetricAggregation", MetricQueryRequest.Aggregation.class);
        requests.put("/MetricQueryRequest/properties/range", MetricQueryRequest.Range.class);
        requests.forEach((pointer, type) -> {
            JsonNode schema = schema(pointer);
            assertEquals(names(schema.get("properties")), components(type), pointer + " properties");
            Set<String> contractRequired = textSet(schema.get("required"));
            assertEquals(contractRequired, requiredInBody(type, contractRequired), pointer + " required");
            assertFalse(schema.path("additionalProperties").asBoolean(true), pointer + " additionalProperties: false");
        });
        // The two inline bodies.
        JsonNode declare = SPEC.at("/paths/~1console~1v1~1metrics~1catalog~1{name}~1type/post/requestBody/content/application~1json/schema");
        assertEquals(names(declare.get("properties")), components(DeclareMetricTypeRequest.class));
        assertEquals(textSet(declare.get("required")), components(DeclareMetricTypeRequest.class));
        JsonNode hidden = SPEC.at("/paths/~1console~1v1~1dashboards~1{dashboardId}~1widgets~1{widgetId}/patch/requestBody/content/application~1json/schema");
        assertEquals(names(hidden.get("properties")), components(HiddenRequest.class));
        assertEquals(textSet(hidden.get("required")), components(HiddenRequest.class));
    }

    @Test
    void noByteFigureIsEverAComponentOfTheCapabilitiesCollectorOrDisk() {
        for (Class<?> type : new Class<?>[] {MetricsWire.Capabilities.class, MetricsWire.Collector.class,
                MetricsWire.Disk.class, MetricsWire.CollectorReasonCount.class, MetricsWire.Features.class,
                MetricsWire.Tier.class}) {
            for (String component : components(type)) {
                String lower = component.toLowerCase();
                assertFalse(lower.contains("byte") || lower.contains("size") || lower.contains("budget")
                        || lower.contains("database") && !lower.equals("unfrozenclosedpartitions"),
                        type.getSimpleName() + "." + component + " is install-wide data, which row 53 may not carry");
            }
        }
        assertEquals(Set.of("state"), components(MetricsWire.Disk.class), "OQ-16 (a): the disk is a state and nothing else");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Set<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> required(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> Arrays.stream(c.getDeclaredAnnotations())
                        .noneMatch(a -> a.annotationType().getSimpleName().equals("WireOptional")))
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * A request record's compact constructor refuses a missing required value; what the contract calls optional is the
     * components {@code groupBy} only. So its "required" set is every component except the optional ones.
     */
    private static Set<String> requiredInBody(Class<?> type, Set<String> contractRequired) {
        Set<String> all = components(type);
        Set<String> optional = new TreeSet<>(all);
        optional.removeAll(contractRequired);
        Set<String> out = new TreeSet<>(all);
        out.removeAll(optional);
        return out;
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> out = new TreeSet<>();
        if (array != null) {
            array.forEach(node -> out.add(node.asText()));
        }
        return out;
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
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml");
    }
}
