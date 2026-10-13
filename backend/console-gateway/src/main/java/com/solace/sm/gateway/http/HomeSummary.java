package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code HomeSummary} ({@code AvailabilityOnly} + {@code solutions}).
 *
 * <p>⚠️ Carries NO top-level {@code stateVersion} (owner ruling {@code OR-55}): an aggregate belongs to no single
 * entity, and each solution row carries its own.
 */
public record HomeSummary(@WireOptional Availability availability, List<SolutionRow> solutions) {

    public HomeSummary {
        solutions = List.copyOf(Objects.requireNonNull(solutions, "solutions"));
    }

    /**
     * One catalog solution. {@code instanceCount} is over the instances the caller may see ({@code instance.view} at
     * each instance's own scope); {@code byHealth} counts only those among them where {@code health.view} is also
     * held, keyed in the nine-value vocabulary, and is ABSENT (never zeroed) when none qualifies.
     * {@code stateVersion} is the catalog row's own.
     */
    public record SolutionRow(UUID solutionId, String name, int instanceCount,
                              @WireOptional Map<String, Integer> byHealth, long stateVersion) {

        public SolutionRow {
            Objects.requireNonNull(solutionId, "solutionId");
            Objects.requireNonNull(name, "name");
            if (instanceCount < 0) {
                throw new IllegalArgumentException("instanceCount must not be negative");
            }
            byHealth = byHealth == null ? null : Map.copyOf(byHealth);
        }
    }
}
