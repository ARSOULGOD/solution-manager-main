package com.solace.sm.dp.apply.model;

/**
 * {@code SM-D15}'s restart-strategy ladder, verbatim — the values {@code strategy_known} admits.
 *
 * <p>Whether a given PACKAGE supports a value is admission-checked elsewhere: it is package-relative
 * and inexpressible in a CHECK (design §3.1).
 */
public enum Strategy {

    MANUAL("manual"),
    ROLLING_HEALTH_GATED("rolling-health-gated"),
    ROLLING_NO_VALIDATION("rolling-no-validation"),
    RESTART_ALL("restart-all"),
    SCHEDULED("scheduled");

    private final String sql;

    Strategy(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    public static Strategy fromSql(String sql) {
        for (Strategy s : values()) {
            if (s.sql.equals(sql)) {
                return s;
            }
        }
        throw new IllegalArgumentException("not a strategy this schema can hold: " + sql);
    }
}
