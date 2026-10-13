package com.solace.sm.dp.api.agw;

/** Stable refusal vocabulary shared by the transport adapters and dp-core. */
public enum GatewayCode {
    UNKNOWN,
    UNAUTHENTICATED_IDENTITY,
    IDENTITY_REVOKED,
    TOKEN_CONSUMED,
    PROTOCOL_VERSION_UNSUPPORTED,
    UPGRADE_REQUIRED,
    CAPABILITY_MISMATCH,
    CAPABILITY_MISSING,
    BINDING_NOT_IN_LEASE,
    TARGET_MISMATCH,
    LEASE_KEY_MISMATCH,
    GRANT_INVALID,
    REQUEST_ID_REUSED,
    REQUEST_EXPIRED,
    IN_PROGRESS,
    LEASE_RENEW_REQUIRED,
    RE_ENROLL_REQUIRED,
    ISSUE_NOT_ALLOWED,
    REPLICA_REF_INVALID,
    REPLICA_CAP_EXCEEDED,
    REPORTER_DISPLACED,
    UNDECLARED_LABEL,
    LABEL_INVALID,
    UNKNOWN_REPLICA,
    INCARNATION_RETIRED,
    ADMISSION_LIMITED,
    SESSION_SUPERSEDED,
    LEASE_RENEW_REFUSED,
    STALE_GENERATION,
    UNKNOWN_COMMAND,
    SUPERSEDED,
    KEY_HASH_MISMATCH,
    TENANT_MISMATCH,
    PAYLOAD_TOO_LARGE,
    OUT_OF_CREDITS,
    CLOCK_SUSPECT,
    DRAINING,
    INTERNAL_UNAVAILABLE,
    STALE_EPOCH,

    // ---- later codes are additive; none renumbers or renames an existing one ----
    // ---- plan 015 T2.1a: the dotted codes the DP records and the agent path refuses with (AGW-D*, BE-D66) ----
    /** The agent has no open session or no unexpired lease holding the placement: an agent step fails fast (BE-D66). */
    AGENT_SESSION_ABSENT("agent.session.absent"),
    AGENT_SESSION_LOST("agent.session.lost"),
    /** A pull's re-render differs from the recorded keyed hash (AGW-D26). */
    AGENT_PAYLOAD_RENDER_CHANGED("agent.payload.render.changed"),
    AGENT_OPERATION_UNSUPPORTED("agent.operation.unsupported"),
    /** The discovery port is not implemented yet (T2.1b stub; T4.1). */
    AGENT_DISCOVERY_UNAVAILABLE("agent.discovery.unavailable"),
    AGENT_DISCOVERY_INCOMPLETE("agent.discovery.incomplete"),
    AGENT_DISCOVERY_OUTSIDE_ENVELOPE("agent.discovery.outside.envelope"),
    AGENT_DISCOVERY_PORT_TAKEN("agent.discovery.port.taken"),
    AGENT_DISCOVERY_CONTAINER_TAKEN("agent.discovery.container.taken"),
    AGENT_DISCOVERY_CONFIG_TAKEN("agent.discovery.config.taken"),
    // ---- plan 015 T4.1/T4.2: the three refusals of the plan's own text that T2.1a did not declare (additive, HF18) ----
    /** The agent's target already holds {@code sm.dp.agent.max-placements-per-target} live placements (OR-128 (11)). */
    AGENT_PLACEMENT_CAP("agent.placement.cap"),
    /** A live instance of the enrolled environment already holds the name the container's label asks for. */
    INSTANCE_NAME_TAKEN("instance.name.taken"),
    /** The control plane refused the governance node: the organization's entitlement limit (OR-40) or no entitlement. */
    ENTITLEMENT_QUOTA("entitlement.quota"),
    // ---- plan 015 Final (HF18, authorised by the integrator, additive only): the two wire error codes a refused ack carries
    // ---- that had no member, so gateway.command.refused audited them as UNKNOWN (W5 review m-4 (b)). The constant names equal
    // ---- the proto's ERROR_CODE_TARGET_MOVED / ERROR_CODE_RENDER_CHANGED, which is how the gateway maps them both ways. ----
    /** The agent refused a rematerialize: the host file is not the expected current one (nothing written). */
    TARGET_MOVED,
    /**
     * The WIRE code (proto ERROR_CODE_RENDER_CHANGED) the AGENT reports for a pull refused because the re-render differs from the
     * recorded one, and the gateway maps it to and from the wire by name. {@link #AGENT_PAYLOAD_RENDER_CHANGED} is the DATA PLANE's
     * own coded error ({@code agent.payload.render.changed}) when it raises the same condition itself. Two members for one condition
     * on the two sides of the wire is deliberate: do not "fix" one away (final review NIT; the no-wire-string constructor form is the
     * additive HF18 shape).
     */
    RENDER_CHANGED;

    private final String wire;

    GatewayCode() {
        this.wire = null;
    }

    GatewayCode(String wire) {
        this.wire = wire;
    }

    /** The string recorded in audit rows and refusals: the dotted form for the T2.1a codes, else the constant name. */
    public String wire() {
        return wire == null ? name() : wire;
    }
}
