package com.solace.sm.dp.apply.model;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 — the {@code apply_id} shape design §3.1 asks for ("UUIDv7: time-ordered,
 * index-friendly").
 *
 * <p>Java 17 has no v7 generator ({@code UUID.randomUUID()} is v4), and the PostgreSQL 14 floor has
 * no {@code uuidv7()} either (that arrived in PG 18), so the id is minted here. The layout, bit for
 * bit:
 *
 * <pre>
 *   bits 127..80  unix_ts_ms   48 bits, big-endian milliseconds since the epoch
 *   bits  79..76  ver          0b0111
 *   bits  75..64  rand_a       12 random bits
 *   bits  63..62  var          0b10
 *   bits  61..0   rand_b       62 random bits
 * </pre>
 *
 * <p><b>Why time-ordering survives {@link UUID#compareTo}</b>, which compares the two 64-bit halves
 * as SIGNED longs: the most significant bit of the high half is bit 47 of the millisecond counter,
 * and unix milliseconds do not reach 2^47 until the year 6429. Until then every generated id has a
 * non-negative high half and signed comparison agrees with unsigned. Stated because it is the one
 * assumption that makes "index-friendly" true rather than hoped-for.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    /** A fresh v7 id stamped with the current wall clock. */
    public static UUID next() {
        return at(System.currentTimeMillis(), RANDOM);
    }

    /**
     * The pure form — every bit that is not the timestamp comes from {@code rnd}, so a test can pin
     * the millisecond and still exercise the real layout code.
     *
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z; only the low 48 bits are used
     * @param rnd         the randomness source for {@code rand_a} and {@code rand_b}
     */
    static UUID at(long epochMillis, Random rnd) {
        long msb = (epochMillis & 0x0000_FFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;                          // version 7, bits 79..76
        msb |= rnd.nextInt(0x1000);              // rand_a, 12 bits
        long lsb = rnd.nextLong();
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;           // clear the two variant bits
        lsb |= 0x8000_0000_0000_0000L;           // variant 0b10, bits 63..62
        return new UUID(msb, lsb);
    }

    /** The 48-bit timestamp back out of a v7 id — the field {@link UUID#compareTo} sorts on first. */
    public static long timestampMillis(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
