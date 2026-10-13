package com.solace.sm.cp.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.authz.GrantReachAuthorization;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.persistence.JdbcTargetRepository;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Target governance (plan 010 T2.7) against a real {@code sm_control} schema, running <b>as {@code sm_cp_runtime}</b>:
 * the OR-25 audit rule only fires for that role, so a test as anyone else proves nothing about the audited write.
 * Authorization is the real {@link ScopeEvaluatingAuthorizationService} and {@link GrantReachAuthorization} over the
 * real grants, never a stub.
 */
class TargetServiceIT {

    private final NamedParameterJdbcTemplate runtime = TargetFixtures.runtime();

    private TargetService service;
    private ReachAuthorization reach;
    private Tree tree;

    @BeforeEach
    void setUp() {
        DataSource dataSource = runtime.getJdbcTemplate().getDataSource();
        ScopePathResolver paths = new ScopePathResolver(new JdbcResourceScopeDirectory(runtime));
        JdbcPrincipalOrganizationResolver organizations = new JdbcPrincipalOrganizationResolver(runtime);
        JdbcAuthorizationGrantRepository grants = new JdbcAuthorizationGrantRepository(runtime);
        service = new TargetService(new JdbcTargetRepository(runtime),
                new ScopeEvaluatingAuthorizationService(organizations, grants, paths), new JdbcAuditContext(runtime),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), Clock.systemUTC());
        reach = new GrantReachAuthorization(organizations, grants, paths);
        tree = TargetFixtures.seedTree();
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("127.0.0.1", UUID.randomUUID());
    }

    private UUID principalWith(String role, ScopePath scope) {
        UUID id = TargetFixtures.localPrincipal(tree.organization(), "u-" + UUID.randomUUID(), "unused-credential", false);
        TargetFixtures.bind(id, role, scope);
        return id;
    }

    private TargetRegistration.Request request(String name, ScopePath owner) {
        return new TargetRegistration.Request(null, name, "docker", "ssh-docker", owner);
    }

    private TargetView register(UUID actor, String name, ScopePath owner) {
        return service.register(new PrincipalRef(actor), tree.organization(), request(name, owner), origin());
    }

    // ---------------------------------------------------------------- registration

    /**
     * Plan 013 T1.4, D-REG section 8, against the REAL partial unique index {@code target_name_once}: two identical
     * registrations released by a barrier. Both answer, the second as the replay of the first, and exactly ONE row (and
     * one audit row) exists. Without the retry the loser is a {@code DuplicateKeyException}, i.e. a {@code 500}.
     */
    @Test
    void twoIdenticalRegistrationsAtOnceBothAnswerTheSameRowAndWriteOne() throws Exception {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            String name = "race-vm-" + round;
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                List<java.util.concurrent.Future<TargetView>> both = new java.util.ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    both.add(pool.submit(() -> {
                        barrier.await();
                        return register(admin, name, tree.environmentScope());
                    }));
                }
                TargetView first = both.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
                TargetView second = both.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(second.targetId()).as("round " + round + ": the loser answers the winner's row")
                        .isEqualTo(first.targetId());
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(TargetFixtures.targetRows(tree.organization())).isEqualTo(rounds);
    }

    @Test
    void theTargetWriteIsAuditedAsTargetRegisteredWithTheActor() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        RequestOrigin origin = origin();

        TargetView registered = service.register(new PrincipalRef(admin), tree.organization(),
                request("audited-vm", tree.environmentScope()), origin);

        List<Map<String, Object>> rows = TargetFixtures.auditRows("target", origin.correlationId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("audit_operation", "TARGET_REGISTERED")
                .containsEntry("actor_principal_id", admin).containsEntry("client_address", "127.0.0.1");
        assertThat(rows.get(0).get("row_key").toString()).contains(registered.targetId().toString());
        assertThat(registered.ownershipScope()).isEqualTo(tree.environmentScope());
    }

    @Test
    void aPrincipalWithoutTargetCreateAtTheOwnershipScopeIsRefusedAndNothingIsWritten() {
        UUID viewer = principalWith("Viewer", tree.organizationScope());
        UUID operator = principalWith("Operator", tree.organizationScope());

        assertThatThrownBy(() -> register(viewer, "viewer-vm", tree.environmentScope()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> register(operator, "operator-vm", tree.environmentScope()))
                .as("Operator holds target.use and target.view but not target.create")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(TargetFixtures.targetRows(tree.organization())).isZero();
    }

    @Test
    void aGrantBelowTheOwnershipScopeDoesNotAuthorizeItAndAnAncestorGrantDoes() {
        UUID envAdmin = principalWith("Administrator", tree.environmentScope());

        assertThatThrownBy(() -> register(envAdmin, "reach-up", tree.organizationScope()))
                .as("a grant at an environment makes the organization visible, never authorized")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> register(envAdmin, "sibling", tree.siblingEnvironmentScope()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(register(envAdmin, "own-env", tree.environmentScope()).ownershipScope())
                .isEqualTo(tree.environmentScope());

        UUID orgAdmin = principalWith("Administrator", tree.organizationScope());
        assertThat(register(orgAdmin, "below-org", tree.environmentScope())).isNotNull();
    }

    @Test
    void aSolutionInstanceOrInstallOwnershipScopeIsRefused() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        ScopePath solution = ScopePath.solution(tree.organization(), tree.unit(), tree.environment(), UUID.randomUUID());
        ScopePath instance = ScopePath.instance(tree.organization(), tree.unit(), tree.environment(),
                UUID.randomUUID(), UUID.randomUUID());

        for (ScopePath bad : List.of(solution, instance, ScopePath.INSTALL)) {
            assertThatThrownBy(() -> register(admin, "bad-" + bad.depth(), bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("organization");
        }
        assertThat(TargetFixtures.targetRows(tree.organization())).isZero();
    }

    @Test
    void anotherOrganizationsScopeIsAsAbsentAsANonexistentOne() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        Tree other = TargetFixtures.seedTree();

        assertThatThrownBy(() -> register(admin, "foreign", other.environmentScope()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> register(admin, "nonexistent",
                ScopePath.environment(tree.organization(), tree.unit(), UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void registeringTheSameTargetTwiceReturnsTheSameIdAndWritesOnce() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        RequestOrigin second = origin();

        TargetView first = register(admin, "idem-vm", tree.environmentScope());
        TargetView again = service.register(new PrincipalRef(admin), tree.organization(),
                request("idem-vm", tree.environmentScope()), second);

        assertThat(again).isEqualTo(first);
        assertThat(TargetFixtures.targetRows(tree.organization())).isEqualTo(1);
        assertThat(TargetFixtures.auditRows("target", second.correlationId())).isEmpty();
    }

    @Test
    void theSameNameAtAnotherScopeOrWithAnotherIdIsRefused() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView first = register(admin, "taken", tree.environmentScope());

        assertThatThrownBy(() -> register(admin, "taken", tree.siblingEnvironmentScope()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.register(new PrincipalRef(admin), tree.organization(),
                new TargetRegistration.Request(UUID.randomUUID(), "taken", "docker", "ssh-docker",
                        tree.environmentScope()), origin())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.register(new PrincipalRef(admin), tree.organization(),
                new TargetRegistration.Request(first.targetId(), "other-name", "docker", "ssh-docker",
                        tree.environmentScope()), origin())).isInstanceOf(IllegalStateException.class);
        assertThat(TargetFixtures.targetRows(tree.organization())).isEqualTo(1);
    }

    @Test
    void aRetiredTargetFreesItsNameForANewRegistrationAndTheLiveOneIsTheOneAReplayFinds() {
        // OQ-11 (b) / V018: target_name_once spans live targets only, so a retired name is free again. The retired row
        // stays (the audit trail and the placements that named it), and a replay of the new registration finds the
        // live row, never the retired one that sorts first by age.
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView first = register(admin, "reusable", tree.environmentScope());
        TargetFixtures.retire(first.targetId());

        TargetView second = register(admin, "reusable", tree.siblingEnvironmentScope());

        assertThat(second.targetId()).isNotEqualTo(first.targetId());
        assertThat(second.ownershipScope()).isEqualTo(tree.siblingEnvironmentScope());
        assertThat(register(admin, "reusable", tree.siblingEnvironmentScope())).as("the replay is the live row")
                .isEqualTo(second);
        assertThat(TargetFixtures.targetRows(tree.organization())).isEqualTo(2);
        assertThat(service.listLive(tree.organization())).containsExactly(second);
        assertThatThrownBy(() -> register(admin, "reusable", tree.environmentScope()))
                .as("a live target still holds its name: the same name at another scope is refused")
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- retirement (plan 013 T1.5, OQ-11 (b))

    private TargetRegistration.Retired retire(UUID actor, UUID targetId, RequestOrigin origin) {
        return service.retire(new PrincipalRef(actor), tree.organization(), targetId, origin);
    }

    @Test
    void retiringATargetIsOneAuditedWriteOfTargetRetiredByTheActorAndFreesTheNameInTheSameBreath() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView first = register(admin, "retire-me", tree.environmentScope());
        RequestOrigin origin = origin();

        TargetRegistration.Retired retired = retire(admin, first.targetId(), origin);

        assertThat(retired.alreadyRetired()).isFalse();
        assertThat(retired.view()).isEqualTo(first);
        List<Map<String, Object>> rows = TargetFixtures.auditRows("target", origin.correlationId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("audit_operation", "TARGET_RETIRED")
                .containsEntry("actor_principal_id", admin).containsEntry("client_address", "127.0.0.1");
        assertThat(rows.get(0).get("row_key").toString()).contains(first.targetId().toString());
        assertThat(service.listLive(tree.organization())).isEmpty();
        // the name is free: a new registration under it is a NEW target
        TargetView second = register(admin, "retire-me", tree.environmentScope());
        assertThat(second.targetId()).isNotEqualTo(first.targetId());
        assertThat(TargetFixtures.targetRows(tree.organization())).isEqualTo(2);
    }

    @Test
    void retiringARetiredTargetAnswersTheOriginalTimeAndWritesNoSecondAuditRow() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView target = register(admin, "retire-twice", tree.environmentScope());
        TargetRegistration.Retired first = retire(admin, target.targetId(), origin());
        RequestOrigin again = origin();

        TargetRegistration.Retired second = retire(admin, target.targetId(), again);

        assertThat(second.alreadyRetired()).isTrue();
        assertThat(second.retiredAt()).isEqualTo(first.retiredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(TargetFixtures.auditRows("target", again.correlationId())).isEmpty();
    }

    @Test
    void twoRetirementsAtOnceBothAnswerAndWriteOneAuditRow() throws Exception {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        for (int round = 0; round < 8; round++) {
            TargetView target = register(admin, "race-retire-" + round, tree.environmentScope());
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                List<RequestOrigin> origins = List.of(origin(), origin());
                List<java.util.concurrent.Future<TargetRegistration.Retired>> both = new java.util.ArrayList<>();
                for (RequestOrigin origin : origins) {
                    both.add(pool.submit(() -> {
                        barrier.await();
                        return retire(admin, target.targetId(), origin);
                    }));
                }
                long first = both.stream().map(f -> {
                    try {
                        return f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).filter(r -> !r.alreadyRetired()).count();
                assertThat(first).as("round " + round + ": exactly one call retired it").isEqualTo(1);
                long audits = origins.stream()
                        .mapToLong(o -> TargetFixtures.auditRows("target", o.correlationId()).size()).sum();
                assertThat(audits).as("round " + round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void retiringNeedsTargetEditAtTheTargetsOwnScopeAndOnlyThere() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView envOwned = register(admin, "env-retire", tree.environmentScope());
        TargetView orgOwned = register(admin, "org-retire", tree.organizationScope());
        UUID operator = principalWith("Operator", tree.organizationScope());
        UUID envAdmin = principalWith("Administrator", tree.environmentScope());
        UUID siblingAdmin = principalWith("Administrator", tree.siblingEnvironmentScope());

        assertThatThrownBy(() -> retire(operator, envOwned.targetId(), origin()))
                .as("the Operator holds target.use, not target.edit").isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> retire(envAdmin, orgOwned.targetId(), origin()))
                .as("an environment grant does not authorize an organization-owned target")
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> retire(siblingAdmin, envOwned.targetId(), origin()))
                .as("another environment's administrator cannot even see it")
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.listLive(tree.organization())).containsExactlyInAnyOrder(envOwned, orgOwned);

        assertThat(retire(envAdmin, envOwned.targetId(), origin()).alreadyRetired()).isFalse();
    }

    @Test
    void aTargetOfAnotherOrganizationOrNoneIsAsAbsentAsAnUnknownId() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        Tree other = TargetFixtures.seedTree();
        UUID otherAdmin = TargetFixtures.localPrincipal(other.organization(), "o-" + UUID.randomUUID(), "x", false);
        TargetFixtures.bind(otherAdmin, "Administrator", other.organizationScope());
        TargetView foreign = service.register(new PrincipalRef(otherAdmin), other.organization(),
                request("foreign-retire", other.organizationScope()), origin());

        assertThatThrownBy(() -> retire(admin, foreign.targetId(), origin())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> retire(admin, UUID.randomUUID(), origin())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.listLive(other.organization())).containsExactly(foreign);
    }

    @Test
    void onlyTheV1PairOfKindAndTransportIsRegistrable() {
        UUID admin = principalWith("Administrator", tree.organizationScope());

        assertThatThrownBy(() -> service.register(new PrincipalRef(admin), tree.organization(),
                new TargetRegistration.Request(null, "k8s", "kubernetes", "ssh-docker", tree.environmentScope()),
                origin())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(new PrincipalRef(admin), tree.organization(),
                new TargetRegistration.Request(null, "telnet", "docker", "telnet", tree.environmentScope()),
                origin())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> register(admin, "  ", tree.environmentScope()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- listing and reach

    @Test
    void listLiveIsTheOrganizationsLiveTargetsByNameAndNeverAnotherOrganizations() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView b = register(admin, "b-vm", tree.environmentScope());
        TargetView a = register(admin, "a-vm", tree.organizationScope());
        TargetView retired = register(admin, "c-retired", tree.environmentScope());
        TargetFixtures.retire(retired.targetId());
        Tree other = TargetFixtures.seedTree();
        UUID otherAdmin = TargetFixtures.localPrincipal(other.organization(), "o-" + UUID.randomUUID(), "x", false);
        TargetFixtures.bind(otherAdmin, "Administrator", other.organizationScope());
        service.register(new PrincipalRef(otherAdmin), other.organization(),
                request("foreign-vm", other.organizationScope()), origin());

        assertThat(service.listLive(tree.organization())).containsExactly(a, b);
    }

    /** The single-resource counterpart of AuthorizationIT's "unbacked kinds" loop, which DEPLOYMENT_TARGET left (OR-28). */
    @Test
    void aTargetResolvesToItsOwnOwnershipScopeAndAnUnknownIdFailsClosed() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView envOwned = register(admin, "resolved-env", tree.environmentScope());
        TargetView orgOwned = register(admin, "resolved-org", tree.organizationScope());
        ScopePathResolver paths = new ScopePathResolver(new JdbcResourceScopeDirectory(runtime));

        assertThat(paths.scopePathOf(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, envOwned.targetId(),
                new TenantId(tree.organization())))).isEqualTo(tree.environmentScope());
        assertThat(paths.scopePathOf(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, orgOwned.targetId(),
                new TenantId(tree.organization())))).isEqualTo(tree.organizationScope());
        assertThatThrownBy(() -> paths.scopePathOf(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, UUID.randomUUID(),
                new TenantId(tree.organization())))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anOrganizationOwnedTargetIsNotReachedByAnEnvironmentBoundOperator() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView orgOwned = register(admin, "org-owned", tree.organizationScope());
        TargetView envOwned = register(admin, "env-owned", tree.environmentScope());
        TargetView siblingOwned = register(admin, "sibling-owned", tree.siblingEnvironmentScope());
        UUID operator = principalWith("Operator", tree.environmentScope());
        List<UUID> all = List.of(orgOwned.targetId(), envOwned.targetId(), siblingOwned.targetId());

        reach.requireHeldAnywhere(new PrincipalRef(operator), Permission.TARGET_VIEW);
        Set<UUID> reached = reach.authorizedSubset(new PrincipalRef(operator), Permission.TARGET_VIEW,
                ResourceKind.DEPLOYMENT_TARGET, all);

        assertThat(reached).containsExactly(envOwned.targetId());
    }

    @Test
    void anOrganizationBoundViewerReachesEveryTargetBelowItAndNoOtherOrganizationsTarget() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView envOwned = register(admin, "env-owned", tree.environmentScope());
        TargetView orgOwned = register(admin, "org-owned", tree.organizationScope());
        Tree other = TargetFixtures.seedTree();
        UUID otherAdmin = TargetFixtures.localPrincipal(other.organization(), "o-" + UUID.randomUUID(), "x", false);
        TargetFixtures.bind(otherAdmin, "Administrator", other.organizationScope());
        TargetView foreign = service.register(new PrincipalRef(otherAdmin), other.organization(),
                request("foreign", other.organizationScope()), origin());
        UUID viewer = principalWith("Viewer", tree.organizationScope());

        Set<UUID> reached = reach.authorizedSubset(new PrincipalRef(viewer), Permission.TARGET_VIEW,
                ResourceKind.DEPLOYMENT_TARGET,
                List.of(envOwned.targetId(), orgOwned.targetId(), foreign.targetId(), UUID.randomUUID()));

        assertThat(reached).containsExactlyInAnyOrder(envOwned.targetId(), orgOwned.targetId());
    }

    @Test
    void aRetiredTargetStillResolvesItsOwnershipPathForAuthorization() {
        UUID admin = principalWith("Administrator", tree.organizationScope());
        TargetView retired = register(admin, "retired-vm", tree.environmentScope());
        TargetFixtures.retire(retired.targetId());

        Set<UUID> reached = reach.authorizedSubset(new PrincipalRef(admin), Permission.TARGET_VIEW,
                ResourceKind.DEPLOYMENT_TARGET, List.of(retired.targetId()));

        assertThat(reached).containsExactly(retired.targetId());
    }
}
