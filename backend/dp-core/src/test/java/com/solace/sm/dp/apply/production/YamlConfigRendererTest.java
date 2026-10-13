package com.solace.sm.dp.apply.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.apply.production.YamlConfigRenderer.Leaf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 010 T6.2: the pure half of the renderer. Resume determinism rests on these bytes being a function of content. */
class YamlConfigRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final YamlConfigRenderer renderer = new YamlConfigRenderer();

    private static Leaf leaf(String path, String json) throws Exception {
        return new Leaf(path, JSON.readTree(json));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void keysAreSortedSoAReorderedDraftRendersTheSameBytes() throws Exception {
        List<Leaf> leaves = new ArrayList<>(List.of(
                leaf("cache-engine.ttl", "\"5m\""),
                leaf("cache-engine.mode", "\"READ\""),
                leaf("cache-engine.solace.vpn", "\"v1\""),
                leaf("cache-engine.solace.host", "\"h1\""),
                leaf("cache-engine.cache-hit-response.template-mappings", "{\"zeta\":1,\"alpha\":{\"y\":2,\"x\":3}}"),
                leaf("cache-engine.max-size", "100")));
        byte[] first = renderer.render(leaves);
        Collections.reverse(leaves);
        byte[] reversed = renderer.render(leaves);
        // a nested value whose own keys arrive in another order is the same content
        List<Leaf> rekeyed = new ArrayList<>(leaves);
        rekeyed.set(rekeyed.size() - 1 - 4,
                leaf("cache-engine.cache-hit-response.template-mappings", "{\"alpha\":{\"x\":3,\"y\":2},\"zeta\":1}"));
        byte[] nestedReordered = renderer.render(rekeyed);

        assertArrayEquals(first, reversed);
        assertArrayEquals(first, nestedReordered);
        String yaml = text(first);
        assertTrue(yaml.indexOf("host") < yaml.indexOf("vpn"), yaml);
        assertTrue(yaml.indexOf("mode") < yaml.indexOf("ttl"), yaml);
        assertTrue(yaml.indexOf("alpha") < yaml.indexOf("zeta"), yaml);
    }

    @Test
    void aDottedPathBecomesNestedMapsAndValuesKeepTheirJsonTypes() throws Exception {
        byte[] bytes = renderer.render(List.of(
                leaf("a.b.c", "\"text\""), leaf("a.b.n", "7"), leaf("a.flag", "true"),
                leaf("a.list", "[\"x\",\"y\"]"), leaf("top", "\"true\"")));

        JsonNode parsed = YAML.readTree(bytes);
        assertEquals("text", parsed.at("/a/b/c").textValue());
        assertTrue(parsed.at("/a/b/n").isInt());
        assertTrue(parsed.at("/a/flag").isBoolean());
        assertEquals(2, parsed.at("/a/list").size());
        assertTrue(parsed.at("/top").isTextual(), "the string \"true\" must stay a string, not become a boolean");
    }

    @Test
    void theOutputIsAFixedWriterConfigurationWithNoDocumentMarkerAndNoLineFolding() throws Exception {
        String longValue = "x".repeat(300);
        String yaml = text(renderer.render(List.of(leaf("k.long", JSON.writeValueAsString(longValue)))));

        assertFalse(yaml.startsWith("---"), yaml);
        assertEquals(1, yaml.lines().filter(l -> l.contains("xxxxxxxxxx")).count(), "a long scalar stays on one line");
        assertEquals(longValue, YAML.readTree(yaml).at("/k/long").textValue());
    }

    @Test
    void twoValuesForOnePathOrALeafUnderALeafAreRefusedNotSilentlyMerged() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(List.of(leaf("a.b", "1"), leaf("a.b", "2"))));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(List.of(leaf("a.b", "1"), leaf("a.b.c", "2"))));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(List.of(leaf("a.b.c", "2"), leaf("a.b", "1"))));
    }

    @Test
    void anEmptyPathSegmentIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> renderer.render(List.of(leaf("a..b", "1"))));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(List.of(leaf("", "1"))));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(List.of(leaf(".a", "1"))));
    }

    @Test
    void aRefusalNamesNeitherThePathNorTheValue() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> renderer.render(List.of(leaf("secret.path", "\"VALUE-SENTINEL\""),
                        leaf("secret.path", "\"VALUE-SENTINEL-2\""))));

        assertFalse(String.valueOf(e.getMessage()).contains("VALUE-SENTINEL"));
        assertEquals(null, e.getCause());
    }
}
