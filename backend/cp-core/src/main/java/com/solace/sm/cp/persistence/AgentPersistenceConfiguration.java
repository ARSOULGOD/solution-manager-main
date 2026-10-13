package com.solace.sm.cp.persistence;

import com.solace.sm.cp.agent.AgentAuditContext;
import com.solace.sm.cp.identity.SystemAuditContext;
import com.solace.sm.cp.agent.AgentRegistry;
import com.solace.sm.cp.authz.ScopeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The agent registry and the agent-actor audit context over the {@code sm_control} runtime template (plan 015 T2.2), in
 * their own configuration beside {@code TargetPersistenceConfiguration}: the JDBC types stay in this package.
 */
@Configuration(proxyBeanMethods = false)
public class AgentPersistenceConfiguration {

    @Bean
    AgentRegistry agentRegistry(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAgentRegistry(smControlJdbc);
    }

    /** The canonical-scope store token issue mints the environment's scope row with (see AgentEnrolmentService). */
    @Bean
    ScopeStore agentScopeStore(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcScopeStore(smControlJdbc);
    }

    @Bean
    AgentAuditContext agentAuditContext(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAgentAuditContext(smControlJdbc);
    }

    /** V022 (OR-140): the system-actor form, used by the scheduled enrolment sweep for its two control-plane writes. */
    @Bean
    SystemAuditContext systemAuditContext(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcSystemAuditContext(smControlJdbc);
    }
}
