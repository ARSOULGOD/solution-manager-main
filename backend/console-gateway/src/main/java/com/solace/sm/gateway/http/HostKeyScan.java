package com.solace.sm.gateway.http;

import java.util.List;

/** What a host offered (console.yaml {@code HostKeyScan}). Never an error for a reachable host that offers keys. */
public record HostKeyScan(String host, int port, List<OfferedHostKey> offered) {
}
