package com.solace.sm.gateway.http;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fails context startup if any mapped handler method lacks a classifiable {@link ConsoleOperation},
 * OR if any {@link HandlerMapping} bean in the context is not one of the three shapes this gateway
 * recognizes as safe.
 *
 * <p>Runs after every singleton is instantiated, by which point every {@code HandlerMapping} —
 * including {@link RequestMappingHandlerMapping} — has already finished scanning (its own {@code
 * afterPropertiesSet} runs during its own bean creation, which happens before {@link
 * #afterSingletonsInstantiated()} is invoked on any bean). There is no way to reach production with
 * an unauthorizable endpoint: either every handler is classifiable and every {@code HandlerMapping}
 * is recognized, or the application never comes up.
 *
 * <p>⛔ The second check exists because {@code registry.addInterceptor(...)}
 * ({@link GatewayWebConfiguration#gatewayMvcConfigurer}) only ever attaches
 * {@link AuthenticationInterceptor} to the {@code HandlerMapping} beans Spring's own MVC Java
 * config machinery constructs itself (the annotation-driven mapping, the framework's own resource/
 * view-controller/router-function/bean-name mappings). A {@code HandlerMapping} declared as a plain
 * user {@code @Bean} — like this gateway's own {@link GatewayWebConfiguration#noRouteHandlerMapping}
 * — never receives those interceptors at all. That is safe ONLY because we know exactly what that
 * one bean serves ({@link NoRouteHandler}, unconditionally a 404, needing no authentication). Any
 * OTHER such bean — ours or anyone else's — would be a live, completely unauthenticated route, and
 * {@link AuthenticationInterceptor}'s own fail-closed handler-type check cannot help, because it is
 * never even invoked for that mapping. This is therefore a hard startup gate, not a best-effort scan.
 */
public final class OperationCoverageValidator implements SmartInitializingSingleton {

    private final ApplicationContext context;

    public OperationCoverageValidator(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // DispatcherServlet itself detects HandlerMapping beans including ancestor contexts
        // (WebApplicationContextUtils / BeanFactoryUtils.beansOfTypeIncludingAncestors), so a
        // HandlerMapping bean parked in a parent context would run in production yet be invisible
        // to a scan of this context alone.
        Map<String, HandlerMapping> mappings =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerMapping.class, true, true);
        mappings.forEach(this::validateMapping);
    }

    private void validateMapping(String beanName, HandlerMapping mapping) {
        if (mapping instanceof RequestMappingHandlerMapping requestMapping) {
            requestMapping.getHandlerMethods().values()
                    .forEach(handlerMethod -> validateHandlerMethod(handlerMethod, context));
            requireAuthenticationInterceptorAttached(beanName, requestMapping);
            return;
        }
        if (isTheGatewaysOwnNoRouteHandlerMapping(mapping) || isAnEmptyFrameworkMapping(mapping)) {
            return;
        }
        throw new IllegalStateException("HandlerMapping bean '" + beanName + "' (" + mapping.getClass().getName()
                + ") is neither the RequestMappingHandlerMapping, this gateway's own NoRouteHandler mapping, nor "
                + "an empty framework mapping. A HandlerMapping bean declared outside Spring's own MVC Java "
                + "config machinery never receives the registry's interceptors, so this one would bypass "
                + "authentication entirely — it must be removed, or made to route exclusively through "
                + "NoRouteHandler, before this application may start.");
    }

    /**
     * Being a {@code RequestMappingHandlerMapping} is necessary but NOT sufficient: nothing about
     * the {@code instanceof} check above proves THIS instance ever received
     * {@link AuthenticationInterceptor} via {@link GatewayWebConfiguration#gatewayMvcConfigurer}'s
     * {@code registry.addInterceptor(...)}. A hand-declared second
     * {@code @Bean RequestMappingHandlerMapping} would pass the {@code instanceof} check and serve
     * its own handler methods with no authentication at all. {@code getAdaptedInterceptors()} is
     * the mapping's own, already-initialized interceptor chain (set during that bean's
     * {@code afterPropertiesSet()}, which has already run by the time this validator does) — the
     * same list {@code DispatcherServlet} consults per request — so this inspects exactly what
     * would actually run, not what SHOULD have been configured. ⚠️ Measured: it returns
     * {@code null} (never an empty array) when that list is empty — exactly the hand-declared
     * second mapping's shape — so {@code null} must be treated as "not attached", not fed to
     * {@code Arrays.stream}.
     */
    private static void requireAuthenticationInterceptorAttached(String beanName,
            RequestMappingHandlerMapping requestMapping) {
        HandlerInterceptor[] interceptors = requestMapping.getAdaptedInterceptors();
        boolean attached = interceptors != null
                && Arrays.stream(interceptors).anyMatch(AuthenticationInterceptor.class::isInstance);
        if (!attached) {
            throw new IllegalStateException("RequestMappingHandlerMapping bean '" + beanName
                    + "' has no AuthenticationInterceptor attached, so it would serve its handler methods "
                    + "without authentication. Every RequestMappingHandlerMapping must be constructed "
                    + "through Spring's own MVC Java config machinery so "
                    + "GatewayWebConfiguration#gatewayMvcConfigurer's registry.addInterceptor(...) reaches "
                    + "it — it must be removed before this application may start.");
        }
    }

    /**
     * {@link GatewayWebConfiguration#noRouteHandlerMapping} — recognized by shape, not by bean
     * name: an {@link AbstractUrlHandlerMapping} whose every registered handler is a
     * {@link NoRouteHandler}. {@link NoRouteHandler} unconditionally answers 404 and needs no
     * authentication, so a mapping that serves nothing else is safe to let through unauthenticated.
     */
    private static boolean isTheGatewaysOwnNoRouteHandlerMapping(HandlerMapping mapping) {
        if (!(mapping instanceof AbstractUrlHandlerMapping urlMapping)) {
            return false;
        }
        Map<String, Object> handlers = urlMapping.getHandlerMap();
        return !handlers.isEmpty() && handlers.values().stream().allMatch(handler -> handler instanceof NoRouteHandler)
                && servesNoOtherHandler(urlMapping, true);
    }

    /**
     * Fable review (code-GW-5): {@code getHandlerMap()} lists only the URL-keyed handlers. A mapping also serves its ROOT
     * handler ({@code /}, {@link AbstractUrlHandlerMapping#getRootHandler()}) and its DEFAULT handler
     * ({@link AbstractHandlerMapping#getDefaultHandler()}), and neither appears in the map, so a mapping with an empty map but one
     * of those set would be served with no {@link AuthenticationInterceptor}. Both must be absent, or (for the
     * gateway's own mapping) {@link NoRouteHandler}.
     */
    private static boolean servesNoOtherHandler(AbstractHandlerMapping mapping, boolean noRouteAllowed) {
        if (!isAbsentOr(mapping.getDefaultHandler(), noRouteAllowed)) {
            return false;
        }
        return !(mapping instanceof AbstractUrlHandlerMapping urlMapping)
                || isAbsentOr(urlMapping.getRootHandler(), noRouteAllowed);
    }

    private static boolean isAbsentOr(Object handler, boolean noRouteAllowed) {
        return handler == null || noRouteAllowed && handler instanceof NoRouteHandler;
    }

    /**
     * A framework-constructed mapping that maps nothing yet (Boot's view-controller / bean-name /
     * router-function mappings when nothing is registered against them). Since it serves no
     * content at all, there is nothing to authenticate; it is a real problem only once something
     * is actually registered against it, which the {@code else} branch below catches.
     */
    private static boolean isAnEmptyFrameworkMapping(HandlerMapping mapping) {
        if (mapping instanceof AbstractUrlHandlerMapping urlMapping) {
            return urlMapping.getHandlerMap().isEmpty() && servesNoOtherHandler(urlMapping, false);
        }
        if (mapping instanceof RouterFunctionMapping routerMapping) {
            return routerMapping.getRouterFunction() == null && servesNoOtherHandler(routerMapping, false);
        }
        return false;
    }

    private static void validateHandlerMethod(HandlerMethod handlerMethod, ApplicationContext context) {
        Method method = handlerMethod.getMethod();
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(method, ConsoleOperation.class);
        if (annotation == null) {
            throw new IllegalStateException("handler method " + method
                    + " carries no @ConsoleOperation and therefore cannot be authorized");
        }
        // Throws UnclassifiableOperationException, which is exactly the point: an operation id
        // nobody approved a verb for must not have a live handler.
        OperationClassification classification = OperationPolicy.classify(annotation.value());
        validateResourceResolver(method, annotation, classification, context);
    }

    /**
     * A CATALOG operation is authorized against a resource {@link AuthenticationInterceptor}
     * resolves and checks BEFORE the handler runs (Task 1's mandatory pre-handler authorization).
     * That resolution is only possible if the operation names a real {@link ResourceResolver}
     * bean; a SESSION_ESTABLISHING, LOCAL, AUTHENTICATION_ONLY or REACH operation is never
     * authorized against a resource at all, so it must name none. Either violation must never reach
     * production traffic.
     */
    private static void validateResourceResolver(Method method, ConsoleOperation annotation,
            OperationClassification classification, ApplicationContext context) {
        Class<? extends ResourceResolver> resolverType = annotation.resource();
        boolean isCatalog = classification instanceof OperationClassification.Catalog;
        if (isCatalog) {
            if (resolverType == NoResource.class) {
                throw new IllegalStateException("CATALOG handler method " + method + " (operation '"
                        + annotation.value() + "') declares no @ConsoleOperation(resource=...); every CATALOG "
                        + "operation must name a ResourceResolver so its resource is resolved and authorized "
                        + "before the handler runs");
            }
            int resolverBeanCount = context.getBeanNamesForType(resolverType).length;
            if (resolverBeanCount == 0) {
                throw new IllegalStateException("resource resolver " + resolverType.getName()
                        + " declared by handler method " + method + " (operation '" + annotation.value()
                        + "') has no bean in the context; it cannot be looked up at request time");
            }
            if (resolverBeanCount > 1) {
                // AuthenticationInterceptor#authorize calls context.getBean(annotation.resource()),
                // by type — with two beans of this type in the context, EVERY request through this
                // operation would throw NoUniqueBeanDefinitionException and 500, not just this one
                // handler method's own requests. Refused at startup rather than at first request.
                throw new IllegalStateException("resource resolver " + resolverType.getName()
                        + " declared by handler method " + method + " (operation '" + annotation.value()
                        + "') has " + resolverBeanCount + " beans in the context; exactly one must exist, "
                        + "or every request would 500 on NoUniqueBeanDefinitionException when "
                        + "AuthenticationInterceptor looks it up by type");
            }
        } else if (resolverType != NoResource.class) {
            throw new IllegalStateException("handler method " + method + " (operation '" + annotation.value()
                    + "') is SESSION_ESTABLISHING, LOCAL, AUTHENTICATION_ONLY or REACH and must not declare a resource "
                    + "resolver, but declares " + resolverType.getName());
        }
    }
}
