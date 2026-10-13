package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Builds the {@link RequestOrigin} passed into every call to a state-changing cp-api port —
 * {@code LocalAuthenticationService.authenticate}, {@code SessionAuthentication.revoke},
 * {@code CredentialRotation.rotate} — per rulings {@code OR-19} and {@code OR-20}.
 *
 * <p>⛔ <b>Both fields are server-determined, never caller-supplied.</b> {@code correlationId} is
 * read from the attribute {@link CorrelationIdFilter} already set on the current request — that
 * filter runs at {@code HIGHEST_PRECEDENCE}, before any handler, and never adopts a caller-sent
 * header. {@code clientAddress} comes from {@link ClientAddressResolver}, which trusts {@code
 * X-Forwarded-For} only from a configured trusted proxy.
 *
 * <p>Reads the current request via {@link RequestContextHolder} rather than taking one as a
 * parameter, so the handler methods that call {@link #create()} keep exactly the parameter list
 * their OpenAPI operation signature has ({@code SchemaMirrorLockTest} pins those) — no transport
 * plumbing leaks into a wire-facing method signature.
 */
public final class RequestOriginFactory {

    private final ClientAddressResolver clientAddressResolver;

    public RequestOriginFactory(ClientAddressResolver clientAddressResolver) {
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver");
    }

    public RequestOrigin create() {
        HttpServletRequest request = currentRequest();
        String clientAddress = clientAddressResolver.resolve(request);
        UUID correlationId = correlationId(request);
        return new RequestOrigin(clientAddress, correlationId);
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (!(value instanceof UUID uuid)) {
            throw new IllegalStateException(
                    "no correlation id attribute on the request; CorrelationIdFilter must run before any handler");
        }
        return uuid;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
        return ((ServletRequestAttributes) attributes).getRequest();
    }
}
