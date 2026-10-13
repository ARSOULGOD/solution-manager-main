package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.util.WebUtils;

/**
 * Resolves the resource of an operation authorized at the scope its BODY names: {@code scanHostKeys} and
 * {@code registerTarget} evaluate {@code target.create} at the body's {@code ownershipScope} (plan 013 T1.4; D-REG
 * sections 4 and 8 step 1).
 *
 * <p>⚠️ {@link ResourceResolver} says a body is unreadable in {@code preHandle}. That is true of Spring's own binding, and
 * it is why every other resolver reads the path. Here the body IS read, by {@link PeekableBodyRequest}, which {@link
 * PeekableBodyFilter} puts in front of every unsafe request: the bytes are held (bounded) and handed to the handler again
 * unchanged. Authorization still happens in the interceptor, before the handler, which is the property the interceptor
 * exists to guarantee.
 *
 * <p>Only the one {@code ownershipScope} object is read here. Anything else about the body is the handler's to validate;
 * a body this cannot read as a scope is {@code 400} (it would be one anyway), and a request that did not come through
 * the filter fails CLOSED rather than being authorized at a guess.
 *
 * <p>⛔ {@code I5}: the tenant on the reference is the session's own; the body names a node, never an organization, and
 * the authorization service answers {@code 404} for a node outside the caller's organization exactly as for an absent one.
 *
 * <p><b>Parser differential.</b> The scope authorized here is recorded on the request ({@link #AUTHORIZED}); the handler
 * derives its own from the bound body and {@link #requireAuthorized} refuses to act unless the two are the same node. Two
 * parsers reading one body should agree, and this makes "should" into "must".
 */
@Component
public final class OwnershipScopeResolver implements ResourceResolver {

    /** The request attribute holding the {@link ResourceRef} this resolver authorized. */
    static final String AUTHORIZED = OwnershipScopeResolver.class.getName() + ".authorized";

    private static final String FIELD = "ownershipScope";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        PeekableBodyRequest peekable = WebUtils.getNativeRequest(request, PeekableBodyRequest.class);
        if (peekable == null) {
            // Fail closed: a route wired without the filter must not be authorized at a scope nobody read.
            throw new IllegalStateException("the request body is not peekable; PeekableBodyFilter is not in front");
        }
        ScopeRef scope = scopeOf(peekable.peek());
        ResourceRef resource = OwnershipScope.of(scope, session.organizationId()).resource(session.organizationId());
        request.setAttribute(AUTHORIZED, resource);
        return resource;
    }

    private static ScopeRef scopeOf(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode node = root == null ? null : root.get(FIELD);
            if (node == null || !node.isObject()) {
                throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
            }
            return MAPPER.treeToValue(node, ScopeRef.class);
        } catch (IOException | IllegalArgumentException e) {
            // the message of a parse failure may quote the body; nothing of it is kept
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
    }

    /**
     * The handler's check that it acts on the node that was authorized.
     *
     * @throws IllegalStateException the request carries no authorized scope, or the bound body names another node
     */
    static void requireAuthorized(ScopeRef bound, java.util.UUID organizationId) {
        Objects.requireNonNull(bound, "bound");
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object authorized = attributes == null ? null
                : attributes.getAttribute(AUTHORIZED, RequestAttributes.SCOPE_REQUEST);
        ResourceRef expected = OwnershipScope.of(bound, organizationId).resource(organizationId);
        if (!expected.equals(authorized)) {
            // never a message built from the request
            throw new IllegalStateException("the scope acted on is not the scope that was authorized");
        }
    }
}
