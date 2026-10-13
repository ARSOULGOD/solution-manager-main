package com.solace.sm.cp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * The one spelling of a package document that its digest is taken over: no insignificant whitespace, object keys
 * in ascending order, array order preserved, decimals written without an exponent. Two documents that differ only
 * in whitespace or key order are the same document and so have the same digest, which is what makes a re-load
 * idempotent.
 */
final class PackageCanonicalizer {

    private PackageCanonicalizer() {
    }

    static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** {@code sha256:} followed by the lowercase hex digest of the canonical text's UTF-8 bytes. */
    static String digest(String canonicalText) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
                names.add(it.next());
            }
            Collections.sort(names);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(JsonNodeFactory.instance.textNode(names.get(i)));
                out.append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isBigDecimal() || node.isFloatingPointNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            out.append(node);
        }
    }
}
