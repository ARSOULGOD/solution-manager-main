package com.solace.sm.gateway.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a gateway handler method with the OpenAPI {@code operationId} it serves.
 *
 * <p>Every handler method the MVC mapping registers must carry one whose value
 * {@link OperationPolicy#classify(String)} can classify, or the context fails to start
 * ({@link OperationCoverageValidator}). There is no default and no bypass: an operation with no
 * approved verb does not get a handler at all, rather than a handler that skips the check.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConsoleOperation {

    /** The OpenAPI {@code operationId}, exactly as {@code contracts/openapi/console.yaml} spells it. */
    String value();

    /**
     * The {@link ResourceResolver} bean type that resolves the {@code ResourceRef} this CATALOG
     * operation is authorized against, resolved from the request BEFORE the handler runs
     * ({@link AuthenticationInterceptor}).
     *
     * <p>⛔ <b>Mandatory for every CATALOG operation, forbidden for every SESSION_ESTABLISHING or
     * LOCAL operation.</b> {@link OperationCoverageValidator} fails startup if a CATALOG operation
     * leaves this at the default, if a SESSION_ESTABLISHING/LOCAL operation sets it to anything
     * else, or if the declared type has no bean in the context. The default, {@link NoResource},
     * means "no resolver declared" and is never itself a usable resolver.
     */
    Class<? extends ResourceResolver> resource() default NoResource.class;
}
