package com.solace.sm.gateway.archfixture;

import javax.sql.DataSource;

/** Planted violation: the gateway holding a {@code DataSource} (rule {@code theGatewayHoldsNoDatasource}). */
public final class BadGatewayDataSourceClass {

    private DataSource dataSource;

    public DataSource dataSource() {
        return dataSource;
    }
}
