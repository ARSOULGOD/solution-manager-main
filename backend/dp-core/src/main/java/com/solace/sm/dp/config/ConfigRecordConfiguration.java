package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The config of record as beans (plan 010 T5.2). Not auto-configured: the launcher reaches it through the DP
 * aggregator (T2.6), together with {@code ConfigPersistenceConfiguration}, which supplies
 * {@link ConfigGenerationRows} and its own readiness check, and with the secret store's configuration, which
 * supplies the keyed {@link ConfigHasher}.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigRecordConfiguration {

    /** {@code sm.dp.config.retained-generations}, default 10, minimum 1 (validated by {@link Retention}). */
    @Bean
    Retention configRetention(
            @Value("${sm.dp.config.retained-generations:" + Retention.DEFAULT + "}") int retainedGenerations) {
        return new Retention(retainedGenerations);
    }

    @Bean
    ConfigRecordService configRecordService(TransactionTemplate smDataTransactions, ConfigGenerationRows rows,
            ConfigHasher configHasher, Retention configRetention, @Qualifier("smDataClock") ObjectProvider<Clock> clock,
            RollbackTargetRows rollbackTargetRows, MetricDeclarationRows metricDeclarationRows) {
        return new ConfigRecordServiceImpl(smDataTransactions, rows, configHasher, configRetention,
                clock.getIfAvailable(Clock::systemUTC), rollbackTargetRows, metricDeclarationRows);
    }
}
