package com.solace.sm.dp.apply.runtime.ssh;

/** Opens a {@link HostConnection} for a placement's target; the seam that lets the pool be tested without a host. */
@FunctionalInterface
interface HostConnector {

    /**
     * @throws com.solace.sm.dp.apply.runtime.RuntimeAdapterException {@code ssh.connection.failed},
     *         {@code ssh.credential.unavailable}, {@code ssh.credential.invalid} or {@code target.address.refused}
     *         (the address policy refused the stored host, plan 013 T1.2); never a cause or a message
     */
    HostConnection connect(SshDockerPlacement placement);
}
