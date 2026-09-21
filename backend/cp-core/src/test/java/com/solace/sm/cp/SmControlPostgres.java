package com.solace.sm.cp;

import com.solace.sm.cp.identity.CredentialHasher;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared PostgreSQL 14 harness for {@code sm_control} integration tests. */
public final class SmControlPostgres {

    private static final Map<String, NamedParameterJdbcTemplate> JDBC_BY_VERSION = new HashMap<>();
    private static final Map<String, NamedParameterJdbcTemplate> RUNTIME_JDBC_BY_VERSION = new HashMap<>();
    private static final Map<String, PostgreSQLContainer<?>> CONTAINERS_BY_VERSION = new HashMap<>();
    private static final Map<String, char[]> BOOTSTRAP_CREDENTIALS_BY_VERSION = new HashMap<>();
    private static final Map<String, String> BOOTSTRAP_HASHES_BY_VERSION = new HashMap<>();

    private SmControlPostgres() {
    }

    public static synchronized NamedParameterJdbcTemplate jdbcAtVersion(String version) {
        return JDBC_BY_VERSION.computeIfAbsent(version, ignored -> {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:14");
            container.start();
            DataSource phaseZero = new DriverManagerDataSource(
                    container.getJdbcUrl(), container.getUsername(), container.getPassword());
            String migratorPassword = UUID.randomUUID().toString();
            String runtimePassword = UUID.randomUUID().toString();
            JdbcTemplate bootstrap = new JdbcTemplate(phaseZero);
            runPhaseZero(phaseZero);
            bootstrap.execute("ALTER ROLE sm_cp_migrator PASSWORD '" + migratorPassword + "'");
            bootstrap.execute("ALTER ROLE sm_cp_runtime PASSWORD '" + runtimePassword + "'");
            bootstrap.execute("GRANT CREATE ON DATABASE " + container.getDatabaseName() + " TO sm_cp_owner");
            char[] bootstrapCredential = opaqueBootstrapCredential();
            String bootstrapHash = new CredentialHasher().hash(bootstrapCredential);
            DataSource migratorDataSource = new DriverManagerDataSource(
                    container.getJdbcUrl(), "sm_cp_migrator", migratorPassword);
            Flyway.configure().dataSource(migratorDataSource).initSql("SET ROLE sm_cp_owner")
                    .defaultSchema("sm_control")
                    .locations("classpath:db/migration/sm_control")
                    .placeholders(Map.of("bootstrap_credential_hash", bootstrapHash))
                    .target(MigrationVersion.fromVersion(version)).load().migrate();
            CONTAINERS_BY_VERSION.put(version, container);
            BOOTSTRAP_CREDENTIALS_BY_VERSION.put(version, bootstrapCredential);
            BOOTSTRAP_HASHES_BY_VERSION.put(version, bootstrapHash);
            RUNTIME_JDBC_BY_VERSION.put(version, new NamedParameterJdbcTemplate(
                    new DriverManagerDataSource(container.getJdbcUrl(), "sm_cp_runtime", runtimePassword)));
            return new NamedParameterJdbcTemplate(migratorDataSource);
        });
    }

    public static synchronized NamedParameterJdbcTemplate runtimeJdbcAtVersion(String version) {
        jdbcAtVersion(version);
        return RUNTIME_JDBC_BY_VERSION.get(version);
    }

    public static synchronized char[] bootstrapCredentialAtVersion(String version) {
        jdbcAtVersion(version);
        return BOOTSTRAP_CREDENTIALS_BY_VERSION.get(version).clone();
    }

    public static synchronized String bootstrapCredentialHashAtVersion(String version) {
        jdbcAtVersion(version);
        return BOOTSTRAP_HASHES_BY_VERSION.get(version);
    }

    private static void runPhaseZero(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/sm_control_phase0.sql"))
                    .populate(connection);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("unable to run sm_control Phase 0", exception);
        }
    }

    private static char[] opaqueBootstrapCredential() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }
}
