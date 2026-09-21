package com.solace.sm.cp.authz.archfixture;

import java.sql.Connection;

/** Planted violation proving CP JDBC remains forbidden outside {@code cp.persistence}. */
public final class BadCpJdbcOutsidePersistenceClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
