package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.ConsoleOperations;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.Harness;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S0 point 24 (plan 010 T1.3): the fifth classification. A {@code reach} operation is refused
 * BEFORE its handler unless the verb is held at one or more grants in the session's organization,
 * and it resolves NO resource — the one thing a list over many scopes cannot name.
 */
class ReachClassificationTest {

    private static final String PATH = GatewayTestSupport.ReachFixtureController.INSTANCES_PATH;

    private final ObjectMapper mapper = new ObjectMapper();
    private Harness harness;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        harness = GatewayTestSupport.newHarness();
        mvc = harness.mockMvc();
    }

    @Test
    void everyReachOperationClassifiesAsReachWithItsRegisteredVerb() {
        for (String operationId : OperationPolicy.reachOperations()) {
            OperationClassification classification = OperationPolicy.classify(operationId);
            OperationClassification.Reach reach = assertInstanceOf(OperationClassification.Reach.class,
                    classification, operationId);
            assertEquals(ConsoleOperations.permissionFor(operationId).orElseThrow(), reach.permission(), operationId);
        }
    }

    @Test
    void aCatalogOperationThatIsNotReachStaysCatalog() {
        assertInstanceOf(OperationClassification.Catalog.class, OperationPolicy.classify("getInstance"));
        assertInstanceOf(OperationClassification.Catalog.class, OperationPolicy.classify("declareInstance"));
    }

    @Test
    void theReachPreCheckAdmitsTheRequestAndResolvesNoResource() throws Exception {
        harness.withSession("tok-reach-ok", "csrf", UUID.randomUUID(), false);

        mvc.perform(get(PATH).cookie(new Cookie("__Host-SM_SESSION", "tok-reach-ok"))).andExpect(status().isOk());

        FakeAuthorizationService authorization = harness.authorizationService();
        assertEquals(java.util.List.of(Permission.INSTANCE_VIEW), authorization.reachChecks,
                "the pre-check is made with the operation's own verb");
        assertTrue(authorization.resources.isEmpty() && authorization.permissions.isEmpty(),
                "a reach operation never calls the single-resource require: it has no resource to name");
        assertEquals(1, harness.reachInvocationCount());
    }

    @Test
    void aPrincipalHoldingTheVerbNowhereIsRefusedBeforeTheHandler() throws Exception {
        harness.withSession("tok-reach-deny", "csrf", UUID.randomUUID(), false);
        harness.authorizationService().outcome = FakeAuthorizationService.Outcome.DENY;

        mvc.perform(get(PATH).cookie(new Cookie("__Host-SM_SESSION", "tok-reach-deny")))
                .andExpect(status().isForbidden())
                .andExpect(result -> {
                    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
                    assertEquals("FORBIDDEN", body.get("code").asText());
                });

        assertEquals(0, harness.reachInvocationCount(), "the refusal is pre-handler");
    }

    @Test
    void theReachPreCheckUsesTheSessionsPrincipalNeverARequestField() throws Exception {
        var session = harness.withSession("tok-reach-who", "csrf", UUID.randomUUID(), false);

        mvc.perform(get(PATH + "?principalId=" + UUID.randomUUID())
                .cookie(new Cookie("__Host-SM_SESSION", "tok-reach-who"))).andExpect(status().isOk());

        assertEquals(java.util.List.of(session.principal()), harness.authorizationService().reachPrincipals);
    }

    @Test
    void aMustRotateSessionIsStoppedByTheRotationGateBeforeTheReachCheck() throws Exception {
        harness.withSession("tok-reach-mr", "csrf", UUID.randomUUID(), true);

        mvc.perform(get(PATH).cookie(new Cookie("__Host-SM_SESSION", "tok-reach-mr")))
                .andExpect(status().is(428));

        assertTrue(harness.authorizationService().reachChecks.isEmpty(), "must-rotate precedes authorization");
        assertEquals(java.util.List.of(Permission.INSTANCE_VIEW), harness.forcedRotationGate().catalogChecks,
                "the gate sees the reach operation's verb, exactly as for a catalog operation");
    }

    @Test
    void anUnauthenticatedRequestIsRefusedBeforeTheReachCheck() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        assertTrue(harness.authorizationService().reachChecks.isEmpty());
    }
}
