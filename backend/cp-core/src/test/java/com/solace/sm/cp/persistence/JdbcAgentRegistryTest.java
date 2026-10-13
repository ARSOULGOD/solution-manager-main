package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Plan 015 T2.2 review finding 15: a certificate element that is not bytes is refused, never re-encoded. */
class JdbcAgentRegistryTest {

    @Test
    void aByteaElementIsReturnedAsIs() {
        assertArrayEquals(new byte[] {1, 2}, JdbcAgentRegistry.bytesOf(new byte[] {1, 2}));
    }

    @Test
    void anythingElseIsRefusedRatherThanReEncoded() {
        assertThrows(IllegalStateException.class, () -> JdbcAgentRegistry.bytesOf("\\x3082"));
        assertThrows(IllegalStateException.class, () -> JdbcAgentRegistry.bytesOf(null));
    }
}
