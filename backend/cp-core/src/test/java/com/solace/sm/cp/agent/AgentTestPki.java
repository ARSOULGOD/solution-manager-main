package com.solace.sm.cp.agent;

import com.solace.sm.cp.config.AgentGatewayCaProperties;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/** A throwaway operator CA keystore and agent CSRs for the tests. Nothing here is a credential anyone uses. */
public final class AgentTestPki {

    public static final String PASSWORD = "test-only-not-a-secret";
    public static final String ALIAS = "agent-ca";
    public static final String NONCE_ALIAS = "lease-nonce-hmac";

    private AgentTestPki() {
        throw new AssertionError();
    }

    /** A configured CA backed by a freshly generated PKCS12 file under {@code directory}. */
    public static AgentGatewayCaProperties properties(Path directory) throws Exception {
        return properties(directory, "secp256r1", Duration.ofDays(365));
    }

    /** A CA on the given EC curve whose certificate is valid for {@code caLife} from now. */
    public static AgentGatewayCaProperties properties(Path directory, String curve, Duration caLife) throws Exception {
        return properties(directory, curve, caLife, true);
    }

    /** As above, optionally WITHOUT the lease-nonce HMAC key entry. */
    public static AgentGatewayCaProperties propertiesWithoutNonceKey(Path directory) throws Exception {
        return properties(directory, "secp256r1", Duration.ofDays(365), false);
    }

    private static AgentGatewayCaProperties properties(Path directory, String curve, Duration caLife, boolean withNonceKey)
            throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        KeyPair pair = ec(curve);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Principal("CN=Test Agent CA"),
                new BigInteger(64, new java.security.SecureRandom()), Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(caLife)), new X500Principal("CN=Test Agent CA"), pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        X509Certificate ca = new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                builder.build(new JcaContentSignerBuilder("secp384r1".equals(curve) ? "SHA384withECDSA" : "SHA256withECDSA")
                        .setProvider("BC").build(pair.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD.toCharArray(), new Certificate[] {ca});
        if (withNonceKey) {
            byte[] hmacKey = new byte[32];
            new java.security.SecureRandom().nextBytes(hmacKey);
            store.setEntry(NONCE_ALIAS, new KeyStore.SecretKeyEntry(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256")),
                    new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
        }
        Path file = directory.resolve("agent-ca-" + System.nanoTime() + ".p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, PASSWORD.toCharArray());
        }
        AgentGatewayCaProperties properties = new AgentGatewayCaProperties();
        properties.setKeyStoreFile(file.toString());
        properties.setKeyStorePassword(PASSWORD);
        properties.setKeyAlias(ALIAS);
        properties.setConnectEndpoint("agents.example.test:8443");
        return properties;
    }

    /** A valid CSR for a fresh key. */
    public static byte[] csr() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        KeyPair pair = ec();
        return new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=agent"), pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(pair.getPrivate()))
                .getEncoded();
    }

    /** A CSR whose signature does not verify: the body of one request under the signature of another. */
    public static byte[] csrWithBadSignature() throws Exception {
        byte[] good = csr();
        byte[] bad = good.clone();
        bad[bad.length - 3] ^= 0x55;
        return bad;
    }

    private static KeyPair ec() throws Exception {
        return ec("secp256r1");
    }

    private static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    /** A CSR for a fresh key of the named algorithm: "EC:secp384r1", "RSA:1024", "DSA:1024". */
    public static byte[] csrFor(String spec) throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        String[] parts = spec.split(":");
        KeyPair pair;
        String signature;
        if (parts[0].equals("EC")) {
            // BC generates the curves the JDK provider does not (secp256k1), which a refusal test needs.
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
            generator.initialize(new ECGenParameterSpec(parts[1]));
            pair = generator.generateKeyPair();
            signature = "SHA256withECDSA";
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(parts[0]);
            generator.initialize(Integer.parseInt(parts[1]));
            pair = generator.generateKeyPair();
            signature = parts[0].equals("RSA") ? "SHA256withRSA" : "SHA256withDSA";
        }
        return new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=agent"), pair.getPublic())
                .build(new JcaContentSignerBuilder(signature).setProvider("BC").build(pair.getPrivate())).getEncoded();
    }

    /** A valid P-256 CSR that ASKS for a SAN, a CA basicConstraints and a server EKU through extensionRequest. */
    public static byte[] csrAskingForExtensions() throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        KeyPair pair = ec();
        org.bouncycastle.asn1.x509.ExtensionsGenerator extensions = new org.bouncycastle.asn1.x509.ExtensionsGenerator();
        extensions.addExtension(Extension.subjectAlternativeName, false, new org.bouncycastle.asn1.x509.GeneralNames(
                new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.dNSName, "evil.example.test")));
        extensions.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        extensions.addExtension(Extension.extendedKeyUsage, false, new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth));
        return new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=agent"), pair.getPublic())
                .addAttribute(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(pair.getPrivate())).getEncoded();
    }

    static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }
}
