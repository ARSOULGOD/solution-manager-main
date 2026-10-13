package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.dp.api.apply.DueSchedule;
import com.solace.sm.dp.api.apply.ScheduleCommand.Operation;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 012 T7 - the gateway's {@code ScheduleFirer} (shape (c), {@code OR-122} (2)): one pass enumerates organizations with
 * a due schedule through the one port method that does so, asks the data plane for each organization's due schedules,
 * re-requires the SUBMITTER's verbs through {@code cp-api} (which the data plane may not call, {@code I7}) and only then
 * has the data plane admit. A submitter who lost a verb or was deactivated gets the schedule refused with the closed
 * reason (W4); an expired schedule is missed without asking anyone; an infrastructure failure never reads as a
 * revocation and never stops the pass.
 */
class ScheduleFirerTest {

    private static final Instant NOW = Instant.parse("2030-01-02T02:00:30Z");
    private static final Instant OPENS = Instant.parse("2030-01-02T02:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-01-02T04:00:00Z");

    private Calls calls;
    private FakeSchedulePort port;
    private FakeAuthorizationService authorization;
    private Instant clockNow;
    private ScheduleFirer firer;

    @BeforeEach
    void rig() {
        calls = new Calls();
        port = new FakeSchedulePort(calls);
        authorization = new FakeAuthorizationService();
        clockNow = NOW;
        firer = new ScheduleFirer(port, authorization, new Clock() {
            @Override public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override public Instant instant() {
                return clockNow;
            }
        }, true, Duration.ofSeconds(15));
    }

    private static DueSchedule due(UUID instance, UUID submitter, Operation operation, String strategy, Instant closes) {
        return new DueSchedule(UUID.randomUUID(), instance, submitter, operation, strategy, OPENS, closes);
    }

    private DueSchedule queue(UUID tenant, DueSchedule... schedules) {
        port.dueTenants = new java.util.ArrayList<>(port.dueTenants);
        if (!port.dueTenants.contains(tenant)) {
            ((java.util.ArrayList<UUID>) port.dueTenants).add(tenant);
        }
        port.due.put(tenant, List.of(schedules));
        return schedules[0];
    }

    // ---- one pass ---------------------------------------------------------------------------------------------------

