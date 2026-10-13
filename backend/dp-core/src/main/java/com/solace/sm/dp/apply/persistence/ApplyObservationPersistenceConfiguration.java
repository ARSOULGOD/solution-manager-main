package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.service.ApplyObservationRows;
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
 * The journal read port of {@code observeApply} (plan 010 T6.3), over the runtime credential's template. It reads
 * tables {@code ApplyPersistenceConfiguration}'s readiness check already requires, plus the one column {@code OR-108} (1)
 * added ({@code apply_attempt.pre_image_change}, {@code V015}), which this configuration checks for itself: the engine
 * writes it and this port reads it, and a database without it would fail on the first rollback refusal rather than at start.
 */
@Configuration(proxyBeanMethods = false)
public class ApplyObservationPersistenceConfiguration {

    /** What V015 adds: one column. A literal; nothing in it is caller-supplied. */
    static final String READINESS_QUERY =
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_data'"
                    + " AND table_name = 'apply_attempt' AND column_name = 'pre_image_change'";

    static final int EXPECTED = 1;

    @Bean
    InitializingBean attemptPreImageChangeReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED) {
                throw new IllegalStateException("sm_data is not migrated to the attempt shape this build expects "
                        + "(found " + found + " of " + EXPECTED + " objects): run the migration step "
                        + "(SmDataMigrationMain) before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("attemptPreImageChangeReadiness")
    ApplyObservationRows applyObservationRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcApplyObservationRepository(smDataJdbc);
    }
}
