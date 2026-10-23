package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import java.util.Objects;

/**
 * The gateway's ports, each wired and kit-tested on its own (plan 015 section 4.2.1: there is no aggregate port
 * interface in {@code dp-api}). This record only bundles the beans for the one class that uses them all; it holds
 * no logic.
 */
public record AgentGatewayPortSet(AgentEnrollmentPort enrollment, AgentIdentityPort identity, AgentSessionPort sessions,
        AgentLeasePort leases, AgentCommandPort commands, ActualStatePort reports, AgentTelemetryPort telemetry,
        AgentAuditPort audit, AgentDiscoveryPort discovery, AgentTelemetryDeclarationsPort declarations,
        AgentLogRetentionSource logRetention) {
    public AgentGatewayPortSet {
        Objects.requireNonNull(enrollment, "enrollment");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(leases, "leases");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(declarations, "declarations");
        Objects.requireNonNull(logRetention, "logRetention");
    }
}
