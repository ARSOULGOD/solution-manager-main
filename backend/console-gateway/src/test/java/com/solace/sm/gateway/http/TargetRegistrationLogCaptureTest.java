package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * MF-R3, gateway half (plan 013 T1.4; {@code I6}): a key is registered through HTTP, by every path (accepted, replayed,
 * refused, a body Spring cannot read, a data-plane failure) with the root logger at TRACE and an appender capturing EVERY
 * event, and not one byte of the key text appears in any message, argument, MDC value or stack trace.
 *
 * <p>The launcher's {@code TargetRegistrationEndpointIT} repeats it end to end against the real appenders at the
 * production levels; this one is the cheaper, wider net (TRACE) over the same pipeline.
 */
class TargetRegistrationLogCaptureTest {

    private static final String MARKER = "LOGMARKER-91d3e7b2-do-not-leak";
    private static final String KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + MARKER
            + "\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String PIN = "SHA256:" + "A".repeat(43);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private TargetRegistrationControllerTest.Fakes fakes;
    private UUID organization;

    @BeforeEach
    void capture() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.start();
        root.addAppender(appender);

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true");
        context.register(TargetRegistrationControllerTest.Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*")
                .addFilter(new PeekableBodyFilter(), "/*").build();
        fakes = context.getBean(TargetRegistrationControllerTest.Fakes.class);
        organization = UUID.randomUUID();
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(GatewayTestSupport.FakeSessionAuthentication.class).register("tok", session, "csrf");
        fakes.hierarchy.organization = organization;
    }

    @AfterEach
    void release() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        root.setLevel(previousLevel);
        appender.stop();
    }

    private MvcResult register(String body) throws Exception {
        return mvc.perform(post("/api/cp/targets").cookie(new Cookie("__Host-SM_SESSION", "tok"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String body(String name, String key) {
        return "{\"name\":\"" + name + "\",\"kind\":\"docker\",\"transport\":\"ssh-docker\",\"ownershipScope\":{},"
                + "\"connection\":{\"host\":\"10.0.0.7\",\"sshUser\":\"sm\",\"hostKeyFingerprint\":\"" + PIN
                + "\",\"allowedConfigRoot\":\"/srv/sm\",\"allowedContainerPrefix\":\"sm-\"},\"privateKey\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(key) + "}";
    }

    @Test
    void noPathLogsAnyByteOfTheKey() throws Exception {
        assertEquals(201, register(body("vm-1", KEY)).getResponse().getStatus());                  // accepted
        assertEquals(200, register(body("vm-1", KEY)).getResponse().getStatus());                  // replayed
        fakes.admin.precheckFailure = new CredentialInvalidException();
        assertEquals(422, register(body("vm-2", KEY)).getResponse().getStatus());                  // refused
        fakes.admin.precheckFailure = null;
        assertEquals(400, register(body("vm-3", KEY).replace("\"kind\":\"docker\"", "\"kind\":\"nope\""))
                .getResponse().getStatus());                                                       // unreadable
        assertEquals(400, register("{\"privateKey\":\"" + MARKER + "\",\"bogus\":1}").getResponse().getStatus());
        fakes.admin.registerFailureOnce = new RuntimeException("simulated crash");
        assertEquals(500, register(body("vm-4", KEY)).getResponse().getStatus());                  // a failure

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            lines.add(String.valueOf(event.getMessage()));
            lines.add(String.valueOf(event.getMDCPropertyMap()));
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    lines.add(String.valueOf(argument));
                }
            }
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                lines.add(String.valueOf(t.getMessage()));
                for (var frame : t.getStackTraceElementProxyArray()) {
                    lines.add(frame.getSTEAsString());
                }
            }
        }
        assertTrue(appender.list.size() > 5, "the capture saw the pipeline run: " + appender.list.size() + " events");
        for (String line : lines) {
            assertFalse(line.contains(MARKER), "a log line carries the key: " + line);
        }
    }
}
