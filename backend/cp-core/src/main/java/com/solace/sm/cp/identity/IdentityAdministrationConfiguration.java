package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.PrincipalAdministration;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.persistence.IdentityAdministrationPersistenceConfiguration;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wires user and assignment administration (plan 013 T3.1) and exposes them BY THEIR {@code cp-api} TYPE, so the gateway
 * depends on {@code cp-api} only ({@code I7}). Imported by {@code ControlPlaneConfiguration}. No bean here references a
 * JDBC type: the repositories come from {@link IdentityAdministrationPersistenceConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(IdentityAdministrationPersistenceConfiguration.class)
public class IdentityAdministrationConfiguration {

    @Bean
    PrincipalAdministration principalAdministration(PrincipalAdministrationRepository principalAdministrationRepository,
            SessionRepository sessionRepository, CredentialHasher credentialHasher,
            AuthorizationService authorizationService, AuthorizationGrantRepository authorizationGrantRepository,
            AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new PrincipalAdministrationService(principalAdministrationRepository, sessionRepository,
                credentialHasher, authorizationService, authorizationGrantRepository, auditContext, smControlTransactions, smControlClock,
                UUID::randomUUID);
    }

    @Bean
    AssignmentAdministration assignmentAdministration(AssignmentRepository assignmentRepository,
            PrincipalAdministrationRepository principalAdministrationRepository,
            AuthorizationService authorizationService, ReachAuthorization reachAuthorization,
            AuthorizationGrantRepository authorizationGrantRepository, ScopePathResolver scopePathResolver,
            AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions) {
        return new AssignmentAdministrationService(assignmentRepository, principalAdministrationRepository,
                authorizationService, reachAuthorization, authorizationGrantRepository, scopePathResolver,
                auditContext, smControlTransactions, UUID::randomUUID);
    }
}
