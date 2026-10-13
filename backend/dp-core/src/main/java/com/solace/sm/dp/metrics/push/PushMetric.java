package com.solace.sm.dp.metrics.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One {@code solace_event} metric a package version declares (plan 011 P11-5.1; OR-106, OR-107), parsed from the grammar-checked
 * source the Data Plane stored at declare. The schema and both validators are the first enforcers; this is the second, so a
 * definition planted past them (a superuser, an older package) still cannot widen a subscription or read a pointer outside the
 * grammar.
 *
 * <p>A histogram is never collected, so it is not a push metric (the ingest path ignores one too). {@code extract=count} needs
 * a counter, and a {@code valuePointer} it carries is ignored (OR-106: optional for it); {@code extract=value} (the default) needs one.
 *
 * @param keyConfigField the field of the metric's component whose configured value identifies the instance in its events
 * @param keyPointer     where the event body carries that value
 */
public record PushMetric(String name, String componentType, String metricType, TopicPattern topic, EventFilter filter,
        Extraction extraction, String valuePointer, String keyConfigField, String keyPointer) {

    /** The events a metric reads: the value at {@code pointer}, read as text, must equal {@code equals}. */
    public record EventFilter(String pointer, String equals) {
    }

    public enum Extraction {
        /** The finite number at {@code valuePointer}. */
        VALUE,
        /** A counter of the events that pass the filter, cumulative in the Data Plane (OR-106). */
        COUNT
    }

    /** The pointer grammar of the schema: one to eight segments of {@code [A-Za-z0-9_.-]{1,64}}, no {@code .} or {@code ..} segment. */
    static final Pattern POINTER = Pattern.compile("^(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_.-]{1,64}){1,8}$");
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z0-9_.-]{1,256}$");
    private static final Pattern EQUALS = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @return empty for a definition that is not a collected push metric (another source kind, a histogram)
     * @throws IllegalArgumentException a {@code solace_event} source outside the grammar: a fixed sentence, no part of the source
     */
    public static Optional<PushMetric> of(MetricDefinitionSpec spec) {
        if (!"solace_event".equals(spec.sourceKind())) {
            return Optional.empty();
        }
        if (!"counter".equals(spec.metricType()) && !"gauge".equals(spec.metricType())) {
            return Optional.empty();
        }
        JsonNode source;
        try {
            source = JSON.readTree(spec.sourceJson());
        } catch (IOException e) {
            throw new IllegalArgumentException("a push metric's source is not JSON");
        }
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("a push metric's source is not an object");
        }
        TopicPattern topic = TopicPattern.of(text(source, "topicPattern"));
        JsonNode key = source.path("instanceKey");
        String configField = text(key, "configField");
        String keyPointer = text(key, "pointer");
        if (!FIELD.matcher(configField).matches() || !POINTER.matcher(keyPointer).matches()) {
            throw new IllegalArgumentException("a push metric's instanceKey is outside the grammar");
        }
        Extraction extraction = switch (source.path("extract").asText("value")) {
            case "value" -> Extraction.VALUE;
            case "count" -> Extraction.COUNT;
            default -> throw new IllegalArgumentException("a push metric's extraction is value or count");
        };
        String valuePointer = source.hasNonNull("valuePointer") ? source.get("valuePointer").asText() : null;
        if (extraction == Extraction.VALUE) {
            if (valuePointer == null || !POINTER.matcher(valuePointer).matches()) {
                throw new IllegalArgumentException("a value extraction names a valuePointer inside the grammar");
            }
        } else {
            if (!"counter".equals(spec.metricType())) {
                throw new IllegalArgumentException("a count extraction is a counter");
            }
            // W3-05 (OR-106): valuePointer is OPTIONAL for a count. One inside the grammar is accepted and IGNORED (never read);
            // one outside it is refused, exactly as the schema refuses it.
            if (valuePointer != null && !POINTER.matcher(valuePointer).matches()) {
                throw new IllegalArgumentException("a count extraction's valuePointer is outside the grammar");
            }
            valuePointer = null;
        }
        EventFilter filter = null;
        if (source.hasNonNull("eventFilter")) {
            JsonNode f = source.get("eventFilter");
            String pointer = text(f, "pointer");
            String equals = text(f, "equals");
            if (!POINTER.matcher(pointer).matches() || !EQUALS.matcher(equals).matches()) {
                throw new IllegalArgumentException("a push metric's eventFilter is outside the grammar");
            }
            filter = new EventFilter(pointer, equals);
        }
        return Optional.of(new PushMetric(spec.metricName(), spec.componentType(), spec.metricType(), topic, filter,
                extraction, valuePointer, configField, keyPointer));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("a push metric's source lacks a required text member");
        }
        return value.textValue();
    }
}
