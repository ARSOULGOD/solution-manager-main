package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code SM-D15}'s {@code rolling-health-gated} gate: a <b>policy over observations</b>, under a
 * bounded deadline.
 *
 * <p>Deliberately not a {@link RuntimeAdapter} operation ({@code BE-D12} §2.1). A gate is the core's
 * judgement about what it saw, expressed through repeated {@link RuntimeAdapter#status} calls; an
 * adapter that owned the gate would be deciding its own outcome.
 *
 * <p><b>Two conjuncts, both required.</b> The baseline must have moved <i>and</i> the runtime must
 * say {@code HEALTHY}. Health alone would certify a restart that never happened — a replica that was
 * already healthy and was never touched satisfies "healthy" trivially — and {@code restart-observed}
 * is a baseline comparison by construction (§2.2 rule 3).
 */
public final class HealthGate {

    private final Clock clock;
    private final Sleeper sleeper;
    private final ApplyEngineProperties props;

    public HealthGate(Clock clock, Sleeper sleeper, ApplyEngineProperties props) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * Polls {@code replica} until it has restarted and is healthy, or the deadline expires.
     *
     * @param before the baseline captured <b>before</b> the lifecycle verb was issued — the thing
     *               restart-observed compares against, and the reason this is a parameter rather
     *               than something the gate reads for itself. <b>{@code null} when the replica was
     *               not running before the verb</b>: there is no prior start to compare against,
     *               and the observation that matters is simply that it is up now
     */
    public GateVerdict await(RuntimeAdapter adapter, TargetReplica replica, PreBaseline before) {
        Instant deadline = clock.instant().plus(props.healthGateDeadline());
        ErrorClass last = new ErrorClass("gate.no.observation");

        while (clock.instant().isBefore(deadline)) {
            RuntimeStatus now;
            try {
                now = adapter.status(replica);
            } catch (RuntimeAdapterException e) {
                // An unreadable replica is not a verdict. Keep polling until the deadline: a
                // daemon that is briefly unreachable during a restart is the ordinary case, and
                // failing the gate on the first hiccup would fail applies that are about to pass.
                last = e.errorClass();
                sleeper.sleep(props.healthPollInterval());
                continue;
            }

            if (now.liveness() == Liveness.ABSENT) {
                // The replica is GONE, which is not "this runtime publishes no health signal" --
                // an adapter that reads health out of the runtime's own inspect output has nothing
                // to report for a container that no longer exists, and reports NOT_DECLARED for
                // both. Told apart here, because the two answers are different facts and an
                // operator reading gate.signal.absent about a deleted container would look at the
                // wrong thing.
                return new GateVerdict.Failed(new ErrorClass("gate.replica.absent"));
            }
            if (now.health() == HealthSignal.NOT_DECLARED) {
                // Not a failure and not a pass. The strategy the operator chose cannot be executed
                // against this runtime, and saying so is the only honest answer (L7). Returned
                // immediately rather than polled on: an absent signal does not arrive later.
                return new GateVerdict.Unavailable(new ErrorClass("gate.signal.absent"));
            }

            Optional<PreBaseline> observed = now.startBaseline();
            boolean restarted = observed.isPresent()
                    && (before == null || !observed.get().startedAt().equals(before.startedAt()));

            if (restarted && now.health() == HealthSignal.HEALTHY) {
                return new GateVerdict.Passed(observed.get());
            }
            last = new ErrorClass(restarted ? "gate.unhealthy" : "gate.no.restart");
            sleeper.sleep(props.healthPollInterval());
        }
        return new GateVerdict.Failed(last);
    }
}
