package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryRefusedException;
import com.solace.sm.dp.api.metrics.query.MetricsQueryUnavailableException;
import com.solace.sm.dp.api.metrics.query.QueryRefusal;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Plan 011 P11-7.2: {@code queryRange}, {@code queryInstant} and {@code getCapabilities} over the real pipeline with the
 * ports faked. The reach rule (M-Q17), the verbs, the rate gate and the refusals' wire are pinned here; the data plane's
 * own arithmetic is {@code MetricsQueryServiceIT}'s.
 */
class MetricsQueryRoutesTest extends MetricsRoutesRig {

    // ---------------------------------------------------------------------------- who may call, and what they reach

    @Test
    void aCallerWithNoMetricsGrantInTheOrganizationIs403() throws Exception {
        authorization.outcome = Outcome.DENY;
        MvcResult range = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
        assertEquals(403, range.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(range).get("code").asText());
        assertEquals(List.of(), query.ranges, "the data plane was never asked");
        assertEquals(List.of(Permission.METRICS_QUERY), authorization.reachChecks);
    }

    @Test
    void unauthorizedInstancesAreOmittedLikeNonexistentOnes() throws Exception {
        // Review Focus 4: the caller reaches A only. B is real and out of reach; the third id does not exist.
        authorization.authorizedIds.add(instanceA);
        UUID missing = UUID.randomUUID();
        MvcResult result = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA, instanceB, missing));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(instanceA), query.ranges.get(0).instanceIds(),
                "the data plane is handed only what the caller reaches; the query is not refused for the others");
        assertFalse(result.getResponse().getContentAsString().contains(instanceB.toString()));
        // The reach filter is the operation's own verb, asked of INSTANCEs, with the caller's claim.
        assertEquals(Permission.METRICS_QUERY, authorization.subsetCalls.get(0).permission());
        assertEquals(Set.of(instanceA, instanceB, missing), authorization.subsetCalls.get(0).ids());
    }

    @Test
    void anIdThatFilteringEmptiesIsAskedAboutAsAFreshUnknownIdSoNothingIsAnOracle() throws Exception {
        authorization.authorizedIds.clear();
        MvcResult outOfReach = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceB));
        assertEquals(200, outOfReach.getResponse().getStatus());
        List<UUID> asked = query.ranges.get(0).instanceIds();
        assertEquals(1, asked.size(), "an empty list is malformed to the data plane, so it is given one id that is not there");
        assertFalse(asked.contains(instanceB));
        assertFalse(asked.contains(instanceA));
        // A foreign or missing id takes the same path, so the two requests are indistinguishable to the data plane.
        postAs("token", "/query/range", queryBody("cache.size", "auto", "none", UUID.randomUUID()));
        assertEquals(1, query.ranges.get(1).instanceIds().size());
    }

    @Test
    void theOrganizationIsTheSessionsNeverARequestField() throws Exception {
        authorization.authorizedIds.add(instanceA);
        postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
        assertEquals(List.of(session.organizationId()), query.organizations);
        // A body that names an organization is refused, not honoured and not ignored.
        Map<String, Object> body = queryBody("cache.size", "auto", "none", instanceA);
        body.put("organizationId", UUID.randomUUID().toString());
        assertEquals(400, postAs("token", "/query/range", body).getResponse().getStatus());
    }

    @Test
    void aViewerMayQueryButNotExportOrForceRaw() throws Exception {
        authorization.authorizedIds.add(instanceA);
        authorization.reachOutcomeByPermission.put(Permission.METRICS_FORCE_RAW, Outcome.DENY);
        assertEquals(200, postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA))
                .getResponse().getStatus());
        MvcResult raw = postAs("token", "/query/range", queryBody("cache.size", "raw", "none", instanceA));
        assertEquals(403, raw.getResponse().getStatus());
        assertEquals(1, query.ranges.size(), "the raw request never reached the data plane");
    }

    @Test
    void resolutionRawIntersectsWithWhereForceRawIsHeld() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_QUERY, Set.of(instanceA, instanceB));
        authorization.authorizedIdsByPermission.put(Permission.METRICS_FORCE_RAW, Set.of(instanceA));
        MvcResult raw = postAs("token", "/query/range", queryBody("cache.size", "raw", "none", instanceA, instanceB));
        assertEquals(200, raw.getResponse().getStatus());
        assertEquals(List.of(instanceA), query.ranges.get(0).instanceIds(), "forceRaw is held at A only");
        assertEquals(RangeQuery.Resolution.RAW, query.ranges.get(0).resolution());
        // An automatic resolution needs only metrics.query.
        postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA, instanceB));
        assertEquals(List.of(instanceA, instanceB), query.ranges.get(1).instanceIds());
    }

    @Test
    void anInstantQueryNeedsOnlyTheQueryVerbWhateverTheResolution() throws Exception {
        authorization.authorizedIds.add(instanceA);
        authorization.reachOutcomeByPermission.put(Permission.METRICS_FORCE_RAW, Outcome.DENY);
        MvcResult result = postAs("token", "/query/instant", queryBody("cache.size", "raw", "sum", instanceA));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, query.instants.size());
        assertEquals(RangeQuery.Across.SUM, query.instants.get(0).acrossInstances());
        // W4-01: the verb is one, so the data plane is never told to scan the raw tier for an instant.
        assertEquals(RangeQuery.Resolution.AUTO, query.instants.get(0).resolution(),
                "an instant query must not change the tier: raw is metrics.forceRaw's cost");
    }

    // ------------------------------------------------------------------------------------------------- the request

    @Test
    void theRequestShapeIsStrictAndLabelsAndGroupByAreRefusedInV1() throws Exception {
        authorization.authorizedIds.add(instanceA);
        Map<String, Object> twoMetrics = queryBody("cache.size", "auto", "none", instanceA);
        twoMetrics.put("metric", List.of("cache.size", "cache.hit_count"));
        assertEquals(400, postAs("token", "/query/range", twoMetrics).getResponse().getStatus(), "DM-D23: exactly one metric");
        Map<String, Object> grouped = queryBody("cache.size", "auto", "sum", instanceA);
        grouped.put("groupBy", List.of("region"));
        assertEquals(400, postAs("token", "/query/range", grouped).getResponse().getStatus());
        Map<String, Object> labelled = queryBody("cache.size", "auto", "none", instanceA);
        ((Map<String, Object>) labelled.get("selector")).put("labels", Map.of("region", "eu"));
        assertEquals(400, postAs("token", "/query/range", labelled).getResponse().getStatus());
        Map<String, Object> stray = queryBody("cache.size", "auto", "none", instanceA);
        ((Map<String, Object>) stray.get("selector")).put("tenant", "x");
        assertEquals(400, postAs("token", "/query/range", stray).getResponse().getStatus());
        assertEquals(400, postAs("token", "/query/range", queryBody("Bad Name", "auto", "none", instanceA)).getResponse()
                .getStatus());
        Map<String, Object> inverted = queryBody("cache.size", "auto", "none", instanceA);
        inverted.put("range", Map.of("from", "2026-10-06T02:00:00Z", "to", "2026-10-06T01:00:00Z"));
        assertEquals(400, postAs("token", "/query/range", inverted).getResponse().getStatus());
        Map<String, Object> noIds = queryBody("cache.size", "auto", "none");
        assertEquals(400, postAs("token", "/query/range", noIds).getResponse().getStatus());
        assertEquals(List.of(), query.ranges, "a malformed request never reaches the data plane");
    }

    @Test
    void aMalformedRequestTheDataPlaneRefusesIsA400NotA500() throws Exception {
        authorization.authorizedIds.add(instanceA);
        query.failure = new IllegalArgumentException("range exceeds what any tier can serve");
        MvcResult result = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(result).get("code").asText());
        assertFalse(result.getResponse().getContentAsString().contains("tier"), "the data plane's message never reaches the body");
    }

    // ------------------------------------------------------------------------------------------------ the refusals

    @Test
    void theCapCountsRenderedSeriesOverTheAuthorizedSet() throws Exception {
        authorization.authorizedIds.add(instanceA);
        authorization.authorizedIds.add(instanceB);
        // The data plane projects what it was handed; the gateway hands it only the authorized set.
        query.failure = null;
        postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA, instanceB, UUID.randomUUID(),
                UUID.randomUUID()));
        assertEquals(2, query.ranges.get(0).instanceIds().size(), "unauthorized and missing ids add nothing to the cap");
        query.failure = new MetricsQueryRefusedException(QueryRefusal.SERIES_CAP_EXCEEDED, 10, 12, RangeQuery.SeriesBy.REPLICA);
        MvcResult refused = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA, instanceB));
        assertEquals(422, refused.getResponse().getStatus());
        JsonNode problem = json(refused);
        assertEquals("SERIES_CAP_EXCEEDED", problem.get("code").asText());
        assertEquals("Series cap exceeded", problem.get("title").asText());
        assertEquals(10, problem.get("cap").asInt());
        assertEquals(12, problem.get("projected").asInt());
        assertEquals("replica", problem.get("seriesBy").asText());
        assertEquals("application/problem+json", refused.getResponse().getContentType());
        assertNotNull(problem.get("correlationId"));
    }

    @Test
    void everyDataPlaneRefusalIsTheContractsCodeWithAFixedTitle() throws Exception {
        authorization.authorizedIds.add(instanceA);
        Map<QueryRefusal, String> titles = Map.of(
                QueryRefusal.AGGREGATION_NOT_APPLICABLE, "Aggregation not applicable",
                QueryRefusal.GROUP_BY_REQUIRES_AGGREGATION, "Group by requires an aggregation",
                QueryRefusal.STAT_REQUIRES_AGGREGATION, "Single-stat requires an aggregation",
                QueryRefusal.METRIC_UNTYPED, "Metric has no declared type");
        for (Map.Entry<QueryRefusal, String> entry : titles.entrySet()) {
            query.failure = new MetricsQueryRefusedException(entry.getKey());
            MvcResult result = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
            assertEquals(422, result.getResponse().getStatus(), entry.getKey().name());
            assertEquals(entry.getKey().wire(), json(result).get("code").asText());
            assertEquals(entry.getValue(), json(result).get("title").asText());
            assertFalse(json(result).has("cap"), "only the series cap carries a cap");
        }
    }

    @Test
    void aQuerySideThatCannotAnswerIs503() throws Exception {
        authorization.authorizedIds.add(instanceA);
        query.failure = new MetricsQueryUnavailableException(MetricsQueryUnavailableException.Reason.BUSY);
        MvcResult result = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
        assertEquals(503, result.getResponse().getStatus());
        assertEquals("SERVICE_UNAVAILABLE", json(result).get("code").asText());
    }

    @Test
    void theQueryRateLimitIs429() throws Exception {
        authorization.authorizedIds.add(instanceA);
        for (int i = 0; i < 10; i++) {
            assertEquals(200, postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA))
                    .getResponse().getStatus(), "request " + i);
        }
        MvcResult limited = postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA));
        assertEquals(429, limited.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(limited).get("code").asText());
        assertEquals("query-rate", json(limited).get("reason").asText());
        assertEquals("1", limited.getResponse().getHeader("Retry-After"));
        assertEquals(10, query.ranges.size(), "the limited request never reached the data plane");
        // The limit is per SESSION: another session is untouched, and a second later this one is too.
        login("other");
        assertEquals(200, postAs("other", "/query/range", queryBody("cache.size", "auto", "none", instanceA)).getResponse()
                .getStatus());
        clock.advance(java.time.Duration.ofSeconds(1));
        assertEquals(200, postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA)).getResponse()
                .getStatus());
        // The instant route shares the gate.
        for (int i = 0; i < 12; i++) {
            postAs("token", "/query/instant", queryBody("cache.size", "auto", "sum", instanceA));
        }
        assertEquals(429, postAs("token", "/query/instant", queryBody("cache.size", "auto", "sum", instanceA))
                .getResponse().getStatus());
    }

    // ----------------------------------------------------------------------------------------------------- the wire

    @Test
    void theNilUuidSentinelForAnAggregateLineNoLongerExists() {
        assertThrows(NoSuchFieldException.class, () -> SeriesView.SeriesRef.class.getField("AGGREGATE_INSTANCE"),
                "W4-06: the aggregate's instance id is absent, never a fixed UUID a renderer could mistake for an instance");
        assertDoesNotThrow(() -> new SeriesView.SeriesRef(null, null, null), "an aggregate line's reference is all-null");
    }

    @Test
    void theResultIsTheContractsShape() throws Exception {
        authorization.authorizedIds.add(instanceA);
        query.next = new RangeResult(new RangeResult.Metric("cache.hit_count", RangeResult.MetricType.COUNTER, "events"),
                new RangeResult.Render(RangeResult.Transform.RATE, 60),
                new RangeResult.Served(RangeQuery.Resolution.AUTO, RangeResult.Tier.HOURLY, 3600),
                new RangeResult.Coverage(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), true,
                        Instant.parse("2026-10-06T00:00:00Z")),
                7200,
                List.of(new SeriesView(new SeriesView.SeriesRef(null, null, null), "sum of 2", 2, 0, SeriesView.State.OK,
                        List.of(new SeriesView.Point(Instant.parse("2026-10-06T00:00:00Z"), 1.5, 120, 120)),
                        List.of(new SeriesView.Gap(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T03:00:00Z"),
                                "no-samples")))),
                new RangeResult.Partial(true, List.of(new RangeResult.Unavailable(instanceA, "mon-a",
                        RangeResult.Reason.COLLECTOR_DEGRADED))));
        JsonNode result = json(postAs("token", "/query/range", queryBody("cache.hit_count", "auto", "sum", instanceA)));
        assertEquals(1, result.get("queryVersion").asInt());
        assertEquals("counter", result.at("/metric/metricType").asText());
        assertEquals("rate", result.at("/render/transform").asText());
        assertEquals(60, result.at("/render/rateSeconds").asInt());
        assertEquals("hourly", result.at("/resolution/served").asText());
        assertEquals("auto", result.at("/resolution/requested").asText());
        assertTrue(result.at("/coverage/truncatedByRetention").asBoolean());
        assertEquals("2026-10-06T00:00:00Z", result.at("/coverage/provisionalFrom").asText());
        assertEquals(7200, result.get("gapThresholdSeconds").asInt());
        JsonNode series = result.at("/series/0");
        assertEquals("2026-10-06T00:00:00Z", series.at("/points/0/0").asText());
        assertEquals(1.5, series.at("/points/0/1").asDouble());
        assertEquals(120, series.at("/points/0/2").asInt());
        assertEquals(4, series.at("/points/0").size());
        assertEquals("no-samples", series.at("/gaps/0/reason").asText());
        assertEquals("ok", series.get("state").asText());
        assertTrue(series.at("/seriesRef/placementId").isNull());
        assertTrue(series.at("/seriesRef/sourceKey").isNull());
        assertTrue(series.at("/seriesRef").has("instanceId"), "required and nullable, like placementId and sourceKey");
        assertTrue(series.at("/seriesRef/instanceId").isNull(),
                "OR-112 (6), W4-06: an across-instances aggregate has no instance, so it carries NO instance id");
        assertFalse(series.toString().contains("00000000-0000-0000-0000-000000000000"), "and no nil-UUID sentinel anywhere");
        assertEquals("collector-degraded", result.at("/partial/unavailable/0/reason").asText());
        // An absent provisionalFrom is OMITTED, not null.
        query.next = resultFor(List.of(instanceA));
        assertFalse(json(postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA)))
                .at("/coverage").has("provisionalFrom"));
        assertFalse(json(postAs("token", "/query/range", queryBody("cache.size", "auto", "none", instanceA)))
                .at("/render").has("rateSeconds"));
    }

    // -------------------------------------------------------------------------------------------- capabilities (53)

    @Test
    void capabilitiesCarryTheCollectorBlock() throws Exception {
        query.health = new CollectorHealthView(CollectorHealthView.State.LIVE, LocalDate.parse("2027-02-01"), 2,
                List.of(new CollectorHealthView.ReasonCount(CollectorReason.SCRAPE_FAILED, 4)),
                new DiskAlarmView(DiskAlarmView.State.WARNING));
        MvcResult result = getAs("token", "/capabilities");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode capabilities = json(result);
        assertEquals(10, capabilities.get("maxSeriesPerWidget").asInt());
        assertEquals(1000, capabilities.get("maxPointsPerSeries").asInt());
        assertEquals("P183D", capabilities.at("/tiers/0/retention").asText());
        assertEquals("P730D", capabilities.get("maxRange").asText());
        assertEquals("live", capabilities.at("/collector/state").asText());
        assertEquals("2027-02-01", capabilities.at("/collector/runwayEnd").asText());
        assertEquals(2, capabilities.at("/collector/unfrozenClosedPartitions").asInt());
        assertEquals("scrape_failed", capabilities.at("/collector/reasons/0/reason").asText());
        assertEquals(4, capabilities.at("/collector/reasons/0/last24h").asInt());
        assertEquals("warning", capabilities.at("/collector/disk/state").asText());
        assertEquals(List.of(), authorization.reachChecks, "authentication only: no verb is asked");
    }

    @Test
    void capabilitiesNeverCarryInstallWideByteCounts() throws Exception {
        MvcResult result = getAs("token", "/capabilities");
        String text = result.getResponse().getContentAsString().toLowerCase();
        for (String forbidden : List.of("bytes", "metricsbytes", "databasebytes", "budget")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertEquals(Set.of("state"), fieldNames(json(result).at("/collector/disk")));
    }

    @Test
    void capabilitiesReasonsAreTheCallersOrganizationOnly() throws Exception {
        getAs("token", "/capabilities");
        login("second");
        UUID other = UUID.randomUUID();
        com.solace.sm.cp.api.session.Session foreign = GatewayTestSupport.newSession(UUID.randomUUID(), other, false);
        context.getBean(GatewayTestSupport.FakeSessionAuthentication.class).register("foreign", foreign, "csrf-foreign");
        getAs("foreign", "/capabilities");
        assertEquals(List.of(session.organizationId(), other), query.organizations,
                "the data plane is asked about the SESSION's organization each time, never anything the request said");
    }

    // OR-112 (6), W4-05: runwayEnd and disk may be ABSENT, and the collector's state is then "unknown", never a claim.

    private JsonNode collectorOf(CollectorHealthView health) throws Exception {
        query.health = health;
        MvcResult result = getAs("token", "/capabilities");
        assertEquals(200, result.getResponse().getStatus());
        return json(result).get("collector");
    }

    @Test
    void aCollectorThatHasMeasuredNothingIsUnknownWithNeitherRunwayEndNorDisk() throws Exception {
        JsonNode collector = collectorOf(new CollectorHealthView(CollectorHealthView.State.LIVE, null, 0, List.of(), null));
        assertEquals("unknown", collector.get("state").asText(), "no measured runway or disk, so neither live nor degraded");
        assertFalse(collector.has("runwayEnd"), "absent, never today's date standing in for a measurement");
        assertFalse(collector.has("disk"), "absent, never 'unconfigured' standing in for an unmeasured disk");
        assertEquals(0, collector.get("unfrozenClosedPartitions").asInt());
        assertTrue(collector.get("reasons").isArray());
    }

    @Test
    void aKnownRunwayWithNoDiskMeasurementYetIsUnknownAndKeepsItsRunwayEnd() throws Exception {
        JsonNode collector = collectorOf(new CollectorHealthView(CollectorHealthView.State.LIVE,
                LocalDate.parse("2027-02-01"), 0, List.of(), null));
        assertEquals("unknown", collector.get("state").asText(), "half a measurement is not a live claim");
        assertEquals("2027-02-01", collector.get("runwayEnd").asText());
        assertFalse(collector.has("disk"));
    }

    @Test
    void aMeasuredDiskWithNoRunwayYetIsUnknownAndKeepsItsDisk() throws Exception {
        JsonNode collector = collectorOf(new CollectorHealthView(CollectorHealthView.State.LIVE, null, 0, List.of(),
                new DiskAlarmView(DiskAlarmView.State.OK)));
        assertEquals("unknown", collector.get("state").asText());
        assertFalse(collector.has("runwayEnd"));
        assertEquals("ok", collector.at("/disk/state").asText());
    }

    @Test
    void aDegradedCollectorIsDegradedEvenWithAMissingMeasurement() throws Exception {
        JsonNode collector = collectorOf(new CollectorHealthView(CollectorHealthView.State.DEGRADED, null, 0, List.of(),
                new DiskAlarmView(DiskAlarmView.State.CRITICAL)));
        assertEquals("degraded", collector.get("state").asText(), "the data plane said so: a measured fact outranks 'unknown'");
        assertFalse(collector.has("runwayEnd"));
        assertEquals("critical", collector.at("/disk/state").asText());
    }

    @Test
    void aFullyMeasuredLiveCollectorIsLive() throws Exception {
        JsonNode collector = collectorOf(new CollectorHealthView(CollectorHealthView.State.LIVE,
                LocalDate.parse("2027-02-01"), 0, List.of(), new DiskAlarmView(DiskAlarmView.State.UNCONFIGURED)));
        assertEquals("live", collector.get("state").asText());
        assertEquals("2027-02-01", collector.get("runwayEnd").asText());
        assertEquals("unconfigured", collector.at("/disk/state").asText(), "no budget set is a measured answer, not an absence");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
