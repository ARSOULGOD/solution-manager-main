package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult.MetricType;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Type-aware aggregation (plan 011 P11-6.1; DM-D8, M-Q20, M-Q24 (8)). Pure: rows in, cells out.
 *
 * <p><b>Gauges</b> keep {@code sum}, {@code count}, {@code min} and {@code max}, so a mean is {@code sum / count} at every
 * stage and is never an average of averages. <b>Counters</b> are rendered as a rate: {@code delta / coveredSeconds}, the
 * actual timestamp spacing of the samples that carried an increment, never a nominal interval; a null delta or no covered
 * span gives NO cell (never zero, M-Q9). The rendered value is {@code rate/s * rateSeconds}.
 *
 * <p>Across the PLACEMENTS of one instance counters SUM their rates (M-Q20): each placement's rate is computed first and
 * the rates are added, which is not {@code sum(delta) / sum(covered)} (that would average them). Across INSTANCES a
 * counter is aggregated over the instances' rates (sum, mean over the contributors, min, max), and a gauge as
 * {@code sum(sum) / sum(count)} for the mean, the sum of the instances' means for {@code SUM}, and the extremes.
 */
final class Aggregator {

    /**
     * One bucket of one line. {@code sum}, {@code n}, {@code min} and {@code max} are the gauge's pooling state and are
     * zero for a counter; {@code value} is what is drawn. A counter cell read from one series also keeps its {@code delta} and
     * {@code covered} seconds, so placements that ran one after another can be combined as sum(delta) / sum(covered).
     */
    record Cell(double value, double sum, long n, double min, double max, long samples, long expected, double delta,
            double covered, boolean gapped) {

        /** A cell with counter state and no spacing judgement. */
        Cell(double value, double sum, long n, double min, double max, long samples, long expected, double delta,
                double covered) {
            this(value, sum, n, min, max, samples, expected, delta, covered, false);
        }

        /** A cell with no counter state (a gauge, or an already-combined line). */
        Cell(double value, double sum, long n, double min, double max, long samples, long expected) {
            this(value, sum, n, min, max, samples, expected, 0, 0, false);
        }

        Cell withGapped(boolean flag) {
            return flag == gapped ? this : new Cell(value, sum, n, min, max, samples, expected, delta, covered, flag);
        }
    }

    /** Fable MON-01: a combined bucket is gapped when any bucket it was made from is. */
    private static boolean anyGapped(List<Cell> cells) {
        for (Cell c : cells) {
            if (c.gapped()) {
                return true;
            }
        }
        return false;
    }

    /** The buckets of a line whose stored spacing judgement says a gap ended in them. */
    static java.util.Set<Instant> gappedBuckets(TreeMap<Instant, Cell> cells) {
        java.util.Set<Instant> out = new java.util.HashSet<>();
        cells.forEach((bucket, cell) -> {
            if (cell.gapped()) {
                out.add(bucket);
            }
        });
        return out;
    }

    private Aggregator() {
    }

    /** One series' rows as cells, ordered by bucket. A bucket with nothing attributable has no cell. */
    static TreeMap<Instant, Cell> cells(MetricType type, List<MetricsQueryRows.BucketRow> rows, int rateSeconds) {
        TreeMap<Instant, Cell> out = new TreeMap<>();
        for (MetricsQueryRows.BucketRow row : rows) {
            if (type == MetricType.GAUGE) {
                if (row.sum() == null || row.sampleCount() <= 0) {
                    continue;
                }
                out.put(row.bucket(), new Cell(row.sum() / row.sampleCount(), row.sum(), row.sampleCount(),
                        row.min(), row.max(), row.sampleCount(), row.expectedCount()).withGapped(row.gapped()));
            } else {
                if (row.delta() == null || row.coveredSeconds() == null || row.coveredSeconds() <= 0) {
                    continue;
                }
                double value = row.delta() / row.coveredSeconds() * rateSeconds;
                out.put(row.bucket(), new Cell(value, 0, 0, 0, 0, row.sampleCount(), row.expectedCount(), row.delta(),
                        row.coveredSeconds(), row.gapped()));
            }
        }
        return out;
    }

    /**
     * The series of ONE placement (or lines with no lifetime to tell apart) into one line: counters add their rates, gauges
     * pool their samples.
     */
    static TreeMap<Instant, Cell> combinePlacements(MetricType type, Collection<TreeMap<Instant, Cell>> lines) {
        TreeMap<Instant, List<Cell>> byBucket = group(lines);
        TreeMap<Instant, Cell> out = new TreeMap<>();
        byBucket.forEach((bucket, cells) -> out.put(bucket, type == MetricType.COUNTER ? sumRates(cells) : pool(cells)));
        return out;
    }

