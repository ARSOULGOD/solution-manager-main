package com.solace.sm.dp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 015 W4 lane D: the count names are closed, and {@link AgentTelemetryCounts#NAMES} lists every one a meter binding needs. */
class AgentTelemetryCountsTest {

    @Test
    void namesListsEveryPublicNameConstantExactlyOnce() throws Exception {
        List<String> declared = new ArrayList<>();
        for (Field f : AgentTelemetryCounts.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        assertEquals(new HashSet<>(declared), new HashSet<>(AgentTelemetryCounts.NAMES), "a count with no entry in NAMES has no meter");
        assertEquals(declared.size(), AgentTelemetryCounts.NAMES.size(), "no name listed twice");
        assertFalse(AgentTelemetryCounts.NAMES.isEmpty());
    }

    @Test
    void aCountReadsWhatWasAddedAndIgnoresNonPositiveAdditions() {
        AgentTelemetryCounts counts = new AgentTelemetryCounts();
        counts.add(AgentTelemetryCounts.REJECTED, 2);
        counts.add(AgentTelemetryCounts.REJECTED, 0);
        counts.add(AgentTelemetryCounts.REJECTED, -5);
        assertEquals(2, counts.get(AgentTelemetryCounts.REJECTED));
        assertEquals(0, counts.get(AgentTelemetryCounts.NOT_ACKED));
    }
}
