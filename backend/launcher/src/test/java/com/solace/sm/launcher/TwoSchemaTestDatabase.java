package com.solace.sm.launcher;

import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.SmControlMigrationMain;
import com.solace.sm.dp.apply.persistence.SmDataMigrationMain;
import java.sql.Connection;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The same one-container, both-schemas provisioning {@code ProductionAuthenticationIT} uses,
 * factored out so Task 4a/4b's tests do not have to re-derive it: one real PostgreSQL 14, both
 * planes' real phase-0 SQL, both real migration mains, and the runtime credentials registered as
 * the properties the application binds — nothing more, since these tests exercise the apply worker
 * and the startup sweep, not authentication.
 */
final class TwoSchemaTestDatabase {

    final String jdbcUrl;
    final DataSource superuser;
    final NamedParameterJdbcTemplate ownerJdbc;

    private TwoSchemaTestDatabase(String jdbcUrl, DataSource superuser) {
        this.jdbcUrl = jdbcUrl;
        this.superuser = superuser;
        this.ownerJdbc = new NamedParameterJdbcTemplate(superuser);
    }

    /**
     * Starts the container, migrates both schemas, and registers exactly the properties
     * {@code SolutionManagerApplication} needs to boot: the two runtime credentials, and the
     * gateway properties that have no default (mirroring {@code ProductionAuthenticationIT}, whose
     * own suite is this build's proof that this set is sufficient to boot for real).
     */
    static TwoSchemaTestDatabase provision(DynamicPropertyRegistry registry) {
        Provisioned p = doProvision();
        p.properties().forEach((key, value) -> registry.add(key, () -> value));
        return p.db();
    }

    /**
     * The same provisioning, but returning the properties as a plain {@code Map} instead of
     * registering them with a {@link DynamicPropertyRegistry} — for a test that boots MORE THAN ONE
     * {@code ApplicationContext} against ONE database by hand, under its own thread control (e.g.
     * to observe one context mid-boot while a second one is attempted). JUnit's
     * {@code @DynamicPropertySource} is one-registry-per-test-class and does not fit that shape;
     * {@code SpringApplicationBuilder.properties(Map)} accepts the exact same keys this method
     * would otherwise hand to the registry.
     */
    static Provisioned provisionStandalone() {
        return doProvision();
    }

    /**
     * The switch that withdraws the production executor, adapter and config source (plan 010 T6.2, M4/M16 ii). The ITs
     * that bring their OWN executor over an in-memory adapter set it: production now supplies one, and two
     * {@code ApplyExecutor}s (or two beans named {@code runtimeAdapter}) abort the boot.
     */
    static final String PRODUCTION_APPLY = "sm.dp.apply.production.enabled";

    /** The health poller's switch (plan 010 T7.2); {@link #doProvision} turns it off for every IT by default. */
    static final String HEALTH_POLLER = "sm.dp.health.enabled";

    /** As {@link #provision}, with the production apply path withdrawn, for a test that supplies its own executor. */
    static TwoSchemaTestDatabase provisionWithOwnExecutor(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase db = provision(registry);
        registry.add(PRODUCTION_APPLY, () -> "false");
        return db;
    }

    /** As {@link #provisionStandalone}, with the production apply path withdrawn from the returned properties. */
    static Provisioned provisionStandaloneWithOwnExecutor() {
        Provisioned provisioned = provisionStandalone();
        Map<String, String> properties = new LinkedHashMap<>(provisioned.properties());
        properties.put(PRODUCTION_APPLY, "false");
        return new Provisioned(provisioned.db(), properties);
    }

    /** The environment-variable names the DP secret store reads its master key from (plan 010 T2.6). */
    static final String MASTER_KEY = "SM_DP_MASTER_KEY";
    static final String MASTER_KEY_ID = "SM_DP_MASTER_KEY_ID";

