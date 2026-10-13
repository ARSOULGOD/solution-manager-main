package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The canonical form a generation's keyed digest is taken over (OR-33 guard 1). */
class CanonicalContentTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"type\":\"cache\",\"instanceKey\":\"c1\"}]}";

    private static StageCommand cmd(String draft, List<RenderedEntryValue> rendered) {
        return new StageCommand(1, "cache", "2.4", SELECTION, draft, rendered, GenerationOrigin.EDIT, null, ACTOR);
    }

    private static final List<RenderedEntryValue> ONE = List.of(RenderedEntryValue.plain("c1", "a.b", "1"));

    @Test
    void objectKeyOrderAtEveryDepthDoesNotChangeTheBytes() {
        CanonicalContent a = CanonicalContent.of(cmd("{\"c1\":{\"x\":1,\"y\":{\"p\":1,\"q\":2}}}", ONE));
        CanonicalContent b = CanonicalContent.of(cmd("{\"c1\":{\"y\":{\"q\":2,\"p\":1},\"x\":1}}", ONE));

        assertArrayEquals(a.digestInput(), b.digestInput());
        assertEquals(a.draftValues(), b.draftValues());
    }

    @Test
    void anArrayKeepsItsOrderBecauseAListInADraftIsOrderedContent() {
        CanonicalContent a = CanonicalContent.of(cmd("{\"c1\":{\"queues\":[\"a\",\"b\"]}}", ONE));
        CanonicalContent b = CanonicalContent.of(cmd("{\"c1\":{\"queues\":[\"b\",\"a\"]}}", ONE));

        assertFalse(java.util.Arrays.equals(a.digestInput(), b.digestInput()));
    }

    @Test
    void theRenderedSetIsOrderedByInstanceAndPathSoSubmissionOrderDoesNotMatter() {
        var one = RenderedEntryValue.plain("c1", "a", "1");
        var two = RenderedEntryValue.plain("c1", "b", "2");
        var other = RenderedEntryValue.plain("c0", "z", "3");

        assertArrayEquals(CanonicalContent.of(cmd("{}", List.of(one, two, other))).digestInput(),
                CanonicalContent.of(cmd("{}", List.of(other, two, one))).digestInput());
    }

    @Test
    void anyDifferenceInContentChangesTheBytes() {
        byte[] base = CanonicalContent.of(cmd("{\"c1\":{\"x\":1}}", ONE)).digestInput();

        assertFalse(java.util.Arrays.equals(base, CanonicalContent.of(cmd("{\"c1\":{\"x\":2}}", ONE)).digestInput()));
        assertFalse(java.util.Arrays.equals(base, CanonicalContent.of(cmd("{\"c1\":{\"x\":1}}",
                List.of(RenderedEntryValue.plain("c1", "a.b", "2")))).digestInput()));
        assertFalse(java.util.Arrays.equals(base, CanonicalContent.of(new StageCommand(1, "cache", "2.5", SELECTION,
                "{\"c1\":{\"x\":1}}", ONE, GenerationOrigin.EDIT, null, ACTOR)).digestInput()),
                "the package version is part of the content");
        assertFalse(java.util.Arrays.equals(base, CanonicalContent.of(new StageCommand(1, "cache", "2.4",
                "{\"instances\":[]}", "{\"c1\":{\"x\":1}}", ONE, GenerationOrigin.EDIT, null, ACTOR)).digestInput()));
    }

    @Test
    void presentationLayoutIsNeitherDigestedNorStoredInTheGeneration() {
        // OR-96 (UI-D20): layout is presentation state. It is excluded from the hash AND from the immutable
        // generation row; it lives in the per-instance presentation record instead.
        String at10 = "{\"instances\":[{\"instanceKey\":\"c1\",\"type\":\"cache\",\"layout\":{\"x\":10,\"y\":20}}]}";
        String at99 = "{\"instances\":[{\"instanceKey\":\"c1\",\"type\":\"cache\",\"layout\":{\"x\":99,\"y\":1.5}}]}";
        String bare = "{\"instances\":[{\"instanceKey\":\"c1\",\"type\":\"cache\"}]}";
        String otherType = "{\"instances\":[{\"instanceKey\":\"c1\",\"type\":\"other\",\"layout\":{\"x\":10,\"y\":20}}]}";

        CanonicalContent a = CanonicalContent.of(sel(at10));
        CanonicalContent b = CanonicalContent.of(sel(at99));

        assertArrayEquals(a.digestInput(), b.digestInput(), "moved nodes digest alike");
        assertArrayEquals(a.digestInput(), CanonicalContent.of(sel(bare)).digestInput(),
                "and a selection with no layout digests like one with");
        assertFalse(java.util.Arrays.equals(a.digestInput(), CanonicalContent.of(sel(otherType)).digestInput()),
                "everything but the layout stays content");
        assertFalse(a.componentSelection().contains("layout"), "the stored selection carries no layout: " + a.componentSelection());
        assertEquals(CanonicalContent.of(sel(bare)).componentSelection(), a.componentSelection(),
                "it is exactly the selection without it");
    }

    @Test
    void theLayoutTheCommandCarriesIsCanonicalAndSeparateFromTheSelection() {
        CanonicalContent c = CanonicalContent.of(withLayout("{\"c2\":{\"y\":1.5,\"x\":-3},\"c1\":{\"x\":10,\"y\":20}}"));

        assertEquals("{\"c1\":{\"x\":10,\"y\":20},\"c2\":{\"x\":-3,\"y\":1.5}}", c.layout(),
                "keys sorted at every depth, numbers as the client sent them");
        assertArrayEquals(CanonicalContent.of(sel(SELECTION)).digestInput(), c.digestInput(),
                "the layout is not digested");
        assertNull(CanonicalContent.of(sel(SELECTION)).layout(), "no layout in the command: leave the record alone");
        assertEquals("{}", CanonicalContent.of(withLayout("{}")).layout(), "an empty layout is a layout (clear it)");
    }

    @Test
    void aMalformedLayoutIsRefusedWithoutEchoingIt() {
        for (String bad : List.of("[]", "\"s\"", "{\"c1\":1}", "{\"c1\":{\"x\":1}}", "{\"c1\":{\"x\":\"1\",\"y\":2}}",
                "{\"c1\":{\"x\":1,\"y\":2,\"z\":3}}", "{\"c1\":{\"x\":1,\"y\":2}", "{\"SENTINEL-l\":{\"x\":1}}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CanonicalContent.of(withLayout(bad)), bad);
            assertFalse(e.getMessage().contains("SENTINEL"), e.getMessage());
        }
    }

    private static StageCommand withLayout(String layoutJson) {
        return new StageCommand(1, "cache", "2.4", SELECTION, "{}", ONE, GenerationOrigin.EDIT, null, ACTOR, layoutJson);
    }

    private static StageCommand sel(String selection) {
        return new StageCommand(1, "cache", "2.4", selection, "{}", ONE, GenerationOrigin.EDIT, null, ACTOR);
    }

    @Test
    void provenanceAndTheBaseAreNotContent() {
        byte[] edit = CanonicalContent.of(cmd("{}", ONE)).digestInput();
        byte[] rollback = CanonicalContent.of(new StageCommand(99, "cache", "2.4", SELECTION, "{}", ONE,
                GenerationOrigin.ROLLBACK, 3L, UUID.randomUUID())).digestInput();

        assertArrayEquals(edit, rollback, "a copy-forward of identical content is guard 1's 'unchanged'");
    }

    @Test
    void aSecretIsItsReferenceAndTwoReferencesDiffer() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        CanonicalContent a = CanonicalContent.of(cmd("{}", List.of(RenderedEntryValue.secret("c1", "pw", s1))));
        CanonicalContent b = CanonicalContent.of(cmd("{}", List.of(RenderedEntryValue.secret("c1", "pw", s2))));

        assertTrue(a.renderedEntries().contains(s1.toString()));
        assertFalse(a.renderedEntries().contains("\"value\""));
        assertFalse(java.util.Arrays.equals(a.digestInput(), b.digestInput()));
    }

    @Test
    void duplicateRenderedPathsAndMalformedJsonAreRefusedWithoutEchoingTheText() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd("{}", List.of(
                RenderedEntryValue.plain("c1", "a", "1"), RenderedEntryValue.plain("c1", "a", "2")))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CanonicalContent.of(cmd("{\"password\": SENTINEL-x", ONE)));
        assertFalse(e.getMessage().contains("SENTINEL"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd("[]", ONE)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd("\"s\"", ONE)));
        IllegalArgumentException v = assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(
                cmd("{}", List.of(RenderedEntryValue.plain("c1", "a", "{not json SENTINEL-y")))));
        assertFalse(v.getMessage().contains("SENTINEL"), v.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(
                cmd("{}", List.of(RenderedEntryValue.plain("c1", "a", "")))));
    }

    @Test
    void theDigestInputIsACopy() {
        CanonicalContent c = CanonicalContent.of(cmd("{}", ONE));
        byte[] first = c.digestInput();
        first[0] = 'X';
        assertNotEquals('X', c.digestInput()[0]);
    }
}
