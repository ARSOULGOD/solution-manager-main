package com.solace.sm.dp.apply.model;

/**
 * {@code apply_request.resolution_outcome} — what the operator confirmed actually happened, and the
 * input §2.2 rule 1's monotonicity consumes: {@code applied} raises the watermark, {@code not_applied}
 * does not.
 */
public enum ResolutionOutcome {

    APPLIED("applied"),
    NOT_APPLIED("not_applied");

    private final String sql;

    ResolutionOutcome(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static ResolutionOutcome fromSql(String sql) {
        for (ResolutionOutcome o : values()) {
            if (o.sql.equals(sql)) {
                return o;
            }
        }
        throw new IllegalArgumentException("not a resolution outcome this schema can hold: " + sql);
    }
}
