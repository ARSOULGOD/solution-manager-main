package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code Problem} plus the echo {@code createConnection}/{@code updateConnection}'s {@code 422
 * CONNECTION_TEST_FAILED} carries (plan 013 T4, {@code BE-D66}): the peer's rejection reason in {@code detail}, and
 * the request's own non-secret {@code name}/{@code attributes} so the form can be left filled in. Shaped like
 * {@link FleetRefusedBody}: {@code Problem} has no {@code additionalProperties: false}, so the contract already
 * allows a code-specific body to extend it.
 *
 * <p>⛔ As {@link ProblemBody}: nothing a caller supplied reaches {@code title}; {@code detail} and the echo come
 * only from {@link ConnectionTestFailedException}, which {@link ConnectionAttributeCodec} already stripped of every
 * secret-typed attribute before it was ever thrown.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConnectionTestFailedBody(String type, String title, int status, ErrorCode code, UUID correlationId,
                                       String detail, @WireOptional String name, Map<String, Object> attributes) {

    public ConnectionTestFailedBody {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(detail, "detail");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    public static ConnectionTestFailedBody of(UUID correlationId, ConnectionTestFailedException exception) {
        ProblemBody problem = ProblemBody.withDetail(ErrorCode.CONNECTION_TEST_FAILED, correlationId,
                exception.rejectionReason());
        return new ConnectionTestFailedBody(problem.type(), problem.title(), problem.status(), problem.code(),
                correlationId, problem.detail(), exception.echoedName(), exception.echoedAttributes());
    }
}
