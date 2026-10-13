package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressProperties;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.SecretMaterial;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T1.5, {@code testTargetConnection} against a REAL {@code sshd} ({@link SshdFixture}): the pinned host key, the
 * stored key and the one fixed command, over the wire, as {@link SshTargetConnectionTester} + {@link SshjTargetSshProbe}.
 *
 * <p><b>What the image cannot do.</b> It has no Docker, so the command exits 127 and Docker is reported unavailable: that is
 * the same result a stopped daemon or a user outside the {@code docker} group gives (exit 1), which is live gate L4 on the
 * E2E host, where the daemon can be stopped and an account without the group used. This class proves everything but the
 * Docker answer itself.
 *
 * <p><b>Written, NOT YET RUN against the container image</b> (Docker was down the night it was written). It was run, with the
 * same assertions, against a throwaway system {@code sshd} on loopback whose forced command stood in for Docker; see plan
 * 013 T1.5's Results ("indicative, not the fixture").
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetConnectionTestIT {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final KeyPair CLIENT = SshTestKeys.ed25519Pair();
    private static SshdFixture sshd;
    private static volatile SshTestKeys.Key storedKey = SshTestKeys.ed25519OpenSsh(CLIENT);

    @BeforeAll
    static void start() {
        sshd = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT));
    }

    @AfterAll
    static void stop() {
        if (sshd != null) {
            sshd.close();
        }
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    /** The fingerprint of the host key the transport actually negotiates with this host (it is not ours to assume which). */
    static String negotiatedPin(String host, int port) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new HostKeyVerifier() {
                @Override
                public boolean verify(String hostname, int p, java.security.PublicKey key) {
                    seen.set(SshHostKeyScanner.fingerprintOf(key));
                    return true;
                }

                @Override
                public List<String> findExistingAlgorithms(String hostname, int p) {
                    return List.of();
                }
            });
            ssh.connect(host, port);
        }
        return seen.get();
    }

    private static SshTargetConnectionTester testerFor(String host, int port, String pin) {
        return testerFor(host, port, pin, null);
    }

    private static SshTargetConnectionTester testerFor(String host, int port, String pin,
            com.solace.sm.dp.api.inventory.HostKeyType recordedType) {
        TargetConnectionRecord record = new TargetConnectionRecord(ORG, TARGET, host, port, SshdFixture.USER, pin,
                CREDENTIAL, "/srv/sm", "sm-", UUID.randomUUID(), Instant.EPOCH, null, null, recordedType);
        TargetConnectionRows rows = (TargetConnectionRows) Proxy.newProxyInstance(
                TargetConnectionTestIT.class.getClassLoader(), new Class<?>[] {TargetConnectionRows.class},
                (p, m, a) -> Optional.of(record));
        SshCredentialSource credentials = (tenant, ref) -> SecretMaterial.adopt(
                storedKey.text().getBytes(StandardCharsets.UTF_8));
        return new SshTargetConnectionTester(rows, credentials, new PermissiveTargetAddressPolicy(), Clock.systemUTC());
    }

    // ---- the steps -----------------------------------------------------------------------------------------

    @Test
    void theRightPinAndTheRightKeyPassTheFirstTwoStepsAndTheImageHasNoDockerSoTheThirdFailsAsAResult() throws Exception {
        String pin = negotiatedPin(sshd.host(), sshd.port());
        assertTrue(sshd.hostKeyFingerprints().containsValue(pin), "the pin is one of the host's three keys");

        TargetConnectionTestResult result = testerFor(sshd.host(), sshd.port(), pin).test(ORG, TARGET);

        assertTrue(result.hostKeyPinned());
        assertTrue(result.authenticated());
        assertFalse(result.dockerAvailable(), "no docker in the image: exit 127, reported as unavailable, not as an error");
        assertNull(result.dockerServerVersion());
    }

    /**
     * Review W4C-1. The host offers an Ed25519, an ECDSA and an RSA key, and sshj's own order picks only one of them, so
     * before the fix a pin to either of the other two never verified: the test reported {@code hostKeyPinned = false} on a
     * genuine host. Every key the host offers must be pinnable, whatever the client's preference. The expected fingerprints
     * come from the {@code .pub} files the daemon generated, by the wire blob, not from the code under test.
     */
    @Test
    void aPinToAnyOfTheHostsThreeKeyTypesVerifiesAndTheTestGoesOnToAuthenticate() {
        // A daemon of its own. The image's sshd is OpenSSH 10, whose PerSourcePenalties (default on: noauth 1 s, authfail
        // 5 s, enforced from 15 s accumulated) refuse a source that has dropped connections before authenticating, and
        // this test does so by design (each key family tried before the pinned one is a host-key refusal). Sharing the
        // class's daemon made the outcome depend on how many such connections the other tests had made first.
        try (SshdFixture own = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT))) {
            Map<String, String> offered = own.hostKeyFingerprints();
            assertEquals(java.util.Set.of("ed25519", "ecdsa", "rsa"), offered.keySet());

            for (Map.Entry<String, String> key : offered.entrySet()) {
                PinnedHostKeyConnect.forgetAll();            // discovery from cold, every time: the first operation after a registration
                TargetConnectionTestResult result = testerFor(own.host(), own.port(), key.getValue()).test(ORG, TARGET);

                assertTrue(result.hostKeyPinned(), "pinned to the " + key.getKey() + " key");
                assertTrue(result.authenticated(), "authenticated after pinning the " + key.getKey() + " key");
            }
        }
    }

    /**
     * OR-125 (2): the connection test negotiates the RECORDED type. Each of the host's three keys, recorded with its own
     * type, verifies and authenticates; the same pin recorded with the wrong type is NOT pinned (one attempt, no fallback to
     * discovering the real type), so a probe that ignored the recorded type would pass where this one fails.
     */
    @Test
    void aPinWithItsRecordedTypeVerifiesAndWithAWrongRecordedTypeFailsClosed() {
        try (SshdFixture own = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT))) {
            Map<String, String> offered = own.hostKeyFingerprints();
            Map<String, com.solace.sm.dp.api.inventory.HostKeyType> types = Map.of(
                    "ed25519", com.solace.sm.dp.api.inventory.HostKeyType.ED25519,
                    "ecdsa", com.solace.sm.dp.api.inventory.HostKeyType.ECDSA_NISTP256,
                    "rsa", com.solace.sm.dp.api.inventory.HostKeyType.RSA);

            for (Map.Entry<String, String> key : offered.entrySet()) {
                PinnedHostKeyConnect.forgetAll();
                TargetConnectionTestResult right = testerFor(own.host(), own.port(), key.getValue(),
                        types.get(key.getKey())).test(ORG, TARGET);
                assertTrue(right.hostKeyPinned(), "pinned to the " + key.getKey() + " key with its type recorded");
                assertTrue(right.authenticated());
            }

            PinnedHostKeyConnect.forgetAll();
            TargetConnectionTestResult wrong = testerFor(own.host(), own.port(), offered.get("ed25519"),
                    com.solace.sm.dp.api.inventory.HostKeyType.RSA).test(ORG, TARGET);
            assertFalse(wrong.hostKeyPinned(), "a wrong recorded type fails closed instead of being discovered around");
        }
    }

    @Test
    void aPinOfAnotherMachineIsNotPinnedAndNothingAfterItIsAttempted() throws Exception {
        String wrong = "SHA256:" + "A".repeat(43);

        TargetConnectionTestResult result = testerFor(sshd.host(), sshd.port(), wrong).test(ORG, TARGET);

        assertFalse(result.hostKeyPinned());
        assertFalse(result.authenticated());
        assertFalse(result.dockerAvailable());
    }

    @Test
    void aKeyTheHostDoesNotAcceptPassesThePinAndFailsAuthentication() throws Exception {
        String pin = negotiatedPin(sshd.host(), sshd.port());
        SshTestKeys.Key before = storedKey;
        storedKey = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());      // a key the host has never heard of
        try {
            TargetConnectionTestResult result = testerFor(sshd.host(), sshd.port(), pin).test(ORG, TARGET);

            assertTrue(result.hostKeyPinned());
            assertFalse(result.authenticated());
            assertFalse(result.dockerAvailable());
        } finally {
            storedKey = before;
        }
    }

    @Test
    void s3TheOldKeyIsRefusedOnceTheHostForgetsItAndTheNewKeyAuthenticatesThroughTheSameTester() throws Exception {
        String pin = negotiatedPin(sshd.host(), sshd.port());
        KeyPair replacement = SshTestKeys.ed25519Pair();
        SshTestKeys.Key oldKey = storedKey;
        try {
            sshd.setAuthorizedKeys(SshdFixture.authorizedKeyLine(replacement));       // the operator's step 4: remove the old key
            assertFalse(testerFor(sshd.host(), sshd.port(), pin).test(ORG, TARGET).authenticated(),
                    "the old key is refused by the host");

            storedKey = SshTestKeys.ed25519OpenSsh(replacement);                      // the rotation repointed the record
            assertTrue(testerFor(sshd.host(), sshd.port(), pin).test(ORG, TARGET).authenticated(),
                    "the next operation authenticates with the new key");
        } finally {
            storedKey = oldKey;
            sshd.setAuthorizedKeys(SshdFixture.authorizedKeyLine(CLIENT));
        }
    }

    @Test
    void nothingListeningIsAResultWithEveryStepFalse() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }

        TargetConnectionTestResult result = testerFor("127.0.0.1", closed, "SHA256:" + "A".repeat(43)).test(ORG, TARGET);

        assertFalse(result.hostKeyPinned());
        assertFalse(result.authenticated());
        assertFalse(result.dockerAvailable());
    }

    @Test
    void theStrictPolicyRefusesALoopbackTargetBeforeAnyConnectionSoTheTestIsAResultNotAConnection() throws Exception {
        TargetConnectionRecord record = new TargetConnectionRecord(ORG, TARGET, "127.0.0.1", sshd.port(),
                SshdFixture.USER, "SHA256:" + "A".repeat(43), CREDENTIAL, "/srv/sm", "sm-", UUID.randomUUID(),
                Instant.EPOCH, null);
        TargetConnectionRows rows = (TargetConnectionRows) Proxy.newProxyInstance(
                TargetConnectionTestIT.class.getClassLoader(), new Class<?>[] {TargetConnectionRows.class},
                (p, m, a) -> Optional.of(record));
        SshTargetConnectionTester strict = new SshTargetConnectionTester(rows,
                (tenant, ref) -> SecretMaterial.adopt(storedKey.text().getBytes(StandardCharsets.UTF_8)),
                new StrictTargetAddressPolicy(new TargetAddressProperties(List.of())), Clock.systemUTC());

        TargetConnectionTestResult result = strict.test(ORG, TARGET);

        assertFalse(result.hostKeyPinned());
        assertEquals(false, result.authenticated());
    }
}
