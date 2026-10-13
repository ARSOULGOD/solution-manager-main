package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.ExportAudit.RefusalReason;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.AuthenticationInterceptor;
import com.solace.sm.gateway.http.CorrelationIdFilter;
import com.solace.sm.gateway.http.RequestOriginFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The refused export's {@code DENY} audit row (OR-112 (6), {@code W4-04}): an export refused with {@code 403}, {@code 422}
 * or {@code 429} writes one row of the verb {@code metrics.export}, outcome {@code deny}, with a fixed {@code reason_code}
 * and the request's correlation id. Before this, the plan, {@code ExportController} and {@code ExportAudit} each said "a
 * refusal is the interceptor's DENY row", and no such row existed anywhere.
 *
 * <p><b>Why an interceptor of its own, in {@code afterCompletion}.</b> The three statuses are produced in three places that
 * share nothing: {@code 403} by {@link AuthenticationInterceptor} before the handler runs, {@code 422} and the rate-gate
 * {@code 429} by the handler on the request thread, and the concurrency {@code 429} by the stream's own thread on the async
 * re-dispatch. The one point every one of them passes, after its exception has been turned into a response, is
 * {@code afterCompletion}, and the one fact that names the outcome is the status the client is about to see. Deciding by that
 * status means a later change of which code throws what cannot leave a refusal unaudited. This interceptor is registered
 * <b>ahead of</b> {@code AuthenticationInterceptor} (so its {@code afterCompletion} also runs when that interceptor throws),
 * and does nothing in {@code preHandle}.
 *
 * <p>What is NOT audited, by the ruling: a {@code 400} (a malformed request), a {@code 401} (no principal to name), a
 * {@code 404}, a {@code 503} (the shared database budget is a condition of the install, not of the caller), and any export
 * that ran (that is {@link ExportAudit#record}). Only {@code ExportController}'s route is looked at.
 *
 * <p>A failing audit write is logged by class alone and never replaces the refusal the client is owed.
 */
public final class ExportRefusalAuditInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(ExportRefusalAuditInterceptor.class);

    private final ExportAudit audit;
    private final RequestOriginFactory originFactory;

    public ExportRefusalAuditInterceptor(ExportAudit audit, RequestOriginFactory originFactory) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.originFactory = Objects.requireNonNull(originFactory, "originFactory");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != ExportController.class) {
            return;
        }
        RefusalReason reason = reasonOf(response.getStatus());
        // No session means the refusal came before there was a principal to name (a 401 is not one of the three, and a 403
        // that precedes the session, an origin or CSRF rejection, is not an export refusal: the caller is not known).
        if (reason == null || !(request.getAttribute(AuthenticationInterceptor.SESSION_ATTRIBUTE) instanceof Session session)) {
            return;
        }
        Object correlation = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        UUID correlationId = correlation instanceof UUID uuid ? uuid : UUID.randomUUID();
        try {
            RequestOrigin origin = originFactory.create();
            audit.refused(session.principal(), origin, new TenantId(session.organizationId()), correlationId, reason);
        } catch (RuntimeException failed) {
            // Never replaces the refusal the client is owed; the class alone is logged (a driver message can carry a host,
            // a role or a path).
            LOG.error("the refused-export audit row could not be written ({}), correlationId={}",
                    failed.getClass().getSimpleName(), correlationId);
        }
    }

    /** Exactly the statuses OR-112 (6) names; everything else, including a 400, 401, 404 and 503, is no DENY row. */
    private static RefusalReason reasonOf(int status) {
        return switch (status) {
            case 403 -> RefusalReason.FORBIDDEN;
            case 422 -> RefusalReason.TOO_LARGE;
            case 429 -> RefusalReason.RATE_LIMITED;
            default -> null;
        };
    }

    /** Registers the interceptor ahead of {@link AuthenticationInterceptor} (which is at the default order, 0). */
    public static WebMvcConfigurer registration(ExportRefusalAuditInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }
}
