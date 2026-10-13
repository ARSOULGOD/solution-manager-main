package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.home.ApplyAttentionRows;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The apply side of the home attention list over the runtime credential (plan 010 T10.5). Read-only by construction:
 * one {@code SELECT}, and nothing else. {@code I5}: the statement binds {@code tenant_id} everywhere it names a table,
 * and takes it as a parameter - there is no tenant enumeration here.
 *
 * <p>An {@code APPLY_INDETERMINATE} request is listed unconditionally: it still blocks every later apply on its scope
 * until an operator resolves it, and admission does not refuse a {@code stop} on that scope, so a later request of
 * another operation must never hide it (S811-4). {@code CONFIG_DIVERGED} is listed only while it is the latest
 * <i>apply</i> of its (instance, placement): a stop is not a newer word about the configuration. The window therefore
 * runs over {@code apply} requests only, and only over pairs that have at least one attention-worthy one, so the cost
 * follows the number of such pairs and not the length of the tenant's journal. The nil UUID is the explicit
 * instance-wide sentinel in {@code apply_request.placement_id}; it is mapped to "no placement".
 */
public final class JdbcApplyAttentionRepository implements ApplyAttentionRows {

    private static final UUID INSTANCE_WIDE = new UUID(0L, 0L);

    private static final String SQL = "SELECT r.instance_id, r.placement_id, r.state, r.state_version FROM ("
            + "   SELECT a.instance_id, a.placement_id, a.state, a.state_version,"
            + "          row_number() OVER (PARTITION BY a.instance_id, a.placement_id"
            + "                             ORDER BY a.requested_at DESC, a.apply_id DESC) AS rn"
            + "   FROM sm_data.apply_request a"
            + "   WHERE a.tenant_id = :t AND a.operation = 'apply' AND (a.instance_id, a.placement_id) IN ("
            + "       SELECT b.instance_id, b.placement_id FROM sm_data.apply_request b"
            + "       WHERE b.tenant_id = :t AND b.operation = 'apply'"
            + "         AND b.state IN ('APPLY_INDETERMINATE', 'CONFIG_DIVERGED'))"
            + " ) r"
            + " JOIN sm_data.instance_state i ON i.tenant_id = :t AND i.instance_id = r.instance_id"
            + "   AND i.lifecycle = 'active'"
            + " WHERE r.state = 'APPLY_INDETERMINATE' OR (r.rn = 1 AND r.state = 'CONFIG_DIVERGED')"
            + " ORDER BY r.state_version, r.instance_id, r.placement_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApplyAttentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> needingAttention(UUID tenantId) {
        return jdbc.query(SQL, new MapSqlParameterSource().addValue("t", tenantId), (rs, n) -> {
            UUID placement = rs.getObject("placement_id", UUID.class);
            return new Row(rs.getObject("instance_id", UUID.class),
                    INSTANCE_WIDE.equals(placement) ? Optional.empty() : Optional.of(placement),
                    rs.getString("state"), rs.getLong("state_version"));
        });
    }
}
