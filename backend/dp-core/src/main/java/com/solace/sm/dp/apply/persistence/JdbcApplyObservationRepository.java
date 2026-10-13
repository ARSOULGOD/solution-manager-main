package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.service.ApplyObservationRows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The journal read behind {@code observeApply} (plan 010 T6.3). Read-only by construction - it issues {@code SELECT}
 * and nothing else - and adapter-free by construction: this class has no way to reach a host.
 *
 * <p>{@code I5}: every statement binds {@code tenant_id}, and {@code instance_id} too, so a request id of another
 * instance (or tenant) selects nothing. Ordered by {@code state_version}, the only ordering signal; with the
 * commit-order stamping of V007/V008 a client that has seen a stamp has seen every lower one.
 */
public final class JdbcApplyObservationRepository implements ApplyObservationRows {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApplyObservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ObservedRequest> observe(TenantId tenant, InstanceId instance, ApplyId applyId, Long sinceStateVersion,
            int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("i", instance.value()).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT " + ApplyRequestRepository.COLUMNS + ", reconcile_class"
                + " FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i");
        if (applyId != null) {
            sql.append(" AND apply_id = :a");
            params.addValue("a", applyId.value());
        }
        if (sinceStateVersion != null) {
            sql.append(" AND state_version > :since");
            params.addValue("since", sinceStateVersion);
        }
        sql.append(" ORDER BY state_version ASC LIMIT :limit");
        Map<UUID, String> reconcileClasses = new LinkedHashMap<>();
        List<ApplyRequestRow> rows = jdbc.query(sql.toString(), params, (rs, n) -> {
            ApplyRequestRow row = ApplyRequestRepository.MAPPER.mapRow(rs, n);
            reconcileClasses.put(row.applyId().value(), rs.getString("reconcile_class"));
            return row;
        });
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ObservedStep>> steps = new LinkedHashMap<>();
        rows.forEach(r -> steps.put(r.applyId().value(), new ArrayList<>()));
        jdbc.query("SELECT i.apply_id, i.placement_id, i.step, a.attempt_no, a.outcome, a.error_class, a.pre_image_change"
                        + " FROM sm_data.apply_step_intent i"
                        + " LEFT JOIN LATERAL (SELECT x.attempt_no, x.outcome, x.error_class, x.pre_image_change::text"
                        + "     AS pre_image_change"
                        + "     FROM sm_data.apply_attempt x WHERE x.intent_id = i.intent_id"
                        + "     ORDER BY x.attempt_no DESC LIMIT 1) a ON true"
                        + " WHERE i.tenant_id = :t AND i.apply_id IN (:ids)"
                        + " ORDER BY i.created_at, i.intent_id",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("ids", steps.keySet()),
                rs -> {
                    String outcome = rs.getString("outcome");
                    UUID stepPlacement = rs.getObject("placement_id", UUID.class);
                    steps.get(rs.getObject("apply_id", UUID.class)).add(new ObservedStep(
                            Step.valueOf(rs.getString("step")), rs.getObject("attempt_no") != null,
                            outcome == null ? null : AttemptOutcome.valueOf(outcome), rs.getString("error_class"),
                            Evidence.readChangedContent(rs.getString("pre_image_change")),
                            // the nil value is a pre-V021 instance-wide stop's history: the placement is unknown
                            PlacementId.INSTANCE_WIDE.value().equals(stepPlacement) ? null : stepPlacement));
                });

        List<ObservedRequest> result = new ArrayList<>(rows.size());
        for (ApplyRequestRow row : rows) {
            result.add(new ObservedRequest(row, steps.get(row.applyId().value()),
                    reconcileClasses.get(row.applyId().value())));
        }
        return result;
    }
}
