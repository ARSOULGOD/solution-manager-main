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
 * {@code getLogLevel} and {@code setLogLevel} (plan 010 T9.3, {@code OR-103} option Rd). A translator: the protocol is
 * {@link LogLevelOrchestrator}'s. Both are {@code instance}-scope CATALOG operations: the operation's verb
 * ({@code logs.read}, {@code logs.setLevel}) at the instance the path names has passed in
 * {@link AuthenticationInterceptor} before this handler runs ({@link InstanceResolver}).
 *
 * <p>{@code getLogLevel} is a bounded live read; {@code setLogLevel} with {@code mode: config} is a normal apply
 * ({@code 202}, observed at {@code /apply/observe}); {@code mode: live} is refused with a fixed detail.
 *
 * <p>⛔ This controller never holds a transport or a runtime adapter.
 */
@RestController
@RequestMapping("/api/dp")
public class LogLevelController {

    private final LogLevelOrchestrator orchestrator;

    public LogLevelController(LogLevelOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @GetMapping("/instances/{instanceId}/log-levels/{component}")
    @ConsoleOperation(value = "getLogLevel", resource = InstanceResolver.class)
    public ResponseEntity<LogLevel> getLogLevel(@PathVariable UUID instanceId, @PathVariable String component,
            @RequestParam(required = false) UUID placementId, Session session) {
        return ResponseEntity.ok(orchestrator.read(session, instanceId, component, placementId));
    }

    @PostMapping("/instances/{instanceId}/log-levels/{component}")
    @ConsoleOperation(value = "setLogLevel", resource = InstanceResolver.class)
    public ResponseEntity<ApplyAccepted> setLogLevel(@PathVariable UUID instanceId, @PathVariable String component,
            @RequestBody SetLogLevelRequest request, Session session) {
        ApplyAccepted body = orchestrator.change(session, instanceId, component, request);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION,
                "/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + body.applyId()).body(body);
    }
}
