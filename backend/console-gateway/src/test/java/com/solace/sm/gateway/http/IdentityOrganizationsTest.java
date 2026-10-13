package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.hierarchy.VisibleOrganization;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.gateway.http.GatewayTestSupport.Harness;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code OR-116}: {@code Identity.organizations} on {@code login} and {@code getIdentity}.
 *
 * <p>The visibility RULE is {@code ScopeVisibleHierarchyDirectory}'s and is proven against a real
 * database in {@code HierarchyDirectoryIT} and the launcher's {@code HierarchyEndpointsIT}; what
 * is proven here is the gateway's part — that the wire carries exactly what the directory returned
 * (ids and names, nested), that an empty answer is {@code []} and never absent, and the documented
 * forced-rotation policy: the field is populated only when the hierarchy lists would answer, so
 * for a {@code mustRotate} session the directory is not even consulted.
 */
class IdentityOrganizationsTest {

    private static final String LOGIN = "/api/cp/auth/login";
    private static final String IDENTITY = "/api/cp/auth/identity";

    private final ObjectMapper mapper = new ObjectMapper();

    private Harness harness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        harness = GatewayTestSupport.newHarness();
        mvc = harness.mockMvc();
    }

    private static VisibleOrganization organization(UUID id, String name, ScopeNode... subs) {
        return new VisibleOrganization(new ScopeNode(id, name), List.of(subs));
    }

    @Test
    void getIdentityCarriesTheVisibleOrganizationsAndSubOrganizationsWithIdsAndNames() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        harness.hierarchyDirectory().visibleToReturn = List.of(organization(organizationId, "Acme",
                new ScopeNode(subA, "Payments"), new ScopeNode(subB, "Logistics")));
        harness.withSession("tok-org", "csrf-org", organizationId, false);

        JsonNode body = body(mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-org")))
                .andExpect(status().isOk()).andReturn());

        JsonNode organizations = body.get("organizations");
        assertEquals(1, organizations.size());
        assertEquals(organizationId.toString(), organizations.at("/0/id").asText());
        assertEquals("Acme", organizations.at("/0/name").asText());
        assertEquals(2, organizations.at("/0/subOrganizations").size());
        assertEquals(subA.toString(), organizations.at("/0/subOrganizations/0/id").asText());
        assertEquals("Payments", organizations.at("/0/subOrganizations/0/name").asText());
        assertEquals(subB.toString(), organizations.at("/0/subOrganizations/1/id").asText());
        assertEquals("Logistics", organizations.at("/0/subOrganizations/1/name").asText());
    }

    @Test
    void aCallerWithNoBindingsGetsAnEmptyArrayNotAnAbsentField() throws Exception {
        harness.hierarchyDirectory().visibleToReturn = List.of();
        harness.withSession("tok-none", "csrf-none", UUID.randomUUID(), false);

        JsonNode body = body(mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-none")))
                .andExpect(status().isOk()).andReturn());

        assertTrue(body.has("organizations"), "required by the contract: present even when empty");
        assertTrue(body.get("organizations").isArray());
        assertEquals(0, body.get("organizations").size());
    }

    @Test
    void whileMustRotateTheFieldIsEmptyAndTheDirectoryIsNotConsulted() throws Exception {
        UUID organizationId = UUID.randomUUID();
        harness.hierarchyDirectory().visibleToReturn = List.of(organization(organizationId, "Acme"));
        harness.withSession("tok-rot", "csrf-rot", organizationId, true);

        JsonNode body = body(mvc.perform(get(IDENTITY).cookie(new Cookie("__Host-SM_SESSION", "tok-rot")))
                .andExpect(status().isOk()).andReturn());

        assertEquals(0, body.get("organizations").size(),
                "the hierarchy lists answer 428 until rotation, so the identity field is empty too");
        assertTrue(harness.hierarchyDirectory().visibleOrganizationsSessions.isEmpty());
        assertTrue(body.get("mustRotate").asBoolean());
    }

    @Test
    void loginCarriesTheSameFieldComputedForTheSessionItJustEstablished() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        harness.hierarchyDirectory().visibleToReturn =
                List.of(organization(organizationId, "Acme", new ScopeNode(sub, "Payments")));
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organizationId, false);
        harness.localAuthenticationService().onAuthenticate("operator",
                () -> new LocalLoginResult(new SessionToken("session-token-value"),
                        new CsrfToken("csrf-token-value"), session));

        JsonNode body = body(mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content("{\"organization\":\"acme\",\"username\":\"operator\",\"credential\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn());

        assertEquals(organizationId.toString(), body.at("/organizations/0/id").asText());
        assertEquals(sub.toString(), body.at("/organizations/0/subOrganizations/0/id").asText());
        assertEquals(List.of(session), harness.hierarchyDirectory().visibleOrganizationsSessions,
                "the directory is asked about the authenticated session, never a client-supplied organization");
    }

    @Test
    void loginWhileMustRotateIsEmptyAndDoesNotConsultTheDirectory() throws Exception {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), true);
        harness.hierarchyDirectory().visibleToReturn = List.of(organization(UUID.randomUUID(), "Acme"));
        harness.localAuthenticationService().onAuthenticate("bootstrap",
                () -> new LocalLoginResult(new SessionToken("session-token-value"),
                        new CsrfToken("csrf-token-value"), session));

        JsonNode body = body(mvc.perform(post(LOGIN)
                        .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                        .contentType("application/json")
                        .content("{\"organization\":\"acme\",\"username\":\"bootstrap\",\"credential\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn());

        assertEquals(0, body.get("organizations").size());
        assertTrue(harness.hierarchyDirectory().visibleOrganizationsSessions.isEmpty());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
