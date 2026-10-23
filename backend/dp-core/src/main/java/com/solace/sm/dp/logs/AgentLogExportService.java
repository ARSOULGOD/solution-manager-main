package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.agw.AgentLogChunk;
import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogExportService;
import com.solace.sm.dp.api.logs.LogExportStream;
import com.solace.sm.dp.api.logs.LogExportStream.Refusal;
import com.solace.sm.dp.api.logs.LogExportStream.Refusal.Kind;
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
 *       {@code (organization, instance, placement)}, agent transport); anything else is a refusal and the gateway is never
 *       asked.</li>
 *   <li><b>Refused before the first byte.</b> {@link #open} waits, bounded by {@code startTimeout}, for the agent's first
 *       answer (a chunk, a refusal, or an end), so every refusal that can be known then is a {@link Refusal}, which the caller
 *       turns into a real status, and not a file. Only what happens after that is an in-band marker.</li>
 *   <li><b>Pull is credit.</b> A chunk is taken from the {@link LogExportHandle} only inside {@link LogExportStream#next()} (the
 *       first, awaited at open, is held), and the broker grants the agent one credit per chunk taken, so a reader that stops
 *       pulling stops the agent. At most one chunk is held at a time.</li>
 *   <li><b>Bounded twice.</b> {@code maxBytes} is the size of the DOWNLOAD: every byte written (instant, text, newlines, gap
 *       markers, UTF-8) counts, with {@link #RESERVE} bytes held back for the closing marker, so the whole body never exceeds
 *       it. The {@code deadline} is checked on every pull. An export that is revoked, cancelled or lost sends no final chunk, so
 *       the stream ends itself at the deadline or when the handle reports it ended, with a fixed marker carrying the
 *       {@code seq} to resume from.</li>
 *   <li><b>Nothing of the host.</b> Marker text is fixed here (I6); a gap's reason is echoed only if it is in the agent's closed
 *       vocabulary, else {@code UNKNOWN}. A log line is the managed integration's own output and is not redacted, as for a page;
 *       newlines inside it are flattened so a line cannot pass for a marker.</li>
 *   <li><b>Bounded concurrency.</b> {@code maxConcurrent} exports at once; one more is a {@link Kind#CAPACITY} refusal, never a
 *       queue. The slot is released when the stream ends or is closed (which also cancels the agent request, freeing the
 *       gateway's per-agent slot).</li>
 * </ul>
 */
public final class AgentLogExportService implements LogExportService {

    private static final Logger log = LoggerFactory.getLogger(AgentLogExportService.class);

    /** Bytes held back from the bound for the closing marker; every fixed marker (with a 20-digit resume seq) is shorter. */
    static final int RESERVE = 256;
    static final String SIZE_MARKER = "truncated: the size limit was reached";
    static final String TIME_MARKER = "truncated: the time limit was reached";
    static final String LOST_MARKER = "incomplete: the agent's connection was lost";
    static final String ABORT_MARKER = "incomplete: the export ended unexpectedly";
    static final String COMPLETE_WITH_GAPS = "complete, with gaps";

    private static final Set<String> GAP_REASONS =
            Set.of("DROPPED_OLDEST", "COLLECTION_PAUSED", "CONTAINER_UNREADABLE", "CAP_REDUCED", "OVERSIZE");

    /** How long one wait on the handle lasts, so a keep-alive reaches the writer about every second. */
    private static final Duration SLICE = Duration.ofSeconds(1);

    private final PlacementTransports transports;
    private final PlacementRows placements;
    private final AgentLogPort port;
    private final Duration deadline;
    private final long maxBytes;
    private final Duration startTimeout;
    private final Semaphore slots;

    public AgentLogExportService(PlacementTransports transports, PlacementRows placements, AgentLogPort port,
            Duration deadline, long maxBytes, int maxConcurrent, Duration startTimeout) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.port = Objects.requireNonNull(port, "port");
        if (Objects.requireNonNull(deadline, "deadline").isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("the export deadline must be positive");
        }
        this.deadline = deadline;
        if (maxBytes < 4L * RESERVE) {
            throw new IllegalArgumentException("the export size bound must be at least " + 4 * RESERVE + " bytes");
        }
        this.maxBytes = maxBytes;
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("at least one concurrent export is required");
        }
        if (Objects.requireNonNull(startTimeout, "startTimeout").isNegative() || startTimeout.isZero()) {
            throw new IllegalArgumentException("the start wait must be positive");
        }
        this.startTimeout = startTimeout;
        this.slots = new Semaphore(maxConcurrent);
    }

    @Override
    public LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        if (!slots.tryAcquire()) {
            return refused(Kind.CAPACITY, AgentLogService.AT_CAPACITY);
        }
        Stream stream = null;
        try {
            if (placements.findLive(organizationId, instanceId, placementId).isEmpty() || transports
                    .transportOf(new PlacementRef(new TenantId(organizationId), new InstanceId(instanceId),
                            new PlacementId(placementId)))
                    .filter(PlacementTransports.AGENT::equals).isEmpty()) {
                slots.release();
                return refused(Kind.UNRESOLVABLE, AgentLogService.UNRESOLVABLE);
            }
            Optional<AgentSessionRef> session = port.sessionFor(organizationId, placementId);
            if (session.isEmpty()) {
                slots.release();
                return refused(Kind.UNAVAILABLE, AgentLogService.NO_SESSION);
            }
            LogExportHandle handle = port.openExport(session.get(), placementId, 0L, deadline, maxBytes);
            stream = new Stream(handle, System.nanoTime() + deadline.toNanos());
            Optional<String> refusal = stream.awaitStart(startTimeout);
            if (refusal.isPresent()) {
                stream.close();
                return refused(Kind.UNAVAILABLE, refusal.get());
            }
            Stream started = stream;
            stream = null;
            return started;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(stream);
        } catch (RuntimeException e) {
            log.warn("an agent log export for placement {} could not start: {}", placementId, e.getClass().getSimpleName());
            return failed(stream);
        }
    }

    /** A start that failed after the slot was taken: free it (through the stream when one exists) and refuse. */
    private LogExportStream failed(Stream stream) {
        if (stream != null) {
            stream.close();
        } else {
            slots.release();
        }
        return refused(Kind.UNAVAILABLE, AgentLogService.UNSERVED);
    }

    private static LogExportStream refused(Kind kind, String reason) {
        Refusal refusal = new Refusal(kind, reason);
        return new LogExportStream() {
            @Override
            public String next() {
                return null;
            }

            @Override
            public Optional<Refusal> refusal() {
                return Optional.of(refusal);
            }

            @Override
            public void close() {
                // nothing is held: the slot, if any, was released before this was returned
            }
        };
    }

    private static String marker(String text) {
        return LogExportStream.MARKER_PREFIX + " " + text + "\n";
    }

    private static String marker(String text, long resumeSeq) {
        return marker(text + "; resume from seq " + resumeSeq);
    }

    private static long bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** One export. Single consumer; {@code close()} and {@code abort()} may come from another thread. */
    private final class Stream implements LogExportStream {
        private final LogExportHandle handle;
        private final long deadlineNanos;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile long resumeSeq;
        private long used;
        private boolean gapsSeen;
        private AgentLogChunk pending;       // the chunk awaited at open, rendered by the first next()
        private String tail;                 // the final marker still to be handed out, or null
        private volatile boolean ended;

        Stream(LogExportHandle handle, long deadlineNanos) {
            this.handle = handle;
            this.deadlineNanos = deadlineNanos;
        }

        /**
         * Waits up to {@code wait} for the agent's first answer. A chunk is held for {@link #next}; a refusal, a lost session or a
         * timeout before any chunk is returned as a reason; a clean end with nothing to send is an empty export.
         */
        Optional<String> awaitStart(Duration wait) throws InterruptedException {
            long until = System.nanoTime() + wait.toNanos();
            while (true) {
                long left = until - System.nanoTime();
                Optional<AgentLogChunk> chunk = handle.next(Duration.ofNanos(Math.max(0, left)));
                if (chunk.isPresent()) {
                    pending = chunk.get();
                    return Optional.empty();
                }
                switch (handle.state()) {
                    case OPEN:
                        if (left <= 0) {
                            return Optional.of(AgentLogService.TIMEOUT);
                        }
                        break;
                    case COMPLETE:
                        return Optional.empty();
                    case UNAVAILABLE:
                        return Optional.of(AgentLogService.reasonText(handle.unavailableReason().orElse("")));
                    case TIMED_OUT:
                        return Optional.of(AgentLogService.TIMEOUT);
                    case SESSION_LOST:
                        return Optional.of(AgentLogService.SESSION_LOST);
                    default:
                        return Optional.of(AgentLogService.UNSERVED);
                }
            }
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
            if (remaining <= 0 && pending == null) {
                return finishAtDeadline();
            }
            AgentLogChunk chunk = pending;
            pending = null;
            if (chunk == null) {
                Duration wait = Duration.ofNanos(Math.min(remaining, SLICE.toNanos()));
                try {
                    chunk = handle.next(wait).orElse(null);
                } catch (RuntimeException e) {
                    log.warn("an agent log export failed: {}", e.getClass().getSimpleName());
                    return finish(marker(ABORT_MARKER, resumeSeq));
                }
            }
            if (chunk != null) {
                return render(chunk);
            }
            switch (handle.state()) {
                case OPEN:
                    return "";
                case COMPLETE:
                    return finish(gapsSeen ? marker(COMPLETE_WITH_GAPS) : "");
                case UNAVAILABLE:
                    return finish(marker(ABORT_MARKER, resumeSeq));
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

        private String finishAtDeadline() {
            cancelOnce();
            return finish(marker(TIME_MARKER, resumeSeq));
        }

        /** One chunk to text, gaps merged in by time, within the byte bound (everything written counts). */
        private String render(AgentLogChunk chunk) {
            StringBuilder out = new StringBuilder();
            List<AgentLogGap> gaps = chunk.gaps();
            long budget = maxBytes - RESERVE;
            int g = 0;
            for (LogLineView line : chunk.lines()) {
                while (g < gaps.size() && !gaps.get(g).from().isAfter(line.at())) {
                    if (!fits(out, gapMarker(gaps.get(g++)), budget)) {
                        return cut(out);
                    }
                }
                String text = line.message().replace('\r', ' ').replace('\n', ' ');
                if (!fits(out, line.at() + " " + text + "\n", budget)) {
                    return cut(out);
                }
            }
            while (g < gaps.size()) {
                if (!fits(out, gapMarker(gaps.get(g++)), budget)) {
                    return cut(out);
                }
            }
            resumeSeq = chunk.resumeSeq();
            if (chunk.last()) {
                tail = gapsSeen ? marker(COMPLETE_WITH_GAPS) : "";
            }
            return out.toString();
        }

        /** Appends {@code piece} if its UTF-8 size keeps the body within the budget. */
        private boolean fits(StringBuilder out, String piece, long budget) {
            long size = bytes(piece);
            if (used + size > budget) {
                return false;
            }
            used += size;
            out.append(piece);
            return true;
        }

        /** The bound was reached mid-chunk: stop the agent now, hand out what fits, then the closing marker. */
        private String cut(StringBuilder out) {
            cancelOnce();
            tail = marker(SIZE_MARKER, resumeSeq);
            return out.toString();
        }

        private String gapMarker(AgentLogGap gap) {
            gapsSeen = true;
            String reason = GAP_REASONS.contains(gap.reason()) ? gap.reason() : "UNKNOWN";
            return marker("gap " + reason + " from " + gap.from() + " to " + gap.to());
        }

        /** Hands out the last text, then ends: cancels the agent request if it is still running and frees the slot. */
        private String finish(String last) {
            ended = true;
            tail = null;
            close();
            return last.isEmpty() ? null : last;
        }

        @Override
        public String abort() {
            close();
            return marker(ABORT_MARKER, resumeSeq);
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
