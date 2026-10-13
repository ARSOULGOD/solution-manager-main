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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /** What an INSERT names. Never {@code state_version}: the stamp trigger sets it and the runtime role has no grant. */
    private static final String INSERT_COLUMNS =
            "apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                    + " outage_confirmed_by, requested_by, requested_at, state, terminal_at";

    /** What a read returns: the insert set plus the stamp. */
    static final String COLUMNS = INSERT_COLUMNS + ", state_version";

    /**
     * Mirrors {@code apply_inflight_fence}'s state predicate — see {@code RequestState.blocksScope}.
     *
     * <p><b>MINOR fix (post-review).</b> This used to be a hand-typed literal,
     * {@code ('REQUESTED','RUNNING','APPLY_INDETERMINATE')}, spelt independently of
     * {@code TenantEnumerationRepository.tenantsWithNonTerminalWork}, which derives the identical set
     * from {@link RequestState#blocksScope()}. Two hand-maintained spellings of one predicate is
     * exactly the drift that class's own javadoc claims cannot happen — a claim that was false while
     * this literal existed independently of it. Deriving both from {@link RequestState#blocksScope()}
     * (computed once, here, at class-init) makes the claim true: there is now exactly one place this
     * set is spelt out in Java, and both classes read it.
     */
    private static final String BLOCKING_STATES = Arrays.stream(RequestState.values())
            .filter(RequestState::blocksScope)
            .map(s -> "'" + s.name() + "'")
            .collect(Collectors.joining(",", "(", ")"));

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
                "INSERT INTO sm_data.apply_request (" + INSERT_COLUMNS + ")"
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

    /**
     * Whether the data plane has declared this instance and holds it {@code active} — the only lifecycle admission
     * accepts (plan 010 T2.2). A plain read, tenant-bound ({@code I5}): another tenant's declaration of the same
     * instance id is not this tenant's. It takes no lock; admission calls it before any row is written and again
     * under the scope lock.
     */
    public boolean instanceIsActive(TenantId t, InstanceId i) {
        return jdbc.queryForList("SELECT lifecycle FROM sm_data.instance_state"
                                + " WHERE tenant_id = :t AND instance_id = :i",
                        new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value()),
                        String.class)
                .stream().anyMatch("active"::equals);
    }

    /**
     * Whether {@code generation} exists in the config of record for this instance, read UNDER the instance's
     * {@code instance_state} row lock (plan 010 T6.3). The lock is the whole point: retention (T5.2) deletes a
     * generation while holding it, and the {@code apply_request} insert that protects the generation is made by the
     * caller's transaction while it still holds it, so the answer cannot go stale before the insert.
     *
     * <p>Call it after the {@code apply_scope} lock: the order is {@code apply_scope → instance_state}, everywhere.
     * A missing {@code instance_state} row (not declared) answers {@code false}. Tenant- and instance-bound ({@code I5}).
     */
    public boolean generationExistsLockingInstance(TenantId t, InstanceId i, long generation) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", t.value()).addValue("i", i.value());
        if (jdbc.queryForList("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i"
                + " FOR UPDATE", key, Integer.class).isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.config_generation"
                        + " WHERE tenant_id = :t AND instance_id = :i AND generation = :g)",
                key.addValue("g", generation), Boolean.class));
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

    /**
     * Whether a request NEWER than {@code row} exists on its scope - any state, any operation, a stop included (plan 010
     * T6.8). "On its scope" is {@link ScopeKey#overlaps}'s meaning: the same placement, or either side instance-wide. A
     * stop is admitted while an apply is indeterminate (it is exempt from the fence), and the operator who stopped the
     * instance has said the instance must not run: a re-drive after that is a restart nobody wants.
     *
     * <p>Ordered by {@code requested_at}, then {@code apply_id} (a UUIDv7, so time-ordered itself) to break a tie.
     */
    public boolean laterRequestExists(ApplyRequestRow row) {
        ScopeKey s = row.scope();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request"
                        + " WHERE tenant_id = :t AND instance_id = :i AND apply_id <> :a"
                        + " AND (requested_at > :at OR (requested_at = :at AND apply_id > :a))"
                        + " AND (:everyPlacement OR placement_id = :p OR placement_id = :nil))",
                new MapSqlParameterSource().addValue("t", s.tenant().value()).addValue("i", s.instance().value())
                        .addValue("a", row.applyId().value()).addValue("at", Sql.at(row.requestedAt()))
                        .addValue("p", s.placement().value()).addValue("nil", PlacementId.INSTANCE_WIDE.value())
                        .addValue("everyPlacement", s.placement().isInstanceWide()),
                Boolean.class));
    }

    /**
     * The reconciler's give-up classifier (V011), written only while the request is {@code APPLY_INDETERMINATE}: the
     * terminal guard refuses it afterwards, which is what lets an operator's resolution win. The stamp trigger sees a
     * changed column that is not the compaction fold, so the write draws a new {@code state_version}.
     *
     * @return rows updated: 0 means the request was not {@code APPLY_INDETERMINATE}
     */
    public int writeReconcileClass(TenantId t, ApplyId id, String classifier) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_request SET reconcile_class = :c"
                        + " WHERE tenant_id = :t AND apply_id = :a AND state = 'APPLY_INDETERMINATE'",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())
                        .addValue("c", classifier)));
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

    static final RowMapper<ApplyRequestRow> MAPPER = (rs, n) -> {
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
                Sql.instant(rs, "terminal_at"),
                Sql.nullableLong(rs, "state_version"));
    };
}
