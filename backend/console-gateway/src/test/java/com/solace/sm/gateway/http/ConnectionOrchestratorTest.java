package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.connection.ConnectionConflictException;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.connection.ConnectionRegistry;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.connection.ConnectionCredentialService;
import com.solace.sm.dp.api.connection.ConnectionTestOutcome;
import com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration;
import com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Plan 013 T4 fix round 1, item 3 ({@code BE-D66}): the composition's own load-bearing invariants, with every
 * cp-api and dp-api port faked — no live DB or broker, the same style {@code ConnectionServiceTest} (Task 2) uses
 * for {@code ConnectionService}. Not full coverage of the eight Connections classes; scoped to what a review
 * flagged as untested and load-bearing:
 *
 * <ul>
 *   <li>authorize-before-anything ({@link #anUnauthorizedCreateReachesNeitherTheRateGateNorTheSealNorTheRegistry});</li>
 *   <li>a refused rate-check or a refused test leaves the {@code cp} row unwritten
 *       ({@link #aSpentRateBudgetWritesNothingAndNeverSealsASecret},
 *       {@link #aFailedTestWritesNothingToCpCore}, {@link #aFailedUpdateTestWritesNothingToCpCore});</li>
 *   <li>the echo the 422 carries is the SECRET-STRIPPED split, not the raw request
 *       ({@link #theFailedTestEchoNeverCarriesTheSecretAttribute});</li>
 *   <li>the rate gate is charged before any secret is sealed
 *       ({@link #aSpentRateBudgetWritesNothingAndNeverSealsASecret});</li>
 *   <li>fix round 1, item 4's 503-vs-422 split
 *       ({@link #anUnreachableTestFailureIs503NotTheEchoingFourTwoTwo}).</li>
 * </ul>
 */
class ConnectionOrchestratorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Session SESSION = GatewayTestSupport.newSession(UUID.randomUUID(), ORG, false);

    private final FakeConnectionDirectory connections = new FakeConnectionDirectory();
    private final FakeConnectionRegistry registry = new FakeConnectionRegistry();
    private final FakeReachAuthorization reach = new FakeReachAuthorization();
    private final ConnectionTypeCatalog catalog = new ConnectionTypeCatalog();
    private final ConnectionAttributeCodec codec = new ConnectionAttributeCodec(catalog);
    private final FakeConnectionCredentialService credentials = new FakeConnectionCredentialService();
    private final FakeRuntimeAdministration runtimeAdministration = new FakeRuntimeAdministration();
    private final GatewayTestSupport.FakeHierarchyDirectory hierarchy = new GatewayTestSupport.FakeHierarchyDirectory();
    private final RequestOriginFactory origins = new RequestOriginFactory(new ClientAddressResolver(new GatewayProperties()));

    /** One permit: enough for the happy-path tests, and the exact budget {@link #aSpentRateBudgetWritesNothingAndNeverSealsASecret} spends first. */
    private final ScanRateGate rateGate = new ScanRateGate(1, () -> 0L);
    private ConnectionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ConnectionOrchestrator(connections, registry, reach, codec, catalog, credentials,
                hierarchy, origins, rateGate, runtimeAdministration);
        authorizeRequestContext();
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A request context with the {@code ownershipScope: {}} body already marked authorized, as the interceptor would. */
    private static void authorizeRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cp/connections");
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, UUID.randomUUID());
        request.setAttribute(OwnershipScopeResolver.AUTHORIZED,
                com.solace.sm.cp.api.authz.ResourceRef.of(ResourceKind.ORGANIZATION, ORG,
                        new com.solace.sm.cp.api.authz.TenantId(ORG)));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static CreateConnectionRequest createRequest(Map<String, Object> attributes, boolean testBeforeSave) {
        return new CreateConnectionRequest("broker-1", "solace-broker", ScopeRef.ORGANIZATION, attributes,
                testBeforeSave);
    }

    private static CreateConnectionRequest createRequest(String connectionType, Map<String, Object> attributes,
            boolean testBeforeSave) {
        return new CreateConnectionRequest("remote-1", connectionType, ScopeRef.ORGANIZATION, attributes,
                testBeforeSave);
    }

    // ---- authorize-before-anything -----------------------------------------------------------------------------

    @Test
    void anUnauthorizedCreateReachesNeitherTheRateGateNorTheSealNorTheRegistry() {
        // No AUTHORIZED attribute on the current request: the interceptor never ran (or authorized a different
        // node) — the orchestrator's OwnershipScopeResolver.requireAuthorized check is the FIRST thing create()
        // does, before the rate gate, the secret seal or any registry call.
        RequestContextHolder.resetRequestAttributes();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/cp/connections")));
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                true);

        assertThrows(IllegalStateException.class, () -> orchestrator.create(SESSION, request));

        assertEquals(0, credentials.sealCalls);
        assertEquals(0, credentials.testCalls);
        assertEquals(0, registry.createCalls);
    }

    // ---- rate-gate-before-seal / a refused rate-check writes nothing --------------------------------------------

    @Test
    void aSpentRateBudgetWritesNothingAndNeverSealsASecret() {
        // The gate has exactly one permit (field above); spend it here, outside the orchestrator, so the
        // orchestrator's own check is the one that is refused.
        rateGate.check(SESSION.id());
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                true);

        assertThrows(GatewayRejectionException.class, () -> orchestrator.create(SESSION, request));

        assertEquals(0, credentials.sealCalls, "the rate gate is charged BEFORE any secret is sealed");
        assertEquals(0, credentials.testCalls);
        assertEquals(0, registry.createCalls, "nothing written to cp-core either");
    }

    // ---- a failed test writes nothing to cp-core, and the echo is the secret-stripped split ---------------------

    @Test
    void aFailedTestWritesNothingToCpCore() {
        credentials.nextSuccess = false;
        credentials.nextReachable = true;
        credentials.nextRejectionReason = "bad credentials";
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                true);

        ConnectionTestFailedException refusal = assertThrows(ConnectionTestFailedException.class,
                () -> orchestrator.create(SESSION, request));

        assertEquals("bad credentials", refusal.rejectionReason());
        assertEquals(1, credentials.sealCalls, "the seal happens before the test (BE-D66's own stated order)");
        assertEquals(1, credentials.testCalls);
        assertEquals(0, registry.createCalls, "a failed test never reaches the cp write");
    }

    @Test
    void theFailedTestEchoNeverCarriesTheSecretAttribute() {
        credentials.nextSuccess = false;
        credentials.nextReachable = true;
        credentials.nextRejectionReason = "bad credentials";
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                true);

        ConnectionTestFailedException refusal = assertThrows(ConnectionTestFailedException.class,
                () -> orchestrator.create(SESSION, request));

        assertFalse(refusal.echoedAttributes().containsKey("password"),
                "the secret-typed attribute must never reach the echo (I6)");
        assertTrue(refusal.echoedAttributes().containsKey("host"), "non-secret fields are echoed back");
        assertEquals("broker.example.com", refusal.echoedAttributes().get("host"));
    }

    @Test
    void anUnreachableTestFailureIs503NotTheEchoingFourTwoTwo() {
        credentials.nextSuccess = false;
        credentials.nextReachable = false;
        credentials.nextRejectionReason = "connection refused";
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                true);

        GatewayRejectionException refusal = assertThrows(GatewayRejectionException.class,
                () -> orchestrator.create(SESSION, request));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, refusal.code());
        assertEquals(0, registry.createCalls);
    }

    // ---- the same invariant on updateConnection (BE-D66: "in either plane") -------------------------------------

    @Test
    void aFailedUpdateTestWritesNothingToCpCore() {
        UUID connectionId = UUID.randomUUID();
        connections.rows.add(new ConnectionView(connectionId, "broker-1", "solace-broker", 1,
                ScopePath.organization(ORG), Map.of("host", "broker.example.com", "port", 55555, "vpn", "default"),
                Map.of(), true, 0, 1, Instant.now(), Instant.now()));
        credentials.nextSuccess = false;
        credentials.nextReachable = true;
        credentials.nextRejectionReason = "bad credentials";
        UpdateConnectionRequest request = new UpdateConnectionRequest(null, Map.of("password", "newSecret"), true);

        assertThrows(ConnectionTestFailedException.class,
                () -> orchestrator.update(SESSION, connectionId, request));

        assertEquals(0, registry.updateCalls, "a failed update test never reaches the cp write either");
    }

    // ---- fix round 2: malformed input must be 422 VALIDATION_FAILED, never an uncaught 500 ----------------------

    @Test
    void aNonNumericPortIsRejectedAsValidationFailedNotAnUncaughtNumberFormatException() {
        CreateConnectionRequest request = createRequest(
                Map.of("host", "broker.example.com", "port", "not-a-number", "vpn", "default",
                        "password", "s3cret"),
                true);

        ConnectionValidationException refusal = assertThrows(ConnectionValidationException.class,
                () -> orchestrator.create(SESSION, request));

        assertEquals("port", refusal.field());
        assertEquals(0, credentials.testCalls, "the test probe is never reached");
        assertEquals(0, registry.createCalls, "nothing written to cp-core");
    }

    @Test
    void anUnknownAuthMethodOnRemoteServerIsRejectedAsValidationFailedNotAnUncaughtIllegalArgumentException() {
        CreateConnectionRequest request = createRequest("remote-server",
                Map.of("host", "box.example.com", "username", "deployer", "authMethod", "kerberos",
                        "password", "s3cret"),
                true);

        ConnectionValidationException refusal = assertThrows(ConnectionValidationException.class,
                () -> orchestrator.create(SESSION, request));

        assertEquals("authMethod", refusal.field());
        assertEquals(0, credentials.testCalls, "the test probe is never reached");
        assertEquals(0, registry.createCalls, "nothing written to cp-core");
    }

    @Test
    void switchingAuthMethodToPrivateKeyOnUpdateWithoutAKeyIsRejectedAsValidationFailedNotAnUncaughtNullPointerException() {
        UUID connectionId = UUID.randomUUID();
        connections.rows.add(new ConnectionView(connectionId, "remote-1", "remote-server", 1,
                ScopePath.organization(ORG),
                Map.of("host", "box.example.com", "username", "deployer", "authMethod", "basic"), Map.of(), true, 0,
                1, Instant.now(), Instant.now()));
        // No privateKey supplied: the existing connection has no sealed key ref to fall back on either.
        UpdateConnectionRequest request = new UpdateConnectionRequest(null,
                Map.of("authMethod", "private-key"), true);

        ConnectionValidationException refusal = assertThrows(ConnectionValidationException.class,
                () -> orchestrator.update(SESSION, connectionId, request));

        assertEquals("privateKey", refusal.field());
        assertEquals(0, credentials.testCalls, "the test probe is never reached");
        assertEquals(0, registry.updateCalls, "nothing written to cp-core");
    }

    // ---- plan 015: the connection_runtime projection seam ---------------------------------------------------

    @Test
    void createRemoteServerConnectionDoesNotProjectDeploymentEnvelope() {
        CreateConnectionRequest request = createRequest("remote-server",
                Map.of("host", "vm-1.corp.example", "username", "deployer", "authMethod", "basic",
                        "password", "s3cret"),
                false);

        orchestrator.create(SESSION, request);

        assertEquals(1, registry.createCalls);
        assertEquals(0, runtimeAdministration.upserts.size(),
                "deployment-owned fields must not be fabricated in the Connection projection");
    }

    @Test
    void createSolaceBrokerConnectionNeverTouchesRuntimeProjection() {
        CreateConnectionRequest request = createRequest(Map.of("host", "broker.example.com", "password", "s3cret"),
                false);

        orchestrator.create(SESSION, request);

        assertEquals(0, runtimeAdministration.upserts.size());
    }

    @Test
    void deactivateRetiresRuntimeProjectionBeforeTheRegistryCall() {
        UUID connectionId = UUID.randomUUID();
        connections.rows.add(new ConnectionView(connectionId, "remote-1", "remote-server", 1,
                ScopePath.organization(ORG), Map.of("host", "vm-1.corp.example"), Map.of(), true, 0, 1, Instant.now(),
                Instant.now()));

        assertThrows(ConnectionConflictException.class, () -> orchestrator.deactivate(SESSION, connectionId));

        assertEquals(List.of(connectionId), runtimeAdministration.retired);
    }

    // Case 4 (a null non-secret attribute value) is exercised directly against ConnectionAttributeCodec in
    // ConnectionAttributeCodecTest: both wire records (CreateConnectionRequest/UpdateConnectionRequest) already
    // reject a null-valued JSON attribute at deserialization via their own Map.copyOf, so a null cannot reach the
    // orchestrator through the real HTTP path — but the codec is called directly elsewhere (and must not NPE if
    // that upstream filtering is ever relaxed), which is exactly what the review flagged.

    // ---- fakes --------------------------------------------------------------------------------------------------

    private static final class FakeConnectionDirectory implements ConnectionDirectory {
        final List<ConnectionView> rows = new ArrayList<>();

        @Override
        public List<ConnectionView> list(UUID organizationId) {
            return List.copyOf(rows);
        }
    }

    private static final class FakeConnectionRegistry implements ConnectionRegistry {
        int createCalls;
        int updateCalls;
        int deactivateCalls;
        int deleteCalls;

        @Override
        public ConnectionView create(PrincipalRef actor, UUID organizationId, CreateRequest request,
                RequestOrigin origin) {
            createCalls++;
            return new ConnectionView(UUID.randomUUID(), request.name(), request.connectionType(),
                    request.descriptorVersion(), request.ownershipScope(), request.attributes(),
                    request.secretStatus(), true, 0, 1, Instant.now(), Instant.now());
        }

        @Override
        public ConnectionView update(PrincipalRef actor, UUID organizationId, UUID connectionId,
                UpdateRequest request, RequestOrigin origin) {
            updateCalls++;
            return new ConnectionView(connectionId, request.name() == null ? "broker-1" : request.name(),
                    "solace-broker", 1, ScopePath.organization(organizationId),
                    request.attributes() == null ? Map.of() : request.attributes(),
                    request.secretStatus() == null ? Map.of() : request.secretStatus(), true, 0, 2, Instant.now(),
                    Instant.now());
        }

        @Override
        public ConnectionView deactivate(PrincipalRef actor, UUID organizationId, UUID connectionId,
                RequestOrigin origin) {
            deactivateCalls++;
            throw new ConnectionConflictException(ConnectionConflictException.Kind.STALE_WRITE, "not used here");
        }

        @Override
        public void delete(PrincipalRef actor, UUID organizationId, UUID connectionId, RequestOrigin origin) {
            deleteCalls++;
        }
    }

    private static final class FakeReachAuthorization implements ReachAuthorization {
        @Override
        public void requireHeldAnywhere(PrincipalRef principal, Permission permission) {
            // not exercised by the tests in this class (listConnections is out of scope here)
        }

        @Override
        public Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
                Collection<UUID> ids) {
            return Set.copyOf(ids);
        }
    }

    private static final class FakeRuntimeAdministration implements ConnectionRuntimeAdministration {
        final List<ConnectionRuntimeSpec> upserts = new ArrayList<>();
        final List<UUID> retired = new ArrayList<>();

        @Override
        public void upsert(UUID organizationId, ConnectionRuntimeSpec spec, UUID actor) {
            upserts.add(spec);
        }

        @Override
        public void retire(UUID organizationId, UUID connectionId, UUID actor) {
            retired.add(connectionId);
        }
    }

    private static final class FakeConnectionCredentialService implements ConnectionCredentialService {
        int sealCalls;
        int testCalls;
        boolean nextSuccess = true;
        boolean nextReachable = true;
        String nextRejectionReason;

        @Override
        public UUID sealCredential(UUID tenantId, char[] value, UUID actor) {
            sealCalls++;
            return UUID.randomUUID();
        }

        @Override
        public ConnectionTestOutcome testSolaceBroker(UUID tenantId, String host, int port, String vpn,
                String username, UUID passwordSecretRef) {
            testCalls++;
            return outcome();
        }

        @Override
        public ConnectionTestOutcome testRemoteServer(UUID tenantId, String host, int port, String username,
                String authMethod, UUID passwordSecretRef, UUID privateKeySecretRef, UUID passphraseSecretRef) {
            testCalls++;
            return outcome();
        }

        private ConnectionTestOutcome outcome() {
            return new ConnectionTestOutcome(nextSuccess, nextSuccess ? null : nextRejectionReason, nextReachable,
                    Instant.now());
        }
    }
}
