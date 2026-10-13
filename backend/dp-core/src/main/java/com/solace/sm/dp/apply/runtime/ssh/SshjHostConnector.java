package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * Opens a long-lived sshj connection for the session pool (plan 010 T7.1): the SAME trust as the per-operation
 * transport (the placement's pinned host-key fingerprint; the private key resolved by reference, for one use, from
 * memory), plus transport keep-alive so a connection that dies between polls is noticed.
 *
 * <p>M8 (sshj 0.40.0, source at tag {@code v0.40.0} and {@code javap} of the jar): {@code ConnectionImpl} keeps its
 * channels in a {@code ConcurrentHashMap} and imposes no channel count, so several {@code Session}s may be opened
 * on one connected {@code SSHClient} (the pool bounds them per session); the {@code KeepAliveProvider.KEEP_ALIVE}
 * thread sends a keep-alive every {@code keepAliveInterval} seconds and, when sending throws, calls
 * {@code transport.die(e)} - after which {@link SSHClient#isConnected()} is false.
 */
final class SshjHostConnector implements HostConnector {

    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;

    /**
     * The deadline watchdogs (W2-03). A few threads, because closing a channel may itself wait on a wedged peer for the
     * operation timeout, and one stuck close must not delay the next target's watchdog.
     */
    static final java.util.concurrent.ScheduledThreadPoolExecutor WATCHDOGS = watchdogs();

    private static java.util.concurrent.ScheduledThreadPoolExecutor watchdogs() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(4,
                task -> {
                    Thread t = new Thread(task, "sm-ssh-watchdog-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final SshCredentialSource credentials;
    private final Duration keepAliveInterval;
    private final TargetAddressPolicy addressPolicy;
    private final HostKeyTypeRecorder recorder;

    SshjHostConnector(SshCredentialSource credentials, Duration keepAliveInterval, TargetAddressPolicy addressPolicy) {
        this(credentials, keepAliveInterval, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    SshjHostConnector(SshCredentialSource credentials, Duration keepAliveInterval, TargetAddressPolicy addressPolicy,
            HostKeyTypeRecorder recorder) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.keepAliveInterval = Objects.requireNonNull(keepAliveInterval, "keepAliveInterval");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public HostConnection connect(SshDockerPlacement p) {
        SSHClient ssh = null;
        boolean ok = false;
        try {
            java.net.InetAddress address = SshjDockerTransport.checkedAddress(addressPolicy, p.host());
            // The pinned key's recorded type is negotiated directly; while it is not known it is discovered, and recorded on
            // the first success (OR-125 (2)). A keep-alive on each attempt.
            ssh = PinnedHostKeyConnect.connect(p.pinnedHostKeyFingerprint(), p.pinnedHostKeyType(),
                    CONNECT_TIMEOUT_MILLIS, OPERATION_TIMEOUT_MILLIS,
                    config -> config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE),
                    c -> c.connect(address, p.port()), c -> { }, () -> false,
                    type -> recorder.learned(p.tenant(), p.pinnedHostKeyFingerprint(), type));
            ssh.getConnection().getKeepAlive().setKeepAliveInterval((int) Math.max(1, keepAliveInterval.toSeconds()));
            ssh.authPublickey(p.user(), SshjDockerTransport.loadKey(ssh, credentials, p));
            ok = true;
            return new SshjHostConnection(ssh);
        } catch (RuntimeAdapterException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));
        } finally {
            if (!ok && ssh != null) {
                try {
                    ssh.close();
                } catch (IOException ignored) {
                    // the classified connect failure stands
                }
            }
        }
    }

    /** One pooled connection: probes only, over fresh sessions (channels) of the one transport. */
    private static final class SshjHostConnection implements HostConnection {

        private final SSHClient ssh;

        SshjHostConnection(SSHClient ssh) {
            this.ssh = ssh;
        }

        @Override
        public boolean isAlive() {
            return ssh.isConnected() && ssh.isAuthenticated();
        }

        @Override
        public ProbeResult probe(String fixedCommand, Duration deadline) {
            Captured out = run(fixedCommand, deadline, HostProbe.MAX_BODY_BYTES + 16, HostProbe.MAX_BODY_BYTES + 8);
            return SshjDockerTransport.parseProbe(out.exit, out.output);
        }

        @Override
        public HostMetricExec.InspectResult inspect(String fixedCommand, Duration deadline) {
            Captured out = run(fixedCommand, deadline, HostMetricExec.MAX_INSPECT_BYTES + 1,
                    HostMetricExec.MAX_INSPECT_BYTES);
            return new HostMetricExec.InspectResult(out.exit,
                    new String(out.output, java.nio.charset.StandardCharsets.UTF_8));
        }

        private record Captured(int exit, byte[] output) {
        }

        /**
         * One fixed command over a fresh channel, bounded by {@code deadline} (W2-03). sshj 0.40.0's
         * {@code ChannelInputStream} has no read timeout ({@code AbstractChannel} builds it with none), so a command that
         * never answers blocks {@code readNBytes} and {@code join} forever. A watchdog closes THIS channel at the
         * deadline, which ends the blocked read; the call then fails {@code timeout}, never {@code ssh.connection.lost}
         * (the transport is fine, so there is nothing to reconnect and repeat).
         */
        private Captured run(String fixedCommand, Duration deadline, int readLimit, int maxOutput) {
            java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
            Session session = null;
            java.util.concurrent.ScheduledFuture<?> watchdog = null;
            try {
                session = ssh.startSession();
                final Session bounded = session;
                watchdog = WATCHDOGS.schedule(() -> {
                    expired.set(true);
                    closeQuietly(bounded);
                }, deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                Session.Command command = session.exec(fixedCommand);
                byte[] output = command.getInputStream().readNBytes(readLimit);
                if (expired.get()) {
                    throw new RuntimeAdapterException(new ErrorClass(SshSessionPool.TIMEOUT));
                }
                if (output.length > maxOutput) {
                    throw new RuntimeAdapterException(new ErrorClass("ssh.probe.output.too.large"));
                }
                command.join();
                Integer exit = command.getExitStatus();
                if (expired.get()) {
                    throw new RuntimeAdapterException(new ErrorClass(SshSessionPool.TIMEOUT));
                }
                if (exit == null) {
                    throw new RuntimeAdapterException(new ErrorClass(SshSessionPool.CONNECTION_LOST));
                }
                return new Captured(exit, output);
            } catch (IOException e) {
                // the cause is dropped on purpose (I6). A channel the watchdog closed is a timeout; any other IO failure
                // under a command means this connection is not trustworthy any more, which is exactly what the pool's
                // reconnect-once policy is for
                throw new RuntimeAdapterException(new ErrorClass(
                        expired.get() ? SshSessionPool.TIMEOUT : SshSessionPool.CONNECTION_LOST));
            } finally {
                if (watchdog != null) {
                    watchdog.cancel(false);
                }
                closeQuietly(session);
            }
        }

        private static void closeQuietly(Session session) {
            if (session == null) {
                return;
            }
            try {
                session.close();
            } catch (IOException | RuntimeException ignored) {
                // closing a channel that is already gone
            }
        }

        @Override
        public void close() {
            try {
                ssh.close();
            } catch (IOException ignored) {
                // closing an already-dead connection
            }
        }
    }
}
