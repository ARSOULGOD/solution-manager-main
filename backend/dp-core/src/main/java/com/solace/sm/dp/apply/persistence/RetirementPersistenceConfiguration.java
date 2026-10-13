package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.retirement.RetirementRows;
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
 * The persistence port for {@code instance_retirement} (plan 012 T3), with its OWN readiness check for what V023 adds, so
 * no task edits another area's expected-table list. Imported by {@code RetirementConfiguration}.
 *
 * <p>Two things must be there or the new binary must not go Ready: the table (V023 creates it) and the widened lifecycle
 * vocabulary. The second matters on its own: on a V022 schema the table is absent too, but a schema that someone half-applied
 * would pass the first check and then fail at the first {@code active -> retiring} with a CHECK violation. The runtime role
 * reads {@code pg_get_constraintdef} through the catalog, which needs no grant.
 */
@Configuration(proxyBeanMethods = false)
public class RetirementPersistenceConfiguration {

    /** The table V023 creates, and the lifecycle CHECK it widens; literals, nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT (SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name = 'instance_retirement')"
            + " + (SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sm_data'"
            + " AND c.relname = 'instance_state' AND k.conname = 'instance_lifecycle'"
            + " AND pg_get_constraintdef(k.oid) LIKE '%retiring%')";

    static final int EXPECTED = 2;

    @Bean
    InitializingBean retirementSchemaReadiness(DataSource smDataDataSource) {
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
                        + found + " of " + EXPECTED + " parts of the undeclare schema, V023): run the migration step "
                        + "- SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("retirementSchemaReadiness")
    RetirementRows retirementRows(NamedParameterJdbcTemplate smDataJdbc, ApplyScopeRepository applyScopeRepository) {
        return new JdbcRetirementRepository(smDataJdbc, applyScopeRepository);
    }
}
