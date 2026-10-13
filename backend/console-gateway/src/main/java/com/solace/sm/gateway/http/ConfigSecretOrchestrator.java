package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
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
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.ConfigSecretService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.SecretValueRejectedException;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.inventory.InstanceView;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code setConfigSecret} (plan 010 T10.1; OR-30, OR-45, OR-60): one call that stores a config-field secret AND stages
 * the generation that references it, so no stored secret is ever unreferenced (point 17, shape (a)).
 *
 * <p><b>The same stage path, not a new one.</b> The staged content is a copy-forward of the instance's latest draft with
 * one field's value replaced by {@code secretRef://<new id>}, and it is built by the same rules {@code stageConfig}
 * applies (OR-34): the server re-projects the selection and the new draft, refuses an unprojectable selection, an
 * undeclared key or a blocking validation, requires every other secret reference in the draft to resolve, and stores
 * ITS rendered set, never a client's. Those steps run in {@link ConfigSecretService}'s transaction, after the instance
 * row is locked and the {@code baseStateVersion} checked, because the reference does not exist until the store mints it.
 * That builder is pure CPU: the package, the observed state and the draft are all read before the lock is taken.
 *
 * <p><b>Which field.</b> The path names a field key, not a component instance, and the contract's body carries no
 * instance key. The target is the one secret field of that key in the latest draft's selection; no such field
 * is a {@code 404} (the field is not one this instance's package marks secret), and a key that two selected
 * component instances both declare is a {@code 422}, never a guess (a credential must not land on an instance the
 * operator did not mean).
 *
 * <p><b>{@code I6}.</b> The value is passed through as a {@code char[]} the controller zeroises; this class never
 * copies it, prints it or puts it in an exception, and every refusal here has fixed text. <b>{@code I5}.</b> The
 * organization is {@code Session.organizationId()} on every call to both planes.
 */
@Component
public class ConfigSecretOrchestrator {

    private final DeclareOrchestrator instances;
    private final SolutionCatalog catalog;
    private final ConfigRecordService config;
    private final ConfigSecretService configSecrets;
    private final ObservedStateService observedState;
    private final SecretReferences secrets;
    private final DesignerProjector projector;
    private final DesignerStageValidator validator;
    private final DesignerCodec codec;
    private final MetricApplicability applicability;

    public ConfigSecretOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog, ConfigRecordService config,
            ConfigSecretService configSecrets, ObservedStateService observedState, SecretReferences secrets,
            DesignerProjector projector, DesignerStageValidator validator, DesignerCodec codec,
            MetricApplicability applicability) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.config = Objects.requireNonNull(config, "config");
        this.configSecrets = Objects.requireNonNull(configSecrets, "configSecrets");
        this.observedState = Objects.requireNonNull(observedState, "observedState");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.applicability = Objects.requireNonNull(applicability, "applicability");
    }

    /**
     * @param value the secret literal; the CALLER zeroises it after this returns
     */
    public ConfigSecretResult setSecret(Session session, UUID instanceId, String fieldKey, char[] value,
            long baseStateVersion) {
        UUID organization = session.organizationId();
        InstanceView view = instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        PackageModel.SolutionPackage pkg = catalog
                .documentOf(organization, view.catalogSolutionId(), view.declaredPackageVersion())
                .map(codec::readPackage)
                .orElseThrow(() -> new StageRefusedException(StageRefusedException.Reason.PACKAGE_UNAVAILABLE));
        // No draft means no component instance to hold a secret field: the field is not one this instance has.
        ConfigDraft draft = config.latestDraft(organization, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));

        StateModel.ComponentSelection selection = DesignerOrchestrator.readSelection(draft.componentSelectionJson());
        Map<String, Map<String, Object>> values = DesignerOrchestrator.readDraft(draft.draftValuesJson());
        StateModel.ObservedState observed = DesignerOrchestrator
                .toState(observedState.observed(organization, instanceId));
        Object packageVersion = InstanceController.packageVersionOnTheWire(view.declaredPackageVersion());

        ViewModel current = projector.project(pkg, selection, values, observed, Map.of(), packageVersion);
        refuseUnprojectable(current);
        String instanceKey = targetInstanceKey(current, fieldKey);
        UUID actor = session.principal().id();

        StageResult result;
        try {
            result = configSecrets.storeAndStage(organization, instanceId, baseStateVersion, value, actor,
                    reference -> stageCommand(organization, view, pkg, draft, selection, values, observed,
                            packageVersion, instanceKey, fieldKey, reference, baseStateVersion, actor));
        } catch (NoSuchElementException gone) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        } catch (SecretValueRejectedException refused) {
            throw new StageRefusedException(StageRefusedException.Reason.SECRET_VALUE_REJECTED);
        }
        // StaleStateVersionException propagates to GatewayErrorAdvice: 409 CONFLICT, and nothing was stored.
        return new ConfigSecretResult(result.stateVersion(), null, result.generation().generation());
    }

    /**
     * The stage command for the draft with {@code instanceKey}'s {@code fieldKey} pointing at {@code reference},
     * through the rules {@code stageConfig} applies. Runs inside the data plane's transaction and so performs no I/O
     * beyond the resolution of the references that were ALREADY in the draft (a read the store answers from committed
     * rows; the new reference is not committed yet and is known to be the one just minted).
     */
    private StageCommand stageCommand(UUID organization, InstanceView view, PackageModel.SolutionPackage pkg,
            ConfigDraft draft, StateModel.ComponentSelection selection, Map<String, Map<String, Object>> values,
            StateModel.ObservedState observed, Object packageVersion, String instanceKey, String fieldKey,
            UUID reference, long baseStateVersion, UUID actor) {
        Map<String, Map<String, Object>> next = new LinkedHashMap<>();
        values.forEach((key, fields) -> next.put(key, new LinkedHashMap<>(fields)));
        next.computeIfAbsent(instanceKey, key -> new LinkedHashMap<>())
                .put(fieldKey, DesignerOrchestrator.SECRET_REF_PREFIX + reference);

        // The server is its own claim: what it would render for this draft, checked by the same validator stage uses.
        ViewModel claim = projector.project(pkg, selection, next, observed, Map.of(), packageVersion);
        StageOutcome outcome = validator.validateStage(new DesignerStageValidator.StageRequest(pkg, selection, next,
                observed, Map.of(), packageVersion, claim.rendered()));
        ViewModel server = outcome instanceof StageOutcome.Accepted accepted ? accepted.serverResult()
                : ((StageOutcome.Refused) outcome).serverResult();

        List<UUID> references = DesignerOrchestrator.secretReferencesOf(server, next);
        refuseUnprojectable(server);
        if (outcome instanceof StageOutcome.Refused refused) {
            boolean undeclared = refused.reasons().stream()
                    .anyMatch(r -> r instanceof StageRefusalReason.UndeclaredDraftKey);
            throw new StageRefusedException(undeclared ? StageRefusedException.Reason.UNDECLARED_KEY
                    : StageRefusedException.Reason.RENDERED_MISMATCH);
        }
        if (DesignerOrchestrator.hasBlockingValidation(server)) {
            throw new StageRefusedException(StageRefusedException.Reason.BLOCKING_VALIDATION);
        }
        // Every OTHER reference the draft carries must still resolve. The new one is the id the store just minted in
        // this transaction: it is not committed, so the store cannot be asked about it, and it was not typed by anyone.
        for (UUID existing : references) {
            if (!existing.equals(reference) && !secrets.exists(organization, existing)) {
                throw new StageRefusedException(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED);
            }
        }
        return new StageCommand(baseStateVersion, pkg.solution(), view.declaredPackageVersion(),
                draft.componentSelectionJson(), DesignerOrchestrator.writeJson(next),
                DesignerOrchestrator.storedRendered(server, new HashSet<>(references)), GenerationOrigin.EDIT, null,
                actor, null, DesignerOrchestrator.loggerSupportOf(pkg, selection),
                DesignerOrchestrator.activeMetricNames(applicability, pkg, view, selection, next));
    }

    private static void refuseUnprojectable(ViewModel model) {
        for (ViewModel.Diagnostic diagnostic : model.diagnostics()) {
            if (DesignerOrchestrator.UNPROJECTABLE.contains(diagnostic.code())) {
                throw new StageRefusedException(StageRefusedException.Reason.SELECTION_UNRESOLVED);
            }
        }
    }

    /**
     * The instance key of the one selected component whose package marks {@code fieldKey} secret. 404 when there is
     * none (a field that does not exist, and a field that is not secret, are the same answer); 422 when several do.
     */
    private static String targetInstanceKey(ViewModel model, String fieldKey) {
        Optional<String> found = Optional.empty();
        for (ViewModel.ComponentView component : model.components()) {
            boolean declares = component.sections().stream().flatMap(section -> section.fields().stream())
                    .anyMatch(field -> field.masked() && field.key().equals(fieldKey));
            if (!declares) {
                continue;
            }
            if (found.isPresent()) {
                throw new StageRefusedException(StageRefusedException.Reason.SECRET_FIELD_AMBIGUOUS);
            }
            found = Optional.of(component.instanceKey());
        }
        return found.orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    /** Names no collaborator state. */
    @Override
    public String toString() {
        return "ConfigSecretOrchestrator";
    }
}
