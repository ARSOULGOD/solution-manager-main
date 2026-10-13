package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ReconciliationLedger;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import com.solace.sm.dp.apply.persistence.AttemptRepository;
import com.solace.sm.dp.apply.persistence.SmDataProperties;
import com.solace.sm.dp.apply.persistence.StepIntentRepository;
import com.solace.sm.dp.config.LadderRecorder;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine services of design §2.2/§3.3 as beans, over the repositories and the transaction
 * template {@code ApplyPersistenceConfiguration} publishes.
 *
 * <p>A separate configuration in the {@code engine} package — not more {@code @Bean} methods on the
 * persistence one — so the dependency between the two packages points one way: {@code engine}
 * consumes {@code persistence}; {@code persistence} knows nothing of the services built on it. A
 * configuration class that instantiated services from inside the persistence package would have made
 * the two packages a cycle. That one-way direction holds today (verified by inspection: no
 * {@code apply.engine} import anywhere under {@code apply/persistence/}), but — unlike
 * {@code jdbcIsConfinedToApplyPersistence}, the {@code arch-tests} module's ArchUnit rule for the
 * data-plane/persistence boundary — <b>no gate enforces this engine/persistence direction</b>; nothing
 * here would catch a future edit that introduced the cycle. Plan 005 is where an ArchUnit rule for
 * this direction belongs, once it can carry its own planted-violation fixture and review. The future
 * application class {@code @Import}s both, deliberately, exactly as it imports
 * {@code MeshBridgeConfiguration}.
 *
 * <p>This class is the one place in {@code engine} that touches Spring's context API; the services
 * themselves see only {@link TransactionTemplate}.
 */
@Configuration(proxyBeanMethods = false)
public class ApplyEngineConfiguration {

    /**
     * The clock every engine service stamps rows with, as a BEAN rather than {@code Instant.now()}
     * scattered through the services: one seam, so a future test can freeze time in one place, and
     * UTC explicitly because {@code timestamptz} comparisons across a DST boundary are exactly the
     * kind of thing that works for eight months.
     */
    @Bean
    Clock smDataClock() {
        return Clock.systemUTC();
    }

    private static final Logger log = LoggerFactory.getLogger(ApplyEngineConfiguration.class);

    /**
     * The apply-side emitter ({@code ApplyEventsConfiguration}, imported by {@code DataPlaneConfiguration}). Optional
     * HERE only so the persistence/engine suites that boot this class without a mesh publisher keep booting: they
     * observe the journal, not the notices. A context with none logs one WARN, and the production boot has one - the
     * launcher-level boot test asserts it.
     */
    private static ApplyEventEmitter emitterOrSilent(ObjectProvider<ApplyEventEmitter> provider) {
        ApplyEventEmitter emitter = provider.getIfAvailable();
        if (emitter == null) {
            log.warn("no ApplyEventEmitter in this context: apply notices (apply-outcome, rollout) are not published");
            return ApplyEventEmitter.silent();
        }
        return emitter;
    }

    @Bean
    AdmissionService admissionService(TransactionTemplate smDataTransactions,
            ApplyScopeRepository applyScopeRepository, ApplyRequestRepository applyRequestRepository,
            Clock smDataClock, ObjectProvider<ApplyEventEmitter> applyEventEmitter) {
        return new AdmissionService(smDataTransactions, applyScopeRepository,
                applyRequestRepository, smDataClock, emitterOrSilent(applyEventEmitter));
    }

    @Bean
    OutcomeService outcomeService(TransactionTemplate smDataTransactions,
            ApplyScopeRepository applyScopeRepository, ApplyRequestRepository applyRequestRepository,
            Clock smDataClock, ObjectProvider<ApplyEventEmitter> applyEventEmitter,
            ObjectProvider<LadderRecorder> ladderRecorder, ObjectProvider<ReconciliationLedger> reconciliationLedger) {
        return new OutcomeService(smDataTransactions, applyScopeRepository, applyRequestRepository,
                smDataClock, emitterOrSilent(applyEventEmitter),
                ladderRecorder.getIfAvailable(LadderRecorder::none),
                reconciliationLedger.getIfAvailable(ReconciliationLedger::none));
    }

    @Bean
    StepIntentService stepIntentService(TransactionTemplate smDataTransactions,
            ApplyRequestRepository applyRequestRepository, StepIntentRepository stepIntentRepository,
            Clock smDataClock) {
        return new StepIntentService(smDataTransactions, applyRequestRepository,
                stepIntentRepository, smDataClock);
    }

    /**
     * The engine's own bounds. Not {@code SmDataProperties}: those are the persistence layer's, and
     * the step budget and the gate deadline are the step loop's.
     */
    @Bean
    ApplyEngineProperties applyEngineProperties() {
        return ApplyEngineProperties.defaults();
    }

    /**
     * The {@code rolling-health-gated} gate. A bean rather than a field of {@code ApplyExecutor}
     * because its two collaborators — the clock and the sleeper — are the two things a test needs to
     * control, and a gate built inside the executor could not be given either.
     */
    @Bean
    HealthGate healthGate(Clock smDataClock, ApplyEngineProperties applyEngineProperties) {
        return new HealthGate(smDataClock, Sleeper.real(), applyEngineProperties);
    }

    /** §2.2 rule 3's decision table. Stateless and pure, so one instance serves every caller. */
    @Bean
    ResumePlanner resumePlanner() {
        return new ResumePlanner();
    }

    /** {@code sm.data.apply.max-attempts}, default 3 — obligation (m)'s cap. */
    @Bean
    AttemptPolicy attemptPolicy(SmDataProperties props) {
        return new AttemptPolicy(props.apply().maxAttempts());
    }

    @Bean
    AttemptService attemptService(TransactionTemplate smDataTransactions,
            AttemptRepository attemptRepository, AttemptPolicy attemptPolicy, Clock smDataClock) {
        return new AttemptService(smDataTransactions, attemptRepository, attemptPolicy,
                smDataClock);
    }
}
