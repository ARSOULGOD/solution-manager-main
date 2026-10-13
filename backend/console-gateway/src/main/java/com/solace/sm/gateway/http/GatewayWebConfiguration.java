package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.session.SessionAuthentication;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

/**
 * Wires the authenticated HTTP boundary from cp-api port beans, by type. Constructs no CP
 * service, repository, {@code DataSource}, or {@code RuntimeAdapter} — every dependency below is
 * a cp-api interface the composition root (launcher) supplies its own implementation for.
 *
 * <p>The production launcher component-scans {@code com.solace.sm.gateway}, so this class and
 * every {@code @RestController} beside it (e.g. {@code AuthController}) are picked up
 * automatically; nothing here needs to be re-declared there.
 *
 * <p>⚠️ This class deliberately does NOT implement {@code WebMvcConfigurer} itself. A
 * {@code @Configuration} class's own instance methods (its {@code @Bean} factories) can only run
 * once the class itself is constructed, so a constructor here that needed one of ITS OWN
 * produced beans (e.g. {@code AuthenticationInterceptor}) would be a self-referential cycle
 * Spring correctly refuses at startup. {@link #gatewayMvcConfigurer} sidesteps that by producing
 * an independent {@code WebMvcConfigurer} bean whose constructor arguments are resolved the
 * ordinary way.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayWebConfiguration {

    @Bean
    public AuthenticationInterceptor authenticationInterceptor(GatewayProperties properties,
            SessionAuthentication sessionAuthentication, ForcedRotationGate forcedRotationGate,
            AuthorizationService authorizationService, ReachAuthorization reachAuthorization,
            ApplicationContext context, AuthRateGate authRateGate) {
        return new AuthenticationInterceptor(properties, sessionAuthentication, forcedRotationGate,
                authorizationService, reachAuthorization, context, authRateGate);
    }

    /**
     * The {@code OR-24} gate's monotonic time source: a bean named {@code authRateClock} if one
     * exists (tests supply a controllable wall-clock {@link Clock}, translated to synthetic
     * nanoseconds so the gate's own refill math stays in nanosecond units even under test),
     * otherwise {@link System#nanoTime()} directly — never wall-clock time in production, since a
     * backwards NTP step would otherwise freeze or falsely refill the bucket (see {@link
     * AuthRateGate}'s class javadoc).
     *
     * <p>⚠️ <b>Deliberately NOT a by-type {@code Clock} injection and NOT a conditional bean.</b> The production
     * context already holds two {@code Clock} beans ({@code smDataClock}, {@code smControlClock}), so a by-type
     * injection is ambiguous, and a missing-bean condition in a plain configuration class depends on registration
     * order. An earlier revision did both and started only because the condition happened to be evaluated before
     * the plane configurations registered their clocks.
     */
    @Bean
    public AuthRateGate authRateGate(ClientAddressResolver clientAddressResolver, GatewayProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("authRateClock") java.util.Optional<Clock> authRateClock) {
        LongSupplier nanos = authRateClock
                .<LongSupplier>map(clock -> () -> clock.millis() * 1_000_000L)
                .orElse(System::nanoTime);
        return new AuthRateGate(clientAddressResolver, properties, nanos);
    }

    @Bean
    public SessionArgumentResolver sessionArgumentResolver() {
        return new SessionArgumentResolver();
    }

    /**
     * Plan 013 OQ-14 (b): a credential-bearing request that is not on a secure channel is refused before anything else
     * looks at it. Registered BEFORE {@link AuthenticationInterceptor} (see {@link #gatewayMvcConfigurer}).
     */
    @Bean
    public CredentialTransportInterceptor credentialTransportInterceptor(GatewayProperties properties) {
        return new CredentialTransportInterceptor(properties);
    }

    /**
     * Plan 013 T1.4: lets {@link OwnershipScopeResolver} read the {@code ownershipScope} a body names before the handler
     * reads the body. Reads nothing unless asked, so for every other route it changes nothing.
     */
    @Bean
    public FilterRegistrationBean<PeekableBodyFilter> peekableBodyFilterRegistration() {
        FilterRegistrationBean<PeekableBodyFilter> registration = new FilterRegistrationBean<>(new PeekableBodyFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public WebMvcConfigurer gatewayMvcConfigurer(AuthenticationInterceptor authenticationInterceptor,
            CredentialTransportInterceptor credentialTransportInterceptor,
            SessionArgumentResolver sessionArgumentResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // order matters: the transport check runs first, before the session, CSRF or any body is looked at
                registry.addInterceptor(credentialTransportInterceptor);
                registry.addInterceptor(authenticationInterceptor);
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(sessionArgumentResolver);
            }
        };
    }

    @Bean
    public ClientAddressResolver clientAddressResolver(GatewayProperties properties) {
        return new ClientAddressResolver(properties);
    }

    @Bean
    public RequestOriginFactory requestOriginFactory(ClientAddressResolver clientAddressResolver) {
        return new RequestOriginFactory(clientAddressResolver);
    }

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public OperationCoverageValidator operationCoverageValidator(ApplicationContext context) {
        return new OperationCoverageValidator(context);
    }

    @Bean
    public NoRouteHandler noRouteHandler() {
        return new NoRouteHandler();
    }

    /**
     * Lowest-precedence fallback mapping — tried only once every {@code @RequestMapping} handler
     * has already declined. A separate, deliberately non-annotation-driven {@code HandlerMapping}
     * so {@link OperationCoverageValidator} and {@link AuthenticationInterceptor} never see it
     * (see {@link NoRouteHandler}'s javadoc).
     */
    @Bean
    public HandlerMapping noRouteHandlerMapping(NoRouteHandler noRouteHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.LOWEST_PRECEDENCE);
        mapping.setUrlMap(Map.of("/**", noRouteHandler));
        return mapping;
    }
}
