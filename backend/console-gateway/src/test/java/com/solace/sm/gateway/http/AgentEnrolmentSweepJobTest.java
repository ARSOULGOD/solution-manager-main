package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T4.6 (OR-140): the scheduled enrolment sweep. One pass asks the control plane for the organizations that have a
 * candidate, sweeps each, and survives a failure in the enumeration or in one organization. The attribution (the system actor) is
 * the control plane's and is proved against PostgreSQL; here the job's own contract.
 */
class AgentEnrolmentSweepJobTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private final List<String> calls = new ArrayList<>();
    private final List<UUID> organizations = new ArrayList<>();
    private RuntimeException enumerationFailure;
    private UUID failingOrganization;

    private final AgentEnrolmentSweep controlPlane = new AgentEnrolmentSweep() {
        @Override
        public List<UUID> organizations(Duration olderThan) {
            calls.add("cp.organizations:" + olderThan);
            if (enumerationFailure != null) {
                throw enumerationFailure;
            }
            return List.copyOf(organizations);
        }

        @Override
        public List<Candidate> candidates(UUID organizationId, Duration olderThan, Candidate after, int limit) {
            calls.add("cp.candidates:" + organizationId);
            if (organizationId.equals(failingOrganization)) {
                throw new IllegalStateException("database down");
            }
            return List.of(new Candidate(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH));
        }

        @Override
        public void revoke(UUID organizationId, Candidate candidate, UUID correlationId) {
            calls.add("cp.revoke:" + organizationId);
        }

        @Override
        public void retire(UUID organizationId, Candidate candidate, UUID correlationId) {
            calls.add("cp.retire:" + organizationId);
        }
    };

    private final TargetConnectionService dataPlane = new TargetConnectionService() {
        @Override
        public void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey, UUID actor) {
            throw new AssertionError();
        }

        @Override
        public void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor) {
            throw new AssertionError();
        }

        @Override
        public Map<UUID, TargetEnvelope> liveEnvelopes(UUID organizationId, Collection<UUID> targetIds) {
            return Map.of();
        }
    };

    private final AgentTargetSweep sweep = new AgentTargetSweep(controlPlane, dataPlane, UUID::randomUUID, WINDOW);

    private AgentEnrolmentSweepJob job(boolean enabled) {
        return new AgentEnrolmentSweepJob(controlPlane, sweep, enabled, Duration.ofMillis(50));
    }

    @Test
    void aPassSweepsEveryOrganizationTheControlPlaneNamesUnderTheAbandonWindow() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        organizations.addAll(List.of(a, b));

        job(true).runOnce();

        assertEquals("cp.organizations:" + WINDOW, calls.get(0));
        assertTrue(calls.contains("cp.revoke:" + a) && calls.contains("cp.retire:" + a), calls.toString());
        assertTrue(calls.contains("cp.revoke:" + b) && calls.contains("cp.retire:" + b), calls.toString());
    }

    @Test
    void noOrganizationWithACandidateIsNoWork() {
        job(true).runOnce();
        assertEquals(List.of("cp.organizations:" + WINDOW), calls);
    }

    @Test
    void aFailedEnumerationIsSwallowedAndTheNextPassAsksAgain() {
        enumerationFailure = new IllegalStateException("database down");
        AgentEnrolmentSweepJob job = job(true);
        job.runOnce();
        enumerationFailure = null;
        organizations.add(UUID.randomUUID());
        job.runOnce();
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("cp.revoke")), calls.toString());
    }

    @Test
    void oneOrganizationsFailureDoesNotStopTheRest() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        failingOrganization = bad;
        organizations.addAll(List.of(bad, good));

        job(true).runOnce();

        assertTrue(calls.contains("cp.revoke:" + good), calls.toString());
        assertFalse(calls.contains("cp.revoke:" + bad));
    }

    @Test
    void aDisabledJobNeverRunsAndAnEnabledOneRunsAndStops() throws Exception {
        organizations.add(UUID.randomUUID());
        AgentEnrolmentSweepJob disabled = job(false);
        disabled.start();
        assertFalse(disabled.isRunning());
        Thread.sleep(150);
        assertTrue(calls.isEmpty(), "withdrawn: nothing ran");

        AgentEnrolmentSweepJob enabled = job(true);
        enabled.start();
        assertTrue(enabled.isRunning());
        long deadline = System.currentTimeMillis() + 5000;
        while (calls.stream().noneMatch(c -> c.startsWith("cp.retire")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        enabled.stop();
        assertFalse(enabled.isRunning());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("cp.retire")), "the scheduled pass ran");
    }

    @Test
    void aNonPositiveIntervalIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AgentEnrolmentSweepJob(controlPlane, sweep, true, Duration.ZERO));
    }
}
