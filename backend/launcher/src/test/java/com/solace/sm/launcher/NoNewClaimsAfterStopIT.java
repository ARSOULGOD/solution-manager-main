package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.worker.ApplyWorker;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * MINOR fix (post-review): the other half of the plan's own
 * {@code shutdownDrainsInFlightWorkAndStopsClaimingNewWork} that
 * {@code ApplyWorkerPoisonAndDrainIT} never covered — that a STOPPED worker never takes a NEW
 * claim, not merely that an in-flight one is left alone. This admits a fresh {@code REQUESTED} row
 * only AFTER {@code worker.stop()} has already returned, and proves it stays {@code REQUESTED}
 * across several poll intervals the (stopped) worker would otherwise have used to claim it.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(NoNewClaimsAfterStopIT.TestApplyExecutorConfiguration.class)
class NoNewClaimsAfterStopIT {

    private static TwoSchemaTestDatabase database;

    private static final UUID TENANT = UUID.randomUUID();
    private static final PlacementRef PLACEMENT_REF = new PlacementRef(new TenantId(TENANT),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        database = TwoSchemaTestDatabase.provisionWithOwnExecutor(registry);
        // Several fast polls, so "never claimed" is a meaningful wait, not a lucky one.
        registry.add("sm.dp.apply.worker.poll-interval", () -> "80ms");
    }

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyWorker worker;

    @Test
    void aRowAdmittedAfterStopIsNeverClaimed() throws InterruptedException {
        // The worker booted running (nothing admitted yet); stop it before anything exists to claim.
        worker.stop();

        ScopeKey scope = PLACEMENT_REF.scope();
        DeclaredInstances.registered(database, scope);
        ApplyCommand command =
                new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null, "operator-1");
        ApplyId id = ((AdmissionResult.Admitted) executor.admit(command)).id();

        // Several poll intervals' worth of waiting -- a running worker would have claimed this
        // long before the sleep below returns.
        Thread.sleep(800);

        assertEquals(RequestState.REQUESTED,
                requests.find(new TenantId(TENANT), id).orElseThrow().state(),
                "a stopped worker must never claim a row admitted after stop() returned");
    }

    @TestConfiguration
    static class TestApplyExecutorConfiguration {

        @Bean
        RuntimeAdapter runtimeAdapter() {
            return new InMemoryRuntimeAdapter(PLACEMENT_REF, true, "replica-1");
        }

        @Bean
        RenderedConfigSource renderedConfigSource() {
            return (placement, generation) -> GENERATION_7;
        }

        @Bean
        ApplyExecutor applyExecutor(com.solace.sm.dp.apply.engine.AdmissionService admissionService,
                com.solace.sm.dp.apply.engine.StepIntentService stepIntentService,
                com.solace.sm.dp.apply.engine.AttemptService attemptService,
                com.solace.sm.dp.apply.engine.OutcomeService outcomeService,
                RuntimeAdapter runtimeAdapter, RenderedConfigSource renderedConfigSource,
                com.solace.sm.dp.apply.engine.HealthGate healthGate,
                com.solace.sm.dp.apply.engine.ResumePlanner resumePlanner,
                com.solace.sm.dp.apply.engine.ApplyEngineProperties applyEngineProperties) {
            return new ApplyExecutor(admissionService, stepIntentService, attemptService,
                    outcomeService, runtimeAdapter, renderedConfigSource, healthGate, resumePlanner,
                    applyEngineProperties);
        }
    }
}
