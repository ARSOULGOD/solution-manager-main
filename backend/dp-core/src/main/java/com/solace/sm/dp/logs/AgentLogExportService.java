package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.agw.AgentLogChunk;
import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogExportService;
import com.solace.sm.dp.api.logs.LogExportStream;
import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.inventory.PlacementRows;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogExportService} over a {@code host-local} agent's collected log (plan 016 task 10, spec 4.1 and 5).
 *
 * <ul>
 *   <li><b>Tenant-bound (I5).</b> The placement is resolved exactly as {@link AgentLogService} resolves it (live row for
 *       {@code (organization, instance, placement)}, agent transport); anything else is a single fixed marker and the
 *       gateway is never asked.</li>
 *   <li><b>Pull is credit.</b> A chunk is taken from the {@link LogExportHandle} only inside {@link LogExportStream#next()}, and
 *       the broker grants the agent one credit per chunk taken, so a reader that stops pulling stops the agent. At most one
 *       chunk is held at a time.</li>
 *   <li><b>Bounded twice.</b> {@code maxBytes} of line text (counted here as well as by the port) and {@code deadline}. A
 *       revoked, cancelled or lost export sends no final chunk, so the stream ends itself at the deadline or when the handle
 *       reports it ended, and says so with a fixed marker. Every marker carries the {@code seq} to resume from.</li>
 *   <li><b>Nothing of the host.</b> Marker text is fixed here (I6); a gap's reason is echoed only if it is in the agent's
 *       closed vocabulary, else {@code UNKNOWN}. A log line is the managed integration's own output and is not redacted, as for
 *       a page; newlines inside it are flattened so a line cannot pass for a marker.</li>
 *   <li><b>Bounded concurrency.</b> {@code maxConcurrent} exports at once; one more is a declared busy marker, never a queue.
 *       The slot is released when the stream ends or is closed (which also cancels the agent request, freeing the gateway's
 *       per-agent slot).</li>
 * </ul>
 */
public final class AgentLogExportService implements LogExportService {

    private static final Logger log = LoggerFactory.getLogger(AgentLogExportService.class);

    static final String SIZE_MARKER = "truncated: the size limit was reached";
    static final String TIME_MARKER = "truncated: the time limit was reached";
    static final String LOST_MARKER = "incomplete: the agent's connection was lost";
    static final String COMPLETE_WITH_GAPS = "complete, with gaps";

    private static final Set<String> GAP_REASONS =
            Set.of("DROPPED_OLDEST", "COLLECTION_PAUSED", "CONTAINER_UNREADABLE", "CAP_REDUCED", "OVERSIZE");

    /** How long one wait on the handle lasts, so a keep-alive reaches the writer (and notices a gone client) about every second. */
    private static final Duration SLICE = Duration.ofSeconds(1);

    private final PlacementTransports transports;
    private final PlacementRows placements;
    private final AgentLogPort port;
    private final Duration deadline;
    private final long maxBytes;
    private final Semaphore slots;

    public AgentLogExportService(PlacementTransports transports, PlacementRows placements, AgentLogPort port,
            Duration deadline, long maxBytes, int maxConcurrent) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.port = Objects.requireNonNull(port, "port");
        if (Objects.requireNonNull(deadline, "deadline").isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("the export deadline must be positive");
        }
        this.deadline = deadline;
        if (maxBytes < 1) {
            throw new IllegalArgumentException("the export size bound must be positive");
        }
        this.maxBytes = maxBytes;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("at least one concurrent export is required");
        }
        this.slots = new Semaphore(maxConcurrent);
    }

    @Override
    public LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        if (!slots.tryAcquire()) {
            return refused(AgentLogService.AT_CAPACITY);
        }
        boolean handedOver = false;
        try {
            if (placements.findLive(organizationId, instanceId, placementId).isEmpty() || transports
                    .transportOf(new PlacementRef(new TenantId(organizationId), new InstanceId(instanceId),
                            new PlacementId(placementId)))
                    .filter(PlacementTransports.AGENT::equals).isEmpty()) {
                return refused(AgentLogService.UNRESOLVABLE);
            }
            Optional<AgentSessionRef> session = port.sessionFor(organizationId, placementId);
            if (session.isEmpty()) {
                return refused(AgentLogService.NO_SESSION);
            }
            LogExportHandle handle = port.openExport(session.get(), placementId, 0L, deadline, maxBytes);
            Stream stream = new Stream(handle, System.nanoTime() + deadline.toNanos());
            handedOver = true;
            return stream;
        } catch (RuntimeException e) {
            log.warn("an agent log export for placement {} could not start: {}", placementId, e.getClass().getSimpleName());
            return refused(AgentLogService.UNSERVED);
        } finally {
            if (!handedOver) {
                slots.release();
            }
        }
    }

    private static LogExportStream refused(String text) {
        return new LogExportStream() {
            private boolean sent;

            @Override
            public String next() {
                if (sent) {
                    return null;
                }
                sent = true;
                return marker("unavailable: " + text);
            }

            @Override
            public void close() {
                sent = true;
            }
        };
    }

    private static String marker(String text) {
        return LogExportStream.MARKER_PREFIX + " " + text + "\n";
    }

    private static String marker(String text, long resumeSeq) {
        return marker(text + "; resume from seq " + resumeSeq);
    }

    /** One export. Single consumer; {@code close()} may come from another thread. */
    private final class Stream implements LogExportStream {
        private final LogExportHandle handle;
        private final long deadlineNanos;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long used;
        private long resumeSeq;
        private boolean gapsSeen;
        private String tail;       // the final marker still to be handed out, or null
        private boolean ended;

        Stream(LogExportHandle handle, long deadlineNanos) {
            this.handle = handle;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public String next() throws InterruptedException {
            if (ended) {
                return null;
            }
            if (tail != null) {
                return finish(tail);
            }
            long remaining = deadlineNanos - System.nanoTime();
            Duration wait = remaining <= 0 ? Duration.ZERO : Duration.ofNanos(Math.min(remaining, SLICE.toNanos()));
            Optional<AgentLogChunk> chunk;
            try {
                chunk = handle.next(wait);
            } catch (RuntimeException e) {
                log.warn("an agent log export failed: {}", e.getClass().getSimpleName());
                return finish(marker("unavailable: " + AgentLogService.UNSERVED));
            }
            if (chunk.isPresent()) {
                return render(chunk.get());
            }
            LogExportHandle.State state = handle.state();
            switch (state) {
                case OPEN:
                    if (remaining <= 0) {
                        return finish(marker(TIME_MARKER, resumeSeq));
                    }
                    return "";
                case COMPLETE:
                    return finish(gapsSeen ? marker(COMPLETE_WITH_GAPS) : "");
                case UNAVAILABLE:
                    return finish(marker("unavailable: "
                            + AgentLogService.reasonText(handle.unavailableReason().orElse(""))));
                case TIMED_OUT:
                    return finish(marker(TIME_MARKER, resumeSeq));
                case SIZE_LIMIT:
                    return finish(marker(SIZE_MARKER, resumeSeq));
                case SESSION_LOST:
                    return finish(marker(LOST_MARKER, resumeSeq));
                case CANCELLED:
                default:
                    return finish(marker("incomplete: the export was cancelled", resumeSeq));
            }
        }

        /** One chunk to text, gaps merged in by time, within the byte bound. */
        private String render(AgentLogChunk chunk) {
            StringBuilder out = new StringBuilder();
            List<AgentLogGap> gaps = chunk.gaps();
            int g = 0;
            for (LogLineView line : chunk.lines()) {
                while (g < gaps.size() && !gaps.get(g).from().isAfter(line.at())) {
                    out.append(gapMarker(gaps.get(g++)));
                }
                String text = line.message().replace('\r', ' ').replace('\n', ' ');
                long size = text.getBytes(StandardCharsets.UTF_8).length;
                if (used + size > maxBytes) {
                    cancelOnce();
                    return finishWith(out, marker(SIZE_MARKER, resumeSeq));
                }
                used += size;
                out.append(line.at()).append(' ').append(text).append('\n');
            }
            while (g < gaps.size()) {
                out.append(gapMarker(gaps.get(g++)));
            }
            resumeSeq = chunk.resumeSeq();
            if (chunk.last()) {
                tail = gapsSeen ? marker(COMPLETE_WITH_GAPS) : "";
            }
            return out.toString();
        }

        private String gapMarker(AgentLogGap gap) {
            gapsSeen = true;
            String reason = GAP_REASONS.contains(gap.reason()) ? gap.reason() : "UNKNOWN";
            return marker("gap " + reason + " from " + gap.from() + " to " + gap.to());
        }

        private String finishWith(StringBuilder out, String endMarker) {
            tail = endMarker;
            return out.toString();
        }

        /** Hands out the last text, then ends: cancels the agent request if it is still running and frees the slot. */
        private String finish(String last) {
            ended = true;
            tail = null;
            close();
            return last.isEmpty() ? null : last;
        }

        /** Tells the agent to stop, once however many paths reach here. */
        private void cancelOnce() {
            if (cancelled.compareAndSet(false, true)) {
                handle.cancel();
            }
        }

        @Override
        public void close() {
            ended = true;
            try {
                cancelOnce();
            } finally {
                if (released.compareAndSet(false, true)) {
                    slots.release();
                }
            }
        }
    }
}
