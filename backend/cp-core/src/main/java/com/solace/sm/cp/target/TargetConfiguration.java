package com.solace.sm.cp.target;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.persistence.TargetPersistenceConfiguration;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Target governance as a bean (plan 010 T2.7). One bean answers both cp-api ports ({@code TargetDirectory},
 * {@code TargetRegistration}); a consumer injects the interface it needs, and exactly one bean matches each. Imported
 * by {@code ControlPlaneConfiguration}. No bean here references a JDBC type: the repository comes from
 * {@link TargetPersistenceConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(TargetPersistenceConfiguration.class)
public class TargetConfiguration {

    @Bean
    TargetService targetService(TargetRepository targetRepository, AuthorizationService authorizationService,
            AuditContext auditContext, @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new TargetService(targetRepository, authorizationService, auditContext, smControlTransactions,
                smControlClock);
    }
}
