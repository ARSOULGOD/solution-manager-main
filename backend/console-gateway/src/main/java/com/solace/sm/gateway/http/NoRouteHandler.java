package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.Controller;

/**
 * The lowest-priority fallback for any request no {@code @RequestMapping} handler claims.
 *
 * <p>Deliberately an old-style {@link Controller} registered on its own {@code HandlerMapping}
 * (see {@link GatewayWebConfiguration}), not a {@code @RequestMapping}-annotated method. It is
 * invisible to {@link AuthenticationInterceptor} (interceptors registered via
 * {@code WebMvcConfigurer} attach only to the annotation-driven mapping, never to this hand-built
 * one) — an unmapped route needs no authentication to answer a plain {@code 404}. It is NOT
 * invisible to {@link OperationCoverageValidator}: that validator walks every
 * {@code HandlerMapping} bean, not just {@code RequestMappingHandlerMapping}'s handler methods,
 * and recognizes this exact mapping by shape ({@code isTheGatewaysOwnNoRouteHandlerMapping}) —
 * deliberately exempting it rather than failing to see it — because a mapping that serves nothing
 * but this unconditional-404 handler needs no {@code @ConsoleOperation} either.
 *
 * <p>⚠️ Renders the {@link ProblemBody} directly rather than throwing, deliberately. Measured
 * (bytecode of {@code ExceptionHandlerExceptionResolver.shouldApplyTo} / {@code
 * AbstractHandlerMethodExceptionResolver.shouldApplyTo} in spring-webmvc 6.2.19): that resolver
 * only special-cases {@code ResourceHttpRequestHandler} and {@code HandlerFunction} handlers; for
 * any other non-{@code HandlerMethod} handler — exactly what an old-style {@link Controller} is —
 * it returns {@code false} and never invokes {@code @ExceptionHandler} advice at all. A thrown
 * exception here would therefore propagate unhandled instead of being rendered by
 * {@link GatewayErrorAdvice}, so this class renders the same {@link ProblemBody} shape itself —
 * and {@link AuthenticationInterceptor} reuses {@link #render} for exactly the same reason, for
 * the ONE other handler shape that reaches it without ever being a {@code HandlerMethod}.
 */
public final class NoRouteHandler implements Controller {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ModelAndView handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        render(request, response);
        return null;
    }

    /**
     * Renders the {@code NOT_FOUND} {@link ProblemBody} shape directly onto {@code response} —
     * the ONE {@link ObjectMapper} configuration this shape is ever written with. Shared with
     * {@link AuthenticationInterceptor}'s non-{@code HandlerMethod}, non-{@code NoRouteHandler}
     * branch, which needs the identical rendering for the identical reason documented on this
     * class: neither handler shape ever reaches {@link GatewayErrorAdvice}.
     */
    static void render(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemBody body = ProblemBody.of(ErrorCode.NOT_FOUND, correlationId(request));
        response.setStatus(ErrorCode.NOT_FOUND.status());
        response.setContentType("application/problem+json");
        MAPPER.writeValue(response.getWriter(), body);
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
