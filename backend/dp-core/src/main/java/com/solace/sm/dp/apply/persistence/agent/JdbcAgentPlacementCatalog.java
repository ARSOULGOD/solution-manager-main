package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * What the agent path asks the registry about a placement's target (plan 015 T3.1): which transport reaches it
 * ({@link PlacementTransports}) and, for an agent target, which agent owns the placement ({@link AgentPlacementCatalog}). Read
 * from {@code sm_data} on every call and bound by {@code (tenant, instance, placement)} (I5): the agent is the one the placement's
 * TARGET names, so an id an agent or a caller supplied can never select it. It lives in the persistence package because the
 * architecture confines {@code org.springframework.jdbc} there.
 *
 * <p>The transport answer is the stored wire value and is NOT interpreted here: the router refuses a value it does not know.
 */
public final class JdbcAgentPlacementCatalog implements AgentPlacementCatalog, PlacementTransports {

    private static final ErrorClass INVALID = new ErrorClass("placement.invalid");

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentPlacementCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<String> transportOf(PlacementRef placement) {
        return jdbc.query("SELECT tc.transport FROM sm_data.placement p JOIN sm_data.target_connection tc"
                + " ON tc.tenant_id = p.tenant_id AND tc.target_id = p.target_id"
                + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND p.placement_id = :placement",
                key(placement), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    @Override
    public boolean isAgentTarget(UUID tenantId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.target_connection"
                + " WHERE tenant_id = :tenant AND target_id = :target AND transport = 'agent')",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), Boolean.class));
    }

    @Override
    public Optional<AgentPlacement> resolve(PlacementRef placement) {
        return jdbc.query("SELECT tc.agent_id, p.container_name, p.remote_config_directory, p.container_config_path,"
                + " p.file_name, p.mechanism, p.health_kind FROM sm_data.placement p JOIN sm_data.target_connection tc"
                + " ON tc.tenant_id = p.tenant_id AND tc.target_id = p.target_id"
                + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND p.placement_id = :placement"
                + " AND p.retired_at IS NULL AND tc.transport = 'agent' AND tc.agent_id IS NOT NULL AND tc.retired_at IS NULL",
                key(placement), (rs, n) -> {
                    try {
                        // the placement's own grammar is applied to what is read: a row that breaks it is refused, not trusted
                        return new AgentPlacement(rs.getObject(1, UUID.class),
                                new AgentPlacementRef(placement.tenant().value(), placement.instance().value(),
                                        placement.placement().value()),
                                rs.getString(2),
                                new ConfigLocation(rs.getString(4), new WriterAccess.RemoteSftp(rs.getString(3)), rs.getString(5),
                                        ConfigLocation.Mechanism.valueOf(rs.getString(6))),
                                rs.getString(7));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        throw new RuntimeAdapterException(INVALID);      // the cause is dropped
                    }
                }).stream().findFirst();
    }

    /**
     * The keyed hash of the newest write at or below the generation the agent persisted, but ONLY when that write is known to have
     * landed: an ACKED APPLIED one, or a SUPERSEDED one whose late acknowledgement said APPLIED. When the newest write ended
     * {@code APPLY_INDETERMINATE} (acked, or a late one) the file may be that write's or the previous one's, so the answer is
     * unknown rather than an older hash named as definite (W3 integration re-review, minor 3).
     */
    @Override
    public Optional<String> appliedConfigHash(AgentPlacement placement, long atOrBelowGeneration) {
        return jdbc.query("SELECT config_hash, (state = 'ACKED' AND outcome = 'APPLIED') OR detail_code = 'late.applied' AS landed"
                + " FROM sm_data.agent_command WHERE tenant_id = :tenant AND agent_id = :agent"
                + " AND placement_id = :placement AND operation IN ('APPLY', 'REMATERIALIZE') AND config_hash IS NOT NULL"
                + " AND wire_generation <= :generation AND ((state = 'ACKED' AND outcome IN ('APPLIED', 'APPLY_INDETERMINATE'))"
                + " OR (state = 'SUPERSEDED' AND detail_code IN ('late.applied', 'late.apply_indeterminate')))"
                + " ORDER BY wire_generation DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenant", placement.ref().tenantId()).addValue("agent", placement.agentId())
                        .addValue("placement", placement.ref().placementId()).addValue("generation", atOrBelowGeneration),
                (rs, n) -> rs.getBoolean("landed") ? rs.getString("config_hash") : null).stream()
                .filter(Objects::nonNull).findFirst();
    }

    @Override
    public Optional<PreBaseline> ackedWriteBaseline(AgentPlacement placement, UUID intentId) {
        return jdbc.query("SELECT write_baseline ->> 'startedAt' AS started, write_baseline ->> 'processRef' AS ref"
                + " FROM sm_data.apply_attempt WHERE tenant_id = :tenant AND intent_id = :intent AND write_baseline IS NOT NULL"
                + " ORDER BY attempt_no DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenant", placement.ref().tenantId()).addValue("intent", intentId),
                (rs, n) -> new PreBaseline(Instant.parse(rs.getString("started")), rs.getString("ref"))).stream()
                .findFirst();
    }

    @Override
    public Optional<Instant> observedStart(AgentPlacement placement) {
        return jdbc.query("SELECT started_at FROM sm_data.placement_start_observation WHERE tenant_id = :tenant"
                + " AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", placement.ref().tenantId())
                        .addValue("placement", placement.ref().placementId()),
                (rs, n) -> AgentSql.instant(rs, "started_at")).stream().findFirst();
    }

    private static MapSqlParameterSource key(PlacementRef placement) {
        return new MapSqlParameterSource().addValue("tenant", placement.tenant().value())
                .addValue("instance", placement.instance().value()).addValue("placement", placement.placement().value());
    }

    @Override
    public String toString() {
        return "JdbcAgentPlacementCatalog";
    }
}
