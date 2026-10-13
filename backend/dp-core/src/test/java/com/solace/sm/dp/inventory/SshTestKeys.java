package com.solace.sm.dp.inventory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Throwaway SSH key pairs for tests (plan 013 T1.1, MF-R1), built from the JDK alone: no new dependency, no key
 * material in the repository, and the expected fingerprint computed from the public key's SSH wire blob by hand, not
 * by the code under test (so a test that compares the two is not circular).
 *
 * <p>The always-on formats are PKCS#8 PEM, legacy PKCS#1 PEM and (for Ed25519) the {@code openssh-key-v1} container
 * with cipher and KDF {@code none}. Every ENCRYPTED format (the JDK 17 cannot write PBES2 PKCS#8 with a name-to-OID
 * mapping, and an OpenSSH container needs bcrypt) and the OpenSSH container for other key types need
 * {@code ssh-keygen}; {@link #sshKeygenAvailable()} says whether it is on the path, and {@link #viaSshKeygen} builds
 * one in a directory it removes afterwards.
 */
public final class SshTestKeys {

    /** A private key as text, with the SHA256 fingerprint of its PUBLIC half, spelt as {@code ssh-keygen -l} does. */
    public record Key(String text, String publicFingerprint) {
        public char[] chars() {
            return text.toCharArray();
        }

        /** The same key with CRLF line ends. */
        public Key crlf() {
            return new Key(text.replace("\n", "\r\n"), publicFingerprint);
        }

        /** The same key with no trailing newline. */
        public Key withoutTrailingNewline() {
            return new Key(text.stripTrailing(), publicFingerprint);
        }
    }

    private SshTestKeys() {
    }

    // ---- RSA ------------------------------------------------------------------------------------------------

    public static KeyPair rsaPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String rsaFingerprint(KeyPair pair) {
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        putString(blob, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
        putMpint(blob, pub.getPublicExponent());
        putMpint(blob, pub.getModulus());
        return sha256(blob.toByteArray());
    }

    /** {@code -----BEGIN PRIVATE KEY-----}: what the JDK's own encoding is. */
    public static Key rsaPkcs8(KeyPair pair) {
        return new Key(pem("PRIVATE KEY", pair.getPrivate().getEncoded()), rsaFingerprint(pair));
    }

    /** {@code -----BEGIN RSA PRIVATE KEY-----}: the PKCS#1 DER inside the PKCS#8 wrapper (fixed 26-byte header). */
    public static Key rsaPkcs1(KeyPair pair) {
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        if (pkcs8[22] != 0x04 || pkcs8[23] != (byte) 0x82) {
            throw new IllegalStateException("unexpected PKCS#8 layout for a 2048-bit RSA key");
        }
        return new Key(pem("RSA PRIVATE KEY", Arrays.copyOfRange(pkcs8, 26, pkcs8.length)), rsaFingerprint(pair));
    }

    // ---- Ed25519 --------------------------------------------------------------------------------------------

    public static KeyPair ed25519Pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] last32(byte[] encoded) {
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    public static String ed25519Fingerprint(KeyPair pair) {
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        putString(blob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        putString(blob, last32(pair.getPublic().getEncoded()));
        return sha256(blob.toByteArray());
    }

    public static Key ed25519Pkcs8(KeyPair pair) {
        return new Key(pem("PRIVATE KEY", pair.getPrivate().getEncoded()), ed25519Fingerprint(pair));
    }

    /**
     * {@code -----BEGIN OPENSSH PRIVATE KEY-----} with cipher {@code none} and KDF {@code none}, hand-built to
     * PROTOCOL.key's layout: the magic, five header fields, the public blob, then the private section (two equal
     * check integers, the key type, the public key, the 64-byte private key {@code seed || public}, a comment, and
     * padding 1, 2, 3 ... to the 8-byte block).
     */
    public static Key ed25519OpenSsh(KeyPair pair) {
        byte[] seed = last32(pair.getPrivate().getEncoded());
        byte[] pub = last32(pair.getPublic().getEncoded());
        byte[] type = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream publicBlob = new ByteArrayOutputStream();
        putString(publicBlob, type);
        putString(publicBlob, pub);

        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        priv.writeBytes(new byte[] {1, 2, 3, 4, 1, 2, 3, 4});             // checkint, twice
        putString(priv, type);
        putString(priv, pub);
        ByteArrayOutputStream pair64 = new ByteArrayOutputStream();
        pair64.writeBytes(seed);
        pair64.writeBytes(pub);
        putString(priv, pair64.toByteArray());
        putString(priv, "test".getBytes(StandardCharsets.US_ASCII));
        for (int pad = 1; priv.size() % 8 != 0; pad++) {
            priv.write(pad);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        putString(out, "none".getBytes(StandardCharsets.US_ASCII));     // cipher
        putString(out, "none".getBytes(StandardCharsets.US_ASCII));     // kdf
        putString(out, new byte[0]);                                     // kdf options
        putInt(out, 1);                                                  // number of keys
        putString(out, publicBlob.toByteArray());
        putString(out, priv.toByteArray());
        return new Key(pem("OPENSSH PRIVATE KEY", out.toByteArray()), ed25519Fingerprint(pair));
    }

    /**
     * W1 review C-1: an OpenSSH-container key whose unauthenticated OUTER public blob is {@code outerFrom}'s and whose
     * (cipher "none") private section is {@code privateFrom}'s. The two halves of two different keys: the one forgery
     * the container permits, because nothing ties the outer blob to the private section.
     */
    public static Key spliced(Key outerFrom, Key privateFrom) {
        byte[] outer = openSshBody(outerFrom.text());
        byte[] inner = openSshBody(privateFrom.text());
        byte[] header = java.util.Arrays.copyOf(inner, headerLength(inner));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);                                        // magic, cipher, kdf, kdf options, key count
        putString(out, outerPublicBlob(outer));
        putString(out, privateSection(inner));
        return new Key(pem("OPENSSH PRIVATE KEY", out.toByteArray()), outerFrom.publicFingerprint());
    }

    private static byte[] openSshBody(String pem) {
        StringBuilder b64 = new StringBuilder();
        for (String line : pem.split("\\R")) {
            if (!line.startsWith("-----") && !line.isBlank()) {
                b64.append(line.trim());
            }
        }
        return java.util.Base64.getDecoder().decode(b64.toString());
    }

    private static int skipString(byte[] b, int at) {
        int n = ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
        return at + 4 + n;
    }

    private static int headerLength(byte[] b) {
        int at = "openssh-key-v1\0".length();
        at = skipString(b, at);       // cipher
        at = skipString(b, at);       // kdf
        at = skipString(b, at);       // kdf options
        return at + 4;                // number of keys
    }

    private static byte[] outerPublicBlob(byte[] b) {
        int at = headerLength(b);
        return java.util.Arrays.copyOfRange(b, at + 4, skipString(b, at));
    }

    private static byte[] privateSection(byte[] b) {
        int at = skipString(b, headerLength(b));
        return java.util.Arrays.copyOfRange(b, at + 4, skipString(b, at));
    }

    // ---- ssh-keygen (optional) ------------------------------------------------------------------------------

    public static boolean sshKeygenAvailable() {
        try {
            Process p = new ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Generates a key pair with the host's {@code ssh-keygen} in a temporary directory that is removed before this
     * returns. {@code type} is {@code ed25519}, {@code rsa} or {@code ecdsa}; {@code format} is {@code OPENSSH}
     * (the default container), {@code PEM} or {@code PKCS8}; an empty {@code passphrase} writes an unencrypted key.
     * The fingerprint is {@code ssh-keygen -l}'s own reading of the public file.
     */
    public static Key viaSshKeygen(String type, String format, String passphrase) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sm-test-key");
            Path file = dir.resolve("k");
            java.util.List<String> generate = new java.util.ArrayList<>(java.util.List.of("ssh-keygen", "-q", "-t",
                    type, "-N", passphrase, "-C", "test", "-f", file.toString()));
            if (!"OPENSSH".equals(format)) {
                generate.addAll(java.util.List.of("-m", format));     // the OpenSSH container is the default
            }
            run(dir, generate.toArray(String[]::new));
            String fingerprint = run(dir, "ssh-keygen", "-l", "-E", "sha256", "-f", file + ".pub").split("\\s+")[1];
            return new Key(Files.readString(file), fingerprint);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (dir != null) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // a temp directory of throwaway keys; the OS clears it
                }
            }
        }
    }

    private static String run(Path dir, String... command) throws IOException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException(command[0] + " failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return output.strip();
    }

    // ---- encoding -------------------------------------------------------------------------------------------

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(70, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private static String sha256(byte[] blob) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static void putString(ByteArrayOutputStream out, byte[] bytes) {
        putInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void putMpint(ByteArrayOutputStream out, BigInteger n) {
        putString(out, n.toByteArray());     // two's complement, big-endian, a leading 0 for a high bit: SSH's mpint
    }
}
