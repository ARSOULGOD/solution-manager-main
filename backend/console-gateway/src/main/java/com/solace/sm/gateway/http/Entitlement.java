package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code Entitlement}: how many instances of one solution the session's organization may
 * declare, and how many it has declared and not retired. Carries no organization and no principal: who set it is audit,
 * not something every {@code solution.view} holder is shown.
 *
 * @param declaredInstances instances not retired by undeclare (plan 012 T4's count)
 * @param setAt             ISO-8601 instant
 */
public record Entitlement(String solution, int maxInstances, long declaredInstances, String setAt) {

    public Entitlement {
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(setAt, "setAt");
    }
}
