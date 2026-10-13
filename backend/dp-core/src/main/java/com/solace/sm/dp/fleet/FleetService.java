package com.solace.sm.dp.fleet;

import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.fleet.FleetAcceptedView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyService;
import com.solace.sm.dp.api.fleet.FleetConflictException;
import com.solace.sm.dp.api.fleet.FleetMemberView;
import com.solace.sm.dp.api.fleet.FleetObservationView;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.dp.api.fleet.FleetValidationException.Rule;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fleet apply (plan 010 T11.2; {@code REQ-013}, {@code OR-47}; {@code docs/design/backend/fleet-apply.md}): one
 * submit across several instances, each member its own ordinary apply request with its own id, journal and audit line,
 * all traceable to one fleet id, run in batches by ONE scheduler.
 *
 * <h2>A fleet is a record over ordinary requests</h2>
 * {@code apply_request} gains nothing. A member's request id is MINTED AT SUBMIT and inserted by admission later
 * ({@code fleet-apply.md} section 4.2 (a)), so a crash between an admission and its record is recovered by admitting again
 * under the same id: admission answers {@code AlreadyAdmitted}, and the member is recorded {@code admitted}.
 *
 * <h2>Authorization is not here</h2>
 * The data plane may not call the control plane's {@code AuthorizationService} ({@code I7}). The gateway {@code require}s
 * every member at its own instance and hands over verdicts; a refused member is stored and reported, never read, validated
 * or applied here, and authorization is decided at submit and not repeated for a later batch (Q-F1 (a)).
 *
 * <h2>{@link #advance}: two transactions around the admissions</h2>
 * <ol>
 *   <li><b>(i) Transaction A</b> locks the fleet row; settles every admitted member whose request has settled
 *       ({@link FleetSettlement}); under {@code halt-remaining} skips every pending member of a LATER batch once a member
 *       of the current one has failed; moves to the next batch once the current one is fully settled, or lands the fleet
 *       on its terminal state. Commits (stamped).
 *   <li><b>(ii)</b> Each pending member of the current batch is admitted under its pre-minted id, each in the engine's own
 *       transaction. An exception is caught for that member and answers nothing: it is never a refusal and never stalls the
 *       others.
 *   <li><b>(iii) Transaction B</b> locks the fleet row again and records each member admitted or not admitted, updating only
 *       members still {@code pending}. BEFORE recording {@code not_admitted} it re-reads whether a request with the member's
 *       id exists, and records {@code admitted} if one does.
 * </ol>
 * No transaction holds the fleet row while it takes {@code apply_scope}: admission (ii) holds {@code apply_scope} then
 * {@code instance_state}, and (i) and (iii) hold only the fleet row, so a fleet adds no edge that could invert the order.
 *
 * <h2>Across one instance's placements (plan 012 T6; {@code OPS-Q8}, {@code BE-D50})</h2>
 * A rollout across placements is a fleet of placement-scoped applies, so the multi-placement safety of the ungated
 * strategies is the fleet's, set by {@code batchSize} and the batch rules, never by the strategy inside one request:
 * <ul>
 *   <li><b>K-2, refuse at submit</b> ({@link #requireNoRepeatedInstance}): a {@code rolling-health-gated} or
 *       {@code rolling-no-validation} fleet whose any batch holds two placements of one instance answers {@code 422}
 *       ({@link Rule#BATCH_INSTANCE_REPEATED}) and writes nothing. {@code restart-all} is exempt - its outage
 *       confirmation covers the multi-placement outage - and so is {@code manual}, which restarts nothing.
 *   <li><b>The settle delay</b> ({@link #stillSettling}): a {@code rolling-no-validation} fleet opens its next batch no
 *       earlier than {@code sm.dp.fleet.no-validation.settle} (default 30 s) after the LAST settle anchor
 *       ({@code COALESCE(terminal_at, apply_reconciliation.gave_up_at)}) of the current batch's requests. It is computed from persisted timestamps on every pass and holds no in-memory timer, so a
 *       scheduler restart keeps it. It lives here, between batches, because {@code ApplyExecutor} never sees more than
 *       one placement.
 * </ul>
 *
 * <h2>One advancer, and why correctness depends on it</h2>
 * The id check in admission and (iii)'s re-read make CRASH RECOVERY safe. They do not make concurrent advancers safe: a
 * member genuinely refused to one advancer ({@code SCOPE_BUSY}) that a second admits a moment later would be recorded
 * {@code not_admitted} while its apply runs. So exactly one advancer exists per fleet - the {@code FleetScheduler}, one
 * thread, in the process that holds {@code WorkerSingletonGuard} - and NOTHING ELSE calls {@link #advance}:
 * {@link #submitFleet} wakes the scheduler and returns, and never advances.
 *
 * <p>{@code I5}: the tenant is the caller's argument and part of every key. {@code I6}: nothing here holds a secret; the
 * rows are ids, generation numbers and classifier names.
 */
public final class FleetService implements FleetApplyService {

    private static final Logger LOG = LoggerFactory.getLogger(FleetService.class);

    /**
     * The strategies the API serves ({@code ApplyServiceImpl}'s set, and for the same reason): plan 012 T2 ({@code OR-121})
     * serves the two ungated ones the engine already ran; {@code scheduled} answers {@code 501} until plan 012 T7.
     */
    private static final Set<Strategy> SERVED = EnumSet.of(Strategy.MANUAL, Strategy.ROLLING_HEALTH_GATED,
            Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL);

    /** The one door to the engine: an admission under a pre-minted id. */
    @FunctionalInterface
    public interface Admitter {
        AdmissionResult admit(ApplyCommand command, ApplyId id);
    }

    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final FleetRows fleets;
    private final InstanceRows instances;
    private final PlacementRows placements;
    private final ConfigGenerationRows generations;
    private final Admitter admitter;
    private final Clock clock;
    private final Duration noValidationSettle;
    private final Runnable wake;

    /**
     * @param tx                 the {@code sm_data} transaction template (REQUIRED); the engine's admission opens its own
     * @param noValidationSettle the pause between two batches of a {@code rolling-no-validation} fleet
     *                           ({@code sm.dp.fleet.no-validation.settle}); zero withdraws it
     * @param wake               brings the scheduler's next pass forward; latency only, a failure never fails a submit
     */
    public FleetService(TransactionTemplate tx, FleetRows fleets, InstanceRows instances, PlacementRows placements,
            ConfigGenerationRows generations, Admitter admitter, Clock clock, Duration noValidationSettle,
            Runnable wake) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.fleets = Objects.requireNonNull(fleets, "fleets");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.admitter = Objects.requireNonNull(admitter, "admitter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.noValidationSettle = Objects.requireNonNull(noValidationSettle, "noValidationSettle");
        if (noValidationSettle.isNegative()) {
            throw new IllegalArgumentException("the settle delay cannot be negative");
        }
        this.wake = Objects.requireNonNull(wake, "wake");
        // One snapshot for a read that spans the fleet row and its members: a fleet observed half-way through a
        // transition would show a state its own members contradict.
        this.readTx = new TransactionTemplate(tx.getTransactionManager());
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx.setReadOnly(true);
    }

    // ---- submit ----------------------------------------------------------------------------------------------

    @Override
    public FleetAcceptedView submitFleet(UUID organizationId, FleetApplyCommand command) {
        Strategy strategy = Strategy.fromSql(command.strategy());
        if (!SERVED.contains(strategy)) {
            throw new NotImplementedStrategyException(strategy.sql());
        }
        if (!FleetRecord.CONTINUE.equals(command.onFailure()) && !FleetRecord.HALT_REMAINING.equals(command.onFailure())) {
            throw new IllegalArgumentException("onFailure is continue or halt-remaining");
        }
        if (command.members().stream().allMatch(FleetApplyCommand.Member::refused)) {
            throw new IllegalArgumentException("a fleet with no authorized member is refused by the caller, never recorded");
        }
        requireNamedOnce(command);
        validateAuthorizedMembers(organizationId, command);
        requireNoRepeatedInstance(strategy, command);

        UUID fleetId = Uuid7.next();
        FleetRecord fleet = new FleetRecord(organizationId, fleetId, command.solutionId(), strategy.sql(),
                command.outageConfirmedBy(), command.batchSize(), command.onFailure(), FleetRecord.RUNNING, 0,
                command.actor().toString(), clock.instant(), command.correlationId(), null, 0);
        FleetAcceptedView accepted = tx.execute(status -> {
            fleets.insertFleet(fleet);
            List<FleetApplyCommand.Member> named = command.members();
            for (int ordinal = 0; ordinal < named.size(); ordinal++) {
                FleetApplyCommand.Member member = named.get(ordinal);
                MemberRecord row = member.refused()
                        ? new MemberRecord(organizationId, fleetId, ordinal, null, member.instanceId(),
                                member.placementId(), member.generation(), null, MemberRecord.REFUSED,
                                member.refusal().name(), null, null)
                        : new MemberRecord(organizationId, fleetId, ordinal, ordinal / command.batchSize(),
                                member.instanceId(), member.placementId(), member.generation(), ApplyId.next().value(),
                                MemberRecord.PENDING, null, null, null);
                try {
                    fleets.insertMember(row);
                } catch (FleetRows.PlacementReservedException reserved) {
                    throw new FleetConflictException(ordinal);
                }
            }
            return accepted(organizationId, fleetId);
        });
        try {
            wake.run();
        } catch (RuntimeException e) {
            LOG.warn("fleet submit: the scheduler wake failed; its next pass will advance the fleet ({})",
                    e.getClass().getSimpleName());
        }
        return accepted;
    }

    /** A member is named once (section 5.1): a property of the body alone, so it reveals nothing about any instance. */
    private static void requireNamedOnce(FleetApplyCommand command) {
        Set<List<UUID>> seen = new HashSet<>();
        for (int ordinal = 0; ordinal < command.members().size(); ordinal++) {
            FleetApplyCommand.Member member = command.members().get(ordinal);
            if (!seen.add(List.of(member.instanceId(), member.placementId()))) {
                throw new FleetValidationException(ordinal, Rule.DUPLICATE_MEMBER);
            }
        }
    }

    /**
     * {@code OPS-Q8} K-2: for a {@code rolling-*} strategy a batch holds at most one placement of any instance. Member
     * {@code k} is in batch {@code k / batchSize} (the ordinal counts a refused member too), and the first member to
     * repeat an instance within its batch is the one named. Only AUTHORIZED members count: a refused member never runs, so
     * it makes nothing "simultaneous", and counting it would let a caller learn from the {@code 422} that an instance it
     * may not see shares the batch. Runs after the per-member checks so a more basic failure of an earlier member is
     * answered first; it reads nothing and writes nothing. {@code restart-all} and {@code manual} are exempt.
     */
    private static void requireNoRepeatedInstance(Strategy strategy, FleetApplyCommand command) {
        if (strategy != Strategy.ROLLING_HEALTH_GATED && strategy != Strategy.ROLLING_NO_VALIDATION) {
            return;
        }
        Set<List<Object>> seen = new HashSet<>();
        for (int ordinal = 0; ordinal < command.members().size(); ordinal++) {
            FleetApplyCommand.Member member = command.members().get(ordinal);
            if (!member.refused() && !seen.add(List.of(ordinal / command.batchSize(), member.instanceId()))) {
                throw new FleetValidationException(ordinal, Rule.BATCH_INSTANCE_REPEATED);
            }
        }
    }

    /**
     * Section 5.1, for AUTHORIZED members only and in request order: the first failure refuses the whole fleet. A refused
     * member is never read - a {@code 422} saying "instance X has no generation 9" would confirm that X exists to a caller
     * who cannot see it. Reads only; nothing is written until every member has passed.
     */
    private void validateAuthorizedMembers(UUID organizationId, FleetApplyCommand command) {
        TenantId tenant = new TenantId(organizationId);
        for (int ordinal = 0; ordinal < command.members().size(); ordinal++) {
            FleetApplyCommand.Member member = command.members().get(ordinal);
            if (member.refused()) {
                continue;
            }
            Optional<InstanceRecord> instance = instances.find(organizationId, member.instanceId());
            if (instance.isEmpty() || instance.get().lifecycle() != InstanceLifecycle.ACTIVE
                    || !instance.get().catalogSolutionId().equals(command.solutionId())) {
                throw new FleetValidationException(ordinal, Rule.INSTANCE_NOT_ELIGIBLE);
            }
            if (placements.findLive(organizationId, member.instanceId(), member.placementId()).isEmpty()) {
                throw new FleetValidationException(ordinal, Rule.PLACEMENT_NOT_LIVE);
            }
            if (generations.view(tenant, new InstanceId(member.instanceId()), member.generation()).isEmpty()) {
                throw new FleetValidationException(ordinal, Rule.GENERATION_NOT_STAGED);
            }
        }
    }

    // ---- observe ---------------------------------------------------------------------------------------------

    @Override
    public Optional<FleetObservationView> observeFleet(UUID organizationId, UUID fleetId) {
        return readTx.execute(status -> fleets.find(organizationId, fleetId).map(fleet -> {
            List<FleetMemberView> members = memberViews(organizationId, fleetId);
            return new FleetObservationView(fleet.fleetId(), fleet.catalogSolutionId(), fleet.state(), fleet.strategy(),
                    fleet.batchSize(), fleet.onFailure(), fleet.currentBatch(), fleet.requestedAt(), fleet.terminalAt(),
                    fleet.stateVersion(), members);
        }));
    }

    private FleetAcceptedView accepted(UUID organizationId, UUID fleetId) {
        FleetRecord fleet = fleets.find(organizationId, fleetId).orElseThrow();
        return new FleetAcceptedView(fleetId, fleet.stateVersion(), fleet.state(), memberViews(organizationId, fleetId));
    }

    /**
     * Every member, as the contract shows it. The pre-minted id is a RESERVATION until the member is admitted: it names no
     * {@code apply_request}, so it is shown (with the request's state and stamp) exactly when the member is
     * {@code admitted}, and never for a {@code pending}, {@code skipped}, {@code not_admitted} or {@code refused} one.
     */
    private List<FleetMemberView> memberViews(UUID organizationId, UUID fleetId) {
        List<MemberRecord> members = fleets.members(organizationId, fleetId);
        Map<UUID, MemberRequest> requests = fleets.requestsOf(organizationId, members.stream()
                .filter(m -> m.is(MemberRecord.ADMITTED)).map(MemberRecord::applyId).toList());
        List<FleetMemberView> views = new ArrayList<>(members.size());
        for (MemberRecord m : members) {
            boolean admitted = m.is(MemberRecord.ADMITTED);
            MemberRequest request = admitted ? requests.get(m.applyId()) : null;
            views.add(new FleetMemberView(m.ordinal(), m.batch(), m.instanceId(), m.placementId(), m.generation(),
                    m.disposition(), admitted ? m.applyId() : null, m.settled(), m.refusalCode(), m.admissionRefusal(),
                    request == null ? null : request.state().name(), request == null ? null : request.stateVersion()));
        }
        return views;
    }

    // ---- advance: the scheduler's, and nobody else's -----------------------------------------------------------

    /** This tenant's {@code RUNNING} fleets: one tenant-scoped read. The scheduler iterates the tenants it is given. */
    public List<UUID> runningFleets(UUID tenantId) {
        return fleets.runningFleets(tenantId);
    }

    /**
     * One idempotent, crash-safe pass over one fleet (class javadoc). <b>Called only by {@code FleetScheduler}</b>, on its
     * one thread, in the process holding {@code WorkerSingletonGuard}; {@code onlyTheFleetSchedulerAdvances...} pins that
     * {@link #submitFleet} never calls it. A pass over a fleet that is not {@code RUNNING} writes nothing.
     */
    public void advance(UUID tenantId, UUID fleetId) {
        Optional<List<MemberRecord>> toAdmit = tx.execute(status -> settleAndMove(tenantId, fleetId));
        if (toAdmit == null || toAdmit.isEmpty() || toAdmit.get().isEmpty()) {
            return;
        }
        FleetRecord fleet = fleets.find(tenantId, fleetId).orElseThrow();
        Strategy strategy = Strategy.fromSql(fleet.strategy());

        // (ii) each admission in the engine's own transaction; an exception answers nothing for that member
        Map<Integer, AdmissionResult> answers = new HashMap<>();
        for (MemberRecord member : toAdmit.get()) {
            ApplyCommand command = new ApplyCommand(new ScopeKey(new TenantId(tenantId),
                    new InstanceId(member.instanceId()), new PlacementId(member.placementId())), Operation.APPLY,
                    member.generation(), strategy, fleet.outageConfirmedBy(), fleet.requestedBy());
            try {
                answers.put(member.ordinal(), admitter.admit(command, new ApplyId(member.applyId())));
            } catch (RuntimeException e) {
                // Classifier only: an engine or driver message can carry a value. The next tick tries this member again,
                // and (iii) decides on what is in the journal, not on what this call managed to say.
                LOG.warn("fleet advance: admission of one member did not answer ({})", e.getClass().getSimpleName());
            }
        }

        // (iii) record
        tx.executeWithoutResult(status -> record(tenantId, fleetId, toAdmit.get(), answers));
    }

    /**
     * Transaction A: everything that depends only on the fleet and its members' requests. Returns the members of the
     * (possibly new) current batch still to admit, or empty when the fleet is not running or has just landed.
     */
    private Optional<List<MemberRecord>> settleAndMove(UUID tenantId, UUID fleetId) {
        Optional<FleetRecord> locked = fleets.lock(tenantId, fleetId);
        if (locked.isEmpty() || !locked.get().running()) {
            return Optional.empty();
        }
        FleetRecord fleet = locked.get();
        List<MemberRecord> members = new ArrayList<>(fleets.members(tenantId, fleetId));

        // settle every admitted member whose request has settled - once, and never again
        List<UUID> unsettled = members.stream().filter(m -> m.is(MemberRecord.ADMITTED) && m.settled() == null)
                .map(MemberRecord::applyId).toList();
        Map<UUID, MemberRequest> requests = fleets.requestsOf(tenantId, unsettled);
        for (int i = 0; i < members.size(); i++) {
            MemberRecord member = members.get(i);
            MemberRequest request = member.is(MemberRecord.ADMITTED) && member.settled() == null
                    ? requests.get(member.applyId()) : null;
            Optional<String> settled = request == null ? Optional.empty() : FleetSettlement.of(request);
            if (settled.isPresent() && fleets.settle(tenantId, fleetId, member.ordinal(), settled.get()) == 1) {
                members.set(i, withSettled(member, settled.get()));
            }
        }

        // halt-remaining acts BETWEEN batches: a failure in the current (or an earlier) batch stops every unadmitted
        // member of a LATER one; an admitted member is never stopped, and nor is a same-batch sibling (Q-F9 (a))
        int current = fleet.currentBatch();
        if (FleetRecord.HALT_REMAINING.equals(fleet.onFailure()) && members.stream().anyMatch(
                m -> m.batch() != null && m.batch() <= fleet.currentBatch() && MemberRecord.FAILED.equals(m.settled()))) {
            for (int i = 0; i < members.size(); i++) {
                MemberRecord member = members.get(i);
                if (member.is(MemberRecord.PENDING) && member.batch() > fleet.currentBatch()
                        && fleets.markSkipped(tenantId, fleetId, member.ordinal()) == 1) {
                    members.set(i, withDisposition(member, MemberRecord.SKIPPED));
                }
            }
        }

        // the next batch opens only when every member of the current one is settled (or was never going to be)
        while (batchDone(members, current)) {
            int next = nextBatch(members, current);
            if (next < 0) {
                fleets.land(tenantId, fleetId, verdict(members), clock.instant());
                return Optional.empty();
            }
            if (stillSettling(tenantId, fleet, members, current)) {
                // the current batch is done but its hosts were restarted too recently: stay on it, write nothing, and
                // let the next pass look again (the delay is read from persisted timestamps, never a timer)
                return Optional.empty();
            }
            fleets.moveToBatch(tenantId, fleetId, next);
            current = next;
        }
        int batch = current;
        return Optional.of(members.stream().filter(m -> m.is(MemberRecord.PENDING) && m.batch() == batch).toList());
    }

    /**
     * {@code OPS-Q8} (x): whether a {@code rolling-no-validation} fleet must still wait before opening the batch after
     * {@code batch} - that is, whether {@code settle} has not yet passed since the LAST settle anchor among the requests
     * of {@code batch}. Always {@code false} for every other strategy, for a zero delay, and for a batch none of whose
     * members was ever admitted (nothing was restarted, so there is nothing to settle).
     *
     * <p>The anchor is {@code COALESCE(apply_request.terminal_at, apply_reconciliation.gave_up_at)} (W4 review W4B-1).
     * {@code terminal_at} is set exactly for the states that are neither {@code REQUESTED}, {@code RUNNING} nor
     * {@code APPLY_INDETERMINATE} ({@code terminal_has_timestamp}, measured against V002, unchanged through V011), so a
     * member that settled {@code failed} because the reconciler gave up on an {@code APPLY_INDETERMINATE} request has none.
     * That give-up is NOT bounded by an age: the first inspection is due at once and several classifiers give up on it, so
     * the host may still be restarting. The reconciler closes the request's {@code apply_reconciliation} row with
     * {@code gave_up_at} in the same transaction that writes the classifier (V011), a persisted instant that survives a
     * restart, and no migration is needed to read it. A member with neither instant cannot have settled, so the fail-safe
     * direction holds. {@code BE-D50} names {@code terminal_at}; the extension is recorded there as a dated rider.
     */
    private boolean stillSettling(UUID tenantId, FleetRecord fleet, List<MemberRecord> members, int batch) {
        if (!Strategy.ROLLING_NO_VALIDATION.sql().equals(fleet.strategy()) || noValidationSettle.isZero()) {
            return false;
        }
        List<UUID> admitted = members.stream().filter(m -> m.batch() != null && m.batch() == batch
                && m.is(MemberRecord.ADMITTED)).map(MemberRecord::applyId).toList();
        Instant last = fleets.requestsOf(tenantId, admitted).values().stream().map(MemberRequest::settleAnchor)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        return last != null && clock.instant().isBefore(last.plus(noValidationSettle));
    }

    /** Whether every member of {@code batch} is done: admitted and settled, refused at admission, or skipped. */
    private static boolean batchDone(List<MemberRecord> members, int batch) {
        return members.stream().filter(m -> m.batch() != null && m.batch() == batch).allMatch(m ->
                m.is(MemberRecord.NOT_ADMITTED) || m.is(MemberRecord.SKIPPED)
                        || (m.is(MemberRecord.ADMITTED) && m.settled() != null));
    }

    /** The lowest batch after {@code current} that still has a pending member, or -1. An empty batch is passed over. */
    private static int nextBatch(List<MemberRecord> members, int current) {
        return members.stream().filter(m -> m.is(MemberRecord.PENDING) && m.batch() > current)
                .mapToInt(MemberRecord::batch).min().orElse(-1);
    }

    /**
     * The fleet's terminal state, over the members that were ever going to run (a refused member is reported, not
     * counted): {@code HALTED} when a member was skipped; else {@code COMPLETED_WITH_FAILURES} when any settled failed;
     * else {@code SUCCEEDED}.
     */
    private static String verdict(List<MemberRecord> members) {
        if (members.stream().anyMatch(m -> m.is(MemberRecord.SKIPPED))) {
            return FleetRecord.HALTED;
        }
        if (members.stream().anyMatch(m -> MemberRecord.FAILED.equals(m.settled()))) {
            return FleetRecord.COMPLETED_WITH_FAILURES;
        }
        return FleetRecord.SUCCEEDED;
    }

    /** Transaction B: record what (ii) learned, updating only members still pending. */
    private void record(UUID tenantId, UUID fleetId, List<MemberRecord> attempted, Map<Integer, AdmissionResult> answers) {
        Optional<FleetRecord> locked = fleets.lock(tenantId, fleetId);
        if (locked.isEmpty() || !locked.get().running()) {
            return;
        }
        for (MemberRecord member : attempted) {
            AdmissionResult answer = answers.get(member.ordinal());
            boolean admitted = answer instanceof AdmissionResult.Admitted || answer instanceof AdmissionResult.AlreadyAdmitted;
            // The re-read: a refusal, or no answer at all, is believed only if no request exists under the member's id. A
            // refusal under the scope lock means a request holds the scope - and if it is THIS member's (a crash between
            // (ii) and here, replayed), the member is admitted, whatever admission said this time.
            if (admitted || fleets.requestExists(tenantId, member.applyId())) {
                fleets.markAdmitted(tenantId, fleetId, member.ordinal());
            } else if (answer != null) {
                fleets.markNotAdmitted(tenantId, fleetId, member.ordinal(), refusalName(answer));
            }
            // else: an exception and no request - the member stays pending, and the next tick tries it again
        }
    }

    /** A classifier from a closed set (the {@code RefusalReason} names and the three pre-admission refusals), never text. */
    private static String refusalName(AdmissionResult answer) {
        if (answer instanceof AdmissionResult.NotAdmitted refused) {
            return refused.reason().name();
        }
        if (answer instanceof AdmissionResult.StrategyUnsupported) {
            return "STRATEGY_UNSUPPORTED";
        }
        if (answer instanceof AdmissionResult.ScopeUnsupported) {
            return "SCOPE_UNSUPPORTED";
        }
        if (answer instanceof AdmissionResult.OperationUnsupported) {
            return "OPERATION_UNSUPPORTED";
        }
        throw new IllegalStateException("an admission answer this service cannot have asked for");
    }

    private static MemberRecord withSettled(MemberRecord m, String settled) {
        return new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(), m.batch(), m.instanceId(), m.placementId(),
                m.generation(), m.applyId(), m.disposition(), m.refusalCode(), m.admissionRefusal(), settled);
    }

    private static MemberRecord withDisposition(MemberRecord m, String disposition) {
        return new MemberRecord(m.tenantId(), m.fleetId(), m.ordinal(), m.batch(), m.instanceId(), m.placementId(),
                m.generation(), m.applyId(), disposition, m.refusalCode(), m.admissionRefusal(), m.settled());
    }
}
