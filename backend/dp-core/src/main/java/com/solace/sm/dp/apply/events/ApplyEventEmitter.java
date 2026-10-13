package com.solace.sm.dp.apply.events;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.events.EntityRef;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.EventPublisher;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the data plane's apply-side notices: {@code instance.apply-outcome} (OR-4, OR-58), {@code instance.rollout}
 * and {@code instance.config-state} (OR-37). Plan 010 T6.4, executing plan 007 Task 5.
 *
 * <p>It is an emitter, not an {@link EventPublisher}: it CALLS one, injected by interface type and wrapped by the
 * data plane's after-commit decorator ({@code MeshBridgeConfiguration}). It holds no broker session and no transport
 * type, so it adds nothing to the certified publisher inventory. Every method is called from the engine at the
 * journal's own transition - never from a controller, never from an adapter - and, where that transition is a
 * database write, from INSIDE the transaction that makes it: the decorator defers the publish to that transaction's
 * commit and discards it on rollback. A transport failure is the publisher's to swallow and count (BE-D1); a
 * malformed event is a caller bug and throws here, into the caller, before anything is deferred.
 *
 * <p><b>Payloads carry identifiers and closed enums only ({@code I6}).</b> {@code instance.apply-outcome} carries
 * {@code applyId} and {@code state} and nothing else: no message and no {@code ErrorClass} token (OR-4), because a
 * classifier can embed a host or container identifier. A consumer that needs <i>why</i> reads the journal over REST.
 *
 * <p><b>{@code stateVersion} is never invented here ({@code B/D15}, OR-35).</b> The apply-outcome carries the
 * request's own {@code apply_request.state_version}, the stamp the database assigned in the landing transaction.
 * Rollout steps move no stamped row, so a rollout notice repeats the stamp the request holds when the step is
 * journaled; the terminal apply-outcome is always strictly newer than every rollout notice of its apply.
 * Config-state carries {@code instance_state}'s stamp, which the {@code reached_rung} write bumps.
 */
