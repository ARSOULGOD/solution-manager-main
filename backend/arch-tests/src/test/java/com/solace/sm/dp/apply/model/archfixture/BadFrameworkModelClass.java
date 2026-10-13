package com.solace.sm.dp.apply.model.archfixture;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * A planted violation: the apply MODEL reaching for a Spring type.
 *
 * <p>The model is the one layer that is framework-free by design — it is the vocabulary shared by the
 * engine, the repositories and (later) the API and event mappers, and a Spring or JDBC import in it
 * would make every one of those consumers inherit the dependency. Deliberately a {@code spring-tx}
 * type rather than a JDBC one, so this fixture trips {@code applyModelIsFrameworkFree} and NOT
 * {@code jdbcIsConfinedToApplyPersistence} — two rules proven by two distinct violations, not one
 * violation counted twice.
 */
public final class BadFrameworkModelClass {

    private TransactionTemplate transactions;

    public TransactionTemplate transactions() {
        return transactions;
    }
}
