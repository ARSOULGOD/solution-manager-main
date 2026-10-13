package com.solace.sm.dp.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-process counts of what the agent telemetry path decided (plan 015 T3.4): {@code dp-core} carries no metrics library, so the
 * names are the contract and the meter binding (the launcher's, onto the gateway's registry: plan 015 W4 lane D) reads {@link #get} for each of {@link #NAMES}. A name is a closed constant below;
 * nothing agent-supplied ever becomes a name (I6: no sample text, label or id is a key).
 */
public final class AgentTelemetryCounts {

    /** The ingest port returned {@code Pending}, which v1 never does (AGW-D27 item 2). */
    public static final String PENDING_UNEXPECTED = "agent.telemetry.pending.unexpected";
    /** A batch whose {@code batchSeq} was at or below the stream's watermark: acknowledged, not forwarded. */
    public static final String DUPLICATE_BATCH = "agent.telemetry.duplicate.batch";
    /** A batch whose {@code batchSeq} is BELOW the stream's watermark: not a redelivery, so the agent's sequence was reset (plan 015 section 10). */
    public static final String SEQUENCE_BEHIND_WATERMARK = "agent.telemetry.sequence.behind.watermark";
    /** The ingest port refused the batch for good (its placement is gone, it is too large, the database refused it). */
    public static final String REJECTED = "agent.telemetry.rejected";
    /** The ingest port is holding the batch (RetryAfter) or dropped it from its bounded buffer: not acknowledged. */
    public static final String NOT_ACKED = "agent.telemetry.not.acked";
    /** Samples refused because the agent's measured clock offset exceeds the window (OR-134). */
    public static final String CLOCK_SKEW_SAMPLES = "agent.telemetry.clock.skew.samples";
    /** Samples that lacked a series key or a timestamp. */
    public static final String MALFORMED_SAMPLES = "agent.telemetry.malformed.samples";
    /** Gap markers received: they name agent sequence numbers, which have no instant to chart, so none is forwarded. */
    public static final String GAPS_NOT_FORWARDED = "agent.telemetry.gaps.not.forwarded";

    /**
     * Every count name above, closed: what a meter binding registers (plan 015 W4 lane D). A constant added above and not here is a
     * count nobody can see; {@code AgentTelemetryCountsTest} fails on it.
     */
    public static final List<String> NAMES = List.of(PENDING_UNEXPECTED, DUPLICATE_BATCH, SEQUENCE_BEHIND_WATERMARK, REJECTED,
            NOT_ACKED, CLOCK_SKEW_SAMPLES, MALFORMED_SAMPLES, GAPS_NOT_FORWARDED);

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public void add(String name, long n) {
        Objects.requireNonNull(name, "name");
        if (n > 0) {
            counts.computeIfAbsent(name, key -> new AtomicLong()).addAndGet(n);
        }
    }

    public long get(String name) {
        AtomicLong value = counts.get(name);
        return value == null ? 0 : value.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new java.util.TreeMap<>();
        counts.forEach((name, value) -> out.put(name, value.get()));
        return out;
    }
}
