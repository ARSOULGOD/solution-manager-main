package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Set;
import java.util.UUID;

/**
 * Request body of {@code registerTarget} (console.yaml {@code RegisterTargetRequest}; {@code additionalProperties:
 * false}). A structural fault (a missing field, a stray key, a name or key past its length) is {@code 400}.
 *
 * <p>⛔ {@code I6}: {@code privateKey} is the key file's text. It is write-only: it appears in no response, no exception
 * message, no audit row and no log line, and {@link #toString} never prints it. There is no organization and no target id
 * in the body: the tenant is the session's and the CP mints the id ({@code I5}).
 */
public record RegisterTargetRequest(String name, TargetKind kind, TargetTransport transport, ScopeRef ownershipScope,
        TargetConnectionSpecRequest connection, String privateKey, UUID connectionId, String runtime,
        String configRoot, String containerPrefix, String serviceNamePattern) {

    /** Mirrors {@code EnvelopeSecretStore.MAX_VALUE_BYTES} and the secret table's length CHECK. */
    public static final int MAX_PRIVATE_KEY_CHARS = 16384;

    public RegisterTargetRequest(String name, TargetKind kind, TargetTransport transport, ScopeRef ownershipScope,
            TargetConnectionSpecRequest connection, String privateKey) {
        this(name, kind, transport, ownershipScope, connection, privateKey, null, null, null, null, null);
    }

    public RegisterTargetRequest {
        if (name == null || name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException("name is required and 1 to 100 characters");
        }
        if (kind == null || transport == null || ownershipScope == null) {
            throw new IllegalArgumentException("kind, transport and ownershipScope are required");
        }
        if (connectionId == null) {
            if (transport != TargetTransport.SSH_DOCKER) {
                // registerTarget cannot create an agent target: it is created at an agent's enrolment (plan 015 T3.5, BE-D71).
                throw new IllegalArgumentException("transport must be ssh-docker");
            }
            if (connection == null) {
                throw new IllegalArgumentException("connection is required for a direct target");
            }
            if (privateKey == null || privateKey.isEmpty() || privateKey.length() > MAX_PRIVATE_KEY_CHARS) {
                throw new IllegalArgumentException("privateKey is required and at most 16384 characters");
            }
        }
        if (connectionId != null && (connection != null || privateKey != null)) {
            throw new IllegalArgumentException("a linked target must not carry SSH credentials");
        }
        if (connectionId != null && (runtime == null || configRoot == null)) {
            throw new IllegalArgumentException("runtime and configRoot are required for a linked target");
        }
        if (connectionId != null) {
            if (!Set.of("docker", "systemd").contains(runtime) || !runtime.equals(kind.wireValue())
                    || transport != TargetTransport.SSH) {
                throw new IllegalArgumentException("linked target kind, runtime and transport must agree");
            }
            if (!configRoot.matches("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$")) {
                throw new IllegalArgumentException("configRoot must be a canonical absolute path");
            }
            if (runtime.equals("docker") && (containerPrefix == null
                    || !containerPrefix.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$"))) {
                throw new IllegalArgumentException("containerPrefix is required for docker targets");
            }
            if (runtime.equals("systemd") && (serviceNamePattern == null || serviceNamePattern.isBlank()
                    || serviceNamePattern.length() > 128 || serviceNamePattern.chars().anyMatch(Character::isWhitespace))) {
                throw new IllegalArgumentException("serviceNamePattern is required for systemd targets");
            }
        }
    }

    @Override
    public String toString() {
        return "RegisterTargetRequest[name=" + name + ", kind=" + kind + ", transport=" + transport
                + ", ownershipScope=" + ownershipScope + ", connection=" + connection + ", privateKey=[REDACTED],"
                + " connectionId=" + connectionId + ", runtime=" + runtime + "]";
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
