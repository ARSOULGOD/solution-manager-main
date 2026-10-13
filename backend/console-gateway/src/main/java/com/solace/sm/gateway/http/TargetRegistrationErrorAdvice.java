package com.solace.sm.gateway.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link TargetRegistrationRefusedException}. It handles ONLY that type, so it changes nothing for any other
 * route, and it sits ahead of {@link GatewayErrorAdvice} (whose catch-all {@code Exception} handler would otherwise claim
 * the exception as a {@code 500}); {@code MetricsErrorAdvice} is the precedent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TargetRegistrationErrorAdvice {

    @ExceptionHandler(TargetRegistrationRefusedException.class)
    public ResponseEntity<ProblemBody> handleRefused(TargetRegistrationRefusedException exception,
            HttpServletRequest request) {
        ErrorCode code = exception.reason().code();
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(code, correlationId(request), exception.detail()));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
