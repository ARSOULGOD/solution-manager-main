package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRefusalReason;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.readback.ReadBackCandidate;
import com.solace.sm.dp.api.readback.RuntimeReadBackService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code acceptRuntimeConfig} (plan 010 T10.4; D-ACCEPT, as ruled by {@code OR-104}), composed here because the gateway is
 * the one module permitted both {@code -api}s: the package and the projection are the control plane's, the host's file and
 * the config of record are the data plane's ({@code I7}).
 *
 * <p>The flow, with the cheap refusals first (D-ACCEPT §4.1):
 * <ol>
 *   <li><b>Authorization.</b> The interceptor has passed {@code config.edit} at the instance; this adds the second verb,
 *       {@code config.overrideWarning} (accepting drift is an override, not an edit), naming it on denial; then the
 *       instance and the named placement must be live and the organization's (the same {@code 404} as out of scope).</li>
 *   <li><b>Read.</b> The data plane reads the placement's own file, parses it and maps its declared paths
 *       ({@link RuntimeReadBackService}); this class hands it a {@link FieldPathTable} and receives paths and values, never a
 *       secret ({@code I6}, {@code V-4}).</li>
 *   <li><b>Compose.</b> The latest generation's draft, with each mapped non-secret value replacing the field's and each
 *       declared non-secret field the file does not carry unset ({@code the file is the runtime's truth, and a missing key
 *       means the MI uses its default}), for a field the projection shows; an inactive field keeps its stored value
 *       ({@code OR-108} (6)). A secret field KEEPS its current reference: a read-back secret is never adopted. A
 *       {@code yaml} field keeps its current value: it is not accepted at all.</li>
 *   <li><b>Stage.</b> The SAME server-side half {@code stageConfig} runs ({@code OR-34}): the server re-projects, refuses an
 *       undeclared key and a blocking validation, and stores its own result, under {@code origin = accept-runtime}. A
 *       warning does not block. Nothing is written to the host ({@code X-1}); the response says so, and says whether the
 *       file still differs from SM's rendering.</li>
 * </ol>
 *
 * <p><b>{@code I5}:</b> the organization and the actor are the session's. <b>{@code I6}:</b> no refusal and no response
 * carries a value; a refusal names the package's own field key and a closed rule code.
 */
