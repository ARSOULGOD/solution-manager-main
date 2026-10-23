package com.solace.sm.agw;

import com.google.protobuf.Timestamp;
import com.solace.sm.dp.api.agw.AgentLogChunk;
import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogLineView;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.LogDown;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.LogUp;

/**
 * The gateway's side of agent-collected logs (plan 016 task 7): the {@link AgentLogPort} the data plane calls, the table that
 * matches replies to requests by {@code request_id}, and the {@code Logs} stream's two-way traffic. It holds no durable state and
 * never a database handle; a request lives in memory until its reply, its deadline, or the end of its session.
 *
 * <p>Rules it keeps, from the agent's contract (spec 4.1, 4.2):
 * <ul>
 *   <li>A {@code LogRequest} is sent on the {@code Connect} stream BEFORE the first {@code LogCredit}: the agent starts every
 *       export with {@code Limits.initial_credits} itself, so the gateway sends none up front and then grants one credit per chunk
 *       the consumer takes. A credit that overtook its request would be dropped by the agent as an unknown id.</li>
 *   <li>A request goes only to a session that advertised log capability whose lease names the placement.</li>
 *   <li>A refusal always arrives as a {@code LogPage} with a reason, for an export too (a chunk has no reason field).</li>
 *   <li>An export that is revoked, cancelled, or ends with its session gets no final chunk; this class ends it itself, on its
 *       deadline or as soon as it learns the session or stream is gone.</li>
 *   <li>The {@code Logs} {@code onNext} never blocks: it looks the request up and hands the result off (futures complete on the
 *       scheduler, never on the gRPC thread; export chunks go into a bounded queue).</li>
 * </ul>
 */
