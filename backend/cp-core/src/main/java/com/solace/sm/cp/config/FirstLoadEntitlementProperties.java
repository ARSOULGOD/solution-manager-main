package com.solace.sm.cp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code OR-148}: a package load entitles its organization on first load. {@code sm.cp.entitlement.on-first-load.enabled}
 * (default {@code true}); {@code false} restores the refusal. {@code ...max-instances} (default 10, at least 1) is the
 * limit of the entitlement a first load creates.
 */
@ConfigurationProperties(prefix = "sm.cp.entitlement.on-first-load")
public class FirstLoadEntitlementProperties {
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_MAX_INSTANCES = 10;

    private boolean enabled = DEFAULT_ENABLED;
    private int maxInstances = DEFAULT_MAX_INSTANCES;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }
}
