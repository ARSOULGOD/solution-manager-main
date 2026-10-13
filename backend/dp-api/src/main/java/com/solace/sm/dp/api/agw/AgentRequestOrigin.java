package com.solace.sm.dp.api.agw;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minted by the gateway per RPC from the gRPC peer — never a value the agent sent (plan 015 P15-8, {@code BE-D70}).
 * It is the only source of {@code OR-25}'s correlation id and client address on the agent path; the Telemetry RPC mints
 * one too. {@code clientAddress} is the transport peer: behind an L4 load balancer without PROXY protocol that is the
 * balancer, not the agent — an operator concern, documented in the install runbook (T3.2 also fixes the interceptor to
 * pass the bare host address).
 */
public record AgentRequestOrigin(UUID correlationId, String clientAddress) {
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9:._%-]{1,64}$");

    public AgentRequestOrigin {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(clientAddress, "clientAddress");
        if (!ADDRESS.matcher(clientAddress).matches()) {
            throw new IllegalArgumentException("clientAddress is not a bare host address");
        }
    }
}
