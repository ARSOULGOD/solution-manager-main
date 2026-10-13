package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.util.WebUtils;

/**
 * Resolves {@code grantAssignment} to the node the body's {@code scope} names, the scope {@code role.assign} is evaluated
 * at (plan 013 T3.2; D-REG sections 4 and 5.4 U6): a principal bound at a sub-organization can grant inside it, and finds
 * the organization and a sibling refused or absent.
 *
 * <p>⚠️ Like {@link OwnershipScopeResolver}, which this follows, it READS the body in {@code preHandle} through
 * {@link PeekableBodyRequest} (a {@link ResourceResolver} cannot otherwise see one), reads only the one {@code scope}
 * object, and fails CLOSED when the request did not come through {@link PeekableBodyFilter}. The scope authorized is
 * recorded on the request ({@link #AUTHORIZED}) and the handler's {@link #requireAuthorized} refuses to act unless the
 * scope it derives from the BOUND body is the same node: two parsers reading one body should agree, and this makes
 * "should" into "must".
 *
 * <p>⛔ {@code I5}: the organization is the session's. The body names a node, never an organization; the authorization
 * service answers {@code 404} for a node outside the caller's organization exactly as for an absent one. The service
 * authorizes again at the same node and checks that the ancestors the request claims are the node's real ones.
 */
@Component
public final class GrantedScopeResolver implements ResourceResolver {

    /** The request attribute holding the {@link ResourceRef} this resolver authorized. */
    static final String AUTHORIZED = GrantedScopeResolver.class.getName() + ".authorized";

    private static final String FIELD = "scope";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        PeekableBodyRequest peekable = WebUtils.getNativeRequest(request, PeekableBodyRequest.class);
        if (peekable == null) {
            // Fail closed: a route wired without the filter must not be authorized at a scope nobody read.
            throw new IllegalStateException("the request body is not peekable; PeekableBodyFilter is not in front");
        }
        ScopePath path = scopeOf(peekable.peek(), session.organizationId());
        ResourceRef resource = GrantScopes.nodeAt(path, session.organizationId());
        request.setAttribute(AUTHORIZED, resource);
        return resource;
    }

    private static ScopePath scopeOf(byte[] body, UUID organizationId) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode node = root == null ? null : root.get(FIELD);
            if (node == null || !node.isObject()) {
                throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
            }
            return GrantScopes.pathOf(MAPPER.treeToValue(node, ScopeRef.class), organizationId);
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
    static void requireAuthorized(ScopePath bound, UUID organizationId) {
        Objects.requireNonNull(bound, "bound");
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object authorized = attributes == null ? null
                : attributes.getAttribute(AUTHORIZED, RequestAttributes.SCOPE_REQUEST);
        if (!GrantScopes.nodeAt(bound, organizationId).equals(authorized)) {
            // never a message built from the request
            throw new IllegalStateException("the scope acted on is not the scope that was authorized");
        }
    }
}
