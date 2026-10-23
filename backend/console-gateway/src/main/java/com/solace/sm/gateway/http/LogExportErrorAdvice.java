package com.solace.sm.gateway.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link LogExportUnavailableException} only; ahead of the catch-all like {@code MetricsErrorAdvice}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogExportErrorAdvice {

    @ExceptionHandler(LogExportUnavailableException.class)
    public ResponseEntity<ProblemBody> handle(LogExportUnavailableException exception, HttpServletRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        UUID correlationId = id instanceof UUID uuid ? uuid : UUID.randomUUID();
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(ErrorCode.SERVICE_UNAVAILABLE, correlationId, exception.detail()));
    }
}
