package com.solace.sm.cp.designer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The Java twin of the TS fixture generator's {@code canonical()}
 * ({@code contracts/view-models/src/fixtures.ts}): {@code JSON.stringify(value, null, 2)} plus a
 * trailing newline — 2-space indent, object keys in the order the map yields them, array order
 * as given.
 *
 * <p>Test-only on purpose: production code never serializes a view-model through this (the
 * browser does the byte-sensitive save/diff, {@code FE-D17}); the writer exists so a golden test
 * can compare BYTES, where Jackson's pretty printer would differ on harmless whitespace
 * ({@code "a" : 1}, {@code [ ]}) and so prove nothing about key order.
 *
 * <p>Follows ECMAScript {@code JSON.stringify}: an empty object is {@code {}} and an empty array
 * {@code []}; strings escape only {@code "}, {@code \}, the C0 controls (short forms
 * {@code \b \t \n \f \r}, otherwise a lowercase four-digit hex escape) and lone surrogates; {@code /} and
 * non-ASCII are written as-is; an integral number prints without a fraction.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** The whole document: the value at indent 0 plus a trailing newline, as the generator writes a file. */
    static String document(Object value) {
        return value(value, 0) + "\n";
    }

    /** The value as it appears nested at {@code indentLevel} (its own first line is not indented). */
    static String value(Object value, int indentLevel) {
        StringBuilder out = new StringBuilder();
        write(out, value, indentLevel);
        return out.toString();
    }

    private static void write(StringBuilder out, Object v, int level) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String s) {
            string(out, s);
        } else if (v instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (v instanceof Number n) {
            number(out, n);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                indent(out, level + 1);
                string(out, (String) e.getKey());
                out.append(": ");
                write(out, e.getValue(), level + 1);
            }
            out.append('\n');
            indent(out, level);
            out.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            boolean first = true;
            for (Object item : l) {
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                indent(out, level + 1);
                write(out, item, level + 1);
            }
            out.append('\n');
            indent(out, level);
            out.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    private static void number(StringBuilder out, Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            out.append(n.longValue());
            return;
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null"); // JSON.stringify's rule
        } else if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            out.append(new BigDecimal(d).toBigInteger());
        } else {
            out.append(Double.toString(d));
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    boolean loneSurrogate = Character.isHighSurrogate(c)
                            ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                            : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || loneSurrogate) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }
}
