package com.solace.sm.dp.metrics.export;

import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The streaming export (plan 011 P11-6.2), built from interface types only: {@link ExportRows} is
 * {@code ExportPersistenceConfiguration}'s repository, and the {@link MetricsDbBudget} is the ONE bean
 * {@code MetricsConfiguration} registers. This class registers no budget, no pool and no executor, so "4" means four across
 * queries and exports together. Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExportProperties.class)
public class MetricsExportConfiguration {

    @Bean
    MetricsExportService metricsExportService(ExportRows exportRows, MetricsDbBudget metricsDbBudget, ExportProperties properties) {
        return new MetricsExportServiceImpl(exportRows, metricsDbBudget, properties);
    }
}
