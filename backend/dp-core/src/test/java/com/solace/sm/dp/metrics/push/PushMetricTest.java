package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1: a stored {@code solace_event} definition is re-checked by the Data Plane (second enforcer behind the schema
 * and both validators). Whatever a package author, an older package or a superuser put in {@code metric_definition.source}, nothing
 * outside the grammar becomes a subscription, a pointer or a key.
 */
class PushMetricTest {

    private static MetricDefinitionSpec spec(String type, String sourceJson) {
        return new MetricDefinitionSpec("filemesh.m", PushFixtures.COMPONENT, type, "events", "solace_event", sourceJson, null, true);
    }

    private static String source(String body) {
        return "{\"kind\":\"solace_event\"," + body + ",\"onUnavailable\":\"degrade\"}";
    }

    private static final String KEY = "\"instanceKey\":{\"configField\":\"" + PushFixtures.ADAPTER_ID_FIELD
            + "\",\"pointer\":\"/header/adapterID\"}";
    private static final String TOPIC = "\"topicPattern\":\"a/b/*/heartbeat\"";

    private static void refused(String type, String sourceJson) {
        assertThrows(IllegalArgumentException.class, () -> PushMetric.of(spec(type, sourceJson)), sourceJson);
    }

    @Test
    void theReviewedDeclarationParsesIntoAMetricThatKnowsItsTopicItsKeyAndItsExtraction() {
        PushMetric m = PushMetric.of(PushFixtures.heartbeats()).orElseThrow();

        assertEquals("filemesh.heartbeats", m.name());
        assertEquals(PushFixtures.HEARTBEAT_PATTERN, m.topic().value());
        assertEquals(PushMetric.Extraction.COUNT, m.extraction());
        assertEquals(PushFixtures.ADAPTER_ID_FIELD, m.keyConfigField());
        assertEquals("/header/adapterID", m.keyPointer());
        assertEquals(null, m.valuePointer());
        PushMetric gauge = PushMetric.of(PushFixtures.runtime()).orElseThrow();
        assertEquals(PushMetric.Extraction.VALUE, gauge.extraction());
        assertEquals("/fileStateEvent/runTimeMs", gauge.valuePointer());
    }

    @Test
    void anAbsentExtractIsAValueExtractionThatNeedsItsPointer() {
        assertTrue(PushMetric.of(spec("gauge", source(TOPIC + "," + KEY + ",\"valuePointer\":\"/a/b\""))).isPresent());
        refused("gauge", source(TOPIC + "," + KEY));
    }

    @Test
    void aCountIsACounterAndItsOptionalValuePointerIsAcceptedAndIgnored() {
        assertTrue(PushMetric.of(spec("counter", source(TOPIC + "," + KEY + ",\"extract\":\"count\""))).isPresent());
        // W3-05 (OR-106): valuePointer is optional for a count; a grammar-valid one is ignored, never read
        PushMetric withPointer = PushMetric.of(spec("counter",
                source(TOPIC + "," + KEY + ",\"extract\":\"count\",\"valuePointer\":\"/a\""))).orElseThrow();
        assertEquals(PushMetric.Extraction.COUNT, withPointer.extraction());
        assertEquals(null, withPointer.valuePointer());
        refused("counter", source(TOPIC + "," + KEY + ",\"extract\":\"count\",\"valuePointer\":\"/a/../b\""));
        refused("gauge", source(TOPIC + "," + KEY + ",\"extract\":\"count\""));
    }

    @Test
    void aTopicOrAKeyOrAPointerOutsideTheGrammarIsRefused() {
        refused("counter", source("\"topicPattern\":\"a/b/>\"," + KEY + ",\"extract\":\"count\""));
        refused("counter", source("\"topicPattern\":\"a/{instanceKey}/x\"," + KEY + ",\"extract\":\"count\""));
        refused("counter", source(TOPIC + ",\"instanceKey\":{\"configField\":\"f\",\"pointer\":\"/a/../b\"},\"extract\":\"count\""));
        refused("counter", source(TOPIC + ",\"instanceKey\":{\"configField\":\"f g\",\"pointer\":\"/a\"},\"extract\":\"count\""));
        refused("counter", source(TOPIC + ",\"instanceKey\":{\"pointer\":\"/a\"},\"extract\":\"count\""));
        refused("counter", source(TOPIC + ",\"extract\":\"count\""));
        refused("gauge", source(TOPIC + "," + KEY + ",\"valuePointer\":\"/a/./b\""));
        refused("counter", source(TOPIC + "," + KEY + ",\"extract\":\"rate\""));
        refused("counter", source(TOPIC + "," + KEY + ",\"extract\":\"count\",\"eventFilter\":{\"pointer\":\"/a/../b\",\"equals\":\"x\"}"));
        refused("counter", source(TOPIC + "," + KEY + ",\"extract\":\"count\",\"eventFilter\":{\"pointer\":\"/a\",\"equals\":\"x y\"}"));
        refused("counter", "[]");
        refused("counter", "not json");
    }

    @Test
    void aSourceOfAnotherKindOrAHistogramIsNotAPushMetricAndIsNotAnError() {
        assertEquals(Optional.empty(), PushMetric.of(new MetricDefinitionSpec("m", "c", "gauge", "u", "http_json",
                "{\"kind\":\"http_json\"}", 30, true)));
        assertEquals(Optional.empty(), PushMetric.of(spec("histogram", source(TOPIC + "," + KEY + ",\"extract\":\"count\""))));
    }

    @Test
    void theRefusalNamesNoPartOfTheSource() {
        String planted = source("\"topicPattern\":\"SENTINEL/>\"," + KEY + ",\"extract\":\"count\"");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PushMetric.of(spec("counter", planted)));

        assertFalse(String.valueOf(e.getMessage()).contains("SENTINEL"), e.getMessage());
    }
}
