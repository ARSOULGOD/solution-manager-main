package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.persistence.HierarchyPersistenceConfiguration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wires the hierarchy writes (plan 013 T2.2) and exposes them BY THEIR {@code cp-api} TYPE, so the gateway depends on
 * {@code cp-api} only ({@code I7}). Imported by {@code ControlPlaneConfiguration}. No bean here references a JDBC type:
 * the repository comes from {@link HierarchyPersistenceConfiguration}. The reads ({@code HierarchyDirectory}) are wired,
 * as {@code OR-120} left them, in {@code ControlPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Import(HierarchyPersistenceConfiguration.class)
public class HierarchyConfiguration {

    @Bean
    HierarchyAdministration hierarchyAdministration(HierarchyWriteRepository hierarchyWriteRepository,
            AuthorizationService authorizationService, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions) {
        return new HierarchyAdministrationService(hierarchyWriteRepository, authorizationService, auditContext,
                smControlTransactions, UUID::randomUUID);
    }
}
