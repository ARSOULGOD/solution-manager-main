package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** The replacement for the actuator routes the launcher cannot have: authenticated, GET-only, separate from the console. */
class AgentGatewayManagementServerTest {
    private static final String TOKEN = "management-token-0123456789";

    private record Fixture(AgentGatewayManagementServer management, AgentGatewayMetrics metrics, AgentGatewayProperties properties) { }

    private static Fixture fixture(int port, String token) {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setManagementPort(port);
        properties.setManagementToken(token);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        AgentGatewayMetrics metrics = new AgentGatewayMetrics(new SimpleMeterRegistry());
        AgentGatewayService service = new AgentGatewayService(null, properties, scheduler, () -> "t", metrics);
        AgentGatewayServer gateway = new AgentGatewayServer(properties, service, scheduler);
        return new Fixture(new AgentGatewayManagementServer(properties, gateway, service, metrics), metrics, properties);
    }

    private static HttpResponse<String> get(int port, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (authorization != null) request.header("Authorization", authorization);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void itIsOffByDefaultSoNothingListensUnlessAnOperatorAsksForIt() {
        Fixture f = fixture(0, "");
        f.management().start();
        assertFalse(f.management().isRunning());
    }

    @Test
    void itRefusesToStartWithoutATokenBecauseItIsNeverServedUnauthenticated() {
        Fixture f = fixture(-1, "short");
        assertThrows(IllegalStateException.class, f.management()::start);
        assertFalse(f.management().isRunning());
    }

    @Test
    void everyRequestNeedsTheBearerTokenAndOnlyGetIsAllowed() throws Exception {
        Fixture f = fixture(-1, TOKEN);
        f.management().start();
        try {
            int port = f.management().actualPort();
            assertEquals(401, get(port, "/management/health", null).statusCode());
            assertEquals(401, get(port, "/management/metrics", "Bearer wrong-token-0123456789").statusCode());
            assertEquals(401, get(port, "/management/metrics", "Basic " + TOKEN).statusCode());
            assertEquals("Bearer", get(port, "/management/health", null).headers().firstValue("WWW-Authenticate").orElse(""));
            HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/management/health"))
                    .header("Authorization", "Bearer " + TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
            assertEquals(404, get(port, "/management/other", "Bearer " + TOKEN).statusCode());
            assertEquals(404, get(port, "/actuator/health", "Bearer " + TOKEN).statusCode(), "the actuator path does not exist");
        } finally {
            f.management().stop();
        }
    }

    @Test
    void healthAndMetricsAreServedToTheTokenHolderAndNeverContainTheToken() throws Exception {
        Fixture f = fixture(-1, TOKEN);
        f.management().start();
        try {
            int port = f.management().actualPort();
            f.metrics().sessionClaimed();
            f.metrics().admissionRejected("rate");
            HttpResponse<String> health = get(port, "/management/health", "Bearer " + TOKEN);
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"UP\""), health.body());
            assertTrue(health.body().contains("\"sessions\":0") && health.body().contains("\"maxSessions\":2000"), health.body());

            HttpResponse<String> metrics = get(port, "/management/metrics", "Bearer " + TOKEN);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(metrics.body().contains("agw_sessions_claimed_total 1"), metrics.body());
            assertTrue(metrics.body().contains("agw_admission_rejected_total{reason=\"rate\"} 1"), metrics.body());
            assertTrue(metrics.body().contains("jvm_memory_heap_used_bytes"), "JVM gauges for a capacity review");
            assertFalse(metrics.body().contains(TOKEN) || health.body().contains(TOKEN));
        } finally {
            f.management().stop();
        }
    }
}
