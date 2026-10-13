package com.solace.sm.cp.archfixture;

/**
 * Planted violation (OR-113 (7), extended by {@code OR-140}): control-plane code that enumerates organizations from the rows of a
 * data table. The rule used to read only {@code tenant_id}; the control plane spells it {@code organization_id}. Only the named
 * registry class (the sweep job's one read) and the operator commands may do this.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class EnumeratesOrganizationsFromDataRows {

    private EnumeratesOrganizationsFromDataRows() {
    }

    public static String distinct() {
        return "SELECT DISTINCT r.organization_id FROM sm_control.agent_registration r";
    }

    public static String grouped() {
        return "SELECT count(*) FROM sm_control.target GROUP  BY  organization_id";
    }
}
