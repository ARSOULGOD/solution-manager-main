package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 011 P7.5, ported by plan 015 T4.4 — capacity of ONE gateway replica, measured on the shipped image against a real PostgreSQL.
 *
 * <p>What is measured is stated by the run itself in {@code target/capacity/capacity.md}: the hardware (host and the cgroup limits the
 * containers ran under), the workload, and per phase the throughput, the protocol latencies the synthetic fleet observed, the end-to-end
 * command latency the database recorded, the containers' CPU and memory, and the command queue. Nothing here is a capacity <i>claim</i>:
 * it is a measurement on one machine, reproducible with
 * {@code mvn -o -f backend/pom.xml -pl launcher -am -Pagent-live verify -Dit.test=AgentCapacityIT -Dcapacity.agents=N -Dcapacity.seconds=S}.
 *
 * <p>The agents are {@link SyntheticAgent}s: protocol-faithful, hostless. Each is enrolled with a token {@code issueAgentEnrolment}
 * issued, REPORTS one container so discovery (OR-27) gives it a placement, and answers what the engine asks of it. They load the gateway
 * and the database; they prove nothing about host behaviour, which the real-agent drills do.
 *
 * <p>Load is real: a "command" is an apply request submitted through the console ({@code stageConfig} then {@code submitApply}, strategy
 * {@code manual}: a PREFLIGHT and an APPLY per request), and the engine's worker drives the commands. The earlier design inserted command
 * rows directly; this one has no such door (every command belongs to an apply's journal), so the rate is bounded by one request in flight
 * per placement and by the console's own request path, and the defaults are smaller than the earlier run's.
 *
 * <p>Phases: (1) enrol, connect and discover the fleet, (2) steady state: heartbeats, reports and telemetry only, (3) command load at the
 * configured rate, (4) overload: one request per agent at once, (5) a planned restart: the container is stopped with the whole fleet
 * attached, (6) a crash: the container is killed, (7) recovery: the steady-state latencies return. A phase's numbers are a window, not a
 * lifetime average.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCapacityIT {
    private static final int AGENTS = Integer.getInteger("capacity.agents", 20);
    private static final int SECONDS = Integer.getInteger("capacity.seconds", 15);
    private static final int REQUESTS_PER_SECOND = Integer.getInteger("capacity.requestsPerSecond", 4);
    private static final String GATEWAY_CPUS = System.getProperty("capacity.gatewayCpus", "2");
    private static final String GATEWAY_MEMORY = System.getProperty("capacity.gatewayMemory", "1g");
    private static final String DB_CPUS = System.getProperty("capacity.dbCpus", "2");
    private static final String DB_MEMORY = System.getProperty("capacity.dbMemory", "2g");
    private static final int MAX_SESSIONS = Integer.getInteger("capacity.maxSessions", 5000);
    private static final int ADMISSION_RATE = Integer.getInteger("capacity.admissionRate", 100);
    private static final int ADMISSION_BURST = Integer.getInteger("capacity.admissionBurst", 200);
    private static final int TELEMETRY_SECONDS = Integer.getInteger("capacity.telemetryIntervalSeconds", 2);
    private static final int OPERATION_MILLIS = Integer.getInteger("capacity.operationMillis", 50);
    private static final String CONFIG_ROOT = "/srv/synthetic";

    private ContainerStack stack;
    private final String launcher = "sm-it-capacity-" + UUID.randomUUID().toString().substring(0, 8);
    private int gatewayPort;
    private int readinessPort;
    private int managementPort;
    private int httpPort;
    private final SyntheticAgent.Stats stats = new SyntheticAgent.Stats();
    private final List<SyntheticAgent> fleet = new ArrayList<>();
    private ScheduledExecutorService clock;
    private final StringBuilder report = new StringBuilder();
    private final List<Sample> samples = new ArrayList<>();
    private volatile boolean sampling;
    private Thread sampler;
    private World world;
    private UUID solutionId;
    private Client console;
    private Login operator;
    /** Per agent: the instance its placement belongs to (read from discovery), and the request in flight, if any. */
    private final Map<Integer, UUID> instances = new HashMap<>();
    private final Map<Integer, UUID> inFlight = new HashMap<>();
    private int staged;

    private record Sample(long at, double gatewayCpu, double gatewayMemMb, double dbCpu, double dbMemMb, long queued, long offered,
            long delivered, long acked, double heapUsedMb, double threads) { }

    @BeforeAll
    void provision() throws Exception {
        AgentLive.require();
        Path jar = Path.of("target").toFile().isDirectory() ? Files.list(Path.of("target"))
                .filter(p -> p.getFileName().toString().matches("launcher-.*-exec\\.jar")).findFirst().orElse(null) : null;
        assertNotNull(jar, "the repackaged launcher jar is not built (it exists when failsafe runs after package)");
        stack = ContainerStack.create(true, null);
        stack.startDatabaseAndMigrate(new ContainerStack.Limits(DB_CPUS, DB_MEMORY), List.of("-c", "max_connections=200"));
        gatewayPort = AgentTestSupport.freePort();
        readinessPort = AgentTestSupport.freePort();
        managementPort = AgentTestSupport.freePort();
        httpPort = AgentTestSupport.freePort();
        clock = Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        // the install's operator steps, as every launcher IT does
        world = DeclareFixtures.seed(new NamedParameterJdbcTemplate(stack.sql.getDataSource()));
        EntitlementMain.run(stack.jdbcUrl, "sm_cp_runtime", stack.secret("cp_runtime_password"), world.organization(), "cache",
                AGENTS + 10, DeclareFixtures.ADMIN);
        startLauncher();
        console = ContainerStack.consoleClient(httpPort);
        Login admin = console.login(DeclareFixtures.ADMIN);
        ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = console.post("/api/cp/solutions/load", admin, load);
        assertTrue(loaded.statusCode() == 201, loaded.body());
        solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(loaded.body()).get("solutionId").asText());
        operator = console.login(DeclareFixtures.OPERATOR_ONE);
    }

    @AfterAll
    void cleanup() throws Exception {
        sampling = false;
        if (sampler != null) {
            sampler.join(5000);
        }
        fleet.forEach(SyntheticAgent::stop);
        if (clock != null) {
            clock.shutdownNow();
        }
        if (stack != null) {
            ContainerStack.docker("rm", "-f", launcher);
            stack.close();
        }
    }

    private void startLauncher() {
        Map<String, String> env = new LinkedHashMap<>(stack.launcherEnvironment(gatewayPort));
        env.put("SM_AGENT_GATEWAY_MAX_SESSIONS", String.valueOf(MAX_SESSIONS));
        env.put("SM_AGENT_GATEWAY_ADMISSION_RATE_PER_SECOND", String.valueOf(ADMISSION_RATE));
        env.put("SM_AGENT_GATEWAY_ADMISSION_BURST", String.valueOf(ADMISSION_BURST));
        env.put("SM_AGENT_GATEWAY_DRAIN_WINDOW", "20s");
        env.put("SM_AGENT_GATEWAY_DRAIN_RECONNECT_SPREAD", "10s");
        env.put("SM_DP_AGENT_MAX_PLACEMENTS_PER_TARGET", "16");
        env.put("SM_GATEWAY_AUTH_RATE_CAPACITY", "100000");
        env.put("SM_GATEWAY_AUTH_RATE_REFILL_PER_MINUTE", "600000");
        env.putAll(extraEnvironment());
        var started = stack.startLauncher(launcher, env, gatewayPort, readinessPort, managementPort, httpPort,
                new ContainerStack.Limits(GATEWAY_CPUS, GATEWAY_MEMORY));
        if (!started.ok()) {
            throw new IllegalStateException(started.output());
        }
        ContainerStack.awaitHealthy(launcher, Duration.ofSeconds(180));
    }

    private static Map<String, String> extraEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        for (String property : System.getProperties().stringPropertyNames()) {
            if (property.startsWith("capacity.env.")) {
                env.put(property.substring("capacity.env.".length()), System.getProperty(property));
            }
        }
        return env;
    }

    // ---- the run ----

    @Test
    void measure() throws Exception {
        JdbcTemplate sql = stack.sql;
        report.append("# Plan 015 T4.4 (from plan 011 P7.5) — gateway capacity measurement\n\nGenerated ").append(Instant.now())
                .append(" by `AgentCapacityIT`.\n\n");
        hardware();
        long start = System.nanoTime();
        SyntheticAgent.Config config = new SyntheticAgent.Config("127.0.0.1", gatewayPort, stack.certs.resolve("agent-ca.pem").toFile(),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(TELEMETRY_SECONDS), 5, Duration.ofMillis(OPERATION_MILLIS),
                clock, stats, CONFIG_ROOT);
        startSampler(sql);

        // 1. enrol (a console token each), connect, and let discovery give every agent its placement
        phase("1 enrol, connect and discover " + AGENTS + " agents");
        long enrolStart = System.nanoTime();
        Login admin = console.login(DeclareFixtures.ADMIN);
        for (int i = 0; i < AGENTS; i++) {
            ObjectNode issue = DeclareFixtures.MAPPER.createObjectNode();
            issue.put("targetName", "edge-cap-" + i).put("allowedConfigRoot", CONFIG_ROOT).put("allowedContainerPrefix", "sm-")
                    .put("solutionId", solutionId.toString()).put("packageVersion", 1);
            HttpResponse<String> issued = console.post("/api/cp/environments/" + world.envOne() + "/agent-enrolments", admin, issue);
            assertTrue(issued.statusCode() == 201, issued.body());
            JsonNode body = DeclareFixtures.MAPPER.readTree(issued.body());
            fleet.add(new SyntheticAgent(config, i, body.get("agentId").asText(), body.get("enrollmentToken").asText()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (SyntheticAgent agent : fleet) {
            futures.add(pool.submit(() -> {
                agent.enrol();
                return null;
            }));
        }
        for (var f : futures) {
            f.get(10, TimeUnit.MINUTES);
        }
        pool.shutdown();
        double enrolSeconds = (System.nanoTime() - enrolStart) / 1e9;
        long connectStart = System.nanoTime();
        for (int i = 0; i < fleet.size(); i++) {
            fleet.get(i).connect();
            if (i % Math.max(1, ADMISSION_RATE / 2) == 0) {
                Thread.sleep(500); // ramp at about half the admission rate
            }
        }
        awaitActive(Duration.ofSeconds(Integer.getInteger("capacity.connectTimeoutSeconds", 180)));
        double connectSeconds = (System.nanoTime() - connectStart) / 1e9;
        for (int i = 0; i < fleet.size(); i++) {
            instances.put(i, sql.queryForObject("select instance_id from sm_data.agent_discovery where agent_id = ?::uuid and state = 'REGISTERED'",
                    UUID.class, fleet.get(i).agentId));
        }
        report.append(String.format(Locale.ROOT, "- enrolment: %d agents in %.1f s (%.1f/s); enrol p50/p95/p99/max %s ms\n",
                AGENTS, enrolSeconds, AGENTS / enrolSeconds, fmt(stats.enrol.percentiles(0))));
        report.append(String.format(Locale.ROOT, "- connect and discovery to all ACTIVE with a placement: %.1f s; session-open p50/p95/p99/max %s ms; admission refusals %d; protocol errors %s\n\n",
                connectSeconds, fmt(stats.sessionOpen.percentiles(0)), stats.refusedAdmission.get(), stats.errorCodes));

        // 2. steady state
        steady("2 steady state (heartbeats, reports, telemetry only)");

        // 3. command load
        requestLoad("3 apply requests at " + REQUESTS_PER_SECOND + "/s", REQUESTS_PER_SECOND, SECONDS);

        // 4. overload: one request per agent at once
        burst("4 overload: one apply request per agent at once (" + AGENTS + ")");

        // 5. planned restart (drain) and 6. crash (reconnect storm)
        plannedRestart("5 planned restart: docker restart with " + AGENTS + " agents attached (the drain)");
        crashStorm("6 crash: SIGKILL with " + AGENTS + " agents attached (the reconnect storm)");

        // 7. recovery
        steady("7 recovery (same load as phase 2)");

        sampling = false;
        sampler.join(5000);
        report.append("\n## Interpretation limits\n\nOne machine, one gateway replica, a synthetic fleet on the same host as the containers (so the load generator competes with them for CPU), "
                + "PostgreSQL with default tuning apart from max_connections. Commands are real apply requests through the console, one in flight per placement, so the command rate "
                + "is bounded by the console's request path and the engine's worker, not by the gateway alone. The numbers bound what this build does here; they are not a promised capacity, "
                + "and they say nothing about a multi-replica deployment (not supported in this build) or a real WAN.\n");
        report.append(String.format(Locale.ROOT, "\nTotal run time %.0f s.\n", (System.nanoTime() - start) / 1e9));
        Path out = Path.of("target", "capacity");
        Files.createDirectories(out);
        Files.writeString(out.resolve("capacity.md"), report.toString());
        System.out.println(report);
        assertTrue(stats.sessionsOpened.get() >= AGENTS, "the whole fleet reached ACTIVE at least once");
    }

    // ---- phases ----

    private void steady(String name) throws Exception {
        phase(name);
        Window w = open();
        Thread.sleep(SECONDS * 1000L);
        close(w);
    }

    /** Submits {@code perSecond} apply requests each second for {@code seconds}, one per agent that has none in flight. */
    private void requestLoad(String name, int perSecond, int seconds) throws Exception {
        phase(name);
        Window w = open();
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        long submitted = 0;
        int cursor = 0;
        while (System.nanoTime() < deadline) {
            long tickStart = System.nanoTime();
            reapFinished();
            for (int k = 0; k < perSecond; k++) {
                for (int tries = 0; tries < AGENTS; tries++) {
                    int a = (cursor++) % AGENTS;
                    if (!inFlight.containsKey(a)) {
                        submit(a);
                        submitted++;
                        break;
                    }
                }
            }
            long wait = 1_000_000_000L - (System.nanoTime() - tickStart);
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000L);
            }
        }
        try {
            await("in-flight requests to finish", Duration.ofSeconds(180), () -> {
                reapFinished();
                return inFlight.isEmpty();
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + diagnose(), e);
        }
        report.append(String.format(Locale.ROOT, "- %d apply requests submitted; %d refused by the console\n", submitted, refused));
        close(w);
    }

    private void burst(String name) throws Exception {
        phase(name);
        Window w = open();
        reapFinished();
        long peak = 0;
        for (int a = 0; a < AGENTS; a++) {
            if (!inFlight.containsKey(a)) {
                submit(a);
            }
        }
        long begin = System.nanoTime();
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
            peak = Math.max(peak, pending());
            Thread.sleep(500);
            reapFinished();
        }
        report.append(String.format(Locale.ROOT, "- peak queue depth %d; drained in %.1f s\n", peak, (System.nanoTime() - begin) / 1e9));
        close(w);
    }

    private int refused;

    /** What the data plane did with the requests, for a failure message. */
    private String diagnose() {
        JdbcTemplate sql = stack.sql;
        return "requests " + sql.queryForList("select state || ':' || count(*) from sm_data.apply_request group by state", String.class)
                + "; commands " + sql.queryForList("select k || ':' || c from (select operation || ':' || state || ':' || coalesce(outcome,'-') || ':' "
                + "|| coalesce(detail_code,'-') as k, count(*) as c from sm_data.agent_command group by 1) t", String.class)
                + "; attempts " + sql.queryForList("select k || ':' || c from (select step || ':' || coalesce(outcome,'open') || ':' "
                + "|| coalesce(error_class,'') as k, count(*) as c from sm_data.apply_attempt group by 1) t", String.class)
                + "; synthetic errors " + stats.errorCodes + "; in flight " + inFlight.size();
    }

    private void submit(int a) throws Exception {
        UUID instance = instances.get(a);
        JsonNode state = DeclareFixtures.MAPPER.readTree(console.get("/api/dp/instances/" + instance, operator).body());
        HttpResponse<String> stagedResponse = console.post("/api/dp/instances/" + instance + "/config", operator,
                E2eStack.stageBody(state.get("stateVersion").asLong(), "cap-" + (++staged), null, null));
        if (stagedResponse.statusCode() != 200) {
            refused++;
            return;
        }
        long generation = DeclareFixtures.MAPPER.readTree(stagedResponse.body()).get("generation").asLong();
        UUID placement = UUID.fromString(fleet.get(a).placement());
        HttpResponse<String> accepted = console.post("/api/dp/instances/" + instance + "/apply", operator,
                E2eStack.submitBody(generation, "manual", placement));
        if (accepted.statusCode() == 202) {
            inFlight.put(a, UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText()));
        } else {
            refused++;
        }
    }

    private void reapFinished() {
        inFlight.entrySet().removeIf(e -> {
            List<String> state = stack.sql.queryForList("select state from sm_data.apply_request where apply_id = ?", String.class, e.getValue());
            return !state.isEmpty() && !List.of("REQUESTED", "RUNNING", "APPLY_INDETERMINATE").contains(state.get(0));
        });
    }

    /** A planned restart: the container is stopped (SIGTERM), so the gateway drains and assigns each agent a staggered return. */
    private void plannedRestart(String name) throws Exception {
        phase(name);
        Window w = open();
        long closedBefore = stats.sessionsClosed.get();
        long refusedBefore = stats.refusedAdmission.get();
        long drainsBefore = stats.drains.get();
        inFlight.clear();
        Instant t0 = Instant.now();
        ContainerStack.docker("restart", "-t", "60", launcher);
        ContainerStack.awaitHealthy(launcher, Duration.ofSeconds(180));
        double healthySeconds = Duration.between(t0, Instant.now()).toMillis() / 1000.0;
        awaitActive(Duration.ofMinutes(10));
        double recovered = Duration.between(t0, Instant.now()).toMillis() / 1000.0;
        report.append(String.format(Locale.ROOT, "- container healthy after %.1f s; all %d agents ACTIVE again after %.1f s; agents told to drain %d; sessions closed %d; admission refusals during recovery %d\n",
                healthySeconds, AGENTS, recovered, stats.drains.get() - drainsBefore, stats.sessionsClosed.get() - closedBefore,
                stats.refusedAdmission.get() - refusedBefore));
        close(w);
    }

    /** A crash: SIGKILL, no drain; every agent finds the listener gone and returns on its own schedule, which is the admission limits' job. */
    private void crashStorm(String name) throws Exception {
        phase(name);
        Window w = open();
        long closedBefore = stats.sessionsClosed.get();
        long refusedBefore = stats.refusedAdmission.get();
        Instant t0 = Instant.now();
        ContainerStack.docker("kill", "-s", "KILL", launcher);
        Thread.sleep(2000);
        ContainerStack.docker("start", launcher);
        ContainerStack.awaitHealthy(launcher, Duration.ofSeconds(180));
        double healthySeconds = Duration.between(t0, Instant.now()).toMillis() / 1000.0;
        awaitActive(Duration.ofMinutes(10));
        double recovered = Duration.between(t0, Instant.now()).toMillis() / 1000.0;
        report.append(String.format(Locale.ROOT, "- killed with SIGKILL; container healthy after %.1f s; all %d agents ACTIVE again after %.1f s; sessions closed %d; admission refusals during recovery %d (the limits at work: %d/s sustained, burst %d)\n",
                healthySeconds, AGENTS, recovered, stats.sessionsClosed.get() - closedBefore, stats.refusedAdmission.get() - refusedBefore,
                ADMISSION_RATE, ADMISSION_BURST));
        close(w);
    }

    // ---- bookkeeping ----

    private long pending() {
        return stack.sql.queryForObject("select count(*) from sm_data.agent_command where state in ('QUEUED','OFFERED','DELIVERED')", Long.class);
    }

    private void awaitActive(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (fleet.stream().allMatch(SyntheticAgent::isActive)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("only " + fleet.stream().filter(SyntheticAgent::isActive).count() + "/" + fleet.size() + " agents ACTIVE after " + timeout
                + "; sessions opened " + stats.sessionsOpened.get() + ", closed " + stats.sessionsClosed.get() + ", errors " + stats.errorCodes
                + "; discovery rows " + stack.sql.queryForList("select state || ':' || coalesce(refusal_code, '-') || ':' || count(*) from sm_data.agent_discovery "
                + "group by state, refusal_code", String.class) + "; with a placement " + fleet.stream().filter(a -> a.placement() != null).count()
                + "\nlauncher log tail:\n" + tail(ContainerStack.docker("logs", "--tail", "40", launcher).output()));
    }

    private static void await(String what, Duration timeout, java.util.function.BooleanSupplier condition) {
        RealAgentHarness.await(what, timeout, condition);
    }

    private record Window(long startNanos, long startMillis, int hb, int rep, int tel, int offer, int sampleFrom, long telemetryCommitted,
            long telemetryBatches, long telemetryAcked, long commandsAcked, long reportsSent, long heartbeatsSent) { }

    private long committedSamples() {
        return stack.sql.queryForObject("select count(*) from sm_metrics.metrics_raw", Long.class);
    }

    private Window open() {
        long committed = committedSamples();
        synchronized (samples) {
            return new Window(System.nanoTime(), System.currentTimeMillis(), stats.heartbeat.count(), stats.report.count(), stats.telemetry.count(),
                    stats.noticeToOffer.count(), samples.size(), committed, stats.telemetryBatches.get(), stats.telemetryAcked.get(),
                    stats.commandsAcked.get(), stats.reports.get(), stats.heartbeats.get());
        }
    }

    private void close(Window w) {
        double seconds = (System.nanoTime() - w.startNanos()) / 1e9;
        long committed = committedSamples() - w.telemetryCommitted();
        List<Sample> window;
        synchronized (samples) {
            window = new ArrayList<>(samples.subList(Math.min(w.sampleFrom(), samples.size()), samples.size()));
        }
        report.append(String.format(Locale.ROOT, "- window %.0f s: heartbeats %.1f/s, reports %.1f/s, telemetry batches %.1f/s (acked %.1f/s), samples committed %.1f/s, commands acked %.1f/s\n",
                seconds, (stats.heartbeats.get() - w.heartbeatsSent()) / seconds, (stats.reports.get() - w.reportsSent()) / seconds,
                (stats.telemetryBatches.get() - w.telemetryBatches()) / seconds, (stats.telemetryAcked.get() - w.telemetryAcked()) / seconds,
                committed / seconds, (stats.commandsAcked.get() - w.commandsAcked()) / seconds));
        report.append("  - round trips as seen by the fleet (p50/p95/p99/max ms): heartbeat ").append(fmt(stats.heartbeat.percentiles(w.hb())))
                .append("; report ").append(fmt(stats.report.percentiles(w.rep()))).append("; telemetry ack ").append(fmt(stats.telemetry.percentiles(w.tel())))
                .append("; notice-to-offer ").append(fmt(stats.noticeToOffer.percentiles(w.offer()))).append("\n");
        List<Double> e2e = stack.sql.queryForList("select extract(epoch from (acked_at - created_at)) * 1000 from sm_data.agent_command "
                + "where acked_at is not null and created_at >= to_timestamp(?)", Double.class, w.startMillis() / 1000.0);
        if (!e2e.isEmpty()) {
            double[] sorted = e2e.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            report.append(String.format(Locale.ROOT, "  - command end-to-end, database timestamps (enqueue to terminal ack), n=%d: p50 %.0f / p95 %.0f / p99 %.0f / max %.0f ms\n",
                    sorted.length, at(sorted, 0.5), at(sorted, 0.95), at(sorted, 0.99), sorted[sorted.length - 1]));
        }
        if (!window.isEmpty()) {
            report.append(String.format(Locale.ROOT, "  - gateway container (cgroup %s CPU, %s): CPU avg %.0f%% / max %.0f%% of one core, memory avg %.0f / max %.0f MB; "
                            + "database container: CPU avg %.0f%% / max %.0f%%, memory max %.0f MB\n",
                    GATEWAY_CPUS, GATEWAY_MEMORY, avg(window, s -> s.gatewayCpu()), max(window, s -> s.gatewayCpu()), avg(window, s -> s.gatewayMemMb()),
                    max(window, s -> s.gatewayMemMb()), avg(window, s -> s.dbCpu()), max(window, s -> s.dbCpu()), max(window, s -> s.dbMemMb())));
            report.append(String.format(Locale.ROOT, "  - gateway JVM (management endpoint): heap used avg %.0f / max %.0f MB, live threads max %.0f\n",
                    avg(window, s -> s.heapUsedMb()), max(window, s -> s.heapUsedMb()), max(window, s -> s.threads())));
            report.append(String.format(Locale.ROOT, "  - queue depth: max queued %.0f, max offered %.0f, max delivered %.0f; final acked total %d\n",
                    max(window, s -> (double) s.queued()), max(window, s -> (double) s.offered()), max(window, s -> (double) s.delivered()),
                    window.get(window.size() - 1).acked()));
        }
        report.append("  - fleet: sessions opened ").append(stats.sessionsOpened.get()).append(", closed ").append(stats.sessionsClosed.get())
                .append(", admission refusals ").append(stats.refusedAdmission.get()).append(", protocol errors ").append(stats.errorCodes)
                .append(", expected protocol events ").append(stats.notes).append("\n\n");
    }

    private void phase(String name) {
        report.append("## ").append(name).append("\n\n");
        System.out.println("[capacity] " + name);
    }

    private void hardware() throws Exception {
        report.append("## Hardware and configuration\n\n");
        report.append("- host: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append(", ").append(Runtime.getRuntime().availableProcessors()).append(" logical CPUs, JVM ")
                .append(System.getProperty("java.version")).append("\n");
        report.append("- docker: ").append(ContainerStack.docker("info", "--format", "cpus={{.NCPU}} mem={{.MemTotal}} server={{.ServerVersion}}").output().trim()).append("\n");
        report.append("- gateway replica: the shipped image, cgroup limits --cpus=").append(GATEWAY_CPUS).append(" --memory=").append(GATEWAY_MEMORY)
                .append("; max-sessions ").append(MAX_SESSIONS).append(", admission ").append(ADMISSION_RATE).append("/s burst ").append(ADMISSION_BURST)
                .append(", poll interval 1 s (default), notice-retry 15 s (default)\n");
        report.append("- database: postgres:14, --cpus=").append(DB_CPUS).append(" --memory=").append(DB_MEMORY)
                .append(", max_connections=200, otherwise default configuration; the gateway's pool is the default\n");
        report.append("- workload: ").append(AGENTS).append(" synthetic agents (one container, one placement each); heartbeat 30 s; report 30 s; telemetry batch every ")
                .append(TELEMETRY_SECONDS).append(" s with 5 samples; simulated operation time ").append(OPERATION_MILLIS).append(" ms; ")
                .append(REQUESTS_PER_SECOND).append(" apply requests/s in phase 3 (manual strategy: PREFLIGHT and APPLY); phase length ").append(SECONDS).append(" s\n\n");
    }

    private void startSampler(JdbcTemplate sql) {
        sampling = true;
        sampler = new Thread(() -> {
            while (sampling) {
                try {
                    double[] gateway = dockerStats(launcher);
                    double[] db = dockerStats(stack.db);
                    Map<String, Long> states = new LinkedHashMap<>();
                    sql.query("select state, count(*) from sm_data.agent_command group by state", rs -> {
                        states.put(rs.getString(1), rs.getLong(2));
                    });
                    double[] jvm = jvmGauges();
                    synchronized (samples) {
                        samples.add(new Sample(System.currentTimeMillis(), gateway[0], gateway[1], db[0], db[1],
                                states.getOrDefault("QUEUED", 0L), states.getOrDefault("OFFERED", 0L),
                                states.getOrDefault("DELIVERED", 0L), states.getOrDefault("ACKED", 0L), jvm[0], jvm[1]));
                    }
                } catch (RuntimeException ignored) {
                    // a sample is skipped while the container is restarting
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "capacity-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** {@code [heap used MB, live threads]} from the gateway's own authenticated management endpoint. */
    private double[] jvmGauges() {
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + managementPort + "/management/metrics"))
                    .header("Authorization", "Bearer " + ContainerStack.MANAGEMENT_TOKEN).timeout(Duration.ofSeconds(3)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            double heap = Double.NaN;
            double threads = Double.NaN;
            for (String line : response.body().split("\n")) {
                if (line.startsWith("jvm_memory_heap_used_bytes ")) {
                    heap = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) / (1024 * 1024);
                } else if (line.startsWith("jvm_threads_live ")) {
                    threads = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
            return new double[] {heap, threads};
        } catch (Exception e) {
            return new double[] {Double.NaN, Double.NaN};
        }
    }

    /** {@code [cpu percent (100 = one core), memory MB]} from the container's cgroup, via docker stats. */
    private static double[] dockerStats(String container) {
        String out = ContainerStack.docker("stats", "--no-stream", "--format", "{{.CPUPerc}}|{{.MemUsage}}", container).output().trim();
        String[] parts = out.split("\\|");
        double cpu = Double.parseDouble(parts[0].replace("%", ""));
        String used = parts[1].split("/")[0].trim();
        double mb = used.endsWith("GiB") ? Double.parseDouble(used.replace("GiB", "")) * 1024
                : used.endsWith("MiB") ? Double.parseDouble(used.replace("MiB", "")) : Double.parseDouble(used.replaceAll("[^0-9.]", "")) / 1024;
        return new double[] {cpu, mb};
    }

    private static String tail(String text) {
        List<String> lines = text.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static String fmt(double[] p) {
        return String.format(Locale.ROOT, "%.1f / %.1f / %.1f / %.1f", p[0], p[1], p[2], p[3]);
    }

    private static double at(double[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static double avg(List<Sample> window, java.util.function.ToDoubleFunction<Sample> f) {
        return window.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static double max(List<Sample> window, java.util.function.ToDoubleFunction<Sample> f) {
        return window.stream().mapToDouble(f).max().orElse(Double.NaN);
    }
}
