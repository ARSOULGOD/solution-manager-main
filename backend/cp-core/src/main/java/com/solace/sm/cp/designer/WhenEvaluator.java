package com.solace.sm.cp.designer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The control plane's one predicate evaluator ({@code UI-D2}: equality plus AND, nothing else), extracted unchanged from
 * {@link DesignerProjectorImpl} (plan 011 P11-4.1, M-Q15) so {@code project()} and {@link MetricApplicabilityImpl} cannot
 * disagree about what a {@code when:} means. {@code project()} uses it for a field's and an action's visibility, the
 * metric applicability for a metric's: the same function over the same kind of value map.
 *
 * <p>There is no second evaluator: the data plane stores the list this produces and never evaluates anything
 * ({@code TheListsAreEvaluatedByCpCoreNeverTheDp} in {@code arch-tests}).
 */
final class WhenEvaluator {

    private WhenEvaluator() {
    }

    /**
     * UI-D2: equality + AND only, over defaults-applied values.
     *
     * @return the keys of {@code when} that do not hold; empty means the predicate holds (a {@code null} predicate always does)
     */
    static List<String> evaluate(Map<String, Object> when, Map<String, Object> values) {
        if (when == null) {
            return List.of();
        }
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Object> e : when.entrySet()) {
            if (!strictEquals(values.get(e.getKey()), e.getValue())) {
                failed.add(e.getKey());
            }
        }
        return failed;
    }

    /**
     * JS {@code ===}. Java's boxed {@code Integer(5).equals(Long(5L))} is false where JS's
     * {@code 5 === 5} is true (JS has one numeric type) — special-cased here so a value that
     * survived two different Jackson integral widenings still compares equal.
     */
    static boolean strictEquals(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return Objects.equals(a, b);
    }
}
