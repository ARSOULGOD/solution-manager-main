package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.identity.AssignmentConflictException;
import com.solace.sm.cp.api.identity.AssignmentInvalidException;
import com.solace.sm.cp.api.identity.PrincipalConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders the three refusals of user and role administration (plan 013 T3.2): {@link PrincipalConflictException} and
 * {@link AssignmentConflictException} as {@code 409 CONFLICT}, {@link AssignmentInvalidException} as {@code 422
 * VALIDATION_FAILED}. It handles ONLY those types, so it changes nothing for any other route, and it sits ahead of
 * {@link GatewayErrorAdvice} (whose catch-all {@code Exception} handler would otherwise claim them as a {@code 500});
 * {@link HierarchyErrorAdvice} is the precedent.
 *
 * <p>⛔ {@code detail} is the exception's own message, which is fixed text per {@code Reason}: it carries no username, no
 * id and no cause (a driver's unique-violation text reproduces the key). A {@code USERNAME_TAKEN} names no organization:
 * the index is per organization ({@code OR-125} (4)), so the {@code 409} says only that a user of the caller's own
 * organization has the name.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserAdministrationErrorAdvice {

    @ExceptionHandler({PrincipalConflictException.class, AssignmentConflictException.class})
    public ResponseEntity<ProblemBody> handleConflict(RuntimeException exception, HttpServletRequest request) {
        return problem(ErrorCode.CONFLICT, exception, request);
    }

    @ExceptionHandler(AssignmentInvalidException.class)
    public ResponseEntity<ProblemBody> handleInvalid(AssignmentInvalidException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, exception, request);
    }

    private static ResponseEntity<ProblemBody> problem(ErrorCode code, RuntimeException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(code, correlationId(request), exception.getMessage()));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
