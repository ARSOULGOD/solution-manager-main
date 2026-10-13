package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.identity.CredentialRotation;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.identity.CredentialRotationRequiredException;
import com.solace.sm.cp.api.identity.EffectivePermissionView;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.identity.IdentityDirectory;
import com.solace.sm.cp.api.identity.IdentityView;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionAuthentication;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.api.session.SessionToken;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Shared test rig: fakes for every cp-api port the gateway consumes, a test-only CATALOG fixture
 * controller, and a {@link Harness} that assembles a real (non-mocked) Spring MVC context
 * wired exactly as {@link GatewayWebConfiguration} wires production, so tests exercise the real
 * filter → interceptor → argument-resolver → controller → advice pipeline end to end.
 */
final class GatewayTestSupport {

    static final String TRUSTED_ORIGIN = "https://console.example.test";
    static final String FOREIGN_ORIGIN = "https://evil.example.test";

    private GatewayTestSupport() {
    }

    /** {@code sm.gateway.auth-rate.*} — see {@link AuthRateGate}. Every harness needs SOME values; the
     * defaults here (capacity 10, refill 10/min) are generous enough that no existing boundary test
     * incidentally trips OR-24, while still small enough for tests that deliberately do to reach the
     * limit in only a handful of requests. */
    record AuthRateConfig(int capacity, int refillPerMinute, int maxTrackedKeys) {
        static final AuthRateConfig DEFAULT = new AuthRateConfig(10, 10, 100_000);
    }

    static Harness newHarness() {
        return newHarness(List.of());
    }

    static Harness newHarness(List<String> trustedProxies) {
        return newHarness(trustedProxies, AuthRateConfig.DEFAULT);
    }

