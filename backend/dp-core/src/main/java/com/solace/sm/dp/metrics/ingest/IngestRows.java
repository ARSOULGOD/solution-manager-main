package com.solace.sm.dp.metrics.ingest;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The persistence ports of the ingest path (plan 011 P11-3.1), kept as one file of small interfaces so the service
 * and its tests see no JDBC type: the {@code Jdbc*} implementations live in {@code dp.apply.persistence}
 * ({@code jdbcIsConfinedToPersistencePackages}). Every method names the tenant first (I5). None opens a transaction:
 * {@link IngestService} owns the one per batch, so a refusal anywhere rolls the whole batch back.
 */
public interface IngestRows {

    /** What the placement row, its instance and the instance's package version say about a batch (I1: all SM-held). */
    interface Placements {
        /** The tenant's LIVE placement; another tenant's, a retired one and an unknown one all read as empty. */
        Optional<PlacementContext> resolve(UUID tenantId, UUID placementId);
    }

    /**
     * @param definitions        the instance's package version's declared metrics, by name
     * @param restartSignalComponents the components that version declares a restart signal for
     * @param acquisitionMode    {@code direct-http} or {@code via-ssh}, from {@code instance_state}
     */
    record PlacementContext(UUID tenantId, UUID instanceId, UUID placementId, UUID catalogSolutionId,
            String packageVersion, String acquisitionMode, Map<String, MetricDef> definitions,
            Set<String> restartSignalComponents) {
        public PlacementContext {
            definitions = Map.copyOf(definitions);
            restartSignalComponents = Set.copyOf(restartSignalComponents);
        }
    }

    /** One declared metric, as the per-version copy holds it. {@code metricType} is counter, gauge or histogram. */
    record MetricDef(String name, String componentType, String metricType, String unit, Integer intervalSeconds) {
    }

    interface Series {
        /**
         * The series that count toward the instance's cap (W2-11): those of a LIVE placement whose latest segment was
         * written under {@code packageVersion} (the instance's current one), plus a series with no segment yet. A retired
         * placement's series, and a retyped metric's old series (a new type or unit mints a new series), are history, not
         * load: counting them would lock an instance out of its own dashboards after enough re-targets or upgrades.
         */
        int countForInstance(UUID tenantId, UUID instanceId, String packageVersion);

        Optional<StoredSeries> find(SeriesKey key);

        /** Inserts the series (an identity conflict reads back the existing row, so it is safe to repeat). */
        StoredSeries create(SeriesKey key, Instant createdAt);

        Optional<Segment> latestSegment(UUID tenantId, long seriesId);

        void addSegment(UUID tenantId, long seriesId, Instant effectiveFrom, String packageVersion, Integer intervalSeconds);

        /** Append-only; a repeat at the same instant is ignored. */
        void addReset(UUID tenantId, long seriesId, Instant at, String signal);
    }

    /** M-Q7 (a)'s identity, less the tenant's own id which every key carries. {@code labelsFingerprint} is constant in v1. */
    record SeriesKey(UUID tenantId, UUID instanceId, UUID placementId, String metricName, String metricType, String unit,
            SourceDiscriminator discriminator, String labelsFingerprint) {
    }

    record StoredSeries(long seriesId, SeriesKey key) {
    }

    record Segment(Instant effectiveFrom, String packageVersion, Integer intervalSeconds) {
    }

    interface Raw {
        enum Outcome {
            INSERTED,
            /** The same (series, ts) was already there: D-2, ignored. */
            DUPLICATE,
            /** No partition accepts the timestamp: the runway has run out. The statement failed; the transaction cannot go on. */
            NO_PARTITION
        }

        Outcome insert(UUID tenantId, long seriesId, Instant ts, double value, Double increment, Instant receivedAt);

        /**
         * As {@link #insert}, for a timestamp outside the UTC month of now and the one after it, where a missing partition
         * is expected and permanent rather than a runway shortage (Fable MON-03). PostgreSQL aborts the whole transaction
         * on the failed {@code INSERT}; this runs it inside a savepoint and rolls back to it on {@link Outcome#NO_PARTITION},
         * so the caller's transaction stays usable and the rest of the batch commits. Must be called inside a transaction.
         */
        Outcome insertBeyondRunway(UUID tenantId, long seriesId, Instant ts, double value, Double increment,
                Instant receivedAt);

        Optional<LastSample> latest(UUID tenantId, long seriesId);

        boolean exists(UUID tenantId, long seriesId, Instant ts);
    }

    record LastSample(Instant ts, double value) {
    }

    interface Counters {
        /** Adds {@code count} (positive) to the (tenant, placement, reason, UTC day) counter. */
        void add(UUID tenantId, UUID placementId, CollectorReason reason, LocalDate day, long count);
    }

    interface Markers {
        /** The latest restart token observed on the placement, if any. */
        Optional<String> token(UUID tenantId, UUID placementId);

        /** Records the latest observation. It is a record, not the comparison base (M-Q9). */
        void observe(UUID tenantId, UUID placementId, String token, Instant at);
    }
}
