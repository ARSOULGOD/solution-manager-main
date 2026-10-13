package com.solace.sm.dp.apply.persistence.archfixture;

import java.sql.Connection;

/**
 * The counterpart fixture: a class INSIDE {@code com.solace.sm.dp.apply.persistence..} holding the
 * same JDBC type. The rule must leave it alone, or it is a rule that rejects everything and proves
 * nothing.
 */
public final class LegalPersistenceJdbcClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
