package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-32's spelling against the places a hash is stored (plan 010 T5.1; D-KEY 7.2): the intent row, the attempt
 * row, and a resolution's evidence. No migration widens a hash grammar, so {@code hmacsha256v<n>:<64 hex>} must
 * already satisfy the model grammar and V002's {@code resolution_is_evidence} element predicate, written as
 * {@code sm_dp_runtime}. The refusal test pins WHY the spelling is what it is: {@code hmac-sha256:<id>:<hex>}
 * would have needed a new migration and a model change, and none is allocated.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeyedHashJournalCompatibilityIT {

    private static final String INSERT_REQUEST = "INSERT INTO sm_data.apply_request"
            + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
            + "  outage_confirmed_by, requested_by, requested_at, state, terminal_at, resolved_by,"
            + "  resolution_outcome, resolution_observed)"
            + " VALUES (:apply_id, :tenant_id, :instance_id, :placement_id, 'apply', 1, 'manual', NULL,"
            + " 'operator-1', :requested_at, 'INDETERMINATE_RESOLVED', :terminal_at, 'duty-operator', 'applied',"
            + " CAST(:resolution_observed AS jsonb))";

    private static final Duration BUDGET = Duration.ofMinutes(2);
    private static final ConfigHasher HASHER = TestHashers.keyed();

    private static AdmissionService admission;
    private static OutcomeService outcomes;
    private static NamedParameterJdbcTemplate runtime;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        admission = ApplyEngineHarness.admission();
        outcomes = ApplyEngineHarness.outcomes();
        runtime = ApplyEngineHarness.jdbc();
    }

    private static String keyedHash(TenantId tenant, String content) {
        return HASHER.hash(tenant, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String evidenceFor(String observedHash) {
        return "{\"stagedGeneration\": 1, \"replicas\": [{\"ref\": \"r1\", \"restarted\": true,"
                + " \"observedHash\": \"" + observedHash + "\"}]}";
    }

    @Test
    void theProductionSpellingIsAcceptedByTheModelGrammarAndTheV002Check() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId placement = new PlacementId(UUID.randomUUID());   // placement-scoped since V021 (BE-D44)
        ScopeKey wide = InventoryFixtures.registered(new ScopeKey(tenant, instance, placement));
        String hash = keyedHash(tenant, "generation: 10\n");
        assertEquals(true, hash.matches("hmacsha256v1:[0-9a-f]{64}"), hash);

        // 1. the intent row carries the keyed hash as config_hash (the write-verification hash)
        ApplyId apply = assertInstanceOf(AdmissionOutcome.Admitted.class,
                admission.admit(ApplyEngineHarness.apply(wide, 10))).applyId();
        StepIntentRow intent = new StepIntentRow(Uuid7.next(), apply, placement, Step.MATERIALIZE, new ReplicaRef("r1"),
                "idem-" + UUID.randomUUID(), hash, Instant.now());
        ApplyEngineHarness.intents().insert(tenant, intent);

        // 2. the attempt row carries it as observed_hash (read-back evidence), through the model grammar
        AttemptService attemptsService = ApplyEngineHarness.attemptsService(3);
        AttemptRow open = attemptsService.open(tenant, apply, intent, null, BUDGET);
        attemptsService.close(tenant, open.attemptId(), AttemptOutcome.SUCCEEDED, hash, null, null);
        assertEquals(hash,
                ApplyEngineHarness.attempts().latest(tenant, intent.intentId()).orElseThrow().observedHash());

        // 3. an INDETERMINATE_RESOLVED resolution carries it in its evidence, through the typed resolver AND
        //    through V002's element predicate (resolveIndeterminate writes as sm_dp_runtime)
        outcomes.start(tenant, apply);
        outcomes.land(tenant, apply, RequestState.APPLY_INDETERMINATE);
        outcomes.resolveIndeterminate(tenant, apply,
                new com.solace.sm.dp.apply.model.Resolution("duty-operator",
                        com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                        new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(10,
                                List.of(new ReplicaObservation(new ReplicaRef("r1"), true, hash)))));
        assertEquals(RequestState.INDETERMINATE_RESOLVED,
                ApplyEngineHarness.requests().find(tenant, apply).orElseThrow().state());
    }

    @Test
    void aHashSpelledHmacDashSha256IsRefusedByBoth() {
        TenantId tenant = new TenantId(SmDataPostgres.freshTenant());
        String hex = keyedHash(tenant, "x").substring("hmacsha256v1:".length());
        String dashed = "hmac-sha256:1:" + hex;

        // the model grammar: a '-' and a second ':' are outside ^[a-z0-9]+:[0-9a-f]{16,128}$
        assertThrows(IllegalArgumentException.class,
                () -> new ReplicaObservation(new ReplicaRef("r1"), true, dashed));
        assertThrows(IllegalArgumentException.class, () -> new AttemptRow(Uuid7.next(), Uuid7.next(), Step.MATERIALIZE,
                (short) 1, null, Instant.now(), null, Instant.now().plusSeconds(60), null, dashed, null, null));

        // V002's resolution_is_evidence, with the model bypassed by hand-written SQL: the same spelling is refused
        // by the CHECK, and the production spelling is accepted by the very same statement (the control)
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant.value(), instance);
        ApplyEngineHarness.scopes().ensureScopeRow(tenant, new InstanceId(instance));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "resolution_is_evidence",
                () -> insertResolved(tenant, instance, evidenceFor(dashed)),
                "an hmac-sha256:<id>:<hex> element must be refused by the V002 predicate");
        assertDoesNotThrow(() -> insertResolved(tenant, instance, evidenceFor("hmacsha256v1:" + hex)));
    }

    private static void insertResolved(TenantId tenant, UUID instance, String evidence) {
        UUID placement = UUID.randomUUID();
        InventoryFixtures.registered(tenant.value(), instance, placement);   // V024: the request names a registered placement
        runtime.update(INSERT_REQUEST, new MapSqlParameterSource()
                .addValue("apply_id", UUID.randomUUID())
                .addValue("tenant_id", tenant.value())
                .addValue("instance_id", instance)
                .addValue("placement_id", placement)
                .addValue("requested_at", OffsetDateTime.now())
                .addValue("terminal_at", OffsetDateTime.now())
                .addValue("resolution_observed", evidence));
    }
}
