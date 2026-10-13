package com.solace.sm.dp.apply.runtime.ssh;

import com.hierynomus.sshj.key.KeyAlgorithm;
import com.hierynomus.sshj.key.KeyAlgorithms;
import com.solace.sm.dp.api.inventory.HostKeyType;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * THE way every sshj connector connects to a host whose key is PINNED (review {@code W4C-1}): the per-operation transport
 * ({@code SshjDockerTransport}), the pooled health connector ({@code SshjHostConnector}) and the connection test's probe
 * ({@code SshjTargetSshProbe}).
 *
 * <p><b>The defect this closes.</b> sshj (0.40.0, read with {@code javap}) lets the CLIENT's order decide which host-key
 * type a connection uses: {@code DefaultConfig} lists Ed25519, then ECDSA, then RSA, {@code Proposal.negotiate} takes the
 * first entry of the client's list that the server also offers, and {@code FingerprintVerifier} reports no known
 * algorithms ({@code findExistingAlgorithms} is empty), so nothing moves the pinned key's type forward. A pin to any key
 * but the one type sshj prefers among the host's keys therefore never verifies, although registration accepts any key the
 * host offers (it scans all of them). Not a hole (the connection fails closed), but every apply, health probe and log read
 * of such a target fails as {@code ssh.connection.failed}, and the connection test reports the host key as not pinned,
 * which reads like a man-in-the-middle on a genuine host.
 *
 * <p><b>The fix (OR-125 (2)).</b> The pin's key type is persisted beside the fingerprint
 * ({@code target_connection.host_key_algorithm}, {@code sm_data} V027), taken at registration and at a re-pin from the data
 * plane's own fresh scan ({@code OfferedHostKey.algorithm}). When it is known, every connector negotiates THAT type directly:
 * one attempt, the pinned type first in the client's list. A fingerprint is a hash of one key and so belongs to one type for
 * good, so there is nothing to try next: a host that does not present that key fails verification and the connection fails
 * closed, as a mismatch should.
 *
 * <p><b>While the type is not known</b> (a row registered before V027, or by the operator command, which scans fingerprints
 * only) the connector falls back to discovery, as the interim fix did: it connects with ONE key FAMILY preferred at a time
 * (Ed25519, then ECDSA, then RSA, the order {@link SshHostKeyScanner} also scans in) until the pinned fingerprint verifies. A
 * host that does not offer the family presents another of its keys, which fails host-key verification (or key exchange);
 * both mean "try the next". Anything else (an unreachable host, a refused connection, a timeout, the caller's abandon signal)
 * stops at once. The family that verified is remembered in memory per fingerprint, tried first next time, and the other two
 * are still tried if it fails. At most three connection attempts, usually one. <b>On the first success the exact key type
 * that matched the pin is reported to {@code learned}</b> so the caller can persist it, and every later connection is a
 * single attempt. A SHA-256 fingerprint cannot be inverted to a type, so no migration can back-fill a row without asking the
 * host; this is the scan-free rule.
 *
 * <p>No credential is involved: nothing here authenticates. The caller authenticates on the client this returns.
 */
final class PinnedHostKeyConnect {

    /** One connection attempt to an address the caller has already resolved and checked. */
    @FunctionalInterface
    interface Connect {
        void to(SSHClient ssh) throws IOException;
    }

    /** A host-key type family: the signature algorithms sshj may negotiate for keys of one type. */
    enum KeyFamily {
        ED25519(List.of(KeyAlgorithms.EdDSA25519())),
        ECDSA(List.of(KeyAlgorithms.ECDSASHANistp256(), KeyAlgorithms.ECDSASHANistp384(),
                KeyAlgorithms.ECDSASHANistp521())),
        /** One RSA host key answers all three signature names; the server picks, the key is one and the same. */
        RSA(List.of(KeyAlgorithms.RSASHA512(), KeyAlgorithms.RSASHA256(), KeyAlgorithms.SSHRSA()));

        private final List<Factory.Named<KeyAlgorithm>> algorithms;

        KeyFamily(List<? extends Factory.Named<KeyAlgorithm>> algorithms) {
            this.algorithms = List.copyOf(algorithms);
        }

        List<Factory.Named<KeyAlgorithm>> algorithms() {
            return algorithms;
        }
    }

    /** The signature algorithms that present a host key of exactly this type, in sshj's preference among them. */
    static List<Factory.Named<KeyAlgorithm>> algorithmsOf(HostKeyType type) {
        return switch (type) {
            case ED25519 -> KeyFamily.ED25519.algorithms();
            case ECDSA_NISTP256 -> List.of(KeyAlgorithms.ECDSASHANistp256());
            case ECDSA_NISTP384 -> List.of(KeyAlgorithms.ECDSASHANistp384());
            case ECDSA_NISTP521 -> List.of(KeyAlgorithms.ECDSASHANistp521());
            case RSA -> KeyFamily.RSA.algorithms();
        };
    }

    /** Fingerprints are few (one per registered target) but untrusted in count; the least recently used are forgotten. */
    private static final int REMEMBERED = 1024;

    private static final Map<String, KeyFamily> LEARNED = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, KeyFamily> eldest) {
                    return size() > REMEMBERED;
                }
            });

    private PinnedHostKeyConnect() {
    }

    /** The families to try for {@code pin}, in order: the remembered one first, then the others, Ed25519 first. */
    static List<KeyFamily> attemptOrder(String pin) {
        List<KeyFamily> order = new ArrayList<>(List.of(KeyFamily.values()));
        KeyFamily learned = LEARNED.get(pin);
        if (learned != null) {
            order.remove(learned);
            order.add(0, learned);
        }
        return order;
    }

    /** Forgets what was learned (tests only). */
    static void forgetAll() {
        LEARNED.clear();
    }

    /**
     * Whether a failed attempt says "this key family is not it" (so the next may be): the host offers no key of the
     * family (key exchange cannot settle) or offers one that is not the pin (host-key verification refused it). Nothing
     * else is: a connection that could not be made, a timeout and a closed socket would fail the next family the same way.
     */
    static boolean tryNextFamily(IOException failure) {
        if (!(failure instanceof TransportException transport)) {
            return false;
        }
        DisconnectReason reason = transport.getDisconnectReason();
        return reason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE || reason == DisconnectReason.KEY_EXCHANGE_FAILED;
    }

    /**
     * Verifies the pin exactly as {@code FingerprintVerifier} does, and remembers the TYPE of the key that matched: the
     * exact type (not just the family) is what gets persisted, so a later connection can lead with it alone. Reports
     * nothing for a key that is refused or whose type the data plane does not pin by.
     */
    static final class CapturingVerifier implements HostKeyVerifier {
        private final HostKeyVerifier delegate;
        private volatile HostKeyType seen;

        CapturingVerifier(HostKeyVerifier delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /** The type of the key that matched the pin, or empty if none has. */
        Optional<HostKeyType> seen() {
            return Optional.ofNullable(seen);
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            boolean matched = delegate.verify(hostname, port, key);
            if (matched) {
                seen = HostKeyType.ofWire(SshHostKeyScanner.algorithmOf(key)).orElse(null);
            }
            return matched;
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return delegate.findExistingAlgorithms(hostname, port);
        }
    }

    /** One way of leading the negotiation: a family under discovery (remembered on success), or one known type. */
    private record Attempt(KeyFamily family, List<Factory.Named<KeyAlgorithm>> lead) {
    }

    /** As the full form, for a caller with nothing to configure and nothing that can abandon the attempt. */
    static SSHClient connect(String pin, int connectTimeoutMillis, int operationTimeoutMillis, Connect connect)
            throws IOException {
        return connect(pin, connectTimeoutMillis, operationTimeoutMillis, config -> { }, connect, ssh -> { },
                () -> false);
    }

    /** As the full form, for a pin whose type is not known and nobody wants to learn it (tests, and the interim callers). */
    static SSHClient connect(String pin, int connectTimeoutMillis, int operationTimeoutMillis,
            Consumer<DefaultConfig> configure, Connect connect, Consumer<SSHClient> onClient, BooleanSupplier abandoned)
            throws IOException {
        return connect(pin, null, connectTimeoutMillis, operationTimeoutMillis, configure, connect, onClient, abandoned,
                learned -> { });
    }

    /**
     * Connects to the host with the pinned key's type preferred in the negotiation, and returns the connected,
     * host-key-verified client (the caller authenticates on it and closes it).
     *
     * @param pinnedType the pinned key's recorded type, or {@code null} while it is not known: a known type is connected
     *                   with directly, in ONE attempt; an unknown one is discovered, family by family
     * @param configure  shapes each attempt's {@link DefaultConfig} BEFORE the key algorithms are set (a keep-alive provider)
     * @param onClient   told of each client right after it is created, before it connects, so a watchdog can close it
     * @param abandoned  true once the caller's deadline has passed: no further family is tried
     * @param learned    told, on a success of DISCOVERY only, the exact type of the key that matched the pin, so the caller
     *                   can record it; never called when {@code pinnedType} was known, and never allowed to fail the
     *                   connection
     * @throws IOException the last attempt's failure: a connection failure at once, or, when no family verified, the
     *                     host-key verification or key-exchange failure (the cause is the caller's to drop: it can quote the pin)
     */
    static SSHClient connect(String pin, HostKeyType pinnedType, int connectTimeoutMillis, int operationTimeoutMillis,
            Consumer<DefaultConfig> configure, Connect connect, Consumer<SSHClient> onClient, BooleanSupplier abandoned,
            Consumer<HostKeyType> learned) throws IOException {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(learned, "learned");
        List<Attempt> attempts = pinnedType != null
                ? List.of(new Attempt(null, algorithmsOf(pinnedType)))
                : attemptOrder(pin).stream().map(family -> new Attempt(family, family.algorithms())).toList();
        IOException last = null;
        for (Attempt attempt : attempts) {
            if (abandoned.getAsBoolean()) {
                break;
            }
            DefaultConfig config = new DefaultConfig();
            configure.accept(config);
            config.setKeyAlgorithms(preferring(attempt.lead(), config.getKeyAlgorithms()));
            SSHClient ssh = new SSHClient(config);
            boolean connected = false;
            try {
                onClient.accept(ssh);
                if (abandoned.getAsBoolean()) {
                    break;                  // the deadline passed while this client was being made: it is closed below
                }
                ssh.setConnectTimeout(connectTimeoutMillis);
                ssh.setTimeout(operationTimeoutMillis);
                CapturingVerifier verifier = new CapturingVerifier(FingerprintVerifier.getInstance(pin));
                ssh.addHostKeyVerifier(verifier);
                connect.to(ssh);
                connected = true;
                if (attempt.family() != null) {
                    LEARNED.put(pin, attempt.family());
                    verifier.seen().ifPresent(type -> report(learned, type));
                }
                return ssh;
            } catch (IOException e) {
                last = e;
                if (!tryNextFamily(e)) {
                    throw e;
                }
            } finally {
                if (!connected) {
                    closeQuietly(ssh);
                }
            }
        }
        throw last != null ? last : new IOException("the connection attempt was abandoned");
    }

    /** Recording a learned type is a convenience of the next connection; it must never fail this one. */
    private static void report(Consumer<HostKeyType> learned, HostKeyType type) {
        try {
            learned.accept(type);
        } catch (RuntimeException ignored) {
            // the type will be learned again by the next discovery
        }
    }

    /**
     * The family's algorithms FIRST, then every other algorithm sshj would offer, not a list restricted to the family.
     * sshj takes the first entry of the CLIENT's list that the server also offers, so the family leads; but the same list is
     * what sshj looks a signer up in when it authenticates with the user's key (measured: a connection restricted to ECDSA
     * could not sign with an Ed25519 user key, and authentication failed), so the user's key type must stay in it. A host
     * that does not offer the family falls through to another key, which fails verification, which tries the next family.
     */
    static List<Factory.Named<KeyAlgorithm>> preferring(KeyFamily family, List<Factory.Named<KeyAlgorithm>> defaults) {
        return preferring(family.algorithms(), defaults);
    }

    /** As above, for an arbitrary lead: one family, or the algorithms of one known key type. */
    static List<Factory.Named<KeyAlgorithm>> preferring(List<Factory.Named<KeyAlgorithm>> lead,
            List<Factory.Named<KeyAlgorithm>> defaults) {
        List<Factory.Named<KeyAlgorithm>> ordered = new ArrayList<>(lead);
        for (Factory.Named<KeyAlgorithm> other : defaults) {
            if (ordered.stream().noneMatch(a -> a.getName().equals(other.getName()))) {
                ordered.add(other);
            }
        }
        return ordered;
    }

    private static void closeQuietly(SSHClient ssh) {
        try {
            ssh.close();
        } catch (IOException | RuntimeException ignored) {
            // an attempt that did not connect, or a connection already gone; the attempt's own failure stands
        }
    }
}
