package com.solace.sm.gateway.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link ConfigViewRefusedException} only; ahead of the catch-all like {@code LogExportErrorAdvice}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigViewErrorAdvice {

    @ExceptionHandler(ConfigViewRefusedException.class)
    public ResponseEntity<ProblemBody> handle(ConfigViewRefusedException exception, HttpServletRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        UUID correlationId = id instanceof UUID uuid ? uuid : UUID.randomUUID();
        return ResponseEntity.status(exception.code().status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(exception.code(), correlationId, exception.detail()));
    }
}
