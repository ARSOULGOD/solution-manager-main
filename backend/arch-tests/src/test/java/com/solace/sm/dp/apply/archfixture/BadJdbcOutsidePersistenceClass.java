package com.solace.sm.dp.apply.archfixture;

import java.sql.Connection;

/**
 * Planted violation of {@code jdbcIsConfinedToApplyPersistence}: a data-plane class that is NOT in
 * {@code com.solace.sm.dp.apply.persistence..} yet holds a JDBC type. Test scope, arch-tests only,
 * filtered out of the real rule run by {@code PlaneBoundaryTest.ExcludeArchFixtures}.
 */
public final class BadJdbcOutsidePersistenceClass {

    private Connection connection;

    public Connection connection() {
        return connection;
    }
}
