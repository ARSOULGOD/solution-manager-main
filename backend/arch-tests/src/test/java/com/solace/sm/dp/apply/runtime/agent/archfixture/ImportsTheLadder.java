package com.solace.sm.dp.apply.runtime.agent.archfixture;

import com.solace.sm.dp.config.ConfigLadder;

/**
 * Planted violation (plan 015 W4 note (e)): a class of {@code dp.apply.runtime..} depending on {@code dp.config..}. That is the
 * package cycle the shared {@code StartTolerance} removed: {@code AgentRuntimeAdapter} imported {@code ConfigLadder.START_TOLERANCE} while
 * {@code dp.config} imports {@code dp.apply.runtime}. {@code DpPackageCycleTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class ImportsTheLadder {

    private ImportsTheLadder() {
    }

    public static Class<?> ladder() {
        return ConfigLadder.class;
    }
}
