package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.OptionalLong;

/**
 * Which generation of an instance last reached {@code restart-observed} (plan 011 P11-4.3, M-Q15): the collector
 * switches its active metric list only then, so a staged or merely persisted generation changes nothing it reads.
 * Plan 010 stores ONE instance-level rung beside {@code last_generation}, which describes only the newest generation, so
 * this is derived from the apply journal and never from that column.
 *
 * <p>Whatever reads the generation in force reads it here: the collector's plan (path 1), the push subscription plan
 * (path 2) and the query service. A generation counts by any route the config ladder's {@code restart-observed} rung
 * names (OR-112 (1)): a gated apply's journal, a gated apply confirmed by verify-then-retry with its gate proved, or an
 * ungated apply (a {@code manual} one included) followed by a container start the data plane observed later than the
 * write. So an instance applied with {@code manual} is collected and subscribed once its container has restarted.
 */
public interface RestartObservedGenerations {

    /** The newest generation whose every live placement's apply observed the restart, or empty when none has. */
    OptionalLong latestRestartObservedGeneration(TenantId tenant, InstanceId instance);
}
