package com.solace.sm.cp.identity;

/**
 * The closed vocabulary {@code sm_control.change_audit.audit_operation}'s {@code CHECK} constraint
 * accepts ({@code OR-19}). A single source of truth so a Java-side literal can never drift from the
 * database's own vocabulary.
 */
public final class AuditOperations {

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_REFUSED = "LOGIN_REFUSED";
    public static final String LOGOUT = "LOGOUT";
    public static final String CREDENTIAL_ROTATED = "CREDENTIAL_ROTATED";
    public static final String CREDENTIAL_ROTATION_REFUSED = "CREDENTIAL_ROTATION_REFUSED";
    public static final String LOCKOUT_CLEARED = "LOCKOUT_CLEARED";

    /** V013 (plan 010 T2.0): the governance operations. */
    public static final String INSTANCE_DECLARED = "INSTANCE_DECLARED";
    public static final String PACKAGE_LOADED = "PACKAGE_LOADED";
    public static final String ENTITLEMENT_SET = "ENTITLEMENT_SET";
    public static final String TARGET_REGISTERED = "TARGET_REGISTERED";
    public static final String TARGET_RETIRED = "TARGET_RETIRED";

    /** V014 (plan 011 P11-2.3): the per-instance dashboard's writes, one operation per user-visible action. */
    public static final String DASHBOARD_WIDGET_SAVED = "DASHBOARD_WIDGET_SAVED";
    public static final String DASHBOARD_LAYOUT_SET = "DASHBOARD_LAYOUT_SET";
    public static final String DASHBOARD_WIDGET_HIDDEN = "DASHBOARD_WIDGET_HIDDEN";
    public static final String DASHBOARD_RESET = "DASHBOARD_RESET";

    /**
     * V017 (plan 012 T4, {@code OR-121}): the control plane's half of undeclare - an instance's governance node is
     * retired. The data plane's half is {@code instance_retirement} in {@code sm_data}; neither plane writes the
     * other's schema (I7).
     */
    public static final String INSTANCE_UNDECLARED = "INSTANCE_UNDECLARED";

    /**
     * V018 (plan 013 T2.1, {@code OR-121}): the hierarchy writes of the registration API - a sub-organization or an
     * environment is created, any of the three node kinds is renamed.
     */
    public static final String HIERARCHY_NODE_CREATED = "HIERARCHY_NODE_CREATED";
    public static final String HIERARCHY_NODE_RENAMED = "HIERARCHY_NODE_RENAMED";

    /**
     * V018 (plan 013 W3, OQ-6 (a)): the operator user. A local principal is created, deactivated, or has its
     * credential reset; a role binding is granted or revoked. The credential hash is never copied into an audit row.
     */
    public static final String PRINCIPAL_CREATED = "PRINCIPAL_CREATED";
    public static final String PRINCIPAL_DEACTIVATED = "PRINCIPAL_DEACTIVATED";
    public static final String PRINCIPAL_CREDENTIAL_RESET = "PRINCIPAL_CREDENTIAL_RESET";
    public static final String ROLE_BINDING_GRANTED = "ROLE_BINDING_GRANTED";
    public static final String ROLE_BINDING_REVOKED = "ROLE_BINDING_REVOKED";

    /**
     * V021 (plan 013 T2, {@code BE-D66}, {@code OR-130}): the Connection config object's writes. A delete leaves
     * no row (unlike a Target's retirement), so it carries its own operation name rather than reusing a
     * TARGET_RETIRED-shaped one.
     */
    public static final String CONNECTION_CREATED = "CONNECTION_CREATED";
    public static final String CONNECTION_UPDATED = "CONNECTION_UPDATED";
    public static final String CONNECTION_DEACTIVATED = "CONNECTION_DEACTIVATED";
    public static final String CONNECTION_DELETED = "CONNECTION_DELETED";

    /**
     * V026 (plan 015 T1.3, written V021 before the {@code OR-147} reconciliation; {@code OR-126} A8, {@code BE-D70}): the agent path. A principal issues an enrolment token and
     * revokes an agent; the agent itself is the actor of the other two (token consumption with its CP target row, and the
     * {@code OR-27} governance node). The database refuses an agent actor on any other operation.
     */
    public static final String AGENT_ENROLMENT_ISSUED = "AGENT_ENROLMENT_ISSUED";
    public static final String AGENT_REVOKED = "AGENT_REVOKED";
    public static final String AGENT_ENROLLED = "AGENT_ENROLLED";
    public static final String AGENT_INSTANCE_REGISTERED = "AGENT_INSTANCE_REGISTERED";

    private AuditOperations() {
    }
}
