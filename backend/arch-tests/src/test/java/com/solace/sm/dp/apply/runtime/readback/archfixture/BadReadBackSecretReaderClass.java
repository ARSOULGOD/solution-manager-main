package com.solace.sm.dp.apply.runtime.readback.archfixture;

import com.solace.sm.dp.secret.SecretResolver;

/**
 * A planted violation of {@code readBackNeverHoldsASecretReader}: read-back code that holds the secret store's read side,
 * which is how a read-back secret could be fetched, compared by hand, or adopted. It exists so the rule can be shown to
 * FIRE; without it the rule is a promise nobody has proved can fail.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadReadBackSecretReaderClass {

    private final SecretResolver resolver;

    public BadReadBackSecretReaderClass(SecretResolver resolver) {
        this.resolver = resolver;
    }

    public SecretResolver resolver() {
        return resolver;
    }
}
