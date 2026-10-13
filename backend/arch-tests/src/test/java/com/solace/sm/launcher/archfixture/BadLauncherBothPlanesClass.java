package com.solace.sm.launcher.archfixture;

import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.dp.api.agw.LeaseGrant;

/** Deliberate violation: a launcher class that is not one of the four named ones composing cp-api with dp-api. */
public final class BadLauncherBothPlanesClass {

    private TargetDirectory controlPlane;
    private LeaseGrant dataPlane;
}
