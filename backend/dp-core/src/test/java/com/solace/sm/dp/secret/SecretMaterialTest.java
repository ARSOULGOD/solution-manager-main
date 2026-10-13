package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretMaterialTest {

    @Test
    void secretMaterialIsZeroisedOnClose() {
        byte[] plain = "hunter2-sentinel".getBytes(StandardCharsets.UTF_8);
        byte[] live;
        try (SecretMaterial m = new SecretMaterial(plain)) {
            live = m.bytes();
            assertEquals("hunter2-sentinel", new String(live, StandardCharsets.UTF_8));
        }
        for (byte b : live) {
            assertEquals(0, b, "the buffer the material owned must be zero after close");
        }
    }

    @Test
    void theBufferTheCallerHandedInIsTheOneThatIsZeroed() {
        byte[] plain = "abc".getBytes(StandardCharsets.UTF_8);
        new SecretMaterial(plain).close();
        assertArrayEquals(new byte[3], plain);
    }

    @Test
    void aClosedMaterialRefusesToHandOutBytesOrChars() {
        SecretMaterial m = new SecretMaterial("abc".getBytes(StandardCharsets.UTF_8));
        m.close();
        assertTrue(m.isClosed());
        assertThrows(IllegalStateException.class, m::bytes);
        assertThrows(IllegalStateException.class, m::chars);
        m.close(); // idempotent
    }

    @Test
    void charsIsAFreshCopyTheCallerOwnsAndMayZero() {
        try (SecretMaterial m = new SecretMaterial("héllo".getBytes(StandardCharsets.UTF_8))) {
            char[] c = m.chars();
            assertEquals("héllo", new String(c));
            java.util.Arrays.fill(c, '\0');
            assertEquals("héllo", new String(m.chars()), "zeroing the copy does not touch the material");
        }
    }

    @Test
    void toStringNamesNothing() {
        try (SecretMaterial m = new SecretMaterial("hunter2-sentinel".getBytes(StandardCharsets.UTF_8))) {
            assertFalse(m.toString().contains("hunter2"), m.toString());
        }
    }

    @Test
    void adoptTakesOwnershipOfTheBufferAndCloseZeroesIt() {
        byte[] buffer = "adopted-secret".getBytes(StandardCharsets.UTF_8);
        SecretMaterial m = SecretMaterial.adopt(buffer);
        assertEquals("adopted-secret", new String(m.bytes(), StandardCharsets.UTF_8));
        m.close();
        assertArrayEquals(new byte[buffer.length], buffer, "the adopted buffer itself was zeroed");
        assertThrows(NullPointerException.class, () -> SecretMaterial.adopt(null));
    }
}
