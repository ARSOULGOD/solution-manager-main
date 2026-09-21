package com.solace.sm.launcher.archfixture;

import com.solace.sm.dp.apply.runtime.RuntimeAdapter;

/** Deliberate violation proving launcher code cannot wire a runtime adapter. */
public final class BadLauncherRuntimeAdapterClass {

    private RuntimeAdapter runtimeAdapter;
}
