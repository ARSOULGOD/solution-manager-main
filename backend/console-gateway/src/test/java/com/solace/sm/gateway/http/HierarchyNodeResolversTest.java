package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.session.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The resolvers of plan 013 T2.3's hierarchy and entitlement routes: each names the node the PATH names, under the
 * SESSION's tenant, and refuses ({@code 404}) a path whose organization is not the session's before the authorizer is
 * asked anything ({@code I5}: the service trusts the organization it is handed).
 */
class HierarchyNodeResolversTest {

    private final UUID organization = UUID.randomUUID();
    private final Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);

    private static MockHttpServletRequest request(Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, new HashMap<>(variables));
        return request;
    }

    @Test
    void theOrganizationResolverNamesThePathOrganizationUnderTheSessionsTenant() {
        ResourceRef ref = new OrganizationNodeResolver()
                .resolve(request(Map.of("organizationId", organization.toString())), session);
        assertEquals(ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization)), ref);
    }

    @Test
    void theSubOrganizationResolverNamesThePathSubOrganization() {
        UUID sub = UUID.randomUUID();
        ResourceRef ref = new SubOrganizationNodeResolver().resolve(request(Map.of(
                "organizationId", organization.toString(), "subOrganizationId", sub.toString())), session);
        assertEquals(ResourceRef.of(ResourceKind.SUB_ORGANIZATION, sub, new TenantId(organization)), ref);
    }

    @Test
    void theEnvironmentResolverNamesThePathEnvironment() {
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        ResourceRef ref = new EnvironmentNodeResolver().resolve(request(Map.of(
                "organizationId", organization.toString(), "subOrganizationId", sub.toString(),
                "environmentId", env.toString())), session);
        assertEquals(ResourceRef.of(ResourceKind.ENVIRONMENT, env, new TenantId(organization)), ref);
    }

    @Test
    void aPathOrganizationThatIsNotTheSessionsIsNotFoundForEveryResolver() {
        Map<String, String> foreign = Map.of("organizationId", UUID.randomUUID().toString(),
                "subOrganizationId", UUID.randomUUID().toString(), "environmentId", UUID.randomUUID().toString());
        assertThrows(ResourceNotFoundException.class, () -> new OrganizationNodeResolver().resolve(request(foreign), session));
        assertThrows(ResourceNotFoundException.class, () -> new SubOrganizationNodeResolver().resolve(request(foreign), session));
        assertThrows(ResourceNotFoundException.class, () -> new EnvironmentNodeResolver().resolve(request(foreign), session));
    }

    @Test
    void anIdThatIsNotAUuidOrIsMissingNamesNothing() {
        assertThrows(ResourceNotFoundException.class,
                () -> new OrganizationNodeResolver().resolve(request(Map.of("organizationId", "nope")), session));
        assertThrows(ResourceNotFoundException.class,
                () -> new SubOrganizationNodeResolver().resolve(request(Map.of(
                        "organizationId", organization.toString(), "subOrganizationId", "nope")), session));
        assertThrows(ResourceNotFoundException.class,
                () -> new EnvironmentNodeResolver().resolve(request(Map.of(
                        "organizationId", organization.toString(), "subOrganizationId", UUID.randomUUID().toString())),
                        session));
        assertThrows(ResourceNotFoundException.class,
                () -> new OrganizationNodeResolver().resolve(new MockHttpServletRequest(), session));
    }

    @Test
    void theInstallResolverNamesTheAdministratorRoleRowUnderTheSessionsTenantAndTakesNothingFromTheRequest() {
        ResourceRef ref = new InstallResourceResolver().resolve(new MockHttpServletRequest(), session);
        assertEquals(ResourceKind.ROLE_DEFINITION, ref.kind());
        assertEquals(new TenantId(organization), ref.tenant());
        assertEquals(EntitlementAdministration.INSTALL_ANCHOR_ROLE, ref.id());
        assertEquals(UUID.fromString("00000000-0000-4000-8000-000000000001"), ref.id(),
                "V003's built-in Administrator: the row the resolver of ROLE_DEFINITION must find");
    }

    @Test
    void theInstallOrganizationResolverNamesTheInstallAnchorWhateverOrganizationThePathNamesButRefusesAMalformedId() {
        // W5 review W5C-3: shape only; the id is not compared with the session's (the 403 must not depend on it)
        for (UUID named : new UUID[] {organization, UUID.randomUUID()}) {
            ResourceRef ref = new InstallOrganizationResolver()
                    .resolve(request(Map.of("organizationId", named.toString())), session);
            assertEquals(ResourceRef.of(ResourceKind.ROLE_DEFINITION, EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                    new TenantId(organization)), ref);
        }
        assertThrows(ResourceNotFoundException.class,
                () -> new InstallOrganizationResolver().resolve(request(Map.of("organizationId", "nope")), session));
        assertThrows(ResourceNotFoundException.class,
                () -> new InstallOrganizationResolver().resolve(new MockHttpServletRequest(), session));
    }
}
