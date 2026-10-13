package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.config.AgentGatewayCaProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 015 T2.2, the operator-keystore CA ({@code OR-126} A4) as built, on Bouncy Castle 1.86: a leaf for exactly the
 * CSR's key, named for the agent, client-auth only, signed by the keystore's CA; and no keystore means no certificate and
 * a refusal to start when the process is told the agent path is required.
 */
class Pkcs12AgentCertificateAuthorityTest {

    @TempDir
    Path directory;

    @Test
    void theLeafIsNamedForTheAgentIsClientAuthOnlyAndVerifiesAgainstTheKeystoresCa() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(AgentTestPki.properties(directory));
        UUID agent = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-10T12:00:00Z");

        AgentCertificateAuthority.IssuedCertificate issued = ca.issue(agent, AgentTestPki.csr(), now, Duration.ZERO);

        X509Certificate leaf = AgentTestPki.parse(issued.chainDer().get(0));
        X509Certificate root = AgentTestPki.parse(issued.chainDer().get(1));
        leaf.verify(root.getPublicKey());
        assertEquals("CN=" + agent, leaf.getSubjectX500Principal().getName());
        assertTrue(leaf.getSerialNumber().signum() > 0 && leaf.getSerialNumber().bitLength() <= 159, "a 159-bit positive serial");
        for (int i = 0; i < 24; i++) { // a 160-bit draw exceeds 159 bits half the time, so 24 draws make a regression certain
            assertTrue(AgentTestPki.parse(ca.issue(agent, AgentTestPki.csr(), now, Duration.ZERO).chainDer().get(0))
                    .getSerialNumber().bitLength() <= 159);
        }
        boolean[] usage = leaf.getKeyUsage();
        assertTrue(usage[0], "digitalSignature");
        assertFalse(usage[2], "an EC leaf has no keyEncipherment");
        assertEquals(-1, leaf.getBasicConstraints(), "a leaf is not a CA");
        assertEquals(java.util.List.of("1.3.6.1.5.5.7.3.2"), leaf.getExtendedKeyUsage());
        assertEquals(now.minusSeconds(30), issued.validFrom());
        assertEquals(now.plus(Duration.ofDays(30)), issued.validTo(), "ZERO asks for the CA's maximum leaf lifetime");
        assertEquals(1, issued.trustBundlePem().size());
        assertTrue(new String(issued.trustBundlePem().get(0), StandardCharsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----"));
    }

    @Test
    void aRequestedLifetimeIsCappedAtTheMaximum() throws Exception {
        AgentGatewayCaProperties properties = AgentTestPki.properties(directory);
        properties.setMaximumLeafLifetime(Duration.ofDays(2));
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(properties);
        Instant now = Instant.parse("2026-10-10T12:00:00Z");

        assertEquals(now.plus(Duration.ofDays(2)),
                ca.issue(UUID.randomUUID(), AgentTestPki.csr(), now, Duration.ofDays(400)).validTo());
        assertEquals(now.plus(Duration.ofHours(1)),
                ca.issue(UUID.randomUUID(), AgentTestPki.csr(), now, Duration.ofHours(1)).validTo());
    }

    @Test
    void aMalformedOrBadlySignedCsrIsTheCsrRefusalNamingNothingOfIt() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(AgentTestPki.properties(directory));
        for (byte[] bad : new byte[][] {"garbage".getBytes(StandardCharsets.UTF_8), AgentTestPki.csrWithBadSignature()}) {
            AgentEnrollmentRefusedException failure = assertThrows(AgentEnrollmentRefusedException.class,
                    () -> ca.issue(UUID.randomUUID(), bad, Instant.now(), Duration.ZERO));
            assertEquals(AgentEnrollmentRefusedException.Reason.CSR_REFUSED, failure.reason());
            assertFalse(failure.getMessage().contains("garbage"));
        }
    }

