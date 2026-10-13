package com.solace.sm.dp.apply.persistence;

import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Phase 1 as its OWN process — the deploy-time migration step of design §3.1, and the whole of the
 * external review's finding 1.
 *
 * <p><b>Why a {@code main} and not a bean.</b> {@code sm_dp_migrator} is a member of
 * {@code sm_dp_owner}: whoever holds its password can rewrite {@code sm_data}. While Phase 1 ran as a
 * Spring {@code InitializingBean}, that password was bound into the application's own configuration
 * properties for the life of the JVM, so the privilege boundary §3.1 draws between the two roles was a
 * convention about which bean read which field rather than a property of the process. Splitting the
 * step out makes it structural: the application is never given the credential, so no component of it —
 * careless, compromised or merely curious — can act as schema owner.
 *
 * <p><b>How the runbook runs it.</b> On Kubernetes this is an init container or a {@code Job} with the
 * migrator secret mounted, ordered before the Deployment; on-prem it is one command in the install and
 * upgrade procedures, run before the service is started. Either way the application process that
 * follows receives only {@code sm_dp_runtime}, and
 * {@link ApplyPersistenceConfiguration#smDataSchemaReadiness} is what stops it serving a schema this
 * step has not yet touched.
 *
 * <p><b>What this step prints, and what it deliberately does not.</b> Flyway logs the connection URL
 * itself. Measured on the success path of {@code ApplyPersistenceConfigurationIT} through a logback
 * {@code ListAppender} on the root logger — 193 events for one migrate, of which FOUR name the URL:
 *
 * <pre>
 * INFO  org.flywaydb.core.FlywayExecutor —
 *     Database: jdbc:postgresql://localhost:32958/sm_ctx_migrated (PostgreSQL 14.24)
 * DEBUG org.springframework.jdbc.datasource.SimpleDriverDataSource —
 *     Creating new JDBC Driver Connection to [jdbc:postgresql://localhost:32958/sm_ctx_migrated]
 * DEBUG org.flywaydb.core.internal.database.DatabaseTypeRegister —
 *     Multiple databases found that handle url 'jdbc:postgresql://…': CockroachDB,PostgreSQL
 * </pre>
 *
 * <p>The first of those is at INFO — the level a production deployment actually runs at — and it is why
 * {@link JdbcUrls#requireLocation} exists and why this class refuses any URL that is not a bare
 * location with allowlisted parameters, instead of passing it on. Anything inside the URL — a
 * {@code ?password=}, an {@code alice:hunter2@host}, or a value smuggled into a property this code
 * never heard of — would be printed on every successful migration, by a library this code does not
 * control and deliberately does not silence: suppressing the line would hide the exposure rather than
 * remove it.
 *
 * <p>The FAILURE path is stricter still, and it is the half this class DOES control. Flyway's own
 * connection-failure message embeds the URL and the login role, so it is never printed: a failure is
 * reported as one line built only from stable classifiers — the exception's simple class name and, when
 * a {@link SQLException} is anywhere in the cause chain, its SQLSTATE. Measured against an unreachable
 * host, that line is {@code the sm_data migration failed: FlywaySqlException (SQLSTATE 08001) — …}.
 *
 * <p>No Spring here on purpose: a context would need configuration, and configuration is the thing
 * being kept away from the application. Three environment variables, one Flyway run, an exit code.
 */
public final class SmDataMigrationMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_DATA_MIGRATOR_URL";

    /** The migrator role, normally {@code sm_dp_migrator}. */
    public static final String USERNAME_VARIABLE = "SM_DATA_MIGRATOR_USERNAME";

    /** That role's secret. Read, used, and never echoed — not into stdout, not into a failure. */
    public static final String PASSWORD_VARIABLE = "SM_DATA_MIGRATOR_PASSWORD";

    /** A required environment variable was absent, blank, or carried something it must not. */
    static final int EXIT_MISSING_VARIABLE = 2;

    /** Flyway refused the migration. */
    static final int EXIT_FAILED = 1;

    /** The failure line's fixed tail — one sentence saying why the diagnosis is deliberately thin. */
    static final String NO_MESSAGE_TEXT = " — no message text is printed because Flyway's includes "
            + "the connection URL; diagnose with psql using the same credential";

    private SmDataMigrationMain() {
    }

    /**
     * The entry point the runbook invokes.
     *
     * @param args ignored — configuration arrives through the environment rather than the command
     *             line, so the password is not in the invoking shell's history. That is a convenience,
     *             not the protection: environment variables are process state, and whether another
     *             process on the host can read them depends on the host and the runtime. The boundary
     *             that matters is that this is a SEPARATE process from the application, which never
     *             receives the migrator credential at all; the install runbook owns how this one is
     *             isolated while it runs.
     */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

    /**
     * {@link #main} minus {@link System#exit}, and with both streams injected so a test can read every
     * byte this step emits.
     *
     * <p>Failures are reported as ONE line naming what is wrong, assembled from classifiers only. A
     * missing-variable message names the VARIABLE; a URL outside {@link JdbcUrls}' location grammar
     * names the part of the grammar it missed and the two variables a credential belongs in; a
     * migration failure names the exception's simple class name and its
     * SQLSTATE. No URL, no username, no password and no library-supplied message text reaches either
     * stream ({@code I6}) — the exception object is not printed at all, because Flyway's
     * {@code toString} carries the URL and the role.
     *
     * @param env the environment to read — {@code System.getenv()} in production
     * @param out where the success line goes
     * @param err where the single failure line goes
     * @return 0 on success, {@value #EXIT_FAILED} when the migration failed,
     *         {@value #EXIT_MISSING_VARIABLE} when a variable is missing or malformed
     */
    static int exitCode(Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> read = new LinkedHashMap<>();
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                err.println(name + " is not set — the sm_data migration step reads its "
                        + "credential from the environment, and will not guess one");
                return EXIT_MISSING_VARIABLE;
            }
            read.put(name, value);
        }
        try {
            JdbcUrls.requireLocation(read.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            // Its message is JdbcUrls' own words — the rule and the allowlist, never the URL, a value or
            // an unrecognised parameter name — so it is safe to print, and it tells the operator which
            // part of the grammar was missed.
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        try {
            MigrationReport report = runAll(read.get(URL_VARIABLE), read.get(USERNAME_VARIABLE),
                    read.get(PASSWORD_VARIABLE));
            out.println("sm_data: migrated " + report.smData().migrationsExecuted + " (versions "
                    + versions(report.smData()) + ")");
            out.println("sm_metrics: migrated " + report.smMetrics().migrationsExecuted + " (versions "
                    + versions(report.smMetrics()) + ")");
            return 0;
        } catch (NoDriverRegistered e) {
            // Its message is this class's own and names no URL, so it is safe to print verbatim —
            // and it is the one failure a classifier alone would leave undiagnosable.
            err.println("the " + SmDataMigrator.SCHEMA + " migration failed: " + e.getMessage());
            return EXIT_FAILED;
        } catch (MigrationStageException e) {
            // WHICH history failed (plan 011 P11-2.1); the cause is classified, never printed.
            err.println("the " + e.schema() + " migration failed: " + classify(e.getCause()));
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("the " + SmDataMigrator.SCHEMA + " migration failed: " + classify(e));
            return EXIT_FAILED;
        }
    }

    /**
     * Applies Phase 1 with the migrator credential — the one-shot step every caller shares, including
     * the integration harness, so no test can prove a path production does not take.
     *
     * @param url      the SM database's JDBC URL, carrying no credential
     * @param username the migrator role
     * @param password its secret
     * @return Flyway's own report: which versions were applied, in order
     */
    public static MigrateResult run(String url, String username, String password) {
        return runAll(url, username, password).smData();
    }

    /**
     * Applies Phase 1 of BOTH schemas, {@code sm_data} first and then {@code sm_metrics} (plan 011 P11-2.1; M-Q19),
     * each with its own Flyway history. {@link #run} keeps its signature and answers {@code sm_data}'s result, so
     * every existing caller migrates both schemas without an edit.
     *
     * <p>Two separate runs, not one: a failure in {@code sm_metrics} leaves {@code sm_data}'s migrations committed
     * (MF1 (iii)), and a re-run applies only what is missing. A failure is raised as {@link MigrationStageException}
     * naming the schema, with the original exception as its cause.
     *
     * @return both reports
     * @throws MigrationStageException a migration of the named schema failed
     */
    public static MigrationReport runAll(String url, String username, String password) {
        javax.sql.DataSource dataSource = dataSource(url, username, password);
        MigrateResult smData = stage(SmDataMigrator.SCHEMA, () -> SmDataMigrator.migrate(dataSource));
        MigrateResult smMetrics = stage(SmMetricsMigrator.SCHEMA, () -> SmMetricsMigrator.migrate(dataSource));
        return new MigrationReport(smData, smMetrics);
    }

    private static MigrateResult stage(String schema, java.util.function.Supplier<MigrateResult> migration) {
        try {
            return migration.get();
        } catch (RuntimeException e) {
            throw new MigrationStageException(schema, e);
        }
    }

    /** A migration of one schema failed; the schema's name is the only text of its own (the cause may carry the URL). */
    static final class MigrationStageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String schema;

        MigrationStageException(String schema, RuntimeException cause) {
            super("the " + schema + " migration failed", cause);
            this.schema = schema;
        }

        String schema() {
            return schema;
        }
    }

    /**
     * A one-connection-at-a-time data source for the migrator credential — and the one choke point
     * every path to Flyway or the driver passes through, so it is where {@link JdbcUrls} is enforced.
     *
     * <p><b>Fix round 1 finding 1.</b> {@link #exitCode} already calls {@link JdbcUrls#requireLocation}
     * on the environment-variable path, but that call guarded only the reader of
     * {@link #URL_VARIABLE} — {@link #run} and this method were reachable directly, with no check at
     * all, and the integration harness's own bootstrap did exactly that (through
     * {@code SmDataPostgres.migrate()}), handing Flyway a URL {@code JdbcUrls} would have refused.
     * Since {@link SmDataMigrator#migrate} and {@link org.springframework.jdbc.datasource.SimpleDriverDataSource}
     * both log the URL (Flyway at INFO on every successful migrate, Spring at DEBUG on every connect —
     * see the class javadoc), a public entry point that reaches either without the grammar check is
     * exactly the {@code I6} leak this class exists to prevent. The check is repeated here — not moved
     * here instead of {@link #exitCode}'s — because the env path must still refuse with
     * {@value #EXIT_MISSING_VARIABLE} <b>before attempting a connection</b>, which is already what
     * {@link SmDataMigrationMainTest} asserts; {@link JdbcUrls#requireLocation} is pure and idempotent,
     * so validating twice costs nothing and closes every other caller, present or future, at the one
     * place both of them must pass through.
     *
     * <p>A {@link SimpleDriverDataSource} on purpose: it opens one connection per
     * {@code getConnection()} with no pool to shut down, and it is used exactly once, for the length of
     * {@link SmDataMigrator#migrate}. It lives HERE rather than on the Spring configuration because
     * that class no longer has a migrator credential to build one from.
     *
     * <p>The driver comes from {@link DriverManager#getDriver(String)} rather than
     * {@code new org.postgresql.Driver()} so that the wiring compiles against {@code java.sql} only —
     * it registers itself through {@code META-INF/services/java.sql.Driver}. Naming the concrete
     * driver here would tie the step to the JDBC vendor for no gain.
     *
     * <p><b>Ordering, corrected (fix round 2).</b> The grammar check runs FIRST, unconditionally,
     * before {@code url} touches {@link DriverManager#getDriver(String)} or anything else. An earlier
     * version of this method resolved the driver first on the theory that "neither order matters for
     * the {@code I6} guarantee, since a {@code DataSource} is returned only if both succeed" — that
     * theory is false, and was measured false against the compiled {@code postgresql-42.7.11.jar}:
     * pgjdbc's own {@code Driver.parseURL} (called from {@code acceptsURL}, which
     * {@code DriverManager.getDriver} calls on every registered driver before this method ever sees a
     * result) logs a {@code WARNING} through {@code java.util.logging} — independent of this class's
     * own streams or its {@code Logback}-routed loggers — and, for three distinct grammar violations,
     * that warning line CONTAINS the offending URL, credential parameter included:
     * <pre>
     * WARNING org.postgresql.Driver parseURL — JDBC URL must contain a / at the end of the host or
     *     port: jdbc:postgresql://db.example?password=hunter2
     * WARNING org.postgresql.Driver parseURL — JDBC URL contains too many / characters:
     *     jdbc:postgresql://db.example:5432/sm/extra?password=hunter2
     * WARNING org.postgresql.util.PGPropertyUtil convertPgPortToInt — JDBC URL invalid port number:
     *     hunter2@db.example
     * </pre>
     * The third is exactly the {@code alice:hunter2@host} userinfo form this class's own javadoc says
     * is refused without echoing — it only looked clean under the driver-first ordering because every
     * existing test drove {@link #exitCode}, which validates before ever calling this method; a direct
     * caller (the harness, or any future one) got no such protection. {@code java.util.logging}'s
     * default {@code ConsoleHandler} prints {@code WARNING} to {@code System.err} with no configuration
     * at all. <b>Measured (fix round 3), not merely reasoned:</b> {@code jul-to-slf4j} is on this
     * module's classpath ({@code jul-to-slf4j:2.0.18}, transitively via
     * {@code spring-boot-starter-logging}), and installing {@code SLF4JBridgeHandler} on the root
     * {@code java.util.logging} logger — exactly what Boot's {@code LoggingApplicationListener} does
     * for a real application process — and then triggering this same userinfo case produced ONE
     * Logback event, captured through a {@code ListAppender} on the SLF4J root logger:
     * <pre>
     * WARN org.postgresql.util.PGPropertyUtil -- JDBC URL invalid port number: hunter2@db.example
     * </pre>
     * confirming the bridged record lands at SLF4J {@code WARN} and still carries the URL — above the
     * DEBUG level this class already treats as a leak surface. Grammar-first closes this:
     * {@link JdbcUrls#requireLocation} throws before {@code url} reaches {@code DriverManager} or any
     * driver's {@code parseURL}, so none of the three cases above can occur any more — each is now
     * refused silently, by grammar position, before pgjdbc is ever consulted.
     *
     * <p>{@link #NoDriverRegistered} stays reachable and meaningful — see
     * {@code SmDataMigrationMainTest.theDriverResolutionFailureIsStillReachableAndStillNamesNoUrl},
     * which now drives it with a grammar-CONFORMING URL and the postgres driver deliberately
     * deregistered for the length of the call (re-registered in a {@code finally}), which reproduces
     * the case this exception documents — a distribution shipped without the driver — more faithfully
     * than the old {@code jdbc:nope://} probe did, and does not depend on ordering here at all.
     */
    static SimpleDriverDataSource dataSource(String url, String username, String password) {
        JdbcUrls.requireLocation(url);
        try {
            return new SimpleDriverDataSource(DriverManager.getDriver(url), url, username, password);
        } catch (SQLException e) {
            throw new NoDriverRegistered(e);
        }
    }

    /**
     * No registered driver claims the URL's scheme.
     *
     * <p>Its own type, and not a bare {@link IllegalStateException}, so {@link #exitCode} can tell the
     * one failure whose message is safe to print from every failure whose message is not. The message
     * says "the configured URL's scheme" rather than quoting the URL — a driver-resolution failure is
     * the FIRST thing a misconfigured deployment hits, and it must not be the place the URL escapes.
     */
    static final class NoDriverRegistered extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NoDriverRegistered(SQLException cause) {
            super("no JDBC driver is registered for the configured URL's scheme", cause);
        }
    }

    /**
     * A failure as classifiers only: {@code FlywaySqlException (SQLSTATE 08001) — …}.
     *
     * <p>The simple class name is stable across versions and carries no data. The SQLSTATE is the
     * five-character standard code — {@code 08001} unable to connect, {@code 28P01} bad password,
     * {@code 42501} insufficient privilege — which is what an operator actually needs to know, and is
     * likewise data-free.
     */
    static String classify(Throwable failure) {
        String sqlState = sqlStateOf(failure);
        return failure.getClass().getSimpleName()
                + (sqlState == null ? "" : " (SQLSTATE " + sqlState + ")")
                + NO_MESSAGE_TEXT;
    }

    /** The first SQLSTATE in the cause chain, or null. Bounded, because a chain may be cyclic. */
    private static String sqlStateOf(Throwable failure) {
        Throwable c = failure;
        for (int depth = 0; c != null && depth < 32; c = c.getCause(), depth++) {
            if (c instanceof SQLException sql && sql.getSQLState() != null
                    && !sql.getSQLState().isBlank()) {
                return sql.getSQLState();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    private static String versions(MigrateResult result) {
        if (result.migrations == null || result.migrations.isEmpty()) {
            return "none — already at " + result.targetSchemaVersion;
        }
        return result.migrations.stream().map(m -> m.version).collect(Collectors.joining(", "));
    }
}
