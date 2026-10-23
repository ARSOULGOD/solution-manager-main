package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.logs.LogExportService;
import com.solace.sm.dp.api.logs.LogExportStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * {@code exportLogs} (plan 016 task 10): a bounded download of one agent placement's collected log. An {@code instance}-scope
 * CATALOG operation authorized by {@code logs.read} at the path's instance ({@link InstanceResolver}) through the one
 * authorization path before this handler runs; no check lives here and no verb is added.
 *
 * <ul>
 *   <li>Everything that can be refused is refused <b>before</b> the first byte: an instance that is not visible, an
 *       undeclared component or a foreign placement is {@code 404}, an ambiguous placement is {@code 400}, and a placement on
 *       an SSH target is {@code 501 AGENT_OPERATION_UNSUPPORTED} (no SSH export exists), decided on the data plane's own
 *       view without touching a host.</li>
 *   <li>The organization is {@code Session.organizationId()} (I5). The data plane re-resolves the placement under it.</li>
 *   <li>The body is {@link StreamingResponseBody}: the stream is pulled one piece at a time, so a slow client slows the agent
 *       (the data plane grants credit per chunk taken) and nothing accumulates here. Size and time bounds, gap and truncation
 *       marker lines are the data plane's ({@code AgentLogExportService}); a stream with a failed write (the client went away)
 *       is closed, which cancels the agent request and frees its slot.</li>
 *   <li>⚠️ A log line is the managed integration's own output and is not redacted; {@code logs.read} is the control.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dp")
public class LogExportController {

    private final DeclareOrchestrator instances;
    private final DeclaredComponents components;
    private final LogExportService export;

    public LogExportController(DeclareOrchestrator instances, SolutionCatalog catalog, DesignerCodec codec,
            LogExportService export) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.components = new DeclaredComponents(catalog, codec);
        this.export = Objects.requireNonNull(export, "export");
    }

    @GetMapping("/instances/{instanceId}/logs/{component}/export")
    @ConsoleOperation(value = "exportLogs", resource = InstanceResolver.class)
    public ResponseEntity<StreamingResponseBody> exportLogs(@PathVariable UUID instanceId, @PathVariable String component,
            @RequestParam(required = false) UUID placementId, Session session) {
        UUID organization = session.organizationId();
        InstanceView view = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        components.require(organization, view, component);
        if (view.placements().isEmpty()) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        UUID placement = LogsController.placementFor(view, placementId);
        boolean agent = view.placements().stream()
                .filter(p -> p.placementId().equals(placement)).anyMatch(PlacementView::isAgent);
        if (!agent) {
            throw new GatewayRejectionException(ErrorCode.AGENT_OPERATION_UNSUPPORTED);
        }
        StreamingResponseBody body = out -> pump(export.open(organization, instanceId, placement), out);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(component + "-" + instanceId + ".log").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Writes the stream to the client, a piece at a time, flushing after each (a keep-alive writes nothing but is flushed too,
     * which is how a client that went away is noticed while the agent is quiet). The stream is always closed, which cancels
     * the agent request and releases its slots.
     */
    static void pump(LogExportStream stream, OutputStream out) throws IOException {
        try (stream) {
            for (String piece = stream.next(); piece != null; piece = stream.next()) {
                if (!piece.isEmpty()) {
                    out.write(piece.getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
