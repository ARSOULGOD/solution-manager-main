package com.solace.sm.cp.identity;

import java.util.UUID;

/**
 * The ids V003 seeds for the three built-in roles. They are primary keys the migration fixes, so a code constant cannot
 * drift from them (an IT reads the table and compares). Only {@link #ADMINISTRATOR} is named by logic: it is the role the
 * API never grants and never unbinds (plan 013 {@code OQ-6 (a)}, {@code OR-12}).
 */
public final class BuiltInRoles {

    public static final UUID ADMINISTRATOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final UUID VIEWER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    public static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private BuiltInRoles() {
    }
}
