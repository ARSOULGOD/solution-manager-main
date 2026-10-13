package com.solace.sm.dp.apply.runtime.ssh;

import java.util.Set;

/**
 * Asks a host which SSH host keys it offers (OR-89's residual, plan 010 T7.1). A scan authenticates nothing and sends
 * no credential; its result is used only to make the same machine unregisterable twice in one organization, never to
 * decide whom to trust (the pinned fingerprint does that).
 */
@FunctionalInterface
public interface HostKeyScanner {

    /**
     * @return the {@code SHA256:<base64>} fingerprint of each key type the host presented; never empty
     * @throws IllegalStateException when the host could not be reached or presented no key (the message names no host)
     */
    Set<String> scan(String host, int port);
}
