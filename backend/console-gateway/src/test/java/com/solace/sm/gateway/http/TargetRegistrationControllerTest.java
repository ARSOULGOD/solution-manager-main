package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.hierarchy.VisibleOrganization;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.RegistrationPrecheck;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionAdministration;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code scanHostKeys}, {@code registerTarget} and {@code getTargetConnection} (plan 013 T1.4) over the real filter,
 * interceptor, resolver, controller, orchestrator and advice pipeline, with every cp-api and dp-api port faked.
 *
 * <p>What each test group proves: (1) the authorization is the interceptor's, at the node the BODY names, and a refusal
 * reaches no port; (2) {@code registerTarget} runs D-REG section 8's order and every refusal that can be decided before
 * a write leaves NOTHING; (3) the S5 sequence (a failure after the control-plane commit, the same {@code POST} completing,
 * a replay, a different key, the same key re-exported); (4) the private key is zeroised, and appears in no response.
 */
class TargetRegistrationControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A recognisable marker: if any byte of it shows up in a response or a captured line, the key leaked. */
    private static final String KEY_MARKER = "KEYMARKER-4f9c2a7e-do-not-leak";
    private static final String KEY_TEXT = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + KEY_MARKER
            + "\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String PIN = "SHA256:" + "A".repeat(43);
    private static final String OTHER_KEY_TYPE = "SHA256:" + "B".repeat(43);

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private Fakes fakes;
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
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*")
                .addFilter(new PeekableBodyFilter(), "/*").build();
        fakes = context.getBean(Fakes.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
        fakes.hierarchy.organization = organization;
    }

    // ---------------------------------------------------------------------------------------------- the rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        Fakes fakes() {
            return new Fakes();
        }

        @Bean
        TargetDirectory targetDirectory(Fakes fakes) {
            return fakes.targets;
        }

        @Bean
        TargetRegistration targetRegistration(Fakes fakes) {
            return fakes.targets;
        }

        @Bean
        TargetConnectionAdministration administration(Fakes fakes) {
            return fakes.admin;
        }

        @Bean
        HostKeyScanService hostKeyScanService(Fakes fakes) {
            return fakes.scanner;
        }

        @Bean
        TargetProbeAudit targetProbeAudit(Fakes fakes) {
            return fakes.audit;
        }

        @Bean
        @Primary
        HierarchyDirectory treeHierarchy(Fakes fakes) {
            return fakes.hierarchy;
        }

        @Bean
        ScanRateGate scanRateGate(Fakes fakes) {
            return new ScanRateGate(10, fakes.nanos::get);
        }

        @Bean
        TargetRegistrationOrchestrator targetRegistrationOrchestrator(Fakes fakes, RequestOriginFactory origins,
                ScanRateGate gate) {
            return new TargetRegistrationOrchestrator(fakes.targets, fakes.targets, fakes.admin, fakes.scanner,
                    fakes.audit, fakes.hierarchy, origins, gate);
        }

        @Bean
        OwnershipScopeResolver ownershipScopeResolver() {
            return new OwnershipScopeResolver();
        }

        @Bean
        TargetResolver targetResolver() {
            return new TargetResolver();
        }

        @Bean
        TargetRegistrationController targetRegistrationController(TargetRegistrationOrchestrator orchestrator) {
            return new TargetRegistrationController(orchestrator);
        }

        @Bean
        TargetRegistrationErrorAdvice targetRegistrationErrorAdvice() {
            return new TargetRegistrationErrorAdvice();
        }
    }

    /** One place for every fake, sharing one ordered event log so a test can assert the ORDER of the two planes. */
    static final class Fakes {
        final List<String> events = new ArrayList<>();
        final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
        final FakeTargets targets = new FakeTargets(events);
        final FakeAdmin admin = new FakeAdmin(events);
        final FakeScanner scanner = new FakeScanner(events);
        final FakeAudit audit = new FakeAudit();
        final FakeHierarchy hierarchy = new FakeHierarchy();
    }

    static final class FakeTargets implements TargetDirectory, TargetRegistration {
        final List<String> events;
        final List<TargetView> rows = new ArrayList<>();
        final List<Request> requests = new ArrayList<>();
        final List<PrincipalRef> actors = new ArrayList<>();
        final List<UUID> organizations = new ArrayList<>();
        final List<RequestOrigin> origins = new ArrayList<>();
        /** Plan 013 T1.5: a retired target leaves {@link #rows} (it is no longer listed) and is kept here with when. */
        final Map<UUID, TargetView> retiredViews = new HashMap<>();
        final Map<UUID, Instant> retiredAt = new HashMap<>();
        final List<UUID> retireRequests = new ArrayList<>();
        RuntimeException retireFailure;

        FakeTargets(List<String> events) {
            this.events = events;
        }

        @Override
        public Retired retire(PrincipalRef actor, UUID organizationId, UUID targetId, RequestOrigin origin) {
            events.add("cp.retire");
            retireRequests.add(targetId);
            actors.add(actor);
            organizations.add(organizationId);
            origins.add(origin);
            if (retireFailure != null) {
                RuntimeException failure = retireFailure;
                retireFailure = null;
                throw failure;
            }
            TargetView live = rows.stream().filter(row -> row.targetId().equals(targetId)).findFirst().orElse(null);
            if (live == null) {
                TargetView gone = retiredViews.get(targetId);
                if (gone == null) {
                    throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET);
                }
                return new Retired(gone, retiredAt.get(targetId), true);
            }
            rows.remove(live);
            retiredViews.put(targetId, live);
            retiredAt.put(targetId, Instant.parse("2026-10-08T12:00:00Z"));
            return new Retired(live, retiredAt.get(targetId), false);
        }

        @Override
        public List<TargetView> listLive(UUID organizationId) {
            return List.copyOf(rows);
        }

        @Override
        public TargetView register(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin) {
            events.add("cp.register");
            requests.add(request);
            actors.add(actor);
            organizations.add(organizationId);
            origins.add(origin);
            for (TargetView row : rows) {
                if (row.name().equals(request.name())) {
                    if (!row.kind().equals(request.kind()) || !row.ownershipScope().equals(request.ownershipScope())) {
                        throw new IllegalStateException("a target with this name is already registered differently");
                    }
                    return row;
                }
            }
            TargetView created = new TargetView(UUID.randomUUID(), request.name(), request.kind(),
                    request.transport(), request.ownershipScope());
            rows.add(created);
            return created;
        }
    }

    static final class FakeAdmin implements TargetConnectionAdministration {
        record Stored(TargetConnectionSpec spec, String keyId, Instant at) {
        }

        final List<String> events;
        final Map<UUID, Stored> stored = new HashMap<>();
        final List<char[]> keysSeen = new ArrayList<>();
        final List<UUID> replayingSeen = new ArrayList<>();
        final List<TargetChangeOrigin> originsSeen = new ArrayList<>();
        final List<TargetConnectionSpec> specsRegistered = new ArrayList<>();
        final List<UUID> organizationsSeen = new ArrayList<>();
        RuntimeException precheckFailure;
        RuntimeException registerFailureOnce;
        List<OfferedHostKey> offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"),
                new OfferedHostKey(OTHER_KEY_TYPE, "ssh-rsa"));

        FakeAdmin(List<String> events) {
            this.events = events;
        }

        @Override
        public RegistrationPrecheck precheckRegistration(UUID organizationId, UUID replayingTargetId,
                TargetConnectionSpec spec, char[] privateKey) {
            events.add("dp.precheck");
            organizationsSeen.add(organizationId);
            replayingSeen.add(replayingTargetId);
            keysSeen.add(privateKey);
            if (precheckFailure != null) {
                throw precheckFailure;
            }
            return new RegistrationPrecheck(offered, "SHA256:" + "P".repeat(43));
        }

        @Override
        public RegistrationOutcome registerConnection(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
                char[] privateKey, UUID actor, TargetChangeOrigin origin) {
            events.add("dp.register");
            organizationsSeen.add(organizationId);
            keysSeen.add(privateKey);
            originsSeen.add(origin);
            specsRegistered.add(spec);
            if (registerFailureOnce != null) {
                RuntimeException failure = registerFailureOnce;
                registerFailureOnce = null;
                throw failure;
            }
            String keyId = new String(privateKey).replace("\r\n", "\n").strip();
            Stored existing = stored.get(targetId);
            if (existing == null) {
                stored.put(targetId, new Stored(spec, keyId, Instant.parse("2026-10-08T00:00:00Z")));
                return RegistrationOutcome.CREATED;
            }
            if (!existing.spec().equals(spec)) {
                throw new IllegalStateException("different record");
            }
            if (!existing.keyId().equals(keyId)) {
                throw new TargetCredentialConflictException();
            }
            return RegistrationOutcome.REPLAYED;
        }

        @Override
        public Optional<TargetConnectionView> connection(UUID organizationId, UUID targetId) {
            Stored row = stored.get(targetId);
            if (row == null) {
                return Optional.empty();
            }
            TargetConnectionSpec spec = row.spec();
            return Optional.of(new TargetConnectionView(targetId, spec.host(), spec.port(), spec.sshUser(),
                    spec.hostKeyFingerprint(), List.copyOf(spec.allHostKeyFingerprints()), spec.allowedConfigRoot(),
                    spec.allowedContainerPrefix(), new TargetConnectionView.CredentialSummary(true,
                            "SHA256:" + "P".repeat(43), row.at())));
        }
    }

    static final class FakeScanner implements HostKeyScanService {
        final List<String> events;
        int calls;
        RuntimeException failure;
        List<OfferedHostKey> offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"),
                new OfferedHostKey(OTHER_KEY_TYPE, "ssh-rsa"));

        FakeScanner(List<String> events) {
            this.events = events;
        }

        @Override
        public List<OfferedHostKey> scan(String host, int port) {
            events.add("dp.scan");
            calls++;
            if (failure != null) {
                throw failure;
            }
            return offered;
        }
    }

    static final class FakeAudit implements TargetProbeAudit {
        record Row(PrincipalRef actor, RequestOrigin origin, TenantId organization, ResourceKind kind, UUID scopeId,
                ScanResult result) {
        }

        final List<Row> rows = new ArrayList<>();
        /** Plan 013 T1.5: the rescan of an edit and the connection test, recorded beside the scans. */
        record Other(String verb, PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId,
                String outcome) {
        }

        final List<Other> others = new ArrayList<>();

        @Override
        public void scanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, ResourceKind scopeKind,
                UUID scopeId, ScanResult result) {
            rows.add(new Row(actor, origin, organization, scopeKind, scopeId, result));
        }

        @Override
        public void rescanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId,
                ScanResult result) {
            others.add(new Other("rescan", actor, origin, organization, targetId, result.code()));
        }

        @Override
        public void tested(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId,
                TestResult result) {
            others.add(new Other("test", actor, origin, organization, targetId, result.code()));
        }
    }

    /** A two-level tree: sub-organizations, each with environments. Every read answers for the one organization. */
    static final class FakeHierarchy implements HierarchyDirectory {
        UUID organization;
        final Map<UUID, List<UUID>> environmentsBySub = new HashMap<>();

        @Override
        public List<ScopeNode> listOrganizations(Session session) {
            return List.of(new ScopeNode(organization, "org"));
        }

        @Override
        public List<ScopeNode> listSubOrganizations(Session session, UUID organizationId) {
            return environmentsBySub.keySet().stream().map(id -> new ScopeNode(id, "sub")).toList();
        }

        @Override
        public List<VisibleOrganization> visibleOrganizations(Session session) {
            return List.of();
        }

        @Override
        public List<ScopeNode> listEnvironments(Session session, UUID organizationId, UUID subOrganizationId) {
            List<UUID> envs = environmentsBySub.get(subOrganizationId);
            if (envs == null) {
                throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
            }
            return envs.stream().map(id -> new ScopeNode(id, "env")).toList();
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult send(String token, String path, String body) throws Exception {
        return mvc.perform(post(path).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static ObjectNode registerBody(String name, ObjectNode scope, String key) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name).put("kind", "docker").put("transport", "ssh-docker");
        body.set("ownershipScope", scope);
        ObjectNode connection = body.putObject("connection");
        connection.put("host", "10.0.0.7").put("port", 22).put("sshUser", "sm").put("hostKeyFingerprint", PIN)
                .put("allowedConfigRoot", "/srv/sm").put("allowedContainerPrefix", "sm-");
        body.put("privateKey", key);
        return body;
    }

    private static ObjectNode organizationScope() {
        return MAPPER.createObjectNode();
    }

    private static ObjectNode environmentScope(UUID environmentId) {
        return MAPPER.createObjectNode().put("environmentId", environmentId.toString());
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult register(String token, ObjectNode body) throws Exception {
        return send(token, "/api/cp/targets", body.toString());
    }

    // ---------------------------------------------------------------------------------- registerTarget: the path

    @Test
    void aRegistrationAuthorizesTargetCreateAtTheBodysScopeThenChecksThenWritesTheControlPlaneThenTheDataPlane()
            throws Exception {
        Session session = login("tok-happy");
        UUID sub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        fakes.hierarchy.environmentsBySub.put(sub, List.of(env));

        MvcResult result = register("tok-happy", registerBody("vm-1", environmentScope(env), KEY_TEXT));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        // authorization: target.create at the ENVIRONMENT the body named, for the session's tenant (I5), before any port
        assertEquals(List.of(Permission.TARGET_CREATE), authorization.permissions);
        assertEquals(ResourceRef.of(ResourceKind.ENVIRONMENT, env, new TenantId(organization)),
                authorization.resources.get(0));
        // D-REG section 8: pre-check (no write) -> control plane -> data plane
        assertEquals(List.of("dp.precheck", "cp.register", "dp.register"), fakes.events);
        // the control plane got the FULL path: the organization is the session's, the sub-organization is derived
        TargetRegistration.Request request = fakes.targets.requests.get(0);
        assertEquals(ScopePath.environment(organization, sub, env), request.ownershipScope());
        assertNull(request.targetId(), "the control plane mints the id; a client cannot name one");
        assertEquals(organization, fakes.targets.organizations.get(0));
        assertEquals(session.principal(), fakes.targets.actors.get(0));
        assertEquals(List.of(organization, organization), fakes.admin.organizationsSeen);
        // the data plane records every key the host offered (OR-89), the pin first
        assertEquals(List.of(PIN, OTHER_KEY_TYPE), List.copyOf(fakes.admin.specsRegistered.get(0).allHostKeyFingerprints()));
        // the change origin is the server's own: this request's correlation id, a resolved client address
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"),
                fakes.admin.originsSeen.get(0).correlationId().toString());

        JsonNode body = json(result);
        assertEquals("vm-1", body.get("name").asText());
        assertEquals("docker", body.get("kind").asText());
        assertEquals(sub.toString(), body.at("/ownershipScope/subOrganizationId").asText());
        assertEquals(env.toString(), body.at("/ownershipScope/environmentId").asText());
        assertEquals("/srv/sm", body.get("allowedConfigRoot").asText());
        assertEquals(PIN, body.at("/connection/hostKeyFingerprint").asText());
        assertEquals("SHA256:" + "P".repeat(43), body.at("/connection/credential/publicKeyFingerprint").asText());
        assertTrue(body.at("/connection/credential/present").asBoolean());
    }

    @Test
    void theDataPlaneIsToldTheTypeOfThePinnedKeyAsItsOwnPrecheckScanReportedItNeverAClientSuppliedOne() throws Exception {
        login("tok-type");
        fakes.admin.offered = List.of(new OfferedHostKey(OTHER_KEY_TYPE, "ssh-ed25519"), new OfferedHostKey(PIN, "ssh-rsa"));

        MvcResult result = register("tok-type", registerBody("vm-type", organizationScope(), KEY_TEXT));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(com.solace.sm.dp.api.inventory.HostKeyType.RSA, fakes.admin.specsRegistered.get(0).hostKeyType(),
                "OR-125 (2): the type of THE PIN (not of the first offered key), from the scan the data plane made");
    }

    @Test
    void aPinnedKeyOfATypeTheDataPlaneDoesNotPinByIsRegisteredWithNoType() throws Exception {
        login("tok-untyped");
        fakes.admin.offered = List.of(new OfferedHostKey(PIN, "unknown"));

        MvcResult result = register("tok-untyped", registerBody("vm-untyped", organizationScope(), KEY_TEXT));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertNull(fakes.admin.specsRegistered.get(0).hostKeyType());
    }

    @Test
    void anOrganizationScopedRegistrationNeedsNoTreeLookup() throws Exception {
        login("tok-org");

        MvcResult result = register("tok-org", registerBody("vm-org", organizationScope(), KEY_TEXT));

        assertEquals(201, result.getResponse().getStatus());
        assertEquals(ResourceRef.of(ResourceKind.ORGANIZATION, organization, new TenantId(organization)),
                authorization.resources.get(0), "an empty ownershipScope is the SESSION's organization (I5)");
        assertEquals(ScopePath.organization(organization), fakes.targets.requests.get(0).ownershipScope());
    }

    @Test
    void aSubOrganizationScopeIsAuthorizedAtTheSubOrganization() throws Exception {
        login("tok-sub");
        UUID sub = UUID.randomUUID();
        fakes.hierarchy.environmentsBySub.put(sub, List.of());

        MvcResult result = register("tok-sub", registerBody("vm-sub", MAPPER.createObjectNode()
                .put("subOrganizationId", sub.toString()), KEY_TEXT));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(ResourceRef.of(ResourceKind.SUB_ORGANIZATION, sub, new TenantId(organization)),
                authorization.resources.get(0));
        assertEquals(ScopePath.subOrganization(organization, sub), fakes.targets.requests.get(0).ownershipScope());
    }

    @Test
    void idsThatDoNotAgreeAreRefusedAs404AndNothingIsWritten() throws Exception {
        login("tok-disagree");
        UUID sub = UUID.randomUUID();
        UUID otherSub = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        fakes.hierarchy.environmentsBySub.put(sub, List.of(env));
        fakes.hierarchy.environmentsBySub.put(otherSub, List.of());

        MvcResult result = register("tok-disagree", registerBody("vm-x", MAPPER.createObjectNode()
                .put("subOrganizationId", otherSub.toString()).put("environmentId", env.toString()), KEY_TEXT));

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(List.of(), fakes.events, "nothing reached either plane");
    }

    @Test
    void aSolutionOrInstanceIsNotAPlaceATargetIsOwned() throws Exception {
        login("tok-deep");

        MvcResult result = register("tok-deep", registerBody("vm-deep", MAPPER.createObjectNode()
                .put("environmentId", UUID.randomUUID().toString()).put("solutionId", UUID.randomUUID().toString()),
                KEY_TEXT));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        assertTrue(authorization.permissions.isEmpty(), "refused on the shape of the request, before any authorization");
        assertEquals(List.of(), fakes.events);
    }

    // -------------------------------------------------------------------------- registerTarget: authorization

    /**
     * The orchestrator acts only on the node the interceptor authorized. Two parsers reading one body should agree; this
     * makes "should" into "must": handed a request whose scope is not the one recorded, nothing is called.
     */
    @Test
    void aScopeOtherThanTheOneAuthorizedIsNeverActedOn() {
        Session session = login("tok-differential");
        UUID authorizedEnv = UUID.randomUUID();
        UUID otherEnv = UUID.randomUUID();
        org.springframework.mock.web.MockHttpServletRequest http =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/cp/targets");
        http.setAttribute(OwnershipScopeResolver.AUTHORIZED,
                ResourceRef.of(ResourceKind.ENVIRONMENT, authorizedEnv, new TenantId(organization)));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(http));
        try {
            TargetRegistrationOrchestrator orchestrator = context.getBean(TargetRegistrationOrchestrator.class);
            RegisterTargetRequest request = new RegisterTargetRequest("vm-diff", TargetKind.DOCKER,
                    TargetTransport.SSH_DOCKER, new ScopeRef(null, otherEnv, null, null),
                    new TargetConnectionSpecRequest("10.0.0.7", 22, "sm", PIN, "/srv/sm", "sm-"), KEY_TEXT);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> orchestrator.register(session, request));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> orchestrator.scan(session,
                    new HostKeyScanRequest("10.0.0.7", 22, new ScopeRef(null, otherEnv, null, null))));
            assertEquals(List.of(), fakes.events, "neither plane was reached");
            assertTrue(fakes.audit.rows.isEmpty());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void aCallerWithoutTheVerbIsRefused403AndNoPortIsReached() throws Exception {
        login("tok-viewer");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        MvcResult result = register("tok-viewer", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(result).get("code").asText());
        assertEquals(List.of(), fakes.events, "the pre-checks (a scan of a host the caller named) are NOT reached");
        assertTrue(fakes.targets.rows.isEmpty());
    }

    @Test
    void anotherOrganizationsScopeIsAnIndistinguishable404() throws Exception {
        login("tok-foreign");
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;

        MvcResult result = register("tok-foreign", registerBody("vm-1", environmentScope(UUID.randomUUID()), KEY_TEXT));

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertEquals(List.of(), fakes.events);
    }

    @Test
    void anOrganizationIdInTheBodyIsRefusedNotIgnored() throws Exception {
        login("tok-i5");
        ObjectNode body = registerBody("vm-1", organizationScope(), KEY_TEXT);
        body.put("organizationId", UUID.randomUUID().toString());
        assertEquals(400, register("tok-i5", body).getResponse().getStatus());

        ObjectNode inScope = registerBody("vm-1", MAPPER.createObjectNode()
                .put("organizationId", UUID.randomUUID().toString()), KEY_TEXT);
        assertEquals(400, register("tok-i5", inScope).getResponse().getStatus());

        ObjectNode named = registerBody("vm-1", organizationScope(), KEY_TEXT);
        named.put("targetId", UUID.randomUUID().toString());
        assertEquals(400, register("tok-i5", named).getResponse().getStatus(), "the CP mints the id");
        assertEquals(List.of(), fakes.events);
    }

    // ---------------------------------------------------- registerTarget: every refusal leaves nothing behind

    private void assertLeftNothing() {
        assertTrue(fakes.targets.rows.isEmpty(), "no control-plane row");
        assertTrue(fakes.admin.stored.isEmpty(), "no data-plane row, secret or event");
        assertFalse(fakes.events.contains("cp.register"), fakes.events.toString());
        assertFalse(fakes.events.contains("dp.register"), fakes.events.toString());
    }

    private void refusal(RuntimeException failure, int status, String code) throws Exception {
        login("tok-refuse");
        fakes.admin.precheckFailure = failure;

        MvcResult result = register("tok-refuse", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(code, json(result).get("code").asText());
        assertLeftNothing();
        // ... and the corrected retry succeeds: one control-plane row, one data-plane record
        fakes.admin.precheckFailure = null;
        assertEquals(201, register("tok-refuse", registerBody("vm-1", organizationScope(), KEY_TEXT))
                .getResponse().getStatus());
        assertEquals(1, fakes.targets.rows.size());
        assertEquals(1, fakes.admin.stored.size());
    }

    @Test
    void anUnusableKeyIsRefused422CredentialInvalidAndLeavesNothing() throws Exception {
        refusal(new CredentialInvalidException(), 422, "CREDENTIAL_INVALID");
    }

    @Test
    void aPinTheHostDoesNotOfferIsRefused422AndLeavesNothing() throws Exception {
        refusal(new HostKeyNotOfferedException(), 422, "HOST_KEY_NOT_OFFERED");
    }

    @Test
    void anAddressTheDataPlaneMayNotConnectToIsRefused422AndLeavesNothing() throws Exception {
        refusal(new TargetAddressRefusedException(), 422, "TARGET_ADDRESS_REFUSED");
    }

    @Test
    void aHostThatCannotBeScannedIs503AndLeavesNothing() throws Exception {
        refusal(new HostKeyScanUnavailableException(), 503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void aHostKeyHeldByAnotherTargetIs409AndLeavesNothing() throws Exception {
        refusal(new HostKeyHeldElsewhereException(), 409, "CONFLICT");
    }

    // ---- review W3C-2: the pre-check's scan is a scan: same budget, same audit row

    private TargetProbeAudit.ScanResult onlyAuditedScan() {
        assertEquals(1, fakes.audit.rows.size(), fakes.audit.rows.toString());
        FakeAudit.Row row = fakes.audit.rows.get(0);
        assertEquals(ResourceKind.ORGANIZATION, row.kind());
        assertEquals(organization, row.scopeId());
        return row.result();
    }

    @Test
    void aRegistrationsScanIsAuditedLikeAScanHostKeysAndTheOutcomeIsTheHosts() throws Exception {
        login("tok-aud-ok");
        assertEquals(201, register("tok-aud-ok", registerBody("vm-1", organizationScope(), KEY_TEXT))
                .getResponse().getStatus());
        assertEquals(TargetProbeAudit.ScanResult.COMPLETED, onlyAuditedScan());
    }

    @Test
    void everyRefusalAfterTheHostWasContactedIsAuditedWithTheOutcomeOfThatScan() throws Exception {
        record Case(RuntimeException failure, TargetProbeAudit.ScanResult result) {
        }
        for (Case c : List.of(
                new Case(new TargetAddressRefusedException(), TargetProbeAudit.ScanResult.ADDRESS_REFUSED),
                new Case(new HostKeyScanUnavailableException(), TargetProbeAudit.ScanResult.UNREACHABLE),
                new Case(new HostKeyNotOfferedException(), TargetProbeAudit.ScanResult.COMPLETED),
                new Case(new HostKeyHeldElsewhereException(), TargetProbeAudit.ScanResult.COMPLETED))) {
            fakes.audit.rows.clear();
            login("tok-aud-" + c.result());
            fakes.admin.precheckFailure = c.failure();

            register("tok-aud-" + c.result(), registerBody("vm-" + c.result(), organizationScope(), KEY_TEXT));

            assertEquals(c.result(), onlyAuditedScan(), c.failure().getClass().getSimpleName());
            assertLeftNothing();
        }
    }

    @Test
    void aRefusalBeforeAnyHostWasContactedIsNotAScanAndIsNotAudited() throws Exception {
        login("tok-aud-pre");
        for (RuntimeException failure : List.<RuntimeException>of(new CredentialInvalidException(),
                new IllegalArgumentException("sshUser is not a login name"))) {
            fakes.admin.precheckFailure = failure;
            register("tok-aud-pre", registerBody("vm-1", organizationScope(), KEY_TEXT));
        }
        assertTrue(fakes.audit.rows.isEmpty(), fakes.audit.rows.toString());
    }

    @Test
    void theAuditRowNamesNoHostAndNoKey() throws Exception {
        login("tok-aud-i6");
        register("tok-aud-i6", registerBody("vm-1", organizationScope(), KEY_TEXT));
        assertFalse(fakes.audit.rows.toString().contains(KEY_MARKER));
        assertFalse(fakes.audit.rows.toString().contains("10.0.0.7"));
        assertFalse(fakes.audit.rows.toString().contains(PIN));
    }

    @Test
    void theEleventhRegistrationInAMinuteIs429AndAuditedAndNeverReachesEitherPlane() throws Exception {
        login("tok-reg-rate");
        for (int i = 0; i < 10; i++) {
            fakes.admin.precheckFailure = new HostKeyNotOfferedException();
            assertEquals(422, register("tok-reg-rate", registerBody("vm-" + i, organizationScope(), KEY_TEXT))
                    .getResponse().getStatus());
        }
        fakes.admin.precheckFailure = null;
        fakes.events.clear();
        fakes.audit.rows.clear();

        MvcResult result = register("tok-reg-rate", registerBody("vm-x", organizationScope(), KEY_TEXT));

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(result).get("code").asText());
        assertNotNull(result.getResponse().getHeader("Retry-After"));
        assertEquals(List.of(), fakes.events, "the refused registration scanned nothing and wrote nothing");
        assertEquals(TargetProbeAudit.ScanResult.RATE_LIMITED, onlyAuditedScan());
        assertTrue(fakes.targets.rows.isEmpty());

        fakes.nanos.addAndGet(60L * 1_000_000_000L);
        assertEquals(201, register("tok-reg-rate", registerBody("vm-x", organizationScope(), KEY_TEXT))
                .getResponse().getStatus());
    }

    @Test
    void scansAndRegistrationsDrawOnOneBudgetPerSession() throws Exception {
        login("tok-shared");
        for (int i = 0; i < 6; i++) {
            assertEquals(200, scan("tok-shared", scanBody("10.0.0.7", organizationScope())).getResponse().getStatus());
        }
        for (int i = 0; i < 4; i++) {
            fakes.admin.precheckFailure = new HostKeyNotOfferedException();
            assertEquals(422, register("tok-shared", registerBody("vm-" + i, organizationScope(), KEY_TEXT))
                    .getResponse().getStatus());
        }
        fakes.admin.precheckFailure = null;

        assertEquals(429, scan("tok-shared", scanBody("10.0.0.7", organizationScope())).getResponse().getStatus());
        assertEquals(429, register("tok-shared", registerBody("vm-y", organizationScope(), KEY_TEXT))
                .getResponse().getStatus());
    }

    @Test
    void aStoredKeyNobodyCanOpenIs409SayingSoNotThatTheKeyOrTheRecordDiffers() throws Exception {
        login("tok-unreadable");
        fakes.admin.registerFailureOnce =
                new com.solace.sm.dp.api.inventory.TargetCredentialUnreadableException();

        MvcResult result = register("tok-unreadable", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(409, result.getResponse().getStatus());
        String detail = json(result).get("detail").asText();
        assertTrue(detail.contains("could not be opened"), detail);
        assertTrue(detail.contains("rotate its credential"), detail);
        assertFalse(detail.contains("different"), "it is neither 'a different key' nor 'a different record': " + detail);
    }

    @Test
    void aRecordThatChangedUnderTheRegistrationIs409AskingForARetryNotADifferentRecord() throws Exception {
        login("tok-changed");
        fakes.admin.registerFailureOnce = new com.solace.sm.dp.api.inventory.TargetConnectionChangedException();

        MvcResult result = register("tok-changed", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(409, result.getResponse().getStatus());
        String detail = json(result).get("detail").asText();
        assertTrue(detail.contains("changed while it was being registered"), detail);
        assertTrue(detail.contains("retry"), detail);
        assertFalse(detail.contains("different"), detail);
    }

    @Test
    void aFieldOutsideItsGrammarIs422NamingTheFieldNeverTheValue() throws Exception {
        login("tok-grammar");
        fakes.admin.precheckFailure = new IllegalArgumentException("sshUser is not a login name");

        MvcResult result = register("tok-grammar", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(422, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertEquals("field 'connection.sshUser' failed validation", body.get("detail").asText());
        assertLeftNothing();
    }

    @Test
    void aGrammarMessageThatNamesNoKnownFieldNamesOnlyTheConnection() throws Exception {
        login("tok-grammar2");
        fakes.admin.precheckFailure = new IllegalArgumentException("something with " + KEY_MARKER + " in it");

        MvcResult result = register("tok-grammar2", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("field 'connection' failed validation", json(result).get("detail").asText());
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
    }

    @Test
    void aNameTakenByADifferentRegistrationIs409BeforeAnythingIsScannedOrWritten() throws Exception {
        login("tok-taken");
        fakes.targets.rows.add(new TargetView(UUID.randomUUID(), "vm-1", "docker", "ssh-docker",
                ScopePath.subOrganization(organization, UUID.randomUUID())));

        MvcResult result = register("tok-taken", registerBody("vm-1", organizationScope(), KEY_TEXT));

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertEquals(List.of(), fakes.events, "not even the scan of the host");
        assertEquals(1, fakes.targets.rows.size());
    }

    // ------------------------------------------------------------------------------- registerTarget: S5 sequence

    @Test
    void aFailureAfterTheControlPlaneCommitIsCompletedByTheSamePostThenReplayedThenRefusedForADifferentKey()
            throws Exception {
        login("tok-s5");
        ObjectNode request = registerBody("vm-s5", organizationScope(), KEY_TEXT);

        // 1. the data plane fails after the control plane committed
        fakes.admin.registerFailureOnce = new RuntimeException("simulated crash");
        assertEquals(500, register("tok-s5", request).getResponse().getStatus());
        assertEquals(1, fakes.targets.rows.size(), "the control-plane row stays: listed degraded");
        assertTrue(fakes.admin.stored.isEmpty());

        // 2. the same POST completes it: 201, and still ONE control-plane row
        MvcResult completed = register("tok-s5", request);
        assertEquals(201, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());
        assertEquals(1, fakes.targets.rows.size());
        assertEquals(1, fakes.admin.stored.size());
        assertEquals(fakes.targets.rows.get(0).targetId().toString(), json(completed).get("targetId").asText());

        // 3. a replay is 200 and writes nothing new
        assertEquals(200, register("tok-s5", request).getResponse().getStatus());
        assertEquals(1, fakes.targets.rows.size());
        assertEquals(1, fakes.admin.stored.size());
        UUID id = fakes.targets.rows.get(0).targetId();
        assertEquals(id, fakes.admin.replayingSeen.get(fakes.admin.replayingSeen.size() - 1),
                "the pre-check does not count the replayed target's own host keys as another's");

        // 4. a different key under the same name is a 409 that says to rotate
        MvcResult different = register("tok-s5", registerBody("vm-s5", organizationScope(),
                KEY_TEXT.replace(KEY_MARKER, "OTHERKEY-" + KEY_MARKER)));
        assertEquals(409, different.getResponse().getStatus());
        assertTrue(json(different).get("detail").asText().contains("rotate"), different.getResponse().getContentAsString());

        // 5. the same key re-exported (line ends, a trailing newline) is a replay: 200
        MvcResult reexported = register("tok-s5", registerBody("vm-s5", organizationScope(),
                KEY_TEXT.replace("\n", "\r\n") + "\r\n"));
        assertEquals(200, reexported.getResponse().getStatus(), reexported.getResponse().getContentAsString());
        assertEquals(1, fakes.targets.rows.size());
    }

    // --------------------------------------------------------------------------------------------- I6: the key

    @Test
    void theKeyIsZeroisedOnEveryPathAndAppearsInNoResponse() throws Exception {
        login("tok-zero");

        MvcResult ok = register("tok-zero", registerBody("vm-1", organizationScope(), KEY_TEXT));
        assertEquals(201, ok.getResponse().getStatus());
        fakes.admin.precheckFailure = new CredentialInvalidException();
        MvcResult refused = register("tok-zero", registerBody("vm-2", organizationScope(), KEY_TEXT));
        assertEquals(422, refused.getResponse().getStatus());

        assertFalse(fakes.admin.keysSeen.isEmpty());
        for (char[] seen : fakes.admin.keysSeen) {
            assertArrayEquals(new char[seen.length], seen, "the buffer handed to the data plane is zeroised after");
        }
        for (MvcResult result : List.of(ok, refused)) {
            assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
            assertFalse(result.getResponse().getHeaderNames().stream()
                    .anyMatch(h -> String.valueOf(result.getResponse().getHeaders(h)).contains(KEY_MARKER)));
        }
    }

    @Test
    void theRequestRecordNeverPrintsTheKey() {
        RegisterTargetRequest request = new RegisterTargetRequest("n", TargetKind.DOCKER, TargetTransport.SSH_DOCKER,
                ScopeRef.ORGANIZATION, new TargetConnectionSpecRequest("h", 22, "u", PIN, "/srv/sm", "sm-"), KEY_TEXT);

        assertFalse(request.toString().contains(KEY_MARKER), request.toString());
        assertTrue(request.toString().contains("[REDACTED]"));
    }

    @Test
    void aKeyPastTheContractsLengthIs400() throws Exception {
        login("tok-long");
        MvcResult result = register("tok-long", registerBody("vm-1", organizationScope(),
                "x".repeat(RegisterTargetRequest.MAX_PRIVATE_KEY_CHARS + 1)));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(List.of(), fakes.events);
    }

    // ------------------------------------------------------------------------------ OQ-14 (b): the transport

    @Test
    void aCredentialOverPlainHttpFromAnotherHostIsRefusedBeforeAnyoneLooksAtIt() throws Exception {
        login("tok-plain");

        MvcResult result = mvc.perform(post("/api/cp/targets").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).cookie(new Cookie(COOKIE, "tok-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-plain").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("vm-1", organizationScope(), KEY_TEXT).toString())).andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("INSECURE_TRANSPORT", json(result).get("code").asText());
        assertTrue(authorization.permissions.isEmpty(), "before the session, the CSRF check or the body");
        assertEquals(List.of(), fakes.events);
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
    }

    @Test
    void aSecureChannelFromAnywhereAndLoopbackBothPass() throws Exception {
        login("tok-secure");
        MvcResult secure = mvc.perform(post("/api/cp/targets").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            request.setSecure(true);
            return request;
        }).cookie(new Cookie(COOKIE, "tok-secure")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-secure").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("vm-a", organizationScope(), KEY_TEXT).toString())).andReturn();
        assertEquals(201, secure.getResponse().getStatus());

        MvcResult loopback = register("tok-secure", registerBody("vm-b", organizationScope(), KEY_TEXT));
        assertEquals(201, loopback.getResponse().getStatus(), "MockMvc's default peer is 127.0.0.1");
    }

    @Test
    void aHeaderFromAPeerThatIsNotATrustedProxyProvesNothing() throws Exception {
        login("tok-spoof");
        MvcResult result = mvc.perform(post("/api/cp/targets").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).header("X-Forwarded-Proto", "https").cookie(new Cookie(COOKIE, "tok-spoof"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-tok-spoof")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("vm-1", organizationScope(), KEY_TEXT).toString())).andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("INSECURE_TRANSPORT", json(result).get("code").asText());
    }

    @Test
    void scanHostKeysCarriesNoCredentialSoItIsNotTransportGated() throws Exception {
        login("tok-scan-plain");
        MvcResult result = mvc.perform(post("/api/dp/host-key-scans").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).cookie(new Cookie(COOKIE, "tok-scan-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-scan-plain").contentType(MediaType.APPLICATION_JSON)
                .content(scanBody("10.0.0.7", organizationScope()).toString())).andReturn();
        assertEquals(200, result.getResponse().getStatus());
    }

    // ----------------------------------------------------------------------------------------- scanHostKeys

    private static ObjectNode scanBody(String host, ObjectNode scope) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("host", host);
        body.set("ownershipScope", scope);
        return body;
    }

    private MvcResult scan(String token, ObjectNode body) throws Exception {
        return send(token, "/api/dp/host-key-scans", body.toString());
    }

    @Test
    void aScanReturnsEveryOfferedKeyAndIsAuditedWithoutThem() throws Exception {
        Session session = login("tok-scan");

        MvcResult result = scan("tok-scan", scanBody("10.0.0.7", organizationScope()));

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("10.0.0.7", body.get("host").asText());
        assertEquals(22, body.get("port").asInt(), "the SSH default");
        assertEquals(2, body.get("offered").size());
        assertEquals(PIN, body.at("/offered/0/fingerprint").asText());
        assertEquals("ssh-ed25519", body.at("/offered/0/algorithm").asText());
        // target.create at the body's scope; nothing is written to either plane
        assertEquals(List.of(Permission.TARGET_CREATE), authorization.permissions);
        assertEquals(List.of("dp.scan"), fakes.events);
        assertEquals(1, fakes.audit.rows.size());
        FakeAudit.Row row = fakes.audit.rows.get(0);
        assertEquals(session.principal(), row.actor());
        assertEquals(new TenantId(organization), row.organization());
        assertEquals(ResourceKind.ORGANIZATION, row.kind());
        assertEquals(organization, row.scopeId());
        assertEquals(TargetProbeAudit.ScanResult.COMPLETED, row.result());
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"), row.origin().correlationId().toString());
    }

    @Test
    void aScanAtAnEnvironmentIsAuditedAtThatEnvironment() throws Exception {
        login("tok-scan-env");
        UUID env = UUID.randomUUID();

        assertEquals(200, scan("tok-scan-env", scanBody("10.0.0.7", environmentScope(env))).getResponse().getStatus());

        assertEquals(ResourceKind.ENVIRONMENT, fakes.audit.rows.get(0).kind());
        assertEquals(env, fakes.audit.rows.get(0).scopeId());
    }

    @Test
    void aScanRefusedByTheAddressPolicyIs422AndIsAudited() throws Exception {
        login("tok-scan-addr");
        fakes.scanner.failure = new TargetAddressRefusedException();

        MvcResult result = scan("tok-scan-addr", scanBody("127.0.0.1", organizationScope()));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("TARGET_ADDRESS_REFUSED", json(result).get("code").asText());
        assertEquals(TargetProbeAudit.ScanResult.ADDRESS_REFUSED, fakes.audit.rows.get(0).result());
        assertFalse(result.getResponse().getContentAsString().contains("127.0.0.1"), "the host is not reflected");
    }

    @Test
    void aScanOfAHostThatCannotBeReachedIs503AndIsAudited() throws Exception {
        login("tok-scan-down");
        fakes.scanner.failure = new HostKeyScanUnavailableException();

        MvcResult result = scan("tok-scan-down", scanBody("10.0.0.7", organizationScope()));

        assertEquals(503, result.getResponse().getStatus());
        assertEquals(TargetProbeAudit.ScanResult.UNREACHABLE, fakes.audit.rows.get(0).result());
    }

    @Test
    void aHostOutsideTheGrammarIs422NamingTheFieldAndIsNotAuditedAsAScan() throws Exception {
        login("tok-scan-grammar");
        fakes.scanner.failure = new IllegalArgumentException("a scan needs a registrable host and a port");

        MvcResult result = scan("tok-scan-grammar", scanBody("not a host", organizationScope()));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("field 'host' failed validation", json(result).get("detail").asText());
        assertTrue(fakes.audit.rows.isEmpty(), "it never reached a host");
    }

    @Test
    void aScanCannotBeAskedByACallerWithoutTheVerbAndReachesNeitherTheScannerNorTheAudit() throws Exception {
        login("tok-scan-deny");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        MvcResult result = scan("tok-scan-deny", scanBody("10.0.0.7", organizationScope()));

        assertEquals(403, result.getResponse().getStatus());
        assertEquals(0, fakes.scanner.calls);
        assertTrue(fakes.audit.rows.isEmpty());
    }

    @Test
    void theEleventhScanInAMinuteIs429WithRetryAfterAndIsAuditedAsRateLimited() throws Exception {
        login("tok-scan-rate");
        for (int i = 0; i < 10; i++) {
            assertEquals(200, scan("tok-scan-rate", scanBody("10.0.0.7", organizationScope())).getResponse().getStatus());
        }

        MvcResult result = scan("tok-scan-rate", scanBody("10.0.0.7", organizationScope()));

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(result).get("code").asText());
        assertNotNull(result.getResponse().getHeader("Retry-After"));
        assertEquals(10, fakes.scanner.calls, "the refused scan never reached the host");
        assertEquals(TargetProbeAudit.ScanResult.RATE_LIMITED, fakes.audit.rows.get(10).result());

        // another session has its own budget, and a minute later the first has tokens again
        login("tok-scan-other");
        assertEquals(200, scan("tok-scan-other", scanBody("10.0.0.7", organizationScope())).getResponse().getStatus());
        fakes.nanos.addAndGet(60L * 1_000_000_000L);
        assertEquals(200, scan("tok-scan-rate", scanBody("10.0.0.7", organizationScope())).getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------ getTargetConnection

    private MvcResult read(String token, UUID targetId) throws Exception {
        return mvc.perform(get("/api/dp/targets/" + targetId + "/connection").cookie(new Cookie(COOKIE, token)))
                .andReturn();
    }

    @Test
    void theConnectionReadIsTargetEditAtTheTargetAndCarriesNoKeyMaterial() throws Exception {
        login("tok-read");
        assertEquals(201, register("tok-read", registerBody("vm-1", organizationScope(), KEY_TEXT)).getResponse().getStatus());
        UUID id = fakes.targets.rows.get(0).targetId();
        authorization.permissions.clear();
        authorization.resources.clear();

        MvcResult result = read("tok-read", id);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(Permission.TARGET_EDIT), authorization.permissions, "OQ-13 (a): not target.view");
        assertEquals(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, id, new TenantId(organization)),
                authorization.resources.get(0));
        JsonNode body = json(result);
        assertEquals(id.toString(), body.get("targetId").asText());
        assertEquals("ssh-docker", body.get("transport").asText());
        assertEquals("10.0.0.7", body.get("host").asText());
        assertEquals(PIN, body.get("hostKeyFingerprint").asText());
        assertEquals(2, body.get("offeredHostKeys").size());
        assertEquals("/srv/sm", body.get("allowedConfigRoot").asText());
        assertEquals("SHA256:" + "P".repeat(43), body.at("/credential/publicKeyFingerprint").asText());
        for (String forbidden : new String[] {"privateKey", "credentialRef", "credential_ref", "secret", "hash"}) {
            assertNull(body.get(forbidden), forbidden);
            assertNull(body.at("/credential").get(forbidden), forbidden);
        }
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
    }

    @Test
    void aTargetTheDataPlaneHoldsNoLiveRecordForIsA404() throws Exception {
        login("tok-read-none");

        assertEquals(404, read("tok-read-none", UUID.randomUUID()).getResponse().getStatus());
    }

    @Test
    void aViewerOfTheTargetWithoutTargetEditIs403() throws Exception {
        login("tok-read-viewer");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        MvcResult result = read("tok-read-viewer", UUID.randomUUID());

        assertEquals(403, result.getResponse().getStatus());
        assertEquals(List.of(), fakes.events);
    }

    @Test
    void aTargetIdThatIsNotAUuidIsA404() throws Exception {
        login("tok-read-bad");
        MvcResult result = mvc.perform(get("/api/dp/targets/not-a-uuid/connection")
                .cookie(new Cookie(COOKIE, "tok-read-bad"))).andReturn();
        assertEquals(404, result.getResponse().getStatus());
    }
}
