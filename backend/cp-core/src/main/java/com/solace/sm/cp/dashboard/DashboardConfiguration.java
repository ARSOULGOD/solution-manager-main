package com.solace.sm.cp.dashboard;

import com.solace.sm.cp.identity.AuditContext;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The dashboard service as a bean (plan 011 P11-7.1). No bean here references a JDBC type: the repository comes from
 * {@code DashboardPersistenceConfiguration}, which {@code ControlPlaneConfiguration} imports beside this class. One
 * bean answers the cp-api {@code DashboardStore} port.
 */
@Configuration(proxyBeanMethods = false)
public class DashboardConfiguration {

    @Bean
    DashboardDocumentValidator dashboardDocumentValidator() {
        return new DashboardDocumentValidator();
    }

    @Bean
    DefaultLayerDeriver defaultLayerDeriver() {
        return new DefaultLayerDeriver();
    }

    @Bean
    DashboardService dashboardService(DashboardRepository dashboardRepository, DashboardDocumentValidator validator,
            DefaultLayerDeriver defaultLayerDeriver, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new DashboardService(dashboardRepository, validator, defaultLayerDeriver, auditContext,
                smControlTransactions, smControlClock);
    }
}
