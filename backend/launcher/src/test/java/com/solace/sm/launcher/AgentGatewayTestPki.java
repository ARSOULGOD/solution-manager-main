package com.solace.sm.launcher;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A throwaway PKI for the agent gateway's launcher tests (plan 015 T3.2, G-A0): an operator CA in a PKCS12 keystore (with the
 * lease-nonce HMAC key entry, optionally withheld), the CA as a PEM trust bundle, a server certificate for {@code localhost}, and
 * client certificates signed by the CA or by a different CA. Nothing here is a credential anyone uses.
 */
final class AgentGatewayTestPki {

    static final String PASSWORD = "test-only-not-a-secret";
    static final String CA_ALIAS = "agent-ca";
    static final String NONCE_ALIAS = "lease-nonce-hmac";

    final Path directory;
    final Path keystore;
    final Path trustBundle;
    final Path serverCertificate;
    final Path serverKey;
    private final KeyPair caPair;
    private final X509Certificate ca;

    /** Creates the keystore with the lease-nonce key entry iff {@code withNonceKey}. */
    AgentGatewayTestPki(Path directory, boolean withNonceKey) throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.directory = directory;
        this.caPair = ecPair();
        this.ca = certificate("CN=Test Agent CA", caPair.getPublic(), "CN=Test Agent CA", caPair.getPrivate(), true, null);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(CA_ALIAS, caPair.getPrivate(), PASSWORD.toCharArray(), new Certificate[] {ca});
        if (withNonceKey) {
            byte[] hmac = new byte[32];
            new SecureRandom().nextBytes(hmac);
            store.setEntry(NONCE_ALIAS, new KeyStore.SecretKeyEntry(new SecretKeySpec(hmac, "HmacSHA256")),
                    new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
        }
        this.keystore = directory.resolve("agent-ca.p12");
        try (OutputStream out = Files.newOutputStream(keystore)) {
            store.store(out, PASSWORD.toCharArray());
        }
        this.trustBundle = directory.resolve("agent-ca.pem");
        Files.writeString(trustBundle, pem(ca), StandardCharsets.US_ASCII);

        KeyPair server = ecPair();
        X509Certificate serverCert = certificate("CN=localhost", server.getPublic(), "CN=Test Agent CA", caPair.getPrivate(), false,
                new GeneralNames(new GeneralName[] {new GeneralName(GeneralName.dNSName, "localhost"),
                        new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        this.serverCertificate = directory.resolve("gateway.crt");
        this.serverKey = directory.resolve("gateway.key");
        Files.writeString(serverCertificate, pem(serverCert), StandardCharsets.US_ASCII);
        Files.writeString(serverKey, pkcs8(server.getPrivate()), StandardCharsets.US_ASCII);
    }

    /** A freshly generated operator CA keystore (with the lease-nonce key) in a temp directory; returns its path. */
    static Path keystore() throws Exception {
        return new AgentGatewayTestPki(Files.createTempDirectory("agent-it-ca"), true).keystore;
    }

    /** A valid P-256 CSR for a fresh key. */
    static byte[] csr() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPair pair = ecPair();
        return new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=agent"), pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(pair.getPrivate())).getEncoded();
    }

    /** A client key and certificate signed by THIS CA; the files are returned as {certificate, key}. */
    Path[] client(String name) throws Exception {
        return clientSignedBy(name, caPair);
    }

    /** A client key and certificate signed by a CA the gateway does not trust. */
    Path[] clientFromAnotherCa(String name) throws Exception {
        return clientSignedBy(name, ecPair());
    }

    private Path[] clientSignedBy(String name, KeyPair issuer) throws Exception {
        KeyPair pair = ecPair();
        X509Certificate cert = certificate("CN=" + name, pair.getPublic(), "CN=Test Agent CA", issuer.getPrivate(), false, null);
        Path certFile = directory.resolve(name + ".crt");
        Path keyFile = directory.resolve(name + ".key");
        Files.writeString(certFile, pem(cert), StandardCharsets.US_ASCII);
        Files.writeString(keyFile, pkcs8(pair.getPrivate()), StandardCharsets.US_ASCII);
        return new Path[] {certFile, keyFile};
    }

    private static KeyPair ecPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String subject, java.security.PublicKey key, String issuer, PrivateKey signer,
            boolean authority, GeneralNames names) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Principal(issuer),
                new BigInteger(64, new SecureRandom()), Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(30))),
                new X500Principal(subject), key);
        if (authority) {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        }
        if (names != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, names);
        }
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(signer)));
    }

    private static String pem(X509Certificate certificate) throws Exception {
        java.io.StringWriter out = new java.io.StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(certificate);
        }
        return out.toString();
    }

    /** PKCS#8, which is what the gateway's Netty server loads ("BEGIN PRIVATE KEY"). */
    private static String pkcs8(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
