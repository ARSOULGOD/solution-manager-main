package com.solace.sm.dp.metrics.push.archfixture;

import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;

/** Planted violation: the push path asking for the tenant list itself, a fifth caller of tenantsWithActiveInstances() (OQ-6 (a)). */
public final class BadPushTenantListClass {
    TenantEnumerationRepository tenants;
}
