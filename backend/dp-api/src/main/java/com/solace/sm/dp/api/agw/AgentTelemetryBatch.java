package com.solace.sm.dp.api.agw;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One batch from an agent stream. <b>Everything here is agent-supplied</b>: {@code labels} and {@code streamId}
 * never resolve a tenant or an instance (I5) — the stream is bound to the session's placements by T3.4.
 */
public record AgentTelemetryBatch(String streamId, long batchSeq, List<Sample> samples,
        List<Gap> gaps, Instant producedAt) {
    public AgentTelemetryBatch {
        samples = List.copyOf(samples == null ? List.of() : samples);
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
    }

    /** {@code labels} are agent-supplied (see the type). */
    public record Sample(String seriesKey, String type, Instant timestamp, double value,
            Map<String, String> labels) {
        public Sample { labels = Map.copyOf(labels == null ? Map.of() : labels); }
    }

    public record Gap(long fromSeq, long toSeq, String reason) { }
}
