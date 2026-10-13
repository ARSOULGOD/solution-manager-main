package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.LadderRows;
import com.solace.sm.dp.config.RollbackTargetRows;
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
 * The persistence port of the config of record, over the runtime credential's template, with its OWN readiness
 * check for its OWN tables (plan 010 "Single-owner files": readiness is per configuration, so no task edits
 * {@code ApplyPersistenceConfiguration}'s {@code EXPECTED_TABLES}). Imported by the launcher through the DP
 * aggregator beside {@code ConfigRecordConfiguration} (T2.6).
 */
@Configuration(proxyBeanMethods = false)
public class ConfigPersistenceConfiguration {

    /**
     * What V010 adds (the table, and the two head columns on {@code instance_state}) and what V013 adds (OR-96's
     * {@code designer_layout}). A literal; nothing in it is caller-supplied, so there is no bind parameter to omit.
     */
    static final String READINESS_QUERY =
            "SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_data'"
                    + " AND table_name IN ('config_generation', 'designer_layout'))"
                    + " + (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_data'"
                    + " AND table_name = 'instance_state' AND column_name IN ('last_generation', 'reached_rung'))";

    /** The two tables plus the two columns. */
    static final int EXPECTED = 4;

    @Bean
    InitializingBean configGenerationReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED) {
                throw new IllegalStateException("sm_data is not migrated to the config-of-record shape this build "
                        + "expects (found " + found + " of " + EXPECTED + " objects): run the migration step "
                        + "(SmDataMigrationMain) before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("configGenerationReadiness")
    ConfigGenerationRows configGenerationRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcConfigGenerationRepository(smDataJdbc);
    }

    /** Which generations an operator rollback may copy forward (T8.1): a read of the journal, no lock. */
    @Bean
    @DependsOn("configGenerationReadiness")
    RollbackTargetRows rollbackTargetRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcRollbackTargetRepository(smDataJdbc);
    }

    /** The ladder's journal read and {@code reached_rung} write (T6.5). Same tables as the rest of this area. */
    @Bean
    @DependsOn("configGenerationReadiness")
    LadderRows ladderRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcLadderRepository(smDataJdbc);
    }
}
