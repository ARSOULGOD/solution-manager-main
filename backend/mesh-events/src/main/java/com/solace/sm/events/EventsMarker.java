package com.solace.sm.events;

/**
 * Package-root marker for {@code mesh-events} — the shared event envelope model (backend design §3,
 * {@code BE-D3}). Task 9 of plan 001 fills this module.
 *
 * <p>This module is deliberately <b>plane-neutral</b>: it depends on neither {@code cp-*} nor
 * {@code dp-*}, which is what makes it safe for either plane to consume and what stops it becoming
 * an {@code I7} conduit. {@code PlaneBoundaryTest.eventsNeverImportCp} / {@code eventsNeverImportDp}
 * enforce that, and they need at least one class here to have anything to check — ArchUnit fails a
 * rule that matches no classes, so an empty module would make them dead rules rather than live ones.
 */
public final class EventsMarker {

    private EventsMarker() {
    }
}
