package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Plan 011 P11-7.2: rows 62-66, the per-instance dashboard, over the real pipeline with the ports faked. The store is a
 * VERIFYING fake: it records every call, so "never reached" is an assertion on its log.
 */
class MetricsDashboardRoutesTest extends MetricsRoutesRig {

    private String db(UUID instance) {
        return "/dashboards/db_" + instance;
    }

    private static final String WIDGET = "{\"schemaVersion\":1,\"title\":\"t\"}";
    private static final Map<String, String> REV_3 = Map.of("X-SM-Dashboard-Revision", "3");

    // -------------------------------------------------------------------- existence and reach come BEFORE the store

    @Test
    void anUnknownOrForeignInstanceIdGets404BeforeDashboardStoreIsCalled() throws Exception {
        UUID unknown = UUID.randomUUID();
        authorization.outcome = Outcome.NOT_FOUND;   // the control plane's directory has no such instance for this caller
        for (MvcResult result : List.of(
                getAs("token", db(unknown)),
                sendAs("POST", "token", db(unknown) + "/widgets", WIDGET, REV_3),
                sendAs("PUT", "token", db(unknown) + "/layout", "{\"layout\":[]}", REV_3),
                sendAs("PATCH", "token", db(unknown) + "/widgets/d_0123456789ab", "{\"hidden\":true}", REV_3),
                sendAs("POST", "token", db(unknown) + "/reset", null, REV_3))) {
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertEquals(List.of(), store.calls, "the store returns a fresh view for ANY id, so it must never be reached");
        assertEquals(0, query.activeCalls.get());
    }

    @Test
    void aMalformedDashboardIdIs404LikeAnUnknownInstance() throws Exception {
        for (String bad : List.of("db_nope", "x_" + instanceA, "db_" + instanceA.toString().toUpperCase(),
                "db_" + instanceA + "0", "db_", instanceA.toString())) {
            MvcResult result = getAs("token", "/dashboards/" + bad);
            assertEquals(404, result.getResponse().getStatus(), bad);
            assertEquals("NOT_FOUND", json(result).get("code").asText());
            assertFalse(result.getResponse().getContentAsString().contains(bad.length() > 3 ? bad.substring(3) : "~"),
                    "the message is never built from the path");
        }
        assertEquals(List.of(), authorization.permissions, "no authorization was even attempted on an id that names nothing");
        assertEquals(List.of(), store.calls);
    }

    @Test
    void aDashboardIdResolvesToItsInstanceScopeFromThePathAlone() throws Exception {
        assertEquals(200, getAs("token", db(instanceA)).getResponse().getStatus());
        assertEquals(Permission.DASHBOARD_VIEW, authorization.permissions.get(0));
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(instanceA, authorization.resources.get(0).id());
        assertEquals(session.organizationId(), authorization.resources.get(0).tenant().value());
        // The write verbs, each at the same instance scope.
        sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, REV_3);
        sendAs("PUT", "token", db(instanceA) + "/layout", "{\"layout\":[]}", REV_3);
        sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", "{\"hidden\":true}", REV_3);
        sendAs("POST", "token", db(instanceA) + "/reset", null, REV_3);
        assertEquals(List.of(Permission.DASHBOARD_VIEW, Permission.DASHBOARD_EDIT, Permission.DASHBOARD_EDIT,
                Permission.DASHBOARD_EDIT, Permission.DASHBOARD_RESET), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.id().equals(instanceA)));
    }

    @Test
    void aViewerOfAnotherInstanceCannotReadThisDashboard() throws Exception {
        authorization.outcomeByResource.put(instanceB, Map.of(Permission.DASHBOARD_VIEW, Outcome.NOT_FOUND));
        assertEquals(404, getAs("token", db(instanceB)).getResponse().getStatus());
        assertEquals(200, getAs("token", db(instanceA)).getResponse().getStatus());
        assertEquals(List.of("get:" + instanceA), store.calls);
    }

    @Test
    void aDashboardWriteForAnInstanceTheDpDoesNotReportActiveIs404AndWritesNothing() throws Exception {
        UUID pending = UUID.randomUUID();
        putInstance(pending, "mon-pending", com.solace.sm.dp.api.inventory.InstanceLifecycle.PENDING,
                com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH);
        // The control plane knows it (authorization allows), the data plane does not report it active (an OR-90 orphan).
        for (MvcResult result : List.of(
                getAs("token", db(pending)),
                sendAs("POST", "token", db(pending) + "/widgets", WIDGET, REV_3),
                sendAs("PUT", "token", db(pending) + "/layout", "{\"layout\":[]}", REV_3),
                sendAs("PATCH", "token", db(pending) + "/widgets/d_0123456789ab", "{\"hidden\":true}", REV_3),
                sendAs("POST", "token", db(pending) + "/reset", null, REV_3))) {
            assertEquals(404, result.getResponse().getStatus());
        }
        assertEquals(List.of(), store.calls, "nothing was written, nothing was even read");
    }

    // ------------------------------------------------------------------------------------------- the revision header

    @Test
    void everyWriteMustPresentTheRevisionItRead() throws Exception {
        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("X-SM-Dashboard-Revision", "abc"),
                Map.of("X-SM-Dashboard-Revision", "-1"), Map.of("X-SM-Dashboard-Revision", "1.5"),
                Map.of("X-SM-Dashboard-Revision", ""), Map.of("X-SM-Dashboard-Revision", "9".repeat(19)))) {
            for (MvcResult result : List.of(
                    sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, headers),
                    sendAs("PUT", "token", db(instanceA) + "/layout", "{\"layout\":[]}", headers),
                    sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", "{\"hidden\":true}", headers),
                    sendAs("POST", "token", db(instanceA) + "/reset", null, headers))) {
                assertEquals(400, result.getResponse().getStatus(), headers.toString());
            }
        }
        assertEquals(List.of(), store.calls, "no last-writer-wins form");
    }

    @Test
    void theRevisionThePageReadIsWhatTheStoreIsGiven() throws Exception {
        sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, Map.of("X-SM-Dashboard-Revision", "41"));
        sendAs("POST", "token", db(instanceA) + "/reset", null, Map.of("X-SM-Dashboard-Revision", "0"));
        assertEquals(List.of(41L, 0L), store.revisions);
    }

    @Test
    void aStaleRevisionIs409AndAnInvalidDocumentIs400AndAnUnknownWidgetIs404() throws Exception {
        store.failure = new InvalidDashboardException(InvalidDashboardException.Kind.STALE_REVISION, "stale");
        MvcResult stale = sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, REV_3);
        assertEquals(409, stale.getResponse().getStatus());
        assertEquals("CONFLICT", json(stale).get("code").asText());
        store.failure = new InvalidDashboardException(InvalidDashboardException.Kind.INVALID,
                "The widget must target this instance.");
        MvcResult invalid = sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, REV_3);
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("The widget must target this instance.", json(invalid).get("detail").asText());
        store.failure = new InvalidDashboardException(InvalidDashboardException.Kind.UNKNOWN_WIDGET, "no");
        assertEquals(404, sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", "{\"hidden\":true}", REV_3)
                .getResponse().getStatus());
        // W4-08: the service says UNKNOWN_WIDGET for a shared widget; any OTHER invalid answer from a hide (for
        // example a document grown past its size bound) is the contract's 400, never a 404.
        store.failure = new InvalidDashboardException(InvalidDashboardException.Kind.INVALID,
                "The dashboard document is too large.");
        MvcResult tooLarge = sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", "{\"hidden\":true}", REV_3);
        assertEquals(400, tooLarge.getResponse().getStatus());
        assertEquals("The dashboard document is too large.", json(tooLarge).get("detail").asText());
    }

    // --------------------------------------------------------------------------------------------- the wire shapes

    @Test
    void dashboardRoutesUseTheDemoWireIdAndDocumentShape() throws Exception {
        MvcResult result = getAs("token", db(instanceA));
        JsonNode dashboard = json(result);
        assertEquals("db_" + instanceA, dashboard.get("id").asText());
        assertEquals(1, dashboard.get("schemaVersion").asInt());
        assertEquals(4, dashboard.get("revision").asLong(), "the revision a write must present next");
        assertEquals(List.of("id", "schemaVersion", "revision", "widgets", "layout", "range", "refresh"),
                java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(dashboard.fieldNames(),
                        0), false).toList());
        assertTrue(dashboard.at("/refresh/intervalSeconds").isNull());
    }

    @Test
    void theDefaultLayerIsDerivedFromThePackageAndTheDataPlanesActiveList() throws Exception {
        query.active = List.of("cache.size", "cache.pushed");
        getAs("token", db(instanceA));
        com.solace.sm.cp.api.dashboard.DefaultLayerInput input = store.inputs.get(0);
        assertEquals("cache-engine", input.entityType());
        assertEquals(java.util.Set.of("cache.size", "cache.pushed"), input.activeMetrics());
        assertEquals(List.of("cache.size", "cache.hit_count", "cache.latency", "cache.pushed", "cache.portless"),
                input.declared().stream().map(m -> m.name()).toList());
        assertEquals(List.of(true, true, true, false, true), input.declared().stream().map(m -> m.defaultChart()).toList(),
                "OQ-7 (b): a missing flag is true, false opts out");
        assertEquals(session.organizationId(), query.organizations.get(0));
    }

    @Test
    void aSavedWidgetIsReturnedWithItsServerAssignedId() throws Exception {
        MvcResult result = sendAs("POST", "token", db(instanceA) + "/widgets", WIDGET, REV_3);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("w_0123456789ab", json(result).get("id").asText());
        assertEquals("shared", json(result).get("layer").asText());
    }

    @Test
    void theLayoutBodyIsExactlyTheLayoutKeyAndTheStoreGetsTheArrayText() throws Exception {
        assertEquals(204, sendAs("PUT", "token", db(instanceA) + "/layout", "{\"layout\":[{\"widgetId\":\"d_0123456789ab\"}]}",
                REV_3).getResponse().getStatus());
        for (String bad : List.of("{}", "[]", "{\"layout\":{}}", "{\"layout\":[],\"x\":1}", "{\"layout\":[],\"layout\":[]}",
                "not json")) {
            assertEquals(400, sendAs("PUT", "token", db(instanceA) + "/layout", bad, REV_3).getResponse().getStatus(), bad);
        }
        assertEquals(1, store.calls.size(), "only the well-formed body reached the store");
    }

    @Test
    void aHiddenFlagBodyIsStrict() throws Exception {
        assertEquals(204, sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", "{\"hidden\":false}", REV_3)
                .getResponse().getStatus());
        for (String bad : List.of("{}", "{\"hidden\":true,\"x\":1}", "{\"hidden\":null}")) {
            assertEquals(400, sendAs("PATCH", "token", db(instanceA) + "/widgets/d_0123456789ab", bad, REV_3).getResponse()
                    .getStatus(), bad);
        }
        assertEquals(404, sendAs("PATCH", "token", db(instanceA) + "/widgets/not-a-widget", "{\"hidden\":true}", REV_3)
                .getResponse().getStatus());
    }

    @Test
    void noRouteFallsBackToTheDemo() throws Exception {
        MvcResult result = getAs("token", "/dashboards");
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertEquals(404, getAs("token", "/no-such").getResponse().getStatus());
    }
}
