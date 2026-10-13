package com.solace.sm.cp.persistence;

import com.solace.sm.cp.governance.GovernanceRepository;
import com.solace.sm.cp.governance.InstanceRetirementRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The governance repository, beside {@link CatalogPersistenceConfiguration} for the same reason: a {@code @Bean}
 * method taking a {@link NamedParameterJdbcTemplate} must itself reside in a persistence package
 * ({@code jdbcIsConfinedToPersistencePackages}).
 */
@Configuration(proxyBeanMethods = false)
public class GovernancePersistenceConfiguration {

    @Bean
    GovernanceRepository governanceRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcGovernanceRepository(smControlJdbc);
    }

    @Bean
    InstanceRetirementRepository instanceRetirementRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcInstanceRetirementRepository(smControlJdbc);
    }
}
