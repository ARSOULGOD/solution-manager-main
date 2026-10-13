package com.solace.sm.dp.metrics.ingest;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingest service (plan 011 P11-3.1), the one {@code TelemetryIngestPort} bean, built from interface types only: the {@code Jdbc*} beans it needs come from
 * {@code IngestPersistenceConfiguration}, so this class references no JDBC type. Imported by
 * {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestProperties.class)
public class MetricsIngestConfiguration {

    @Bean(destroyMethod = "close")
    IngestService telemetryIngestService(TransactionTemplate smDataTransactions, IngestRows.Placements placements,
            IngestRows.Series series, IngestRows.Raw raw, IngestRows.Counters counters, IngestRows.Markers markers,
            IngestProperties properties, @Qualifier("smDataClock") Clock smDataClock) {
        return new IngestService(smDataTransactions, placements, series, raw, counters, markers, properties,
                smDataClock);
    }
}
