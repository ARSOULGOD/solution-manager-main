package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The three claims {@link Uuid7} makes: the RFC 9562 bit layout, and that
 * {@link UUID#compareTo} — which compares SIGNED 64-bit halves — really does order these ids by
 * time.
 */
class Uuid7Test {

    @Test
    void everyGeneratedIdCarriesVersionSevenAndTheRfcVariant() {
        for (int i = 0; i < 1000; i++) {
            UUID id = Uuid7.next();
            assertEquals(7, id.version(), "RFC 9562 version nibble");
            assertEquals(2, id.variant(), "the 0b10 variant of RFC 9562 / 4122");
        }
    }

    @Test
    void theHighFortyEightBitsAreTheMillisecondTheIdWasStampedWith() {
        long millis = 0x0000_0123_4567_89ABL;
        UUID id = Uuid7.at(millis, new Random(42));
        assertEquals(millis, Uuid7.timestampMillis(id));
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void onlyTheLowFortyEightBitsOfTheTimestampAreUsedSoAnOverlongValueCannotCorruptTheVersion() {
        UUID id = Uuid7.at(-1L, new Random(1));
        assertEquals(0xFFFF_FFFF_FFFFL, Uuid7.timestampMillis(id));
        assertEquals(7, id.version(), "the version nibble survives a saturated timestamp");
        assertEquals(2, id.variant());
    }

    @Test
    void aThousandIdsSpanAtLeastTwoMillisecondsAndSortByCompareToInTimeOrder() throws Exception {
        List<UUID> generated = new ArrayList<>();
        Set<Long> distinctMillis = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            if (i == 500) {
                // Guarantees the ">= 2 distinct milliseconds" precondition instead of hoping the
                // loop is slow enough. Without it a fast machine mints all 1000 inside one
                // millisecond and the ordering claim below is never actually exercised.
                Thread.sleep(2);
            }
            UUID id = Uuid7.next();
            generated.add(id);
            distinctMillis.add(Uuid7.timestampMillis(id));
        }

        assertTrue(distinctMillis.size() >= 2,
                "the run must cross a millisecond boundary or it proves nothing about time order. "
                        + "Distinct milliseconds: " + distinctMillis.size());

        // Generation order is already non-decreasing in time…
        for (int i = 1; i < generated.size(); i++) {
            assertTrue(Uuid7.timestampMillis(generated.get(i))
                            >= Uuid7.timestampMillis(generated.get(i - 1)),
                    "wall clock went backwards between id " + (i - 1) + " and " + i);
        }

        // …and UUID.compareTo, which is signed, agrees with it: any two ids stamped in DIFFERENT
        // milliseconds compare in the same direction as their timestamps. This is the claim an
        // index depends on, and the one a signed comparison of the high half could have broken.
        for (int i = 0; i < generated.size(); i++) {
            for (int j = i + 1; j < generated.size(); j++) {
                long ti = Uuid7.timestampMillis(generated.get(i));
                long tj = Uuid7.timestampMillis(generated.get(j));
                if (ti != tj) {
                    assertTrue(generated.get(i).compareTo(generated.get(j)) < 0,
                            "compareTo disagreed with the clock: " + generated.get(i) + " (" + ti
                                    + ") vs " + generated.get(j) + " (" + tj + ")");
                }
            }
        }

        List<UUID> sorted = new ArrayList<>(generated);
        sorted.sort(UUID::compareTo);
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(Uuid7.timestampMillis(sorted.get(i))
                            >= Uuid7.timestampMillis(sorted.get(i - 1)),
                    "sorting by compareTo must leave timestamps non-decreasing");
        }
    }

    @Test
    void aThousandIdsAreDistinct() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(Uuid7.next()), "74 random bits per millisecond must not collide");
        }
    }
}