final class AgentLogBroker implements AgentLogPort {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentLogBroker.class);

    /** What the broker needs of one live, accepted agent session; implemented by the gateway's connect-stream handler. */
    interface Link {
        AgentSessionRef ref();
        boolean logsSupported();
        boolean inLease(UUID placementId);
        /** Sends on the Connect stream; false when it cannot (closed). Serialised with the session's other sends. */
        boolean sendMessage(GatewayToAgent message);
        /** Sends a retention cap for a placement in the lease, remembering it so the next lease event does not repeat it. */
        void pushRetention(UUID placementId, long maxBytes);
    }

    private final Function<UUID, Link> links;
    private final Supplier<Collection<UUID>> agents;
    private final ScheduledExecutorService scheduler;
    private final AgentGatewayProperties properties;
    private final AgentGatewayMetrics metrics;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, LogStream> streams = new ConcurrentHashMap<>();

    AgentLogBroker(Function<UUID, Link> links, Supplier<Collection<UUID>> agents, ScheduledExecutorService scheduler,
            AgentGatewayProperties properties, AgentGatewayMetrics metrics) {
        this.links = links;
        this.agents = agents;
        this.scheduler = scheduler;
        this.properties = properties;
        this.metrics = metrics;
    }

    // ---- the port ----

    @Override
    public Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId) {
        // The organization scopes the caller's lookup of the placement; the gateway never holds tenant scope (AGW-D3), and the agent
        // identity it serves is already fixed by the placement's lease. Nothing here echoes it.
        java.util.Objects.requireNonNull(organizationId, "organizationId");
        for (Link link : liveLinks()) {
            if (link.logsSupported() && link.inLease(placementId)) return Optional.of(link.ref());
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after,
            Instant before, Duration deadline) {
        java.util.Objects.requireNonNull(deadline, "deadline");
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        CompletableFuture<AgentLogPage> future = new CompletableFuture<>();
        Link link = links.apply(ref.agentId());
        String refusal = refusal(link, ref, placementId);
        if (refusal != null) {
            future.complete(AgentLogPage.unavailable(refusal));
            return future;
        }
        PagePending request = new PagePending(UUID.randomUUID().toString(), ref, placementId, future);
        if (!admit(request)) {
            future.complete(AgentLogPage.unavailable("BUSY"));
            return future;
        }
        var query = AgentGatewayOuterClass.LogPageQuery.newBuilder().setLimit(Math.max(0, limit));
        if (after != null) query.setAfter(timestamp(after));
        if (before != null) query.setBefore(timestamp(before));
        // Registered first, so a reply that beats this method's return still finds its request.
        // The deadline is armed only after the request is on the wire: sendMessage can wait on the session's monitor past the
        // deadline, and a timeout's LogCancel must never reach the agent before the LogRequest it cancels (the agent ignores a
        // cancel for an unknown id, then admits the request and holds its slot forever).
        if (!link.sendMessage(logRequest(request.id, placementId, body -> body.setPage(query)))) {
            if (release(request.id) != null) request.complete(AgentLogPage.unavailable("NO_SESSION"));
            return future;
        }
        armAfterSend(request, deadline);
        return future;
    }

    @Override
    public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
        java.util.Objects.requireNonNull(deadline, "deadline");
        Link link = links.apply(ref.agentId());
        String refusal = refusal(link, ref, placementId);
        ExportPending export = new ExportPending(UUID.randomUUID().toString(), ref, placementId, Math.max(0, fromSeq),
                maxBytes, Math.max(1, properties.getInitialTelemetryCredits()));
        if (refusal != null) {
            export.end(LogExportHandle.State.UNAVAILABLE, refusal);
            return export;
        }
        if (!admit(export)) {
            export.end(LogExportHandle.State.UNAVAILABLE, "BUSY");
            return export;
        }
        var query = AgentGatewayOuterClass.LogExportQuery.newBuilder().setFromSeq(export.fromSeq);
        if (!link.sendMessage(logRequest(export.id, placementId, body -> body.setExport(query)))) {
            if (release(export.id) != null) export.end(LogExportHandle.State.SESSION_LOST, null);
            return export;
        }
        armAfterSend(export, deadline);
        return export;
    }

    @Override
    public void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes) {
        Link link = links.apply(ref.agentId());
        if (link == null || !link.ref().equals(ref) || !link.logsSupported() || !link.inLease(placementId)) return;
        link.pushRetention(placementId, Math.max(0, maxBytes));
    }

    // ---- the Logs stream ----

    /** A Logs stream opened by the agent's accepted, log-capable session. A newer stream for the same session replaces the older. */
    LogStream attach(AgentSessionRef ref, StreamObserver<LogDown> response) {
        LogStream stream = new LogStream(ref, response);
        LogStream previous = streams.put(ref.agentId(), stream);
        if (previous != null && previous != stream) previous.finish();
        return stream;
    }

    /** The Logs stream ended (its peer closed or failed): everything waiting on that session's replies ends now. */
    void detach(LogStream stream) {
        boolean current = streams.remove(stream.ref.agentId(), stream);
        stream.finish();
        // A stream already replaced by a newer one must not end the requests issued over the newer one.
        if (current) endAllOf(stream.ref);
    }

    /** The agent's session closed (a close, revoke or supersede): its Logs stream and every request waiting on it end now. */
    void sessionClosed(AgentSessionRef ref) {
        LogStream stream = streams.get(ref.agentId());
        if (stream != null && stream.ref.equals(ref)) {
            streams.remove(ref.agentId(), stream);
            stream.finish();
        }
        endAllOf(ref);
    }

    /** One message from the agent's Logs stream. Never blocks: a map lookup and a hand-off. */
    void onUp(LogStream from, LogUp up) {
        if (up.hasLogPage()) {
            AgentGatewayOuterClass.LogPage page = up.getLogPage();
            Pending request = pending.get(page.getRequestId());
            if (request == null || !request.ref.equals(from.ref)) {
                metrics.logReplyIgnored();
                return;
            }
            boolean refused = page.getUnavailable() != AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED;
            if (request instanceof PagePending pagePending) {
                if (release(request.id) == null) { metrics.logReplyIgnored(); return; }
                pagePending.complete(refused ? AgentLogPage.unavailable(reason(page.getUnavailable()))
                        : new AgentLogPage(lines(page.getLinesList()), gaps(page.getGapsList()), Optional.empty()));
            } else if (refused) {
                // A refusal to an export arrives as a page (a chunk has no reason field).
                if (release(request.id) == null) { metrics.logReplyIgnored(); return; }
                ((ExportPending) request).end(LogExportHandle.State.UNAVAILABLE, reason(page.getUnavailable()));
            } else {
                metrics.logReplyIgnored();      // a reasonless page for an export is not something the agent sends
            }
        } else if (up.hasLogChunk()) {
            AgentGatewayOuterClass.LogChunk chunk = up.getLogChunk();
            Pending request = pending.get(chunk.getRequestId());
            if (!(request instanceof ExportPending export) || !request.ref.equals(from.ref)) {
                metrics.logReplyIgnored();
                return;
            }
            export.accept(chunk);
        }
    }

    // ---- internals ----

    private List<Link> liveLinks() {
        List<Link> out = new ArrayList<>();
        for (UUID agent : agents.get()) {
            Link link = links.apply(agent);
            if (link != null) out.add(link);
        }
        return out;
    }

    private static String refusal(Link link, AgentSessionRef ref, UUID placementId) {
        if (link == null || !link.ref().equals(ref)) return "NO_SESSION";
        if (!link.logsSupported()) return "NO_LOGS_CAPABILITY";
        if (!link.inLease(placementId)) return "PLACEMENT_NOT_IN_LEASE";
        return null;
    }

    /** Registers the request unless the agent already has its bound in flight. */
    private boolean admit(Pending request) {
        AtomicInteger count = inFlight.computeIfAbsent(request.ref.agentId(), k -> new AtomicInteger());
        if (count.incrementAndGet() > properties.getLogMaxInFlightPerAgent()) {
            count.decrementAndGet();
            return false;
        }
        pending.put(request.id, request);
        return true;
    }

    /** Removes the request; non-null only for the one caller that removed it, so exactly one path completes it. */
    private Pending release(String id) {
        Pending removed = pending.remove(id);
        if (removed == null) return null;
        AtomicInteger count = inFlight.get(removed.ref.agentId());
        if (count != null) count.decrementAndGet();
        ScheduledFuture<?> task = removed.deadlineTask;
        if (task != null) task.cancel(false);
        return removed;
    }

    /** Arms the deadline once the request has been sent; a reply that already released the entry leaves nothing to expire. */
    private void armAfterSend(Pending request, Duration deadline) {
        request.deadlineTask = arm(request, deadline);
        if (!pending.containsKey(request.id)) {
            ScheduledFuture<?> task = request.deadlineTask;
            if (task != null) task.cancel(false);
        }
    }

    private ScheduledFuture<?> arm(Pending request, Duration deadline) {
        try {
            return scheduler.schedule(() -> expire(request.id), Math.max(1, deadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            return null;
        }
    }

    private void expire(String id) {
        Pending request = release(id);
        if (request == null) return;
        metrics.logRequestTimedOut();
        sendCancel(request);
        if (request instanceof PagePending page) page.complete(AgentLogPage.unavailable("TIMEOUT"));
        else ((ExportPending) request).end(LogExportHandle.State.TIMED_OUT, null);
    }

    private void endAllOf(AgentSessionRef ref) {
        for (Pending request : new ArrayList<>(pending.values())) {
            if (!request.ref.equals(ref)) continue;
            if (release(request.id) == null) continue;
            if (request instanceof PagePending page) page.complete(AgentLogPage.unavailable("NO_SESSION"));
            else ((ExportPending) request).end(LogExportHandle.State.SESSION_LOST, null);
        }
    }

    private void sendCancel(Pending request) {
        LogStream stream = streams.get(request.ref.agentId());
        if (stream != null) {
            stream.send(LogDown.newBuilder().setLogCancel(
                    AgentGatewayOuterClass.LogCancel.newBuilder().setRequestId(request.id)).build());
        }
    }

    private void sendCredit(Pending request, int credits) {
        LogStream stream = streams.get(request.ref.agentId());
        if (stream != null) {
            stream.send(LogDown.newBuilder().setLogCredit(AgentGatewayOuterClass.LogCredit.newBuilder()
                    .setRequestId(request.id).setCreditsGranted(credits)).build());
        }
    }

    private static GatewayToAgent logRequest(String id, UUID placementId,
            java.util.function.Consumer<AgentGatewayOuterClass.LogRequest.Builder> mode) {
        var body = AgentGatewayOuterClass.LogRequest.newBuilder().setEnvelope(envelope())
                .setRequestId(id).setPlacementRef(placementId.toString());
        mode.accept(body);
        return GatewayToAgent.newBuilder().setLogRequest(body).build();
    }

    static AgentGatewayOuterClass.Envelope envelope() {
        return AgentGatewayOuterClass.Envelope.newBuilder().setMessageId(UUID.randomUUID().toString())
                .setSentAt(timestamp(Instant.now())).build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Instant instant(Timestamp t) { return Instant.ofEpochSecond(t.getSeconds(), t.getNanos()); }

    private static String reason(AgentGatewayOuterClass.UnavailableReason reason) {
        return switch (reason) {
            case UNAVAILABLE_REASON_NOT_COLLECTING -> "NOT_COLLECTING";
            case UNAVAILABLE_REASON_CONTAINER_MISSING -> "CONTAINER_MISSING";
            case UNAVAILABLE_REASON_BUSY -> "BUSY";
            case UNAVAILABLE_REASON_PLACEMENT_NOT_IN_LEASE -> "PLACEMENT_NOT_IN_LEASE";
            // An unspecified or future reason is still a refusal: say so without echoing a value we cannot name.
            default -> "NOT_COLLECTING";
        };
    }

    private static List<LogLineView> lines(List<AgentGatewayOuterClass.LogLine> source) {
        List<LogLineView> out = new ArrayList<>(source.size());
        for (AgentGatewayOuterClass.LogLine line : source) {
            // The level is not on the wire; the data plane derives it from the text as it does for the SSH path.
            out.add(new LogLineView(instant(line.getTs()), Optional.empty(), line.getText().toString(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static List<AgentLogGap> gaps(List<AgentGatewayOuterClass.LogGap> source) {
        List<AgentLogGap> out = new ArrayList<>(source.size());
        for (AgentGatewayOuterClass.LogGap gap : source) {
            out.add(new AgentLogGap(instant(gap.getFrom()), instant(gap.getTo()), gap.getReason()));
        }
        return out;
    }

    private static long textBytes(List<AgentGatewayOuterClass.LogLine> lines) {
        long total = 0;
        for (AgentGatewayOuterClass.LogLine line : lines) total += line.getText().size();
        return total;
    }

    /** Completes on the scheduler, never on the gRPC thread that delivered the reply. */
    private <T> void completeAsync(CompletableFuture<T> future, T value) {
        try {
            scheduler.execute(() -> future.complete(value));
        } catch (RejectedExecutionException shuttingDown) {
            future.complete(value);
        }
    }

    // ---- request kinds ----

    private abstract static class Pending {
        final String id;
        final AgentSessionRef ref;
        final UUID placementId;
        volatile ScheduledFuture<?> deadlineTask;

        Pending(String id, AgentSessionRef ref, UUID placementId) {
            this.id = id;
            this.ref = ref;
            this.placementId = placementId;
        }
    }

    private final class PagePending extends Pending {
        private final CompletableFuture<AgentLogPage> future;

        PagePending(String id, AgentSessionRef ref, UUID placementId, CompletableFuture<AgentLogPage> future) {
            super(id, ref, placementId);
            this.future = future;
        }

        void complete(AgentLogPage page) { completeAsync(future, page); }
    }

    private final class ExportPending extends Pending implements LogExportHandle {
        final long fromSeq;
        private final long maxBytes;
        private final LinkedBlockingQueue<AgentLogChunk> queue;
        private final AtomicLong received = new AtomicLong();
        private volatile State state = State.OPEN;
        private volatile String unavailable;

        ExportPending(String id, AgentSessionRef ref, UUID placementId, long fromSeq, long maxBytes, int credits) {
            super(id, ref, placementId);
            this.fromSeq = fromSeq;
            this.maxBytes = maxBytes;
            // The agent holds at most the initial credits' worth of chunks in flight; the slack is for the final chunk and one
            // misbehaving extra, which ends the export rather than growing the queue.
            this.queue = new LinkedBlockingQueue<>(credits + 2);
        }

        /** Ends the stream once; later calls keep the first cause. Chunks already queued stay readable. */
        void end(State cause, String reason) {
            synchronized (this) {
                if (state != State.OPEN) return;
                unavailable = reason;
                state = cause;
            }
        }

        void accept(AgentGatewayOuterClass.LogChunk chunk) {
            if (state != State.OPEN) { metrics.logReplyIgnored(); return; }
            AgentLogChunk converted = new AgentLogChunk(chunk.getSeq(), lines(chunk.getLinesList()),
                    gaps(chunk.getGapsList()), chunk.getFinal());
            if (!queue.offer(converted)) {
                // More chunks than credits were granted: a protocol violation. Stop the export rather than buffer without bound.
                if (release(id) != null) { sendCancel(this); end(State.SESSION_LOST, null); }
                return;
            }
            long total = received.addAndGet(textBytes(chunk.getLinesList()));
            if (chunk.getFinal()) {
                if (release(id) != null) end(State.COMPLETE, null);
            } else if (maxBytes > 0 && total > maxBytes) {
                if (release(id) != null) { sendCancel(this); end(State.SIZE_LIMIT, null); }
            }
        }

        @Override
        public Optional<AgentLogChunk> next(Duration wait) throws InterruptedException {
            AgentLogChunk chunk = queue.poll();
            if (chunk == null && state == State.OPEN) chunk = queue.poll(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
            if (chunk == null && state != State.OPEN) chunk = queue.poll();      // the end can land between the two reads
            if (chunk != null && !chunk.last() && state == State.OPEN) sendCredit(this, 1);
            return Optional.ofNullable(chunk);
        }

        @Override public State state() { return state; }
        @Override public Optional<String> unavailableReason() { return Optional.ofNullable(unavailable); }

        @Override
        public void cancel() {
            if (release(id) != null) {
                sendCancel(this);
                end(State.CANCELLED, null);
            } else {
                end(State.CANCELLED, null);      // never registered (a refusal) or already ended: a no-op unless still OPEN
            }
        }
    }

    // ---- the Logs stream's outbound half ----

    /** Serialises sends on one Logs stream (gRPC observers are not thread-safe). */
    final class LogStream {
        final AgentSessionRef ref;
        private final StreamObserver<LogDown> response;
        private boolean finished;

        LogStream(AgentSessionRef ref, StreamObserver<LogDown> response) {
            this.ref = ref;
            this.response = response;
        }

        synchronized void send(LogDown message) {
            if (finished) return;
            try { response.onNext(message); }
            catch (RuntimeException closed) { LOG.debug("agent-gateway log send after close: {}", closed.getClass().getName()); }
        }

        synchronized void finish() {
            if (finished) return;
            finished = true;
            try { response.onCompleted(); }
            catch (RuntimeException alreadyClosed) { /* the peer went first */ }
        }
    }
}
