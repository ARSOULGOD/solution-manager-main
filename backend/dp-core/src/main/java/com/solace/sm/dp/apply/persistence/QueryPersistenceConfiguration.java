package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The metrics query's {@code Jdbc*} read repository (plan 011 P11-6.1), over the runtime credential's template. It waits
 * for the {@code sm_metrics} readiness check by bean name, as the rollups' repositories do. JDBC stays in this package;
 * the service is {@code MetricsQueryConfiguration}'s. Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class QueryPersistenceConfiguration {

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcMetricsQueryRepository metricsQueryRepository(NamedParameterJdbcTemplate smDataJdbc,
            PlatformTransactionManager smDataTransactionManager) {
        return new JdbcMetricsQueryRepository(smDataJdbc, smDataTransactionManager);
    }
}
