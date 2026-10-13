package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * The connection fields of a registration (console.yaml {@code TargetConnectionSpec}). {@code allowedConfigRoot} and
 * {@code allowedContainerPrefix} are the envelope ({@code B2}), immutable once registered ({@code OR-52}). The grammars
 * are the data plane's; a value outside one is {@code 422 VALIDATION_FAILED} naming the field.
 */
public record TargetConnectionSpecRequest(String host, Integer port, String sshUser, String hostKeyFingerprint,
        String allowedConfigRoot, String allowedContainerPrefix) {

    public TargetConnectionSpecRequest {
        require(host, "host");
        require(sshUser, "sshUser");
        require(hostKeyFingerprint, "hostKeyFingerprint");
        require(allowedConfigRoot, "allowedConfigRoot");
        require(allowedContainerPrefix, "allowedContainerPrefix");
    }

    public int portOrDefault() {
        return port == null ? HostKeyScanRequest.DEFAULT_PORT : port;
    }

    private static void require(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
