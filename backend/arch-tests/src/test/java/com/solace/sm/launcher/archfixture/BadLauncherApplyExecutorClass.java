package com.solace.sm.launcher.archfixture;

import com.solace.sm.dp.apply.engine.ApplyExecutor;

/** Deliberate violation proving launcher code cannot wire an apply executor. */
public final class BadLauncherApplyExecutorClass {

    private ApplyExecutor applyExecutor;
}
