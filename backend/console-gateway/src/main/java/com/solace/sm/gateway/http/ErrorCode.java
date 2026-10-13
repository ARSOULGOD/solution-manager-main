package com.solace.sm.gateway.http;

/**
 * Mirrors {@code contracts/openapi/console.yaml}'s {@code ErrorCode} schema exactly, including
 * the HTTP status each code carries. Clients switch on {@code code}, never on {@code title},
 * {@code detail}, or the HTTP status alone — several codes here share a status.
 */
public enum ErrorCode {

    UNAUTHENTICATED(401),
    AUTHENTICATION_FAILED(401),
    MUST_ROTATE(428),
    CSRF_REJECTED(403),
    ORIGIN_REJECTED(403),
    FORBIDDEN(403),
    NOT_FOUND(404),
    BAD_REQUEST(400),
    CREDENTIAL_ROTATION_REFUSED(400),
    METHOD_NOT_ALLOWED(405),
    RATE_LIMITED(429),
    SERVICE_UNAVAILABLE(503),
    INTERNAL_ERROR(500),
    /** Stale {@code baseStateVersion}, admission conflict, or a placement collision (§5.6; OR-40). */
    CONFLICT(409),
    /** Package gate, projection mismatch, undeclared key, blocking validation, envelope refusal (§5.6; OR-40). */
    VALIDATION_FAILED(422),
    /**
     * Not entitled, or the organization's quota is exhausted. ⚠️ Not 403 — §5.6 rule 3 makes 403 mean
     * "the verb is not held at the resource's scope"; this is a different refusal (OR-40).
     */
    ENTITLEMENT_REFUSED(409),
    /**
     * Either no approved verb exists for the requested behaviour (§5.6 rule 5; OR-40), or a
     * verb is approved but this build does not yet implement it (e.g. {@code setLogLevel}'s
     * {@code mode: live}, which no build offers in v1 — {@code ENDPOINT-INVENTORY.md} §3).
     */
    NOT_IMPLEMENTED(501),
    /** The query would render more series than the cap (DM-D7); carries {@code {cap, projected, seriesBy}} (plan 011 P11-1.2). */
    SERIES_CAP_EXCEEDED(422),
    /** The aggregation cannot apply to the metric's type, for example a histogram. */
    AGGREGATION_NOT_APPLICABLE(422),
    /** {@code groupBy} without an aggregation across instances is ambiguous, so it is refused, not interpreted. */
    GROUP_BY_REQUIRES_AGGREGATION(422),
    /** A {@code single-stat} or {@code gauge} mark over more than one instance with no aggregation (DM-D22). */
    STAT_REQUIRES_AGGREGATION(422),
    /** The metric has no declared type. No v1 trigger: undeclared names are dropped and counted (M-Q6). */
    METRIC_UNTYPED(422),
    /** The projected export exceeds the row limit; carries {@code {projectedRows, limit}}. */
    EXPORT_TOO_LARGE(422),
    /** The package version's content differs from the one recorded when it was loaded; a digest mismatch fails closed. */
    PACKAGE_VERSION_MISMATCH(409),
    /** A declare's {@code placement.metricsPorts} maps two source ports to one host port (M-Q14). */
    METRICS_PORT_CONFLICT(409),
    /** The private key does not open as an UNENCRYPTED SSH private key; nothing was stored (plan 013 T1.4, D-REG section 6.3). */
    CREDENTIAL_INVALID(422),
    /** The pinned host key is not one the host offered on a fresh scan; nothing was written (plan 013 T1.4, {@code BE-D55}). */
    HOST_KEY_NOT_OFFERED(422),
    /** The data plane may not connect to the address the host names (plan 013 T1.4, D-REG section 7.3). */
    TARGET_ADDRESS_REFUSED(422),
    /**
     * A request that carries a private key or a credential reached the gateway over a channel that is neither secure, a
     * trusted proxy's HTTPS, nor loopback, and was refused BEFORE its body was read (plan 013 OQ-14 (b)).
     */
    INSECURE_TRANSPORT(403),
    /**
     * {@code testBeforeSave: true} ran the connectivity test and it failed
     * ({@code createConnection}/{@code updateConnection}, plan 013 T1, {@code BE-D66}); nothing was
     * written either way. The broker's or host's own rejection reason is in {@code detail}; secret-typed
     * attributes are stripped from the request echo ({@code I6}).
     */
    CONNECTION_TEST_FAILED(422),
    /**
     * The operation is not available on an agent placement in v1 (logs, log level, read-config / accept-runtime, restore,
     * rollback). Nothing was read or written and no SSH connection was made. Distinct from {@link #NOT_IMPLEMENTED}, which says
     * no approved verb exists or this build lacks it (plan 015 T3.3).
     */
    AGENT_OPERATION_UNSUPPORTED(501),
    /**
     * The operation addresses an SSH connection record (edit, credential rotation, connection test) and the target's transport is
     * {@code agent}: nothing was read or written and no connection was made (plan 015 T3.5).
     */
    TARGET_TRANSPORT_UNSUPPORTED(409),
    /** {@code retireTarget} on an agent target whose agent has not been revoked (plan 015 T3.5). */
    AGENT_STILL_ENROLLED(409),
    /**
     * {@code addPlacement}, or a declare naming an agent target: placements on an agent target come only from {@code OR-27}
     * discovery in v1 (plan 015 T3.5).
     */
    TARGET_IS_AGENT_MANAGED(409),
    /** {@code drainAgent}: the agent has no live session (plan 015 T4.3; declared with the other agent codes). */
    AGENT_NOT_CONNECTED(409);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
