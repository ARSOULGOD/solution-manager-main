package com.solace.sm.dp.api.inventory;

/**
 * What an edit would do, decided from the stored record and the request alone, before any connection is made (plan 013
 * T1.5): whether it changes anything, and whether it must therefore scan the host. The gateway asks first to refuse a
 * grammar error and to answer 404 before anything else; the data plane then decides the same thing again under its lock,
 * so the plan is never trusted, and a scan is charged and audited from the data plane's own decision, told through
 * {@link ScanAccounting} (review {@code W4C-3}), not from this plan.
 *
 * @param changes whether any named field differs from the stored record; {@code false} means the edit writes nothing
 * @param rescan  whether the host, the port or the pin changes: the host is then scanned afresh and must still offer the
 *                (new or kept) pin. A change of the SSH user alone does not rescan.
 */
public record EditPlan(boolean changes, boolean rescan) {

    public EditPlan {
        if (rescan && !changes) {
            throw new IllegalArgumentException("an edit that changes nothing does not rescan");
        }
    }
}
