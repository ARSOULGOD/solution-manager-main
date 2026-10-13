package com.solace.sm.dp.apply.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code sm_data} access graph as one bean set (design §3.1) — serving every query with the
 * deliberately weak {@code sm_dp_runtime} credential, and holding no other.
 *
 * <p>Not auto-configured, and not {@code spring.datasource.*}: the future application class
 * {@code @Import}s this deliberately, the same way {@code MeshBridgeConfiguration} is imported. Boot's
 * {@code DataSourceAutoConfiguration} would give the application ONE datasource, and the whole
 * privilege topology of §3.1 — obligations (k), (l), (o), (s), (t) — rests on the runtime connection
 * being a role that cannot migrate and cannot {@code SET ROLE sm_dp_owner}.
 *
 * <p><b>Migration is not here any more.</b> The external review's finding 1: while Phase 1 ran as a
 * bean in this class, the application process held the schema-owner credential for its whole life.
 * Phase 1 is now {@link SmDataMigrationMain}, a separate one-shot process the install runbook runs
 * before this one starts. What replaces the ordering edge is {@link #smDataSchemaReadiness} — a check
 * the runtime role CAN make, refusing to serve a schema the migration step has not reached.
 *
 * <p>{@code I5} note: every statement issued through {@link #smDataJdbc} binds its parameters —
 * {@code NamedParameterJdbcTemplate} is the only template exposed, and no {@code JdbcTemplate} bean
 * is published, so string-interpolated SQL has no convenient path here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmDataProperties.class)
public class ApplyPersistenceConfiguration {

    /**
     * The five tables design §3.1 creates, and the whole of what the runtime role may look for.
     *
     * <p>Deliberately a literal: nothing about this query is caller-supplied, so there is no bind
     * parameter to omit and no {@code I5} surface to open.
     */
    static final String READINESS_QUERY =
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_data'"
                    + " AND table_name IN ('apply_request','apply_scope','placement_watermark',"
                    + "'apply_step_intent','apply_attempt')";

    /**
     * What V021 adds to {@code apply_step_intent} (plan 008 S2, {@code BE-D44}): the placement each intent addresses.
     * Its absence means a V020 schema, on which the engine's INSERT would fail at the first intent, so the new binary
     * must not go Ready on it. The runtime role holds SELECT on the table, which is what makes the column visible to
     * {@code information_schema.columns}.
     */
    static final String PLACEMENT_COLUMN_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_data'"
                    + " AND table_name = 'apply_step_intent' AND column_name = 'placement_id'";

    /**
     * What V024 adds to {@code apply_request} (plan 008 S4, {@code BE-D48}): the VALIDATED foreign key that is the
     * database's referential guard on the placement a request names. The code does not depend on it (the service
     * checks a live placement first), so its absence is not a functional failure; it is the second enforcer
     * {@code BE-D48} and the runbook describe as in force, silently missing, so the binary refuses to go ready without
     * it (W3 review W3A-3). {@code pg_constraint} is readable by every role. {@code convalidated} is part of the
     * test because an unvalidated guard is not the guard.
     */
    static final String PLACEMENT_GUARD_QUERY =
            "SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sm_data'"
                    + " AND c.relname = 'apply_request' AND k.conname = 'apply_request_placement_registered'"
                    + " AND k.convalidated";

    /** How many of them there must be. */
    static final int EXPECTED_TABLES = 5;

    /**
     * Refuses to start against a schema {@link SmDataMigrationMain} has not migrated.
     *
     * <p><b>Why table presence and not a version.</b> The obvious check — read
     * {@code flyway_schema_history} and compare the applied version against the one this build ships —
     * is not available to this role, and that is the intended posture, not an oversight: design §3.1's
     * {@code ALTER DEFAULT PRIVILEGES} only reaches tables created AFTER it, Flyway creates its history
     * table before the first migration runs, and so {@code sm_dp_runtime} gets
     * {@code permission denied for table flyway_schema_history} (measured, and stated in §3.1). Reading
     * it would mean granting the runtime role a view of the migrator's bookkeeping, which is a wider
     * grant than the check is worth. So the check is the one the runtime role can honestly make: are
     * the five tables it is granted on present.
     *
     * <p>That is presence, not version pinning: a future migration that only ALTERs an existing table
     * would pass this check. While there are six migrations creating five tables that is the whole
     * distance between "unmigrated" and "migrated"; when migrations grow, pinning the version belongs
     * to the plan that grows them — most likely by having the migration step write a small
     * runtime-readable marker rather than by widening the grant.
     */
    @Bean
    InitializingBean smDataSchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            int placementColumn;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(PLACEMENT_COLUMN_QUERY)) {
                rs.next();
                placementColumn = rs.getInt(1);
            }
            if (found == EXPECTED_TABLES && placementColumn != 1) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects "
                        + "(apply_step_intent.placement_id is absent, V021 has not run): run the migration step "
                        + "— SmDataMigrationMain with the sm_dp_migrator credential — before starting the "
                        + "application (docs/runbooks/operations.md, \"Upgrade across the placement-aware apply "
                        + "journal\")");
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build "
                        + "expects (found " + found + " of " + EXPECTED_TABLES + " tables): run the "
                        + "migration step — SmDataMigrationMain with the sm_dp_migrator credential — "
                        + "before starting the application");
            }
            int placementGuard;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(PLACEMENT_GUARD_QUERY)) {
                rs.next();
                placementGuard = rs.getInt(1);
            }
            if (placementGuard != 1) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects "
                        + "(the validated constraint apply_request_placement_registered is absent, V024 has not "
                        + "run): run the migration step — SmDataMigrationMain with the sm_dp_migrator credential "
                        + "— before starting the application (docs/runbooks/operations.md, \"Upgrade across the "
                        + "apply-request placement guard\")");
            }
        };
    }

    /** The application pool: {@code sm_dp_runtime}, the role that owns nothing. */
    @Bean(destroyMethod = "close")
    HikariDataSource smDataDataSource(SmDataProperties props) {
        SmDataProperties.Credentials c = props.runtime();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setPoolName("sm-data");
        // Explicit, not HikariCP's default: the metrics budget (sm.dp.metrics.db-budget) is a share of THIS size.
        ds.setMaximumPoolSize(props.pool().maximumSize());
        // The schema is set on the connection rather than written into every statement: unqualified
        // names in repository SQL then resolve inside sm_data and nowhere else.
        ds.setSchema(SmDataMigrator.SCHEMA);
        return ds;
    }

    /**
     * The transaction manager the admission transaction of §3.3 runs under — and, not incidentally,
     * the one that makes {@code SpringTransactionBoundary.inTransaction()} true, so an after-commit
     * publish is ordered against a real database commit rather than an empty synchronized scope.
     *
     * <p><b>Fix round 1 finding 2.</b> {@code @DependsOn} the readiness check for the same reason
     * {@link #smDataJdbc} carries it: Task 3 injects this manager (and {@link #smDataTransactions}
     * below) into repositories and engine services, and a transaction manager that can open a
     * transaction before readiness has run could touch an unmigrated schema. Only {@code smDataJdbc}
     * carried the edge before this fix; a caller reaching {@code sm_data} through
     * {@code PlatformTransactionManager}/{@code TransactionTemplate} instead of
     * {@code NamedParameterJdbcTemplate} was not stopped by anything.
     */
    @Bean
    @DependsOn("smDataSchemaReadiness")
    PlatformTransactionManager smDataTransactionManager(DataSource smDataDataSource) {
        return new DataSourceTransactionManager(smDataDataSource);
    }

    /**
     * Bind-parameter-only template ({@code I5}).
     *
     * <p>{@code @DependsOn} the readiness check, by bean name, so no repository can issue a query
     * before it has run. Bean-name coupling is the cost; the alternative — a repository meeting a
     * schema with no tables and reporting it as a broken query — is what it buys.
     */
    @Bean
    @DependsOn("smDataSchemaReadiness")
    NamedParameterJdbcTemplate smDataJdbc(DataSource smDataDataSource) {
        return new NamedParameterJdbcTemplate(smDataDataSource);
    }

    /**
     * Programmatic transactions for the admission path, which needs an explicit boundary.
     *
     * <p>{@code @DependsOn} the readiness check — fix round 1 finding 2, same rationale as
     * {@link #smDataTransactionManager}.
     */
    @Bean
    @DependsOn("smDataSchemaReadiness")
    TransactionTemplate smDataTransactions(PlatformTransactionManager smDataTransactionManager) {
        return new TransactionTemplate(smDataTransactionManager);
    }

    @Bean
    ApplyScopeRepository applyScopeRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new ApplyScopeRepository(smDataJdbc);
    }

    @Bean
    ApplyRequestRepository applyRequestRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new ApplyRequestRepository(smDataJdbc);
    }

    @Bean
    StepIntentRepository stepIntentRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new StepIntentRepository(smDataJdbc);
    }

    @Bean
    AttemptRepository attemptRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new AttemptRepository(smDataJdbc);
    }

    /**
     * Task 4a's claim. Depends only on persistence-package collaborators (no {@code ApplyExecutor}),
     * so — unlike the worker built on top of it — this bean exists unconditionally, in every
     * context, production included.
     */
    @Bean
    ApplyClaimRepository applyClaimRepository(TransactionTemplate smDataTransactions,
            NamedParameterJdbcTemplate smDataJdbc, ApplyRequestRepository applyRequestRepository) {
        return new ApplyClaimRepository(smDataTransactions, smDataJdbc, applyRequestRepository);
    }

    /** Task 4b's enumerator — see its own javadoc for the {@code I5} exception it states. */
    @Bean
    TenantEnumerationRepository tenantEnumerationRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new TenantEnumerationRepository(smDataJdbc);
    }

    /**
     * Task 4a's cross-process singleton guard. Built HERE, over the raw {@code DataSource}, so
     * that no class outside this package ever holds a {@code javax.sql.DataSource} reference —
     * {@code jdbcIsConfinedToPersistencePackages} (arch-tests) confines that type to exactly this
     * package and {@code cp.persistence}. {@code com.solace.sm.dp.apply.worker.ApplyWorker} takes
     * the finished guard as a plain collaborator instead.
     */
    @Bean
    WorkerSingletonGuard workerSingletonGuard(DataSource smDataDataSource) {
        return new WorkerSingletonGuard(smDataDataSource);
    }
}
