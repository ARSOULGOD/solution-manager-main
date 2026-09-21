package com.solace.sm.cp.archfixture;

import com.solacesystems.jcsmp.JCSMPFactory;

/**
 * Planted violation for the JCSMP-confinement rule: a class outside {@code com.solace.sm.dp.meshbridge}
 * touching the broker SDK. Design §2: "All mesh I/O for the backend goes through [mesh-bridge]; no
 * other module holds a broker session."
 */
public final class BadCpJcsmpClass {

    Object plantedViolation() {
        return JCSMPFactory.onlyInstance();
    }
}
