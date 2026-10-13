package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.api.metrics.query.SeriesView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The server-declared gaps of one rendered line (plan 011 P11-6.1; DMR-21, discovery default D-3). Derived from the
 * emitted points, so a line made of several placements or instances has the gaps of what is drawn.
 *
 * <p>The two rules differ because the tiers store different things. On the raw tier a gap is spacing beyond the threshold
 * (twice the segment interval, never less than a bucket). On a rollup tier a bucket either exists or does not: an ABSENT
 * bucket is a gap, and so is one in which the spacing between two consecutive samples exceeded twice the interval in force
 * (the rollup's stored {@code max_gap_seconds}, judged at read time), except inside the provisional tail, where the open hour
 * is incomplete only because it is not over.
 *
 * <p>Fable MON-01: the rollup rule is NOT {@code sampleCount < expectedCount}. The collector re-arms a source from the
 * dispatch tick that fired it, so its realised period is always a little longer than the declared interval and nearly every
 * closed hour holds a few samples fewer than {@code 3600 / interval}: that rule put a gap band on every hour of every polled
 * series. The raw rule's own question, "was a spacing longer than twice the interval?", is the one asked here, of the longest
 * spacing that ended in the bucket (looking back across the bucket's edge, so an outage that straddles two hours is seen).
 * A bucket that follows an ABSENT bucket is not also flagged: the {@code no-samples} band already explains the spacing
 * that ended in it.
 */
final class GapDeriver {

    static final String NO_SAMPLES = "no-samples";
    static final String PARTIAL_SAMPLES = "partial-samples";

    private GapDeriver() {
    }

    static List<SeriesView.Gap> raw(List<SeriesView.Point> points, int bucketSeconds, long thresholdSeconds) {
        List<SeriesView.Gap> gaps = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            Instant previous = points.get(i - 1).ts();
            Instant next = points.get(i).ts();
            if (Duration.between(previous, next).toSeconds() > thresholdSeconds) {
                gaps.add(new SeriesView.Gap(previous.plusSeconds(bucketSeconds), next, NO_SAMPLES));
            }
        }
        return gaps;
    }

    /** @param gapped the buckets whose stored spacing judgement says a spacing over twice the interval ended in them */
    static List<SeriesView.Gap> rollup(List<SeriesView.Point> points, int bucketSeconds, Instant provisionalFrom,
            Set<Instant> gapped) {
        List<SeriesView.Gap> gaps = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            SeriesView.Point point = points.get(i);
            boolean provisional = provisionalFrom != null && !point.ts().isBefore(provisionalFrom);
            boolean followsAnAbsentBucket = i > 0 && points.get(i - 1).ts().plusSeconds(bucketSeconds).isBefore(point.ts());
            if (!provisional && !followsAnAbsentBucket && gapped.contains(point.ts())) {
                gaps.add(new SeriesView.Gap(point.ts(), point.ts().plusSeconds(bucketSeconds), PARTIAL_SAMPLES));
            }
            if (i + 1 < points.size()) {
                Instant next = points.get(i + 1).ts();
                Instant end = point.ts().plusSeconds(bucketSeconds);
                if (next.isAfter(end)) {
                    gaps.add(new SeriesView.Gap(end, next, NO_SAMPLES));
                }
            }
        }
        gaps.sort((a, b) -> a.from().compareTo(b.from()));
        return gaps;
    }
}
