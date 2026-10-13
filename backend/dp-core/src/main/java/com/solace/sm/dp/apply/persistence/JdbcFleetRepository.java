package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.fleet.FleetRecord;
import com.solace.sm.dp.fleet.FleetRows;
import com.solace.sm.dp.fleet.MemberRecord;
import com.solace.sm.dp.fleet.MemberRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.fleet_action} and {@code sm_data.fleet_member} (plan 010 T11.2; {@code V014}), as the data plane's
 * runtime role holds them: {@code INSERT} and a column-scoped {@code UPDATE}, and no {@code DELETE} (the fleet is part of
 * the audit trail).
 *
 * <p>{@code I5}: every statement is keyed by the tenant first and binds every value - nothing here reads across tenants.
 * The one cross-tenant read a fleet makes, the tenant list its scheduler iterates, is
 * {@link TenantEnumerationRepository#tenantsWithActiveInstances()} ({@code OR-49}); it is called, never re-spelt, and this
 * class holds no second copy of it.
 *
 * <p>Every update is CONDITIONAL on the row's current disposition and reports the rows it changed, so a late writer meets
 * zero rows rather than the forward-only trigger. A refusal the database makes is reshaped by {@link Sql} into a named
 * {@link PgConstraintViolation}; the one this class translates is the placement reservation, which is an expected answer
 * ({@code 409}), not a defect.
 *
 * <p>{@code I6}: the tables hold ids, generation numbers and classifier names only; nothing secret can be written here.
 */
public final class JdbcFleetRepository implements FleetRows {

    /** The partial unique index of section 5.6, by the name the refusal surfaces under. */
    static final String RESERVATION = "fleet_member_reservation";

    private static final String FLEET_COLUMNS = "tenant_id, fleet_id, catalog_solution_id, strategy, outage_confirmed_by,"
            + " batch_size, on_failure, state, current_batch, requested_by, requested_at, correlation_id, terminal_at,"
            + " state_version";

    private static final String MEMBER_COLUMNS = "tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
            + " generation, apply_id, disposition, refusal_code, admission_refusal, settled";

    private static final RowMapper<FleetRecord> FLEET = JdbcFleetRepository::fleet;
    private static final RowMapper<MemberRecord> MEMBER = JdbcFleetRepository::member;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFleetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertFleet(FleetRecord fleet) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy,"
                        + " outage_confirmed_by, batch_size, on_failure, state, current_batch, requested_by,"
                        + " requested_at, correlation_id) VALUES (:t, :f, :c, :strategy, :outage, :size, :onFailure,"
                        + " :state, :batch, :by, :at, :corr)",
                new MapSqlParameterSource().addValue("t", fleet.tenantId()).addValue("f", fleet.fleetId())
                        .addValue("c", fleet.catalogSolutionId()).addValue("strategy", fleet.strategy())
                        .addValue("outage", Sql.redacted(fleet.outageConfirmedBy()))
                        .addValue("size", fleet.batchSize()).addValue("onFailure", fleet.onFailure())
                        .addValue("state", fleet.state()).addValue("batch", fleet.currentBatch())
                        .addValue("by", fleet.requestedBy()).addValue("at", Sql.at(fleet.requestedAt()))
                        .addValue("corr", fleet.correlationId())));
    }

    @Override
    public void insertMember(MemberRecord member) {
        try {
            Sql.write(() -> jdbc.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch,"
                            + " instance_id, placement_id, generation, apply_id, disposition, refusal_code)"
                            + " VALUES (:t, :f, :o, :b, :i, :p, :g, :a, :d, :rc)",
                    new MapSqlParameterSource().addValue("t", member.tenantId()).addValue("f", member.fleetId())
                            .addValue("o", member.ordinal()).addValue("b", member.batch())
                            .addValue("i", member.instanceId()).addValue("p", member.placementId())
                            .addValue("g", member.generation()).addValue("a", member.applyId())
                            .addValue("d", member.disposition()).addValue("rc", member.refusalCode())));
        } catch (PgConstraintViolation e) {
            if (e.namesAnyOf(RESERVATION)) {
                throw new PlacementReservedException();
            }
            throw e;
        }
    }

    @Override
    public Optional<FleetRecord> find(UUID tenantId, UUID fleetId) {
        return jdbc.query("SELECT " + FLEET_COLUMNS + " FROM sm_data.fleet_action WHERE tenant_id = :t AND fleet_id = :f",
                key(tenantId, fleetId), FLEET).stream().findFirst();
    }

    @Override
    public Optional<FleetRecord> lock(UUID tenantId, UUID fleetId) {
        return jdbc.query("SELECT " + FLEET_COLUMNS + " FROM sm_data.fleet_action WHERE tenant_id = :t AND fleet_id = :f"
                + " FOR UPDATE", key(tenantId, fleetId), FLEET).stream().findFirst();
    }

    @Override
    public List<UUID> runningFleets(UUID tenantId) {
        return jdbc.queryForList("SELECT fleet_id FROM sm_data.fleet_action WHERE tenant_id = :t AND state = 'RUNNING'"
                + " ORDER BY requested_at, fleet_id", new MapSqlParameterSource("t", tenantId), UUID.class);
    }

    @Override
    public List<MemberRecord> members(UUID tenantId, UUID fleetId) {
        return jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM sm_data.fleet_member WHERE tenant_id = :t AND fleet_id = :f"
                + " ORDER BY ordinal", key(tenantId, fleetId), MEMBER);
    }

    @Override
    public Map<UUID, MemberRequest> requestsOf(UUID tenantId, Collection<UUID> applyIds) {
        Map<UUID, MemberRequest> found = new HashMap<>();
        if (applyIds.isEmpty()) {
            return found;
        }
        // settle_anchor: a given-up APPLY_INDETERMINATE request has no terminal_at (terminal_has_timestamp), but the
        // reconciler closed its apply_reconciliation row with gave_up_at in the give-up transaction (V011). The join is
        // tenant-bound on both sides, like every read here (I5); sm_dp_runtime holds SELECT on the table.
        jdbc.query("SELECT q.apply_id, q.state, q.resolution_outcome, q.reconcile_class, q.state_version, q.terminal_at,"
                        + " COALESCE(q.terminal_at, r.gave_up_at) AS settle_anchor"
                        + " FROM sm_data.apply_request q"
                        + " LEFT JOIN sm_data.apply_reconciliation r ON r.tenant_id = q.tenant_id AND r.apply_id = q.apply_id"
                        + " WHERE q.tenant_id = :t AND q.apply_id IN (:ids)",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("ids", applyIds),
                (RowMapper<Void>) (rs, n) -> {
                    found.put(rs.getObject("apply_id", UUID.class),
                            new MemberRequest(RequestState.valueOf(rs.getString("state")),
                                    rs.getString("resolution_outcome"), rs.getString("reconcile_class"),
                                    rs.getLong("state_version"), Sql.instant(rs, "terminal_at"),
                                    Sql.instant(rs, "settle_anchor")));
                    return null;
                });
        return found;
    }

    @Override
    public boolean requestExists(UUID tenantId, UUID applyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a)",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("a", applyId), Boolean.class));
    }

    @Override
    public int markAdmitted(UUID tenantId, UUID fleetId, int ordinal) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_member SET disposition = 'admitted'"
                + " WHERE tenant_id = :t AND fleet_id = :f AND ordinal = :o AND disposition = 'pending'",
                member(tenantId, fleetId, ordinal)));
    }

    @Override
    public int markNotAdmitted(UUID tenantId, UUID fleetId, int ordinal, String admissionRefusal) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_member SET disposition = 'not_admitted',"
                + " admission_refusal = :r, settled = 'failed'"
                + " WHERE tenant_id = :t AND fleet_id = :f AND ordinal = :o AND disposition = 'pending'",
                member(tenantId, fleetId, ordinal).addValue("r", admissionRefusal)));
    }

    @Override
    public int markSkipped(UUID tenantId, UUID fleetId, int ordinal) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_member SET disposition = 'skipped'"
                + " WHERE tenant_id = :t AND fleet_id = :f AND ordinal = :o AND disposition = 'pending'",
                member(tenantId, fleetId, ordinal)));
    }

    @Override
    public int settle(UUID tenantId, UUID fleetId, int ordinal, String settled) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_member SET settled = :s"
                + " WHERE tenant_id = :t AND fleet_id = :f AND ordinal = :o AND disposition = 'admitted'"
                + " AND settled IS NULL", member(tenantId, fleetId, ordinal).addValue("s", settled)));
    }

    @Override
    public int moveToBatch(UUID tenantId, UUID fleetId, int batch) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_action SET current_batch = :b"
                + " WHERE tenant_id = :t AND fleet_id = :f AND state = 'RUNNING' AND current_batch < :b",
                key(tenantId, fleetId).addValue("b", batch)));
    }

    @Override
    public int land(UUID tenantId, UUID fleetId, String state, Instant at) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.fleet_action SET state = :s, terminal_at = :at"
                + " WHERE tenant_id = :t AND fleet_id = :f AND state = 'RUNNING'",
                key(tenantId, fleetId).addValue("s", state).addValue("at", Sql.at(at))));
    }

    // ---- mapping -------------------------------------------------------------------------------------------

    private static MapSqlParameterSource key(UUID tenantId, UUID fleetId) {
        return new MapSqlParameterSource().addValue("t", tenantId).addValue("f", fleetId);
    }

    private static MapSqlParameterSource member(UUID tenantId, UUID fleetId, int ordinal) {
        return key(tenantId, fleetId).addValue("o", ordinal);
    }

    private static FleetRecord fleet(ResultSet rs, int n) throws SQLException {
        return new FleetRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("fleet_id", UUID.class),
                rs.getObject("catalog_solution_id", UUID.class), rs.getString("strategy"),
                rs.getString("outage_confirmed_by"), rs.getInt("batch_size"), rs.getString("on_failure"),
                rs.getString("state"), rs.getInt("current_batch"), rs.getString("requested_by"),
                Sql.instant(rs, "requested_at"), rs.getObject("correlation_id", UUID.class),
                Sql.instant(rs, "terminal_at"), rs.getLong("state_version"));
    }

    private static MemberRecord member(ResultSet rs, int n) throws SQLException {
        int batch = rs.getInt("batch");
        Integer nullableBatch = rs.wasNull() ? null : batch;
        return new MemberRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("fleet_id", UUID.class),
                rs.getInt("ordinal"), nullableBatch, rs.getObject("instance_id", UUID.class),
                rs.getObject("placement_id", UUID.class), rs.getLong("generation"),
                rs.getObject("apply_id", UUID.class), rs.getString("disposition"), rs.getString("refusal_code"),
                rs.getString("admission_refusal"), rs.getString("settled"));
    }
}
