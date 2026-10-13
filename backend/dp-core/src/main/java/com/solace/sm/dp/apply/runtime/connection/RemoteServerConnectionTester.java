package com.solace.sm.dp.apply.runtime.connection;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code testBeforeSave} for a {@code remote-server} Connection (plan 013 T3, {@code BE-D66}): connects over SSH
 * and authenticates — {@code basic} (password) or {@code private-key} (optionally passphrase-protected), per
 * {@code remote-server.json}'s {@code authMethod} discriminator — then disconnects. No command is ever run.
 *
 * <p>⛔ <b>Deliberately no host-key pinning or scanning.</b> That is exclusive to Target registration
 * (`BE-D54`/`BE-D55`): a Connection is a config value object, never a deployment-trust anchor. This class accepts
 * whatever host key the peer offers — the same trust posture an {@code ssh} client without
 * {@code StrictHostKeyChecking} has.
 *
 * <p><b>Every failure is a result, never an exception.</b> An unreachable host, a host key exchange failure, a
 * password or key the host refuses, and a probe that throws are each a failed {@link ConnectionTestResult}
 * carrying the host's own rejection text; none is a {@code 5xx}.
 *
 * <p>{@code I6}: the password/private key/passphrase are each resolved from the secret store for the single call
 * that needs them and are closed (zeroised) before this class's {@link #test} returns, win or lose.
 */
public final class RemoteServerConnectionTester {

    /** {@code remote-server.json}'s {@code authMethod} discriminator values. */
    public enum AuthMethod {
        BASIC, PRIVATE_KEY
    }

    /**
     * What the probe needs to connect. For {@link AuthMethod#BASIC}, {@code passwordSecretRef} may be {@code null}
     * (the attribute is not required). For {@link AuthMethod#PRIVATE_KEY}, {@code privateKeySecretRef} is always
     * present (the attribute is required) and {@code passphraseSecretRef} is {@code null} when the key is
     * unencrypted.
     */
    public record Binding(TenantId tenant, String host, int port, String username, AuthMethod authMethod,
            UUID passwordSecretRef, UUID privateKeySecretRef, UUID passphraseSecretRef) {

        public Binding {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(authMethod, "authMethod");
            if (authMethod == AuthMethod.PRIVATE_KEY) {
                Objects.requireNonNull(privateKeySecretRef, "privateKeySecretRef");
            }
        }
    }

    /** What the probe saw. {@code rejectionReason} is {@code null} exactly when both steps are true. */
    record Steps(boolean connected, boolean authenticated, String rejectionReason) {
        static final Steps NOTHING = new Steps(false, false, null);
    }

    /** The seam under sshj: tests supply a double, production {@link SshjConnectionAuthProbe}. */
    @FunctionalInterface
    interface Probe {
        Steps run(Binding binding);
    }

    private final Probe probe;
    private final Clock clock;

    /**
     * Production: sshj, accepting whatever host key the peer offers, the credential opened by reference, the
     * submitted host checked by {@code addressPolicy} before anything connects (owner ruling, fix round 1: the
     * same {@code TargetAddressPolicy} Target registration already applies, at the same bar).
     */
    public RemoteServerConnectionTester(SecretResolver secrets, TargetAddressPolicy addressPolicy, Clock clock) {
        this(new SshjConnectionAuthProbe(secrets, addressPolicy), clock);
    }

    RemoteServerConnectionTester(Probe probe, Clock clock) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConnectionTestResult test(Binding binding) {
        Objects.requireNonNull(binding, "binding");
        Steps steps;
        boolean defect = false;
        try {
            steps = probe.run(binding);
        } catch (RuntimeException e) {
            // Whatever it was: a result, with no cause kept, because a transport message can quote what the host
            // said about the credential (I6). Treated as reachable/422 below, not unreachable/503 (fix round 1,
            // item 4): an unclassified defect is not evidence the host could not be reached.
            steps = Steps.NOTHING;
            defect = true;
        }
        boolean success = steps.connected() && steps.authenticated();
        // Fix round 1, item 4: sshj's connect-then-authenticate already gives the two-step signal the contract's
        // 503-vs-422 split needs — unlike JCSMP (see JcsmpBrokerProbe), no heuristic is needed here. A transport
        // failure never got past connect(); a credential or auth rejection happens only once connected is true.
        boolean reachable = success || steps.connected() || defect;
        return new ConnectionTestResult(success, success ? null : steps.rejectionReason(), reachable,
                clock.instant());
    }
}
