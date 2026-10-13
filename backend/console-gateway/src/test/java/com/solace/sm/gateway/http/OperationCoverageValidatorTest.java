package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Proves the startup-time coverage check: a mapped handler method with no {@code @ConsoleOperation}
 * must never come up serving traffic. This is deliberately a SEPARATE, minimal context from
 * {@link GatewayTestSupport} — the whole point is to plant exactly one bad handler and nothing else.
 */
class OperationCoverageValidatorTest {

    @Test
    void aHandlerWithoutConsoleOperationFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(BadConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("@ConsoleOperation")),
                "expected an @ConsoleOperation coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aHandlerWithAnUnclassifiableOperationIdFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(UnclassifiableConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("not classifiable")),
                "expected an UnclassifiableOperationException in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    // ── HandlerMapping inspection (coordinator review addition) ────────────────────────────────

    @Test
    void aContextRegisteringAStaticResourceHandlerFailsStartup() {
        // A resource handler mapping is a real HandlerMapping bean whose handler
        // (ResourceHttpRequestHandler) is not a HandlerMethod and is not NoRouteHandler — exactly
        // the shape that would let a static resource bypass authentication entirely if this
        // validator did not refuse to start.
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(ResourceHandlerConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("HandlerMapping")),
                "expected a HandlerMapping coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aContextWithAnExtraHandlerMappingBeanFailsStartup() {
        // A hand-declared HandlerMapping @Bean (as opposed to one Spring's own MVC Java config
        // machinery constructs) never receives the registry's interceptors, regardless of what it
        // serves — so it must be refused outright, not inspected for "is it harmless".
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(ExtraHandlerMappingConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("HandlerMapping")),
                "expected a HandlerMapping coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aContextWithASecondHandDeclaredRequestMappingHandlerMappingFailsStartup() {
        // A hand-declared second RequestMappingHandlerMapping @Bean passes the instanceof check
        // in validateMapping's first branch, but Spring's own WebMvcConfigurationSupport only ever
        // calls .setInterceptors(...) on the ONE RequestMappingHandlerMapping it itself constructs
        // — this one never receives AuthenticationInterceptor, so it would serve unauthenticated
        // if the validator did not separately require the interceptor to be attached.
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(SecondRequestMappingHandlerMappingConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("AuthenticationInterceptor")),
                "expected an AuthenticationInterceptor coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void theNormalGatewayContextStartsSuccessfully() {
        GatewayTestSupport.Harness harness = GatewayTestSupport.newHarness();
        assertNotNull(harness.mockMvc());
    }

    // ── resource resolver coverage (Task 1: mandatory pre-handler authorization) ────────────────

