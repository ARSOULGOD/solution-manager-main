package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.configview.ConfigViewResult;
import com.solace.sm.dp.api.configview.ConfigViewService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.gateway.http.metrics.MetricsRejectionException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code viewConfig} (plan 017 task 6): the allow-listed keys of one agent placement's configuration file. An
 * {@code instance}-scope CATALOG operation authorized by the existing {@code config.view} at the path's instance
 * ({@link InstanceResolver}) through the one authorization path before this handler runs; no check lives here and no verb
 * is added.
 *
 * <ul>
 *   <li>The instance is looked up through {@link DeclareOrchestrator#get}, so it is visible exactly when {@code getInstance}
 *       shows it, and the placement must be one of its live placements; anything else is the shared {@code 404}. The data
 *       plane then re-resolves the placement under the organization AND the path's instance, so a placement of another
 *       instance is never read even if this check were wrong.</li>
 *   <li>The organization is {@code Session.organizationId()} (I5), never a request field.</li>
 *   <li>A missing file is the same {@code 404} body as an unknown placement: nothing tells a caller the placement exists.</li>
 *   <li>Every other refusal is its own status ({@link #refused}); a {@code detail}, where there is one, is the data plane's
 *       FIXED reason text. No body names a file path, host, container, key outside the entries, or the allow-list (I6).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dp")
public class ConfigViewController {

    /** The {@code Retry-After} on a busy view: the same 5 seconds as a busy log export. */
    static final int BUSY_RETRY_AFTER_SECONDS = 5;

    private final DeclareOrchestrator instances;
    private final ConfigViewService configView;

    public ConfigViewController(DeclareOrchestrator instances, ConfigViewService configView) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.configView = Objects.requireNonNull(configView, "configView");
    }

    @GetMapping("/instances/{instanceId}/placements/{placementId}/config")
    @ConsoleOperation(value = "viewConfig", resource = InstanceResolver.class)
    public ResponseEntity<ConfigView> viewConfig(@PathVariable UUID instanceId, @PathVariable UUID placementId,
            Session session) {
        UUID organization = session.organizationId();
        InstanceView view = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        if (view.placements().stream().noneMatch(p -> p.placementId().equals(placementId))) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        ConfigViewResult result = configView.view(organization, instanceId, placementId);
        if (result instanceof ConfigViewResult.Refused refusal) {
            throw refused(refusal.reason());
        }
        ConfigViewResult.Ok ok = (ConfigViewResult.Ok) result;
        return ResponseEntity.ok(new ConfigView(
                ok.entries().stream().map(e -> new ConfigView.Entry(e.path(), e.value())).toList(),
                ok.hiddenCount(), ok.truncated()));
    }

    /** A data-plane refusal as the status it deserves (spec section 6). */
    static RuntimeException refused(ConfigViewResult.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> new ResourceNotFoundException(ResourceKind.INSTANCE);
            case NOT_AN_AGENT_PLACEMENT -> new GatewayRejectionException(ErrorCode.AGENT_OPERATION_UNSUPPORTED);
            case BUSY -> new MetricsRejectionException(ErrorCode.RATE_LIMITED,
                    MetricsRejectionException.CONFIG_VIEW_CONCURRENCY, BUSY_RETRY_AFTER_SECONDS);
            case TOO_LARGE -> new ConfigViewRefusedException(ErrorCode.CONFIG_TOO_LARGE, reason.message());
            case INVALID_YAML -> new ConfigViewRefusedException(ErrorCode.CONFIG_INVALID_YAML, reason.message());
            case AGENT_UNAVAILABLE -> new ConfigViewRefusedException(ErrorCode.SERVICE_UNAVAILABLE, reason.message());
            case BAD_GATEWAY -> new ConfigViewRefusedException(ErrorCode.AGENT_REPLY_REJECTED, reason.message());
        };
    }
}
