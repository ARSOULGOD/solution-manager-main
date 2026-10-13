package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors {@code contracts/openapi/console.yaml}'s {@code Problem} schema. The ONE error shape
 * every operation returns.
 *
 * <p>⛔ {@code title} and {@code detail} are FIXED per {@link ErrorCode} — never an exception
 * message, never user input — so nothing an attacker or a buggy caller supplies can ever reach
 * a response body through this type. {@link #of(ErrorCode, UUID)} is the only way to build one.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProblemBody(String type, String title, int status, ErrorCode code, UUID correlationId,
                          @WireOptional String detail, @WireOptional String instance) {

    private static final Map<ErrorCode, String> TITLES = Map.ofEntries(
            Map.entry(ErrorCode.UNAUTHENTICATED, "Authentication required"),
            Map.entry(ErrorCode.AUTHENTICATION_FAILED, "Authentication failed"),
            Map.entry(ErrorCode.MUST_ROTATE, "Credential rotation required"),
            Map.entry(ErrorCode.CSRF_REJECTED, "CSRF validation failed"),
            Map.entry(ErrorCode.ORIGIN_REJECTED, "Origin not trusted"),
            Map.entry(ErrorCode.FORBIDDEN, "Not permitted"),
            Map.entry(ErrorCode.NOT_FOUND, "Not found"),
            Map.entry(ErrorCode.BAD_REQUEST, "Bad request"),
            Map.entry(ErrorCode.CREDENTIAL_ROTATION_REFUSED, "Credential rotation refused"),
            Map.entry(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed"),
            Map.entry(ErrorCode.RATE_LIMITED, "Rate limit exceeded"),
            Map.entry(ErrorCode.SERVICE_UNAVAILABLE, "Service unavailable"),
            Map.entry(ErrorCode.INTERNAL_ERROR, "Internal error"),
            Map.entry(ErrorCode.CONFLICT, "Conflict"),
            Map.entry(ErrorCode.VALIDATION_FAILED, "Validation failed"),
            Map.entry(ErrorCode.ENTITLEMENT_REFUSED, "Entitlement refused"),
            Map.entry(ErrorCode.NOT_IMPLEMENTED, "Not implemented"),
            // Plan 011 P11-4.1: the two codes a declare answers with.
            Map.entry(ErrorCode.PACKAGE_VERSION_MISMATCH, "Package version mismatch"),
            Map.entry(ErrorCode.METRICS_PORT_CONFLICT, "Metrics port conflict"),
            // Plan 011 P11-7.2: the other six P11-1.2 codes, the monitoring routes' refusals. A body built for a code with
            // no title fails its own non-null check and the original refusal is lost, so every ErrorCode has one
            // (ProblemBodyTitlesTest pins it).
            Map.entry(ErrorCode.SERIES_CAP_EXCEEDED, "Series cap exceeded"),
            Map.entry(ErrorCode.AGGREGATION_NOT_APPLICABLE, "Aggregation not applicable"),
            Map.entry(ErrorCode.GROUP_BY_REQUIRES_AGGREGATION, "Group by requires an aggregation"),
            Map.entry(ErrorCode.STAT_REQUIRES_AGGREGATION, "Single-stat requires an aggregation"),
            Map.entry(ErrorCode.METRIC_UNTYPED, "Metric has no declared type"),
            Map.entry(ErrorCode.EXPORT_TOO_LARGE, "Export too large"),
            // Plan 013 T1.4: the registration API's refusals.
            Map.entry(ErrorCode.CREDENTIAL_INVALID, "Credential invalid"),
            Map.entry(ErrorCode.HOST_KEY_NOT_OFFERED, "Host key not offered"),
            Map.entry(ErrorCode.TARGET_ADDRESS_REFUSED, "Target address refused"),
            Map.entry(ErrorCode.INSECURE_TRANSPORT, "Insecure transport"),
            // Plan 013 T1: the connection registration API's connectivity-test refusal.
            Map.entry(ErrorCode.CONNECTION_TEST_FAILED, "Connection test failed"),
            // Plan 015 T3.3: an operation an agent placement cannot serve.
            Map.entry(ErrorCode.AGENT_OPERATION_UNSUPPORTED, "Operation not available on an agent placement"),
            // Plan 015 T3.5: the agent target's refusals.
            Map.entry(ErrorCode.TARGET_TRANSPORT_UNSUPPORTED, "Target transport unsupported"),
            Map.entry(ErrorCode.AGENT_STILL_ENROLLED, "Agent still enrolled"),
            Map.entry(ErrorCode.TARGET_IS_AGENT_MANAGED, "Target is agent managed"),
            Map.entry(ErrorCode.AGENT_NOT_CONNECTED, "Agent not connected"));

    public ProblemBody {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    /** The usual constructor path in production: title/detail are derived from {@code code}, never supplied. */
    public static ProblemBody of(ErrorCode code, UUID correlationId) {
        return new ProblemBody("about:blank", TITLES.get(code), code.status(), code, correlationId, null, null);
    }

    /**
     * A body whose {@code detail} names the failed rule. ⛔ {@code detail} must be built by the caller from fixed
     * text and closed identifiers only (a gate id), never from an exception message or any request content.
     */
    public static ProblemBody withDetail(ErrorCode code, UUID correlationId, String detail) {
        return new ProblemBody("about:blank", TITLES.get(code), code.status(), code, correlationId,
                Objects.requireNonNull(detail, "detail"), null);
    }
}
