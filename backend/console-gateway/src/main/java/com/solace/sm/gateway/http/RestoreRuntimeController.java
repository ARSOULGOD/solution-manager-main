package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code restoreRuntimeConfig} (plan 010 T10.2, OR-45): {@code B/D12}'s manual drift reconcile, "re-apply desired". A
 * translator: the protocol is {@link ApplyOrchestrator}'s. An {@code instance}-scope CATALOG operation: {@code config.apply}
 * at the instance named by the path has passed in {@link AuthenticationInterceptor} before the body is read.
 *
 * <p>{@code 202} is returned when the request is admitted and durable, before any adapter call; the body carries the
 * {@code applyId} and the request's {@code stateVersion}, and a {@code Location} points at the observation to poll, exactly
 * as {@code submitApply}'s. ⛔ This controller never constructs or calls a runtime adapter, and imports none.
 */
@RestController
@RequestMapping("/api/dp")
public class RestoreRuntimeController {

    private final ApplyOrchestrator orchestrator;

    public RestoreRuntimeController(ApplyOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @PostMapping("/instances/{instanceId}/config/restore-runtime")
    @ConsoleOperation(value = "restoreRuntimeConfig", resource = InstanceResolver.class)
    public ResponseEntity<ApplyAccepted> restoreRuntimeConfig(@PathVariable UUID instanceId,
            @RequestBody RestoreRuntimeRequest request, Session session) {
        ApplyAccepted body = orchestrator.restoreRuntime(session, instanceId, request);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION,
                "/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + body.applyId()).body(body);
    }
}
