package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.health.StartObservationRows;
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
 * The persistence ports for {@code placement_health} (plan 010 T7.2) and {@code placement_start_observation} (OR-112 (1),
 * V018), with their OWN readiness check for their OWN tables, so no task edits another area's expected-table list.
 * Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class HealthPersistenceConfiguration {

    /** The tables V012 and V018 create for this area; a literal, nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name IN ('placement_health', 'placement_start_observation')";

    static final int EXPECTED_TABLES = 2;

    @Bean
    InitializingBean healthSchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects (found "
                        + found + " of " + EXPECTED_TABLES + " health tables): run the migration step "
                        + "- SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("healthSchemaReadiness")
    StartObservationRows startObservationRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcStartObservationRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("healthSchemaReadiness")
    PlacementHealthRows placementHealthRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcPlacementHealthRepository(smDataJdbc);
    }
}
