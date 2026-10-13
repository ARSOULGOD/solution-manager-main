package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.ReconciliationLedger;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_reconciliation} (V011): the bookkeeping that keeps the indeterminate reconciler bounded (plan 010 T6.8).
 *
 * <p>{@code I5}: every statement binds {@code tenant_id}. There is no cross-tenant read here - the reconciler is handed
 * a tenant by the worker's existing enumeration ({@code OR-44}) and everything below is scoped to it.
 *
 * <p>The scheduling clock is the caller's: every instant is supplied, never taken from {@code now()}, so a test moves
 * time and the bounds are about the data plane's clock alone (the host's clock is read only by the engine, and compared only
 * with the host's own earlier values).
 *
 * <p>A row is <b>due</b> when {@code gave_up_at IS NULL AND next_attempt_at <= now}. {@code gave_up_at} is the one terminal
 * mark: a give-up, a finished reconciliation and an operator who resolved first all set it, and nothing clears it.
 */
public final class JdbcReconciliationRepository implements ReconciliationLedger {

    /** One bookkeeping row. */
    public record Row(TenantId tenant, ApplyId applyId, int inspections, int redrives, Instant nextAttemptAt,
            Instant firstIndeterminateAt, Instant gaveUpAt, String lastClass) {
    }

    private static final String COLUMNS = "tenant_id, apply_id, inspections, redrives, next_attempt_at,"
            + " first_indeterminate_at, gave_up_at, last_class";

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            new TenantId(rs.getObject("tenant_id", UUID.class)),
            new ApplyId(rs.getObject("apply_id", UUID.class)),
            rs.getInt("inspections"), rs.getInt("redrives"),
            Sql.instant(rs, "next_attempt_at"), Sql.instant(rs, "first_indeterminate_at"),
            Sql.instant(rs, "gave_up_at"), rs.getString("last_class"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gives every {@code APPLY_INDETERMINATE} request of the tenant that has no row one, due {@code dueAt}, first seen
     * {@code now}. Idempotent: a request that already has a row keeps it untouched, so a restart cannot reset a counter or
     * the age bound's origin. One read of the tenant's open requests; it touches no host.
     *
     * @return how many rows it created
     */
    public int enqueueIndeterminate(TenantId t, Instant now, Instant dueAt) {
        return Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at, first_indeterminate_at)"
                        + " SELECT r.tenant_id, r.apply_id, :due, :now FROM sm_data.apply_request r"
                        + " WHERE r.tenant_id = :t AND r.state = 'APPLY_INDETERMINATE'"
                        + " AND NOT EXISTS (SELECT 1 FROM sm_data.apply_reconciliation x"
                        + "   WHERE x.tenant_id = r.tenant_id AND x.apply_id = r.apply_id)"
                        + " ON CONFLICT (tenant_id, apply_id) DO NOTHING",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("now", Sql.at(now))
                        .addValue("due", Sql.at(dueAt))));
    }

    /**
     * Whether the tenant has an {@code APPLY_INDETERMINATE} request that is due: no row yet, or an open row whose
     * {@code next_attempt_at} has come. The poll's cheap guard. Read-only.
     */
    public boolean hasDue(TenantId t, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request r"
                        + " LEFT JOIN sm_data.apply_reconciliation x ON x.tenant_id = r.tenant_id AND x.apply_id = r.apply_id"
                        + " WHERE r.tenant_id = :t AND r.state = 'APPLY_INDETERMINATE'"
                        + " AND (x.apply_id IS NULL OR (x.gave_up_at IS NULL AND x.next_attempt_at <= :now)))",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("now", Sql.at(now)), Boolean.class));
    }

    /** Makes sure this one request has a row (due {@code now}); a row that exists is left exactly as it is. */
    public void ensureRow(TenantId t, ApplyId id, Instant now) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at, first_indeterminate_at)"
                        + " VALUES (:t, :a, :now, :now) ON CONFLICT (tenant_id, apply_id) DO NOTHING",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("now", Sql.at(now))));
    }

    /**
     * Claims up to {@code limit} due rows of the tenant, oldest first, pushing each row's {@code next_attempt_at} to
     * {@code leaseUntil} in the same statement so a second poll tick (or a second pool thread) does not pick the same
     * request up while this one is being inspected. {@code FOR UPDATE SKIP LOCKED} makes the claim safe against any
     * concurrent claimer. The lease is a floor, not a promise: the caller sets the real next attempt when it finishes.
     */
    public List<Row> claimDue(TenantId t, Instant now, Instant leaseUntil, int limit) {
        return jdbc.query(
                "UPDATE sm_data.apply_reconciliation SET next_attempt_at = :lease"
                        + " WHERE (tenant_id, apply_id) IN (SELECT tenant_id, apply_id FROM sm_data.apply_reconciliation"
                        + "   WHERE tenant_id = :t AND gave_up_at IS NULL AND next_attempt_at <= :now"
                        + "   AND EXISTS (SELECT 1 FROM sm_data.apply_request r WHERE r.tenant_id = :t"
                        + "     AND r.apply_id = apply_reconciliation.apply_id AND r.state <> 'RUNNING')"
                        + "   ORDER BY next_attempt_at, apply_id LIMIT :limit FOR UPDATE SKIP LOCKED)"
                        + " AND tenant_id = :t RETURNING " + COLUMNS,
                new MapSqlParameterSource().addValue("t", t.value()).addValue("now", Sql.at(now))
                        .addValue("lease", Sql.at(leaseUntil)).addValue("limit", limit),
                MAPPER);
    }

    /**
     * One failed or undecided inspection: counts it, sets the next attempt and the classifier. Does nothing to a row that
     * is closed.
     *
     * @return the inspection count after this one
     */
    public int recordFailedInspection(TenantId t, ApplyId id, Instant nextAttemptAt, ErrorClass why) {
        List<Integer> counts = jdbc.queryForList(
                "UPDATE sm_data.apply_reconciliation SET inspections = inspections + 1, next_attempt_at = :next,"
                        + " last_class = :why WHERE tenant_id = :t AND apply_id = :a AND gave_up_at IS NULL"
                        + " RETURNING inspections",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("next", Sql.at(nextAttemptAt)).addValue("why", why.token()),
                Integer.class);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    /** Sets the next attempt without counting an inspection: a re-drive that was cut off again. */
    public void scheduleNext(TenantId t, ApplyId id, Instant nextAttemptAt, ErrorClass why) {
        Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_reconciliation SET next_attempt_at = :next, last_class = :why"
                        + " WHERE tenant_id = :t AND apply_id = :a AND gave_up_at IS NULL",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("next", Sql.at(nextAttemptAt))
                        .addValue("why", why == null ? null : why.token())));
    }

    public Optional<Row> find(TenantId t, ApplyId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_reconciliation"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()), MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<RedriveBook> redriveBook(TenantId tenant, ApplyId id) {
        return find(tenant, id).map(r -> new RedriveBook(r.firstIndeterminateAt(), r.redrives()));
    }

    @Override
    public void recordIndeterminateLanding(TenantId tenant, ApplyId id, Instant now) {
        ensureRow(tenant, id, now);
    }

    @Override
    public void countRedrive(TenantId tenant, ApplyId id, Instant now) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, redrives, next_attempt_at,"
                        + " first_indeterminate_at) VALUES (:t, :a, 1, :now, :now)"
                        + " ON CONFLICT (tenant_id, apply_id) DO UPDATE SET redrives = apply_reconciliation.redrives + 1",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("a", id.value())
                        .addValue("now", Sql.at(now))));
    }

    @Override
    public void close(TenantId tenant, ApplyId id, Instant now, ErrorClass lastClass) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at,"
                        + " first_indeterminate_at, gave_up_at, last_class) VALUES (:t, :a, :now, :now, :now, :why)"
                        + " ON CONFLICT (tenant_id, apply_id) DO UPDATE SET"
                        + " gave_up_at = coalesce(apply_reconciliation.gave_up_at, EXCLUDED.gave_up_at),"
                        + " last_class = CASE WHEN apply_reconciliation.gave_up_at IS NULL THEN EXCLUDED.last_class"
                        + "                   ELSE apply_reconciliation.last_class END",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("a", id.value())
                        .addValue("now", Sql.at(now)).addValue("why", lastClass.token())));
    }
}
