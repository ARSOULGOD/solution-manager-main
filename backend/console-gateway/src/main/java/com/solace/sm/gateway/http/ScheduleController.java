package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listApplySchedules} and {@code cancelApplySchedule} (plan 012 T7; {@code OPS-Q9} Sch-2). A translator: the protocol
 * is {@link ApplyOrchestrator}'s. Both are {@code instance}-scope CATALOG operations - {@code config.view} and
 * {@code config.apply} at the instance named by the path have passed in {@link AuthenticationInterceptor} before this runs
 * ({@link InstanceResolver}) - and a cancel is any {@code config.apply} holder's, not only the submitter's.
 *
 * <p>Creating a schedule is not here: it is {@code submitApply} / {@code rollbackApply} with a {@code schedule} field.
 */
@RestController
@RequestMapping("/api/dp")
public class ScheduleController {

    /** The contract's {@code limit}: {@code minimum 1, maximum 200, default 50} (as {@code observeApply}). */
    static final int DEFAULT_LIMIT = ApplyController.DEFAULT_LIMIT;
    static final int MAX_LIMIT = ApplyController.MAX_LIMIT;

    private final ApplyOrchestrator orchestrator;

    public ScheduleController(ApplyOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @GetMapping("/instances/{instanceId}/apply-schedules")
    @ConsoleOperation(value = "listApplySchedules", resource = InstanceResolver.class)
    public ResponseEntity<ApplyScheduleList> listApplySchedules(@PathVariable UUID instanceId,
            @RequestParam(required = false) Integer limit, Session session) {
        int bound = limit == null ? DEFAULT_LIMIT : limit;
        if (bound < 1 || bound > MAX_LIMIT) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(orchestrator.listSchedules(session, instanceId, bound));
    }

    @DeleteMapping("/instances/{instanceId}/apply-schedules/{scheduleId}")
    @ConsoleOperation(value = "cancelApplySchedule", resource = InstanceResolver.class)
    public ResponseEntity<ApplySchedule> cancelApplySchedule(@PathVariable UUID instanceId,
            @PathVariable UUID scheduleId, Session session) {
        return ResponseEntity.ok(orchestrator.cancelSchedule(session, instanceId, scheduleId));
    }
}
