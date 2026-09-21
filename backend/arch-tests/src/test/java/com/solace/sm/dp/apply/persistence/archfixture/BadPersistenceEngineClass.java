package com.solace.sm.dp.apply.persistence.archfixture;

import com.solace.sm.dp.apply.engine.AttemptService;

/**
 * A planted violation: the persistence package reaching UP into the engine.
 *
 * <p>Plan 004's carried-forward watch item, in its own words: {@code ApplyEngineConfiguration}'s
 * javadoc states the direction as verified by inspection, but no rule enforced it the way
 * {@code jdbcIsConfinedToApplyPersistence} enforces the data-plane/persistence boundary. The two
 * packages would become a cycle the moment one edit pointed the other way, and a cycle here means
 * the repositories can see the services built on them.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadPersistenceEngineClass {

    private AttemptService service;

    public AttemptService service() {
        return service;
    }
}
