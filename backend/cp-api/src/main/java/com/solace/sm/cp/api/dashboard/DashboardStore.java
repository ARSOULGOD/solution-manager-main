package com.solace.sm.cp.api.dashboard;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.UUID;

/**
 * The per-instance shared dashboard (M-Q10 (a), M-Q23; PROPOSED {@code SM-D31}).
 *
 * <p>⛔ <b>Authorization is the gateway's, existence is the gateway's.</b> This port checks no verb: the route's
 * operation ({@code dashboard.view}, {@code dashboard.edit}, {@code dashboard.reset}) is enforced before it is
 * called. And a dashboard exists only for an instance the DATA PLANE reports as active; the control plane cannot
 * ask the data plane ({@code I7}), so the gateway checks {@code InstanceInventoryService.findActive} first. A
 * pending or abandoned instance (an {@code OR-90} orphan is one) therefore never gets a dashboard, and the
 * operator runbook's orphan delete never meets the {@code ON DELETE RESTRICT} key on
 * {@code sm_control.dashboard}.
 *
 * <p>Every method takes the organization explicitly and binds it in its own statement ({@code I5}); the gateway
 * passes {@code Session.organizationId()}, never a request field. Every write takes the revision the caller read
 * ({@code 0} when nothing is stored yet) and is refused as {@link InvalidDashboardException.Kind#STALE_REVISION}
 * if it is not the stored one.
 */
public interface DashboardStore {

    /**
     * The dashboard as it renders now. Writes nothing, even for an instance whose dashboard is not stored yet.
     *
     * <p>⛔ <b>Precondition: the caller has already resolved {@code instanceId} under {@code organization} and
     * authorized reach to it.</b> For any id, including another organization's or an unknown one, this returns a
     * fresh view (revision 0, derived defaults) rather than refusing, so whether an instance exists, and whether the
     * caller may see it, rests entirely on the gateway's check (plan 011 P11-7.2: an unknown or foreign instance id
     * gets 404 before this is called).
     */
    DashboardView get(TenantId organization, UUID instanceId, DefaultLayerInput input);

    /** Stores a shared widget; {@code DASHBOARD_WIDGET_SAVED}. The view carries the id the server gave it. */
    DashboardView saveWidget(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, WidgetSubmission widget, DefaultLayerInput input);

    /** Places every widget, default ones included, exactly once; {@code DASHBOARD_LAYOUT_SET}. */
    DashboardView setLayout(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, String layoutJson, DefaultLayerInput input);

    /** Hides or shows a product-default widget, never deletes it; {@code DASHBOARD_WIDGET_HIDDEN}. */
    DashboardView setHidden(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, String widgetId, boolean hidden, DefaultLayerInput input);

    /** Clears the hide overrides and the default placements, keeps every shared widget; {@code DASHBOARD_RESET}. */
    DashboardView reset(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, DefaultLayerInput input);
}
