package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.LadderEvidence;
import com.solace.sm.dp.config.LadderEvidence.PlacementEvidence;
import com.solace.sm.dp.config.LadderEvidence.RequestEvidence;
import com.solace.sm.dp.config.LadderEvidence.StepEvidence;
import com.solace.sm.dp.config.LadderRows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The journal read and the {@code reached_rung} write behind the config ladder (plan 010 T6.5), over the runtime
 * credential. In this package because JDBC is confined here; the rules are {@code dp.config.ConfigLadder}'s.
 *
 * <p>{@code I5}: every statement binds {@code tenant_id} and {@code instance_id}. The read issues {@code SELECT} only and
 * has no way to reach a host. The write sets one column, the one {@code V010} grants, and only from the value the caller
 * read ({@code reached_rung IS NOT DISTINCT FROM :expected}), so it never changes a row it did not expect to.
 */
public final class JdbcLadderRepository implements LadderRows {

    /**
     * The states that say what is on a host. {@code REQUESTED}/{@code RUNNING} are in flight, {@code FAILED_NO_EFFECT}
     * wrote nothing, and {@code SUPERSEDED} was overtaken by a later request that is itself the evidence.
     */
    private static final String SETTLED = "('REQUESTED','RUNNING','FAILED_NO_EFFECT','SUPERSEDED')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLadderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LadderEvidence evidence(TenantId tenant, InstanceId instance) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("i", instance.value());
        List<Object[]> head = jdbc.query("SELECT last_generation, reached_rung FROM sm_data.instance_state"
                        + " WHERE tenant_id = :t AND instance_id = :i", key,
                (rs, n) -> new Object[] {rs.getLong("last_generation"), rs.getString("reached_rung")});
        if (head.isEmpty()) {
            return LadderEvidence.none();
        }
        long lastGeneration = (Long) head.get(0)[0];
        String stored = (String) head.get(0)[1];

        // each live placement with the container start the data plane last observed on it, if any (OR-112 (1))
        Map<UUID, Instant> starts = new LinkedHashMap<>();
        jdbc.query("SELECT p.placement_id, o.started_at FROM sm_data.placement p"
                        + " LEFT JOIN sm_data.placement_start_observation o"
                        + "   ON o.tenant_id = p.tenant_id AND o.placement_id = p.placement_id"
                        + " WHERE p.tenant_id = :t AND p.instance_id = :i AND p.retired_at IS NULL"
                        + " ORDER BY p.placement_id", key,
                rs -> {
                    starts.put(rs.getObject("placement_id", UUID.class), Sql.instant(rs, "started_at"));
                });
        List<UUID> placements = new ArrayList<>(starts.keySet());
        Map<UUID, Settled> latest = latestSettled(key, placements);
        Map<UUID, List<StepEvidence>> steps = stepsOf(tenant, latest.values().stream().map(s -> s.row.applyId().value()).toList());

