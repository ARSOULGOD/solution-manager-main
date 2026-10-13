package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.CredentialHasher;
import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Phase 1 of the {@code sm_control} bootstrap as its OWN process — mirroring dp-core's
 * {@code SmDataMigrationMain}, and for the identical reason: {@code sm_cp_migrator} is a member of
 * {@code sm_cp_owner}, so whoever holds its password can rewrite {@code sm_control}. Splitting the
 * step out of the application means the application is never given that credential, so no component
 * of it can act as schema owner.
 *
 * <p><b>URL grammar.</b> Like the DP precedent ({@code SmDataMigrationMain}), the configured URL is
 * held to {@link JdbcUrls#requireLocation} — cp-core's own copy of dp-core's allowlist grammar
 * ({@code I7} bars importing dp-core's directly — this is an independent implementation of the same
 * rule, not a shared one. Checked here, on the environment-variable path, AND again in
 * {@link #dataSource} — the one choke point every caller (including the integration harness) passes
 * through on the way to a driver or Flyway, so a future direct caller of {@link #run}/{@link #dataSource}
 * is guarded too, exactly as dp-core's own two checks are. What else is carried over: neither stream
 * ever receives the raw exception message (which Flyway's own failure embeds the URL and role in), the
 * bootstrap credential hash is validated as a hash — never a plaintext — before Flyway ever sees it,
 * and a failure is reported as classifiers only (exception simple name plus SQLSTATE when present).
 *
 * <p><b>How the runbook runs it.</b> One command in the install and upgrade procedures, before the
 * application starts; on Kubernetes, an init container or Job ordered before the Deployment. The
 * application process that follows receives only {@code sm_cp_runtime}, and
 * {@link ControlPlanePersistenceConfiguration#smControlSchemaReadiness} is what stops it serving a
 * schema this step has not yet touched.
 */
public final class SmControlMigrationMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_CONTROL_MIGRATOR_URL";

    /** The migrator role, normally {@code sm_cp_migrator}. */
    public static final String USERNAME_VARIABLE = "SM_CONTROL_MIGRATOR_USERNAME";

    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_CONTROL_MIGRATOR_PASSWORD";

    /**
     * The Argon2id HASH of the installer-supplied single-use bootstrap secret — never the plaintext.
     * Validated with {@link CredentialHasher#parametersOf} before it reaches Flyway.
     */
    public static final String BOOTSTRAP_CREDENTIAL_HASH_VARIABLE = "SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH";

    /** A required environment variable was absent, blank, or malformed. */
    static final int EXIT_MISSING_VARIABLE = 2;

    /** Flyway refused the migration. */
    static final int EXIT_FAILED = 1;

    /** The failure line's fixed tail. */
    static final String NO_MESSAGE_TEXT = " — no message text is printed because Flyway's own failure "
            + "message may carry the connection URL; diagnose with psql using the same credential";

    private SmControlMigrationMain() {
    }

    /**
     * The entry point the runbook invokes.
     *
     * @param args ignored — configuration arrives through the environment, never the command line
     */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

    /**
     * {@link #main} minus {@link System#exit}, with both streams injected so a test can read every
     * byte this step emits.
     *
     * @return 0 on success, {@value #EXIT_FAILED} on a migration failure,
     *         {@value #EXIT_MISSING_VARIABLE} when a variable is missing or malformed
     */
    static int exitCode(Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> read = new LinkedHashMap<>();
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE,
                BOOTSTRAP_CREDENTIAL_HASH_VARIABLE}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                err.println(name + " is not set — the sm_control migration step reads its "
                        + "configuration from the environment, and will not guess it");
                return EXIT_MISSING_VARIABLE;
            }
            read.put(name, value);
        }
        try {
            CredentialHasher.parametersOf(read.get(BOOTSTRAP_CREDENTIAL_HASH_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(BOOTSTRAP_CREDENTIAL_HASH_VARIABLE + " does not parse as an {argon2id} hash — "
                    + "it must be the HASH of the installer-supplied bootstrap credential, never the "
                    + "plaintext secret itself");
            return EXIT_MISSING_VARIABLE;
        }
        try {
            JdbcUrls.requireLocation(read.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            // Its message is JdbcUrls' own words — the rule and the allowlist, never the URL, a value
            // or an unrecognised parameter name — so it is safe to print.
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        try {
            MigrateResult result = run(read.get(URL_VARIABLE), read.get(USERNAME_VARIABLE),
                    read.get(PASSWORD_VARIABLE), read.get(BOOTSTRAP_CREDENTIAL_HASH_VARIABLE));
            out.println("migrated " + result.migrationsExecuted + " (versions " + versions(result) + ")");
            return 0;
        } catch (NoDriverRegistered e) {
            err.println("the sm_control migration failed: " + e.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("the sm_control migration failed: " + classify(e));
            return EXIT_FAILED;
        }
    }

    /**
     * Applies Phase 1 with the migrator credential — the one-shot step every caller shares, including
     * the integration harness.
     */
    public static MigrateResult run(String url, String username, String password, String bootstrapCredentialHash) {
        return SmControlMigrator.migrate(dataSource(url, username, password), bootstrapCredentialHash);
    }

    /**
     * A one-connection-at-a-time data source for the migrator credential — and, along with
     * {@link #run}, the choke point every caller (the env-variable path above, and the integration
     * harness directly) passes through on the way to a driver or Flyway, so it is where
     * {@link JdbcUrls} is enforced regardless of caller.
     *
     * <p>The grammar check runs FIRST, before {@code url} ever reaches {@link DriverManager#getDriver}
     * — dp-core's {@code SmDataMigrationMain} measured that pgjdbc's own {@code Driver.parseURL}
     * (invoked from {@code acceptsURL}, which {@code DriverManager.getDriver} calls on every
     * registered driver before this method sees a result) logs a {@code java.util.logging} WARNING
     * that, for several grammar violations, CONTAINS the offending URL — including a credential
     * parameter or the userinfo form — independent of this class's own streams. Grammar-first means
     * none of those cases reach pgjdbc's parser at all.
     *
     * <p>The driver comes from {@link DriverManager#getDriver(String)} rather than naming the
     * concrete driver class, so this wiring compiles against {@code java.sql} only.
     */
    static SimpleDriverDataSource dataSource(String url, String username, String password) {
        JdbcUrls.requireLocation(url);
        try {
            return new SimpleDriverDataSource(DriverManager.getDriver(url), url, username, password);
        } catch (SQLException e) {
            throw new NoDriverRegistered(e);
        }
    }

    /** No registered driver claims the URL's scheme. */
    static final class NoDriverRegistered extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NoDriverRegistered(SQLException cause) {
            super("no JDBC driver is registered for the configured URL's scheme", cause);
        }
    }

    /**
     * A failure as classifiers only: {@code FlywaySqlException (SQLSTATE 08001) — …}. Package-private
     * rather than private: {@link SmControlLockoutRecoveryMain} reuses it for the identical reason —
     * never echo a failure's own message, which may embed a URL or role name.
     */
    static String classify(Throwable failure) {
        String sqlState = sqlStateOf(failure);
        return failure.getClass().getSimpleName()
                + (sqlState == null ? "" : " (SQLSTATE " + sqlState + ")")
                + NO_MESSAGE_TEXT;
    }

    /** The first SQLSTATE in the cause chain, or null. Bounded, because a chain may be cyclic. */
    static String sqlStateOf(Throwable failure) {
        Throwable c = failure;
        for (int depth = 0; c != null && depth < 32; c = c.getCause(), depth++) {
            if (c instanceof SQLException sql && sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
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
