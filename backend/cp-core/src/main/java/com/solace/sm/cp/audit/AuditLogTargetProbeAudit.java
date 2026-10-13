package com.solace.sm.cp.audit;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The scan audit over the append-only {@link AuditLog} (plan 013 T1.4; D-REG section 9), shaped as {@link
 * AuditLogExportAudit} is: the verb is {@code target.create} ({@code HIGH}, so the row is high-consequence by the verb
 * itself), the resource is the scope node it was authorized at, and the detail keys are exactly {@code correlation_id}
 * plus either {@code new_status} (an {@code ALLOW} that ended some way) or {@code reason_code} (a {@code DENY}). No
 * host, port, key or fingerprint can reach it: its port has no parameter for one. Since plan 013 T1.5 it also records a
 * connection test: {@code target.use} on the target itself, the correlation id and the furthest step reached.
 */
public final class AuditLogTargetProbeAudit implements TargetProbeAudit {

    private static final Set<ResourceKind> SCOPES = Set.of(ResourceKind.ORGANIZATION, ResourceKind.SUB_ORGANIZATION,
            ResourceKind.ENVIRONMENT);

    private final AuditLog auditLog;
    private final Clock clock;

    public AuditLogTargetProbeAudit(AuditLog auditLog, Clock clock) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void scanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, ResourceKind scopeKind,
            UUID scopeId, ScanResult result) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(result, "result");
        if (!SCOPES.contains(Objects.requireNonNull(scopeKind, "scopeKind"))) {
            throw new IllegalArgumentException("a target is owned by an organization, an organization unit or an environment");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlation_id", origin.correlationId());
        detail.put(result.denied() ? "reason_code" : "new_status", result.code());
        auditLog.record(new AuditEntry(clock.instant(), actor, Permission.TARGET_CREATE, scopeKind, scopeId,
                organization, result.denied() ? AuditEntry.Outcome.DENY : AuditEntry.Outcome.ALLOW, detail));
    }

    @Override
    public void tested(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId,
            TestResult result) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(result, "result");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlation_id", origin.correlationId());
        detail.put(result.denied() ? "reason_code" : "new_status", result.code());
        auditLog.record(new AuditEntry(clock.instant(), actor, Permission.TARGET_USE, ResourceKind.DEPLOYMENT_TARGET,
                targetId, organization, result.denied() ? AuditEntry.Outcome.DENY : AuditEntry.Outcome.ALLOW, detail));
    }

    @Override
    public void rescanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId,
            ScanResult result) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(result, "result");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlation_id", origin.correlationId());
        detail.put(result.denied() ? "reason_code" : "new_status", result.code());
        auditLog.record(new AuditEntry(clock.instant(), actor, Permission.TARGET_EDIT, ResourceKind.DEPLOYMENT_TARGET,
                targetId, organization, result.denied() ? AuditEntry.Outcome.DENY : AuditEntry.Outcome.ALLOW, detail));
    }
}
