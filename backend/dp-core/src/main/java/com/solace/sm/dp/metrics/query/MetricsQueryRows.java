package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every statement the query service issues, behind one seam (plan 011 P11-6.1). Implemented in
 * {@code dp.apply.persistence} ({@code JdbcMetricsQueryRepository}); the service holds no JDBC.
 *
 * <p>{@link #read} runs ONE read-only transaction with {@code statement_timeout} set for it, and everything the body does
 * through the {@link Reader} happens inside it. Every {@link Reader} method takes the tenant FIRST and binds it (I5):
 * the only statement that does not is the install-level partition read, which says so.
 */
public interface MetricsQueryRows {

    /** Thrown by an implementation when a statement hit {@code statement_timeout}. */
    final class StatementTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StatementTimeout(Throwable cause) {
            super("a metrics statement hit its timeout", cause);
        }
    }

    <T> T read(Duration statementTimeout, Function<Reader, T> body);

    /** One series of the asked metric, with the lifetime of the placement it belongs to. */
    record SeriesRow(long seriesId, UUID instanceId, UUID placementId, RangeResult.MetricType type, String unit,
            String instanceName, Instant seriesCreatedAt, Instant placementCreatedAt, Instant placementRetiredAt,
            Integer intervalSeconds) {
    }

    /** A metric's declared type, from the package definitions, for a metric with no series yet. */
    record Declared(String type, String unit) {
    }

    /**
     * One bucket of one series, additive, at whatever width it was read. A gauge carries {@code sum}, {@code min} and
     * {@code max}; a counter carries {@code delta} and {@code coveredSeconds}, both null when no increment is attributable
     * (never zero). {@code gapped} is a rollup row's stored spacing judgement (Fable MON-01): the longest spacing between two
     * consecutive samples ending in the bucket exceeded twice the interval in force. It is never derived from {@code sampleCount}
     * against {@code expectedCount}: the collector's realised period is always a little longer than the declared interval.
     */
    record BucketRow(long seriesId, Instant bucket, long sampleCount, long expectedCount, Double sum, Double min,
            Double max, Double delta, Double coveredSeconds, boolean gapped) {

        /** A bucket with no spacing judgement (a raw-built one: the raw tier judges its own gaps, and a stitched tail is provisional). */
        public BucketRow(long seriesId, Instant bucket, long sampleCount, long expectedCount, Double sum, Double min,
                Double max, Double delta, Double coveredSeconds) {
            this(seriesId, bucket, sampleCount, expectedCount, sum, min, max, delta, coveredSeconds, false);
        }
    }

    record ObservedRow(String metricName, RangeResult.MetricType type, String unit, Instant seriesCreatedAt,
            UUID placementId, Instant lastTs) {
    }

    interface Reader {

        /** The series of {@code metric} of these instances. {@code entityType}, when set, keeps only instances whose package declares the metric on that component. */
        List<SeriesRow> series(UUID tenant, String metric, String entityType, Collection<UUID> instanceIds, Instant asOf);

        /** The metric's definition among the packages of these instances, when no series exists yet. */
        Optional<Declared> declared(UUID tenant, String metric, String entityType, Collection<UUID> instanceIds);

        /** The instances that exist in this tenant, with their names. An id of another tenant is simply absent. */
        Map<UUID, String> instanceNames(UUID tenant, Collection<UUID> instanceIds);

        /** Raw samples of the series in {@code [from, to)} bucketed to {@code bucketSeconds} (epoch-2001 aligned). */
        List<BucketRow> raw(UUID tenant, RangeResult.MetricType type, Collection<Long> seriesIds, Instant from, Instant to,
                int bucketSeconds);

        /** Rollup rows whose bucket starts in {@code [from, to)}; {@code daily} picks the table (a closed switch). */
        List<BucketRow> rollup(UUID tenant, boolean daily, Collection<Long> seriesIds, Instant from, Instant to);

        /** The instances with a collector failure counted since {@code sinceDay}. */
        Set<UUID> instancesWithCollectorFailures(UUID tenant, Collection<UUID> instanceIds, LocalDate sinceDay);

        List<ObservedRow> observed(UUID tenant, String entityType, Collection<UUID> instanceIds);

        /** Counts per placement and reason since {@code sinceDay}. */
        Map<UUID, Map<CollectorReason, Long>> reasonsByPlacement(UUID tenant, Collection<UUID> placementIds, LocalDate sinceDay);

        /** Counts per reason of the whole tenant since {@code sinceDay}: the per-placement counter plus the broker-connection-level one (W3-03). */
        Map<CollectorReason, Long> reasons(UUID tenant, LocalDate sinceDay);

        /** INSTALL-LEVEL: attached closed partitions of {@code metrics_raw} with no freeze record. No tenant is read. */
        int unfrozenClosedPartitions(Instant now);
    }
}
