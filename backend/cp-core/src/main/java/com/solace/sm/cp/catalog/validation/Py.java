package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * The few Python semantics the reference gate leans on, so the port agrees with it rather than with a
 * "cleaner" reading. Where the Python's behaviour is a quirk it is kept and named here; the parity tests run
 * the Python as an oracle over exactly these cases ({@code MutantParityTest}).
 */
final class Py {

    private Py() {
    }

    /** Python truthiness of a parsed JSON value ({@code x or {}}, {@code not x}). */
    static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.decimalValue().signum() != 0;
        }
        if (n.isTextual()) {
            return !n.textValue().isEmpty();
        }
        return n.size() > 0;
    }

    /** The string value, or null when the node is absent or not a string. */
    static String text(JsonNode n) {
        return n != null && n.isTextual() ? n.textValue() : null;
    }

    /**
     * Python {@code ==} over JSON scalars: a bool is the number 0 or 1 ({@code True == 1}), numbers compare by
     * value ({@code 1 == 1.0}), strings by content; a string never equals a number.
     */
    static boolean eq(JsonNode a, JsonNode b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null || y != null) {
            return x != null && y != null && x.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    static boolean in(JsonNode value, Iterable<JsonNode> collection) {
        for (JsonNode candidate : collection) {
            if (eq(value, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Python {@code isinstance(v, int)}: true for a JSON integer AND for a bool (bool subclasses int). */
    static boolean isInt(JsonNode n) {
        return n.isIntegralNumber() || n.isBoolean();
    }

    private static BigDecimal number(JsonNode n) {
        if (n.isBoolean()) {
            return n.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return n.isNumber() ? n.decimalValue() : null;
    }

    /** A version id for a message: an integer is a selector, not a field value; anything else is not echoed. */
    static String version(JsonNode n) {
        return n != null && n.isIntegralNumber() ? n.asText() : "?";
    }

    static String versions(List<JsonNode> ns) {
        return ns.stream().map(Py::version).toList().toString();
    }
}
