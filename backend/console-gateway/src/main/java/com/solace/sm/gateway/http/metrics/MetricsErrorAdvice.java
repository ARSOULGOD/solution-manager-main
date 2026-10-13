package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.dp.api.metrics.query.MetricsQueryRefusedException;
import com.solace.sm.dp.api.metrics.query.MetricsQueryUnavailableException;
import com.solace.sm.dp.api.metrics.query.QueryRefusal;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.gateway.http.CorrelationIdFilter;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.ProblemBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The monitoring routes' refusals that need a body {@code GatewayErrorAdvice} does not build: a {@code reason}, a cap, a
 * projected row count, a {@code Retry-After}. It handles ONLY the monitoring exceptions below, so it changes nothing for any
 * other route, and it sits ahead of {@code GatewayErrorAdvice} (whose catch-all {@code Exception} handler would otherwise
 * claim them as {@code 500}).
 *
 * <p>⛔ Every {@code detail} is fixed text. A refusal names a closed code and, at most, a COUNT or a bound: never a metric
 * name, an instance, a tenant or a value from the request.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetricsErrorAdvice {

    @ExceptionHandler(MetricsRejectionException.class)
    public ResponseEntity<ExtendedProblemBody> handleRejection(MetricsRejectionException exception,
            HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.code().status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (exception.retryAfterSeconds() != null) {
            builder.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
        }
        return builder.body(new ExtendedProblemBody(ProblemBody.of(exception.code(), correlationId(request)),
                Map.of("reason", exception.reason())));
    }

    /** A request the data plane declines to compute: {@code 422}, the code the data plane named. */
    @ExceptionHandler(MetricsQueryRefusedException.class)
    public ResponseEntity<ExtendedProblemBody> handleRefused(MetricsQueryRefusedException exception,
            HttpServletRequest request) {
        ErrorCode code = codeOf(exception.refusal());
        ProblemBody problem = ProblemBody.withDetail(code, correlationId(request), detailOf(exception.refusal()));
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (exception.refusal() == QueryRefusal.SERIES_CAP_EXCEEDED && exception.cap() != null
                && exception.projected() != null && exception.seriesBy() != null) {
            extensions.put("cap", exception.cap());
            extensions.put("projected", exception.projected());
            extensions.put("seriesBy", seriesBy(exception.seriesBy()));
        }
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ExtendedProblemBody(problem, extensions));
    }

    /** The shared database budget stayed spent, or the statement timed out: the UI degrades, it never blanks. */
    @ExceptionHandler(MetricsQueryUnavailableException.class)
    public ResponseEntity<ProblemBody> handleUnavailable(MetricsQueryUnavailableException exception,
            HttpServletRequest request) {
        return unavailable(request);
    }

    /** {@code EXPORT_TOO_LARGE}: before any byte, or the stream's own count outran the limit. */
    @ExceptionHandler(ExportTooLargeException.class)
    public ResponseEntity<ExtendedProblemBody> handleTooLarge(ExportTooLargeException exception,
            HttpServletRequest request) {
        ProblemBody problem = ProblemBody.withDetail(ErrorCode.EXPORT_TOO_LARGE, correlationId(request),
                "the export would exceed the row limit; narrow the range");
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("projectedRows", exception.projectedRows());
        extensions.put("limit", exception.limit());
        return ResponseEntity.status(ErrorCode.EXPORT_TOO_LARGE.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ExtendedProblemBody(problem, extensions));
    }

    /**
     * Past one of the two export caps: {@code 429}, {@code export-concurrency}. The database budget staying spent is a
     * different condition ("the database is busy", not "you already have an export running"): {@code 503}.
     */
    @ExceptionHandler(ExportBusyException.class)
    public ResponseEntity<?> handleExportBusy(ExportBusyException exception, HttpServletRequest request) {
        if (exception.reason() == ExportBusyException.Reason.DATABASE_BUDGET) {
            return unavailable(request);
        }
        return handleRejection(new MetricsRejectionException(ErrorCode.RATE_LIMITED,
                MetricsRejectionException.EXPORT_CONCURRENCY, 5), request);
    }

    /**
     * A dashboard write the control plane refused. Its message is a fixed, caller-safe sentence that never echoes the
     * submitted document ({@code InvalidDashboardException}'s own contract), so it may be the {@code detail}. The status
     * is the CONTRACT's, not the exception's javadoc's: an invalid document or layout is {@code 400} (the dashboard
     * routes list no {@code 422}), a stale revision {@code 409}, an unknown widget {@code 404}.
     */
    @ExceptionHandler(InvalidDashboardException.class)
    public ResponseEntity<ProblemBody> handleInvalidDashboard(InvalidDashboardException exception,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        ProblemBody body = switch (exception.kind()) {
            case STALE_REVISION -> ProblemBody.withDetail(ErrorCode.CONFLICT, correlationId,
                    "the dashboard changed since it was read; reload it and try again");
            case UNKNOWN_WIDGET -> ProblemBody.of(ErrorCode.NOT_FOUND, correlationId);
            case INVALID -> ProblemBody.withDetail(ErrorCode.BAD_REQUEST, correlationId, exception.getMessage());
        };
        return ResponseEntity.status(body.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * Every bounded export thread is busy (W4-09): the same {@code 429 export-concurrency} as a spent data-plane cap,
     * because to the caller it is the same condition ("too many exports running"), and it is refused before a byte.
     */
    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<?> handleExportThreadsBusy(org.springframework.core.task.TaskRejectedException exception,
            HttpServletRequest request) {
        return handleRejection(new MetricsRejectionException(ErrorCode.RATE_LIMITED,
                MetricsRejectionException.EXPORT_CONCURRENCY, 5), request);
    }

    private static ResponseEntity<ProblemBody> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(ErrorCode.SERVICE_UNAVAILABLE, correlationId(request),
                        "the metrics query side could not answer now; retry"));
    }

    static ErrorCode codeOf(QueryRefusal refusal) {
        return switch (refusal) {
            case SERIES_CAP_EXCEEDED -> ErrorCode.SERIES_CAP_EXCEEDED;
            case AGGREGATION_NOT_APPLICABLE -> ErrorCode.AGGREGATION_NOT_APPLICABLE;
            case GROUP_BY_REQUIRES_AGGREGATION -> ErrorCode.GROUP_BY_REQUIRES_AGGREGATION;
            case STAT_REQUIRES_AGGREGATION -> ErrorCode.STAT_REQUIRES_AGGREGATION;
            case METRIC_UNTYPED -> ErrorCode.METRIC_UNTYPED;
        };
    }

    private static String detailOf(QueryRefusal refusal) {
        return switch (refusal) {
            case SERIES_CAP_EXCEEDED -> "the request would render more series than the cap; narrow the selection";
            case AGGREGATION_NOT_APPLICABLE -> "that aggregation cannot apply to this metric's type";
            case GROUP_BY_REQUIRES_AGGREGATION -> "groupBy requires an aggregation across instances";
            case STAT_REQUIRES_AGGREGATION -> "a single value over more than one instance requires an aggregation";
            case METRIC_UNTYPED -> "the metric has no declared type for the selected instances";
        };
    }

    static String seriesBy(RangeQuery.SeriesBy seriesBy) {
        return seriesBy.name().toLowerCase(Locale.ROOT);
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
