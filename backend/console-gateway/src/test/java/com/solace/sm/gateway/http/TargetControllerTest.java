package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code listTargets} (plan 010 T2.7) over the real filter, interceptor, argument-resolver, controller and advice
 * pipeline, with the cp-api and dp-api ports faked. The point of every test here is the handler's second check: the
 * pre-handler reach pass authorizes nothing about any item, so the list must be filtered through
 * {@code ReachAuthorization#authorizedSubset} with the operation's own verb. The recording fake in
 * {@link GatewayTestSupport} reports an EMPTY subset by default, so a handler that skips the filter shows an empty
 * list and a handler that ignores its answer cannot show more than it was told.
 */
class TargetControllerTest {

    private static final String PATH = "/api/cp/targets";
    private static final String COOKIE = "__Host-SM_SESSION";

    private final ObjectMapper mapper = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeTargetDirectory directory;
    private FakeConnections connections;
    private FakeAuthorizationService authorization;
    private UUID organization;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true",
                "sm.gateway.auth-rate.capacity=10", "sm.gateway.auth-rate.refill-per-minute=10",
                "sm.gateway.auth-rate.max-tracked-keys=100000");
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        directory = context.getBean(FakeTargetDirectory.class);
        connections = context.getBean(FakeConnections.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        FakeTargetDirectory targetDirectory() {
            return new FakeTargetDirectory();
        }

        @Bean
        FakeConnections targetConnectionService() {
            return new FakeConnections();
        }

        @Bean
        TargetController targetController(TargetDirectory directory,
                com.solace.sm.cp.api.authz.ReachAuthorization reach, TargetConnectionService connections) {
            return new TargetController(directory, reach, connections);
        }
    }

    static final class FakeTargetDirectory implements TargetDirectory {
        List<TargetView> targets = new ArrayList<>();
        final List<UUID> organizationsAsked = new ArrayList<>();

        @Override
        public List<TargetView> listLive(UUID organizationId) {
            organizationsAsked.add(organizationId);
            return targets;
        }
    }

    static final class FakeConnections implements TargetConnectionService {
        final Map<UUID, TargetEnvelope> envelopes = new HashMap<>();
        final List<Collection<UUID>> asked = new ArrayList<>();

        @Override
        public void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey,
                UUID actor) {
            throw new AssertionError("a list never registers");
        }

        @Override
        public void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor) {
            throw new AssertionError("a list never rotates");
        }

        @Override
        public Map<UUID, TargetEnvelope> liveEnvelopes(UUID organizationId, Collection<UUID> targetIds) {
            asked.add(List.copyOf(targetIds));
            Map<UUID, TargetEnvelope> out = new HashMap<>();
            for (UUID id : targetIds) {
                if (envelopes.containsKey(id)) {
                    out.put(id, envelopes.get(id));
                }
            }
            return out;
        }
    }

    private TargetView target(String name, ScopePath owner) {
        return new TargetView(UUID.randomUUID(), name, "docker", "ssh-docker", owner);
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private JsonNode list(String token) throws Exception {
        String body = mvc.perform(get(PATH).cookie(new Cookie(COOKIE, token))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void listTargetsReturnsOnlyTargetsTheCallerHoldsTargetViewOn() throws Exception {
        Session session = login("tok-subset");
        ScopePath env = ScopePath.environment(organization, UUID.randomUUID(), UUID.randomUUID());
        TargetView mine = target("env-vm", env);
        TargetView other = target("other-env-vm", ScopePath.environment(organization, UUID.randomUUID(),
                UUID.randomUUID()));
        directory.targets = List.of(mine, other);
        authorization.authorizedIds.add(mine.targetId());

        JsonNode json = list("tok-subset");

        assertEquals(1, json.get("items").size());
        assertEquals(mine.targetId().toString(), json.at("/items/0/targetId").asText());
        assertEquals(1, authorization.subsetCalls.size(), "the handler must call authorizedSubset exactly once");
        FakeAuthorizationService.SubsetCall call = authorization.subsetCalls.get(0);
        assertEquals(Permission.TARGET_VIEW, call.permission(), "the operation's OWN verb, not another");
        assertEquals(ResourceKind.DEPLOYMENT_TARGET, call.kind());
        assertEquals(java.util.Set.of(mine.targetId(), other.targetId()), call.ids());
        assertEquals(session.principal(), call.principal());
        assertEquals(List.of(Permission.TARGET_VIEW), authorization.reachChecks, "the pre-check uses the same verb");
    }

    @Test
    void anUnauthorizedIdIsAbsentNotMerelyHidden() throws Exception {
        login("tok-none");
        TargetView unauthorized = target("not-mine", ScopePath.organization(organization));
        directory.targets = List.of(unauthorized);
        // authorizedIds stays empty: the recording fake's default

        JsonNode json = list("tok-none");

        assertEquals(0, json.get("items").size());
        assertFalse(json.toString().contains(unauthorized.targetId().toString()), json.toString());
        assertTrue(connections.asked.stream().allMatch(ids -> !ids.contains(unauthorized.targetId())),
                "the data plane is never asked about a target the caller cannot see");
    }

    @Test
    void theOrganizationIsTheSessionsOwnNeverARequestField() throws Exception {
        login("tok-org");
        mvc.perform(get(PATH + "?organizationId=" + UUID.randomUUID()).header("X-Organization", UUID.randomUUID())
                .cookie(new Cookie(COOKIE, "tok-org"))).andExpect(status().isOk());
        assertEquals(List.of(organization), directory.organizationsAsked);
    }

    @Test
    void listTargetsCarriesNoStateVersion() throws Exception {
        login("tok-version");
        TargetView t = target("vm", ScopePath.organization(organization));
        directory.targets = List.of(t);
        authorization.authorizedIds.add(t.targetId());
        connections.envelopes.put(t.targetId(), new TargetEnvelope("/srv/sm", "sm-"));

        JsonNode json = list("tok-version");

        assertNull(json.get("stateVersion"), "OR-38: a list's versions are per item, and a target has none");
        assertNull(json.at("/items/0/stateVersion").textValue());
        assertNull(json.get("availability"), "every listed target has a connection record: nothing to degrade");
    }

    @Test
    void aListedTargetCarriesItsKindTransportOwnerAndEnvelopeAndNothingFromTheConnection() throws Exception {
        login("tok-shape");
        ScopePath owner = ScopePath.environment(organization, UUID.randomUUID(), UUID.randomUUID());
        TargetView t = target("vm", owner);
        directory.targets = List.of(t);
        authorization.authorizedIds.add(t.targetId());
        connections.envelopes.put(t.targetId(), new TargetEnvelope("/srv/sm", "sm-"));

        JsonNode item = list("tok-shape").at("/items/0");

        assertEquals("docker", item.get("kind").asText());
        assertEquals("ssh-docker", item.get("transport").asText());
        assertEquals(owner.environment().toString(), item.at("/ownershipScope/environmentId").asText());
        assertEquals("/srv/sm", item.get("allowedConfigRoot").asText());
        assertEquals("sm-", item.get("allowedContainerPrefix").asText());
        for (String forbidden : new String[] {"host", "port", "sshUser", "hostKeyFingerprint", "credentialRef"}) {
            assertNull(item.get(forbidden), forbidden + " must never leave the data plane");
        }
    }

    @Test
    void aTargetWithNoConnectionRecordIsListedAsDegradedNotHidden() throws Exception {
        login("tok-degraded");
        TargetView connected = target("connected", ScopePath.organization(organization));
        TargetView bare = target("bare", ScopePath.organization(organization));
        directory.targets = List.of(connected, bare);
        authorization.authorizedIds.add(connected.targetId());
        authorization.authorizedIds.add(bare.targetId());
        connections.envelopes.put(connected.targetId(), new TargetEnvelope("/srv/sm", "sm-"));

        JsonNode json = list("tok-degraded");

        assertEquals(2, json.get("items").size(), "a degraded target is never hidden");
        assertEquals("degraded", json.at("/availability/state").asText());
        assertTrue(json.at("/availability/reason").asText().contains("no connection record"), json.toString());
        JsonNode bareItem = json.get("items").get(0).get("targetId").asText().equals(bare.targetId().toString())
                ? json.get("items").get(0) : json.get("items").get(1);
        assertEquals("bare", bareItem.get("name").asText());
        assertNull(bareItem.get("allowedConfigRoot"));
        assertNull(bareItem.get("allowedContainerPrefix"));
    }

    @Test
    void aCallerHoldingTheVerbNowhereIsRefusedBeforeTheHandler() throws Exception {
        login("tok-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        mvc.perform(get(PATH).cookie(new Cookie(COOKIE, "tok-deny"))).andExpect(status().isForbidden());

        assertTrue(directory.organizationsAsked.isEmpty(), "the handler never ran");
    }

    @Test
    void noSessionIsUnauthenticatedAndMustRotateIsRefused() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        Session rotating = GatewayTestSupport.newSession(UUID.randomUUID(), organization, true);
        context.getBean(FakeSessionAuthentication.class).register("tok-rotate", rotating, "csrf");
        mvc.perform(get(PATH).cookie(new Cookie(COOKIE, "tok-rotate"))).andExpect(status().is(428));

        assertTrue(directory.organizationsAsked.isEmpty());
    }
}
