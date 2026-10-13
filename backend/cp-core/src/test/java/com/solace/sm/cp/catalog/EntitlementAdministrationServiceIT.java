package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.catalog.EntitlementAdministration.Entitlement;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.governance.GovernanceService;
import com.solace.sm.cp.governance.InstanceRetirementService;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcEntitlementRepository;
import com.solace.sm.cp.persistence.JdbcGovernanceRepository;
import com.solace.sm.cp.persistence.JdbcInstanceRetirementRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.target.TargetFixtures;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 013 T2.2: {@link EntitlementAdministrationService} against a real {@code sm_control} schema at the head (V018),
 * running <b>as {@code sm_cp_runtime}</b>. Authorization is the real scope evaluator over real grants. Instances are
 * declared and undeclared through the real {@code GovernanceService} and {@code InstanceRetirementService}, so the count
 * under test is the count the declare path enforces, not a copy of it.
 */
class EntitlementAdministrationServiceIT {

    private static final String CLIENT = "203.0.113.40";

    private final NamedParameterJdbcTemplate runtime = TargetFixtures.runtime();
    private final NamedParameterJdbcTemplate migrator = TargetFixtures.migrator();

    private TransactionTemplate tx;
    private EntitlementAdministration service;
    private InstanceGovernance governance;
    private InstanceRetirementGovernance retirement;
    private Tree tree;
    private UUID entry;

