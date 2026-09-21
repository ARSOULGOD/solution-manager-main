package com.solace.sm.cp.authz;

/** The canonical hierarchy level requested below a session-derived organization. */
public enum ScopeKind {
    INSTALL(0),
    ORGANIZATION(1),
    ORGANIZATION_UNIT(2),
    ENVIRONMENT(3),
    SOLUTION(4),
    INSTANCE(5);

    private final int depth;

    ScopeKind(int depth) {
        this.depth = depth;
    }

    int depth() {
        return depth;
    }
}