@Component
public class AcceptRuntimeOrchestrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeclareOrchestrator instances;
    private final ConfigRecordService config;
    private final ObservedStateService observedState;
    private final SecretReferences secrets;
    private final RuntimeReadBackService readBack;
    private final DesignerProjector projector;
    private final DesignerStageValidator validator;
    private final AuthorizationService authorization;
    private final DesignerOrchestrator designer;

    public AcceptRuntimeOrchestrator(DeclareOrchestrator instances, ConfigRecordService config,
            ObservedStateService observedState, SecretReferences secrets, RuntimeReadBackService readBack,
            DesignerProjector projector, DesignerStageValidator validator, AuthorizationService authorization,
            DesignerOrchestrator designer) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.config = Objects.requireNonNull(config, "config");
        this.observedState = Objects.requireNonNull(observedState, "observedState");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.readBack = Objects.requireNonNull(readBack, "readBack");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.designer = Objects.requireNonNull(designer, "designer");
    }

    public AcceptedRuntimeConfig accept(Session session, UUID instanceId, AcceptRuntimeRequest request) {
        UUID organization = session.organizationId();

        // 1. authorization: the second verb first (a denial names it), then the instance and placement are this
        //    organization's and live. An out-of-scope instance has already been a 404 in the interceptor.
        requireAlso(session, Permission.CONFIG_OVERRIDE_WARNING, instanceId);
        InstanceView view = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        if (view.placements().stream().noneMatch(p -> p.placementId().equals(request.placementId()))) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        AgentPlacements.refuseIfAgent(view, request.placementId());      // plan 015 T3.3: 501 before any host is read
        PackageModel.SolutionPackage pkg = designer.packageOf(organization, view)
                .orElseThrow(() -> new StageRefusedException(StageRefusedException.Reason.PACKAGE_UNAVAILABLE));
        ConfigDraft latest = config.latestDraft(organization, instanceId)
                .orElseThrow(() -> new AcceptRefusedException(AcceptRefusedException.Reason.NO_GENERATION));
        StateModel.ComponentSelection selection = DesignerOrchestrator.readSelection(latest.componentSelectionJson());
        Map<String, Map<String, Object>> current = DesignerOrchestrator.readDraft(latest.draftValuesJson());
        StateModel.ObservedState observed = DesignerOrchestrator.toState(observedState.observed(organization, instanceId));
        Object version = InstanceController.packageVersionOnTheWire(view.declaredPackageVersion());

        // 2. read: what is declared, then the data plane reads, parses and maps. A refusal propagates (404/422/503).
        ViewModel declared = projector.project(pkg, selection, current, observed, Map.of(), version);
        for (ViewModel.Diagnostic diagnostic : declared.diagnostics()) {
            if (DesignerOrchestrator.UNPROJECTABLE.contains(diagnostic.code())) {
                throw new StageRefusedException(StageRefusedException.Reason.SELECTION_UNRESOLVED);
            }
        }
        FieldPathTable table = tableOf(declared, current);
        ReadBackCandidate candidate = readBack.readBack(organization, instanceId, request.placementId(), table);

        // 3. compose: mapped non-secret values replace, absent ones unset; secrets and yaml keep what they hold
        Map<String, Map<String, Object>> accepted = compose(current, table, candidate, fieldsOf(declared));

        // 4. the server's half of a stage (OR-34): re-project, refuse an undeclared key and a block, store its own result
        ViewModel server = projector.project(pkg, selection, accepted, observed, Map.of(), version);
        StageOutcome outcome = validator.validateStage(new DesignerStageValidator.StageRequest(pkg, selection, accepted,
                observed, Map.of(), version, server.rendered()));
        ViewModel serverResult = outcome instanceof StageOutcome.Accepted a ? a.serverResult()
                : ((StageOutcome.Refused) outcome).serverResult();
        if (outcome instanceof StageOutcome.Refused refused) {
            boolean undeclared = refused.reasons().stream().anyMatch(r -> r instanceof StageRefusalReason.UndeclaredDraftKey);
            throw new StageRefusedException(undeclared ? StageRefusedException.Reason.UNDECLARED_KEY
                    : StageRefusedException.Reason.RENDERED_MISMATCH);
        }
        List<AcceptRefusedException.FieldRule> blocks = blockingRules(serverResult);
        if (!blocks.isEmpty()) {
            throw new AcceptRefusedException(AcceptRefusedException.Reason.BLOCKING_VALIDATION, blocks);
        }
        List<AcceptRefusedException.FieldRule> lossy = roundTripLosses(serverResult, candidate);
        if (!lossy.isEmpty()) {
            throw new AcceptRefusedException(AcceptRefusedException.Reason.ROUND_TRIP, lossy);
        }

        List<UUID> references = DesignerOrchestrator.secretReferencesOf(serverResult, accepted);
        for (UUID reference : references) {
            if (!secrets.exists(organization, reference)) {
                throw new StageRefusedException(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED);
            }
        }
        StageCommand command = new StageCommand(request.baseStateVersion(), pkg.solution(), view.declaredPackageVersion(),
                latest.componentSelectionJson(), DesignerOrchestrator.writeJson(accepted),
                DesignerOrchestrator.storedRendered(serverResult, new HashSet<>(references)),
                GenerationOrigin.ACCEPT_RUNTIME, latest.generation(), session.principal().id(), null,
                DesignerOrchestrator.loggerSupportOf(pkg, selection),
                designer.activeMetricNames(pkg, view, selection, accepted));
        StageResult result;
        try {
            result = config.stage(organization, instanceId, command);
        } catch (NoSuchElementException gone) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        // StaleStateVersionException propagates to GatewayErrorAdvice: 409 CONFLICT.

        boolean bytesDiffer = readBack.fileDiffersFromRendering(organization, instanceId, request.placementId(),
                result.generation().generation(), candidate);
        List<AcceptedRuntimeConfig.NotAccepted> report = notAccepted(candidate, serverResult);
        boolean truncated = candidate.truncated() || report.size() > ReadBackCandidate.MAX_NOT_ACCEPTED;
        return new AcceptedRuntimeConfig(result.stateVersion(), null, result.generation().generation(),
                result.unchanged(), request.placementId(), bytesDiffer, truncated,
                truncated && report.size() > ReadBackCandidate.MAX_NOT_ACCEPTED
                        ? report.subList(0, ReadBackCandidate.MAX_NOT_ACCEPTED) : report);
    }

    // ---- the table ----------------------------------------------------------------------------------------------

    /**
     * Every declared, non-virtual field of every selected instance, active or not, by the path the renderer writes it at.
     * A secret field carries the reference the draft currently holds (a reference, never a value).
     */
    private static FieldPathTable tableOf(ViewModel declared, Map<String, Map<String, Object>> current) {
        List<FieldPathTable.Entry> entries = new ArrayList<>();
        for (ViewModel.ComponentView component : declared.components()) {
            Map<String, Object> values = current.getOrDefault(component.instanceKey(), Map.of());
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    if (field.renderedPath() == null) {
                        continue;   // virtual: it reaches no config file
                    }
                    UUID reference = field.masked() ? referenceOf(values.get(field.key())) : null;
                    entries.add(new FieldPathTable.Entry(component.instanceKey(), field.key(), field.renderedPath(),
                            "yaml-editor".equals(field.controlKind()) ? "yaml" : field.controlKind(), field.masked(),
                            reference));
                }
            }
        }
        return new FieldPathTable(entries);
    }

    private static UUID referenceOf(Object draftValue) {
        if (draftValue instanceof String text && text.startsWith("secretRef://")) {
            try {
                return UUID.fromString(text.substring("secretRef://".length()));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    // ---- compose --------------------------------------------------------------------------------------------------

    /**
     * The accepted draft: the latest generation's draft, changed only where the file says something DIFFERENT.
     *
     * <p>A mapped value equal to the field's <i>effective</i> value (its draft value, or its default when the draft has
     * none) leaves the draft alone. A file SM itself rendered carries every defaulted field too, and writing those into
     * the draft would mint a new generation for a file that says nothing new: guard 1 ({@code unchanged}) would never
     * fire for the most ordinary accept there is. A declared non-secret field the file does not carry is unset (the MI
     * runs on its default) when the projection shows it, and kept when the projection does not (it is not rendered, so
     * its absence says nothing; {@code OR-108} (6)); a secret or {@code yaml} field is never touched.
     */
    private static Map<String, Map<String, Object>> compose(Map<String, Map<String, Object>> current,
            FieldPathTable table, ReadBackCandidate candidate, Map<String, ViewModel.FieldView> declared) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        current.forEach((instance, values) -> out.put(instance, new LinkedHashMap<>(values)));
        Map<String, JsonNode> mapped = new HashMap<>();
        for (ReadBackCandidate.MappedValue value : candidate.values()) {
            mapped.put(value.instanceKey() + '\u0000' + value.fieldKey(), readTree(value.valueJson()));
        }
        for (FieldPathTable.Entry field : table.entries()) {
            if (field.secret() || field.yaml()) {
                continue;   // never adopted: the draft keeps what it holds
            }
            String key = field.instanceKey() + '\u0000' + field.fieldKey();
            Map<String, Object> values = out.computeIfAbsent(field.instanceKey(), k -> new LinkedHashMap<>());
            JsonNode value = mapped.get(key);
            if (value == null || value.isNull()) {
                // Absent (or null) in the file means unset, for a field the projection SHOWS. A field it does not show
                // (its `when` is false, an inactive TLS keystore) is not rendered, so the file's silence about it is no
                // runtime fact: the stored draft value stays (OR-108 (6), D-ACCEPT section 3.3 item 5).
                ViewModel.FieldView shown = declared.get(key);
                if (shown == null || shown.visible()) {
                    values.remove(field.fieldKey());
                }
            } else if (!jsonEquals(effective(declared.get(key)), value)) {
                values.put(field.fieldKey(), parse(value.toString()));
            }
        }
        return out;
    }

    /** The field's value as the projection resolved it (draft over default), as a tree; null when it has none. */
    private static JsonNode effective(ViewModel.FieldView field) {
        if (field != null && field.value() instanceof ViewModel.FieldValue.Value v && v.value() != null) {
            return MAPPER.valueToTree(v.value());
        }
        return MAPPER.nullNode();
    }

    private static Object parse(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            // the parser's message quotes configuration (I6): not repeated
            throw new IllegalStateException("a mapped value could not be read");
        }
    }

    // ---- what the server's projection says --------------------------------------------------------------------------

    /** Every blocking validation of a VISIBLE field, as the package's own field key and a closed rule code. */
    private static List<AcceptRefusedException.FieldRule> blockingRules(ViewModel server) {
        List<AcceptRefusedException.FieldRule> out = new ArrayList<>();
        for (ViewModel.ComponentView component : server.components()) {
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    for (ViewModel.Validation validation : field.validations()) {
                        if ("block".equals(validation.severity())) {
                            out.add(new AcceptRefusedException.FieldRule(field.key(), validation.code()));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * D-ACCEPT §3.3 item 7: a mapped value of a VISIBLE field must equal what the server renders for it. A difference means
     * the mapping lost or changed a value (the file holds {@code "250000"}, a string, where the package renders the integer),
     * which is refused rather than quietly rewritten at the next apply.
     */
    private static List<AcceptRefusedException.FieldRule> roundTripLosses(ViewModel server, ReadBackCandidate candidate) {
        Map<String, Object> rendered = new HashMap<>();
        for (ViewModel.RenderedEntry entry : server.rendered()) {
            rendered.put(entry.instanceKey() + '\u0000' + entry.renderedPath(), entry.value());
        }
        Map<String, ViewModel.FieldView> fields = fieldsOf(server);
        List<AcceptRefusedException.FieldRule> out = new ArrayList<>();
        for (ReadBackCandidate.MappedValue value : candidate.values()) {
            ViewModel.FieldView field = fields.get(value.instanceKey() + '\u0000' + value.fieldKey());
            if (field == null || !field.visible() || field.renderedPath() == null) {
                continue;
            }
            String key = value.instanceKey() + '\u0000' + field.renderedPath();
            if (!rendered.containsKey(key) || !jsonEquals(MAPPER.valueToTree(rendered.get(key)), readTree(value.valueJson()))) {
                out.add(new AcceptRefusedException.FieldRule(field.key(), "ROUND_TRIP"));
            }
        }
        return out;
    }

    private static Map<String, ViewModel.FieldView> fieldsOf(ViewModel server) {
        Map<String, ViewModel.FieldView> out = new HashMap<>();
        for (ViewModel.ComponentView component : server.components()) {
            for (ViewModel.SectionView section : component.sections()) {
                for (ViewModel.FieldView field : section.fields()) {
                    out.put(component.instanceKey() + '\u0000' + field.key(), field);
                }
            }
        }
        return out;
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a mapped value could not be read");
        }
    }

    /** JSON equality where one number is one number: {@code 5} equals {@code 5.0}, as it does to the MI's binder. */
    static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText())) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = a.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode other = b.get(field.getKey());
                if (other == null || !jsonEquals(field.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    // ---- the report -----------------------------------------------------------------------------------------------

    /**
     * What was not adopted: the data plane's account (paths, secrets compared in memory, SM's own keys) plus the declared
     * fields the file carried that are INACTIVE under the accepted values. Sorted by path and capped at the contract's 200.
     */
    private static List<AcceptedRuntimeConfig.NotAccepted> notAccepted(ReadBackCandidate candidate, ViewModel server) {
        Map<String, ViewModel.FieldView> fields = fieldsOf(server);
        List<AcceptedRuntimeConfig.NotAccepted> out = new ArrayList<>();
        for (ReadBackCandidate.NotAccepted entry : candidate.notAccepted()) {
            out.add(new AcceptedRuntimeConfig.NotAccepted(entry.path(), entry.fieldKey(), entry.reason().wire(),
                    entry.comparison() == null ? null : entry.comparison().name().toLowerCase(java.util.Locale.ROOT)));
        }
        for (ReadBackCandidate.MappedValue value : candidate.values()) {
            ViewModel.FieldView field = fields.get(value.instanceKey() + '\u0000' + value.fieldKey());
            if (field != null && !field.visible() && field.renderedPath() != null) {
                out.add(new AcceptedRuntimeConfig.NotAccepted(field.renderedPath(), field.key(),
                        NotAcceptedReason.INACTIVE_FIELD.wire(), null));
            }
        }
        out.sort(Comparator.comparing(AcceptedRuntimeConfig.NotAccepted::path));
        return List.copyOf(out);
    }

    /** A secondary verb: a denial names WHICH verb; a missing or out-of-scope resource stays an indistinguishable 404. */
    private void requireAlso(Session session, Permission permission, UUID instanceId) {
        try {
            authorization.require(session.principal(), permission,
                    ResourceRef.of(ResourceKind.INSTANCE, instanceId, new TenantId(session.organizationId())));
        } catch (AccessDeniedException e) {
            throw new SecondaryVerbDeniedException(permission);
        }
    }
}