    @BeforeEach
    void setUp() {
        DataSource dataSource = runtime.getJdbcTemplate().getDataSource();
        ScopePathResolver paths = new ScopePathResolver(new JdbcResourceScopeDirectory(runtime));
        ScopeEvaluatingAuthorizationService authorization = new ScopeEvaluatingAuthorizationService(
                new JdbcPrincipalOrganizationResolver(runtime), new JdbcAuthorizationGrantRepository(runtime), paths);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcGovernanceRepository governanceRepository = new JdbcGovernanceRepository(runtime);
        service = new EntitlementAdministrationService(new JdbcEntitlementRepository(runtime), governanceRepository,
                authorization, new JdbcAuditContext(runtime), tx, Clock.systemUTC());
        governance = new GovernanceService(governanceRepository, new JdbcAuditContext(runtime), tx, UUID::randomUUID);
        retirement = new InstanceRetirementService(new JdbcInstanceRetirementRepository(runtime),
                new JdbcAuditContext(runtime), tx, Clock.systemUTC());
        tree = TargetFixtures.seedTree();
        entry = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, 'cache', now())", tree.organization(), entry);
    }

    private static RequestOrigin origin() {
        return new RequestOrigin(CLIENT, UUID.randomUUID());
    }

    private PrincipalRef principalWith(String role, ScopePath scope) {
        UUID id = TargetFixtures.localPrincipal(tree.organization(), "e-" + UUID.randomUUID(), "unused-credential", false);
        if (scope.depth() == 0) {
            // The one install scope row is seeded by V007 (scope_one_install: no other can exist) and cannot be
            // canonicalized at runtime, so an install-level grant binds to it directly.
            migrator.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                            + " VALUES (:binding, :principal, (SELECT role_id FROM sm_control.role WHERE name = :role),"
                            + " '00000000-0000-4000-8000-000000000010')",
                    Map.of("binding", UUID.randomUUID(), "principal", id, "role", role));
        } else {
            TargetFixtures.bind(id, role, scope);
        }
        return new PrincipalRef(id);
    }

    private PrincipalRef installAdmin() {
        return principalWith("Administrator", ScopePath.INSTALL);
    }

    private UUID declare(PrincipalRef actor) {
        return governance.register(actor, origin(), tree.organization(), tree.environment(), entry, UUID.randomUUID())
                .instanceId();
    }

    private Integer storedMax() {
        return migrator.queryForList("SELECT max_instances FROM sm_control.entitlement WHERE organization_id = :o"
                + " AND solution = 'cache'", Map.of("o", tree.organization()), Integer.class).stream().findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- who may set

    @Test
    void anInstallBoundAdministratorSetsTheLimitAndTheWriteIsAuditedWithItsActorAndClientAddress() {
        PrincipalRef admin = installAdmin();
        RequestOrigin origin = origin();

        Entitlement set = service.set(admin, origin, tree.organization(), "cache", 4);

        assertThat(set.solution()).isEqualTo("cache");
        assertThat(set.maxInstances()).isEqualTo(4);
        assertThat(set.declaredInstances()).isZero();
        assertThat(storedMax()).isEqualTo(4);
        List<Map<String, Object>> rows = TargetFixtures.auditRows("entitlement", origin.correlationId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("audit_operation", "ENTITLEMENT_SET")
                .containsEntry("actor_principal_id", admin.id()).containsEntry("client_address", CLIENT);
        assertThat(migrator.queryForObject("SELECT set_by FROM sm_control.entitlement WHERE organization_id = :o"
                + " AND solution = 'cache'", Map.of("o", tree.organization()), UUID.class)).isEqualTo(admin.id());
    }

    @Test
    void anOrganizationBoundAdministratorCannotRaiseItsOwnQuotaAndNothingIsWritten() {
        PrincipalRef orgAdmin = principalWith("Administrator", tree.organizationScope());

        assertThatThrownBy(() -> service.set(orgAdmin, origin(), tree.organization(), "cache", 10))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(storedMax()).isNull();
    }

    @Test
    void anOperatorAndAViewerCannotSetEvenAtInstallScope() {
        PrincipalRef operator = principalWith("Operator", ScopePath.INSTALL);
        PrincipalRef viewer = principalWith("Viewer", ScopePath.INSTALL);

        assertThatThrownBy(() -> service.set(operator, origin(), tree.organization(), "cache", 1))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.set(viewer, origin(), tree.organization(), "cache", 1))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(storedMax()).isNull();
    }

    @Test
    void anInstallBoundAdministratorSettingAnotherOrganizationsLimitFindsItAbsentAndNothingIsWritten() {
        PrincipalRef admin = installAdmin();
        Tree other = TargetFixtures.seedTree();
        RequestOrigin origin = origin();

        assertThatThrownBy(() -> service.set(admin, origin, other.organization(), "cache", 7))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.entitlement WHERE organization_id = :o",
                Map.of("o", other.organization()), Integer.class)).as("no entitlement row for the other organization")
                .isZero();
        assertThat(TargetFixtures.auditRows("entitlement", origin.correlationId())).as("and no audit row").isEmpty();
        assertThat(storedMax()).as("and the caller's own organization is untouched").isNull();
    }

    @Test
    void settingTheLimitOfANonexistentOrganizationIsAbsentNotAForeignKeyViolation() {
        PrincipalRef admin = installAdmin();

        assertThatThrownBy(() -> service.set(admin, origin(), UUID.randomUUID(), "cache", 7))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPrincipalBoundOnlyBelowTheOrganizationIsRefusedTheInstallResource() {
        PrincipalRef envAdmin = principalWith("Administrator", tree.environmentScope());

        assertThatThrownBy(() -> service.set(envAdmin, origin(), tree.organization(), "cache", 1))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- the refusal below use, and what counts as use

    @Test
    void loweringTheLimitBelowTheDeclaredCountIsRefusedAndTheStoredLimitIsUnchanged() {
        PrincipalRef admin = installAdmin();
        service.set(admin, origin(), tree.organization(), "cache", 5);
        declare(admin);
        declare(admin);
        declare(admin);

        assertThatThrownBy(() -> service.set(admin, origin(), tree.organization(), "cache", 2))
                .isInstanceOfSatisfying(EntitlementRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(EntitlementRefusedException.Reason.BELOW_DECLARED));
        assertThat(storedMax()).isEqualTo(5);

        assertThat(service.set(admin, origin(), tree.organization(), "cache", 3).declaredInstances()).isEqualTo(3);
        assertThat(storedMax()).as("equal to use is allowed").isEqualTo(3);
        assertThatThrownBy(() -> declare(admin)).as("and a declare past it is the existing quota refusal")
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void anUndeclaredInstanceDoesNotCountSoTheLimitMayBeLoweredAfterItsRemoval() {
        PrincipalRef admin = installAdmin();
        service.set(admin, origin(), tree.organization(), "cache", 3);
        UUID first = declare(admin);
        declare(admin);
        assertThatThrownBy(() -> service.set(admin, origin(), tree.organization(), "cache", 1))
                .isInstanceOf(EntitlementRefusedException.class);

        retirement.deregister(admin, origin(), tree.organization(), first);

        Entitlement lowered = service.set(admin, origin(), tree.organization(), "cache", 1);
        assertThat(lowered.maxInstances()).isEqualTo(1);
        assertThat(lowered.declaredInstances()).as("the retired instance is not counted").isEqualTo(1);
    }

    @Test
    void settingTheLimitTheRowAlreadyHoldsIsAnIdempotentNoOpWithNoSecondAuditRowAndTheSameSetAt() {
        PrincipalRef admin = installAdmin();
        Entitlement first = service.set(admin, origin(), tree.organization(), "cache", 6);
        RequestOrigin again = origin();

        Entitlement second = service.set(admin, again, tree.organization(), "cache", 6);

        assertThat(second).isEqualTo(first);
        assertThat(TargetFixtures.auditRows("entitlement", again.correlationId())).isEmpty();
    }

    @Test
    void changingTheLimitUpdatesThePreviousRowAsTheActorAndAuditsAnUpdate() {
        PrincipalRef admin = installAdmin();
        service.set(admin, origin(), tree.organization(), "cache", 2);
        RequestOrigin raise = origin();

        Entitlement raised = service.set(admin, raise, tree.organization(), "cache", 9);

        assertThat(raised.maxInstances()).isEqualTo(9);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.entitlement WHERE organization_id = :o",
                Map.of("o", tree.organization()), Long.class)).isEqualTo(1);
        assertThat(TargetFixtures.auditRows("entitlement", raise.correlationId())).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("audit_operation", "ENTITLEMENT_SET")
                        .containsEntry("actor_principal_id", admin.id()));
    }

    @Test
    void aSetTakesTheEntitlementRowLockBeforeItCountsSoADeclareInFlightIsNeverMissed() throws Exception {
        PrincipalRef admin = installAdmin();
        service.set(admin, origin(), tree.organization(), "cache", 5);
        declare(admin);
        declare(admin);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A third declare in flight: it holds the entitlement row FOR UPDATE (OR-40) and has inserted an instance
            // that no other transaction can yet see. GovernanceService joins this outer transaction.
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                governance.register(admin, origin(), tree.organization(), tree.environment(), entry, UUID.randomUUID());
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Entitlement> setter =
                    pool.submit(() -> service.set(admin, origin(), tree.organization(), "cache", 2));

            assertThatThrownBy(() -> setter.get(700, TimeUnit.MILLISECONDS))
                    .as("the set waits on the row lock while the declare's transaction is open")
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            // The count was taken AFTER the lock: it sees three, not the two a count before the lock would see, so
            // 2 is refused. A set that counted first would pass its check, then wait on the upsert, and lower the
            // limit below the instance the declare just committed.
            assertThatThrownBy(() -> setter.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(EntitlementRefusedException.class);
            assertThat(storedMax()).isEqualTo(5);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- the read

    @Test
    void theListIsTheOrganizationsOwnEntitlementsWithTheirDeclaredCountsAndNeverAnotherOrganizations() {
        PrincipalRef admin = installAdmin();
        service.set(admin, origin(), tree.organization(), "cache", 5);
        service.set(admin, origin(), tree.organization(), "file-mesh", 2);
        declare(admin);
        Tree other = TargetFixtures.seedTree();
        UUID otherPrincipal = TargetFixtures.localPrincipal(other.organization(), "o-" + UUID.randomUUID(), "x", false);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution, max_instances,"
                + " set_by, set_at) VALUES (?, 'foreign-solution', 1, ?, now())", other.organization(), otherPrincipal);
        PrincipalRef viewer = principalWith("Viewer", tree.organizationScope());

        List<Entitlement> listed = service.list(viewer, tree.organization());

        assertThat(listed).extracting(Entitlement::solution).containsExactly("cache", "file-mesh");
        assertThat(listed).extracting(Entitlement::declaredInstances).containsExactly(1L, 0L);
        assertThat(listed).extracting(Entitlement::maxInstances).containsExactly(5, 2);
    }

    @Test
    void aListOfAnotherOrganizationIsAsAbsentAsANonexistentOne() {
        Tree other = TargetFixtures.seedTree();
        PrincipalRef viewer = principalWith("Viewer", tree.organizationScope());

        assertThatThrownBy(() -> service.list(viewer, other.organization())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validationRefusesBeforeAnyRead() {
        PrincipalRef admin = installAdmin();

        assertThatThrownBy(() -> service.set(admin, origin(), tree.organization(), "Not Valid", 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set(admin, origin(), tree.organization(), "cache", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storedMax()).isNull();
    }
}
