package com.solace.sm.gateway.archfixture;

import org.springframework.jdbc.core.JdbcTemplate;

/** Planted violation: the gateway holding a {@code JdbcTemplate} (rule {@code theGatewayHoldsNoDatasource}). */
public final class BadGatewayJdbcTemplateClass {

    private JdbcTemplate jdbc;

    public JdbcTemplate jdbc() {
        return jdbc;
    }
}
