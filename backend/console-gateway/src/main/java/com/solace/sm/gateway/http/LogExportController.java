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
import com.solace.sm.gateway.http.metrics.MetricsRejectionException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

/**
 * {@code exportLogs} (plan 016 task 10): a bounded download of one agent placement's collected log. An {@code instance}-scope
 * CATALOG operation authorized by {@code logs.read} at the path's instance ({@link InstanceResolver}) through the one
 * authorization path before this handler runs; no check lives here and no verb is added.
 *
 * <ul>
 *   <li>Everything that can be refused is refused <b>before</b> the first byte, as a real status: an instance that is not
 *       visible, an undeclared component, a foreign or unresolvable placement is {@code 404}; an ambiguous placement is {@code 400};
 *       a placement on an SSH target is {@code 501 AGENT_OPERATION_UNSUPPORTED} (no SSH export exists), decided on the data plane's
 *       own view without touching a host; too many exports (the data plane's cap or this gateway's threads) is
 *       {@code 429 RATE_LIMITED} with {@code reason: log-export-concurrency}; an agent that cannot serve it now is
 *       {@code 503 SERVICE_UNAVAILABLE} with the data plane's fixed reason. The data plane waits, bounded, for the agent's first
 *       answer before {@code open} returns, so these are known here. Only what happens after the first byte is an in-band marker.</li>
 *   <li>{@code fromSeq} (optional, a non-negative int64, default 0) is the resume point a marker named ({@code resume from seq N}); a
 *       value that is not one is {@code 400} before anything is read.</li>
 *   <li>The organization is {@code Session.organizationId()} (I5). The data plane re-resolves the placement under it.</li>
 *   <li>The body is written by a {@link WebAsyncTask} on the log export's OWN bounded threads ({@link LogExportExecution}): the stream is pulled one piece at a time, so a slow client slows the agent
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
    private final LogExportExecution execution;

    public LogExportController(DeclareOrchestrator instances, SolutionCatalog catalog, DesignerCodec codec,
            LogExportService export, LogExportExecution execution) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.components = new DeclaredComponents(catalog, codec);
        this.export = Objects.requireNonNull(export, "export");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @GetMapping("/instances/{instanceId}/logs/{component}/export")
    @ConsoleOperation(value = "exportLogs", resource = InstanceResolver.class)
    public WebAsyncTask<Void> exportLogs(@PathVariable UUID instanceId, @PathVariable String component,
            @RequestParam(required = false) UUID placementId, @RequestParam(required = false) String fromSeq,
            Session session, HttpServletResponse response) {
        UUID organization = session.organizationId();
        long resumeFrom = resumePoint(fromSeq);
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
        // the gateway's own threads: refused as a status before the data plane is asked
        if (!execution.tryAcquire()) {
            throw busy();
        }
        Runnable release = execution.releaser();
        LogExportStream stream;
        try {
            stream = export.open(organization, instanceId, placement, resumeFrom);
            Optional<LogExportStream.Refusal> refusal = stream.refusal();
            if (refusal.isPresent()) {
                stream.close();
                throw refused(refusal.get());
            }
        } catch (RuntimeException e) {
            release.run();
            throw e;
        }
        return download(stream, response, "logs-" + instanceId + ".log", execution, release);
    }

    /**
     * The download as an async task on the log export's own threads. Headers are set inside the task, so a task the executor
     * refuses leaves no stray {@code Content-Disposition} on the refusal. The permit goes back when the pump has actually
     * finished (not at a timeout while a thread may still be writing); if the task never started, completion hands it back.
     * Every abnormal end after the first byte aborts the connection ({@link LogExportAbortedException}).
     */
    static WebAsyncTask<Void> download(LogExportStream stream, HttpServletResponse response, String filename,
            LogExportExecution execution, Runnable release) {
        AtomicBoolean started = new AtomicBoolean();
        WebAsyncTask<Void> task = new WebAsyncTask<>(execution.timeoutMillis(), execution.executor(), () -> {
            started.set(true);
            try {
                response.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8).toString());
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                pump(stream, response.getOutputStream());
                return null;
            } catch (LogExportAbortedException aborted) {
                throw aborted;
            } catch (IOException | RuntimeException failed) {
                throw new LogExportAbortedException(failed);
            } finally {
                stream.close();
                release.run();
            }
        });
        task.onTimeout(() -> {
            throw new LogExportAbortedException(null);
        });
        task.onError(() -> {
            throw new LogExportAbortedException(null);
        });
        task.onCompletion(() -> {
            stream.close();
            if (!started.get()) {
                release.run();
            }
        });
        return task;
    }

    /** The {@code seq} an earlier abnormal end named: a non-negative int64, absent meaning the oldest line; anything else is {@code 400}. */
    static long resumePoint(String fromSeq) {
        if (fromSeq == null) {
            return 0L;
        }
        long value;
        try {
            value = Long.parseLong(fromSeq);
        } catch (NumberFormatException notANumber) {
            value = -1;
        }
        if (value < 0) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return value;
    }

    private static MetricsRejectionException busy() {
        return new MetricsRejectionException(ErrorCode.RATE_LIMITED, MetricsRejectionException.LOG_EXPORT_CONCURRENCY, 5);
    }

    /** A refusal known before the first byte, as the status it deserves. */
    private static RuntimeException refused(LogExportStream.Refusal refusal) {
        return switch (refusal.kind()) {
            case CAPACITY -> busy();
            case UNRESOLVABLE -> new ResourceNotFoundException(ResourceKind.INSTANCE);
            case UNAVAILABLE -> new LogExportUnavailableException(refusal.reason());
        };
    }

    /**
     * Writes the stream to the client, a piece at a time, flushing after each (a keep-alive writes nothing; the flush is
     * best effort, and a client that went away is certain to be noticed only at the next real write or the deadline). The
     * stream is always closed, which cancels the agent request and releases its slots.
     *
     * <p>A clean end with no marker means complete, so every abnormal end writes the stream's fixed {@code incomplete} marker
     * where the response is still writable: an interrupt (a shutdown) restores the flag and writes it; an unexpected failure
     * writes it and propagates. {@link #download} then turns the failure (or an {@link IOException} from a write, where no
     * marker was possible) into a {@link LogExportAbortedException}, which {@link LogExportAbortAdvice} leaves unresolved, so
     * the container closes the connection WITHOUT the chunked terminator and the client sees a failed transfer; no error body is
     * appended (measured on a real container, {@code LogExportTomcatTest}).
     */
    static void pump(LogExportStream stream, OutputStream out) throws IOException {
        try (stream) {
            try {
                for (String piece = stream.next(); piece != null; piece = stream.next()) {
                    if (!piece.isEmpty()) {
                        out.write(piece.getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                writeMarker(stream, out);
            } catch (RuntimeException failed) {
                writeMarker(stream, out);
                throw failed;
            }
        }
    }

    private static void writeMarker(LogExportStream stream, OutputStream out) throws IOException {
        out.write(stream.abort().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
