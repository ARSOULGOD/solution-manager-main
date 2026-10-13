package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The body-scope resolver (plan 013 T1.4): what it authorizes, what it refuses, and that it fails CLOSED. */
class OwnershipScopeResolverTest {

    private final UUID organization = UUID.randomUUID();
    private final Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
    private final OwnershipScopeResolver resolver = new OwnershipScopeResolver();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static PeekableBodyRequest request(String body) {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/cp/targets");
        raw.setContent(body.getBytes(StandardCharsets.UTF_8));
        return new PeekableBodyRequest(raw);
    }

    @Test
    void anEnvironmentIdIsAuthorizedAsThatEnvironmentForTheSessionsTenant() {
        UUID env = UUID.randomUUID();
        PeekableBodyRequest request = request("{\"name\":\"x\",\"ownershipScope\":{\"environmentId\":\"" + env + "\"}}");

        ResourceRef resource = resolver.resolve(request, session);

        assertEquals(ResourceRef.of(ResourceKind.ENVIRONMENT, env, new TenantId(organization)), resource);
        assertSame(resource, request.getAttribute(OwnershipScopeResolver.AUTHORIZED));
    }

    @Test
    void anEmptyScopeIsTheSessionsOrganization() {
        ResourceRef resource = resolver.resolve(request("{\"ownershipScope\":{}}"), session);
        assertEquals(ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization)), resource);
    }

    @Test
    void theDeepestIdWins() {
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        ResourceRef resource = resolver.resolve(request("{\"ownershipScope\":{\"subOrganizationId\":\"" + sub
                + "\",\"environmentId\":\"" + env + "\"}}"), session);
        assertEquals(ResourceKind.ENVIRONMENT, resource.kind());
        assertEquals(env, resource.id());
    }

    @Test
    void aBodyItCannotReadAsAScopeIs400() {
        for (String body : new String[] {"", "not json", "[]", "{}", "{\"ownershipScope\":null}",
                "{\"ownershipScope\":\"x\"}", "{\"ownershipScope\":{\"environmentId\":\"nope\"}}",
                "{\"ownershipScope\":{\"organizationId\":\"" + UUID.randomUUID() + "\"}}",
                "{\"ownershipScope\":{\"bogus\":1}}"}) {
            GatewayRejectionException refused = assertThrows(GatewayRejectionException.class,
                    () -> resolver.resolve(request(body), session), body);
            assertEquals(ErrorCode.BAD_REQUEST, refused.code(), body);
        }
    }

    @Test
    void aSolutionOrAnInstanceIsNotAPlaceATargetIsOwned() {
        assertThrows(DeclareValidationException.class, () -> resolver.resolve(
                request("{\"ownershipScope\":{\"solutionId\":\"" + UUID.randomUUID() + "\"}}"), session));
        assertThrows(DeclareValidationException.class, () -> resolver.resolve(
                request("{\"ownershipScope\":{\"instanceId\":\"" + UUID.randomUUID() + "\"}}"), session));
    }

    @Test
    void aRequestThatDidNotComeThroughTheFilterIsNeverAuthorizedAtAGuess() {
        MockHttpServletRequest unwrapped = new MockHttpServletRequest("POST", "/api/cp/targets");
        unwrapped.setContent("{\"ownershipScope\":{}}".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(unwrapped, session));
    }

    @Test
    void theHandlerRefusesToActOnAScopeOtherThanTheOneThatWasAuthorized() {
        UUID env = UUID.randomUUID();
        PeekableBodyRequest request = request("{\"ownershipScope\":{\"environmentId\":\"" + env + "\"}}");
        resolver.resolve(request, session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        OwnershipScopeResolver.requireAuthorized(new ScopeRef(null, env, null, null), organization);       // the same node
        // a parser differential (or a mis-wired route) that hands the handler a different node is refused
        assertThrows(IllegalStateException.class, () -> OwnershipScopeResolver.requireAuthorized(
                new ScopeRef(null, UUID.randomUUID(), null, null), organization));
        assertThrows(IllegalStateException.class,
                () -> OwnershipScopeResolver.requireAuthorized(ScopeRef.ORGANIZATION, organization));
    }

    @Test
    void withNoAuthorizationRecordedTheHandlerRefuses() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/cp/targets")));
        assertThrows(IllegalStateException.class,
                () -> OwnershipScopeResolver.requireAuthorized(ScopeRef.ORGANIZATION, organization));
    }
}
