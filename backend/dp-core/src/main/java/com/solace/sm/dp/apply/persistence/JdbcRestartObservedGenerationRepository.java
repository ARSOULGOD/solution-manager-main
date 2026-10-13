package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.StartTolerance;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The newest generation of an instance that reached {@code restart-observed} (plan 011 P11-4.3, M-Q15), read from the
 * apply journal: {@code instance_state.reached_rung} describes only {@code last_generation} and is therefore never the
 * source here (a later stage moves it back to {@code rendered} while an older generation is still the one in force).
 *
 * <p><b>The rule is {@code ConfigLadder}'s</b> ({@code dp.config}, plan 010 T6.5; OR-112 (1)), restated per generation
 * because the ladder reads only each placement's LATEST request. A generation reached {@code restart-observed} when, for
 * every LIVE placement of the instance, some request of that generation took one of three routes:
 * <ol type="a">
 *   <li><b>gated, journal</b>: {@code APPLIED} under {@code rolling-health-gated}, every {@code MATERIALIZE} intent having,
 *       on its replica, a {@code LIFECYCLE} intent whose latest attempt succeeded and a {@code VERIFY} intent whose latest
 *       attempt succeeded with a read-back hash equal to the hash the intent bound;</li>
 *   <li><b>gated, confirmed by verify-then-retry</b>: {@code INDETERMINATE_RESOLVED} with outcome {@code applied} under the
 *       gated strategy, every {@code MATERIALIZE} intent having the same succeeded {@code LIFECYCLE} (the gate proof the
 *       reconciler journals) AND a resolution observation of its replica that says restarted onto the bound hash;</li>
 *   <li><b>ungated, a container start observed</b>: any other strategy, the generation <i>persisted</i> (staged, applied or
 *       superseded while staged: every
 *       {@code MATERIALIZE} succeeded with a read-back equal to its bound hash, all intents bound to one hash; or resolved
 *       {@code applied} with each replica confirmed at that hash), and the placement's observed container start
 *       ({@code placement_start_observation}) LATER than the start the host reported when each {@code MATERIALIZE} write was
 *       read back ({@code apply_attempt.write_baseline}): both are the Docker host's clock, so no skew between it and the
 *       data plane's can misjudge a start. The generation must also not have been <i>overtaken</i>: no later write of the
 *       placement that the same start post-dates (see {@link #overtaken}).</li>
 * </ol>
 * An intent alone is never evidence; a generation no live placement has taken a route for is never an answer. With no live
 * placement there is nothing to observe: empty.
 *
 * <p><b>Why the latest start per placement is enough:</b> a start later than generation g's write loaded g's file unless a
 * newer write came first. That newer write is looked for explicitly ({@link #overtaken}): a later request of the placement
 * that never settled as leaving g's file in place (so its write may have reached the disk: it diverged, was
 * indeterminate, or persisted) shadows g whenever the same start also post-dates ITS write, and the answer is then the
 * newer generation's own comparison, or nothing. An older generation satisfied by a start that precedes the newer write is
 * still the generation that start loaded.
 *
 * <p>Read-only, every table keyed by the tenant first (I5); every value is bound.
 */
public final class JdbcRestartObservedGenerationRepository implements RestartObservedGenerations {

    // NULL-safety: every predicate below is an EXISTS (never NULL), or a coalesce, because each sits under a NOT: a NULL
    // there would silently read as "no violation" and claim a generation observed on the strength of a missing attempt.

    /** Intent {@code intent}'s LATEST attempt exists and satisfies {@code condition} (over the alias {@code a}). */
    private static String latestAttempt(String intent, String condition) {
        return "EXISTS (SELECT 1 FROM sm_data.apply_attempt a WHERE a.intent_id = " + intent + ".intent_id"
                + " AND a.attempt_no = (SELECT max(z.attempt_no) FROM sm_data.apply_attempt z WHERE z.intent_id = "
                + intent + ".intent_id) AND " + condition + ")";
    }

    /** Request {@code r} has MATERIALIZE intents, and every one of them (alias {@code m}) satisfies {@code condition}. */
    private static String everyMaterialize(String condition) {
        return "(EXISTS (SELECT 1 FROM sm_data.apply_step_intent m WHERE m.tenant_id = :t AND m.apply_id = r.apply_id"
                + "     AND m.step = 'MATERIALIZE')"
                + " AND NOT EXISTS (SELECT 1 FROM sm_data.apply_step_intent m WHERE m.tenant_id = :t"
                + "     AND m.apply_id = r.apply_id AND m.step = 'MATERIALIZE' AND NOT (" + condition + ")))";
    }

    /** The request's MATERIALIZE intents all bound one hash (the ladder's rule: differing hashes claim nothing). */
    private static final String ONE_BOUND_HASH = "(SELECT count(DISTINCT m.config_hash) FROM sm_data.apply_step_intent m"
            + " WHERE m.tenant_id = :t AND m.apply_id = r.apply_id AND m.step = 'MATERIALIZE') = 1";

    /** For intent {@code m}: a LIFECYCLE intent of the same replica whose latest attempt succeeded (the gate passed). */
    private static final String GATE_PASSED = "EXISTS (SELECT 1 FROM sm_data.apply_step_intent l WHERE l.tenant_id = :t"
            + " AND l.apply_id = r.apply_id AND l.step = 'LIFECYCLE' AND l.replica_ref = m.replica_ref AND "
            + latestAttempt("l", "a.outcome = 'SUCCEEDED'") + ")";

    /** For intent {@code m}: the same replica's VERIFY, bound to the same hash, succeeded and read back that hash. */
    private static final String VERIFIED = "EXISTS (SELECT 1 FROM sm_data.apply_step_intent v WHERE v.tenant_id = :t"
            + " AND v.apply_id = r.apply_id AND v.step = 'VERIFY' AND v.replica_ref = m.replica_ref"
            + " AND v.config_hash = m.config_hash AND "
            + latestAttempt("v", "a.outcome = 'SUCCEEDED' AND a.observed_hash = m.config_hash") + ")";

    /** The resolution observation names replica {@code m}'s ref at its bound hash ({@code restarted} as required). */
    private static String resolvedAt(boolean restarted) {
        return "coalesce(jsonb_path_exists(r.resolution_observed, '$.replicas[*] ? (@.ref == $ref"
                + (restarted ? " && @.restarted == true" : "") + " && @.observedHash == $hash)',"
                + " jsonb_build_object('ref', m.replica_ref, 'hash', m.config_hash)), false)";
    }

    /** Route (a): the gate passed and the post-restart read-back matched, for every MATERIALIZE intent of the request. */
    private static final String GATED_JOURNAL = "(r.state = 'APPLIED' AND r.strategy = 'rolling-health-gated' AND "
            + everyMaterialize(GATE_PASSED + " AND " + VERIFIED) + ")";

    /** Route (b): confirmed by verify-then-retry: the gate proof AND the reconciler's restarted-at-hash observation. */
    private static final String GATED_CONFIRMED = "(r.state = 'INDETERMINATE_RESOLVED' AND r.resolution_outcome = 'applied'"
            + " AND r.strategy = 'rolling-health-gated' AND "
            + everyMaterialize(GATE_PASSED + " AND " + resolvedAt(true)) + ")";

    /**
     * Persisted, as the ladder states it, for a request that is not gated: the read-back, or the resolution's observation.
     * {@code SUPERSEDED} counts here although the ladder never reads it (it reads only the newest request): the state is the
     * marking of an elder {@code STAGED} (V006), so that request WAS staged, with the same read-back; and a start between its
     * write and its successor's is the elder's file loaded, which is exactly what "the generation in force" means.
     */
    private static final String PERSISTED = "(" + ONE_BOUND_HASH + " AND ("
            + "(r.state IN ('STAGED', 'APPLIED', 'SUPERSEDED') AND "
            + everyMaterialize(latestAttempt("m", "a.outcome = 'SUCCEEDED' AND a.observed_hash = m.config_hash")) + ")"
            + " OR (r.state = 'INDETERMINATE_RESOLVED' AND r.resolution_outcome = 'applied' AND "
            + everyMaterialize(resolvedAt(false)) + ")))";

    /** The tolerance of {@code ConfigLadder} (rounding, not skew), as the interval the comparisons add to a baseline. */
    private static final String TOLERANCE = " + interval '" + StartTolerance.VALUE.toMillis() + " milliseconds'";

    /** The host's start the write attempt {@code alias} reported at its read-back, as an instant (the column's shape is CHECKed). */
    private static String baselineOf(String alias) {
        return "(" + alias + ".write_baseline ->> 'startedAt')::timestamptz";
    }

    /**
     * Every MATERIALIZE write of the request succeeded on its latest attempt, left a write baseline, and the placement's
     * observed start is later than it: all host-clock values (OR-112 (1)), and the request's write was not overtaken.
     */
    private static final String STARTED_AFTER_THE_WRITE = "EXISTS (SELECT 1 FROM sm_data.placement_start_observation o"
            + " WHERE o.tenant_id = :t AND o.placement_id = p.placement_id AND "
            + everyMaterialize(latestAttempt("m", "a.outcome = 'SUCCEEDED' AND a.write_baseline IS NOT NULL AND o.started_at > "
                    + baselineOf("a") + TOLERANCE))
            + " AND " + overtaken() + ")";

    /**
     * No LATER apply of the same placement (of another generation) whose write the observed start may have loaded in place of
     * this request's. A later request counts unless it provably left this file in place ({@code FAILED_NO_EFFECT} wrote
     * nothing; {@code FAILED_ROLLED_BACK} restored the pre-image and verified it). For each of its MATERIALIZE writes, on the
     * latest attempt:
     * <ul>
     *   <li>with a write baseline: the start post-dates it (the same host-clock rule as above) - the container restarted
     *       after that write, so it loaded the later file;</li>
     *   <li>with none (it failed, was cut off, or the container had never started): it may have reached the disk, and the
     *       host's clock cannot say when. The data plane's clock can say it only one way: the start was first SEEN at
     *       {@code observed_at}, so a write that began at or after that moment cannot have preceded it; one that began
     *       earlier might have, and shadows (data plane clock against data plane clock, no skew).</li>
     * </ul>
     */
    private static String overtaken() {
        return "NOT EXISTS (SELECT 1 FROM sm_data.apply_request r2 WHERE r2.tenant_id = :t"
                + " AND r2.placement_id = p.placement_id AND r2.operation = 'apply' AND r2.generation <> r.generation"
                + " AND r2.requested_at > r.requested_at"
                + " AND r2.state NOT IN ('FAILED_NO_EFFECT', 'FAILED_ROLLED_BACK')"
                + " AND EXISTS (SELECT 1 FROM sm_data.apply_step_intent m2 WHERE m2.tenant_id = :t AND m2.apply_id = r2.apply_id"
                + "     AND m2.step = 'MATERIALIZE' AND " + latestAttempt("m2",
                        "((a.write_baseline IS NOT NULL AND o.started_at > " + baselineOf("a") + TOLERANCE + ")"
                                + " OR (a.write_baseline IS NULL AND a.started_at < o.observed_at))") + "))";
    }

    /** Route (c): not gated, persisted, and a start observed after the write. */
    private static final String UNGATED_START = "(r.strategy <> 'rolling-health-gated' AND " + PERSISTED
            + " AND " + STARTED_AFTER_THE_WRITE + ")";

    /** A request of generation {@code g}, on the placement {@code p}, that observed its restart by one of the routes. */
    private static final String OBSERVED_BY_PLACEMENT = "EXISTS (SELECT 1 FROM sm_data.apply_request r"
            + " WHERE r.tenant_id = :t AND r.instance_id = :i AND r.placement_id = p.placement_id"
            + " AND r.operation = 'apply' AND r.generation = g.generation"
            + " AND (" + GATED_JOURNAL + " OR " + GATED_CONFIRMED + " OR " + UNGATED_START + "))";

    private static final String LATEST = "SELECT max(g.generation) FROM (SELECT DISTINCT generation"
            + " FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i AND operation = 'apply'"
            + " AND state IN ('STAGED', 'APPLIED', 'INDETERMINATE_RESOLVED', 'SUPERSEDED')) g"
            + " WHERE EXISTS (SELECT 1 FROM sm_data.placement p"
            + "     WHERE p.tenant_id = :t AND p.instance_id = :i AND p.retired_at IS NULL)"
            + " AND NOT EXISTS (SELECT 1 FROM sm_data.placement p"
            + "     WHERE p.tenant_id = :t AND p.instance_id = :i AND p.retired_at IS NULL"
            + "     AND NOT " + OBSERVED_BY_PLACEMENT + ")";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRestartObservedGenerationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong latestRestartObservedGeneration(TenantId tenant, InstanceId instance) {
        List<Long> answer = jdbc.query(LATEST, new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("i", instance.value()), (rs, n) -> {
                    long generation = rs.getLong(1);
                    return rs.wasNull() ? null : generation;
                });
        Long generation = answer.isEmpty() ? null : answer.get(0);
        return generation == null ? OptionalLong.empty() : OptionalLong.of(generation);
    }
}
