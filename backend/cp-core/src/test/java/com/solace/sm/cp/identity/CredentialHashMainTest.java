package com.solace.sm.cp.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CredentialHashMainTest {
    private static int run(String stdin, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        return CredentialHashMain.run(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), new PrintStream(out), new PrintStream(err));
    }

    @Test
    void itPrintsAnArgon2idHashTheMigrationJobAcceptsAndNeverTheSecret() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String secret = "correct horse battery staple";

        assertEquals(0, run(secret + "\n", out, err));

        String hash = out.toString(StandardCharsets.UTF_8).strip();
        assertTrue(hash.startsWith("{argon2id}$argon2id$"), hash);
        assertTrue(new CredentialHasher().verify(secret.toCharArray(), hash));
        assertFalse(hash.contains(secret) || err.toString(StandardCharsets.UTF_8).contains(secret));
    }

    @Test
    void anEmptySecretIsRefusedRatherThanHashed() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertEquals(2, run("\n", out, err));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }
}
