package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetirementView.PlacementHealth;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.retirement.RetirementRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.instance_retirement} and the lifecycle moves of {@code sm_data.instance_state} that only undeclare makes
 * (plan 012 T3), over the runtime credential. In this package because JDBC is confined here; the service in
 * {@code dp.retirement} reaches it through {@link RetirementRows}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}. {@code state_version} is never written
 * (no column grant, V023): the stamp trigger sets it on every write. The record's placements are read from, and written as,
 * {@code jsonb} by the database itself ({@code jsonb_agg} on the way in, {@code jsonb_array_elements} on the way out), so no
 * JSON library is involved and no text that could carry a value is ever built here.
 */
public final class JdbcRetirementRepository implements RetirementRows {

    private static final String VIEW_COLUMNS = "r.retirement_id, r.instance_id, r.requested_by, r.requested_at,"
            + " r.correlation_id, r.workload_disposition, r.completed_at, r.outcome, r.state_version";

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplyScopeRepository scopes;

    public JdbcRetirementRepository(NamedParameterJdbcTemplate jdbc, ApplyScopeRepository scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    @Override
    public void lockScope(UUID tenantId, UUID instanceId) {
        TenantId t = new TenantId(tenantId);
        InstanceId i = new InstanceId(instanceId);
        scopes.ensureScopeRow(t, i);
        scopes.lockScope(t, i);
    }

    @Override
    public boolean requestInFlight(UUID tenantId, UUID instanceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.apply_request"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance"
                        + " AND state IN ('REQUESTED', 'RUNNING', 'APPLY_INDETERMINATE'))",
                key(tenantId, instanceId), Boolean.class));
    }

    @Override
    public boolean fleetReserves(UUID tenantId, UUID instanceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.fleet_member m"
                        + " JOIN sm_data.fleet_action f ON f.tenant_id = m.tenant_id AND f.fleet_id = m.fleet_id"
                        + " WHERE m.tenant_id = :tenant AND m.instance_id = :instance"
                        + " AND m.disposition IN ('pending', 'admitted') AND m.settled IS NULL AND f.state = 'RUNNING')",
                key(tenantId, instanceId), Boolean.class));
    }

    @Override
    public boolean scheduleReserves(UUID tenantId, UUID instanceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.apply_schedule"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND state = 'PENDING')",
                key(tenantId, instanceId), Boolean.class));
    }

    @Override
    public int markRetiring(UUID tenantId, UUID instanceId) {
        return move(tenantId, instanceId, "active", "retiring");
    }

    @Override
    public int markRetired(UUID tenantId, UUID instanceId) {
        return move(tenantId, instanceId, "retiring", "retired");
    }

    @Override
    public int markActive(UUID tenantId, UUID instanceId) {
        return move(tenantId, instanceId, "retiring", "active");
    }

    /** Conditional on the source lifecycle, so a statement that changes nothing is never issued (it would stamp). */
    private int move(UUID tenantId, UUID instanceId, String from, String to) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.instance_state SET lifecycle = :to"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND lifecycle = :from",
                key(tenantId, instanceId).addValue("from", from).addValue("to", to)));
    }

    @Override
    public void insertOpen(UUID tenantId, UUID retirementId, UUID instanceId, UUID requestedBy, Instant requestedAt,
            UUID correlationId, WorkloadDisposition workload) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id,"
                        + " placement_id, requested_by, requested_at, correlation_id, workload_disposition, last_health)"
                        + " SELECT :tenant, :retirement, :instance, NULL, :by, :at, :correlation, :workload,"
                        + " coalesce(jsonb_agg(jsonb_build_object('placementId', p.placement_id,"
                        + "                                       'lastHealth', h.state) ORDER BY p.placement_id),"
                        + "          '[]'::jsonb)"
                        + " FROM sm_data.placement p"
                        + " LEFT JOIN sm_data.placement_health h"
                        + "   ON h.tenant_id = p.tenant_id AND h.placement_id = p.placement_id"
                        + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND p.retired_at IS NULL",
                key(tenantId, instanceId).addValue("retirement", retirementId).addValue("by", requestedBy)
                        .addValue("at", Sql.at(requestedAt)).addValue("correlation", correlationId)
                        .addValue("workload", workload.wire())));
    }

    @Override
    public Optional<RetirementView> open(UUID tenantId, UUID instanceId) {
        return view("r.tenant_id = :tenant AND r.instance_id = :instance AND r.placement_id IS NULL"
                + " AND r.completed_at IS NULL", "", key(tenantId, instanceId));
    }

    @Override
    public Optional<RetirementView> latestClosed(UUID tenantId, UUID instanceId, RetirementOutcome outcome) {
        return view("r.tenant_id = :tenant AND r.instance_id = :instance AND r.placement_id IS NULL"
                + " AND r.outcome = :outcome", " ORDER BY r.completed_at DESC, r.state_version DESC",
                key(tenantId, instanceId).addValue("outcome", outcome.wire()));
    }

    private Optional<RetirementView> view(String where, String order, MapSqlParameterSource params) {
        List<Header> headers = jdbc.query("SELECT " + VIEW_COLUMNS + " FROM sm_data.instance_retirement r WHERE "
                + where + order + " LIMIT 1", params, JdbcRetirementRepository::header);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        Header h = headers.get(0);
        List<PlacementHealth> placements = jdbc.query("SELECT e.value ->> 'placementId' AS placement_id,"
                        + " e.value ->> 'lastHealth' AS last_health"
                        + " FROM sm_data.instance_retirement r,"
                        + " jsonb_array_elements(r.last_health) WITH ORDINALITY AS e(value, ord)"
                        + " WHERE r.tenant_id = :tenant AND r.retirement_id = :retirement ORDER BY e.ord",
                new MapSqlParameterSource().addValue("tenant", params.getValue("tenant"))
                        .addValue("retirement", h.retirementId()),
                (rs, i) -> new PlacementHealth(UUID.fromString(rs.getString("placement_id")),
                        Optional.ofNullable(rs.getString("last_health"))));
        return Optional.of(new RetirementView(h.retirementId(), h.instanceId(), h.requestedBy(), h.requestedAt(),
                h.correlationId(), WorkloadDisposition.fromWire(h.workload()), placements, h.completedAt(),
                h.outcome(), h.stateVersion()));
    }

    @Override
    public int close(UUID tenantId, UUID retirementId, RetirementOutcome outcome) {
        // now() is the transaction's own timestamp: the lifecycle trigger accepts retiring -> active only when the record
        // was closed rolled-back at EXACTLY this transaction's timestamp, which is how it knows "in the same transaction".
        return Sql.write(() -> jdbc.update("UPDATE sm_data.instance_retirement SET completed_at = now(),"
                        + " outcome = :outcome WHERE tenant_id = :tenant AND retirement_id = :retirement"
                        + " AND completed_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("retirement", retirementId)
                        .addValue("outcome", outcome.wire())));
    }

    @Override
    public List<RetiringInstance> retiring(UUID tenantId) {
        return jdbc.query("SELECT s.instance_id, r.retirement_id, r.requested_at FROM sm_data.instance_state s"
                        + " JOIN sm_data.instance_retirement r ON r.tenant_id = s.tenant_id"
                        + " AND r.instance_id = s.instance_id AND r.placement_id IS NULL AND r.completed_at IS NULL"
                        + " WHERE s.tenant_id = :tenant AND s.lifecycle = 'retiring'"
                        + " ORDER BY r.requested_at, s.instance_id",
                new MapSqlParameterSource("tenant", tenantId),
                (rs, i) -> new RetiringInstance(rs.getObject("instance_id", UUID.class),
                        rs.getObject("retirement_id", UUID.class), Sql.instant(rs, "requested_at")));
    }

    private static MapSqlParameterSource key(UUID tenantId, UUID instanceId) {
        return new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId);
    }

    /** The scalar columns of one record, before its placements are read. */
    private record Header(UUID retirementId, UUID instanceId, UUID requestedBy, Instant requestedAt, UUID correlationId,
            String workload, Optional<Instant> completedAt, Optional<RetirementOutcome> outcome, long stateVersion) {
    }

    private static Header header(ResultSet rs, int i) throws SQLException {
        String outcome = rs.getString("outcome");
        return new Header(rs.getObject("retirement_id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("requested_by", UUID.class), Sql.instant(rs, "requested_at"),
                rs.getObject("correlation_id", UUID.class), rs.getString("workload_disposition"),
                outcome == null ? Optional.empty() : Optional.of(Sql.instant(rs, "completed_at")),
                outcome == null ? Optional.empty() : Optional.of(RetirementOutcome.fromWire(outcome)),
                rs.getLong("state_version"));
    }
}
