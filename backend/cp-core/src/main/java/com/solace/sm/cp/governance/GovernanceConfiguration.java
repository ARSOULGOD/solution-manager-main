package com.solace.sm.cp.governance;

import com.solace.sm.cp.agent.AgentAuditContext;
import com.solace.sm.cp.api.governance.AgentInstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.persistence.GovernancePersistenceConfiguration;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wires the instance governance registration (plan 010 T4.3) and its retirement (plan 012 T4) and exposes each BY ITS
 * {@code cp-api} TYPE, so the gateway depends on {@code cp-api} only ({@code I7}). Imported by
 * {@code ControlPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(GovernancePersistenceConfiguration.class)
public class GovernanceConfiguration {

    @Bean
    InstanceGovernance instanceGovernance(GovernanceRepository governanceRepository, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions) {
        return new GovernanceService(governanceRepository, auditContext, smControlTransactions, UUID::randomUUID);
    }

    /**
     * Plan 015 T4.2: the agent-actor form of the same registration, exposed by its own {@code cp-api} type. A separate class that does NOT implement
     * {@code InstanceGovernance}, so each bean is of exactly one {@code cp-api} type (an {@code InstanceGovernance} post-processor
     * or injection point never meets the agent form). A context with
     * no agent audit context (a narrow one without the agent gateway's control-plane half) builds one whose
     * {@code registerForAgent} refuses.
     */
    @Bean
    AgentInstanceGovernance agentInstanceGovernance(GovernanceRepository governanceRepository, AuditContext auditContext,
            ObjectProvider<AgentAuditContext> agentAuditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions) {
        return new AgentInstanceGovernanceService(new GovernanceService(governanceRepository, auditContext,
                agentAuditContext.getIfAvailable(), smControlTransactions, UUID::randomUUID));
    }

    @Bean
    InstanceRetirementGovernance instanceRetirementGovernance(InstanceRetirementRepository retirementRepository,
            AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions) {
        return new InstanceRetirementService(retirementRepository, auditContext, smControlTransactions,
                Clock.systemUTC());
    }
}
