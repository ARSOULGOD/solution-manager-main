package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.AssignmentRepository;
import com.solace.sm.cp.identity.PrincipalAdministrationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The user- and assignment-administration repositories over the {@code sm_control} runtime template (plan 013 T3.1), in
 * their own configuration for the reason {@link HierarchyPersistenceConfiguration} gives: a {@code @Bean} method taking
 * a {@link NamedParameterJdbcTemplate} must reside in a persistence package, and no task edits
 * {@link ControlPlanePersistenceConfiguration} for a repository.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityAdministrationPersistenceConfiguration {

    @Bean
    PrincipalAdministrationRepository principalAdministrationRepository(
            @Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcPrincipalAdministrationRepository(smControlJdbc);
    }

    @Bean
    AssignmentRepository assignmentRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAssignmentRepository(smControlJdbc);
    }
}
