package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.config.AgentGatewayCaProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 015 T2.2 follow-up (T2.1b review): the lease nonce is an HMAC-SHA256 under a secret-key entry of the operator's CA
 * keystore. The first test is the measurement the design rests on: the JDK's PKCS12 KeyStore round-trips a secret-key entry
 * in this setup. The control plane never checks a consumed nonce; it only mixes it in (the DP's digest compare is the check).
 */
class Pkcs12LeaseNonceSignerTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path directory;

    private Pkcs12LeaseNonceSigner signer() throws Exception {
        return new Pkcs12LeaseNonceSigner(AgentTestPki.properties(directory));
    }

    @Test
    void aPkcs12SecretKeyEntryRoundTripsThroughTheJdkKeyStoreApiAndSignerReadsIt() throws Exception {
        AgentGatewayCaProperties properties = AgentTestPki.properties(directory);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(properties.getKeyStoreFile()))) {
            store.load(in, AgentTestPki.PASSWORD.toCharArray());
        }
        assertTrue(store.entryInstanceOf(AgentTestPki.NONCE_ALIAS, KeyStore.SecretKeyEntry.class));
        assertEquals(32, store.getKey(AgentTestPki.NONCE_ALIAS, AgentTestPki.PASSWORD.toCharArray()).getEncoded().length);
        new Pkcs12LeaseNonceSigner(properties).verifyOperable();
    }

    @Test
    void identicalInputsGiveAnIdenticalNonceAndAnyChangeGivesAnother() throws Exception {
        Pkcs12LeaseNonceSigner signer = signer();
        String base = signer.nonce(AGENT, "req-1", "abc123", "prior");
        assertEquals(base, signer.nonce(AGENT, "req-1", "abc123", "prior"));
        assertEquals(64, base.length());

        Set<String> all = new HashSet<>(Set.of(base));
        all.add(signer.nonce(UUID.fromString("00000000-0000-0000-0000-0000000000a2"), "req-1", "abc123", "prior"));
        all.add(signer.nonce(AGENT, "req-2", "abc123", "prior"));
        all.add(signer.nonce(AGENT, "req-1", "abc124", "prior"));
        all.add(signer.nonce(AGENT, "req-1", "abc123", "prior2"));
        all.add(signer.nonce(AGENT, "req-1", "abc123", null));
        all.add(signer.nonce(AGENT, "req-1", "abc123", ""));
        assertEquals(7, all.size(), "each input changes the nonce, and null differs from empty");
    }

    @Test
    void movingBytesAcrossAFieldBoundaryChangesTheNonce() throws Exception {
        Pkcs12LeaseNonceSigner signer = signer();
        assertNotEquals(signer.nonce(AGENT, "ab", "c", "d"), signer.nonce(AGENT, "a", "bc", "d"));
        // The adjacent pair that can actually slide: the thumbprint and the consumed nonce.
        assertNotEquals(signer.nonce(AGENT, "r", "tx", "y"), signer.nonce(AGENT, "r", "t", "xy"));
        assertNotEquals(signer.nonce(AGENT, "r", "t", "xy"), signer.nonce(AGENT, "r", "txy", ""));
    }

    @Test
    void aDifferentKeyGivesADifferentNonce() throws Exception {
        String one = signer().nonce(AGENT, "req-1", "abc123", "prior");
        Path other = Files.createDirectory(directory.resolve("other"));
        String two = new Pkcs12LeaseNonceSigner(AgentTestPki.properties(other)).nonce(AGENT, "req-1", "abc123", "prior");
        assertNotEquals(one, two, "same public inputs under another keystore's key");
    }

    @Test
    void theNonceIsNotAPlainHashOfItsInputs() throws Exception {
        String nonce = signer().nonce(AGENT, "req-1", "abc123", "prior");
        String plain = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Pkcs12LeaseNonceSigner.encode("req-1", AGENT.toString(), "abc123", "prior")));
        assertNotEquals(plain, nonce);
    }

    @Test
    void aMissingKeyEntryFailsClosedForNonceAndVerify() throws Exception {
        Pkcs12LeaseNonceSigner missing = new Pkcs12LeaseNonceSigner(AgentTestPki.propertiesWithoutNonceKey(directory));
        assertThrows(IllegalStateException.class, missing::verifyOperable);
        assertThrows(IllegalStateException.class, () -> missing.nonce(AGENT, "r", "t", null));

        AgentGatewayCaProperties wrongAlias = AgentTestPki.properties(Files.createDirectory(directory.resolve("w")));
        wrongAlias.setLeaseNonceKeyAlias("no-such-entry");
        assertThrows(IllegalStateException.class, () -> new Pkcs12LeaseNonceSigner(wrongAlias).verifyOperable());

        assertThrows(IllegalStateException.class,
                () -> new Pkcs12LeaseNonceSigner(new AgentGatewayCaProperties()).nonce(AGENT, "r", "t", null));
        AgentGatewayCaProperties badPassword = AgentTestPki.properties(Files.createDirectory(directory.resolve("p")));
        badPassword.setKeyStorePassword("not-the-password");
        assertThrows(IllegalStateException.class, () -> new Pkcs12LeaseNonceSigner(badPassword).verifyOperable());
    }

    @Test
    void aKeyShorterThanThirtyTwoBytesIsRefused() throws Exception {
        AgentGatewayCaProperties properties = AgentTestPki.properties(directory);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(properties.getKeyStoreFile()))) {
            store.load(in, AgentTestPki.PASSWORD.toCharArray());
        }
        store.setEntry("short", new KeyStore.SecretKeyEntry(new javax.crypto.spec.SecretKeySpec(new byte[16], "HmacSHA256")),
                new KeyStore.PasswordProtection(AgentTestPki.PASSWORD.toCharArray()));
        try (java.io.OutputStream out = Files.newOutputStream(Path.of(properties.getKeyStoreFile()))) {
            store.store(out, AgentTestPki.PASSWORD.toCharArray());
        }
        properties.setLeaseNonceKeyAlias("short");
        assertThrows(IllegalStateException.class, () -> new Pkcs12LeaseNonceSigner(properties).verifyOperable());
    }

    @Test
    void theKeyBytesAppearInNoToStringExceptionMessageOrNonce() throws Exception {
        AgentGatewayCaProperties properties = AgentTestPki.properties(directory);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(properties.getKeyStoreFile()))) {
            store.load(in, AgentTestPki.PASSWORD.toCharArray());
        }
        byte[] key = store.getKey(AgentTestPki.NONCE_ALIAS, AgentTestPki.PASSWORD.toCharArray()).getEncoded();
        Pkcs12LeaseNonceSigner signer = new Pkcs12LeaseNonceSigner(properties);
        String nonce = signer.nonce(AGENT, "req-1", "abc123", "prior");

        for (String shown : new String[] {signer.toString(), nonce, AgentTestPki.PASSWORD}) {
            assertFalse(shown.contains(HexFormat.of().formatHex(key)) || shown.contains(Base64.getEncoder().encodeToString(key)));
        }
        assertEquals("Pkcs12LeaseNonceSigner", signer.toString());

        // Failure messages name no key either (nor the password).
        properties.setKeyStorePassword("not-the-password");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Pkcs12LeaseNonceSigner(properties).verifyOperable());
        assertFalse(failure.getMessage().contains(AgentTestPki.PASSWORD) || failure.getMessage().contains("not-the-password"));
        assertEquals(null, failure.getCause(), "the cause is not chained: a provider message is not ours to forward");
    }

    @Test
    void whenTheAgentPathIsRequiredAMissingNonceKeyFailsStartup() throws Exception {
        AgentGatewayCaProperties required = AgentTestPki.propertiesWithoutNonceKey(directory);
        required.setRequired(true);
        assertThrows(IllegalStateException.class, () -> new AgentConfiguration().leaseNonceSigner(required));

        required.setRequired(false);
        new AgentConfiguration().leaseNonceSigner(required); // built; only issuance refuses

        AgentGatewayCaProperties ok = AgentTestPki.properties(Files.createDirectory(directory.resolve("ok")));
        ok.setRequired(true);
        new AgentConfiguration().leaseNonceSigner(ok);
        assertEquals("lease-nonce-hmac", new AgentGatewayCaProperties().getLeaseNonceKeyAlias());
        assertEquals(Duration.ofDays(30), ok.getMaximumLeafLifetime());
    }
}
