package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import com.solace.sm.dp.health.AgentReportStaleness;
import com.solace.sm.dp.health.HealthRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code AGW-D28} item 1: "no report newer than the staleness window is {@code unreachable}". An agent that dies, or a host that
 * loses power, stops reporting, and nothing else writes an agent placement's health (the dp-remote poller skips it), so without this
 * the last verdict, for example {@code GREEN}, would stand for ever on {@code getHealth} and the dashboards.
 *
 * <p><b>A scheduled job, not a read ({@code OR-140}, plan 015 T4.6).</b> {@link AgentStalenessJob} marks every organization's silent
 * agent placements on a schedule and fires {@code instance.health} through {@link HealthRecorder}, whether or not anyone is
 * reading; {@code getHealth} writes nothing. This class is the per-instance step the job calls, and the one place the window is
 * applied. The cross-organization read the job needs is {@code OR-140}'s, for that job only.
 *
 * <p>The verdict is {@code unreachable} (the vocabulary's "could not be reached", {@code OR-39}: unknown, not down). Only a
 * placement that declares a probe is touched ({@code OR-101}: {@code NOT_APPLICABLE} stays), only one the agent HAS reported (never
 * reported is {@code bootstrap}, not {@code unreachable}), and a placement already {@code unreachable} is not rewritten (write on
 * change). A fresh trusted report overwrites the verdict through the intake. The last report's age is the watermark's
 * {@code updated_at}, which an untrusted (lease-expired) report also moves: the agent is then alive and reporting, only
 * unauthorised, which is not "unreachable".
 */
public final class AgentHealthStaleness implements AgentReportStaleness {

    /** Ten of the gateway's thirty-second report intervals ({@code SessionAccept.limits.report_interval}). */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    private final JdbcAgentReportRepository reports;
    private final HealthRecorder recorder;
    private final Clock clock;
    private final Duration window;

    public AgentHealthStaleness(JdbcAgentReportRepository reports, HealthRecorder recorder, Clock clock, Duration window) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("sm.dp.agent.report-staleness must be positive");
        }
    }

    /** The instant before which a report makes a placement stale, on the data plane's clock: now minus the window. */
    public Instant cutoff() {
        return clock.instant().minus(window);
    }

    @Override
    public void markStale(UUID organizationId, UUID instanceId) {
        Instant cutoff = cutoff();
        for (UUID placement : reports.staleProbedPlacements(organizationId, instanceId, cutoff)) {
            // Re-read the watermark just before the write: a report that committed since the listing has already written a fresh
            // verdict, which this must not overwrite. The residual (a report landing between this read and the record) is one
            // statement wide and heals with the next report, at most one report interval later.
            if (reports.stillStale(organizationId, placement, cutoff)) {
                recorder.record(organizationId, instanceId, placement, HealthState.UNREACHABLE);
            }
        }
    }
}
