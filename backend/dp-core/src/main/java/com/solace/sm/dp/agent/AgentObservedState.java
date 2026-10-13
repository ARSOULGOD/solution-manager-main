package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The read side of the agent path's {@code status} (plan 015 T3.3, {@code AGW-D28} item 5): what the LAST ACCEPTED, TRUSTED report
 * of a placement said, with liveness read fresh from the database at the moment of the question.
 *
 * <p><b>The plain digest lives here and nowhere else.</b> {@link AgentObservation#reportedSha256()} is a plain SHA-256 of rendered
 * bytes: {@code I6} and {@code OR-32} forbid storing it, so it is held in this process's memory only, bounded, scoped by organization
 * and placement, and lost on a restart. After a restart {@link #latest} answers "no observation" (the adapter reads that as unknown,
 * never as healthy) until the agent's next report, and that report carries NO digest (only an operation-finished report does, once):
 * the adapter then answers {@code status} from the keyed hash of the newest acknowledged applied command at or below the reported
 * persisted generation (plan 015 W3 integration, finding 1), never from here. Health and the container start ARE persisted ({@code placement_health},
 * {@code placement_start_observation}) by the intake; they are not duplicated here.
 *
 * <p>An observation is remembered only after the report's transaction COMMITS, so a rolled-back report is never visible.
 */
public final class AgentObservedState implements AgentObservedStatePort {

    /** One observation per placement is kept; the oldest is evicted past this many (a host runs a handful of containers). */
    static final int MAX_REMEMBERED = 20_000;

    private record Key(UUID tenantId, UUID placementId) {
    }

    private record Remembered(long sequence, AgentHealthObservation health, String reportedSha256, Long persistedGeneration,
            Instant startedAt, Instant observedAt) {
    }

    private final JdbcAgentReportRepository reports;
    private final Map<Key, Remembered> latest = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Remembered> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    public AgentObservedState(JdbcAgentReportRepository reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    @Override
    public Optional<AgentObservation> latest(AgentPlacementRef placement) {
        Objects.requireNonNull(placement, "placement");
        Remembered remembered;
        synchronized (latest) {
            remembered = latest.get(new Key(placement.tenantId(), placement.placementId()));
        }
        if (remembered == null) {
            return Optional.empty();
        }
        boolean live = reports.live(placement.tenantId(), placement.placementId());
        return Optional.of(new AgentObservation(live, remembered.health(), remembered.reportedSha256(),
                remembered.persistedGeneration(), remembered.startedAt(), remembered.observedAt()));
    }

    /**
     * Remembers an observation once the surrounding transaction commits (at once when there is none). <b>Merged field by field</b>:
     * an item that does not carry a hash, a health, a generation or a start did not OBSERVE it, which is not the same as the host
     * having none (except that a NEW container start discards the health held for the previous one). The real agent's periodic report never carries {@code effective_hash} and an operation-finished report may carry
     * no health, so replacing the whole record would erase the hash the read-back waits for within one report interval. And an item
     * is applied only if its {@code sequence} is higher than the one held, so two commits whose after-commit callbacks interleave can
     * never leave the older one in place.
     */
    public void remember(UUID tenantId, UUID placementId, long sequence, AgentHealthObservation health, String reportedSha256,
            Long persistedGeneration, Instant startedAt, Instant observedAt) {
        Runnable put = () -> {
            synchronized (latest) {
                Key key = new Key(tenantId, placementId);
                Remembered held = latest.get(key);
                if (held != null && held.sequence() >= sequence) {
                    return;
                }
                // A health observed under ANOTHER container start is the previous container's verdict: an item that reports a
                // different start and no health of its own (the agent's post-restart inspect can fail) must not inherit it, or
                // the health gate passes on the old container's HEALTHY before the new one was ever probed (W3 integration, minor 4).
                boolean newStart = held != null && startedAt != null && !startedAt.equals(held.startedAt());
                latest.put(key, new Remembered(sequence,
                        health != null || held == null || newStart ? health : held.health(),
                        reportedSha256 != null || held == null ? reportedSha256 : held.reportedSha256(),
                        persistedGeneration != null || held == null ? persistedGeneration : held.persistedGeneration(),
                        startedAt != null || held == null ? startedAt : held.startedAt(), observedAt));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    put.run();
                }
            });
        } else {
            put.run();
        }
    }
}
