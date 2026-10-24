package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * A {@code viewConfig} refusal that carries the data plane's FIXED reason text as {@code detail} (plan 017 task 6):
 * {@code 413 CONFIG_TOO_LARGE}, {@code 422 CONFIG_INVALID_YAML}, {@code 502 AGENT_REPLY_REJECTED} or
 * {@code 503 SERVICE_UNAVAILABLE}. {@code detail} is a {@code ConfigViewResult.Reason} message, never request content and never
 * anything the agent sent (I6). {@link ConfigViewErrorAdvice} renders it.
 */
final class ConfigViewRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String detail;

    ConfigViewRefusedException(ErrorCode code, String detail) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    ErrorCode code() {
        return code;
    }

    String detail() {
        return detail;
    }
}
