package com.solace.sm.dp.apply.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Phase 1 of the {@code sm_metrics} bootstrap (plan 011 P11-2.1; M-Q19, {@code OR-76}): {@link SmDataMigrator}'s twin
 * for the DP-owned metrics schema, with a Flyway history of its own.
 *
 * <p><b>Why a second history.</b> {@code sm_metrics} starts at {@code V001} and has no number contention with
 * {@code sm_data}, whose numbering plan 010 allocates. Each run sets its own {@code schemas(...)} and
 * {@code defaultSchema(...)}, so Flyway records the history in the first of them: {@code sm_metrics.flyway_schema_history}
 * beside {@code sm_data.flyway_schema_history} (MF1, measured in {@code SmMetricsMigratorIT}).
 *
 * <p>Everything {@link SmDataMigrator}'s javadoc says about {@code initSql} (ownership at CREATE time) and
 * {@code createSchemas(false)} (a gate: a database that skipped Phase 0 fails loudly) holds here unchanged. The
 * schema is created by Phase 0's {@code CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner}.
 */
public final class SmMetricsMigrator {

    /** The metrics schema ({@code I7}: DP-owned; no foreign key reaches {@code sm_control}). */
    public static final String SCHEMA = "sm_metrics";

    /** Classpath location of the {@code sm_metrics} migrations, apart from {@code sm_data}'s. */
    public static final String LOCATION = "classpath:db/migration/sm_metrics";

    private SmMetricsMigrator() {
    }

    /**
     * Applies the {@code sm_metrics} migrations with the migrator credential.
     *
     * @param migratorDataSource a {@code sm_dp_migrator} data source, never the runtime one
     * @return Flyway's own report
     */
    public static MigrateResult migrate(DataSource migratorDataSource) {
        return Flyway.configure()
                .dataSource(migratorDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .initSql(SmDataMigrator.INIT_SQL)
                .locations(LOCATION)
                .createSchemas(false)
                .load()
                .migrate();
    }
}
