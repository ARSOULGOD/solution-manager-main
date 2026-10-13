package com.solace.sm.cp.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcHierarchyWriteRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.persistence.JdbcTargetRepository;
import com.solace.sm.cp.target.TargetFixtures;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import com.solace.sm.cp.target.TargetService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 013 T2.2: {@link HierarchyAdministrationService} against a real {@code sm_control} schema at the head (V018),
 * running <b>as {@code sm_cp_runtime}</b> (the OR-25 audit rule only fires for that role). Authorization is the real
 * {@link ScopeEvaluatingAuthorizationService} over real grants, never a stub; every principal is bound by a built-in role
 * at a scope, so the tests prove the verb and the scope, not a fake.
 *
 * <p><b>OR-123:</b> uniqueness is asserted through the database's own indexes. No test here, and no line of the service,
 * compares two names in Java.
 */
class HierarchyAdministrationServiceIT {

    private final NamedParameterJdbcTemplate runtime = TargetFixtures.runtime();
    private final NamedParameterJdbcTemplate migrator = TargetFixtures.migrator();

    private HierarchyAdministration service;
    private TargetService targets;
    private Tree tree;

    @BeforeEach
    void setUp() {
        DataSource dataSource = runtime.getJdbcTemplate().getDataSource();
        ScopePathResolver paths = new ScopePathResolver(new JdbcResourceScopeDirectory(runtime));
        JdbcPrincipalOrganizationResolver organizations = new JdbcPrincipalOrganizationResolver(runtime);
        JdbcAuthorizationGrantRepository grants = new JdbcAuthorizationGrantRepository(runtime);
        ScopeEvaluatingAuthorizationService authorization =
                new ScopeEvaluatingAuthorizationService(organizations, grants, paths);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new HierarchyAdministrationService(new JdbcHierarchyWriteRepository(runtime), authorization,
                new JdbcAuditContext(runtime), tx, UUID::randomUUID);
        targets = new TargetService(new JdbcTargetRepository(runtime), authorization, new JdbcAuditContext(runtime), tx,
                Clock.systemUTC());
        tree = TargetFixtures.seedTree();
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.30", UUID.randomUUID());
    }

