package com.solace.sm.gateway.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mints a fresh server-side correlation id for every request, before the rest of the chain runs,
 * and marks every response as never cacheable.
 *
 * <p>⛔ A caller-supplied {@code X-Correlation-Id} is never adopted as the server's id — this
 * filter does not read the incoming header at all, so there is nothing to trust or distrust.
 * Registered at the highest filter precedence ({@link GatewayWebConfiguration}) so both the
 * correlation id header and {@code Cache-Control}/{@code Pragma} are present on every response —
 * success, error, an unmapped route, a CSRF/origin rejection — including one produced by a later
 * filter's own failure, since nothing downstream runs before this filter sets them.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = "com.solace.sm.gateway.http.correlationId";
    private static final String MDC_KEY = "correlation_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlationId = UUID.randomUUID();
        response.setHeader(CORRELATION_ID_HEADER, correlationId.toString());
        // Every response is a session/authorization/audit-bearing API response, never a static
        // asset — no path under this filter's mapping may ever be cached by a browser or an
        // intermediary.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        MDC.put(MDC_KEY, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
