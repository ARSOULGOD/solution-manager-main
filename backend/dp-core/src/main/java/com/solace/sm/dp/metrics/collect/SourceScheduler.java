package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.collect.CollectionPlan.TargetKey;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Next-due scheduling for the collector (plan 011 P11-4.3, M-Q3 {@code OR-72}): which {@linkplain Unit units} start now,
 * which missed their deadline, how many may run against one target, and how long a failing host rests. <b>Pure
 * bookkeeping</b>: time is a parameter, no thread or I/O lives here, so every rule is a plain assertion
 * ({@code SourceSchedulerTest}). The worker pool is {@link MetricsCollector}'s.
 *
 * <ul>
 *   <li><b>Next-due with jitter.</b> A unit that starts at {@code t} is next due at {@code t + interval * (1 +- jitter)},
 *       so a plan's units do not all hit a host in the same second.</li>
 *   <li><b>Deadline = interval.</b> A unit still running at its next due time, or still waiting for a target slot a whole
 *       interval after it was due, is {@linkplain Round#missed() missed}: a gap and a {@code DEADLINE_MISSED} count,
 *       <i>never</i> a stored zero and never a second concurrent run of the same unit.</li>
 *   <li><b>Per-target cap.</b> At most {@code perTargetCap} runs are in flight against one target (M-Q3's reservation;
 *       the SSH session pool is shared with the health poller). A unit that cannot get a slot stays due and is retried at
 *       the next dispatch.</li>
 *   <li><b>Per-host backoff.</b> After the n-th consecutive failed run a target is not read again for
 *       {@code min(maxBackoffMultiplier, 2^(n-1))} intervals (1x, 2x, 4x, 8x...); one answer resets it. Another target is
 *       unaffected.</li>
 * </ul>
 *
 * <p>Thread-safe: every method is synchronized. {@link #finished} may arrive for a unit a refresh has since dropped.
 */
public final class SourceScheduler {

    /** What one dispatch decided: the units to start now, and the ones whose deadline passed without a run. */
    public record Round(List<Unit> start, List<Unit> missed) {
        public Round {
            start = List.copyOf(start);
            missed = List.copyOf(missed);
        }
    }

    private static final class UnitState {
        Unit unit;
        Instant dueAt;
        boolean inFlight;

        UnitState(Unit unit, Instant dueAt) {
            this.unit = unit;
            this.dueAt = dueAt;
        }
    }

    private static final class TargetState {
        int inFlight;
        int failures;
        Instant backoffUntil = Instant.MIN;
    }

    private final int perTargetCap;
    private final double jitter;
    private final int maxBackoffMultiplier;
    private final DoubleSupplier random;
    private final Map<Unit.Id, UnitState> units = new LinkedHashMap<>();
    private final Map<TargetKey, TargetState> targets = new HashMap<>();

    /**
     * @param random uniform in [0, 1]; {@code 0.5} means no jitter
     */
    public SourceScheduler(int perTargetCap, double jitter, int maxBackoffMultiplier, DoubleSupplier random) {
        if (perTargetCap < 1 || maxBackoffMultiplier < 1 || jitter < 0 || jitter > 0.5) {
            throw new IllegalArgumentException("a scheduler needs a cap >= 1, a backoff multiplier >= 1 and a jitter "
                    + "between 0 and 0.5");
        }
        this.perTargetCap = perTargetCap;
        this.jitter = jitter;
        this.maxBackoffMultiplier = maxBackoffMultiplier;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * The cap on runs against one target: the configured cap, less the probe slots of a target's SSH connection kept for
     * health ({@code sshProbesPerSession - healthReserve}), and never below 1.
     */
    public static int effectiveCap(CollectorProperties properties, int sshProbesPerSession) {
        int room = sshProbesPerSession - properties.healthReserve();
        return Math.max(1, Math.min(properties.perTargetCap(), room));
    }

    /**
     * Makes {@code units} the tenant's plan. A unit already known keeps its cadence and its in-flight mark (a refresh is
     * not a restart); a new one is due at once (less a little jitter spread); one that is gone is forgotten.
     */
    public synchronized void replace(TenantId tenant, List<Unit> plan, Instant now) {
        Set<Unit.Id> keep = new HashSet<>();
        for (Unit unit : plan) {
            keep.add(unit.id());
            UnitState known = units.get(unit.id());
            if (known == null) {
                units.put(unit.id(), new UnitState(unit, now.plus(initialOffset(unit))));
            } else {
                known.unit = unit;
            }
        }
        units.entrySet().removeIf(e -> e.getKey().tenant().equals(tenant) && !keep.contains(e.getKey()));
        pruneTargets();
    }

    /** Forgets every unit of a tenant that is no longer in the active list: its collection stops with the tick. */
    public synchronized void retainTenants(Collection<TenantId> tenants) {
        Set<TenantId> keep = Set.copyOf(tenants);
        units.entrySet().removeIf(e -> !keep.contains(e.getKey().tenant()));
        pruneTargets();
    }

    /** Decides one dispatch. A started unit is marked in flight and holds a slot until {@link #finished}. */
    public synchronized Round due(Instant now) {
        List<Unit> start = new ArrayList<>();
        List<Unit> missed = new ArrayList<>();
        for (UnitState state : units.values()) {
            if (now.isBefore(state.dueAt)) {
                continue;
            }
            Unit unit = state.unit;
            if (state.inFlight) {
                missed.add(unit);                              // still running at its next due time: a gap
                state.dueAt = now.plus(next(unit));
                continue;
            }
            TargetState target = targets.computeIfAbsent(unit.target(), k -> new TargetState());
            if (now.isBefore(target.backoffUntil)) {
                state.dueAt = max(state.dueAt, target.backoffUntil);   // resting: no read, and no miss to count
                continue;
            }
            if (target.inFlight >= perTargetCap) {
                if (!now.isBefore(state.dueAt.plusSeconds(unit.intervalSeconds()))) {
                    missed.add(unit);                          // waited a whole interval for a slot
                    state.dueAt = now.plus(next(unit));
                }
                continue;
            }
            state.inFlight = true;
            target.inFlight++;
            state.dueAt = now.plus(next(unit));
            start.add(unit);
        }
        return new Round(start, missed);
    }

    /**
     * A run ended. {@code hostFailed} is true when the host gave no answer at all this run (every fetch a transport
     * failure); that backs the target off. An answer of any status resets it.
     */
    public synchronized void finished(Unit unit, Instant now, boolean hostFailed) {
        UnitState state = units.get(unit.id());
        if (state != null) {
            state.inFlight = false;
        }
        TargetState target = targets.computeIfAbsent(unit.target(), k -> new TargetState());
        if (target.inFlight > 0) {
            target.inFlight--;
        }
        if (hostFailed) {
            target.failures++;
            long multiplier = Math.min(maxBackoffMultiplier, 1L << Math.min(target.failures - 1, 30));
            target.backoffUntil = now.plusSeconds(unit.intervalSeconds() * multiplier);
        } else {
            target.failures = 0;
            target.backoffUntil = Instant.MIN;
        }
    }

    public synchronized int inFlight(TargetKey target) {
        TargetState state = targets.get(target);
        return state == null ? 0 : state.inFlight;
    }

    public synchronized int knownUnits() {
        return units.size();
    }

    // ---- arithmetic -------------------------------------------------------------------------------------------

    private Duration next(Unit unit) {
        double factor = 1 + jitter * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.max(1, Math.round(unit.intervalSeconds() * 1000.0 * factor)));
    }

    /** Spreads a new plan's first reads over at most {@code jitter} of the interval; never earlier than now. */
    private Duration initialOffset(Unit unit) {
        double offset = unit.intervalSeconds() * 1000.0 * jitter * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.max(0, Math.round(offset)));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** A target nothing references and nothing runs against holds no state worth keeping. */
    private void pruneTargets() {
        Set<TargetKey> referenced = new HashSet<>();
        units.values().forEach(s -> referenced.add(s.unit.target()));
        targets.entrySet().removeIf(e -> e.getValue().inFlight == 0 && !referenced.contains(e.getKey()));
    }
}
