package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_request} — design §3.1's control history, and the DP's audit record for the
 * apply-family verbs.
 *
 * <p>The column set every UPDATE here touches is a subset of the column-scoped grant §3.1 issues to
 * {@code sm_dp_runtime}: {@code state, terminal_at, resolved_by, resolution_outcome,
 * resolution_observed, attempt_summary}. Identity columns are not runtime-writable and no method
 * offers to write them; {@code resolution_note} is in neither the INSERT nor the UPDATE grant and no
 * method names it (see {@link Resolution}) — the grant is the enforcement, this class simply does not
 * tempt it.
 */
public final class ApplyRequestRepository {

    private static final String COLUMNS =
            "apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                    + " outage_confirmed_by, requested_by, requested_at, state, terminal_at";

    /** Mirrors {@code apply_inflight_fence}'s state predicate — see {@code RequestState.blocksScope}. */
    private static final String BLOCKING_STATES = "('REQUESTED','RUNNING','APPLY_INDETERMINATE')";

    private final NamedParameterJdbcTemplate jdbc;

    public ApplyRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a freshly admitted request.
     *
     * @throws IllegalArgumentException if the row is not {@code REQUESTED} — every other state is
     *                                  reached by {@link #transition}, so that the terminal-guard
     *                                  trigger sees a real UPDATE rather than a rewritten history
     * @throws PgConstraintViolation    on any class-23 refusal, carrying the fence's name
     */
    public void insert(ApplyRequestRow row) {
        if (row.state() != RequestState.REQUESTED) {
            throw new IllegalArgumentException(
                    "a request is born REQUESTED; " + row.state() + " is reached by transition()");
        }
        ScopeKey s = row.scope();
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_request (" + COLUMNS + ")"
                        + " VALUES (:a, :t, :i, :p, :op, :gen, :strategy, :outage, :by, :at, :state,"
                        + " :terminalAt)",
                new MapSqlParameterSource()
                        .addValue("a", row.applyId().value())
                        .addValue("t", s.tenant().value())
                        .addValue("i", s.instance().value())
                        .addValue("p", s.placement().value())
                        .addValue("op", row.operation().sql())
                        .addValue("gen", row.generation())
                        .addValue("strategy", row.strategy() == null ? null : row.strategy().sql())
                        .addValue("outage", row.outageConfirmedBy())
                        .addValue("by", row.requestedBy())
                        .addValue("at", Sql.at(row.requestedAt()))
                        .addValue("state", row.state().name())
                        .addValue("terminalAt", Sql.at(row.terminalAt()))));
    }

    public Optional<ApplyRequestRow> find(TenantId t, ApplyId id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value()),
                MAPPER).stream().findFirst();
    }

    /**
     * Every request that fences the instance — ANY placement, {@code stop} excluded.
     *
     * <p>Returning the whole set rather than a boolean is what lets §3.3's cross-scope rule live in
     * the engine, where {@link ScopeKey#overlaps} can express "either is instance-wide, or they are
     * equal" — the rule the partial unique indexes provably cannot see across the sentinel boundary.
     * The stop exclusion mirrors the fence's own {@code operation <> 'stop'} predicate, so the
     * database constraint and the admission check say the same thing rather than contradicting each
     * other.
     */
    public List<ApplyRequestRow> blockingRequests(TenantId t, InstanceId i) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i"
                        + " AND state IN " + BLOCKING_STATES + " AND operation <> 'stop'",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value()),
                MAPPER);
    }

    /**
     * Every real placement of one instance that SM has ever been asked to act on — {@code BE-D26}'s
     * enumerator for an instance-wide stop.
     *
     * <p>Request history, not a placements registry: there is no registry in v1. This is the
     * complete set of placements SM has any relationship with, which is the honest boundary of what
     * an instance-wide stop can promise. {@code placement_watermark} would be strictly worse — it
     * holds only placements whose apply reached a watermark-raising terminal state, so a placement
     * SM wrote config to and then failed on would be invisible to the stop.
     */
    public List<PlacementId> placementsOf(TenantId t, InstanceId i) {
        return jdbc.queryForList("SELECT DISTINCT placement_id FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i"
                        + " AND placement_id <> :nil ORDER BY placement_id",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value())
                        .addValue("nil", PlacementId.INSTANCE_WIDE.value()),
                UUID.class).stream().map(PlacementId::new).toList();
    }

    /** The stop family's own, narrower concurrency rule: at most one in flight per exact scope. */
    public boolean stopInFlight(TenantId t, InstanceId i, PlacementId p) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p"
                        + " AND operation = 'stop' AND state IN ('REQUESTED','RUNNING'))",
                scope(t, i, p), Boolean.class));
    }

    /** The elder {@code STAGED} row admission must mark {@code SUPERSEDED} before inserting. */
    public Optional<ApplyId> stagedRequest(TenantId t, InstanceId i, PlacementId p) {
        return jdbc.queryForList("SELECT apply_id FROM sm_data.apply_request"
                                + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p"
                                + " AND state = 'STAGED'",
                        scope(t, i, p), UUID.class)
                .stream().findFirst().map(ApplyId::new);
    }

    /**
     * A guarded state walk.
     *
     * @param terminalAt the terminal timestamp, or null for a non-terminal target
     *                   ({@code terminal_has_timestamp} is an equality, both ways)
     * @return rows updated: 0 means the row was not in {@code from} — a lost race or a wrong state,
     *         reported rather than thrown, because admission and resume both treat it as information
     */
    public int transition(TenantId t, ApplyId id, RequestState from, RequestState to,
            Instant terminalAt) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET state = :to, terminal_at = :terminalAt"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = :from",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("from", from.name()).addValue("to", to.name())
                        .addValue("terminalAt", Sql.at(terminalAt))));
    }

    /**
     * {@code APPLY_INDETERMINATE → INDETERMINATE_RESOLVED} with all four resolution columns written
     * in ONE statement — {@code resolution_is_evidence} is an equality over the whole set, so writing
     * them in two statements is not merely slower, it is impossible.
     *
     * <p>The evidence arrives TYPED and is serialized by {@link Evidence} here: the two key names the
     * CHECK reads, {@code stagedGeneration} and {@code replicas}, are produced by one method rather
     * than by whichever caller assembled a string. {@code resolution_note} is deliberately NOT in the
     * SET list — see {@link Resolution}: v1 writes no free text, so the column stays NULL.
     */
    public int resolveIndeterminate(TenantId t, ApplyId id, Resolution r, Instant at) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET state = 'INDETERMINATE_RESOLVED',"
                        + " terminal_at = :at, resolved_by = :by, resolution_outcome = :outcome,"
                        + " resolution_observed = CAST(:observed AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = 'APPLY_INDETERMINATE'",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("at", Sql.at(at))
                        .addValue("by", r.resolvedBy())
                        .addValue("outcome", r.outcome().sql())
                        .addValue("observed", Sql.redacted(Evidence.write(r.evidence())))));
    }

    /** The resume read of design §3.1: what was in flight when the DP died. */
    public List<ApplyRequestRow> nonTerminal(TenantId t) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND state IN " + BLOCKING_STATES
                        + " ORDER BY requested_at",
                new MapSqlParameterSource("t", t.value()), MAPPER);
    }

    /**
     * The compaction fold — {@code attempt_summary} and nothing else.
     *
     * <p>This is the terminal guard's sanctioned write 2. The trigger compares
     * {@code to_jsonb(NEW) - 'attempt_summary'} against the same projection of OLD, so a statement
     * that also touched one further column would be refused with P0001; the statement is written
     * narrowly here so that the refusal is a bug report, not a normal path.
     *
     * <p><b>The one untyped hole in the typed evidence seam.</b> Every other {@code jsonb} column in
     * this class crosses as a model type routed through {@link Evidence} — {@code summaryJson} is a
     * caller-supplied {@code String} cast straight to {@code jsonb}, with no {@code Evidence} shape
     * behind it. That is not an oversight so much as a sequencing fact: this method was written in
     * Task 3, before the typed seam existed (Task 5), and it has no caller today — design §3.1's
     * compaction job, which would fold counts and last-error-class content derived from
     * {@code error_class} into this column, is not built. It IS bound through {@link Sql#redacted}, so
     * the TRACE bind line is safe; what is missing is the shape control the rest of this class has.
     * Plan 005's compaction job should either give this a typed shape through {@link Evidence} or
     * write down why a string is right. Until then: a malformed string here yields a class-{@code 22}
     * data-exception refusal, and unlike a class-23 refusal, {@link Sql#write} does deliberately NOT
     * reshape class 22 (see {@link PgConstraintViolation}'s javadoc) — it propagates WITH the driver
     * cause, and PostgreSQL's {@code 22P02} message quotes the offending token. {@link
     * PgConstraintViolation}'s reasoning that "every column here is bound as a typed parameter, so a
     * class-22 refusal would be a repository defect" holds for the four typed columns; it does not
     * hold for this one.
     */
    public int foldAttemptSummary(TenantId t, ApplyId id, String summaryJson) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET attempt_summary = CAST(:s AS jsonb)"
                        + " WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("a", id.value())
                        .addValue("s", Sql.redacted(summaryJson))));
    }

    private static MapSqlParameterSource scope(TenantId t, InstanceId i, PlacementId p) {
        return new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value())
                .addValue("p", p.value());
    }

    private static final RowMapper<ApplyRequestRow> MAPPER = (rs, n) -> {
        String strategy = rs.getString("strategy");
        return new ApplyRequestRow(
                new ApplyId(rs.getObject("apply_id", UUID.class)),
                new ScopeKey(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new InstanceId(rs.getObject("instance_id", UUID.class)),
                        new PlacementId(rs.getObject("placement_id", UUID.class))),
                Operation.fromSql(rs.getString("operation")),
                Sql.nullableLong(rs, "generation"),
                strategy == null ? null : Strategy.fromSql(strategy),
                rs.getString("outage_confirmed_by"),
                rs.getString("requested_by"),
                Sql.instant(rs, "requested_at"),
                RequestState.valueOf(rs.getString("state")),
                Sql.instant(rs, "terminal_at"));
    };
}
