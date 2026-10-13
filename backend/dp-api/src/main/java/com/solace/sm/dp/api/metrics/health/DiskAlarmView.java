package com.solace.sm.dp.api.metrics.health;

import java.util.Locale;
import java.util.Objects;

/**
 * What the data plane lets out of the process about the metrics disk (plan 011 P11-3.2, OQ-16 (a), {@code OR-92}):
 * a STATE and nothing else. The measured sizes are install-wide totals across tenants, so no byte figure is a component
 * here, and none may become one without a ruling; the operator reads the figures from the partition command.
 */
public record DiskAlarmView(State state) {

    public DiskAlarmView {
        Objects.requireNonNull(state, "state");
    }

    /** {@code ok} below 80 % of the budget, {@code warning} from 80 %, {@code critical} from 95 %, {@code unconfigured} with no budget. */
    public enum State {
        OK, WARNING, CRITICAL, UNCONFIGURED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
