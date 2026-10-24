package com.solace.sm.gateway.http.metrics;

import com.solace.sm.gateway.http.ErrorCode;
import java.util.Objects;

/**
 * A monitoring route refused a request with a code the shared {@code GatewayRejectionException} cannot say: it carries
 * the contract's {@code reason} extension member ({@code ReasonedProblem}: {@code query-rate},
 * {@code export-concurrency}, {@code metric-declare-deferred}) and, for a {@code 429}, the {@code Retry-After} seconds.
 * {@link MetricsErrorAdvice} renders it.
 *
 * <p>Never built from request content: {@code reason} is one of the closed constants below and the message is the
 * code's name.
 */
public final class MetricsRejectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String QUERY_RATE = "query-rate";
    public static final String EXPORT_CONCURRENCY = "export-concurrency";
    /** {@code exportLogs}: too many log exports at once (the data plane's cap or the gateway's own threads), plan 016 task 10. */
    public static final String LOG_EXPORT_CONCURRENCY = "log-export-concurrency";
    /** {@code viewConfig}: too many config views at once (the data plane's bound or the agent's one-at-a-time), plan 017 task 6. */
    public static final String CONFIG_VIEW_CONCURRENCY = "config-view-concurrency";
    public static final String METRIC_DECLARE_DEFERRED = "metric-declare-deferred";

    private final ErrorCode code;
    private final String reason;
    private final Integer retryAfterSeconds;

    public MetricsRejectionException(ErrorCode code, String reason, Integer retryAfterSeconds) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /** Null unless the response is a {@code 429}. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