    @Test
    void aDueScheduleIsAdmittedOnlyAfterItsSubmittersVerbsAreRequiredAgainAtTheInstance() {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID submitter = UUID.randomUUID();
        DueSchedule s = queue(tenant, due(instance, submitter, Operation.APPLY, "manual", CLOSES));

        firer.fireDue();

        assertEquals(List.of("dp.tenantsWithDueSchedules", "dp.due", "dp.admit"), calls.log);
        assertEquals(List.of("admit:" + s.scheduleId()), port.fired);
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions);
        assertEquals(List.of(new PrincipalRef(submitter)), authorization.principals, "the SUBMITTER's, not a session's");
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(instance, authorization.resources.get(0).id());
        assertEquals(tenant, authorization.resources.get(0).tenant().value(), "the organization the data plane named, never a guess");
    }

    @Test
    void theVerbsAskedAtFireAreTheOnesTheSubmitAskedInTheSameOrder() {
        record Case(Operation operation, String strategy, List<Permission> verbs) {
        }
        for (Case c : List.of(
                new Case(Operation.APPLY, "manual", List.of(Permission.CONFIG_APPLY)),
                new Case(Operation.ROLLBACK, "manual", List.of(Permission.CONFIG_ROLLBACK)),
                new Case(Operation.APPLY, "rolling-health-gated", List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART)),
                new Case(Operation.ROLLBACK, "rolling-health-gated",
                        List.of(Permission.CONFIG_ROLLBACK, Permission.INSTANCE_RESTART)),
                new Case(Operation.APPLY, "rolling-no-validation", List.of(Permission.CONFIG_APPLY,
                        Permission.INSTANCE_RESTART, Permission.INSTANCE_RESTART_OVERRIDE_SAFETY)),
                new Case(Operation.ROLLBACK, "restart-all", List.of(Permission.CONFIG_ROLLBACK,
                        Permission.INSTANCE_RESTART, Permission.INSTANCE_RESTART_OVERRIDE_SAFETY)))) {
            authorization.permissions.clear();
            port.dueTenants = List.of();
            port.due.clear();
            queue(UUID.randomUUID(), due(UUID.randomUUID(), UUID.randomUUID(), c.operation(), c.strategy(), CLOSES));
            firer.fireDue();
            assertEquals(c.verbs(), authorization.permissions, c.operation() + " " + c.strategy());
        }
    }

    @Test
    void aSubmitterWhoLostAVerbIsRefusedWithTheClosedReasonAndNothingIsAdmittedW4() {
        UUID tenant = UUID.randomUUID();
        DueSchedule s = queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "restart-all", CLOSES));
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY);

        firer.fireDue();

        assertEquals(List.of("refuse:" + s.scheduleId() + ":schedule.authority.revoked"), port.fired);
        assertFalse(calls.has("dp.admit"));
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART,
                Permission.INSTANCE_RESTART_OVERRIDE_SAFETY), authorization.permissions,
                "every verb up to the one that failed was asked, and no verb after it");
    }

    @Test
    void aDeactivatedSubmitterOrAnInstanceNoLongerVisibleReadsAsNotFoundAndIsTheSameRefusal() {
        UUID tenant = UUID.randomUUID();
        DueSchedule s = queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        authorization.outcome = Outcome.NOT_FOUND;

        firer.fireDue();

        assertEquals(List.of("refuse:" + s.scheduleId() + ":schedule.authority.revoked"), port.fired);
        assertFalse(port.fired.stream().anyMatch(f -> f.contains("schedule.target.gone")),
                "Fable F-9: the authorization API cannot tell a gone instance from a lost scope or a deactivated"
                        + " submitter, so a not-found is never labelled target.gone here (that reason is the data plane's, at admission)");
        assertFalse(calls.has("dp.admit"));
    }

    @Test
    void anExpiredScheduleIsMissedWithoutAskingForAnyVerb() {
        UUID tenant = UUID.randomUUID();
        DueSchedule s = queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual",
                NOW.minusSeconds(1)));

        firer.fireDue();

        assertEquals(List.of("miss:" + s.scheduleId()), port.fired, "W3: SM was down through the window");
        assertTrue(authorization.permissions.isEmpty(), "nobody is asked whether the submitter could have run it");
    }

    @Test
    void theWindowIsHalfOpenAScheduleAtNotAfterExactlyIsMissed() {
        UUID tenant = UUID.randomUUID();
        DueSchedule s = queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", NOW));
        firer.fireDue();
        assertEquals(List.of("miss:" + s.scheduleId()), port.fired);
    }

    @Test
    void theEnumerationCallsCarryTheFirersPassReadingAndTheAdmissionCarriesNoReadingAtAll() {
        UUID tenant = UUID.randomUUID();
        queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        java.util.List<Instant> seen = new java.util.ArrayList<>();
        FakeSchedulePort recording = new FakeSchedulePort(calls) {
            @Override public List<UUID> tenantsWithDueSchedules(Instant now) {
                seen.add(now);
                return port.dueTenants;
            }

            @Override public List<DueSchedule> due(UUID organizationId, Instant now) {
                seen.add(now);
                return port.due.get(organizationId);
            }

            @Override public ScheduleFireResult admit(UUID organizationId, UUID scheduleId) {
                // W3B-1: admit has no clock parameter - the data plane reads its own after it holds the row's lock
                return ScheduleFireResult.admitted(UUID.randomUUID());
            }
        };
        ScheduleFirer f = new ScheduleFirer(recording, authorization, Clock.fixed(NOW, ZoneOffset.UTC), true, Duration.ofSeconds(1));

        f.fireDue();

        assertEquals(List.of(NOW, NOW), seen, "one reading per pass selects what is due; it does not decide any window");
    }

    @Test
    void aScheduleWhoseWindowClosesWhileThePassWorksThroughAnEarlierOneIsMissedNotAdmitted() {
        UUID tenant = UUID.randomUUID();
        DueSchedule first = due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES);
        DueSchedule second = due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", NOW.plusSeconds(5));
        queue(tenant, first, second);
        // admitting the first takes ten seconds of the firer's clock: the second's window has closed by the time its turn comes
        port.admitResult = id -> {
            clockNow = clockNow.plusSeconds(10);
            return ScheduleFireResult.admitted(UUID.randomUUID());
        };

        firer.fireDue();

        assertEquals(List.of("admit:" + first.scheduleId(), "miss:" + second.scheduleId()), port.fired,
                "never admitted outside its window, whatever the pass started with");
    }

    // ---- failures never stop the pass -------------------------------------------------------------------------------

    @Test
    void anInfrastructureFailureOfTheAuthorizationIsNeitherARevocationNorAnAdmissionAndTheNextScheduleStillRuns() {
        UUID tenant = UUID.randomUUID();
        UUID flaky = UUID.randomUUID();
        DueSchedule first = due(flaky, UUID.randomUUID(), Operation.APPLY, "manual", CLOSES);
        DueSchedule second = due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES);
        queue(tenant, first, second);
        com.solace.sm.cp.api.authz.AuthorizationService throwing = (principal, permission, resource) -> {
            if (resource.id().equals(flaky)) {
                throw new IllegalStateException("the control plane is not answering");
            }
            authorization.require(principal, permission, resource);
        };
        ScheduleFirer f = new ScheduleFirer(port, throwing, Clock.fixed(NOW, ZoneOffset.UTC), true, Duration.ofSeconds(1));

        f.fireDue();

        assertEquals(List.of("admit:" + second.scheduleId()), port.fired,
                "the unknown authority stays PENDING for the next tick: not refused, not admitted");
    }

    @Test
    void aFailingAdmitNeverStopsTheOtherSchedulesNorTheOtherOrganizations() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        DueSchedule a = queue(t1, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES),
                due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        DueSchedule b = queue(t2, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        port.admitResult = id -> {
            if (id.equals(a.scheduleId())) {
                throw new IllegalStateException("no executor");
            }
            return ScheduleFireResult.admitted(UUID.randomUUID());
        };

        firer.fireDue();

        assertEquals(3, port.fired.size(), "all three were attempted: " + port.fired);
        assertTrue(port.fired.contains("admit:" + b.scheduleId()));
    }

    @Test
    void anOrganizationWhoseDueReadFailsIsSkippedAndTheNextOneRuns() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        queue(good, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        FakeSchedulePort failing = new FakeSchedulePort(calls) {
            @Override public List<UUID> tenantsWithDueSchedules(Instant now) {
                return List.of(bad, good);
            }

            @Override public List<DueSchedule> due(UUID organizationId, Instant now) {
                if (organizationId.equals(bad)) {
                    throw new IllegalStateException("read failed");
                }
                return port.due.get(organizationId);
            }
        };
        ScheduleFirer f = new ScheduleFirer(failing, authorization, Clock.fixed(NOW, ZoneOffset.UTC), true, Duration.ofSeconds(1));

        f.fireDue();

        assertEquals(1, failing.fired.size(), failing.fired.toString());
    }

    @Test
    void aFailingEnumerationEndsThePassQuietly() {
        port.enumerationFailure = new IllegalStateException("database down");
        firer.fireDue();
        assertEquals(List.of("dp.tenantsWithDueSchedules"), calls.log);
    }

    @Test
    void deferredAndNotPendingResultsAreNotErrors() {
        UUID tenant = UUID.randomUUID();
        queue(tenant, due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES),
                due(UUID.randomUUID(), UUID.randomUUID(), Operation.APPLY, "manual", CLOSES));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        port.admitResult = id -> n.getAndIncrement() == 0 ? ScheduleFireResult.deferred() : ScheduleFireResult.notPending();
        firer.fireDue();
        assertEquals(2, port.fired.size());
    }

    // ---- the loop ---------------------------------------------------------------------------------------------------

    @Test
    void startRunsPassesOnItsOwnThreadUntilStopped() throws Exception {
        CountDownLatch passes = new CountDownLatch(3);
        FakeSchedulePort counting = new FakeSchedulePort(calls) {
            @Override public List<UUID> tenantsWithDueSchedules(Instant now) {
                passes.countDown();
                return List.of();
            }
        };
        ScheduleFirer loop = new ScheduleFirer(counting, authorization, Clock.systemUTC(), true, Duration.ofMillis(20));

        loop.start();
        try {
            assertTrue(loop.isRunning());
            assertTrue(passes.await(10, TimeUnit.SECONDS), "the periodic pass ran");
        } finally {
            loop.stop();
        }
        assertFalse(loop.isRunning());
        long after = passes.getCount();
        Thread.sleep(100);
        assertEquals(after, passes.getCount(), "no pass after stop");
    }

    @Test
    void aDisabledFirerStartsNoThreadAndSaysSo() {
        FakeSchedulePort never = new FakeSchedulePort(calls) {
            @Override public List<UUID> tenantsWithDueSchedules(Instant now) {
                throw new AssertionError("a disabled firer must not enumerate");
            }
        };
        ScheduleFirer disabled = new ScheduleFirer(never, authorization, Clock.systemUTC(), false, Duration.ofMillis(10));
        disabled.start();
        assertFalse(disabled.isRunning());
        disabled.stop();
    }

    @Test
    void thePollIntervalMustBePositive() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            try {
                new ScheduleFirer(port, authorization, Clock.systemUTC(), true, bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("poll-interval"), expected.getMessage());
            }
        }
    }
}
