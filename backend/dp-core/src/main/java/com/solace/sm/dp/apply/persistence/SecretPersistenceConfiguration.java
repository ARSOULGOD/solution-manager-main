package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.secret.CanaryRows;
import com.solace.sm.dp.secret.SecretRows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The two persistence ports of the DP secret store, over the runtime credential's template. Imported by the
 * launcher beside {@code SecretConfiguration} (plan 010 T2.6); kept apart from
 * {@link ApplyPersistenceConfiguration} so the secret store's wiring does not edit the apply graph's.
 */
@Configuration(proxyBeanMethods = false)
public class SecretPersistenceConfiguration {

    @Bean
    SecretRows secretRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcSecretRepository(smDataJdbc);
    }

    @Bean
    CanaryRows canaryRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcKekCanaryRepository(smDataJdbc);
    }
}
