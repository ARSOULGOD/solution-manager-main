package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.time.Instant;

/**
 * A bounded read of a placement's container logs over the placement's SSH connection (plan 010 T9.1).
 *
 * <p>A <b>read</b>: nothing mutates through it. The exec string is fixed in source and built only from an {@code int}
 * and two {@link Instant}s plus the placement's grammar-checked container name (no shell composition from data).
 * Package-private {@code SshDockerTransport} keeps the demo's older {@code logs(...)}; this is the public, narrower seam
 * the request-thread read uses.
 */
public interface HostLogs {

    /**
     * The raw {@code docker logs --timestamps} output (stdout and stderr merged), one RFC3339Nano-stamped line per
     * entry, at most 512 KiB.
     *
     * @param limit  1..1000; bounds the output when {@code before} is absent (see below)
     * @param after  inclusive lower bound the host applies, or {@code null}
     * @param before inclusive upper bound the host applies, or {@code null}. ⚠️ Measured (M11): Docker applies
     *               {@code --tail} BEFORE {@code --until}, so no tail is sent with a {@code before}; the host returns
     *               the whole window and the caller trims it, and a window over the byte bound is
     *               {@code ssh.docker.output.too.large}.
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException {@code ssh.docker.logs.limit.refused} for a limit or
     *         window outside its bounds (before any session is opened); a connection or exec failure otherwise
     */
    String read(SshDockerPlacement placement, int limit, Instant after, Instant before);

    /** The production reader over SSH; the credential comes from {@code credentials}, by reference. */
    static HostLogs over(SshCredentialSource credentials, TargetAddressPolicy addressPolicy) {
        return over(credentials, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    /** As above, reporting a pinned key's type discovered on a connection to {@code recorder} (OR-125 (2)). */
    static HostLogs over(SshCredentialSource credentials, TargetAddressPolicy addressPolicy,
            HostKeyTypeRecorder recorder) {
        return new SshjDockerTransport(credentials, addressPolicy, recorder);
    }
}
