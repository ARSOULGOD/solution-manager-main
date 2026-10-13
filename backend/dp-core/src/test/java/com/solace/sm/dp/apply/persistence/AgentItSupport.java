package com.solace.sm.dp.apply.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The public face of this package's package-private harness ({@link SmDataPostgres}, {@link InventoryFixtures},
 * {@link ApplyRows}, {@link Refusals}, {@link MetricsDb}) for the agent-gateway ITs in {@code ...persistence.agent}
 * (plan 015 T1.1). It adds nothing: every method delegates, so the agent ITs run on the one shared PostgreSQL 14.
 */
public final class AgentItSupport {

    public static final String MIGRATOR_PASSWORD = SmDataPostgres.MIGRATOR_PASSWORD;

    private AgentItSupport() {
    }

    public static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    public static NamedParameterJdbcTemplate superuserJdbc() {
        return SmDataPostgres.superuserJdbc();
    }

    public static NamedParameterJdbcTemplate runtimeJdbc() {
        return SmDataPostgres.runtimeJdbc();
    }

    public static DataSource superuserDataSource() {
        return SmDataPostgres.superuser();
    }

    public static DataSource dataSource(String url, String user, String password) {
        return SmDataPostgres.dataSource(url, user, password);
    }

    /**
     * An active instance with its apply scope, in the database {@code jdbc} names (not the shared one: the agent ITs run in
     * a database of their own, see {@link #freshDatabase(String)}).
     */
    public static void declareInstance(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance) {
        InventoryFixtures.declare(jdbc, tenant, instance, "active");
        jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    /** A database of its own (plan 015 T1.1 fix round, finding 12): rows the agent ITs leave behind reach no other IT. */
    public record AgentDb(String url, NamedParameterJdbcTemplate owner, NamedParameterJdbcTemplate runtime,
            DataSource ownerDataSource) {
    }

    /** A new database, fully migrated, with Phase 0's roles; the same roles as the shared one. */
    public static AgentDb freshDatabase(String name) {
        MetricsDb db = MetricsDb.fresh(name);
        DataSource owner = db.superuser();                 // ONE data source, so a TransactionTemplate over it binds the template
        return new AgentDb(db.url(), new NamedParameterJdbcTemplate(owner),
                new NamedParameterJdbcTemplate(db.runtime()), owner);
    }

    public static UUID placement(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, UUID placement) {
        return InventoryFixtures.placement(jdbc, tenant, instance, target, placement);
    }

    /** {@link InventoryFixtures#target(NamedParameterJdbcTemplate, UUID)}: an SSH-docker target of the organization (another host than any agent's). */
    public static UUID sshTarget(NamedParameterJdbcTemplate jdbc, UUID tenant) {
        return InventoryFixtures.target(jdbc, tenant);
    }

    /** A REQUESTED {@code apply} on {@code placement}. */
    public static UUID request(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID placement) {
        return ApplyRows.insertRequest(jdbc, tenant, instance, placement, "REQUESTED");
    }

    /** {@link InventoryFixtures#agentTarget}: the agent's target and identity, in one transaction. Returns the target id. */
    public static UUID agentTarget(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID agent, String thumbprint) {
        return InventoryFixtures.agentTarget(jdbc, tenant, agent, thumbprint);
    }

    /** {@link InventoryFixtures#agentPlacement}. */
    public static UUID agentPlacement(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, UUID placement) {
        return InventoryFixtures.agentPlacement(jdbc, tenant, instance, target, placement);
    }

    /**
     * Plan 015 T4.1: the REAL {@code InventoryService} over the runtime role, for the discovery intake's ITs. It never registers an SSH key, so the throwaway secret ring is never used.
     */
    public static com.solace.sm.dp.inventory.InventoryService inventory(NamedParameterJdbcTemplate runtime,
            org.springframework.transaction.support.TransactionTemplate tx, java.time.Clock clock) {
        return inventory(runtime, tx, clock, com.solace.sm.dp.apply.events.ApplyEventEmitter.silent());
    }

    /** As above, emitting through {@code events} (W4 integration: so the declarations-change signal of an activation is observable). */
    public static com.solace.sm.dp.inventory.InventoryService inventory(NamedParameterJdbcTemplate runtime,
            org.springframework.transaction.support.TransactionTemplate tx, java.time.Clock clock,
            com.solace.sm.dp.apply.events.ApplyEventEmitter events) {
        com.solace.sm.dp.secret.EnvelopeSecretStore secrets = SecretFixtures.store(
                SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 47), null));
        return new com.solace.sm.dp.inventory.InventoryService(tx, new JdbcInstanceStateRepository(runtime),
                new JdbcPlacementRepository(runtime), new JdbcTargetConnectionRepository(runtime), secrets, secrets, clock,
                new JdbcMetricDefinitionRepository(runtime), com.solace.sm.dp.config.LadderRecorder.none(),
                events, null);
    }

    public static org.postgresql.util.PSQLException assertRefused(String sqlState, String constraintOrNull, Executable e) {
        return Refusals.assertRefused(sqlState, constraintOrNull, e);
    }

    /** As above, with a sentence saying which of several probes this one is. */
    public static org.postgresql.util.PSQLException assertRefused(String sqlState, String constraintOrNull, Executable e,
            String because) {
        return Refusals.assertRefused(sqlState, constraintOrNull, e, because);
    }

    /** A new database with Phase 0's schema half and NOTHING migrated; returns its URL. */
    public static String freshUnmigratedDatabase(String name) {
        return MetricsDb.fresh(name, MetricsDb.Migrated.NOTHING).url();
    }

    public static DataSource superuserOf(String url) {
        return SmDataPostgres.dataSource(url, SmDataPostgres.container().getUsername(),
                SmDataPostgres.container().getPassword());
    }

    public static Flyway flyway(DataSource migrator, String targetOrNull) {
        var cfg = Flyway.configure().dataSource(migrator).schemas(SmDataMigrator.SCHEMA)
                .defaultSchema(SmDataMigrator.SCHEMA).initSql(SmDataMigrator.INIT_SQL)
                .locations(SmDataMigrator.LOCATION).createSchemas(false);
        return (targetOrNull == null ? cfg : cfg.target(targetOrNull)).load();
    }
}
