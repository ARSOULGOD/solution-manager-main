package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code governance} tag's read operations (plan 010 T5.3 serves {@code getConfigLadder}; health, logs and the
 * rest arrive with their own tasks). {@code getConfigLadder} is an {@code instance}-scope CATALOG operation
 * authorized by {@code config.view} at the path's instance.
 */
@RestController
@RequestMapping("/api/dp")
public class GovernanceController {

    private final DesignerOrchestrator designer;

    public GovernanceController(DesignerOrchestrator designer) {
        this.designer = Objects.requireNonNull(designer, "designer");
    }

    @GetMapping("/instances/{instanceId}/config-ladder")
    @ConsoleOperation(value = "getConfigLadder", resource = InstanceResolver.class)
    public ResponseEntity<ConfigLadder> getConfigLadder(@PathVariable UUID instanceId, Session session) {
        return ResponseEntity.ok(designer.ladder(session, instanceId));
    }
}
