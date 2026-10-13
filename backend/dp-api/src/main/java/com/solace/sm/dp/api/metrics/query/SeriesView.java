package com.solace.sm.dp.api.metrics.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One rendered line. A bucket with no samples has NO point, never a null (UI-D8); the gaps are declared by the server so
 * the Java side and the chart cannot disagree about where a line breaks.
 *
 * @param ref               which instance and placement it is; {@code instanceId} is null for an across-instances aggregate
 * @param label             what a legend shows
 * @param replicasTotal     the instance's live placements
 * @param replicasInactive  always 0 here: an inactive replica is the health plane's fact, composed by the gateway
 */
public record SeriesView(SeriesRef ref, String label, int replicasTotal, int replicasInactive, State state,
        List<Point> points, List<Gap> gaps) {

    public SeriesView {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(state, "state");
        points = List.copyOf(points);
        gaps = List.copyOf(gaps);
    }

    /**
     * {@code placementId} is null for an instance-level or aggregate line; {@code sourceKey} is the placement id of a poll.
     *
     * <p><b>{@code instanceId} is null for an across-instances aggregate line</b> (OR-112 (6), {@code W4-06}): that line
     * belongs to no single instance, and the contract makes the field required and nullable, exactly as {@code placementId}
     * and {@code sourceKey} are. W3-09 had served the nil UUID in its place so the field stayed a uuid; a fixed UUID that
     * names no instance is a value a renderer can mistake for one, so it is gone, and so is the constant that carried it.
     */
    public record SeriesRef(UUID instanceId, UUID placementId, String sourceKey) {
    }

    /** {@code [timestamp, value, sampleCount, expectedCount]}; expected is 0 when the tier cannot know it. */
    public record Point(Instant ts, double value, long sampleCount, long expectedCount) {
    }

    public record Gap(Instant from, Instant to, String reason) {
    }

    /** Only {@link #OK} is produced here; the rest are the gateway's composition with the control plane and health. */
    public enum State { OK, INACTIVE_REPLICA, PENDING_CONFIG_CHANGE, TYPE_SUSPECT, UNVERIFIED_DURABILITY }
}
