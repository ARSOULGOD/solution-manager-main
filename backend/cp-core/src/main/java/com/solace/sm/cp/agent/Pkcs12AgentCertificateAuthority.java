package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.config.AgentGatewayCaProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/** Production CA signer. It fails closed until an operator supplies the CP-owned keystore. */
public final class Pkcs12AgentCertificateAuthority implements AgentCertificateAuthority {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AgentGatewayCaProperties properties;

    public Pkcs12AgentCertificateAuthority(AgentGatewayCaProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public void verifyOperable() {
        load();
    }

    @Override
    public IssuedCertificate issue(UUID agentId, byte[] csrDer, Instant now, Duration requestedLifetime) {
        Loaded ca = load();
        JcaPKCS10CertificationRequest jcaCsr;
        try {
            jcaCsr = new JcaPKCS10CertificationRequest(new PKCS10CertificationRequest(csrDer)).setProvider("BC");
            if (!jcaCsr.isSignatureValid(new JcaContentVerifierProviderBuilder().setProvider("BC")
                    .build(jcaCsr.getPublicKey()))) {
                throw new AgentEnrollmentRefusedException(AgentEnrollmentRefusedException.Reason.CSR_REFUSED);
            }
            if (!acceptedKey(jcaCsr.getPublicKey())) {
                throw new AgentEnrollmentRefusedException(AgentEnrollmentRefusedException.Reason.CSR_REFUSED);
            }
        } catch (AgentEnrollmentRefusedException refused) {
            throw refused;
        } catch (IOException | RuntimeException | PKCSException | OperatorCreationException
                 | java.security.GeneralSecurityException e) {
            // A malformed request is the caller's fault, not the CA's: the message names no byte of it (I6).
            throw new AgentEnrollmentRefusedException(AgentEnrollmentRefusedException.Reason.CSR_REFUSED);
        }
        try {
            Duration lifetime = requestedLifetime == null || requestedLifetime.isNegative()
                    || requestedLifetime.isZero() ? properties.getMaximumLeafLifetime()
                    : requestedLifetime.compareTo(properties.getMaximumLeafLifetime()) > 0
                            ? properties.getMaximumLeafLifetime() : requestedLifetime;
            Instant validFrom = now.minusSeconds(30);
            // A leaf never outlives the CA that signed it: past the CA's notAfter no chain verifies.
            Instant validTo = now.plus(lifetime);
            Instant caNotAfter = ca.certificate.getNotAfter().toInstant();
            if (validTo.isAfter(caNotAfter)) validTo = caNotAfter;
            if (!validTo.isAfter(now)) throw new IllegalStateException("agent CA certificate has expired");
            BigInteger serial;
            do { serial = new BigInteger(159, RANDOM); } while (serial.signum() == 0); // 159 bits: positive in 20 octets
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(ca.certificate,
                    serial, Date.from(validFrom), Date.from(validTo),
                    new X500Principal("CN=" + agentId), jcaCsr.getPublicKey());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            String signature = signatureAlgorithm(ca.key);
            ContentSigner signer = new JcaContentSignerBuilder(signature).setProvider("BC").build(ca.key);
            X509Certificate leaf = new JcaX509CertificateConverter().setProvider("BC")
                    .getCertificate(new X509CertificateHolder(builder.build(signer).getEncoded()));
            return new IssuedCertificate(List.of(leaf.getEncoded(), ca.certificate.getEncoded()),
                    List.of(pem(ca.certificate)), validFrom, validTo);
        } catch (Exception e) {
            // The cause is kept for the operator log; the message carries nothing from the request.
            throw new IllegalStateException("agent certificate issuance failed", e);
        }
    }

    /** SHA-256 with a P-256 or RSA CA key; SHA-384 with a P-384 (or larger) CA key. */
    private static String signatureAlgorithm(PrivateKey key) {
        if ("RSA".equalsIgnoreCase(key.getAlgorithm())) return "SHA256withRSA";
        if (key instanceof ECKey ec && ec.getParams().getOrder().bitLength() > 256) return "SHA384withECDSA";
        return "SHA256withECDSA";
    }

    /** v1 accepts EC P-256 and P-384 only (an agent's key is generated by the agent; nothing else is a policy we have tested). */
    private static boolean acceptedKey(PublicKey key) throws java.security.GeneralSecurityException {
        if (!(key instanceof ECKey ec)) return false;
        for (String curve : new String[] {"secp256r1", "secp384r1"}) {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec(curve));
            ECParameterSpec named = parameters.getParameterSpec(ECParameterSpec.class);
            if (named.getCurve().equals(ec.getParams().getCurve()) && named.getOrder().equals(ec.getParams().getOrder())
                    && named.getGenerator().equals(ec.getParams().getGenerator())) {
                return true;
            }
        }
        return false;
    }

    private record Loaded(PrivateKey key, X509Certificate certificate) { }

    private Loaded load() {
        if (properties.getKeyStoreFile().isBlank() || properties.getKeyStorePassword().isBlank()
                || properties.getKeyAlias().isBlank()) {
            throw new IllegalStateException("agent CA keystore is not configured");
        }
        try {
            KeyStore store = KeyStore.getInstance(properties.getKeyStoreType());
            try (InputStream input = Files.newInputStream(Path.of(properties.getKeyStoreFile()))) {
                store.load(input, properties.getKeyStorePassword().toCharArray());
            }
            PrivateKey key = (PrivateKey) store.getKey(properties.getKeyAlias(), properties.getKeyStorePassword().toCharArray());
            X509Certificate ca = (X509Certificate) store.getCertificate(properties.getKeyAlias());
            if (key == null || ca == null) throw new IllegalStateException("agent CA alias has no key and certificate");
            return new Loaded(key, ca);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("agent CA keystore cannot be loaded", e);
        }
    }

    private static byte[] pem(X509Certificate certificate) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) { writer.writeObject(certificate); }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
