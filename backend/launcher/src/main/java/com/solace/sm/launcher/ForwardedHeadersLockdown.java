package com.solace.sm.launcher;

import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot unless {@code server.forward-headers-strategy} is {@code none} (plan 013 review {@code W3C-1}).
 *
 * <p>Why the launcher pins it: the gateway decides several things from {@code request.isSecure()} and
 * {@code request.getRemoteAddr()} ({@code CredentialTransportInterceptor}, {@code ClientAddressResolver}) and then applies
 * its OWN, exact {@code sm.gateway.trusted-proxies} rule to {@code X-Forwarded-Proto} and {@code X-Forwarded-For}. If the
 * servlet container has already rewritten those two values from the same headers, the gateway's rule is bypassed by
 * whoever is first to read them. Measured from the Spring Boot 3.5.16 jars (javap):
 * <ul>
 *   <li>{@code TomcatWebServerFactoryCustomizer.getOrDeduceUseForwardHeaders()} returns {@code true} when the property is
 *       unset and {@code CloudPlatform.getActive(environment)} is a platform whose {@code isUsingForwardHeaders()} is true.
 *       Kubernetes ({@code KUBERNETES_SERVICE_HOST} and {@code KUBERNETES_SERVICE_PORT} set), Cloud Foundry, Heroku, Azure App
 *       Service and Nomad all are, and {@code spring.main.cloud-platform} forces one. Boot then adds Tomcat's
 *       {@code RemoteIpValve}, whose default {@code internalProxies} cover 10/8, 172.16/12, 192.168/16, 169.254/16, 127/8,
 *       100.64/10, ::1 and fc00::/7, so a client on any of those networks that sends {@code X-Forwarded-Proto: https} (or
 *       {@code X-Forwarded-For: 127.0.0.1}) is believed;</li>
 *   <li>{@code native} installs that valve; {@code framework} registers a {@code ForwardedHeaderFilter}
 *       ({@code ServletWebServerFactoryAutoConfiguration$ForwardedHeaderFilterConfiguration} is conditional on the value
 *       {@code framework}). Only {@code none} installs neither.</li>
 * </ul>
 * So the properties file sets it to {@code none}, and this class makes a deployment overlay that sets anything else (or
 * removes the line, which would hand the decision back to cloud-platform detection) a startup failure rather than a quiet
 * downgrade.
 */
@Component
class ForwardedHeadersLockdown {

    static final String PROPERTY = "server.forward-headers-strategy";

    /**
     * Either one, set to any text, installs {@code RemoteIpValve} whatever the strategy says
     * ({@code TomcatWebServerFactoryCustomizer.customizeRemoteIpValve}: {@code hasText(protocolHeader) ||
     * hasText(remoteIpHeader) || getOrDeduceUseForwardHeaders()}, read from the 3.5.16 jar).
     */
    static final List<String> VALVE_PROPERTIES = List.of("server.tomcat.remoteip.protocol-header",
            "server.tomcat.remoteip.remote-ip-header");

    ForwardedHeadersLockdown(Environment environment) {
        require(environment.getProperty(PROPERTY));
        for (String property : VALVE_PROPERTIES) {
            requireBlank(property, environment.getProperty(property));
        }
    }

    /** @throws IllegalStateException {@code value} has text: the property would install the valve on its own */
    static void requireBlank(String property, String value) {
        if (value != null && !value.isBlank()) {
            throw new IllegalStateException(property + " must not be set: it makes the servlet container rewrite the "
                    + "request's scheme or peer address from a header, whatever " + PROPERTY + " says");
        }
    }

    /** @throws IllegalStateException the strategy is not exactly {@code none} (case-insensitive); the text names no value */
    static void require(String strategy) {
        if (strategy == null || !"none".equals(strategy.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(PROPERTY + " must be 'none': the gateway applies its own "
                    + "sm.gateway.trusted-proxies rule to X-Forwarded-Proto and X-Forwarded-For, and the servlet "
                    + "container must not rewrite the request's scheme or peer address first");
        }
    }
}
