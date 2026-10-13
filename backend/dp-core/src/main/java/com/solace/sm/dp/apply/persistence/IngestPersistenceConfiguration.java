package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The ingest path's {@code Jdbc*} repositories (plan 011 P11-3.1), over the runtime credential's template. They touch
 * {@code sm_metrics} and read {@code sm_data}'s placement, instance and metric-definition rows, so each waits for BOTH
 * readiness checks ({@link MetricsPersistenceConfiguration}'s and {@link MetricDefinitionPersistenceConfiguration}'s,
 * by bean name): a repository that met an unmigrated schema would report a broken query instead of "run the migration".
 * The service that uses them is {@code MetricsIngestConfiguration}'s; JDBC stays in this package.
 */
@Configuration(proxyBeanMethods = false)
public class IngestPersistenceConfiguration {

    @Bean
    @DependsOn({"smMetricsSchemaReadiness", "metricDefinitionSchemaReadiness"})
    JdbcIngestContextRepository ingestContextRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcIngestContextRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcSeriesRepository seriesRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcSeriesRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcRawSampleRepository rawSampleRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcRawSampleRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcIngestCounterRepository ingestCounterRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcIngestCounterRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("smMetricsSchemaReadiness")
    JdbcRestartMarkerRepository restartMarkerRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcRestartMarkerRepository(smDataJdbc);
    }
}
