package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** I6 at the seam's one type that holds a host's file in clear (BE-D39). */
class ConfigReadBackTest {

    private static final byte[] FILE = "password: hunter2-sentinel\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void theConfigReadBackBuffersAreZeroedAfterUse() {
        byte[] owned = FILE.clone();
        ConfigReadBack back = new ConfigReadBack(owned, "hmacsha256v1:abc");

        back.close();

        assertTrue(back.isClosed());
        assertArrayEquals(new byte[owned.length], owned, "close must zero the very array it owns");
        assertThrows(IllegalStateException.class, back::bytes);
    }

    @Test
    void itHandsOutACopyNeverItsOwnArray() {
        byte[] owned = FILE.clone();
        try (ConfigReadBack back = new ConfigReadBack(owned, "h")) {
            byte[] first = back.bytes();
            assertNotSame(owned, first);
            first[0] = 'X';
            assertArrayEquals(FILE, back.bytes(), "a caller scribbling on its copy must not change the read-back");
        }
    }

    @Test
    void itHasNoPrintableFormThatNamesAByte() {
        try (ConfigReadBack back = new ConfigReadBack(FILE.clone(), "hmacsha256v1:abc")) {
            String printed = back.toString();
            assertFalse(printed.contains("hunter2"), printed);
            assertFalse(printed.contains("password"), printed);
            assertEquals("ConfigReadBack[" + FILE.length + " bytes, hmacsha256v1:abc]", printed);
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        ConfigReadBack back = new ConfigReadBack(FILE.clone(), "h");
        back.close();
        back.close();
        assertTrue(back.isClosed());
    }
}
