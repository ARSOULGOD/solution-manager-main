package com.solace.sm.launcher.archfixture;

import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;

/** Deliberate violation: a launcher class that is not one of the named adapters reaching for the cp-api agent seam. */
public final class BadLauncherCpAgentClass {

    private ControlPlaneAgentGateway controlPlane;
}