    /**
     * @param trustedProxies bound to {@code sm.gateway.trusted-proxies}, in order. Every test
     *         harness this gateway builds is a single, non-proxied MockMvc/HTTP client talking
     *         straight to it — never itself the documented reverse-proxy topology — so when the
     *         caller configures no trusted proxies, {@code sm.gateway.direct-client-connections}
     *         is set {@code true} to satisfy {@code B1}'s startup gate: exactly one of the two must
     *         be set, and the empty-proxies case genuinely IS this harness's own topology. When
     *         the caller DOES configure trusted proxies, that is the explicit choice instead, and
     *         setting direct-client-connections too would be the contradictory combination B1
     *         refuses.
     * @param authRate bound to {@code sm.gateway.auth-rate.*} (see {@link AuthRateConfig}).
     */
    static Harness newHarness(List<String> trustedProxies, AuthRateConfig authRate) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        List<String> inlinedProperties = new ArrayList<>();
        inlinedProperties.add("sm.gateway.trusted-origins[0]=" + TRUSTED_ORIGIN);
        for (int i = 0; i < trustedProxies.size(); i++) {
            inlinedProperties.add("sm.gateway.trusted-proxies[" + i + "]=" + trustedProxies.get(i));
        }
        if (trustedProxies.isEmpty()) {
            inlinedProperties.add("sm.gateway.direct-client-connections=true");
        }
        inlinedProperties.add("sm.gateway.auth-rate.capacity=" + authRate.capacity());
        inlinedProperties.add("sm.gateway.auth-rate.refill-per-minute=" + authRate.refillPerMinute());
        inlinedProperties.add("sm.gateway.auth-rate.max-tracked-keys=" + authRate.maxTrackedKeys());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, inlinedProperties.toArray(String[]::new));
        context.register(TestConfig.class);
        context.refresh();

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*")
                .build();

        return new Harness(mockMvc, context,
                context.getBean(FakeSessionAuthentication.class),
                context.getBean(FakeLocalAuthenticationService.class),
                context.getBean(FakeIdentityDirectory.class),
                context.getBean(FakeForcedRotationGate.class),
                context.getBean(FakeCredentialRotation.class),
                context.getBean(FakeAuthorizationService.class),
                context.getBean(FakeHierarchyDirectory.class),
                context.getBean(MutableClock.class));
    }

    static Session newSession(UUID principalId, UUID organizationId, boolean mustRotate) {
        Instant now = Instant.now();
        return new Session(new SessionId(UUID.randomUUID()), new PrincipalRef(principalId), organizationId,
                mustRotate, now, now, now.plusSeconds(3600), now.plusSeconds(86_400));
    }

    record Harness(MockMvc mockMvc, AnnotationConfigWebApplicationContext context,
                   FakeSessionAuthentication sessionAuthentication,
                   FakeLocalAuthenticationService localAuthenticationService,
                   FakeIdentityDirectory identityDirectory,
                   FakeForcedRotationGate forcedRotationGate,
                   FakeCredentialRotation credentialRotation,
                   FakeAuthorizationService authorizationService,
                   FakeHierarchyDirectory hierarchyDirectory,
                   MutableClock clock) {

        /** Registers a live session reachable via {@code token}, without going through login. */
        Session withSession(String token, String csrf, UUID organizationId, boolean mustRotate) {
            Session session = newSession(UUID.randomUUID(), organizationId, mustRotate);
            sessionAuthentication.register(token, session, csrf);
            return session;
        }

        /** The handler-invocation counter on the test-only CATALOG fixture controller. */
        int fixtureInvocationCount() {
            return context.getBean(CatalogFixtureController.class).invocations.get();
        }

        /** The handler-invocation counter on the test-only REACH fixture controller. */
        int reachInvocationCount() {
            return context.getBean(ReachFixtureController.class).invocations.get();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class TestConfig {

        @Bean
        FakeSessionAuthentication sessionAuthentication() {
            return new FakeSessionAuthentication();
        }

        @Bean
        FakeLocalAuthenticationService localAuthenticationService() {
            return new FakeLocalAuthenticationService();
        }

        @Bean
        FakeIdentityDirectory identityDirectory() {
            return new FakeIdentityDirectory();
        }

        @Bean
        FakeForcedRotationGate forcedRotationGate() {
            return new FakeForcedRotationGate();
        }

        @Bean
        FakeCredentialRotation credentialRotation() {
            return new FakeCredentialRotation();
        }

        @Bean
        FakeAuthorizationService authorizationService() {
            return new FakeAuthorizationService();
        }

        /**
         * The ONLY {@code Clock} bean in this test context (production's {@code Clock} bean
         * lives in the launcher's {@code SolutionManagerConfiguration}, never in
         * {@link GatewayWebConfiguration} — see that class's {@code authRateGate} javadoc), so a
         * test that needs OR-24's token bucket to see fake elapsed time calls
         * {@link Harness#clock()}{@code .advance(...)} rather than sleeping.
         */
        /** Named {@code authRateClock}: the gate takes it by name, never by type (see GatewayWebConfiguration). */
        @Bean(name = "authRateClock")
        MutableClock authRateClock() {
            return new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        }

        /**
         * Production gets exactly one {@link GatewayErrorAdvice} via component-scanning its
         * {@code @RestControllerAdvice} annotation (the launcher scans {@code com.solace.sm.gateway});
         * {@link GatewayWebConfiguration} deliberately does NOT also declare it as a {@code @Bean}
         * (that was the duplicate-registration bug). This raw {@code @EnableWebMvc} test context does
         * no component scanning at all, so it registers the one copy explicitly here instead.
         */
        @Bean
        GatewayErrorAdvice gatewayErrorAdvice() {
            return new GatewayErrorAdvice();
        }

        @Bean
        AuthController authController(LocalAuthenticationService localAuthenticationService,
                IdentityDirectory identityDirectory, SessionAuthentication sessionAuthentication,
                CredentialRotation credentialRotation, RequestOriginFactory requestOriginFactory,
                HierarchyDirectory hierarchyDirectory) {
            return new AuthController(localAuthenticationService, identityDirectory, sessionAuthentication,
                    credentialRotation, requestOriginFactory, hierarchyDirectory);
        }

        @Bean
        FakeHierarchyDirectory hierarchyDirectory() {
            return new FakeHierarchyDirectory();
        }

        @Bean
        HierarchyController hierarchyController(HierarchyDirectory hierarchyDirectory) {
            return new HierarchyController(hierarchyDirectory);
        }

        @Bean
        FixtureResourceResolver fixtureResourceResolver() {
            return new FixtureResourceResolver();
        }

        @Bean
        CatalogFixtureController catalogFixtureController() {
            return new CatalogFixtureController();
        }

        @Bean
        ReachFixtureController reachFixtureController() {
            return new ReachFixtureController();
        }
    }

    /**
     * TEST-ONLY: resolves a fresh resource under the calling session's own organization for the
     * fixture operations below. Registered as a bean so {@code @ConsoleOperation(resource = ...)}
     * can look it up by type, exactly like a production resolver.
     */
    static final class FixtureResourceResolver implements ResourceResolver {
        @Override
        public ResourceRef resolve(HttpServletRequest request, Session session) {
            return ResourceRef.instance(UUID.randomUUID(), new TenantId(session.organizationId()));
        }
    }

    /**
     * TEST-ONLY: two real catalog operations (already registered in {@code ConsoleOperations})
     * used to prove must-rotate and authorization plumbing without depending on a real
     * business endpoint. Authorization itself now happens entirely in
     * {@link AuthenticationInterceptor}, before either handler method runs — {@link #invocations}
     * proves that: it stays at zero whenever authorization denies or 404s.
     */
    @RestController
    static class CatalogFixtureController {

        static final String HEALTH_PATH = "/api/fixture/health";
        static final String STAGE_PATH = "/api/fixture/stage";

        final AtomicInteger invocations = new AtomicInteger();

        @GetMapping(HEALTH_PATH)
        @ConsoleOperation(value = "getHealth", resource = FixtureResourceResolver.class)
        ResponseEntity<Void> getHealth(Session session) {
            invocations.incrementAndGet();
            return ResponseEntity.ok().build();
        }

        @PostMapping(STAGE_PATH)
        @ConsoleOperation(value = "stageConfig", resource = FixtureResourceResolver.class)
        ResponseEntity<Void> stageConfig(Session session) {
            invocations.incrementAndGet();
            return ResponseEntity.ok().build();
        }
    }

    /**
     * TEST-ONLY: a {@code x-sm-scope: reach} operation (S0 point 24). It declares NO
     * {@code resource}: the interceptor resolves none, so {@link #invocations} staying at zero is the
     * proof that the pre-handler reach check, not the handler, refused.
     */
    @RestController
    static class ReachFixtureController {

        static final String INSTANCES_PATH = "/api/fixture/instances";

        final AtomicInteger invocations = new AtomicInteger();

        @GetMapping(INSTANCES_PATH)
        @ConsoleOperation("listInstances")
        ResponseEntity<Void> listInstances(Session session) {
            invocations.incrementAndGet();
            return ResponseEntity.ok().build();
        }
    }

    static final class FakeSessionAuthentication implements SessionAuthentication {

        private final Map<String, Session> sessionsByToken = new HashMap<>();
        private final Map<UUID, String> csrfBySession = new HashMap<>();
        private final Set<UUID> revoked = new HashSet<>();
        final List<SessionId> revokeCalls = new ArrayList<>();
        final List<RequestOrigin> revokeOrigins = new ArrayList<>();
        /** OR-24 proof: the rate gate must run BEFORE session resolution, so a gated request never adds here. */
        final AtomicInteger resolveCalls = new AtomicInteger();

        void register(String token, Session session, String csrf) {
            sessionsByToken.put(token, session);
            csrfBySession.put(session.id().value(), csrf);
        }

        @Override
        public Optional<Session> resolve(SessionToken token) {
            resolveCalls.incrementAndGet();
            Session session = sessionsByToken.get(token.value());
            if (session == null || revoked.contains(session.id().value())) {
                return Optional.empty();
            }
            return Optional.of(session);
        }

        @Override
        public boolean verifyCsrf(SessionId session, CsrfToken presented) {
            String expected = csrfBySession.get(session.value());
            return expected != null && expected.equals(presented.value());
        }

        @Override
        public Optional<CsrfToken> csrfTokenFor(SessionId session) {
            String value = csrfBySession.get(session.value());
            return value == null ? Optional.empty() : Optional.of(new CsrfToken(value));
        }

        @Override
        public void revoke(SessionId session, RequestOrigin origin) {
            revoked.add(session.value());
            revokeCalls.add(session);
            revokeOrigins.add(origin);
        }
    }

    static final class FakeLocalAuthenticationService implements LocalAuthenticationService {

        private final Map<String, Supplier<LocalLoginResult>> outcomes = new HashMap<>();
        final List<String> attempts = new ArrayList<>();
        /** The organization each attempt typed, as typed (OR-125 (4)). */
        final List<String> organizations = new ArrayList<>();
        final List<RequestOrigin> origins = new ArrayList<>();

        void onAuthenticate(String username, Supplier<LocalLoginResult> outcome) {
            outcomes.put(username, outcome);
        }

        @Override
        public LocalLoginResult authenticate(LocalCredentials credentials, RequestOrigin origin) {
            attempts.add(credentials.username());
            organizations.add(credentials.organization());
            origins.add(origin);
            Supplier<LocalLoginResult> outcome = outcomes.get(credentials.username());
            if (outcome == null) {
                throw new LoginRefusedException();
            }
            return outcome.get();
        }
    }

    static final class FakeIdentityDirectory implements IdentityDirectory {

        private final Map<UUID, String> usernames = new HashMap<>();
        private final Map<UUID, List<EffectivePermissionView>> permissions = new HashMap<>();
        private final Map<UUID, List<String>> roleNames = new HashMap<>();

        void registerUsername(UUID principalId, String username) {
            usernames.put(principalId, username);
        }

        void registerPermissions(SessionId sessionId, List<EffectivePermissionView> views) {
            permissions.put(sessionId.value(), views);
        }

        void registerRoleNames(UUID principalId, List<String> names) {
            roleNames.put(principalId, names);
        }

        @Override
        public IdentityView describe(Session session) {
            String username = usernames.getOrDefault(session.principal().id(), "operator");
            List<EffectivePermissionView> views = permissions.getOrDefault(session.id().value(), List.of());
            List<String> roles = roleNames.getOrDefault(session.principal().id(), List.of());
            // This fake sits above IdentityService — it stands in for the cp-api port, not for
            // JdbcRoleBindingRepository's install-scope query — so it has no scope concept to
            // narrow against. `registerRoleNames` in these tests only ever registers a bare
            // "Administrator" (never a scoped, non-install binding), so
            // `roles.contains("Administrator")` is an accurate stand-in for the real
            // `boundToRoleAtInstallScope` check at this boundary.
            boolean superAdmin = roles.contains("Administrator");
            return new IdentityView(session.principal(), username, PrincipalSource.LOCAL, session.mustRotate(), views, roles,
                    superAdmin);
        }
    }

    static final class FakeForcedRotationGate implements ForcedRotationGate {

        private final Set<UUID> mustRotateSessions = new HashSet<>();
        final List<Object> catalogChecks = new ArrayList<>();
        final List<LocalOperation> localChecks = new ArrayList<>();

        void markMustRotate(SessionId sessionId) {
            mustRotateSessions.add(sessionId.value());
        }

        @Override
        public void check(Session session, Permission attempted) {
            catalogChecks.add(attempted);
            checkInternal(session);
        }

        @Override
        public void check(Session session, LocalOperation operation) {
            localChecks.add(operation);
            // The three local operations are always allowed regardless of rotation state.
        }

        @Override
        public void check(Session session) {
            checkInternal(session);
        }

        private void checkInternal(Session session) {
            if (session.mustRotate() || mustRotateSessions.contains(session.id().value())) {
                throw new CredentialRotationRequiredException();
            }
        }
    }

    static final class FakeCredentialRotation implements CredentialRotation {

        boolean refuseNext = false;
        final List<Session> rotateCalls = new ArrayList<>();
        final List<RequestOrigin> rotateOrigins = new ArrayList<>();

        @Override
        public void rotate(Session current, char[] currentCredential, char[] replacementCredential,
                RequestOrigin origin) {
            rotateCalls.add(current);
            rotateOrigins.add(origin);
            if (refuseNext) {
                throw new CredentialRotationRefusedException();
            }
        }
    }

    /**
     * Implements BOTH authorization ports so every context that already supplies a
     * {@code FakeAuthorizationService} bean also supplies the {@link ReachAuthorization} the
     * interceptor now requires. Production keeps them separate beans (cp-core's
     * {@code GrantReachAuthorization}); the shared {@link #outcome} is a test convenience only.
     */
    static final class FakeAuthorizationService implements AuthorizationService, ReachAuthorization {

        enum Outcome { ALLOW, DENY, NOT_FOUND }

        Outcome outcome = Outcome.ALLOW;
        /**
         * A per-verb outcome that wins over {@link #outcome} in {@link #require}: lets a test hold one verb of a
         * two-sided check (declareInstance's {@code instance.bindTarget} / {@code target.use}) and not another.
         */
        final Map<Permission, Outcome> outcomeByPermission = new HashMap<>();
        /**
         * Plan 010 T11.2: a per-RESOURCE outcome, per verb, that wins over both above - a fleet is authorized member by
         * member, each at its own instance, so a test must be able to refuse one instance and not another.
         */
        final Map<UUID, Map<Permission, Outcome>> outcomeByResource = new HashMap<>();
        /**
         * Plan 011 P11-7.2: a per-verb outcome for {@link #requireHeldAnywhere} alone, which wins over {@link #outcome} there
         * (a Viewer holds metrics.query somewhere and metrics.forceRaw nowhere). Separate from {@link #outcomeByPermission},
         * which {@code require} reads, so a test that scripts one does not change the other.
         */
        final Map<Permission, Outcome> reachOutcomeByPermission = new HashMap<>();
        final List<PrincipalRef> principals = new ArrayList<>();
        final List<Permission> permissions = new ArrayList<>();
        final List<ResourceRef> resources = new ArrayList<>();
        /** The permissions {@link #requireHeldAnywhere} was asked about, in order. */
        final List<Permission> reachChecks = new ArrayList<>();
        final List<PrincipalRef> reachPrincipals = new ArrayList<>();

        @Override
        public void requireHeldAnywhere(PrincipalRef principal, Permission permission) {
            reachPrincipals.add(principal);
            reachChecks.add(permission);
            if (reachOutcomeByPermission.getOrDefault(permission, outcome) != Outcome.ALLOW) {
                throw new AccessDeniedException(permission);
            }
        }

        /** One {@link #authorizedSubset} call as the handler made it. */
        record SubsetCall(PrincipalRef principal, Permission permission, ResourceKind kind, Set<UUID> ids) {
        }

        /** Every {@link #authorizedSubset} call, in order — a reach handler that never calls it leaves this empty. */
        final List<SubsetCall> subsetCalls = new ArrayList<>();
        /**
         * The ids this fake reports as authorized. Empty by default, so a handler that does not call
         * {@link #authorizedSubset} (or ignores its answer) shows an empty list in its test, never a
         * full one — the fake never returns "everything it was given".
         */
        final Set<UUID> authorizedIds = new java.util.LinkedHashSet<>();
        /**
         * A per-verb answer for {@link #authorizedSubset} that wins over {@link #authorizedIds}: lets a test hold one
         * verb at an instance and not another (the home reads filter by two verbs at once).
         */
        final Map<Permission, Set<UUID>> authorizedIdsByPermission = new HashMap<>();

        @Override
        public Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
                java.util.Collection<UUID> ids) {
            subsetCalls.add(new SubsetCall(principal, permission, kind, Set.copyOf(ids)));
            Set<UUID> subset = new java.util.LinkedHashSet<>(ids);
            subset.retainAll(authorizedIdsByPermission.getOrDefault(permission, authorizedIds));
            return Set.copyOf(subset);
        }

        @Override
        public void require(PrincipalRef principal, Permission permission, ResourceRef resource) {
            principals.add(principal);
            permissions.add(permission);
            resources.add(resource);
            Outcome scripted = outcomeByResource.getOrDefault(resource.id(), Map.of()).get(permission);
            switch (scripted != null ? scripted : outcomeByPermission.getOrDefault(permission, outcome)) {
                case DENY -> throw new AccessDeniedException(permission);
                case NOT_FOUND -> throw new ResourceNotFoundException(resource.kind());
                case ALLOW -> { /* no-op */ }
            }
        }
    }

    /**
     * TEST-ONLY: the three authentication-only hierarchy reads (console-integration.md §5.2 rows
     * 8-10). {@link #NOT_FOUND} lets a test simulate the out-of-scope/nonexistent-parent 404
     * {@code ScopeVisibleHierarchyDirectory} raises; {@link #sessionsSeen} proves the session (and
     * so its server-derived organization, {@code I5}) actually reaches this port, never a
     * client-supplied one.
     */
    static final class FakeHierarchyDirectory implements HierarchyDirectory {

        enum Outcome { RETURN, NOT_FOUND }

        Outcome outcome = Outcome.RETURN;
        List<com.solace.sm.cp.api.hierarchy.ScopeNode> nodesToReturn = List.of();
        final List<Session> sessionsSeen = new ArrayList<>();
        UUID lastOrganizationId;
        UUID lastSubOrganizationId;

        @Override
        public List<com.solace.sm.cp.api.hierarchy.ScopeNode> listOrganizations(Session session) {
            sessionsSeen.add(session);
            return respond(ResourceKind.ORGANIZATION);
        }

        @Override
        public List<com.solace.sm.cp.api.hierarchy.ScopeNode> listSubOrganizations(Session session,
                UUID organizationId) {
            sessionsSeen.add(session);
            lastOrganizationId = organizationId;
            return respond(ResourceKind.ORGANIZATION);
        }

        /** OR-116: what {@code visibleOrganizations} returns, and the sessions it was asked about. */
        List<com.solace.sm.cp.api.hierarchy.VisibleOrganization> visibleToReturn = List.of();
        final List<Session> visibleOrganizationsSessions = new ArrayList<>();

        @Override
        public List<com.solace.sm.cp.api.hierarchy.VisibleOrganization> visibleOrganizations(Session session) {
            visibleOrganizationsSessions.add(session);
            return visibleToReturn;
        }

        @Override
        public List<com.solace.sm.cp.api.hierarchy.ScopeNode> listEnvironments(Session session,
                UUID organizationId, UUID subOrganizationId) {
            sessionsSeen.add(session);
            lastOrganizationId = organizationId;
            lastSubOrganizationId = subOrganizationId;
            return respond(ResourceKind.SUB_ORGANIZATION);
        }

        private List<com.solace.sm.cp.api.hierarchy.ScopeNode> respond(ResourceKind notFoundKind) {
            if (outcome == Outcome.NOT_FOUND) {
                throw new ResourceNotFoundException(notFoundKind);
            }
            return nodesToReturn;
        }
    }

    /** A {@link Clock} a test can move forward explicitly, for {@code OR-24}'s token-bucket refill. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
