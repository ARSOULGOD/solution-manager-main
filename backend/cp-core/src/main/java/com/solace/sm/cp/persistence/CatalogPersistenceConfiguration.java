package com.solace.sm.cp.persistence;

import com.solace.sm.cp.catalog.CatalogRepository;
import com.solace.sm.cp.catalog.EntitlementRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The catalog's repositories. A separate configuration, beside {@link ControlPlanePersistenceConfiguration}, for
 * the reason that class gives for hosting repositories at all ({@code jdbcIsConfinedToPersistencePackages}: a
 * {@code @Bean} method taking a {@link NamedParameterJdbcTemplate} must itself reside in a persistence package) -
 * and so two parallel tasks adding their repositories never edit the same file.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogPersistenceConfiguration {

    @Bean
    CatalogRepository catalogRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcCatalogRepository(smControlJdbc);
    }

    @Bean
    EntitlementRepository entitlementRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcEntitlementRepository(smControlJdbc);
    }
}
