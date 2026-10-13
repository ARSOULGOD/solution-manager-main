package com.solace.sm.dp.apply.engine.archfixture;

import java.sql.Connection;

/**
 * A planted violation: the apply ENGINE reaching for a raw JDBC {@link Connection}.
 *
 * <p>It exists to answer a question the plan asked rather than assumed — does
 * {@code jdbcIsConfinedToApplyPersistence} already cover {@code ..dp.apply.engine..}, or is a second
 * rule needed? The rule is written as "every package OUTSIDE
 * {@code com.solace.sm.dp.apply.persistence}", so it should; this class is what turns "should" into
 * a failing check. Excluded from the real run by {@code ExcludeArchFixtures}.
 */
public final class BadEngineJdbcClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
