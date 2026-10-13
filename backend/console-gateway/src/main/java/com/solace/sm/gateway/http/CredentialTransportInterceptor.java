package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a {@link CredentialBearing} request that did not arrive over a secure channel, BEFORE its body is read (plan 013
 * OQ-14 (b); D-REG section 6.1): {@code 403 INSECURE_TRANSPORT}, fixed text.
 *
 * <p>Why it is needed: {@code OR-21}'s {@code __Host-} cookies constrain what a BROWSER sends, not what Postman or a
 * misconfigured proxy sends, and any of those can carry a private key in clear. A request passes if exactly one of these
 * holds:
 * <ul>
 *   <li>the container says the connection is secure ({@code request.isSecure()});</li>
 *   <li>the socket peer is a configured {@code sm.gateway.trusted-proxies} entry and the proxy's own
 *       {@code X-Forwarded-Proto} is {@code https} (the last value, the nearest proxy's: the same trust rule
 *       {@link ClientAddressResolver} applies to {@code X-Forwarded-For});</li>
 *   <li>the socket peer is loopback, which covers the local stack.</li>
 * </ul>
 *
 * <p>⚠️ <b>The two container values this reads are trustworthy only because the launcher pins
 * {@code server.forward-headers-strategy=none}</b> (review {@code W3C-1}). Left to Spring Boot's default, a deployment on a
 * detected cloud platform (Kubernetes, Cloud Foundry, Heroku, Azure App Service, Nomad) gets Tomcat's {@code RemoteIpValve},
 * which rewrites {@code isSecure()} and {@code getRemoteAddr()} from {@code X-Forwarded-Proto} and {@code X-Forwarded-For}
 * for any private-range peer, bypassing this class's trusted-proxy rule. The launcher's {@code ForwardedHeadersLockdown}
 * fails startup unless the strategy is {@code none}. A trusted proxy must also OVERWRITE {@code X-Forwarded-Proto} with its
 * own listener's scheme; one that passes a client's value through lets the client assert {@code https}.
 *
 * <p>⚠️ <b>A trusted proxy's word replaces the loopback allowance.</b> A proxy on the same machine is loopback to this
 * process, and a request that crossed the network in clear to it must not pass for the local stack, so when the peer is a
 * trusted proxy only its {@code X-Forwarded-Proto} counts. (This is narrower than the plan's three alternatives read
 * independently: a loopback-addressed trusted proxy forwarding {@code http} is refused.) A peer that is not a trusted
 * proxy cannot assert anything with the header: it is ignored.
 *
 * <p>This is an interceptor, not a servlet filter, so it keys on the handler's {@link CredentialBearing} mark rather than
 * on a path pattern that a URL variant could miss. It runs before {@link AuthenticationInterceptor} (registration order)
 * and before Spring reads any body.
 */
public final class CredentialTransportInterceptor implements HandlerInterceptor {

    private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private final GatewayProperties properties;

    public CredentialTransportInterceptor(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && AnnotatedElementUtils.hasAnnotation(method.getMethod(), CredentialBearing.class)
                && !isSecureChannel(request)) {
            throw new GatewayRejectionException(ErrorCode.INSECURE_TRANSPORT);
        }
        return true;
    }

    boolean isSecureChannel(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        Optional<String> peer = ClientAddresses.canonicalize(request.getRemoteAddr());
        if (peer.isEmpty()) {
            return false;      // a peer that is not an address cannot be shown to be local
        }
        if (properties.getTrustedProxies().contains(peer.get())) {
            return "https".equals(lastForwardedProto(request));
        }
        return isLoopback(peer.get());
    }

    private static String lastForwardedProto(HttpServletRequest request) {
        Enumeration<String> fields = request.getHeaders(FORWARDED_PROTO);
        if (fields == null) {
            return null;
        }
        List<String> all = Collections.list(fields);
        if (all.isEmpty()) {
            return null;
        }
        String[] values = String.join(",", all).split(",");
        return values[values.length - 1].trim().toLowerCase(Locale.ROOT);
    }

    /** {@code literal} is an IP literal ({@link ClientAddresses#canonicalize} produced it), so nothing is resolved. */
    private static boolean isLoopback(String literal) {
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
