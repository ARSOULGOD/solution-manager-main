package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.agw.GatewayCode;
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
import com.solace.sm.launcher.RealAgentHarness.Agent;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Plan 015 T4.4 (ported from plan 011 P7.1/P7.2/P6.3): the REAL Go {@code sm-agent} against the REAL Java data plane
 * ({@link SolutionManagerApplication}), over mTLS, driving a REAL Docker container. Everything is seeded through the product: the
 * enrolment is {@code issueAgentEnrolment} and the agent's own enrolment with that token; the instance and placement are made by
 * discovery (OR-27) from the container's {@code com.solace.sm.*} labels; generations are staged and applied through the console's
 * endpoints. Covered: enrolment and lease, preflight and apply through the apply engine, a restarting apply, telemetry committed before
 * it is acknowledged, a data-plane {@code RetryAfter} that the real agent retries (T3.4 note), a refused tampered payload, an agent
 * restart that resumes from its data dir, stop, planned drain mid-command with a renewal prompt (W4 owed F3), revocation, and a
 * redaction check over the captured JVM and agent logs.
 *
 * <p>Fails (never skips) without go, Docker, alpine and the Cache image ({@link AgentLive#require}); excluded from the ordinary build.
 * Run: {@code mvn -o -f backend/pom.xml -pl launcher -am -Pagent-live verify -Dit.test=RealAgentDockerE2EIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealAgentDockerE2EIT {

    private RealAgentHarness h;
    private Agent agent;
    private long generation1;
    private long generation2;
    private byte[] rendered1;
    private byte[] rendered2;
    private String keyedHash2;

    @org.junit.jupiter.api.AfterEach
    void faultsNeverLeakIntoTheNextTest() {
        FaultInjection.reset();
    }

    @BeforeAll
    void boot() throws Exception {
        h = RealAgentHarness.create(1, null, null);
        agent = h.agents.get(0);
        h.startDp(Map.of());
    }

    @AfterAll
    void shutdown() throws Exception {
        for (String name : extras) {
            AgentTestSupport.run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(30));
        }
        if (h != null) {
            h.preserveLogs(Path.of("target", "real-agent-e2e"));
            h.close();
        }
    }

    private String logTail() {
        return RealAgentHarness.agentLogTail(agent) + "\ncommands: " + h.commandsOf(agent);
    }

    // ------------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void aFreshAgentEnrollsOverMtlsIsDiscoveredAndHoldsALease() throws Exception {
        h.bringUp(agent);
        RealAgentHarness.await("a lease", Duration.ofSeconds(60), () -> h.jdbc().queryForObject(
                "select count(*) from sm_data.agent_lease where agent_id = ?::uuid and state = 'ACTIVE'", Integer.class, agent.id) == 1);
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_data.agent_identity where agent_id = ?::uuid", Integer.class,
                agent.id), "enrolment minted exactly one identity");
        assertEquals(1L, h.sessionEpoch(agent));
        assertTrue(Files.list(agent.data).findAny().isPresent(), "the agent persisted its enrolled identity in its data dir");
        // The enrolment token is single use: the control plane recorded it as consumed and kept no plaintext.
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_control.agent_registration where agent_id = ?::uuid "
                + "and enrollment_used_at is not null", Integer.class, agent.id));
        assertEquals(1, h.enrolments(agent), "one audited enrolment (the control plane's AGENT_ENROLLED)");
        // OR-27: the placement and the instance were made by discovery of the labelled container, none by a hand-made row
        assertEquals("REGISTERED", h.jdbc().queryForObject("select state from sm_data.agent_discovery where agent_id = ?::uuid "
                + "and container_name = ?", String.class, agent.id, agent.container));
        assertEquals(agent.conf.toString(), h.jdbc().queryForObject("select remote_config_directory from sm_data.placement "
                + "where placement_id = ?", String.class, agent.placement), "the placement's directory is the container's bind mount source");
    }

    @Test
    @Order(2)
    void preflightAndApplyStageGenerationOneThroughTheApplyEngine() throws Exception {
        generation1 = h.stage(agent, RealAgentHarness.PAYLOAD_MARKER + "-one");
        rendered1 = h.rendered(agent, generation1);
        ApplyId id = h.submitApply(agent, generation1, "manual");
        RequestState landed = h.awaitTerminal(agent, id, Duration.ofSeconds(120));
        assertEquals(RequestState.STAGED, landed, "manual apply stages: " + logTail());

        assertEquals(new String(rendered1, java.nio.charset.StandardCharsets.UTF_8), h.configOnHost(agent),
                "the agent wrote exactly the rendered bytes");
        List<String> ops = h.commandsOf(agent);
        // an engine step may legitimately be retried (observed under heavy CPU contention), so the contract asserted is
        // "preflight then apply, both acknowledged, each with its own strictly ascending wire generation"
        assertEquals("PREFLIGHT:ACKED:PREFLIGHT_OK", ops.get(0), "commands: " + ops);
        assertTrue(ops.contains("APPLY:ACKED:APPLIED"), "commands: " + ops);
        List<Long> numbers = h.jdbc().queryForList("select wire_generation from sm_data.agent_command where agent_id = ?::uuid "
                + "order by wire_generation", Long.class, agent.id);
        for (int i = 0; i < numbers.size(); i++) {
            assertEquals(i + 1L, numbers.get(i), "dense, strictly ascending per-placement numbering: " + numbers);
        }
        assertEquals(ops.size(), h.jdbc().queryForObject("select count(*) from sm_data.agent_command where agent_id = ?::uuid "
                + "and (config_hash is not null) = (operation in ('APPLY', 'REMATERIALIZE'))", Integer.class, agent.id),
                "only an apply carries a keyed hash");
    }

    @Test
    @Order(3)
    void aRestartingApplyChangesTheRealContainerAndTheTrustedReportConverges() throws Exception {
        String startedBefore = RealAgentHarness.inspect(agent, "{{.State.StartedAt}}");
        generation2 = h.stage(agent, RealAgentHarness.PAYLOAD_MARKER + "-two");
        rendered2 = h.rendered(agent, generation2);
        ApplyId id = h.submitApply(agent, generation2, "rolling-no-validation");
        RequestState landed = h.awaitTerminal(agent, id, Duration.ofSeconds(180));
        assertEquals(RequestState.APPLIED, landed, "restart-bearing apply lands APPLIED: " + logTail());

        assertEquals(new String(rendered2, java.nio.charset.StandardCharsets.UTF_8), h.configOnHost(agent));
        assertTrue(RealAgentHarness.inspect(agent, "{{.State.Running}}").contains("true"), "container is running after restart");
        assertNotEquals(startedBefore, RealAgentHarness.inspect(agent, "{{.State.StartedAt}}"), "the container really restarted");
        assertTrue(h.commandsOf(agent).contains("RESTART:ACKED:APPLIED"), h.commandsOf(agent).toString());
        keyedHash2 = h.jdbc().queryForObject("select config_hash from sm_data.agent_command where agent_id = ?::uuid and "
                + "operation = 'APPLY' order by wire_generation desc limit 1", String.class, agent.id);
        // the apply's read-back used the agent's report: a report was accepted for this placement (the watermark is the durable proof)
        RealAgentHarness.await("a durable report watermark", Duration.ofSeconds(60), () -> h.jdbc().queryForObject(
                "select count(*) from sm_data.agent_report_watermark where agent_id = ?::uuid and placement_id = ?", Integer.class,
                agent.id, agent.placement) == 1);
    }

    @Test
    @Order(4)
    void agentTelemetryIsCommittedAndQueryableThroughTheCanonicalPath() throws Exception {
        // the package's declarations reached the agent by push (nothing was seeded into a telemetry mapping), it scraped the
        // container's own JSON and the data plane committed it before acknowledging
        RealAgentHarness.await("committed telemetry for cache.hit_rate", Duration.ofSeconds(120),
                () -> h.latestSample(agent, "cache.hit_rate") != null);
        assertTrue(h.sampleCount(agent) > 0, "samples persisted");
        assertTrue(h.telemetryWatermark(agent) > 0, "the acknowledged watermark advanced only after commit");
        assertEquals(0.5, h.latestSample(agent, "cache.hit_rate"), 0.0001, "the value scraped from the container's HTTP-JSON endpoint");
    }

    @Test
    @Order(5)
    void aTelemetryBatchTheDataPlaneAnswersRetryAfterIsRetriedByTheRealAgentAndLandsOnce() throws Exception {
        RealAgentHarness.await("telemetry flowing", Duration.ofSeconds(60), () -> h.telemetryWatermark(agent) > 0);
        FaultInjection.CALLS.clear();
        FaultInjection.INGEST_RETRY_AFTER.set(1);
        RealAgentHarness.await("one batch answered RetryAfter", Duration.ofSeconds(60),
                () -> FaultInjection.CALLS.stream().anyMatch(c -> c.startsWith("ingest:RETRY_AFTER:")));
        long refusedSeq = Long.parseLong(FaultInjection.CALLS.stream().filter(c -> c.startsWith("ingest:RETRY_AFTER:"))
                .findFirst().orElseThrow().substring("ingest:RETRY_AFTER:".length()));
        long watermarkAtRefusal = h.telemetryWatermark(agent);
        long samplesAtRefusal = h.sampleCount(agent);
        RealAgentHarness.await("the same batch to be sent again and the stream to move past it", Duration.ofSeconds(90),
                () -> h.telemetryWatermark(agent) >= refusedSeq && h.telemetryWatermark(agent) > watermarkAtRefusal);
        long deliveries = FaultInjection.CALLS.stream().filter(c -> c.equals("ingest:" + refusedSeq)).count();
        assertTrue(deliveries >= 2, "the refused batch " + refusedSeq + " was offered again (ingest calls: " + FaultInjection.CALLS + ")");
        // "No sample twice" is metrics_raw's PRIMARY KEY (tenant_id, series_id, ts) (sm_metrics V001), so it is not asserted here. What the
        // RetryAfter must achieve is that the refused batch's samples LANDED: the watermark passed it (above) and rows were stored after it.
        RealAgentHarness.await("samples stored after the refused batch", Duration.ofSeconds(60), () -> h.sampleCount(agent) > samplesAtRefusal);
        assertTrue(h.active(agent), "the session is still ACTIVE: " + RealAgentHarness.health(agent));
        FaultInjection.reset();
    }

    @Test
    @Order(6)
    void restartingTheAgentResumesFromItsDataDirWithoutReEnrollingAndBumpsTheFencingEpoch() throws Exception {
        long before = h.sessionEpoch(agent);
        h.stopAgent(agent);
        h.startAgent(agent, false);
        RealAgentHarness.await("session reclaimed", Duration.ofSeconds(60), () -> h.sessionEpoch(agent) > before);
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_data.agent_identity where agent_id = ?::uuid", Integer.class,
                agent.id), "no second identity: no re-enrollment");
        assertEquals(1, h.enrolments(agent), "exactly one enrolment");
        RealAgentHarness.await("ACTIVE again", Duration.ofSeconds(60), () -> h.active(agent));
    }

    @Test
    @Order(7)
    void reApplyingAnAlreadyAppliedGenerationIsRefusedAtAdmissionAndRunsNoHostOperation() throws Exception {
        int commandsBefore = h.commandCount(agent);
        String startedBefore = RealAgentHarness.inspect(agent, "{{.State.StartedAt}}");
        ScopeKey scope = new ScopeKey(new TenantId(h.world.organization()), new InstanceId(agent.instance), new PlacementId(agent.placement));
        AdmissionResult result = h.dp.getBean(ApplyExecutor.class).admit(new ApplyCommand(scope, Operation.APPLY, generation2,
                Strategy.MANUAL, null, "operator-1"));
        assertTrue(result instanceof AdmissionResult.NotAdmitted, "a non-increasing generation is refused: " + result);
        assertEquals(commandsBefore, h.commandCount(agent), "no command was queued for the agent");
        assertEquals(startedBefore, RealAgentHarness.inspect(agent, "{{.State.StartedAt}}"), "the container was not touched");
        assertEquals(new String(rendered2, java.nio.charset.StandardCharsets.UTF_8), h.configOnHost(agent));
    }

    @Test
    @Order(8)
    void aCommandWhosePayloadDoesNotMatchItsHashIsRefusedByTheAgentAndTheHostNeverHoldsIt() throws Exception {
        long generation3 = h.stage(agent, RealAgentHarness.PAYLOAD_MARKER + "-three");
        FaultInjection.TAMPER_PULL.set(1);
        ApplyId id = h.submitApply(agent, generation3, "manual");
        try {
            RealAgentHarness.await("the agent's refusal", Duration.ofSeconds(90), () -> h.refusals(id) > 0);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + h.diagnose(id) + "\ninjected: " + FaultInjection.CALLS + "\n" + logTail(), e);
        }
        assertEquals("KEY_HASH_MISMATCH", h.jdbc().queryForObject("select detail_code from sm_data.agent_command where apply_id = ? "
                + "and state = 'REFUSED'", String.class, id.value()), "a coded protocol refusal, with its reason kept (not invalid.detail)");
        assertTrue(FaultInjection.CALLS.stream().anyMatch(c -> c.startsWith("pull:TAMPERED:")), "the tamper took effect");
        assertFalse(h.configOnHost(agent).contains("tampered"), "the host never held the tampered bytes");
        FaultInjection.reset();
        // the engine retries the step: the second offer is whole, so the request lands with the real payload
        RequestState landed = h.awaitTerminal(agent, id, Duration.ofSeconds(120));
        assertEquals(RequestState.STAGED, landed, h.diagnose(id));
        assertEquals(new String(h.rendered(agent, generation3), java.nio.charset.StandardCharsets.UTF_8), h.configOnHost(agent));
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_data.agent_command where apply_id = ? and state = 'REFUSED'",
                Integer.class, id.value()), "exactly one refusal");
    }

    @Test
    @Order(9)
    void aPlannedDrainWithACommandInFlightAndARenewalPromptCompletesAndTheAgentReturnsAfterTheAdvisedDelay() throws Exception {
        // W4 integration owed F3: promptRenewalNow, pushLease and renew() do not look at drainingSession, pushDeclarations does. The
        // measurement: drain the live real agent while it holds a command (so the drain window is open for seconds) and, inside that
        // window, register a SECOND placement (a second labelled container), which makes the lease stale and prompts a renewal.
        FaultInjection.reset();
        anIdleDrainReturnsAfterTheAdvisedDelay();
        FaultInjection.RENEW_EVENTS.clear();
        RealAgentHarness.await("ACTIVE", Duration.ofSeconds(60), () -> h.active(agent));
        long epochBefore = h.sessionEpoch(agent);
        int renewalsBefore = FaultInjection.RENEWALS.get();
        long leasesBefore = h.jdbc().queryForObject("select count(*) from sm_data.agent_lease where agent_id = ?::uuid", Long.class, agent.id);
        int drainEventsBefore = h.eventCount(agent, "gateway.drain");
        int completesBefore1 = h.eventCount(agent, "gateway.drain.complete");
        FaultInjection.DB_DELAY_MILLIS.set(2500);          // the terminal ack commits slowly: the command is in flight for seconds
        Instant since = Instant.now();
        ApplyId restart = h.admitRestart(agent);
        RealAgentHarness.await("the agent to hold the command", Duration.ofSeconds(60), () -> {
            String s = h.commandState(restart, "RESTART");
            return s.startsWith("DELIVERED") || s.startsWith("OFFERED");
        });
        Instant drainAt = Instant.now();
        HttpResponse<String> drained = h.post("/api/dp/agents/" + agent.id + "/drain", DeclareFixtures.ADMIN, null);
        assertEquals(202, drained.statusCode(), drained.body());
        // inside the open window: a second container is discovered into a second placement
        java.util.UUID secondPlacement = secondPlacement();
        // The drain notice is audited once. The answer's audit row (gateway.drain.complete) is awaited briefly and RECORDED, not required:
        // with the acknowledgement held 2.5 s the agent can answer and close before the gateway reads the answer, and then the row is not
        // written (plan 015 T4.4, finding 8; the outcome is durable either way, asserted below).
        RealAgentHarness.await("the drain to be audited", Duration.ofSeconds(60), () -> h.eventCount(agent, "gateway.drain") > drainEventsBefore);
        for (int i = 0; i < 100 && h.eventCount(agent, "gateway.drain.complete") <= completesBefore1; i++) {
            Thread.sleep(200);
        }
        System.out.println("[drain with a command in flight] gateway.drain.complete audited: "
                + (h.eventCount(agent, "gateway.drain.complete") > completesBefore1));
        assertEquals(drainEventsBefore + 1, h.eventCount(agent, "gateway.drain"));
        FaultInjection.DB_DELAY_MILLIS.set(0);
        RealAgentHarness.await("the agent to reconnect under a new epoch", Duration.ofSeconds(60), () -> h.sessionEpoch(agent) > epochBefore);
        Duration away = Duration.between(drainAt, Instant.now());
        RealAgentHarness.await("ACTIVE after the drain", Duration.ofSeconds(60), () -> h.active(agent));
        RealAgentHarness.await("the command to finish", Duration.ofSeconds(60), () -> h.commandState(restart, "RESTART").equals("ACKED:APPLIED"));
        assertEquals(1, RealAgentHarness.containerStarts(agent, since), "one real restart: the drain did not run it twice");
        // The advised reconnect delay is NOT asserted here: the agent counts it from the Drain notice, and this drain is held open for
        // seconds by the slowed acknowledgement, so any bound would hold whatever the agent did (the idle drain above is where it is measured).
        long leasesAfter = h.jdbc().queryForObject("select count(*) from sm_data.agent_lease where agent_id = ?::uuid", Long.class, agent.id);
        System.out.println("[F3 measurement, command in flight] " + drainTimeline(drainEventsBefore) + "; renewals seen by the port during the test "
                + (FaultInjection.RENEWALS.get() - renewalsBefore) + ": " + FaultInjection.RENEW_EVENTS + "; leases " + leasesBefore + " -> "
                + leasesAfter + "; away " + away.toMillis() + " ms; second placement " + secondPlacement);
        // F3's facts, asserted (the "one ACTIVE lease" check this replaces is the partial unique index agent_lease_one_active, V028, and
        // could not fail). A renewal reached the port while the drain was open, none was lost mid-flight, and the lease that is ACTIVE
        // afterwards is a current one on the open session.
        assertTrue(FaultInjection.RENEWALS.get() > renewalsBefore, "a renewal reached the port during the drain test");
        assertEquals(FaultInjection.RENEW_EVENTS.stream().filter(e -> e.startsWith("start@")).count(),
                FaultInjection.RENEW_EVENTS.stream().filter(e -> e.startsWith("done@")).count(), "every renewal that started completed");
        assertCurrentLeaseOnAnOpenSession();
    }

    /**
     * An IDLE drain (nothing in flight, so nothing holds it open): the agent leaves and returns no sooner than the advised delay after
     * the Drain notice (the agent counts it from the notice; both instants are the database's clock), and within the delay plus spread.
     */
    private void anIdleDrainReturnsAfterTheAdvisedDelay() throws Exception {
        RealAgentHarness.await("ACTIVE", Duration.ofSeconds(60), () -> h.active(agent));
        long epoch = h.sessionEpoch(agent);
        int drains = h.eventCount(agent, "gateway.drain");
        HttpResponse<String> drained = h.post("/api/dp/agents/" + agent.id + "/drain", DeclareFixtures.ADMIN, null);
        assertEquals(202, drained.statusCode(), drained.body());
        RealAgentHarness.await("the drain to be audited", Duration.ofSeconds(30), () -> h.eventCount(agent, "gateway.drain") > drains);
        RealAgentHarness.await("the agent to reconnect", Duration.ofSeconds(60), () -> h.sessionEpoch(agent) > epoch);
        long gap = h.jdbc().queryForObject("select (extract(epoch from (select opened_at from sm_data.agent_session where agent_id = ?::uuid)) "
                + "- extract(epoch from (select max(occurred_at) from sm_data.agent_event where agent_id = ?::uuid and code = 'gateway.drain'))) * 1000",
                Double.class, agent.id, agent.id).longValue();
        System.out.println("[idle drain] Drain notice to the new session: " + gap + " ms (advised 1 s + up to 6 s spread)");
        assertTrue(gap >= 1000, "the agent honoured drain-reconnect-min (1 s) after an idle drain: " + gap + " ms");
        assertTrue(gap <= 15_000, "and returned within the minimum plus spread plus slack: " + gap + " ms");
        RealAgentHarness.await("ACTIVE after the idle drain", Duration.ofSeconds(60), () -> h.active(agent));
    }

    /** The agent has an ACTIVE lease that has not lapsed, and its session row is open. */
    private void assertCurrentLeaseOnAnOpenSession() {
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_data.agent_lease where agent_id = ?::uuid and state = 'ACTIVE' "
                + "and expires_at > now()", Integer.class, agent.id), "an unexpired ACTIVE lease");
        assertEquals(0, h.jdbc().queryForObject("select count(*) from sm_data.agent_session where agent_id = ?::uuid and closed_at is not null",
                Integer.class, agent.id), "the session is open");
    }

    /** Epoch milliseconds of the newest gateway.drain / gateway.drain.complete audit rows (after {@code drainsBefore} drains). */
    private String drainTimeline(int drainsBefore) {
        Long drain = h.jdbc().queryForObject("select (extract(epoch from max(occurred_at)) * 1000)::bigint from sm_data.agent_event "
                + "where agent_id = ?::uuid and code = 'gateway.drain'", Long.class, agent.id);
        Long complete = h.jdbc().queryForObject("select (extract(epoch from max(occurred_at)) * 1000)::bigint from sm_data.agent_event "
                + "where agent_id = ?::uuid and code = 'gateway.drain.complete'", Long.class, agent.id);
        return "drain@" + drain + " drain.complete@" + complete;
    }

    @Test
    @Order(10)
    void aRenewalInFlightWhenTheDrainBeginsDoesNotStrandTheDrainOrTheAgent() throws Exception {
        // W4 integration owed F3, the sharper form: promptRenewalNow, pushLease and renew() do not check drainingSession. Hold a renewal
        // IN FLIGHT (the port answers it 4 s late), start the drain while it is, and observe what the real agent does with a lease that
        // arrives after Drain: does it still answer DrainComplete, close, and come back after the advised delay?
        FaultInjection.reset();
        FaultInjection.RENEW_EVENTS.clear();
        RealAgentHarness.await("ACTIVE", Duration.ofSeconds(60), () -> h.active(agent));
        long epochBefore = h.sessionEpoch(agent);
        int drainsBefore = h.eventCount(agent, "gateway.drain");
        int completesBefore = h.eventCount(agent, "gateway.drain.complete");
        FaultInjection.RENEW_DELAY_MILLIS.set(4000);
        java.util.UUID secondPlacement = secondPlacement();     // makes the lease stale: the gateway prompts a renewal
        RealAgentHarness.await("a renewal to be in flight", Duration.ofSeconds(30),
                () -> FaultInjection.RENEW_EVENTS.stream().anyMatch(e -> e.startsWith("start@"))
                        && FaultInjection.RENEW_EVENTS.stream().noneMatch(e -> e.startsWith("done@")));
        Instant drainAt = Instant.now();
        HttpResponse<String> drained = h.post("/api/dp/agents/" + agent.id + "/drain", DeclareFixtures.ADMIN, null);
        assertEquals(202, drained.statusCode(), drained.body());
        // the answer's audit row can be lost to the agent's fast close (finding 8), so the new session is the other proof the drain ended
        RealAgentHarness.await("the drain to complete", Duration.ofSeconds(60),
                () -> h.eventCount(agent, "gateway.drain.complete") > completesBefore || h.sessionEpoch(agent) > epochBefore);
        RealAgentHarness.await("the renewal to finish", Duration.ofSeconds(30),
                () -> FaultInjection.RENEW_EVENTS.stream().anyMatch(e -> e.startsWith("done@")));
        FaultInjection.RENEW_DELAY_MILLIS.set(0);
        RealAgentHarness.await("the agent to reconnect under a new epoch", Duration.ofSeconds(60), () -> h.sessionEpoch(agent) > epochBefore);
        Duration away = Duration.between(drainAt, Instant.now());
        RealAgentHarness.await("ACTIVE after the drain", Duration.ofSeconds(60), () -> h.active(agent));
        System.out.println("[F3 measurement, renewal in flight at the drain] " + drainTimeline(drainsBefore) + "; drain requested@"
                + drainAt.toEpochMilli() + "; renewals: " + FaultInjection.RENEW_EVENTS + "; away " + away.toMillis() + " ms; second placement "
                + secondPlacement + "; health after: " + RealAgentHarness.health(agent));
        assertEquals(1, h.eventCount(agent, "gateway.drain") - drainsBefore, "one drain");
        assertTrue(FaultInjection.RENEW_EVENTS.stream().filter(e -> e.startsWith("start@")).count() >= 1, "the held renewal was seen");
        assertEquals(FaultInjection.RENEW_EVENTS.stream().filter(e -> e.startsWith("start@")).count(),
                FaultInjection.RENEW_EVENTS.stream().filter(e -> e.startsWith("done@")).count(), "the held renewal completed, none lost");
        assertCurrentLeaseOnAnOpenSession();
        assertFalse(RealAgentHarness.health(agent).contains("halted_reason"), "the agent was not halted: " + RealAgentHarness.health(agent));
    }

    /** A second labelled container for the same agent: returns the placement discovery makes for it (awaited, not seeded). */
    private java.util.UUID secondPlacement() throws Exception {
        String name = "sm-m" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path conf = Files.createDirectories(agent.root.resolve(name));
        extras.add(name);
        AgentTestSupport.Result started = AgentTestSupport.startManagedContainer(name, "inst-" + name.substring(4), conf);
        assertTrue(started.ok(), started.output());
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> found = new java.util.concurrent.atomic.AtomicReference<>();
        RealAgentHarness.await("the second container to be registered", Duration.ofSeconds(90), () -> {
            List<java.util.UUID> rows = h.jdbc().queryForList("select placement_id from sm_data.agent_discovery where agent_id = ?::uuid "
                    + "and container_name = ? and state = 'REGISTERED'", java.util.UUID.class, agent.id, name);
            found.set(rows.isEmpty() ? null : rows.get(0));
            return !rows.isEmpty();
        });
        return found.get();
    }

    private final List<String> extras = new java.util.ArrayList<>();

    @Test
    @Order(11)
    void stopStopsTheRealContainerAndIsObservedNotJustIssued() throws Exception {
        ApplyId id = h.submitStop(agent);
        RequestState landed = h.awaitTerminal(agent, id, Duration.ofSeconds(120));
        assertEquals(RequestState.STOPPED, landed, "stop lands STOPPED: " + logTail());
        RealAgentHarness.await("container stopped", Duration.ofSeconds(30),
                () -> RealAgentHarness.inspect(agent, "{{.State.Running}}").contains("false"));
        assertEquals(1, h.jdbc().queryForObject("select count(*) from sm_data.agent_command where agent_id = ?::uuid and "
                + "operation = 'STOP' and state = 'ACKED'", Integer.class, agent.id));
    }

    @Test
    @Order(12)
    void revokingTheAgentClosesItsSessionAndTheNextConnectIsRefused() throws Exception {
        HttpResponse<String> revoked = h.post("/api/cp/agents/" + agent.id + "/revoke", DeclareFixtures.ADMIN, null);
        assertEquals(200, revoked.statusCode(), revoked.body());
        Instant at = Instant.now();
        RealAgentHarness.await("the agent to be halted by Revoke", Duration.ofSeconds(30), () -> RealAgentHarness.health(agent)
                .contains("IDENTITY_REVOKED") || RealAgentHarness.health(agent).contains("\"authority_phase\":\"REVOKED\""));
        assertTrue(Duration.between(at, Instant.now()).toSeconds() < 15, "within the revocation-check interval plus delivery");
        // Revoke is sent before the audit row is written (revokeAndClose), so the agent can report itself revoked first: wait for the row.
        RealAgentHarness.await("the revocation to be audited", Duration.ofSeconds(15), () -> h.eventCount(agent, "gateway.revoked") >= 1);
        assertEquals(1, h.eventCount(agent, "gateway.revoked"), "the revocation is audited once");
        long epochAtRevoke = h.sessionEpoch(agent);
        assertEquals(0, h.jdbc().queryForObject("select count(*) from sm_data.agent_lease where agent_id = ?::uuid and state = 'ACTIVE'",
                Integer.class, agent.id), "no live lease");
        // a restarted agent cannot claim a session
        h.killAgent(agent);
        h.startAgent(agent, false);
        RealAgentHarness.await("the restarted agent to be refused", Duration.ofSeconds(30),
                () -> RealAgentHarness.health(agent).contains("IDENTITY_REVOKED"));
        assertEquals(epochAtRevoke, h.sessionEpoch(agent), "no new session was claimed");
        assertFalse(h.active(agent));
    }

    @Test
    @Order(13)
    void noSecretPayloadDigestOrTokenReachesTheCapturedJavaOrAgentLogs(CapturedOutput output) throws Exception {
        String javaLogs = output.getAll();
        String agentLogs = RealAgentHarness.agentLog(agent);
        String serverKey = Files.readString(h.pki.serverKey);
        java.util.List<String> secrets = new java.util.ArrayList<>(List.of(agent.token, RealAgentHarness.PAYLOAD_MARKER,
                RealAgentHarness.CA_PASSWORD, "BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY", "BEGIN CERTIFICATE",
                serverKey.lines().skip(1).findFirst().orElse("<none>")));
        for (byte[] payload : new byte[][] {rendered1, rendered2}) {
            String plain = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
            secrets.add(plain);
        }
        if (keyedHash2 != null) {
            secrets.add(keyedHash2.substring(keyedHash2.indexOf(':') + 1));        // the keyed digest of the applied config
        }
        for (String secret : secrets) {
            assertFalse(javaLogs.contains(secret), "Java logs leaked: " + redact(secret));
            assertFalse(agentLogs.contains(secret), "agent logs leaked: " + redact(secret));
        }
        assertFalse(agentLogs.isBlank(), "the agent actually logged (so the leak check is meaningful)");
        assertFalse(javaLogs.isBlank(), "the Java side actually logged");
    }

    private static String redact(String secret) {
        return secret.length() <= 6 ? "***" : secret.substring(0, 3) + "...(" + secret.length() + " chars)";
    }
}
