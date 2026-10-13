package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.identity.CredentialHasher;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link SmControlMigrationMain}'s environment contract, and one successful migration against a
 * Phase-0-only container — the scope the task asked for: exit codes for missing/malformed
 * environment, and a successful migrate. {@code main()} calls {@link System#exit}, so this drives the
 * package-private {@code exitCode} instead, exactly as dp-core's equivalent test does.
 */
class SmControlMigrationMainTest {

    private static final String PASSWORD = "s3cr3t-migrator-pw";
    private static final String VALID_HASH = new CredentialHasher().hash("bootstrap-secret".toCharArray());

    @Test
    void aMissingUrlExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmControlMigrationMain.URL_VARIABLE);
        assertMissing(SmControlMigrationMain.URL_VARIABLE, env);
    }

    @Test
    void aMissingUsernameExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmControlMigrationMain.USERNAME_VARIABLE);
        assertMissing(SmControlMigrationMain.USERNAME_VARIABLE, env);
    }

    @Test
    void aBlankPasswordCountsAsMissingBecauseAnEmptySecretIsAlmostAlwaysAnUnsetOne() {
        Map<String, String> env = complete();
        env.put(SmControlMigrationMain.PASSWORD_VARIABLE, "   ");
        assertMissing(SmControlMigrationMain.PASSWORD_VARIABLE, env);
    }

    @Test
    void aMissingBootstrapCredentialHashExitsTwo() {
        Map<String, String> env = complete();
        env.remove(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE);
        assertMissing(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE, env);
    }

    @Test
    void aBootstrapCredentialHashThatIsActuallyAPlaintextIsRefusedBeforeFlywayEverRuns() {
        Map<String, String> env = complete();
        env.put(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE, "not-an-argon2id-hash");

        Run run = run(env);

        assertEquals(SmControlMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }

    @Test
    void aSuccessfulMigrationAgainstAPhaseZeroOnlyContainerAppliesEveryPinnedVersionAndIsIdempotent() throws Exception {
        MigratorDatabase database = freshMigratorReadyDatabase();
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlMigrationMain.URL_VARIABLE, database.url());
        env.put(SmControlMigrationMain.USERNAME_VARIABLE, "sm_cp_migrator");
        env.put(SmControlMigrationMain.PASSWORD_VARIABLE, database.migratorPassword());
        env.put(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE, VALID_HASH);

        Run first = run(env);
        assertEquals(0, first.code, first.stderr());
        // Every pinned migration, counted from CHECKSUMS.sha256 (which MigrationChecksumsTest ties to the files on disk)
        // rather than a literal that every new migration, in every lane, would have to edit in the same line.
        assertTrue(first.stdout().contains("migrated " + pinnedMigrationCount() + " "), first.stdout());

        Run second = run(env);
        assertEquals(0, second.code, second.stderr());
        assertTrue(second.stdout().contains("migrated 0"),
                "a runbook that re-runs the step must be a no-op. Was: " + second.stdout());

        DataSource superuser = database.superuser();
        JdbcTemplate jdbc = new JdbcTemplate(superuser);
        Integer principals = jdbc.queryForObject("SELECT count(*) FROM sm_control.principal", Integer.class);
        assertEquals(1, principals, "V007 seeds exactly the bootstrap administrator");
    }

    // -------------------------------------------------------------------------------------------

    private static long pinnedMigrationCount() throws java.io.IOException {
        try (java.io.InputStream in = new ClassPathResource("db/migration/sm_control/CHECKSUMS.sha256").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count();
        }
    }

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlMigrationMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm");
        env.put(SmControlMigrationMain.USERNAME_VARIABLE, "sm_cp_migrator");
        env.put(SmControlMigrationMain.PASSWORD_VARIABLE, PASSWORD);
        env.put(SmControlMigrationMain.BOOTSTRAP_CREDENTIAL_HASH_VARIABLE, VALID_HASH);
        return env;
    }

    private record Run(int code, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        String stdout() {
            return out.toString(StandardCharsets.UTF_8).strip();
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SmControlMigrationMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out, err);
    }

    private static void assertMissing(String variable, Map<String, String> env) {
        Run run = run(env);
        assertEquals(SmControlMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(variable), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }

    private record MigratorDatabase(String url, String migratorPassword, DataSource superuser) {
    }

    /** A fresh container carrying only Phase 0's roles and schema, with a known migrator password. */
    private static MigratorDatabase freshMigratorReadyDatabase() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:14");
        container.start();
        DataSource superuser = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/sm_control_phase0.sql"))
                    .populate(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("unable to run sm_control Phase 0", e);
        }
        String migratorPassword = "migrator-" + System.nanoTime();
        new JdbcTemplate(superuser).execute("ALTER ROLE sm_cp_migrator PASSWORD '" + migratorPassword + "'");
        // A bare location, never container.getJdbcUrl(): this URL now flows through
        // SmControlMigrationMain.dataSource()'s JdbcUrls guard, and Testcontainers' own URL carries
        // ?loggerLevel=OFF, which that guard's allowlist does not admit.
        return new MigratorDatabase(SmControlPostgres.jdbcUrl(container), migratorPassword, superuser);
    }
}
