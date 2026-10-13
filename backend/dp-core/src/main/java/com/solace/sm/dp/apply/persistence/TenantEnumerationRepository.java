package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Task 4b's tenant enumerator — {@code sm_data}'s own rows are the only registry: the CP owns the
 * tenant registry and {@code I7} forbids the cross-schema read (design §8 row 9), so recovery
 * enumerates from the DP's own {@code apply_request} history instead.
 *
 * <p><b>This is the codebase's first deliberately unscoped cross-tenant read</b> — {@code I5} says
 * "every query goes through the central data-access module that applies tenant/authz scope", and
 * this one, by its very purpose, does not: it exists to discover WHICH tenants have work, so there
 * is no tenant to scope by yet. It is legal ({@code V002}'s {@code GRANT SELECT} is table-wide) and
 * necessary (there is no other way to learn the answer). It reads one column, it is read-only, and
 * every caller uses it only to build a PER-TENANT loop that is scoped from there on — see
 * {@code ApplyStartupRecovery} and {@code ApplyWorker}.
 *
 * <p><b>The exception is recorded: owner ruling {@code OR-44}</b> ({@code docs/design/DECISIONS.md},
 * 2026-09-28). {@code OR-44} rules that the worker's per-poll tenant enumeration is an {@code I5}
 * exception — one column, read-only, in the persistence package, feeding a per-tenant scoped loop — and
 * that it widens plan 007 Task 4b's startup-only exception to the steady state: {@code
 * ApplyWorker.pollOnce} calls this method once per poll tick for the life of the process. The
 * condition that makes the widening safe is operational, not in this class: one Solution Manager
 * replica (Kubernetes {@code strategy: Recreate}), enforced at runtime by {@code
 * WorkerSingletonGuard} and stated for operators in {@code docs/runbooks/install-prerequisites.md}.
 * This is no longer an open item and no code here is waiting on one.
 *
 * <p><b>OR-113 (7) qualifies, and does not widen, the two reads.</b> An <i>operator command</i> run with the owner
 * credential ({@code MetricsPartitionMain archive}/{@code drop}; {@code KeyRotationMain}) may enumerate tenants from the
 * rows it operates on, because these two lists omit tenants that hold data but have no active instance and an archive
 * must not lose them. That is never reachable from the running application, and an architecture test
 * ({@code TenantEnumerationBoundaryTest}) fails if any other class carries {@code SELECT DISTINCT tenant_id} /
 * {@code GROUP BY tenant_id} SQL, or calls {@link #tenantsWithNonTerminalWork()} from outside recovery and the worker
 * ({@code MonitoringBoundaryTest} holds the other list to its four callers). These two lists stay the only
 * application-side enumerations.
 *
 * <p><b>It is not a precedent for a third.</b> There are exactly two cross-tenant reads, and this
 * method is the first. The second — owner ruling 2026-09-29 (Q-T), {@code OR-49}:
 * {@code SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'}, feeding
 * the health poller, fleet batch advancement and the key re-wrap command — is the only other one
 * permitted; it is written by plan 010 T2.4 Step 3b in this class, not by this change, and it must be
 * written once there, never re-spelled by a caller. Owner ruling M-Q3 ({@code OR-72}, which amends
 * {@code OR-49}) names plan 011's metrics collector as the fourth, named caller of that second list; owner
 * ruling {@code OR-92} (OQ-6 (a)) widens that one caller's PURPOSE to "the metrics background loop"
 * ({@code MetricsBackgroundLoop}), which reads the list once per tick and fans out per tenant to collection, the
 * rollups and the broker push-subscription reconcile. It stays ONE named caller; nothing in plan 010 calls the list
 * for it. A third list, or a fifth caller, needs a new owner ruling.
 *
 * <p><b>Reuses the non-terminal state set rather than restating it.</b> {@code OutcomeService.nonTerminal}
 * (engine) is exactly the set {@link RequestState#blocksScope} names — {@code REQUESTED},
 * {@code RUNNING}, {@code APPLY_INDETERMINATE} — and {@code blocksScope} is the one place that set
 * is spelt out, mirroring {@code apply_inflight_fence} in the DDL. Filtering
 * {@link RequestState#values()} by {@link RequestState#blocksScope} — rather than a second hand-typed
 * SQL literal — means this class and {@code OutcomeService.nonTerminal} can never drift apart: both
 * ultimately answer to the same enum method. {@code persistenceNeverReachesUpIntoTheEngine} (a real
 * arch-tests rule) is exactly why this class reaches for the {@code model}-package predicate instead
 * of importing {@code OutcomeService} itself — {@code model} is beneath both {@code engine} and
 * {@code persistence}, so referencing it here creates no boundary crossing.
 *
 * <p>Deliberately a <b>superset</b> of "tenants with RECOVERABLE work" — {@code ApplyExecutor.resume}
 * (and its narrower sibling {@code resumeRunning}) skip {@code APPLY_INDETERMINATE}, {@code RESTART}
 * operations and instance-wide applies on purpose. A tenant enumerated here may turn out to have
 * nothing a caller's own filter acts on; that is cheap and correct, never a bug this class should
 * try to avoid by re-deriving the narrower set itself.
 */
public final class TenantEnumerationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TenantEnumerationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>The second cross-tenant read</b> — owner ruling 2026-09-29 (Q-T), {@code OR-49}, written ONCE here
     * (plan 010 T2.4 Step 3b) and never re-spelt by a caller. One column, read-only: the tenant ids that have at
     * least one ACTIVE instance ({@code instance_state.lifecycle = 'active'}); a tenant whose instances are all
     * {@code pending} or {@code abandoned} is not yielded.
     *
     * <p><b>Permitted callers</b> (each uses it only to build a per-tenant, scoped loop): the health poller
     * (T7.2), fleet batch advancement (T11.2) and the key re-wrap command ({@code KeyRotationMain}); and, by
     * owner ruling M-Q3 ({@code OR-72}, which amends {@code OR-49}), plan 011's metrics background loop
     * ({@code MetricsBackgroundLoop}: collection, rollups and the push reconcile, {@code OR-92} OQ-6 (a)) as the
     * fourth, named caller. A third list, or a fifth caller, needs a new owner ruling.
     *
     * <p><b>What it does not reach</b> (D-KEY 5.4): a tenant that holds secrets but no active instance, such as
     * one whose target is registered and whose first instance is not yet declared. The key re-wrap therefore
     * also accepts {@code --tenant}; owner ruling {@code OR-85} (OQ-1) adds no third read to close the gap.
     */
    public List<TenantId> tenantsWithActiveInstances() {
        return jdbc.queryForList(
                        "SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'",
                        new MapSqlParameterSource(), UUID.class)
                .stream().map(TenantId::new).toList();
    }

    /** Every tenant with at least one {@code apply_request} row this build still has to think about. */
    public List<TenantId> tenantsWithNonTerminalWork() {
        List<String> blocking = Arrays.stream(RequestState.values())
                .filter(RequestState::blocksScope)
                .map(Enum::name)
                .toList();
        return jdbc.queryForList(
                        "SELECT DISTINCT tenant_id FROM sm_data.apply_request WHERE state IN (:states)",
                        new MapSqlParameterSource("states", blocking), UUID.class)
                .stream().map(TenantId::new).toList();
    }
}
