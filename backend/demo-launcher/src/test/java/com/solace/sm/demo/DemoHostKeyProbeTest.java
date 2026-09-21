package com.solace.sm.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import org.junit.jupiter.api.Test;

class DemoHostKeyProbeTest {

    @Test
    void discoveredFingerprintIsAcceptedByThePinnedSshjVerifier() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String fingerprint = DemoHostKeyProbe.fingerprint(keyPair.getPublic());

        assertTrue(fingerprint.startsWith("SHA256:"));
        assertTrue(FingerprintVerifier.getInstance(fingerprint).verify("host.example", 22, keyPair.getPublic()));
    }
}
