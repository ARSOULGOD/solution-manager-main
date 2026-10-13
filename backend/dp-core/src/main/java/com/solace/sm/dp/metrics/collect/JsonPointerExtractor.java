package com.solace.sm.dp.metrics.collect;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Reads one number (or one restart token) out of a metric document by a JSON-pointer-shaped path (plan 011 P11-4.2).
 *
 * <p>The pointer grammar is the schema's, re-checked here: up to eight {@code /segment}s of
 * {@code [A-Za-z0-9_.-]{1,64}}, no {@code .} or {@code ..} segment, no {@code ~} escape. An absent or empty pointer
 * means the whole body is the number (MF9: {@code cache.size} answers a bare JSON number). Boot's single-metric
 * response is read at {@code /measurements/0/value} (MF8).
 *
 * <p><b>It refuses what is not a finite number.</b> A string, {@code null}, an object, a trailing token, an unparsable
 * body, and a value such as {@code 1e400} (a valid JSON number that Jackson reads as {@code Infinity} as a double)
 * are all {@link Extraction.Failed}, before anything reaches the ingest port, which re-checks (P11-3.1). A failure
 * names no value: the body is the target's answer and never renders into a message (I6).
 */
public final class JsonPointerExtractor {

    private static final Pattern POINTER = Pattern.compile("^(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_.-]{1,64}){0,8}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_.:+-]{1,128}$");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonPointerExtractor() {
    }

    /** A finite number, or a failure that carries no part of the body. */
    public sealed interface Extraction permits Extraction.Value, Extraction.Failed {

        record Value(double value) implements Extraction {
        }

        /** The closed reason word {@code parse_failed}; nothing from the body. */
        record Failed() implements Extraction {
            public String reason() {
                return "parse_failed";
            }

            @Override
            public String toString() {
                return "Failed[parse_failed]";
            }
        }
    }

    private static final Extraction FAILED = new Extraction.Failed();

    /** The finite number at {@code pointer}, or {@link Extraction.Failed}. */
    public static Extraction number(byte[] body, String pointer) {
        JsonNode node = nodeAt(body, pointer);
        if (node == null || !node.isNumber()) {
            return FAILED;
        }
        double value = node.doubleValue();
        return Double.isFinite(value) ? new Extraction.Value(value) : FAILED;
    }

    /**
     * The value at {@code pointer} as an opaque restart token (OQ-1 (a)): a finite number or a short string of
     * {@code [A-Za-z0-9_.:+-]}, rendered the way it was written; anything else is {@code null}.
     */
    public static String token(byte[] body, String pointer) {
        JsonNode node = nodeAt(body, pointer);
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return Double.isFinite(node.doubleValue()) ? node.asText() : null;
        }
        if (node.isTextual() && TOKEN.matcher(node.textValue()).matches()) {
            return node.textValue();
        }
        return null;
    }

    /** The node at the pointer, or {@code null} for an unparsable body, a bad pointer or a missing node. */
    private static JsonNode nodeAt(byte[] body, String pointer) {
        if (body == null || body.length == 0) {
            return null;
        }
        String path = pointer == null ? "" : pointer;
        if (!POINTER.matcher(path).matches()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || root.isMissingNode()) {
                return null;
            }
            JsonNode node = path.isEmpty() ? root : root.at(JsonPointer.compile(path));
            return node.isMissingNode() ? null : node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
