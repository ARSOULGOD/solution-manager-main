package com.solace.sm.launcher.archfixture;

import javax.sql.DataSource;

/** Deliberate violation proving launcher code cannot access JDBC APIs. */
public final class BadLauncherJdbcClass {

    private DataSource dataSource;
}
