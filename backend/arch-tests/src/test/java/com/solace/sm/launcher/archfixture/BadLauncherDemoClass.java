package com.solace.sm.launcher.archfixture;

import com.solace.sm.demo.archfixture.DemoCompositionType;

/** Deliberate violation proving launcher code cannot use demo composition. */
public final class BadLauncherDemoClass {

    private DemoCompositionType demoComposition;
}
