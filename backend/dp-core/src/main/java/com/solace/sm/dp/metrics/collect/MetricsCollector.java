package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Metric;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Source;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.FetchResult;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRead;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts what is due and reads it (plan 011 P11-4.3, M-Q3 {@code OR-72}): the executing half of the collector, over
 * {@link SourceScheduler}'s decisions. {@link #dispatch} asks the scheduler what is due, hands each unit to the bounded
 * worker pool, and counts the deadlines that passed; a worker fetches the unit's documents through the INSTANCE's
 * fetcher, reads one restart token, and puts the samples through the one ingest port.
 *
 * <p><b>Rules, each with a test</b>
 * <ul>
 *   <li>The fetcher is the instance's mode and nothing else: {@code direct-http} reads over HTTP, {@code via-ssh} over
 *       SSH. A failed read is a failed read; it is never retried through the other (M-Q2: no automatic fallback).</li>
 *   <li>A failure is a <b>gap and a count</b> ({@code SCRAPE_FAILED}, {@code PARSE_FAILED}, {@code DEADLINE_MISSED}),
 *       never a stored zero: nothing is ingested for a read that gave no number.</li>
 *   <li>{@code onUnavailable: degrade} and {@code error} behave the same in v1 (OQ-13 (a)): a gap plus
 *       {@code SCRAPE_FAILED}. The catalog reports the declared value.</li>
 *   <li>One restart token per placement per cycle (OQ-1 (a), M-Q9): over SSH the container's own, in direct mode each
 *       component's declared signal. A token that could not be read is passed as none, and the ingest rules decide
 *       what that means for a declared or docker source (never an inferred reset).</li>
 *   <li>It never reads {@code placement_health}: collection and health are independent (B/D2, UI-D8), and it never logs
 *       a body, a host or a value, only a classifier.</li>
 * </ul>
 */
