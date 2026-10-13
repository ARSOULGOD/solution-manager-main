package com.solace.sm.cp.audit;

import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The export audit over the append-only {@link AuditLog} (plan 011 P11-7.1; M-Q22 (7)).
 *
 * <p><b>How the result is expressed.</b> {@link AuditEntry.Outcome} is only {@code ALLOW} or {@code DENY}, which
 * is the AUTHORIZATION decision; an export that was allowed is {@code ALLOW}. How the stream ENDED goes in the
 * allow-listed detail key {@code new_status}: {@code completed}, {@code failed} or {@code cut} (the async timeout
 * or a client disconnect). The detail keys are exactly {@code correlation_id}, {@code count} (rows written) and
 * {@code new_status}, nothing else: no metric name, no selector, no range. The verb {@code metrics.export} is
 * {@code HIGH} consequence, so {@link AuditEntry#kindFor} classifies this row as high-consequence by the verb
 * itself, not by anything this class sets.
 *
 * <p><b>A refused export</b> ({@code 403}, {@code 422}, {@code 429}; OR-112 (6), {@code W4-04}) is one {@code DENY} row of the
 * same verb and resource, with the detail keys {@code correlation_id} and a fixed {@code reason_code}
 * ({@link RefusalReason#code}), and no {@code count} or {@code new_status}: no stream was opened.
 */
public final class AuditLogExportAudit implements ExportAudit {

    private final AuditLog auditLog;
    private final Clock clock;

    public AuditLogExportAudit(AuditLog auditLog, Clock clock) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId,
            long rows, ExportResult result) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(result, "result");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlation_id", correlationId);
        detail.put("count", rows);
        detail.put("new_status", result.status());
        auditLog.record(new AuditEntry(clock.instant(), actor, Permission.METRICS_EXPORT, ResourceKind.ORGANIZATION,
                organization.value(), organization, AuditEntry.Outcome.ALLOW, detail));
    }

    @Override
    public void refused(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId,
            RefusalReason reason) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(reason, "reason");
        // OR-112 (6), W4-04: the same verb and resource as record(), outcome DENY, and the two detail keys a refusal has
        // (both allow-listed by AuditEntry): the fixed reason_code and the correlation id that joins to the HTTP response.
        // No count: no stream was opened.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlation_id", correlationId);
        detail.put("reason_code", reason.code());
        auditLog.record(new AuditEntry(clock.instant(), actor, Permission.METRICS_EXPORT, ResourceKind.ORGANIZATION,
                organization.value(), organization, AuditEntry.Outcome.DENY, detail));
    }
}
