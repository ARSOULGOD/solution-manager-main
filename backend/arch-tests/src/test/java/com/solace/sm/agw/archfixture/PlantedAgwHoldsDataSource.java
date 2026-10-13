package com.solace.sm.agw.archfixture;

import javax.sql.DataSource;

/** Planted violation: the gateway holding a {@code DataSource} (rule {@code agentGatewayHoldsNoDatasource}). */
public final class PlantedAgwHoldsDataSource {
    private DataSource dataSource;

    public DataSource dataSource() {
        return dataSource;
    }
}
