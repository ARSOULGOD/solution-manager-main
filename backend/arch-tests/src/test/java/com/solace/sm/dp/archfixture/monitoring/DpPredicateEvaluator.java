package com.solace.sm.dp.archfixture.monitoring;

import java.util.Map;

/**
 * Planted violation (plan 011 P11-4.1, M-Q15): a predicate evaluator in the data plane. The control plane's evaluator is
 * the only one ({@code UI-D2} is one grammar); the data plane stores the list it is handed and evaluates nothing.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class DpPredicateEvaluator {

    private DpPredicateEvaluator() {
    }

    public static boolean holds(Map<String, Object> when, Map<String, Object> values) {
        return when.entrySet().stream().allMatch(e -> e.getValue().equals(values.get(e.getKey())));
    }
}
