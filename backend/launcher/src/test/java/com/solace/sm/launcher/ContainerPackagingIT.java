package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.AgentTestSupport.Result;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.RealAgentHarness.Agent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 011 P7.3, re-landed by plan 015 T4.4 — the shipped artifact, not the code: the launcher as the container image {@code runtime/}
 * builds, started from the documented inputs against a real PostgreSQL, with the real Go agent enrolling over the published gateway port.
 *
 * <p>Drills: the image is reproducible; Phase 0 and Phase 1 run as their own one-shot containers with their own secret FILES (OR-138) and
 * migration is idempotent; the application container is non-root, becomes healthy (the L4 readiness socket) and carries only runtime
 * credentials, none of them as an environment value; the agent enrols with a token the console issued and runs a real host command
 * through it; {@code docker stop} drains (the in-flight command's outcome is recorded, the agent is told, the exit is inside the grace
 * period); {@code docker kill -9} mid-command loses nothing and runs nothing twice; recreating the container keeps every durable row; a
 * missing certificate fails fast and loudly; a bad secret file stops the wrapper with exit 64 and leaks nothing; the authenticated
 * management endpoint serves health and metrics while no unauthenticated actuator route exists; {@code compose.yml} fails closed on
 * its empty variables and renders when they are set.
 *
 * <p>The earlier maintenance-job drill is gone with the {@code sm-maintain} image entrypoint (dropped by T2.4: maintenance is the data
 * plane's own daily loop, OR-77, proven in dp-core).
 *
 * <p>Seeding: the organization, environment, users and entitlement are the install's operator steps (as the database superuser /
 * {@link EntitlementMain}, the way every launcher IT does); the package, the enrolment, the stage and the apply go through the
 * published console port. Fails (never skips) without Docker, go, alpine, the Cache image or the repackaged jar. Evidence:
 * {@code target/failure-matrix/packaging.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContainerPackagingIT {
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String launcher = "sm-it-launcher-" + suffix;
    private final String image = "sm-launcher:it-" + suffix;

    private Path repo;
    private ContainerStack stack;
    private Path agentBinary;
    private Agent agent;
    private JdbcTemplate sql;
    private int gatewayPort;
    private int readinessPort;
    private int managementPort;
    private int httpPort;
    private MatrixEvidence ev;
    private World world;
    private UUID solutionId;
    private int restarts;

    @FunctionalInterface
    private interface Body {
        String run() throws Exception;
    }

    @BeforeAll
    void provision() throws Exception {
        AgentLive.require();
        repo = Path.of("..", "..").toAbsolutePath().normalize();
        assertNotNull(findJar(), "the repackaged launcher jar is not built (it exists when failsafe runs after package)");
        ev = new MatrixEvidence("Plan 011 P7.3 / plan 015 T4.4 — container packaging and operations (real image, real PostgreSQL, real Go agent)",
                Path.of("target", "failure-matrix", "packaging.md"));
        stack = ContainerStack.create(false, image);
        Path work = stack.work;
        agentBinary = work.resolve("sm-agent");
        Result built = AgentTestSupport.buildAgent(agentBinary);
        assertTrue(built.ok(), "go build of sm-agent failed: " + built.output());
        agent = new Agent(0, work);
        gatewayPort = AgentTestSupport.freePort();
        readinessPort = AgentTestSupport.freePort();
        managementPort = AgentTestSupport.freePort();
        httpPort = AgentTestSupport.freePort();
    }

    @AfterAll
    void cleanup() throws Exception {
        if (stack == null) {
            return;
        }
        if (agent != null && agent.process != null && agent.process.isAlive()) {
            agent.process.destroy();
            agent.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            agent.process.destroyForcibly();
        }
        Files.createDirectories(Path.of("target", "failure-matrix"));
        if (agent != null && Files.exists(agent.log)) {
            Files.copy(agent.log, Path.of("target", "failure-matrix", "packaging-agent.log"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        dockerQuiet("rm", "-f", launcher, launcher + "-b");
        if (agent != null) {
            AgentTestSupport.removeManagedContainer(agent.container, stack.work);
        }
        dockerQuiet("image", "rm", "-f", image);
        stack.close();
    }

    // ---- helpers ----

    private static Path findJar() throws Exception {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (var files = Files.list(target)) {
            return files.filter(p -> p.getFileName().toString().matches("launcher-.*-exec\\.jar")).findFirst().orElse(null);
        }
    }

    private static Result docker(String... args) {
        return ContainerStack.docker(args);
    }

    private static void dockerQuiet(String... args) {
        docker(args);
    }

    private void scenario(String id, String title, String expected, Body body) throws Exception {
        ev.begin(id, title, expected);
        try {
            ev.pass(body.run());
        } catch (Throwable t) {
            ev.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            ev.observe("launcher log tail:\n" + tail(docker("logs", "--tail", "40", launcher).output()));
            throw t;
        }
    }

    private static String tail(String text) {
        List<String> lines = text.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private Result startLauncher(String name, Map<String, String> overrides) {
        Map<String, String> env = stack.launcherEnvironment(gatewayPort);
        env.put("SM_AGENT_GATEWAY_POLL_INTERVAL", "200ms");
        env.put("SM_DP_APPLY_WORKER_POLL_INTERVAL", "200ms");
        env.put("SM_AGENT_GATEWAY_DRAIN_WINDOW", "20s");
        env.put("SM_AGENT_GATEWAY_DRAIN_RECONNECT_MIN", "1s");
        env.put("SM_AGENT_GATEWAY_DRAIN_RECONNECT_SPREAD", "2s");
        env.putAll(overrides);
        return stack.startLauncher(name, env, gatewayPort, readinessPort, managementPort, httpPort, ContainerStack.Limits.NONE);
    }

    private void awaitHealthy(String name, Duration timeout) {
        ContainerStack.awaitHealthy(name, timeout);
    }

    private void awaitActive() {
        RealAgentHarness.await("an ACTIVE agent session", Duration.ofSeconds(90), () -> {
            String h = RealAgentHarness.health(agent);
            return h.contains("\"session_phase\":\"ACTIVE\"") && h.contains("\"authority_phase\":\"ACTIVE\"");
        });
    }

    private Client console() {
        return ContainerStack.consoleClient(httpPort);
    }

    private HttpResponse<String> post(Login login, String path, JsonNode body) throws Exception {
        return console().post(path, login, body);
    }

    /** The console's own endpoints: a fresh restart-bearing apply of the agent's placement (PREFLIGHT, APPLY, RESTART). */
    private UUID restartApply() throws Exception {
        Login operator = console().login(DeclareFixtures.OPERATOR_ONE);
        UUID instance = sql.queryForObject("select instance_id from sm_data.agent_discovery where agent_id = ?::uuid and state = 'REGISTERED'",
                UUID.class, agent.id);
        UUID placement = sql.queryForObject("select placement_id from sm_data.agent_discovery where agent_id = ?::uuid and state = 'REGISTERED'",
                UUID.class, agent.id);
        long base = DeclareFixtures.MAPPER.readTree(console().get("/api/dp/instances/" + instance, operator).body()).get("stateVersion").asLong();
        HttpResponse<String> staged = post(operator, "/api/dp/instances/" + instance + "/config",
                E2eStack.stageBody(base, "pkg-" + (++restarts), null, null));
        assertEquals(200, staged.statusCode(), staged.body());
        long generation = DeclareFixtures.MAPPER.readTree(staged.body()).get("generation").asLong();
        HttpResponse<String> accepted = post(operator, "/api/dp/instances/" + instance + "/apply",
                E2eStack.submitBody(generation, "rolling-no-validation", placement));
        assertEquals(202, accepted.statusCode(), accepted.body());
        return UUID.fromString(DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText());
    }

    /** {@code state:outcome} of the RESTART command the apply produced, or {@code ABSENT}. */
    private String state(UUID apply) {
        List<String> rows = sql.queryForList("select state || ':' || coalesce(outcome, '-') from sm_data.agent_command "
                + "where apply_id = ? and operation = 'RESTART'", String.class, apply);
        return rows.isEmpty() ? "ABSENT" : rows.get(0);
    }

    private long epoch() {
        List<Long> rows = sql.queryForList("select fencing_epoch from sm_data.agent_session where agent_id = ?::uuid", Long.class, agent.id);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private HttpResponse<String> http(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- drills ----

    @Test
    @Order(1)
    void theImageIsReproducible() throws Exception {
        scenario("P01", "Reproducible image", "Two builds of the same inputs produce the same repackaged jar and the same OCI manifest digest", () -> {
            Result built = AgentTestSupport.run(List.of("bash", "-c", "SM_SKIP_MVN=1 ./runtime/scripts/build-image.sh --check " + image),
                    repo, Duration.ofMinutes(12));
            ev.observe(built.output().lines().filter(l -> l.startsWith("jar:") || l.startsWith("image manifest:") || l.contains("REPRODUCIBLE")).toList().toString());
            assertTrue(built.ok() && built.output().contains("REPRODUCIBLE: identical manifest digests"), built.output());
            dockerQuiet("image", "rm", "-f", image + "-recheck");
            return "identical manifest digests across two --no-cache builds";
        });
    }

    @Test
    @Order(2)
    void phaseZeroAndPhaseOneRunAsTheirOwnContainersAndMigrationIsIdempotent() throws Exception {
        scenario("P02", "Bootstrap and migrate as separate one-shot containers",
                "Phase 0 (superuser) and Phase 1 (migrator roles, separate JVM) run from the shipped files with their secret files; a re-run "
                        + "of Phase 1 is a no-op; the application is given neither credential", () -> {
            stack.startDatabaseAndMigrate(ContainerStack.Limits.NONE, List.of());
            sql = stack.sql;
            ev.observe("secrets generated by runtime/scripts/init-secrets.sh: " + Files.list(stack.secrets).count() + " files");
            Result second = stack.migrate();
            ev.observe("second migrate: " + second.output().lines().filter(l -> l.contains("migrated")).toList());
            assertTrue(second.ok(), second.output());
            assertEquals(3, second.output().lines().filter(l -> l.contains("migrated 0")).count(), "sm_control, sm_data and sm_metrics: all no-ops");
            for (String secret : List.of(stack.secret("cp_migrator_password"), stack.secret("dp_migrator_password"), stack.secret("db_superuser_password"))) {
                assertFalse(second.output().contains(secret), "no secret in the job output");
            }
            // the install's operator steps (as every launcher IT does): the organization, its environment, users and entitlement
            world = DeclareFixtures.seed(new NamedParameterJdbcTemplate(sql.getDataSource()));
            EntitlementMain.run(stack.jdbcUrl, "sm_cp_runtime", stack.secret("cp_runtime_password"), world.organization(), "cache", 50,
                    DeclareFixtures.ADMIN);
            return "phase 0, then phase 1 twice (second a no-op); no secret echoed";
        });
    }

    @Test
    @Order(3)
    void theApplicationContainerStartsHealthyAsNonRootWithOnlyRuntimeCredentialsAndAnAgentEnrolls() throws Exception {
        scenario("P03", "Startup from documented inputs; enrolment; real host command",
                "The container becomes healthy (readiness socket), runs as uid 10001, holds no migrator/superuser secret and no password in "
                        + "its environment; the agent enrols with a token the console issued, through the published port, and a command "
                        + "runs on the real target", () -> {
            Result started = startLauncher(launcher, Map.of());
            assertTrue(started.ok(), started.output());
            Instant t0 = Instant.now();
            awaitHealthy(launcher, Duration.ofSeconds(150));
            ev.observe("healthy " + Duration.between(t0, Instant.now()).toSeconds() + " s after start");
            assertEquals("10001", docker("exec", launcher, "id", "-u").output().trim(), "not root");
            String inspected = docker("inspect", "-f", "{{json .Config.Env}}", launcher).output();
            String environment = docker("exec", launcher, "env").output();
            for (String name : List.of("db_superuser_password", "cp_migrator_password", "dp_migrator_password", "cp_runtime_password", "dp_runtime_password")) {
                assertFalse(inspected.contains(stack.secret(name)), "docker inspect shows " + name);
                assertFalse(environment.contains(stack.secret(name)), "a new process in the container sees " + name);
            }
            assertFalse(inspected.contains(AgentGatewayTestPki.PASSWORD), "docker inspect shows the CA keystore password");
            assertTrue(inspected.contains("SM_CONTROL_RUNTIME_PASSWORD_FILE") && inspected.contains("SM_DATA_RUNTIME_PASSWORD_FILE"),
                    "the runtime credentials arrive as files");
            try (var socket = new java.net.Socket("127.0.0.1", readinessPort)) {
                ev.observe("readiness socket accepted on the published port");
            }
            // the package, the enrolment and the agent, all through the published port
            Login admin = console().login(DeclareFixtures.ADMIN);
            ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = post(admin, "/api/cp/solutions/load", load);
            assertEquals(201, loaded.statusCode(), loaded.body());
            solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(loaded.body()).get("solutionId").asText());
            ObjectNode issue = DeclareFixtures.MAPPER.createObjectNode();
            issue.put("targetName", "edge-pkg").put("allowedConfigRoot", agent.root.toString()).put("allowedContainerPrefix", "sm-")
                    .put("solutionId", solutionId.toString()).put("packageVersion", 1);
            HttpResponse<String> issued = post(admin, "/api/cp/environments/" + world.envOne() + "/agent-enrolments", issue);
            assertEquals(201, issued.statusCode(), issued.body());
            JsonNode enrolment = DeclareFixtures.MAPPER.readTree(issued.body());
            agent.id = enrolment.get("agentId").asText();
            agent.token = enrolment.get("enrollmentToken").asText();
            Result container = AgentTestSupport.startManagedContainer(agent.container, agent.label, agent.conf);
            assertTrue(container.ok(), container.output());
            RealAgentHarness.launchAgent(agentBinary, stack.pki.trustBundle, gatewayPort, agent, agent.token);
            RealAgentHarness.await("the labelled container to be discovered", Duration.ofSeconds(120), () -> sql.queryForObject(
                    "select count(*) from sm_data.agent_discovery where agent_id = ?::uuid and state = 'REGISTERED'", Integer.class, agent.id) == 1);
            awaitActive();
            Instant since = Instant.now();
            UUID apply = restartApply();
            RealAgentHarness.await("the command", Duration.ofSeconds(120), () -> state(apply).equals("ACKED:APPLIED"));
            assertEquals(1, RealAgentHarness.containerStarts(agent, since));
            return "healthy as non-root with runtime secrets as files only; enrolled through the published port; one real restart";
        });
    }

    @Test
    @Order(4)
    void healthAndMetricsAreAuthenticatedAndNoActuatorRouteExists() throws Exception {
        scenario("P04", "Observability without an unauthenticated route",
                "The management endpoint needs its bearer token; the console serves no actuator or management route; the "
                        + "gateway's meters and the JVM's are scrapeable", () -> {
            assertEquals(401, http(managementPort, "/management/health", null).statusCode());
            assertEquals(401, http(managementPort, "/management/metrics", "wrong-token-0123456789").statusCode());
            HttpResponse<String> health = http(managementPort, "/management/health", ContainerStack.MANAGEMENT_TOKEN);
            HttpResponse<String> metrics = http(managementPort, "/management/metrics", ContainerStack.MANAGEMENT_TOKEN);
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\"") && health.body().contains("\"sessions\":1"), health.body());
            assertTrue(metrics.body().contains("agw_sessions_active 1") && metrics.body().contains("agw_commands_acked_total"), metrics.body());
            ev.observe("management health: " + health.body().trim());
            for (String path : List.of("/actuator", "/actuator/health", "/actuator/metrics", "/management/health", "/health", "/")) {
                int status = http(httpPort, path, null).statusCode();
                ev.observe("console " + path + " -> " + status);
                assertNotEquals(200, status, "the console must not answer " + path + " unauthenticated");
            }
            return "401 without the token, 200 with it; the console answers 200 to none of the probe paths";
        });
    }

    @Test
    @Order(5)
    void dockerStopDrainsTheInFlightCommandAndExitsInsideTheGracePeriod() throws Exception {
        scenario("P05", "Graceful shutdown (docker stop)",
                "SIGTERM drains: the in-flight command's outcome is recorded, the agent is told and answers, the container exits "
                        + "inside the grace period; after docker start the agent returns", () -> {
            Instant since = Instant.now();
            UUID apply = restartApply();
            RealAgentHarness.await("the agent to hold the restart", Duration.ofSeconds(90), () -> state(apply).startsWith("DELIVERED") || state(apply).startsWith("ACKED"));
            long epochBefore = epoch();
            Instant stopAt = Instant.now();
            Result stopped = docker("stop", "-t", "90", launcher);
            long seconds = Duration.between(stopAt, Instant.now()).toSeconds();
            String exit = docker("inspect", "-f", "{{.State.ExitCode}}", launcher).output().trim();
            String logs = docker("logs", launcher).output();
            ev.observe("docker stop returned after " + seconds + " s, exit code " + exit);
            ev.observe(logs.lines().filter(l -> l.contains("agent-gateway drained")).toList().toString());
            assertTrue(stopped.ok());
            assertTrue(seconds < 60, "inside the 60 s shutdown timeout, not killed at the 90 s grace: " + seconds);
            assertTrue(logs.contains("agent-gateway drained: sessions=1 answered=1 forced=0"), tail(logs));
            assertEquals("ACKED:APPLIED", state(apply), "the in-flight command's outcome is durable before the process is gone");
            assertEquals(1, RealAgentHarness.containerStarts(agent, since));
            assertTrue(sql.queryForObject("select count(*) from sm_data.agent_event where code = 'gateway.drain.complete'", Integer.class) >= 1);
            assertTrue(docker("start", launcher).ok());
            awaitHealthy(launcher, Duration.ofSeconds(150));
            RealAgentHarness.await("the agent to reconnect", Duration.ofSeconds(90), () -> epoch() > epochBefore);
            awaitActive();
            return "drained in " + seconds + " s with exit " + exit + "; command ACKED before shutdown; agent reconnected";
        });
    }

    @Test
    @Order(6)
    void aFrozenAgentIsCutOffAtTheDrainWindowAndTheReadinessSocketClosesFirst() throws Exception {
        scenario("P05b", "Drain with an agent that never answers; readiness closes first",
                "The L4 readiness socket stops accepting as soon as shutdown begins (a balancer stops sending new connections) while "
                        + "the drain is still waiting; an agent that never answers is cut off at the drain window, counted forced, and "
                        + "the container still exits inside the grace period; the agent then reconnects normally", () -> {
            awaitActive();
            long epochBefore = epoch();
            long pid = agent.process.pid();
            assertTrue(AgentTestSupport.run(List.of("kill", "-STOP", String.valueOf(pid)), Duration.ofSeconds(5)).ok());
            try {
                // Probed from INSIDE the container (docker exec + bash /dev/tcp): Docker Desktop's published-port proxy accepts on the host
                // side whether or not anything listens behind it, so a host-side connect cannot tell "closed".
                assertTrue(docker("exec", launcher, "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9444").ok(), "before stop: the readiness socket accepts");
                ev.observe("before stop: the readiness socket accepts");
                Instant stopAt = Instant.now();
                CompletableFuture<Result> stopping = CompletableFuture.supplyAsync(() -> docker("stop", "-t", "90", launcher));
                boolean refusedWhileRunning = false;
                Instant deadline = Instant.now().plusSeconds(15);
                while (Instant.now().isBefore(deadline) && !stopping.isDone()) {
                    Result probe = docker("exec", launcher, "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9444");
                    if (!probe.ok() && !probe.output().toLowerCase().contains("is not running")
                            && docker("inspect", "-f", "{{.State.Running}}", launcher).output().trim().equals("true")) {
                        refusedWhileRunning = true;
                        break;
                    }
                    Thread.sleep(100);
                }
                ev.observe("readiness refused while the container was still running: " + refusedWhileRunning + " ("
                        + Duration.between(stopAt, Instant.now()).toMillis() + " ms after the stop began)");
                assertTrue(refusedWhileRunning, "the readiness socket must close at the start of shutdown, before the drain completes");
                Result result = stopping.get(100, java.util.concurrent.TimeUnit.SECONDS);
                long seconds = Duration.between(stopAt, Instant.now()).toSeconds();
                String logs = docker("logs", launcher).output();
                ev.observe("docker stop returned after " + seconds + " s; " + logs.lines().filter(l -> l.contains("agent-gateway drained")).reduce((a, b) -> b).orElse(""));
                assertTrue(result.ok());
                assertTrue(logs.contains("agent-gateway drained: sessions=1 answered=0 forced=1"), tail(logs));
                assertTrue(seconds < 60, "inside the shutdown timeout: " + seconds);
            } finally {
                AgentTestSupport.run(List.of("kill", "-CONT", String.valueOf(pid)), Duration.ofSeconds(5));
            }
            assertTrue(docker("start", launcher).ok());
            awaitHealthy(launcher, Duration.ofSeconds(150));
            RealAgentHarness.await("the agent to reconnect", Duration.ofSeconds(120), () -> epoch() > epochBefore);
            awaitActive();
            return "readiness closed first; the frozen agent was forced at the window; the agent reconnected once thawed";
        });
    }

    @Test
    @Order(7)
    void aKilledProcessLosesNothingAndRunsNothingTwice() throws Exception {
        scenario("P06", "Crash (docker kill -s KILL) mid-command",
                "No drain, no goodbye: after the container is started again the command completes exactly once and the agent "
                        + "resumes", () -> {
            Instant since = Instant.now();
            UUID apply = restartApply();
            RealAgentHarness.await("the agent to hold the restart", Duration.ofSeconds(90), () -> state(apply).startsWith("DELIVERED") || state(apply).startsWith("ACKED"));
            long epochBefore = epoch();
            assertTrue(docker("kill", "-s", "KILL", launcher).ok());
            String exit = docker("inspect", "-f", "{{.State.ExitCode}}", launcher).output().trim();
            ev.observe("exit code after SIGKILL: " + exit + "; command state at the moment of the kill: " + state(apply));
            Thread.sleep(3000); // the agent notices the dead connection and backs off
            assertTrue(docker("start", launcher).ok());
            awaitHealthy(launcher, Duration.ofSeconds(150));
            RealAgentHarness.await("the command to finish", Duration.ofSeconds(120), () -> state(apply).equals("ACKED:APPLIED"));
            RealAgentHarness.await("the agent to reconnect", Duration.ofSeconds(90), () -> epoch() > epochBefore);
            awaitActive();
            int starts = RealAgentHarness.containerStarts(agent, since);
            ev.observe("docker starts=" + starts + "; epoch " + epochBefore + " -> " + epoch());
            assertEquals(1, starts);
            return "completed once after a SIGKILL; exit " + exit;
        });
    }

    @Test
    @Order(8)
    void recreatingTheContainerKeepsEveryDurableRow() throws Exception {
        scenario("P07", "Persistent state across container recreation",
                "Removing and recreating the application container keeps the database's history (commands, audit, identity, "
                        + "lease); the agent resumes without re-enrolling", () -> {
            int commands = sql.queryForObject("select count(*) from sm_data.agent_command", Integer.class);
            int events = sql.queryForObject("select count(*) from sm_data.agent_event", Integer.class);
            long epochBefore = epoch();
            assertTrue(docker("rm", "-f", launcher).ok());
            assertTrue(startLauncher(launcher, Map.of()).ok());
            awaitHealthy(launcher, Duration.ofSeconds(150));
            RealAgentHarness.await("the agent to reconnect", Duration.ofSeconds(90), () -> epoch() > epochBefore);
            awaitActive();
            assertTrue(sql.queryForObject("select count(*) from sm_data.agent_command", Integer.class) >= commands);
            assertTrue(sql.queryForObject("select count(*) from sm_data.agent_event", Integer.class) >= events);
            assertEquals(1, sql.queryForObject("select count(*) from sm_data.agent_identity", Integer.class), "no second identity");
            assertEquals(1, sql.queryForObject("select count(distinct correlation_id) from sm_control.change_audit "
                    + "where audit_operation = 'AGENT_ENROLLED'", Integer.class), "no re-enrolment (one enrolment writes more than one audited row, under one correlation id)");
            ev.observe("commands " + commands + " -> kept; agent events " + events + " -> kept; epoch " + epochBefore + " -> " + epoch());
            return "history intact; agent resumed under a recreated container";
        });
    }

    @Test
    @Order(9)
    void aMissingCertificateFailsFastAndLoudlyWithoutLeakingAnything() throws Exception {
        scenario("P08", "Missing TLS material",
                "A deployment whose certificate path is wrong does not start serving: the process exits non-zero quickly, the log "
                        + "names the cause class, and no secret is printed", () -> {
            assertTrue(docker("stop", "-t", "90", launcher).ok());
            Result started = startLauncher(launcher + "-b", Map.of("SM_AGENT_GATEWAY_CERT", "/run/secrets/does-not-exist.crt"));
            assertTrue(started.ok(), started.output());
            RealAgentHarness.await("the broken container to exit", Duration.ofSeconds(150),
                    () -> docker("inspect", "-f", "{{.State.Running}}", launcher + "-b").output().trim().equals("false"));
            String exit = docker("inspect", "-f", "{{.State.ExitCode}}", launcher + "-b").output().trim();
            String logs = docker("logs", launcher + "-b").output();
            ev.observe("exit code " + exit + "; " + logs.lines().filter(l -> l.contains("TLS listener could not start")).findFirst().orElse("(message not found)"));
            assertNotEquals("0", exit);
            assertTrue(logs.contains("agent-gateway TLS listener could not start"), tail(logs));
            for (String secret : List.of(stack.secret("cp_runtime_password"), stack.secret("dp_runtime_password"), AgentGatewayTestPki.PASSWORD,
                    ContainerStack.MANAGEMENT_TOKEN, stack.secret("db_superuser_password"))) {
                assertFalse(logs.contains(secret), "secret leaked into the failure log");
            }
            dockerQuiet("rm", "-f", launcher + "-b");
            assertTrue(docker("start", launcher).ok());
            awaitHealthy(launcher, Duration.ofSeconds(150));
            return "exit " + exit + " with a named cause and no secret in the log";
        });
    }

    @Test
    @Order(10)
    void anEmptyOrMissingSecretFileStopsTheWrapperWithExit64AndLeaksNothing() throws Exception {
        scenario("P09", "Secret files are read by the shipped wrapper, fail closed, and are never echoed",
                "sm-tool and sm-launcher read NAME_FILE into NAME for the JVM only: an empty file, a missing file and a value given both "
                        + "ways each stop the wrapper with exit 64 and a message that names the variable, never its value", () -> {
            Path empty = Files.writeString(stack.work.resolve("empty-secret"), "");
            Files.setPosixFilePermissions(empty, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            Result emptyFile = docker("run", "--rm", "-v", empty + ":/run/secrets/x:ro", "-e", "SM_CONTROL_RUNTIME_PASSWORD_FILE=/run/secrets/x",
                    "--entrypoint", "/opt/sm/bin/sm-tool", image, "com.solace.sm.cp.persistence.EntitlementMain");
            Result missing = docker("run", "--rm", "-e", "SM_DATA_RUNTIME_PASSWORD_FILE=/run/secrets/nowhere",
                    "--entrypoint", "/opt/sm/bin/sm-tool", image, "com.solace.sm.cp.persistence.EntitlementMain");
            String secret = stack.secret("cp_runtime_password");
            Result both = docker("run", "--rm", "-v", stack.secrets.resolve("cp_runtime_password") + ":/run/secrets/x:ro",
                    "-e", "SM_CONTROL_RUNTIME_PASSWORD_FILE=/run/secrets/x", "-e", "SM_CONTROL_RUNTIME_PASSWORD=" + secret,
                    "--entrypoint", "/opt/sm/bin/sm-tool", image, "com.solace.sm.cp.persistence.EntitlementMain");
            ev.observe("empty: " + emptyFile.output().trim() + " | missing: " + missing.output().trim() + " | both: " + both.output().trim());
            assertFalse(emptyFile.ok());
            assertFalse(missing.ok());
            assertFalse(both.ok());
            assertTrue(emptyFile.output().contains("SM_CONTROL_RUNTIME_PASSWORD_FILE is empty"), emptyFile.output());
            assertTrue(missing.output().contains("SM_DATA_RUNTIME_PASSWORD_FILE is not a readable regular file"), missing.output());
            assertTrue(both.output().contains("set only one of SM_CONTROL_RUNTIME_PASSWORD and SM_CONTROL_RUNTIME_PASSWORD_FILE"), both.output());
            assertFalse(both.output().contains(secret), "the value is never echoed");
            return "empty, missing and doubly-given secrets each stop the wrapper with a named variable and no value";
        });
    }

    @Test
    @Order(11)
    void theComposeFileFailsClosedOnItsEmptyVariablesAndRendersWhenTheyAreSet() throws Exception {
        scenario("P10", "compose.yml validates", "docker compose config refuses the shipped .env.example (every required value is empty, by design) "
                + "and renders once the documented variables are set", () -> {
            Result shipped = AgentTestSupport.run(List.of("docker", "compose", "--env-file", ".env.example", "-f", "compose.yml",
                    "--profile", "bootstrap", "--profile", "migrate", "config", "-q"), repo.resolve("runtime"), Duration.ofSeconds(60));
            ev.observe("with the shipped .env.example: " + (shipped.ok() ? "rendered" : shipped.output().lines().findFirst().orElse("")));
            assertFalse(shipped.ok(), "compose must fail closed while the required variables are empty");
            assertTrue(shipped.output().contains("missing a value") || shipped.output().contains("set it in .env"), shipped.output());
            Path filled = Files.writeString(stack.work.resolve("filled.env"), String.join("\n",
                    "SM_IMAGE=sm-launcher:dev", "SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH=hash-not-used", "SM_TRUSTED_ORIGIN=https://console.example.test",
                    "SM_TRUSTED_PROXY=172.18.0.1", "SM_AGENT_ENDPOINT=sm.example.test:9443", "SM_DP_MASTER_KEY_ID=packaging-it", ""));
            Result config = AgentTestSupport.run(List.of("docker", "compose", "--env-file", filled.toString(), "-f", "compose.yml",
                    "--profile", "bootstrap", "--profile", "migrate", "config", "-q"), repo.resolve("runtime"), Duration.ofSeconds(60));
            ev.observe("docker compose config with the variables set: " + (config.ok() ? "valid" : config.output()));
            assertTrue(config.ok(), config.output());
            return "refuses to render without the required variables; valid with them";
        });
    }
}
