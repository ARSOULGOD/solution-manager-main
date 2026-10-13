package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.PreBaseline;
import java.util.UUID;

/**
 * An optional capability of a {@link RuntimeAdapter}: which {@code write_baseline} the engine records on a MATERIALIZE attempt it
 * closes SUCCEEDED. It is deliberately NOT a method of {@link RuntimeAdapter} (that interface's operation set is pinned: an
 * eleventh is a design change). An adapter without it gets the engine's long-standing rule, the read-back status's own start
 * baseline; so dp-remote is unchanged.
 *
 * <p>The agent path implements it and answers {@code null}: the host reports the start WITH the write's acknowledgement, which the
 * data plane records itself in the acknowledgement's transaction ({@code AGW-D28} item 2, {@code OR-139}); a baseline derived from
 * anything else must never stand in for it, so a write acknowledged without a start has none.
 */
public interface WriteBaselinePolicy {

    /**
     * @param intentId the MATERIALIZE intent whose attempt is closing: a retry or a resume reuses the intent's command, so the
     *                 start the acknowledgement recorded may sit on an EARLIER attempt of the same intent (OR-139, clarified)
     */
    PreBaseline writeBaseline(PlacementRef placement, UUID intentId, RuntimeStatus afterWrite);

    /** The baseline to record for a write on {@code adapter}: its policy if it has one, else the status's own start baseline. */
    static PreBaseline of(RuntimeAdapter adapter, PlacementRef placement, UUID intentId, RuntimeStatus afterWrite) {
        return adapter instanceof WriteBaselinePolicy policy ? policy.writeBaseline(placement, intentId, afterWrite)
                : afterWrite.baseline();
    }
}
