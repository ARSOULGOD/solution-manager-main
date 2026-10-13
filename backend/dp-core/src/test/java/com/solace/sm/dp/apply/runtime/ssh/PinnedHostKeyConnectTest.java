package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hierynomus.sshj.key.KeyAlgorithm;
import com.solace.sm.dp.api.inventory.HostKeyType;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import java.util.Optional;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.apply.runtime.ssh.PinnedHostKeyConnect.KeyFamily;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Review {@code W4C-1}, offline: how a connector finds the host-key type a pin belongs to. The wire behaviour (a real
 * {@code sshd} offering all three types, a pin to each) is {@code TargetConnectionTestIT} and {@code SshdFixtureIT}; this
 * class proves the decision logic with a fake connection, so it needs no daemon.
 */
class PinnedHostKeyConnectTest {

    private static final String PIN = "SHA256:" + "A".repeat(43);
    private static final String OTHER_PIN = "SHA256:" + "B".repeat(43);

    @BeforeEach
    @AfterEach
    void forget() {
        PinnedHostKeyConnect.forgetAll();
    }

    private static TransportException notThePin() {
        return new TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "not the pin");
    }

    private static List<String> names(SSHClient ssh) {
        return ssh.getTransport().getConfig().getKeyAlgorithms().stream().map(Factory.Named::getName).toList();
    }

    // ---- which families, in which order ----------------------------------------------------------------------

    @Test
    void anUnknownPinTriesEd25519ThenEcdsaThenRsa() {
        assertEquals(List.of(KeyFamily.ED25519, KeyFamily.ECDSA, KeyFamily.RSA), PinnedHostKeyConnect.attemptOrder(PIN));
    }

    @Test
    void theFamilyThatVerifiedIsTriedFirstNextTimeAndTheOthersStillFollow() throws Exception {
        AtomicInteger attempt = new AtomicInteger();
        PinnedHostKeyConnect.connect(PIN, 1, 1, config -> { }, ssh -> {
            if (attempt.incrementAndGet() < 3) {
                throw notThePin();
            }
        }, ssh -> { }, () -> false).close();

        assertEquals(List.of(KeyFamily.RSA, KeyFamily.ED25519, KeyFamily.ECDSA), PinnedHostKeyConnect.attemptOrder(PIN));
        assertEquals(List.of(KeyFamily.ED25519, KeyFamily.ECDSA, KeyFamily.RSA),
                PinnedHostKeyConnect.attemptOrder(OTHER_PIN), "what was learned belongs to that fingerprint alone");
    }

    // ---- the negotiation list ----------------------------------------------------------------------------------

    @Test
    void theFamilyLeadsTheListAndEverythingElseStaysSoTheUsersKeyCanStillSign() {
        List<Factory.Named<KeyAlgorithm>> defaults = new DefaultConfig().getKeyAlgorithms();
        List<String> defaultNames = defaults.stream().map(Factory.Named::getName).toList();

        for (KeyFamily family : KeyFamily.values()) {
            List<String> ordered = PinnedHostKeyConnect.preferring(family, defaults).stream()
                    .map(Factory.Named::getName).toList();
            List<String> lead = family.algorithms().stream().map(Factory.Named::getName).toList();

            assertEquals(lead, ordered.subList(0, lead.size()), family + " leads");
            assertEquals(ordered.size(), ordered.stream().distinct().count(), family + ": no duplicates");
            assertTrue(ordered.containsAll(defaultNames), family + ": nothing sshj would sign with is dropped (a user key of "
                    + "another type must still authenticate: measured against a real sshd)");
        }
    }

    @Test
    void eachAttemptIsConfiguredWithItsFamilyAheadAndTheCallersConfigurationApplied() throws Exception {
        List<List<String>> seen = new ArrayList<>();
        AtomicInteger configured = new AtomicInteger();
        AtomicInteger attempt = new AtomicInteger();

        PinnedHostKeyConnect.connect(PIN, 1, 1, config -> configured.incrementAndGet(), ssh -> {
            seen.add(names(ssh));
            if (attempt.incrementAndGet() < 3) {
                throw notThePin();
            }
        }, ssh -> { }, () -> false).close();

        assertEquals(3, seen.size());
        assertEquals(3, configured.get(), "the caller's configuration (a keep-alive) applies to every attempt");
        assertEquals("ssh-ed25519", seen.get(0).get(0));
        assertEquals("ecdsa-sha2-nistp256", seen.get(1).get(0));
        assertEquals("rsa-sha2-512", seen.get(2).get(0));
    }

    // ---- what counts as "try the next family" ------------------------------------------------------------------

    @Test
    void onlyAHostKeyThatIsNotThePinOrNoKeyExchangeMeansTryTheNextFamily() {
        assertTrue(PinnedHostKeyConnect.tryNextFamily(notThePin()));
        assertTrue(PinnedHostKeyConnect.tryNextFamily(new TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "x")));

        assertFalse(PinnedHostKeyConnect.tryNextFamily(new TransportException(DisconnectReason.CONNECTION_LOST, "x")));
        assertFalse(PinnedHostKeyConnect.tryNextFamily(new TransportException("no reason given")));
        assertFalse(PinnedHostKeyConnect.tryNextFamily(new ConnectException("refused")));
        assertFalse(PinnedHostKeyConnect.tryNextFamily(new SocketTimeoutException("timed out")));
        assertFalse(PinnedHostKeyConnect.tryNextFamily(new IOException("closed")));
    }

    @Test
    void anUnreachableHostStopsAtOnceWithoutTryingTheOtherFamilies() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectException refused = new ConnectException("refused");

        IOException thrown = assertThrows(IOException.class, () -> PinnedHostKeyConnect.connect(PIN, 1, 1, config -> { },
                ssh -> {
                    attempts.incrementAndGet();
                    throw refused;
                }, ssh -> { }, () -> false));

        assertSame(refused, thrown);
        assertEquals(1, attempts.get(), "a refused connection would fail the same way for every family");
        assertEquals(KeyFamily.ED25519, PinnedHostKeyConnect.attemptOrder(PIN).get(0), "nothing was learned");
    }

    @Test
    void aPinNoFamilyVerifiesTriesEachOnceAndFailsWithTheLastVerificationFailure() {
        AtomicInteger attempts = new AtomicInteger();

        IOException thrown = assertThrows(IOException.class, () -> PinnedHostKeyConnect.connect(PIN, 1, 1, config -> { },
                ssh -> {
                    attempts.incrementAndGet();
                    throw notThePin();
                }, ssh -> { }, () -> false));

        assertEquals(3, attempts.get());
        assertInstanceOf(TransportException.class, thrown);
        assertEquals(KeyFamily.ED25519, PinnedHostKeyConnect.attemptOrder(PIN).get(0), "a failure teaches nothing");
    }

    @Test
    void aCallerThatHasGivenUpStartsNoFurtherAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        boolean[] abandoned = {false};

        assertThrows(IOException.class, () -> PinnedHostKeyConnect.connect(PIN, 1, 1, config -> { }, ssh -> {
            attempts.incrementAndGet();
            abandoned[0] = true;                 // the deadline passes during the first attempt
            throw notThePin();
        }, ssh -> { }, () -> abandoned[0]));

        assertEquals(1, attempts.get());
    }

    @Test
    void everyClientIsHandedToTheCallerBeforeItConnectsSoAWatchdogCanCloseIt() throws Exception {
        List<SSHClient> handed = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();

        try (SSHClient connected = PinnedHostKeyConnect.connect(PIN, 1, 1, config -> { }, ssh -> {
            assertEquals(attempt.get() + 1, handed.size(), "handed over before the attempt ran");
            if (attempt.incrementAndGet() < 2) {
                throw notThePin();
            }
        }, handed::add, () -> false)) {
            assertEquals(2, handed.size());
            assertSame(handed.get(1), connected);
        }
    }

    // ---- OR-125 (2): a pin whose type is recorded is connected with directly -----------------------------------

    private static final Map<HostKeyType, String> LEADS = Map.of(
            HostKeyType.ED25519, "ssh-ed25519",
            HostKeyType.ECDSA_NISTP256, "ecdsa-sha2-nistp256",
            HostKeyType.ECDSA_NISTP384, "ecdsa-sha2-nistp384",
            HostKeyType.ECDSA_NISTP521, "ecdsa-sha2-nistp521",
            HostKeyType.RSA, "rsa-sha2-512");

    @Test
    void aPinWithARecordedTypeIsConnectedWithInOneAttemptThatLeadsWithThatType() throws Exception {
        for (Map.Entry<HostKeyType, String> expected : LEADS.entrySet()) {
            List<List<String>> seen = new ArrayList<>();

            PinnedHostKeyConnect.connect(PIN, expected.getKey(), 1, 1, config -> { }, ssh -> { },
                    ssh -> seen.add(names(ssh)), () -> false, learned -> { }).close();

            assertEquals(1, seen.size(), expected.getKey() + ": one attempt, no discovery");
            assertEquals(expected.getValue(), seen.get(0).get(0), expected.getKey() + " leads");
        }
    }

    @Test
    void aRecordedEcdsaCurveLeadsAloneNotTheWholeFamily() throws Exception {
        List<List<String>> seen = new ArrayList<>();

        PinnedHostKeyConnect.connect(PIN, HostKeyType.ECDSA_NISTP384, 1, 1, config -> { }, ssh -> { },
                ssh -> seen.add(names(ssh)), () -> false, learned -> { }).close();

        List<String> names = seen.get(0);
        assertEquals("ecdsa-sha2-nistp384", names.get(0));
        assertTrue(names.indexOf("ecdsa-sha2-nistp256") > 0 && names.indexOf("ecdsa-sha2-nistp521") > 0,
                "the other curves stay in the list (a user key must still sign) but behind the pinned one: " + names);
    }

    @Test
    void aRecordedTypeThatDoesNotVerifyFailsClosedAfterOneAttemptAndTriesNoOtherFamily() {
        AtomicInteger attempts = new AtomicInteger();

        IOException thrown = assertThrows(IOException.class, () -> PinnedHostKeyConnect.connect(PIN, HostKeyType.RSA, 1,
                1, config -> { }, ssh -> {
                    attempts.incrementAndGet();
                    throw notThePin();
                }, ssh -> { }, () -> false, learned -> { }));

        assertInstanceOf(TransportException.class, thrown);
        assertEquals(1, attempts.get(), "a fingerprint is a hash of one key: no other type can verify it");
    }

    @Test
    void aRecordedTypeIsNeverReportedAsLearnedAndTeachesTheMemoNothing() throws Exception {
        List<HostKeyType> learned = new ArrayList<>();

        PinnedHostKeyConnect.connect(PIN, HostKeyType.RSA, 1, 1, config -> { }, ssh -> { }, ssh -> { }, () -> false,
                learned::add).close();

        assertEquals(List.of(), learned, "nothing was discovered, so nothing is recorded");
        assertEquals(KeyFamily.ED25519, PinnedHostKeyConnect.attemptOrder(PIN).get(0));
    }

    // ---- the capturing verifier: how discovery learns the exact type -------------------------------------------

    private static KeyPair ecdsa(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    @Test
    void theCapturingVerifierReportsTheTypeOfTheKeyThatMatchedThePinAndOnlyThen() throws Exception {
        Map<HostKeyType, KeyPair> keys = Map.of(
                HostKeyType.ED25519, SshTestKeys.ed25519Pair(),
                HostKeyType.RSA, SshTestKeys.rsaPair(),
                HostKeyType.ECDSA_NISTP256, ecdsa("secp256r1"),
                HostKeyType.ECDSA_NISTP384, ecdsa("secp384r1"),
                HostKeyType.ECDSA_NISTP521, ecdsa("secp521r1"));

        for (Map.Entry<HostKeyType, KeyPair> key : keys.entrySet()) {
            String pin = SshHostKeyScanner.fingerprintOf(key.getValue().getPublic());
            PinnedHostKeyConnect.CapturingVerifier verifier = new PinnedHostKeyConnect.CapturingVerifier(
                    FingerprintVerifier.getInstance(pin));

            assertEquals(Optional.empty(), verifier.seen(), "nothing verified yet");
            assertFalse(verifier.verify("h", 22, SshTestKeys.ed25519Pair().getPublic()), "another key is refused");
            assertEquals(Optional.empty(), verifier.seen(), "a refused key reports nothing");
            assertTrue(verifier.verify("h", 22, key.getValue().getPublic()));
            assertEquals(Optional.of(key.getKey()), verifier.seen());
        }
    }
}
