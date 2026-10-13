package com.solace.sm.dp.api.health;

import java.util.UUID;

/**
 * The data plane's read side for an instance's health (plan 010 T7.2). It reads what the poller persisted and touches
 * no host: a request never waits on SSH. Every call is tenant-scoped by {@code organizationId}, which is
 * {@code Session.organizationId()}, never a request field (I5); the caller has already authorized the instance, and an
 * instance that is not this organization's reads as one with nothing polled, so the answer discloses nothing.
 */
public interface HealthService {

    HealthView health(UUID organizationId, UUID instanceId);
}
