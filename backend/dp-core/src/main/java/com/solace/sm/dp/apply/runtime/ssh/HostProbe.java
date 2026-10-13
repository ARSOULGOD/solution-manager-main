package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.time.Duration;
import java.util.Arrays;

/**
 * A bounded read of an HTTP endpoint on the target host's loopback, over the placement's SSH connection.
 *
 * <p>It is a <b>read</b>: the health poller (T7.2) and the apply engine's health gate (T6.9) use it; nothing
 * mutates through it. The command is fixed in source and built only from an {@code int} port and a path that
 * matches {@code ^/[A-Za-z0-9/_.-]{0,255}$} (SM-D11a P: no shell composition from data).
 */
public interface HostProbe {

    /** Seconds. The SSH operation timeout is 15 s, so a probe may not ask for more than this. */
    int MAX_TIMEOUT_SECONDS = 10;

    /** Upper bound on the response body kept; a larger answer is a transport failure, not a truncated body. */
    int MAX_BODY_BYTES = 64 * 1024;

    /**
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException {@code ssh.probe.refused} for a port, path
     *         or timeout outside the grammar (before any session is opened); a connection or exec failure
     *         otherwise. A non-2xx HTTP answer is <b>not</b> a failure: it is a {@link ProbeResult}.
     */
    ProbeResult probe(SshDockerPlacement placement, int port, String path, Duration timeout);

    /**
     * @param exitCode   the exit status of {@code curl} (0 = an HTTP answer was received, whatever its status;
     *                   7 = connection refused, 6 = host or network unreachable (direct http only), 28 = timed out)
     * @param httpStatus the HTTP status, or 0 when no answer was received
     * @param body       at most {@link #MAX_BODY_BYTES}; a copy
     */
    record ProbeResult(int exitCode, int httpStatus, byte[] body) {
        public ProbeResult {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Length only: the body is the target's answer and never renders into a log line. */
        @Override
        public String toString() {
            return "ProbeResult[exitCode=" + exitCode + ", httpStatus=" + httpStatus + ", body="
                    + body.length + " bytes]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ProbeResult r && r.exitCode == exitCode && r.httpStatus == httpStatus
                    && Arrays.equals(r.body, body);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * exitCode + httpStatus) + Arrays.hashCode(body);
        }
    }

    /** The production probe over SSH; the credential comes from {@code credentials}, by reference. */
    static HostProbe over(SshCredentialSource credentials, TargetAddressPolicy addressPolicy) {
        return over(credentials, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    /** As above, reporting a pinned key's type discovered on a connection to {@code recorder} (OR-125 (2)). */
    static HostProbe over(SshCredentialSource credentials, TargetAddressPolicy addressPolicy,
            HostKeyTypeRecorder recorder) {
        return new SshjDockerTransport(credentials, addressPolicy, recorder);
    }
}
