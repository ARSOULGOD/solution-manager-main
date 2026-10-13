package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link HierarchyNameConflictException} as {@code 409 CONFLICT} (plan 013 T2.3; {@code OR-123}). It handles ONLY
 * that type, so it changes nothing for any other route, and it sits ahead of {@link GatewayErrorAdvice} (whose catch-all
 * {@code Exception} handler would otherwise claim the exception as a {@code 500});
 * {@link TargetRegistrationErrorAdvice} is the precedent.
 *
 * <p>⛔ {@code detail} is fixed text per node kind. The name a caller sent is never echoed, and the exception carries no
 * cause (PostgreSQL's unique-violation text reproduces the key).
 *
 * <p>An organization's detail does NOT say "sibling": organization names are unique across the install (V015's
 * {@code organization_name_ci_uq}), so the clash is with any organization, not a sibling. Only an install-bound
 * principal can reach it ({@code OR-125} (3) renames an organization at install scope only; plan 013, W2 review
 * W2D-2), so it discloses nothing across a tenant boundary.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HierarchyErrorAdvice {

    @ExceptionHandler(HierarchyNameConflictException.class)
    public ResponseEntity<ProblemBody> handleNameConflict(HierarchyNameConflictException exception,
            HttpServletRequest request) {
        String detail = switch (exception.kind()) {
            case ORGANIZATION -> "an organization of this installation already has this name "
                    + "(compared without regard to case)";
            case SUB_ORGANIZATION -> "a sibling sub-organization already has this name (compared without regard to case)";
            case ENVIRONMENT -> "a sibling environment already has this name (compared without regard to case)";
            default -> throw new IllegalStateException("only a hierarchy node has a name conflict");
        };
        return ResponseEntity.status(ErrorCode.CONFLICT.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(ErrorCode.CONFLICT, correlationId(request), detail));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
