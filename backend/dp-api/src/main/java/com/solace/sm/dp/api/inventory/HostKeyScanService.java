package com.solace.sm.dp.api.inventory;

import java.util.List;

/**
 * Asks a host which SSH host keys it offers, from the data plane's own network position (plan 013 T1.2, D-REG section
 * 7.1). A scan authenticates nothing and carries no credential; what it returns is <b>untrusted</b> and is never a pin
 * (OpenSSH's own manual says as much of {@code ssh-keyscan}): a pin is an explicit field a human confirmed, which the
 * registration re-checks against a fresh scan ({@code BE-D55}).
 *
 * <p>The address rule applies first: the scan connects only to an address {@code TargetAddressPolicy} accepted, and
 * connects to that very address.
 */
public interface HostKeyScanService {

    /** A key the host offered: its {@code SHA256:<base64>} fingerprint and the key type it was offered under. */
    record OfferedHostKey(String fingerprint, String algorithm) {

        public OfferedHostKey {
            if (fingerprint == null || !fingerprint.startsWith("SHA256:")) {
                throw new IllegalArgumentException("a host key fingerprint is spelt SHA256:<base64>");
            }
            if (algorithm == null || algorithm.isBlank()) {
                throw new IllegalArgumentException("a host key carries its key type");
            }
        }
    }

    /**
     * @return one entry per distinct key the host offered, in preference order; never empty
     * @throws TargetAddressRefusedException      the name resolves to an address the data plane may not connect to
     * @throws HostKeyScanUnavailableException    the host could not be reached or presented no SSH host key
     */
    List<OfferedHostKey> scan(String host, int port);
}