    @Test
    void aCatalogOperationWithNoResolverFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(CatalogWithNoResolverConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("declares no @ConsoleOperation(resource=")),
                "expected a missing-resolver coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aCatalogOperationsResolverWithNoBeanFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(CatalogWithUnregisteredResolverConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("has no bean in the context")),
                "expected a resolver-has-no-bean coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aCatalogOperationsResolverWithTwoBeansFailsContextStartup() {
        // context.getBeanNamesForType(resolverType).length == 2 here — AuthenticationInterceptor
        // looks the resolver up BY TYPE (context.getBean(annotation.resource())), so with two
        // beans of that type every request through this operation would throw
        // NoUniqueBeanDefinitionException and 500. This must be refused at startup, not per-request.
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(CatalogWithTwoResolverBeansConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("exactly one must exist")),
                "expected a two-resolver-beans coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Test
    void aLocalOperationDeclaringAResolverFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(LocalOperationWithResolverConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("must not declare a resource resolver")),
                "expected a local-operation-with-resolver coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    /**
     * The fourth classification kind ({@code AuthenticationOnly} — console-integration.md §5.2
     * rows 8-10) must be refused exactly like SESSION_ESTABLISHING/LOCAL when it declares a
     * resolver: {@code listOrganizations} derives its own result from the session's bindings and
     * has nothing for {@link AuthenticationInterceptor} to resolve or authorize against.
     */
    @Test
    void anAuthenticationOnlyOperationDeclaringAResolverFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(AuthenticationOnlyWithResolverConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("must not declare a resource resolver")),
                "expected an authentication-only-with-resolver coverage failure somewhere in the cause "
                        + "chain, got: " + causeChainMessages(failure));
    }

    /**
     * The fifth classification kind ({@code Reach} — S0 point 24) resolves NO resource by
     * construction, so a reach operation that names a resolver is a contradiction and must not start.
     */
    @Test
    void aReachOperationDeclaringAResolverFailsContextStartup() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(ReachWithResolverConfig.class);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("must not declare a resource resolver")),
                "expected a reach-with-resolver coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    /** TEST-ONLY: never registered as a bean, on purpose — proves the "no bean" startup check. */
    static final class UnregisteredResolver implements ResourceResolver {
        @Override
        public com.solace.sm.cp.api.authz.ResourceRef resolve(
                jakarta.servlet.http.HttpServletRequest request, com.solace.sm.cp.api.session.Session session) {
            throw new UnsupportedOperationException("never invoked; the context must fail before this could run");
        }
    }

    static final class RegisteredResolver implements ResourceResolver {
        @Override
        public com.solace.sm.cp.api.authz.ResourceRef resolve(
                jakarta.servlet.http.HttpServletRequest request, com.solace.sm.cp.api.session.Session session) {
            throw new UnsupportedOperationException("never invoked; the context must fail before this could run");
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class CatalogWithNoResolverConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        CatalogWithNoResolverController catalogWithNoResolverController() {
            return new CatalogWithNoResolverController();
        }
    }

    @RestController
    static class CatalogWithNoResolverController {

        @GetMapping("/api/bad/catalog-no-resolver")
        @ConsoleOperation("getHealth") // CATALOG, but leaves `resource` at its NoResource default
        ResponseEntity<Void> noResolver() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class CatalogWithUnregisteredResolverConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        CatalogWithUnregisteredResolverController catalogWithUnregisteredResolverController() {
            return new CatalogWithUnregisteredResolverController();
        }
        // Deliberately no @Bean of type UnregisteredResolver.
    }

    @RestController
    static class CatalogWithUnregisteredResolverController {

        @GetMapping("/api/bad/catalog-unregistered-resolver")
        @ConsoleOperation(value = "getHealth", resource = UnregisteredResolver.class)
        ResponseEntity<Void> unregisteredResolver() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class CatalogWithTwoResolverBeansConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        // Two beans of the SAME resolver type — exactly the shape that would make
        // context.getBean(RegisteredResolver.class) throw NoUniqueBeanDefinitionException at
        // request time.
        @Bean
        RegisteredResolver registeredResolverOne() {
            return new RegisteredResolver();
        }

        @Bean
        RegisteredResolver registeredResolverTwo() {
            return new RegisteredResolver();
        }

        @Bean
        CatalogWithTwoResolverBeansController catalogWithTwoResolverBeansController() {
            return new CatalogWithTwoResolverBeansController();
        }
    }

    @RestController
    static class CatalogWithTwoResolverBeansController {

        @GetMapping("/api/bad/catalog-two-resolver-beans")
        @ConsoleOperation(value = "getHealth", resource = RegisteredResolver.class)
        ResponseEntity<Void> twoResolverBeans() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class LocalOperationWithResolverConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        RegisteredResolver registeredResolver() {
            return new RegisteredResolver();
        }

        @Bean
        LocalOperationWithResolverController localOperationWithResolverController() {
            return new LocalOperationWithResolverController();
        }
    }

    @RestController
    static class LocalOperationWithResolverController {

        @GetMapping("/api/bad/local-with-resolver")
        @ConsoleOperation(value = "getIdentity", resource = RegisteredResolver.class) // LOCAL — must declare none
        ResponseEntity<Void> localWithResolver() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class AuthenticationOnlyWithResolverConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        RegisteredResolver registeredResolver() {
            return new RegisteredResolver();
        }

        @Bean
        AuthenticationOnlyWithResolverController authenticationOnlyWithResolverController() {
            return new AuthenticationOnlyWithResolverController();
        }
    }

    @RestController
    static class AuthenticationOnlyWithResolverController {

        // AUTHENTICATION_ONLY (console-integration.md §5.2 rows 8-10) — must declare no resolver.
        @GetMapping("/api/bad/authentication-only-with-resolver")
        @ConsoleOperation(value = "listOrganizations", resource = RegisteredResolver.class)
        ResponseEntity<Void> authenticationOnlyWithResolver() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class ReachWithResolverConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        RegisteredResolver registeredResolver() {
            return new RegisteredResolver();
        }

        @Bean
        ReachWithResolverController reachWithResolverController() {
            return new ReachWithResolverController();
        }
    }

    @RestController
    static class ReachWithResolverController {

        // REACH (S0 point 24) — must declare no resolver.
        @GetMapping("/api/bad/reach-with-resolver")
        @ConsoleOperation(value = "listInstances", resource = RegisteredResolver.class)
        ResponseEntity<Void> reachWithResolver() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class SecondRequestMappingHandlerMappingConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        RequestMappingHandlerMapping secondRequestMappingHandlerMapping() {
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setOrder(Ordered.LOWEST_PRECEDENCE - 2);
            return mapping;
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class ResourceHandlerConfig implements WebMvcConfigurer {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/");
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class ExtraHandlerMappingConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        HandlerMapping extraHandlerMapping() {
            SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
            mapping.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
            mapping.setUrlMap(Map.of("/extra/**", (HttpRequestHandler) (request, response) -> { }));
            return mapping;
        }
    }

    /**
     * Fable review (code-GW-5): {@code getHandlerMap()} does not list a mapping's ROOT handler ({@code /}) or its DEFAULT
     * handler, so a mapping that sets only one of those looked "empty" and was let through unauthenticated.
     */
    @Test
    void aContextWithAHandlerMappingThatSetsOnlyARootHandlerFailsStartup() {
        failsAsAnUnauthenticatedMapping(RootHandlerMappingConfig.class);
    }

    @Test
    void aContextWithAHandlerMappingThatSetsOnlyADefaultHandlerFailsStartup() {
        failsAsAnUnauthenticatedMapping(DefaultHandlerMappingConfig.class);
    }

    private static void failsAsAnUnauthenticatedMapping(Class<?> config) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(config);

        Throwable failure = assertThrows(Throwable.class, context::refresh);
        assertTrue(causeChainMessages(failure).stream().anyMatch(
                        message -> message != null && message.contains("HandlerMapping")),
                "expected a HandlerMapping coverage failure somewhere in the cause chain, got: "
                        + causeChainMessages(failure));
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class RootHandlerMappingConfig extends ExtraHandlerMappingConfig {

        @Bean
        @Override
        HandlerMapping extraHandlerMapping() {
            SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
            mapping.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
            mapping.setRootHandler((HttpRequestHandler) (request, response) -> { });   // no url map at all
            return mapping;
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class DefaultHandlerMappingConfig extends ExtraHandlerMappingConfig {

        @Bean
        @Override
        HandlerMapping extraHandlerMapping() {
            SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
            mapping.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
            mapping.setDefaultHandler((HttpRequestHandler) (request, response) -> { });   // no url map at all
            return mapping;
        }
    }

    private static List<String> causeChainMessages(Throwable throwable) {
        List<String> messages = new ArrayList<>();
        Throwable current = throwable;
        while (current != null) {
            messages.add(current.getMessage());
            current = current.getCause();
        }
        return messages;
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class BadConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        BadController badController() {
            return new BadController();
        }
    }

    @RestController
    static class BadController {

        @GetMapping("/api/bad/no-annotation")
        ResponseEntity<Void> noAnnotation() {
            return ResponseEntity.ok().build();
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(GatewayWebConfiguration.class)
    static class UnclassifiableConfig {

        @Bean
        GatewayTestSupport.FakeSessionAuthentication sessionAuthentication() {
            return new GatewayTestSupport.FakeSessionAuthentication();
        }

        @Bean
        GatewayTestSupport.FakeForcedRotationGate forcedRotationGate() {
            return new GatewayTestSupport.FakeForcedRotationGate();
        }

        @Bean
        GatewayTestSupport.FakeAuthorizationService authorizationService() {
            return new GatewayTestSupport.FakeAuthorizationService();
        }

        @Bean
        UnclassifiableController unclassifiableController() {
            return new UnclassifiableController();
        }
    }

    @RestController
    static class UnclassifiableController {

        @GetMapping("/api/bad/unclassifiable")
        @ConsoleOperation("instance.undeclare")
        ResponseEntity<Void> unclassifiable() {
            return ResponseEntity.ok().build();
        }
    }
}
