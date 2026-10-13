package com.solace.sm.cp.events;

import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.events.EntityRef;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.EventPublisher;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Emits {@code catalog.package}: the CONTROL plane's event, because the package catalog is control-plane state
 * (backend design section 3; {@code envelope.schema.json} closes {@code producer} to {@code cp-core | dp-core}, so
 * a data-plane emitter would have to mislabel itself). Plan 010 T3.3, executing plan 007 Task 5a.
 *
 * <p>It is an emitter, not an {@link EventPublisher}: it CALLS one, injected by interface type and wrapped by the
 * control plane's own after-commit decorator ({@link CatalogEventsConfiguration}). It holds no broker session and
 * no transport type, so it adds nothing to the certified publisher inventory. {@link #onPackageLoaded} is called
 * from inside the catalog's transaction; the decorator defers the publish to after that transaction commits and
 * discards it on rollback.
 *
 * <p>The payload carries identifiers only ({@code I6}): the solution name and the canonical package-version text.
 * The topic is derived from the tenant alone ({@code sm/v1/<tenant>/notify/catalog/package}); the schema forbids
 * instance and placement identity on this event and the shape validator rejects either.
 */
public final class CatalogEventEmitter {

    private static final String PRODUCER = "cp-core";
    private static final String EVENT = "catalog.package";

    private final EventPublisher publisher;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public CatalogEventEmitter(EventPublisher publisher, Clock clock, Supplier<UUID> ids) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * @param tenant         the organization, as the topic's tenant level ({@code I5}: the caller's own session
     *                       organization, never a request field)
     * @param solution       the package's declared solution
     * @param packageVersion the stored canonical {@code packageVersion} text, e.g. {@code [1]} or {@code ["2.4.0"]}
     *                       (the notify schema leaves this field untyped - contracts/events Known gap 4)
     * @param stateVersion   the catalog entry's state version after this load (the sole ordering input, {@code B/D15})
     * @throws IllegalArgumentException only for a malformed event, by {@link EventPublisher}'s contract - a caller bug
     */
    public void onPackageLoaded(TenantId tenant, String solution, String packageVersion, long stateVersion) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(packageVersion, "packageVersion");
        publisher.publish(new EventEnvelope(ids.get(), clock.instant(), PRODUCER, tenant.value().toString(),
                new EntityRef("solution", null, null, solution), stateVersion, EVENT,
                Map.of("solution", solution, "packageVersion", packageVersion)));
    }
}
