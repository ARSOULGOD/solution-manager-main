package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Plan 010 T6.3's launcher ITs share one thing: the production boot with the production adapter withdrawn
 * ({@code sm.dp.apply.production.enabled=false}) and a LOCAL one in its place, so the worker that picks up a
 * submission never opens a socket. The adapter is an in-memory one per placement behind a gate and a counter, so a
 * test can hold the worker, count every call it makes, and make the host "unreachable".
 */
final class ApplyItSupport {

    private ApplyItSupport() {
    }

    /** Every call enters {@link #enter}; a test can hold callers at a gate, count them, or make them fail. */
    static final class RoutingAdapter implements RuntimeAdapter {

        private final Map<PlacementRef, InMemoryRuntimeAdapter> adapters = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean unreachable;
        volatile CountDownLatch gate;
        /** Whether the in-memory runtime of a placement FIRST used from now on publishes a health signal (T6.9's probe). */
        volatile boolean healthSignal;

        /** The fault a placement's in-memory runtime is created with (T6.8: a start baseline nobody can read). */
        volatile InMemoryRuntimeAdapter.Fault newHostFault = InMemoryRuntimeAdapter.Fault.NONE;

        private InMemoryRuntimeAdapter of(PlacementRef p) {
            // by default no signal is declared (T6.3 item 3); a test that exercises the gate turns one on first
            return adapters.computeIfAbsent(p, ref -> {
                InMemoryRuntimeAdapter created = new InMemoryRuntimeAdapter(ref, healthSignal, "replica-1");
                created.fault(newHostFault);
                return created;
            });
        }

        /** The in-memory runtime behind a placement, created now if it has not been used yet (T10.4's tests seed the host file). */
        InMemoryRuntimeAdapter hostFor(PlacementRef placement) {
            return of(placement);
        }

        /** What {@code TransactionSynchronizationManager} said inside the LAST {@link #readConfig}: null before any. */
        volatile Boolean transactionDuringRead;

        /** The in-memory runtime behind one placement (it exists once the placement has been used). */
        InMemoryRuntimeAdapter host(UUID placementId) {
            return adapters.entrySet().stream().filter(e -> e.getKey().placement().value().equals(placementId))
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
        }

