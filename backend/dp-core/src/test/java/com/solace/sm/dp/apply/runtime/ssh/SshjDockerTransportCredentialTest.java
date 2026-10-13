package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.Test;

/**
 * T6.1 (M8, measured against sshj 0.40.0 and a live sshd): the private key is loaded from memory with
 * {@code SSHClient.loadKeys(String privateKey, String publicKey, PasswordFinder)}, by reference, and the
 * material is closed (zeroised) the moment the key provider exists. No file is ever created.
 */
class SshjDockerTransportCredentialTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final UUID PASSPHRASE = UUID.randomUUID();

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(TENANT, "host.example", 22, "demo", CREDENTIAL,
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static byte[] pkcs8Pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Hands out one material and remembers it, so the test can ask whether it was closed. */
    private static final class RecordingSource implements SshCredentialSource {
        final List<SecretMaterial> issued = new ArrayList<>();
        final List<Object[]> asked = new ArrayList<>();
        final List<SecretPurpose> askedPurposes = new ArrayList<>();
        private final byte[] pem;

        RecordingSource(byte[] pem) {
            this.pem = pem;
        }

        @Override
        public SecretMaterial privateKey(TenantId tenant, UUID credentialRef) {
            asked.add(new Object[] {tenant, credentialRef});
            askedPurposes.add(SecretPurpose.SSH_PRIVATE_KEY);
            return issue();
        }

        @Override
        public SecretMaterial privateKey(TenantId tenant, UUID credentialRef, SecretPurpose purpose) {
            asked.add(new Object[] {tenant, credentialRef});
            askedPurposes.add(purpose);
            return issue();
        }

        @Override
        public SecretMaterial passphrase(TenantId tenant, UUID passphraseRef, SecretPurpose purpose) {
            asked.add(new Object[] {tenant, passphraseRef});
            askedPurposes.add(purpose);
            return SecretMaterial.adopt("passphrase".getBytes(StandardCharsets.UTF_8));
        }

        private SecretMaterial issue() {
            SecretMaterial material = SecretMaterial.adopt(pem.clone());
            issued.add(material);
            return material;
        }
    }

    private static Set<String> tmpdirEntries() throws IOException {
        try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.map(Path::toString).collect(Collectors.toSet());
        }
    }

    @Test
    void theKeyIsNeverWrittenToDisk() throws Exception {
        KeyPair pair = keyPair();
        RecordingSource source = new RecordingSource(pkcs8Pem(pair));
        Set<String> before = tmpdirEntries();

        KeyProvider provider = SshjDockerTransport.loadKey(new SSHClient(), source, placement());

        assertNotNull(provider);
        assertArrayEquals(pair.getPublic().getEncoded(), provider.getPublic().getEncoded(),
                "the provider carries the key that was in memory");
        assertEquals(before, tmpdirEntries(), "no file was created under java.io.tmpdir");
        assertEquals(1, source.issued.size());
        assertTrue(source.issued.get(0).isClosed(), "the credential source's material was closed (zeroised)");
    }

    @Test
    void theCredentialIsAskedForByReferenceForThePlacementsTenant() throws Exception {
        RecordingSource source = new RecordingSource(pkcs8Pem(keyPair()));

        SshjDockerTransport.loadKey(new SSHClient(), source, placement());

        assertEquals(1, source.asked.size());
        assertEquals(TENANT, source.asked.get(0)[0], "I5: the tenant is the placement's, never a default");
        assertEquals(CREDENTIAL, source.asked.get(0)[1]);
    }

    @Test
    void connectionBackedPlacementUsesTheConnectionCredentialPurpose() throws Exception {
        RecordingSource source = new RecordingSource(pkcs8Pem(keyPair()));
        SshDockerPlacement connectionPlacement = new SshDockerPlacement(TENANT, "host.example", 22, "demo", CREDENTIAL,
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, AcquisitionMode.VIA_SSH, null,
                SecretPurpose.CONNECTION_CREDENTIAL);

        SshjDockerTransport.loadKey(new SSHClient(), source, connectionPlacement);

        assertEquals(List.of(SecretPurpose.CONNECTION_CREDENTIAL), source.askedPurposes);
    }

    @Test
    void encryptedKeyPathResolvesTheOptionalPassphraseReference() throws Exception {
        RecordingSource source = new RecordingSource(pkcs8Pem(keyPair()));
        SshDockerPlacement connectionPlacement = new SshDockerPlacement(TENANT, "host.example", 22, "demo", CREDENTIAL,
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, AcquisitionMode.VIA_SSH, null,
                SecretPurpose.CONNECTION_CREDENTIAL, PASSPHRASE);

        SshjDockerTransport.loadKey(new SSHClient(), source, connectionPlacement);

        assertEquals(List.of(SecretPurpose.CONNECTION_CREDENTIAL, SecretPurpose.CONNECTION_CREDENTIAL),
                source.askedPurposes);
        assertEquals(PASSPHRASE, source.asked.get(0)[1]);
        assertEquals(CREDENTIAL, source.asked.get(1)[1]);
    }

    @Test
    void anUnreadableKeyIsClassifiedAndStillClosesTheMaterial() {
        RecordingSource source = new RecordingSource("not a key at all".getBytes(StandardCharsets.UTF_8));

        RuntimeAdapterException failure = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.loadKey(new SSHClient(), source, placement()));

        assertEquals("ssh.credential.invalid", failure.errorClass().token());
        assertTrue(source.issued.get(0).isClosed(), "closed on the failure path too");
        assertTrue(!String.valueOf(failure.getMessage()).contains("not a key"), "no key text in the message");
    }

    @Test
    void aMissingCredentialIsClassifiedWithoutTheReferenceInTheMessage() {
        SshCredentialSource missing = (tenant, ref) -> {
            throw com.solace.sm.dp.secret.SecretFailures.notFound();
        };

        RuntimeAdapterException failure = assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.loadKey(new SSHClient(), missing, placement()));

        assertEquals("ssh.credential.unavailable", failure.errorClass().token());
        assertTrue(!String.valueOf(failure.getMessage()).contains(CREDENTIAL.toString()));
    }
}
