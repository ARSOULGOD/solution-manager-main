package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 4a's claim — the queue is the apply journal, and a claim is a row-level operation in
 * PostgreSQL, which is already the only truth. No new durable store, no broker-as-queue.
 *
 * <p><b>The ONLY {@code REQUESTED → RUNNING} transition in the system.</b> Winning the claim IS
 * being started: {@code OutcomeService.start} is never called on the forward path any more (Task
 * 4's {@code runAdmitted} enters on a row already {@code RUNNING}); {@code start} stays the tested,
 * documented owner of that transition's SQL shape, and this class calls the SAME guarded UPDATE
 * ({@link ApplyRequestRepository#transition}) rather than re-implementing it — one transition, one
 * piece of SQL, exercised two ways.
 *
 * <p><b>Lives in {@code persistence}, not {@code engine}, deliberately.</b> {@code OutcomeService}
 * (the service that "keeps the meaning" of the transition — the watermark, the fence, the
 * {@code state_fits_operation} CHECK) is an {@code engine}-package class, and
 * {@code persistenceNeverReachesUpIntoTheEngine} (arch-tests) forbids this package from importing
 * it. That boundary is also why the scope-row lock {@code OutcomeService.start}/{@code land} take
 * is not taken here: nothing about the claim needs it. Admission already treats {@code REQUESTED}
 * and {@code RUNNING} identically (both block the scope — see {@code RequestState.blocksScope}), so
 * moving a row from one to the other changes no watermark and races no concurrent {@code land()} —
 * there is at most one non-terminal apply/restart per scope by construction (the fences), so no
 * second writer is ever touching this row while it is {@code REQUESTED}.
 *
 * <p><b>Mechanism.</b> One transaction: {@code SELECT … FOR NO KEY UPDATE SKIP LOCKED LIMIT 1} picks a
 * candidate {@code REQUESTED} row for the tenant, locking it against every OTHER concurrent
 * claimer — with exactly one row available, seven of eight concurrent callers find the candidate
 * set empty and return {@code Optional.empty()} without ever reaching the UPDATE. The winner then
 * runs the SAME guarded {@code UPDATE … WHERE state = 'REQUESTED'} {@code transition()} already
 * uses elsewhere. The row lock makes the win deterministic; the {@code WHERE} guard is defense in
 * depth, not a substitute for it (design note carried from Task 4a's own text) — {@code updated == 1}
 * is asserted rather than assumed.
 *
 * <p><b>No lease, no {@code claimed_by}, no {@code claimed_at}.</b> {@code sm_data.apply_request}
 * has neither column, and {@code V002}'s grant is column-scoped to
 * {@code (state, terminal_at, resolved_by, resolution_outcome, resolution_observed,
 * attempt_summary)} — none of which can hold a worker identity. Reclaim is restart-only: a crash
 * mid-execution leaves the row {@code RUNNING}, and Task 4b's startup sweep recovers it. This is
 * safe (not merely cheap) only because at most one worker POOL, system-wide, ever calls this method
 * — see {@code WorkerSingletonGuard} in the launcher.
 */
public final class ApplyClaimRepository {

    private final TransactionTemplate tx;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplyRequestRepository requests;

    public ApplyClaimRepository(TransactionTemplate smDataTransactions,
            NamedParameterJdbcTemplate smDataJdbc, ApplyRequestRepository applyRequestRepository) {
        this.tx = smDataTransactions;
        this.jdbc = smDataJdbc;
        this.requests = applyRequestRepository;
    }

    /**
     * The claim's row lock. {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE} (W3 review W3I-6): the claim then changes only
     * {@code state}, never a key column, and {@code FOR UPDATE} would conflict with the {@code FOR KEY SHARE} that every
     * foreign key to {@code apply_request} takes ({@code apply_schedule.apply_id}, {@code apply_reconciliation}, step and
     * attempt rows). The schedule firer committed its request and then updated the schedule row, whose V025 trigger takes
     * {@code instance_state FOR UPDATE} while the claimer's stamp (V008) wanted the same row: a 40P01 deadlock, the firer
     * lost, and the schedule rolled back to PENDING although the apply ran. {@code FOR NO KEY UPDATE} still conflicts with
     * itself and with {@code FOR UPDATE}, so claimers stay mutually exclusive (PostgreSQL 14, explicit-locking, table 13.3).
     */
    static final String CLAIM_SELECT = "SELECT apply_id FROM sm_data.apply_request"
            + " WHERE tenant_id = :t AND state = 'REQUESTED'"
            + " AND operation <> 'restart'"
            + " AND NOT (operation = 'apply' AND placement_id = :instanceWide)"
            + " ORDER BY requested_at ASC LIMIT 1 FOR NO KEY UPDATE SKIP LOCKED";

    /**
     * Whether the (outer, aliased {@code r}) request addresses a placement on an AGENT target (plan 015 T3.1, P15-6): the
     * placement of a placement-scoped request, or any live placement of the instance for an instance-wide one (an instance-wide
     * stop reaches every placement, agent ones included). Read from the registry, bound by tenant (I5).
     */
    static final String AGENT_REQUEST = "EXISTS (SELECT 1 FROM sm_data.placement pl JOIN sm_data.target_connection tc"
            + " ON tc.tenant_id = pl.tenant_id AND tc.target_id = pl.target_id"
            + " WHERE pl.tenant_id = r.tenant_id AND tc.transport = 'agent'"
            + " AND ((r.placement_id <> :instanceWide AND pl.placement_id = r.placement_id)"
            + " OR (r.placement_id = :instanceWide AND pl.instance_id = r.instance_id AND pl.retired_at IS NULL)))";

    /**
     * {@link #CLAIM_SELECT} with agent-target requests left {@code REQUESTED}: what the worker claims with once its agent cap is
     * reached, so the remaining thread(s) are only ever given dp-remote work. The same candidate set otherwise.
     */
    static final String CLAIM_SELECT_WITHOUT_AGENTS = "SELECT r.apply_id FROM sm_data.apply_request r"
            + " WHERE r.tenant_id = :t AND r.state = 'REQUESTED'"
            + " AND r.operation <> 'restart'"
            + " AND NOT (r.operation = 'apply' AND r.placement_id = :instanceWide)"
            + " AND NOT " + AGENT_REQUEST
            + " ORDER BY r.requested_at ASC LIMIT 1 FOR NO KEY UPDATE OF r SKIP LOCKED";

    private static final String IS_AGENT_REQUEST = "SELECT " + AGENT_REQUEST
            + " FROM sm_data.apply_request r WHERE r.tenant_id = :t AND r.apply_id = :id";

    /** A claimed request, and whether it addresses an agent target (the worker counts those against its cap). */
    public record Claim(ApplyId id, boolean agent) {
    }

    /**
     * Claims the oldest still-{@code REQUESTED} row like {@link #claimNextAsStarted}, but when {@code agentAllowed} is false the
     * candidate set excludes requests on agent targets, and reports whether the one it claimed addresses an agent target.
     */
    public Optional<Claim> claimNext(TenantId t, boolean agentAllowed) {
        Optional<Claim> claimed = tx.execute(status -> {
            MapSqlParameterSource params = new MapSqlParameterSource("t", t.value())
                    .addValue("instanceWide", PlacementId.INSTANCE_WIDE.value());
            List<UUID> candidate = jdbc.queryForList(agentAllowed ? CLAIM_SELECT : CLAIM_SELECT_WITHOUT_AGENTS, params, UUID.class);
            if (candidate.isEmpty()) {
                return Optional.<Claim>empty();
            }
            ApplyId id = new ApplyId(candidate.get(0));
            boolean agent = Boolean.TRUE.equals(jdbc.queryForObject(IS_AGENT_REQUEST,
                    new MapSqlParameterSource("t", t.value()).addValue("instanceWide", PlacementId.INSTANCE_WIDE.value())
                            .addValue("id", id.value()), Boolean.class));
            int updated = requests.transition(t, id, RequestState.REQUESTED, RequestState.RUNNING, null);
            return updated == 1 ? Optional.of(new Claim(id, agent)) : Optional.<Claim>empty();
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    /**
     * Claims (and thereby starts) the oldest still-{@code REQUESTED} row of this tenant, or
     * {@code Optional.empty()} if there is none, or if a concurrent claimer won the race for the
     * one that exists.
     *
     * <p><b>MINOR fix (post-review): the candidate set is narrowed to the operation/scope shapes
     * {@code ApplyExecutor.runAdmitted} actually supports.</b> Today {@code ApplyExecutor.admit} is
     * the ONLY writer of {@code REQUESTED} rows, and it already refuses a {@code RESTART} operation
     * and an instance-wide {@code APPLY} before a row is ever inserted — so this {@code WHERE}
     * clause changes nothing observable today. It exists for the caller {@code admit} does not
     * control: {@link AdmissionService#admit} is public and would insert either shape if some future
     * writer called it directly (Task 4's HTTP submission path is the obvious candidate). Without
     * this filter, such a row would be claimed here, then throw inside {@code runAdmitted} (a
     * restart operation reaches {@code run()}'s ordinary apply path, which has no restart handling;
     * an instance-wide apply has no single {@link com.solace.sm.dp.apply.model.PlacementRef} to
     * build) and sit {@code RUNNING} forever — the startup sweep does not pick it up either,
     * because {@code ApplyExecutor.resumeRunning} skips exactly these two shapes for the same
     * reason. Filtering here means the row is left {@code REQUESTED} instead — visible, not
     * silently stranded — which is the honest failure mode until a decision gives it one.
     */
    public Optional<ApplyId> claimNextAsStarted(TenantId t) {
        Optional<ApplyId> claimed = tx.execute(status -> {
            List<UUID> candidate = jdbc.queryForList(
                    CLAIM_SELECT,
                    new MapSqlParameterSource("t", t.value())
                            .addValue("instanceWide", PlacementId.INSTANCE_WIDE.value()),
                    UUID.class);
            if (candidate.isEmpty()) {
                return Optional.<ApplyId>empty();
            }
            ApplyId id = new ApplyId(candidate.get(0));
            int updated = requests.transition(t, id, RequestState.REQUESTED, RequestState.RUNNING,
                    null);
            return updated == 1 ? Optional.of(id) : Optional.<ApplyId>empty();
        });
        return claimed == null ? Optional.empty() : claimed;
    }
}
