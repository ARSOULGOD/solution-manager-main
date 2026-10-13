package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;

/**
 * Request body of {@code editTargetConnection} (console.yaml {@code EditTargetConnectionRequest}; {@code
 * additionalProperties: false}). Each field is optional and names what to change; at least one must be named. A structural
 * fault is {@code 400}: an unknown property (which is how {@code allowedConfigRoot} and {@code allowedContainerPrefix}, the
 * immutable envelope ({@code OR-52}), an organization, a target id and a key are all refused), an empty string, a value
 * past its length. The GRAMMAR of each field is the data plane's ({@code TargetConnectionValidator}, the one place it
 * lives) and is {@code 422}.
 *
 * <p>There is no private key here: a key is replaced by {@code rotateTargetCredential}, never by an edit.
 */
public record EditTargetConnectionRequest(String host, Integer port, String sshUser, String hostKeyFingerprint) {

    public EditTargetConnectionRequest {
        notEmpty(host, "host", 254);
        notEmpty(sshUser, "sshUser", 64);
        notEmpty(hostKeyFingerprint, "hostKeyFingerprint", 128);
        if (host == null && port == null && sshUser == null && hostKeyFingerprint == null) {
            throw new IllegalArgumentException("at least one of host, port, sshUser and hostKeyFingerprint is required");
        }
    }

    private static void notEmpty(String value, String field, int max) {
        if (value != null && (value.isEmpty() || value.length() > max)) {
            throw new IllegalArgumentException(field + " is not empty and at most " + max + " characters");
        }
    }

    TargetConnectionEdit toEdit() {
        return new TargetConnectionEdit(host, port, sshUser, hostKeyFingerprint);
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
