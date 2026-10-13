package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.meshbridge.ingest.BrokerMessageSink;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptions;
import com.solace.sm.dp.meshbridge.ingest.RawBrokerMessage;
import com.solace.sm.dp.metrics.collect.PushReconcile;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder.NumberRead;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan.EnvironmentPush;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan.TenantPush;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The broker push path (plan 011 P11-5.1; M-Q12, OR-106, OR-107): subscribes what the plan says, turns each event it is delivered
 * into at most one sample on one placement, and hands it to the ONE ingest seam ({@link TelemetryIngestPort}, discriminator
 * {@code BROKER_PUSH}, the same bounded buffer, caps and counters as path 1).
 *
 * <p><b>Three threads, one rule each.</b> The loop's thread calls {@link #reconcile} (a pure read of rows, then a hand-off to
 * the subscription manager, which never holds it), {@link #retainTenants} and {@link #flushCounts} (the only thread that opens a
 * database connection for a count). The broker SDK's receive thread calls {@link #accept}, which only queues and returns. One
 * worker thread drains the queue, decodes, attributes and ingests; it is the only writer of the cumulative counts, so they need no lock.
 *
 * <p><b>Nothing of a payload outlives the decode.</b> The queue holds bytes until the worker reads them. After attribution only a
 * metric name, a placement, a timestamp and a finite number remain; no body, topic, key value or adapter id is stored, logged,
 * counted by content or put in an exception message ({@code I6}). Every failure is a closed word and a count.
 *
 * <p><b>ts is the Data Plane's UTC receipt instant</b> (OQ-4 (a)): the event's own header timestamp is local time with no zone
 * (facts F14) and is never read. {@code bindingId} is the broker connection, {@code sourceKey} the placement (OQ-15 (a)).
 */