public final class MetricsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

    private final SourceScheduler scheduler;
    private final MetricFetcher direct;
    private final MetricFetcher viaSsh;
    private final TelemetryIngestPort ingest;
    private final CollectorCounts counts;
    private final Executor workers;
    private final Clock clock;
    private final java.util.Set<AcquisitionMode> unavailableLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param viaSsh the SSH fetcher, or {@code null} when none is wired ({@code sm.dp.health.enabled=false} withdraws the
     *               session pool it shares): an SSH instance is then counted {@code SCRAPE_FAILED} and never read
     *               through the direct fetcher
     * @param workers the bounded pool a run executes on; production's is a fixed pool of platform threads
     */
    public MetricsCollector(SourceScheduler scheduler, MetricFetcher direct, MetricFetcher viaSsh,
            TelemetryIngestPort ingest, CollectorCounts counts, Executor workers, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.direct = Objects.requireNonNull(direct, "direct");
        this.viaSsh = viaSsh;
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.counts = Objects.requireNonNull(counts, "counts");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts every due unit, and counts every deadline that passed with the unit still running or still unserved. */
    public void dispatch() {
        SourceScheduler.Round round = scheduler.due(clock.instant());
        for (Unit missed : round.missed()) {
            counts.add(missed.placement().tenant().value(), missed.placementId(), CollectorReason.DEADLINE_MISSED, 1);
        }
        for (Unit unit : round.start()) {
            try {
                workers.execute(() -> run(unit));
            } catch (RejectedExecutionException e) {
                // the pool is shut down or saturated beyond its queue: a gap, never a silent loss
                scheduler.finished(unit, clock.instant(), false);
                counts.add(unit.placement().tenant().value(), unit.placementId(), CollectorReason.DEADLINE_MISSED, 1);
            }
        }
    }

    /** Writes the counts waiting (the loop calls this each tick, on its own thread), and lets the SSH fetcher release idle sessions. */
    public void flushCounts() {
        counts.flush();
        if (viaSsh != null) {
            try {
                viaSsh.housekeeping();
            } catch (RuntimeException e) {
                LOG.warn("metrics collection: the SSH fetcher's housekeeping failed ({})", e.getClass().getSimpleName());
            }
        }
    }

    // ---- one run ------------------------------------------------------------------------------------------------

    private void run(Unit unit) {
        boolean hostFailed = false;
        try {
            hostFailed = collect(unit);
        } catch (RuntimeException e) {
            // classifier only: a driver or transport message can carry a host, a path or a role
            LOG.warn("metrics collection: one placement could not be read ({})", e.getClass().getSimpleName());
            counts.add(unit.placement().tenant().value(), unit.placementId(), CollectorReason.SCRAPE_FAILED, 1);
        } finally {
            scheduler.finished(unit, clock.instant(), hostFailed);
        }
    }

    /** @return true when the host gave no answer at all (every document a transport failure) */
    private boolean collect(Unit unit) {
        UUID tenant = unit.placement().tenant().value();
        UUID placement = unit.placementId();
        MetricFetcher fetcher = unit.mode() == AcquisitionMode.DIRECT_HTTP ? direct : viaSsh;
        if (fetcher == null) {
            // W3-10: every document is still counted, but the explanation is logged once per mode, not once per run of every
            // unit (200 placements at a 30 s interval would otherwise write 400 identical lines a minute)
            if (unavailableLogged.add(unit.mode())) {
                LOG.warn("metrics collection: {} document(s) not read, no {} fetcher is wired (classifier fetcher.unavailable);"
                        + " logged once, every later document is counted scrape_failed", unit.sources().size(), unit.mode().wire());
            }
            counts.add(tenant, placement, CollectorReason.SCRAPE_FAILED, unit.sources().size());
            return true;
        }
        Tokens tokens = readTokens(unit, fetcher);
        List<FetchRequest> requests = unit.sources().stream().map(Source::request).toList();
        Map<FetchRequest, FetchResult> answers = fetcher.fetchAll(requests);

        List<IngestSample> samples = new ArrayList<>();
        int transport = 0;
        Map<String, Integer> failures = new java.util.TreeMap<>();
        int deferred = 0;
        for (Source source : unit.sources()) {
            FetchResult result = answers.get(source.request());
            if (result instanceof FetchResult.Ok ok) {
                for (Metric metric : source.metrics()) {
                    JsonPointerExtractor.Extraction value = JsonPointerExtractor.number(ok.body(), metric.pointer());
                    if (value instanceof JsonPointerExtractor.Extraction.Value v) {
                        samples.add(new IngestSample(metric.name(), ok.ts(), v.value(), ok.receivedAt(),
                                tokens.of(metric.componentType())));
                    } else {
                        counts.add(tenant, placement, CollectorReason.PARSE_FAILED, 1);
                    }
                }
            } else if (result instanceof FetchResult.Deferred) {
                // W3-02: not attempted (the pool's load, not the host's answer): a missed deadline, never a failed scrape
                counts.add(tenant, placement, CollectorReason.DEADLINE_MISSED, 1);
                deferred++;
            } else {
                counts.add(tenant, placement, CollectorReason.SCRAPE_FAILED, 1);
                failures.merge(classifierOf(result), 1, Integer::sum);
                if (result == null || result instanceof FetchResult.Transport) {
                    transport++;
                }
            }
        }
        if (!failures.isEmpty()) {
            // classifiers only (a fixed vocabulary, or an HTTP status): never a host, a path, a body or a message
            LOG.warn("metrics collection: {} of {} document(s) could not be read ({}), restart token {}",
                    failures.values().stream().mapToInt(Integer::intValue).sum(), unit.sources().size(), failures,
                    tokens.classifier());
        }
        if (deferred > 0) {
            LOG.info("metrics collection: {} of {} document(s) deferred, the SSH pool had no free slot",
                    deferred, unit.sources().size());
        }
        if (!samples.isEmpty()) {
            IngestResult outcome = ingest.ingest(IngestBatch.polled(tenant, placement, samples));
            if (outcome instanceof IngestResult.Rejected rejected) {
                LOG.debug("metrics collection: a batch was refused ({})", rejected.code());
            }
        }
        return !unit.sources().isEmpty() && transport == unit.sources().size();
    }

    /** One start-time read per placement per cycle (direct mode: one per component that declares a signal). */
    private Tokens readTokens(Unit unit, MetricFetcher fetcher) {
        if (unit.mode() == AcquisitionMode.VIA_SSH) {
            return Tokens.one(fetcher.restartToken(new RestartRequest(unit.placement(), unit.targetId(), null)));
        }
        Map<String, String> byComponent = new HashMap<>();
        java.util.Set<String> classifiers = new java.util.TreeSet<>();
        unit.signals().forEach((component, signal) -> {
            RestartRead read = fetcher.restartToken(new RestartRequest(unit.placement(), unit.targetId(), signal));
            byComponent.put(component, valueOf(read));
            classifiers.add(classifierOf(read));
        });
        return Tokens.perComponent(byComponent, classifiers.isEmpty() ? "not.declared" : classifiers.toString());
    }

    /** A fixed-vocabulary token for a result that is not an answer; an HTTP status is an answer's own number. */
    private static String classifierOf(FetchResult result) {
        if (result instanceof FetchResult.Transport transport) {
            return transport.classifier();
        }
        if (result instanceof FetchResult.Http http) {
            return "http." + http.status();
        }
        return "fetch.missing";
    }

    private static String classifierOf(RestartRead read) {
        if (read instanceof RestartRead.Failed failed) {
            return failed.classifier();
        }
        return read instanceof RestartRead.Token ? "ok" : "not.declared";
    }

    private static String valueOf(RestartRead read) {
        return read instanceof RestartRead.Token token ? token.value() : null;
    }

    private record Tokens(String single, Map<String, String> byComponent, String classifier) {
        static Tokens one(RestartRead read) {
            return new Tokens(valueOf(read), Map.of(), classifierOf(read));
        }

        static Tokens perComponent(Map<String, String> byComponent, String classifier) {
            return new Tokens(null, byComponent, classifier);
        }

        String of(String component) {
            return single != null ? single : byComponent.get(component);
        }
    }
}
