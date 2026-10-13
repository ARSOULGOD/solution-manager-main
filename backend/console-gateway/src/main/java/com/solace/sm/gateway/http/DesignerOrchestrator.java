package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRefusalReason;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.LoggerSupport;
import com.solace.sm.dp.api.config.ObservedInstance;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.inventory.InstanceView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code getDesigner}, {@code stageConfig}, {@code getConfigHistory} and {@code getConfigLadder} (plan 010 T5.3),
 * composed here because the gateway is the one module permitted both {@code -api}s and the designer port needs the
 * catalog (control plane), the config of record and the observed state (data plane) in one request ({@code I7}).
 *
 * <p><b>The server re-projects (OR-34).</b> A stage never stores what the browser computed: it runs
 * {@link DesignerStageValidator}, which re-projects server-side, and persists the SERVER'S rendered set. The client's
 * {@code rendered} is only evidence of drift, and a mismatch is refused. Nothing here writes to a host: a stage is a
 * row in the config of record, and only apply touches a host.
 *
 * <p><b>{@code I6}: no secret value passes through, in either direction.</b> A secret field is accepted only as a
 * {@code secretRef://<uuid>} reference to a stored {@code CONFIG_FIELD} secret of this organization, in a VISIBLE or a
 * HIDDEN field alike (a hidden literal would otherwise be stored verbatim in the draft); the stored rendered entry is
 * the reference, never a value; and no refusal quotes anything the request carried.
 *
 * <p><b>{@code I5}:</b> the organization is {@code Session.organizationId()} on every call to both planes. The
 * instance is looked up through {@link DeclareOrchestrator#get}, so it is visible exactly when {@code getInstance}
 * shows it (a registered-but-pending instance is settled first).
 */
@Component
public class DesignerOrchestrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The history page. The contract has no limit parameter; the retention bound keeps a long history short anyway. */
    static final int HISTORY_LIMIT = 100;

    static final String SECRET_REF_PREFIX = "secretRef://";

    /** The canonical 8-4-4-4-12 form; {@code UUID.fromString} alone also accepts short, unpadded groups. */
    private static final java.util.regex.Pattern CANONICAL_UUID =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Diagnostics that mean the selection or the version could not be projected at all. */
    static final Set<String> UNPROJECTABLE = Set.of("VERSION_UNKNOWN", "PACKAGE_VERSION_NOT_DECLARED",
            "UNKNOWN_COMPONENT");

    private final DeclareOrchestrator instances;
    private final SolutionCatalog catalog;
    private final ConnectionDirectory connections;
    private final ConfigRecordService config;
    private final ObservedStateService observedState;
    private final SecretReferences secrets;
    private final DesignerProjector projector;
    private final DesignerStageValidator validator;
    private final DesignerCodec codec;
    private final MetricApplicability applicability;

    @org.springframework.beans.factory.annotation.Autowired
    public DesignerOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog,
            ConnectionDirectory connections, ConfigRecordService config,
            ObservedStateService observedState, SecretReferences secrets, DesignerProjector projector,
            DesignerStageValidator validator, DesignerCodec codec, MetricApplicability applicability) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.config = Objects.requireNonNull(config, "config");
        this.observedState = Objects.requireNonNull(observedState, "observedState");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.applicability = Objects.requireNonNull(applicability, "applicability");
    }

    /** Compatibility seam for fixture-only callers that do not provide the connection directory. */
    public DesignerOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog, ConfigRecordService config,
            ObservedStateService observedState, SecretReferences secrets, DesignerProjector projector,
            DesignerStageValidator validator, DesignerCodec codec, MetricApplicability applicability) {
        this(instances, catalog, organization -> List.of(), config, observedState, secrets, projector, validator, codec,
                applicability);
    }

    // ---- getDesigner ---------------------------------------------------------------------------------------

    public Designer designer(Session session, UUID instanceId) {
        UUID organization = session.organizationId();
        // The version is read BEFORE the draft: a stage that commits between the two makes the draft newer than the
        // version, so the operator's next stage carries a stale base and is refused (409). The other order could hand
        // out a base NEWER than the draft the operator saw, and the stage would overwrite an edit unseen.
        InstanceView view = requireInstance(session, instanceId);
        Optional<ConfigDraft> draft = config.latestDraft(organization, instanceId);

        PackageModel.SolutionPackage pkg = packageOf(organization, view).orElse(null);
        StateModel.ComponentSelection selection = draft.map(d -> readSelection(d.componentSelectionJson()))
                .orElseGet(() -> pkg == null
                        ? new StateModel.ComponentSelection(List.of())
                        : MetricsDeclarationBuilder.declareTimeSelection(pkg,
                                InstanceController.packageVersionOnTheWire(view.declaredPackageVersion()), view.role()));
        Map<String, Map<String, Object>> values = draft.map(d -> readDraft(d.draftValuesJson())).orElse(Map.of());

        ViewModel viewModel = projector.project(pkg, selection, values,
                toState(observedState.observed(organization, instanceId)), connectionCatalog(organization),
                InstanceController.packageVersionOnTheWire(view.declaredPackageVersion()));
        return new Designer(view.stateVersion(), null, draft.map(ConfigDraft::generation).orElse(null),
                codec.viewModelTree(viewModel), draft.map(d -> readLayout(d.layoutJson())).orElse(Map.of()), view.role());
    }

    // ---- stageConfig ---------------------------------------------------------------------------------------

    public StagedConfig stage(Session session, UUID instanceId, StageConfigRequest request) {
        UUID organization = session.organizationId();
        InstanceView view = requireInstance(session, instanceId);
        PackageModel.SolutionPackage pkg = packageOf(organization, view)
                .orElseThrow(() -> new StageRefusedException(StageRefusedException.Reason.PACKAGE_UNAVAILABLE));

        StateModel.ComponentSelection selection = selectionOf(request.componentSelection());
        Set<String> keys = new HashSet<>();
        for (StateModel.ComponentInstance instance : selection.instances()) {
            if (!keys.add(instance.instanceKey())) {
                throw new StageRefusedException(StageRefusedException.Reason.SELECTION_UNRESOLVED);
            }
        }

        // OR-34: re-project on the server. The outcome's view model is the SERVER'S either way.
        StageOutcome outcome = validator.validateStage(new DesignerStageValidator.StageRequest(pkg, selection,
                request.draftValues(), toState(observedState.observed(organization, instanceId)),
                connectionCatalog(organization),
                InstanceController.packageVersionOnTheWire(view.declaredPackageVersion()), claimOf(request)));
        ViewModel server = outcome instanceof StageOutcome.Accepted accepted ? accepted.serverResult()
                : ((StageOutcome.Refused) outcome).serverResult();

        // I6 first: a literal in a secret field outranks every other refusal, so it is the one named.
        List<UUID> references = secretReferencesOf(server, request.draftValues());
        for (ViewModel.Diagnostic diagnostic : server.diagnostics()) {
            if (UNPROJECTABLE.contains(diagnostic.code())) {
                throw new StageRefusedException(StageRefusedException.Reason.SELECTION_UNRESOLVED);
            }
        }
        if (outcome instanceof StageOutcome.Refused refused) {
            boolean undeclared = refused.reasons().stream()
                    .anyMatch(r -> r instanceof StageRefusalReason.UndeclaredDraftKey);
            throw new StageRefusedException(undeclared ? StageRefusedException.Reason.UNDECLARED_KEY
                    : StageRefusedException.Reason.RENDERED_MISMATCH);
        }
        if (hasBlockingValidation(server)) {
            throw new StageRefusedException(StageRefusedException.Reason.BLOCKING_VALIDATION);
        }

        // Every secret reference in the draft, whether its field is visible, hidden or virtual, must resolve here: a
        // draft value is stored verbatim, so an unresolved one would put an arbitrary string in draft_values (I6).
        for (UUID reference : references) {
            if (!secrets.exists(organization, reference)) {
                throw new StageRefusedException(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED);
            }
        }
        // OR-96: layout is presentation state. The selection stored in the config of record carries none; the layout
        // goes beside it to the DP's presentation record, so a layout-only save (which is "unchanged" config) is kept.
        StageCommand command = new StageCommand(request.baseStateVersion(), pkg.solution(),
                view.declaredPackageVersion(), selectionJson(request.componentSelection()),
                writeJson(request.draftValues()), storedRendered(server, new HashSet<>(references)), GenerationOrigin.EDIT, null,
                session.principal().id(), layoutJson(request.componentSelection()), loggerSupportOf(pkg, selection),
                activeMetricNames(pkg, view, selection, request.draftValues()));
        StageResult result;
        try {
            result = config.stage(organization, instanceId, command);
        } catch (NoSuchElementException gone) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        // StaleStateVersionException propagates to GatewayErrorAdvice: 409 CONFLICT.
        return new StagedConfig(result.stateVersion(), null, result.generation().generation(), result.unchanged());
    }

    /**
     * The browser and the server must project package-declared connection bindings from the same catalog.
     * Connection secrets remain presence metadata only; no secret value enters this map.
     */
    private Map<String, List<Object>> connectionCatalog(UUID organization) {
        List<Object> entries = new ArrayList<>();
        for (ConnectionView connection : connections.list(organization)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", connection.connectionId().toString());
            entry.put("connectionType", connection.connectionType());
            entry.put("attributes", connection.attributes());
            List<String> secretAttributes = connection.secretStatus().entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!secretAttributes.isEmpty()) {
                entry.put("secretAttributes", secretAttributes);
            }
            entries.add(entry);
        }
        return Map.of("connection", entries);
    }

    /**
     * Plan 011 M-Q15: the metric names active for THIS draft, by the control plane's evaluator, stored with the generation.
     * The draft the stage stores is the one evaluated (never the running values, which a restart has not yet made true).
     * Package-private: accept-runtime and a console secret re-project an edited draft and evaluate it the same way.
     */
    List<String> activeMetricNames(PackageModel.SolutionPackage pkg, InstanceView view,
            StateModel.ComponentSelection selection, Map<String, Map<String, Object>> draft) {
        return activeMetricNames(applicability, pkg, view, selection, draft);
    }

    static List<String> activeMetricNames(MetricApplicability applicability, PackageModel.SolutionPackage pkg,
            InstanceView view, StateModel.ComponentSelection selection, Map<String, Map<String, Object>> draft) {
        return applicability.activeMetricNames(pkg, InstanceController.packageVersionOnTheWire(view.declaredPackageVersion()),
                selection, draft);
    }

    /**
     * OR-103 / BE-D37, BE-D38: the package's logger declaration for the components this stage selects, or {@code null}
     * when none declares one. Only the declared FACT travels: the data plane turns it into the SM-owned overlay keys with
     * its one function, so the gateway cannot mint (or drift from) an overlay key.
     *
     * <p>The baseline is the union of the selected, loggers-declaring components' {@code logs.baselineExposure}. A
     * component that declares {@code loggers} but no baseline contributes nothing, because the exposure list SM writes
     * replaces the MI's own and a list without the baseline would withdraw what the MI exposes today (the package gate
     * requires the baseline, so this is reachable only for a package loaded before it existed). A baseline entry outside
     * the endpoint grammar also contributes nothing: no overlay is a fail-safe, a half-trusted list is not.
     */
    static LoggerSupport loggerSupportOf(PackageModel.SolutionPackage pkg, StateModel.ComponentSelection selection) {
        Set<String> selected = new HashSet<>();
        selection.instances().forEach(i -> selected.add(i.type()));
        java.util.TreeSet<String> baseline = new java.util.TreeSet<>();
        boolean declared = false;
        for (PackageModel.PackageComponent component : pkg.components()) {
            Map<String, Object> logs = component.logs();
            if (!selected.contains(component.type()) || logs == null || !(logs.get("loggers") instanceof Map<?, ?>)
                    || !(logs.get("baselineExposure") instanceof List<?> exposure)) {
                continue;
            }
            for (Object endpoint : exposure) {
                if (!(endpoint instanceof String id) || !LoggerSupport.ENDPOINT_ID.matcher(id).matches()) {
                    return null;
                }
                baseline.add(id);
            }
            declared = true;
        }
        return declared ? new LoggerSupport(List.copyOf(baseline)) : null;
    }

    // ---- getConfigHistory / getConfigLadder ----------------------------------------------------------------

    public ConfigHistory history(Session session, UUID instanceId) {
        InstanceView view = requireInstance(session, instanceId);
        List<GenerationView> generations = config.history(session.organizationId(), instanceId, HISTORY_LIMIT);
        return new ConfigHistory(view.stateVersion(), null, generations.stream()
                .map(g -> new ConfigHistory.Entry(g.generation(), g.createdAt().toString(),
                        g.createdBy().toString(), g.contentDigest()))
                .toList());
    }

    public ConfigLadder ladder(Session session, UUID instanceId) {
        InstanceView view = requireInstance(session, instanceId);
        ConfigLadderView ladder = observedState.ladder(session.organizationId(), instanceId);
        Availability availability = ladder.degradedReason() == null ? null
                : new Availability(AvailabilityState.DEGRADED, null, ladder.degradedReason(), null);
        return new ConfigLadder(view.stateVersion(), availability,
                ladder.rungs().stream().map(r -> new ConfigLadder.Rung(r.rung().wire(), r.present(), r.generation(),
                        r.configHash())).toList(),
                ladder.reached().wire(), null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------
    // Package-private (not private) so AcceptRuntimeOrchestrator reuses the server-side half of a stage verbatim (T10.4).

    /** Authorization at the instance has passed; only an ACTIVE row is readable, else the same 404 as out of scope. */
    private InstanceView requireInstance(Session session, UUID instanceId) {
        return instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    Optional<PackageModel.SolutionPackage> packageOf(UUID organization, InstanceView view) {
        return catalog.documentOf(organization, view.catalogSolutionId(), view.declaredPackageVersion())
                .map(codec::readPackage);
    }

    static StateModel.ObservedState toState(ObservedInstance observed) {
        ObservedInstance.Rungs rungs = observed.config();
        return new StateModel.ObservedState(
                new StateModel.VersionObservation(observed.declaredVersion(), observed.observedVersion(),
                        observed.versionState()),
                new StateModel.ConfigState(rungs.desired(), rungs.rendered(), rungs.persisted(),
                        rungs.restartObserved(), rungs.effectiveVerified()),
                observed.replicas().stream().map(r -> new StateModel.ReplicaObservation(r.id(), r.health())).toList(),
                null);
    }

    private static StateModel.ComponentSelection selectionOf(StageConfigRequest.ComponentSelection body) {
        return new StateModel.ComponentSelection(body.instances().stream()
                .map(i -> new StateModel.ComponentInstance(i.instanceKey(), i.type(),
                        i.layout() == null ? null
                                : new StateModel.Layout(i.layout().x().intValue(), i.layout().y().intValue())))
                .toList());
    }

    private static List<ViewModel.RenderedEntry> claimOf(StageConfigRequest request) {
        return request.rendered().stream()
                .map(r -> new ViewModel.RenderedEntry(r.instanceKey(), r.renderedPath(), r.value())).toList();
    }

    /**
     * Every secret field of every selected instance, visible, hidden or virtual, may hold only nothing or EXACTLY
     * {@code secretRef://} followed by a canonical UUID. Anything else is a literal (or a malformed reference) and is
     * refused without asking the store and without quoting it. Returns the references found, to be resolved.
     */
    static List<UUID> secretReferencesOf(ViewModel server, Map<String, Map<String, Object>> draft) {
        List<UUID> references = new ArrayList<>();
        for (ViewModel.ComponentView component : server.components()) {
            Map<String, Object> values = draft.getOrDefault(component.instanceKey(), Map.of());
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    if (!field.masked()) {
                        continue;
                    }
                    Object raw = values.get(field.key());
                    if (raw == null || "".equals(raw)) {
                        continue;
                    }
                    if (!(raw instanceof String text) || !text.startsWith(SECRET_REF_PREFIX)) {
                        throw new StageRefusedException(StageRefusedException.Reason.SECRET_LITERAL);
                    }
                    references.add(referenceOf(text));
                }
            }
        }
        return references;
    }

    /** The UUID of a {@code secretRef://<uuid>} string; a prefix with anything else after it is not a reference. */
    static UUID referenceOf(String text) {
        String id = text.substring(SECRET_REF_PREFIX.length());
        if (!CANONICAL_UUID.matcher(id).matches()) {
            throw new StageRefusedException(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED);
        }
        return UUID.fromString(id);
    }

    static boolean hasBlockingValidation(ViewModel server) {
        for (ViewModel.ComponentView component : server.components()) {
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    if (field.validations().stream().anyMatch(v -> "block".equals(v.severity()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The SERVER'S rendered set, as stored: a plain value as JSON text, a secret field as the reference it holds.
     * Secret-ness is the package's own ({@code masked}), never the value's shape. Every reference has already been
     * shape-checked and resolved ({@link #secretReferencesOf}, then the store) before this runs.
     */
    static List<RenderedEntryValue> storedRendered(ViewModel server, Set<UUID> resolved) {
        Set<String> secretPaths = new HashSet<>();
        for (ViewModel.ComponentView component : server.components()) {
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    if (field.masked() && field.renderedPath() != null) {
                        secretPaths.add(component.instanceKey() + '\u0000' + field.renderedPath());
                    }
                }
            }
        }
        List<RenderedEntryValue> out = new ArrayList<>();
        for (ViewModel.RenderedEntry entry : server.rendered()) {
            if (secretPaths.contains(entry.instanceKey() + '\u0000' + entry.renderedPath())) {
                // A secret entry is a reference the draft carried and the store resolved, never anything else.
                if (!(entry.value() instanceof String text) || !text.startsWith(SECRET_REF_PREFIX)
                        || !resolved.contains(referenceOf(text))) {
                    throw new StageRefusedException(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED);
                }
                out.add(RenderedEntryValue.secret(entry.instanceKey(), entry.renderedPath(), referenceOf(text)));
            } else {
                out.add(RenderedEntryValue.plain(entry.instanceKey(), entry.renderedPath(), writeJson(entry.value())));
            }
        }
        return out;
    }

    private static String selectionJson(StageConfigRequest.ComponentSelection body) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode instances = root.putArray("instances");
        for (StageConfigRequest.Instance instance : body.instances()) {
            ObjectNode node = instances.addObject();
            node.put("instanceKey", instance.instanceKey());
            node.put("type", instance.type());
        }
        return writeJson(root);
    }

    /**
     * OR-96: the layout of this save, instance key to position, exactly as the client sent the numbers (the contract
     * types x and y as number, and a canvas position may be fractional). An instance with no layout is absent, so the
     * record is REPLACED by what this save said: a selection that carries none stages an empty layout.
     */
    private static String layoutJson(StageConfigRequest.ComponentSelection body) {
        ObjectNode root = MAPPER.createObjectNode();
        for (StageConfigRequest.Instance instance : body.instances()) {
            if (instance.layout() != null) {
                ObjectNode layout = root.putObject(instance.instanceKey());
                layout.set("x", MAPPER.valueToTree(instance.layout().x()));
                layout.set("y", MAPPER.valueToTree(instance.layout().y()));
            }
        }
        return writeJson(root);
    }

    private static Map<String, Designer.Position> readLayout(String json) {
        Map<String, Designer.Position> out = new java.util.TreeMap<>();
        try {
            MAPPER.readTree(json).fields().forEachRemaining(e -> out.put(e.getKey(),
                    new Designer.Position(e.getValue().get("x").numberValue(), e.getValue().get("y").numberValue())));
        } catch (JsonProcessingException | RuntimeException e) {
            // The message would quote stored text: not repeated.
            throw new IllegalStateException("a stored designer layout could not be read");
        }
        return out;
    }

    static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a staged value could not be written as JSON");
        }
    }

    static StateModel.ComponentSelection readSelection(String json) {
        try {
            return MAPPER.readValue(json, StateModel.ComponentSelection.class);
        } catch (JsonProcessingException e) {
            // The message quotes the stored text, which is configuration (I6): not repeated.
            throw new IllegalStateException("a stored component selection could not be read");
        }
    }

    static Map<String, Map<String, Object>> readDraft(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Map<String, Object>>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored draft values could not be read");
        }
    }
}
