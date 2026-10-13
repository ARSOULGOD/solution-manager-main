package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import com.solace.sm.launcher.RealAgentHarness.Agent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * Plan 015 T4.4: the runtime-neutral adapter kit ({@link AbstractRuntimeAdapterContractTest}, the obligations every
 * {@link RuntimeAdapter} owes) run over the REAL Go {@code sm-agent}: the production {@code runtimeAdapter} bean of the real
 * {@link SolutionManagerApplication}, the real gateway, PostgreSQL 14 and a real Docker container per test. The simulated-agent
 * version is {@code AgentRuntimeAdapterContractTest} in dp-core; this class is the proof that the same obligations hold against the host.
 *
 * <p>Each test gets a FRESH fixture, as the kit requires: a new labelled container is started, discovered (OR-27) into a new placement
 * of the same agent, and waited for until the agent's lease names it. Nothing is seeded.
 *
 * <p>The kit calls the context-less operations, and the agent path insists on a journal identity ({@code agent_command} has a foreign key
 * to {@code apply_step_intent}). {@link Journal} plays the apply engine's part with the engine's own services: one real apply request per
 * generation (so the intent key is the same for the same step of the same generation and different for any other), made RUNNING and
 * given its intent rows as the worker does, and made terminal when the next generation starts (the in-flight fence allows one open
 * request per placement). The apply worker's poll is set to an hour so it cannot claim these requests: they are driven by hand.
 *
 * <p>Fails (never skips) without go, Docker, alpine and the Cache image. Run:
 * {@code mvn -o -f backend/pom.xml -pl launcher -am -Pagent-live verify -Dit.test=AgentRuntimeAdapterRealAgentContractIT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRuntimeAdapterRealAgentContractIT extends AbstractRuntimeAdapterContractTest {

    private RealAgentHarness h;
    private Agent agent;
    private final List<String> containers = new ArrayList<>();

    @BeforeAll
    void boot() throws Exception {
        h = RealAgentHarness.create(1, null, null);
        agent = h.agents.get(0);
        h.startDp(Map.of("sm.dp.apply.worker.poll-interval", "1h", "sm.dp.agent.max-placements-per-target", "200",
                "sm.dp.agent.readback-wait", "30s"));
        h.bringUp(agent);                  // the harness's own container is discovered too: a placement the kit never uses
    }

    @AfterAll
    void shutdown() throws Exception {
        for (String name : containers) {
            AgentTestSupport.run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(30));
        }
        if (h != null) {
            h.preserveLogs(Path.of("target", "contract-real-agent"));
            h.close();
        }
    }

    @Override
    protected Fixture newFixture() throws Exception {
        String name = "sm-k" + UUID.randomUUID().toString().substring(0, 8);
        Path conf = Files.createDirectories(agent.root.resolve(name));
        containers.add(name);
        AgentTestSupport.Result started = AgentTestSupport.startManagedContainer(name, "inst-" + name.substring(4), conf);
        assertTrue(started.ok(), started.output());
        UUID[] ids = new UUID[2];
        RealAgentHarness.await("the container " + name + " to be discovered", Duration.ofSeconds(90), () -> {
            List<Map<String, Object>> rows = h.jdbc().queryForList("select instance_id, placement_id from sm_data.agent_discovery "
                    + "where agent_id = ?::uuid and container_name = ? and state = 'REGISTERED'", agent.id, name);
            if (rows.isEmpty()) {
                return false;
            }
            ids[0] = (UUID) rows.get(0).get("instance_id");
            ids[1] = (UUID) rows.get(0).get("placement_id");
            return true;
        });
        RealAgentHarness.await("the agent's lease to name the placement", Duration.ofSeconds(90), () -> Boolean.TRUE.equals(
                h.jdbc().queryForObject("select coalesce(bool_or(? = any(placement_ids)), false) from sm_data.agent_lease "
                        + "where agent_id = ?::uuid and state = 'ACTIVE'", Boolean.class, ids[1], agent.id)) && h.active(agent));
        RealAgentHarness.await("the agent's first report of the container", Duration.ofSeconds(90), () -> h.jdbc().queryForObject(
                "select count(*) from sm_data.agent_report_watermark where agent_id = ?::uuid and placement_id = ?", Integer.class,
                agent.id, ids[1]) > 0);
        TenantId tenant = new TenantId(h.world.organization());
        ScopeKey scope = new ScopeKey(tenant, new InstanceId(ids[0]), new PlacementId(ids[1]));
        PlacementRef ref = new PlacementRef(tenant, new InstanceId(ids[0]), new PlacementId(ids[1]));
        RuntimeAdapter real = h.dp.getBean("runtimeAdapter", RuntimeAdapter.class);
        Journal journal = new Journal(h.dp.getBean(AdmissionService.class), h.dp.getBean(ApplyRequestRepository.class),
                h.dp.getBean(StepIntentService.class), scope);
        ConfigHasher hasher = h.dp.getBean(ConfigHasher.class);
        ConfigLocation location = new ConfigLocation(AgentTestSupport.CONTAINER_CONFIG_DIR, new WriterAccess.RemoteSftp(conf.toString()),
                AgentTestSupport.CONFIG_FILE_NAME, ConfigLocation.Mechanism.ADDITIONAL_LOCATION);
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(ids[1].toString()));
        java.util.function.Supplier<Instant> lastReport = () -> {
            List<java.sql.Timestamp> rows = h.jdbc().queryForList("select updated_at from sm_data.agent_report_watermark "
                    + "where agent_id = ?::uuid and placement_id = ?", java.sql.Timestamp.class, agent.id, ids[1]);
            return rows.isEmpty() ? Instant.EPOCH : rows.get(0).toInstant();
        };
        return new Fixture(new JournalContextAdapter(real, journal, lastReport), ref, List.of(replica), location, true, hasher,
                Map.of(Unsupported.ROLLBACK, "agent.rollback.unsupported", Unsupported.READ_CONFIG, "config.read.unsupported",
                        Unsupported.STOP_NOOP_REPORT, ""));
    }

    /** Real apply requests and intent rows for the kit's context-less calls (see the class comment). */
    static final class Journal {
        private final AdmissionService admission;
        private final ApplyRequestRepository requests;
        private final StepIntentService intents;
        private final ScopeKey scope;
        private final Map<Long, ApplyId> byGeneration = new HashMap<>();
        private ApplyId current;

        Journal(AdmissionService admission, ApplyRequestRepository requests, StepIntentService intents, ScopeKey scope) {
            this.admission = admission;
            this.requests = requests;
            this.intents = intents;
            this.scope = scope;
        }

        private synchronized ApplyId applyOf(long generation) {
            ApplyId existing = byGeneration.get(generation);
            if (existing != null) {
                return existing;
            }
            if (current != null) {
                requests.transition(scope.tenant(), current, RequestState.RUNNING, RequestState.STOPPED, Instant.now());
            }
            AdmissionOutcome outcome = admission.admit(new ApplyCommand(scope, Operation.STOP, null, null, null, "operator-kit"));
            if (!(outcome instanceof AdmissionOutcome.Admitted admitted)) {
                throw new AssertionError("the kit's apply request was not admitted: " + outcome);
            }
            requests.transition(scope.tenant(), admitted.applyId(), RequestState.REQUESTED, RequestState.RUNNING, null);
            byGeneration.put(generation, admitted.applyId());
            current = admitted.applyId();
            return admitted.applyId();
        }

        synchronized RuntimeOperationContext context(long generation, Step step, ReplicaRef replica, String hashOrNull) {
            ApplyId apply = applyOf(generation);
            String key = ApplyExecutor.idempotencyKey(apply, step, replica);
            StepIntentRow intent = intents.ensureIntent(scope.tenant(), apply, scope.placement(), step, replica, key, hashOrNull);
            return new RuntimeOperationContext(apply, intent.intentId(), key, UUID.randomUUID(), step);
        }
    }

    /**
     * {@code ContextInjectingAdapter}'s shape, over {@link Journal}: every context is backed by real journal rows.
     *
     * <p>One difference from the simulated agent, stated: what {@code status} answers on this path is the agent's REPORT, and a report
     * follows a host operation by up to a reporting interval. The kit reads {@code status} the moment an operation returns, as it can
     * against a runtime that is read live. So after a mutating operation the next {@code status} first waits (bounded) for the first
     * report the agent made after the operation returned. That wait is the harness's, not the adapter's: the engine reads back through
     * its own bounded wait ({@code sm.dp.agent.readback-wait}), proven by {@code RealAgentDockerE2EIT} and the failure matrix.
     */
    static final class JournalContextAdapter implements RuntimeAdapter {
        private final RuntimeAdapter delegate;
        private final Journal journal;
        private final java.util.function.Supplier<Instant> lastReport;
        private volatile Instant operated;

        JournalContextAdapter(RuntimeAdapter delegate, Journal journal, java.util.function.Supplier<Instant> lastReport) {
            this.delegate = delegate;
            this.journal = journal;
            this.lastReport = lastReport;
        }

        /**
         * Runs a mutating operation and remembers when it BEGAN: the agent reports straight after it finishes a host operation, before the
         * adapter even returns, so "a report after the operation" means after the start, not after the return.
         */
        private <T> T operated(java.util.function.Supplier<T> operation) {
            Instant began = Instant.now();
            try {
                return operation.get();
            } finally {
                operated = began;
            }
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            return delegate.replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            return delegate.prepare(placement, generation, journal.context(generation, Step.PREFLIGHT, ReplicaRef.PLACEMENT, null));
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
            return operated(() -> delegate.materializeConfig(replica, generation, config,
                    journal.context(generation, Step.MATERIALIZE, replica.ref(), config.hash())));
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
                String expectedCurrentHash) {
            return operated(() -> delegate.rematerializeIfCurrent(replica, generation, config, expectedCurrentHash,
                    journal.context(generation, Step.MATERIALIZE, replica.ref(), config.hash())));
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
            return operated(() -> delegate.startOrUpdate(replica, generation, journal.context(generation, Step.LIFECYCLE, replica.ref(), null)));
        }

        @Override
        public LifecycleResult stop(TargetReplica replica) {
            return operated(() -> delegate.stop(replica, journal.context(0, Step.STOP, replica.ref(), null)));
        }

        @Override
        public RuntimeStatus status(TargetReplica replica) {
            Instant since = operated;
            if (since != null) {
                RealAgentHarness.await("the agent's first report after the operation", Duration.ofSeconds(40),
                        () -> lastReport.get().isAfter(since));
                operated = null;
                try {
                    Thread.sleep(300);          // the observation is remembered just after the watermark commits
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.status(replica);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
            return delegate.assessRollback(placement, generation, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
            return delegate.restore(placement, generation, targetHash);
        }

        @Override
        public ConfigReadBack readConfig(TargetReplica replica) {
            return delegate.readConfig(replica);
        }
    }
}
