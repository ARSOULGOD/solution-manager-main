package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogExportStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The log download on a REAL container (embedded Tomcat, a raw socket): what the client actually receives when the export ends
 * abnormally after the first byte (plan 016 task 10 re-review N-1/N-2). The real {@code GatewayErrorAdvice} (the catch-all)
 * and {@code MetricsErrorAdvice} are in the context, so a failure that were resolved would show up as a JSON body appended to
 * the file and a clean chunked terminator. The contract: the marker, then the connection is cut with NO terminator; a clean
 * end with no marker is a complete download.
 */
class LogExportTomcatTest {

    private static final String LINE = "2026-01-01T00:00:00Z line one\n";
    private static final String TERMINATOR = "\r\n0\r\n\r\n";

    static Tomcat tomcat;
    static int port;
    static LogExportExecution execution;
    static LogExportExecution shortTimeout;

    @Configuration
    @EnableWebMvc
    static class Config {
        @Bean Probe probe() {
            return new Probe();
        }

        @Bean com.solace.sm.gateway.http.metrics.MetricsErrorAdvice metricsAdvice() {
            return new com.solace.sm.gateway.http.metrics.MetricsErrorAdvice();
        }

        @Bean LogExportAbortAdvice abortAdvice() {
            return new LogExportAbortAdvice();
        }

        @Bean GatewayErrorAdvice catchAll() {
            return new GatewayErrorAdvice();
        }
    }

    /** A stream that gives one line and then does what the mode says. */
    static LogExportStream stream(String mode) {
        return new LogExportStream() {
            int n;

            @Override
            public String next() throws InterruptedException {
                if (++n == 1) {
                    return LINE;
                }
                switch (mode) {
                    case "throw":
                        throw new IllegalStateException("boom");
                    case "stuck":     // does not react to an interrupt, like a blocking socket write
                        long until = System.nanoTime() + 2_500_000_000L;
                        while (System.nanoTime() < until) {
                            Thread.onSpinWait();
                        }
                        return null;
                    case "second":
                        return n == 2 ? "second line\n" : null;
                    default:
                        return null;
                }
            }

            @Override
            public String abort() {
                close();
                return LogExportStream.MARKER_PREFIX + " incomplete: the export ended unexpectedly; resume from seq 1\n";
            }

            @Override
            public void close() {
            }
        };
    }

    @RestController
    static class Probe {
        private WebAsyncTask<Void> task(LogExportExecution exec, String mode, HttpServletResponse response) {
            return LogExportController.download(stream(mode), new FailingSecondWrite(response, mode.equals("second")),
                    "logs-x.log", exec, () -> { });
        }

        @GetMapping("/ok") WebAsyncTask<Void> ok(HttpServletResponse r) { return task(execution, "ok", r); }
        @GetMapping("/throw") WebAsyncTask<Void> thrw(HttpServletResponse r) { return task(execution, "throw", r); }
        @GetMapping("/ioe") WebAsyncTask<Void> ioe(HttpServletResponse r) { return task(execution, "second", r); }
        @GetMapping("/stuck") WebAsyncTask<Void> stuck(HttpServletResponse r) { return task(shortTimeout, "stuck", r); }
    }

    /** Wraps the response so the second write fails, as a broken socket does. */
    static final class FailingSecondWrite extends jakarta.servlet.http.HttpServletResponseWrapper {
        private final boolean failing;

        FailingSecondWrite(HttpServletResponse response, boolean failing) {
            super(response);
            this.failing = failing;
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            jakarta.servlet.ServletOutputStream real = super.getOutputStream();
            if (!failing) {
                return real;
            }
            return new jakarta.servlet.ServletOutputStream() {
                int writes;

                @Override public boolean isReady() { return real.isReady(); }
                @Override public void setWriteListener(jakarta.servlet.WriteListener l) { real.setWriteListener(l); }
                @Override public void write(int b) throws IOException { real.write(b); }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (++writes > 1) {
                        throw new IOException("synthetic write failure");
                    }
                    real.write(b, off, len);
                }

                @Override public void flush() throws IOException { real.flush(); }
            };
        }
    }

    @BeforeAll
    static void start() throws Exception {
        execution = new LogExportExecution(4, Duration.ofSeconds(30));
        shortTimeout = new LogExportExecution(4, 800L);
        tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(System.getProperty("java.io.tmpdir") + "/sm-log-export-tomcat");
        Context ctx = tomcat.addContext("", null);
        AnnotationConfigWebApplicationContext app = new AnnotationConfigWebApplicationContext();
        app.register(Config.class);
        Wrapper w = Tomcat.addServlet(ctx, "ds", new DispatcherServlet(app));
        w.setAsyncSupported(true);
        w.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/", "ds");
        tomcat.getConnector();
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        tomcat.stop();
        execution.destroy();
        shortTimeout.destroy();
    }

    /** What arrived, and whether the chunked terminator did. */
    record Received(String text, boolean terminated) {
    }

    static Received raw(String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(15_000);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                int r;
                while ((r = in.read(buf)) != -1) {
                    b.write(buf, 0, r);
                    if (b.toString(StandardCharsets.UTF_8).endsWith(TERMINATOR)) {
                        return new Received(b.toString(StandardCharsets.UTF_8), true);
                    }
                }
            } catch (java.net.SocketException reset) {
                // a reset is an abort too
            }
            return new Received(b.toString(StandardCharsets.UTF_8), false);
        }
    }

    @Test
    void theSuccessPathEndsWithTheChunkedTerminatorAndNoMarker() throws Exception {
        Received got = raw("/ok");

        assertTrue(got.text().contains("line one"), got.text());
        assertTrue(got.terminated(), "a complete download ends cleanly: " + got.text());
        assertFalse(got.text().contains("# sm-export:"), "no marker means complete: " + got.text());
        assertTrue(got.text().contains("attachment; filename=\"logs-x.log\""), got.text());
    }

    @Test
    void aFailureAfterTheFirstByteGivesTheMarkerThenAnAbortedConnectionWithNoJsonAppended() throws Exception {
        Received got = raw("/throw");

        assertTrue(got.text().contains("line one"), got.text());
        assertTrue(got.text().contains("# sm-export: incomplete: the export ended unexpectedly; resume from seq 1"), got.text());
        assertFalse(got.text().contains("\"status\":500") || got.text().contains("Internal error"),
                "the catch-all must not append a problem body to the download: " + got.text());
        assertFalse(got.terminated(), "the chunked body must NOT be terminated, so the client sees an error: " + got.text());
    }

    @Test
    void aBrokenWriteAbortsTheConnectionToo() throws Exception {
        Received got = raw("/ioe");

        assertTrue(got.text().contains("line one"), got.text());
        assertFalse(got.text().contains("\"status\":500") || got.text().contains("Internal error"), got.text());
        assertFalse(got.terminated(), got.text());
    }

    @Test
    void theAsyncBackstopTimeoutAbortsEvenWhenThePumpThreadIgnoresTheInterrupt() throws Exception {
        Received got = raw("/stuck");

        assertTrue(got.text().contains("line one"), got.text());
        assertFalse(got.terminated(), "a timeout must not look like a clean end: " + got.text());
    }
}
