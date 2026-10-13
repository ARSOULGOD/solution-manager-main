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
 * Renders {@link AgentRefusedException} only, ahead of {@link GatewayErrorAdvice}'s catch-all (the precedent is
 * {@link TargetRegistrationErrorAdvice}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentErrorAdvice {

    @ExceptionHandler(AgentRefusedException.class)
    public ResponseEntity<ProblemBody> handleRefused(AgentRefusedException exception, HttpServletRequest request) {
        ErrorCode code = exception.reason().code();
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(code, correlationId(request), exception.detail()));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
