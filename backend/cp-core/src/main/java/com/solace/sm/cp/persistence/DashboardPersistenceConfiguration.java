package com.solace.sm.cp.persistence;

import com.solace.sm.cp.audit.AuditLog;
import com.solace.sm.cp.dashboard.DashboardRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The dashboard repository and the FIRST PRODUCTION {@link AuditLog} bean (plan 011 P11-7.1), over the
 * {@code sm_control} runtime template. In {@code cp.persistence} because a {@code @Bean} method taking a
 * {@link NamedParameterJdbcTemplate} references {@code org.springframework.jdbc}, which
 * {@code jdbcIsConfinedToPersistencePackages} confines to this package. The tables' presence is already proved by
 * {@link ControlPlanePersistenceConfiguration}'s readiness check, which the template this takes depends on.
 *
 * <p>⛔ <b>There is exactly one {@link AuditLog} bean, and it is this one.</b> A second would make every by-type
 * consumer ambiguous; {@code AuditLogExportAuditIT.exactlyOneProductionAuditLogBeanExists} pins it.
 */
@Configuration(proxyBeanMethods = false)
public class DashboardPersistenceConfiguration {

    @Bean
    DashboardRepository dashboardRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcDashboardRepository(smControlJdbc);
    }

    @Bean
    AuditLog auditLog(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
        return new JdbcAuditLog(smControlJdbc);
    }
}
