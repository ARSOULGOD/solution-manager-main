package com.solace.sm.dp.config;

/** An instance's version and the head of its generation chain, as read under the row lock. */
public record InstanceHead(long stateVersion, long lastGeneration) {
}