        private void enter() {
            calls.incrementAndGet();
            CountDownLatch held = gate;
            if (held != null) {
                try {
                    held.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (unreachable) {
                throw new RuntimeAdapterException(new ErrorClass("ssh.unreachable"));
            }
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef placement) {
            enter();
            return of(placement).replicas(placement);
        }

        @Override
        public PreflightResult prepare(PlacementRef placement, long generation) {
            enter();
            return of(placement).prepare(placement, generation);
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
            enter();
            return of(replica.placement()).materializeConfig(replica, generation, config);
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
                String expectedCurrentHash) {
            enter();
            return of(replica.placement()).rematerializeIfCurrent(replica, generation, config, expectedCurrentHash);
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
            enter();
            return of(replica.placement()).startOrUpdate(replica, generation);
        }

        @Override
        public LifecycleResult stop(TargetReplica replica) {
            enter();
            return of(replica.placement()).stop(replica);
        }

        @Override
        public RuntimeStatus status(TargetReplica replica) {
            enter();
            return of(replica.placement()).status(replica);
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
            enter();
            return of(placement).assessRollback(placement, generation, intendedHash);
        }

        @Override
        public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
            enter();
            return of(placement).restore(placement, generation, targetHash);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.ConfigReadBack readConfig(TargetReplica replica) {
            enter();
            transactionDuringRead = org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive();
            return of(replica.placement()).readConfig(replica);
        }
    }

    @TestConfiguration
    static class ExecutorConfiguration {

        @Bean
        RoutingAdapter runtimeAdapter() {
            return new RoutingAdapter();
        }

        @Bean
        RenderedConfigSource renderedConfigSource() {
            RenderedConfig config = TestHashers.rendered("namespace: orders\n".getBytes(StandardCharsets.UTF_8));
            return (placement, generation) -> config;
        }

        @Bean
        ApplyExecutor applyExecutor(com.solace.sm.dp.apply.engine.AdmissionService admissionService,
                com.solace.sm.dp.apply.engine.StepIntentService stepIntentService,
                com.solace.sm.dp.apply.engine.AttemptService attemptService,
                com.solace.sm.dp.apply.engine.OutcomeService outcomeService, RuntimeAdapter runtimeAdapter,
                RenderedConfigSource renderedConfigSource, com.solace.sm.dp.apply.engine.HealthGate healthGate,
                com.solace.sm.dp.apply.engine.ResumePlanner resumePlanner,
                com.solace.sm.dp.apply.engine.ApplyEngineProperties applyEngineProperties,
                com.solace.sm.dp.apply.engine.LivePlacements livePlacements) {
            // The registry's live placements (plan 008 S3, BE-D46): an instance-wide stop in these ITs addresses what the
            // instance HAS, exactly as production does.
            return new ApplyExecutor(admissionService, stepIntentService, attemptService, outcomeService,
                    runtimeAdapter, renderedConfigSource, healthGate, resumePlanner, applyEngineProperties,
                    com.solace.sm.dp.apply.events.ApplyEventEmitter.silent(), livePlacements);
        }
    }

    // ---- the stage body the cache-2.4 package renders with its defaults ---------------------------------------

    static ObjectNode stageBody(long base, String namespace) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("baseStateVersion", base);
        ObjectNode selected = body.putObject("componentSelection").putArray("instances").addObject();
        selected.put("instanceKey", "i1").put("type", "cache-engine");
        selected.putObject("layout").put("x", 10).put("y", 20);
        ObjectNode draft = body.putObject("draftValues").putObject("i1");
        draft.put("namespace", namespace).put("key-source", "TOPIC").put("max-size", 1000);
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("mode", "READ");
        rendered.put("provider", "caffeine");
        rendered.put("namespace", namespace);
        rendered.put("key-source", "TOPIC");
        rendered.put("max-size", 1000);
        rendered.put("solace.enabled", false);
        for (Map.Entry<String, Object> entry : rendered.entrySet()) {
            body.withArray("rendered").addObject().put("instanceKey", "i1")
                    .put("renderedPath", "cache-engine." + entry.getKey())
                    .set("value", DeclareFixtures.MAPPER.valueToTree(entry.getValue()));
        }
        return body;
    }

    /** One declared instance with a live placement; {@code version} is the instance's stateVersion at declare. */
    record Declared(UUID instance, UUID placement, long version) {
    }

    /** Loads the cache package (idempotent for the same content), declares an instance as operator one. */
    static Declared declare(Client http, DeclareFixtures.World world, String name, String container, int healthPort)
            throws Exception {
        ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        JsonNode loadedBody = DeclareFixtures.MAPPER.readTree(loaded.body());
        UUID solution = UUID.fromString(loadedBody.get("solutionId").asText());
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(solution, world.targetOne(), name, container, healthPort));
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode body = DeclareFixtures.MAPPER.readTree(declared.body());
        return new Declared(UUID.fromString(body.get("instanceId").asText()),
                UUID.fromString(body.get("placements").get(0).get("placementId").asText()),
                body.get("stateVersion").asLong());
    }

    /** Stages one generation as operator one and returns its number. */
    static long stage(Client http, UUID instance, long base, String namespace) throws Exception {
        HttpResponse<String> staged = http.post("/api/dp/instances/" + instance + "/config",
                http.login(DeclareFixtures.OPERATOR_ONE), stageBody(base, namespace));
        assertEquals(200, staged.statusCode(), staged.body());
        return DeclareFixtures.MAPPER.readTree(staged.body()).get("generation").asLong();
    }

    /** Polls {@code observeApply?applyId=} until the request is terminal; returns that observation. */
    static JsonNode awaitTerminal(Client http, Login login, UUID instance, String applyId, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.get(
                    "/api/dp/instances/" + instance + "/apply/observe?applyId=" + applyId, login);
            assertEquals(200, response.statusCode(), response.body());
            last = DeclareFixtures.MAPPER.readTree(response.body()).get("items").get(0);
            if (last.get("terminal").asBoolean()) {
                return last;
            }
            Thread.sleep(100);
        }
        fail("request " + applyId + " never reached a terminal state within " + timeout + "; last seen " + last);
        throw new AssertionError("unreachable");
    }
}
