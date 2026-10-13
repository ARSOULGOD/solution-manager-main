package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.authz.ScopeStore;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.target.TargetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 015 T2.2, token issue at service level (the HTTP surface is T4.3): the envelope and the target name are stored,
 * an envelope outside the registerTarget grammars is refused before anything is written, the two verbs are checked first,
 * and the issuing principal is the audit actor and the registration's creator. Only the token's digest is stored (I6).
 */
class AgentEnrolmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.9", CORRELATION);

    private final List<String> events = new ArrayList<>();
    private final List<Permission> required = new ArrayList<>();
    private final List<Object[]> contexts = new ArrayList<>();
    private final List<TargetRepository.Stored> liveTargets = new ArrayList<>();
    private final FakeAgentRegistry registry = new FakeAgentRegistry(events);
    private Permission denied;
    private ScopePath storedEnvironment = ScopePath.environment(ORG, UNIT, ENV);
    private final List<ScopePath> canonicalized = new ArrayList<>();

    private AgentEnrolmentService service() {
        return service(Duration.ofHours(24), Duration.ofDays(30));
    }

    private AgentEnrolmentService service(Duration defaultValidity, Duration maxValidity) {
        AuthorizationService authorization = (principal, permission, resource) -> {
            events.add("authorize." + permission.id());
            required.add(permission);
            assertEquals(ResourceKind.ENVIRONMENT, resource.kind());
            if (permission == denied) throw new AccessDeniedException(permission);
        };
        TargetRepository targets = new TargetRepository() {
            @Override public List<TargetView> listLive(UUID o) { return List.of(); }
            @Override public Optional<Stored> findByName(UUID o, String name) {
                return liveTargets.stream().filter(s -> s.view().name().equals(name)).findFirst();
            }
            @Override public Optional<Stored> findById(UUID o, UUID t) { return Optional.empty(); }
            @Override public void insert(UUID o, UUID t, String n, String k, String tr, ScopePath s, UUID c, Instant a) {
                throw new AssertionError("issuing a token writes no target");
            }
            @Override public int markRetired(UUID o, UUID t, Instant a) { return 0; }
        };
        ResourceScopeDirectory directory = resource -> {
            events.add("directory.find");
            return Optional.ofNullable(storedEnvironment);
        };
        ScopeStore scopes = path -> {
            events.add("scope.canonicalize");
            canonicalized.add(path);
            return new ScopeId(UUID.randomUUID());
        };
        return new AgentEnrolmentService(registry, targets, directory, scopes, authorization,
                (actor, operation, correlation, address) -> {
                    events.add("audit.set");
                    contexts.add(new Object[] {actor, operation, correlation, address});
                }, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                defaultValidity, maxValidity);
    }

    private static AgentEnrolment.Request request(String name, String root, String prefix, String version, Duration valid) {
        return new AgentEnrolment.Request(ScopePath.environment(ORG, UNIT, ENV), SOLUTION, version, name, root, prefix, valid);
    }

    private static AgentEnrolment.Request ok() {
        return request("edge-host-1", "/opt/sm/config", "sm-", "1", null);
    }

    @Test
    void issuingStoresTheEnvelopeTheNameAndOnlyTheTokensDigestAndAuditsThePrincipal() throws Exception {
        AgentEnrolment.IssuedToken issued = service().issue(ACTOR, ORG, ok(), ORIGIN);

        assertEquals(List.of("directory.find", "authorize.agent.enrol", "authorize.target.create", "audit.set",
                "scope.canonicalize", "registry.insert"), events);
        assertEquals(List.of(ScopePath.environment(ORG, UNIT, ENV)), canonicalized,
                "the environment scope row is minted by the issuing principal, inside the audited transaction");
        assertEquals(List.of(Permission.AGENT_ENROL, Permission.TARGET_CREATE), required);
        assertArrayEquals(new Object[] {ACTOR.id(), AuditOperations.AGENT_ENROLMENT_ISSUED, CORRELATION, "203.0.113.9"},
                contexts.get(0));
        AgentRegistry.NewRegistration row = registry.inserted.get(0);
        assertEquals(issued.agentId(), row.agentId());
        assertEquals(ORG, row.organizationId());
        assertEquals(UNIT, row.organizationUnitId());
        assertEquals(ENV, row.environmentId());
        assertEquals(SOLUTION, row.catalogSolutionId());
        assertEquals("1", row.packageVersion());
        assertEquals("edge-host-1", row.targetName());
        assertEquals("/opt/sm/config", row.allowedConfigRoot());
        assertEquals("sm-", row.allowedContainerPrefix());
        assertEquals(ACTOR.id(), row.createdBy(), "created_by is the principal who ISSUED the token (OR-128 (5))");
        assertEquals(NOW, row.createdAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), row.expiresAt(), "OR-136 (1): the default is 24 hours");
        assertEquals(issued.expiresAt(), row.expiresAt());
        // 256 random bits, base64url: 43 characters; only the unkeyed SHA-256 is stored (OR-128 (9)).
        assertEquals(43, issued.token().length());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(issued.token().getBytes(StandardCharsets.UTF_8)),
                row.tokenDigest());
        assertFalse(issued.toString().contains(issued.token()));
    }

    @Test
    void twoTokensDiffer() {
        AgentEnrolmentService service = service();
        assertFalse(service.issue(ACTOR, ORG, ok(), ORIGIN).token()
                .equals(service.issue(ACTOR, ORG, request("edge-2", "/opt/sm/config", "sm-", "1", null), ORIGIN).token()));
    }

    @Test
    void anEnvelopeOutsideTheRegisterTargetGrammarsIsRefusedBeforeAnythingIsAuthorizedOrWritten() {
        for (AgentEnrolment.Request bad : List.of(
                request("n", "/opt/../etc", "sm-", "1", null),
                request("n", "relative/path", "sm-", "1", null),
                request("n", "/opt//double", "sm-", "1", null),
                request("n", "/", "sm-", "1", null),
                request("n", "/opt/sm config", "sm-", "1", null),
                request("n", "/opt/sm", "-leading-dash", "1", null),
                request("n", "/opt/sm", "has space", "1", null),
                request("n", "/opt/sm", "x".repeat(65), "1", null),
                request("  ", "/opt/sm", "sm-", "1", null),
                request("n".repeat(129), "/opt/sm", "sm-", "1", null),
                request("n", "/opt/sm", "sm-", "[1]", null),
                request("n", "/opt/sm", "sm-", "{}", null),
                request("n", "/opt/sm", "sm-", "not json", null),
                request("n", "/opt/sm", "sm-", "1", Duration.ZERO),
                request("n", "/opt/sm", "sm-", "1", Duration.ofDays(31)))) {
            assertThrows(IllegalArgumentException.class, () -> service().issue(ACTOR, ORG, bad, ORIGIN), bad.toString());
        }
        assertEquals(List.of(), events);
    }

    // ---- OR-136 (1): the lifetime is configuration ----------------------------------------------------------

    @Test
    void aConfiguredPairIsHonouredForTheDefaultAndForTheCeiling() {
        AgentEnrolmentService configured = service(Duration.ofHours(2), Duration.ofDays(3));
        configured.issue(ACTOR, ORG, ok(), ORIGIN);
        assertEquals(NOW.plus(Duration.ofHours(2)), registry.inserted.get(0).expiresAt(), "the configured default applies");
        configured.issue(ACTOR, ORG, request("edge-2", "/opt/sm", "sm-", "1", Duration.ofDays(3)), ORIGIN);
        assertEquals(NOW.plus(Duration.ofDays(3)), registry.inserted.get(1).expiresAt(), "the configured maximum is allowed");
        assertThrows(IllegalArgumentException.class, () -> configured.issue(ACTOR, ORG,
                request("edge-3", "/opt/sm", "sm-", "1", Duration.ofDays(3).plusSeconds(1)), ORIGIN),
                "one second above the configured maximum is refused");
    }

    @Test
    void aRequestAboveTheDefaultMaximumOf30DaysIsRefusedAndThirtyDaysIsNot() {
        service().issue(ACTOR, ORG, request("n", "/opt/sm", "sm-", "1", Duration.ofDays(30)), ORIGIN);
        assertThrows(IllegalArgumentException.class,
                () -> service().issue(ACTOR, ORG, request("m", "/opt/sm", "sm-", "1", Duration.ofDays(30).plusSeconds(1)), ORIGIN));
    }

    @Test
    void anInvalidConfigurationDoesNotStart() {
        assertThrows(IllegalArgumentException.class, () -> service(Duration.ofDays(2), Duration.ofDays(1)), "default > max");
        assertThrows(IllegalArgumentException.class, () -> service(Duration.ZERO, Duration.ofDays(1)), "default of zero");
        assertThrows(IllegalArgumentException.class, () -> service(Duration.ofHours(-1), Duration.ofDays(1)), "negative default");
        assertThrows(IllegalArgumentException.class, () -> service(Duration.ofHours(1), Duration.ZERO), "max of zero");
        assertThrows(IllegalArgumentException.class, () -> service(Duration.ofHours(1), Duration.ofDays(-1)), "negative max");
        service(Duration.ofDays(1), Duration.ofDays(1));      // equal is valid
    }

    @Test
    void aStringVersionIsAccepted() {
        service().issue(ACTOR, ORG, request("n", "/opt/sm", "sm-", "\"v2\"", Duration.ofMinutes(5)), ORIGIN);
        assertEquals(NOW.plus(Duration.ofMinutes(5)), registry.inserted.get(0).expiresAt());
    }

    @Test
    void bothVerbsAreRequiredAndAMissingOneWritesNothing() {
        for (Permission missing : List.of(Permission.AGENT_ENROL, Permission.TARGET_CREATE)) {
            events.clear();
            denied = missing;
            assertThrows(AccessDeniedException.class, () -> service().issue(ACTOR, ORG, ok(), ORIGIN));
            assertFalse(events.contains("registry.insert"));
            assertFalse(events.contains("audit.set"));
        }
    }

    @Test
    void anEnvironmentNamedUnderTheWrongUnitIsAsAbsentAsAnUnknownOneAndNothingIsAuthorized() {
        AgentEnrolment.Request wrongUnit = new AgentEnrolment.Request(ScopePath.environment(ORG, UUID.randomUUID(), ENV),
                SOLUTION, "1", "n", "/opt/sm", "sm-", null);
        assertThrows(ResourceNotFoundException.class, () -> service().issue(ACTOR, ORG, wrongUnit, ORIGIN));
        storedEnvironment = null;
        assertThrows(ResourceNotFoundException.class, () -> service().issue(ACTOR, ORG, ok(), ORIGIN));
        assertEquals(List.of("directory.find", "directory.find"), events);
    }

    @Test
    void anEnvironmentOfAnotherOrganizationIsAsAbsentAsANonexistentOneAndNothingIsAuthorized() {
        assertThrows(ResourceNotFoundException.class,
                () -> service().issue(ACTOR, UUID.randomUUID(), ok(), ORIGIN));
        assertEquals(List.of(), events);
    }

    @Test
    void aSolutionLevelPathIsNotAnEnvironment() {
        AgentEnrolment.Request notAnEnvironment = new AgentEnrolment.Request(ScopePath.organization(ORG), SOLUTION, "1",
                "n", "/opt/sm", "sm-", null);
        assertThrows(IllegalArgumentException.class, () -> service().issue(ACTOR, ORG, notAnEnvironment, ORIGIN));
    }

    @Test
    void aLiveTargetOfTheSameNameRefusesTheIssue() {
        liveTargets.add(new TargetRepository.Stored(new TargetView(UUID.randomUUID(), "edge-host-1", "docker",
                "ssh-docker", ScopePath.organization(ORG)), null));
        assertThrows(IllegalStateException.class, () -> service().issue(ACTOR, ORG, ok(), ORIGIN));
        assertTrue(registry.inserted.isEmpty());
    }
}
