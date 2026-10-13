package com.solace.sm.dp.metrics.push;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/**
 * The I6 filter before write (plan 011 P11-5.1; M-Q12): parses a broker event into a document, lets the caller ask it three
 * narrow questions (the key at a declared pointer, whether the declared filter holds, the finite number at a declared pointer)
 * and discards it. What leaves is a key string, a yes or no and a finite number. The payload is the MI's own, may carry a
 * secret (a {@code start} event's plaintext broker password, facts F15), and is NEVER stored, logged, counted by content or put
 * in an exception message: no value of it is in any result's {@code toString}, and a failure is a closed word.
 *
 * <p>A body that is not one JSON object, or is over the size bound, is refused without being read ({@link #parse} is empty): it
 * names no instance, so it is counted at the connection, never against a placement.
 *
 * <p>Immutable and thread-safe; each {@link Body} is for one event on one thread and is not kept.
 */
public final class BrokerSampleDecoder {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final int maxBodyBytes;

    /** @param maxBodyBytes events larger than this are refused unread */
    public BrokerSampleDecoder(int maxBodyBytes) {
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("the body bound must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    /** The event as a document, or empty when it is empty, too large, not JSON, or not one JSON object. */
    public Optional<Body> parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > maxBodyBytes) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            return root != null && root.isObject() ? Optional.of(new Body(root)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();   // the driver's message may quote the payload: it is discarded, never rethrown or logged
        }
    }

    /** What a pointer's number is: a finite one, or "not a finite number" (a string, a boolean, null, an object, 1e400). */
    public sealed interface NumberRead permits NumberRead.Finite, NumberRead.NotFinite {

        NumberRead NOT_A_FINITE_NUMBER = NotFinite.INSTANCE;

        record Finite(double value) implements NumberRead {
        }

        /** Carries nothing from the payload. */
        enum NotFinite implements NumberRead {
            INSTANCE;

            @Override
            public String toString() {
                return "NotAFiniteNumber";
            }
        }
    }

    /** One parsed event. Its {@link #toString} names nothing of the payload. */
    public static final class Body {

        private final JsonNode root;

        private Body(JsonNode root) {
            this.root = root;
        }

        /** The text at {@code pointer}, only when that node is a JSON string; empty for anything else, a bad pointer included. */
        public Optional<String> text(String pointer) {
            JsonNode node = at(pointer);
            return node != null && node.isTextual() ? Optional.of(node.textValue()) : Optional.empty();
        }

        /** The finite number at {@code pointer}, or {@link NumberRead#NOT_A_FINITE_NUMBER}. */
        public NumberRead number(String pointer) {
            JsonNode node = at(pointer);
            if (node == null || !node.isNumber()) {
                return NumberRead.NOT_A_FINITE_NUMBER;
            }
            double value = node.doubleValue();
            return Double.isFinite(value) ? new NumberRead.Finite(value) : NumberRead.NOT_A_FINITE_NUMBER;
        }

        /** Whether the scalar at the filter's pointer, read as text, equals the filter's value. */
        public boolean matches(PushMetric.EventFilter filter) {
            JsonNode node = at(filter.pointer());
            return node != null && node.isValueNode() && !node.isNull() && filter.equals().equals(node.asText());
        }

        private JsonNode at(String pointer) {
            if (pointer == null || !PushMetric.POINTER.matcher(pointer).matches()) {
                return null;
            }
            JsonNode node = root.at(JsonPointer.compile(pointer));
            return node.isMissingNode() ? null : node;
        }

        @Override
        public String toString() {
            return "Body[redacted]";
        }
    }
}
