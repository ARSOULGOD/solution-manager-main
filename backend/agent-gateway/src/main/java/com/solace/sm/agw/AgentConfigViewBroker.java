package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigView.Entry;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.ConfigViewReply;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;

/**
 * The gateway's side of the MI config view (plan 017 task 4): the {@link AgentConfigViewPort} the data plane calls, and the
 * request/reply pairing on the existing {@code Connect} stream ({@code GatewayToAgent.config_view_request} out,
 * {@code AgentToGateway.config_view_reply} in). It holds no durable state and no database handle.
 *
 * <p>Rules it keeps:
 * <ul>
 *   <li>A request goes only to a session that advertised {@code CAPABILITY_CONFIG_VIEW} and whose lease names the placement;
 *       an older agent's decoder ends the Connect stream on a message it does not know.</li>
 *   <li>At most one view in flight per agent; a second is {@code BUSY} and is not sent.</li>
 *   <li>The deadline starts only once the request is on the wire (a send can wait on the session's monitor).</li>
 *   <li>Every exit (reply, deadline, refusal, malformed reply, send failure, the placement leaving the lease, the session ending,
 *       the caller being interrupted) releases the agent's slot; exactly one path wins the release and completes the view.</li>
 *   <li>A reply is matched by request id AND by the session it was sent on (agent id and fencing epoch); any other reply is
 *       dropped and counted. The reply carries no placement, so the request id alone binds it to its placement.</li>
 *   <li>A reply is validated before it is handed up; one outside the contract becomes {@code UNAVAILABLE}/{@code UNKNOWN} and
 *       nothing of it is kept. Nothing here logs a key, a value or an allow pattern.</li>
 * </ul>
 */
final class AgentConfigViewBroker implements AgentConfigViewPort {
    private static final String REASON_PREFIX = "CONFIG_VIEW_REASON_";

    /** What the broker needs of one live, accepted agent session; implemented by the gateway's connect-stream handler. */
    interface Link {
        AgentSessionRef ref();
        boolean inLease(UUID placementId);
        /** The agent advertised {@code CAPABILITY_CONFIG_VIEW}: an agent that did not would end its stream on the unknown message. */
        boolean configViewSupported();
        /** Sends on the Connect stream; false when it cannot (closed). Serialised with the session's other sends. */
        boolean sendMessage(GatewayToAgent message);
    }

    private final Function<UUID, Link> links;
    private final Supplier<Duration> deadline;
    private final AgentGatewayMetrics metrics;
    /** The one pending view of each agent: the source of truth for the slot. */
    private final Map<UUID, Pending> byAgent = new ConcurrentHashMap<>();
    private final Map<String, Pending> byId = new ConcurrentHashMap<>();

    AgentConfigViewBroker(Function<UUID, Link> links, Supplier<Duration> deadline, AgentGatewayMetrics metrics) {
        this.links = links;
        this.deadline = deadline;
        this.metrics = metrics;
    }

    @Override
    public AgentConfigView read(String tenantId, String agentId, String placementId, List<String> allow, int maxBytes) {
        // The tenant scopes the caller's lookup; the gateway holds no tenant scope (AGW-D3) and never sends it to the agent.
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        UUID agent = uuid(agentId, "agentId");
        UUID placement = uuid(placementId, "placementId");
        Objects.requireNonNull(allow, "allow");
        List<String> patterns = List.copyOf(allow);      // throws on a null element
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");

        Link link = links.apply(agent);
        AgentSessionRef ref = link == null ? null : link.ref();
        if (ref == null) return AgentConfigView.unavailable();
        if (!link.inLease(placement)) return AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE);
        if (!link.configViewSupported()) return AgentConfigView.unavailable();