public final class ApplyEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(ApplyEventEmitter.class);

    private static final String PRODUCER = "dp-core";

    private final EventPublisher publisher;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private volatile DeclarationsChangeSink declarations = DeclarationsChangeSink.NONE;

    public ApplyEventEmitter(EventPublisher publisher, Clock clock, Supplier<UUID> ids) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * An emitter that publishes nowhere. For engine suites that exercise the journal and do not observe events; no
     * production bean uses it ({@code ApplyEngineConfiguration} takes the configured emitter).
     */
    public static ApplyEventEmitter silent() {
        return new ApplyEventEmitter(event -> { }, Clock.systemUTC(), UUID::randomUUID);
    }

    /**
     * Wires the declarations-change signal (plan 015 W4 lane D): {@link #settleLadder} tells the sink after every settle. Set once at
     * composition by the agent-gateway ports; {@link DeclarationsChangeSink#NONE} until then. One sink: a second call REPLACES the first
     * (the last wins), which is what a test context that composes twice needs.
     */
    public void declarationsSink(DeclarationsChangeSink sink) {
        this.declarations = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Tells the declarations sink that an instance's declared collection set may have changed. {@link #settleLadder} calls it after
     * every settle; the paths that change the set WITHOUT settling the ladder call it themselves (plan 015 W4 lane D fix round):
     * activation and the pending sweep, the three retirement transitions, and a dp-remote start observation. Best effort and, for the
     * agent sink, deferred to the commit of the caller's transaction; a failure never reaches the caller.
     */
    public void declarationsChanged(TenantId tenant, InstanceId instance) {
        bestEffort("declarations", () -> declarations.changed(tenant, instance));
    }

    /**
     * The engine's call-site guard: runs one emission and keeps the journal ahead of the notice. A notice is
     * freshness, never correctness (BE-D1) - REST is authoritative - so a defect in building one must not roll back a
     * landing or strand an apply. The emitter methods themselves still throw into their caller, loudly, on a
     * malformed event; the engine calls them through here so that the loud failure is an ERROR log plus the tests
     * that assert the notice arrived, rather than a request stuck {@code RUNNING}. Only the exception's class is
     * logged ({@code I6}: a message can carry a value).
     */
    public static void bestEffort(String notice, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException e) {
            log.error("{} notice not published: {}", notice, e.getClass().getSimpleName());
        }
    }

    /**
     * A request has landed on a terminal state, or on {@code APPLY_INDETERMINATE} (OR-58). Publishes
     * {@code instance.apply-outcome} for an {@code apply} only.
     *
     * <p>Not published, and not an error: a {@code stop} or {@code restart} (M10 - stops are REST-observed, and an
     * instance-wide stop has no legal placement-scoped envelope), and an instance-wide {@code apply} (OR-4 requires a
     * real {@code placementId}; the nil sentinel is not a storable placement, and the engine refuses such an apply
     * before admission anyway). Whether {@code request.state()} is a state the notice may carry is the contract's
     * call, not this method's: the shape validator throws for any other, so a state added to the engine without the
     * contract is a loud caller bug rather than a silent gap.
     *
     * @param request the request AFTER the transition, read back in the landing transaction so it carries the stamp
     */
    public void onApplyOutcome(ApplyRequestRow request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != Operation.APPLY || request.scope().placement().isInstanceWide()) {
            return;
        }
        publisher.publish(new EventEnvelope(ids.get(), clock.instant(), PRODUCER, tenantOf(request),
                placementEntity(request), stampOf(request), "instance.apply-outcome",
                Map.of("applyId", request.applyId().value().toString(), "state", request.state().name())));
    }

    /**
     * One replica's rollout step, as the engine journaled it (design section 3.5): its config written and read back
     * ({@code staged}), the restart verb issued ({@code restart-requested}), or the restart observed
     * ({@code restart-observed}).
     */
    public void onRollout(ApplyRequestRow request, ReplicaRef replica, RolloutStep step) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(step, "step");
        if (request.operation() != Operation.APPLY || request.scope().placement().isInstanceWide()) {
            return;
        }
        publisher.publish(new EventEnvelope(ids.get(), clock.instant(), PRODUCER, tenantOf(request),
                placementEntity(request), stampOf(request), "instance.rollout",
                Map.of("applyId", request.applyId().value().toString(), "replicaId", replica.value(),
                        "step", step.wire())));
    }

    /**
     * The instance's configuration ladder moved (OR-37). v1 emits {@code instance.config-state} for an instance with
     * exactly one live placement; with more, {@code reached} is the LOWEST rung across placements, and the event fires
     * only when that lowest rung moves. The caller - the one place that writes {@code instance_state.reached_rung} -
     * supplies the lowest rung before and after; this method is the firing rule.
     *
     * @param previous           the instance rung before this write, or {@code null} if none had been reached
     * @param reached            the instance rung after it
     * @param instanceStateVersion {@code instance_state}'s stamp after the {@code reached_rung} write
     * @return whether a notice was published (the rung moved)
     */
    public boolean onConfigStateReached(TenantId tenant, InstanceId instance, LadderRung previous, LadderRung reached,
            long instanceStateVersion) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(reached, "reached");
        if (reached == previous) {
            return false;
        }
        publisher.publish(new EventEnvelope(ids.get(), clock.instant(), PRODUCER, tenant.value().toString(),
                new EntityRef("instance", instance.value().toString(), null, null), instanceStateVersion,
                "instance.config-state", Map.of("reached", reached.wire())));
        return true;
    }

    /**
     * The ONE settle-and-announce of the configuration ladder (plan 008 S3, OQ-8.10 (a): "one rule, two callers"). The
     * landing path ({@code OutcomeService}) and the placement path ({@code InventoryService.addPlacement} and
     * {@code retirePlacement}) both derive the instance rung through {@link LadderRecorder#settle}, write
     * {@code instance_state.reached_rung} only if it moved, and announce {@code instance.config-state} through
     * {@link #onConfigStateReached}'s firing rule, so the stored rung, the read and the event tell one story. Called inside
     * the caller's transaction: the after-commit decorator releases the notice on commit and discards it on rollback. A
     * failure to build or publish the notice never reaches the caller ({@link #bestEffort}).
     *
     * @return whether the stored rung moved
     */
    public boolean settleLadder(LadderRecorder ladder, TenantId tenant, InstanceId instance) {
        Objects.requireNonNull(ladder, "ladder");
        boolean moved = ladder.settle(tenant, instance).map(move -> {
            bestEffort("config-state", () -> onConfigStateReached(tenant, instance, move.previous(), move.reached(),
                    move.stateVersion()));
            return true;
        }).orElse(false);
        // Every settle, moved or not: a placement add or retire changes the declared set without moving the rung. A prompt to
        // re-read, never data; the sink defers to the commit and a failure never reaches the caller.
        declarationsChanged(tenant, instance);
        return moved;
    }

    /**
     * A placement's health state changed (plan 010 T7.2): {@code instance.health}, placement-scoped, watermarked by the
     * placement row's own stamp, with the new state as its only payload (identifiers and a closed token - {@code I6}).
     * Called by the health recorder inside the transaction that persisted the change, so the after-commit publisher
     * releases it only on commit.
     */
    public void onHealthTransition(TenantId tenant, UUID instanceId, UUID placementId, HealthState state,
            long stateVersion) {
        publisher.publish(new EventEnvelope(ids.get(), clock.instant(), PRODUCER, tenant.value().toString(),
                new EntityRef("placement", instanceId.toString(), placementId.toString(), null), stateVersion,
                "instance.health", Map.of("state", state.wire())));
    }

    private static String tenantOf(ApplyRequestRow request) {
        return request.scope().tenant().value().toString();
    }

    private static EntityRef placementEntity(ApplyRequestRow request) {
        return new EntityRef("placement", request.scope().instance().value().toString(),
                request.scope().placement().value().toString(), null);
    }

    private static long stampOf(ApplyRequestRow request) {
        if (request.stateVersion() == null) {
            throw new IllegalArgumentException("apply_request " + request.applyId().value()
                    + " carries no state_version: the event's only ordering input is the stamp the database assigned, "
                    + "so the row must be read back after the transition");
        }
        return request.stateVersion();
    }
}
