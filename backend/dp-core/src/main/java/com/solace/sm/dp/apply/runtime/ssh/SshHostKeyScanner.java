package com.solace.sm.dp.apply.runtime.ssh;

import com.hierynomus.sshj.key.KeyAlgorithms;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import java.io.IOException;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * Enumerates a host's SSH host keys with sshj: one connection per key algorithm, each restricted to that algorithm,
 * each aborted by the verifier as soon as the host presents its key - so no authentication is attempted and no
 * credential exists in this class. A server that does not offer an algorithm simply fails that connection, which is
 * how the host's key types are discovered.
 *
 * <p>{@link #fingerprintOf} is the SHA-256 of the key's wire blob, base64 without padding, spelt
 * {@code SHA256:<base64>} - the spelling a pin has and {@code FingerprintVerifier} compares (the test proves the two
 * agree, so a recorded fingerprint is exactly what an operator would pin).
 */
public final class SshHostKeyScanner implements HostKeyScanner {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** In preference order; the transport itself (DefaultConfig) prefers the first the server also supports. */
    private static final List<com.hierynomus.sshj.key.KeyAlgorithms.Factory> ALGORITHMS = List.of(
            KeyAlgorithms.EdDSA25519(), KeyAlgorithms.ECDSASHANistp256(), KeyAlgorithms.ECDSASHANistp384(),
            KeyAlgorithms.ECDSASHANistp521(), KeyAlgorithms.RSASHA512(), KeyAlgorithms.RSASHA256(),
            KeyAlgorithms.SSHRSA());

    public static String fingerprintOf(PublicKey key) {
        try {
            byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            return "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The key type a host key was offered as ({@code ssh-ed25519}, {@code ecdsa-sha2-nistp256}, {@code ssh-rsa}, ...): the
     * name {@code ssh-keygen -l} prints in parentheses in upper case, and the one stable thing about a key. The scan
     * iterates signature algorithms, and an RSA host key answers three of them ({@code rsa-sha2-512}, {@code
     * rsa-sha2-256}, {@code ssh-rsa}) with one and the same key, so the algorithm that happened to see it first
     * would be an arbitrary label.
     */
    static String algorithmOf(PublicKey key) {
        KeyType type = KeyType.fromKey(key);
        return type == KeyType.UNKNOWN ? "unknown" : type.toString();
    }

    /** One connection attempt, to a name or to an address already checked. */
    @FunctionalInterface
    interface Connect {
        void to(SSHClient ssh) throws IOException;
    }

    /**
     * The operator command's scan (plan 010 T7.1): by NAME, from the operator's own machine, where connecting to
     * whatever the operator typed is the status quo and no address policy applies. The data plane's scan is
     * {@link SshHostKeyScanService}, which checks the address first.
     */
    @Override
    public Set<String> scan(String host, int port) {
        Set<String> found = new LinkedHashSet<>();
        for (OfferedHostKey key : scanKeys(ssh -> ssh.connect(host, port))) {
            found.add(key.fingerprint());
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("the host could not be reached or presented no SSH host key");
        }
        return found;
    }

    /**
     * The keys {@code address} offers, connecting to that very address (it carries the registered name, which is what
     * sshj hands the verifier: MF-R4). Possibly empty; the caller decides what an empty scan means.
     */
    List<OfferedHostKey> scanChecked(InetAddress address, int port) {
        return scanKeys(ssh -> ssh.connect(address, port));
    }

    /**
     * The longest a whole scan may take (plan 013 T1.4). A scan runs on the request path, and a peer that answers each of
     * the seven attempts a little at a time would otherwise hold a servlet thread for seven attempt timeouts.
     */
    static final java.time.Duration DEFAULT_BUDGET = java.time.Duration.ofSeconds(10);

    static List<OfferedHostKey> scanKeys(Connect connect) {
        return scanKeys(connect, DEFAULT_BUDGET);
    }

    /**
     * @param budget the whole scan's time: each attempt's connect and read timeout is the smaller of
     *               {@link #CONNECT_TIMEOUT_MILLIS} and what is left of it, no attempt starts once it is spent, and each
     *               attempt is a HARD deadline of that same length (a watchdog closes its socket), so a peer that trickles
     *               bytes inside every read timeout cannot hold the calling thread past it. Name resolution is not
     *               covered (the data plane's scan connects to an address it has already resolved and checked).
     */
    /**
     * The backstop behind sshj's per-READ timeouts (review {@code W3C-3}). {@code ssh.setTimeout} and
     * {@code setConnectTimeout} bound each blocking read, not the attempt: {@code TransportImpl.receiveServerIdent} (sshj
     * 0.40.0, read with javap) reads the peer's identification one byte at a time until a line starts with {@code SSH-},
     * logging any other header line, with a 255 limit per LINE and none on the number of lines, so a peer that sends one
     * byte inside every read timeout resets it forever and never ends the attempt. One daemon thread closes the attempt's
     * socket when the attempt's deadline passes; the blocked read then fails at once.
     */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    private static java.util.concurrent.ScheduledThreadPoolExecutor watchdog() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                runnable -> {
                    Thread thread = new Thread(runnable, "ssh-scan-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static List<OfferedHostKey> scanKeys(Connect connect, java.time.Duration budget) {
        Map<String, OfferedHostKey> found = new LinkedHashMap<>();
        long deadline = System.nanoTime() + budget.toNanos();
        for (Factory.Named<com.hierynomus.sshj.key.KeyAlgorithm> algorithm : ALGORITHMS) {
            long remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis < 1) {
                break;      // the budget is spent: report what was seen
            }
            int attemptMillis = (int) Math.min(CONNECT_TIMEOUT_MILLIS, remainingMillis);
            long attemptStarted = System.nanoTime();
            java.util.concurrent.atomic.AtomicBoolean sawKey = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
            DefaultConfig config = new DefaultConfig();
            config.setKeyAlgorithms(List.of(algorithm));
            java.util.concurrent.ScheduledFuture<?> hardDeadline = null;
            try (SSHClient ssh = new SSHClient(config)) {
                ssh.setConnectTimeout(attemptMillis);
                ssh.setTimeout(attemptMillis);
                hardDeadline = WATCHDOG.schedule(() -> {
                    expired.set(true);
                    closeQuietly(ssh);
                }, attemptMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                ssh.addHostKeyVerifier(new HostKeyVerifier() {
                    @Override
                    public boolean verify(String hostname, int p, PublicKey key) {
                        sawKey.set(true);
                        found.putIfAbsent(fingerprintOf(key), new OfferedHostKey(fingerprintOf(key), algorithmOf(key)));
                        return false;                          // we only wanted to see the key
                    }

                    @Override
                    public List<String> findExistingAlgorithms(String hostname, int p) {
                        return List.of();
                    }
                });
                connect.to(ssh);
            } catch (java.net.ConnectException | java.net.NoRouteToHostException | java.net.SocketTimeoutException
                     | java.net.UnknownHostException | java.net.PortUnreachableException unreachable) {
                break;      // the host cannot be reached at all: six more 5 s timeouts would learn nothing (D-5)
            } catch (IOException | RuntimeException expected) {
                if (expired.get() && !sawKey.get()) {
                    // the watchdog ended it: a peer that outlasts the attempt's deadline is not refusing, it is stalling
                    break;
                }
                // The verifier refuses on purpose, and a host that does not offer this algorithm refuses too. But a
                // peer that took the whole attempt timeout to refuse is not refusing: it is silent (it accepted the
                // connection and sent no banner), and it will be silent to the other six attempts too.
                if (!sawKey.get()
                        && (timedOut(expected) || attemptTookItsWholeTimeout(attemptStarted, attemptMillis))) {
                    break;
                }
            } finally {
                if (hardDeadline != null) {
                    hardDeadline.cancel(false);
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Closes the attempt's socket from the watchdog thread: that, not {@code SSHClient.close()}, unblocks a pending read. */
    private static void closeQuietly(SSHClient ssh) {
        try {
            java.net.Socket socket = ssh.getSocket();
            if (socket != null) {
                socket.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // the attempt is over either way
        }
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean attemptTookItsWholeTimeout(long startedNanos, int attemptMillis) {
        long tookMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return tookMillis >= attemptMillis - attemptMillis / 10;
    }
}
