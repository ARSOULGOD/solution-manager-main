package com.solace.sm.cp.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.dashboard.DashboardView;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException.Kind;
import com.solace.sm.cp.api.dashboard.WidgetSubmission;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcDashboardRepository;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DashboardService} over the real {@code V014} schema, as {@code sm_cp_runtime}: the derived default layer,
 * hide-not-delete, reset, the optimistic revision, and that every write is audited as its own operation.
 */
class DashboardServiceIT {

    private static final String KEY = "dashboard-service-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final JdbcDashboardRepository repository = new JdbcDashboardRepository(runtime);
    private final DashboardService service = new DashboardService(repository, new DashboardDocumentValidator(),
            new DefaultLayerDeriver(),
            new JdbcAuditContext(runtime),
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource())),
            Clock.systemUTC());
    private final DefaultLayerInput input = DashboardFixtures.input();

    // ---------------------------------------------------------------- the derived default layer

    @Test
    void theDefaultLayerIsDerivedFromTheActiveDeclaredMetricsAndNeverStored() {
        Tenant t = tenant();

        DashboardView view = service.get(t.id(), t.instance, input);

        assertThat(view.revision()).as("nothing is stored yet").isZero();
        assertThat(view.dashboardId()).isEqualTo("db_" + t.instance);
        assertThat(metrics(view)).containsExactly("m.requests", "m.depth");
        assertThat(widgets(view)).allSatisfy(w -> assertThat(w.path("hidden").asBoolean()).isFalse());
        assertThat(layout(view)).as("every default widget is placed").hasSize(2);
        assertThat(count("SELECT count(*) FROM sm_control.dashboard WHERE instance_id = ?", t.instance))
                .as("reading a dashboard writes nothing").isZero();
    }

    @Test
    void aDefaultChartFalseMetricHasNoDefaultWidgetEvenWhenActive() {
        Tenant t = tenant();

        assertThat(metrics(service.get(t.id(), t.instance, input))).doesNotContain("m.quiet", "m.inactive",
                "m.latency");
    }

    // ---------------------------------------------------------------- shared widgets

    @Test
    void aSharedWidgetIsStoredWithAServerIdAndAPlacementBelowTheDefaults() throws IOException {
        Tenant t = tenant();

        DashboardView saved = service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);

        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.savedWidgetId()).startsWith("w_").isNotEqualTo("w_client_value");
        JsonNode widget = widgetById(saved, saved.savedWidgetId());
        assertThat(widget.path("layer").asText()).isEqualTo("shared");
        assertThat(widget.path("title").asText()).isEqualTo("m.depth over time");
        assertThat(widget.has("hidden")).isFalse();
        JsonNode placement = placementOf(saved, saved.savedWidgetId());
        JsonNode lowestDefault = layout(saved).stream().filter(p -> !p.path("widgetId").asText()
                .equals(saved.savedWidgetId())).reduce((a, b) -> a.path("y").asInt() >= b.path("y").asInt() ? a : b)
                .orElseThrow();
        assertThat(placement.path("y").asInt()).as("below every default placement")
                .isGreaterThanOrEqualTo(lowestDefault.path("y").asInt() + lowestDefault.path("h").asInt());
        assertThat(document(service.get(t.id(), t.instance, input)))
                .as("what a read returns is what the write returned (jsonb re-orders keys, not content)")
                .isEqualTo(document(saved));
    }

    @Test
    void aSharedWidgetOutsideThePackageVersionIsRefusedAndNothingIsWritten() {
        Tenant t = tenant();

        assertThatThrownBy(() -> service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.from_another_version")), input))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));

        assertThat(count("SELECT count(*) FROM sm_control.dashboard WHERE instance_id = ?", t.instance)).isZero();
    }

    @Test
    void aDocumentOverTheServiceLimitIsRefusedAsInvalid() {
        Tenant t = tenant();
        String bigOptions = "{\"pad\":\"" + "x".repeat(30_000) + "\"}";
        String big = DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "[\"" + t.instance + "\"]", "m.depth",
                "big", bigOptions);
        DashboardView first = service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(big), input);

        assertThatThrownBy(() -> service.saveWidget(t.actor(), origin(), t.id(), t.instance, first.revision(),
                new WidgetSubmission(big), input))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
        assertThat(service.get(t.id(), t.instance, input).revision()).as("the refused write changed nothing")
                .isEqualTo(first.revision());
    }

    // ---------------------------------------------------------------- hide, never delete

    @Test
    void aDefaultWidgetCanBeHiddenNotDeleted() {
        Tenant t = tenant();
        String defaultId = DefaultLayerDeriver.widgetId("m.requests");

        DashboardView hidden = service.setHidden(t.actor(), origin(), t.id(), t.instance, 0, defaultId, true, input);

        assertThat(widgetById(hidden, defaultId).path("hidden").asBoolean()).isTrue();
        assertThat(widgets(hidden)).as("hiding deletes nothing").hasSize(2);
        assertThat(layout(hidden)).as("a hidden widget keeps its place").hasSize(2);
        DashboardView shown = service.setHidden(t.actor(), origin(), t.id(), t.instance, hidden.revision(),
                defaultId, false, input);
        assertThat(widgetById(shown, defaultId).path("hidden").asBoolean()).isFalse();
    }

    @Test
    void aSharedWidgetCannotBeHiddenAndAnUnknownOneIsNotFound() {
        Tenant t = tenant();
        DashboardView saved = service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);

        assertThatThrownBy(() -> service.setHidden(t.actor(), origin(), t.id(), t.instance, saved.revision(),
                saved.savedWidgetId(), true, input))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNKNOWN_WIDGET)); // W4-08: the contract's 404, said by the service
        assertThatThrownBy(() -> service.setHidden(t.actor(), origin(), t.id(), t.instance, saved.revision(),
                "d_000000000000", true, input))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.UNKNOWN_WIDGET));
    }

    @Test
    void aHideChoiceSurvivesAnInactiveInterludeBecauseItIsStoredByIdNotByPosition() {
        Tenant t = tenant();
        String defaultId = DefaultLayerDeriver.widgetId("m.requests");
        DashboardView hidden = service.setHidden(t.actor(), origin(), t.id(), t.instance, 0, defaultId, true, input);
        DefaultLayerInput nothingActive = new DefaultLayerInput(input.entityType(), input.declared(), Set.of());

        assertThat(widgets(service.get(t.id(), t.instance, nothingActive))).isEmpty();
        assertThat(widgetById(service.get(t.id(), t.instance, input), defaultId).path("hidden").asBoolean())
                .as("the override applied again once the metric returned").isTrue();
        assertThat(hidden.revision()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- layout

    @Test
    void aLayoutMustPlaceEveryWidgetAndIsPersistedWithTheDefaultPlacements() {
        Tenant t = tenant();
        DashboardView before = service.get(t.id(), t.instance, input);
        List<String> ids = widgets(before).stream().map(w -> w.path("id").asText()).toList();
        String reversed = "[{\"widgetId\":\"" + ids.get(0) + "\",\"x\":6,\"y\":0,\"w\":6,\"h\":3,\"order\":1},"
                + "{\"widgetId\":\"" + ids.get(1) + "\",\"x\":0,\"y\":0,\"w\":6,\"h\":3,\"order\":0}]";

        DashboardView set = service.setLayout(t.actor(), origin(), t.id(), t.instance, 0, reversed, input);

        assertThat(placementOf(set, ids.get(0)).path("x").asInt()).isEqualTo(6);
        assertThat(placementOf(set, ids.get(0)).path("h").asInt()).isEqualTo(3);
        assertThatThrownBy(() -> service.setLayout(t.actor(), origin(), t.id(), t.instance, set.revision(),
                "[{\"widgetId\":\"" + ids.get(0) + "\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0}]", input))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
    }

    // ---------------------------------------------------------------- reset (OQ-8 (a))

    @Test
    void resetKeepsSharedWidgets() {
        Tenant t = tenant();
        String defaultId = DefaultLayerDeriver.widgetId("m.requests");
        DashboardView saved = service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);
        DashboardView hidden = service.setHidden(t.actor(), origin(), t.id(), t.instance, saved.revision(),
                defaultId, true, input);
        List<String> ids = widgets(hidden).stream().map(w -> w.path("id").asText()).toList();
        StringBuilder moved = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            moved.append(i == 0 ? "" : ",").append("{\"widgetId\":\"").append(ids.get(i))
                    .append("\",\"x\":0,\"y\":").append(i * 4).append(",\"w\":12,\"h\":4,\"order\":").append(i)
                    .append("}");
        }
        DashboardView arranged = service.setLayout(t.actor(), origin(), t.id(), t.instance, hidden.revision(),
                moved.append("]").toString(), input);

        DashboardView reset = service.reset(t.actor(), origin(), t.id(), t.instance, arranged.revision(), input);

        assertThat(widgetById(reset, saved.savedWidgetId()).path("layer").asText())
                .as("the shared widget is kept").isEqualTo("shared");
        assertThat(placementOf(reset, saved.savedWidgetId()).path("w").asInt())
                .as("and so is its placement").isEqualTo(12);
        assertThat(widgetById(reset, defaultId).path("hidden").asBoolean()).as("the hide override is cleared")
                .isFalse();
        JsonNode defaultPlacement = placementOf(reset, defaultId);
        assertThat(defaultPlacement.path("w").asInt()).as("the default placement is back to the automatic one")
                .isEqualTo(6);
        assertThat(defaultPlacement.path("y").asInt()).as("below the kept shared widget")
                .isGreaterThanOrEqualTo(placementOf(reset, saved.savedWidgetId()).path("y").asInt());
    }

    // ---------------------------------------------------------------- the optimistic revision

    @Test
    void aStaleRevisionIs409() {
        Tenant t = tenant();
        DashboardView first = service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);
        DashboardView second = service.saveWidget(t.actor(), origin(), t.id(), t.instance, first.revision(),
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.requests")), input);
        assertThat(second.revision()).isEqualTo(2);

        // The caller read revision 1; revision 2 is stored. Every write kind is refused, and writes nothing.
        List<Runnable> staleWrites = List.of(
                () -> service.saveWidget(t.actor(), origin(), t.id(), t.instance, first.revision(),
                        new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input),
                () -> service.setLayout(t.actor(), origin(), t.id(), t.instance, first.revision(), "[]", input),
                () -> service.setHidden(t.actor(), origin(), t.id(), t.instance, first.revision(),
                        DefaultLayerDeriver.widgetId("m.requests"), true, input),
                () -> service.reset(t.actor(), origin(), t.id(), t.instance, first.revision(), input),
                // a first write presented as if nothing were stored
                () -> service.reset(t.actor(), origin(), t.id(), t.instance, 0, input));
        for (Runnable write : staleWrites) {
            assertThatThrownBy(write::run).isInstanceOfSatisfying(InvalidDashboardException.class,
                    e -> assertThat(e.kind()).isEqualTo(Kind.STALE_REVISION));
        }
        assertThat(document(service.get(t.id(), t.instance, input))).isEqualTo(document(second));
    }

    @Test
    void theRepositoryWritesOnlyAtTheRevisionItIsGiven() {
        Tenant t = tenant();
        service.saveWidget(t.actor(), origin(), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        RequestOrigin origin = origin();

        // The service compared revisions before; this is the guard that holds when two writers raced past that.
        Boolean raced = tx.execute(status -> {
            new JdbcAuditContext(runtime).set(t.actor().id(), AuditOperations.DASHBOARD_RESET,
                    origin.correlationId(), origin.clientAddress());
            return repository.update(t.organization, t.instance, 7, "{\"schemaVersion\":1}", t.actor().id(),
                    java.time.Instant.now());
        });

        assertThat(raced).isFalse();
        assertThat(repository.find(t.organization, t.instance)).hasValueSatisfying(
                stored -> assertThat(stored.revision()).isEqualTo(1));
    }

    // ---------------------------------------------------------------- audit

    @Test
    void everyWriteIsAuditedAsItsOperation() {
        Tenant t = tenant();
        String defaultId = DefaultLayerDeriver.widgetId("m.requests");
        List<RequestOrigin> origins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            origins.add(origin());
        }

        DashboardView saved = service.saveWidget(t.actor(), origins.get(0), t.id(), t.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(t.instance, "m.depth")), input);
        List<String> ids = widgets(saved).stream().map(w -> w.path("id").asText()).toList();
        StringBuilder layout = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            layout.append(i == 0 ? "" : ",").append("{\"widgetId\":\"").append(ids.get(i))
                    .append("\",\"x\":0,\"y\":").append(i * 2).append(",\"w\":12,\"h\":2,\"order\":").append(i)
                    .append("}");
        }
        DashboardView laidOut = service.setLayout(t.actor(), origins.get(1), t.id(), t.instance, saved.revision(),
                layout.append("]").toString(), input);
        DashboardView hidden = service.setHidden(t.actor(), origins.get(2), t.id(), t.instance, laidOut.revision(),
                defaultId, true, input);
        service.reset(t.actor(), origins.get(3), t.id(), t.instance, hidden.revision(), input);

        assertThat(auditOperationOf(origins.get(0))).isEqualTo(AuditOperations.DASHBOARD_WIDGET_SAVED);
        assertThat(auditOperationOf(origins.get(1))).isEqualTo(AuditOperations.DASHBOARD_LAYOUT_SET);
        assertThat(auditOperationOf(origins.get(2))).isEqualTo(AuditOperations.DASHBOARD_WIDGET_HIDDEN);
        assertThat(auditOperationOf(origins.get(3))).isEqualTo(AuditOperations.DASHBOARD_RESET);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'dashboard'"
                + " AND actor_principal_id = :actor", Map.of("actor", t.actor().id()), Integer.class))
                .as("all four attributed to the acting principal").isEqualTo(4);
    }

    // ---------------------------------------------------------------- tenancy (I5)

    @Test
    void aDashboardIsInvisibleToAndUnwritableByAnotherOrganization() {
        Tenant mine = tenant();
        Tenant theirs = tenant();
        service.saveWidget(theirs.actor(), origin(), theirs.id(), theirs.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(theirs.instance, "m.depth")), input);

        DashboardView seen = service.get(mine.id(), theirs.instance, input);
        assertThat(seen.revision()).as("their stored dashboard is not found under my organization").isZero();
        assertThat(widgets(seen).stream().filter(w -> "shared".equals(w.path("layer").asText()))).isEmpty();
        assertThatThrownBy(() -> service.saveWidget(mine.actor(), origin(), mine.id(), theirs.instance, 0,
                new WidgetSubmission(DashboardFixtures.widget(theirs.instance, "m.depth")), input))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- harness

    private record Tenant(UUID organization, UUID instance, PrincipalRef actor) {
        TenantId id() {
            return new TenantId(organization);
        }
    }

    private Tenant tenant() {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)",
                organization);
        migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                organization, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                + " organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", organization, unit, environment);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                + " environment_id, solution_id) VALUES (?, ?, ?, ?)", organization, unit, environment, solution);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", organization, unit,
                environment, solution, instance);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "dashboard-svc-" + principal);
        return new Tenant(organization, instance, new PrincipalRef(principal));
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.50", UUID.randomUUID());
    }

    private String auditOperationOf(RequestOrigin origin) {
        return migrator.queryForObject("SELECT audit_operation FROM sm_control.change_audit"
                + " WHERE table_name = 'dashboard' AND correlation_id = :c",
                Map.of("c", origin.correlationId()), String.class);
    }

    private int count(String sql, Object... args) {
        return migrator.getJdbcTemplate().queryForObject(sql, Integer.class, args);
    }

    private static JsonNode document(DashboardView view) {
        try {
            return JSON.readTree(view.documentJson());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<JsonNode> widgets(DashboardView view) {
        List<JsonNode> widgets = new ArrayList<>();
        document(view).path("widgets").forEach(widgets::add);
        return widgets;
    }

    private static List<JsonNode> layout(DashboardView view) {
        List<JsonNode> layout = new ArrayList<>();
        document(view).path("layout").forEach(layout::add);
        return layout;
    }

    private static List<String> metrics(DashboardView view) {
        return widgets(view).stream().map(w -> w.path("query").path("metric").asText()).toList();
    }

    private static JsonNode widgetById(DashboardView view, String id) {
        return widgets(view).stream().filter(w -> id.equals(w.path("id").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no widget " + id));
    }

    private static JsonNode placementOf(DashboardView view, String id) {
        Set<String> seen = new HashSet<>();
        for (JsonNode placement : layout(view)) {
            assertThat(seen.add(placement.path("widgetId").asText())).as("placed once").isTrue();
        }
        return layout(view).stream().filter(p -> id.equals(p.path("widgetId").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no placement for " + id));
    }
}
