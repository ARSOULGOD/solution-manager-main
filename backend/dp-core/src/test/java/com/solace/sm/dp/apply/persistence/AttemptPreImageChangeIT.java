package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.service.ApplyObservationRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 OR-108 (1), {@code sm_data} V015: the declared paths a changed pre-image differs at are persisted WITH the
 * attempt that found it, read back through the observation, and bounded by the database's own CHECK. A path is a path:
 * the CHECK refuses anything shaped like content (I6), a list over fifty, and a record on any step but the two that
 * assess or use a pre-image.
 */
@Testcontainers(disabledWithoutDocker = true)
class AttemptPreImageChangeIT {

    private static final Duration BUDGET = Duration.ofMinutes(2);

    private static NamedParameterJdbcTemplate su;
    private static AttemptRepository attempts;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        su = SmDataPostgres.superuserJdbc();
        attempts = ApplyEngineHarness.attempts();
    }

    private static final class Fixture {
        final TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        final InstanceId instance = new InstanceId(UUID.randomUUID());
        final ApplyId apply;
        // placement-scoped since V021 (BE-D44): an intent's placement is its request's own
        final PlacementId placement = new PlacementId(UUID.randomUUID());

        Fixture() {
            ScopeKey wide = InventoryFixtures.registered(new ScopeKey(tenant, instance, placement));
            apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                    ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(wide, 10))).applyId();
        }

        /** A CLOSED attempt of {@code step}, as the engine leaves one after assessing or restoring. */
        AttemptRow closedAttempt(Step step, AttemptOutcome outcome) {
            ReplicaRef replica = step.placementScoped() ? ReplicaRef.PLACEMENT : new ReplicaRef("cache-engine-1");
            String hash = step.carriesHash() ? "sha256:3333333333333333333333333333333333333333" : null;
            StepIntentRow intent = new StepIntentRow(Uuid7.next(), apply, placement, step, replica, "idem-" + UUID.randomUUID(),
                    hash, Instant.now());
            ApplyEngineHarness.intents().insert(tenant, intent);
            AttemptService service = ApplyEngineHarness.attemptsService(3);
            AttemptRow open = service.open(tenant, apply, intent, null, BUDGET);
            service.close(tenant, open.attemptId(), outcome, null,
                    outcome == AttemptOutcome.FAILED ? new ErrorClass("rollback.preimage.altered") : null, null);
            return open;
        }

        List<ApplyObservationRows.ObservedStep> observedSteps() {
            return new JdbcApplyObservationRepository(SmDataPostgres.runtimeJdbc())
                    .observe(tenant, instance, apply, null, 10).get(0).steps();
        }
    }

    // ---- persisted with the attempt, read back by the observation ---------------------------------------------------

    @Test
    void aChangeRecordedOnAnAttemptIsReadBackOnItsObservedStep() {
        Fixture f = new Fixture();
        AttemptRow assess = f.closedAttempt(Step.ROLLBACK_ASSESS, AttemptOutcome.SUCCEEDED);
        ChangedContent change = ChangedContent.of(List.of("cache-engine.ttl", "cache-engine.solace.host"), true);

        assertEquals(1, attempts.recordPreImageChange(f.tenant, assess.attemptId(), change));

        ApplyObservationRows.ObservedStep step = f.observedSteps().stream()
                .filter(s -> s.step() == Step.ROLLBACK_ASSESS).findFirst().orElseThrow();
        assertEquals(List.of("cache-engine.solace.host", "cache-engine.ttl"), step.changed().paths());
        assertTrue(step.changed().otherContentChanged());
        assertFalse(step.changed().pathsTruncated());
    }

    @Test
    void aStepWithNoRecordedChangeCarriesNone() {
        Fixture f = new Fixture();
        f.closedAttempt(Step.ROLLBACK_ASSESS, AttemptOutcome.SUCCEEDED);
        f.closedAttempt(Step.ROLLBACK, AttemptOutcome.FAILED);

        assertTrue(f.observedSteps().stream().allMatch(s -> s.changed() == null), "nothing recorded, nothing claimed");
    }

    @Test
    void theChangeIsRecordedOnceAndNeverRewritten() {
        Fixture f = new Fixture();
        AttemptRow rollback = f.closedAttempt(Step.ROLLBACK, AttemptOutcome.FAILED);

        assertEquals(1, attempts.recordPreImageChange(f.tenant, rollback.attemptId(),
                ChangedContent.of(List.of("cache-engine.ttl"), false)));
        assertEquals(0, attempts.recordPreImageChange(f.tenant, rollback.attemptId(),
                ChangedContent.of(List.of("cache-engine.max-size"), false)), "the first record stands");

        assertEquals(List.of("cache-engine.ttl"), f.observedSteps().stream().filter(s -> s.step() == Step.ROLLBACK)
                .findFirst().orElseThrow().changed().paths());
    }

    @Test
    void anotherTenantsAttemptIsInvisibleToTheRecord() {
        Fixture f = new Fixture();
        AttemptRow assess = f.closedAttempt(Step.ROLLBACK_ASSESS, AttemptOutcome.SUCCEEDED);

        assertEquals(0, attempts.recordPreImageChange(new TenantId(UUID.randomUUID()), assess.attemptId(),
                ChangedContent.of(List.of("cache-engine.ttl"), false)), "I5: tenant-bound");
        assertNull(f.observedSteps().stream().filter(s -> s.step() == Step.ROLLBACK_ASSESS).findFirst().orElseThrow()
                .changed());
    }

    // ---- the database's own bound -------------------------------------------------------------------------------------

    private static void rawSet(UUID attemptId, String json) {
        su.update("UPDATE sm_data.apply_attempt SET pre_image_change = CAST(:j AS jsonb) WHERE attempt_id = :a",
                new MapSqlParameterSource().addValue("j", json).addValue("a", attemptId));
    }

    private static String paths(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add("\"cache-engine.f" + i + "\"");
        }
        return String.join(",", out);
    }

    @Test
    void theShapeCheckRefusesWhatIsNotAnObservationOfPaths() {
        Fixture f = new Fixture();
        UUID attempt = f.closedAttempt(Step.ROLLBACK_ASSESS, AttemptOutcome.SUCCEEDED).attemptId();
        String ok = "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":false,\"otherContentChanged\":false}";

        for (String bad : List.of(
                "[]",
                "\"cache-engine.ttl\"",
                "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":false}",
                "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":false,\"otherContentChanged\":false,\"x\":1}",
                "{\"paths\":\"cache-engine.ttl\",\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[\"cache engine\"],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[\"password=hunter2\"],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[\"a\\nb\"],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[1],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[{\"a\":1}],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[],\"pathsTruncated\":false,\"otherContentChanged\":false}",
                "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":\"no\",\"otherContentChanged\":false}",
                "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":false,\"otherContentChanged\":null}",
                "{\"paths\":[" + paths(51) + "],\"pathsTruncated\":true,\"otherContentChanged\":false}")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "pre_image_change_shape", () -> rawSet(attempt, bad),
                    "refused: " + bad);
        }
        rawSet(attempt, ok);
        rawSet(attempt, "{\"paths\":[" + paths(50) + "],\"pathsTruncated\":true,\"otherContentChanged\":false}");
        rawSet(attempt, "{\"paths\":[],\"pathsTruncated\":false,\"otherContentChanged\":true}");
    }

    @Test
    void onlyTheStepsThatAssessOrUseAPreImageMayCarryOne() {
        Fixture f = new Fixture();
        String ok = "{\"paths\":[\"cache-engine.ttl\"],\"pathsTruncated\":false,\"otherContentChanged\":false}";
        for (Step step : List.of(Step.MATERIALIZE, Step.VERIFY, Step.PREFLIGHT)) {
            UUID attempt = f.closedAttempt(step, AttemptOutcome.SUCCEEDED).attemptId();
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "pre_image_change_only_for_rollback_steps",
                    () -> rawSet(attempt, ok), step.name());
        }
        rawSet(f.closedAttempt(Step.ROLLBACK, AttemptOutcome.FAILED).attemptId(), ok);
        rawSet(f.closedAttempt(Step.ROLLBACK_ASSESS, AttemptOutcome.SUCCEEDED).attemptId(), ok);
    }
}
