package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.authz.ScopeCanonicalizationService;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.authz.ScopeNotCanonicalizableException;
import com.solace.sm.cp.authz.ScopeRequest;
import com.solace.sm.cp.authz.SessionContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 14 proof for the nine Task 2 obligations that have no later-task dependency. */
@Testcontainers(disabledWithoutDocker = true)
class ScopeCanonicalizationIT {

    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() {
        jdbc = SmControlPostgres.jdbcAtVersion("001");
    }

    @BeforeEach
    void clearTree() {
        truncateTree();
    }

    private void truncateTree() {
        jdbc.getJdbcTemplate().execute(
                "TRUNCATE sm_control.scope, sm_control.instance, sm_control.solution, sm_control.environment, "
                        + "sm_control.organization_unit, sm_control.organization");
    }

    @Test
    void aScopeWithWrongDepthIsRefusedByScopeShape() {
        UUID org = organization();
        UUID unit = unit(org);
        assertRefused("scope_shape", () -> scope("environment", org, unit, null, null, null));
    }

    @Test
    void mutationWithoutScopeShapeMakesTheWrongDepthWriteAccepted() {
        assertAcceptedWithDroppedConstraint("scope_shape", () -> {
            UUID org = organization();
            UUID unit = unit(org);
            scope("environment", org, unit, null, null, null);
        });
    }

    @Test
    void aWrongTypeAtTheUnitPositionIsRefusedByTheCompositeForeignKey() {
        UUID org = organization();
        UUID unit = unit(org);
        UUID environment = environment(org, unit);
        assertRefused("scope_organization_unit_fk", () -> scope("organization_unit", org, environment, null, null, null));
    }

    @Test
    void mutationWithoutTheUnitForeignKeyMakesTheWrongTypeWriteAccepted() {
        assertAcceptedWithDroppedConstraint("scope_organization_unit_fk", () -> {
            UUID org = organization();
            UUID unit = unit(org);
            UUID environment = environment(org, unit);
            scope("organization_unit", org, environment, null, null, null);
        });
    }

    @Test
    void aChildUnderTheWrongParentIsRefusedByTheCompositeForeignKey() {
        UUID org = organization();
        UUID correctUnit = unit(org);
        UUID otherUnit = unit(org);
        UUID environment = environment(org, correctUnit);
        assertRefused("scope_environment_fk", () -> scope("environment", org, otherUnit, environment, null, null));
    }

    @Test
    void mutationWithoutTheEnvironmentForeignKeyMakesTheWrongParentWriteAccepted() {
        assertAcceptedWithDroppedConstraint("scope_environment_fk", () -> {
            UUID org = organization();
            UUID correctUnit = unit(org);
            UUID otherUnit = unit(org);
            UUID environment = environment(org, correctUnit);
            scope("environment", org, otherUnit, environment, null, null);
        });
    }

    @Test
    void aCrossOrganizationComponentIsUnstorable() {
        UUID firstOrganization = organization();
        UUID secondOrganization = organization();
        UUID secondUnit = unit(secondOrganization);
        assertRefused("scope_organization_unit_fk", () -> scope(
                "organization_unit", firstOrganization, secondUnit, null, null, null));
    }

    @Test
    void mutationWithoutTheUnitForeignKeyMakesTheCrossOrganizationWriteAccepted() {
        assertAcceptedWithDroppedConstraint("scope_organization_unit_fk", () -> {
            UUID firstOrganization = organization();
            UUID secondOrganization = organization();
            UUID secondUnit = unit(secondOrganization);
            scope("organization_unit", firstOrganization, secondUnit, null, null, null);
        });
    }

    @Test
    void canonicalizeValidatesRequestedDescendantsAgainstTheSessionOrganizationInTheDatabase() {
        UUID firstOrganization = organization();
        UUID secondOrganization = organization();
        UUID secondUnit = unit(secondOrganization);
        UUID secondEnvironment = environment(secondOrganization, secondUnit);
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(new JdbcScopeStore(jdbc));

        assertRefused("scope_organization_unit_fk", () -> service.canonicalize(
                new SessionContext(firstOrganization), ScopeRequest.environment(secondUnit, secondEnvironment)));
    }

    @Test
    void aDuplicateEnvironmentScopeIsRefused() {
        UUID org = organization();
        UUID unit = unit(org);
        UUID environment = environment(org, unit);
        scope("environment", org, unit, environment, null, null);
        assertRefused("scope_one_environment", () -> scope("environment", org, unit, environment, null, null));
    }

