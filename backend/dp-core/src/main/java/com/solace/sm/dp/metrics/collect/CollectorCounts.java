package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The counts only the collector can make (plan 011 P11-4.3, M-Q21): a fetch that failed, a document that would not parse,
 * a deadline that passed. They never reach the ingest port (no sample exists for them), so they accumulate here, in
 * memory and bounded by (placements x reasons), and the loop writes them once per tick on ITS thread. That keeps every
 * worker thread off the database and holds the ingest writer's one-connection promise: a worker that fails counts a
 * number, it never opens a connection.
 *
 * <p>A write that cannot be made (the database is unreachable) leaves the count in place for the next tick; a count whose
 * placement is gone (its foreign key refuses) is dropped, as the ingest path drops the same.
 */
public final class CollectorCounts {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorCounts.class);

    private record Key(UUID tenant, UUID placement, CollectorReason reason) {
    }

    private final IngestRows.Counters counters;
    private final Clock clock;
    private final Map<Key, Long> pending = new HashMap<>();

    public CollectorCounts(IngestRows.Counters counters, Clock clock) {
        this.counters = Objects.requireNonNull(counters, "counters");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void add(UUID tenant, UUID placement, CollectorReason reason, long n) {
        if (n > 0) {
            pending.merge(new Key(tenant, placement, reason), n, Long::sum);
        }
    }

    /** What is waiting to be written for one placement and reason. */
    public synchronized long pending(UUID tenant, UUID placement, CollectorReason reason) {
        return pending.getOrDefault(new Key(tenant, placement, reason), 0L);
    }

    /** Writes everything waiting; returns quietly when the database cannot take it, keeping what it could not write. */
    public void flush() {
        List<Map.Entry<Key, Long>> taken;
        synchronized (this) {
            taken = new ArrayList<>(pending.entrySet());
            pending.clear();
        }
        LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        boolean databaseDown = false;
        for (Map.Entry<Key, Long> entry : taken) {
            Key key = entry.getKey();
            if (databaseDown) {
                add(key.tenant(), key.placement(), key.reason(), entry.getValue());
                continue;
            }
            try {
                counters.add(key.tenant(), key.placement(), key.reason(), day, entry.getValue());
            } catch (PgConstraintViolation e) {
                // the placement is gone: there is nothing left to count against
            } catch (RuntimeException e) {
                databaseDown = true;
                LOG.warn("collector counts could not be written ({}); they are kept for the next tick",
                        e.getClass().getSimpleName());
                add(key.tenant(), key.placement(), key.reason(), entry.getValue());
            }
        }
    }
}
