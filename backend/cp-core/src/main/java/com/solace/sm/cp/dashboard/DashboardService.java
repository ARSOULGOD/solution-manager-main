package com.solace.sm.cp.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.dashboard.DashboardStore;
import com.solace.sm.cp.api.dashboard.DashboardView;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException.Kind;
import com.solace.sm.cp.api.dashboard.WidgetSubmission;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The per-instance shared dashboard (plan 011 P11-7.1; M-Q10, M-Q23; PROPOSED {@code SM-D31}).
 *
 * <h2>What is stored, and what is derived</h2>
 * Only the {@code shared} layer is stored: the operator's widgets, the layout that places every widget, and the
 * ids of product-default widgets the operator <i>hid</i>. The {@code product-default} layer is derived on every
 * read by {@link DefaultLayerDeriver} from the instance's package version and the metrics currently active, so a
 * package upgrade replaces it wholesale and an operator's choices survive it. A default widget is hidden, never
 * deleted.
 *
 * <h2>Writes</h2>
 * Each is one transaction: the audit context ({@code OR-25}, one {@code DASHBOARD_*} operation), the revision
 * check, the validation, the write. The revision is checked <i>first</i>, so a stale caller is told {@code 409}
 * whatever else is wrong with its request, and again by the {@code UPDATE ... WHERE revision = :expected}, which is
 * the check that holds under concurrency.
 *
 * <h2>Reset (OQ-8 (a))</h2>
 * Clears the hide overrides and the default widgets' placements; keeps every shared widget and its placement.
 */
public final class DashboardService implements DashboardStore {

