package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.RefusalReason;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (a), (b), (c) and (e) — at BOTH layers, because design §3.3 says neither alone is
 * sufficient: "the index is the belt for identical scopes; the serialized admission check is the
 * braces for overlapping ones".
 *
 * <p>So every fact here is proven twice where both layers can express it: once through
 * {@link AdmissionService}, which returns a {@link AdmissionOutcome.Refused} an operator can read,
 * and once by going AROUND the service with a raw repository insert, which must be refused by the
 * named partial unique index. A test suite that only exercised the service could not tell a working
 * fence from a missing one.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdmissionFencesIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyRequestRepository requests;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        requests = ApplyEngineHarness.requests();
    }

    // -------------------------------------------------------------------------------------------
    // The measurement the whole engine's concurrency story rests on.
    // -------------------------------------------------------------------------------------------

    @Test
    void theTransactionTemplateRunsAtPostgresqlsDefaultIsolationWhichIsReadCommitted() {
        String isolation = ApplyEngineHarness.transactions().execute(status ->
                ApplyEngineHarness.jdbc().queryForObject(
                        "SELECT current_setting('transaction_isolation')",
                        new MapSqlParameterSource(), String.class));
        assertEquals("read committed", isolation,
                "design §2.2 rule 2 is a statement about READ COMMITTED specifically — the arbiter "
                        + "retry exists because a concurrent insert becomes visible to a NEW "
                        + "transaction. If the default ever moved, that reasoning would need redoing");
    }

    // -------------------------------------------------------------------------------------------
    // (a) + (b): the stop exemption, and what it is an exemption FROM.
    // -------------------------------------------------------------------------------------------

    @Test
    void aStopIsAdmittedWhileAnApplyOnTheSameScopeIsRunningWhileASecondApplyIsRefused() {
        Scope s = new Scope();
        ApplyId running = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, running);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)),
                "(a) the operator's emergency lever is never fenced by an apply — design §3.3");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)));
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.restart(s.wide)));
    }

    @Test
    void aStopIsAdmittedWhileAnApplyIsIndeterminateWhileASecondApplyIsRefused() {
        Scope s = new Scope();
        ApplyId stuck = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, stuck);
        outcomes.land(s.tenant, stuck, RequestState.APPLY_INDETERMINATE);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)));
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)),
                "(b) architecture.md §16.1: an unresolved APPLY_INDETERMINATE blocks further applies");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.restart(s.wide)));
    }

    // -------------------------------------------------------------------------------------------
    // (c): the stop family's own, narrower rule.
    // -------------------------------------------------------------------------------------------

    @Test
    void aSecondInFlightStopOnTheSameScopeIsRefused() {
        Scope s = new Scope();
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.wide)));
        assertRefused(RefusalReason.STOP_IN_FLIGHT, admission.admit(ApplyEngineHarness.stop(s.wide)));
    }

    @Test
    void aStopOnADifferentPlacementIsAdmittedBesideAnInFlightStopThePositiveControl() {
        Scope s = new Scope();
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.placement(s.p1))));
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(s.placement(s.p2))),
                "stop_inflight_fence is per SCOPE, and two placements are two scopes");
    }

    // -------------------------------------------------------------------------------------------
    // The cross-scope rule the indexes cannot see — design §3.3's "braces".
    // -------------------------------------------------------------------------------------------

    @Test
    void aPlacementScopedApplyIsRefusedWhileAnInstanceWideOneIsRunning() {
        Scope s = new Scope();
        ApplyId wide = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, wide);

        assertRefused(RefusalReason.SCOPE_BUSY,
                admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 11)),
                "no per-placement admission while an instance-wide request is in flight");
    }

    @Test
    void anInstanceWideApplyIsRefusedWhileAPlacementScopedOneIsRunning() {
        Scope s = new Scope();
        ApplyId narrow = admitted(admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 10)));
        outcomes.start(s.tenant, narrow);

        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(s.wide, 11)),
                "…and vice versa: the sentinel overlaps every placement");
    }

    @Test
    void aSecondPlacementIsAdmittedBesideAFirstThePositiveControlForTheOverlapRule() {
        Scope s = new Scope();
        ApplyId first = admitted(admission.admit(ApplyEngineHarness.apply(s.placement(s.p1), 10)));
        outcomes.start(s.tenant, first);

        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(s.placement(s.p2), 10)),
                "two disjoint placements do NOT overlap — an admission check that refused here "
                        + "would be serializing the whole instance, which is not what §3.3 says");
    }

    @Test
    void aPlacementScopedStopIsAdmittedWhileAnInstanceWideApplyIsRunningOrIndeterminate() {
        Scope running = new Scope();
        ApplyId wide = admitted(admission.admit(ApplyEngineHarness.apply(running.wide, 10)));
        running.startOf(wide);
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(running.placement(running.p1))),
                "the CROSS-SCOPE half of the stop exemption, which obligation (a) names explicitly");

        Scope stuck = new Scope();
        ApplyId indeterminate = admitted(admission.admit(ApplyEngineHarness.apply(stuck.wide, 10)));
        outcomes.start(stuck.tenant, indeterminate);
        outcomes.land(stuck.tenant, indeterminate, RequestState.APPLY_INDETERMINATE);
        assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.stop(stuck.placement(stuck.p1))));
    }

    // -------------------------------------------------------------------------------------------
    // (e): tenancy. A fence that let tenant A block tenant B would be an I5 defect in the schema.
    // -------------------------------------------------------------------------------------------

    @Test
    void theSameInstanceAndPlacementUnderADifferentTenantIsAdmittedWhileTheSameTenantIsRefused() {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        UUID tenantA = SmDataPostgres.freshTenant();
        UUID tenantB = SmDataPostgres.freshTenant();
        ScopeKey a = ApplyEngineHarness.scope(tenantA, instance, new PlacementId(placement));
        ScopeKey b = ApplyEngineHarness.scope(tenantB, instance, new PlacementId(placement));

        ApplyId first = admitted(admission.admit(ApplyEngineHarness.apply(a, 10)));
        outcomes.start(new TenantId(tenantA), first);

        assertInstanceOf(AdmissionOutcome.Admitted.class, admission.admit(ApplyEngineHarness.apply(b, 10)),
                "(e) the identical (instance, placement) under another tenant must not collide");
        assertRefused(RefusalReason.SCOPE_BUSY, admission.admit(ApplyEngineHarness.apply(a, 11)),
                "…while the same tenant-and-scope still collides as intended");
    }

    // -------------------------------------------------------------------------------------------
    // The BELT: the same facts, with AdmissionService removed from the picture.
    // -------------------------------------------------------------------------------------------

    @Test
    void aRawSecondRequestedRowOnTheSameScopeIsRefusedByApplyInflightFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedApply(s.wide, 10));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.APPLY_INFLIGHT_FENCE,
                () -> requests.insert(ApplyEngineHarness.requestedApply(s.wide, 11)));
    }

    @Test
    void aRawSecondStopRowOnTheSameScopeIsRefusedByStopInflightFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null));

        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.STOP_INFLIGHT_FENCE,
                () -> requests.insert(
                        ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null)));
    }

    @Test
    void aRawStopBesideARawApplyOnOneScopeIsAcceptedTheTwoFencesAreSeparate() {
        Scope s = new Scope();
        s.ensureScopeRow();
        requests.insert(ApplyEngineHarness.requestedApply(s.wide, 10));
        requests.insert(ApplyEngineHarness.requestedRow(s.wide, Operation.STOP, null, null));
        assertEquals(2, requests.nonTerminal(s.tenant).size(),
                "the apply fence excludes stops by its own predicate — the constraint and the prose "
                        + "must agree, and this is the row-level proof that they do");
    }

    // -------------------------------------------------------------------------------------------
    // (p): the STAGED fence, and the supersession order it exists to enforce.
    // -------------------------------------------------------------------------------------------

    @Test
    void aRawSecondStagedRowWithoutMarkingTheElderIsRefusedByStagedFence() {
        Scope s = new Scope();
        s.ensureScopeRow();
        ApplyRequestRow elder = ApplyEngineHarness.requestedApply(s.wide, 10);
        requests.insert(elder);
        assertEquals(1, requests.transition(s.tenant, elder.applyId(), RequestState.REQUESTED,
                RequestState.STAGED, Instant.now()));

        ApplyRequestRow younger = ApplyEngineHarness.requestedApply(s.wide, 11);
        requests.insert(younger);
        Refusals.assertViolation(Refusals.UNIQUE_VIOLATION, Constraints.STAGED_FENCE,
                () -> requests.transition(s.tenant, younger.applyId(), RequestState.REQUESTED,
                        RequestState.STAGED, Instant.now()));
    }

    @Test
    void admissionMarksTheElderStagedRowSupersededAndRefreshesItsTerminalTimestamp() throws Exception {
        Scope s = new Scope();
        ApplyId elder = admitted(admission.admit(ApplyEngineHarness.apply(s.wide, 10)));
        outcomes.start(s.tenant, elder);
        outcomes.land(s.tenant, elder, RequestState.STAGED);

        Instant stagedAt = requests.find(s.tenant, elder).orElseThrow().terminalAt();
        assertNotNull(stagedAt, "STAGED is terminal for the manual strategy, so it carries a stamp");
        Thread.sleep(5);

        AdmissionOutcome outcome = admission.admit(ApplyEngineHarness.apply(s.wide, 11));
        assertInstanceOf(AdmissionOutcome.Admitted.class, outcome);

        ApplyRequestRow marked = requests.find(s.tenant, elder).orElseThrow();
        assertEquals(RequestState.SUPERSEDED, marked.state());
        assertTrue(marked.terminalAt().isAfter(stagedAt),
                "§3.2: the marking UPDATE refreshes terminal_at to the SUPERSESSION time, so the "
                        + "row's stamp says when it became superseded, not when it staged. Was "
                        + marked.terminalAt() + ", staged at " + stagedAt);

        List<ApplyRequestRow> open = requests.nonTerminal(s.tenant);
        assertEquals(1, open.size(), "only the new request is open");
        assertEquals(RequestState.REQUESTED, open.get(0).state());
    }

    // -------------------------------------------------------------------------------------------

    private static ApplyId admitted(AdmissionOutcome outcome) {
        return assertInstanceOf(AdmissionOutcome.Admitted.class, outcome,
                "expected admission, got " + outcome).applyId();
    }

    private static void assertRefused(RefusalReason expected, AdmissionOutcome outcome) {
        assertRefused(expected, outcome, "refusal reason");
    }

    private static void assertRefused(RefusalReason expected, AdmissionOutcome outcome,
            String because) {
        AdmissionOutcome.Refused refused = assertInstanceOf(AdmissionOutcome.Refused.class, outcome,
                "expected a refusal, got " + outcome);
        assertEquals(expected, refused.reason(), because + " — detail was: " + refused.detail());
    }

    /** One fresh tenant, one instance, two placements — the isolation unit for a shared container. */
    private static final class Scope {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final UUID instance = UUID.randomUUID();
        private final PlacementId p1 = new PlacementId(UUID.randomUUID());
        private final PlacementId p2 = new PlacementId(UUID.randomUUID());
        private final ScopeKey wide = ApplyEngineHarness.scope(tenant.value(), instance,
                PlacementId.INSTANCE_WIDE);

        ScopeKey placement(PlacementId p) {
            return ApplyEngineHarness.scope(tenant.value(), instance, p);
        }

        void ensureScopeRow() {
            ApplyEngineHarness.scopes().ensureScopeRow(wide.tenant(), wide.instance());
        }

        void startOf(ApplyId id) {
            ApplyEngineHarness.outcomes().start(tenant, id);
        }
    }
}
