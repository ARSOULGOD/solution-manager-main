package com.solace.sm.launcher;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.util.List;
import java.util.Locale;

/** Maps the control plane's stored capability vocabulary ({@code apply-config}) to the data plane's enum. */
final class AgentCapabilities {
    private AgentCapabilities() {
    }

    static AgentCapability of(String stored) {
        try {
            return AgentCapability.valueOf(stored.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new GatewayPortException(GatewayCode.CAPABILITY_MISMATCH, "unsupported agent capability");
        }
    }

    static List<AgentCapability> all(List<String> stored) {
        return stored.stream().map(AgentCapabilities::of).toList();
    }
}
