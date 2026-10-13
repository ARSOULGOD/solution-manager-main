package com.solace.sm.dp.apply.persistence;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ONE PostgreSQL 14 container per JVM, bootstrapped once, shared by every {@code sm_data} IT.
 *
 * <p>PostgreSQL 14 because {@code SM-D26} pins it as the floor and every obligation in design §3.3
 * is a statement about <i>that</i> version's behaviour — NULL index keys distinct, no
 * {@code NULLS NOT DISTINCT}, schema {@code public} still owned by the bootstrap superuser with
 * {@code CREATE} open to {@code PUBLIC}. Testing on 16 would test a database the product does not
 * claim.
 *
 * <p><b>One container, not one per class.</b> Roles are CLUSTER-scoped, so Phase 0 is a
 * once-per-cluster act — the design's own harness note says a literal re-run aborts at
 * {@code CREATE ROLE}. Sharing the container is therefore not a speed optimisation, it is what makes
 * the bootstrap testable at all. Tests isolate from each other with {@link #freshTenant()} instead.
 *
 * <p>Nothing here is a Spring context: the ITs want the raw privilege topology, and a context would
 * put an extra layer between the assertion and the grant it is asserting.
 */
final class SmDataPostgres {

    /** The pinned floor. Not "postgres:latest" — see the class javadoc. */
    static final DockerImageName IMAGE = DockerImageName.parse("postgres:14");

    /** Test-only login secrets. Phase 0 deliberately carries none — they are runbook material. */
    static final String MIGRATOR_PASSWORD = "migrator-test-pw";
    static final String RUNTIME_PASSWORD = "runtime-test-pw";

    private static PostgreSQLContainer<?> container;
    private static boolean bootstrapped;
    private static MigrateResult migrateResult;
    private static List<StatementOutcome> phase0Outcomes;
    private static DataSource superuserDs;
    private static DataSource migratorDs;
    private static DataSource runtimeDs;

    private SmDataPostgres() {
    }

    static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(IMAGE);
            c.start();
            container = c;
        }
        return container;
    }

    /**
     * Phase 0 + test passwords + Phase 1, exactly once per JVM.
     *
     * @return Flyway's report from the single migrate run every IT shares
     */
    static synchronized MigrateResult bootstrap() {
        if (!bootstrapped) {
            phase0Outcomes = runPhase0();
            assignTestPasswords();
            migrateResult = migrate();
            bootstrapped = true;
        }
        return migrateResult;
    }

    /**
     * What Phase 0 said when it ran — kept because it runs once per JVM and the bootstrap IT must be
     * able to assert on it whatever order the ITs execute in.
     */
    static synchronized List<StatementOutcome> phase0Outcomes() {
        bootstrap();
        return phase0Outcomes;
    }

    /**
     * ONE DataSource instance per role, cached — and the caching is load-bearing, not tidiness.
     *
     * <p>Measured the hard way: Spring binds a transactional connection to the
     * {@code DataSource} <b>instance</b> as its {@code TransactionSynchronizationManager} resource
     * key. A {@code JdbcTemplate} over one instance and a {@code DataSourceTransactionManager} over
     * a second instance of the same URL therefore do NOT share a transaction — the template
     * silently takes its own auto-commit connection, every write commits immediately, and a
     * rollback test passes for the wrong reason. Handing out fresh instances made
     * {@code SpringTransactionBoundaryDataSourceIT}'s rollback case fail with the row still present;
     * that failure is what produced this comment. Production is safe by construction (one
     * {@code smDataDataSource} bean), so this is a harness hazard — and exactly the sort a harness
     * that hands out new objects from a getter creates for itself.
     */
    static synchronized DataSource superuser() {
        if (superuserDs == null) {
            PostgreSQLContainer<?> c = container();
            superuserDs = dataSource(jdbcUrl(), c.getUsername(), c.getPassword());
        }
        return superuserDs;
    }

    static synchronized DataSource migratorDataSource() {
        if (migratorDs == null) {
            migratorDs = dataSource(jdbcUrl(), "sm_dp_migrator", MIGRATOR_PASSWORD);
        }
        return migratorDs;
    }

    static synchronized DataSource runtimeDataSource() {
        if (runtimeDs == null) {
            runtimeDs = dataSource(jdbcUrl(), "sm_dp_runtime", RUNTIME_PASSWORD);
        }
        return runtimeDs;
    }

    static NamedParameterJdbcTemplate superuserJdbc() {
        return new NamedParameterJdbcTemplate(superuser());
    }

    static NamedParameterJdbcTemplate migratorJdbc() {
        return new NamedParameterJdbcTemplate(migratorDataSource());
    }

    static NamedParameterJdbcTemplate runtimeJdbc() {
        return new NamedParameterJdbcTemplate(runtimeDataSource());
    }

    /**
     * Executes {@code db/bootstrap/sm_data_phase0.sql} verbatim as the superuser, statement by
     * statement with {@link SqlStatements} — the same splitter {@link SketchMirrorTest} compares
     * with, so the script this runs is provably the script the design carries.
     *
     * <p>Per-statement, not as one batch: the design's own harness rule says a batched script
     * mis-attributes errors, and the {@code REVOKE}'s {@link java.sql.SQLWarning} has to be
     * attributable to the {@code REVOKE}.
     */
    static List<StatementOutcome> runPhase0() {
        return SqlScript.executeEach(superuser(), phase0Sql());
    }

    static String phase0Sql() {
        return DesignSketch.read(DesignSketch.repoRoot()
                .resolve("backend/dp-core/src/main/resources/" + SmDataMigrator.PHASE0_RESOURCE));
    }

    /**
     * Gives the two login roles passwords.
     *
     * <p>Separate from Phase 0 on purpose, and the separation is the design's, not the harness's:
     * §3.1 says LOGIN credentials are install-runbook material (pg_hba, managed secrets) and the
     * sketch deliberately carries none — but TCP connections need them, so the harness supplies its
     * own throwaway pair here rather than editing the script under test. DDL cannot bind parameters;
     * both values are compile-time constants of this class, never input.
     */
    static void assignTestPasswords() {
        SqlScript.execute(superuser(),
                "ALTER ROLE sm_dp_migrator PASSWORD '" + MIGRATOR_PASSWORD + "'");
        SqlScript.execute(superuser(),
                "ALTER ROLE sm_dp_runtime PASSWORD '" + RUNTIME_PASSWORD + "'");
    }

    /**
     * Phase 1, through the one-shot entrypoint the install runbook runs.
     *
     * <p>Deliberately {@link SmDataMigrationMain#run} and not {@code SmDataMigrator.migrate} directly:
     * this harness IS the runbook's stand-in, so every IT's bootstrap exercises the same call the
     * deployment makes — including the URL it hands Flyway, which is why {@link #jdbcUrl()} builds a
     * bare location rather than {@code container().getJdbcUrl()} (fix round 1 finding 1: see
     * {@link #jdbcUrl(String)}'s javadoc).
     */
    static MigrateResult migrate() {
        return SmDataMigrationMain.run(jdbcUrl(), "sm_dp_migrator", MIGRATOR_PASSWORD);
    }

    /** A tenant no other test has used — the isolation unit, since the container is shared. */
    static UUID freshTenant() {
        return UUID.randomUUID();
    }

    static SimpleDriverDataSource dataSource(String url, String user, String password) {
        return SmDataMigrationMain.dataSource(url, user, password);
    }

    /** The container's own JDBC URL, as a bare location naming its default database. */
    static String jdbcUrl() {
        return jdbcUrl(container().getDatabaseName());
    }

    /**
     * A bare {@code jdbc:postgresql://host:port/database} location for another database in the same
     * cluster — never {@code container().getJdbcUrl()}.
     *
     * <p><b>Fix round 1 finding 1.</b> {@code PostgreSQLContainer.getJdbcUrl()} appends
     * {@code ?loggerLevel=OFF} (measured: {@code PostgreSQLContainer}'s constructor calls
     * {@code withUrlParam("loggerLevel", "OFF")}) — a parameter {@link JdbcUrls#ALLOWED} does not
     * carry, and does not gain, because widening the production grammar for a test convenience is the
     * wrong direction (the review's own ruling). Once {@link SmDataMigrationMain#dataSource} enforces
     * {@link JdbcUrls#requireLocation} as its choke point, every caller in this harness — not only
     * {@link #migrate()} — must hand it a URL the grammar admits, or the harness stops being able to
     * connect at all. This method is now the ONLY place in the harness that turns the container's
     * coordinates into a URL, so the harness's claim to be "the runbook's stand-in" (see
     * {@link #migrate()}) is true of the URL SHAPE as well as the call path.
     *
     * @param database the database name — the container's own for every normal connection, or a
     *                  sibling database's for the (s) negative controls and the no-Phase-0 IT
     */
    static String jdbcUrl(String database) {
        PostgreSQLContainer<?> c = container();
        return "jdbc:postgresql://" + c.getHost() + ":"
                + c.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    /** One executed statement and whatever the server said about it. */
    record StatementOutcome(String sql, List<String> warnings) {
    }
}
