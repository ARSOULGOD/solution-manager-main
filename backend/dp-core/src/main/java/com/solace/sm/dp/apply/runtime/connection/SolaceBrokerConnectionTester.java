package com.solace.sm.dp.apply.runtime.connection;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.meshbridge.connection.JcsmpBrokerProbe;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code testBeforeSave} for a {@code solace-broker} Connection (plan 013 T3, {@code BE-D66}): opens a JCSMP
 * session with the submitted host/port/vpn/username/password, confirms it connected, and disconnects. No
 * message is ever sent or subscribed to — connecting and tearing down is the whole test.
 *
 * <p>Unlike {@code JcsmpEventPublisher}'s session (long-lived, reconnecting), this one is opened, confirmed,
 * and closed once, synchronously, per call — exactly what {@code BE-D66} describes.
 *
 * <p><b>Every failure is a result, never an exception.</b> An unreachable host, a VPN the broker does not
 * know, a username/password the broker refuses, and a probe that throws are each a failed
 * {@link ConnectionTestResult} carrying the broker's own rejection text; none is a {@code 5xx}.
 *
 * <p>{@code I6}: the password is resolved from the secret store for the single call that needs it and is
 * closed (zeroised) before this class's {@link #test} returns, win or lose. Only the broker's own rejection
 * message — never the password — ever reaches the result.
 */
public final class SolaceBrokerConnectionTester {

    /** What the probe needs to connect. {@code passwordSecretRef} is {@code null} when no password attribute is set. */
    public record Binding(TenantId tenant, String host, int port, String vpn, String username,
            UUID passwordSecretRef) {

        public Binding {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(vpn, "vpn");
        }
    }

    /**
     * What the probe saw. {@code rejectionReason} is {@code null} exactly when {@code connected} is true.
     * {@code reachable} (fix round 1, item 4) is {@code false} only when the probe never got far enough to be
     * accepted or refused by a broker (an address the policy refuses, a name that does not resolve, or a
     * transport-level failure {@link JcsmpBrokerProbe#isTransportFailure} recognizes) — the caller maps that to
     * {@code 503}; anything else (irrelevant when {@code connected} is true) maps to {@code 422}. Public: {@link
     * JcsmpBrokerProbe}, which constructs it, now lives in {@code com.solace.sm.dp.meshbridge.connection}.
     */
    public record Steps(boolean connected, String rejectionReason, boolean reachable) {
        static final Steps NOTHING = new Steps(false, null, true);

        /** The 2-arg shape every call site but the two address-policy refusals used before fix round 1: reachable. */
        public Steps(boolean connected, String rejectionReason) {
            this(connected, rejectionReason, true);
        }
    }

    /** The seam under JCSMP: tests supply a double, production {@link JcsmpBrokerProbe}. Public: see {@link Steps}. */
    @FunctionalInterface
    public interface Probe {
        Steps run(Binding binding);
    }

    private final Probe probe;
    private final Clock clock;

    /**
     * Production: a real JCSMP session, the password opened by reference from the secret store, the submitted
     * host checked by {@code addressPolicy} before anything connects (owner ruling, fix round 1: the same
     * {@code TargetAddressPolicy} Target registration already applies, at the same bar).
     */
    public SolaceBrokerConnectionTester(SecretResolver secrets, TargetAddressPolicy addressPolicy, Clock clock) {
        this(new JcsmpBrokerProbe(secrets, addressPolicy), clock);
    }

    SolaceBrokerConnectionTester(Probe probe, Clock clock) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConnectionTestResult test(Binding binding) {
        Objects.requireNonNull(binding, "binding");
        Steps steps;
        try {
            steps = probe.run(binding);
        } catch (RuntimeException e) {
            // Whatever it was (a classified transport failure, a defect): a result, with no cause kept, because a
            // transport message can quote what the broker said about the credential (I6).
            steps = Steps.NOTHING;
        }
        return new ConnectionTestResult(steps.connected(), steps.connected() ? null : steps.rejectionReason(),
                steps.reachable(), clock.instant());
    }
}