    /**
     * A per-call random test KEK and its id, spelled as the literal environment variables the production provider
     * reads. Importing the data plane aggregator makes every launcher boot fail closed without a master key, so every
     * launcher IT registers these. Never a committed default key (D-KEY): random bytes, minted per provisioning.
     */
    static Map<String, String> testMasterKeyProperties() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(MASTER_KEY, Base64.getEncoder().encodeToString(key));
        properties.put(MASTER_KEY_ID, "launcher-it-" + UUID.randomUUID().toString().substring(0, 8));
        // a test cannot set a process environment variable; production refuses a key in any other source (OR-85)
        properties.put(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES, "true");
        return properties;
    }

    /** For the ITs that provision their own databases: registers {@link #testMasterKeyProperties()}. */
    static void registerTestMasterKey(DynamicPropertyRegistry registry) {
        testMasterKeyProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    private static Provisioned doProvision() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");
        postgres.start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/"
                + postgres.getDatabaseName();

        DataSource superuser =
                new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate superuserJdbc = new JdbcTemplate(superuser);
        runPhase0(superuser, "db/bootstrap/sm_control_phase0.sql");
        runPhase0(superuser, "db/bootstrap/sm_data_phase0.sql");

        String cpMigratorPassword = "cp-migrator-" + UUID.randomUUID();
        String cpRuntimePassword = "cp-runtime-" + UUID.randomUUID();
        String dpMigratorPassword = "dp-migrator-" + UUID.randomUUID();
        String dpRuntimePassword = "dp-runtime-" + UUID.randomUUID();
        superuserJdbc.execute("ALTER ROLE sm_cp_migrator PASSWORD '" + cpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_cp_runtime PASSWORD '" + cpRuntimePassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_migrator PASSWORD '" + dpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_runtime PASSWORD '" + dpRuntimePassword + "'");

        String bootstrapCredentialHash =
                new CredentialHasher().hash("unused-bootstrap-credential".toCharArray());
        SmControlMigrationMain.run(url, "sm_cp_migrator", cpMigratorPassword, bootstrapCredentialHash);
        SmDataMigrationMain.run(url, "sm_dp_migrator", dpMigratorPassword);

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("sm.control.runtime.url", url);
        properties.put("sm.control.runtime.username", "sm_cp_runtime");
        properties.put("sm.control.runtime.password", cpRuntimePassword);
        properties.put("sm.data.runtime.url", url);
        properties.put("sm.data.runtime.username", "sm_dp_runtime");
        properties.put("sm.data.runtime.password", dpRuntimePassword);
        properties.put("sm.gateway.trusted-origins[0]", "https://console.test");
        properties.put("sm.gateway.direct-client-connections", "true");
        // Plan 010 T7.2: the health poller is ON in production and OFF in every IT that does not test it - it would
        // otherwise probe the fixtures' fake hosts, draw stamps from the one sequence and take instance row locks
        // beneath tests that assert on exactly those things. HealthEndpointsIT sets it back to true.
        properties.put(HEALTH_POLLER, "false");
        // OR-140 (plan 015 T4.6): the two scheduled agent jobs are ON in production and OFF in every IT by default, for the same reason: a pass
        // firing across cached contexts would race unrelated tests on shared rows. A test that needs a pass calls runOnce() by hand.
        properties.put("sm.gateway.agent-sweep.enabled", "false");
        properties.put("sm.dp.agent.staleness-job.enabled", "false");
        properties.putAll(testMasterKeyProperties());

        return new Provisioned(new TwoSchemaTestDatabase(url, superuser), properties);
    }

    /** One provisioned database, plus the properties a manual {@code SpringApplicationBuilder} needs. */
    record Provisioned(TwoSchemaTestDatabase db, Map<String, String> properties) {
    }

    private static void runPhase0(DataSource superuser, String classpathResource) {
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource(classpathResource)).populate(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("phase 0 (" + classpathResource + ") failed", e);
        }
    }
}
