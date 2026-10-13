package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import java.util.Objects;

/**
 * What a {@link HealthGate} concluded about one replica.
 *
 * <p>Three answers, not two. {@link Unavailable} is the one that is easy to leave out and expensive
 * to leave out: a runtime that publishes no health signal cannot be health-gated at all, and folding
 * that into {@link Passed} would make {@code rolling-health-gated} indistinguishable from
 * {@code rolling-no-validation} while keeping its name — the worse of the two failures, because the
 * operator chose the gated strategy precisely to get a gate ({@code L7}).
 */
public sealed interface GateVerdict {

    /** The replica restarted and the runtime says it is healthy. {@code observed} is the new baseline. */
    record Passed(PreBaseline observed) implements GateVerdict {

        public Passed {
            Objects.requireNonNull(observed, "the observed baseline");
        }
    }

    /** The deadline expired with the gate unsatisfied. The classifier says which half was missing. */
    record Failed(ErrorClass why) implements GateVerdict {

        public Failed {
            Objects.requireNonNull(why, "why");
        }
    }

    /** There is no signal to gate on. Not a failure of the replica — a failure of the question. */
    record Unavailable(ErrorClass why) implements GateVerdict {

        public Unavailable {
            Objects.requireNonNull(why, "why");
        }
    }
}
