package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T3.5, T4.6: the sweep of an interrupted agent enrolment for one organization (run by the scheduled job), against fakes of the two ports it asks. The control plane pages
 * the agent targets whose token expired a window ago, the data plane says which hold a connection, and ONLY the others are
 * abandoned: a completed enrolment is never touched however old its token is, a healthy majority cannot starve a degraded target
 * behind it, and a replay that lands between the revoke and the retire keeps the target alive.
 */
class AgentTargetSweepTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");

    private final UUID organization = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final List<AgentEnrolmentSweep.Candidate> old = new ArrayList<>();
    private final List<UUID> connected = new ArrayList<>();
    private final List<AgentEnrolmentSweep.Candidate> revoked = new ArrayList<>();
    private final List<AgentEnrolmentSweep.Candidate> retired = new ArrayList<>();
    private final List<UUID> correlations = new ArrayList<>();
    private final List<UUID> retireCorrelations = new ArrayList<>();
    private UUID connectsAfterRevoke;
    private int pagesAsked;
    private Duration askedOlderThan;
    private RuntimeException controlPlaneFailure;

    private final AgentEnrolmentSweep controlPlane = new AgentEnrolmentSweep() {
        @Override
        public List<Candidate> candidates(UUID organizationId, Duration olderThan, Candidate after, int limit) {
            calls.add("cp.candidates:" + organizationId.equals(organization) + ":" + limit);
            askedOlderThan = olderThan;
            pagesAsked++;
            if (controlPlaneFailure != null) {
                throw controlPlaneFailure;
            }
            int from = after == null ? 0 : old.indexOf(after) + 1;
            return List.copyOf(old.subList(Math.min(from, old.size()), Math.min(from + limit, old.size())));
        }

        @Override
        public List<UUID> organizations(Duration olderThan) {
            throw new AssertionError("the per-organization sweep does not enumerate organizations");
        }

        @Override
        public void revoke(UUID organizationId, Candidate candidate, UUID correlationId) {
            calls.add("cp.revoke");
            revoked.add(candidate);
            correlations.add(correlationId);
            if (candidate.targetId().equals(connectsAfterRevoke)) {
                connected.add(candidate.targetId());        // the agent's replay lands between the revoke and the retire
            }
        }

        @Override
        public void retire(UUID organizationId, Candidate candidate, UUID correlationId) {
            calls.add("cp.retire");
            retired.add(candidate);
            retireCorrelations.add(correlationId);
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
            calls.add("dp.liveEnvelopes:" + targetIds.size());
            Map<UUID, TargetEnvelope> out = new HashMap<>();
            targetIds.stream().filter(connected::contains).forEach(id -> out.put(id, new TargetEnvelope("/srv/sm", "sm-")));
            return out;
        }
    };

    private final AgentTargetSweep sweep = new AgentTargetSweep(controlPlane, dataPlane,
            UUID::randomUUID, WINDOW);

    private AgentEnrolmentSweep.Candidate candidate() {
        AgentEnrolmentSweep.Candidate c = new AgentEnrolmentSweep.Candidate(UUID.randomUUID(), UUID.randomUUID(),
                T0.plusSeconds(old.size()));
        old.add(c);
        return c;
    }

    @Test
    void nothingOldIsOneBoundedControlPlaneReadAndNoDataPlaneRead() {
        sweep.sweep(organization);

        assertEquals(List.of("cp.candidates:true:" + AgentTargetSweep.MAX_BATCH), calls);
        assertEquals(WINDOW, askedOlderThan, "the control plane computes the cutoff on its own clock");
    }

    @Test
    void onlyTheDegradedOnesAreAbandonedAndAConnectedOneIsNeverTouched() {
        AgentEnrolmentSweep.Candidate degraded = candidate();
        AgentEnrolmentSweep.Candidate done = candidate();
        connected.add(done.targetId());

        sweep.sweep(organization);

        assertEquals(List.of(degraded), revoked);
        assertEquals(List.of(degraded), retired);
        assertEquals(List.of("cp.candidates:true:" + AgentTargetSweep.MAX_BATCH, "dp.liveEnvelopes:2", "cp.revoke",
                "dp.liveEnvelopes:1", "cp.retire"), calls, "revoke, look again, then retire");
        assertEquals(correlations, retireCorrelations, "one correlation id joins a target's revoke and its retire");
    }

    @Test
    void aHealthyMajorityCannotStarveADegradedTargetBehindIt() {
        for (int i = 0; i < AgentTargetSweep.MAX_BATCH + 1; i++) {
            connected.add(candidate().targetId());          // 21 healthy enrolments with the OLDEST tokens
        }
        AgentEnrolmentSweep.Candidate degraded = candidate();    // on the second page

        sweep.sweep(organization);

        assertEquals(List.of(degraded), retired, "found on page two");
        assertEquals(2, pagesAsked, "two pages were enough");
        assertTrue(revoked.size() == 1);
    }

    @Test
    void thePagesPerRequestAreCapped() {
        for (int i = 0; i < AgentTargetSweep.MAX_BATCH * (AgentTargetSweep.MAX_PAGES + 3); i++) {
            connected.add(candidate().targetId());
        }
        sweep.sweep(organization);
        assertEquals(AgentTargetSweep.MAX_PAGES, pagesAsked, "bounded work per organization per pass");
    }

    @Test
    void aReplayThatLandsAfterTheRevokeKeepsTheTargetAliveAndTheControlPlaneRowIsNotRetired() {
        AgentEnrolmentSweep.Candidate raced = candidate();
        connectsAfterRevoke = raced.targetId();

        sweep.sweep(organization);

        assertEquals(List.of(raced), revoked, "the registration is revoked: no further replay is honoured");
        assertTrue(retired.isEmpty(), "the data plane now holds the target, so the control plane row stays");
    }

    @Test
    void aFailureNeverFailsThePassThatRanTheSweep() {
        controlPlaneFailure = new IllegalStateException("database down");
        sweep.sweep(organization);       // no exception: the job pass continues
        assertTrue(revoked.isEmpty());
    }

    @Test
    void aNonPositiveWindowIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTargetSweep(controlPlane, dataPlane,
                UUID::randomUUID, Duration.ZERO));
    }

    @Test
    void aDegradedTargetSortedBehindMoreHealthyOnesThanOnePassReadsIsRetiredWithinFewPasses() {
        AgentTargetSweep small = new AgentTargetSweep(controlPlane, dataPlane, UUID::randomUUID, WINDOW, 2, 2);
        for (int i = 0; i < 9; i++) {
            connected.add(candidate().targetId());           // nine healthy enrolments with the OLDEST tokens
        }
        AgentEnrolmentSweep.Candidate degraded = candidate(); // sorted last: position 10; one pass reads 4
        int passes = 0;
        while (retired.isEmpty() && passes < 5) {
            small.sweep(organization);
            passes++;
        }
        assertEquals(List.of(degraded), retired, "reached despite the healthy rows in front of it");
        assertEquals(3, passes, "ceil(10 / (2 * 2)) passes, not never");
    }

    @Test
    void theCursorStartsOverWhenAPageComesBackShortAndIsForgottenForAnOrganizationNoLongerListed() {
        AgentTargetSweep small = new AgentTargetSweep(controlPlane, dataPlane, UUID::randomUUID, WINDOW, 2, 1);
        for (int i = 0; i < 3; i++) {
            connected.add(candidate().targetId());
        }
        small.sweep(organization);                 // reads rows 1-2, cursor after row 2
        small.sweep(organization);                 // reads row 3 (short page): cursor dropped
        small.sweep(organization);                 // starts again: rows 1-2
        small.retainCursorsFor(List.of());         // the organization is no longer listed: its cursor is forgotten
        small.sweep(organization);                 // starts again: rows 1-2
        assertEquals(List.of("dp.liveEnvelopes:2", "dp.liveEnvelopes:1", "dp.liveEnvelopes:2", "dp.liveEnvelopes:2"),
                calls.stream().filter(c -> c.startsWith("dp.")).toList());
    }
}
