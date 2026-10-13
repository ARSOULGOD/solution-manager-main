package com.solace.sm.dp.apply.persistence.archfixture;

import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;

/**
 * Planted violation (OR-113 (7)): a third caller of OR-44's list. Never executed.
 * <b>Do not copy this pattern into production code.</b>
 */
public final class BadWorkerListCaller {

    private BadWorkerListCaller() {
    }

    public static Object thirdCaller(TenantEnumerationRepository tenants) {
        return tenants.tenantsWithNonTerminalWork();
    }
}
