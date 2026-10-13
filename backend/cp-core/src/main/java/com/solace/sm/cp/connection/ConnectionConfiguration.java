package com.solace.sm.cp.connection;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.persistence.ConnectionPersistenceConfiguration;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Connection governance as a bean (plan 013 T7 fix: this bean was never registered, so no application
 * context that includes {@code console-gateway}'s {@code ConnectionController}/{@code
 * ConnectionManagementController} could start — {@code ConnectionOrchestrator} needs {@code
 * ConnectionDirectory}/{@code ConnectionRegistry} and nothing in {@code cp-core} supplied them). Shaped exactly
 * like {@link com.solace.sm.cp.target.TargetConfiguration}: one bean answers both cp-api ports ({@code
 * ConnectionDirectory}, {@code ConnectionRegistry}); a consumer injects the interface it needs, and exactly one
 * bean matches each. No bean here references a JDBC type: the repository comes from
 * {@link ConnectionPersistenceConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(ConnectionPersistenceConfiguration.class)
public class ConnectionConfiguration {

    @Bean
    ConnectionService connectionService(ConnectionRepository connectionRepository,
            AuthorizationService authorizationService, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new ConnectionService(connectionRepository, authorizationService, auditContext,
                smControlTransactions, smControlClock);
    }
}