    @Test
    void mutationWithoutTheEnvironmentUniqueIndexMakesTheDuplicateWriteAccepted() {
        assertAcceptedWithDroppedIndex("scope_one_environment", () -> {
            UUID org = organization();
            UUID unit = unit(org);
            UUID environment = environment(org, unit);
            scope("environment", org, unit, environment, null, null);
            scope("environment", org, unit, environment, null, null);
        });
    }

    @Test
    void anEnvironmentWithAnUnknownParentUnitIsUnstorable() {
        UUID org = organization();
        assertRefused("environment_organization_unit_fk", () -> jdbc.update(
                "INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id) "
                        + "VALUES (:org, :unit, :environment)",
                new MapSqlParameterSource().addValue("org", org).addValue("unit", UUID.randomUUID())
                        .addValue("environment", UUID.randomUUID())));
    }

    @Test
    void mutationWithoutTheEnvironmentParentForeignKeyMakesTheUnknownParentWriteAccepted() {
        assertAcceptedWithDroppedConstraint("environment", "environment_organization_unit_fk", () -> {
            UUID org = organization();
            jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id) "
                            + "VALUES (:org, :unit, :environment)",
                    new MapSqlParameterSource().addValue("org", org).addValue("unit", UUID.randomUUID())
                            .addValue("environment", UUID.randomUUID()));
        });
    }

    @Test
    void anInstanceWithAnUnknownSolutionParentIsUnstorable() {
        UUID org = organization();
        UUID unit = unit(org);
        UUID environment = environment(org, unit);
        assertRefused("instance_solution_fk", () -> jdbc.update(
                "INSERT INTO sm_control.instance "
                        + "(organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:org, :unit, :environment, :solution, :instance)",
                new MapSqlParameterSource().addValue("org", org).addValue("unit", unit)
                        .addValue("environment", environment).addValue("solution", UUID.randomUUID())
                        .addValue("instance", UUID.randomUUID())));
    }

    @Test
    void mutationWithoutTheInstanceParentForeignKeyMakesTheUnknownParentWriteAccepted() {
        assertAcceptedWithDroppedConstraint("instance", "instance_solution_fk", () -> {
            UUID org = organization();
            UUID unit = unit(org);
            UUID environment = environment(org, unit);
            jdbc.update("INSERT INTO sm_control.instance "
                            + "(organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                            + "VALUES (:org, :unit, :environment, :solution, :instance)",
                    new MapSqlParameterSource().addValue("org", org).addValue("unit", unit)
                            .addValue("environment", environment).addValue("solution", UUID.randomUUID())
                            .addValue("instance", UUID.randomUUID()));
        });
    }

    @Test
    void canonicalizeRefusesTheInstallScope() {
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(new JdbcScopeStore(jdbc));
        assertThatThrownBy(() -> service.canonicalize(new SessionContext(UUID.randomUUID()), ScopeRequest.install()))
                .isInstanceOf(ScopeNotCanonicalizableException.class);
    }

    @Test
    void onlyOneInstallScopeCanExist() {
        scope("install", null, null, null, null, null);
        assertRefused("scope_one_install", () -> scope("install", null, null, null, null, null));
    }

    @Test
    void mutationWithoutTheInstallUniqueIndexMakesTheSecondInstallWriteAccepted() {
        assertAcceptedWithDroppedIndex("scope_one_install", () -> {
            scope("install", null, null, null, null, null);
            scope("install", null, null, null, null, null);
        });
    }

    @Test
    void concurrentCanonicalizationReturnsTheSameScopeId() throws Exception {
        UUID org = organization();
        UUID unit = unit(org);
        UUID environment = environment(org, unit);
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(new JdbcScopeStore(jdbc));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ScopeId>> scopes = List.of(
                    executor.submit(canonicalizeAfterBarrier(service, org, unit, environment, ready, go)),
                    executor.submit(canonicalizeAfterBarrier(service, org, unit, environment, ready, go)));
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent canonicalization workers did not reach the start barrier");
            }
            go.countDown();
            assertThat(getScopeWithin(scopes.get(0))).isEqualTo(getScopeWithin(scopes.get(1)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aDuplicateCanonicalizationLeavesBothSpringTransactionsUsableForFollowingWrites() throws Exception {
        UUID org = organization();
        UUID unit = unit(org);
        UUID environment = environment(org, unit);
        CyclicBarrier afterMissingLookup = new CyclicBarrier(2);
        JdbcScopeStore store = new JdbcScopeStore(jdbc, () -> await(afterMissingLookup));
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(store);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<CanonicalizationResult>> results = List.of(
                    executor.submit(canonicalizeAndWrite(transaction, service, org, unit, environment)),
                    executor.submit(canonicalizeAndWrite(transaction, service, org, unit, environment)));
            CanonicalizationResult first = getWithin(results.get(0));
            CanonicalizationResult second = getWithin(results.get(1));

            assertThat(first.scopeId()).isEqualTo(second.scopeId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.organization_unit "
                            + "WHERE organization_id = :org AND organization_unit_id IN (:first, :second)",
                    new MapSqlParameterSource().addValue("org", org).addValue("first", first.followingUnitId())
                            .addValue("second", second.followingUnitId()), Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .as("concurrent canonicalization workers must stop after a failure")
                    .isTrue();
        }
    }

    private static Callable<ScopeId> canonicalizeAfterBarrier(ScopeCanonicalizationService service, UUID org,
            UUID unit, UUID environment, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent canonicalization workers were not released from the start barrier");
            }
            return service.canonicalize(new SessionContext(org), ScopeRequest.environment(unit, environment));
        };
    }

    private Callable<CanonicalizationResult> canonicalizeAndWrite(TransactionTemplate transaction,
            ScopeCanonicalizationService service, UUID org, UUID unit, UUID environment) {
        return () -> transaction.execute(status -> {
            ScopeId scopeId = service.canonicalize(new SessionContext(org), ScopeRequest.environment(unit, environment));
            UUID followingUnitId = UUID.randomUUID();
            jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) "
                            + "VALUES (:org, :unit)",
                    new MapSqlParameterSource().addValue("org", org).addValue("unit", followingUnitId));
            return new CanonicalizationResult(scopeId, followingUnitId);
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("timed out waiting for both canonicalization calls to pass the lookup", e);
        }
    }

    private static CanonicalizationResult getWithin(Future<CanonicalizationResult> result) throws Exception {
        try {
            return result.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("canonicalization transaction did not finish within 15 seconds", e);
        }
    }

    private static ScopeId getScopeWithin(Future<ScopeId> result) throws Exception {
        try {
            return result.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("canonicalization worker did not finish within 15 seconds", e);
        }
    }

    private UUID organization() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", id));
        return id;
    }

    private UUID unit(UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) "
                        + "VALUES (:org, :id)",
                new MapSqlParameterSource().addValue("org", organization).addValue("id", id));
        return id;
    }

    private UUID environment(UUID organization, UUID unit) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id) "
                        + "VALUES (:org, :unit, :id)",
                new MapSqlParameterSource().addValue("org", organization).addValue("unit", unit).addValue("id", id));
        return id;
    }

    private void scope(String kind, UUID org, UUID unit, UUID environment, UUID solution, UUID instance) {
        jdbc.update("INSERT INTO sm_control.scope "
                        + "(scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:id, :kind, :org, :unit, :environment, :solution, :instance)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("kind", kind)
                        .addValue("org", org).addValue("unit", unit).addValue("environment", environment)
                        .addValue("solution", solution).addValue("instance", instance));
    }

    private static void assertRefused(String constraint, ThrowingSql action) {
        assertThatThrownBy(action::run).satisfies(error -> {
            SQLException sql = sqlException(error);
            assertThat(sql.getSQLState()).isIn("23503", "23505", "23514");
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    private void assertAcceptedWithDroppedConstraint(String constraint, ThrowingSql action) {
        assertAcceptedWithDroppedConstraint("scope", constraint, action);
    }

    private void assertAcceptedWithDroppedConstraint(String table, String constraint, ThrowingSql action) {
        mutateSchema("ALTER TABLE sm_control." + table + " DROP CONSTRAINT " + constraint, constraint, action);
    }

    private void assertAcceptedWithDroppedIndex(String index, ThrowingSql action) {
        mutateSchema("DROP INDEX sm_control." + index, index, action);
    }

    private void mutateSchema(String mutation, String refusal, ThrowingSql action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute(mutation);
            assertThatThrownBy(() -> assertRefused(refusal, action))
                    .as("the corresponding refusal test must fail after: " + mutation)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
            status.setRollbackOnly();
        });
    }

    private static SQLException sqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                return sql;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    @FunctionalInterface
    private interface ThrowingSql {
        void run();
    }

    private record CanonicalizationResult(ScopeId scopeId, UUID followingUnitId) {
    }
}
