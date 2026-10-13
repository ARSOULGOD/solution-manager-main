package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostLogs;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogService} over one SSH read (plan 010 T9.1, {@code OR-43}).
 *
 * <ul>
 *   <li><b>Bounded in every dimension.</b> The limit and the window are validated before any host is touched; the
 *       transport bounds the exec's bytes and its connect and read times; and at most {@code maxConcurrentReads} reads
 *       are in flight at once - a read that cannot get a slot within {@code acquireTimeout} is a DECLARED degraded page,
 *       never a queue and never a hang. A browser cannot fan out unbounded SSH sessions to a customer's host.</li>
 *   <li><b>Tenant-bound.</b> The placement is looked up by {@code (organization, instance, placement)} together
 *       ({@code TablePlacementCatalog}), so a placement that is not this instance's, in this organization, is
 *       unresolvable (I5).</li>
 *   <li><b>A host that cannot be read is declared, not thrown.</b> Every adapter failure becomes a page with no lines
 *       and a fixed reason. The reason is chosen from the failure's classifier and never quotes it, the host, the path or
 *       the container (I6).</li>
 *   <li><b>Not redacted.</b> A line is the managed integration's own output (see {@link LogService}).</li>
 *   <li><b>A {@code before} with no {@code after} pages backwards in bounded windows</b> ({@code OR-108} (5)). Docker
 *       applies {@code --tail} before {@code --until} (M11), so the host cannot be asked for "the last N lines before X"
 *       and would return the whole log up to X, which for a large log is over the byte bound. So the read asks for one
 *       window of {@code window} (default 15 minutes, {@code --since X - window --until X}), keeps its newest lines, and
 *       steps back one window at a time until it has {@code limit} lines or has looked back {@code maxWindows} windows.
 *       Every host read is therefore bounded in bytes, and a large log is never "unavailable" merely for being large. A
 *       window that is itself over the byte bound is halved (down to {@link #MIN_WINDOW}), so a chatty container still
 *       answers. A page shorter than {@code limit} means the look-back ended, not that the log did.</li>
 * </ul>
 */
public final class LogServiceImpl implements LogService {

    private static final Logger log = LoggerFactory.getLogger(LogServiceImpl.class);

    static final String BUSY = "log reads are at capacity; try again shortly";
    static final String TOO_LARGE = "the requested window holds more output than one read may return; narrow it with after or before";
    static final String COMMAND_FAILED = "the container's logs could not be read (it may not exist, or its runtime refused the request)";
    static final String BINDING = "the placement's host binding is not available";
    static final String AUTHENTICATION = "the host could not be authenticated to";
    static final String UNREACHABLE = "the host could not be reached";
    static final String UNREADABLE = "the host could not be read";

    /** {@code OR-108} (5): the default width of one backward window. */
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    /** The most windows one read steps back through: a bound on host reads (and so on the request's latency). */
    static final int DEFAULT_MAX_WINDOWS = 8;
    /** A too-large window is halved no further than this. */
    static final Duration MIN_WINDOW = Duration.ofSeconds(1);

    private final SshDockerPlacementCatalog catalog;
    private final HostLogs logs;
    private final Semaphore slots;
    private final Duration acquireTimeout;
    private final Duration window;
    private final int maxWindows;

    public LogServiceImpl(SshDockerPlacementCatalog catalog, HostLogs logs, int maxConcurrentReads,
            Duration acquireTimeout) {
        this(catalog, logs, maxConcurrentReads, acquireTimeout, DEFAULT_WINDOW, DEFAULT_MAX_WINDOWS);
    }

    public LogServiceImpl(SshDockerPlacementCatalog catalog, HostLogs logs, int maxConcurrentReads,
            Duration acquireTimeout, Duration window, int maxWindows) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.logs = Objects.requireNonNull(logs, "logs");
        if (maxConcurrentReads < 1) {
            throw new IllegalArgumentException("at least one concurrent log read is required");
        }
        if (Objects.requireNonNull(acquireTimeout, "acquireTimeout").isNegative()) {
            throw new IllegalArgumentException("the slot wait cannot be negative");
        }
        if (Objects.requireNonNull(window, "window").compareTo(MIN_WINDOW) < 0) {
            throw new IllegalArgumentException("the backward window cannot be shorter than " + MIN_WINDOW);
        }
        if (maxWindows < 1) {
            throw new IllegalArgumentException("at least one backward window is required");
        }
        this.slots = new Semaphore(maxConcurrentReads);
        this.acquireTimeout = acquireTimeout;
        this.window = window;
        this.maxWindows = maxWindows;
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
            return unavailable(BUSY);
        }
        try {
            SshDockerPlacement placement = catalog.lookup(new PlacementRef(new TenantId(organizationId),
                    new InstanceId(instanceId), new PlacementId(placementId)));
            List<LogLineView> lines = after == null && before != null
                    ? pageBackwards(placement, limit, before)
                    : DockerLogLineParser.page(logs.read(placement, limit, after, before), after, before, limit);
            return new LogPageView(lines, Optional.empty());
        } catch (RuntimeAdapterException e) {
            // the classifier is a fixed token (no host, path or message); it is the only thing logged
            log.warn("a log read for placement {} was not served: {}", placementId, e.errorClass().token());
            return unavailable(reasonOf(e.errorClass().token()));
        } finally {
            slots.release();
        }
    }

    /**
     * The newest {@code limit} lines strictly before {@code before}, oldest first, read one bounded window at a time
     * (OR-108 (5)). Windows are contiguous and each covers {@code [end - width, end)}: the host's {@code --since} is
     * inclusive and the parser's {@code after} exclusive, so {@code --since} is sent one nanosecond early and a line
     * stamped exactly on a window's edge is read once, by the window it belongs to.
     */
    private List<LogLineView> pageBackwards(SshDockerPlacement placement, int limit, Instant before) {
        List<LogLineView> page = new ArrayList<>();
        Duration width = window;
        Instant end = before;
        for (int step = 0; step < maxWindows && page.size() < limit; step++) {
            List<LogLineView> inWindow;
            while (true) {
                Instant start = end.minus(width);
                try {
                    inWindow = DockerLogLineParser.page(logs.read(placement, limit - page.size(), start.minusNanos(1), end),
                            start.minusNanos(1), end, limit - page.size());
                    end = start;
                    break;
                } catch (RuntimeAdapterException e) {
                    Duration narrower = width.dividedBy(2);
                    if (!"ssh.docker.output.too.large".equals(e.errorClass().token())
                            || narrower.compareTo(MIN_WINDOW) < 0) {
                        throw e;
                    }
                    width = narrower;   // a chatty container: ask for less, and keep asking for less for the rest of this read
                }
            }
            page.addAll(0, inWindow);
        }
        return List.copyOf(page);
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

    private static String reasonOf(String token) {
        return switch (token) {
            case "ssh.docker.output.too.large" -> TOO_LARGE;
            case "ssh.docker.command.failed" -> COMMAND_FAILED;
            case "placement.unresolvable", "target.unavailable", "placement.invalid" -> BINDING;
            case "ssh.credential.unavailable", "ssh.credential.invalid" -> AUTHENTICATION;
            case "ssh.connection.failed" -> UNREACHABLE;
            default -> UNREADABLE;
        };
    }
}
