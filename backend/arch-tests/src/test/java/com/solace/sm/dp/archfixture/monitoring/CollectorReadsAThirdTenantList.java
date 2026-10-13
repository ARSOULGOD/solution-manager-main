package com.solace.sm.dp.archfixture.monitoring;

import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;

/**
 * Planted violation (plan 011 P11-7.2, {@code OR-92} OQ-6 (a)): a fifth caller of
 * {@code TenantEnumerationRepository#tenantsWithActiveInstances()}. The cross-tenant list has exactly four named callers
 * (the health poller, the fleet batch advancer, the key re-wrap command and the metrics background loop); a fifth is a new
 * tenant enumeration, which needs an owner ruling and not a convenient call.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class CollectorReadsAThirdTenantList {

    private CollectorReadsAThirdTenantList() {
    }

    public static Object fifthCaller(TenantEnumerationRepository tenants) {
        return tenants.tenantsWithActiveInstances();
    }
}