    /** Compact bytes. The database limit is on the re-spelled jsonb text, which is a little larger. */
    static final int MAX_DOCUMENT_CHARS = 48 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DashboardRepository dashboards;
    private final DashboardDocumentValidator validator;
    private final DefaultLayerDeriver defaults;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public DashboardService(DashboardRepository dashboards, DashboardDocumentValidator validator,
            DefaultLayerDeriver defaults, AuditContext auditContext, TransactionOperations transactions,
            Clock clock) {
        this.dashboards = Objects.requireNonNull(dashboards, "dashboards");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DashboardView get(TenantId organization, UUID instanceId, DefaultLayerInput input) {
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(input, "input");
        Optional<DashboardRepository.Stored> stored = dashboards.find(organization.value(), instanceId);
        return view(instanceId, input, stored.map(DashboardService::parse).orElseGet(DashboardService::fresh),
                stored.map(DashboardRepository.Stored::revision).orElse(0L), null);
    }

    @Override
    public DashboardView saveWidget(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, WidgetSubmission widget, DefaultLayerInput input) {
        Objects.requireNonNull(widget, "widget");
        return write(actor, origin, organization, instanceId, expectedRevision, input,
                AuditOperations.DASHBOARD_WIDGET_SAVED, stored -> {
                    ObjectNode shared = validator.shared(widget.json(), instanceId, input);
                    String widgetId = "w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    shared.put("id", widgetId);
                    // The default placements are persisted with the first shared widget: from here the layout is the
                    // operator's, and a default appearing later must not push the widgets already arranged.
                    ArrayNode layout = effectiveLayout(stored, effectiveWidgets(instanceId, input, stored));
                    int nextRow = 0;
                    int nextOrder = 0;
                    for (JsonNode placement : layout) {
                        nextRow = Math.max(nextRow, placement.path("y").asInt() + placement.path("h").asInt());
                        nextOrder = Math.max(nextOrder, placement.path("order").asInt() + 1);
                    }
                    ObjectNode placement = layout.addObject();
                    placement.put("widgetId", widgetId);
                    placement.put("x", 0);
                    placement.put("y", nextRow);
                    placement.put("w", 6);
                    placement.put("h", 2);
                    placement.put("order", nextOrder);
                    stored.withArray("widgets").add(shared);
                    stored.set("layout", layout);
                    return widgetId;
                });
    }

    @Override
    public DashboardView setLayout(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, String layoutJson, DefaultLayerInput input) {
        Objects.requireNonNull(layoutJson, "layoutJson");
        return write(actor, origin, organization, instanceId, expectedRevision, input,
                AuditOperations.DASHBOARD_LAYOUT_SET, stored -> {
                    Set<String> ids = new LinkedHashSet<>();
                    effectiveWidgets(instanceId, input, stored).forEach(widget -> ids.add(idOf(widget)));
                    stored.set("layout", validator.layout(layoutJson, ids));
                    return null;
                });
    }

    @Override
    public DashboardView setHidden(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, String widgetId, boolean hidden, DefaultLayerInput input) {
        Objects.requireNonNull(widgetId, "widgetId");
        return write(actor, origin, organization, instanceId, expectedRevision, input,
                AuditOperations.DASHBOARD_WIDGET_HIDDEN, stored -> {
                    Set<String> defaultIds = new HashSet<>();
                    defaults.derive(instanceId, input).forEach(widget -> defaultIds.add(idOf(widget)));
                    if (!defaultIds.contains(widgetId)) {
                        boolean shared = false;
                        for (JsonNode widget : stored.withArray("widgets")) {
                            shared |= widgetId.equals(idOf(widget));
                        }
                        // W4-08: a shared widget is, for hiding, unknown (the contract's 404). Saying so HERE, as the
                        // one kind, is what lets the gateway translate nothing: every INVALID stays a 400.
                        throw new InvalidDashboardException(Kind.UNKNOWN_WIDGET, shared
                                ? "Only product-default widgets can be hidden." : "No such widget on this dashboard.");
                    }
                    Set<String> hiddenIds = new LinkedHashSet<>();
                    stored.withArray("hiddenDefaults").forEach(id -> hiddenIds.add(id.asText()));
                    if (hidden) {
                        hiddenIds.add(widgetId);
                    } else {
                        hiddenIds.remove(widgetId);
                    }
                    ArrayNode rewritten = JSON.createArrayNode();
                    hiddenIds.forEach(rewritten::add);
                    stored.set("hiddenDefaults", rewritten);
                    return null;
                });
    }

    @Override
    public DashboardView reset(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, DefaultLayerInput input) {
        return write(actor, origin, organization, instanceId, expectedRevision, input,
                AuditOperations.DASHBOARD_RESET, stored -> {
                    Set<String> sharedIds = new HashSet<>();
                    stored.withArray("widgets").forEach(widget -> sharedIds.add(idOf(widget)));
                    ArrayNode kept = JSON.createArrayNode();
                    for (JsonNode placement : stored.withArray("layout")) {
                        if (sharedIds.contains(placement.path("widgetId").asText())) {
                            kept.add(placement);
                        }
                    }
                    stored.set("layout", kept);
                    stored.set("hiddenDefaults", JSON.createArrayNode());
                    return null;
                });
    }

    // ---------------------------------------------------------------- the write path

    /** What a write does to the stored document; returns the id of a widget it stored, or {@code null}. */
    @FunctionalInterface
    private interface Mutation {
        String apply(ObjectNode stored);
    }

    private DashboardView write(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID instanceId,
            long expectedRevision, DefaultLayerInput input, String auditOperation, Mutation mutation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(input, "input");
        if (expectedRevision < 0) {
            throw new InvalidDashboardException(Kind.INVALID, "The revision is not valid.");
        }
        return transactions.execute(status -> {
            auditContext.set(actor.id(), auditOperation, origin.correlationId(), origin.clientAddress());
            Optional<DashboardRepository.Stored> current = dashboards.find(organization.value(), instanceId);
            long currentRevision = current.map(DashboardRepository.Stored::revision).orElse(0L);
            if (expectedRevision != currentRevision) {
                throw stale();
            }
            ObjectNode stored = current.map(DashboardService::parse).orElseGet(DashboardService::fresh);
            String savedWidgetId = mutation.apply(stored);
            String document = stored.toString();
            if (document.length() > MAX_DOCUMENT_CHARS) {
                throw new InvalidDashboardException(Kind.INVALID, "The dashboard document is too large.");
            }
            boolean written = currentRevision == 0
                    ? dashboards.insert(organization.value(), instanceId, document, actor.id(), clock.instant())
                    : dashboards.update(organization.value(), instanceId, currentRevision, document, actor.id(),
                            clock.instant());
            if (!written) {
                throw stale();
            }
            return view(instanceId, input, stored, currentRevision + 1, savedWidgetId);
        });
    }

    private static InvalidDashboardException stale() {
        return new InvalidDashboardException(Kind.STALE_REVISION, "The dashboard changed since it was read.");
    }

    // ---------------------------------------------------------------- the stored document

    private static ObjectNode fresh() {
        ObjectNode stored = JSON.createObjectNode();
        stored.put("schemaVersion", 1);
        stored.putArray("widgets");
        stored.putArray("layout");
        stored.putArray("hiddenDefaults");
        stored.putObject("range").put("preset", "24h");
        stored.putObject("refresh").putNull("intervalSeconds");
        return stored;
    }

    private static ObjectNode parse(DashboardRepository.Stored stored) {
        try {
            if (JSON.readTree(stored.documentJson()) instanceof ObjectNode document
                    && document.path("schemaVersion").asInt() == 1) {
                return document;
            }
        } catch (JsonProcessingException unreadable) {
            // falls through: the stored text is ours, so this is a server fault, not the caller's
        }
        throw new IllegalStateException("a stored dashboard document is not a version 1 object");
    }

    // ---------------------------------------------------------------- the rendered document

    private DashboardView view(UUID instanceId, DefaultLayerInput input, ObjectNode stored, long revision,
            String savedWidgetId) {
        List<ObjectNode> widgets = effectiveWidgets(instanceId, input, stored);
        ObjectNode document = JSON.createObjectNode();
        document.put("id", "db_" + instanceId);
        document.put("schemaVersion", 1);
        ArrayNode rendered = document.putArray("widgets");
        widgets.forEach(rendered::add);
        document.set("layout", effectiveLayout(stored, widgets));
        document.set("range", stored.path("range").deepCopy());
        document.set("refresh", stored.path("refresh").deepCopy());
        return new DashboardView("db_" + instanceId, instanceId, revision, document.toString(), savedWidgetId);
    }

    /** The derived default widgets (with their hide flag), then the stored shared widgets. */
    private List<ObjectNode> effectiveWidgets(UUID instanceId, DefaultLayerInput input, ObjectNode stored) {
        Set<String> hidden = new HashSet<>();
        stored.withArray("hiddenDefaults").forEach(id -> hidden.add(id.asText()));
        List<ObjectNode> widgets = new ArrayList<>();
        for (ObjectNode widget : defaults.derive(instanceId, input)) {
            widget.put("hidden", hidden.contains(idOf(widget)));
            widgets.add(widget);
        }
        for (JsonNode shared : stored.withArray("widgets")) {
            widgets.add(((ObjectNode) shared).deepCopy());
        }
        return widgets;
    }

    /**
     * The stored placements of widgets that still exist, plus an automatic placement for each widget that has none
     * (two to a row, below everything placed). Ordered by {@code order}. A placement for a widget that no longer
     * exists (its metric went inactive) is dropped from the result and comes back if the widget does.
     */
    private static ArrayNode effectiveLayout(ObjectNode stored, List<ObjectNode> widgets) {
        Map<String, JsonNode> placed = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        widgets.forEach(widget -> ids.add(idOf(widget)));
        for (JsonNode placement : stored.withArray("layout")) {
            String widgetId = placement.path("widgetId").asText();
            if (ids.contains(widgetId)) {
                placed.putIfAbsent(widgetId, placement);
            }
        }
        int nextRow = 0;
        int nextOrder = 0;
        for (JsonNode placement : placed.values()) {
            nextRow = Math.max(nextRow, placement.path("y").asInt() + placement.path("h").asInt());
            nextOrder = Math.max(nextOrder, placement.path("order").asInt() + 1);
        }
        List<JsonNode> all = new ArrayList<>(placed.values());
        int unplaced = 0;
        for (String id : ids) {
            if (!placed.containsKey(id)) {
                ObjectNode placement = JSON.createObjectNode();
                placement.put("widgetId", id);
                placement.put("x", (unplaced % 2) * 6);
                placement.put("y", nextRow + (unplaced / 2) * 2);
                placement.put("w", 6);
                placement.put("h", 2);
                placement.put("order", nextOrder + unplaced);
                all.add(placement);
                unplaced++;
            }
        }
        all.sort(Comparator.comparingInt((JsonNode placement) -> placement.path("order").asInt()));
        ArrayNode layout = JSON.createArrayNode();
        all.forEach(placement -> layout.add(placement.deepCopy()));
        return layout;
    }

    private static String idOf(JsonNode widget) {
        return widget.path("id").asText();
    }
}
