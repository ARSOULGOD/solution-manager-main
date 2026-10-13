package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The one place that writes {@code instance_state.reached_rung} (plan 010 T6.5). {@code OutcomeService} calls
 * {@link #settle} from INSIDE the landing transaction of an {@code apply}, after the journal row has moved: the
 * journal is read in that same transaction, so the rung and the landing commit or roll back together.
 *
 * <p>The column is a cache, not the ladder: {@code getConfigLadder} is derived from the journal ({@link ConfigLadder})
 * and never reads it. It exists so {@code instance.config-state} fires on a <i>movement</i> (OR-37) without a replay:
 * the previous rung is the column's value, and an unwritten column means the rung the stage left it at. It is written
 * only when the rung changed, because every write stamps {@code instance_state} and a stamp is an observable change.
 */
public final class LadderRecorder {

    /** Knows no instance and so never sees a movement: no read of a database, no write. */
    private static final LadderRecorder NONE = new LadderRecorder(new LadderRows() {
        @Override
        public LadderEvidence evidence(TenantId tenant, InstanceId instance) {
            return LadderEvidence.none();
        }

        @Override
        public OptionalLong storeReachedRung(TenantId tenant, InstanceId instance, LadderRung expected,
                LadderRung next) {
            throw new UnsupportedOperationException("the none recorder writes nothing");
        }
    });

    private final LadderRows rows;

    public LadderRecorder(LadderRows rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    /** A recorder that records nothing: the journal-only suites. No production bean uses it. */
    public static LadderRecorder none() {
        return NONE;
    }

    /**
     * @param previous     the column's value before this write, null if it had never been written
     * @param reached      the instance rung after it
     * @param stateVersion {@code instance_state}'s stamp after the write
     */
    public record Move(LadderRung previous, LadderRung reached, long stateVersion) {
    }

    /** Derives the instance rung, writes it if it moved, and says how it moved. */
    public Optional<Move> settle(TenantId tenant, InstanceId instance) {
        LadderEvidence evidence = rows.evidence(tenant, instance);
        LadderRung now = ConfigLadder.instanceRung(evidence);
        LadderRung stored = evidence.storedRung();
        LadderRung before = stored != null ? stored : ConfigLadder.unappliedRung(evidence);
        if (now == before) {
            return Optional.empty();
        }
        OptionalLong version = rows.storeReachedRung(tenant, instance, stored, now);
        if (version.isEmpty()) {
            // The landing holds the instance_state row lock (the request's stamp trigger took it), so nobody else can
            // have moved the column since it was read. Reaching here is a defect, not a race.
            throw new IllegalStateException("instance_state.reached_rung moved under the landing transaction");
        }
        return Optional.of(new Move(stored, now, version.getAsLong()));
    }
}
