package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;

/**
 * Runs plan 011's two fixed metric commands on a placement's host over a pooled SSH session (P11-4.2b). The
 * implementation is {@link SshSessionPool}; the seam exists so the fetcher can be driven by a spy session.
 *
 * <p>A command is a {@link HostMetricProbe.Curl} or a {@link HostMetricProbe.Inspect}: values only
 * {@link HostMetricProbe} can build, from a grammar-checked path, parameters and container name. This interface cannot
 * carry a caller-supplied shell string (SM-D11a).
 */
public interface HostMetricExec {

    /** Upper bound on the {@code docker inspect} output kept (one line of an id and a timestamp). */
    int MAX_INSPECT_BYTES = 4096;

    /**
     * Runs the metrics {@code curl}. A non-2xx answer is a {@link ProbeResult} (exit 0, its status), not a failure
     * (M19).
     *
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException a pool or connection classifier
     *         ({@code ssh.pool.busy}, {@code ssh.connection.failed}, ...)
     */
    ProbeResult curl(SshDockerPlacement placement, HostMetricProbe.Curl command);

    /**
     * Runs the fixed {@code docker inspect}.
     *
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException a pool or connection classifier
     */
    InspectResult inspect(SshDockerPlacement placement, HostMetricProbe.Inspect command);

    /**
     * @param exitCode the exit status of {@code docker inspect} (0 on success; non-zero for a missing container)
     * @param stdout   at most {@link #MAX_INSPECT_BYTES}; the container's own output, never logged
     */
    record InspectResult(int exitCode, String stdout) {
        public InspectResult {
            stdout = stdout == null ? "" : stdout;
        }

        /** Length only: the output is the host's answer. */
        @Override
        public String toString() {
            return "InspectResult[exitCode=" + exitCode + ", stdout=" + stdout.length() + " chars]";
        }
    }
}
