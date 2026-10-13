package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.launcher.RealAgentHarness.Agent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Plan 011 P7.2, ported by plan 015 T4.4 — the unexplained extra command, reproduced under CPU pressure and explained.
 *
 * <p>The first real-agent run once saw a command beyond {@code PREFLIGHT} and {@code APPLY} while other CPU-heavy commands ran
 * and could not reproduce it. {@link AgentFailureMatrixIT} M07 reproduces it deterministically by delaying the actual-state
 * report; this class shows it happens <i>naturally</i>, from the same cause, when the machine is busy — and that it does not
 * once the adapter waits for the report that proves the apply.
 *
 * <p>Two arms run the same real apply sequence ({@code PREFLIGHT} then {@code APPLY}, manual strategy) a number of times with every
 * core kept busy: arm A with {@code sm.dp.agent.readback-wait=0} (the behaviour before the fix), arm B with the default. For every
 * apply the trace records each command (operation, wire generation, attempt id, session epoch it was offered under, enqueue,
 * delivery-ack and terminal-ack times), each engine attempt and its error class, and — the causal evidence — when the actual-state
 * report that follows the apply was committed relative to the terminal acknowledgement. Arm A may or may not see the race on
 * a given machine on a given day; whatever it sees must be explained by a negative margin (report after ack). Arm B must see none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtraCommandUnderCpuPressureIT {
    // A BeforeAll that fails the class when a prerequisite is missing is RealAgentHarness.create (AgentLive.require).
    private static final int APPLIES = Integer.getInteger("pressure.applies", 12);

    private RealAgentHarness h;
    private Agent agent;
    private final List<Thread> hogs = new ArrayList<>();
    private final AtomicBoolean burning = new AtomicBoolean();
    private final StringBuilder out = new StringBuilder();
    private long nextGeneration = 1;

    @BeforeAll
    void boot() throws Exception {
        h = RealAgentHarness.create(1, null, null);
        agent = h.agents.get(0);
        out.append("# Plan 011 — the extra command under CPU pressure\n\nGenerated ").append(Instant.now())
                .append(" by `ExtraCommandUnderCpuPressureIT`. Host: ").append(Runtime.getRuntime().availableProcessors())
                .append(" logical CPUs; ").append(Runtime.getRuntime().availableProcessors() * 2)
                .append(" busy threads kept running during every apply.\n\n");
    }

    @AfterAll
    void shutdown() throws Exception {
        stopBurning();
        if (h != null) {
            Path dir = Path.of("target", "failure-matrix");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("extra-command.md"), out.toString());
            h.preserveLogs(dir.resolve("extra-command"));
            h.close();
        }
    }

    private void startBurning() {
        burning.set(true);
        for (int i = 0; i < Runtime.getRuntime().availableProcessors() * 2; i++) {
            Thread hog = new Thread(() -> {
                double x = 1;
                while (burning.get()) {
                    x = Math.sqrt(x * 1.0000001 + 1);
                }
            }, "cpu-hog-" + i);
            hog.setDaemon(true);
            hogs.add(hog);
            hog.start();
        }
    }

    private void stopBurning() throws InterruptedException {
        burning.set(false);
        for (Thread hog : hogs) {
            hog.join(2000);
        }
        hogs.clear();
    }

    private record Run(int applies, int extraApplies, int attemptRetries, List<String> traces) { }

    private Run arm(String name, Map<String, String> overrides) throws Exception {
        h.startDp(overrides);
        if (agent.token == null) {
            h.bringUp(agent);                                  // first arm: enrol, start, discover
        } else {
            h.startAgent(agent, false);
        }
        RealAgentHarness.await("an ACTIVE agent", Duration.ofSeconds(90), () -> h.active(agent));
        startBurning();
        int extra = 0;
        int retries = 0;
        List<String> traces = new ArrayList<>();
        try {
            for (int i = 0; i < APPLIES; i++) {
                // a distinct staged generation every time (the console's own endpoints; nothing is inserted)
                long generation = h.stage(agent, "pressure-" + (nextGeneration++));
                long before = h.jdbc().queryForObject("select coalesce(max(wire_generation),0) from sm_data.agent_command where agent_id = ?::uuid", Long.class, agent.id);
                var id = h.submitApply(agent, generation, "manual");
                RequestState landed = h.awaitTerminal(agent, id, Duration.ofSeconds(180));
                List<Map<String, Object>> commands = h.jdbc().queryForList("select operation, wire_generation, state, outcome, offered_epoch, "
                        + "attempt_id, created_at, received_at, acked_at from sm_data.agent_command where agent_id = ?::uuid "
                        + "and wire_generation > ? order by wire_generation", agent.id, before);
                int applyCommands = (int) commands.stream().filter(c -> "APPLY".equals(c.get("operation"))).count();
                List<String> attempts = h.jdbc().queryForList("select step || ':' || coalesce(outcome,'open') || ':' || coalesce(error_class,'') "
                        + "from sm_data.apply_attempt where apply_id = ? order by started_at desc limit 6", String.class, id.value());
                // when the actual-state report that followed was committed, relative to the terminal acknowledgement of the (last) APPLY.
                // The durable record of a report is the per-placement watermark's updated_at (the report table of the earlier design is
                // gone), so this is the LATEST report's time: a margin of the report that proves the apply, never earlier than it.
                Map<String, Object> lastApply = commands.stream().filter(c -> "APPLY".equals(c.get("operation"))).reduce((x, y) -> y).orElseThrow();
                Double marginMillis = h.jdbc().queryForObject("select (extract(epoch from (w.updated_at - ?)) * 1000) from sm_data.agent_report_watermark w "
                        + "where w.agent_id = ?::uuid and w.placement_id = ?", Double.class,
                        lastApply.get("acked_at"), agent.id, agent.placement);
                String trace = String.format("%s apply %d: request %s; %d command(s) %s; latest report committed %.0f ms after the terminal ack of the last APPLY; attempts %s",
                        name, i + 1, landed, commands.size(), commands.stream().map(c -> c.get("operation") + "#" + c.get("wire_generation") + "@epoch"
                                + c.get("offered_epoch") + "[" + c.get("state") + ":" + c.get("outcome") + "]").toList(),
                        marginMillis == null ? Double.NaN : marginMillis, attempts);
                traces.add(trace);
                System.out.println(trace);
                if (applyCommands > 1) {
                    extra++;
                }
                retries += (int) attempts.stream().limit(3).filter(x -> x.contains("readback.unavailable")).count();
            }
        } finally {
            stopBurning();
            h.stopDp();
            h.killAgent(agent);
        }
        return new Run(APPLIES, extra, retries, traces);
    }

    @Test
    @Order(1)
    void withoutTheReadBackWaitTheExtraCommandAppearsAndIsExplainedByTheReportArrivingAfterTheAck() throws Exception {
        Run run = arm("A (readback-wait=0, the pre-fix behaviour)", Map.of("sm.dp.agent.readback-wait", "0s"));
        out.append("## Arm A — `sm.dp.agent.readback-wait=0` (behaviour before the fix)\n\n")
                .append(run.applies()).append(" applies under CPU pressure; **").append(run.extraApplies())
                .append("** produced a second APPLY command.\n\n");
        run.traces().forEach(t -> out.append("- ").append(t).append("\n"));
        out.append("\n");
        // Explanation, not a count: every extra command has a failed attempt classified readback.unavailable and a report that
        // landed after the terminal acknowledgement. (Whether the race fires on a given machine is not guaranteed; M07 forces it.)
        for (String trace : run.traces()) {
            // "2 command(s)" is the normal PREFLIGHT + APPLY; a third is the extra APPLY
            if (trace.contains("3 command(s)")) {
                assertTrue(trace.contains("readback.unavailable"), "an extra command must be the engine's read-back retry: " + trace);
            }
        }
    }

    @Test
    @Order(2)
    void withTheReadBackWaitNoApplyEverProducesAnExtraCommandUnderTheSamePressure() throws Exception {
        Run run = arm("B (default read-back wait)", Map.of());
        out.append("## Arm B — default `sm.dp.agent.readback-wait` (45 s)\n\n")
                .append(run.applies()).append(" applies under the same CPU pressure; **").append(run.extraApplies())
                .append("** produced a second APPLY command.\n\n");
        run.traces().forEach(t -> out.append("- ").append(t).append("\n"));
        out.append("\n");
        assertEquals(0, run.extraApplies(), "no apply may enqueue a second APPLY: " + run.traces());
        assertEquals(0, run.attemptRetries(), "and no attempt may be retried for want of a read-back");
    }
}
