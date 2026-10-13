package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.SshTestKeys;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T0.2 / T1.2 / T1.5 against a REAL {@code sshd} ({@link SshdFixture}): the scan returns every key type the host
 * offers, with the fingerprints {@code ssh-keygen -l} would print; and a key added to and removed from {@code authorized_keys}
 * at run time is accepted and then refused (what scenario S3's "the old key is refused" needs).
 *
 * <p><b>Written, NOT YET RUN</b> (Docker was down on the night it was written). The first run also settles the one fact
 * the image's README does not state: that {@code sshd} re-reads the file on every authentication.
 */
@Testcontainers(disabledWithoutDocker = true)
class SshdFixtureIT {

    private static final KeyPair CLIENT = SshTestKeys.ed25519Pair();
    private static SshdFixture sshd;

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

    @Test
    void scanningTheFixtureReturnsEveryKeyTypeItOffersWithTheFingerprintsOfItsOwnPubFiles() {
        SshHostKeyScanService scan = new SshHostKeyScanService(new PermissiveTargetAddressPolicy(), new SshHostKeyScanner());

        List<OfferedHostKey> offered = scan.scan(sshd.host(), sshd.port());

        Map<String, String> expected = sshd.hostKeyFingerprints();
        assertEquals(Set.copyOf(expected.values()),
                offered.stream().map(OfferedHostKey::fingerprint).collect(Collectors.toSet()),
                "the three keys sshd generated, by fingerprint");
        assertEquals(Set.of("ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa"),
                offered.stream().map(OfferedHostKey::algorithm).collect(Collectors.toSet()));
    }

    @Test
    void aKeyInAuthorizedKeysAuthenticatesAndOnceRemovedIsRefusedAtOnce() throws Exception {
        authenticateWith(CLIENT);     // accepted

        KeyPair replacement = SshTestKeys.ed25519Pair();
        sshd.setAuthorizedKeys(SshdFixture.authorizedKeyLine(replacement));       // S3: rotate on the host

        assertThrows(UserAuthException.class, () -> authenticateWith(CLIENT), "the old key is refused");
        authenticateWith(replacement);
        sshd.setAuthorizedKeys(SshdFixture.authorizedKeyLine(CLIENT));            // restore for the other tests
    }

    /**
     * Review W4C-1 for the POOLED health connector: a placement pinned to the host's ECDSA or RSA key connects, as one
     * pinned to its Ed25519 key does. (The per-operation transport and the connection test use the same
     * {@code PinnedHostKeyConnect}; the test is {@code TargetConnectionTestIT}.)
     */
    @Test
    void theHealthConnectorConnectsWithAPinToAnyOfTheHostsThreeKeyTypes() throws Exception {
        String key = SshTestKeys.ed25519OpenSsh(CLIENT).text();
        SshCredentialSource credentials = (tenant, ref) -> com.solace.sm.dp.secret.SecretMaterial
                .adopt(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SshjHostConnector connector = new SshjHostConnector(credentials, java.time.Duration.ofSeconds(30),
                new PermissiveTargetAddressPolicy());

        for (Map.Entry<String, String> pinned : sshd.hostKeyFingerprints().entrySet()) {
            PinnedHostKeyConnect.forgetAll();
            SshDockerPlacement placement = new SshDockerPlacement(
                    new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), sshd.host(), sshd.port(),
                    SshdFixture.USER, java.util.UUID.randomUUID(), pinned.getValue(), "sm-demo", "/srv/sm", "/srv/sm/demo",
                    "/app/external/spring/config", "application.yml",
                    com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);

            try (HostConnection connection = connector.connect(placement)) {
                org.junit.jupiter.api.Assertions.assertTrue(connection.isAlive(),
                        "connected and authenticated with the pin of the " + pinned.getKey() + " key");
            }
        }
    }

    private static final Map<String, com.solace.sm.dp.api.inventory.HostKeyType> TYPE_OF_FIXTURE_KEY = Map.of(
            "ed25519", com.solace.sm.dp.api.inventory.HostKeyType.ED25519,
            "ecdsa", com.solace.sm.dp.api.inventory.HostKeyType.ECDSA_NISTP256,
            "rsa", com.solace.sm.dp.api.inventory.HostKeyType.RSA);

    /**
     * OR-125 (2), on the wire: a pin whose type is RECORDED connects in ONE attempt whatever its type, and the attempt leads
     * with that type, so the host presents that very key. Before the type was persisted the ECDSA pin took two attempts and
     * the RSA pin three, the first ones refused by the verifier.
     */
    @Test
    void aPinWithARecordedTypeConnectsInOneAttemptForEachOfTheHostsThreeKeyTypes() throws Exception {
        for (Map.Entry<String, String> pinned : sshd.hostKeyFingerprints().entrySet()) {
            PinnedHostKeyConnect.forgetAll();
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<com.solace.sm.dp.api.inventory.HostKeyType> learned = new java.util.ArrayList<>();

            try (SSHClient ssh = PinnedHostKeyConnect.connect(pinned.getValue(), TYPE_OF_FIXTURE_KEY.get(pinned.getKey()),
                    8_000, 15_000, config -> { }, c -> {
                        attempts.incrementAndGet();
                        c.connect(sshd.host(), sshd.port());
                    }, c -> { }, () -> false, learned::add)) {
                org.junit.jupiter.api.Assertions.assertTrue(ssh.isConnected(), pinned.getKey());
            }

            assertEquals(1, attempts.get(), pinned.getKey() + ": no discovery when the type is recorded");
            assertEquals(java.util.List.of(), learned, "nothing was discovered, so nothing is reported");
        }
    }

    /**
     * OR-125 (2), on the wire: while the type is NOT recorded the connector discovers it, connects, and reports the EXACT type
     * of the key that matched the pin (not just its family), which is what gets persisted.
     */
    @Test
    void aPinWithNoRecordedTypeIsDiscoveredAndTheExactTypeThatMatchedIsReported() throws Exception {
        // A daemon of its own: discovery's failed attempts are pre-authentication drops, which OpenSSH 10's
        // PerSourcePenalties charge to the source (see TargetConnectionTestIT), and the class's daemon must stay usable.
        try (SshdFixture own = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT))) {
            for (Map.Entry<String, String> pinned : own.hostKeyFingerprints().entrySet()) {
                PinnedHostKeyConnect.forgetAll();
                java.util.List<com.solace.sm.dp.api.inventory.HostKeyType> learned = new java.util.ArrayList<>();

                try (SSHClient ssh = PinnedHostKeyConnect.connect(pinned.getValue(), null, 8_000, 15_000, config -> { },
                        c -> c.connect(own.host(), own.port()), c -> { }, () -> false, learned::add)) {
                    org.junit.jupiter.api.Assertions.assertTrue(ssh.isConnected(), pinned.getKey());
                }

                assertEquals(java.util.List.of(TYPE_OF_FIXTURE_KEY.get(pinned.getKey())), learned, pinned.getKey());
            }
        }
    }

    /** The pooled connector end to end: a placement with no recorded type connects and the recorder hears the type once. */
    @Test
    void theHealthConnectorReportsTheDiscoveredTypeToItsRecorderOnlyWhenNoneWasRecorded() throws Exception {
        String key = SshTestKeys.ed25519OpenSsh(CLIENT).text();
        SshCredentialSource credentials = (tenant, ref) -> com.solace.sm.dp.secret.SecretMaterial
                .adopt(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<String> heard = new java.util.ArrayList<>();
        SshjHostConnector connector = new SshjHostConnector(credentials, java.time.Duration.ofSeconds(30),
                new PermissiveTargetAddressPolicy(),
                (tenant, fingerprint, type) -> heard.add(fingerprint + ":" + type));

        try (SshdFixture own = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT))) {
            for (Map.Entry<String, String> pinned : own.hostKeyFingerprints().entrySet()) {
                PinnedHostKeyConnect.forgetAll();
                heard.clear();
                for (com.solace.sm.dp.api.inventory.HostKeyType recorded : new com.solace.sm.dp.api.inventory.HostKeyType[] {
                        null, TYPE_OF_FIXTURE_KEY.get(pinned.getKey())}) {
                    SshDockerPlacement placement = new SshDockerPlacement(
                            new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), own.host(), own.port(),
                            SshdFixture.USER, java.util.UUID.randomUUID(), pinned.getValue(), "sm-demo", "/srv/sm",
                            "/srv/sm/demo", "/app/external/spring/config", "application.yml",
                            com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10,
                            com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH, null, recorded);

                    try (HostConnection connection = connector.connect(placement)) {
                        org.junit.jupiter.api.Assertions.assertTrue(connection.isAlive());
                    }
                }

                assertEquals(java.util.List.of(pinned.getValue() + ":" + TYPE_OF_FIXTURE_KEY.get(pinned.getKey())), heard,
                        pinned.getKey() + ": reported once, by the connection that had to discover it");
            }
        }
    }

    /**
     * OR-125 (2): every connector negotiates the RECORDED type and nothing else. A pin whose recorded type is the wrong one
     * (the Ed25519 fingerprint recorded as an RSA key) is led with RSA, the host presents its RSA key, the verifier refuses
     * it, and the connection fails closed after that one attempt: the connector does not fall back to discovering the real
     * type. With the right type recorded the same pin connects. This is how a connector that ignored the recorded type would
     * be caught: it would discover the type and succeed.
     */
    @Test
    void everyConnectorHonoursTheRecordedTypeAndFailsClosedWhenItIsWrong() throws Exception {
        String key = SshTestKeys.ed25519OpenSsh(CLIENT).text();
        SshCredentialSource credentials = (tenant, ref) -> com.solace.sm.dp.secret.SecretMaterial
                .adopt(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SshjHostConnector connector = new SshjHostConnector(credentials, java.time.Duration.ofSeconds(30),
                new PermissiveTargetAddressPolicy());
        SshjDockerTransport transport = new SshjDockerTransport(credentials, new PermissiveTargetAddressPolicy());

        try (SshdFixture own = SshdFixture.start(SshdFixture.authorizedKeyLine(CLIENT))) {
            String pin = own.hostKeyFingerprints().get("ed25519");
            java.util.function.Function<com.solace.sm.dp.api.inventory.HostKeyType, SshDockerPlacement> placement =
                    recorded -> new SshDockerPlacement(
                            new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), own.host(), own.port(),
                            SshdFixture.USER, java.util.UUID.randomUUID(), pin, "sm-demo", "/srv/sm", "/srv/sm/demo",
                            "/app/external/spring/config", "application.yml",
                            com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10,
                            com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH, null, recorded);
            PinnedHostKeyConnect.forgetAll();

            com.solace.sm.dp.apply.runtime.RuntimeAdapterException pooled = assertThrows(
                    com.solace.sm.dp.apply.runtime.RuntimeAdapterException.class,
                    () -> connector.connect(placement.apply(com.solace.sm.dp.api.inventory.HostKeyType.RSA)));
            assertEquals("ssh.connection.failed", pooled.errorClass().token(), "the pooled connector");

            com.solace.sm.dp.apply.runtime.RuntimeAdapterException perOperation = assertThrows(
                    com.solace.sm.dp.apply.runtime.RuntimeAdapterException.class,
                    () -> transport.read(placement.apply(com.solace.sm.dp.api.inventory.HostKeyType.RSA), 10, null, null));
            assertEquals("ssh.connection.failed", perOperation.errorClass().token(), "the per-operation transport");

            try (HostConnection connection = connector.connect(
                    placement.apply(com.solace.sm.dp.api.inventory.HostKeyType.ED25519))) {
                org.junit.jupiter.api.Assertions.assertTrue(connection.isAlive(), "the right type connects");
            }
            com.solace.sm.dp.apply.runtime.RuntimeAdapterException past = assertThrows(
                    com.solace.sm.dp.apply.runtime.RuntimeAdapterException.class,
                    () -> transport.read(placement.apply(com.solace.sm.dp.api.inventory.HostKeyType.ED25519), 10, null,
                            null));
            org.junit.jupiter.api.Assertions.assertNotEquals("ssh.connection.failed", past.errorClass().token(),
                    "with the right type the transport connected and authenticated, and failed later (no docker in the image)");
        }
    }

    private static void authenticateWith(KeyPair pair) throws Exception {
        String pin = sshd.hostKeyFingerprints().get("ed25519");
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(pin);
            ssh.connect(sshd.host(), sshd.port());
            OpenSSHKeyV1KeyFile keyFile = new OpenSSHKeyV1KeyFile();
            keyFile.init(SshTestKeys.ed25519OpenSsh(pair).text(), null);
            KeyProvider provider = keyFile;
            ssh.authPublickey(SshdFixture.USER, provider);
        }
    }
}
