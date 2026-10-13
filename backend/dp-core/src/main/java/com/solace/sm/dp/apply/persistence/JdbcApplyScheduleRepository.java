package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.apply.ScheduleCommand.Operation;
import com.solace.sm.dp.api.apply.ScheduleReason;
import com.solace.sm.dp.schedule.ScheduleRecord;
import com.solace.sm.dp.schedule.ScheduleRecord.State;
import com.solace.sm.dp.schedule.ScheduleRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.apply_schedule} (plan 012 T7, {@code V025}), over the runtime credential. In this package because JDBC is
 * confined here; the service in {@code dp.schedule} reaches it through {@link ScheduleRows}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id} - except {@link #dueTenants}, the ONE
 * cross-organization read of this feature ({@code OR-122} (2)), which answers tenant ids and nothing else and is on the
 * {@code TenantEnumerationBoundaryTest} allow-list by this class's name (every other statement here is tenant-bound).
 * {@code state_version} is never written (no column grant): the stamp trigger sets it on every write. The state moves are
 * conditional on {@code state = 'PENDING'}, so a statement that changes nothing is never issued (it would stamp).
 */
public final class JdbcApplyScheduleRepository implements ScheduleRows {

    private static final String COLUMNS = "tenant_id, schedule_id, instance_id, placement_id, operation, generation,"
            + " rollback_apply_id, rollback_generation, strategy, outage_confirmed_by, not_before, not_after,"
            + " requested_by, requested_at, state, cancelled_by, apply_id, target_generation, admitted_generation,"
            + " reason, resolved_at, state_version";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApplyScheduleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public long insert(ScheduleRecord row) {
        Long stamped = Sql.writeReturning(() -> jdbc.queryForObject("INSERT INTO sm_data.apply_schedule (tenant_id,"
                        + " schedule_id, instance_id, placement_id, operation, generation, rollback_apply_id,"
                        + " rollback_generation, strategy, outage_confirmed_by, not_before, not_after, requested_by,"
                        + " requested_at) VALUES (:tenant, :schedule, :instance, :placement, :operation, :generation,"
                        + " :rollbackApply, :rollbackGeneration, :strategy, :confirmedBy, :notBefore, :notAfter,"
                        + " :requestedBy, :requestedAt) RETURNING state_version",
                new MapSqlParameterSource().addValue("tenant", row.tenantId()).addValue("schedule", row.scheduleId())
                        .addValue("instance", row.instanceId()).addValue("placement", row.placementId())
                        .addValue("operation", row.operation().wire()).addValue("generation", row.generation())
                        .addValue("rollbackApply", row.rollbackApplyId())
                        .addValue("rollbackGeneration", row.rollbackGeneration()).addValue("strategy", row.strategy())
                        .addValue("confirmedBy", row.outageConfirmedBy()).addValue("notBefore", Sql.at(row.notBefore()))
                        .addValue("notAfter", Sql.at(row.notAfter())).addValue("requestedBy", row.requestedBy())
                        .addValue("requestedAt", Sql.at(row.requestedAt())), Long.class));
        return Objects.requireNonNull(stamped, "the insert returned no state_version");
    }

    @Override
    public Optional<ScheduleRecord> lock(UUID tenantId, UUID scheduleId) {
        return one("SELECT " + COLUMNS + " FROM sm_data.apply_schedule WHERE tenant_id = :tenant"
                + " AND schedule_id = :schedule FOR UPDATE", key(tenantId, scheduleId));
    }

    @Override
    public Optional<ScheduleRecord> lockOfInstance(UUID tenantId, UUID instanceId, UUID scheduleId) {
        return one("SELECT " + COLUMNS + " FROM sm_data.apply_schedule WHERE tenant_id = :tenant"
                + " AND instance_id = :instance AND schedule_id = :schedule FOR UPDATE",
                key(tenantId, scheduleId).addValue("instance", instanceId));
    }

    @Override
    public Optional<ScheduleRecord> find(UUID tenantId, UUID instanceId, UUID scheduleId) {
        return one("SELECT " + COLUMNS + " FROM sm_data.apply_schedule WHERE tenant_id = :tenant"
                + " AND instance_id = :instance AND schedule_id = :schedule",
                key(tenantId, scheduleId).addValue("instance", instanceId));
    }

    @Override
    public List<ScheduleRecord> list(UUID tenantId, UUID instanceId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_schedule WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance ORDER BY requested_at DESC, schedule_id LIMIT :limit",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("limit", limit),
                JdbcApplyScheduleRepository::row);
    }

    @Override
    public List<UUID> dueTenants(Instant now) {
        // ⛔ the cross-organization read (OR-122 (2)): ids only, partial index apply_schedule_due
        return jdbc.query("SELECT DISTINCT tenant_id FROM sm_data.apply_schedule WHERE state = 'PENDING'"
                        + " AND not_before <= :now ORDER BY tenant_id",
                new MapSqlParameterSource("now", Sql.at(now)), (rs, i) -> rs.getObject("tenant_id", UUID.class));
    }

    @Override
    public List<ScheduleRecord> due(UUID tenantId, Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_schedule WHERE tenant_id = :tenant"
                        + " AND state = 'PENDING' AND not_before <= :now ORDER BY not_before, schedule_id LIMIT :limit",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("now", Sql.at(now))
                        .addValue("limit", limit),
                JdbcApplyScheduleRepository::row);
    }

    @Override
    public int admitted(UUID tenantId, UUID scheduleId, UUID applyId, Long targetGeneration, long admittedGeneration,
            Instant at) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.apply_schedule SET state = 'ADMITTED', apply_id = :apply,"
                        + " target_generation = :target, admitted_generation = :admitted, resolved_at = :at"
                        + " WHERE tenant_id = :tenant AND schedule_id = :schedule AND state = 'PENDING'",
                key(tenantId, scheduleId).addValue("apply", applyId).addValue("target", targetGeneration)
                        .addValue("admitted", admittedGeneration).addValue("at", Sql.at(at))));
    }

    @Override
    public int close(UUID tenantId, UUID scheduleId, State state, ScheduleReason reason, Instant at) {
        if (state != State.MISSED && state != State.REFUSED) {
            throw new IllegalArgumentException("close() ends a schedule MISSED or REFUSED, never " + state);
        }
        return Sql.write(() -> jdbc.update("UPDATE sm_data.apply_schedule SET state = :state, reason = :reason,"
                        + " resolved_at = :at WHERE tenant_id = :tenant AND schedule_id = :schedule AND state = 'PENDING'",
                key(tenantId, scheduleId).addValue("state", state.name()).addValue("reason", reason.wire())
                        .addValue("at", Sql.at(at))));
    }

    @Override
    public int cancel(UUID tenantId, UUID scheduleId, UUID cancelledBy, Instant at) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.apply_schedule SET state = 'CANCELLED',"
                        + " cancelled_by = :by, resolved_at = :at"
                        + " WHERE tenant_id = :tenant AND schedule_id = :schedule AND state = 'PENDING'",
                key(tenantId, scheduleId).addValue("by", cancelledBy).addValue("at", Sql.at(at))));
    }

    private Optional<ScheduleRecord> one(String sql, MapSqlParameterSource params) {
        List<ScheduleRecord> found = jdbc.query(sql, params, JdbcApplyScheduleRepository::row);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static MapSqlParameterSource key(UUID tenantId, UUID scheduleId) {
        return new MapSqlParameterSource().addValue("tenant", tenantId).addValue("schedule", scheduleId);
    }

    private static ScheduleRecord row(ResultSet rs, int i) throws SQLException {
        String reason = rs.getString("reason");
        return new ScheduleRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("schedule_id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("placement_id", UUID.class),
                operation(rs.getString("operation")), Sql.nullableLong(rs, "generation"),
                rs.getObject("rollback_apply_id", UUID.class), Sql.nullableLong(rs, "rollback_generation"),
                rs.getString("strategy"), rs.getObject("outage_confirmed_by", UUID.class),
                Sql.instant(rs, "not_before"), Sql.instant(rs, "not_after"), rs.getObject("requested_by", UUID.class),
                Sql.instant(rs, "requested_at"), State.valueOf(rs.getString("state")),
                rs.getObject("cancelled_by", UUID.class), rs.getObject("apply_id", UUID.class),
                Sql.nullableLong(rs, "target_generation"), Sql.nullableLong(rs, "admitted_generation"),
                reason == null ? null : ScheduleReason.fromWire(reason), Sql.instant(rs, "resolved_at"),
                rs.getLong("state_version"));
    }

    private static Operation operation(String wire) {
        for (Operation o : Operation.values()) {
            if (o.wire().equals(wire)) {
                return o;
            }
        }
        throw new IllegalStateException("not a schedule operation: " + wire);
    }
}
