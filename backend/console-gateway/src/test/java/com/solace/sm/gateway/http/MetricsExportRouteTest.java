package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.cp.api.audit.ExportAudit.ExportResult;
import com.solace.sm.cp.api.audit.ExportAudit.RefusalReason;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Plan 011 P11-7.2: {@code exportRange} (row 60) over the real pipeline, THROUGH Spring MVC's async dispatch, with the data
 * plane faked. ⚠️ Wired and tested here; not counted served until P11-8.1's 105 M-row measurement.
 */
class MetricsExportRouteTest extends MetricsRoutesRig {

    private static final String RANGE = "from=2026-10-06T00:00:00Z&to=2026-10-06T01:00:00Z";

    /** Performs the request and, if the body is streamed, completes it through the async dispatch. */
    private MvcResult export(String token, String query) throws Exception {
        MvcResult started = mvc.perform(get(V1 + "/export/range?" + query).cookie(new Cookie(COOKIE, token))).andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(started)).andReturn();
        }
        return started;
    }

    @Test
    void exportStreamsCsvAndWritesOneAuditRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("text/csv"));
        assertEquals("attachment; filename=\"metrics-export.csv\"", result.getResponse().getHeader("Content-Disposition"));
        assertEquals("instance_id,placement_id,timestamp,value\r\nrow\r\nrow\r\n", result.getResponse().getContentAsString());
        assertEquals(1, audit.rows.size(), "one export is one audit row");
        FakeAudit.Row row = audit.rows.get(0);
        assertEquals(ExportResult.COMPLETED, row.result());
        assertEquals(2, row.rows());
        assertEquals(session.organizationId(), row.organization().value());
        assertEquals(session.principal(), row.actor());
        assertEquals(UUID.fromString(result.getResponse().getHeader("X-Correlation-Id")), row.correlationId(),
                "the row joins to the HTTP response");
        assertEquals(List.of(row.correlationId()), export.streamed.stream().map(c -> c.correlationId()).toList());
    }

    @Test
    void oneExportIsOneAuthorizationOneRateCountAndOneAuditRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        long before = gate.checks();
        MvcResult started = mvc.perform(get(V1 + "/export/range?metric=cache.size&" + RANGE + "&instanceId=" + instanceA)
                .cookie(new Cookie(COOKIE, "token"))).andReturn();
        assertTrue(started.getRequest().isAsyncStarted(), "the body is streamed as async processing");
        mvc.perform(asyncDispatch(started)).andReturn();
        assertEquals(List.of(Permission.METRICS_EXPORT), authorization.reachChecks,
                "the ASYNC re-dispatch must not decide authorization a second time");
        assertEquals(1, gate.checks() - before);
        assertEquals(1, audit.rows.size());
    }

    @Test
    void exportPastTheRowLimitIs422BeforeAnyByte() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.planFailure = new ExportTooLargeException(25_000_000L, 20_000_000L);
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(422, result.getResponse().getStatus());
        assertFalse(result.getRequest().isAsyncStarted());
        JsonNode problem = json(result);
        assertEquals("EXPORT_TOO_LARGE", problem.get("code").asText());
        assertEquals("Export too large", problem.get("title").asText());
        assertEquals(25_000_000L, problem.get("projectedRows").asLong());
        assertEquals(20_000_000L, problem.get("limit").asLong());
        assertEquals(List.of(), export.streamed, "not a byte was asked for");
        assertEquals(List.of(), audit.rows, "a refused export is not an export");
        assertNull(result.getResponse().getHeader("Content-Disposition"));
    }

    @Test
    void everyBoundedExportThreadBusyIsTheSameCleanTooManyRequestsAndNeverAnAudit() throws Exception {
        authorization.authorizedIds.add(instanceA);
        context.getBean(RefusableExecutor.class).refuse = true;   // W4-09: the pool is bounded, so a submit can be refused
        MvcResult started = mvc.perform(get(V1 + "/export/range?metric=cache.size&" + RANGE + "&instanceId=" + instanceA)
                .cookie(new Cookie(COOKIE, "token"))).andReturn();
        assertTrue(started.getRequest().isAsyncStarted());
        // MockMvc's asyncDispatch() insists on a result its own interceptor records, and that interceptor only runs on the
        // pool thread a REFUSED submit never reached; a real container dispatches the refusal (the manager already holds
        // it as the concurrent result), so do the same dispatch by hand.
        MvcResult result = mvc.perform(servletContext -> {
            var request = started.getRequest();
            request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
            request.setAsyncStarted(false);
            return request;
        }).andReturn();
        assertEquals(429, result.getResponse().getStatus());
        assertEquals("export-concurrency", json(result).get("reason").asText());
        assertTrue(Integer.parseInt(result.getResponse().getHeader("Retry-After")) >= 1);
        assertEquals(List.of(), export.streamed, "the stream never started");
        assertEquals(List.of(), audit.rows);
    }

    @Test
    void aConcurrencyCapSpentOnTheStreamingThreadIsACleanTooManyRequests() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.streamFailure = new ExportBusyException(ExportBusyException.Reason.ORGANIZATION);
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(429, result.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(result).get("code").asText());
        assertEquals("export-concurrency", json(result).get("reason").asText());
        assertTrue(Integer.parseInt(result.getResponse().getHeader("Retry-After")) >= 1);
        assertEquals(List.of(), audit.rows);
        assertNull(result.getResponse().getHeader("Content-Disposition"), "nothing of a download was set");
        // The shared database budget staying spent is a different condition.
        export.streamFailure = new ExportBusyException(ExportBusyException.Reason.DATABASE_BUDGET);
        assertEquals(503, export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA).getResponse().getStatus());
        export.streamFailure = null;
        export.planFailure = new ExportBusyException(ExportBusyException.Reason.DATABASE_BUDGET);
        assertEquals(503, export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA).getResponse().getStatus());
        assertEquals(List.of(), audit.rows);
    }

    @Test
    void aStreamThatFailsAfterItsFirstByteIsAuditedFailedAndOneThatIsCutIsAuditedCut() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.writeBeforeFailing = true;
        export.streamFailure = new IllegalStateException("database went away");
        try {
            export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        } catch (Exception expected) {
            // the container reports the broken stream; the audit is what is asserted
        }
        assertEquals(ExportResult.FAILED, audit.rows.get(0).result());
        audit.rows.clear();
        export.streamFailure = new IOException("client went away");
        try {
            export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        } catch (Exception expected) {
            // as above
        }
        assertEquals(ExportResult.CUT, audit.rows.get(0).result());
    }

    @Test
    void theStreamIsCutAtTheConfiguredLimitAndAuditedCutWithTheRowsDelivered() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.midStream = () -> clock.advance(Duration.ofMinutes(31));   // the default bound is 30 minutes
        try {
            export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        } catch (Exception expected) {
            // the second half of the body is refused by the deadline
        }
        assertEquals(1, audit.rows.size());
        assertEquals(ExportResult.CUT, audit.rows.get(0).result());
        assertEquals(0, audit.rows.get(0).rows(), "the header was delivered, no data row was");
    }

    @Test
    void anAuditThatFailsDoesNotReplaceHowTheExportEnded() throws Exception {
        authorization.authorizedIds.add(instanceA);
        // A recorder that throws is swallowed and logged: the client still gets its CSV.
        audit.failure = new IllegalStateException("audit database down");
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(200, result.getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------------- who, and which ids

    @Test
    void aCallerWithoutTheExportVerbIs403AndNothingIsPlannedOrStreamed() throws Exception {
        authorization.outcome = Outcome.DENY;
        assertEquals(403, export("token", "metric=cache.size&" + RANGE).getResponse().getStatus());
        assertEquals(List.of(), export.planned);
        assertEquals(List.of(Permission.METRICS_EXPORT), authorization.reachChecks);
    }

    @Test
    void exportIntersectsWithWhereMetricsExportIsHeldAndOutOfReachIdsAreOmittedSilently() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_EXPORT, java.util.Set.of(instanceA));
        UUID missing = UUID.randomUUID();
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA + "&instanceId="
                + instanceB + "&instanceId=" + missing);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(instanceA), export.streamed.get(0).instanceIds());
        assertEquals(Permission.METRICS_EXPORT, authorization.subsetCalls.get(0).permission());
        // Only ids out of reach: a header-only export, exactly as for ids that do not exist; never a 403.
        export.streamed.clear();
        MvcResult none = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceB);
        assertEquals(200, none.getResponse().getStatus());
        assertEquals(List.of(), export.streamed.get(0).instanceIds());
    }

    @Test
    void noInstanceIdSelectsEveryInstanceTheCallerCanReach() throws Exception {
        authorization.authorizedIdsByPermission.put(Permission.METRICS_EXPORT, java.util.Set.of(instanceB));
        export("token", "metric=cache.size&" + RANGE);
        assertEquals(List.of(instanceB), export.streamed.get(0).instanceIds());
    }

    @Test
    void theQueryIsValidatedBeforeAnyDataPlaneCall() throws Exception {
        for (String bad : List.of("metric=Bad%20Name&" + RANGE, "metric=cache.size&from=nope&to=2026-10-06T01:00:00Z",
                "metric=cache.size&from=2026-10-06T01:00:00Z&to=2026-10-06T00:00:00Z", "metric=cache.size&" + RANGE
                        + "&instanceId=not-a-uuid", RANGE, "metric=cache.size&from=2026-10-06T00:00:00Z")) {
            assertEquals(400, export("token", bad).getResponse().getStatus(), bad);
        }
        assertEquals(List.of(), export.planned);
    }

    @Test
    void theExportSharesTheQueryRateGate() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, export("token", "metric=cache.size&" + RANGE).getResponse().getStatus());
        }
        MvcResult limited = export("token", "metric=cache.size&" + RANGE);
        assertEquals(429, limited.getResponse().getStatus());
        assertEquals("query-rate", json(limited).get("reason").asText());
    }
    // --------------------------------------------------- a refused export is one DENY row (OR-112 (6), W4-04)

    private FakeAudit.Denial onlyDenial(MvcResult result) {
        assertEquals(1, audit.denials.size(), "one refusal is one DENY row");
        FakeAudit.Denial denial = audit.denials.get(0);
        assertEquals(session.principal(), denial.actor());
        assertEquals(session.organizationId(), denial.organization().value());
        assertEquals(UUID.fromString(result.getResponse().getHeader("X-Correlation-Id")), denial.correlationId(),
                "the row joins to the HTTP response");
        assertEquals(List.of(), audit.rows, "and a refusal is never recorded as an export");
        return denial;
    }

    @Test
    void aForbiddenExportWritesTheDenyRow() throws Exception {
        authorization.outcome = Outcome.DENY;
        MvcResult result = export("token", "metric=cache.size&" + RANGE);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(RefusalReason.FORBIDDEN, onlyDenial(result).reason());
    }

    @Test
    void anExportPastTheRowLimitWritesTheDenyRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.planFailure = new ExportTooLargeException(25_000_000L, 20_000_000L);
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals(RefusalReason.TOO_LARGE, onlyDenial(result).reason());
    }

    @Test
    void aSpentQueryRateWritesTheDenyRowAndNotTheTenAllowedExports() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(200, export("token", "metric=cache.size&" + RANGE).getResponse().getStatus());
        }
        assertEquals(List.of(), audit.denials, "ten allowed exports are ten ALLOW rows and no DENY row");
        audit.rows.clear();
        MvcResult limited = export("token", "metric=cache.size&" + RANGE);
        assertEquals(429, limited.getResponse().getStatus());
        assertEquals(RefusalReason.RATE_LIMITED, onlyDenial(limited).reason());
    }

    @Test
    void aConcurrencyCapSpentOnTheStreamingThreadWritesTheDenyRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        export.streamFailure = new ExportBusyException(ExportBusyException.Reason.ORGANIZATION);
        MvcResult result = export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA);
        assertEquals(429, result.getResponse().getStatus());
        assertEquals(RefusalReason.RATE_LIMITED, onlyDenial(result).reason());
    }

    @Test
    void everyBoundedExportThreadBusyWritesTheDenyRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        context.getBean(RefusableExecutor.class).refuse = true;
        MvcResult started = mvc.perform(get(V1 + "/export/range?metric=cache.size&" + RANGE + "&instanceId=" + instanceA)
                .cookie(new Cookie(COOKIE, "token"))).andReturn();
        assertTrue(started.getRequest().isAsyncStarted());
        assertEquals(List.of(), audit.denials, "nothing is decided until the async dispatch");
        MvcResult result = mvc.perform(servletContext -> {
            var request = started.getRequest();
            request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
            request.setAsyncStarted(false);
            return request;
        }).andReturn();
        assertEquals(429, result.getResponse().getStatus());
        assertEquals(RefusalReason.RATE_LIMITED, onlyDenial(result).reason());
    }

    @Test
    void anAllowedExportAndTheRefusalsTheRulingDoesNotNameWriteNoDenyRow() throws Exception {
        authorization.authorizedIds.add(instanceA);
        assertEquals(200, export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA)
                .getResponse().getStatus());
        assertEquals(400, export("token", "metric=not%20a%20metric&" + RANGE).getResponse().getStatus());
        assertEquals(401, export("no-such-token", "metric=cache.size&" + RANGE).getResponse().getStatus());
        export.streamFailure = new ExportBusyException(ExportBusyException.Reason.DATABASE_BUDGET);
        assertEquals(503, export("token", "metric=cache.size&" + RANGE + "&instanceId=" + instanceA)
                .getResponse().getStatus());
        assertEquals(List.of(), audit.denials, "only 403, 422 and 429 are DENY rows (OR-112 (6))");
    }

    @Test
    void anotherRoutesRefusalIsNotAnExportDenial() throws Exception {
        authorization.outcome = Outcome.DENY;
        MvcResult query = mvc.perform(get(V1 + "/metrics/catalog").cookie(new Cookie(COOKIE, "token"))).andReturn();
        assertEquals(403, query.getResponse().getStatus(), "the other route is refused too");
        assertEquals(List.of(), audit.denials);
    }

    @Test
    void aDenyRowThatCannotBeWrittenNeverReplacesTheRefusal() throws Exception {
        authorization.outcome = Outcome.DENY;
        audit.failure = new IllegalStateException("audit database down");
        MvcResult result = export("token", "metric=cache.size&" + RANGE);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(result).get("code").asText());
    }
}
