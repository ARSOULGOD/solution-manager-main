package com.solace.sm.gateway.archfixture;

import org.flywaydb.core.Flyway;

/** Planted violation: the gateway holding a Flyway type (rule {@code theGatewayHoldsNoDatasource}). */
public final class BadGatewayFlywayClass {

    private Flyway flyway;

    public Flyway flyway() {
        return flyway;
    }
}
