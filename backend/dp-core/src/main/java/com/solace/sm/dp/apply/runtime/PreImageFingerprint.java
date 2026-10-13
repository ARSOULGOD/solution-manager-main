package com.solace.sm.dp.apply.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.apply.model.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * OR-102: the keyed fingerprint recorded when a host's pre-apply config file is retained, and the check made before the
 * retained copy is used.
 *
 * <p><b>What is recorded</b> ({@link #record}), all under {@link ConfigHasher}'s keyed hash (OR-32, per tenant):
 * <ul>
 *   <li>{@code whole} - the keyed hash of the copy's bytes (what rollback and restore compare);
 *   <li>{@code paths} - for each of the package's declared <b>non-secret</b> dotted paths, a keyed digest of the value
 *       found there (or of its absence), so a later change can be named by path;
 *   <li>{@code rest} - one keyed digest over the remainder of the parsed document, that is the document with the
 *       declared non-secret paths removed. <b>The remainder includes secret-flagged values</b> (OR-113 (5), which keeps
 *       this digest as built and amends the wording of OR-102), so a secret edited in the retained pre-image is
 *       detected and reported as "other content changed", never by name. A path-only change is not mistaken for one.
 *       This adds no exposure: the digest is keyed with the DP-held key (OR-32 names {@code I6} for it, exactly as for
 *       {@code whole}) and is stored beside the pre-image, which already holds the plaintext. What is never done is
 *       digesting a secret-flagged path <i>on its own</i>: the only paths this class is given are non-secret ones, so a
 *       record never names a secret path;
 *   <li>{@code seal} - a keyed hash over everything above plus the file name and generation, so a record cannot be
 *       edited, or carried over from another generation or file, without the DP's key.
 * </ul>
 * The record names paths and carries digests. It never carries a value ({@code I6}).
 *
 * <p><b>Where it lives is the caller's business.</b> This class turns bytes into a record and back into a verdict; it
 * does no I/O, so a local-filesystem store and an SFTP transport share it. Whoever can write the retained copy can
 * write the record beside it, but cannot make the two agree without the key; the one thing they can do is remove the
 * record, which reads {@link Verdict.Unrecorded} and is refused, never trusted.
 */
public final class PreImageFingerprint {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final int VERSION = 1;
    private static final String ABSENT = "\u0000absent";

    private PreImageFingerprint() {
    }

    /** What {@link #check} concluded. */
    public sealed interface Verdict {

        /** The copy hashes to exactly what was recorded. */
        record Intact() implements Verdict {
        }

        /** The record is trustworthy and the copy no longer matches it. */
        record Changed(PreImageChange change) implements Verdict {

            public Changed {
                Objects.requireNonNull(change, "change");
            }
        }

        /**
         * There is no record to check against, or it is not one this DP sealed for this file and generation (missing,
         * garbled, edited, from another tenant's key, or sealed under a key version that can no longer be loaded).
         * The copy is neither trusted nor reported as changed: nothing can be said about it.
         */
        record Unrecorded() implements Verdict {
        }
    }

    /**
     * The record for {@code preImage}, as bytes to store beside it.
     *
     * @param nonSecretPaths the dotted paths the package declares non-secret; never a secret-flagged path
     */
    public static byte[] record(ConfigHasher hasher, TenantId tenant, String fileName, long generation,
            byte[] preImage, Set<String> nonSecretPaths) {
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(fileName, "file name");
        Objects.requireNonNull(preImage, "pre-image");
        TreeSet<String> paths = new TreeSet<>(Objects.requireNonNull(nonSecretPaths, "paths"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", VERSION);
        body.put("file", fileName);
        body.put("generation", generation);
        body.put("whole", hasher.hash(tenant, preImage));
        ObjectNode root = parse(preImage);
        Map<String, String> digests = new TreeMap<>();
        if (root != null) {
            for (String path : paths) {
                digests.put(path, hasher.hash(tenant, pathInput(path, find(root, path))));
            }
            body.put("rest", hasher.hash(tenant, restInput(root, paths)));
        } else {
            body.put("rest", null);
        }
        body.put("paths", digests);
        body.put("seal", hasher.hash(tenant, sealInput(body)));
        return write(body);
    }

    /**
     * Whether {@code current} is what {@code recorded} describes. Re-hashes the copy; never throws for a bad record.
     *
     * @param recorded the stored record, or {@code null} when there is none
     */
    public static Verdict check(ConfigHasher hasher, TenantId tenant, String fileName, long generation,
            byte[] recorded, byte[] current) {
        Objects.requireNonNull(hasher, "hasher");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(current, "current");
        Map<String, Object> body = read(recorded);
        if (body == null || !trusted(hasher, tenant, fileName, generation, body)) {
            return new Verdict.Unrecorded();
        }
        try {
            if (hasher.verifies(tenant, (String) body.get("whole"), current)) {
                return new Verdict.Intact();
            }
            return new Verdict.Changed(explain(hasher, tenant, body, current));
        } catch (ConfigHashUnverifiableException e) {
            return new Verdict.Unrecorded();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean trusted(ConfigHasher hasher, TenantId tenant, String fileName, long generation,
            Map<String, Object> body) {
        try {
            if (!Integer.valueOf(VERSION).equals(body.get("v")) || !fileName.equals(body.get("file"))
                    || !Long.valueOf(generation).equals(asLong(body.get("generation")))
                    || !(body.get("whole") instanceof String) || !(body.get("seal") instanceof String)
                    || !(body.get("paths") instanceof Map)
                    || (body.get("rest") != null && !(body.get("rest") instanceof String))) {
                return false;
            }
            for (Object digest : ((Map<String, Object>) body.get("paths")).values()) {
                if (!(digest instanceof String)) {
                    return false;
                }
            }
            Map<String, Object> unsealed = new LinkedHashMap<>(body);
            String seal = (String) unsealed.remove("seal");
            return hasher.verifies(tenant, seal, sealInput(unsealed));
        } catch (ConfigHashUnverifiableException | ClassCastException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static PreImageChange explain(ConfigHasher hasher, TenantId tenant, Map<String, Object> body,
            byte[] current) {
        Map<String, Object> recordedPaths = (Map<String, Object>) body.get("paths");
        ObjectNode root = parse(current);
        if (root == null || body.get("rest") == null) {
            // Either side is not a document the paths could be read from: nothing can be named, so say so.
            return new PreImageChange(List.of(), true);
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new TreeMap<>(recordedPaths).entrySet()) {
            byte[] now = pathInput(entry.getKey(), find(root, entry.getKey()));
            if (!hasher.verifies(tenant, (String) entry.getValue(), now)) {
                changed.add(entry.getKey());
            }
        }
        boolean restChanged =
                !hasher.verifies(tenant, (String) body.get("rest"), restInput(root, recordedPaths.keySet()));
        return new PreImageChange(changed, restChanged || changed.isEmpty());
    }

    // ---- the pieces each digest is taken over ---------------------------------------------------------

    private static byte[] pathInput(String path, JsonNode value) {
        return ("path\u0000" + path + "\u0000" + (value == null ? ABSENT : canonical(value)))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] restInput(ObjectNode root, Set<String> paths) {
        ObjectNode remainder = root.deepCopy();
        for (String path : paths) {
            remove(remainder, path.split("\\.", -1), 0);
        }
        return ("rest\u0000" + canonical(remainder)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sealInput(Map<String, Object> unsealed) {
        return write(unsealed);
    }

    private static JsonNode find(ObjectNode root, String path) {
        JsonNode node = root;
        for (String segment : path.split("\\.", -1)) {
            if (!(node instanceof ObjectNode) || !node.has(segment)) {
                return null;
            }
            node = node.get(segment);
        }
        return node;
    }

    /** Removes the leaf, then any object the removal left empty, so "a.b removed" and "a never had b" read alike. */
    private static boolean remove(ObjectNode node, String[] segments, int depth) {
        String segment = segments[depth];
        JsonNode child = node.get(segment);
        if (child == null) {
            return false;
        }
        if (depth == segments.length - 1) {
            node.remove(segment);
        } else if (child instanceof ObjectNode inner && remove(inner, segments, depth + 1) && inner.isEmpty()) {
            node.remove(segment);
        }
        return true;
    }

    private static String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        canonical(node, out);
        return out.toString();
    }

    private static void canonical(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), field.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : sorted.entrySet()) {
                out.append(first ? "" : ",").append(quote(field.getKey())).append(':');
                canonical(field.getValue(), out);
                first = false;
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                out.append(i == 0 ? "" : ",");
                canonical(node.get(i), out);
            }
            out.append(']');
        } else {
            out.append(node.isTextual() ? quote(node.textValue()) : node.toString());
        }
    }

    private static String quote(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException("a string could not be quoted");
        }
    }

    // ---- parsing and (de)serialisation; no failure here quotes the content --------------------------

    /** The document as an object tree, or {@code null} when it is not one (unparseable, empty, or not a mapping). */
    private static ObjectNode parse(byte[] bytes) {
        try {
            JsonNode node = YAML.readTree(bytes);
            return node instanceof ObjectNode object ? object : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(byte[] recorded) {
        if (recorded == null) {
            return null;
        }
        try {
            Object parsed = JSON.readValue(recorded, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("a pre-image record could not be written");
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
