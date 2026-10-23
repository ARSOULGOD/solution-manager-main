package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogService} over a {@code host-local} agent's collected log (plan 016 task 8, spec 4.1). The page is asked of the
 * gateway's {@link AgentLogPort}; no host is reached by the data plane.
 *
 * <ul>
 *   <li><b>Tenant-bound (I5).</b> The placement is resolved from the DP's own tables by {@code (organization, instance,
 *       placement)} together ({@link PlacementTransports}); one that is not this instance's, in this organization, or whose
 *       target is not an agent, is a declared unresolvable page and the gateway is never asked about it. Nothing the
 *       client supplied but the placement id selects a session.</li>
 *   <li><b>Declared, never thrown.</b> A missing session, a refusal, a busy agent or a missed deadline is a page with no
 *       lines and a fixed reason. The reason is chosen from the port's closed vocabulary and quotes nothing (I6): no
 *       host, container, path or message.</li>
 *   <li><b>Bounded.</b> At most {@code maxConcurrentReads} requests are in flight; one that cannot get a slot within
 *       {@code acquireTimeout} is a declared busy page. The wait for the agent is the port's {@code deadline}, which the
 *       gateway arms after the request is sent, plus a short grace; this class never waits longer than that.</li>
 *   <li><b>The level is derived here.</b> Log level is not on the wire; it is read from the text exactly as the SSH path
 *       reads it ({@link DockerLogLineParser#levelOf}).</li>
 * </ul>
 */
public final class AgentLogService implements LogService {

    private static final Logger log = LoggerFactory.getLogger(AgentLogService.class);

    static final String AT_CAPACITY = "log reads are at capacity; try again shortly";
    static final String UNRESOLVABLE = "the placement's logs are not available";
    static final String NO_SESSION = "the agent is not connected";
    static final String NO_LOGS_CAPABILITY = "the agent does not support log reads";
    static final String NOT_COLLECTING = "log collection is not enabled for this placement";
    static final String CONTAINER_MISSING = "the agent could not find the placement's container";
    static final String AGENT_BUSY = "the agent is busy; try again shortly";
    static final String NOT_IN_LEASE = "the agent does not currently serve this placement";
    static final String TIMEOUT = "the agent did not answer in time";
    static final String SESSION_LOST = "the agent's connection was lost";
    static final String UNSERVED = "the agent could not serve the logs";

    /** Allowed past the port's own deadline before this class stops waiting (the port completes its futures itself). */
    private static final Duration GRACE = Duration.ofSeconds(1);

    private final PlacementTransports transports;
    private final AgentLogPort port;
    private final Duration deadline;
    private final Semaphore slots;
    private final Duration acquireTimeout;

    public AgentLogService(PlacementTransports transports, AgentLogPort port, Duration deadline, int maxConcurrentReads,
            Duration acquireTimeout) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.port = Objects.requireNonNull(port, "port");
        if (Objects.requireNonNull(deadline, "deadline").isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("the agent request deadline must be positive");
        }
        this.deadline = deadline;
        if (maxConcurrentReads < 1) {
            throw new IllegalArgumentException("at least one concurrent log read is required");
        }
        if (Objects.requireNonNull(acquireTimeout, "acquireTimeout").isNegative()) {
            throw new IllegalArgumentException("the slot wait cannot be negative");
        }
        this.slots = new Semaphore(maxConcurrentReads);
        this.acquireTimeout = acquireTimeout;
    }

    @Override
    public LogPageView read(UUID organizationId, UUID instanceId, UUID placementId, int limit, Instant after,
            Instant before) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (after != null && before != null && !after.isBefore(before)) {
            throw new IllegalArgumentException("after must be before before");
        }
        if (!acquire()) {
            return unavailable(AT_CAPACITY);
        }
        try {
            boolean agentPlacement = transports.transportOf(new PlacementRef(new TenantId(organizationId),
                    new InstanceId(instanceId), new PlacementId(placementId)))
                    .filter(PlacementTransports.AGENT::equals).isPresent();
            if (!agentPlacement) {
                return unavailable(UNRESOLVABLE);
            }
            Optional<AgentSessionRef> session = port.sessionFor(organizationId, placementId);
            if (session.isEmpty()) {
                return unavailable(NO_SESSION);
            }
            CompletableFuture<AgentLogPage> future =
                    port.requestPage(session.get(), placementId, limit, after, before, deadline);
            AgentLogPage page = future.get(deadline.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
            return page.unavailableReason().map(code -> unavailable(reasonText(code)))
                    .orElseGet(() -> new LogPageView(withLevels(page.lines()), Optional.empty()));
        } catch (TimeoutException e) {
            return unavailable(TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(TIMEOUT);
        } catch (ExecutionException | RuntimeException e) {
            // the port's futures never fail; whatever this is, say nothing of it to the caller (I6)
            log.warn("an agent log read for placement {} failed: {}", placementId, e.getClass().getSimpleName());
            return unavailable(UNSERVED);
        } finally {
            slots.release();
        }
    }

    private static List<LogLineView> withLevels(List<LogLineView> lines) {
        return lines.stream()
                .map(l -> new LogLineView(l.at(), DockerLogLineParser.levelOf(l.message()), l.message()))
                .toList();
    }

    /** True when a slot was taken (and must be released). */
    private boolean acquire() {
        try {
            return slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static LogPageView unavailable(String reason) {
        return new LogPageView(List.of(), Optional.of(reason));
    }

    /** The port of a context with no gateway: no agent is ever connected. */
    static AgentLogPort absentPort() {
        return new AgentLogPort() {
            @Override
            public Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId) {
                return Optional.empty();
            }

            @Override
            public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit,
                    Instant after, Instant before, Duration deadline) {
                return CompletableFuture.completedFuture(AgentLogPage.unavailable("NO_SESSION"));
            }

            @Override
            public com.solace.sm.dp.api.agw.LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq,
                    Duration deadline, long maxBytes) {
                throw new UnsupportedOperationException("no agent gateway in this context");
            }

            @Override
            public void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes) {
                // no session, nothing to send
            }
        };
    }

    /** The port's reason code to fixed text; a code this build does not know is {@link #UNSERVED}, never echoed. */
    static String reasonText(String code) {
        return switch (code) {
            case "NO_SESSION" -> NO_SESSION;
            case "NO_LOGS_CAPABILITY" -> NO_LOGS_CAPABILITY;
            case "NOT_COLLECTING" -> NOT_COLLECTING;
            case "CONTAINER_MISSING" -> CONTAINER_MISSING;
            case "BUSY" -> AGENT_BUSY;
            case "PLACEMENT_NOT_IN_LEASE" -> NOT_IN_LEASE;
            case "TIMEOUT" -> TIMEOUT;
            case "SESSION_LOST" -> SESSION_LOST;
            default -> UNSERVED;
        };
    }
}
