package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1: a package's topic pattern, re-checked by the Data Plane exactly as the schema checks it (defence in
 * depth: the plan refuses what a validator was bypassed to let through), and matched the way the broker matches (MF26: case
 * sensitive; {@code *} is exactly one level).
 */
class TopicPatternTest {

    @Test
    void aPatternMatchesATopicOfTheSameDepthWithEveryLevelExactAndAStarForOne() {
        TopicPattern p = TopicPattern.of(PushFixtures.HEARTBEAT_PATTERN);

        assertTrue(p.matches(PushFixtures.topic("ADAPTER_1", "heartbeat")));
        assertFalse(p.matches(PushFixtures.topic("ADAPTER_1", "fcomplete")), "the last level is a literal");
        assertFalse(p.matches("a/b/c/heartbeat"), "a * is exactly ONE level: a shorter topic is not matched");
        assertFalse(p.matches("x/" + PushFixtures.topic("ADAPTER_1", "heartbeat")), "nor a longer one");
        assertFalse(p.matches(""));
    }

    @Test
    void theMatchIsCaseSensitiveBecauseTheBrokersIs() {
        // MF26, measured on the local broker (solace-pubsub-enterprise 10.25.0.177): subscribe a/B/c, publish a/b/c: not delivered
        TopicPattern p = TopicPattern.of("a/B/*/heartbeat");

        assertTrue(p.matches("a/B/x/heartbeat"));
        assertFalse(p.matches("a/b/x/heartbeat"));
        assertFalse(p.matches("a/B/x/Heartbeat"));
    }

    @Test
    void theLastLevelIsALiteralNeverAWildcardOrASubstitution() {
        for (String bad : new String[] {"a/b/>", "a/b/*", "a/b/{instanceKey}", ">", "*", "a/b/c*", "a/b/>c"}) {
            assertThrows(IllegalArgumentException.class, () -> TopicPattern.of(bad), bad);
        }
    }

    @Test
    void noLevelIsAnythingButALiteralOrAStar() {
        for (String bad : new String[] {"a/#/c", "a/+/c", "a//c", "/a/c", "a/c/", "a/{instanceKey}/c", "a/b c/d",
                "a/b;c/d", "a/>/c", "a/**/c", "a/b*/c", "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> TopicPattern.of(bad), "'" + bad + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> TopicPattern.of("a/".repeat(16) + "b"), "more than 16 levels");
        assertThrows(IllegalArgumentException.class, () -> TopicPattern.of("a/" + "x".repeat(65)), "a level over 64 characters");
    }

    @Test
    void theValueIsWhatWasGivenAndTheLastLevelIsNamed() {
        TopicPattern p = TopicPattern.of(PushFixtures.FCOMPLETE_PATTERN);

        assertEquals(PushFixtures.FCOMPLETE_PATTERN, p.value());
        assertEquals("fcomplete", p.lastLevel());
    }
}
