package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code testTargetConnection} over SSH (plan 013 T1.5; D-REG section 5.1 T6, OQ-9 (a)): the stored record is read, an SSH
 * probe connects with the pinned host key, authenticates with the stored key and runs the one fixed
 * {@code docker version} command, and what it saw becomes a {@link TargetConnectionTestResult}.
 *
 * <p><b>Every failure is a result.</b> An address the policy refuses, an unreachable host, a host that presents another
 * key than the pin, a key the host refuses, a stopped daemon, a user outside the {@code docker} group and a probe that
 * throws are each a result with the steps they did not reach set false; none is an exception, so the route never answers
 * {@code 5xx} for a host being down. Only a target the data plane has no live record for is an exception (it is a
 * {@code 404}).
 *
 * <p>{@code I5}: the record is looked up for the caller's organization, and the credential for the record's own. {@code I6}:
 * the probe returns two flags, an exit status and the first line of the command's stdout; the key, its reference and any
 * exception text stay below this class.
 */
public final class SshTargetConnectionTester implements TargetConnectionTester {

    /** What the probe needs to connect: the stored record's connection fields, for one tenant. */
    record Binding(TenantId tenant, String host, int port, String sshUser, UUID credentialRef,
            String pinnedHostKeyFingerprint, HostKeyType pinnedHostKeyType) {

        /** A binding whose pinned key's type is not known: every binding before OR-125 (2). */
        Binding(TenantId tenant, String host, int port, String sshUser, UUID credentialRef,
                String pinnedHostKeyFingerprint) {
            this(tenant, host, port, sshUser, credentialRef, pinnedHostKeyFingerprint, null);
        }
    }

    /**
     * What the probe saw. The steps are cumulative: a probe that did not authenticate reports no Docker exit.
     *
     * @param dockerExit   the exit status of the one command, or {@code null} when it did not run or did not finish
     * @param dockerOutput the command's stdout (bounded by the probe), or {@code null}
     */
    record Steps(boolean hostKeyPinned, boolean authenticated, Integer dockerExit, String dockerOutput) {

        static final Steps NOTHING = new Steps(false, false, null, null);
    }

    /** The seam under the sshj code: tests supply a double, production {@link SshjTargetSshProbe}. */
    @FunctionalInterface
    interface Probe {
        Steps run(Binding binding);
    }

    private final TargetConnectionRows connections;
    private final Probe probe;
    private final Clock clock;

    /** Production: sshj, the address policy applied at the moment of connecting, the key opened by reference. */
    public SshTargetConnectionTester(TargetConnectionRows connections, SshCredentialSource credentials,
            TargetAddressPolicy addressPolicy, Clock clock) {
        this(connections, new SshjTargetSshProbe(credentials, addressPolicy, HostKeyTypeRecorder.over(connections)),
                clock);
    }

    SshTargetConnectionTester(TargetConnectionRows connections, Probe probe, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TargetConnectionTestResult test(UUID organizationId, UUID targetId) {
        TargetConnectionRecord record = connections.find(Objects.requireNonNull(organizationId, "organizationId"),
                Objects.requireNonNull(targetId, "targetId")).filter(c -> c.retiredAt() == null)
                .orElseThrow(() -> new NoSuchElementException("the target has no live connection record"));
        if (record.isAgent()) {
            // plan 015 T3.5: an agent target has no address to connect to; no probe, no connector, no budget spent
            throw new com.solace.sm.dp.api.inventory.TargetTransportUnsupportedException();
        }
        Steps steps;
        try {
            steps = probe.run(new Binding(new TenantId(organizationId), record.host(), record.port(), record.sshUser(),
                    record.credentialRef(), record.hostKeyFingerprint(), record.hostKeyType()));
        } catch (RuntimeException e) {
            // Whatever it was (a policy refusal, a classified transport failure, a defect): a result, with no cause kept,
            // because a transport message can quote what the host said (I6).
            steps = Steps.NOTHING;
        }
        String version = version(steps);
        boolean pinned = steps.hostKeyPinned();
        boolean authenticated = pinned && steps.authenticated();
        boolean docker = authenticated && version != null;
        return new TargetConnectionTestResult(pinned, authenticated, docker, docker ? version : null, clock.instant());
    }

    /** The first line of a successful {@code docker version}, if it is a plain version string; else {@code null}. */
    private static String version(Steps steps) {
        if (!steps.authenticated() || !Integer.valueOf(0).equals(steps.dockerExit()) || steps.dockerOutput() == null) {
            return null;
        }
        String firstLine = steps.dockerOutput().strip().lines().findFirst().orElse("").strip();
        return TargetConnectionTestResult.isVersion(firstLine) ? firstLine : null;
    }
}
