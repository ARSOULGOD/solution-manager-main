package com.solace.sm.dp.metrics.push.archfixture;

import com.solace.sm.dp.secret.SecretResolver;

/** Planted violation: the push plan or decoder reaching the secret store (a secret-typed instanceKey could then be resolved). */
public final class BadPushSecretReaderClass {
    SecretResolver resolver;
}
