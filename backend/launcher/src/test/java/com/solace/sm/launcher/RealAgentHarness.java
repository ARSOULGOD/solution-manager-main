package com.solace.sm.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
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
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One PostgreSQL 14, one CA, N real Go agents and their real Docker targets, and a Data Plane the test starts, stops and restarts by
 * hand (plan 015 T4.4). The Data Plane is the real {@link SolutionManagerApplication} (control plane and data plane, the gateway
 * listener, the CP-backed issuers) with only {@link FaultInjection} added at the port seam.
 *
 * <p><b>Seeded through the product, not SQL.</b> The organization, environment, principals and entitlement are the install's operator
 * steps ({@link DeclareFixtures#seed}, {@link EntitlementMain}); everything an agent needs is made the way an operator makes it:
 * the package is loaded over HTTP, an enrolment is issued with {@code issueAgentEnrolment}, the real {@code sm-agent} enrols with that
 * token over mTLS, and each container that carries {@code com.solace.sm.instance} and {@code com.solace.sm.config-file} is
 * discovered (OR-27) into an instance and a placement with no hand-made row. Config generations are staged and applied through the
 * designer and apply endpoints. What still touches a table, and why, is named at each use:
 * <ul>
 *   <li>reads, to observe (commands, sessions, leases, samples, events);</li>
 *   <li><b>SQL GAP 1</b>: the control plane has no operation that sets a registration's lease time-to-live or admission margin
 *       ({@link #tuneLease}), nor one that narrows an agent's capabilities ({@code AgentFailureMatrixIT} M12).</li>
 * </ul>
 */
final class RealAgentHarness implements AutoCloseable {
    static final String CA_PASSWORD = AgentGatewayTestPki.PASSWORD;
    /** Distinct per run, so a leak scan can look for it: it is staged into every config the tests apply. */
    static final String PAYLOAD_MARKER = "zz-e2e-payload-marker-" + UUID.randomUUID().toString().substring(0, 8);

    /** One agent, its enrolment, its managed container and the instance and placement discovery made for it. */
    static final class Agent {
        final int index;
        final String container = "sm-m" + UUID.randomUUID().toString().substring(0, 8);
        final String label = "inst-" + UUID.randomUUID().toString().substring(0, 8);
        final Path root;
        final Path conf;
        final Path data;
        final Path log;
        final int adminPort;
        Process process;
        /** Set by {@link #enrol}: the agent id the control plane issued, and the one-time token (never logged). */
        String id;
        String token;
        UUID instance;
        UUID placement;

        Agent(int index, Path work) throws IOException {
            this.index = index;
            this.root = Files.createDirectories(work.resolve("root-" + index));
            this.conf = Files.createDirectories(root.resolve(container));
            this.data = work.resolve("agent-data-" + index);
            this.log = work.resolve("agent-" + index + ".log");
            this.adminPort = AgentTestSupport.freePort();
        }

        UUID agentId() {
            return UUID.fromString(id);
        }
    }

    final Path work;
    final AgentGatewayTestPki pki;
    final int gatewayPort;
    final Path agentBinary;
    final TwoSchemaTestDatabase db;
    final Map<String, String> databaseProperties;
    final World world;
    final List<Agent> agents = new ArrayList<>();
    final Integer leaseTtlSeconds;
    final Integer admissionMarginSeconds;
    ConfigurableApplicationContext dp;
    UUID solutionId;
    private int httpPort;
    private Client http;
    private final Map<String, Login> logins = new java.util.concurrent.ConcurrentHashMap<>();

    private RealAgentHarness(Path work, AgentGatewayTestPki pki, int gatewayPort, Path agentBinary,
            TwoSchemaTestDatabase.Provisioned database, World world, Integer leaseTtlSeconds, Integer admissionMarginSeconds) {
        this.work = work;
        this.pki = pki;
        this.gatewayPort = gatewayPort;
        this.agentBinary = agentBinary;
        this.db = database.db();
        this.databaseProperties = database.properties();
        this.world = world;
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.admissionMarginSeconds = admissionMarginSeconds;
    }

    /**
     * Provisions everything except the DP and the agent processes: the database and the install's operator steps, the CA, the built
     * {@code sm-agent}, and one running managed container per agent (not yet registered anywhere). Fails (never skips) when go, Docker,
     * alpine or the Cache image is missing ({@link AgentLive#require}).
     *
     * @param leaseTtlSeconds       {@code null} keeps the control plane's default (300 s); otherwise applied by {@link #tuneLease} (SQL GAP 1)
     * @param admissionMarginSeconds as above (default 5 s)
     */
    static RealAgentHarness create(int agentCount, Integer leaseTtlSeconds, Integer admissionMarginSeconds) throws Exception {
        AgentLive.require();
        // Docker Desktop shares only $HOME, so bind-mounted config directories live under it.
        Path work = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "sm-matrix-");
        RealAgentHarness harness = null;
        try {
            AgentGatewayTestPki pki = new AgentGatewayTestPki(Files.createDirectories(work.resolve("pki")), true);
            Path binary = work.resolve("sm-agent");
            AgentTestSupport.Result build = AgentTestSupport.buildAgent(binary);
            if (!build.ok()) {
                throw new IllegalStateException("go build of sm-agent failed: " + build.output());
            }
            TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
            World world = DeclareFixtures.seed(provisioned.db().ownerJdbc);
            // the install's step: the organization's entitlement to the solution, with room for every instance a run discovers
            EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime", provisioned.properties().get("sm.control.runtime.password"),
                    world.organization(), "cache", 200, DeclareFixtures.ADMIN);
            harness = new RealAgentHarness(work, pki, AgentTestSupport.freePort(), binary, provisioned, world,
                    leaseTtlSeconds, admissionMarginSeconds);
            for (int i = 0; i < agentCount; i++) {
                Agent agent = new Agent(i, work);
                harness.agents.add(agent);
                AgentTestSupport.Result started = AgentTestSupport.startManagedContainer(agent.container, agent.label, agent.conf);
                if (!started.ok()) {
                    throw new IllegalStateException("could not start the managed container: " + started.output());
                }
            }
            return harness;
        } catch (Throwable partial) {
            // A failure part-way tears down what was built (the CA keystore and server key under $HOME, the sm-m* containers): the caller
            // never receives a harness whose close() could do it.
            try {
                if (harness != null) {
                    harness.close();
                } else {
                    AgentTestSupport.deleteTree(work);
                }
            } catch (Exception cleanup) {
                partial.addSuppressed(cleanup);
            }
            throw partial;
        }
    }

    JdbcTemplate jdbc() {
        return db.ownerJdbc.getJdbcTemplate();
    }

    // ---- Data Plane lifecycle ----

    Map<String, String> dpProperties(Map<String, String> overrides) {
        Map<String, String> props = new LinkedHashMap<>(databaseProperties);
        props.put("server.port", "0");
        props.put("spring.main.banner-mode", "off");
        props.put("sm.gateway.auth-rate.capacity", "100000");
        props.put("sm.gateway.auth-rate.refill-per-minute", "600000");
        props.put("sm.agent.gateway.enabled", "true");
        props.put("sm.agent.gateway.bind-address", "127.0.0.1");
        props.put("sm.agent.gateway.port", String.valueOf(gatewayPort));
        props.put("sm.agent.gateway.certificate-file", pki.serverCertificate.toString());
        props.put("sm.agent.gateway.private-key-file", pki.serverKey.toString());
        props.put("sm.agent.gateway.trust-bundle-file", pki.trustBundle.toString());
        props.put("sm.agent.gateway.poll-interval", "200ms");
        props.put("sm.agent.gateway.revocation-check-interval", "1s");
        props.put("sm.agent.gateway.drain-window", "20s");
        props.put("sm.agent.gateway.drain-reconnect-min", "1s");
        props.put("sm.agent.gateway.drain-reconnect-spread", "6s");
        props.put("sm.control.agent-gateway.ca.key-store-file", pki.keystore.toString());
        props.put("sm.control.agent-gateway.ca.key-store-password", AgentGatewayTestPki.PASSWORD);
        props.put("sm.control.agent-gateway.ca.key-alias", AgentGatewayTestPki.CA_ALIAS);
        props.put("sm.control.agent-gateway.ca.connect-endpoint", "127.0.0.1:" + gatewayPort);
        props.put("sm.dp.agent.runtime.enabled", "true");
        props.put("sm.dp.apply.worker.poll-interval", "200ms");
        props.putAll(overrides);
        return props;
    }

    void startDp(Map<String, String> overrides) throws Exception {
        if (dp != null) {
            throw new IllegalStateException("the DP is already running");
        }
        // Highest precedence, like @DynamicPropertySource: the launcher's own application.properties (which defaults
        // sm.agent.gateway.enabled=false) would otherwise beat SpringApplicationBuilder's default properties.
        Map<String, Object> properties = new LinkedHashMap<>(dpProperties(overrides));
        dp = new SpringApplicationBuilder(SolutionManagerApplication.class, FaultInjection.Decorator.class)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new org.springframework.core.env.MapPropertySource("matrix", properties)))
                .run();
        httpPort = ((ServletWebServerApplicationContext) dp).getWebServer().getPort();
        http = new Client(httpPort);
        logins.clear();
        if (solutionId == null) {
            ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = http.post("/api/cp/solutions/load", login(DeclareFixtures.ADMIN), load);
            if (loaded.statusCode() / 100 != 2) {
                throw new IllegalStateException("package.load answered " + loaded.statusCode() + ": " + loaded.body());
            }
            solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(loaded.body()).get("solutionId").asText());
        }
    }

    /**
     * {@link #startDp} after a data plane process was SIGKILLed: the worker pool's single-holder guard is a database session lock, and
     * PostgreSQL releases a killed holder's session only once it notices the socket closed, so the start is retried for a few seconds.
     */
    void startDpWhenTheKilledOneIsReleased(Map<String, String> overrides) throws Exception {
        Exception last = null;
        for (int i = 0; i < 30; i++) {
            try {
                startDp(overrides);
                return;
            } catch (Exception e) {
                last = e;
                if (dp != null) {
                    throw e;
                }
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    // ---- the Data Plane as a real process: the only way to KILL it ----

    private Process dpProcess;

    /**
     * The same application, the repackaged jar {@code failsafe} runs after, started as its OWN JVM against the same database, gateway
     * port and CA, so that a test can SIGKILL it ({@link #killDpProcess}): closing an in-process context is a graceful stop (the engine's
     * worker finishes its failure path while the context closes) and proves a different thing. No {@link FaultInjection} here: that is an
     * in-JVM decorator. Use instead of {@link #startDp}; only one of the two runs at a time.
     */
    void startDpProcess(Map<String, String> overrides) throws Exception {
        if (dp != null || (dpProcess != null && dpProcess.isAlive())) {
            throw new IllegalStateException("a data plane is already running");
        }
        Path jar;
        try (var files = Files.list(Path.of("target"))) {
            jar = files.filter(p -> p.getFileName().toString().matches("launcher-.*-exec\\.jar")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("the repackaged launcher jar is not built"));
        }
        int port = AgentTestSupport.freePort();
        Map<String, String> props = dpProperties(overrides);
        props.put("server.port", String.valueOf(port));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        Map<String, String> environment = new LinkedHashMap<>();
        props.forEach((key, value) -> {
            if (key.equals("SM_DP_MASTER_KEY") || key.equals("SM_DP_MASTER_KEY_ID")) {
                environment.put(key, value);              // production reads the key from the environment or a file (OR-85)
            } else if (key.toLowerCase(java.util.Locale.ROOT).contains("password")) {
                // never a -D argument (visible in ps to other local users): Spring's relaxed binding reads the environment form
                environment.put(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'), value);
            } else if (!key.equals(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES)) {
                command.add("-D" + key + "=" + value);
            }
        });
        command.addAll(List.of("-jar", jar.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("dp-process.log").toFile()));
        builder.environment().putAll(environment);
        dpProcess = builder.start();
        httpPort = port;
        http = new Client(port);
        logins.clear();
        await("the data plane process to listen", Duration.ofSeconds(120), () -> {
            if (!dpProcess.isAlive()) {
                throw new AssertionError("the data plane process exited " + dpProcess.exitValue());
            }
            try (java.net.Socket gateway = new java.net.Socket("127.0.0.1", gatewayPort);
                    java.net.Socket console = new java.net.Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException notYet) {
                return false;
            }
        });
    }

    /** SIGKILL: no drain, no shutdown hook, no goodbye. */
    void killDpProcess() throws InterruptedException {
        if (dpProcess != null) {
            dpProcess.destroyForcibly();
            dpProcess.waitFor(15, TimeUnit.SECONDS);
            dpProcess = null;
        }
    }

    /** As {@link #awaitTerminal} for a data plane that is not in this JVM: reads the request's state row. */
    RequestState awaitTerminalInDatabase(ApplyId id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            List<String> rows = jdbc().queryForList("select state from sm_data.apply_request where apply_id = ?", String.class, id.value());
            state = rows.isEmpty() ? null : rows.get(0);
            if (state != null && RequestState.valueOf(state).isTerminal()) {
                return RequestState.valueOf(state);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("request " + id.value() + " not terminal within " + timeout + " (last " + state + ")");
    }

    /** Graceful: closes the context, which stops the gateway through its drain. */
    void stopDp() {
        if (dp != null) {
            dp.close();
            dp = null;
        }
    }

    Login login(String username) throws Exception {
        Login cached = logins.get(username);
        if (cached == null) {
            cached = http.login(username);
            logins.put(username, cached);
        }
        return cached;
    }

    /** The operations the console exposes, as one named user; the status and body are the caller's to assert. */
    HttpResponse<String> post(String path, String username, JsonNode body) throws Exception {
        return http.post(path, login(username), body == null ? DeclareFixtures.MAPPER.createObjectNode() : body);
    }

    JsonNode get(String path, String username) throws Exception {
        HttpResponse<String> response = http.get(path, login(username));
        if (response.statusCode() != 200) {
            throw new AssertionError(path + " answered " + response.statusCode() + ": " + response.body());
        }
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    // ---- enrolment, agents, discovery ----

    /**
     * {@code issueAgentEnrolment} for the agent's envelope (the config root is the agent's own directory, the prefix is {@code sm-}), as
     * the environment's administrator. Sets {@link Agent#id} and {@link Agent#token}; the token is single use and is never logged.
     */
    void enrol(Agent a) throws Exception {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("targetName", "edge-" + a.index + "-" + UUID.randomUUID().toString().substring(0, 6))
                .put("allowedConfigRoot", a.root.toString()).put("allowedContainerPrefix", "sm-")
                .put("solutionId", solutionId.toString()).put("packageVersion", 1);
        HttpResponse<String> issued = http.post("/api/cp/environments/" + world.envOne() + "/agent-enrolments",
                login(DeclareFixtures.ADMIN), body);
        if (issued.statusCode() != 201) {
            throw new IllegalStateException("issueAgentEnrolment answered " + issued.statusCode() + ": " + issued.body());
        }
        JsonNode json = DeclareFixtures.MAPPER.readTree(issued.body());
        a.id = json.get("agentId").asText();
        a.token = json.get("enrollmentToken").asText();
        if (leaseTtlSeconds != null || admissionMarginSeconds != null) {
            tuneLease(a, leaseTtlSeconds, admissionMarginSeconds);
        }
    }

    /**
     * <b>SQL GAP 1</b>: the lease time-to-live and the admission margin are columns of the control plane's registration with defaults
     * (300 s, 5 s) and no operation sets them: {@code issueAgentEnrolment} fixes them server-side. The lease scenarios need a short lease.
     */
    void tuneLease(Agent a, Integer ttlSeconds, Integer marginSeconds) {
        jdbc().update("update sm_control.agent_registration set lease_ttl_seconds = coalesce(?, lease_ttl_seconds), "
                + "admission_margin_seconds = coalesce(?, admission_margin_seconds) where agent_id = ?::uuid",
                ttlSeconds, marginSeconds, a.id);
    }

    void startAgent(Agent a, boolean enrolling) throws Exception {
        if (enrolling && a.token == null) {
            enrol(a);
        }
        launchAgent(agentBinary, pki.trustBundle, gatewayPort, a, enrolling ? a.token : null);
    }

    /** Starts the real sm-agent against {@code 127.0.0.1:gatewayPort}; no telemetry file, the DP pushes the declarations (AG-D28). */
    static void launchAgent(Path agentBinary, Path trust, int gatewayPort, Agent a, String enrolmentToken) throws IOException {
        List<String> command = new ArrayList<>(List.of(agentBinary.toString(), "--data-dir", a.data.toString(),
                "--connect-endpoint", "127.0.0.1:" + gatewayPort, "--trust-bundle", trust.toString(),
                "--admin-addr", "127.0.0.1:" + a.adminPort, "--roles", "control,telemetry", "--log-level", "debug"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(a.log.toFile()));
        if (enrolmentToken != null) {
            builder.environment().put("SM_AGENT_ENROLL_TOKEN", enrolmentToken);
        }
        a.process = builder.start();
    }

    /** A second process presenting the same enrolled identity (a cloned data dir): the hard split-brain case. */
    Process startAgentClone(Path cloneData, Path cloneLog, int adminPort) throws IOException {
        List<String> command = new ArrayList<>(List.of(agentBinary.toString(), "--data-dir", cloneData.toString(),
                "--connect-endpoint", "127.0.0.1:" + gatewayPort, "--trust-bundle", pki.trustBundle.toString(),
                "--admin-addr", "127.0.0.1:" + adminPort, "--roles", "control", "--log-level", "debug"));
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(cloneLog.toFile())).start();
    }

    void stopAgent(Agent a) throws InterruptedException {
        if (a.process != null && a.process.isAlive()) {
            a.process.destroy();
            if (!a.process.waitFor(15, TimeUnit.SECONDS)) {
                a.process.destroyForcibly();
            }
        }
    }

    void killAgent(Agent a) throws InterruptedException {
        if (a.process != null && a.process.isAlive()) {
            a.process.destroyForcibly();
            a.process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Waits until discovery (OR-27) has turned the agent's labelled container into a REGISTERED instance and placement, and records
     * their ids. Nothing is inserted: the agent reported the container, the data plane minted the rows, the control plane's completer
     * registered the governance node.
     */
    void awaitRegistered(Agent a, Duration timeout) {
        await("the container " + a.container + " to be discovered and REGISTERED", timeout, () -> {
            List<Map<String, Object>> rows = jdbc().queryForList("select instance_id, placement_id from sm_data.agent_discovery "
                    + "where agent_id = ?::uuid and state = 'REGISTERED' and container_name = ?", a.id, a.container);
            if (rows.isEmpty()) {
                return false;
            }
            a.instance = (UUID) rows.get(0).get("instance_id");
            a.placement = (UUID) rows.get(0).get("placement_id");
            return true;
        });
    }

    /** Enrol, start, discover, and wait for an ACTIVE session: the state every scenario starts from. */
    void bringUp(Agent a) throws Exception {
        startAgent(a, true);
        awaitRegistered(a, Duration.ofSeconds(120));
        await("the agent to be ACTIVE", Duration.ofSeconds(60), () -> active(a));
    }

    boolean active(Agent a) {
        String health = health(a);
        return health.contains("\"session_phase\":\"ACTIVE\"") && health.contains("\"authority_phase\":\"ACTIVE\"");
    }

    // ---- config generations and operations (the console's own endpoints; the engine's entry for what has no endpoint) ----

    /** Stages one secret-free generation of the agent's instance through {@code stageConfig}; returns its generation number. */
    long stage(Agent a, String namespace) throws Exception {
        long base = get("/api/dp/instances/" + a.instance, DeclareFixtures.OPERATOR_ONE).get("stateVersion").asLong();
        HttpResponse<String> staged = http.post("/api/dp/instances/" + a.instance + "/config", login(DeclareFixtures.OPERATOR_ONE),
                E2eStack.stageBody(base, namespace, null, null));
        if (staged.statusCode() != 200) {
            throw new AssertionError("stageConfig answered " + staged.statusCode() + ": " + staged.body());
        }
        return DeclareFixtures.MAPPER.readTree(staged.body()).get("generation").asLong();
    }

    /** {@code submitApply} as the operator: {@code 202}, the apply id. */
    ApplyId submitApply(Agent a, long generation, String strategy) throws Exception {
        HttpResponse<String> accepted = http.post("/api/dp/instances/" + a.instance + "/apply", login(DeclareFixtures.OPERATOR_ONE),
                E2eStack.submitBody(generation, strategy, a.placement));
        if (accepted.statusCode() != 202) {
            throw new AssertionError("submitApply answered " + accepted.statusCode() + ": " + accepted.body());
        }
        return new ApplyId(UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText()));
    }

    /** {@code stopInstance} as the operator. */
    ApplyId submitStop(Agent a) throws Exception {
        HttpResponse<String> accepted = http.post("/api/dp/instances/" + a.instance + "/stop", login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.MAPPER.createObjectNode().put("placementId", a.placement.toString()));
        if (accepted.statusCode() != 202) {
            throw new AssertionError("stopInstance answered " + accepted.statusCode() + ": " + accepted.body());
        }
        return new ApplyId(UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText()));
    }

    /**
     * Admits an operation through the apply engine's own entry point. Used for a restart, which the console has no endpoint for (the
     * engine's restart is reached by a strategy of an apply or a fleet action): the request is the same row an endpoint would write.
     */
    ApplyId admit(Agent a, Operation operation, Long generation, Strategy strategy) {
        ScopeKey scope = new ScopeKey(new TenantId(world.organization()), new InstanceId(a.instance), new PlacementId(a.placement));
        AdmissionResult result = dp.getBean(ApplyExecutor.class)
                .admit(new ApplyCommand(scope, operation, generation, strategy, null, "operator-matrix"));
        if (!(result instanceof AdmissionResult.Admitted admitted)) {
            throw new AssertionError("apply was not admitted: " + result);
        }
        return admitted.id();
    }

    /** The newest generation {@link #admitRestart} staged. */
    long lastStagedGeneration;
    private int restartSeq;

    /**
     * The engine admits no standalone restart (a RESTART request is {@code OperationUnsupported}); a host restart is the second step of a
     * restart-bearing apply. So this stages a fresh secret-free generation and submits it with {@code rolling-no-validation}: the agent
     * receives PREFLIGHT, APPLY (writes the file) and RESTART, in that order, as one apply request.
     */
    ApplyId admitRestart(Agent a) throws Exception {
        lastStagedGeneration = stage(a, "restart-" + (++restartSeq));
        return submitApply(a, lastStagedGeneration, "rolling-no-validation");
    }

    /** {@code submitApply} without asserting the status: for the refusals. */
    HttpResponse<String> trySubmitApply(Agent a, long generation, String strategy) throws Exception {
        return http.post("/api/dp/instances/" + a.instance + "/apply", login(DeclareFixtures.OPERATOR_ONE),
                E2eStack.submitBody(generation, strategy, a.placement));
    }

    RequestState awaitTerminal(Agent a, ApplyId id, Duration timeout) throws InterruptedException {
        ApplyRequestRepository requests = dp.getBean(ApplyRequestRepository.class);
        Instant deadline = Instant.now().plus(timeout);
        RequestState state = null;
        while (Instant.now().isBefore(deadline)) {
            state = requests.find(new TenantId(world.organization()), id).orElseThrow().state();
            if (state.isTerminal()) {
                return state;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("request " + id.value() + " not terminal within " + timeout + " (last " + state + ")");
    }

    /** The bytes the data plane rendered for a staged generation: what the agent must have written. */
    byte[] rendered(Agent a, long generation) {
        return dp.getBean(RenderedConfigSource.class).render(new PlacementRef(new TenantId(world.organization()),
                new InstanceId(a.instance), new PlacementId(a.placement)), generation).bytes();
    }

    String configOnHost(Agent a) throws IOException {
        Path file = a.conf.resolve(AgentTestSupport.CONFIG_FILE_NAME);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "<absent>";
    }

    // ---- observation ----

    /** The agent's own {@code /healthz} JSON (session phase, authority phase, halted reason), or {@code ""} if unreachable. */
    static String health(Agent a) {
        return http(a, "/healthz");
    }

    /** Sum of every {@code /metrics} sample whose line starts with {@code prefix}. */
    static double metric(Agent a, String prefix) {
        return http(a, "/metrics").lines().filter(l -> l.startsWith(prefix))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }

    /** An agent process's {@code /healthz} by its admin port (for a process that is not one of the harness's {@link Agent}s). */
    static String healthAt(int adminPort) {
        return http(adminPort, "/healthz");
    }

    private static String http(Agent a, String path) {
        return http(a.adminPort, path);
    }

    private static String http(int adminPort, String path) {
        try {
            return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + adminPort + path)).timeout(Duration.ofSeconds(3)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code state:outcome} of the command the apply produced for {@code operation}, the newest first row; {@code ABSENT} if none. */
    String commandState(ApplyId apply, String operation) {
        List<String> rows = jdbc().queryForList("select state || ':' || coalesce(outcome, '-') from sm_data.agent_command "
                + "where apply_id = ? and operation = ? order by created_at desc", String.class, apply.value(), operation);
        return rows.isEmpty() ? "ABSENT" : rows.get(0);
    }

    /** How many of the apply's commands the agent REFUSED (a refused step is retried by the engine, so the newest row may already be a success). */
    int refusals(ApplyId apply) {
        return jdbc().queryForObject("select count(*) from sm_data.agent_command where apply_id = ? and state = 'REFUSED'", Integer.class,
                apply.value());
    }

    /** Every command of the agent as {@code operation:state:outcome}, oldest first. */
    List<String> commandsOf(Agent a) {
        return jdbc().queryForList("select operation || ':' || state || ':' || coalesce(outcome, '-') from sm_data.agent_command "
                + "where agent_id = ?::uuid order by created_at, wire_generation", String.class, a.id);
    }

    int commandCount(Agent a) {
        return jdbc().queryForObject("select count(*) from sm_data.agent_command where agent_id = ?::uuid", Integer.class, a.id);
    }

    long sessionEpoch(Agent a) {
        List<Long> rows = jdbc().queryForList("select fencing_epoch from sm_data.agent_session where agent_id = ?::uuid",
                Long.class, a.id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** The control plane's audited enrolments of the agent (the data plane records none: the enrolment is the control plane's act). */
    int enrolments(Agent a) {
        // one enrolment writes more than one audited row (the token's consumption and the target it creates), under one correlation id
        return jdbc().queryForObject("select count(distinct correlation_id) from sm_control.change_audit "
                + "where audit_operation = 'AGENT_ENROLLED' and actor_agent_id = ?::uuid", Integer.class, a.id);
    }

    /** What the data plane did for one request, for a failure message: commands, attempts, state. */
    String diagnose(ApplyId id) {
        try {
            return "request " + dp.getBean(ApplyRequestRepository.class).find(new TenantId(world.organization()), id)
                    .map(r -> r.state().toString()).orElse("?") + "; commands "
                    + jdbc().queryForList("select operation || ':' || state || ':' || coalesce(outcome,'-') || ':' || coalesce(detail_code,'-') "
                    + "from sm_data.agent_command where apply_id = ? order by wire_generation", String.class, id.value())
                    + "; attempts " + jdbc().queryForList("select step || ':' || coalesce(outcome,'open') || ':' || coalesce(error_class,'') "
                    + "from sm_data.apply_attempt where apply_id = ? order by started_at", String.class, id.value());
        } catch (RuntimeException e) {
            return "(not readable: " + e.getMessage() + ")";
        }
    }

    int eventCount(Agent a, String code) {
        return jdbc().queryForObject("select count(*) from sm_data.agent_event where agent_id = ?::uuid and code = ?",
                Integer.class, a.id, code);
    }

    /** The newest raw value of a metric of the agent's placement, or {@code null}. */
    Double latestSample(Agent a, String metric) {
        List<Double> rows = jdbc().queryForList("select m.value from sm_metrics.metrics_raw m join sm_metrics.series s "
                + "using (tenant_id, series_id) where s.placement_id = ? and s.metric_name = ? order by m.ts desc limit 1",
                Double.class, a.placement, metric);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long sampleCount(Agent a) {
        return jdbc().queryForObject("select count(*) from sm_metrics.metrics_raw m join sm_metrics.series s "
                + "using (tenant_id, series_id) where s.placement_id = ?", Long.class, a.placement);
    }

    /** The acknowledged batch watermark of the agent's telemetry stream (the stream is the placement id), or 0. */
    long telemetryWatermark(Agent a) {
        List<Long> rows = jdbc().queryForList("select last_batch_seq from sm_data.agent_telemetry_watermark where agent_id = ?::uuid",
                Long.class, a.id);
        return rows.isEmpty() ? 0 : rows.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** How many times the managed container was started since {@code since}: the host-side effect of a RESTART. */
    static int containerStarts(Agent a, Instant since) {
        AgentTestSupport.Result events = AgentTestSupport.run(List.of("docker", "events", "--since",
                String.format("%d.%09d", since.getEpochSecond(), since.getNano()), "--until", String.valueOf(Instant.now().plusSeconds(1).getEpochSecond()),
                "--filter", "container=" + a.container, "--filter", "event=start", "--format", "{{.Time}}"),
                Duration.ofSeconds(20));
        return (int) events.output().lines().filter(l -> !l.isBlank()).count();
    }

    static String inspect(Agent a, String format) {
        return AgentTestSupport.run(List.of("docker", "inspect", "-f", format, a.container), Duration.ofSeconds(20))
                .output().trim();
    }

    static String agentLog(Agent a) {
        try {
            return Files.exists(a.log) ? Files.readString(a.log) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Non-debug agent log lines, for failure messages. */
    static String agentLogTail(Agent a) {
        try {
            if (!Files.exists(a.log)) {
                return "(no agent log)";
            }
            List<String> lines = Files.readAllLines(a.log).stream().filter(l -> !l.contains("\"level\":\"DEBUG\"")).toList();
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()));
        } catch (IOException e) {
            return "(agent log unreadable)";
        }
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // the row may not exist yet
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what);
            }
        }
        throw new AssertionError("timed out after " + timeout + " waiting for " + what);
    }

    /** Copies each agent's log next to the evidence (target/ is build output, never committed). */
    void preserveLogs(Path evidenceDir) throws IOException {
        Files.createDirectories(evidenceDir);
        for (Agent a : agents) {
            if (Files.exists(a.log)) {
                Files.copy(a.log, evidenceDir.resolve("agent-" + a.index + ".log"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override
    public void close() throws Exception {
        for (Agent a : agents) {
            stopAgent(a);
        }
        stopDp();
        killDpProcess();
        for (Agent a : agents) {
            AgentTestSupport.removeManagedContainer(a.container, work);
        }
        AgentTestSupport.deleteTree(work);
    }
}
