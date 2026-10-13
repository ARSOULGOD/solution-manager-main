package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;

/**
 * One authenticated connection to a target host, shared by many probes (plan 010 T7.1).
 *
 * <p>The only operations are a <b>fixed probe command</b> built by {@code SshjDockerTransport#probeCommand} (or
 * {@link HostMetricProbe#curl}) from an {@code int} and a grammar-checked path, and a fixed {@code docker inspect}
 * built by {@link HostMetricProbe#inspect}; this type cannot carry a caller-supplied shell string out of the pool
 * (SM-D11a). The apply path never holds one: it opens a connection per operation (T6.1).
 */
interface HostConnection extends AutoCloseable {

    /** True while the transport is up and authenticated; keep-alive turns a silently dropped session into false. */
    boolean isAlive();

    /**
     * Runs one fixed probe command, giving up at {@code deadline} (W2-03: sshj's channel streams have no read timeout,
     * so the implementation bounds the call itself and closes the one channel when the deadline passes).
     *
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException {@link SshSessionPool#CONNECTION_LOST} when the
     *         connection failed under the call (the pool reconnects and retries once when the connection was
     *         reused); {@link SshSessionPool#TIMEOUT} when the deadline passed; another classifier for a failure that
     *         is not the connection's
     */
    ProbeResult probe(String fixedCommand, java.time.Duration deadline);

    /**
     * Runs one fixed {@code docker inspect} command (plan 011 P11-4.2b), built by {@link HostMetricProbe#inspect}.
     *
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException as {@link #probe}
     */
    HostMetricExec.InspectResult inspect(String fixedCommand, java.time.Duration deadline);

    @Override
    void close();
}
