package com.solace.sm.dp.api.inventory;

import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.Objects;
import java.util.UUID;

/**
 * Plan 008 S3 ({@code BE-D47}): the second placement of an active instance. The caller (the gateway) has resolved
 * {@code placement}'s probe and {@code metrics} from the instance's DECLARED package version, never from the request
 * body, because the data plane reads no package ({@code UI-D2}).
 *
 * @param targetId    the target the new placement runs on; {@code target.use} on it was settled by the gateway
 * @param placement   the browser-named fields, checked against the target's envelope (B2) by the service
 * @param requestedBy the acting principal's id, recorded as {@code placement.created_by} (OR-125 (1), sm_data V026; it was
 *                    carried but not recorded until then, W2 review W2A-1)
 * @param metrics     what the gateway resolved from the package for this version (plan 011 M-Q5, M-Q14): the definitions
 *                    (a {@code solace_event} source refuses the add, {@code BE-D47}), and this placement's source-port to
 *                    host-port map. Only the canonical constructor exists on purpose: a production path that built this
 *                    with {@link MetricsDeclaration#none()} would silently give the new placement no metrics endpoints.
 */
public record AddPlacementCommand(UUID targetId, PlacementSpec placement, UUID requestedBy,
        MetricsDeclaration metrics) {

    public AddPlacementCommand {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(metrics, "metrics");
    }
}
