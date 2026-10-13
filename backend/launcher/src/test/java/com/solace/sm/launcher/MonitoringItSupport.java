package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * What the monitoring ITs share (plan 011 P11-7.2): the verbs {@link DeclareFixtures.Client} does not have, two declared
 * instances with real stored samples written through the REAL ingest seam, and principals with no binding. Nothing here
 * is faked: HTTP, the catalog, declare, the reach authorization and ingest are the production ones.
 */
final class MonitoringItSupport {

    static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    static final String NOBODY = "monitoring-it-nobody";

    private MonitoringItSupport() {
    }

    /** The two instances and the first sample's instant. {@code a} is in {@code envOne}, {@code b} in {@code envTwo}. */
    record Seeded(UUID solutionId, UUID a, UUID placementA, UUID b, UUID placementB, Instant start, int samples) {
    }

    /** A principal bound to nothing: every reach and catalog verb is refused. */
    static void seedNobody(NamedParameterJdbcTemplate jdbc, World world) {
        // Through the registry, so the fixture login names this principal's own organization (OR-125 (4)).
        DeclareFixtures.principal(jdbc, world.organization(), NOBODY);
    }

    static void entitle(DeclareFixtures.World world, com.solace.sm.launcher.TwoSchemaTestDatabase.Provisioned provisioned,
            int quota) {
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", quota,
                DeclareFixtures.ADMIN);
    }

    /**
     * Loads the Cache fixture as the administrator, declares one instance per environment (each by that environment's
     * operator), and ingests {@code samples} 30-second samples of {@code cache.size} (gauge) and {@code cache.hit_count}
     * (counter) for each placement, ending a minute ago and never before the start of the current UTC month (the only
     * raw partitions that exist).
     */
    static Seeded seedInstances(Http http, TelemetryIngestPort ingest, NamedParameterJdbcTemplate ownerJdbc, World world,
            int samples, int healthPortBase) throws Exception {
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        UUID solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());
        UUID a = declare(http, solutionId, world.envOne(), DeclareFixtures.OPERATOR_ONE, world.targetOne(), "mon-a",
                healthPortBase);
        UUID b = declare(http, solutionId, world.envTwo(), DeclareFixtures.OPERATOR_TWO, world.targetTwo(), "mon-b",
                healthPortBase + 1);
        UUID pa = placementOf(ownerJdbc, world, a);
        UUID pb = placementOf(ownerJdbc, world, b);
        Instant monthStart = Instant.now().atZone(java.time.ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant last = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant start = last.minusSeconds(30L * (samples - 1));
        if (start.isBefore(monthStart)) {
            start = monthStart.plusSeconds(1);
        }
        for (UUID[] pair : new UUID[][] {{pa}, {pb}}) {
            List<IngestSample> batch = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                Instant ts = start.plusSeconds(30L * i);
                batch.add(new IngestSample("cache.size", ts, 100 + i, ts));
                batch.add(new IngestSample("cache.hit_count", ts, 1000 + 10L * i, ts));
            }
            IngestResult result = ingest.ingest(IngestBatch.polled(world.organization(), pair[0], batch));
            assertEquals(IngestResult.Committed.class, result.getClass(), "ingest: " + result);
        }
        return new Seeded(solutionId, a, pa, b, pb, start, samples);
    }

    private static UUID declare(Http http, UUID solutionId, UUID environment, String operator, UUID target, String name,
            int healthPort) throws Exception {
        ObjectNode body = DeclareFixtures.declareBody(solutionId, target, name, "sm-" + name, healthPort);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + environment + "/instances",
                http.login(operator), body);
        assertEquals(201, declared.statusCode(), declared.body());
        return UUID.fromString(MAPPER.readTree(declared.body()).get("instanceId").asText());
    }

    private static UUID placementOf(NamedParameterJdbcTemplate jdbc, World world, UUID instance) {
        return jdbc.queryForObject("SELECT placement_id FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i",
                Map.of("t", world.organization(), "i", instance), UUID.class);
    }

    static JsonNode json(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    /** The verbs the shared client lacks, plus an explicit header map and a streaming read. */
    static final class Http {
        private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30)).build();
        final int port;
        final DeclareFixtures.Client client;

        Http(int port) {
            this.port = port;
            this.client = new DeclareFixtures.Client(port);
        }

        Login login(String username) throws IOException, InterruptedException {
            return client.login(username);
        }

        HttpResponse<String> post(String path, Login login, JsonNode body) throws IOException, InterruptedException {
            return client.post(path, login, body);
        }

        HttpResponse<String> get(String path, Login login) throws IOException, InterruptedException {
            return client.get(path, login);
        }

        HttpResponse<String> anonymous(String method, String path) throws IOException, InterruptedException {
            return client.anonymous(method, path);
        }

        /** Any method, a raw string body and extra headers (the dashboard revision). */
        HttpResponse<String> send(String method, String path, Login login, String body, Map<String, String> headers)
                throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                    .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf());
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

}
