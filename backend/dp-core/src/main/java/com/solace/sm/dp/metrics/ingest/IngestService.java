package com.solace.sm.dp.metrics.ingest;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.RestartDetection;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.metrics.RunwayDegradation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one {@link TelemetryIngestPort} (plan 011 P11-3.1): SM-held identity (I1), the ruled counter semantics (M-Q9),
 * the caps (M-Q6, M-Q24), a bounded buffer and durable per-placement counters (M-Q21).
 *
 * <p><b>One transaction per batch.</b> Everything a batch writes (series, segments, resets, raw points, counters, the
 * restart marker) commits together or not at all. The per-series state the counter rules need lives in memory and is
 * advanced ONLY after the commit, so a rolled-back batch leaves no trace in it.
 *
 * <p><b>One writer thread</b> ({@link IngestWriter}) runs every transaction, so ingest holds one pool connection and
 * that state needs no lock. {@link #ingest} puts its batch in the bounded {@link IngestBuffer}, wakes the writer and
 * waits for its own batch only up to {@code caller-wait}. A batch the database or the partition runway cannot take
 * stays held and the caller is answered {@code RetryAfter}: a database outage never blocks a collector for a pool's
 * connection timeout, and never loses a batch except by the bound, which is counted.
 *
 * <p>Nothing here names a JDBC type: the {@link IngestRows} ports are implemented in {@code dp.apply.persistence}.
 */
public final class IngestService implements TelemetryIngestPort, RunwayDegradation, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);

    /** sha256 of the canonical EMPTY label map {@code {}} (discovery default D-10: the allowlist is empty in v1). */
    public static final String EMPTY_LABELS_FINGERPRINT = sha256("{}");

    private static final int MAX_TOKEN_LENGTH = 256;

    /** W2-10: how far past the data plane's clock a sample's timestamp may lie before it is dropped as unparseable. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    /** What the writer thread remembers about one series. {@code lastTs} is null until a sample has been stored. */
    private record SeriesState(long seriesId, Instant lastTs, double lastValue, String lastToken, String segmentVersion,
            Integer segmentInterval) {
        CounterSemantics.Previous previous() {
            return lastTs == null ? null : new CounterSemantics.Previous(lastTs, lastValue, lastToken);
        }
    }

    private record CounterKey(UUID tenantId, UUID placementId, CollectorReason reason) {
    }

    /** The result of one attempt on the writer thread; {@code runwayExhausted} means roll back and hold. */
    private record Applied(IngestResult result, boolean runwayExhausted) {
    }

    private final TransactionTemplate transactions;
    private final IngestRows.Placements placements;
    private final IngestRows.Series series;
    private final IngestRows.Raw raw;
    private final IngestRows.Counters counters;
    private final IngestRows.Markers markers;
    private final IngestProperties properties;
    private final Clock clock;
    private final IngestBuffer buffer;
    private final IngestWriter writer;

    // Writer-thread only.
    private final Map<IngestRows.SeriesKey, SeriesState> cache = new HashMap<>();
    private final Map<IngestBuffer.Key, Instant> restingUntil = new HashMap<>();
    private Instant databaseRestingUntil = Instant.MIN;

    // Any thread.
    private final Map<CounterKey, Long> pendingCounts = new HashMap<>();
    private volatile boolean runwayDegraded;
    private final Map<IngestBuffer.Key, Instant> lastSeen = new ConcurrentHashMap<>();

    public IngestService(TransactionTemplate transactions, IngestRows.Placements placements, IngestRows.Series series,
            IngestRows.Raw raw, IngestRows.Counters counters, IngestRows.Markers markers, IngestProperties properties,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.series = Objects.requireNonNull(series, "series");
        this.raw = Objects.requireNonNull(raw, "raw");
        this.counters = Objects.requireNonNull(counters, "counters");
        this.markers = Objects.requireNonNull(markers, "markers");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.buffer = new IngestBuffer(properties.bufferTicks());
        this.writer = new IngestWriter(this::drain, properties.drainInterval());
    }

    // ---- the port -----------------------------------------------------------------------------------------------

    @Override
    public IngestResult ingest(IngestBatch batch) {
        IngestResult refusal = refuse(batch);
        if (refusal != null) {
            return refusal;
        }
        lastSeen.put(IngestBuffer.Key.of(batch), clock.instant());
        IngestBuffer.Held held = new IngestBuffer.Held(batch, new CompletableFuture<>());
        for (IngestBuffer.Held dropped : buffer.offer(held)) {
            addPending(dropped.batch().organizationId(), dropped.batch().placementId(), CollectorReason.BUFFER_DROPPED,
                    Math.max(1, dropped.batch().samples().size()));
            dropped.done().complete(new IngestResult.Rejected("buffer.dropped"));
        }
        if (!writer.kick()) {
            return retryAfter();
        }
        // W3-14: the push path's one worker never uses the commit result, so it does not stand waiting for it: a slow commit
        // must not stall the drain of the bounded receive queue (the batch stays held either way)
        Duration wait = batch.discriminator() == SourceDiscriminator.BROKER_PUSH
                ? min(properties.callerWait(), properties.pushCallerWait()) : properties.callerWait();
        try {
            return held.done().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return retryAfter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return retryAfter();
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** The checks that need no database: size, the dp-poll identity mapping (M-Q7) and the v1 gap-marker rule (D-3). */
    private IngestResult refuse(IngestBatch batch) {
        if (batch.samples().size() > properties.maxBatchSamples()) {
            return new IngestResult.Rejected("batch.too-large");
        }
        if (batch.discriminator() == SourceDiscriminator.DP_POLL) {
            if (!batch.gapMarkers().isEmpty()) {
                return new IngestResult.Rejected("gap-markers.refused");
            }
            if (!batch.bindingId().equals(batch.placementId())
                    || !batch.sourceKey().equals(batch.placementId().toString())) {
                return new IngestResult.Rejected("identity.mismatch");
            }
        }
        return null;
    }

    private IngestResult.RetryAfter retryAfter() {
        return new IngestResult.RetryAfter(properties.retryDelay().toMillis());
    }

    // ---- the writer thread --------------------------------------------------------------------------------------

    /** One pass over every held placement, oldest batch first, then the counters nobody could write yet. */
    private void drain() {
        for (IngestBuffer.Key key : buffer.keys()) {
            Instant now = clock.instant();
            if (now.isBefore(databaseRestingUntil)) {
                answerHeld(key, retryAfter());
                continue;
            }
            Instant rest = restingUntil.get(key);
            if (rest != null && now.isBefore(rest)) {
                answerHeld(key, retryAfter());
                continue;
            }
            restingUntil.remove(key);
            drainPlacement(key);
        }
        if (!clock.instant().isBefore(databaseRestingUntil)) {
            flushPendingCounts();
        }
    }

    private void drainPlacement(IngestBuffer.Key key) {
        IngestBuffer.Held head;
        while ((head = buffer.claimHead(key)) != null) {
            try {
                if (!drainHead(key, head)) {
                    return;
                }
            } finally {
                buffer.release(key, head);   // W2-13: committing is over, whatever happened
            }
        }
    }

    /** One held batch. False means stop draining this placement (the batch stays held and its callers were answered). */
    private boolean drainHead(IngestBuffer.Key key, IngestBuffer.Held head) {
        IngestResult result;
        try {
            result = attempt(head.batch());
        } catch (DataAccessException | TransactionException e) {
            // The database is unreachable or broke the transaction: hold everything, rest, and say so quietly.
            databaseRestingUntil = clock.instant().plus(properties.retryDelay());
            LOG.warn("ingest could not reach the database ({}); {} batches are held and it retries in {}",
                    e.getClass().getSimpleName(), buffer.total(), properties.retryDelay());
            answerHeld(key, retryAfter());
            return false;
        } catch (PgConstraintViolation e) {
            LOG.warn("ingest refused a batch the database would not accept (constraint {}); it is not retried",
                    e.constraint());
            result = new IngestResult.Rejected("batch.refused");
        }
        if (result instanceof IngestResult.RetryAfter retry) {
            restingUntil.put(key, clock.instant().plusMillis(retry.millis()));
            answerHeld(key, retry);
            return false;
        }
        buffer.removeHead(key, head);
        head.done().complete(result);
        return true;
    }

    /** Tells every caller still waiting on this placement that its batch is held. */
    private void answerHeld(IngestBuffer.Key key, IngestResult retry) {
        buffer.snapshot(key).forEach(held -> held.done().complete(retry));
    }

    // ---- one batch, one transaction -----------------------------------------------------------------------------

    private IngestResult attempt(IngestBatch batch) {
        Map<CollectorReason, Integer> counted = new EnumMap<>(CollectorReason.class);
        Map<CollectorReason, Long> pendingSnapshot = pendingFor(batch.organizationId(), batch.placementId());
        Map<IngestRows.SeriesKey, SeriesState> work = new HashMap<>();
        Applied applied = transactions.execute(status -> {
            Applied result = apply(batch, counted, work, pendingSnapshot);
            if (result.runwayExhausted()) {
                status.setRollbackOnly();
            }
            return result;
        });
        if (applied.runwayExhausted()) {
            runwayDegraded = true;
            countRunwayDegraded(batch);
            return retryAfter();
        }
        if (applied.result() instanceof IngestResult.Committed) {
            cache.putAll(work);
            subtractPending(batch.organizationId(), batch.placementId(), pendingSnapshot);
            runwayDegraded = false;
        }
        return applied.result();
    }

    private Applied apply(IngestBatch batch, Map<CollectorReason, Integer> counted,
            Map<IngestRows.SeriesKey, SeriesState> work, Map<CollectorReason, Long> pendingSnapshot) {
        UUID tenant = batch.organizationId();
        Optional<IngestRows.PlacementContext> found = placements.resolve(tenant, batch.placementId());
        if (found.isEmpty()) {
            return new Applied(new IngestResult.Rejected("placement.unknown"), false);
        }
        IngestRows.PlacementContext context = found.get();
        Instant now = clock.instant();
        int accepted = 0;
        String observedToken = null;
        Instant observedTokenTs = null;
        for (IngestSample sample : batch.samples()) {
            String token = tokenOf(sample);
            if (token != null && (observedTokenTs == null || !sample.ts().isBefore(observedTokenTs))) {
                observedToken = token;
                observedTokenTs = sample.ts();
            }
            IngestRows.MetricDef definition = context.definitions().get(sample.metricName());
            if (definition == null) {
                count(counted, CollectorReason.UNDECLARED_DROPPED);
                continue;
            }
            boolean counter = "counter".equals(definition.metricType());
            if (!counter && !"gauge".equals(definition.metricType())) {
                continue; // a histogram is never fetched and never stored; the catalog shows it as unsupported
            }
            if (!Double.isFinite(sample.value()) || sample.restartToken() != null && token == null
                    && !sample.restartToken().isBlank()) {
                count(counted, CollectorReason.PARSE_FAILED);
                continue;
            }
            if (sample.ts().isAfter(now.plus(MAX_FUTURE_SKEW))) {
                // W2-10: a timestamp beyond the skew is a clock fault or a corrupt sample, never data. Dropped before a
                // series is minted for it, so it can neither stall its placement's queue nor leave a row behind.
                count(counted, CollectorReason.PARSE_FAILED);
                continue;
            }
            IngestRows.SeriesKey key = new IngestRows.SeriesKey(tenant, context.instanceId(), context.placementId(),
                    definition.name(), definition.metricType(), definition.unit(), batch.discriminator(),
                    EMPTY_LABELS_FINGERPRINT);
            SeriesState state = work.get(key);
            if (state == null) {
                state = cache.get(key);
            }
            if (state == null) {
                state = load(key);
            }
            if (state == null) {
                if (series.countForInstance(tenant, context.instanceId(), context.packageVersion()) >= properties.maxSeriesPerInstance()) {
                    count(counted, CollectorReason.CAP_REJECTED);
                    continue;
                }
                state = new SeriesState(series.create(key, now).seriesId(), null, 0, null, null, null);
            }
            if (state.lastTs() != null && !sample.ts().isAfter(state.lastTs())) {
                // An exact repeat is ignored (D-2). Anything else older than the series' last point is dropped and
                // counted (OQ-12 (a)): a clock step back or a replayed buffer, never stored out of order.
                if (sample.ts().isBefore(state.lastTs()) && !raw.exists(tenant, state.seriesId(), sample.ts())) {
                    count(counted, CollectorReason.OUT_OF_ORDER);
                }
                work.put(key, state);
                continue;
            }
            Integer interval = batch.discriminator() == SourceDiscriminator.DP_POLL
                    ? definition.intervalSeconds() != null ? definition.intervalSeconds() : properties.defaultIntervalSeconds()
                    : null;
            CounterSemantics.Decision decision = counter
                    ? CounterSemantics.counter(detection(batch.discriminator(), context, definition), state.previous(),
                            sample.ts(), sample.value(), token, interval)
                    : CounterSemantics.gauge(state.previous(), token);
            if (decision.counted() != null) {
                count(counted, decision.counted());
            }
            if (!Objects.equals(state.segmentVersion(), context.packageVersion())
                    || !Objects.equals(state.segmentInterval(), interval) || state.segmentVersion() == null) {
                series.addSegment(tenant, state.seriesId(), sample.ts(), context.packageVersion(), interval);
            }
            // Fable MON-03: only a timestamp outside the current and next month can hit a partition that will never exist, and
            // that failure aborts the transaction; those inserts run in a savepoint so the rest of the batch still commits.
            IngestRows.Raw.Outcome outcome = isCurrentOrNextMonth(sample.ts(), now)
                    ? raw.insert(tenant, state.seriesId(), sample.ts(), sample.value(), decision.increment(),
                            sample.receivedAt())
                    : raw.insertBeyondRunway(tenant, state.seriesId(), sample.ts(), sample.value(), decision.increment(),
                            sample.receivedAt());
            if (outcome == IngestRows.Raw.Outcome.NO_PARTITION) {
                if (isCurrentOrNextMonth(sample.ts(), now)) {
                    return new Applied(null, true); // the runway ran out: hold the batch until the operator's command
                }
                // W2-10: no partition will ever appear for this timestamp (before a detached month, or past the
                // runway). Holding the batch would retry it forever at the head of its placement's queue.
                count(counted, CollectorReason.OUT_OF_ORDER);
                work.put(key, state);
                continue;
            }
            if (outcome == IngestRows.Raw.Outcome.INSERTED) {
                accepted++;
                if (decision.resetSignal() != null) {
                    series.addReset(tenant, state.seriesId(), sample.ts(), decision.resetSignal());
                }
                work.put(key, new SeriesState(state.seriesId(), sample.ts(), sample.value(), decision.tokenAfter(),
                        context.packageVersion(), interval));
            } else {
                work.put(key, state);
            }
        }
        if (observedToken != null) {
            markers.observe(tenant, context.placementId(), observedToken, now);
        }
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        Map<CollectorReason, Long> toWrite = new EnumMap<>(CollectorReason.class);
        counted.forEach((reason, n) -> toWrite.merge(reason, (long) n, Long::sum));
        pendingSnapshot.forEach((reason, n) -> toWrite.merge(reason, n, Long::sum));
        toWrite.forEach((reason, n) -> {
            if (n > 0) {
                counters.add(tenant, context.placementId(), reason, day, n);
            }
        });
        return new Applied(new IngestResult.Committed(accepted, counted), false);
    }

    private SeriesState load(IngestRows.SeriesKey key) {
        Optional<IngestRows.StoredSeries> stored = series.find(key);
        if (stored.isEmpty()) {
            return null;
        }
        long id = stored.get().seriesId();
        Optional<IngestRows.LastSample> last = raw.latest(key.tenantId(), id);
        Optional<IngestRows.Segment> segment = series.latestSegment(key.tenantId(), id);
        // After a boot the previous token is the placement's latest observation (M-Q9): the one fallback the design
        // accepts, because the per-series token died with the process.
        String token = last.isPresent() ? markers.token(key.tenantId(), key.placementId()).orElse(null) : null;
        return new SeriesState(id, last.map(IngestRows.LastSample::ts).orElse(null),
                last.map(IngestRows.LastSample::value).orElse(0.0), token,
                segment.map(IngestRows.Segment::packageVersion).orElse(null),
                segment.map(IngestRows.Segment::intervalSeconds).orElse(null));
    }

    /**
     * Derived from SM's own rows, never from the batch (the enum's javadoc): over SSH it is the container's start
     * time; over direct HTTP it is the package's declared restart signal for the metric's component, if any.
     */
    static RestartDetection detection(SourceDiscriminator discriminator, IngestRows.PlacementContext context,
            IngestRows.MetricDef definition) {
        if (discriminator != SourceDiscriminator.DP_POLL) {
            return RestartDetection.INFERRED;
        }
        if ("via-ssh".equals(context.acquisitionMode())) {
            return RestartDetection.DOCKER;
        }
        return context.restartSignalComponents().contains(definition.componentType()) ? RestartDetection.DECLARED
                : RestartDetection.INFERRED;
    }

    /** Whether {@code ts} lies in the UTC month of {@code now} or the one after it: the only months a runway can lack. */
    static boolean isCurrentOrNextMonth(Instant ts, Instant now) {
        java.time.YearMonth month = java.time.YearMonth.from(ts.atOffset(java.time.ZoneOffset.UTC));
        java.time.YearMonth current = java.time.YearMonth.from(now.atOffset(java.time.ZoneOffset.UTC));
        return month.equals(current) || month.equals(current.plusMonths(1));
    }

    /** A valid token is 1..256 characters; blank is none; longer is a malformed sample, not a truncated one. */
    private static String tokenOf(IngestSample sample) {
        String token = sample.restartToken();
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        return token;
    }

    private static void count(Map<CollectorReason, Integer> counted, CollectorReason reason) {
        counted.merge(reason, 1, Integer::sum);
    }

    // ---- counters that could not be written yet -------------------------------------------------------------------

    private synchronized void addPending(UUID tenant, UUID placement, CollectorReason reason, long n) {
        pendingCounts.merge(new CounterKey(tenant, placement, reason), n, Long::sum);
    }

    private synchronized Map<CollectorReason, Long> pendingFor(UUID tenant, UUID placement) {
        Map<CollectorReason, Long> out = new EnumMap<>(CollectorReason.class);
        pendingCounts.forEach((k, n) -> {
            if (k.tenantId().equals(tenant) && k.placementId().equals(placement)) {
                out.put(k.reason(), n);
            }
        });
        return out;
    }

    private synchronized void subtractPending(UUID tenant, UUID placement, Map<CollectorReason, Long> written) {
        written.forEach((reason, n) -> pendingCounts.computeIfPresent(new CounterKey(tenant, placement, reason),
                (k, current) -> current - n > 0 ? current - n : null));
    }

    /** The slow path: RUNWAY_DEGRADED outside the rolled-back batch's transaction; if even that fails it waits. */
    private void countRunwayDegraded(IngestBatch batch) {
        try {
            LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            transactions.executeWithoutResult(status -> counters.add(batch.organizationId(), batch.placementId(),
                    CollectorReason.RUNWAY_DEGRADED, day, 1));
        } catch (DataAccessException | TransactionException | PgConstraintViolation e) {
            addPending(batch.organizationId(), batch.placementId(), CollectorReason.RUNWAY_DEGRADED, 1);
        }
    }

    /** Writes counters that waited for the database, per placement, so one retired placement strands nothing else. */
    private void flushPendingCounts() {
        List<CounterKey> keys;
        synchronized (this) {
            keys = new ArrayList<>(pendingCounts.keySet());
        }
        LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        for (CounterKey key : keys) {
            Long n;
            synchronized (this) {
                n = pendingCounts.get(key);
            }
            if (n == null) {
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> counters.add(key.tenantId(), key.placementId(),
                        key.reason(), day, n));
                subtractPending(key.tenantId(), key.placementId(), Map.of(key.reason(), n));
            } catch (PgConstraintViolation e) {
                // The placement is gone (its foreign key refuses): there is nothing left to count against.
                subtractPending(key.tenantId(), key.placementId(), Map.of(key.reason(), n));
            } catch (DataAccessException | TransactionException e) {
                databaseRestingUntil = clock.instant().plus(properties.retryDelay());
                return;
            }
        }
    }

    // ---- observation ---------------------------------------------------------------------------------------------

    /**
     * The partition keeper could not extend the runway (plan 011 P11-3.2): every placement that sent a batch within
     * {@code active-window} counts one {@code RUNWAY_DEGRADED}, written with the next drain, so the operator sees the
     * degradation per placement before an insert fails. Never throws, and touches no database itself.
     */
    @Override
    public void runwayCouldNotBeExtended() {
        Instant cutoff = clock.instant().minus(properties.activeWindow());
        lastSeen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        lastSeen.keySet().forEach(key -> addPending(key.tenantId(), key.placementId(), CollectorReason.RUNWAY_DEGRADED, 1));
        runwayDegraded = true;
    }

    /** True after a batch hit an exhausted partition runway, until a batch next commits (the collector-health flag). */
    public boolean runwayDegraded() {
        return runwayDegraded;
    }

    /** Batches held now, across placements. */
    public int heldBatches() {
        return buffer.total();
    }

    @Override
    public void close() {
        writer.close();
    }

    private static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
