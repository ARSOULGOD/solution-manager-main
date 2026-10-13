package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T7.2 - {@code sm_data} V012 section 2: {@code placement_health} and its change-only stamp (OR-39, OR-35).
 * As {@code sm_dp_runtime}. The refusals are asserted by SQLSTATE and by wording.
 */
@Testcontainers(disabledWithoutDocker = true)
class PlacementHealthIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private static HealthFixtures.Live fixture() {
        return HealthFixtures.livePlacement(runtime, "via-ssh", "actuator-health", "/actuator/health", 8081);
    }

    private static MapSqlParameterSource p(HealthFixtures.Live f) {
        return new MapSqlParameterSource().addValue("t", f.tenant()).addValue("p", f.placement())
                .addValue("i", f.instance()).addValue("now", OffsetDateTime.now());
    }

    private static void insert(HealthFixtures.Live f, String state) {
        runtime.update("INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state, checked_at,"
                + " changed_at) VALUES (:t, :p, :i, '" + state + "', :now, :now)", p(f));
    }

    /** The runtime role has USAGE but not SELECT on the sequence, so read it as the superuser. */
    private static long lastValue() {
        return superuser.queryForObject("SELECT last_value FROM sm_data.state_version_seq",
                new MapSqlParameterSource(), Long.class);
    }

    private static long stamp(HealthFixtures.Live f) {
        return runtime.queryForObject("SELECT state_version FROM sm_data.placement_health"
                + " WHERE tenant_id = :t AND placement_id = :p", p(f), Long.class);
    }

    private static void probeAs(HealthFixtures.Live f, String state, OffsetDateTime at) {
        runtime.update("UPDATE sm_data.placement_health SET state = '" + state + "', checked_at = :at, changed_at = :at"
                        + " WHERE tenant_id = :t AND placement_id = :p",
                p(f).addValue("at", at));
    }

    @Test
    void anInsertTakesTheNextValueOfTheOneSequence() {
        HealthFixtures.Live f = fixture();
        insert(f, "GREEN");
        long first = stamp(f);
        assertTrue(lastValue() >= first, "the stamp is a value of the one sequence");
        HealthFixtures.Live g = fixture();
        insert(g, "RED");
        assertTrue(stamp(g) > first, "one sequence for every row of every table");
    }

    @Test
    void aProbeThatConfirmsTheSameStateUpdatesCheckedAtAndStampsNothing() {
        HealthFixtures.Live f = fixture();
        insert(f, "GREEN");
        long before = stamp(f);
        OffsetDateTime changedAt = runtime.queryForObject("SELECT changed_at FROM sm_data.placement_health"
                + " WHERE tenant_id = :t AND placement_id = :p", p(f), OffsetDateTime.class);
        long sequenceBefore = lastValue();

        probeAs(f, "GREEN", OffsetDateTime.now().plusMinutes(5));

        assertEquals(before, stamp(f), "OR-39: only changes are persisted - the stamp does not move");
        assertEquals(changedAt.toInstant(), runtime.queryForObject("SELECT changed_at FROM sm_data.placement_health"
                + " WHERE tenant_id = :t AND placement_id = :p", p(f), OffsetDateTime.class).toInstant(),
                "an unchanged state cannot move changed_at, whoever writes it");
        assertTrue(runtime.queryForObject("SELECT checked_at > changed_at FROM sm_data.placement_health"
                + " WHERE tenant_id = :t AND placement_id = :p", p(f), Boolean.class), "checked_at moved");
        assertEquals(sequenceBefore, lastValue(),
                "no sequence value was drawn (a burned value is a gap, and gaps are legal - but not free)");
    }

    @Test
    void aChangedStateStampsAndMovesChangedAt() {
        HealthFixtures.Live f = fixture();
        insert(f, "GREEN");
        long before = stamp(f);

        probeAs(f, "RED", OffsetDateTime.now().plusMinutes(1));

        assertTrue(stamp(f) > before);
        assertEquals("RED", runtime.queryForObject("SELECT state FROM sm_data.placement_health"
                + " WHERE tenant_id = :t AND placement_id = :p", p(f), String.class));
    }

    @Test
    void theStateIsTheClosedNotifyVocabulary() {
        HealthFixtures.Live f = fixture();
        for (String state : new String[] {"GREEN", "RED", "RESTART_EXPECTED", "INACTIVE_REPLICA", "NOT_APPLICABLE",
                "unreachable", "not_exposed", "missing", "bootstrap"}) {
            HealthFixtures.Live each = fixture();
            insert(each, state);
        }
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_health_state",
                () -> insert(f, "DOWN"));
    }

    @Test
    void aRowMustNameThePlacementsOwnInstance() {
        HealthFixtures.Live f = fixture();
        HealthFixtures.Live other = fixture();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, null, () -> runtime.update(
                "INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state, checked_at,"
                        + " changed_at) VALUES (:t, :p, :other, 'GREEN', :now, :now)",
                p(f).addValue("other", other.instance()).addValue("t", f.tenant())));
    }

    @Test
    void theRuntimeRoleCannotSetTheStampOrRewriteIdentityOrDelete() {
        HealthFixtures.Live f = fixture();
        insert(f, "GREEN");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.placement_health SET state_version = 1 WHERE tenant_id = :t", p(f)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.placement_health SET instance_id = :i WHERE tenant_id = :t", p(f)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.placement_health WHERE tenant_id = :t", p(f)));
    }

    @Test
    void anInsertTakesTheOwningInstanceRowLockLikeEveryOtherStamp() {
        // a stamp taken without the instance_state lock could commit out of order with the instance's other stamps.
        String function = superuser.queryForObject("SELECT pg_get_functiondef(p.oid) FROM pg_proc p"
                + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'sm_data'"
                + " AND p.proname = 'stamp_placement_health_state_version'", new MapSqlParameterSource(), String.class);
        assertTrue(function.contains("FOR UPDATE"), function);
        assertTrue(function.contains("nextval('sm_data.state_version_seq')"), function);
    }
}