    private UUID principalWith(String role, ScopePath scope) {
        UUID id = TargetFixtures.localPrincipal(tree.organization(), "h-" + UUID.randomUUID(), "unused-credential", false);
        if (scope.depth() == 0) {
            // The one install scope row is seeded by V007 (scope_one_install) and cannot be canonicalized at runtime, so an
            // install-level grant binds to it directly (the same as EntitlementAdministrationServiceIT).
            migrator.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                            + " VALUES (:binding, :principal, (SELECT role_id FROM sm_control.role WHERE name = :role),"
                            + " '00000000-0000-4000-8000-000000000010')",
                    Map.of("binding", UUID.randomUUID(), "principal", id, "role", role));
        } else {
            TargetFixtures.bind(id, role, scope);
        }
        return id;
    }

    private PrincipalRef orgAdmin() {
        return new PrincipalRef(principalWith("Administrator", tree.organizationScope()));
    }

    /** An Administrator bound at INSTALL scope: the only grant that may rename an organization (OR-125 (3)). */
    private PrincipalRef installAdmin() {
        return new PrincipalRef(principalWith("Administrator", ScopePath.INSTALL));
    }

    private long rows(String table, UUID organization) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control." + table + " WHERE organization_id = :o",
                Map.of("o", organization), Long.class);
    }

    private List<Map<String, Object>> audit(String table, UUID correlation) {
        return TargetFixtures.auditRows(table, correlation);
    }

    // ---------------------------------------------------------------- create

    @Test
    void aSubOrganizationIsCreatedTopLevelAuditedAsHierarchyNodeCreatedAndNoScopeRowIsInserted() {
        PrincipalRef admin = orgAdmin();
        long scopesBefore = rows("scope", tree.organization());
        RequestOrigin origin = origin();

        HierarchyAdministration.Created created =
                service.createSubOrganization(admin, origin, tree.organization(), "Payments");

        assertThat(created.created()).isTrue();
        assertThat(created.node().name()).isEqualTo("Payments");
        Map<String, Object> row = migrator.queryForMap("SELECT name, parent_unit_id FROM sm_control.organization_unit"
                + " WHERE organization_unit_id = :id", Map.of("id", created.node().id()));
        assertThat(row.get("name")).isEqualTo("Payments");
        assertThat(row.get("parent_unit_id")).as("top-level only (SM-D32)").isNull();
        assertThat(rows("scope", tree.organization())).as("scope rows are minted on first use, not by a create")
                .isEqualTo(scopesBefore);
        List<Map<String, Object>> audits = audit("organization_unit", origin.correlationId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("audit_operation", "HIERARCHY_NODE_CREATED")
                .containsEntry("actor_principal_id", admin.id()).containsEntry("client_address", "203.0.113.30");
        assertThat(audits.get(0).get("row_key").toString()).contains(created.node().id().toString());
    }

    @Test
    void anEnvironmentIsCreatedAndIsAtOnceATargetsOwnershipScopeWithNoExplicitScopeInsert() {
        PrincipalRef admin = orgAdmin();

        HierarchyAdministration.Created created =
                service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Staging");
        ScopePath newEnvironment = ScopePath.environment(tree.organization(), tree.unit(), created.node().id());
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.scope WHERE environment_id = :e",
                Map.of("e", created.node().id()), Long.class)).as("no scope row yet").isZero();

        TargetView target = targets.register(admin, tree.organization(),
                new TargetRegistration.Request(null, "staging-vm", "docker", "ssh-docker", newEnvironment), origin());

        assertThat(target.ownershipScope()).isEqualTo(newEnvironment);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.scope WHERE environment_id = :e",
                Map.of("e", created.node().id()), Long.class)).as("minted by the first use").isEqualTo(1);
    }

    @Test
    void aUnitBoundPrincipalCreatesEnvironmentsInItsOwnUnitAndFindsASiblingUnitAbsent() {
        UUID siblingUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), siblingUnit);
        PrincipalRef unitAdmin = new PrincipalRef(principalWith("Administrator", tree.unitScope()));

        HierarchyAdministration.Created own =
                service.createEnvironment(unitAdmin, origin(), tree.organization(), tree.unit(), "Own");

        assertThat(own.created()).isTrue();
        assertThatThrownBy(() -> service.createEnvironment(unitAdmin, origin(), tree.organization(), siblingUnit, "X"))
                .as("a sibling unit is exactly as absent as a nonexistent one")
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.createSubOrganization(unitAdmin, origin(), tree.organization(), "Nope"))
                .as("a grant below the organization makes it visible, never authorized for a create there")
                .isInstanceOf(AccessDeniedException.class);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.environment WHERE organization_unit_id = :u"
                + " AND organization_id = :o", Map.of("u", siblingUnit, "o", tree.organization()), Long.class)).isZero();
    }

    @Test
    void aViewerAndAnOperatorHoldNoHierarchyEditAndNothingIsWritten() {
        PrincipalRef viewer = new PrincipalRef(principalWith("Viewer", tree.organizationScope()));
        PrincipalRef operator = new PrincipalRef(principalWith("Operator", tree.organizationScope()));
        long before = rows("organization_unit", tree.organization());

        for (PrincipalRef weak : List.of(viewer, operator)) {
            assertThatThrownBy(() -> service.createSubOrganization(weak, origin(), tree.organization(), "Nope"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.renameEnvironment(weak, origin(), tree.organization(), tree.unit(),
                    tree.environment(), "Nope")).isInstanceOf(AccessDeniedException.class);
        }

        assertThat(rows("organization_unit", tree.organization())).isEqualTo(before);
    }

    @Test
    void anotherOrganizationsNodesAreAsAbsentAsNonexistentOnesForEveryOperation() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();

        assertThatThrownBy(() -> service.createSubOrganization(admin, origin(), other.organization(), "X"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.createEnvironment(admin, origin(), other.organization(), other.unit(), "X"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.createEnvironment(admin, origin(), tree.organization(), other.unit(), "X"))
                .as("another organization's unit under this organization's path").isInstanceOf(ResourceNotFoundException.class);
        // an organization rename is install-scoped (OR-125 (3)): the organization-bound caller is refused at the install
        // anchor, whatever organization it names; an install-bound one finds another organization absent
        assertThatThrownBy(() -> service.renameOrganization(installAdmin(), origin(), other.organization(), "X"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.renameSubOrganization(admin, origin(), other.organization(), other.unit(), "X"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.renameEnvironment(admin, origin(), other.organization(), other.unit(),
                other.environment(), "X")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.renameEnvironment(admin, origin(), tree.organization(), UUID.randomUUID(),
                tree.environment(), "X")).as("a mismatched parent pair").isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- replay and conflict (OR-123)

    @Test
    void creatingTheSameNameUnderTheSameParentTwiceIsAnIdenticalReplayThatWritesAndAuditsNothing() {
        PrincipalRef admin = orgAdmin();
        HierarchyAdministration.Created first =
                service.createSubOrganization(admin, origin(), tree.organization(), "Replayed");
        RequestOrigin again = origin();

        HierarchyAdministration.Created second =
                service.createSubOrganization(admin, again, tree.organization(), "Replayed");

        assertThat(second.created()).isFalse();
        assertThat(second.node()).isEqualTo(first.node());
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.organization_unit WHERE name = 'Replayed'"
                + " AND organization_id = :o", Map.of("o", tree.organization()), Long.class)).isEqualTo(1);
        assertThat(audit("organization_unit", again.correlationId())).as("a replay writes no audit row").isEmpty();
    }

    @Test
    void aNameThatDiffersOnlyByCaseFromASiblingIsTheNamedConflictForEveryNodeKind() {
        PrincipalRef admin = orgAdmin();
        service.createSubOrganization(admin, origin(), tree.organization(), "Platform");
        service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Production");

        assertThatThrownBy(() -> service.createSubOrganization(admin, origin(), tree.organization(), "PLATFORM"))
                .isInstanceOf(HierarchyNameConflictException.class);
        assertThatThrownBy(() -> service.createEnvironment(admin, origin(), tree.organization(), tree.unit(),
                "production")).isInstanceOf(HierarchyNameConflictException.class);

        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.organization_unit WHERE organization_id = :o"
                + " AND lower(name) = 'platform'", Map.of("o", tree.organization()), Long.class)).isEqualTo(1);
    }

    @Test
    void theSameNameUnderTwoDifferentParentsIsAcceptedBecauseTheClashIsAmongSiblingsOnly() {
        PrincipalRef admin = orgAdmin();
        UUID otherUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), otherUnit);

        HierarchyAdministration.Created one =
                service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Shared");
        HierarchyAdministration.Created two =
                service.createEnvironment(admin, origin(), tree.organization(), otherUnit, "Shared");

        assertThat(one.created()).isTrue();
        assertThat(two.created()).isTrue();
        assertThat(one.node().id()).isNotEqualTo(two.node().id());
    }

    @Test
    void twoIdenticalCreatesReleasedTogetherLeaveOneRowAndAnswerOneCreatedAndOneReplay() throws Exception {
        PrincipalRef admin = orgAdmin();
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<HierarchyAdministration.Created> racer = () -> {
            together.await(10, TimeUnit.SECONDS);
            return service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Raced");
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HierarchyAdministration.Created> outcomes = new ArrayList<>();
        try {
            List<Future<HierarchyAdministration.Created>> futures = List.of(pool.submit(racer), pool.submit(racer));
            for (Future<HierarchyAdministration.Created> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).extracting(HierarchyAdministration.Created::created).containsExactlyInAnyOrder(true, false);
        assertThat(outcomes.get(0).node()).isEqualTo(outcomes.get(1).node());
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.environment WHERE name = 'Raced'"
                + " AND organization_id = :o", Map.of("o", tree.organization()), Long.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- rename

    @Test
    void anOrganizationASubOrganizationAndAnEnvironmentAreRenamedAndEachWriteIsAuditedAsHierarchyNodeRenamed() {
        PrincipalRef admin = orgAdmin();
        PrincipalRef install = installAdmin();
        RequestOrigin origin = origin();
        String orgName = "Renamed Org " + UUID.randomUUID();

        assertThat(service.renameOrganization(install, origin, tree.organization(), orgName))
                .isEqualTo(new ScopeNode(tree.organization(), orgName));
        assertThat(service.renameSubOrganization(admin, origin, tree.organization(), tree.unit(), "Renamed Unit"))
                .isEqualTo(new ScopeNode(tree.unit(), "Renamed Unit"));
        assertThat(service.renameEnvironment(admin, origin, tree.organization(), tree.unit(), tree.environment(),
                "Renamed Env")).isEqualTo(new ScopeNode(tree.environment(), "Renamed Env"));

        for (String table : List.of("organization", "organization_unit", "environment")) {
            List<Map<String, Object>> rows = audit(table, origin.correlationId());
            assertThat(rows).as(table).hasSize(1);
            assertThat(rows.get(0)).containsEntry("audit_operation", "HIERARCHY_NODE_RENAMED")
                    .containsEntry("actor_principal_id", table.equals("organization") ? install.id() : admin.id());
        }
        assertThat(migrator.queryForObject("SELECT name FROM sm_control.environment WHERE environment_id = :e",
                Map.of("e", tree.environment()), String.class)).isEqualTo("Renamed Env");
    }

    @Test
    void renamingANodeToItsOwnNameWritesNothingAndRenamingOnlyItsCaseIsAllowedAndWritten() {
        PrincipalRef admin = orgAdmin();
        HierarchyAdministration.Created env =
                service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Casey");
        RequestOrigin same = origin();
        RequestOrigin recase = origin();

        service.renameEnvironment(admin, same, tree.organization(), tree.unit(), env.node().id(), "Casey");
        service.renameEnvironment(admin, recase, tree.organization(), tree.unit(), env.node().id(), "CASEY");

        assertThat(audit("environment", same.correlationId())).as("no change, no audit row").isEmpty();
        assertThat(audit("environment", recase.correlationId())).as("the node does not clash with itself").hasSize(1);
        assertThat(migrator.queryForObject("SELECT name FROM sm_control.environment WHERE environment_id = :e",
                Map.of("e", env.node().id()), String.class)).isEqualTo("CASEY");
    }

    @Test
    void aRenameOntoASiblingsNameIgnoringCaseIsTheNamedConflictAndLeavesBothUnchanged() {
        PrincipalRef admin = orgAdmin();
        HierarchyAdministration.Created a = service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Alpha");
        service.createEnvironment(admin, origin(), tree.organization(), tree.unit(), "Beta");

        assertThatThrownBy(() -> service.renameEnvironment(admin, origin(), tree.organization(), tree.unit(),
                a.node().id(), "bETA")).isInstanceOfSatisfying(HierarchyNameConflictException.class,
                e -> assertThat(e.getMessage()).doesNotContain("bETA"));

        assertThat(migrator.queryForObject("SELECT name FROM sm_control.environment WHERE environment_id = :e",
                Map.of("e", a.node().id()), String.class)).isEqualTo("Alpha");
    }

    @Test
    void organizationNamesAreUniqueAcrossTheInstallIgnoringCaseAndOnlyAnInstallBoundCallerEverSeesTheConflict() {
        PrincipalRef install = installAdmin();
        Tree other = TargetFixtures.seedTree();
        String otherName = migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", other.organization()), String.class);

        assertThatThrownBy(() -> service.renameOrganization(install, origin(), tree.organization(),
                otherName.toUpperCase(java.util.Locale.ROOT))).isInstanceOf(HierarchyNameConflictException.class);
    }

    @Test
    void anOrganizationBoundAdministratorCannotRenameItsOrganizationSoTheInstallWideConflictIsNoCrossTenantOracle() {
        // OR-125 (3): the refusal is the same for a name that another organization holds and for one nobody holds, and
        // nothing is written, so the caller learns nothing about another tenant's organization names
        PrincipalRef orgAdmin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        String takenName = migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", other.organization()), String.class);
        String ownName = migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", tree.organization()), String.class);
        RequestOrigin origin = origin();

        for (String name : List.of(takenName, takenName.toUpperCase(java.util.Locale.ROOT),
                "Never Used " + UUID.randomUUID())) {
            assertThatThrownBy(() -> service.renameOrganization(orgAdmin, origin, tree.organization(), name))
                    .as("the name %s", name).isExactlyInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.renameOrganization(orgAdmin, origin, other.organization(), "X"))
                .as("another, and a nonexistent, organization are refused exactly the same way")
                .isExactlyInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.renameOrganization(orgAdmin, origin, UUID.randomUUID(), "X"))
                .isExactlyInstanceOf(AccessDeniedException.class);

        assertThat(migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", tree.organization()), String.class)).isEqualTo(ownName);
        assertThat(audit("organization", origin.correlationId())).isEmpty();
    }

    @Test
    void aUnitBoundAdministratorAndAViewerAreRefusedAnOrganizationRename() {
        PrincipalRef unitAdmin = new PrincipalRef(principalWith("Administrator", tree.unitScope()));
        PrincipalRef viewer = new PrincipalRef(principalWith("Viewer", ScopePath.INSTALL));

        for (PrincipalRef weak : List.of(unitAdmin, viewer)) {
            assertThatThrownBy(() -> service.renameOrganization(weak, origin(), tree.organization(), "Nope"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void aRenameOfANodeThatIsAlreadyUnderAnotherNameEverywhereKeepsEveryOtherColumnAsItWas() {
        PrincipalRef admin = orgAdmin();
        Map<String, Object> before = migrator.queryForMap("SELECT organization_id, organization_unit_id, parent_unit_id"
                + " FROM sm_control.organization_unit WHERE organization_unit_id = :u", Map.of("u", tree.unit()));

        service.renameSubOrganization(admin, origin(), tree.organization(), tree.unit(), "Only the name");

        assertThat(migrator.queryForMap("SELECT organization_id, organization_unit_id, parent_unit_id"
                + " FROM sm_control.organization_unit WHERE organization_unit_id = :u", Map.of("u", tree.unit())))
                .isEqualTo(before);
    }
}
