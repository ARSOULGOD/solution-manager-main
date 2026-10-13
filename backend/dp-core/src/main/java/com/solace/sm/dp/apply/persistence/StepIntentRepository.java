package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_step_intent} — read and INSERT only, because that is all the grant allows: the table
 * is immutable to {@code sm_dp_runtime} by DCL, not by convention.
 *
 * <p><b>Never {@code ON CONFLICT}.</b> Design §2.2 rule 2 is explicit, and it is a measured position
 * rather than a stylistic one: the table carries two meaningful UNIQUEs, and a conflict on the
 * NON-arbiter {@code idempotency_key} index aborts the transaction before any read-compare could run.
 * A plain INSERT surfaces the 23505 with its constraint name, and the engine decides which of the two
 * stories it is.
 */
public final class StepIntentRepository {

    private static final String COLUMNS =
            "intent_id, tenant_id, apply_id, placement_id, replica_ref, step, idempotency_key, config_hash,"
                    + " created_at";

    private final NamedParameterJdbcTemplate jdbc;

    public StepIntentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The intent of this logical step in this placement (the identity is {@code (tenant, apply, placement, step, replica)}). */
    public Optional<StepIntentRow> find(TenantId t, ApplyId id, PlacementId placement, Step step,
            ReplicaRef replica) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_step_intent"
                        + " WHERE tenant_id = :t AND apply_id = :a AND placement_id = :p AND step = :s"
                        + " AND replica_ref = :r",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("p", placement.value()).addValue("s", step.name())
                        .addValue("r", replica.value()),
                MAPPER).stream().findFirst();
    }

    /** @throws PgConstraintViolation on 23505 — classify it by {@link PgConstraintViolation#constraint()} */
    public void insert(TenantId t, StepIntentRow row) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_step_intent (" + COLUMNS + ")"
                        + " VALUES (:id, :t, :a, :p, :r, :s, :k, :hash, :at)",
                new MapSqlParameterSource()
                        .addValue("id", row.intentId())
                        .addValue("t", t.value())
                        .addValue("a", row.applyId().value())
                        .addValue("p", row.placement().value())
                        .addValue("r", row.replica().value())
                        .addValue("s", row.step().name())
                        .addValue("k", row.idempotencyKey())
                        .addValue("hash", row.configHash())
                        .addValue("at", Sql.at(row.createdAt()))));
    }

    /** Every intent of one request — half of the resume read of design §3.1. */
    public List<StepIntentRow> forRequest(TenantId t, ApplyId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_step_intent"
                        + " WHERE tenant_id = :t AND apply_id = :a ORDER BY created_at, intent_id",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                MAPPER);
    }

    private static final RowMapper<StepIntentRow> MAPPER = (rs, n) -> new StepIntentRow(
            rs.getObject("intent_id", UUID.class),
            new ApplyId(rs.getObject("apply_id", UUID.class)),
            new PlacementId(rs.getObject("placement_id", UUID.class)),
            Step.valueOf(rs.getString("step")),
            new ReplicaRef(rs.getString("replica_ref")),
            rs.getString("idempotency_key"),
            rs.getString("config_hash"),
            Sql.instant(rs, "created_at"));
}
