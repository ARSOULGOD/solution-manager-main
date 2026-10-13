package com.solace.sm.dp.api.agw;

import java.util.Arrays;

/**
 * The rendered bytes of one command, held for <em>one</em> in-memory hop to the gateway ({@code AGW-D26}). Nothing
 * stores them. {@code toString} prints neither the bytes nor either digest (plan 015 Global constraint 9, I6).
 *
 * @param configSha256          the plain digest of {@code bytes}, DP canonical form ({@code sha256:<hex>})
 * @param expectedCurrentSha256 only on REMATERIALIZE: the plain digest the host file must still have; the empty
 *                              string means "must still be absent"; otherwise {@code null}
 */
public record CommandPayload(byte[] bytes, String configSha256, String expectedCurrentSha256) {
    public CommandPayload {
        bytes = Arrays.copyOf(bytes == null ? new byte[0] : bytes, bytes == null ? 0 : bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CommandPayload p && Arrays.equals(bytes, p.bytes)
                && java.util.Objects.equals(configSha256, p.configSha256)
                && java.util.Objects.equals(expectedCurrentSha256, p.expectedCurrentSha256);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + java.util.Objects.hash(configSha256, expectedCurrentSha256);
    }

    @Override
    public String toString() {
        return "CommandPayload[bytes=<redacted:" + bytes.length + ">, configSha256=<redacted>, expectedCurrentSha256=<redacted>]";
    }
}
