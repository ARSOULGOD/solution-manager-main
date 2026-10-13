package com.solace.sm.cp.persistence;

import com.solace.sm.cp.target.TargetRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The target repository over the {@code sm_control} runtime template (plan 010 T2.7), in its own configuration so no
 * task edits {@link ControlPlanePersistenceConfiguration} (single-owner, T2.0). The table's presence is already
 * proved by that class's readiness check, which the template this takes depends on.
 */
@Configuration(proxyBeanMethods = false)
public class TargetPersistenceConfiguration {

    @Bean
    TargetRepository targetRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcTargetRepository(smControlJdbc);
    }
}
