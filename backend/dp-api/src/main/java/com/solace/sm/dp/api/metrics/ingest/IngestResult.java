package com.solace.sm.dp.api.metrics.ingest;

import java.util.Map;
import java.util.Objects;

/** What {@link TelemetryIngestPort#ingest} did with a batch. Exactly one of four kinds. */
public sealed interface IngestResult
        permits IngestResult.Committed, IngestResult.RetryAfter, IngestResult.Rejected, IngestResult.Pending {

    /**
     * The batch is durable. {@code accepted} counts samples newly stored; {@code dropped} counts, by reason, the
     * samples this batch did not store. An exact duplicate is neither accepted nor dropped (D-2).
     */
    record Committed(int accepted, Map<CollectorReason, Integer> dropped) implements IngestResult {
        public Committed {
            dropped = Map.copyOf(Objects.requireNonNull(dropped, "dropped"));
        }
    }

    /** The batch is held; try nothing, the data plane writes it when it can. {@code millis} is positive. */
    record RetryAfter(long millis) implements IngestResult {
        public RetryAfter {
            if (millis <= 0) {
                throw new IllegalArgumentException("a retry delay must be positive, was " + millis);
            }
        }
    }

    /** The batch will never be written, and nothing was. {@code code} is a stable, value-free identifier. */
    record Rejected(String code) implements IngestResult {
        public Rejected {
            Objects.requireNonNull(code, "code");
        }
    }

    /** Reserved for UI-D5 quarantine (M-Q6, {@code OR-75}). Never returned in v1. */
    record Pending(int held) implements IngestResult {
    }
}
