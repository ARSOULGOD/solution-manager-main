package com.solace.sm.gateway.http;

/** One key a host offered (console.yaml {@code OfferedHostKey}): untrusted, and never a pin by itself (D-REG 7.1). */
public record OfferedHostKey(String fingerprint, String algorithm) {
}
