package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import org.junit.jupiter.api.Test;

/**
 * OR-89's residual (plan 010 T7.1): the fingerprint a registration RECORDS for each of a host's keys must be exactly
 * the spelling a pin has - the one {@code FingerprintVerifier} accepts - or the uniqueness it feeds compares strings
 * that never match. Proven against sshj's own verifier, for two key types.
 */
class SshHostKeyScannerTest {

    private static PublicKey rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair().getPublic();
    }

    private static PublicKey ecdsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair().getPublic();
    }

    @Test
    void aRecordedFingerprintIsExactlyWhatAPinLooksLikeAndWhatSshjAccepts() throws Exception {
        for (PublicKey key : new PublicKey[] {rsa(), ecdsa()}) {
            String fingerprint = SshHostKeyScanner.fingerprintOf(key);

            assertTrue(fingerprint.matches("^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$"), fingerprint);
            assertTrue(FingerprintVerifier.getInstance(fingerprint).verify("host", 22, key),
                    "the pin sshj verifies with is the very string the registration records");
        }
    }

    @Test
    void differentKeyTypesOfOneHostHaveDifferentFingerprintsAndNeitherVerifiesTheOther() throws Exception {
        PublicKey rsa = rsa();
        PublicKey ecdsa = ecdsa();
        String rsaFingerprint = SshHostKeyScanner.fingerprintOf(rsa);
        String ecdsaFingerprint = SshHostKeyScanner.fingerprintOf(ecdsa);

        assertFalse(rsaFingerprint.equals(ecdsaFingerprint));
        assertFalse(FingerprintVerifier.getInstance(rsaFingerprint).verify("host", 22, ecdsa));
        assertEquals(rsaFingerprint, SshHostKeyScanner.fingerprintOf(rsa), "deterministic");
    }

    @Test
    void aHostThatCannotBeReachedYieldsNoKeysAndTheScanSaysSoWithoutNamingTheHost() {
        // port 1 on loopback: refused immediately for every algorithm
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SshHostKeyScanner().scan("127.0.0.1", 1));
        assertFalse(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }
}
