package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The "in-flight state" read of design §3.1, end to end: "resume after a DP crash reads the
 * non-terminal {@code apply_request} rows, their {@code apply_step_intent} rows … and the latest
 * {@code apply_attempt} per intent".
 *
 * <p>The distinguishing case is the LAST assertion: an intent with no attempt row. Rule 0's
 * write-before-act ordering is what makes its absence mean "provably issued nothing" rather than
 * "unknown", so resume's whole decision table hangs on this read returning empty for that intent
 * instead of, say, an arbitrary row.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResumeReadIT {

    private static final String HASH = "sha256:4444444444444444444444444444444444444444";

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static StepIntentService stepIntents;
    private static AttemptService attemptService;
    private static ApplyRequestRepository requests;
    private static StepIntentRepository intents;
    private static AttemptRepository attempts;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        stepIntents = ApplyEngineHarness.stepIntents();
        attemptService = ApplyEngineHarness.attemptsService(3);
        requests = ApplyEngineHarness.requests();
        intents = ApplyEngineHarness.intents();
        attempts = ApplyEngineHarness.attempts();
    }

    @Test
    void afterThreeAdmissionsAndOneLandingResumeSeesExactlyTheTwoOpenRequestsWithTheirEvidence() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId p1 = new PlacementId(UUID.randomUUID());
        PlacementId p2 = new PlacementId(UUID.randomUUID());
        PlacementId p3 = new PlacementId(UUID.randomUUID());

        ApplyId running = admit(tenant, instance, p1, 10);
        ApplyId requested = admit(tenant, instance, p2, 10);
        ApplyId landed = admit(tenant, instance, p3, 10);

        // One request is mid-flight with a MATERIALIZE intent and one open attempt…
        outcomes.start(tenant, running);
        StepIntentRow materialize = stepIntents.ensureIntent(tenant, running, p1, Step.MATERIALIZE,
                new ReplicaRef("cache-engine-1"), "idem-" + running.value(), HASH);
        AttemptRow attempt = attemptService.open(tenant, running, materialize, null,
                Duration.ofMinutes(2));

        // …one has an intent but NO attempt (rule 0: it provably issued nothing)…
        StepIntentRow preflight = stepIntents.ensureIntent(tenant, requested, p2, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, "idem-" + requested.value(), null);

        // …and one is already terminal, so resume must not see it at all.
        outcomes.start(tenant, landed);
        outcomes.land(tenant, landed, RequestState.APPLIED);

        List<ApplyRequestRow> open = requests.nonTerminal(tenant);
        assertEquals(Set.of(running, requested),
                open.stream().map(ApplyRequestRow::applyId).collect(Collectors.toSet()),
                "exactly the non-terminal rows, and the APPLIED one excluded");
        assertEquals(2, open.size());
        assertTrue(open.stream().allMatch(r -> r.state().blocksScope()),
                "every row resume reads must be one the fences also consider in flight, or the two "
                        + "would disagree about what 'in flight' means");
        assertTrue(open.stream().allMatch(r -> r.terminalAt() == null),
                "terminal_has_timestamp, read back from the rows resume actually loads");

        // The intent half of the read.
        List<StepIntentRow> runningIntents = intents.forRequest(tenant, running);
        assertEquals(1, runningIntents.size());
        assertEquals(materialize.intentId(), runningIntents.get(0).intentId());
        assertEquals(HASH, runningIntents.get(0).configHash(),
                "the INTENDED hash is what a MATERIALIZE resume compares its read-back against");
        assertEquals(materialize.idempotencyKey(), runningIntents.get(0).idempotencyKey());

        // The attempt half: present and OPEN for the one that was issued…
        AttemptRow latest = attempts.latest(tenant, materialize.intentId()).orElseThrow();
        assertEquals(attempt.attemptId(), latest.attemptId());
        assertEquals((short) 1, latest.attemptNo());
        assertNull(latest.outcome(), "an attempt still open is what 'the DP died mid-step' looks like");
        assertNull(latest.endedAt());

        // …and provably absent for the one that was not.
        assertEquals(1, intents.forRequest(tenant, requested).size());
        Optional<AttemptRow> none = attempts.latest(tenant, preflight.intentId());
        assertTrue(none.isEmpty(),
                "no attempt row means nothing was issued — §2.2 rule 0 is what upgrades that from "
                        + "an absence to a PROOF, and this read is where resume consumes it");

        // And the terminal request's own rows are still there — retention, not resume.
        assertEquals(RequestState.APPLIED, requests.find(tenant, landed).orElseThrow().state());
    }

    private static ApplyId admit(TenantId t, InstanceId i, PlacementId p, long generation) {
        return assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(
                        InventoryFixtures.registered(new ScopeKey(t, i, p)), generation)))
                .applyId();
    }
}
