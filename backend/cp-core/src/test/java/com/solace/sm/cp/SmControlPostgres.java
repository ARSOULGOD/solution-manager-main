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

    /** The runtime role every migration grants access to — {@code sm_cp_runtime}. */
    public static final String RUNTIME_USERNAME = "sm_cp_runtime";

    /**
     * The newest {@code sm_control} migration this tree carries. A test that means "a fully migrated schema" (one the
     * application's readiness checks must accept, or one whose current role grants are asserted) names this constant,
     * so adding a migration moves it here, once, instead of in every such test.
     */
    public static final String LATEST_VERSION = "027";

    private static final Map<String, NamedParameterJdbcTemplate> JDBC_BY_VERSION = new HashMap<>();
    private static final Map<String, NamedParameterJdbcTemplate> RUNTIME_JDBC_BY_VERSION = new HashMap<>();
    private static final Map<String, PostgreSQLContainer<?>> CONTAINERS_BY_VERSION = new HashMap<>();
    private static final Map<String, char[]> BOOTSTRAP_CREDENTIALS_BY_VERSION = new HashMap<>();
    private static final Map<String, String> BOOTSTRAP_HASHES_BY_VERSION = new HashMap<>();
    private static final Map<String, String> RUNTIME_PASSWORDS_BY_VERSION = new HashMap<>();
    private static final Map<String, String> MIGRATOR_PASSWORDS_BY_VERSION = new HashMap<>();

    /**
     * The Flyway target version a cache key's container was actually created (and migrated) at,
     * recorded the moment the container is created. Every single-arg accessor below resolves its
     * argument through this map before touching any {@code *_BY_VERSION} map, rather than assuming
     * the argument IS the version — that assumption is only ever true for a cache key nobody has
     * given an explicit, different version via {@link #jdbcAtVersion(String, String)} yet (e.g. a
     * plain {@code "010"} caller), and is false the moment a caller like
     * {@code BootstrapAdminIT} uses a cache key ({@code "bootstrap-admin-it"}) that is not itself a
     * valid Flyway version. Before this map existed, such a caller only worked by accident of call
     * order: its own two-arg {@link #jdbcAtVersion(String, String)} call had to run BEFORE any
     * single-arg accessor for the same key, so the single-arg call's self-paired
     * {@code jdbcAtVersion(key, key)} hit an already-cached container and never actually tried to
     * migrate to a target named {@code "bootstrap-admin-it"}.
     */
    private static final Map<String, String> VERSION_BY_CACHE_KEY = new HashMap<>();

    private SmControlPostgres() {
    }

    public static synchronized NamedParameterJdbcTemplate jdbcAtVersion(String cacheKey) {
        return jdbcAtVersion(cacheKey, VERSION_BY_CACHE_KEY.getOrDefault(cacheKey, cacheKey));
    }

    /**
     * Like {@link #jdbcAtVersion}, but under a CACHE KEY distinct from the Flyway target version —
     * for a test class whose assertions (e.g. "exactly one row exists in the whole schema") are only
     * true in a container it shares with nobody else, even though it needs the same target schema
     * every other {@code jdbcAtVersion(sameVersion)} caller shares one container for.
     *
     * @param cacheKey the key this container is cached under — unique to the isolated caller
     * @param version  the Flyway target version to migrate to, e.g. {@code "010"}
     */
    public static synchronized NamedParameterJdbcTemplate jdbcAtVersion(String cacheKey, String version) {
        return JDBC_BY_VERSION.computeIfAbsent(cacheKey, ignored -> {
            VERSION_BY_CACHE_KEY.put(cacheKey, version);
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
            // No GRANT CREATE ON DATABASE: the harness now runs the real phase-0 topology, which
            // never grants it. V001 no longer needs it (it re-issued CREATE SCHEMA, which phase 0
            // already did), so every IT proves a phase0-only database migrates.
            char[] bootstrapCredential = opaqueBootstrapCredential();
            String bootstrapHash = new CredentialHasher().hash(bootstrapCredential);
            DataSource migratorDataSource = new DriverManagerDataSource(
                    container.getJdbcUrl(), "sm_cp_migrator", migratorPassword);
            Flyway.configure().dataSource(migratorDataSource).initSql("SET ROLE sm_cp_owner")
                    .defaultSchema("sm_control")
                    .locations("classpath:db/migration/sm_control")
                    .placeholders(Map.of("bootstrap_credential_hash", bootstrapHash))
                    .target(MigrationVersion.fromVersion(version)).load().migrate();
            CONTAINERS_BY_VERSION.put(cacheKey, container);
            BOOTSTRAP_CREDENTIALS_BY_VERSION.put(cacheKey, bootstrapCredential);
            BOOTSTRAP_HASHES_BY_VERSION.put(cacheKey, bootstrapHash);
            RUNTIME_PASSWORDS_BY_VERSION.put(cacheKey, runtimePassword);
            MIGRATOR_PASSWORDS_BY_VERSION.put(cacheKey, migratorPassword);
            RUNTIME_JDBC_BY_VERSION.put(cacheKey, new NamedParameterJdbcTemplate(
                    new DriverManagerDataSource(container.getJdbcUrl(), "sm_cp_runtime", runtimePassword)));
            return new NamedParameterJdbcTemplate(migratorDataSource);
        });
    }

    /**
     * Migrates an ALREADY-built container (cached under {@code cacheKey}) further, to {@code version}, as the real
     * migrator would: the upgrade path, for a test that seeds rows at the old version first (W5D-2). A migration that
     * fails surfaces as the Flyway exception.
     */
    public static synchronized void migrateTo(String cacheKey, String version) {
        jdbcAtVersion(cacheKey);
        DataSource migratorDataSource = new DriverManagerDataSource(
                CONTAINERS_BY_VERSION.get(cacheKey).getJdbcUrl(), "sm_cp_migrator",
                MIGRATOR_PASSWORDS_BY_VERSION.get(cacheKey));
        Flyway.configure().dataSource(migratorDataSource).initSql("SET ROLE sm_cp_owner")
                .defaultSchema("sm_control")
                .locations("classpath:db/migration/sm_control")
                .placeholders(Map.of("bootstrap_credential_hash", BOOTSTRAP_HASHES_BY_VERSION.get(cacheKey)))
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    public static synchronized NamedParameterJdbcTemplate runtimeJdbcAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return RUNTIME_JDBC_BY_VERSION.get(cacheKey);
    }

    public static synchronized char[] bootstrapCredentialAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return BOOTSTRAP_CREDENTIALS_BY_VERSION.get(cacheKey).clone();
    }

    public static synchronized String bootstrapCredentialHashAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return BOOTSTRAP_HASHES_BY_VERSION.get(cacheKey);
    }

    /**
     * The {@code sm_cp_runtime} JDBC URL at a fully migrated version — for Spring-wired ITs.
     *
     * <p>Built as a bare location, never {@code container.getJdbcUrl()}: Testcontainers' own URL
     * carries {@code ?loggerLevel=OFF}, which {@code JdbcUrls.ALLOWED} does not admit, and this URL
     * now flows through that guard (via {@code SmControlProperties.Credentials} or
     * {@code SmControlMigrationMain}) in every caller that binds it.
     */
    public static synchronized String runtimeUrlAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return jdbcUrl(CONTAINERS_BY_VERSION.get(cacheKey));
    }

    /** A bare {@code jdbc:postgresql://host:port/database} location — see {@link #runtimeUrlAtVersion}. */
    public static String jdbcUrl(PostgreSQLContainer<?> container) {
        return "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + container.getDatabaseName();
    }

    /** The {@code sm_cp_runtime} password at a fully migrated version — for Spring-wired ITs. */
    public static synchronized String runtimePasswordAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return RUNTIME_PASSWORDS_BY_VERSION.get(cacheKey);
    }

    /**
     * The container's own bootstrap SUPERUSER credential — never named {@code sm_cp_runtime} — for
     * proving (m2) that a connection under any OTHER role name, even one with every grant a real
     * deployment could give it, must still fail the {@code session_user = 'sm_cp_runtime'}
     * readiness check. Real deployments never use this login; it exists in every container only
     * because Testcontainers' {@code PostgreSQLContainer} creates it to run Phase 0.
     */
    public static synchronized String superuserUrlAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return jdbcUrl(CONTAINERS_BY_VERSION.get(cacheKey));
    }

    /** @see #superuserUrlAtVersion */
    public static synchronized String superuserUsernameAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return CONTAINERS_BY_VERSION.get(cacheKey).getUsername();
    }

    /** @see #superuserUrlAtVersion */
    public static synchronized String superuserPasswordAtVersion(String cacheKey) {
        jdbcAtVersion(cacheKey);
        return CONTAINERS_BY_VERSION.get(cacheKey).getPassword();
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
