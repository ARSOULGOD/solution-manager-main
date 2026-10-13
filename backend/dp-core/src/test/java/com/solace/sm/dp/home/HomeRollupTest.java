package com.solace.sm.dp.home;

import static com.solace.sm.dp.api.health.HealthState.BOOTSTRAP;
import static com.solace.sm.dp.api.health.HealthState.GREEN;
import static com.solace.sm.dp.api.health.HealthState.INACTIVE_REPLICA;
import static com.solace.sm.dp.api.health.HealthState.MISSING;
import static com.solace.sm.dp.api.health.HealthState.NOT_APPLICABLE;
import static com.solace.sm.dp.api.health.HealthState.NOT_EXPOSED;
import static com.solace.sm.dp.api.health.HealthState.RED;
import static com.solace.sm.dp.api.health.HealthState.RESTART_EXPECTED;
import static com.solace.sm.dp.api.health.HealthState.UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.health.HealthState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The per-instance health rollup of plan 010 T10.5 ({@code OR-61}, Q-C1-rollup): {@code project.ts}'s
 * {@code healthRollupOf} taken literally at placement scope, with the four NOTFOUND sub-states inserted. One case per
 * rule, in the ruling's own order.
 */
class HomeRollupTest {

    private static Optional<HealthState> of(HealthState... states) {
        return HomeRollup.of(List.of(states));
    }

    @Test
    void nothingPolledYetIsNoStateAtAll() {
        assertEquals(Optional.empty(), HomeRollup.of(List.of()), "never a guess: the surface declares it instead");
    }

    @Test
    void aPlacementWithNoDeclaredProbeRollsUpNotApplicable() {
        assertEquals(Optional.of(NOT_APPLICABLE), of(NOT_APPLICABLE));
        assertEquals(Optional.of(NOT_APPLICABLE), of(NOT_APPLICABLE, NOT_APPLICABLE));
    }

    @Test
    void aNoProbePlacementIsNotAnObservationNextToAProbedOne() {
        assertEquals(Optional.of(GREEN), of(NOT_APPLICABLE, GREEN), "OR-101: a package property, never RED");
        assertEquals(Optional.of(RED), of(NOT_APPLICABLE, RED));
    }

    @Test
    void anyRedWins() {
        assertEquals(Optional.of(RED), of(GREEN, RED, UNREACHABLE, MISSING, NOT_EXPOSED, RESTART_EXPECTED, BOOTSTRAP));
    }

    @Test
    void thenMissing() {
        assertEquals(Optional.of(MISSING), of(GREEN, UNREACHABLE, MISSING, NOT_EXPOSED, RESTART_EXPECTED, BOOTSTRAP));
    }

    @Test
    void thenUnreachable() {
        assertEquals(Optional.of(UNREACHABLE), of(GREEN, UNREACHABLE, NOT_EXPOSED, RESTART_EXPECTED, BOOTSTRAP));
    }

    @Test
    void thenNotExposed() {
        assertEquals(Optional.of(NOT_EXPOSED), of(GREEN, NOT_EXPOSED, RESTART_EXPECTED, BOOTSTRAP));
    }

    @Test
    void thenRestartExpected() {
        assertEquals(Optional.of(RESTART_EXPECTED), of(GREEN, RESTART_EXPECTED, BOOTSTRAP));
    }

    @Test
    void thenBootstrap() {
        assertEquals(Optional.of(BOOTSTRAP), of(GREEN, BOOTSTRAP));
        assertEquals(Optional.of(BOOTSTRAP), of(INACTIVE_REPLICA, BOOTSTRAP));
    }

    @Test
    void everyPlacementInactiveIsInactiveReplica() {
        assertEquals(Optional.of(INACTIVE_REPLICA), of(INACTIVE_REPLICA));
        assertEquals(Optional.of(INACTIVE_REPLICA), of(INACTIVE_REPLICA, INACTIVE_REPLICA));
    }

    @Test
    void aHealthyActiveStandbyPairCountsGreen() {
        assertEquals(Optional.of(GREEN), of(GREEN, INACTIVE_REPLICA), "exactly as healthRollupOf rolls it up");
    }

    @Test
    void allGreenIsGreen() {
        assertEquals(Optional.of(GREEN), of(GREEN));
        assertEquals(Optional.of(GREEN), of(GREEN, GREEN));
    }
}
