package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code submitApply}, {@code observeApply}, {@code stopInstance} (plan 010 T6.3) and {@code rollbackApply} (T8.1). A translator: the protocol is
 * {@link ApplyOrchestrator}'s. All three are {@code instance}-scope CATALOG operations: the operation's verb at the
 * instance named by the path has passed in {@link AuthenticationInterceptor} before the body is read
 * ({@link InstanceResolver}).
 *
 * <p>{@code 202} is returned when the request is admitted and durable - before any adapter call. The body carries the
 * {@code applyId} and the request's {@code stateVersion}, never the outcome; a {@code Location} points at the
 * observation to poll. A request that carries a {@code schedule} (plan 012 T7) is answered {@code 202} too, with a
 * {@code scheduleId} and no {@code applyId}: it is recorded, not admitted, and its {@code Location} is the list of the
 * instance's schedules.
 *
 * <p>⛔ This controller never constructs or calls a runtime adapter, and imports none (plan 007 Task 12, T6.7).
 */
@RestController
@RequestMapping("/api/dp")
public class ApplyController {

    /** The contract's {@code limit}: {@code minimum 1, maximum 200, default 50}. */
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ApplyOrchestrator orchestrator;

    public ApplyController(ApplyOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @PostMapping("/instances/{instanceId}/apply")
    @ConsoleOperation(value = "submitApply", resource = InstanceResolver.class)
    public ResponseEntity<AcceptedRequest> submitApply(@PathVariable UUID instanceId,
            @RequestBody SubmitApplyRequest request, Session session) {
        if (request.schedule() != null) {
            return scheduled(instanceId, orchestrator.scheduleApply(session, instanceId, request));
        }
        return accepted(instanceId, orchestrator.submit(session, instanceId, request));
    }

    @GetMapping("/instances/{instanceId}/apply/observe")
    @ConsoleOperation(value = "observeApply", resource = InstanceResolver.class)
    public ResponseEntity<ApplyObservationList> observeApply(@PathVariable UUID instanceId,
            @RequestParam(required = false) UUID applyId, @RequestParam(required = false) Long sinceStateVersion,
            @RequestParam(required = false) Integer limit, Session session) {
        int bound = limit == null ? DEFAULT_LIMIT : limit;
        if (bound < 1 || bound > MAX_LIMIT || (sinceStateVersion != null && sinceStateVersion < 0)) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(orchestrator.observe(session, instanceId, applyId, sinceStateVersion, bound));
    }

    @PostMapping("/instances/{instanceId}/apply/rollback")
    @ConsoleOperation(value = "rollbackApply", resource = InstanceResolver.class)
    public ResponseEntity<AcceptedRequest> rollbackApply(@PathVariable UUID instanceId,
            @RequestBody RollbackRequest request, Session session) {
        if (request.schedule() != null) {
            return scheduled(instanceId, orchestrator.scheduleRollback(session, instanceId, request));
        }
        return accepted(instanceId, orchestrator.rollback(session, instanceId, request));
    }

    @PostMapping("/instances/{instanceId}/stop")
    @ConsoleOperation(value = "stopInstance", resource = InstanceResolver.class)
    public ResponseEntity<ApplyAccepted> stopInstance(@PathVariable UUID instanceId,
            @RequestBody(required = false) StopRequest request, Session session) {
        ApplyAccepted body = orchestrator.stop(session, instanceId, request);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, location(instanceId, body)).body(body);
    }

    /** {@code 202} for a schedule: recorded and durable, nothing admitted; the {@code Location} is where schedules are listed. */
    private static ResponseEntity<AcceptedRequest> scheduled(UUID instanceId, ScheduleAccepted body) {
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/dp/instances/" + instanceId + "/apply-schedules")
                .body(body);
    }

    private static ResponseEntity<AcceptedRequest> accepted(UUID instanceId, ApplyAccepted body) {
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, location(instanceId, body)).body(body);
    }

    private static String location(UUID instanceId, ApplyAccepted body) {
        return "/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + body.applyId();
    }
}
