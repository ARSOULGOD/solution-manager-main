package com.solace.sm.dp.meshbridge.ingest;

import java.util.Objects;

/**
 * Where, and as whom, a data-plane subscriber connects to a customer broker (plan 011 P11-5.1; OR-107 OQ-3 (a)). Everything
 * but the credential, which is resolved in memory at connect time and never held here.
 *
 * <p>Plain values, no JCSMP type: this record crosses out of {@code meshbridge.ingest} to the services that read it from a row.
 *
 * @param scheme one of {@code tcp}, {@code tcps}, {@code ws}, {@code wss} (SDK host schemes, {@code HostInfo} of sol-jcsmp 10.30.1)
 */
public record BrokerEndpoint(String scheme, String host, int port, String vpn, String clientUsername) {

    public BrokerEndpoint {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(vpn, "vpn");
        Objects.requireNonNull(clientUsername, "clientUsername");
    }

    /** The JCSMP host string, {@code scheme://host:port}. */
    public String uri() {
        return scheme + "://" + host + ":" + port;
    }
}
