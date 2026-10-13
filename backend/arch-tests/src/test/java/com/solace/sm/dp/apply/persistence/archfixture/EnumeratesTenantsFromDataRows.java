package com.solace.sm.dp.apply.persistence.archfixture;

/**
 * Planted violation (OR-113 (7)): application code that enumerates tenants from the rows of a data table. Only an
 * operator {@code *Main} command run with the owner credential may do that, and the two ruled lists (OR-44, OR-49) stay
 * the only application-side enumerations.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class EnumeratesTenantsFromDataRows {

    private EnumeratesTenantsFromDataRows() {
    }

    public static String distinct() {
        return "SELECT DISTINCT tenant_id FROM sm_metrics.metrics_raw";
    }

    public static String grouped() {
        return "SELECT tenant_id, count(*) FROM sm_data.apply_request GROUP  BY  tenant_id";
    }
}
