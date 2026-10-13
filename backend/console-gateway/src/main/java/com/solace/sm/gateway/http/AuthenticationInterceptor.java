package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.identity.CredentialRotationRequiredException;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionAuthentication;
import com.solace.sm.cp.api.session.SessionToken;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The authenticated HTTP boundary's one enforcement point, in the exact order the contract
 * requires: origin → {@code OR-24} rate gate (login/rotateOwnCredential only) → session →
 * must-rotate → CSRF → authorization.
 *
 * <p>⛔ <b>Authorization is enforced HERE, mandatorily, not in the handler.</b> A CATALOG
 * operation's {@link ConsoleOperation#resource()} names a {@link ResourceResolver} bean; this
 * interceptor resolves the resource from the request and calls
 * {@link AuthorizationService#require} BEFORE the handler method is ever invoked. There is no
 * handler-side hook left to call instead, and none to forget — the {@code RequestAuthorizer} this
 * class used to defer to was removed for exactly that reason. A {@code reach} operation
 * ({@link OperationClassification.Reach}) has no one resource, so its pre-handler check is
 * {@link ReachAuthorization#requireHeldAnywhere} instead, and its handler owes an
 * {@code authorizedSubset} filter. {@link OperationCoverageValidator}
 * guarantees at startup that every CATALOG operation names a resolver whose bean exists, so this
 * interceptor never reaches a CATALOG operation with nothing to resolve against.
 */
public final class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String SESSION_ATTRIBUTE = "com.solace.sm.gateway.http.session";

    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String CSRF_HEADER = "X-SM-CSRF";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    private final GatewayProperties properties;
    private final SessionAuthentication sessionAuthentication;
    private final ForcedRotationGate forcedRotationGate;
    private final AuthorizationService authorizationService;
    private final ReachAuthorization reachAuthorization;
    private final ApplicationContext context;
    private final AuthRateGate authRateGate;

    public AuthenticationInterceptor(GatewayProperties properties, SessionAuthentication sessionAuthentication,
            ForcedRotationGate forcedRotationGate, AuthorizationService authorizationService,
            ReachAuthorization reachAuthorization, ApplicationContext context, AuthRateGate authRateGate) {
        this.properties = properties;
        this.sessionAuthentication = sessionAuthentication;
        this.forcedRotationGate = forcedRotationGate;
        this.authorizationService = authorizationService;
        this.reachAuthorization = reachAuthorization;
        this.context = context;
        this.authRateGate = authRateGate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // MF18 (plan 011 P11-7.2), MEASURED by ExportAsyncTimeoutIT: Spring runs preHandle AGAIN on the ASYNC re-dispatch
        // that completes a StreamingResponseBody, so one export took two authorization decisions. The re-dispatch is the
        // container finishing a request this method already admitted on its REQUEST dispatch (a client cannot cause one),
        // the handler is not run again, and the session, CSRF and authorization were all settled then: running them again
        // adds a second decision and no protection.
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // The console is same-origin only and supports no CORS preflight, so every OPTIONS
        // request is a flat 405 — checked before anything else, including handler type, because
        // Spring answers OPTIONS on a mapped route with its own synthetic HandlerMethod (a
        // private HttpOptionsHandler) that carries no @ConsoleOperation and would otherwise fall
        // through to the "unclassifiable" 500 below.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            throw new GatewayRejectionException(ErrorCode.METHOD_NOT_ALLOWED);
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Fail closed: the only non-HandlerMethod handler this interceptor ever approves is
            // NoRouteHandler, let through to render its own 404. Any OTHER handler here — a
            // static-resource handler, a stray HandlerMapping bean, anything else that slipped
            // past OperationCoverageValidator's startup check — must not be silently
            // authenticated-bypassed. It is rendered as the same NOT_FOUND ProblemBody directly,
            // via NoRouteHandler.render, rather than thrown as a GatewayRejectionException:
            // GatewayErrorAdvice's @ExceptionHandler advice never applies to a non-HandlerMethod
            // handler (see NoRouteHandler's javadoc), so a thrown exception here would escape
            // unhandled to the container's default error page instead of this shape.
            if (handler instanceof NoRouteHandler) {
                return true;
            }
            NoRouteHandler.render(request, response);
            return false;
        }
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), ConsoleOperation.class);
        if (annotation == null) {
            // OperationCoverageValidator should have refused startup before this is reachable.
            throw new GatewayRejectionException(ErrorCode.INTERNAL_ERROR);
        }
        OperationClassification classification = OperationPolicy.classify(annotation.value());
        boolean sessionEstablishing = classification instanceof OperationClassification.SessionEstablishing;
        boolean unsafe = !SAFE_METHODS.contains(request.getMethod());

        // (0) Origin validation for unsafe methods — including login, which has no session yet.
        if (unsafe) {
            validateOrigin(request);
        }

        // (0.5) OR-24 pre-authentication rate gate — login and rotateOwnCredential ONLY, and
        // before ANY cp-api port call, including session resolution below (SessionAuthentication.
        // resolve is itself a port call). Never consulted for any other operation.
        if (isRateGated(classification)) {
            authRateGate.check(request);
        }

        // (1) Session resolution.
        Session session = null;
        if (!sessionEstablishing) {
            session = resolveSession(request).orElseThrow(() -> new GatewayRejectionException(ErrorCode.UNAUTHENTICATED));
            request.setAttribute(SESSION_ATTRIBUTE, session);
        }

        // (2) Must-rotate gate.
        if (session != null) {
            checkMustRotate(session, classification);
        }

        // (3) CSRF — unsafe methods only, and never for session-establishing operations.
        if (unsafe && !sessionEstablishing) {
            requireCsrf(request, session);
        }

        // (4) Authorization — CATALOG operations only, mandatory and pre-handler. The resource is
        // resolved from the request and the permission checked here; a denial or a not-found
        // throws before this method returns, so the handler is never invoked.
        if (classification instanceof OperationClassification.Catalog catalog) {
            authorize(request, session, annotation, catalog);
        } else if (classification instanceof OperationClassification.Reach reach) {
            // S0 point 24: no single resource authorizes a list over many scopes. The verb must be
            // held at one or more grants in the session's own organization; the handler then filters
            // its items by reach. This is a pre-check, never an authorization of any item.
            reachAuthorization.requireHeldAnywhere(session.principal(), reach.permission());
        }

        return true;
    }

    /**
     * {@code OR-24}: the rate gate applies to exactly {@code login} (session-establishing) and
     * {@code rotateOwnCredential} ({@link LocalOperation#ROTATE_OWN_CREDENTIAL}) — never
     * {@code logout}, {@code getIdentity}, or any CATALOG operation.
     */
    private static boolean isRateGated(OperationClassification classification) {
        if (classification instanceof OperationClassification.SessionEstablishing) {
            return true;
        }
        return classification instanceof OperationClassification.Local local
                && local.operation() == LocalOperation.ROTATE_OWN_CREDENTIAL;
    }

    private void authorize(HttpServletRequest request, Session session, ConsoleOperation annotation,
            OperationClassification.Catalog catalog) {
        ResourceResolver resolver = context.getBean(annotation.resource());
        ResourceRef resource = resolver.resolve(request, session);
        authorizationService.require(session.principal(), catalog.permission(), resource);
    }

    private void checkMustRotate(Session session, OperationClassification classification) {
        try {
            if (classification instanceof OperationClassification.Local local) {
                forcedRotationGate.check(session, local.operation());
            } else if (classification instanceof OperationClassification.Catalog catalog) {
                forcedRotationGate.check(session, catalog.permission());
            } else if (classification instanceof OperationClassification.Reach reach) {
                forcedRotationGate.check(session, reach.permission());
            } else if (classification instanceof OperationClassification.AuthenticationOnly) {
                // Authenticated, but none of LocalOperation's three rotation-exempt operations —
                // fails closed exactly like a catalog verb would (ForcedRotationGate#check(Session)'s
                // own javadoc: "fails closed for an authenticated operation that carries neither a
                // catalog verb nor a local operation").
                forcedRotationGate.check(session);
            }
            // OperationClassification.SessionEstablishing: no session existed to check against.
        } catch (CredentialRotationRequiredException e) {
            throw new GatewayRejectionException(ErrorCode.MUST_ROTATE);
        }
    }

    private void requireCsrf(HttpServletRequest request, Session session) {
        String presented = request.getHeader(CSRF_HEADER);
        if (presented == null || presented.isBlank()
                || !sessionAuthentication.verifyCsrf(session.id(), new CsrfToken(presented))) {
            throw new GatewayRejectionException(ErrorCode.CSRF_REJECTED);
        }
    }

    private void validateOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String candidate = (origin != null && !origin.isBlank()) ? origin : originOfReferer(request);
        if (candidate == null || !properties.getTrustedOrigins().contains(candidate)) {
            throw new GatewayRejectionException(ErrorCode.ORIGIN_REJECTED);
        }
    }

    /**
     * Normalizes scheme and host to lowercase and drops an explicit default port, so a Referer
     * that is byte-for-byte what a browser actually sends still matches a {@code trustedOrigins}
     * entry, which {@link GatewayProperties} enforces is always in that same normalized shape.
     */
    private static String originOfReferer(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean explicitDefaultPort = (port == 80 && "http".equals(scheme))
                    || (port == 443 && "https".equals(scheme));
            String portSuffix = (port == -1 || explicitDefaultPort) ? "" : ":" + port;
            return scheme + "://" + host + portSuffix;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<Session> resolveSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String token = null;
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                if (token != null) {
                    // Two __Host-SM_SESSION cookies is ambiguous, and ambiguity is what cookie
                    // tossing exploits. The __Host- prefix (OR-21) already forbids a Domain
                    // attribute and requires Path=/ and Secure, so a sibling subdomain cannot get
                    // the browser to store a second cookie under this exact name for this exact
                    // host in the first place — but this check is kept as defence in depth
                    // regardless: trust neither candidate rather than silently picking one.
                    return Optional.empty();
                }
                token = cookie.getValue();
            }
        }
        return token == null ? Optional.empty() : sessionAuthentication.resolve(new SessionToken(token));
    }
}
