package com.solace.sm.dp.apply.runtime.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A real OpenSSH {@code sshd} in a container, for the registration API's real-SSH scenarios (plan 013 T0.2, OQ-15 (a);
 * the image is approved by {@code OR-124} for exactly this use). <b>Test sources only</b>: never shipped, never on a runtime
 * path, no Maven dependency.
 *
 * <p><b>The image.</b> {@code quay.io/panubo/sshd:1.10.0}, pinned by manifest digest (a different tag or digest needs a
 * new approval). Its facts, read at tag {@code v1.10.0} ({@code I2}): host keys live in {@code /etc/ssh/keys} and are
 * generated on first start by {@code ssh-keygen -A} when none exist (so the host offers an ed25519, an ecdsa and an rsa
 * key); users come from {@code SSH_USERS=name:uid:gid[:shell]}; a user's public keys are the file
 * {@code /etc/authorized_keys/<user>}; sshd listens on 22 inside the container.
 *
 * <p><b>Not verified without Docker (owed):</b> that the image's sshd re-reads {@code /etc/authorized_keys/<user>} on every
 * authentication (so a key added or removed at run time takes effect without a restart, which scenario S3 needs). The README
 * is silent; {@code SshdFixtureIT#aRemovedKeyIsRefusedAtOnce} is the measurement.
 *
 * <p>Docker is NOT in this image, so every check that needs a Docker daemon over SSH ({@code docker version}, the next apply
 * on a new host) stays a live gate on the E2E host (OQ-15 (a)).
 */
public final class SshdFixture implements AutoCloseable {

    /** The approved image, by tag AND digest ({@code OR-124}). */
    public static final String IMAGE = "quay.io/panubo/sshd:1.10.0"
            + "@sha256:6d54f749fe1817628528ad75a88ab368bb6b9b5a7ad0fe08e299560606a20672";

    /** The account the tests authenticate as. */
    public static final String USER = "sm";

    private static final String AUTHORIZED_KEYS = "/etc/authorized_keys/" + USER;
    private static final String[] HOST_KEY_TYPES = {"ed25519", "ecdsa", "rsa"};

    private final GenericContainer<?> container;

    private SshdFixture(GenericContainer<?> container) {
        this.container = container;
    }

    /** Starts the container with {@code authorizedKeys} (public-key lines) installed for {@link #USER}. */
    public static SshdFixture start(String... authorizedKeys) {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(22)
                .withEnv("SSH_USERS", USER + ":1000:1000")
                .withCopyToContainer(Transferable.of(join(authorizedKeys), 0644), AUTHORIZED_KEYS)
                .waitingFor(Wait.forListeningPort());
        container.start();
        return new SshdFixture(container);
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return container.getMappedPort(22);
    }

    /** Runs {@code argv} as root inside the container and returns its trimmed stdout (a test arrangement or observation). */
    public String exec(String... argv) {
        try {
            var result = container.execInContainer(argv);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("exec failed: " + String.join(" ", argv) + ": " + result.getStderr());
            }
            return result.getStdout().trim();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("cannot exec in the container", e);
        }
    }

    /** Replaces the user's authorized keys while the daemon runs (scenario S3: add the new key, remove the old). */
    public void setAuthorizedKeys(String... publicKeyLines) {
        container.copyFileToContainer(Transferable.of(join(publicKeyLines), 0644), AUTHORIZED_KEYS);
    }

    /**
     * The host's own keys, by type ({@code ed25519}, {@code ecdsa}, {@code rsa}), as {@code SHA256:<base64>} fingerprints
     * computed from the {@code .pub} files the daemon generated, by the SSH wire blob and not by the code under test.
     */
    public Map<String, String> hostKeyFingerprints() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String type : HOST_KEY_TYPES) {
            try {
                String line = container.execInContainer("cat", "/etc/ssh/keys/ssh_host_" + type + "_key.pub").getStdout();
                out.put(type, fingerprintOfPublicLine(line));
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("cannot read the host's " + type + " key", e);
            }
        }
        return out;
    }

    /** {@code SHA256:<base64, no padding>} of the blob in {@code <type> <base64> [comment]}, as {@code ssh-keygen -l} prints it. */
    public static String fingerprintOfPublicLine(String line) {
        String[] fields = line.trim().split("\\s+");
        byte[] blob = Base64.getDecoder().decode(fields[1]);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authorized_keys line of an Ed25519 pair (the JDK's {@code KeyPairGenerator}); the private half is the caller's. */
    public static String authorizedKeyLine(KeyPair ed25519) {
        byte[] publicBytes = ed25519.getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(publicBytes, publicBytes.length - 32, publicBytes.length);
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        putString(blob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        putString(blob, raw);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray()) + " sm-it";
    }

    private static void putString(ByteArrayOutputStream out, byte[] value) {
        int length = value.length;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(value, 0, value.length);
    }

    private static String join(String[] lines) {
        return String.join("\n", lines) + "\n";
    }

    @Override
    public void close() {
        container.stop();
    }
}
