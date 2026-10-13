package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.apply.runtime.ssh.SshHostKeyScanner;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

/**
 * Opens a private key the way the SSH transport will, and reports the fingerprint of its PUBLIC half (plan 013 T1.1,
 * D-REG sections 6.3 and 8). It exists for two decisions, both made BEFORE anything is stored:
 *
 * <ul>
 *   <li><b>Parse before store.</b> A key that does not open as an UNENCRYPTED private key is refused with a fixed-text
 *       {@link CredentialInvalidException}. The transport passes no password finder
 *       ({@code SshjDockerTransport.loadKey}: {@code loadKeys(…, null, null)}), so a key that needs one could never be
 *       used, and storing it would only move the failure to the first apply.
 *   <li><b>Compare by public key.</b> The fingerprint ({@code SHA256:<base64>}, the spelling {@code ssh-keygen -l}
 *       prints and a pin has) is the same for every encoding of one key, so a re-export (OpenSSH or PEM, CRLF or LF, a
 *       trailing newline) is the same key. Comparing the text would misread it as a different one. Only this derived,
 *       public value ever leaves the {@code dp-core} inventory (I6).
 * </ul>
 *
 * <p><b>MF-R1 (measured against sshj 0.40.0, pinned in {@code PrivateKeyInspectorTest}).</b> {@code loadKeys} never
 * fails: it returns a lazy provider for every format, encrypted or not, and the failure arrives at
 * {@code getPrivate()}. So this class forces the private key and the public key, and reports success only when both
 * exist. A format the transport cannot open (a SEC1 {@code EC PRIVATE KEY}, measured) is refused for the same reason
 * as an encrypted one: it could never be used.
 *
 * <p><b>The buffer.</b> sshj needs the key as a {@code String}, which the JDK cannot zero: the same residual
 * {@code SshjDockerTransport.loadKey} and the gateway's request records already state (D-REG section 6.2). The caller
 * owns, and zeroises, its {@code char[]}; this class neither keeps nor modifies it.
 *
 * <p><b>No parser text survives.</b> A parser's message can quote key text, so the cause is dropped and the exception
 * is always the fixed one (I6).
 */
public final class PrivateKeyInspector {

    /** Beyond what the secret store would accept: refused before a parser is asked to read it. */
    private static final byte[] PROOF = "sm-key-pair-proof".getBytes(StandardCharsets.US_ASCII);

    private static final int MAX_CHARS = EnvelopeSecretStore.MAX_VALUE_BYTES;

    /**
     * @return {@code SHA256:<base64>} of the key's public half
     * @throws CredentialInvalidException when {@code privateKey} is not an unencrypted private key sshj can open
     */
    public String publicFingerprint(char[] privateKey) {
        if (privateKey == null || privateKey.length == 0 || privateKey.length > MAX_CHARS) {
            throw new CredentialInvalidException();
        }
        try (SSHClient ssh = new SSHClient()) {
            KeyProvider provider = ssh.loadKeys(new String(privateKey), null, null);
            // Lazy until now (MF-R1): an encrypted or unsupported key fails here, not at load.
            if (provider.getPrivate() == null) {
                throw new CredentialInvalidException();
            }
            PublicKey publicKey = provider.getPublic();
            if (publicKey == null || !isPair(provider.getPrivate(), publicKey)) {
                throw new CredentialInvalidException();
            }
            return SshHostKeyScanner.fingerprintOf(publicKey);
        } catch (CredentialInvalidException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Deliberately dropped: the message or cause may quote key text.
            throw new CredentialInvalidException();
        }
    }

    /**
     * W1 review C-1: proves that {@code publicKey} is the public half of {@code privateKey}. For an OpenSSH container
     * sshj takes the public key from the file's OUTER blob, which nothing authenticates: key A's blob on key B's private
     * section would otherwise yield A's fingerprint for B's key. A fixed message is signed with the private key and
     * verified with the public one, so the check holds for every key type sshj opens. Any failure is a refusal.
     */
    private static boolean isPair(PrivateKey privateKey, PublicKey publicKey) {
        try {
            Signature signer = signatureFor(privateKey);
            signer.initSign(privateKey);
            signer.update(PROOF);
            byte[] signature = signer.sign();
            Signature verifier = signatureFor(publicKey);
            verifier.initVerify(publicKey);
            verifier.update(PROOF);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static Signature signatureFor(Key key) throws GeneralSecurityException {
        String algorithm = key.getAlgorithm();
        if ("Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm)) {
            return Signature.getInstance("Ed25519");
        }
        if ("RSA".equals(algorithm)) {
            return Signature.getInstance("SHA256withRSA");
        }
        if ("EC".equals(algorithm) || "ECDSA".equals(algorithm)) {
            return Signature.getInstance("SHA256withECDSA");
        }
        throw new NoSuchAlgorithmException();
    }
}
