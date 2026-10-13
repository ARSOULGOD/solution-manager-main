package com.solace.sm.dp.apply.runtime.readback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException.Reason;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * D-ACCEPT §3.2 (BE-D40): what is refused BEFORE any value is read. Each refusal is pinned to the construct that Jackson
 * reads WRONGLY (measured 2026-10-05: an alias becomes its anchor's name, silently) or that Spring Boot reads differently.
 */
class ConfigFileParserTest {

    private static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Reason refusedWith(String text) {
        return assertThrows(ReadBackRefusedException.class, () -> ConfigFileParser.parse(yaml(text))).reason();
    }

    // ---- what is read ---------------------------------------------------------------------------------------

    @Test
    void anOrdinaryFileIsReadWithItsJsonTypes() {
        JsonNode tree = ConfigFileParser.parse(yaml("cache-engine:\n  max-size: 250000\n  ttl: \"30m\"\n  enabled: true\n"
                + "  ratio: 1.5\n  list: [a, b]\n"));
        JsonNode engine = tree.get("cache-engine");
        assertTrue(engine.get("max-size").isInt());
        assertEquals(250000, engine.get("max-size").intValue());
        assertEquals("30m", engine.get("ttl").textValue());
        assertTrue(engine.get("enabled").booleanValue());
        assertTrue(engine.get("ratio").isDouble());
        assertEquals(2, engine.get("list").size());
    }

    @Test
    void aFlatDottedKeyIsALiteralKeyNotANestedPath() {
        JsonNode tree = ConfigFileParser.parse(yaml("cache-engine.max-size: 5\ncache-engine:\n  ttl: 1h\n"));
        assertTrue(tree.has("cache-engine.max-size"));
        assertFalse(tree.get("cache-engine").has("max-size"));
    }

    @Test
    void anExplicitDocumentStartAndAnEmptyMappingAreAFileWithNothingInIt() {
        assertTrue(ConfigFileParser.parse(yaml("---\n{}\n")).isEmpty());
        assertTrue(ConfigFileParser.parse(yaml("# only a comment on the first line\nkey: value\n")).has("key"));
    }

    // ---- what is refused, each against the construct Jackson would misread ------------------------------------------

    @Test
    void anAnchorAnAliasATagAMergeKeyOrASecondDocumentIsRefused422() {
        assertEquals(Reason.ANCHOR, refusedWith("x: &a hello\n"), "an anchor with no alias");
        assertEquals(Reason.ANCHOR, refusedWith("x: &a hello\ny: *a\n"),
                "Jackson reads y as the anchor's NAME: a wrong value with no error; the anchor is met first");
        assertEquals(Reason.ANCHOR, refusedWith("base: &b {x: 1}\nd: {<<: *b, y: 2}\n"),
                "a merge key is an alias to the MI and a literal key to Jackson");
        assertEquals(Reason.ALIAS, refusedWith("y: *a\n"), "an alias on its own, whatever it points at");
        assertEquals(Reason.ALIAS, refusedWith("d: {<<: *b}\n"), "a merge key's alias on its own");
        assertEquals(Reason.TAG, refusedWith("a: !!java.lang.ProcessBuilder [ls]\n"));
        assertEquals(Reason.TAG, refusedWith("a: !!str 123\n"), "an explicit tag changes a type the renderer never tags");
        assertEquals(Reason.MULTI_DOCUMENT, refusedWith("a: 1\n---\nb: 2\n"),
                "Jackson silently ignores the second document");
    }

    @Test
    void aDuplicateKeyIsRefused422() {
        assertEquals(Reason.DUPLICATE_KEY, refusedWith("a: 1\na: 2\n"), "Jackson alone: last wins, no error");
        assertEquals(Reason.DUPLICATE_KEY, refusedWith("outer:\n  b: 1\n  b: 2\n"));
    }

    @Test
    void anEmptyFileOrACommentOnlyFileIsRefusedAsEmpty() {
        assertEquals(Reason.EMPTY, refusedWith(""));
        assertEquals(Reason.EMPTY, refusedWith("# nothing but a comment\n"));
        assertEquals(Reason.EMPTY, refusedWith("---\n"), "an explicit but empty document");
    }

    @Test
    void aFileThatIsNotAMappingAtTheTopIsRefused() {
        assertEquals(Reason.NOT_A_MAPPING, refusedWith("- a\n- b\n"));
        assertEquals(Reason.NOT_A_MAPPING, refusedWith("just a scalar\n"));
    }

    @Test
    void aFileThatIsNotYamlAtAllIsRefused() {
        assertEquals(Reason.NOT_YAML, refusedWith("a: [1, 2\n"));
        assertEquals(Reason.NOT_YAML, refusedWith("a: b: c: d\n"));
        assertEquals(Reason.NOT_YAML, refusedWith("? [complex, key]\n: value\n"), "a mapping key that is not a scalar");
    }

    @Test
    void invalidUtf8IsNotYaml() {
        byte[] bad = {'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n'};
        assertEquals(Reason.NOT_YAML, assertThrows(ReadBackRefusedException.class, () -> ConfigFileParser.parse(bad)).reason());
    }

    @Test
    void aFileNestedPastTheBoundIsRefused() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < ConfigFileParser.MAX_DEPTH + 2; i++) {
            deep.append("  ".repeat(i)).append("k").append(i).append(":\n");
        }
        deep.append("  ".repeat(ConfigFileParser.MAX_DEPTH + 2)).append("leaf: 1\n");
        assertEquals(Reason.TOO_DEEP, refusedWith(deep.toString()));
        // exactly at the bound is fine
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < ConfigFileParser.MAX_DEPTH - 1; i++) {
            ok.append("  ".repeat(i)).append("k").append(i).append(":\n");
        }
        ok.append("  ".repeat(ConfigFileParser.MAX_DEPTH - 1)).append("leaf: 1\n");
        assertTrue(ConfigFileParser.parse(yaml(ok.toString())).isObject());
    }

    @Test
    void aFileWithMoreNodesThanTheBoundIsRefused() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < ConfigFileParser.MAX_EVENTS; i++) {
            wide.append("k").append(i).append(": 1\n");
        }
        assertEquals(Reason.TOO_LARGE, refusedWith(wide.toString()));
    }

    // ---- I6 -----------------------------------------------------------------------------------------------------

    /** A parser's own message quotes the text it choked on, which can be a secret fragment: none of it survives. */
    @Test
    void aRefusalCarriesNoTextFromTheFileAndNoCause() {
        String secret = "hunter2-sentinel-8c1f";
        for (String file : new String[] {"password: " + secret + "\na: [1, 2\n", "password: &" + secret + " x\n",
                "password: " + secret + "\npassword: other\n", "password: !!" + secret + " x\n",
                "- " + secret + "\n", "password: " + secret + "\n---\nz: 1\n"}) {
            ReadBackRefusedException e = assertThrows(ReadBackRefusedException.class,
                    () -> ConfigFileParser.parse(yaml(file)));
            assertFalse(e.getMessage().contains(secret), e.getMessage());
            assertEquals(null, e.getCause(), "a parser exception cause prints its message when logged");
        }
    }
}
