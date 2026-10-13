package com.solace.sm.cp.api.dashboard;

import java.util.Objects;
import java.util.UUID;

/**
 * One instance's dashboard as it renders: the stored shared layer, the product-default layer derived from the
 * package, and the layout that places all of them.
 *
 * @param dashboardId   {@code db_} plus the instance id: the dashboard IS the instance's (M-Q10)
 * @param revision      the optimistic-concurrency token a write must present; {@code 0} while nothing is stored yet
 * @param documentJson  the rendered document, JSON text: {@code id}, {@code schemaVersion}, {@code widgets} (default
 *                      widgets carry {@code layer: product-default} and a {@code hidden} flag), {@code layout},
 *                      {@code range}, {@code refresh}
 * @param savedWidgetId the id the server gave the widget a {@code saveWidget} just stored, otherwise {@code null}
 */
public record DashboardView(String dashboardId, UUID instanceId, long revision, String documentJson,
        String savedWidgetId) {

    public DashboardView {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(instanceId, "instanceId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision is not negative");
        }
        Objects.requireNonNull(documentJson, "documentJson");
    }
}
