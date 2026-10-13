package com.solace.sm.dp.apply.production;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The pure half of the production renderer (plan 010 T6.2): leaves at dotted paths in, YAML bytes out. A function of
 * content only, so the same generation renders the same bytes in a fresh process, which is what lets a resumed apply
 * re-render deterministically and compare the keyed hash it journaled.
 *
 * <p><b>Determinism is by construction.</b> Every map in the output is a {@link TreeMap} (keys sorted at every depth,
 * including inside a value that is itself an object), the writer configuration is fixed here and nowhere else, and
 * array order is content and is preserved. The writer writes no document-start marker and never folds a long scalar
 * across lines, so one value is one line and a diff of two renders points at the value that changed. Scalars keep their
 * JSON types: a string is always quoted, so {@code "true"} cannot become a boolean when Spring reads the file.
 *
 * <p><b>{@code I6}.</b> The leaves may carry a resolved secret, so no refusal names a path or a value and no refusal has
 * a cause (a Jackson message quotes the text it choked on, and a logger prints cause chains).
 */
final class YamlConfigRenderer {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES));

    /** One value at one dotted path. Never prints the value, and not even the path. */
    record Leaf(String path, JsonNode value) {

        Leaf {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Leaf[" + value.getNodeType() + "]";
        }
    }

    /**
     * @throws IllegalArgumentException for an empty path segment, two values at one path, or a value at a path that
     *         is also the parent of another; the message is fixed text
     */
    byte[] render(List<Leaf> leaves) {
        Map<String, Object> root = new TreeMap<>();
        for (Leaf leaf : Objects.requireNonNull(leaves, "leaves")) {
            place(root, leaf);
        }
        try {
            return YAML.writeValueAsBytes(toNode(root));
        } catch (JsonProcessingException e) {
            // the generator's message can quote content; the cause is dropped on purpose
            throw new IllegalArgumentException("the rendered configuration could not be written");
        }
    }

    @SuppressWarnings("unchecked")
    private static void place(Map<String, Object> root, Leaf leaf) {
        String[] segments = leaf.path().split("\\.", -1);
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("a rendered path has an empty segment");
            }
            boolean last = i == segments.length - 1;
            Object existing = current.get(segment);
            if (last) {
                if (existing != null) {
                    throw new IllegalArgumentException("a rendered path is claimed twice");
                }
                current.put(segment, sorted(leaf.value()));
            } else if (existing == null) {
                Map<String, Object> child = new TreeMap<>();
                current.put(segment, child);
                current = child;
            } else if (existing instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw new IllegalArgumentException("a rendered path runs beneath a value");
            }
        }
    }

    private static Object sorted(JsonNode node) {
        // a leaf is held as a JsonNode; a Map marks a branch, so a value that is itself an object is wrapped to
        // stay distinguishable from one — and to be sorted at every depth.
        return new Value(sortedNode(node));
    }

    private record Value(JsonNode node) {
    }

    private static JsonNode sortedNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> byKey = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> f = it.next();
                byKey.put(f.getKey(), sortedNode(f.getValue()));
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            byKey.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sortedNode(child)));
            return out;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static JsonNode toNode(Object tree) {
        if (tree instanceof Value value) {
            return value.node();
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ((Map<String, Object>) tree).forEach((key, child) -> out.set(key, toNode(child)));
        return out;
    }

    /** Names nothing: the renderer holds no state. */
    @Override
    public String toString() {
        return "YamlConfigRenderer";
    }
}
