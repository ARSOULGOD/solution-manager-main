package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentAdministration;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.authz.ScopeStore;
import com.solace.sm.cp.config.AgentEnrolmentProperties;
import com.solace.sm.cp.config.AgentGatewayCaProperties;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.SystemAuditContext;
import com.solace.sm.cp.persistence.AgentPersistenceConfiguration;
import com.solace.sm.cp.target.TargetRepository;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The control plane's agent authority as beans (plan 015 T2.2), exposed by their cp-api interfaces. Imported by
 * {@code ControlPlaneConfiguration}. No bean here references a JDBC type: the registry comes from
 * {@link AgentPersistenceConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(AgentPersistenceConfiguration.class)
@EnableConfigurationProperties({AgentGatewayCaProperties.class, AgentEnrolmentProperties.class})
public class AgentConfiguration {

    /**
     * With {@code required} set, a missing or unreadable operator keystore fails STARTUP (fail closed at boot); unset, the CA
     * is built and every issuance refuses until a keystore is configured.
     */
    @Bean
    AgentCertificateAuthority agentCertificateAuthority(AgentGatewayCaProperties properties) {
        Pkcs12AgentCertificateAuthority authority = new Pkcs12AgentCertificateAuthority(properties);
        if (properties.isRequired()) {
            authority.verifyOperable();
        }
        return authority;
    }

    /** The lease-nonce HMAC key (a secret-key entry in the CA keystore); with {@code required}, a missing one fails startup too. */
    @Bean
    LeaseNonceSigner leaseNonceSigner(AgentGatewayCaProperties properties) {
        Pkcs12LeaseNonceSigner signer = new Pkcs12LeaseNonceSigner(properties);
        if (properties.isRequired()) {
            signer.verifyOperable();
        }
        return signer;
    }

    @Bean
    ControlPlaneAgentGateway controlPlaneAgentGateway(AgentRegistry registry,
            AgentCertificateAuthority certificateAuthority, LeaseNonceSigner leaseNonceSigner, TargetRepository targetRepository,
            AgentAuditContext agentAuditContext, AgentGatewayCaProperties properties,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new ControlPlaneAgentGatewayService(registry, certificateAuthority, leaseNonceSigner, targetRepository, agentAuditContext,
                smControlTransactions, smControlClock, properties.getConnectEndpoint());
    }

    @Bean
    AgentEnrolment agentEnrolment(AgentRegistry registry, TargetRepository targetRepository,
            ResourceScopeDirectory resourceScopeDirectory, ScopeStore scopeStore, AuthorizationService authorizationService, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock, AgentEnrolmentProperties enrolment) {
        // OR-136 (1): the lifetime is configuration; an invalid pair fails the context here
        return new AgentEnrolmentService(registry, targetRepository, resourceScopeDirectory, scopeStore, authorizationService, auditContext,
                smControlTransactions, smControlClock, new SecureRandom(), enrolment.getTokenDefaultValidity(),
                enrolment.getTokenMaxValidity());
    }

    /** The control plane's half of the scheduled sweep of an interrupted enrolment (plan 015 T3.5, T4.6, OR-140); the gateway's job composes it with the data plane. */
    @Bean
    AgentEnrolmentSweep agentEnrolmentSweep(AgentRegistry registry, TargetRepository targetRepository, SystemAuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new AgentEnrolmentSweepService(registry, targetRepository, auditContext, smControlTransactions, smControlClock);
    }

    /** The operator reads and the revoke of an agent's control-plane record (plan 015 T4.3). */
    @Bean
    AgentAdministration agentAdministration(AgentRegistry registry, ResourceScopeDirectory resourceScopeDirectory,
            AuthorizationService authorizationService, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new AgentAdministrationService(registry, resourceScopeDirectory, authorizationService, auditContext, smControlTransactions, smControlClock);
    }
}
