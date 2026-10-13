package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.schedule.ScheduleRows;
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
 * The persistence port for {@code apply_schedule} (plan 012 T7), with its OWN readiness check for what V025 adds, so no
 * task edits another area's expected-table list. Imported by {@code ApplyScheduleConfiguration}.
 *
 * <p>Two things must be there or the new binary must not go Ready: the table (V025 creates it) and its state-shape CHECK.
 * The second matters on its own: a schema someone half-applied would pass the first check and then fail at the first
 * schedule with a constraint violation. The runtime role reads the catalog, which needs no grant.
 */
@Configuration(proxyBeanMethods = false)
public class ApplySchedulePersistenceConfiguration {

    /** The table V025 creates and the CHECK that holds its state machine's shape; literals, nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT (SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name = 'apply_schedule')"
            + " + (SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sm_data'"
            + " AND c.relname = 'apply_schedule' AND k.conname = 'apply_schedule_state_shape')";

    static final int EXPECTED = 2;

    @Bean
    InitializingBean applyScheduleSchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects (found "
                        + found + " of " + EXPECTED + " parts of the scheduled-apply schema, V025): run the migration "
                        + "step - SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("applyScheduleSchemaReadiness")
    ScheduleRows scheduleRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcApplyScheduleRepository(smDataJdbc);
    }
}