        List<PlacementEvidence> out = new ArrayList<>();
        for (UUID placement : placements) {
            Settled s = latest.get(placement);
            if (s == null) {
                out.add(new PlacementEvidence(placement, null, starts.get(placement)));
                continue;
            }
            ApplyRequestRow row = s.row;
            out.add(new PlacementEvidence(placement, new RequestEvidence(row.generation(), row.state(), row.strategy(),
                    s.outcome, s.replicas, steps.getOrDefault(row.applyId().value(), List.of())),
                    starts.get(placement)));
        }
        return new LadderEvidence(lastGeneration, stored == null ? null : rung(stored), out);
    }

    private record Settled(ApplyRequestRow row, ResolutionOutcome outcome, List<ReplicaObservation> replicas) {
    }

    /** Each live placement's latest settled apply request: the newest stamp wins. */
    private Map<UUID, Settled> latestSettled(MapSqlParameterSource key, List<UUID> placements) {
        Map<UUID, Settled> latest = new HashMap<>();
        if (placements.isEmpty()) {
            return latest;
        }
        jdbc.query("SELECT DISTINCT ON (placement_id) " + ApplyRequestRepository.COLUMNS
                        + ", resolution_outcome, resolution_observed FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i AND operation = 'apply'"
                        + " AND placement_id IN (:placements) AND state NOT IN " + SETTLED
                        + " ORDER BY placement_id, state_version DESC",
                new MapSqlParameterSource().addValues(key.getValues()).addValue("placements", placements),
                rs -> {
                    ApplyRequestRow row = ApplyRequestRepository.MAPPER.mapRow(rs, 0);
                    String outcome = rs.getString("resolution_outcome");
                    ResolutionEvidence evidence = Evidence.readResolutionEvidence(rs.getString("resolution_observed"));
                    List<ReplicaObservation> replicas = evidence instanceof ApplyResolutionEvidence apply
                            ? apply.replicas() : List.of();
                    latest.put(row.scope().placement().value(), new Settled(row,
                            outcome == null ? null : ResolutionOutcome.fromSql(outcome), replicas));
                });
        return latest;
    }

    private Map<UUID, List<StepEvidence>> stepsOf(TenantId tenant, List<UUID> applyIds) {
        Map<UUID, List<StepEvidence>> steps = new LinkedHashMap<>();
        if (applyIds.isEmpty()) {
            return steps;
        }
        jdbc.query("SELECT i.apply_id, i.step, i.replica_ref, i.config_hash, a.outcome, a.observed_hash, a.write_baseline"
                        + " FROM sm_data.apply_step_intent i"
                        + " LEFT JOIN LATERAL (SELECT x.outcome, x.observed_hash, x.write_baseline FROM sm_data.apply_attempt x"
                        + "     WHERE x.intent_id = i.intent_id ORDER BY x.attempt_no DESC LIMIT 1) a ON true"
                        + " WHERE i.tenant_id = :t AND i.apply_id IN (:ids) ORDER BY i.created_at, i.intent_id",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("ids", applyIds),
                rs -> {
                    String outcome = rs.getString("outcome");
                    steps.computeIfAbsent(rs.getObject("apply_id", UUID.class), k -> new ArrayList<>())
                            .add(new StepEvidence(Step.valueOf(rs.getString("step")),
                                    new ReplicaRef(rs.getString("replica_ref")), rs.getString("config_hash"),
                                    outcome == null ? null : AttemptOutcome.valueOf(outcome),
                                    rs.getString("observed_hash"), writeBaseline(rs.getString("write_baseline"))));
                });
        return steps;
    }

    private static Instant writeBaseline(String json) {
        PreBaseline baseline = Evidence.readPreBaseline(json);
        return baseline == null ? null : baseline.startedAt();
    }

    @Override
    public OptionalLong storeReachedRung(TenantId tenant, InstanceId instance, LadderRung expected, LadderRung next) {
        List<Long> stamped = Sql.writeReturning(() -> jdbc.queryForList(
                "UPDATE sm_data.instance_state SET reached_rung = :next"
                        + " WHERE tenant_id = :t AND instance_id = :i"
                        + " AND reached_rung IS NOT DISTINCT FROM CAST(:expected AS text) RETURNING state_version",
                new MapSqlParameterSource().addValue("next", next.wire()).addValue("t", tenant.value())
                        .addValue("i", instance.value())
                        .addValue("expected", expected == null ? null : expected.wire()),
                Long.class));
        return stamped.isEmpty() ? OptionalLong.empty() : OptionalLong.of(stamped.get(0));
    }

    private static LadderRung rung(String wire) {
        for (LadderRung rung : LadderRung.values()) {
            if (rung.wire().equals(wire)) {
                return rung;
            }
        }
        throw new IllegalStateException("sm_data.instance_state.reached_rung does not hold a rung this build wrote");
    }
}
