package com.solace.sm.dp.apply.model;

/**
 * {@code apply_request.operation} — the closed set {@code op_known} enforces.
 *
 * <p>The SQL spelling is lower-case and the Java name is not, so the mapping is explicit here rather
 * than {@code name().toLowerCase()}: a locale-sensitive case fold is exactly the kind of thing that
 * works everywhere but Turkey.
 */
public enum Operation {

    APPLY("apply"),
    RESTART("restart"),
    STOP("stop");

    private final String sql;

    Operation(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static Operation fromSql(String sql) {
        for (Operation o : values()) {
            if (o.sql.equals(sql)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not an operation this schema can hold: " + sql);
    }
}
