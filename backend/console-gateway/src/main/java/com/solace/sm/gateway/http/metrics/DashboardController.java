package com.solace.sm.gateway.http.metrics;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.dashboard.DashboardStore;
import com.solace.sm.cp.api.dashboard.DashboardView;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.api.dashboard.WidgetSubmission;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.DeclareOrchestrator;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayRejectionException;
import com.solace.sm.gateway.http.RequestOriginFactory;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-instance shared dashboard (rows 62-66; plan 011 P11-7.2 over P11-7.1's {@link DashboardStore}).
 *
 * <p>A dashboard IS an instance's ({@code db_} plus the instance id), so every route is an {@code instance}-scope CATALOG
 * operation: {@link DashboardResourceResolver} reads the instance from the path, and {@code dashboard.view},
 * {@code .edit} or {@code .reset} has passed in {@code AuthenticationInterceptor} before this class runs. That also answers
 * existence in the control plane's directory: an unknown or another organization's instance is {@code 404} BEFORE
 * {@link DashboardStore} is called, which matters because the store returns a fresh view for ANY id.
 *
 * <p>A dashboard exists only for an instance the DATA PLANE reports active: a pending or abandoned one (an {@code OR-90}
 * orphan is one) is the same {@code 404}, and nothing is written. The control plane cannot ask the data plane ({@code I7}),
 * so this class does, through {@link DeclareOrchestrator#get} (the check {@code getInstance} makes).
 *
 * <p>Every write must present {@code X-SM-Dashboard-Revision} (W2-07): missing or malformed is {@code 400}, stale is
 * {@code 409}. {@code I5}: the organization is {@code Session.organizationId()}, the instance is the path's.
 */
@RestController
@RequestMapping("/api/console/v1/dashboards")
public class DashboardController {

    private static final String REVISION_HEADER = "X-SM-Dashboard-Revision";
    private static final Pattern REVISION = Pattern.compile("[0-9]{1,18}");
    /** Strict: a duplicate key or trailing content in the layout body is a refusal, never a silent last-wins. */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final DeclareOrchestrator instances;
    private final MonitoringInstances monitoring;
    private final MetricsQueryService query;
    private final DashboardStore store;
    private final RequestOriginFactory originFactory;

    public DashboardController(DeclareOrchestrator instances, MonitoringInstances monitoring, MetricsQueryService query,
            DashboardStore store, RequestOriginFactory originFactory) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.query = Objects.requireNonNull(query, "query");
        this.store = Objects.requireNonNull(store, "store");
        this.originFactory = Objects.requireNonNull(originFactory, "originFactory");
    }

    @GetMapping("/{dashboardId}")
    @ConsoleOperation(value = "getDashboard", resource = DashboardResourceResolver.class)
    public ResponseEntity<ObjectNode> getDashboard(@PathVariable String dashboardId, Session session) {
        Target target = target(dashboardId, session);
        return ResponseEntity.ok(render(store.get(target.tenant(), target.instanceId(), target.input())));
    }

    @PostMapping(value = "/{dashboardId}/widgets", consumes = "application/json")
    @ConsoleOperation(value = "saveWidget", resource = DashboardResourceResolver.class)
    public ResponseEntity<JsonNode> saveWidget(@PathVariable String dashboardId,
            @RequestHeader(value = REVISION_HEADER, required = false) String revision, @RequestBody String body,
            Session session) {
        long expected = revision(revision);
        Target target = target(dashboardId, session);
        DashboardView saved = store.saveWidget(session.principal(), originFactory.create(), target.tenant(),
                target.instanceId(), expected, new WidgetSubmission(body), target.input());
        ObjectNode document = render(saved);
        for (JsonNode widget : document.path("widgets")) {
            if (widget.path("id").asText().equals(saved.savedWidgetId())) {
                return ResponseEntity.ok(widget);
            }
        }
        throw new IllegalStateException("a saved widget is not in the dashboard it was saved to");
    }

    @PutMapping(value = "/{dashboardId}/layout", consumes = "application/json")
    @ConsoleOperation(value = "setDashboardLayout", resource = DashboardResourceResolver.class)
    public ResponseEntity<Void> setDashboardLayout(@PathVariable String dashboardId,
            @RequestHeader(value = REVISION_HEADER, required = false) String revision, @RequestBody String body,
            Session session) {
        long expected = revision(revision);
        String layout = layoutOf(body);
        Target target = target(dashboardId, session);
        store.setLayout(session.principal(), originFactory.create(), target.tenant(), target.instanceId(), expected,
                layout, target.input());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PatchMapping("/{dashboardId}/widgets/{widgetId}")
    @ConsoleOperation(value = "setWidgetHidden", resource = DashboardResourceResolver.class)
    public ResponseEntity<Void> setWidgetHidden(@PathVariable String dashboardId, @PathVariable String widgetId,
            @RequestHeader(value = REVISION_HEADER, required = false) String revision,
            @RequestBody HiddenRequest body, Session session) {
        long expected = revision(revision);
        if (!WIDGET_ID.matcher(widgetId).matches()) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        Target target = target(dashboardId, session);
        // No translation here (W4-08): the service says UNKNOWN_WIDGET for a shared widget, so every INVALID that
        // reaches the advice is the contract's 400.
        store.setHidden(session.principal(), originFactory.create(), target.tenant(), target.instanceId(), expected,
                widgetId, body.hidden(), target.input());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/{dashboardId}/reset")
    @ConsoleOperation(value = "resetDashboard", resource = DashboardResourceResolver.class)
    public ResponseEntity<ObjectNode> resetDashboard(@PathVariable String dashboardId,
            @RequestHeader(value = REVISION_HEADER, required = false) String revision, Session session) {
        long expected = revision(revision);
        Target target = target(dashboardId, session);
        return ResponseEntity.ok(render(store.reset(session.principal(), originFactory.create(), target.tenant(),
                target.instanceId(), expected, target.input())));
    }

    // ---- the one instance this dashboard is, and what its default layer is derived from -----------------------------

    private static final Pattern WIDGET_ID = Pattern.compile("[dw]_[0-9a-f]{12}");

    private record Target(UUID instanceId, TenantId tenant, DefaultLayerInput input) {
    }

    /**
     * The instance, which the data plane must report ACTIVE (else {@code 404}, nothing written), and the two inputs the
     * control plane's default layer needs from outside it: the package version's declared metrics (the control plane's
     * own document) and the metrics currently ACTIVE for the instance (the data plane's, from its newest
     * restart-observed generation).
     */
    private Target target(String dashboardId, Session session) {
        UUID instanceId = DashboardResourceResolver.instanceOf(dashboardId);
        UUID organization = session.organizationId();
        InstanceView instance = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        DefaultLayerInput input = monitoring.packages(organization)
                .dashboardInput(instance, query.activeMetrics(organization, instanceId));
        return new Target(instanceId, new TenantId(organization), input);
    }

    /** The revision the caller read: required (W2-07), a non-negative integer. */
    private static long revision(String header) {
        if (header == null || !REVISION.matcher(header).matches()) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return Long.parseLong(header);
    }

    /** {@code {"layout":[...]}} and nothing else: the array's text, which the store validates cell by cell. */
    private static String layoutOf(String body) {
        try {
            JsonNode root = STRICT.readTree(body);
            if (root != null && root.isObject() && root.size() == 1 && root.path("layout").isArray()) {
                return root.get("layout").toString();
            }
        } catch (JsonProcessingException notJson) {
            // falls through: a malformed body is the contract's 400
        }
        throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
    }

    /** The contract's {@code Dashboard}: the store's document with the {@code revision} the caller must present next. */
    private static ObjectNode render(DashboardView view) {
        try {
            JsonNode document = STRICT.readTree(view.documentJson());
            ObjectNode out = STRICT.createObjectNode();
            out.set("id", document.get("id"));
            out.set("schemaVersion", document.get("schemaVersion"));
            out.put("revision", view.revision());
            document.fields().forEachRemaining(field -> {
                if (!out.has(field.getKey())) {
                    out.set(field.getKey(), field.getValue());
                }
            });
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the dashboard store returned a document that is not JSON", e);
        }
    }
}