    /**
     * W3-11: the placements of ONE instance into one line. {@code lanes.get(i)} is the lane of {@code lines.get(i)}: placements
     * that ran one AFTER another share a lane (a re-target), placements that ran at the same time do not (replicas). Gauges
     * pool their samples whatever the lane. A counter bucket first combines each lane's cells as sum(delta) / sum(covered),
     * which is one continuous line across a switch, and then ADDS the lanes' rates (M-Q20): two replicas' rates add, one
     * placement handing over to the next does not count twice in the bucket where it happens.
     */
    static TreeMap<Instant, Cell> combinePlacements(MetricType type, List<TreeMap<Instant, Cell>> lines, List<Integer> lanes,
            int rateSeconds) {
        if (type != MetricType.COUNTER) {
            return combinePlacements(type, lines);
        }
        TreeMap<Instant, Map<Integer, List<Cell>>> byBucket = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            int lane = lanes.get(i);
            lines.get(i).forEach((bucket, cell) -> byBucket.computeIfAbsent(bucket, b -> new java.util.TreeMap<>())
                    .computeIfAbsent(lane, l -> new ArrayList<>()).add(cell));
        }
        TreeMap<Instant, Cell> out = new TreeMap<>();
        byBucket.forEach((bucket, perLane) -> {
            List<Cell> laneCells = new ArrayList<>();
            for (List<Cell> cells : perLane.values()) {
                laneCells.add(cells.size() == 1 ? cells.get(0) : sequential(cells, rateSeconds));
            }
            Cell total = laneCells.size() == 1 ? laneCells.get(0) : sumRates(laneCells);
            out.put(bucket, total);
        });
        return out;
    }

    /** Placements one after another: one rate over everything they covered together. */
    private static Cell sequential(List<Cell> cells, int rateSeconds) {
        double delta = 0;
        double covered = 0;
        double weighted = 0;
        long samples = 0;
        long expected = 0;
        boolean exact = true;
        for (Cell c : cells) {
            delta += c.delta();
            covered += c.covered();
            weighted += c.value() * c.samples();
            exact &= c.covered() > 0;
            samples += c.samples();
            expected += c.expected();
        }
        if (!exact) {
            // a cell whose counter state was already folded (several series of one placement): weigh by samples instead
            return new Cell(samples == 0 ? 0 : weighted / samples, 0, 0, 0, 0, samples, expected).withGapped(anyGapped(cells));
        }
        return new Cell(delta / covered * rateSeconds, 0, 0, 0, 0, samples, expected, delta, covered, anyGapped(cells));
    }

    // ---- W3-08: rollup rows folded into a wider bin ----------------------------------------------------------------

    /** The 2001-01-01 UTC origin PostgreSQL's {@code date_bin} aligns to here, so a folded bin and a raw-built one agree. */
    static final Instant ORIGIN = Instant.parse("2001-01-01T00:00:00Z");

    /** The start of the {@code widthSeconds}-wide bin, aligned to {@link #ORIGIN}, that contains {@code ts}. */
    static Instant bin(Instant ts, long widthSeconds) {
        long since = ts.getEpochSecond() - ORIGIN.getEpochSecond();
        return ORIGIN.plusSeconds(Math.floorDiv(since, widthSeconds) * widthSeconds);
    }

    /**
     * Rows of a narrower width folded into {@code widthSeconds}-wide bins, per series, by the rules of raw: gauges pool
     * {@code sum}, count, {@code min} and {@code max}; counters add {@code delta} and {@code covered_seconds} (a null stays
     * null when every row's is, never zero), and {@code sample_count} and {@code expected_count} add. Rows come back ordered
     * by series then bin.
     */
    static List<MetricsQueryRows.BucketRow> fold(MetricType type, List<MetricsQueryRows.BucketRow> rows, long widthSeconds) {
        Map<Long, TreeMap<Instant, List<MetricsQueryRows.BucketRow>>> bySeries = new TreeMap<>();
        for (MetricsQueryRows.BucketRow row : rows) {
            bySeries.computeIfAbsent(row.seriesId(), k -> new TreeMap<>())
                    .computeIfAbsent(bin(row.bucket(), widthSeconds), k -> new ArrayList<>()).add(row);
        }
        List<MetricsQueryRows.BucketRow> out = new ArrayList<>();
        bySeries.forEach((series, bins) -> bins.forEach((bin, members) -> out.add(foldOne(type, series, bin, members))));
        return out;
    }

    private static MetricsQueryRows.BucketRow foldOne(MetricType type, long series, Instant bin,
            List<MetricsQueryRows.BucketRow> members) {
        long n = 0;
        long expected = 0;
        Double sum = null;
        Double min = null;
        Double max = null;
        Double delta = null;
        Double covered = null;
        boolean gapped = false;
        for (MetricsQueryRows.BucketRow r : members) {
            gapped |= r.gapped();
            n += r.sampleCount();
            expected += r.expectedCount();
            if (type == MetricType.GAUGE) {
                sum = add(sum, r.sum());
                min = extreme(min, r.min(), true);
                max = extreme(max, r.max(), false);
            } else {
                delta = add(delta, r.delta());
                covered = add(covered, r.coveredSeconds());
            }
        }
        return new MetricsQueryRows.BucketRow(series, bin, n, expected, sum, min, max, delta, covered, gapped);
    }

    private static Double extreme(Double a, Double b, boolean low) {
        if (b == null) {
            return a;
        }
        if (a == null) {
            return b;
        }
        return low ? Math.min(a, b) : Math.max(a, b);
    }

    private static Double add(Double a, Double b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : Double.valueOf(a + b);   // no ternary mixing Double and double: it would unbox a null
    }

    /** One line per instance into one aggregate line, bucket by bucket, over the instances that have that bucket. */
    static TreeMap<Instant, Cell> acrossInstances(MetricType type, RangeQuery.Across across, Collection<TreeMap<Instant, Cell>> lines) {
        TreeMap<Instant, List<Cell>> byBucket = group(lines);
        TreeMap<Instant, Cell> out = new TreeMap<>();
        byBucket.forEach((bucket, cells) -> out.put(bucket, type == MetricType.COUNTER
                ? counterAcross(across, cells) : gaugeAcross(across, cells)));
        return out;
    }

    static List<SeriesView.Point> points(TreeMap<Instant, Cell> cells) {
        List<SeriesView.Point> out = new ArrayList<>(cells.size());
        for (Map.Entry<Instant, Cell> e : cells.entrySet()) {
            out.add(new SeriesView.Point(e.getKey(), e.getValue().value(), e.getValue().samples(), e.getValue().expected()));
        }
        return out;
    }

    private static TreeMap<Instant, List<Cell>> group(Collection<TreeMap<Instant, Cell>> lines) {
        TreeMap<Instant, List<Cell>> byBucket = new TreeMap<>();
        for (TreeMap<Instant, Cell> line : lines) {
            line.forEach((bucket, cell) -> byBucket.computeIfAbsent(bucket, b -> new ArrayList<>()).add(cell));
        }
        return byBucket;
    }

    private static Cell sumRates(List<Cell> cells) {
        if (cells.size() == 1) {
            return cells.get(0);                         // nothing to add: keep the counter state (delta, covered) it carries
        }
        double value = 0;
        long samples = 0;
        long expected = 0;
        for (Cell c : cells) {
            value += c.value();
            samples += c.samples();
            expected += c.expected();
        }
        return new Cell(value, 0, 0, 0, 0, samples, expected).withGapped(anyGapped(cells));
    }

    private static Cell pool(List<Cell> cells) {
        double sum = 0;
        long n = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long expected = 0;
        for (Cell c : cells) {
            sum += c.sum();
            n += c.n();
            min = Math.min(min, c.min());
            max = Math.max(max, c.max());
            expected += c.expected();
        }
        return new Cell(sum / n, sum, n, min, max, n, expected).withGapped(anyGapped(cells));
    }

    private static Cell counterAcross(RangeQuery.Across across, List<Cell> cells) {
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long samples = 0;
        long expected = 0;
        for (Cell c : cells) {
            sum += c.value();
            min = Math.min(min, c.value());
            max = Math.max(max, c.value());
            samples += c.samples();
            expected += c.expected();
        }
        double value = switch (across) {
            case SUM -> sum;
            case AVG -> sum / cells.size();
            case MIN -> min;
            case MAX -> max;
            case NONE -> throw new IllegalArgumentException("no aggregation to apply");
        };
        return new Cell(value, 0, 0, 0, 0, samples, expected).withGapped(anyGapped(cells));
    }

    private static Cell gaugeAcross(RangeQuery.Across across, List<Cell> cells) {
        Cell pooled = pool(cells);
        double sumOfMeans = 0;
        for (Cell c : cells) {
            sumOfMeans += c.value();
        }
        double value = switch (across) {
            case SUM -> sumOfMeans;
            case AVG -> pooled.sum() / pooled.n();
            case MIN -> pooled.min();
            case MAX -> pooled.max();
            case NONE -> throw new IllegalArgumentException("no aggregation to apply");
        };
        return new Cell(value, pooled.sum(), pooled.n(), pooled.min(), pooled.max(), pooled.samples(), pooled.expected()).withGapped(pooled.gapped());
    }
}
