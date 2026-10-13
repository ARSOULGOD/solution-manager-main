package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.cp.persistence.TargetRegistrationMain;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TargetConnectionMain;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.MeshProperties;
import com.solace.sm.dp.meshbridge.ThrowOnceMeshPublisher;
import com.solace.sm.dp.secret.EnvironmentMasterKeyProvider;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Everything one walkthrough run stands on (plan 010 T6.6), assembled once and shared by the live ITs and by the local
 * rehearsal: a PostgreSQL both schemas live in, the world {@link DeclareFixtures} seeds, the installing operator's
 * entitlement, a LOCAL broker for the SM mesh events, and the production {@code SolutionManagerApplication} booted as
 * production boots it - with one switchable difference per {@link Mode}.
 *
 * <h2>The two modes differ in exactly one thing: what answers for the host</h2>
 * <ul>
 *   <li>{@link Mode#LIVE}: the production {@code SshDockerRuntimeAdapter} over the operator-provided SSH host. Nothing is
 *       substituted. This is the only mode that can support the OR-46 claim.</li>
 *   <li>{@link Mode#REHEARSAL}: the production path is withdrawn ({@code sm.dp.apply.production.enabled=false}) and a
 *       certified in-memory host answers instead, behind the same decorator and the same REAL renderer, hasher, secret
 *       store, journal, worker, reconciler, emitter and broker. It exists so that every part of a scenario that is NOT the
 *       host (the operator commands, the REST walk, the events on a real broker, the fault windows, the restart sweep,
 *       the restore) is exercised here and the live run only has to trust the host.</li>
 * </ul>
 * Either way the production adapter bean is wrapped in a {@link FaultInjectingRuntimeAdapter} (a test-scope bean
 * post-processor, MINOR 16): armed, it injects faults; unarmed, it is transparent and counts.
 *
 * <p>Nothing here prints a secret: the local broker's per-run credentials, the Cache broker's password and the SSH key are
 * registered with the {@link EvidenceLog} as forbidden, and the key is read once into a buffer that is zeroised.
 */
final class E2eStack implements AutoCloseable {

    enum Mode {
        LIVE, REHEARSAL
    }

    /** The installing operator's entitlement; generous, because the resilience scenarios declare an instance each. */
    static final int MAX_INSTANCES = 20;

    private static final String ORIGINAL_REHEARSAL_HASH = "hmacsha256v1:" + "0".repeat(64);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-0000000e2e01");

    final Mode mode;
    /** True when the targets are loopback stubs (the rehearsals), which the strict address policy refuses by design. */
    private boolean loopbackTargets;
    final WalkthroughTarget target;
    final EvidenceLog evidence;
    final TwoSchemaTestDatabase.Provisioned provisioned;
    final TwoSchemaTestDatabase db;
    final World world;
    final LocalMeshBroker broker;

    private final Path temporaryKey;
    private final RoutingAdapter rehearsalHost;
    private final List<App> apps = new ArrayList<>();
    private UUID registeredTargetId;
    private Long firstWriteGeneration;

    private E2eStack(Mode mode, WalkthroughTarget target, EvidenceLog evidence, Path temporaryKey) {
        this.mode = mode;
        this.target = target;
        this.evidence = evidence;
        this.temporaryKey = temporaryKey;
        this.rehearsalHost = mode == Mode.REHEARSAL ? new RoutingAdapter() : null;
        if (rehearsalHost != null) {
            rehearsalHost.healthSignal = true;
        }
        char[] cacheBrokerPassword = target.brokerPassword();
        if (cacheBrokerPassword != null) {
            evidence.forbid(new String(cacheBrokerPassword));
            Arrays.fill(cacheBrokerPassword, '\0');
        }
        this.provisioned = mode == Mode.LIVE ? TwoSchemaTestDatabase.provisionStandalone()
                : TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        this.db = provisioned.db();
        this.world = DeclareFixtures.seed(db.ownerJdbc);
        // The installing operator's leg (owner ruling 2026-09-29 (Q-E)): the entitlement, as the operator command.
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", provisioned.properties().get("sm.control.runtime.password"),
                world.organization(), "cache", MAX_INSTANCES, DeclareFixtures.ADMIN);
        this.broker = LocalMeshBroker.start();
        evidence.forbid(broker.publisherPassword());
    }

    static E2eStack live(WalkthroughTarget target, EvidenceLog evidence) {
        return new E2eStack(Mode.LIVE, Objects.requireNonNull(target, "target"), evidence, null);
    }

    /**
     * The LIVE wiring over a target whose endpoints are loopback stubs (the metrics-collection rehearsal): the same stack,
     * with the permissive test address policy the other rehearsals use, because plan 013 T1.2 refuses loopback.
     */
    static E2eStack liveOnLoopbackStubs(WalkthroughTarget target, EvidenceLog evidence) {
        E2eStack stack = live(target, evidence);
        stack.loopbackTargets = true;
        return stack;
    }

    static E2eStack rehearsal(EvidenceLog evidence) {
        try {
            Path key = Files.createTempFile("sm-e2e-rehearsal-", ".key");
            Files.writeString(key, com.solace.sm.dp.inventory.SshTestKeys.ed25519OpenSsh(
                    com.solace.sm.dp.inventory.SshTestKeys.ed25519Pair()).text()); // generated now, never committed
            return new E2eStack(Mode.REHEARSAL, WalkthroughTarget.rehearsal(key), evidence, key);
        } catch (IOException e) {
            throw new IllegalStateException("could not create the rehearsal's throwaway key file");
        }
    }

    /**
     * What the host's adapter calls the replica: the SSH-Docker adapter's replica reference IS the container name; the
     * certified in-memory host of the rehearsal calls its one replica {@code replica-1}.
     */
    String expectedReplicaRef() {
        return mode == Mode.LIVE ? target.container() : "replica-1";
    }

    boolean isLive() {
        return mode == Mode.LIVE;
    }

    UUID organization() {
        return world.organization();
    }

    /** The target the operator commands registered, owned at the walkthrough environment. */
    UUID registeredTargetId() {
        return Objects.requireNonNull(registeredTargetId, "the target is registered after the first boot");
    }

    // ================================================================================================ boot

    /** Boots the production application against this stack's database and broker. */
    App boot() {
        return boot(Map.of());
    }

    App boot(Map<String, String> overrides) {
        return boot(overrides, false);
    }

    /**
     * @param throwOnceMesh replace the application's mesh publisher with one whose transport can be made to throw once
     *        (scenario 3); the real JCSMP transport still sits behind it
     */
    App boot(Map<String, String> overrides, boolean throwOnceMesh) {
        Map<String, Object> props = new LinkedHashMap<>(provisioned.properties());
        props.putAll(broker.meshProperties());
        props.put("server.port", "0");
        props.put("sm.gateway.auth-rate.capacity", "1000");
        props.put("sm.gateway.auth-rate.refill-per-minute", "6000");
        props.put("sm.dp.apply.worker.poll-interval", "200ms");
        props.put("sm.dp.apply.worker.shutdown-grace", "2s");
        props.put("sm.dp.apply.reconcile.initial-backoff", "1s");
        props.put("sm.dp.apply.reconcile.max-backoff", "2s");
        props.putAll(overrides);

        AtomicReference<FaultInjectingRuntimeAdapter> decorated = new AtomicReference<>();
        AtomicReference<ThrowOnceMeshPublisher> meshFault = new AtomicReference<>();
        ApplicationContextInitializer<ConfigurableApplicationContext> wiring = context -> {
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if ("runtimeAdapter".equals(beanName) && bean instanceof RuntimeAdapter adapter) {
                        FaultInjectingRuntimeAdapter wrapper = new FaultInjectingRuntimeAdapter(adapter);
                        decorated.set(wrapper);
                        return wrapper;
                    }
                    if (throwOnceMesh && "meshEventPublisher".equals(beanName) && bean instanceof JcsmpEventPublisher own) {
                        own.close();   // the one MeshBridgeConfiguration built; the replacement connects its own session
                        ThrowOnceMeshPublisher replacement = ThrowOnceMeshPublisher.connectingInBackground(
                                new MeshProperties(broker.smfHost(), broker.vpn(), broker.publisherUsername(),
                                        broker.publisherPassword()));
                        meshFault.set(replacement);
                        return replacement.publisher();
                    }
                    return bean;
                }
            });
            if (rehearsalHost != null) {
                context.getBeanFactory().registerSingleton("rehearsalHost", rehearsalHost);
            }
        };
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SolutionManagerApplication.class)
                .web(WebApplicationType.SERVLET).properties(props).initializers(wiring);
        if (loopbackTargets) {
            builder.sources(LoopbackTargetAddressConfiguration.class);
        }
        if (mode == Mode.REHEARSAL) {
            // the rehearsal's targets are loopback stubs; the address policy would (rightly) refuse them, so the rehearsal wires
            // the permissive test bean. LIVE keeps the strict policy: its target is a real host.
            builder.sources(RehearsalHost.class, LoopbackTargetAddressConfiguration.class);
        }
        ConfigurableApplicationContext context = builder.run();
        // Attached AFTER the run on purpose: Spring Boot's logging system re-initialises logback while the application
        // starts and resets every logger, so an appender attached before would watch nothing (measured: a vacuously green
        // "no complaint" assertion). What the startup sweep logs is therefore not watched; everything after is.
        LogWatch logs = new LogWatch(com.solace.sm.dp.apply.worker.ApplyWorker.class,
                com.solace.sm.dp.apply.events.ApplyEventEmitter.class, JcsmpEventPublisher.class,
                com.solace.sm.dp.apply.reconcile.IndeterminateReconciler.class,
                com.solace.sm.dp.metrics.collect.MetricsCollector.class);
        int port = Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port", Integer.class));
        App app = new App(context, port, Objects.requireNonNull(decorated.get(), "the runtimeAdapter bean was not decorated"),
                logs, meshFault.get());
        apps.add(app);
        return app;
    }

    /**
     * The rehearsal's host: registered explicitly, never component-scanned (it has no stereotype annotation), so a LIVE
     * boot in the same JVM can never see it. The in-memory runtime is the SAME instance across boots, so a host a killed
     * process leaves behind is the host the restarted one finds.
     */
    static final class RehearsalHost {

        @Bean
        RuntimeAdapter runtimeAdapter(@Qualifier("rehearsalHost") RoutingAdapter host) {
            return host;
        }

        /** The production renderer and the production hasher: a rehearsal that rendered fixed bytes would prove nothing. */
        @Bean
        RenderedConfigSource renderedConfigSource(ConfigGenerationRows rows, SecretResolver secrets,
                ConfigHasher hasher) {
            return new ConfigGenerationRenderedConfigSource(rows, secrets, hasher);
        }

        @Bean
        ApplyExecutor applyExecutor(AdmissionService admissionService, StepIntentService stepIntentService,
                AttemptService attemptService, OutcomeService outcomeService, RuntimeAdapter runtimeAdapter,
                RenderedConfigSource renderedConfigSource, HealthGate healthGate, ResumePlanner resumePlanner,
                ApplyEngineProperties applyEngineProperties, ApplyEventEmitter applyEventEmitter,
                com.solace.sm.dp.apply.engine.LivePlacements livePlacements) {
            return new ApplyExecutor(admissionService, stepIntentService, attemptService, outcomeService,
                    runtimeAdapter, renderedConfigSource, healthGate, resumePlanner, applyEngineProperties,
                    applyEventEmitter, livePlacements);
        }
    }

    // ============================================================================ the operator commands

    /**
     * Registers the target through the two operator commands, as an installing operator would (plan 007 Task 11): the
     * control-plane half ({@code TargetRegistrationMain.run}, as the bootstrap admin, the target OWNED AT THE WALKTHROUGH
     * ENVIRONMENT {@code [org, unit, env]}, S0 point 22) and the data-plane half ({@code TargetConnectionMain.register},
     * which seals the SSH key in the secret store and records the host, the pinned key and the envelope).
     *
     * <p>It must run after the FIRST boot: the data-plane half refuses a master key whose canary the application has not
     * yet written, so a mistyped key can never seal a credential nobody can open again.
     */
    UUID registerTarget() {
        String cpPassword = provisioned.properties().get("sm.control.runtime.password");
        char[] credential = DeclareFixtures.CREDENTIAL.toCharArray();
        UUID targetId;
        try {
            targetId = TargetRegistrationMain.run(db.jdbcUrl, "sm_cp_runtime", cpPassword,
                    world.organization().toString(), DeclareFixtures.ADMIN, credential, new TargetRegistrationMain.Spec(null, "e2e-walkthrough-" + mode.name().toLowerCase(),
                            "docker", "ssh-docker", world.unit(), world.envOne())).targetId();
        } finally {
            Arrays.fill(credential, '\0');
        }
        MasterKeyRing ring = new EnvironmentMasterKeyProvider(provisioned.properties()::get).load();
        TargetConnectionSpec spec = new TargetConnectionSpec(target.host(), target.port(), target.user(),
                target.fingerprint(), target.allowedConfigRoot(), target.allowedContainerPrefix());
        char[] key = readKey(target.keyFile());
        try {
            boolean recorded = TargetConnectionMain.register(db.jdbcUrl, "sm_dp_runtime",
                    provisioned.properties().get("sm.data.runtime.password"), ring, world.organization(), targetId,
                    spec, key, TargetConnectionMain.OPERATOR_ACTOR);
            assertTrue(recorded, "the connection record was written by this command");
        } finally {
            Arrays.fill(key, '\0');
        }
        registeredTargetId = targetId;
        return targetId;
    }

    /** Reads a key file into a buffer the caller zeroises; the intermediate bytes are zeroised here. */
    private static char[] readKey(Path file) {
        byte[] bytes = null;
        CharBuffer decoded = null;
        try {
            bytes = Files.readAllBytes(file);
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            char[] copy = new char[decoded.remaining()];
            decoded.get(copy);
            return copy;
        } catch (IOException e) {
            throw new IllegalStateException("the SSH key file could not be read");
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    // ================================================================================== the host and the file

    PlacementRef placementRef(UUID instance, UUID placement) {
        return new PlacementRef(new TenantId(world.organization()), new InstanceId(instance), new PlacementId(placement));
    }

    /** What the host held before this run touched it: kept in memory and in the evidence, never in the repository. */
    record OriginalConfig(String keyedHash, boolean running, String health) {
    }

    /** Reads the replica as it is RIGHT NOW through the production adapter (read-only). */
    RuntimeStatus hostStatus(App app, PlacementRef ref) {
        RuntimeAdapter adapter = app.adapter;
        List<TargetReplica> replicas = adapter.replicas(ref);
        assertEquals(1, replicas.size(), "an SSH-Docker placement is pinned to exactly one container");
        return adapter.status(replicas.get(0));
    }

    /**
     * Captures the host file's ORIGINAL keyed hash before any apply of this run writes. In the rehearsal the in-memory
     * host is first given an original file, so the restore step has something to bring back.
     */
    OriginalConfig captureOriginal(App app, PlacementRef ref) {
        if (rehearsalHost != null) {
            TargetReplica replica = app.adapter.replicas(ref).get(0);
            rehearsalHost.host(ref.placement().value()).setFile(replica, ORIGINAL_REHEARSAL_HASH);
        }
        RuntimeStatus status = hostStatus(app, ref);
        OriginalConfig original = new OriginalConfig(status.observedHash(),
                status.liveness() == com.solace.sm.dp.apply.runtime.Liveness.RUNNING, status.health().name());
        evidence.record("Original host state (taken before any write of this run)",
                "keyed hash of the host file: " + (original.keyedHash() == null ? "ABSENT" : original.keyedHash())
                        + "; container running: " + original.running() + "; health signal: " + original.health());
        return original;
    }

    /** The first generation this run caused a write for: its retained pre-image holds the original file. */
    synchronized void noteWriteGeneration(long generation) {
        if (firstWriteGeneration == null) {
            firstWriteGeneration = generation;
        }
    }

    /** Whether this run has caused a write whose retained pre-image the restore reads (see {@link #noteWriteGeneration}). */
    synchronized boolean hasWriteGeneration() {
        return firstWriteGeneration != null;
    }

    /** The in-memory host of the rehearsal (null live). */
    RoutingAdapter rehearsalHost() {
        return rehearsalHost;
    }

    /**
     * The final step of every live run: put the host's file back to what it was, through the engine's own restore path
     * ({@link RuntimeAdapter#restore}: it re-reads the retained pre-image of the first generation this run wrote, refuses
     * unless it still hashes to the original, writes it atomically), verify by an observation, and - when the run restarted
     * the container and {@code restore.restart} is on - restart it so the RUNNING Cache is back on the original config too.
     *
     * <p>Never throws: it is called from {@code @AfterAll}, where a failure must not hide the failure that got it called. A
     * restore it cannot make is returned as the MANUAL instruction instead, and recorded.
     */
    List<String> restoreOriginal(App app, PlacementRef ref, OriginalConfig original, boolean containerWasRestarted) {
        return restoreOriginal(app, ref, original, containerWasRestarted, target.configDirectory(), target.fileName());
    }

    /**
     * The same restore for a placement whose file is NOT the registered target's own (W4 review W4A-2: the second Cache of
     * the multi-placement gate lives in its own directory), so every MANUAL instruction it writes names {@code configDirectory}
     * and {@code fileName}, never the first Cache's.
     */
    List<String> restoreOriginal(App app, PlacementRef ref, OriginalConfig original, boolean containerWasRestarted,
            String configDirectory, String fileName) {
        List<String> report = new ArrayList<>();
        try {
            if (original == null) {
                report.add("no original was captured (the run failed before the first write), so nothing was written to the host");
                return recordRestore(report);
            }
            RuntimeAdapter adapter = app.adapter.delegate();
            TargetReplica replica = adapter.replicas(ref).get(0);
            String now = adapter.status(replica).observedHash();
            if (Objects.equals(now, original.keyedHash())) {
                report.add("the host file already holds the original keyed hash (" + now + "); no restore needed");
            } else if (original.keyedHash() == null) {
                report.add(manualAbsent(configDirectory, fileName));
            } else if (firstWriteGeneration == null) {
                report.add(manualNoGeneration(configDirectory, fileName, now, original.keyedHash()));
            } else {
                try {
                    adapter.restore(ref, firstWriteGeneration, original.keyedHash());
                    report.add("restored through the engine's own RuntimeAdapter.restore from the pre-image of generation "
                            + firstWriteGeneration);
                } catch (RuntimeAdapterException e) {
                    report.add(manualRefused(configDirectory, fileName, e.errorClass().token(), firstWriteGeneration,
                            original.keyedHash()));
                }
            }
            String after = adapter.status(replica).observedHash();
            report.add("host file keyed hash after the restore step: " + (after == null ? "ABSENT" : after)
                    + (Objects.equals(after, original.keyedHash()) ? " (EQUALS the original)" : " (DIFFERS from the original)"));
            if (containerWasRestarted && target.restartOnRestore() && Objects.equals(after, original.keyedHash())) {
                adapter.startOrUpdate(replica, firstWriteGeneration == null ? 0 : firstWriteGeneration);
                report.add("the container was restarted (this run restarted it) so it runs the original config again");
            } else if (containerWasRestarted) {
                report.add("NOTE: this run restarted the container onto the staged config; it still RUNS that config until "
                        + "the next restart (restore.restart was off, or the file was not restored)");
            }
        } catch (RuntimeException e) {
            report.add(manualFailed(configDirectory, fileName, e.getClass().getSimpleName()));
        }
        return recordRestore(report);
    }

    // The operator instructions, as pure functions of the PLACEMENT's own directory and file (W4 review W4A-2): an
    // instruction that names another placement's file would send the operator to delete or overwrite a healthy Cache.

    static String manualAbsent(String configDirectory, String fileName) {
        return "MANUAL: the host file was ABSENT before the run, and the engine's restore cannot journal an "
                + "absent target (rollback.target.absent). Delete " + configDirectory + "/" + fileName
                + " by hand (SM's retained pre-images are the hidden files named ." + fileName
                + ".sm-preimage-<generation> in the same directory)";
    }

    static String manualNoGeneration(String configDirectory, String fileName, String nowHash, String originalHash) {
        return "MANUAL: the file changed (" + nowHash + ") but this run never recorded a write generation; the "
                + "original keyed hash was " + originalHash + ", and its bytes are in the oldest ." + fileName
                + ".sm-preimage-<generation> file in " + configDirectory;
    }

    static String manualRefused(String configDirectory, String fileName, String errorToken, long generation,
            String originalHash) {
        return "MANUAL: the engine's restore refused (" + errorToken + "). Copy " + configDirectory + "/." + fileName
                + ".sm-preimage-" + generation + " over " + configDirectory + "/" + fileName
                + " (its keyed hash must be " + originalHash + ")";
    }

    static String manualFailed(String configDirectory, String fileName, String failure) {
        return "MANUAL: the restore step itself failed (" + failure + "); restore " + configDirectory + "/" + fileName
                + " from the oldest ." + fileName + ".sm-preimage-<generation> file in that directory, then restart the "
                + "container if it was restarted";
    }

    private List<String> recordRestore(List<String> report) {
        for (String line : report) {
            evidence.record("Restore of the host's original configuration", line);
        }
        return report;
    }

    // ========================================================================================== the database

    List<Map<String, Object>> journal(UUID applyId) {
        return db.ownerJdbc.queryForList("SELECT i.step, i.replica_ref, a.attempt_no, a.outcome, a.error_class,"
                + " a.observed_hash, i.config_hash FROM sm_data.apply_attempt a JOIN sm_data.apply_step_intent i"
                + " ON i.intent_id = a.intent_id WHERE i.tenant_id = :t AND i.apply_id = :a ORDER BY a.started_at,"
                + " a.attempt_no", new MapSqlParameterSource().addValue("t", world.organization())
                .addValue("a", applyId));
    }

    String requestState(UUID applyId) {
        return db.ownerJdbc.queryForObject("SELECT state FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id"
                + " = :a", new MapSqlParameterSource().addValue("t", world.organization()).addValue("a", applyId),
                String.class);
    }

    long count(String sql, Map<String, Object> params) {
        Long value = db.ownerJdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * Makes this run's generation numbers unique on the host. A retained pre-image is named by GENERATION only, and a fresh
     * database counts generations from 1, so a second run against the same host would find the first run's generation-1
     * pre-image, keep it (the adapter never overwrites a retained pre-image) and so "restore" a file that is not this run's
     * original. The floor is set on the instance row the stage command increments, as the database owner, exactly as the
     * fixtures seed everything else; no production code is involved.
     */
    long raiseGenerationFloor(UUID instance) {
        long floor = Instant.now().getEpochSecond() * 100;
        int updated = db.ownerJdbc.update("UPDATE sm_data.instance_state SET last_generation = :g WHERE tenant_id = :t"
                + " AND instance_id = :i AND last_generation = 0", new MapSqlParameterSource().addValue("g", floor)
                .addValue("t", world.organization()).addValue("i", instance));
        assertEquals(1, updated, "the floor is raised once, on an instance that has staged nothing");
        return floor;
    }

    // ================================================================================================= close

    @Override
    public void close() {
        for (int i = apps.size() - 1; i >= 0; i--) {
            apps.get(i).close();
        }
        broker.close();
        if (temporaryKey != null) {
            try {
                Files.deleteIfExists(temporaryKey);
            } catch (IOException e) {
                // a leftover throwaway file in the temp directory is not a failure
            }
        }
    }

    // ================================================================================================== App

    /** One booted application: the context, an HTTP client for it, and the decorated adapter the engine uses. */
    final class App implements AutoCloseable {

        final ConfigurableApplicationContext context;
        final int port;
        final Client http;
        final FaultInjectingRuntimeAdapter adapter;
        final LogWatch logs;
        /** Non-null only for a boot asked for {@code throwOnceMesh}. */
        final ThrowOnceMeshPublisher meshFault;
        private boolean closed;
        private final Map<String, Login> logins = new java.util.concurrent.ConcurrentHashMap<>();

        private App(ConfigurableApplicationContext context, int port, FaultInjectingRuntimeAdapter adapter,
                LogWatch logs, ThrowOnceMeshPublisher meshFault) {
            this.meshFault = meshFault;
            this.context = context;
            this.port = port;
            this.http = new Client(port);
            this.adapter = adapter;
            this.logs = logs;
        }

        <T> T bean(Class<T> type) {
            return context.getBean(type);
        }

        JcsmpEventPublisher mesh() {
            return context.getBean(JcsmpEventPublisher.class);
        }

        /**
         * One login per username per boot: a session outlives a whole scenario, and logging in on every poll would make
         * the resilience scenarios an Argon2id benchmark (measured: tens of CPU-seconds per minute of polling).
         */
        Login login(String username) throws Exception {
            Login cached = logins.get(username);
            if (cached == null) {
                cached = http.login(username);
                logins.put(username, cached);
            }
            return cached;
        }

        // ---- REST: the bootstrap admin's leg and the operator's --------------------------------------------

        /** {@code package.load} is the bootstrap admin's: it sits at organization scope (OR-14), beyond an environment-bound Operator. */
        JsonNode loadPackageAsAdmin() throws Exception {
            ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = http.post("/api/cp/solutions/load", login(DeclareFixtures.ADMIN), load);
            assertTrue(loaded.statusCode() / 100 == 2, "the admin's package load: " + loaded.statusCode());
            return DeclareFixtures.MAPPER.readTree(loaded.body());
        }

        HttpResponse<String> loadPackageAs(String username) throws Exception {
            ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            return http.post("/api/cp/solutions/load", login(username), load);
        }

        JsonNode get(String path, String username) throws Exception {
            HttpResponse<String> response = http.get(path, login(username));
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            return DeclareFixtures.MAPPER.readTree(response.body());
        }

        /** A declare body for the walkthrough's target: the container, the directory and the mount are the host's own. */
        ObjectNode declareBody(UUID solutionId, String name, String configDirectory) {
            ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
            body.put("solutionId", solutionId.toString());
            body.put("name", name);
            body.put("targetId", registeredTargetId().toString());
            body.put("declaredPackageVersion", 1);
            ObjectNode placement = body.putObject("placement");
            placement.put("containerName", target.container());
            placement.put("configDirectory", configDirectory);
            placement.put("containerConfigPath", target.containerConfigPath());
            placement.put("fileName", target.fileName());
            placement.put("healthPort", target.healthPort());
            body.putObject("acquisition").put("mode", target.acquisition());
            return body;
        }

        HttpResponse<String> declareRaw(String username, ObjectNode body) throws Exception {
            return http.post("/api/dp/environments/" + world.envOne() + "/instances", login(username), body);
        }

        Declared declare(UUID solutionId, String name) throws Exception {
            HttpResponse<String> declared = declareRaw(DeclareFixtures.OPERATOR_ONE,
                    declareBody(solutionId, name, target.configDirectory()));
            assertEquals(201, declared.statusCode(), declared.body());
            JsonNode body = DeclareFixtures.MAPPER.readTree(declared.body());
            return new Declared(UUID.fromString(body.get("instanceId").asText()),
                    UUID.fromString(body.get("placements").get(0).get("placementId").asText()),
                    body.get("stateVersion").asLong());
        }

        /** The instance's current {@code stateVersion}, the base the next stage must carry. */
        long stateVersion(UUID instance) throws Exception {
            return get("/api/dp/instances/" + instance, DeclareFixtures.OPERATOR_ONE).get("stateVersion").asLong();
        }

        /** Creates the Cache broker password secret from the environment-held value, zeroising the buffer. */
        UUID putCacheBrokerSecret() {
            char[] password = target.brokerPassword();
            if (password == null) {
                return null;
            }
            try {
                return bean(SecretStore.class).put(world.organization(), SecretPurpose.CONFIG_FIELD, password, ACTOR);
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        /**
         * Stages one generation as operator one and returns its number. With {@code brokerSecret} the Cache's own broker
         * settings are staged (the password only as {@code secretRef://<id>}, I6); without it the staged config keeps
         * {@code solace.enabled=false} and is secret-free.
         */
        long stage(UUID instance, String namespace, UUID brokerSecret) throws Exception {
            ObjectNode body = stageBody(stateVersion(instance), namespace, brokerSecret == null ? null : target.broker(),
                    brokerSecret);
            HttpResponse<String> staged = http.post("/api/dp/instances/" + instance + "/config",
                    login(DeclareFixtures.OPERATOR_ONE), body);
            assertEquals(200, staged.statusCode(), "stage: " + staged.statusCode());
            return DeclareFixtures.MAPPER.readTree(staged.body()).get("generation").asLong();
        }

        /** {@code 202 + applyId}; the id is what every later assertion is keyed by. */
        UUID submit(UUID instance, UUID placement, long generation, String strategy) throws Exception {
            HttpResponse<String> accepted = http.post("/api/dp/instances/" + instance + "/apply",
                    login(DeclareFixtures.OPERATOR_ONE), submitBody(generation, strategy, placement));
            assertEquals(202, accepted.statusCode(), "submitApply: " + accepted.statusCode());
            noteWriteGeneration(generation);
            return UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText());
        }

        JsonNode observe(UUID instance, UUID applyId) throws Exception {
            HttpResponse<String> response = http.get("/api/dp/instances/" + instance + "/apply/observe?applyId="
                    + applyId, login(DeclareFixtures.OPERATOR_ONE));
            assertEquals(200, response.statusCode(), "observeApply: " + response.statusCode());
            return DeclareFixtures.MAPPER.readTree(response.body()).get("items").get(0);
        }

        /** Polls the observation until {@code done}; the REST journal is the authority the console reads. */
        JsonNode await(UUID instance, UUID applyId, Predicate<JsonNode> done, Duration timeout, String what)
                throws Exception {
            Instant deadline = Instant.now().plus(timeout);
            JsonNode last = null;
            while (Instant.now().isBefore(deadline)) {
                last = observe(instance, applyId);
                if (done.test(last)) {
                    return last;
                }
                Thread.sleep(200);
            }
            return fail("request " + applyId + " never reached " + what + " within " + timeout + "; last seen "
                    + (last == null ? "nothing" : last.get("state").asText()));
        }

        JsonNode awaitTerminal(UUID instance, UUID applyId, Duration timeout) throws Exception {
            return await(instance, applyId, o -> o.get("terminal").asBoolean(), timeout, "a terminal state");
        }

        JsonNode ladder(UUID instance) throws Exception {
            return get("/api/dp/instances/" + instance + "/config-ladder", DeclareFixtures.OPERATOR_ONE);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            logs.close();
            // Closing a context whose mesh session is reconnecting parks in JCSMP's reconnect wait for as long as the
            // broker is away (FLAG T6.6-1: JcsmpEventPublisher.close -> producer.close). A test must not hang its whole
            // run on that, so the close runs on a daemon thread and is abandoned, and recorded, after 90 s.
            Thread closer = new Thread(() -> {
                try {
                    context.close();
                } catch (RuntimeException e) {
                    // a context whose worker thread was killed on purpose may complain on the way down
                }
            }, "e2e-app-close");
            closer.setDaemon(true);
            closer.start();
            try {
                closer.join(Duration.ofSeconds(90).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (closer.isAlive()) {
                evidence.record("FLAG T6.6-1 (blocked publish)", "closing the application context did not finish within "
                        + "90 s (the mesh publisher's close parks in JCSMP's reconnect wait while the broker is away); "
                        + "the close was abandoned on a daemon thread");
            }
        }
    }

    // ============================================================================================ stage bodies

    static ObjectNode submitBody(long generation, String strategy, UUID placement) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("generation", generation);
        body.put("strategy", strategy);
        body.put("placementId", placement.toString());
        return body;
    }

    /**
     * What the cache-2.4 package renders for the walkthrough's draft. With a Cache broker the draft and the rendered set
     * both carry {@code solace.*}, the password ONLY as a {@code secretRef://} reference; without one,
     * {@code solace.enabled=false} and nothing secret is staged.
     */
    static ObjectNode stageBody(long base, String namespace, WalkthroughTarget.CacheBroker broker, UUID brokerSecret) {
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
        if (broker == null) {
            rendered.put("solace.enabled", false);
        } else {
            String reference = "secretRef://" + Objects.requireNonNull(brokerSecret, "the broker password secret");
            draft.put("solace.enabled", true).put("solace.host", broker.host()).put("solace.vpn", broker.vpn())
                    .put("solace.username", broker.username()).put("solace.password", reference);
            rendered.put("solace.enabled", true);
            rendered.put("solace.host", broker.host());
            rendered.put("solace.vpn", broker.vpn());
            rendered.put("solace.username", broker.username());
            rendered.put("solace.password", reference);
            rendered.put("solace.messaging-mode", "MESSAGING");
            rendered.put("solace.management.enabled", false);
        }
        for (Map.Entry<String, Object> entry : rendered.entrySet()) {
            body.withArray("rendered").addObject().put("instanceKey", "i1")
                    .put("renderedPath", "cache-engine." + entry.getKey())
                    .set("value", DeclareFixtures.MAPPER.valueToTree(entry.getValue()));
        }
        return body;
    }
}