        Pending request = new Pending(UUID.randomUUID().toString(), ref, placement);
        if (byAgent.putIfAbsent(agent, request) != null) return AgentConfigView.busy();
        byId.put(request.id, request);
        try {
            return await(link, request, placement, patterns, maxBytes);
        } finally {
            release(request);      // a no-op after any normal exit; frees the slot on an unexpected one (an Error, say)
        }
    }

    private AgentConfigView await(Link link, Pending request, UUID placement, List<String> patterns, int maxBytes) {
        // The lease can narrow between the check above and the admit, and placementsLeft would have run before this request was
        // in the table: look again now that it is.
        if (!link.inLease(placement)) {
            if (release(request)) return AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE);
            return request.future.join();
        }
        // Registered before the send, so a reply that beats sendMessage's return still finds its request.
        GatewayToAgent message = GatewayToAgent.newBuilder().setConfigViewRequest(AgentGatewayOuterClass.ConfigViewRequest.newBuilder()
                .setRequestId(request.id).setPlacementId(placement.toString()).addAllAllow(patterns).setMaxBytes(maxBytes)).build();
        boolean sent;
        try {
            sent = link.sendMessage(message);
        } catch (RuntimeException failure) {
            sent = false;
        }
        if (!sent) {
            if (release(request)) return AgentConfigView.unavailable();
            return request.future.join();      // something else already ended it (the session closing, say)
        }
        // The deadline is armed only now, with the request on the wire.
        try {
            return request.future.get(Math.max(1, deadline.get().toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            if (release(request)) {
                metrics.configViewTimedOut();
                return AgentConfigView.unavailable();
            }
            return request.future.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (release(request)) return AgentConfigView.unavailable();
            return request.future.getNow(AgentConfigView.unavailable());
        } catch (ExecutionException impossible) {
            release(request);
            return AgentConfigView.unavailable();
        }
    }

    /** One {@code config_view_reply} from the accepted session {@code from}. Never blocks: a lookup, a check, a completion. */
    void onReply(AgentSessionRef from, ConfigViewReply reply) {
        Pending request = byId.get(reply.getRequestId());
        if (request == null || from == null || !request.ref.equals(from)) {
            metrics.configViewReplyIgnored();
            return;
        }
        AgentConfigView view = validate(reply);
        if (!complete(request, view)) {
            metrics.configViewReplyIgnored();
            return;
        }
        if (view.status() == AgentConfigView.Status.UNAVAILABLE) metrics.configViewReplyMalformed();
    }

    /** The session's lease no longer names some placements: their pending views end now (the agent answers nothing more for them). */
    void placementsLeft(AgentSessionRef ref, Set<UUID> leased) {
        for (Pending request : new ArrayList<>(byAgent.values())) {
            if (request.ref.equals(ref) && !leased.contains(request.placementId)) {
                complete(request, AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE));
            }
        }
    }

    /** The session closed (stream end, revoke, supersede by a newer stream): its pending view ends now. */
    void sessionClosed(AgentSessionRef ref) {
        if (ref == null) return;
        for (Pending request : new ArrayList<>(byAgent.values())) {
            if (request.ref.equals(ref)) complete(request, AgentConfigView.unavailable());
        }
    }

    /** Views in flight across all agents (for leak tests). */
    int inFlight() { return byAgent.size(); }

    // ---- internals ----

    private boolean complete(Pending request, AgentConfigView view) {
        if (!release(request)) return false;
        request.future.complete(view);
        return true;
    }

    /** Frees the agent's slot; true only for the one caller that freed it, so exactly one path completes the view. */
    private boolean release(Pending request) {
        if (!byAgent.remove(request.ref.agentId(), request)) return false;
        byId.remove(request.id, request);
        return true;
    }

    /** The contract's reply shapes; anything else is malformed and nothing of it is kept. */
    static AgentConfigView validate(ConfigViewReply reply) {
        switch (reply.getStatus()) {
            case CONFIG_VIEW_STATUS_OK -> {
                if (reply.getReasonValue() != 0 || reply.getHiddenCount() < 0) return AgentConfigView.malformed();
                List<Entry> entries = new ArrayList<>(reply.getEntriesCount());
                for (AgentGatewayOuterClass.ConfigEntry entry : reply.getEntriesList()) {
                    if (entry.getPath().isEmpty()) return AgentConfigView.malformed();
                    entries.add(new Entry(entry.getPath(), entry.getValue()));
                }
                return AgentConfigView.ok(entries, reply.getHiddenCount(), reply.getTruncated());
            }
            case CONFIG_VIEW_STATUS_REFUSED -> {
                if (reply.getReasonValue() == 0 || reply.getEntriesCount() != 0 || reply.getHiddenCount() != 0 || reply.getTruncated()) {
                    return AgentConfigView.malformed();
                }
                AgentGatewayOuterClass.ConfigViewReason wire = reply.getReason();
                if (wire == AgentGatewayOuterClass.ConfigViewReason.UNRECOGNIZED) return AgentConfigView.refused(Reason.UNKNOWN);
                return AgentConfigView.refused(Reason.of(wire.name().substring(REASON_PREFIX.length())));
            }
            default -> {
                return AgentConfigView.malformed();      // UNSPECIFIED or a status this gateway does not know
            }
        }
    }

    private static UUID uuid(String text, String name) {
        Objects.requireNonNull(text, name);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            throw new IllegalArgumentException(name + " must be a UUID");      // never echoes the input
        }
    }

    private static final class Pending {
        final String id;
        final AgentSessionRef ref;
        final UUID placementId;
        final CompletableFuture<AgentConfigView> future = new CompletableFuture<>();

        Pending(String id, AgentSessionRef ref, UUID placementId) {
            this.id = id;
            this.ref = ref;
            this.placementId = placementId;
        }
    }
}
