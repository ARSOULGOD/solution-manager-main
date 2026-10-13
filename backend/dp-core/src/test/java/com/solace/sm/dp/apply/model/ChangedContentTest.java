package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OR-108 (1): what an apply observation says about a retained pre-image that changed. Paths only, bounded, and never
 * a value (I6): a string that could carry one is not a path and is reported only as "other content changed".
 */
class ChangedContentTest {

    @Test
    void declaredPathsAreKeptSortedAndDistinct() {
        ChangedContent change = ChangedContent.of(List.of("cache-engine.ttl", "cache-engine.solace.host", "cache-engine.ttl"),
                false);

        assertEquals(List.of("cache-engine.solace.host", "cache-engine.ttl"), change.paths());
        assertFalse(change.pathsTruncated());
        assertFalse(change.otherContentChanged());
    }

    @Test
    void moreThanTheBoundIsCutAndSaysSo() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < ChangedContent.MAX_PATHS + 7; i++) {
            paths.add(String.format("cache-engine.field-%03d", i));
        }

        ChangedContent change = ChangedContent.of(paths, false);

        assertEquals(ChangedContent.MAX_PATHS, change.paths().size());
        assertEquals("cache-engine.field-000", change.paths().get(0), "the first in path order are kept");
        assertTrue(change.pathsTruncated(), "a cut list is never silent");
    }

    @Test
    void aStringThatIsNotShapedLikeAPathIsNeverStoredAndCountsAsOtherContent() {
        for (String notAPath : List.of("has space", "key=value", "line\nbreak", "quote\"d", "", "a".repeat(256),
                "password: hunter2", "x;rm -rf")) {
            ChangedContent change = ChangedContent.of(List.of("cache-engine.ttl", notAPath), false);

            assertEquals(List.of("cache-engine.ttl"), change.paths(), "[" + notAPath + "]");
            assertTrue(change.otherContentChanged(), "what cannot be named is reported only as other content");
        }
    }

    @Test
    void aChangeMustSayWhatChanged() {
        assertThrows(IllegalArgumentException.class, () -> ChangedContent.of(List.of(), false));
        assertTrue(ChangedContent.of(List.of(), true).otherContentChanged());
    }

    @Test
    void aPathRecordIsImmutable() {
        List<String> source = new ArrayList<>(List.of("a.b"));
        ChangedContent change = ChangedContent.of(source, false);
        source.add("c.d");

        assertEquals(List.of("a.b"), change.paths());
        assertThrows(UnsupportedOperationException.class, () -> change.paths().add("x"));
    }
}
