package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The {@code sm_metrics} persistence beans over the runtime credential's template (plan 011 P11-2.1), beside
 * {@link InventoryPersistenceConfiguration}. Declares its OWN readiness check for its OWN schema, so no task edits
 * {@link ApplyPersistenceConfiguration}'s {@code EXPECTED_TABLES}. Imported by {@code DataPlaneConfiguration}.
 *
 * <p>Every {@code sm_metrics} statement is schema-qualified: the {@code sm-data} pool pins
 * {@code setSchema("sm_data")}, so an unqualified name would resolve in the wrong schema.
 *
 * <p><b>Readiness is tables and the function's privilege. It is deliberately NOT the runway</b> (Review Focus 5): a
 * database whose partitions ran out must still let config, apply, health and login come up. Only metrics ingest
 * degrades, and it is counted ({@code runway_degraded}).
 */
@Configuration(proxyBeanMethods = false)
public class MetricsPersistenceConfiguration {

    /** The nine tables V001 creates and grants to the runtime role; a literal, nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_metrics'"
            + " AND table_name IN ('series','series_segment','series_reset','restart_marker','metrics_raw',"
            + "'metrics_rollup_1h','metrics_rollup_1d','ingest_counter','partition_freeze_log')";

    static final int EXPECTED_TABLES = 9;

    /** Can the application's own role call the runway function? {@code false} when it does not exist. */
    static final String FUNCTION_QUERY = "SELECT coalesce(has_function_privilege(current_user,"
            + " to_regprocedure('sm_metrics.ensure_metric_partitions()'), 'EXECUTE'), false)";

    @Bean
    InitializingBean smMetricsSchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            boolean executable;
            try (Connection c = smDataDataSource.getConnection(); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                    rs.next();
                    found = rs.getInt(1);
                }
                try (ResultSet rs = s.executeQuery(FUNCTION_QUERY)) {
                    rs.next();
                    executable = rs.getBoolean(1);
                }
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_metrics is not migrated to the shape this build expects (found "
                        + found + " of " + EXPECTED_TABLES + " tables): run the migration step "
                        + "— SmDataMigrationMain with the sm_dp_migrator credential — before starting the application");
            }
            if (!executable) {
                throw new IllegalStateException("the runtime role cannot execute sm_metrics.ensure_metric_partitions(): "
                        + "the partition runway could never be kept. Run the migration step "
                        + "— SmDataMigrationMain with the sm_dp_migrator credential — before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcPartitionRunwayRepository partitionRunwayRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcPartitionRunwayRepository(smDataJdbc);
    }
}
