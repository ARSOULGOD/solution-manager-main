package com.solace.sm.gateway.http;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Keeps {@link LogExportAbortedException} from being resolved (plan 016 task 10, re-review N-1). The catch-all
 * {@code GatewayErrorAdvice} would otherwise write a JSON problem body into the committed download and let the chunked body
 * end cleanly, which would read as a complete file. This handler runs first and rethrows: Spring then leaves the exception
 * unresolved, and the container, with a committed response, closes the connection without the chunked terminator, so the
 * client sees a failed download after the fixed {@code incomplete} marker.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogExportAbortAdvice {

    @ExceptionHandler(LogExportAbortedException.class)
    public void abort(LogExportAbortedException aborted) {
        throw aborted;
    }
}
