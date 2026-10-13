package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.1 and MF-R1: what sshj 0.40.0 does with the key formats an administrator will paste, measured on
 * 2026-10-07 and pinned here.
 *
 * <p><b>MF-R1, measured.</b> {@code SSHClient.loadKeys(String, null, null)} NEVER fails at load: it returns a lazy
 * {@code KeyProvider} for every format below, encrypted or not. The failure is at {@code getPrivate()} (what
 * authentication calls first), as {@code KeyDecryptionFailedException} (OpenSSH container and PKCS#8) or a plain
 * {@code IOException} (legacy encrypted PEM, SEC1 EC PEM). So "the key loads" proves nothing, and
 * {@link PrivateKeyInspector} forces the private key and the public key before it reports anything.
 *
 * <p>The expected fingerprint is NEVER computed by the code under test: it is hand-encoded from the public key's SSH
 * wire blob ({@link SshTestKeys}) or is {@code ssh-keygen -l}'s own reading of the public file.
 */
class PrivateKeyInspectorTest {

    private static final String PASSPHRASE = "test-passphrase";

    private final PrivateKeyInspector inspector = new PrivateKeyInspector();

    private String fingerprintOf(SshTestKeys.Key key) {
        return inspector.publicFingerprint(key.chars());
    }

    // ---- accepted: no tool needed ---------------------------------------------------------------------------

    @Test
    void anUnencryptedKeyInEveryFormatTheJdkCanWriteYieldsItsPublicFingerprint() {
        KeyPair rsa = SshTestKeys.rsaPair();
        KeyPair ed = SshTestKeys.ed25519Pair();
        for (SshTestKeys.Key key : List.of(SshTestKeys.rsaPkcs8(rsa), SshTestKeys.rsaPkcs1(rsa),
                SshTestKeys.ed25519OpenSsh(ed))) {
            assertEquals(key.publicFingerprint(), fingerprintOf(key), key.text().lines().findFirst().orElseThrow());
        }
    }

    @Test
    void theSameKeyReExportedIsTheSameFingerprint() {
        KeyPair rsa = SshTestKeys.rsaPair();
        String expected = SshTestKeys.rsaFingerprint(rsa);
        List<SshTestKeys.Key> reExports = List.of(SshTestKeys.rsaPkcs8(rsa), SshTestKeys.rsaPkcs1(rsa),
                SshTestKeys.rsaPkcs8(rsa).crlf(), SshTestKeys.rsaPkcs8(rsa).withoutTrailingNewline(),
                SshTestKeys.rsaPkcs1(rsa).crlf().withoutTrailingNewline());
        for (SshTestKeys.Key key : reExports) {
            assertEquals(expected, fingerprintOf(key));
        }
    }

    @Test
    void twoDifferentKeysHaveDifferentFingerprints() {
        assertFalse(fingerprintOf(SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair()))
                .equals(fingerprintOf(SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair()))));
    }

    @Test
    void theFingerprintIsOfThePublicHalfAndHasTheSpellingAPinHas() {
        String fingerprint = fingerprintOf(SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair()));

        assertTrue(fingerprint.matches("^SHA256:[A-Za-z0-9+/]{43}$"), fingerprint);
    }

    @Test
    void theCallersBufferIsNotModified() {
        SshTestKeys.Key key = SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair());
        char[] buffer = key.chars();

        inspector.publicFingerprint(buffer);

        assertArrayEquals(key.chars(), buffer, "the caller owns the buffer and zeroises it itself");
    }

    // ---- accepted: every format the host's ssh-keygen writes -------------------------------------------------

    @Test
    void everyUnencryptedFormatSshKeygenWritesAgreesWithSshKeygensOwnFingerprint() {
        requireSshKeygen();
        String[][] cases = {{"ed25519", "OPENSSH"}, {"rsa", "OPENSSH"}, {"ecdsa", "OPENSSH"}, {"rsa", "PEM"},
                {"rsa", "PKCS8"}};
        for (String[] c : cases) {
            SshTestKeys.Key key = SshTestKeys.viaSshKeygen(c[0], c[1], "");
            assertEquals(key.publicFingerprint(), fingerprintOf(key), c[0] + " " + c[1]);
        }
    }

    /**
     * W1 review C-7: by default a missing {@code ssh-keygen} skips the cases that need it; with
     * {@code -Dsm.test.requireSshKeygen=true} (a gate that must not be green by omission) it fails instead.
     */
    private static void requireSshKeygen() {
        if (SshTestKeys.sshKeygenAvailable()) {
            return;
        }
        if (Boolean.getBoolean("sm.test.requireSshKeygen")) {
            fail("ssh-keygen is not on the path and -Dsm.test.requireSshKeygen=true");
        }
        assumeTrue(false, "ssh-keygen is not on the path");
    }

    // ---- refused ---------------------------------------------------------------------------------------------

    /** MF-R1: every one of these LOADS in sshj and fails only when the private key is asked for. */
    @Test
    void aPassphraseProtectedKeyIsRefusedInEveryFormat() {
        requireSshKeygen();
        String[][] cases = {{"ed25519", "OPENSSH"}, {"rsa", "OPENSSH"}, {"rsa", "PEM"}, {"rsa", "PKCS8"}};
        for (String[] c : cases) {
            SshTestKeys.Key key = SshTestKeys.viaSshKeygen(c[0], c[1], PASSPHRASE);
            assertRefused(key.chars(), c[0] + " " + c[1] + " encrypted");
        }
    }

    /**
     * MF-R1 as an executable measurement: {@code loadKeys} alone says nothing. If a later sshj starts failing at load,
     * this test fails and the inspector's "force the private key" step can be revisited with the new fact in hand.
     */
    @Test
    void sshjLoadsAnEncryptedKeyWithoutErrorAndFailsOnlyWhenThePrivateKeyIsAskedFor() throws Exception {
        requireSshKeygen();
        for (String[] c : new String[][] {{"ed25519", "OPENSSH"}, {"rsa", "OPENSSH"}, {"rsa", "PKCS8"}}) {
            SshTestKeys.Key key = SshTestKeys.viaSshKeygen(c[0], c[1], PASSPHRASE);
            try (net.schmizz.sshj.SSHClient ssh = new net.schmizz.sshj.SSHClient()) {
                net.schmizz.sshj.userauth.keyprovider.KeyProvider provider = ssh.loadKeys(key.text(), null, null);

                assertThrows(com.hierynomus.sshj.common.KeyDecryptionFailedException.class, provider::getPrivate,
                        c[0] + " " + c[1]);
            }
        }
        SshTestKeys.Key legacy = SshTestKeys.viaSshKeygen("rsa", "PEM", PASSPHRASE);
        try (net.schmizz.sshj.SSHClient ssh = new net.schmizz.sshj.SSHClient()) {
            net.schmizz.sshj.userauth.keyprovider.KeyProvider provider = ssh.loadKeys(legacy.text(), null, null);

            assertThrows(java.io.IOException.class, provider::getPrivate, "legacy encrypted PEM");
        }
    }

    /** MF-R1: sshj 0.40.0 cannot open a SEC1 {@code EC PRIVATE KEY}, so the transport could never use one either. */
    @Test
    void aSec1EcPemKeyIsRefusedBecauseTheTransportCouldNotOpenIt() {
        requireSshKeygen();
        assertRefused(SshTestKeys.viaSshKeygen("ecdsa", "PEM", "").chars(), "ecdsa PEM");
    }

    /**
     * W1 review C-1: the OpenSSH container's outer public blob is unauthenticated. Key A's blob on key B's private
     * section would report A's fingerprint for B's key, so a replay or a rotation would be judged against the wrong
     * key. The inspector proves the pair (sign with the private half, verify with the public half) and refuses.
     */
    @Test
    void anOpenSshKeyWhosePublicBlobBelongsToAnotherKeyIsRefused() {
        requireSshKeygen();
        for (String type : new String[] {"rsa", "ed25519", "ecdsa"}) {
            SshTestKeys.Key a = SshTestKeys.viaSshKeygen(type, "OPENSSH", "");
            SshTestKeys.Key b = SshTestKeys.viaSshKeygen(type, "OPENSSH", "");
            SshTestKeys.Key spliced = SshTestKeys.spliced(a, b);

            if ("rsa".equals(type)) {
                assertRefused(spliced.chars(), type + " spliced");
            } else {
                // Measured: sshj takes an Ed25519 public key from the private section, and the proof refuses an EC
                // mismatch. Either way the fingerprint of A, the forged outer blob, must never be reported.
                try {
                    assertEquals(b.publicFingerprint(), fingerprintOf(spliced), type + " spliced");
                } catch (CredentialInvalidException refused) {
                    assertEquals(CredentialInvalidException.MESSAGE, refused.getMessage());
                }
            }
            assertEquals(b.publicFingerprint(), fingerprintOf(b), "control: the unspliced key is accepted");
        }
    }

    @Test
    void textThatIsNotAPrivateKeyIsRefused() {
        SshTestKeys.Key real = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
        String firstBodyLine = real.text().lines().skip(1).findFirst().orElseThrow();
        String[] garbage = {"", " ", "k1", "sentinel-key-one", "-----BEGIN OPENSSH PRIVATE KEY-----\nSENTINEL\n"
                + "-----END OPENSSH PRIVATE KEY-----\n", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGNbSENTINEL host",
                real.text().substring(0, real.text().length() / 2), firstBodyLine, "-----BEGIN PRIVATE KEY-----\n"
                + "AAAA\n-----END PRIVATE KEY-----\n"};
        for (String text : garbage) {
            assertRefused(text.toCharArray(), "'" + text.replace("\n", "\\n") + "'");
        }
    }

    /**
     * Past the secret store's own limit a value is refused before a parser reads it. The key itself is VALID and sshj
     * would open it (trailing line ends are ignored: the control below), so only the size guard can refuse this.
     */
    @Test
    void anOversizedValueIsRefusedEvenWhenTheKeyInsideItIsValid() {
        SshTestKeys.Key key = SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair());
        String padding = "\n".repeat(com.solace.sm.dp.secret.EnvelopeSecretStore.MAX_VALUE_BYTES);
        assertEquals(key.publicFingerprint(), fingerprintOf(new SshTestKeys.Key(key.text() + "\n\n", key.publicFingerprint())),
                "control: trailing line ends do not stop sshj opening the key");

        assertRefused((key.text() + padding).toCharArray(), "a valid key and 16 KiB of line ends");
    }

    @Test
    void aNullBufferIsRefused() {
        assertThrows(CredentialInvalidException.class, () -> inspector.publicFingerprint(null));
    }

    /** I6: nothing the parser said survives; the text is fixed, there is no cause, and no key line is in any trace. */
    private void assertRefused(char[] key, String what) {
        String text = new String(key);
        CredentialInvalidException e = assertThrows(CredentialInvalidException.class,
                () -> inspector.publicFingerprint(key), what);
        assertEquals(CredentialInvalidException.MESSAGE, e.getMessage(), what);
        assertNull(e.getCause(), "the parser's cause is dropped: " + what);
        assertEquals(0, e.getSuppressed().length, what);
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        for (String line : text.split("\\R")) {
            if (line.length() >= 8) {
                assertFalse(trace.toString().contains(line), what + ": a key line is in the stack trace");
            }
        }
        for (String line : text.split("\\R")) {
            if (line.length() >= 8 && !line.startsWith("-----")) {
                assertFalse(e.toString().contains(line), what);
            }
        }
    }
}
