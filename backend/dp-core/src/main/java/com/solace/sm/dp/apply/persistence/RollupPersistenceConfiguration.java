package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The rollups' and the disk alarm's {@code Jdbc*} repositories (plan 011 P11-3.2). They wait for the {@code sm_metrics}
 * readiness check by bean name. {@link JdbcPartitionRunwayRepository} (P11-2.1's bean) is the runway keeper's source and
 * is not re-registered here. JDBC stays in this package; the services are {@code MetricsRollupConfiguration}'s.
 */
@Configuration(proxyBeanMethods = false)
public class RollupPersistenceConfiguration {

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcRollupRepository rollupRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcRollupRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcMetricsDiskRepository metricsDiskRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcMetricsDiskRepository(smDataJdbc);
    }
}
