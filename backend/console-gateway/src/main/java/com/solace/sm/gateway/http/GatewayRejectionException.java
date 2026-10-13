package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;

/**
 * Gateway-internal rejection, carrying the {@link ErrorCode} the one
 * {@link GatewayErrorAdvice} renders it as.
 *
 * <p>Thrown by {@link AuthenticationInterceptor} — for origin, session, must-rotate, CSRF and
 * pre-authentication rate-gate ({@code OR-24}) rejections, and when an operation cannot be
 * classified. Authorization failures are the cp-api {@code AccessDeniedException} /
 * {@code ResourceNotFoundException} instead. Never carries a message derived from request
 * content — {@link GatewayErrorAdvice} derives the response body from {@code code} alone.
 *
 * <p>{@code headers()} carries additional response headers the advice must set alongside the
 * {@link ProblemBody} — today, only {@code RATE_LIMITED}'s {@code Retry-After}. Never used to
 * carry anything derived from request content either: every value in it is server-computed.
 */
public final class GatewayRejectionException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, String> headers;

    public GatewayRejectionException(ErrorCode code) {
        this(code, Map.of());
    }

    public GatewayRejectionException(ErrorCode code, Map<String, String> headers) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
