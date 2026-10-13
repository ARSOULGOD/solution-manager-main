package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigSecretService;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ConfigSecretService} as a bean (plan 010 T10.1). Separate from {@link ConfigRecordConfiguration} on purpose: it
 * needs the secret store, which a context that wires only the config of record (and its integration tests) does not
 * have. The launcher reaches it through the DP aggregator, beside {@code SecretConfiguration}, which supplies the store
 * and the keyed {@link ConfigHasher}, and {@code ConfigPersistenceConfiguration}, which supplies the rows.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigSecretConfiguration {

    @Bean
    ConfigSecretService configSecretService(TransactionTemplate smDataTransactions, ConfigGenerationRows rows,
            SecretStore secretStore, ConfigHasher configHasher, Retention configRetention,
            @Qualifier("smDataClock") ObjectProvider<Clock> clock, MetricDeclarationRows metricDeclarationRows) {
        return new ConfigSecretServiceImpl(smDataTransactions, rows, secretStore, configHasher, configRetention,
                clock.getIfAvailable(Clock::systemUTC), metricDeclarationRows);
    }
}
