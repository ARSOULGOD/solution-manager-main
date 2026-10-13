package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code BE-D19}: {@code ReplicaRef}'s constructor now enforces {@code ValueGrammar.REPLICA_REF} —
 * a closed, infrastructure-identifier character class — rather than the withdrawn {@code isBlank()}
 * gate. Every case here mirrors a row of the design's own 17-case measurement table (§3.4), so a
 * future edit to the grammar sees exactly what it would break at the Java layer, not only at SQL.
 *
 * <p>Each refusal case is a SENTINEL, in the same spirit as {@code EvidenceTypesTest}: a value
 * chosen to be as close as possible to something the class should reasonably admit, while still
 * failing for a stated reason — never a value chosen merely because it is exotic.
 */
class ReplicaRefTest {

    // ---------------------------------------------------------------------------------------
    // The sentinel and the length boundary, both edges.
    // ---------------------------------------------------------------------------------------

    @Test
    void thePlacementSentinelConstructsWithoutThrowing() {
        // PLACEMENT is a static final field: had its construction started throwing under the new
        // grammar, this whole test class would fail to load (ExceptionInInitializerError) rather
        // than merely fail this one method — the strongest proof available that it still matches.
        assertTrue(ReplicaRef.PLACEMENT.isPlacement());
        assertEquals("@placement", ReplicaRef.PLACEMENT.value());
    }

    @Test
    void aRefAtTheLengthBoundaryOf255CharactersIsAccepted() {
        String atTheBoundary = "a".repeat(255);
        ReplicaRef ref = assertDoesNotThrow(() -> new ReplicaRef(atTheBoundary));
        assertEquals(atTheBoundary, ref.value());
    }

    @Test
    void aRefOnePastTheLengthBoundaryAt256CharactersIsRefused() {
        String pastTheBoundary = "a".repeat(256);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReplicaRef(pastTheBoundary));
        assertFalse(e.getMessage().contains(pastTheBoundary),
                "the refusal must not echo the value (I6). Was: " + e.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // The two disqualifying cases REPLICA_REF exists to admit — proven ACCEPTED, not merely
    // asserted, because these are the exact facts that ruled out the withdrawn VALUE-based draft
    // (design §3.4, R1 fix round): a Kubernetes-shaped identifier past VALUE's old 128-character
    // cap, and a systemd-escaped unit-name fragment carrying a literal backslash.
    // ---------------------------------------------------------------------------------------

    @Test
    void aKubernetesShapedIdentifierLongerThan128CharactersIsAccepted() {
        String kubernetesShaped200 = "a".repeat(200);
        ReplicaRef ref = assertDoesNotThrow(() -> new ReplicaRef(kubernetesShaped200));
        assertEquals(kubernetesShaped200, ref.value());
    }

    @Test
    void aSystemdEscapedUnitNameFragmentCarryingALiteralBackslashIsAccepted() {
        String systemdFragment = "foo\\x2dbar.mount";
        assertEquals(16, systemdFragment.length(), "the design's own measured 16-character case");
        ReplicaRef ref = assertDoesNotThrow(() -> new ReplicaRef(systemdFragment));
        assertEquals(systemdFragment, ref.value());
    }

    @Test
    void aBareBackslashAloneIsAccepted() {
        // The single character both engines agreed on in the design's own 17-case table: a lone
        // backslash matches REPLICA_REF's class on its own, independent of the systemd context
        // above.
        ReplicaRef ref = assertDoesNotThrow(() -> new ReplicaRef("\\"));
        assertEquals("\\", ref.value());
    }

    // ---------------------------------------------------------------------------------------
    // Obligation 4: the two literals that flip from accepted to refused relative to the withdrawn
    // isBlank()/like_regex "\\S" gate — moved here from SchemaConstraintsIT's own positive-control
    // fixture so the Java and SQL sides of the same fact sit beside each other, not drift apart.
    // ---------------------------------------------------------------------------------------

    @Test
    void aRefWithASpaceQuotesEqualsAndAnAccentedCharacterIsRefused() {
        String hostile = "r1 \"q\" \\ = café";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReplicaRef(hostile));
        assertFalse(e.getMessage().contains(hostile), e.getMessage());
    }

    @Test
    void aRefWithAnEmbeddedNewlineIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplicaRef("r1\nwith-a-newline"));
    }

    // ---------------------------------------------------------------------------------------
    // Obligation 5: empty, whitespace-only, and CJK — refused by name-of-category, not merely
    // assumed from the character class.
    // ---------------------------------------------------------------------------------------

    @Test
    void anEmptyRefIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRef(""));
    }

    @Test
    void aWhitespaceOnlyRefIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRef("   "));
    }

    @Test
    void aCjkRefIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReplicaRef("日本語"));
        assertFalse(e.getMessage().contains("日本語"), e.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // Obligation 2: the two parity negatives — true both BEFORE this change (isBlank() alone
    // already refused a lone control codepoint, since it is not whitespace by Java's own
    // Character.isWhitespace) and AFTER it (the character class now refuses it too), for
    // different reasons. This leg proves the refusal is STILL true post-change, not a newly
    // introduced one — the write-direction gap BE-D19 closes is PostgreSQL's \S accepting these,
    // not Java's constructor, which never admitted them.
    // ---------------------------------------------------------------------------------------

    @Test
    void aLoneControlCodepointU001cIsRefused() {
        String lone = "\u001C";
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRef(lone));
    }

    @Test
    void aLoneControlCodepointU001fIsRefused() {
        String lone = "\u001F";
        assertThrows(IllegalArgumentException.class, () -> new ReplicaRef(lone));
    }

    // ---------------------------------------------------------------------------------------
    // I6: the refusal message names the rule, never the value — checked directly rather than
    // only inferred from ValueGrammar.require's own javadoc.
    // ---------------------------------------------------------------------------------------

    @Test
    void theRefusalMessageNamesThePatternNotTheValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReplicaRef("has a space in it"));
        assertTrue(e.getMessage().contains("[A-Za-z0-9._:/@+"), e.getMessage());
        assertFalse(e.getMessage().contains("has a space in it"), e.getMessage());
    }
}
