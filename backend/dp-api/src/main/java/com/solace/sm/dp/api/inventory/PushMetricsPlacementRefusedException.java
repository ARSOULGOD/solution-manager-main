package com.solace.sm.dp.api.inventory;

/**
 * {@code addPlacement} was refused because the instance's declared package version has a {@code solace_event} metric
 * source (plan 008 OQ-8.9 (a), {@code BE-D47}). A push reading is attributed to a placement by the instance key in its
 * payload ({@code PushAttribution}); with a second live placement every event would be {@code ambiguous_placement} and
 * dropped, and {@code I1} forbids a placement field in the MI's event. Failing here is failing closed and visibly, at
 * the moment of the action. {@code 409}. Names no metric.
 */
public final class PushMetricsPlacementRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PushMetricsPlacementRefusedException() {
        super("the package declares push metrics, which cannot be attributed to one of several placements");
    }
}
