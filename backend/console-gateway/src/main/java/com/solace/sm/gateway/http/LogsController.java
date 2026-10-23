package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code readLogs} (plan 010 T9.1, {@code OR-43}): an {@code instance}-scope CATALOG operation authorized by
 * {@code logs.read} at the path's instance ({@link InstanceResolver}) before this handler runs.
 *
 * <p>A bounded READ on the request thread (the one mutating apply path is untouched): the data plane opens one SSH
 * session to the placement's target, runs one closed {@code docker logs}, and closes it. ⚠️ <b>A log line is the managed
 * integration's own output and SM does not redact it</b> - the endpoint is {@code logs.read}, and that is the control.
 *
 * <ul>
 *   <li>The instance is looked up through {@link DeclareOrchestrator#get}, so it is visible exactly when
 *       {@code getInstance} shows it; anything else is the same {@code 404} as out of scope.</li>
 *   <li>{@code component} must be a component type the instance's declared package declares, else {@code 404}; a package
 *       that cannot be read is the same {@code 404} (nothing to validate against), never a {@code 500}.</li>
 *   <li>{@code placementId} is required when the instance has more than one live placement ({@code 400}); one that is
 *       not this instance's is {@code 404}. An instance with no live placement answers a declared
 *       {@code unavailable} page.</li>
 *   <li>The organization is {@code Session.organizationId()} on the data-plane call, never a request field (I5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dp")
public class LogsController {

    /** The contract's {@code limit} default. */
    static final int DEFAULT_LIMIT = 200;

    static final String NO_PLACEMENT = "the instance has no live placement to read logs from";

    private final DeclareOrchestrator instances;
    private final SolutionCatalog catalog;
    private final DesignerCodec codec;
    private final LogService logs;

    public LogsController(DeclareOrchestrator instances, SolutionCatalog catalog, DesignerCodec codec,
            LogService logs) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logs = Objects.requireNonNull(logs, "logs");
    }

    @GetMapping("/instances/{instanceId}/logs/{component}")
    @ConsoleOperation(value = "readLogs", resource = InstanceResolver.class)
    public ResponseEntity<LogPage> readLogs(@PathVariable UUID instanceId, @PathVariable String component,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) UUID placementId,
            @RequestParam(required = false) String before, @RequestParam(required = false) String after,
            Session session) {
        UUID organization = session.organizationId();
        InstanceView view = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        requireDeclaredComponent(organization, view, component);

        int bound = limit == null ? DEFAULT_LIMIT : limit;
        if (bound < 1 || bound > LogService.MAX_LIMIT) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        Instant notBefore = instantOf(after);
        Instant notAfter = instantOf(before);
        if (notBefore != null && notAfter != null && !notBefore.isBefore(notAfter)) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }

        if (placementId == null && view.placements().isEmpty()) {
            return ResponseEntity.ok(new LogPage(
                    new Availability(AvailabilityState.UNAVAILABLE, null, NO_PLACEMENT, null), List.of()));
        }
        UUID placement = placementFor(view, placementId);
        // an agent placement is served by the data plane's router through the agent's collected log (plan 016); the
        // log-LEVEL operations still refuse it (plan 015 T3.3)
        LogPageView page = logs.read(organization, instanceId, placement, bound, notBefore, notAfter);
        return ResponseEntity.ok(new LogPage(
                page.unavailableReason().map(r -> new Availability(AvailabilityState.UNAVAILABLE, null, r, null))
                        .orElse(null),
                page.lines().stream()
                        .map(l -> new LogPage.Line(l.at().toString(), l.level().orElse(null), l.message()))
                        .toList()));
    }

    /** {@code 404} unless the instance's declared package declares a component of exactly this type. */
    private void requireDeclaredComponent(UUID organization, InstanceView view, String component) {
        boolean declared = catalog.documentOf(organization, view.catalogSolutionId(), view.declaredPackageVersion())
                .map(this::componentTypes)
                .map(types -> types.contains(component))
                .orElse(false);
        if (!declared) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
    }

    private List<String> componentTypes(String document) {
        try {
            return codec.readPackage(document).components().stream().map(PackageModel.PackageComponent::type).toList();
        } catch (IllegalArgumentException unreadable) {
            return List.of();
        }
    }

    private static UUID placementFor(InstanceView view, UUID requested) {
        if (requested != null) {
            if (view.placements().stream().noneMatch(p -> p.placementId().equals(requested))) {
                throw new ResourceNotFoundException(ResourceKind.INSTANCE);
            }
            return requested;
        }
        if (view.placements().size() > 1) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return view.placements().get(0).placementId();
    }

    /** An RFC 3339 date-time with an offset (the contract's {@code date-time}); anything else is {@code 400}. */
    private static Instant instantOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
    }
}
