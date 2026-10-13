package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.ConsoleOperation;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code queryRange} and {@code queryInstant} (rows 58-59). Both are {@code x-sm-scope: reach}: the interceptor has already
 * required {@code metrics.query} held at one or more grants in the caller's organization (else {@code 403}); everything else
 * is {@link QueryComposer}'s.
 *
 * <p>The rate gate (10 per second per session, {@code 429}, {@code reason: query-rate}) is taken HERE, in the handler,
 * after authorization and before any data-plane call: once per request, whatever the dispatch does.
 */
@RestController
@RequestMapping("/api/console/v1/query")
public class QueryController {

    private final QueryComposer composer;
    private final MetricsRateGate gate;

    public QueryController(QueryComposer composer, MetricsRateGate gate) {
        this.composer = Objects.requireNonNull(composer, "composer");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @PostMapping("/range")
    @ConsoleOperation("queryRange")
    public ResponseEntity<MetricsWire.QueryResult> queryRange(@RequestBody MetricQueryRequest body, Session session) {
        gate.check(session.id());
        return ResponseEntity.ok(composer.range(session, body));
    }

    @PostMapping("/instant")
    @ConsoleOperation("queryInstant")
    public ResponseEntity<MetricsWire.QueryResult> queryInstant(@RequestBody MetricQueryRequest body, Session session) {
        gate.check(session.id());
        return ResponseEntity.ok(composer.instant(session, body));
    }
}
