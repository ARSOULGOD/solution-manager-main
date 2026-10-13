package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligation (o)'s trigger half, obligation (p)'s sanctioned transition, and obligation (u)'s
 * compaction branch — the three things {@code apply_request_terminal_guard} decides.
 *
 * <p>The guard has exactly three answers and all three are here: {@code STAGED → SUPERSEDED} passes
 * (sanctioned transition 1), an {@code attempt_summary}-only write on a terminal row passes
 * (sanctioned write 2), and everything else that touches a terminal row raises P0001. Testing only
 * the refusals would leave the two branches that make the design implementable unproven — and the
 * design says so: "measured: without this branch, compaction was unimplementable while the attempt
 * DELETE half succeeded".
 */
@Testcontainers(disabledWithoutDocker = true)
class OutcomeTransitionsIT {

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static ApplyRequestRepository requests;
    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        requests = ApplyEngineHarness.requests();
        runtime = ApplyEngineHarness.jdbc();
    }

    @Test
    void theLegalWalkRequestedToRunningToAppliedSucceedsThroughTheService() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);

        assertEquals(RequestState.REQUESTED, f.state(id));
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt());

        outcomes.start(f.tenant, id);
        assertEquals(RequestState.RUNNING, f.state(id));
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt(),
                "RUNNING is not terminal, so terminal_has_timestamp requires NULL");

        outcomes.land(f.tenant, id, RequestState.APPLIED);
        assertEquals(RequestState.APPLIED, f.state(id));
        assertNotNull(requests.find(f.tenant, id).orElseThrow().terminalAt());
    }

    @Test
    void landingOnAnAlreadyTerminalRowIsRefusedInJavaAndWritesNothing() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);
        Instant terminalAt = requests.find(f.tenant, id).orElseThrow().terminalAt();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, RequestState.FAILED_NO_EFFECT));
        assertTrue(e.getMessage().contains("terminal"), e.getMessage());

        ApplyRequestRow after = requests.find(f.tenant, id).orElseThrow();
        assertEquals(RequestState.APPLIED, after.state(), "nothing may have been written");
        assertEquals(terminalAt, after.terminalAt());
    }

    @Test
    void aRawTerminalToNonTerminalWalkIsRefusedByTheTriggerWithItsOwnWording() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);

        Refusals.assertRaised("is terminal (APPLIED) and immutable",
                () -> requests.transition(f.tenant, id, RequestState.APPLIED,
                        RequestState.REQUESTED, null));
    }

    @Test
    void theStagedToSupersededWalkPassesTheTriggerWhileStagedToAppliedDoesNot() {
        Fixture f = new Fixture();
        ApplyId staged = f.admit(10);
        outcomes.start(f.tenant, staged);
        outcomes.land(f.tenant, staged, RequestState.STAGED);

        Refusals.assertRaised("is terminal (STAGED) and immutable",
                () -> requests.transition(f.tenant, staged, RequestState.STAGED,
                        RequestState.APPLIED, Instant.now()));

        assertEquals(1, requests.transition(f.tenant, staged, RequestState.STAGED,
                        RequestState.SUPERSEDED, Instant.now()),
                "the ONE sanctioned terminal transition — obligation (p)");
        assertEquals(RequestState.SUPERSEDED, f.state(staged));
    }

    @Test
    void anAttemptSummaryOnlyUpdateOnATerminalRowSucceedsWhileOneTouchingAnotherColumnIsRefused() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLIED);

        assertEquals(1, requests.foldAttemptSummary(f.tenant, id,
                        "{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}"),
                "obligation (u): sanctioned write 2 on a terminal row");
        assertEquals("{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}",
                summaryOf(f.tenant, id));

        // The same statement, one GRANTED column wider (resolved_by — resolution_note would be
        // refused earlier still, by the grant, see PrivilegeTopologyIT). BEFORE UPDATE triggers run
        // before CHECK constraints, so this is the trigger's refusal (P0001), not
        // resolution_only_when_resolved's 23514 — and asserting the P0001 wording tells the two apart.
        Refusals.assertRaised("is terminal (APPLIED) and immutable",
                () -> runtime.update("UPDATE sm_data.apply_request"
                                + " SET attempt_summary = CAST(:s AS jsonb), resolved_by = :n"
                                + " WHERE tenant_id = :t AND apply_id = :a",
                        new MapSqlParameterSource()
                                .addValue("s", "{\"folded\": true}")
                                .addValue("n", "sneaking a second column in")
                                .addValue("t", f.tenant.value()).addValue("a", id.value())));
    }

    // -------------------------------------------------------------------------------------------
    // state_fits_operation, in Java before the write and in SQL as the last line. Finding 4: land()
    // used to accept any non-terminal→terminal walk and let the CHECK decide, so an engine bug
    // landing a stop on APPLIED surfaced as a 23514 out of a repository rather than as a sentence.
    // -------------------------------------------------------------------------------------------

    @Test
    void anApplyLandedOnAStopOutcomeIsRefusedInJavaAndTheRowIsUnchanged() {
        // A fresh fixture per case: apply_inflight_fence allows one in-flight apply per scope, so
        // two REQUESTED applies on one instance would be refused at admission and prove nothing.
        for (RequestState wrong : List.of(RequestState.STOPPED, RequestState.STOP_FAILED)) {
            Fixture f = new Fixture();
            assertRefusedByStateFitsOperation(f, f.admit(10), wrong, "apply");
        }
    }

    @Test
    void aStopLandedOnAnApplyOutcomeIsRefusedInJavaAndTheRowIsUnchanged() {
        for (RequestState wrong : List.of(RequestState.APPLIED, RequestState.STAGED)) {
            Fixture f = new Fixture();
            ApplyId id = f.admitStop();
            assertRefusedByStateFitsOperation(f, id, wrong, "stop");
        }
    }

    @Test
    void aRestartLandedOnStagedIsRefusedInJavaBecauseARestartHasNoStagingStep() {
        // Pinning note: this asserts state_fits_operation by name, and stays correct only because
        // land()'s validFor guard runs ABOVE the staged_requires_manual guard (both in this method
        // and in the CHECK's own evaluation, per §3.2's R3 correction on which name a non-manual
        // STAGED row surfaces as). A restart's strategy is never 'manual' (restart_not_manual), so a
        // future reorder that ran staged_requires_manual first would flip this refusal's name to
        // staged_requires_manual without changing what is refused — silently. If that reorder is ever
        // made deliberately, this assertion's expected rule name must move with it.
        Fixture f = new Fixture();
        ApplyId id = f.admitRestart();
        assertRefusedByStateFitsOperation(f, id, RequestState.STAGED, "restart");
    }

    // -------------------------------------------------------------------------------------------
    // BE-D16: state = 'STAGED' implies strategy = 'manual', with no exception (design §3.2). land()
    // refuses the walk in Java, naming staged_requires_manual, before any write — the direct
    // counterpart of state_fits_operation's mirror above. Obligation: refuse each of the four
    // non-manual strategies by name, and accept a manual one (the positive control).
    // -------------------------------------------------------------------------------------------

    @Test
    void landingANonManualApplyOnStagedIsRefusedInJavaForEveryLadderStrategy() {
        for (com.solace.sm.dp.apply.model.Strategy strategy : List.of(
                com.solace.sm.dp.apply.model.Strategy.ROLLING_HEALTH_GATED,
                com.solace.sm.dp.apply.model.Strategy.ROLLING_NO_VALIDATION,
                com.solace.sm.dp.apply.model.Strategy.RESTART_ALL,
                com.solace.sm.dp.apply.model.Strategy.SCHEDULED)) {
            Fixture f = new Fixture();
            ApplyId id = f.admitApply(strategy);
            outcomes.start(f.tenant, id);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> outcomes.land(f.tenant, id, RequestState.STAGED),
                    strategy + " must not be able to land on STAGED");
            assertTrue(e.getMessage().contains("staged_requires_manual"),
                    "the refusal must name the rule it mirrors. Was: " + e.getMessage());
            assertEquals(RequestState.RUNNING, f.state(id), "nothing may have been written");
            assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt());
        }
    }

    @Test
    void landingAManualApplyOnStagedSucceedsThePositiveControlForStagedRequiresManual() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.STAGED);
        assertEquals(RequestState.STAGED, f.state(id));
    }

    // -------------------------------------------------------------------------------------------
    // BE-D17: resolution evidence is operation-specific and generation-bound (design §3.4).
    // resolveIndeterminate() refuses the wrong-shaped evidence and a mismatched apply generation in
    // Java, before any write — the same before-any-write pattern as staged_requires_manual above.
    // Rejection classes (iii) (missing observations) and (iv) (an invented restart staged generation)
    // are proven by EvidenceTypesTest instead: they are refused by the evidence records' own
    // constructors and, for (iv), by RestartResolutionEvidence having no component to invent one in —
    // neither ever reaches resolveIndeterminate() at all.
    // -------------------------------------------------------------------------------------------

    @Test
    void resolveIndeterminateRefusesApplyShapedEvidenceForARestartRequestClassI() {
        Fixture f = new Fixture();
        ApplyId id = f.admitRestart();
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.resolveIndeterminate(f.tenant, id,
                        new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                                com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                                new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(10, List.of(
                                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                                new com.solace.sm.dp.apply.model.ReplicaRef(
                                                        "cache-engine-1"),
                                                true, null))))),
                "a restart request may not be resolved with apply-shaped evidence");
        assertTrue(e.getMessage().contains("resolution_is_evidence"), e.getMessage());
        assertEquals(RequestState.APPLY_INDETERMINATE, f.state(id), "nothing may have been written");
    }

    @Test
    void resolveIndeterminateRefusesRestartShapedEvidenceForAnApplyRequestClassI() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.resolveIndeterminate(f.tenant, id,
                        new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                                com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                                new com.solace.sm.dp.apply.model.RestartResolutionEvidence(List.of(
                                        new com.solace.sm.dp.apply.model.RestartObservation(
                                                new com.solace.sm.dp.apply.model.ReplicaRef(
                                                        "cache-engine-1"),
                                                true))))),
                "an apply request may not be resolved with restart-shaped evidence");
        assertTrue(e.getMessage().contains("resolution_is_evidence"), e.getMessage());
        assertEquals(RequestState.APPLY_INDETERMINATE, f.state(id), "nothing may have been written");
    }

    @Test
    void resolveIndeterminateRefusesAMismatchedApplyGenerationClassIi() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.resolveIndeterminate(f.tenant, id,
                        new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                                com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                                new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(11, List.of(
                                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                                new com.solace.sm.dp.apply.model.ReplicaRef(
                                                        "cache-engine-1"),
                                                true, null))))),
                "evidence naming generation 11 may not resolve a generation-10 request");
        assertTrue(e.getMessage().contains("resolution_is_evidence"), e.getMessage());
        assertTrue(e.getMessage().contains("stagedGeneration=11")
                        && e.getMessage().contains("generation=10"),
                e.getMessage());
        assertEquals(RequestState.APPLY_INDETERMINATE, f.state(id), "nothing may have been written");

        // Positive control: the SAME generation resolves cleanly — the guard above is a match check,
        // not a blanket refusal of apply resolutions.
        outcomes.resolveIndeterminate(f.tenant, id,
                new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                        com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                        new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(10, List.of(
                                new com.solace.sm.dp.apply.model.ReplicaObservation(
                                        new com.solace.sm.dp.apply.model.ReplicaRef("cache-engine-1"),
                                        true, null)))));
        assertEquals(RequestState.INDETERMINATE_RESOLVED, f.state(id));
    }

    @Test
    void resolveIndeterminateAcceptsAFullyEvidencedRestartResolutionThePositiveControl() {
        Fixture f = new Fixture();
        ApplyId id = f.admitRestart();
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        outcomes.resolveIndeterminate(f.tenant, id,
                new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                        com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                        new com.solace.sm.dp.apply.model.RestartResolutionEvidence(List.of(
                                new com.solace.sm.dp.apply.model.RestartObservation(
                                        new com.solace.sm.dp.apply.model.ReplicaRef("cache-engine-1"),
                                        true)))));
        assertEquals(RequestState.INDETERMINATE_RESOLVED, f.state(id));
    }

    @Test
    void landingOnIndeterminateResolvedIsRefusedBecauseOnlyResolveIndeterminateWritesTheEvidence() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, RequestState.INDETERMINATE_RESOLVED));
        assertTrue(e.getMessage().contains("resolveIndeterminate"), e.getMessage());
        assertTrue(e.getMessage().contains("resolution_is_evidence"), e.getMessage());
        assertEquals(RequestState.APPLY_INDETERMINATE, f.state(id),
                "the row is exactly as it was — land() refused before writing. Had it written, the "
                        + "CHECK would have refused an INDETERMINATE_RESOLVED with four NULL "
                        + "resolution columns anyway, and the request would be stuck");
    }

    /**
     * Schema ahead of use: {@code resolution_note} exists in §3.1's DDL and is in the runtime's
     * column-scoped UPDATE grant, and v1 never writes it. An external review named it the one place an
     * operator could paste a secret into during an incident (persisted, then printed by JDBC's TRACE
     * bind-value line), so the {@code Resolution} type has no free-text component and the repository's
     * SET list omits the column. This asserts the column IS null after a resolution, not merely that no
     * Java field maps to it.
     */
    @Test
    void aResolutionLeavesResolutionNoteNullBecauseV1WritesNoFreeText() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);
        outcomes.start(f.tenant, id);
        outcomes.land(f.tenant, id, RequestState.APPLY_INDETERMINATE);
        outcomes.resolveIndeterminate(f.tenant, id, new com.solace.sm.dp.apply.model.Resolution(
                "duty-operator", com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(10, List.of(
                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                new com.solace.sm.dp.apply.model.ReplicaRef("cache-engine-1"), true,
                                null)))));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, f.state(id));
        Boolean noteIsNull = runtime.queryForObject(
                "SELECT resolution_note IS NULL FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", f.tenant.value()).addValue("a", id.value()),
                Boolean.class);
        assertEquals(Boolean.TRUE, noteIsNull,
                "resolution_note is deferred schema — no v1 path may write it");
    }

    /**
     * The SQL half of the same rule, on the one pair the Java half now refuses first.
     *
     * <p>Going around {@code OutcomeService} on purpose: {@code state_fits_operation} stays the last
     * line, and a Java guard nobody can bypass is not a proof that the database has one.
     */
    @Test
    void theRawTransitionOfAnApplyToStoppedIsRefusedByTheDatabaseWithStateFitsOperation() {
        Fixture f = new Fixture();
        ApplyId id = f.admit(10);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.CHECK_VIOLATION,
                "state_fits_operation",
                () -> requests.transition(f.tenant, id, RequestState.REQUESTED,
                        RequestState.STOPPED, Instant.now()));
        assertEquals("apply_request", v.table());
        assertEquals(RequestState.REQUESTED, f.state(id));
    }

    // -------------------------------------------------------------------------------------------

    /** Refused in Java, with the rule named, and nothing written. */
    private static void assertRefusedByStateFitsOperation(Fixture f, ApplyId id,
            RequestState wrong, String operation) {
        RequestState before = f.state(id);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> outcomes.land(f.tenant, id, wrong),
                operation + " must not be able to land on " + wrong);
        assertTrue(e.getMessage().contains("state_fits_operation"),
                "the refusal must name the rule it mirrors. Was: " + e.getMessage());
        assertTrue(e.getMessage().contains(operation) && e.getMessage().contains(wrong.name()),
                e.getMessage());
        assertEquals(before, f.state(id), "nothing may have been written");
        assertNull(requests.find(f.tenant, id).orElseThrow().terminalAt());
    }

    @Test
    void aRequestWhoseScopeRowDoesNotExistIsRefusedByRequestHasScope() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        // plan 010 T2.2: the instance IS declared — the stamp trigger (BEFORE INSERT) would otherwise refuse the row
        // first, with apply_request_has_instance, and this test is about the scope-row foreign key behind it.
        ScopeKey orphan = InventoryFixtures.declared(new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE));

        PgConstraintViolation v = Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION,
                "request_has_scope",
                () -> requests.insert(ApplyEngineHarness.requestedApply(orphan, 1)));
        assertEquals("apply_request", v.table());

        // Positive control: the identical insert succeeds the moment the lock row exists, so the
        // refusal above is the FK and not a malformed row.
        ApplyEngineHarness.scopes().ensureScopeRow(tenant, instance);
        requests.insert(ApplyEngineHarness.requestedApply(orphan, 1));
        assertEquals(1, requests.nonTerminal(tenant).size());
    }

    // -------------------------------------------------------------------------------------------

    private static String summaryOf(TenantId t, ApplyId id) {
        return runtime.queryForObject(
                "SELECT attempt_summary::text FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                String.class);
    }

    private static final class Fixture {

        private final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        private final InstanceId instance = new InstanceId(UUID.randomUUID());
        private final ScopeKey wide = InventoryFixtures.declared(
                new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE));

        ApplyId admit(long generation) {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, generation))).applyId();
        }

        /** An apply on the ladder's given strategy — {@code restart-all} supplies its own outage
         * confirmation so only {@code staged_requires_manual} is under test, never
         * {@code full_outage_confirmed}. */
        ApplyId admitApply(com.solace.sm.dp.apply.model.Strategy strategy) {
            String outageConfirmedBy =
                    strategy == com.solace.sm.dp.apply.model.Strategy.RESTART_ALL
                            ? "duty-manager-3" : null;
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(new com.solace.sm.dp.apply.model.ApplyCommand(wide,
                            com.solace.sm.dp.apply.model.Operation.APPLY, 10L, strategy,
                            outageConfirmedBy, "operator-1"))).applyId();
        }

        ApplyId admitStop() {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.stop(wide))).applyId();
        }

        ApplyId admitRestart() {
            return assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.restart(wide))).applyId();
        }

        RequestState state(ApplyId id) {
            return requests.find(tenant, id).orElseThrow().state();
        }
    }
}
