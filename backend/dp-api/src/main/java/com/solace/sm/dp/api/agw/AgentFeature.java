package com.solace.sm.dp.api.agw;

/**
 * Optional wire features an agent announces in {@code SessionOpen.capabilities_supported} (plan 015 T2.3). Kept apart
 * from {@link AgentCapability}: a feature is never in a lease's capabilities. Later features are additive.
 */
public enum AgentFeature {
    DISCOVERY,
    REMATERIALIZE,
    DECLARATIONS
}