    @Test
    void onlyEcP256AndP384KeysAreAccepted() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(AgentTestPki.properties(directory));
        for (String accepted : new String[] {"EC:secp256r1", "EC:secp384r1"}) {
            assertNotNull(ca.issue(UUID.randomUUID(), AgentTestPki.csrFor(accepted), Instant.now(), Duration.ZERO), accepted);
        }
        for (String refused : new String[] {"RSA:1024", "RSA:2048", "EC:secp521r1", "EC:secp256k1", "DSA:1024"}) {
            AgentEnrollmentRefusedException failure = assertThrows(AgentEnrollmentRefusedException.class,
                    () -> ca.issue(UUID.randomUUID(), AgentTestPki.csrFor(refused), Instant.now(), Duration.ZERO), refused);
            assertEquals(AgentEnrollmentRefusedException.Reason.CSR_REFUSED, failure.reason(), refused);
            assertEquals("the signing request is not acceptable", failure.getMessage(), "a fixed message");
        }
    }

    @Test
    void extensionsAndNamesTheCsrAsksForAreNeverCopiedIntoTheLeaf() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(AgentTestPki.properties(directory));

        X509Certificate leaf = AgentTestPki.parse(ca.issue(UUID.randomUUID(), AgentTestPki.csrAskingForExtensions(),
                Instant.now(), Duration.ZERO).chainDer().get(0));

        assertTrue(leaf.getSubjectAlternativeNames() == null, "no SAN from the request");
        assertEquals(-1, leaf.getBasicConstraints(), "the request cannot make its leaf a CA");
        assertEquals(java.util.List.of("1.3.6.1.5.5.7.3.2"), leaf.getExtendedKeyUsage(), "client auth only");
        assertTrue(leaf.getSubjectX500Principal().getName().startsWith("CN="));
        assertFalse(leaf.getSubjectX500Principal().getName().contains("agent"), "the subject is the agent id, not the request's");
    }

    @Test
    void aLeafNeverOutlivesTheCaCertificate() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(
                AgentTestPki.properties(directory, "secp256r1", Duration.ofDays(2)));
        Instant now = Instant.now();

        AgentCertificateAuthority.IssuedCertificate issued = ca.issue(UUID.randomUUID(), AgentTestPki.csr(), now, Duration.ZERO);

        X509Certificate root = AgentTestPki.parse(issued.chainDer().get(1));
        assertEquals(root.getNotAfter().toInstant(), issued.validTo(), "30 days were allowed; the CA has 2");
        assertEquals(issued.validTo().toEpochMilli() / 1000, AgentTestPki.parse(issued.chainDer().get(0)).getNotAfter().getTime() / 1000);
    }

    @Test
    void anExpiredCaSignsNothing() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(
                AgentTestPki.properties(directory, "secp256r1", Duration.ofDays(2)));
        assertThrows(IllegalStateException.class,
                () -> ca.issue(UUID.randomUUID(), AgentTestPki.csr(), Instant.now().plus(Duration.ofDays(3)), Duration.ZERO));
    }

    @Test
    void aP384CaKeySignsWithSha384() throws Exception {
        Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(
                AgentTestPki.properties(directory, "secp384r1", Duration.ofDays(30)));

        X509Certificate leaf = AgentTestPki.parse(ca.issue(UUID.randomUUID(), AgentTestPki.csr(), Instant.now(), Duration.ZERO)
                .chainDer().get(0));

        assertEquals("SHA384withECDSA", leaf.getSigAlgName());
    }

    @Test
    void withoutAKeystoreNothingIsSigned() throws Exception {
        for (AgentGatewayCaProperties unconfigured : new AgentGatewayCaProperties[] {
                new AgentGatewayCaProperties(), withMissingFile(), withWrongPassword()}) {
            Pkcs12AgentCertificateAuthority ca = new Pkcs12AgentCertificateAuthority(unconfigured);
            assertThrows(IllegalStateException.class, ca::verifyOperable);
            assertThrows(IllegalStateException.class,
                    () -> ca.issue(UUID.randomUUID(), AgentTestPki.csr(), Instant.now(), Duration.ZERO));
        }
    }

    @Test
    void aConfiguredKeystoreVerifiesAsOperable() throws Exception {
        new Pkcs12AgentCertificateAuthority(AgentTestPki.properties(directory)).verifyOperable();
    }

    @Test
    void whenTheAgentPathIsRequiredAMissingKeystoreFailsStartupAndOtherwiseOnlyIssuance() throws Exception {
        AgentGatewayCaProperties required = new AgentGatewayCaProperties();
        required.setRequired(true);
        assertThrows(IllegalStateException.class, () -> new AgentConfiguration().agentCertificateAuthority(required));

        assertNotNull(new AgentConfiguration().agentCertificateAuthority(new AgentGatewayCaProperties()),
                "unset, the CA is built and only issuance refuses");

        AgentGatewayCaProperties configured = AgentTestPki.properties(directory);
        configured.setRequired(true);
        assertNotNull(new AgentConfiguration().agentCertificateAuthority(configured));
    }

    private AgentGatewayCaProperties withMissingFile() throws Exception {
        AgentGatewayCaProperties p = AgentTestPki.properties(directory);
        p.setKeyStoreFile(directory.resolve("absent.p12").toString());
        return p;
    }

    private AgentGatewayCaProperties withWrongPassword() throws Exception {
        AgentGatewayCaProperties p = AgentTestPki.properties(directory);
        p.setKeyStorePassword("not-the-password");
        Files.exists(Path.of(p.getKeyStoreFile()));
        return p;
    }
}
