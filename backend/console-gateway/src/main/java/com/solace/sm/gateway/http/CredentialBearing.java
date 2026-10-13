package com.solace.sm.gateway.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose request body carries a private key or a credential ({@code registerTarget},
 * {@code rotateTargetCredential}, {@code createUser}, {@code resetUserCredential}; plan 013 OQ-14 (b)).
 * {@link CredentialTransportInterceptor} refuses such a request, {@code 403 INSECURE_TRANSPORT}, before its body is read,
 * unless the channel is secure.
 *
 * <p>The mark is on the HANDLER, not on a path, so no spelling of the URL can reach a credential-bearing handler without
 * the check. {@code CredentialBearingRecordsTest} (arch-tests) fails the build if a handler takes a request record with a
 * credential-named field and is not marked.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CredentialBearing {
}
