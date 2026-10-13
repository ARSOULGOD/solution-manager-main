package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.SshTargetConnectionTester.Binding;
import com.solace.sm.dp.apply.runtime.ssh.SshTargetConnectionTester.Steps;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * The sshj half of {@code testTargetConnection} (plan 013 T1.5, OQ-9 (a)): connect to the address the policy accepted with
 * the pinned host key, authenticate with the stored key, and run the ONE fixed command {@link #DOCKER_VERSION_COMMAND}.
 *
 * <p>It runs exactly that string: the class has no method that takes a command, a path, a container name or anything else
 * from a caller, so nothing the browser sent can reach the shell ({@code SM-D11a P}). The command's stderr is never read
 * (it is where Docker says "Cannot connect to the Docker daemon" and where a shell quotes paths), and only the first
 * {@link #MAX_OUTPUT_BYTES} of stdout are kept.
 *
 * <p>One watchdog closes the whole connection at {@link #TOTAL_DEADLINE_MILLIS}: sshj's channel streams have no read
 * timeout (W2-03), so a command that never answers would otherwise hold the request thread. A deadline that fires is a
 * probe that did not finish, which the tester reports as a step not reached.
 */
final class SshjTargetSshProbe implements SshTargetConnectionTester.Probe {

    /** The only command this class runs. Docker's Go template, quoted for the remote shell; no input is spliced in. */
    static final String DOCKER_VERSION_COMMAND = "docker version --format '{{.Server.Version}}'";

    /** A version is a few characters; anything past this is not one and is not kept. */
    static final int MAX_OUTPUT_BYTES = 512;

    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;
    static final long TOTAL_DEADLINE_MILLIS = 25_000;

    private final SshCredentialSource credentials;
    private final TargetAddressPolicy addressPolicy;
    private final HostKeyTypeRecorder recorder;

    SshjTargetSshProbe(SshCredentialSource credentials, TargetAddressPolicy addressPolicy) {
        this(credentials, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    SshjTargetSshProbe(SshCredentialSource credentials, TargetAddressPolicy addressPolicy,
            HostKeyTypeRecorder recorder) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public Steps run(Binding binding) {
        // The watchdog closes whichever attempt is current (the pin may take up to three connections: W4C-1) and tells the
        // connector to start no more.
        AtomicReference<SSHClient> current = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = SshjHostConnector.WATCHDOGS.schedule(() -> {
            expired.set(true);
            closeQuietly(current.get());
        }, TOTAL_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        SSHClient ssh = null;
        try {
            java.net.InetAddress address;
            try {
                address = SshjDockerTransport.checkedAddress(addressPolicy, binding.host());
            } catch (RuntimeAdapterException refusedOrUnresolvable) {
                return Steps.NOTHING;                       // the policy's refusal, or a name that does not resolve
            }
            try {
                ssh = PinnedHostKeyConnect.connect(binding.pinnedHostKeyFingerprint(), binding.pinnedHostKeyType(),
                        CONNECT_TIMEOUT_MILLIS, OPERATION_TIMEOUT_MILLIS, config -> { },
                        c -> c.connect(address, binding.port()), current::set, expired::get,
                        type -> recorder.learned(binding.tenant(), binding.pinnedHostKeyFingerprint(), type));
            } catch (IOException e) {
                // Unreachable, or no host key of the host was the pin (sshj's HOST_KEY_NOT_VERIFIABLE for every key type
                // the host offers): no step passed, and the two are not told apart because the result does not (a
                // mismatch must not confirm a host is there).
                return Steps.NOTHING;
            }
            // The verifier ran during the key exchange inside connect(): reaching here means the pin matched.
            try {
                ssh.authPublickey(binding.sshUser(),
                        SshjDockerTransport.loadKey(ssh, credentials, binding.tenant(), binding.credentialRef()));
            } catch (RuntimeAdapterException unusableKey) {
                return new Steps(true, false, null, null);
            } catch (IOException refused) {
                return new Steps(true, false, null, null);
            }
            return docker(ssh);
        } finally {
            watchdog.cancel(false);
            closeQuietly(ssh);
            closeQuietly(current.get());
        }
    }

    private static Steps docker(SSHClient ssh) {
        try (Session session = ssh.startSession(); Session.Command command = session.exec(DOCKER_VERSION_COMMAND)) {
            byte[] output = command.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
            command.join();
            Integer exit = command.getExitStatus();
            return new Steps(true, true, exit, new String(output, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Authenticated, but the command did not run to an exit status (a closed channel, the deadline): Docker is
            // not shown to be available.
            return new Steps(true, true, null, null);
        }
    }

    private static void closeQuietly(SSHClient ssh) {
        if (ssh == null) {
            return;
        }
        try {
            ssh.close();
        } catch (IOException | RuntimeException ignored) {
            // closing a connection that is already gone
        }
    }
}
