package com.solace.sm.cp.persistence.archfixture;

import java.sql.Connection;

/** Legal JDBC use in the only CP package permitted to own persistence adapters. */
public final class LegalCpPersistenceJdbcClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
