package com.solace.sm.dp.apply.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Q-R3 (a)'s ruled defaults, and the spacing rule: initial, doubling, capped. */
class ReconcilePropertiesTest {

    @Test
    void theDefaultsAreTheRuledOnes() {
        ReconcileProperties d = ReconcileProperties.defaults();

        assertEquals(5, d.maxAttempts());
        assertEquals(Duration.ofSeconds(30), d.initialBackoff());
        assertEquals(Duration.ofMinutes(5), d.maxBackoff());
        assertEquals(1, d.maxRedrives());
        assertEquals(Duration.ofHours(1), d.maxRedriveAge());
    }

    @Test
    void theSpacingDoublesFromTheInitialUpToTheMaximum() {
        ReconcileProperties d = ReconcileProperties.defaults();

        assertEquals(Duration.ofSeconds(30), d.backoffAfter(1));
        assertEquals(Duration.ofSeconds(60), d.backoffAfter(2));
        assertEquals(Duration.ofSeconds(120), d.backoffAfter(3));
        assertEquals(Duration.ofSeconds(240), d.backoffAfter(4));
        assertEquals(Duration.ofMinutes(5), d.backoffAfter(5), "capped, not 480 s");
        assertEquals(Duration.ofMinutes(5), d.backoffAfter(50), "and stays capped");
    }

    @Test
    void anIncoherentBoundIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReconcileProperties(0, Duration.ofSeconds(1), Duration.ofSeconds(2), 1, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconcileProperties(5, Duration.ofSeconds(10), Duration.ofSeconds(5), 1, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconcileProperties(5, Duration.ofSeconds(1), Duration.ofSeconds(5), -1, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconcileProperties(5, Duration.ZERO, Duration.ofSeconds(5), 1, Duration.ofHours(1)));
    }
}
