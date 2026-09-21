package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations (i) and (m), plus §2.2 rule 0's ordering and §3.3's compaction pair.
 *
 * <p>Obligation (m) is deliberately split across two enforcers and both halves are here: the DATABASE
 * owns the lower bound ({@code attempt_no_positive}) and the ENGINE owns the configured cap, "an
 * application-level prove-it-fires, because the cap is configuration the database cannot know".
 *
 * <p>Two more cases close findings from an external review of Task 3's {@code AttemptRepository}
 * (R9, R10) — both proven here rather than left to a javadoc: a reference-less {@code close} must not
 * erase a reference {@code open} already wrote, and a second {@code close} on an already-closed
 * attempt must be refused rather than silently rewriting the recorded outcome.
 */
@Testcontainers(disabledWithoutDocker = true)
class AttemptIT {

    private static final String HASH = "sha256:3333333333333333333333333333333333333333";
    private static final Duration BUDGET = Duration.ofMinutes(2);
    private static final PreBaseline BASELINE =
            new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), "cache-engine-1");

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static AttemptRepository attempts;
    private static StepIntentRepository intents;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        attempts = ApplyEngineHarness.attempts();
        intents = ApplyEngineHarness.intents();
    }

    // -------------------------------------------------------------------------------------------
    // Rule 0: the row exists, committed, BEFORE anything could have been issued.
    // -------------------------------------------------------------------------------------------

    @Test
    void openCommitsTheAttemptRowBeforeReturningSoNoAdapterCanRunAheadOfItsJournalEntry() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        AttemptRow row = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        assertEquals((short) 1, row.attemptNo());
        assertNull(row.outcome(), "an open attempt has no outcome yet — that IS the ordering rule");
        assertNull(row.endedAt());
        assertNotNull(row.stepDeadline());
        assertTrue(visibleOnAnotherConnection(row.attemptId()),
                "the row must be COMMITTED when open() returns: a different connection sees it. "
                        + "Anything weaker and 'an intent with no attempt row provably issued "
                        + "nothing' stops being provable");
    }

    @Test
    void closeRecordsTheOutcomeAndLatestAndCountSeeIt() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        AttemptRow first = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, first.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null);
        AttemptRow second = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, second.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null,
                RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "abc123"));

        assertEquals(2, attempts.count(f.tenant, f.intent.intentId()));
        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(second.attemptId(), latest.attemptId());
        assertEquals((short) 2, latest.attemptNo());
        assertEquals(AttemptOutcome.SUCCEEDED, latest.outcome());
        assertEquals(HASH, latest.observedHash());
        assertNotNull(latest.endedAt());
        assertNull(latest.errorClass());
        assertEquals(RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "abc123"), latest.runtimeRef(),
                "the typed runtime ref round-trips through the jsonb column unchanged");
    }

    // -------------------------------------------------------------------------------------------
    // (m): the engine's cap, and the database's floor.
    // -------------------------------------------------------------------------------------------

    @Test
    void theFourthOpenUnderACapOfThreeIsRefusedByTheEngineAndWritesNoRow() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        // BE-D20: each attempt is closed before the next opens — attempt_open_fence now permits
        // only ONE open row per intent at a time, so a realistic retry sequence (open, act, close,
        // retry) is what this loop must model; the CAP under test here is the total count across
        // all three, not how many are open simultaneously (that is attempt_open_fence's own proof,
        // in aSequentialSecondOpenIsRefusedByAttemptOpenFenceByNameAndARetryAfterCloseIsAccepted).
        for (int i = 0; i < 3; i++) {
            AttemptRow opened = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
            service.close(f.tenant, opened.attemptId(), AttemptOutcome.FAILED, null,
                    new ErrorClass("io.timeout"), null);
        }
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()));

        AttemptService.RetryCapExceeded e = assertThrows(AttemptService.RetryCapExceeded.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET));
        assertTrue(e.getMessage().contains("rollbackOrMarkIndeterminate"), e.getMessage());
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()),
                "a refused open must leave no row — otherwise the cap leaks one attempt per bounce");
    }

    /**
     * The defect this fix closes, stated at a cap of one: attempt 1 opens and stays OPEN, so the
     * count already equals the cap. Checking the cap first would misreport this as
     * {@code RetryCapExceeded} — the correct classification is {@code ConcurrentAttempt}: someone
     * else is running this try, not "no tries remain." The pre-check must run BEFORE the cap check
     * for that reason.
     */
    @Test
    void aSecondOpenBeforeCloseIsConcurrentAttemptNotRetryCapExceededEvenAtACapOfOne() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(1);

        AttemptRow first = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        assertNull(first.outcome(), "attempt 1 stays open for this test's whole duration");

        AttemptService.ConcurrentAttempt e = assertThrows(AttemptService.ConcurrentAttempt.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET),
                "an open attempt must be refused as ConcurrentAttempt even though the count (1) "
                        + "already equals the cap (1) — not RetryCapExceeded");
        assertEquals(AttemptService.ConcurrentAttempt.Provenance.PRE_CHECK, e.provenance(),
                "the pre-check found the open row before any insert was attempted");
        assertNull(e.constraintName(),
                "the pre-check path issued no insert, so PostgreSQL named no constraint — a name "
                        + "here would be fabricated");
        assertEquals(1, attempts.count(f.tenant, f.intent.intentId()),
                "the refused open must not have landed a second row");
    }

    /**
     * The same defect at a cap of three: attempts 1 and 2 are closed, attempt 3 — the FINAL allowed
     * attempt — stays open. Another open must still be {@code ConcurrentAttempt}, not
     * {@code RetryCapExceeded}, because a legitimate attempt is still running. Only once that final
     * attempt closes does the intent legitimately read {@code RetryCapExceeded}.
     */
    @Test
    void anOpenFinalAttemptIsConcurrentAttemptAndOnlyClosingItExposesRetryCapExceeded() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        for (int i = 0; i < 2; i++) {
            AttemptRow opened = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
            service.close(f.tenant, opened.attemptId(), AttemptOutcome.FAILED, null,
                    new ErrorClass("io.timeout"), null);
        }
        AttemptRow third = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        assertEquals((short) 3, third.attemptNo());
        assertNull(third.outcome(), "attempt 3 — the last one the cap allows — stays open");

        AttemptService.ConcurrentAttempt e = assertThrows(AttemptService.ConcurrentAttempt.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET),
                "the cap (3) is already spent by count, but attempt 3 is still open — the correct "
                        + "refusal is ConcurrentAttempt, not RetryCapExceeded");
        assertEquals(AttemptService.ConcurrentAttempt.Provenance.PRE_CHECK, e.provenance());
        assertNull(e.constraintName());
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()),
                "the refused open must not have landed a fourth row");

        service.close(f.tenant, third.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null);
        AttemptService.RetryCapExceeded cap = assertThrows(AttemptService.RetryCapExceeded.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET),
                "with attempt 3 now closed, the pre-check finds nothing open and the cap — genuinely "
                        + "spent — is what refuses this open");
        assertTrue(cap.getMessage().contains("rollbackOrMarkIndeterminate"), cap.getMessage());
        assertEquals(3, attempts.count(f.tenant, f.intent.intentId()),
                "a refused open must still leave no row");
    }

    /**
     * The other clause of {@code AttemptRepository.openState}'s {@code WHERE}, pinned the same way
     * the tenant_id predicate is pinned in {@code TenantIsolationIT}: an open attempt on intent 1
     * must not leak into intent 2's own {@code openState}, even though both intents share this
     * test's own tenant AND apply. Only {@code intent_id = :i} — not merely {@code tenant_id = :t} —
     * may gate this; a dropped {@code intent_id} predicate would make {@code openState} tenant-wide,
     * so ANY open attempt anywhere in the tenant would misclassify a legitimate FIRST open on an
     * unrelated intent as {@code ConcurrentAttempt}.
     *
     * <p>{@link AttemptRepository#count}'s OWN {@code intent_id} predicate rides along on the SAME
     * fixture: {@code count} has {@code tenant_id} pinned ({@code TenantIsolationIT}) but not
     * {@code intent_id} — a pre-existing gap, not introduced here, closed cheaply because intent 1's
     * open attempt is already sitting right there.
     */
    @Test
    void anOpenAttemptOnOneIntentDoesNotFenceAFirstOpenOnAnUnrelatedIntentOfTheSameTenant() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);

        AttemptRow openOnFirst = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        assertNull(openOnFirst.outcome(), "intent 1's attempt stays open for this whole test");

        StepIntentRow other = f.lifecycleIntent();
        assertEquals(0, attempts.count(f.tenant, other.intentId()),
                "count() must not count intent 1's attempt under intent 2 — only intent_id, not "
                        + "merely tenant_id, may gate this either");
        AttemptRepository.OpenState unrelated = attempts.openState(f.tenant, other.intentId());
        assertEquals(0, unrelated.spent());
        assertFalse(unrelated.hasOpen(),
                "intent 1's OPEN row must not be visible under intent 2's own openState — a dropped "
                        + "intent_id predicate would report true here, matching intent 1's row");

        AttemptRow openOnOther = assertDoesNotThrow(
                () -> service.open(f.tenant, f.apply, other, null, BUDGET),
                "a FIRST open on an unrelated intent must succeed — it must never be refused as "
                        + "ConcurrentAttempt merely because SOME OTHER intent in the same tenant has "
                        + "an open attempt");
        assertEquals((short) 1, openOnOther.attemptNo());
        assertNull(openOnOther.outcome());
    }

    /**
     * The structural guard the snapshot-window fix itself had none of: no BEHAVIOURAL test in this
     * class can tell {@code openState}'s single {@code SELECT} apart from a version split back into
     * two — an {@code EXISTS} for {@code hasOpen} then a separate call to {@link
     * AttemptRepository#count} — because with NO concurrent writer in play the two shapes answer
     * identically every time; the snapshot window only opens under a race, and a race is exactly what
     * no ordinary test run reliably contains. This is the same species of gap that made
     * {@code tenant_id} and {@code intent_id} pass every check while unpinned, and the pre-check
     * pass every check while non-atomic: each defect was invisible to a green suite until something
     * asserted the STRUCTURE, not the behaviour, directly.
     *
     * <p>The device, exactly {@code SchemaConstraintsIT.resolutionIsEvidenceNeverCallsJsonbArrayLength}'s
     * (a text-level assertion against an INSTALLED definition, chosen because no behavioural
     * permutation could catch a structural regression there either): here there is no installed
     * database object to inspect — the "definition" is the SQL Spring actually issues — so the
     * install-time text is swapped for {@code LogCapture}, this suite's own established tool for
     * "what did the JVM actually do", already used elsewhere in this file
     * ({@code aRefusedRowsContentNeverReachesALoggerThroughThePgConstraintViolation}). Spring's
     * {@code JdbcTemplate} logs one {@code "Executing prepared SQL statement [...]"} line per
     * statement it sends, at DEBUG, regardless of behavioural outcome — measured directly against
     * ONE {@code openState} call before writing this assertion, not assumed: exactly one such line,
     * carrying this method's own SQL text verbatim. A version split back into two statements issues
     * two such lines, each with its OWN SQL text, and is caught by COUNT alone, with no dependency on
     * either statement's answer.
     */
    @Test
    void openStateIssuesExactlyOneSqlStatementSoItsTwoFactsCannotDriftApart() {
        Fixture f = new Fixture();
        LogCapture.Captured<AttemptRepository.OpenState> cap = LogCapture.during(
                () -> attempts.openState(f.tenant, f.intent.intentId()));
        java.util.List<String> statements = cap.lines().stream()
                .filter(l -> l.logger().equals("org.springframework.jdbc.core.JdbcTemplate")
                        && l.message().startsWith("Executing prepared SQL statement ["))
                .map(LogCapture.Line::message)
                .toList();
        assertEquals(1, statements.size(),
                "openState must issue exactly ONE statement against apply_attempt so spent and "
                        + "hasOpen are read from the SAME READ COMMITTED snapshot — a version split "
                        + "back into two (an EXISTS then a call to count()) would pass every "
                        + "behavioural test in this class while silently reintroducing the "
                        + "snapshot-window defect Important finding 1 closed. Statements observed: "
                        + statements);
    }

    /**
     * The honest half of obligation (m), fix round 1's Important finding 1 — the same pattern
     * {@code MonotonicityIT.obligationNHasNoDatabaseArtifactARawInsertBelowTheWatermarkIsAccepted}
     * uses for obligation (n). The plan's obligation map marks the CAP "engine — no DB artifact for
     * the comparison" ({@code attempt_no_positive} is only the floor, obligation (m)'s OTHER half),
     * and the stated method for every engine-marked obligation is to prove BOTH sides: the service
     * refusing (the test above), and a raw repository write the database ACCEPTS. Without this half,
     * "the cap is the engine's, the database cannot know it" was an assertion about the schema
     * nobody had checked.
     *
     * <p>A raw {@code attempts.open(...)} of {@code attempt_no = 4}, going around
     * {@code AttemptService} entirely after the engine has already refused the same 4th try, is
     * bound by {@code attempt_no_positive} (a floor, not a cap), {@code V005}'s three FOREIGN KEYs,
     * its {@code UNIQUE (intent_id, attempt_no)}, and — {@code BE-D20} — {@code attempt_open_fence}
     * (trivially satisfied here since attempts 1–3 are each closed before the next opens, so no
     * open row of this intent exists when the raw 4th lands) — none of them expresses a COUNT — so
     * it MUST be accepted even though the policy above is 3. If a future migration ever added a
     * cap CHECK or trigger, THIS is the assertion that would turn red and announce the engine's cap
     * had silently become redundant, or worse, out of step with the database.
     */
    @Test
    void obligationMHasNoDatabaseArtifactARawFourthAttemptIsAccepted() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        // BE-D20: closed between opens, exactly as the sibling test above — isolates the COUNT-based
        // cap (this test's own subject) from attempt_open_fence's separate, concurrency-shaped rule.
        for (int i = 0; i < 3; i++) {
            AttemptRow opened = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
            service.close(f.tenant, opened.attemptId(), AttemptOutcome.FAILED, null,
                    new ErrorClass("io.timeout"), null);
        }
        assertThrows(AttemptService.RetryCapExceeded.class,
                () -> service.open(f.tenant, f.apply, f.intent, null, BUDGET),
                "the engine's own refusal — proven already above, restated here as the paired half");

        attempts.open(f.tenant, f.apply, f.attemptRow((short) 4, Step.MATERIALIZE, null));
        assertEquals(4, attempts.count(f.tenant, f.intent.intentId()),
                "the database accepted a 4th attempt row under a configured cap of 3 — the CAP is "
                        + "entirely the engine's, exactly as obligation (m) is stated");
    }

    /**
     * BE-D20 obligation (v).1-2: the fence's own deterministic proof, no race required — a raw
     * repository probe, exactly the idiom {@code StepIntentIT}'s
     * {@code anIdenticalRawReInsertNamesTheIdempotencyKeyIndexNotTheCompositeArbiter} uses for the
     * analogous ambiguity between {@code apply_step_intent}'s two UNIQUEs (design §3.4's
     * classification note).
     *
     * <p>Attempt 1 opens and stays OPEN. A second raw open under a DIFFERENT, non-colliding
     * {@code attempt_no} (2 — the number the count-based engine would compute next) cannot violate
     * the numbering UNIQUE at all, so only {@code attempt_open_fence} can fire — and it does, named,
     * not merely refused. Attempt 1 then closes, and the identical shape the fence just refused is
     * accepted as attempt 2: the fence forbids a second OPEN, never a legitimate retry once the prior
     * attempt is closed (design's own retry-after-close positive control).
     */
    @Test
    void aSequentialSecondOpenIsRefusedByAttemptOpenFenceByNameAndARetryAfterCloseIsAccepted() {
        Fixture f = new Fixture();
        AttemptRow first = f.attemptRow((short) 1, Step.MATERIALIZE, null);
        attempts.open(f.tenant, f.apply, first);

        PgConstraintViolation v = Refusals.assertViolation(Refusals.UNIQUE_VIOLATION,
                Constraints.ATTEMPT_OPEN_FENCE,
                () -> attempts.open(f.tenant, f.apply, f.attemptRow((short) 2, Step.MATERIALIZE, null)));
        System.out.println("MEASURED: a sequential second open, non-colliding attempt_no, refused as "
                + v.constraint());
        assertEquals(1, attempts.count(f.tenant, f.intent.intentId()),
                "the refused second open must not have landed a row");

        assertEquals(1, attempts.close(f.tenant, first.attemptId(), AttemptOutcome.FAILED, null,
                        new ErrorClass("io.timeout"), null, Instant.now()),
                "closing the only open attempt must succeed, or the retry below proves nothing");

        // The retry-after-close positive control: the identical shape the fence just refused is now
        // accepted, because outcome IS NOT NULL excludes the closed row from attempt_open_fence's
        // WHERE clause.
        attempts.open(f.tenant, f.apply, f.attemptRow((short) 2, Step.MATERIALIZE, null));
        assertEquals(2, attempts.count(f.tenant, f.intent.intentId()),
                "a legitimate retry after the prior attempt closed must succeed as attempt 2 — the "
                        + "fence fences OPEN duplicates, not sequential retries");
    }

    /**
     * BE-D20 obligation (v).3: two concurrent {@code open()}s for one intent, now deterministic
     * regardless of collision. Before {@code attempt_open_fence} landed, the outcome depended on
     * whether the two opens collided on the SAME {@code attempt_no} (refused by
     * {@code UNIQUE (intent_id, attempt_no)}) or serialized onto two DIFFERENT numbers, which that
     * UNIQUE alone cannot refuse at all — measured, deterministically, by an external review's
     * {@code BUILD FAILURE} at this task's base commit. The fence removes that dependency: exactly
     * one attempt row lands EITHER way, and the loser learns it as
     * {@link AttemptService.ConcurrentAttempt}, not as a raw constraint violation a step loop cannot
     * classify. Which of the two fence names PostgreSQL actually reports for the loser is measured and
     * recorded below, across repeated runs, rather than asserted as one fixed name (design §3.4's
     * classification note: the two names are not interchangeable in MECHANISM, only in caller-facing
     * OUTCOME). {@code AttemptService.open}'s own pre-check ({@code AttemptRepository#openState})
     * adds a THIRD possible provenance for the loser here: if its pre-check runs after the winner has
     * already committed, it is refused before ever attempting an insert, and names no PostgreSQL
     * constraint at all. Both provenances are legitimate for this race and are asserted separately
     * below, by {@link AttemptService.ConcurrentAttempt#provenance()}.
     */
    @Test
    void twoConcurrentOpensForOneIntentLandExactlyOneAttemptAndTheLoserIsToldSo() throws Exception {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        return service.open(f.tenant, f.apply, f.intent, null, BUDGET);
                    } catch (AttemptService.ConcurrentAttempt e) {
                        return e;
                    }
                }));
            }
            Object first = futures.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS);
            Object second = futures.get(1).get(60, java.util.concurrent.TimeUnit.SECONDS);

            long rows = java.util.stream.Stream.of(first, second)
                    .filter(o -> o instanceof AttemptRow).count();
            long losers = java.util.stream.Stream.of(first, second)
                    .filter(o -> o instanceof AttemptService.ConcurrentAttempt).count();
            AttemptService.ConcurrentAttempt loser = java.util.stream.Stream.of(first, second)
                    .filter(o -> o instanceof AttemptService.ConcurrentAttempt)
                    .map(o -> (AttemptService.ConcurrentAttempt) o)
                    .findFirst().orElse(null);
            String refusedBy = loser == null ? null : loser.constraintName();
            AttemptService.ConcurrentAttempt.Provenance provenance =
                    loser == null ? null : loser.provenance();
            System.out.println("MEASURED: concurrent open — " + rows + " row(s) opened, " + losers
                    + " ConcurrentAttempt, provenance " + provenance + ", refused by " + refusedBy
                    + "; attempts on the intent = " + attempts.count(f.tenant, f.intent.intentId()));
            assertEquals(1, attempts.count(f.tenant, f.intent.intentId()),
                    "exactly one attempt row lands regardless of whether the two opens collided on "
                            + "the same attempt_no or serialized onto different ones — BE-D20's fence "
                            + "removes the old dependency on which one happened");
            assertEquals(1, rows, "one winner. Got: " + first + " / " + second);
            assertEquals(1, losers, "one loser told ConcurrentAttempt — never a raw "
                    + "PgConstraintViolation. Got: " + first + " / " + second);
            // Widened, not weakened: the loser may have been caught by ITS OWN pre-check (no insert
            // ever attempted, so no PostgreSQL name to report) or by the database fence backstopping
            // a pre-check that ran before the winner committed (which DOES name one of the two
            // fences) — both are legitimate outcomes of this race. Stated honestly about what each
            // branch below actually proves: the PRE_CHECK branch's assertNull is guaranteed by
            // ConcurrentAttempt.preCheck's OWN construction (constraintName is hardcoded null there)
            // — it restates that invariant rather than exercising anything this race could falsify.
            // The DATABASE_FENCE branch's assertTrue IS the live check here: it fails the moment
            // PostgreSQL ever reports a name outside the two BE-D20 fences. What both branches
            // together still prove, regardless of which fires, is the assertions ABOVE this
            // comment — exactly one winner, exactly one ConcurrentAttempt loser, never a raw
            // PgConstraintViolation.
            if (provenance == AttemptService.ConcurrentAttempt.Provenance.PRE_CHECK) {
                assertNull(refusedBy,
                        "the pre-check path issued no insert — PostgreSQL named nothing. Got: "
                                + refusedBy);
            } else {
                assertEquals(AttemptService.ConcurrentAttempt.Provenance.DATABASE_FENCE, provenance,
                        "a ConcurrentAttempt must carry one of the two known provenances");
                assertTrue(
                        java.util.List.of(Constraints.ATTEMPT_NO_UNIQUE, Constraints.ATTEMPT_OPEN_FENCE)
                                .contains(refusedBy),
                        "the database-fence path must name one of the two fences BE-D20 classifies "
                                + "— got " + refusedBy);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aNonPositiveAttemptNumberIsRefusedByAttemptNoPositive() {
        Fixture f = new Fixture();
        for (short bad : new short[] {0, -1}) {
            Refusals.assertViolation(Refusals.CHECK_VIOLATION, "attempt_no_positive",
                    () -> attempts.open(f.tenant, f.apply, f.attemptRow(bad, Step.MATERIALIZE,
                            null)));
        }
        // The neighbouring positive control: attempt_no 1 is accepted, so the refusals above are
        // the CHECK and not a malformed row.
        attempts.open(f.tenant, f.apply, f.attemptRow((short) 1, Step.MATERIALIZE, null));
        assertEquals(1, attempts.count(f.tenant, f.intent.intentId()));
    }

    // -------------------------------------------------------------------------------------------
    // (i): the triple FK — an attempt may not borrow another request's intent.
    // -------------------------------------------------------------------------------------------

    @Test
    void anAttemptReferencingAnotherRequestsIntentIsRefusedByTheTripleForeignKey() {
        Fixture a = new Fixture();
        Fixture b = a.sibling();

        // Same tenant, same step (so the (intent_id, step) FK is satisfied), wrong apply_id.
        AttemptRow borrowed = new AttemptRow(Uuid7.next(), b.intent.intentId(), Step.MATERIALIZE,
                (short) 1, null, Instant.now(), null, Instant.now().plusSeconds(60), null, null,
                null, null);
        PgConstraintViolation v = Refusals.assertViolation(Refusals.FOREIGN_KEY_VIOLATION,
                "apply_attempt_tenant_id_apply_id_intent_id_fkey",
                () -> attempts.open(a.tenant, a.apply, borrowed));
        assertEquals("apply_attempt", v.table());

        // Positive control: the identical row under its OWN request is accepted.
        attempts.open(b.tenant, b.apply, borrowed);
        assertEquals(1, attempts.count(b.tenant, b.intent.intentId()));
    }

    // -------------------------------------------------------------------------------------------
    // The two per-step / per-time CHECKs.
    // -------------------------------------------------------------------------------------------

    /**
     * A refused row must not travel out through the exception — not its message, and not its cause
     * chain either. PostgreSQL's error carries {@code DETAIL: Failing row contains (…)}, the whole
     * rejected row, jsonb included. {@code PgConstraintViolation} once embedded that text in its own
     * message; then it carried the driver exception as its CAUSE, which is the same leak one hop
     * later, because ordinary application logging is {@code log.error("persistence failure", ex)} and
     * Logback prints the cause chain (both measured in plan 004's reviews). So the assertion here is
     * made where the leak would actually happen — on what a logger EMITS for the exception — and not
     * on {@code getMessage()} alone.
     */
    @Test
    void aRefusedRowsContentNeverReachesALoggerThroughThePgConstraintViolation() {
        Fixture f = new Fixture();
        // Identifier-shaped, so PreBaseline accepts it — and SHORT, because PostgreSQL truncates each
        // value in `DETAIL: Failing row contains (…)` at 64 characters (measured: a 15-char sentinel
        // after the 52-char jsonb prefix came back as "SENTINELROW..."), which would make the
        // precondition below fail for the wrong reason.
        String sentinel = "SNTL7c2e";
        PreBaseline baseline = new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), sentinel);
        AttemptRow row = f.attemptRow((short) 1, Step.MATERIALIZE, baseline);
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttemptIT.class);

        // Precondition, through the SAME insert issued raw: PostgreSQL's own exception, logged the
        // ordinary way, DOES echo the sentinel — otherwise the assertion below is vacuous.
        LogCapture.Captured<Throwable> raw = LogCapture.during(() -> {
            Throwable t = assertThrows(Throwable.class, () -> ApplyEngineHarness.jdbc().update(
                    "INSERT INTO sm_data.apply_attempt (attempt_id, tenant_id, apply_id, intent_id,"
                            + " step, attempt_no, pre_baseline, started_at, step_deadline)"
                            + " VALUES (:attempt, :t, :a, :intent, :step, :no,"
                            + " CAST(:baseline AS jsonb), :started, :deadline)",
                    new MapSqlParameterSource()
                            .addValue("attempt", row.attemptId())
                            .addValue("t", f.tenant.value())
                            .addValue("a", f.apply.value())
                            .addValue("intent", row.intentId())
                            .addValue("step", row.step().name())
                            .addValue("no", row.attemptNo())
                            .addValue("baseline", Evidence.write(baseline))
                            .addValue("started", Sql.at(row.startedAt()))
                            .addValue("deadline", Sql.at(row.stepDeadline()))));
            log.error("persistence failure", t);
            return t;
        });
        assertEquals(Refusals.CHECK_VIOLATION, Refusals.unwrap(raw.value()).getSQLState(),
                "the raw probe must be refused for the reason under test");
        String rawLine = raw.firstContaining("persistence failure");
        assertNotNull(rawLine, "the ERROR line must have been captured");
        assertTrue(rawLine.contains(sentinel) && rawLine.contains("Failing row"),
                "precondition: a logged driver exception DOES echo the failing row. Line was: " + rawLine);

        // The repository path: the reshaped exception, logged the same way.
        LogCapture.Captured<PgConstraintViolation> reshaped = LogCapture.during(() -> {
            PgConstraintViolation v = Refusals.assertViolation(Refusals.CHECK_VIOLATION,
                    "baseline_only_for_lifecycle", () -> attempts.open(f.tenant, f.apply, row));
            log.error("persistence failure", v);
            return v;
        });
        PgConstraintViolation v = reshaped.value();
        String reshapedLine = reshaped.firstContaining("persistence failure");
        System.out.println("MEASURED: reshaped refusal logged as → " + reshapedLine);

        assertNull(v.getCause(), "no driver exception may be attached — Logback would print it");
        assertNotNull(reshapedLine);
        assertFalse(reshapedLine.contains(sentinel),
                "the rejected row reached a logger through the reshaped exception. Line was: "
                        + reshapedLine);
        assertFalse(reshapedLine.contains("Failing row"),
                "…and PostgreSQL's DETAIL line must not appear at all. Line was: " + reshapedLine);
        assertFalse(reshaped.text().contains("Failing row"),
                "…on ANY captured line, not only the one this test logged. Log was: " + reshaped.text());
        assertTrue(v.getMessage().contains("baseline_only_for_lifecycle")
                        && v.getMessage().contains("apply_attempt"),
                "…while still naming what refused and where. Was: " + v.getMessage());

        // A second surface this measurement exposed, and closed structurally: the capture runs the
        // root logger at TRACE, and at TRACE Spring's StatementCreatorUtils prints EVERY bind value —
        // "Setting SQL statement parameter value: column index 7, parameter value [{…}]". The RAW
        // probe above bound its jsonb as a plain String, so its TRACE line carries the sentinel — the
        // precondition. The repository binds every jsonb document through Sql.redacted, a SqlValue
        // whose toString is a length, so the same TRACE line for the repository path shows the wrapper
        // and never the document. This does not depend on a logger level anyone can change.
        String rawBind = raw.firstContaining("parameter value [{");
        assertNotNull(rawBind, "Spring JDBC no longer logs bind values at TRACE? Re-measure and "
                + "rewrite this comment rather than deleting it");
        assertTrue(rawBind.startsWith("TRACE org.springframework.jdbc.core.StatementCreatorUtils")
                        && rawBind.contains(sentinel),
                "precondition: a PLAIN String bind is printed at TRACE, sentinel included. Was: "
                        + rawBind);
        String redactedBind = reshaped.firstContaining("parameter value [[redacted text");
        System.out.println("MEASURED: repository bind at TRACE → " + redactedBind);
        assertNotNull(redactedBind, "the repository must bind jsonb through Sql.redacted. Log was: "
                + reshaped.text());
        assertFalse(reshaped.text().contains(sentinel),
                "the document reached a logger through SOME captured line. Log was: " + reshaped.text());
    }

    @Test
    void aPreBaselineOnANonLifecycleStepIsRefusedByBaselineOnlyForLifecycle() {
        Fixture f = new Fixture();
        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "baseline_only_for_lifecycle",
                () -> attempts.open(f.tenant, f.apply, f.attemptRow((short) 1, Step.MATERIALIZE,
                        BASELINE)));

        // …and the positive control on the one step that may carry it.
        Fixture lifecycle = f.sibling();
        StepIntentRow intent = lifecycle.lifecycleIntent();
        attempts.open(lifecycle.tenant, lifecycle.apply, new AttemptRow(Uuid7.next(),
                intent.intentId(), Step.LIFECYCLE, (short) 1, BASELINE, Instant.now(), null,
                Instant.now().plusSeconds(60), null, null, null, null));
        assertEquals(1, attempts.count(lifecycle.tenant, intent.intentId()));
        assertEquals(BASELINE,
                attempts.latest(lifecycle.tenant, intent.intentId()).orElseThrow().preBaseline(),
                "the typed baseline round-trips: resume compares against THIS, so a lossy "
                        + "serialization would make the LIFECYCLE case undecidable");
    }

    @Test
    void anEndTimeBeforeTheStartTimeIsRefusedByAttemptTimeOrdered() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        Refusals.assertViolation(Refusals.CHECK_VIOLATION, "attempt_time_ordered",
                () -> attempts.close(f.tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, HASH,
                        null, null, open.startedAt().minusSeconds(60)));

        assertEquals(1, attempts.close(f.tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, HASH,
                        null, null, open.startedAt().plusSeconds(1)),
                "the same close with a sane end time is accepted");
    }

    @Test
    void anUnknownOutcomeCannotBeWrittenBecauseTheModelIsAnEnumAndTheColumnHasOutcomeKnown() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "outcome_known",
                () -> ApplyEngineHarness.jdbc().update(
                        "UPDATE sm_data.apply_attempt SET outcome = 'MAYBE', ended_at = now()"
                                + " WHERE tenant_id = :t AND attempt_id = :a",
                        new MapSqlParameterSource().addValue("t", f.tenant.value())
                                .addValue("a", open.attemptId())));
    }

    // -------------------------------------------------------------------------------------------
    // R9 / R10 — closing two findings from the external review of Task 3's AttemptRepository.
    // -------------------------------------------------------------------------------------------

    /**
     * R9: {@code close()} with no NEW runtime reference to report must not erase the reference
     * {@code open()} (or an earlier writer) already stored.
     *
     * <p>{@code AttemptService.open} never itself supplies a runtime reference — it is discovered only
     * once the adapter has acted — so this drives the repository directly, exactly as a future
     * adapter integration would: {@code open()} with a reference already known (measured against
     * Task 3's own finding: a container the adapter had already started before the journal caught up),
     * then a {@code close()} that has nothing NEW to add ({@code runtimeRef = null}) because the
     * adapter's failure path never touched the container. Before the fix, that {@code close} left the
     * column NULL — losing the one thing an operator needs to find the container's logs.
     */
    @Test
    void aCloseWithNoNewRuntimeReferenceLeavesTheOneOpenAlreadyWrote() {
        Fixture f = new Fixture();
        RuntimeRef fromOpen = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "c-1");
        AttemptRow opened = new AttemptRow(Uuid7.next(), f.intent.intentId(), Step.MATERIALIZE,
                (short) 1, null, Instant.now(), null, Instant.now().plusSeconds(60), null, null,
                null, fromOpen);
        attempts.open(f.tenant, f.apply, opened);

        assertEquals(1, attempts.close(f.tenant, opened.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null, Instant.now()),
                "the close itself must succeed — it is the SURVIVAL of the reference under test, not "
                        + "whether the statement runs");

        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(fromOpen, latest.runtimeRef(),
                "a close() with nothing NEW to report must not erase what open() already wrote (R9)");
        assertEquals(AttemptOutcome.FAILED, latest.outcome());
    }

    /**
     * The other half of R9, stated so the fix is not read as "runtime_ref is append-only": a close
     * that DOES have a new reference still overwrites — {@code coalesce} only reaches for the stored
     * value when the new one is SQL NULL.
     */
    @Test
    void aCloseWithANewRuntimeReferenceStillOverwritesTheOneOpenWrote() {
        Fixture f = new Fixture();
        AttemptRow opened = new AttemptRow(Uuid7.next(), f.intent.intentId(), Step.MATERIALIZE,
                (short) 1, null, Instant.now(), null, Instant.now().plusSeconds(60), null, null,
                null, RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "c-1"));
        attempts.open(f.tenant, f.apply, opened);

        RuntimeRef fromClose = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "c-2");
        attempts.close(f.tenant, opened.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null,
                fromClose, Instant.now());

        assertEquals(fromClose,
                attempts.latest(f.tenant, f.intent.intentId()).orElseThrow().runtimeRef(),
                "a close() that DOES have a new reference overwrites — coalesce is not append-only");
    }

    /**
     * R10: a second {@code close()} on an already-closed attempt must be refused, not silently
     * rewrite the recorded outcome. Measured before the fix: a {@code SUCCEEDED} attempt closed a
     * second time with {@code FAILED} returned 1 row and the outcome flipped — nothing below the
     * runtime credential's plain {@code UPDATE} grant stopped it.
     */
    @Test
    void aSecondCloseOnAnAlreadyClosedAttemptIsRefusedAndTheFirstOutcomeSurvives() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        service.close(f.tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.close(f.tenant, open.attemptId(), AttemptOutcome.FAILED, null,
                        new ErrorClass("io.timeout"), null),
                "AttemptService.close must refuse a re-close rather than let it through as a no-op");
        assertTrue(e.getMessage().contains("updated 0 rows"), e.getMessage());

        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(AttemptOutcome.SUCCEEDED, latest.outcome(),
                "the SECOND close must not have reached the row — the FIRST outcome survives (R10)");
        assertEquals(HASH, latest.observedHash());
        assertNull(latest.errorClass(),
                "the second close's error class must never have been written");

        // The repository-level fact underneath the service's refusal: 0 rows, not an exception.
        assertEquals(0, attempts.close(f.tenant, open.attemptId(), AttemptOutcome.FAILED, null,
                        new ErrorClass("io.timeout"), null, Instant.now()),
                "the fence is in the WHERE clause: a second close reports 0 rows updated");
    }

    // -------------------------------------------------------------------------------------------
    // §3.3's compaction: fold, then delete. Both halves, in the order the design gives them.
    // -------------------------------------------------------------------------------------------

    @Test
    void theCompactionPairFoldsTheSummaryOntoATerminalRequestAndDeletesItsAttempts() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow one = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, one.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("io.timeout"), null);
        AttemptRow two = service.open(f.tenant, f.apply, f.intent, null, BUDGET);
        service.close(f.tenant, two.attemptId(), AttemptOutcome.SUCCEEDED, HASH, null, null);

        outcomes.start(f.tenant, f.apply);
        outcomes.land(f.tenant, f.apply, RequestState.APPLIED);

        String summary = "{\"MATERIALIZE\": {\"attempts\": 2, \"lastError\": \"io.timeout\"}}";
        assertEquals(1, ApplyEngineHarness.requests()
                .foldAttemptSummary(f.tenant, f.apply, summary));
        assertEquals(2, attempts.deleteForRequest(f.tenant, f.apply));
        assertEquals(0, attempts.count(f.tenant, f.intent.intentId()),
                "the request keeps its explanatory power in attempt_summary after its attempts are "
                        + "gone — that is why the fold precedes the delete");
        assertEquals(1, intents.forRequest(f.tenant, f.apply).size(),
                "intents live as long as their request: they are its identity, not its diagnostics");
    }

    // -------------------------------------------------------------------------------------------
    // I6: the evidence columns, and what the TYPES refuse before any SQL is built.
    // -------------------------------------------------------------------------------------------

    /**
     * The external review's finding 2, as a live check — and stated at the strength it actually has.
     *
     * <p>The old signatures took {@code String errorClass} and {@code String runtimeRefJson}, so what
     * these columns may hold was a rule about callers. It is now a rule about the type system, but a
     * rule about SHAPE: {@code token=SECRET-VALUE}, a {@code Bearer} header and a JSON blob are refused
     * because the grammars have no way to express {@code =}, a space, a quote or a brace, and the
     * journal row is provably untouched afterwards.
     *
     * <p><b>A bare compact token is accepted by shape</b>, and the last assertion here measures that
     * rather than leaving it to a javadoc. Keeping a secret out of the VALUE is the ADAPTER's
     * obligation: each adapter's {@link RuntimeRef} builder is the only writer of this column and must
     * be tested against sentinels drawn from its own inputs — docker inspect output, the process
     * environment, mounted files. That is the adapter contract test Plan 005 carries, and this test
     * exists partly to point at it.
     */
    @Test
    void aSecretShapedValueIsRefusedByTheTypeBeforeAnySqlAndNothingIsWritten() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> new ErrorClass("token=SECRET-VALUE"));
        assertTrue(err.getMessage().contains("[a-z]"), err.getMessage());
        IllegalArgumentException ref = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST, "PASSWORD=hunter2"));
        assertTrue(ref.getMessage().contains("[A-Za-z0-9._:/@+-]"), ref.getMessage());
        IllegalArgumentException header = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST,
                        "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"));
        assertTrue(header.getMessage().contains("[A-Za-z0-9._:/@+-]"), header.getMessage());
        IllegalArgumentException blob = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.of(RuntimeRef.Key.HOST, "{\"password\":\"hunter2\"}"));
        assertTrue(blob.getMessage().contains("[A-Za-z0-9._:/@+-]"), blob.getMessage());
        assertFalse(err.getMessage().contains("SECRET-VALUE"),
                "the refusal must not echo the value — otherwise the type moved the secret from the "
                        + "database into the log. Was: " + err.getMessage());
        assertFalse(ref.getMessage().contains("hunter2"), ref.getMessage());
        assertFalse(blob.getMessage().contains("hunter2"), blob.getMessage());

        AttemptRow still = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(open.attemptId(), still.attemptId());
        assertNull(still.outcome(), "the attempt is still OPEN — nothing was written");
        assertNull(still.runtimeRef(), "and runtime_ref is still NULL");
        assertNull(runtimeRefTextOf(open.attemptId()),
                "read raw, past the mapper: the column itself holds nothing");

        // The honest half, measured: what the SHAPE control does not catch. A compact JWT is
        // base64url and dots, so every character is a legal adapter identifier.
        RuntimeRef compact = assertDoesNotThrow(() -> RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID,
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc"));
        System.out.println("MEASURED: a bare compact token is ACCEPTED by shape — "
                + compact.fields().get(RuntimeRef.Key.CONTAINER_ID)
                + " — so the content control is the adapter's contract test, not this type");
    }

    @Test
    void aLegalClassificationAndRuntimeRefRoundTripThroughTheJournal() {
        Fixture f = new Fixture();
        AttemptService service = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = service.open(f.tenant, f.apply, f.intent, null, BUDGET);

        RuntimeRef ref = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service");
        service.close(f.tenant, open.attemptId(), AttemptOutcome.FAILED, null,
                new ErrorClass("docker.api.unreachable"), ref);

        AttemptRow latest = attempts.latest(f.tenant, f.intent.intentId()).orElseThrow();
        assertEquals(new ErrorClass("docker.api.unreachable"), latest.errorClass());
        assertEquals(ref, latest.runtimeRef());
        // Read raw: the stored document is the map and NOTHING else. Key ORDER is not asserted —
        // PostgreSQL's jsonb normalises it (shortest key first), so the insertion order RuntimeRef
        // keeps is a property of the Java value and of Evidence's output, not of the column.
        String stored = runtimeRefTextOf(open.attemptId());
        System.out.println("MEASURED: stored runtime_ref = " + stored);
        assertTrue(stored.contains("\"containerId\": \"9f2c1e4b7a30\""), stored);
        assertTrue(stored.contains("\"unitName\": \"solace-cache.service\""), stored);
        assertEquals(2, SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT count(*)::int FROM jsonb_object_keys((SELECT runtime_ref FROM"
                                + " sm_data.apply_attempt WHERE attempt_id = :a))",
                        new MapSqlParameterSource("a", open.attemptId()), Integer.class),
                "two keys, no smuggled third");
    }

    // -------------------------------------------------------------------------------------------

    /** The runtime_ref column as text, read past the row mapper. */
    private static String runtimeRefTextOf(UUID attemptId) {
        return SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT runtime_ref::text FROM sm_data.apply_attempt WHERE attempt_id = :a",
                new MapSqlParameterSource("a", attemptId), String.class);
    }

    private static boolean visibleOnAnotherConnection(UUID attemptId) {
        return Boolean.TRUE.equals(SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_attempt WHERE attempt_id = :a)",
                new MapSqlParameterSource("a", attemptId), Boolean.class));
    }

    /** A fresh tenant with one open apply and one MATERIALIZE intent on it. */
    private static final class Fixture {

        private final TenantId tenant;
        private final InstanceId instance;
        private final ApplyId apply;
        private final StepIntentRow intent;

        Fixture() {
            this(new TenantId(SmDataPostgres.freshTenant()));
        }

        Fixture(TenantId tenant) {
            this.tenant = tenant;
            this.instance = new InstanceId(UUID.randomUUID());
            ScopeKey wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);
            this.apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    admission.admit(ApplyEngineHarness.apply(wide, 10))).applyId();
            this.intent = new StepIntentRow(Uuid7.next(), apply, Step.MATERIALIZE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), HASH,
                    Instant.now());
            intents.insert(tenant, intent);
        }

        /** Another request under the SAME tenant — what obligation (i)'s cross-reference needs. */
        Fixture sibling() {
            return new Fixture(tenant);
        }

        StepIntentRow lifecycleIntent() {
            StepIntentRow row = new StepIntentRow(Uuid7.next(), apply, Step.LIFECYCLE,
                    new ReplicaRef("cache-engine-1"), "idem-" + UUID.randomUUID(), null,
                    Instant.now());
            intents.insert(tenant, row);
            return row;
        }

        AttemptRow attemptRow(short attemptNo, Step step, PreBaseline preBaseline) {
            return new AttemptRow(Uuid7.next(), intent.intentId(), step, attemptNo, preBaseline,
                    Instant.now(), null, Instant.now().plusSeconds(60), null, null, null, null);
        }
    }
}
