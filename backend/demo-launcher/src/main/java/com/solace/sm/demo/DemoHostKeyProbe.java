package com.solace.sm.demo;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.springframework.stereotype.Service;

/**
 * One unauthenticated-transport setup handshake. It obtains a host key but never sends a
 * credential, opens a session, or executes a remote command. The returned key must still be
 * explicitly pinned in the later instance declaration.
 */
@Service
final class DemoHostKeyProbe {
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;

    HostKeyFingerprint probe(String host, int port) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || port < 1 || port > 65_535) {
            throw new DemoBadRequestException("Enter a valid SSH host and port.");
        }
        AtomicReference<PublicKey> presented = new AtomicReference<>();
        try (SSHClient ssh = new SSHClient()) {
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            ssh.setTimeout(OPERATION_TIMEOUT_MILLIS);
            ssh.addHostKeyVerifier(new HostKeyVerifier() {
                @Override public boolean verify(String ignoredHost, int ignoredPort, PublicKey key) {
                    presented.set(key);
                    return true;
                }

                @Override public List<String> findExistingAlgorithms(String ignoredHost, int ignoredPort) {
                    return List.of();
                }
            });
            ssh.connect(host, port);
            PublicKey key = presented.get();
            if (key == null) {
                throw new DemoUnavailableException();
            }
            return new HostKeyFingerprint(fingerprint(key));
        } catch (IOException unavailable) {
            throw new DemoUnavailableException();
        }
    }

    /** Matches SSHJ's FingerprintVerifier SHA-256 input: the SSH wire public-key bytes. */
    static String fingerprint(PublicKey key) {
        try {
            byte[] wireKey = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wireKey);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    record HostKeyFingerprint(String fingerprint) {}
}
