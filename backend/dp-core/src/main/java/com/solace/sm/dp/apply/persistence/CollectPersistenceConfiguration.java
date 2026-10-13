package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The collector's {@code Jdbc*} reads (plan 011 P11-4.3), over the runtime credential's template: the newest generation
 * that reached {@code restart-observed} (from the apply journal) and the metric-definition reads the plan builder needs
 * (delegating to {@code JdbcMetricDefinitionRepository}, which P11-2.2's own configuration registers behind its readiness
 * check). JDBC stays in this package; the services are {@code MetricsCollectConfiguration}'s. Imported by
 * {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class CollectPersistenceConfiguration {

    @Bean
    JdbcRestartObservedGenerationRepository restartObservedGenerations(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcRestartObservedGenerationRepository(smDataJdbc);
    }

    @Bean
    MetricDefinitionCollectionRows metricDefinitionCollectionRows(JdbcMetricDefinitionRepository metricDefinitions) {
        return new MetricDefinitionCollectionRows(metricDefinitions);
    }
}
