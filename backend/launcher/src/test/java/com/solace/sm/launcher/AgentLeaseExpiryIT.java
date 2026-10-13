package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.launcher.RealAgentHarness.Agent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Plan 011 P7.2, ported by plan 015 T4.4: the lease scenarios of execution plan section 4, against the real Go agent with a lease short
 * enough to let expire. The control plane's smallest allowed lease is 30 s but the DP admits a command only while
 * {@code expires_at - now >= ceiling + margin} (restart: 120 s), so the smallest lease a restart can ever be admitted under is 150 s with
 * no margin (set through {@link RealAgentHarness#tuneLease}: SQL GAP 1, no operation sets a registration's lease time).
 *
 * <p>Two things can end a lease with a healthy agent: the DP going away for longer than the lease lives, and renewals being refused until
 * it lapses. Both must fail closed (no work runs, no false convergence) and both must recover without an operator once the cause clears:
 * an expired lease is renewed with the nonce of its last grant, never re-issued and never fatal.
 *
 * <p>In this design the adapter refuses work for a placement that no live, leased session holds (fail fast, {@code agent.session.absent})
 * instead of queueing it; so while the lease is expired the proof is that the request does not apply and no host operation runs, and after
 * recovery new work runs exactly once. Lease expiry <i>during</i> a step is not reachable with the control plane's ceilings (a step is
 * admitted only while the remaining lease exceeds its ceiling plus margin); it is covered by the agent's reducer tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentLeaseExpiryIT {
    private static final String NOTICE_RETRY = "3s";
    private static final Map<String, String> DP = Map.of("sm.agent.gateway.notice-retry-interval", NOTICE_RETRY);

    private RealAgentHarness h;
    private Agent agent;
    private MatrixEvidence ev;

    @FunctionalInterface
    private interface Body {
        String run() throws Exception;
    }

    @BeforeAll
    void boot() throws Exception {
        h = RealAgentHarness.create(1, 150, 0);
        agent = h.agents.get(0);
        ev = new MatrixEvidence("Plan 011 P7.2 / plan 015 T4.4 — lease expiry and recovery (real Go agent, 150 s lease)",
                Path.of("target", "failure-matrix", "lease-expiry.md"));
        h.startDp(DP);
        h.bringUp(agent);
    }

    @AfterAll
    void shutdown() throws Exception {
        if (h != null) {
            h.preserveLogs(Path.of("target", "failure-matrix", "lease-expiry"));
            h.close();
        }
    }

    private String authorityPhase() {
        String health = RealAgentHarness.health(agent);
        int i = health.indexOf("\"authority_phase\":\"");
        return i < 0 ? "?" : health.substring(i + 19, health.indexOf('"', i + 19));
    }

    private void scenario(String id, String title, String expected, Body body) throws Exception {
        ev.begin(id, title, expected);
        FaultInjection.reset();
        try {
            ev.pass(body.run());
        } catch (Throwable t) {
            ev.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            ev.observe("agent log tail:\n" + RealAgentHarness.agentLogTail(agent));
            throw t;
        } finally {
            FaultInjection.reset();
        }
    }

    /** The digest of the lease's renewal nonce: it changes with every grant. */
    private String leaseNonceDigest() {
        return h.jdbc().queryForObject("select renewal_nonce_digest from sm_data.agent_lease where agent_id = ?::uuid and state = 'ACTIVE'",
                String.class, agent.id);
    }

    @Test
    @Order(1)
    void renewalsRefusedUntilTheLeaseLapsesFailClosedThenRecoverWithoutAnOperator() throws Exception {
        scenario("L1", "Lease expiry with a healthy DP (renewals refused)",
                "The lease lapses: the agent goes LEASE_EXPIRED and runs nothing, the DP offers nothing and reports nothing "
                        + "false; when renewals work again the agent renews with its last nonce (a new lease is asserted), and new work runs "
                        + "exactly once", () -> {
            String nonceBefore = leaseNonceDigest();
            // Not LEASE_RENEW_REFUSED: that means "the nonce is not honoured" and, by design, the gateway tells the agent to RE-ENROL (SessionReject
            // RE_ENROLL_REQUIRED, which halts it). A renewal that merely cannot be done now (the control plane unreachable) is the code that
            // leaves the agent holding its lease and retrying until it lapses.
            FaultInjection.RENEW_REFUSAL = GatewayCode.INTERNAL_UNAVAILABLE;
            Instant started = Instant.now();
            RealAgentHarness.await("the lease to lapse", Duration.ofSeconds(240), () -> authorityPhase().equals("LEASE_EXPIRED"));
            ev.observe("agent reached LEASE_EXPIRED " + Duration.between(started, Instant.now()).toSeconds() + " s after renewals began to be refused");

            Instant since = Instant.now();
            ApplyId held = h.admitRestart(agent);
            RequestState state = h.awaitTerminal(agent, held, Duration.ofSeconds(120));
            Thread.sleep(3000);
            ev.observe("request under the expired lease ended " + state + "; commands " + h.commandsOf(agent) + "; agent phase " + authorityPhase());
            assertNotEquals(RequestState.APPLIED, state, "nothing applies under an expired lease");
            assertEquals(0, RealAgentHarness.containerStarts(agent, since), "no host operation ran while the lease was expired");

            FaultInjection.RENEW_REFUSAL = null;
            // The session closed when the lease lapsed, so the agent returns on its own backoff (min(5 min, 1 s * 2^n), full jitter): up to 5 minutes.
            RealAgentHarness.await("the agent to recover", Duration.ofMinutes(6), () -> h.active(agent));
            assertNotEquals(nonceBefore, leaseNonceDigest(), "a new lease was granted through the renewal path");
            Instant after = Instant.now();
            ApplyId next = h.admitRestart(agent);
            RealAgentHarness.await("work to run", Duration.ofSeconds(90), () -> h.commandState(next, "RESTART").equals("ACKED:APPLIED"));
            int starts = RealAgentHarness.containerStarts(agent, after);
            ev.observe("recovered; lease nonce changed; new work ran; docker starts=" + starts);
            assertEquals(1, starts);
            // Not asserted (the previous OR could not fail once the agent is ACTIVE): whether the recovery was the DP's prompt is recorded.
            ev.observe("the agent's log shows the DP's renewal prompt (LEASE_RENEW_REQUIRED): " + RealAgentHarness.agentLog(agent).contains("LEASE_RENEW_REQUIRED"));
            return "fail-closed while expired, recovered once renewals worked again, new work ran once";
        });
    }

    @Test
    @Order(2)
    void aDpOutageLongerThanTheLeaseDoesNotStrandTheAgent() throws Exception {
        scenario("L2", "Lease expiry across a DP outage",
                "The DP is away longer than the lease lives: the agent fails closed, and when the DP returns it is told "
                        + "LEASE_RENEW_REQUIRED, renews with its last nonce and resumes — it is never halted with "
                        + "ISSUE_NOT_ALLOWED", () -> {
            String nonceBefore = leaseNonceDigest();
            long epochBefore = h.sessionEpoch(agent);
            h.stopDp();
            Instant down = Instant.now();
            RealAgentHarness.await("the lease to lapse with the DP down", Duration.ofSeconds(240),
                    () -> authorityPhase().equals("LEASE_EXPIRED"));
            ev.observe("DP down " + Duration.between(down, Instant.now()).toSeconds() + " s; agent phase LEASE_EXPIRED; health: "
                    + RealAgentHarness.health(agent));

            h.startDp(DP);
            Instant backAt = Instant.now();
            // The agent dials again on its own schedule: min(5 min, 1 s * 2^attempt) with FULL jitter (go-agent backoff.go), so after an
            // outage of this length the next attempt is uniformly anywhere in the first 256 s to 5 min. The wait is therefore the cap plus
            // a margin, and the return time is recorded (an operational fact for the runbook, not a defect).
            RealAgentHarness.await("the agent to come back ACTIVE", Duration.ofMinutes(6), () -> h.active(agent));
            ev.observe("the agent was ACTIVE again " + Duration.between(backAt, Instant.now()).toSeconds() + " s after the DP came back (its backoff cap is 5 min, full jitter)");
            assertFalse(RealAgentHarness.health(agent).contains("halted_reason"), "never halted: " + RealAgentHarness.health(agent));
            assertNotEquals(nonceBefore, leaseNonceDigest(), "renewed through the nonce path, not re-issued");
            assertTrue(h.sessionEpoch(agent) > epochBefore);
            ev.observe("agent ACTIVE again, epoch " + epochBefore + " -> " + h.sessionEpoch(agent) + ", lease nonce rotated");

            Instant since = Instant.now();
            ApplyId after = h.admitRestart(agent);
            RealAgentHarness.await("work to resume", Duration.ofSeconds(90), () -> h.commandState(after, "RESTART").equals("ACKED:APPLIED"));
            assertEquals(1, RealAgentHarness.containerStarts(agent, since));
            return "recovered from an outage longer than the lease without operator action; work resumed and ran once";
        });
    }
}
