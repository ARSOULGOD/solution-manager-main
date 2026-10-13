package com.solace.sm.cp.audit;

import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway-facing audit ports as beans: the export audit (plan 011 P11-7.1) and, since plan 013 T1.4, the scan audit.
 * Both build on the one production {@link AuditLog} bean, which {@code DashboardPersistenceConfiguration} registers (it
 * must live in {@code cp.persistence}); this class registers no {@link AuditLog} of its own. The scan audit is a second
 * bean here, not a new configuration class, so no aggregator needs a new {@code @Import} line (the control plane's is
 * single-writer in a wave that has more than one lane editing it).
 */
@Configuration(proxyBeanMethods = false)
public class ExportAuditConfiguration {

    @Bean
    ExportAudit exportAudit(AuditLog auditLog, @Qualifier("smControlClock") Clock smControlClock) {
        return new AuditLogExportAudit(auditLog, smControlClock);
    }

    @Bean
    TargetProbeAudit targetProbeAudit(AuditLog auditLog, @Qualifier("smControlClock") Clock smControlClock) {
        return new AuditLogTargetProbeAudit(auditLog, smControlClock);
    }
}
