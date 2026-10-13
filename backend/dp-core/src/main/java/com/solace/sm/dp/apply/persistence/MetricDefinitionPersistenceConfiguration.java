package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The persistence port for the metric-definition tables (plan 011 P11-2.2), with its OWN readiness check for its OWN
 * objects, so no task edits another area's expected-table list. It does NOT share {@link MetricsPersistenceConfiguration},
 * which covers {@code sm_metrics} and is a different task's. Imported by {@code DataPlaneConfiguration}.
 *
 * <p>The check asks for the five tables and for the two digest triggers: a schema with the tables and without the
 * triggers would accept a child row carrying the wrong digest, which is the one thing the database half of M-Q5 is for.
 * The message names the migration by its description, which does not move when the integrator renumbers it.
 */
@Configuration(proxyBeanMethods = false)
public class MetricDefinitionPersistenceConfiguration {

    static final String MIGRATION = "metric_definitions_and_endpoints";

    /** What the migration creates. Constants: the query below is built from these and from nothing a caller supplies. */
    static final List<String> TABLES = List.of("package_version_digest", "metric_definition", "metric_restart_signal",
            "placement_metrics_endpoint", "generation_active_metrics");
    static final List<String> TRIGGERS = List.of("metric_definition_digest", "metric_restart_signal_digest");

    /** One row per object found, as {@code table x} or {@code trigger y}: the check compares NAMES, never a count. */
    static final String READINESS_QUERY = "SELECT 'table ' || table_name FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name IN (" + quoted(TABLES) + ")"
            + " UNION ALL SELECT 'trigger ' || t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sm_data' AND NOT t.tgisinternal"
            + " AND t.tgname IN (" + quoted(TRIGGERS) + ")";

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
    }

    /** What the schema lacks, as {@code table x} / {@code trigger y}; empty when this build's shape is all there. */
    static List<String> missing(DataSource smDataDataSource) {
        Set<String> found = new HashSet<>();
        try (Connection c = smDataDataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(READINESS_QUERY)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("sm_data readiness could not be checked for the " + MIGRATION
                    + " migration (SQLSTATE " + e.getSQLState() + ")");
        }
        List<String> missing = new ArrayList<>();
        TABLES.forEach(t -> missing.add("table " + t));
        TRIGGERS.forEach(t -> missing.add("trigger " + t));
        missing.removeAll(found);
        return missing;
    }

    static void verify(DataSource smDataDataSource) {
        List<String> missing = missing(smDataDataSource);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("sm_data is not migrated to the shape this build expects (the " + MIGRATION
                    + " migration is missing: " + String.join(", ", missing) + "): run the migration step "
                    + "- SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
        }
    }

    @Bean
    InitializingBean metricDefinitionSchemaReadiness(DataSource smDataDataSource) {
        return () -> verify(smDataDataSource);
    }

    @Bean
    @DependsOn("metricDefinitionSchemaReadiness")
    JdbcMetricDefinitionRepository metricDefinitions(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcMetricDefinitionRepository(smDataJdbc);
    }
}
