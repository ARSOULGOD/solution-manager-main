package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeHierarchyDirectory;
import com.solace.sm.gateway.http.GatewayTestSupport.Harness;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of console-integration.md §5.2 rows 8-10 — the three authentication-only
 * hierarchy reads — over the real filter → interceptor → argument-resolver → controller → advice
 * pipeline {@link GatewayTestSupport} assembles, with {@link FakeHierarchyDirectory} standing in
 * for the cp-api port.
 */
class HierarchyControllerTest {

    private static final String ORGANIZATIONS = "/api/cp/hierarchy/organizations";
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";

    private final ObjectMapper mapper = new ObjectMapper();

    private Harness harness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        harness = GatewayTestSupport.newHarness();
        mvc = harness.mockMvc();
    }

    private static String subOrgsPath(UUID organizationId) {
        return "/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs";
    }

    private static String environmentsPath(UUID organizationId, UUID subOrganizationId) {
        return "/api/cp/hierarchy/organizations/" + organizationId + "/sub-orgs/" + subOrganizationId
                + "/environments";
    }

    // ── 401 without a session ───────────────────────────────────────────────────────────────

    @Test
    void listOrganizationsWithNoSessionIsUnauthenticated() throws Exception {
        mvc.perform(get(ORGANIZATIONS)).andExpect(status().isUnauthorized());
        assertTrue(harness.hierarchyDirectory().sessionsSeen.isEmpty(),
                "the port must never be called for an unauthenticated request");
    }

    @Test
    void listSubOrganizationsWithNoSessionIsUnauthenticated() throws Exception {
        mvc.perform(get(subOrgsPath(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        assertTrue(harness.hierarchyDirectory().sessionsSeen.isEmpty());
    }

    @Test
    void listEnvironmentsWithNoSessionIsUnauthenticated() throws Exception {
        mvc.perform(get(environmentsPath(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        assertTrue(harness.hierarchyDirectory().sessionsSeen.isEmpty());
    }

    // ── 428 while must_rotate ────────────────────────────────────────────────────────────────

    @Test
    void listOrganizationsWhileMustRotateIsRefused() throws Exception {
        harness.withSession("tok-must-rotate", "csrf-must-rotate", UUID.randomUUID(), true);

        mvc.perform(get(ORGANIZATIONS).cookie(new Cookie(SESSION_COOKIE, "tok-must-rotate")))
                .andExpect(status().is(428));
        assertTrue(harness.hierarchyDirectory().sessionsSeen.isEmpty(),
                "must-rotate must be refused BEFORE the port is ever called — unlike "
                        + "getIdentity/logout/rotateOwnCredential, these three are NOT rotation-exempt");
    }

    // ── success path, and no verb is ever consulted ────────────────────────────────────────

    @Test
    void listOrganizationsReturnsWhatThePortReturnsAndConsultsNoAuthorizationVerb() throws Exception {
        UUID organizationId = UUID.randomUUID();
        Session session = harness.withSession("tok-orgs", "csrf-orgs", organizationId, false);
        harness.hierarchyDirectory().nodesToReturn = List.of(new ScopeNode(organizationId, "ACME"));

        String body = mvc.perform(get(ORGANIZATIONS).cookie(new Cookie(SESSION_COOKIE, "tok-orgs")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        assertTrue(json.get("stateVersion") == null,
                "ScopeNodeList carries no stateVersion (OR-38) — a non-entity response is exempt");
        assertEquals(1, json.get("items").size());
        assertEquals(organizationId.toString(), json.at("/items/0/id").asText());
        assertEquals("ACME", json.at("/items/0/name").asText());
        assertEquals(List.of(session), harness.hierarchyDirectory().sessionsSeen);
        assertTrue(harness.authorizationService().principals.isEmpty(),
                "these three operations are authentication-only — AuthorizationService.require must "
                        + "never be called for them");
    }

    @Test
    void listSubOrganizationsPassesThePathOrganizationIdThrough() throws Exception {
        UUID organizationId = UUID.randomUUID();
        harness.withSession("tok-suborgs", "csrf-suborgs", organizationId, false);

        mvc.perform(get(subOrgsPath(organizationId)).cookie(new Cookie(SESSION_COOKIE, "tok-suborgs")))
                .andExpect(status().isOk());

        assertEquals(organizationId, harness.hierarchyDirectory().lastOrganizationId);
    }

    @Test
    void listEnvironmentsPassesBothPathIdsThrough() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID subOrganizationId = UUID.randomUUID();
        harness.withSession("tok-envs", "csrf-envs", organizationId, false);

        mvc.perform(get(environmentsPath(organizationId, subOrganizationId))
                        .cookie(new Cookie(SESSION_COOKIE, "tok-envs")))
                .andExpect(status().isOk());

        assertEquals(organizationId, harness.hierarchyDirectory().lastOrganizationId);
        assertEquals(subOrganizationId, harness.hierarchyDirectory().lastSubOrganizationId);
    }

    // ── 404 for an out-of-scope or nonexistent parent ──────────────────────────────────────

    @Test
    void listSubOrganizationsOutOfScopeIs404() throws Exception {
        UUID organizationId = UUID.randomUUID();
        harness.withSession("tok-oos", "csrf-oos", organizationId, false);
        harness.hierarchyDirectory().outcome = FakeHierarchyDirectory.Outcome.NOT_FOUND;

        mvc.perform(get(subOrgsPath(organizationId)).cookie(new Cookie(SESSION_COOKIE, "tok-oos")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listEnvironmentsOutOfScopeIs404() throws Exception {
        UUID organizationId = UUID.randomUUID();
        harness.withSession("tok-oos-env", "csrf-oos-env", organizationId, false);
        harness.hierarchyDirectory().outcome = FakeHierarchyDirectory.Outcome.NOT_FOUND;

        mvc.perform(get(environmentsPath(organizationId, UUID.randomUUID()))
                        .cookie(new Cookie(SESSION_COOKIE, "tok-oos-env")))
                .andExpect(status().isNotFound());
    }
}
