package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solace.sm.gateway.http.CredentialBearing;
import com.solace.sm.gateway.http.CredentialTransportInterceptor;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayProperties;
import com.solace.sm.gateway.http.GatewayRejectionException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Plan 013 review {@code W3C-1}: a client cannot talk the servlet container into reporting a secure channel or a loopback
 * peer by sending {@code X-Forwarded-Proto} or {@code X-Forwarded-For}.
 *
 * <p>The launcher's {@code application.properties} is loaded exactly as the application loads it (Boot reads it for any
 * {@code SpringApplication}), and the context is a REAL embedded Tomcat on a random port carrying the REAL
 * {@link CredentialTransportInterceptor} on a {@link CredentialBearing} handler, with {@code spring.main.cloud-platform}
 * forced to {@code kubernetes}: the configuration in which Boot, left to its defaults, turns on {@code RemoteIpValve}. The
 * "client" connects to this machine's own site-local (private-range) address, so the peer is a non-loopback private
 * address, which is inside Tomcat's default {@code internalProxies}. No database, no Docker.
 *
 * <p>The context imports {@code EmbeddedWebServerFactoryCustomizerAutoConfiguration}, which is what registers
 * {@code TomcatWebServerFactoryCustomizer} (the class that decides on the valve); without it these tests pass whatever the
 * properties say (measured: first draft of this test, valve absent with the property removed).
 */
class ForwardedHeadersSpoofTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** The handler the interceptor guards; counts how often a request got through to it. */
    @RestController
    static class Probe {
        static final AtomicInteger REACHED = new AtomicInteger();

        @CredentialBearing
        @PostMapping("/credential")
        String credential() {
            REACHED.incrementAndGet();
            return "reached";
        }
    }

    @RestControllerAdvice
    static class Rejections {
        @ExceptionHandler(GatewayRejectionException.class)
        ResponseEntity<String> refused(GatewayRejectionException e) {
            return ResponseEntity.status(e.code() == ErrorCode.INSECURE_TRANSPORT ? 403 : 500).body(e.code().name());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    @Import({ServletWebServerFactoryAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    static class App implements WebMvcConfigurer {
        private final GatewayProperties properties;

        App(GatewayProperties properties) {
            this.properties = properties;
        }

        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        Rejections rejections() {
            return new Rejections();
        }

        @Bean
        ForwardedHeadersLockdown forwardedHeadersLockdown(Environment environment) {
            return new ForwardedHeadersLockdown(environment);
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new CredentialTransportInterceptor(properties));
        }
    }

    private ConfigurableApplicationContext context;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        Probe.REACHED.set(0);
    }

    /**
     * The launcher's own application.properties applies; the arguments are command-line properties, the highest
     * precedence, which is how an operator's overlay would beat it. Only the cloud platform and the port are forced.
     */
    private ConfigurableApplicationContext start(String... extra) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.main.cloud-platform=kubernetes",
                "--sm.gateway.trusted-origins[0]=https://console.example.test",
                "--sm.gateway.direct-client-connections=true"));
        for (String property : extra) {
            arguments.add("--" + property);
        }
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.SERVLET)
                .run(arguments.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /** This machine's private-range, non-loopback IPv4 address, or null: the peer a LAN client would present. */
    private static String siteLocalAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    private static HttpResponse<String> post(String host, int port, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/credential"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPrivateRangePeerCannotAssertHttpsWithAForwardedProtoHeader() throws Exception {
        String lan = siteLocalAddress();
        assumeTrue(lan != null, "no site-local IPv4 address on this machine to present as a LAN peer");
        context = start();

        HttpResponse<String> response = post(lan, port(context), "X-Forwarded-Proto", "https");

        assertEquals(403, response.statusCode());
        assertEquals("INSECURE_TRANSPORT", response.body());
        assertEquals(0, Probe.REACHED.get(), "the handler must not have run");
    }

    @Test
    void aPrivateRangePeerCannotBecomeLoopbackWithAForwardedForHeader() throws Exception {
        String lan = siteLocalAddress();
        assumeTrue(lan != null, "no site-local IPv4 address on this machine to present as a LAN peer");
        context = start();

        HttpResponse<String> response = post(lan, port(context), "X-Forwarded-For", "127.0.0.1");
        HttpResponse<String> both = post(lan, port(context), "X-Forwarded-For", "127.0.0.1",
                "X-Forwarded-Proto", "https");

        assertEquals(403, response.statusCode());
        assertEquals(403, both.statusCode());
        assertEquals(0, Probe.REACHED.get(), "the handler must not have run");
    }

    @Test
    void aRealLoopbackPeerStillPasses() throws Exception {
        context = start();

        HttpResponse<String> response = post("127.0.0.1", port(context));

        assertEquals(200, response.statusCode(), "the check is not simply refusing everything");
        assertEquals(1, Probe.REACHED.get());
    }

    @Test
    void theContainerInstallsNoForwardedHeaderRewriting() {
        context = start();

        TomcatWebServer tomcat = (TomcatWebServer) ((ServletWebServerApplicationContext) context).getWebServer();
        List<Valve> valves = new ArrayList<>(List.of(tomcat.getTomcat().getEngine().getPipeline().getValves()));
        valves.addAll(List.of(tomcat.getTomcat().getHost().getPipeline().getValves()));
        assertTrue(valves.stream().noneMatch(RemoteIpValve.class::isInstance), "RemoteIpValve is installed: " + valves);
        assertTrue(context.getBeansOfType(ForwardedHeaderFilter.class).isEmpty());
        assertFalse(context.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean.class).values()
                .stream().anyMatch(bean -> bean.getFilter() instanceof ForwardedHeaderFilter));
    }

    @Test
    void anOverlayThatTurnsForwardingOnRefusesToBoot() {
        for (String strategy : List.of("native", "framework")) {
            BeanCreationException failure = assertThrows(BeanCreationException.class,
                    () -> start("server.forward-headers-strategy=" + strategy), strategy);
            assertTrue(rootMessage(failure).contains("server.forward-headers-strategy must be 'none'"), strategy);
        }
    }

    @Test
    void theTwoTomcatRemoteIpPropertiesWouldInstallTheValveOnTheirOwnSoTheyAreRefusedToo() {
        for (String property : ForwardedHeadersLockdown.VALVE_PROPERTIES) {
            BeanCreationException failure = assertThrows(BeanCreationException.class,
                    () -> start(property + "=X-Forwarded-Whatever"), property);
            assertTrue(rootMessage(failure).contains(property + " must not be set"), property);
        }
        assertDoesNotThrow(() -> ForwardedHeadersLockdown.requireBlank("p", " "));
    }

    @Test
    void removingTheLineWouldHandTheDecisionBackToCloudPlatformDetectionSoItIsRefusedToo() {
        assertThrows(IllegalStateException.class, () -> ForwardedHeadersLockdown.require(null));
        assertThrows(IllegalStateException.class, () -> ForwardedHeadersLockdown.require(""));
        assertThrows(IllegalStateException.class, () -> ForwardedHeadersLockdown.require("NATIVE"));
        assertDoesNotThrow(() -> ForwardedHeadersLockdown.require("none"));
        assertDoesNotThrow(() -> ForwardedHeadersLockdown.require(" NONE "));
    }

    @Test
    void theLaunchersPropertiesFilePinsIt() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ForwardedHeadersSpoofTest.class.getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        assertEquals("none", properties.getProperty("server.forward-headers-strategy"));
    }

    private static String rootMessage(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }
}