public final class PushIngestService implements BrokerMessageSink, PushReconcile, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PushIngestService.class);

    private record Queued(UUID tenant, UUID connection, RawBrokerMessage message) {
    }

    private record Routed(UUID tenant, PushRouting routing) {
    }

    private record CountKey(UUID tenant, UUID connection, CollectorReason reason) {
    }

    private final PushSubscriptionPlan planner;
    private final BrokerSubscriptions subscriptions;
    private final PushAttribution attribution;
    private final TelemetryIngestPort ingest;
    private final BrokerCounterRows counterRows;
    private final PushProperties properties;
    private final Clock clock;
    private final CountAccumulator counts = new CountAccumulator();
    private final BlockingQueue<Queued> queue;

    private final Map<BrokerSubscriptions.Key, Routed> routing = new ConcurrentHashMap<>();
    /** The loop thread's own: which connections each organization held after its last reconcile. */
    private final Map<UUID, Set<UUID>> heldByTenant = new HashMap<>();
    private final Map<CountKey, Long> pending = new HashMap<>();
    private final Map<UUID, Integer> refusedLast = new HashMap<>();

    private Thread worker;
    private volatile boolean running;

    public PushIngestService(PushSubscriptionPlan planner, BrokerSubscriptions subscriptions, PushAttribution attribution,
            TelemetryIngestPort ingest, BrokerCounterRows counterRows, PushProperties properties, Clock clock) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.attribution = Objects.requireNonNull(attribution, "attribution");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.counterRows = Objects.requireNonNull(counterRows, "counterRows");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    // ---- the loop's thread ------------------------------------------------------------------------------------------

    @Override
    public void reconcile(TenantId tenantId) {
        UUID tenant = tenantId.value();
        TenantPush plan = planner.build(tenant);
        Integer previous = refusedLast.put(tenant, plan.refused());
        if (plan.refused() > 0 && (previous == null || previous != plan.refused())) {
            // once per change, not every tick: a count only, never a value
            LOG.warn("push plan: {} definitions or configured keys were refused for an organization and are not used",
                    plan.refused());
        }
        Set<UUID> wanted = new HashSet<>();
        for (EnvironmentPush environment : plan.environments()) {
            Set<String> patterns = environment.routing().topicPatterns();
            if (environment.connection().isEmpty() || patterns.isEmpty()) {
                continue;   // nothing to subscribe on: the instance's push metrics are simply not collected
            }
            BrokerConnectionRecord connection = environment.connection().get();
            UUID id = connection.brokerConnectionId();
            routing.put(new BrokerSubscriptions.Key(tenant, id), new Routed(tenant, environment.routing()));
            subscriptions.reconcile(tenant, id, connection.endpoint(), connection.credentialRef(), patterns, this);
            wanted.add(id);
        }
        Set<UUID> before = heldByTenant.getOrDefault(tenant, Set.of());
        for (UUID gone : before) {
            if (!wanted.contains(gone)) {
                release(tenant, gone);
            }
        }
        if (wanted.isEmpty()) {
            heldByTenant.remove(tenant);
        } else {
            heldByTenant.put(tenant, wanted);
        }
        counts.retainPlacements(allPlacements());
    }

    @Override
    public void retainTenants(Collection<TenantId> active) {
        Set<UUID> keep = new HashSet<>();
        active.forEach(t -> keep.add(t.value()));
        for (UUID tenant : Set.copyOf(heldByTenant.keySet())) {
            if (!keep.contains(tenant)) {
                heldByTenant.remove(tenant).forEach(connection -> release(tenant, connection));
            }
        }
        counts.retainPlacements(allPlacements());
    }

    private void release(UUID tenant, UUID connection) {
        routing.remove(new BrokerSubscriptions.Key(tenant, connection));
        subscriptions.release(tenant, connection);
    }

    private Set<UUID> allPlacements() {
        Set<UUID> placements = new HashSet<>();
        routing.values().forEach(r -> placements.addAll(r.routing().placements()));
        return placements;
    }

    @Override
    public void flushCounts() {
        List<Map.Entry<CountKey, Long>> taken;
        synchronized (pending) {
            taken = new ArrayList<>(pending.entrySet());
            pending.clear();
        }
        LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        boolean databaseDown = false;
        for (Map.Entry<CountKey, Long> entry : taken) {
            CountKey key = entry.getKey();
            if (databaseDown) {
                count(key.tenant(), key.connection(), key.reason(), entry.getValue());
                continue;
            }
            try {
                counterRows.add(key.tenant(), key.connection(), key.reason(), day, entry.getValue());
            } catch (PgConstraintViolation e) {
                // the connection is gone: there is nothing left to count against
            } catch (RuntimeException e) {
                databaseDown = true;
                LOG.warn("push counts could not be written ({}); they are kept for the next tick", e.getClass().getSimpleName());
                count(key.tenant(), key.connection(), key.reason(), entry.getValue());
            }
        }
    }

    // ---- the broker SDK's receive thread ----------------------------------------------------------------------------

    @Override
    public void accept(UUID tenantId, UUID brokerConnectionId, RawBrokerMessage message) {
        Routed routed = routing.get(new BrokerSubscriptions.Key(tenantId, brokerConnectionId));
        if (routed == null || !routed.tenant().equals(tenantId)) {
            return;   // a session that was just released, or a tenant that does not own this connection: nothing is attributed (I5)
        }
        if (message.body().length > properties.maxBodyBytes()) {
            // refused unread, and before it is queued: the queue's memory is bounded by capacity x this bound
            count(tenantId, brokerConnectionId, CollectorReason.PARSE_FAILED, 1);
            return;
        }
        if (!queue.offer(new Queued(tenantId, brokerConnectionId, message))) {
            count(tenantId, brokerConnectionId, CollectorReason.BUFFER_DROPPED, 1);
        }
    }

    // ---- the worker -------------------------------------------------------------------------------------------------

    /** Starts the worker thread. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::run, "sm-push-ingest");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        while (running) {
            try {
                Queued first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Queued> taken = new ArrayList<>();
                taken.add(first);
                queue.drainTo(taken, properties.batchSize() - 1);
                process(taken);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOG.warn("push worker: a pass failed ({})", t.getClass().getSimpleName());   // never the message: it may quote a payload
            }
        }
    }

    /**
     * Processes what is queued on the CALLING thread, one batch at a time; returns how many events. Synchronous, so a test
     * (or an operator tool) can step the service without a worker thread; the worker itself runs the same {@code process}.
     */
    public int drainNow() {
        int total = 0;
        List<Queued> taken = new ArrayList<>();
        while (queue.drainTo(taken, properties.batchSize()) > 0) {
            total += taken.size();
            process(taken);
            taken = new ArrayList<>();
        }
        return total;
    }

    private void process(List<Queued> events) {
        Map<List<UUID>, List<IngestSample>> byPlacement = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (Queued event : events) {
            Routed routed = routing.get(new BrokerSubscriptions.Key(event.tenant(), event.connection()));
            if (routed == null || !routed.tenant().equals(event.tenant())) {
                continue;
            }
            PushAttribution.Attributed attributed = attribution.attribute(routed.routing(), event.message().topic(),
                    event.message().body());
            for (CollectorReason reason : attributed.brokerDrops()) {
                count(event.tenant(), event.connection(), reason, 1);
            }
            for (PushAttribution.Hit hit : attributed.hits()) {
                double value;
                if (hit.metric().extraction() == PushMetric.Extraction.COUNT) {
                    value = counts.next(hit.placementId(), hit.metric().name());
                } else if (hit.read() instanceof NumberRead.Finite finite) {
                    value = finite.value();
                } else {
                    value = Double.NaN;   // the ingest path counts parse_failed against the placement and stores nothing
                }
                byPlacement.computeIfAbsent(List.of(event.tenant(), event.connection(), hit.placementId()),
                        k -> new ArrayList<>())
                        .add(new IngestSample(hit.metric().name(), event.message().receivedAt(), value, now));
            }
        }
        byPlacement.forEach((key, samples) -> {
            UUID tenant = key.get(0);
            UUID connection = key.get(1);
            UUID placement = key.get(2);
            IngestResult result = ingest.ingest(new IngestBatch(tenant, connection, placement, placement.toString(),
                    SourceDiscriminator.BROKER_PUSH, samples, List.of()));
            if (result instanceof IngestResult.Rejected rejected) {
                LOG.debug("push ingest: a batch was rejected ({})", rejected.code());
            }
        });
    }

    private void count(UUID tenant, UUID connection, CollectorReason reason, long n) {
        synchronized (pending) {
            pending.merge(new CountKey(tenant, connection, reason), n, Long::sum);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
