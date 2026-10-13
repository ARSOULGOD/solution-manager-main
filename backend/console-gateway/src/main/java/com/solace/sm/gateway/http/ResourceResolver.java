package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the {@link ResourceRef} a CATALOG {@link ConsoleOperation} acts on, from the request
 * that reached it.
 *
 * <p>Declared per operation via {@link ConsoleOperation#resource()} and looked up as a Spring
 * bean, by type. {@link AuthenticationInterceptor#preHandle} invokes it BEFORE the handler method
 * ever runs, so a CATALOG operation cannot reach its handler body without its resource resolving
 * and being authorized first.
 *
 * <p>URI template variables (e.g. an {@code {instanceId}} path segment) are available on
 * {@code request} via
 * {@code request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)}
 * as a {@code Map<String, String>} — Spring MVC sets that attribute while mapping the request,
 * strictly before any {@code HandlerInterceptor}'s {@code preHandle} runs.
 *
 * <p>⛔ <b>This runs in {@code preHandle}, BEFORE the body is ever read.</b> Spring MVC does not
 * parse {@code @RequestBody} until the handler method itself is invoked, which is strictly after
 * every {@code HandlerInterceptor#preHandle} — including this resolution — has already returned.
 * A resource identity a {@link ConsoleOperation#resource()} implementation resolves must therefore
 * come from the path, the query string, or the session — never from the request body, which is
 * not merely inconvenient to read here but genuinely unread and unreadable at this point.
 *
 * <p>⛔ <b>Never put request-derived content into an exception message.</b> A failure here —
 * including {@link ResourceNotFoundException} below, and any {@code RuntimeException} a resolver
 * lets escape — is caught by the 500 path, which logs the exception (message included). A message
 * built from path/query content an attacker controls would land in that log verbatim.
 */
public interface ResourceResolver {

    /**
     * @throws ResourceNotFoundException if the resource the request names does not exist — the
     *         interceptor renders this as {@code 404 NOT_FOUND}, indistinguishable from "exists
     *         but out of scope" ({@code AuthorizationService#require}'s own javadoc explains why).
     *         Its message must never be built from request-derived content — see this interface's
     *         own javadoc.
     */
    ResourceRef resolve(HttpServletRequest request, Session session);
}
