package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The export's repository (plan 011 P11-6.2), beside {@link RollupPersistenceConfiguration}. It waits for the
 * {@code sm_metrics} readiness check by bean name. JDBC stays in this package; the service is
 * {@code MetricsExportConfiguration}'s and is built from {@code ExportRows}. Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class ExportPersistenceConfiguration {

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcMetricsExportRepository metricsExportRepository(NamedParameterJdbcTemplate smDataJdbc,
            PlatformTransactionManager smDataTransactionManager) {
        return new JdbcMetricsExportRepository(smDataJdbc, smDataTransactionManager);
    }
}
