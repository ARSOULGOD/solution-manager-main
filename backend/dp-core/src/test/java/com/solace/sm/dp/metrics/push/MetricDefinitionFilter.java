package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;

/** Test-only builders of the less common declarations (a filter, a second counter on one pattern). */
final class MetricDefinitionFilter {

    /** A numeric extraction that counts only events whose {@code /header/eventType} equals {@code eventType}. */
    MetricDefinitionSpec runtimeFilteredTo(String eventType) {
        return new MetricDefinitionSpec("filemesh.file_run_time_ms", PushFixtures.COMPONENT, "gauge", "ms", "solace_event",
                PushFixtures.source(PushFixtures.FCOMPLETE_PATTERN, "value", "/fileStateEvent/runTimeMs",
                        "{\"pointer\":\"/header/eventType\",\"equals\":\"" + eventType + "\"}", PushFixtures.ADAPTER_ID_FIELD),
                null, true);
    }

    MetricDefinitionSpec secondCounterOn(String pattern) {
        return new MetricDefinitionSpec("filemesh.beats_again", PushFixtures.COMPONENT, "counter", "events", "solace_event",
                PushFixtures.source(pattern, "count", null, null, PushFixtures.ADAPTER_ID_FIELD), null, true);
    }
}
