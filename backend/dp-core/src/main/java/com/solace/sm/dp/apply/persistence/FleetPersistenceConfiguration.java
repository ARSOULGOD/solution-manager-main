package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.fleet.FleetRows;
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
 * The persistence port for {@code fleet_action} and {@code fleet_member} (plan 010 T11.2), with its OWN readiness check for
 * its OWN tables, so no task edits another area's expected-table list. Imported by {@code FleetConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class FleetPersistenceConfiguration {

    /** The two tables V014 creates for this area; a literal, nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name IN ('fleet_action', 'fleet_member')";

    static final int EXPECTED_TABLES = 2;

    @Bean
    InitializingBean fleetSchemaReadiness(DataSource smDataDataSource) {
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
                        + found + " of " + EXPECTED_TABLES + " fleet tables): run the migration step "
                        + "- SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("fleetSchemaReadiness")
    FleetRows fleetRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcFleetRepository(smDataJdbc);
    }
}
