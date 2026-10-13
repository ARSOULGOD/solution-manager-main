package com.solace.sm.cp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit.ScanResult;
import com.solace.sm.cp.api.audit.TargetProbeAudit.TestResult;
import com.solace.sm.cp.api.authz.ConsequenceClass;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 013 T1.4: the scan's audit row, offline over a recording {@link AuditLog}. */
class AuditLogTargetProbeAuditTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private final List<AuditEntry> recorded = new ArrayList<>();
    private final AuditLogTargetProbeAudit audit = new AuditLogTargetProbeAudit(recorded::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID organization = UUID.randomUUID();
    private final PrincipalRef actor = new PrincipalRef(UUID.randomUUID());
    private final UUID correlation = UUID.randomUUID();
    private final RequestOrigin origin = new RequestOrigin("203.0.113.5", correlation);

    @Test
    void aCompletedScanIsOneAllowRowOfTheVerbThatAuthorizedIt() {
        UUID environment = UUID.randomUUID();

        audit.scanned(actor, origin, new TenantId(organization), ResourceKind.ENVIRONMENT, environment,
                ScanResult.COMPLETED);

        assertThat(recorded).hasSize(1);
        AuditEntry row = recorded.get(0);
        assertThat(row.verb()).isEqualTo(Permission.TARGET_CREATE);
        assertThat(row.verb().consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(AuditEntry.kindFor(row.verb())).isEqualTo(AuditKind.HIGH_CONSEQUENCE);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.ALLOW);
        assertThat(row.resourceKind()).isEqualTo(ResourceKind.ENVIRONMENT);
        assertThat(row.resourceId()).isEqualTo(environment);
        assertThat(row.tenant().value()).isEqualTo(organization);
        assertThat(row.actor()).isEqualTo(actor);
        assertThat(row.at()).isEqualTo(NOW);
        assertThat(row.detail()).containsEntry("correlation_id", correlation).containsEntry("new_status", "completed");
    }

    @Test
    void theDetailHoldsNothingButTheCorrelationIdAndTheFixedOutcome() {
        for (ScanResult result : ScanResult.values()) {
            recorded.clear();
            audit.scanned(actor, origin, new TenantId(organization), ResourceKind.ORGANIZATION, organization, result);

            assertThat(new TreeSet<>(recorded.get(0).detail().keySet()))
                    .as(result.name())
                    .containsExactlyInAnyOrder("correlation_id", result.denied() ? "reason_code" : "new_status");
        }
    }

    @Test
    void everyOutcomeCarriesItsFixedCode() {
        assertThat(ScanResult.UNREACHABLE.code()).isEqualTo("unreachable");
        assertThat(ScanResult.ADDRESS_REFUSED.code()).isEqualTo("address_refused");
        assertThat(ScanResult.COMPLETED.code()).isEqualTo("completed");
        assertThat(ScanResult.RATE_LIMITED.code()).isEqualTo("rate_limited");
    }

    @Test
    void aRateLimitedScanIsADenyRowWithAReasonCode() {
        audit.scanned(actor, origin, new TenantId(organization), ResourceKind.SUB_ORGANIZATION, UUID.randomUUID(),
                ScanResult.RATE_LIMITED);

        AuditEntry row = recorded.get(0);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.DENY);
        assertThat(row.detail()).containsEntry("reason_code", "rate_limited").doesNotContainKey("new_status");
    }

    @Test
    void aScopeDeeperThanAnEnvironmentIsRefused() {
        assertThatThrownBy(() -> audit.scanned(actor, origin, new TenantId(organization), ResourceKind.INSTANCE,
                UUID.randomUUID(), ScanResult.COMPLETED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(recorded).isEmpty();
    }

    // ---- plan 013 T1.5: testTargetConnection ----------------------------------------------------------------

    @Test
    void aConnectionTestIsOneAllowRowOfTargetUseOnTheTargetItself() {
        UUID target = UUID.randomUUID();

        audit.tested(actor, origin, new TenantId(organization), target, TestResult.PASSED);

        assertThat(recorded).hasSize(1);
        AuditEntry row = recorded.get(0);
        assertThat(row.verb()).isEqualTo(Permission.TARGET_USE);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.ALLOW);
        assertThat(row.resourceKind()).isEqualTo(ResourceKind.DEPLOYMENT_TARGET);
        assertThat(row.resourceId()).isEqualTo(target);
        assertThat(row.tenant().value()).isEqualTo(organization);
        assertThat(row.actor()).isEqualTo(actor);
        assertThat(row.at()).isEqualTo(NOW);
        assertThat(row.detail()).containsEntry("correlation_id", correlation).containsEntry("new_status", "passed")
                .hasSize(2);
    }

    @Test
    void everyTestOutcomeCarriesItsFixedCodeAndIsAnAllowRow() {
        assertThat(TestResult.PASSED.code()).isEqualTo("passed");
        assertThat(TestResult.DOCKER_UNAVAILABLE.code()).isEqualTo("docker_unavailable");
        assertThat(TestResult.NOT_AUTHENTICATED.code()).isEqualTo("not_authenticated");
        assertThat(TestResult.NOT_REACHED.code()).isEqualTo("not_reached");
        for (TestResult result : TestResult.values()) {
            if (result.denied()) {
                continue;                // a refusal is a DENY row: the next test
            }
            recorded.clear();
            audit.tested(actor, origin, new TenantId(organization), UUID.randomUUID(), result);
            assertThat(recorded.get(0).outcome()).as(result.name()).isEqualTo(AuditEntry.Outcome.ALLOW);
            assertThat(recorded.get(0).detail().get("new_status")).isEqualTo(result.code());
        }
    }

    @Test
    void aTestRefusedForRateIsOneDenyRowWithAReasonCodeAndNoOutcome() {
        UUID target = UUID.randomUUID();

        audit.tested(actor, origin, new TenantId(organization), target, TestResult.RATE_LIMITED);

        assertThat(recorded).hasSize(1);
        AuditEntry row = recorded.get(0);
        assertThat(row.verb()).isEqualTo(Permission.TARGET_USE);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.DENY);
        assertThat(row.resourceId()).isEqualTo(target);
        assertThat(row.detail()).containsEntry("correlation_id", correlation).containsEntry("reason_code", "rate_limited")
                .hasSize(2);
    }

    // ---- plan 013 T1.5: editTargetConnection's rescan --------------------------------------------------------

    @Test
    void aRescanForAnEditIsOneRowOfTargetEditOnTheTargetItselfNotOfTargetCreate() {
        UUID target = UUID.randomUUID();

        audit.rescanned(actor, origin, new TenantId(organization), target, ScanResult.UNREACHABLE);

        assertThat(recorded).hasSize(1);
        AuditEntry row = recorded.get(0);
        assertThat(row.verb()).isEqualTo(Permission.TARGET_EDIT);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.ALLOW);
        assertThat(row.resourceKind()).isEqualTo(ResourceKind.DEPLOYMENT_TARGET);
        assertThat(row.resourceId()).isEqualTo(target);
        assertThat(row.detail()).containsEntry("correlation_id", correlation).containsEntry("new_status", "unreachable")
                .hasSize(2);
    }

    @Test
    void aRateLimitedRescanIsADenyRowLikeAScan() {
        audit.rescanned(actor, origin, new TenantId(organization), UUID.randomUUID(), ScanResult.RATE_LIMITED);

        AuditEntry row = recorded.get(0);
        assertThat(row.outcome()).isEqualTo(AuditEntry.Outcome.DENY);
        assertThat(row.detail()).containsEntry("reason_code", "rate_limited").doesNotContainKey("new_status");
    }
}
