package com.solace.sm.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.metrics.bench.GateMath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Plan 011 P11-8.1 Step 3: times one query shape over real HTTP, as the session it is given. It knows the wire contract
 * ({@code POST /api/console/v1/query/range}, {@code contracts/openapi/console.yaml} {@code MetricQueryRequest}) and nothing of the
 * server, so it can be proven against a stub as well as against the real boot.
 *
 * <p>The first request of a shape is reported apart as {@code cold}; the next {@code runs} are the sample. The query rate limit
 * is 10 per second per session (M-Q24 (5)), so requests are spaced, and the spacing is never part of a timing. A {@code 429} is
 * waited out and retried (and counted), never timed as a result.
 */
final class GateRunner {

    /** One query shape of the plan: a range, and whether the raw tier is forced. */
    record GateCase(String name, Duration range, String resolution, String expectedServed) {
    }

    /** What one shape over {@code instances} instances measured. */
    record CaseResult(String name, int instances, Map<Integer, Integer> statuses, long coldMillis, List<Long> millis, int series,
            long points, String served, String expectedServed, int rateLimited) {
        boolean answered() {
            return !millis.isEmpty();
        }

        /** What this shape's numbers are fit to be called (W4-11). */
        Evidence evidence() {
            if (!answered()) {
                return Evidence.REFUSED;
            }
            return series == 0 || points == 0 ? Evidence.EMPTY : Evidence.TIMED;
        }

        boolean allNotFound() {
            return statuses.size() == 1 && statuses.containsKey(404);
        }
    }

    /**
     * {@code TIMED}: 2xx answers carrying data, so the p50/p95 are query timings. {@code EMPTY}: 2xx answers with no series or no
     * points (a seed, entity type, metric or reach mismatch): the timings are of an empty query, and are not REQ-007 evidence.
     * {@code REFUSED}: no 2xx answer at all.
     */
    enum Evidence { TIMED, EMPTY, REFUSED }

    static final String SESSION_COOKIE = "__Host-SM_SESSION";
    static final String QUERY_RANGE = "/api/console/v1/query/range";

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String session;
    private final String csrf;
    private final String origin;
    private final GateConfig config;

    GateRunner(String baseUrl, String session, String csrf, String origin, GateConfig config) {
        this.baseUrl = baseUrl;
        this.session = session;
        this.csrf = csrf;
        this.origin = origin;
        this.config = config;
    }

    /** The plan's four shapes: 24 h raw, 7 d raw, 30 d hourly, and a 30-day FORCED raw. */
    static List<GateCase> planCases() {
        return List.of(new GateCase("24h-raw", Duration.ofHours(24), "auto", "raw"),
                new GateCase("7d-raw", Duration.ofDays(7), "auto", "raw"),
                new GateCase("30d-hourly", Duration.ofDays(30), "auto", "hourly"),
                new GateCase("30d-forced-raw", Duration.ofDays(30), "raw", "raw"));
    }

    ObjectNode body(GateCase c, List<UUID> instances, Instant to) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("queryVersion", 1);
        body.put("metric", config.metric());
        ObjectNode selector = body.putObject("selector");
        selector.put("entityType", config.entityType());
        ArrayNode ids = selector.putArray("instanceIds");
        instances.forEach(id -> ids.add(id.toString()));
        selector.putObject("labels");
        ObjectNode range = body.putObject("range");
        range.put("from", to.minus(c.range()).toString());
        range.put("to", to.toString());
        body.put("resolution", c.resolution());
        body.put("seriesBy", "instance");
        ObjectNode aggregation = body.putObject("aggregation");
        aggregation.put("acrossReplicas", "auto");
        // One instance is drawn as itself; many are drawn as ONE line, which is what keeps a 200-instance panel under the series cap.
        aggregation.put("acrossInstances", instances.size() == 1 ? "none" : "avg");
        return body;
    }

    CaseResult run(GateCase c, List<UUID> instances) throws IOException, InterruptedException {
        Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        String payload = body(c, instances, to).toString();
        Map<Integer, Integer> statuses = new TreeMap<>();
        List<Long> millis = new ArrayList<>();
        long cold = -1;
        int series = 0;
        long points = 0;
        String served = null;
        int rateLimited = 0;
        int total = 1 + config.runs();
        for (int i = 0; i < total; i++) {
            for (int attempt = 0; ; attempt++) {
                if (config.spacingMillis() > 0) {
                    Thread.sleep(config.spacingMillis());
                }
                long t0 = System.nanoTime();
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl + QUERY_RANGE))
                        .timeout(Duration.ofSeconds(config.timeoutSeconds())).header("Cookie", SESSION_COOKIE + "=" + session)
                        .header("Origin", origin).header("X-SM-CSRF", csrf).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
                long elapsed = (System.nanoTime() - t0) / 1_000_000;
                if (response.statusCode() == 429 && attempt < 5) {
                    rateLimited++;
                    Thread.sleep(1000);
                    continue;
                }
                statuses.merge(response.statusCode(), 1, Integer::sum);
                if (response.statusCode() / 100 == 2) {
                    if (i == 0) {
                        cold = elapsed;
                    } else {
                        millis.add(elapsed);
                    }
                    JsonNode json = MAPPER.readTree(response.body());
                    series = json.path("series").size();
                    points = 0;
                    for (JsonNode s : json.path("series")) {
                        points += s.path("points").size();
                    }
                    served = json.path("resolution").path("served").asText(null);
                }
                break;
            }
        }
        return new CaseResult(c.name(), instances.size(), statuses, cold, millis, series, points, served, c.expectedServed(), rateLimited);
    }

    static long p50(CaseResult r) {
        return GateMath.percentile(r.millis(), 0.50);
    }

    static long p95(CaseResult r) {
        return GateMath.percentile(r.millis(), 0.95);
    }
}
