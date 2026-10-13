package com.solace.sm.dp.archfixture;

import com.solace.sm.launcher.SolutionManagerApplication;

/** Deliberate violation proving that production code cannot use the composition root. */
public final class BadDpDependsOnLauncher {

    private SolutionManagerApplication application;
}
