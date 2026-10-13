package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getDesigner}, {@code stageConfig} and {@code getConfigHistory} (plan 010 T5.3). A translator: the protocol is
 * {@link DesignerOrchestrator}'s. All three are {@code instance}-scope CATALOG operations: {@code config.view} or
 * {@code config.edit} at the instance named by the path has passed in {@link AuthenticationInterceptor} before the
 * body is read ({@link InstanceResolver}). {@code stageConfig} writes a generation to the config of record and
 * NOTHING to a host.
 */
@RestController
@RequestMapping("/api/dp")
public class DesignerController {

    private final DesignerOrchestrator designer;

    public DesignerController(DesignerOrchestrator designer) {
        this.designer = Objects.requireNonNull(designer, "designer");
    }

    @GetMapping("/instances/{instanceId}/designer")
    @ConsoleOperation(value = "getDesigner", resource = InstanceResolver.class)
    public ResponseEntity<Designer> getDesigner(@PathVariable UUID instanceId, Session session) {
        return ResponseEntity.ok(designer.designer(session, instanceId));
    }

    @PostMapping("/instances/{instanceId}/config")
    @ConsoleOperation(value = "stageConfig", resource = InstanceResolver.class)
    public ResponseEntity<StagedConfig> stageConfig(@PathVariable UUID instanceId,
            @RequestBody StageConfigRequest request, Session session) {
        return ResponseEntity.ok(designer.stage(session, instanceId, request));
    }

    @GetMapping("/instances/{instanceId}/config/history")
    @ConsoleOperation(value = "getConfigHistory", resource = InstanceResolver.class)
    public ResponseEntity<ConfigHistory> getConfigHistory(@PathVariable UUID instanceId, Session session) {
        return ResponseEntity.ok(designer.history(session, instanceId));
    }
}
