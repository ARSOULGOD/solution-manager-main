package com.solace.sm.cp.persistence;

import com.solace.sm.cp.hierarchy.HierarchyWriteRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The hierarchy write repository over the {@code sm_control} runtime template (plan 013 T2.2), in its own
 * configuration, for the reason {@link TargetPersistenceConfiguration} gives: a {@code @Bean} method taking a
 * {@link NamedParameterJdbcTemplate} must reside in a persistence package, and no task edits
 * {@link ControlPlanePersistenceConfiguration} for a repository.
 */
@Configuration(proxyBeanMethods = false)
public class HierarchyPersistenceConfiguration {

    @Bean
    HierarchyWriteRepository hierarchyWriteRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcHierarchyWriteRepository(smControlJdbc);
    }
}
