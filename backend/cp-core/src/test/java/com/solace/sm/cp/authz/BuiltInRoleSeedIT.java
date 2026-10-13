package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BuiltInRoleSeedIT {

    private NamedParameterJdbcTemplate jdbc;
    private NamedParameterJdbcTemplate runtime;
    /** The schema as OR-80 (V014) left it: the Operator's 21, before OR-121's three. */
    private NamedParameterJdbcTemplate atOr80;
    /**
     * The schema exactly as OR-121 (V016) left it. {@code latest} moved on (V021/OR-130 grants
     * {@code connection.view} to the Operator too), so the "OR-121 changed exactly these three Operator
     * verbs" diff needs a snapshot pinned here, not at {@code latest}.
     */
    private NamedParameterJdbcTemplate afterOr121;
    /** A fully migrated schema: the role assertions that OR-121 (V016) changed read here, not at the V006 seed. */
    private NamedParameterJdbcTemplate latest;

    /** D10-G2-APPROVAL §1.9.1, rows 39-43: the five verbs the V003 seed does not hold and V016 adds. */
    private static final Set<String> V016_VERBS = Set.of("instance.undeclare", "instance.restart.overrideSafety",
            "target.edit", "hierarchy.edit", "entitlement.set");

    /**
     * D10-G2-APPROVAL §1.11.1, rows 48-51: the four agent verbs V026 adds (OR-126 A8, OR-128 (4)). Plan 015 wrote them as
     * V021 and rows 44-47; the OR-147 reconciliation renumbered the migration after the Connections ones.
     */
    private static final Set<String> V026_VERBS = Set.of("agent.enrol", "agent.revoke", "agent.drain", "agent.view");

    /** The Operator's three additions in V016: OR-121 OPS-Q3, OPS-Q6 and OPS-Q11 (a). */
    private static final Set<String> OPERATOR_V016_VERBS =
            Set.of("instance.undeclare", "instance.restart.overrideSafety", "instance.stop");

    /** OR-130 (V021): the four connection.* verbs, also absent from the V003 seed, same as V016's five. */
    private static final Set<String> V021_VERBS =
            Set.of("connection.view", "connection.create", "connection.edit", "connection.delete");

    /** Every verb the V003 seed does not hold: V016's five, V021's four (connection.*) and V026's four (agent.*). */
    private static final Set<String> AFTER_SEED_VERBS = Stream.of(V016_VERBS, V021_VERBS, V026_VERBS)
            .flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("006");
        runtime = SmControlPostgres.runtimeJdbcAtVersion("006");
        atOr80 = SmControlPostgres.jdbcAtVersion("014");
        afterOr121 = SmControlPostgres.jdbcAtVersion("016");
        latest = SmControlPostgres.jdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.role_binding");
    }

    @Test
    void exactlyTheThreeApprovedBuiltInRolesAreSeeded() {
        assertThat(roleNames()).containsExactlyInAnyOrder("Administrator", "Viewer", "Operator");
    }

    @Test
    void administratorHoldsEveryCatalogPermissionAndNoOtherAtTheSeed() {
        // V003's seed is the ratified 38: the five of §1.9.1 arrive in V016, never in the seed (OR-22).
        assertThat(permissionsOf("Administrator"))
                .containsExactlyInAnyOrderElementsOf(catalogIdsWithout(AFTER_SEED_VERBS));
    }

    @Test
    void administratorHoldsEveryVerbTheDatabaseCatalogHoldsByAQueryNotAList() {
        // OR-121: "every catalog verb (asserted by a query, not a list)" - a verb added to the catalog without an
        // Administrator grant makes this fail, with no constant in this test to forget to bump.
        assertThat(latest.queryForList("SELECT permission_id FROM sm_control.permission_catalog EXCEPT "
                        + "SELECT permission_id FROM sm_control.role_permission "
                        + "WHERE role_id = '00000000-0000-4000-8000-000000000001'", Map.of(), String.class))
                .as("a catalog verb the Administrator does not hold").isEmpty();
        assertThat(permissionsOf(latest, "Administrator")).containsExactlyInAnyOrderElementsOf(catalogIds());
        assertThat(permissionsOf(latest, "Administrator")).hasSize(51);
    }

    @Test
    void theDatabaseCatalogIsExactlyTheFiftyOneTheEnumHolds() {
        assertThat(Set.copyOf(latest.queryForList("SELECT permission_id FROM sm_control.permission_catalog",
                Map.of(), String.class))).containsExactlyInAnyOrderElementsOf(catalogIds());
        assertThat(Set.copyOf(jdbc.queryForList("SELECT permission_id FROM sm_control.permission_catalog",
                Map.of(), String.class))).as("V003's seed is untouched (OR-22)")
                .containsExactlyInAnyOrderElementsOf(catalogIdsWithout(AFTER_SEED_VERBS));
    }

    @Test
    void viewerHoldsExactlyTheEightApprovedReadPermissionsAtTheSeed() {
        assertThat(permissionsOf("Viewer")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view");
    }

    @Test
    void operatorHoldsExactlyTheNineteenApprovedPermissionsAtTheSeed() {
        // V003's seed, before OR-80: the approved 19 (D10-G2-APPROVAL section 1.8).
        assertThat(permissionsOf(jdbc, "Operator")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view",
                "instance.bindTarget", "target.use", "instance.create", "config.edit",
                "config.apply", "config.rollback", "instance.restart", "metric.declare",
                "dashboard.edit", "dashboard.reset", "mesh.ticket.mint");
    }

    @Test
    void operatorHoldsExactlyTheTwentyOnePermissionsAfterOrMQ16() {
        assertThat(permissionsOf(atOr80, "Operator")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view",
                "instance.bindTarget", "target.use", "instance.create", "config.edit",
                "config.apply", "config.rollback", "instance.restart", "metric.declare",
                "dashboard.edit", "dashboard.reset", "mesh.ticket.mint",
                "metrics.export", "metrics.forceRaw");
    }

    @Test
    void operatorHoldsExactlyTheTwentySevenPermissionsAfterV026() {
        // §1.9: the 21 of OR-80 plus instance.stop (OPS-Q11 (a)), instance.undeclare (OPS-Q3 P-1) and
        // instance.restart.overrideSafety (OPS-Q6 B-1) = 24; plus connection.view (OR-130, V021) = 25; plus agent.view
        // and agent.drain (OR-126 A8, V026, §1.11.1) = 27. The other two new Administrator verbs from V016,
        // connection.create/edit/delete from V021 and agent.enrol/revoke from V026 are not Operator verbs.
        assertThat(permissionsOf(latest, "Operator")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view",
                "instance.bindTarget", "target.use", "instance.create", "config.edit",
                "config.apply", "config.rollback", "instance.restart", "metric.declare",
                "dashboard.edit", "dashboard.reset", "mesh.ticket.mint",
                "metrics.export", "metrics.forceRaw",
                "instance.stop", "instance.undeclare", "instance.restart.overrideSafety",
                "connection.view", "agent.view", "agent.drain");
        assertThat(permissionsOf(latest, "Operator")).hasSize(27)
                .doesNotContain("target.edit", "hierarchy.edit", "entitlement.set",
                        "connection.create", "connection.edit", "connection.delete", "agent.enrol", "agent.revoke");
    }

    @Test
    void theOperatorGainsExactlyThreeVerbsBetweenOr80AndOr121() {
        // Pinned at "016" (OR-121's own end-state), not `latest`: `latest` now also carries V021/OR-130's
        // connection.view Operator grant, which would otherwise show up as a fourth "gained" verb here and make
        // this test about V021, not about what OR-121 itself changed.
        Set<String> added = new java.util.HashSet<>(permissionsOf(afterOr121, "Operator"));
        added.removeAll(permissionsOf(atOr80, "Operator"));
        added.removeAll(V021_VERBS); // defensive: "016" carries neither later batch (V021 connection.*, V026 agent.*)
        added.removeAll(V026_VERBS);
        assertThat(added).containsExactlyInAnyOrderElementsOf(OPERATOR_V016_VERBS);
        assertThat(permissionsOf(atOr80, "Operator")).as("V014 is untouched (OR-22)")
                .doesNotContain("instance.stop", "instance.undeclare", "instance.restart.overrideSafety");
    }

    @Test
    void v026GrantsTheFourAgentVerbsToAdministratorAndTheRulingsSubsetToTheOthersAndTheSeedIsUntouched() {
        assertThat(permissionsOf(latest, "Administrator")).containsAll(V026_VERBS);
        assertThat(permissionsOf(atOr80, "Administrator")).doesNotContainAnyElementsOf(V026_VERBS);
        assertThat(permissionsOf(jdbc, "Administrator")).as("the V003 seed is untouched (OR-22)")
                .doesNotContainAnyElementsOf(V026_VERBS);
        assertThat(permissionsOf(latest, "Operator")).filteredOn(V026_VERBS::contains)
                .containsExactlyInAnyOrder("agent.view", "agent.drain");
        assertThat(permissionsOf(latest, "Viewer")).filteredOn(V026_VERBS::contains).containsExactly("agent.view");
    }

    @Test
    void v016GrantsTheFiveNewVerbsToAdministratorAndNoVerbToViewer() {
        assertThat(permissionsOf(latest, "Administrator")).containsAll(V016_VERBS);
        assertThat(permissionsOf(atOr80, "Administrator")).doesNotContainAnyElementsOf(V016_VERBS);
        assertThat(permissionsOf(latest, "Viewer")).doesNotContainAnyElementsOf(V016_VERBS)
                .doesNotContain("instance.stop");
    }

    @Test
    void v016sCatalogAndGrantInsertsAreAuditedWithNoActorLikeV014s() {
        // V021 (OR-130) and V026 (OR-126 A8) repeat V016's own pattern (which itself repeats V014's): a migrator catalog
        // INSERT is audited with NULL actor. `latest` carries all three batches, so the expected set is all of them.
        assertThat(Set.copyOf(latest.queryForList("SELECT row_key ->> 'permission_id' FROM sm_control.change_audit "
                + "WHERE table_name = 'permission_catalog' AND operation = 'INSERT' AND actor_principal_id IS NULL",
                Map.of(), String.class))).as("V016's five, V021's four and V026's four, each a no-actor migration insert")
                .containsExactlyInAnyOrderElementsOf(AFTER_SEED_VERBS);
        assertThat(latest.queryForObject("SELECT count(*) FROM sm_control.change_audit "
                + "WHERE table_name = 'role_permission' AND operation = 'INSERT' AND actor_principal_id IS NULL "
                + "AND new_values ->> 'permission_id' IN ('instance.undeclare', 'instance.restart.overrideSafety', "
                + "'target.edit', 'hierarchy.edit', 'entitlement.set', 'instance.stop')", Map.of(), Integer.class))
                .as("five Administrator grants + three Operator grants of which instance.stop is the sixth verb: "
                        + "5 + 3 = 8 rows").isEqualTo(8);
    }

    @Test
    void metricsExportAndForceRawAreNowOperatorVerbs() {
        // OR-80 supersedes D10-G2-APPROVAL limitation 4 for exactly these two names; OR-121 adds instance.stop (§1.9).
        // The rest stay Administrator-only.
        assertThat(permissionsOf(latest, "Operator")).contains("metrics.export", "metrics.forceRaw", "instance.stop")
                .doesNotContain("mesh.stream.grant", "audit.view", "user.edit");
        assertThat(permissionsOf(jdbc, "Operator")).as("the V003 seed is untouched")
                .doesNotContain("metrics.export", "metrics.forceRaw", "instance.stop");
    }

    @Test
    void viewerGainsExactlyConnectionViewAndAgentView() {
        // OR-121/V016 granted Viewer nothing (v016GrantsTheFiveNewVerbsTo... covers that). OR-130/V021 grants
        // connection.view and OR-126 A8/V026 grants agent.view (§1.10.1, §1.11.1): the Viewer's 8 plus two = 10.
        assertThat(permissionsOf(latest, "Viewer")).containsExactlyInAnyOrder(
                "solution.view", "instance.view", "config.view", "health.view",
                "logs.read", "metrics.query", "dashboard.view", "target.view", "connection.view", "agent.view");
        assertThat(permissionsOf(latest, "Viewer")).hasSize(10);
        assertThat(permissionsOf(latest, "Administrator")).containsExactlyInAnyOrderElementsOf(catalogIds());
    }

    @Test
    void everySeededRolePermissionHasAnApprovedCatalogForeignKey() {
        Set<String> persisted = Set.copyOf(jdbc.queryForList(
                "SELECT permission_id FROM sm_control.role_permission", Map.of(), String.class));
        assertThat(persisted).containsExactlyInAnyOrderElementsOf(catalogIdsWithout(AFTER_SEED_VERBS));
        persisted.forEach(Permission::byId);
        Set<String> persistedLatest = Set.copyOf(latest.queryForList(
                "SELECT permission_id FROM sm_control.role_permission", Map.of(), String.class));
        assertThat(persistedLatest).containsExactlyInAnyOrderElementsOf(catalogIds());
        persistedLatest.forEach(Permission::byId);
    }

    @Test
    void aRolePermissionCannotNameAnUnapprovedCatalogId() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sm_control.role_permission (role_id, permission_id) "
                        + "VALUES ((SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), 'instance.explode')",
                Map.of())).satisfies(error -> assertSqlState(error, "23503"));
    }

    @Test
    void aBindingMustReferenceAnExistingCanonicalScope() {
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                        + "VALUES (:id, :organizationId, 'idp', :username)",
                new MapSqlParameterSource().addValue("id", principalId).addValue("organizationId", organizationId)
                        .addValue("username", "role-binding-" + principalId + "@example.test"));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO sm_control.role_binding "
                        + "(binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = 'Viewer'), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", principalId)
                        .addValue("scopeId", UUID.randomUUID())))
                .satisfies(error -> assertSqlState(error, "23503"));
    }

    @Test
    void theActualRuntimeRoleCanReadDefinitionsButCannotWriteRolesOrDeletePrincipals() {
        assertThat(runtime.queryForObject("SELECT count(*) FROM sm_control.role", Map.of(), Integer.class))
                .isEqualTo(3);
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute(
                "INSERT INTO sm_control.role (role_id, name, built_in) VALUES "
                        + "('00000000-0000-4000-8000-000000000099', 'Unapproved', true)"));
        assertRuntimeRefused(() -> runtime.getJdbcTemplate().execute("DELETE FROM sm_control.principal"));
    }

    @Test
    void mutationGrantingRoleInsertMakesTheRuntimeRefusalTestFail() {
        // OR-25: once the grant permits this write, sm_cp_runtime still needs COMPLETE audit context
        // to actually perform it — this probe's only job is to prove the GRANT was the earlier
        // blocker (not the trigger), so it supplies LOCKOUT_CLEARED (actor-exempt; nothing here logs
        // anyone in or out — the write is a synthetic probe, not a real role-catalog change).
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);
        TransactionTemplate runtimeTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        assertRefusalFailsAfter("GRANT INSERT ON sm_control.role TO sm_cp_runtime",
                "REVOKE INSERT ON sm_control.role FROM sm_cp_runtime",
                () -> runtimeTransaction.executeWithoutResult(status -> {
                    runtimeAuditContext.set(null, AuditOperations.LOCKOUT_CLEARED, UUID.randomUUID(),
                            "builtin-role-seed-it");
                    runtime.getJdbcTemplate().execute(
                            "INSERT INTO sm_control.role (role_id, name, built_in) VALUES "
                                    + "('00000000-0000-4000-8000-000000000098', 'Mutation role', true)");
                }),
                () -> jdbc.getJdbcTemplate().execute(
                        "DELETE FROM sm_control.role WHERE role_id = '00000000-0000-4000-8000-000000000098'"));
    }

    @Test
    void mutationGrantingPrincipalDeleteMakesTheRuntimeRefusalTestFail() {
        UUID organizationId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        assertRefusalFailsAfter("GRANT DELETE ON sm_control.principal TO sm_cp_runtime",
                "REVOKE DELETE ON sm_control.principal FROM sm_cp_runtime", () -> {
            jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                    new MapSqlParameterSource("id", organizationId));
            jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                            + "VALUES (:id, :organizationId, 'idp', :username)",
                    new MapSqlParameterSource().addValue("id", principalId).addValue("organizationId", organizationId)
                            .addValue("username", "mutation-" + principalId + "@example.test"));
        }, () -> deletePrincipalAndRollBackAsRuntime(principalId), () -> { });
    }

    @Test
    void mutationDroppingTheCatalogForeignKeyMakesTheUnknownPermissionRefusalTestFail() {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute(
                    "ALTER TABLE sm_control.role_permission DROP CONSTRAINT role_permission_catalog_fk");
            assertThatThrownBy(() -> aRolePermissionCannotNameAnUnapprovedCatalogId())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
            status.setRollbackOnly();
        });
    }

    private List<String> roleNames() {
        return jdbc.queryForList("SELECT name FROM sm_control.role ORDER BY name", Map.of(), String.class);
    }

    private Set<String> permissionsOf(String roleName) {
        return permissionsOf(jdbc, roleName);
    }

    private static Set<String> permissionsOf(NamedParameterJdbcTemplate schema, String roleName) {
        return Set.copyOf(schema.queryForList("SELECT rp.permission_id FROM sm_control.role_permission rp "
                        + "JOIN sm_control.role r ON r.role_id = rp.role_id WHERE r.name = :roleName",
                Map.of("roleName", roleName), String.class));
    }

    private static Set<String> catalogIds() {
        return Arrays.stream(Permission.values()).map(Permission::id).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> catalogIdsWithout(Set<String> excluded) {
        return Arrays.stream(Permission.values()).map(Permission::id).filter(id -> !excluded.contains(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertRefused(SqlAction action) {
        assertThatThrownBy(action::run).satisfies(error -> assertSqlState(error, "42501"));
    }

    private void assertRefusalFailsAfter(String grant, String revoke, SqlAction action, SqlAction cleanup) {
        assertRefusalFailsAfter(grant, revoke, () -> { }, action, cleanup);
    }

    private void assertRefusalFailsAfter(
            String grant, String revoke, SqlAction setup, SqlAction action, SqlAction cleanup) {
        jdbc.getJdbcTemplate().execute(grant);
        try {
            setup.run();
            assertThatThrownBy(() -> assertRefused(action))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
        } finally {
            try {
                cleanup.run();
            } finally {
                jdbc.getJdbcTemplate().execute(revoke);
            }
        }
    }

    private void assertRuntimeRefused(SqlAction action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertRefused(action);
            status.setRollbackOnly();
        });
    }

    private void deletePrincipalAndRollBackAsRuntime(UUID principalId) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        // OR-25: once the grant permits this DELETE, sm_cp_runtime still needs valid audit context.
        // LOCKOUT_CLEARED (actor-exempt) is used deliberately rather than the deleted principal's own
        // id: the row is already gone by the time the AFTER DELETE trigger inserts its audit row, so
        // naming it as the actor would fail change_audit's own actor_principal_id FK.
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);
        transaction.executeWithoutResult(status -> {
            runtimeAuditContext.set(null, AuditOperations.LOCKOUT_CLEARED, UUID.randomUUID(), "builtin-role-seed-it");
            assertThat(runtime.update("DELETE FROM sm_control.principal WHERE id = :id", Map.of("id", principalId)))
                    .as("the disposable principal is deleted only when the DELETE grant mutation is present")
                    .isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    private static void assertSqlState(Throwable error, String sqlState) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isEqualTo(sqlState);
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run();
    }
}
