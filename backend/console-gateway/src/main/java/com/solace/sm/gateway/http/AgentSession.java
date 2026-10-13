package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code AgentSession}: a fact the data plane holds about the agent's connection, never a promise.
 * Omitted from {@link Agent} until the agent has connected once. {@code draining} is not a state: it is a gateway-process
 * transient with no stored source.
 */
public record AgentSession(State state, String lastSeenAt, @WireOptional String agentVersion,
        @WireOptional String leaseExpiresAt) {

    public enum State {
        CONNECTED("connected"), DISCONNECTED("disconnected");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public AgentSession {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }
}
