package com.solace.sm.dp.configview;

import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.api.configview.ConfigViewResult;
import com.solace.sm.dp.api.configview.ConfigViewResult.Reason;
import com.solace.sm.dp.api.configview.ConfigViewService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.inventory.PlacementRows;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ConfigViewService} over a {@code host-local} agent (plan 017 task 5). The view is asked of the gateway's
 * {@link AgentConfigViewPort}; no host is reached by the data plane.
 *
 * <ul>
 *   <li><b>Tenant-bound (I5).</b> Resolved exactly as {@code AgentLogService} resolves a placement: {@link PlacementRows#findLive}
 *       by {@code (organization, instance, placement)} (a retired, unknown, other-instance or other-organization placement is
 *       {@code NOT_FOUND}), then {@link PlacementTransports#transportOf} for the agent-vs-SSH check. The agent id is the one the
 *       placement's TARGET names in the DP's own tables ({@link AgentPlacementCatalog#resolve}, the apply path's lookup, the same
 *       bean as the transports); the gateway's lease-based {@code AgentLogPort.sessionFor} is not used because it answers only for
 *       sessions with the LOGS capability. Nothing the client supplied but the three ids selects anything.</li>
 *   <li><b>Re-checked (Review Focus 4).</b> Every path of an OK reply must match the allow list that was SENT, with the agent's
 *       own dumb match ({@link #allowed}): exact, or {@code prefix.*} for the strict subtree. One stray path, a duplicate path,
 *       more than {@link #MAX_ENTRIES} entries, more UTF-8 bytes of path plus value than the {@code maxBytes} sent, a negative
 *       hidden count or an OK carrying a reason discards the whole reply: the caller gets the fixed {@code BAD_GATEWAY} and
 *       nothing of the agent's reply is forwarded.</li>
 *   <li><b>Bounded.</b> At most {@code maxConcurrent} views are in flight; one more is {@code BUSY} at once, never queued. The
 *       port blocks, so it runs on this class's own pool (at most {@code maxConcurrent} threads); the caller waits at most
 *       {@code timeout} plus {@link #GRACE}, then abandons the call with an interrupt (the gateway answers an interrupted read
 *       {@code UNAVAILABLE} and frees its per-agent slot) and returns {@code AGENT_UNAVAILABLE}.</li>
 *   <li><b>Silent (I6).</b> No key, value or allow pattern is logged or put in an exception message; a discarded reply logs only
 *       the placement id.</li>
 * </ul>
 */
public final class AgentConfigViewService implements ConfigViewService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigViewService.class);

    /** The most entries an OK reply may carry: the agent's own key limit ({@code configViewMaxKeys} in the go-agent). */
    static final int MAX_ENTRIES = 2000;
    /** The most allow-list entries the data plane will send. */
    static final int MAX_ALLOW_ENTRIES = 64;
    /** The longest allow-list entry. */
    static final int MAX_PATTERN_LENGTH = 256;
    /** The bounds of {@code maxBytes}: at least 1 KiB, at most 4 MiB (well under the gateway's message limit). */
    static final int MIN_MAX_BYTES = 1024;
    static final int MAX_MAX_BYTES = 4 * 1024 * 1024;
    /** The longest the caller may be told to wait. */
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(1);
    /** The most views in flight that may be configured. */
    static final int MAX_CONCURRENT = 64;
    /** Allowed past the timeout before the call is abandoned (the gateway completes its own reads at its deadline). */
    static final Duration GRACE = Duration.ofSeconds(1);

    private final PlacementRows placements;
    private final PlacementTransports transports;
    private final AgentPlacementCatalog agents;
    private final AgentConfigViewPort port;
    private final List<String> allow;
    private final Duration timeout;
    private final int maxBytes;
    private final Semaphore slots;
    private final ThreadPoolExecutor pool;

    public AgentConfigViewService(PlacementRows placements, PlacementTransports transports, AgentPlacementCatalog agents,
            AgentConfigViewPort port, List<String> allow, Duration timeout, int maxBytes, int maxConcurrent) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.port = Objects.requireNonNull(port, "port");
        this.allow = validAllowList(allow);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("sm.dp.config-view.timeout must be positive and at most " + MAX_TIMEOUT);
        }
        this.timeout = timeout;
        if (maxBytes < MIN_MAX_BYTES || maxBytes > MAX_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "sm.dp.config-view.max-bytes must be between " + MIN_MAX_BYTES + " and " + MAX_MAX_BYTES);
        }
        this.maxBytes = maxBytes;
        if (maxConcurrent < 1 || maxConcurrent > MAX_CONCURRENT) {
            throw new IllegalArgumentException("concurrent config views must be between 1 and " + MAX_CONCURRENT);
        }
        this.slots = new Semaphore(maxConcurrent);
        AtomicInteger threads = new AtomicInteger();
        // Admission is the semaphore, released only when the port call returns, so the pool never holds more than maxConcurrent
        // tasks: the queue is a hand-off for the instant between a task's release and its thread becoming idle, never a backlog.
        this.pool = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "sm-config-view-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.pool.allowCoreThreadTimeOut(true);
    }

    /**
     * The allow list, checked so that the agent's dumb match cannot be ambiguous. An entry is one or more segments joined by
     * {@code .}, each segment made only of ASCII letters, digits, {@code -} and {@code _}, optionally followed by a final
     * {@code .*}. Rejected, so: an empty list, more than {@link #MAX_ALLOW_ENTRIES} entries, a null, blank or duplicate entry, one
     * longer than {@link #MAX_PATTERN_LENGTH}, whitespace or control characters, non-ASCII, a {@code *} anywhere but a final
     * {@code .*} (so no bare {@code *}, {@code .*}, {@code a*} or {@code a.*.b}), an empty segment ({@code .a}, {@code a.},
     * {@code a..b}), and the list-index brackets {@code [} {@code ]} (a list is reached through its parent's {@code prefix.*}).
     * Messages name the entry's position only, never its text.
     */
    static List<String> validAllowList(List<String> allow) {
        Objects.requireNonNull(allow, "sm.dp.config-view.allow");
        if (allow.isEmpty() || allow.size() > MAX_ALLOW_ENTRIES) {
            throw new IllegalArgumentException("sm.dp.config-view.allow must have between 1 and " + MAX_ALLOW_ENTRIES + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < allow.size(); i++) {
            String entry = Objects.requireNonNull(allow.get(i), "sm.dp.config-view.allow entry");
            if (!wellFormed(entry)) {
                throw new IllegalArgumentException("sm.dp.config-view.allow entry " + (i + 1)
                        + " is not a dotted path of [A-Za-z0-9_-] segments, optionally ending in .*");
            }
            if (!seen.add(entry)) {
                throw new IllegalArgumentException("sm.dp.config-view.allow entry " + (i + 1) + " is a duplicate");
            }
        }
        return List.copyOf(allow);
    }

    private static boolean wellFormed(String entry) {
        if (entry.isEmpty() || entry.length() > MAX_PATTERN_LENGTH) {
            return false;
        }
        String path = entry.endsWith(".*") ? entry.substring(0, entry.length() - 2) : entry;
        if (path.isEmpty()) {
            return false;
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return false;
            }
            for (int c = 0; c < segment.length(); c++) {
                char ch = segment.charAt(c);
                boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-'
                        || ch == '_';
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The agent's match, verbatim: {@code prefix.*} matches any path starting {@code prefix.}; any other entry matches itself. */
    static boolean allowed(List<String> allow, String path) {
        for (String a : allow) {
            if (a.endsWith(".*")) {
                if (path.startsWith(a.substring(0, a.length() - 1))) {
                    return true;
                }
            } else if (a.equals(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ConfigViewResult view(UUID organizationId, UUID instanceId, UUID placementId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        // a retired placement is refused exactly as the log read refuses it (findLive excludes it)
        if (placements.findLive(organizationId, instanceId, placementId).isEmpty()) {
            return ConfigViewResult.refused(Reason.NOT_FOUND);
        }
        PlacementRef ref = new PlacementRef(new TenantId(organizationId), new InstanceId(instanceId), new PlacementId(placementId));
        Optional<String> transport = transports.transportOf(ref);
        if (transport.isEmpty()) {
            return ConfigViewResult.refused(Reason.NOT_FOUND);
        }
        if (!PlacementTransports.AGENT.equals(transport.get())) {
            return ConfigViewResult.refused(Reason.NOT_AN_AGENT_PLACEMENT);
        }
        Optional<AgentPlacementCatalog.AgentPlacement> agent = agents.resolve(ref);
        if (agent.isEmpty()) {
            return ConfigViewResult.refused(Reason.AGENT_UNAVAILABLE);       // the target has no live agent bound to it
        }
        String agentId = agent.get().agentId().toString();
        if (!slots.tryAcquire()) {
            return ConfigViewResult.refused(Reason.BUSY);
        }
        Future<AgentConfigView> call;
        try {
            call = pool.submit(() -> {
                try {
                    return port.read(organizationId.toString(), agentId, placementId.toString(), allow, maxBytes);
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            slots.release();
            return ConfigViewResult.refused(Reason.AGENT_UNAVAILABLE);      // the service is closed (shutdown)
        }
        AgentConfigView reply;
        try {
            reply = call.get(timeout.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            return ConfigViewResult.refused(Reason.AGENT_UNAVAILABLE);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return ConfigViewResult.refused(Reason.AGENT_UNAVAILABLE);
        } catch (ExecutionException e) {
            // the port does not throw for a refusal; whatever this is, say nothing of it (I6)
            log.warn("a config view for placement {} failed: {}", placementId, e.getCause() == null ? "unknown"
                    : e.getCause().getClass().getSimpleName());
            return ConfigViewResult.refused(Reason.BAD_GATEWAY);
        }
        return map(reply, placementId);
    }

    private ConfigViewResult map(AgentConfigView reply, UUID placementId) {
        if (reply == null) {
            log.warn("a config view for placement {} had no answer; discarded", placementId);
            return ConfigViewResult.refused(Reason.BAD_GATEWAY);
        }
        return switch (reply.status()) {
            case OK -> recheck(reply, placementId);
            case BUSY -> ConfigViewResult.refused(Reason.BUSY);
            case UNAVAILABLE -> ConfigViewResult.refused(reply.reason() == null ? Reason.AGENT_UNAVAILABLE : Reason.BAD_GATEWAY);
            case REFUSED -> ConfigViewResult.refused(refusal(reply.reasonCode().orElse(AgentConfigView.Reason.UNKNOWN)));
        };
    }

    /** The agent's refusal to the console's outcome. */
    static Reason refusal(AgentConfigView.Reason reason) {
        return switch (reason) {
            case FILE_MISSING -> Reason.NOT_FOUND;
            case FILE_TOO_LARGE, TOO_MANY_KEYS -> Reason.TOO_LARGE;
            case INVALID_YAML -> Reason.INVALID_YAML;
            case PLACEMENT_NOT_IN_LEASE -> Reason.AGENT_UNAVAILABLE;
            case FILE_UNREADABLE, NOT_PERMITTED, UNKNOWN -> Reason.BAD_GATEWAY;
        };
    }

    /** Review Focus 4: the whole reply, or nothing of it. */
    private ConfigViewResult recheck(AgentConfigView reply, UUID placementId) {
        List<AgentConfigView.Entry> entries = reply.entries();
        boolean sound = reply.reason() == null && reply.hiddenCount() >= 0 && entries.size() <= MAX_ENTRIES;
        long bytes = 0;
        Set<String> seen = new HashSet<>();
        List<ConfigViewResult.Entry> kept = new ArrayList<>(entries.size());
        for (int i = 0; sound && i < entries.size(); i++) {
            AgentConfigView.Entry entry = entries.get(i);
            bytes += entry.path().getBytes(StandardCharsets.UTF_8).length + entry.value().getBytes(StandardCharsets.UTF_8).length;
            sound = !entry.path().isEmpty() && seen.add(entry.path()) && allowed(allow, entry.path()) && bytes <= maxBytes;
            kept.add(new ConfigViewResult.Entry(entry.path(), entry.value()));
        }
        if (!sound) {
            log.warn("a config view reply for placement {} failed the allow-list re-check and was discarded", placementId);
            return ConfigViewResult.refused(Reason.BAD_GATEWAY);
        }
        return new ConfigViewResult.Ok(kept, reply.hiddenCount(), reply.truncated());
    }

    /** Stops the pool; a view in flight is interrupted (and answered {@code UNAVAILABLE} by the gateway). */
    @Override
    public void close() {
        pool.shutdownNow();
    }

    /** The port of a context with no gateway: no agent is ever connected. */
    static AgentConfigViewPort absentPort() {
        return (tenantId, agentId, placementId, allowList, max) -> AgentConfigView.unavailable();
    }
}
