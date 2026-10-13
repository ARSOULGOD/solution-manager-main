/**
 * The assembled Designer screen (tasks 123/124, design §10/§13/§14, UI
 * §A.8; reference `app/designer.jsx`) — sticky section nav, a scrolling
 * form pane, a pinned save bar. Every piece of behaviour here (projection,
 * validation, save, version blocking, rollout, apply) already lives in the
 * ~25 Designer components built through task 063; this file only
 * assembles them and owns the state that ties them together (which
 * section is expanded, which modal is open, where the apply flow is).
 *
 * **The generation keys the draft** (`FE-D20`). `DesignerPage` remounts
 * this component via `key={load.configGeneration}` whenever the baseline
 * changes — a successful save, a conflict refetch, an accepted/restored
 * runtime — so `useDesignerDraft`'s `initialDraft` is always stable for
 * this component's whole lifetime and a save is never an optimistic edit.
 *
 * **One draft, two views** (`FE-124`, `UI-D1`). A package composing more
 * than one component gets the `Composer` canvas instead of the flat
 * section list — never a second `useDesignerDraft`. `Composer` takes the
 * SAME `getInstanceValues`/`onFieldChange` this file's own single
 * `useDesignerDraft` call already produces, so switching between canvas
 * and list can never lose an edit or diverge from the save payload.
 *
 * **Field ownership across components (single-component / flat-list path
 * only).** `ViewSection` carries no component-instance identity
 * (`SectionNav`'s own module doc), so the flat list renders ONE combined
 * `project()` call across the whole `componentSelection` — correct for
 * Cache (single component) and for any package whose field keys are
 * unique across the whole package (both shipped packages are).
 * `fieldOwner` below is what lets a single `onFieldChange` route an edit
 * to the component instance that actually declares the key. `Composer`
 * itself needs no such map — it scopes `project()` per component instance
 * internally.
 *
 * **`G11` is the rollout/apply acceptance criterion.** `ApplyOutcomePanel`
 * reports what was OBSERVED — config written, a restart seen — never that
 * the process is using it. No stronger verdict is available:
 * `DECISIONS.md` open item 1 means there is no `dp-remote` apply protocol
 * with generation ordering or idempotency, so `applyRepository` resolves
 * to `INDETERMINATE` and nothing else. The saved generation is applied to
 * the selected placement as one package configuration, regardless of
 * whether the package is rendered as a flat form or the multi-component
 * Composer canvas.
 */
import { Box } from '@mui/material';
import { useEffect, useMemo, useState, type ReactElement } from 'react';

import { project } from '@contracts/view-model/project';
import type { ComponentSelection, DraftValues, FieldValue, SolutionPackage } from '@contracts/view-model/types';
import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Dialog } from '@shared/ui';
import type { ConfigLadderReachedKey } from '@shared/vocab';

import { AcceptRuntimeDialog } from '../AcceptRuntimeDialog';
import type { ActionsRepository } from '../actionsRepository';
import { ApplyOutcomePanel } from '../ApplyOutcomePanel';
import { confirmActual } from '../applyRepository';
import type { ApplyOutcome, ApplyRepository } from '../applyRepository';
import { BlockedBanner } from '../BlockedBanner';
import { Composer } from '../Composer';
import type { ConfigRepository, DesignerLoad, SaveConfigResult } from '../configRepository';
import { ConflictDialog } from '../ConflictDialog';
import { ConnectionDriftBanner, detectDrift } from '../ConnectionDriftBanner';
import { DivergedBanner } from '../DivergedBanner';
import { FieldSearch, useFieldSearch } from '../FieldSearch';
import { useDesignerDraft } from '../form/useDesignerDraft';
import { PackageActions } from '../PackageActions';
import { RestartConfirm } from '../RestartConfirm';
import { RESTART_STRATEGIES } from '../restartStrategies';
import { RestoreRuntimeDialog } from '../RestoreRuntimeDialog';
import { RollbackDialog } from '../RollbackDialog';
import { RolloutCard } from '../RolloutCard';
import { SaveBar } from '../SaveBar';
import { SectionCard } from '../SectionCard';
import { SectionNav, useSectionJump } from '../SectionNav';
import { StrategyPicker } from '../StrategyPicker';
import { ValidationSummary } from '../ValidationSummary';
import { collectValidationEntries } from '../ValidationSummary/collectValidationEntries';
import { VersionPill } from '../VersionPill';
import { draftValuesFromYaml, yamlFromFields, type YamlFieldRef } from '../yamlConfiguration';
import { YamlEditor } from '../YamlEditor/YamlEditor';

import { isEditingBlocked } from './observedState';

export interface DesignerFormProps {
  instanceId: string;
  /** Accept/restore-runtime's `placementId` (`BE-D23`) — `undefined` disables both dialogs' confirm actions. */
  placementId: string | undefined;
  load: DesignerLoad;
  componentSelection: ComponentSelection;
  configRepository: ConfigRepository;
  applyRepository: ApplyRepository;
  actionsRepository: ActionsRepository;
  /** Replaces the baseline this component is keyed on — a new generation, remount incoming (`FE-D20`). */
  onLoadChange: (load: DesignerLoad) => void;
  /** Re-runs `DesignerPage`'s own query — the only path `AcceptRuntimeDialog`/`RestoreRuntimeDialog` have back to fresh `observed` state; neither dialog polls. */
  onRefetch: () => void;
}

function fieldOwnerMap(pkg: SolutionPackage, componentSelection: ComponentSelection): ReadonlyMap<string, string> {
  const owners = new Map<string, string>();
  for (const selection of componentSelection) {
    const component = pkg.components.find((candidate) => candidate.type === selection.type);
    for (const field of component?.fields ?? []) {
      owners.set(field.key, selection.instanceId);
    }
  }
  return owners;
}

export function DesignerForm({
  instanceId,
  placementId,
  load,
  componentSelection,
  configRepository,
  applyRepository,
  actionsRepository,
  onLoadChange,
  onRefetch,
}: DesignerFormProps): ReactElement {
  const draft = useDesignerDraft({
    pkg: load.package,
    componentSelection,
    observedState: load.observed,
    initialDraft: load.values,
    packageVersion: load.packageVersion,
    instanceRole: load.role,
    catalog: load.connectionCatalog === undefined ? {} : { connection: load.connectionCatalog },
  });
  const { viewModel } = draft;
  const blocked = isEditingBlocked(load.observed);

  const owners = useMemo(
    () => fieldOwnerMap(load.package, componentSelection),
    [load.package, componentSelection],
  );
  const mergedValues = useMemo(() => {
    const values: Record<string, FieldValue> = {};
    for (const selection of componentSelection) {
      Object.assign(values, draft.getInstanceValues(selection.instanceId));
    }
    return values;
  }, [componentSelection, draft]);
  const handleFieldChange = (key: string, value: FieldValue) => {
    const owner = owners.get(key) ?? componentSelection[0]?.instanceId;
    if (owner === undefined) {
      return;
    }
    draft.onFieldChange(owner, key, value);
  };

  const { containerRef, isExpanded, toggle, jumpToSection, jumpToField } = useSectionJump(
    viewModel.sections,
  );
  const fieldsByConnection = useMemo(() => {
    const result: Record<string, string[]> = {};
    for (const selection of componentSelection) {
      const values = draft.getInstanceValues(selection.instanceId);
      const component = load.package.components.find((candidate) => candidate.type === selection.type);
      for (const field of component?.fields ?? []) {
        if (field.valueFrom === undefined) continue;
        const connectionId = values[field.valueFrom.ref];
        if (typeof connectionId !== 'string') continue;
        (result[connectionId] ??= []).push(field.key);
      }
    }
    return result;
  }, [componentSelection, draft, load.package.components]);
  const connectionDrift = useMemo(
    () =>
      detectDrift(
        load.appliedConnectionRevisions ?? {},
        (load.connectionCatalog ?? []).map((entry) => ({
          id: entry.id,
          name: entry.label,
          revision: entry.revision,
        })),
        fieldsByConnection,
      ),
    [fieldsByConnection, load.appliedConnectionRevisions, load.connectionCatalog],
  );
  // `fieldKey -> (connection id -> name)` for every connection SELECTOR the package declares. The
  // engine's `options` for these are catalog ids; this is the display side (see
  // `FieldRowProps.optionLabels`). Only entries of the selector's own `connectionType` are listed.
  const connectionOptionLabels = useMemo(() => {
    const result: Record<string, Record<string, string>> = {};
    for (const selection of componentSelection) {
      const component = load.package.components.find((candidate) => candidate.type === selection.type);
      for (const field of component?.fields ?? []) {
        if (field.optionsSource?.entity !== 'connection') continue;
        const wanted = field.optionsSource.connectionType;
        result[field.key] = Object.fromEntries(
          (load.connectionCatalog ?? [])
            .filter((entry) => wanted === undefined || entry.connectionType === wanted)
            .map((entry) => [entry.id, entry.label]),
        );
      }
    }
    return result;
  }, [componentSelection, load.package.components, load.connectionCatalog]);
  const fieldLabel = (key: string) =>
    viewModel.sections.flatMap((section) => section.fields).find((field) => field.key === key)?.label ?? key;
  const reviewDrift = (fieldKey: string) => {
    const sectionIndex = viewModel.sections.findIndex((section) =>
      section.fields.some((field) => field.key === fieldKey),
    );
    if (sectionIndex >= 0) jumpToField(sectionIndex, fieldKey);
  };
  const search = useFieldSearch(viewModel.sections);
  const { blocks, warns } = collectValidationEntries(viewModel.sections);

  const yamlFields = useMemo<readonly YamlFieldRef[]>(() => {
    const fields: YamlFieldRef[] = [];
    const catalog = load.connectionCatalog === undefined ? {} : { connection: load.connectionCatalog };
    for (const selection of componentSelection) {
      const scoped = project(
        load.package,
        [selection],
        { [selection.instanceId]: draft.getInstanceValues(selection.instanceId) },
        load.observed,
        load.packageVersion,
        catalog,
        load.role,
      );
      for (const section of scoped.sections) {
        for (const field of section.fields) {
          if (!field.visible || field.renderedPath === null) continue;
          fields.push({ instanceId: selection.instanceId, field });
        }
      }
    }
    return fields;
  }, [componentSelection, draft, load]);

  const [configurationView, setConfigurationView] = useState<'form' | 'yaml'>('form');
  const [yamlText, setYamlText] = useState('');
  const [yamlError, setYamlError] = useState<string | undefined>(undefined);
  const [yamlOmittedSecrets, setYamlOmittedSecrets] = useState(0);

  const openYamlView = () => {
    const serialized = yamlFromFields(yamlFields);
    setYamlText(serialized.text);
    setYamlOmittedSecrets(serialized.omittedSecrets);
    setYamlError(undefined);
    setConfigurationView('yaml');
  };
  const handleYamlChange = (nextText: string) => {
    setYamlText(nextText);
    const parsed = draftValuesFromYaml(nextText, yamlFields);
    setYamlError(parsed.error);
    if (parsed.error === undefined) {
      draft.applyValues(parsed.values);
    }
  };
  const handleYamlCopy = () => {
    void navigator.clipboard?.writeText(yamlText);
  };
  const handleYamlPaste = () => {
    void navigator.clipboard?.readText().then(handleYamlChange).catch(() => {
      setYamlError(t('designerYamlPasteError'));
    });
  };

  const [conflictOpen, setConflictOpen] = useState(false);
  const [runtimeModal, setRuntimeModal] = useState<'accept' | 'restore' | null>(null);

  // ---- Rollout / apply (FE-124, placement-wide saved generation) ----
  const [applyStep, setApplyStep] = useState<'strategy' | 'restart' | null>(null);
  const [strategy, setStrategy] = useState(RESTART_STRATEGIES[0]?.key ?? 'manual');
  const [applyOutcome, setApplyOutcome] = useState<ApplyOutcome>({ kind: 'NOT_STARTED' });
  const [rollbackOpen, setRollbackOpen] = useState(false);

  const isMultiComponent = componentSelection.length > 1;
  const diverged = load.observed.configState === 'diverged' || applyOutcome.kind === 'DIVERGED';
  const observedReplicas =
    applyOutcome.kind === 'NOT_STARTED' || applyOutcome.kind === 'IN_FLIGHT' ? [] : applyOutcome.replicas;
  // Never claims more than `project()`/`applyRepository` themselves know:
  // 'desired' until THIS session's own apply flow reports a restart was
  // observed — `restart-observed` is exactly what `INDETERMINATE` means
  // (G11), never inferred from `configState` (no stated mapping exists).
  const reached: ConfigLadderReachedKey =
    applyOutcome.kind === 'INDETERMINATE' ||
    applyOutcome.kind === 'CONFIRMED_BY_OPERATOR' ||
    applyOutcome.kind === 'DIVERGED'
      ? 'restart-observed'
      : 'desired';
  const selectedStrategy = RESTART_STRATEGIES.find((candidate) => candidate.key === strategy);
  const parsedGeneration =
    load.configGeneration === ''
      ? undefined
      : Number.isSafeInteger(Number(load.configGeneration))
        ? Number(load.configGeneration)
        : Number(load.configGeneration.match(/(\d+)$/)?.[1]);
  const applyGeneration = parsedGeneration !== undefined && Number.isSafeInteger(parsedGeneration)
    ? parsedGeneration
    : undefined;

  useEffect(() => {
    if (applyGeneration === undefined || applyRepository.observeLatest === undefined) {
      return;
    }

    let active = true;
    void applyRepository
      .observeLatest(instanceId, applyGeneration)
      .then((outcome) => {
        if (active && outcome !== undefined) {
          setApplyOutcome(outcome);
        }
      })
      // A missing/unavailable observation must not block Designer editing. The
      // next explicit Apply still reports its own result in the panel.
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, [applyGeneration, applyRepository, instanceId]);

  const applyAction =
    placementId !== undefined && applyGeneration !== undefined
      ? { onApply: () => setApplyStep('strategy') }
      : {};

  // Plan 008 Task 2: `409` (stale `stateVersion`) and `422` (the server's own re-projection
  // disagrees with this client's `rendered[]`) are DISTINCT statuses, never folded into one
  // generic save error — `422` gets its own "stale base, reload" state below, not `ConflictDialog`
  // (which is specifically the "someone else saved first" diff/refetch flow, a different story).
  const [staleProjectionOpen, setStaleProjectionOpen] = useState(false);
  const handleSaveError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 409) {
      setConflictOpen(true);
    } else if (error instanceof ApiError && error.status === 422) {
      setStaleProjectionOpen(true);
    }
  };

  const handleSaved = (result: SaveConfigResult) => {
    const nextValues: Record<string, DraftValues[string]> = {};
    for (const selection of componentSelection) {
      nextValues[selection.instanceId] = draft.getInstanceValues(selection.instanceId);
    }
    onLoadChange({
      ...load,
      values: nextValues,
      configGeneration: String(result.generation),
      stateVersion: result.stateVersion,
    });
  };

  const showRenderedPath = componentSelection.length === 1;

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 2 }}>
        <Chip
          tone="neutral"
          label={t('designerPackageVersionChip', { version: String(load.packageVersion) })}
        />
        <VersionPill status={viewModel.status} />
        <Box sx={{ display: 'flex', gap: 0.5, ml: 'auto' }} role="group" aria-label={t('designerConfigurationViewLabel')}>
          <Button
            variant={configurationView === 'form' ? 'contained' : 'outlined'}
            size="sm"
            onClick={() => setConfigurationView('form')}
          >
            {t('designerFormView')}
          </Button>
          <Button
            variant={configurationView === 'yaml' ? 'contained' : 'outlined'}
            size="sm"
            onClick={openYamlView}
          >
            {t('designerYamlView')}
          </Button>
        </Box>
      </Box>

      {configurationView === 'yaml' ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <BlockedBanner
            status={viewModel.status}
            onAccept={() => setRuntimeModal('accept')}
            onRestore={() => setRuntimeModal('restore')}
          />
          <YamlEditor
            value={yamlText}
            error={yamlError}
            omittedSecrets={yamlOmittedSecrets}
            disabled={blocked}
            onChange={handleYamlChange}
            onCopy={handleYamlCopy}
            onPaste={handleYamlPaste}
          />
          <PackageActions
            instanceId={instanceId}
            sections={viewModel.sections}
            repository={actionsRepository}
            disabled={blocked}
          />
          <ApplyOutcomePanel
            outcome={applyOutcome}
            onConfirm={() => setApplyOutcome(confirmActual(applyOutcome, new Date().toISOString()))}
            onRetry={() => setApplyStep('strategy')}
          />
          <RolloutCard replicas={observedReplicas} reached={reached} diverged={diverged} />
          {blocked ? null : (
            <SaveBar
              instanceId={instanceId}
              repository={configRepository}
              payload={draft.payload}
              baseStateVersion={load.stateVersion}
              componentSelection={componentSelection}
              rendered={draft.rendered}
              dirtyCount={draft.dirtyInstanceCount}
              blockingCount={blocks.length + (yamlError === undefined ? 0 : 1)}
              warnings={warns}
              onSaved={handleSaved}
              onSaveError={handleSaveError}
              {...applyAction}
            />
          )}
        </Box>
      ) : isMultiComponent ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <BlockedBanner
            status={viewModel.status}
            onAccept={() => setRuntimeModal('accept')}
            onRestore={() => setRuntimeModal('restore')}
          />

          <ConnectionDriftBanner
            entries={connectionDrift}
            fieldLabel={fieldLabel}
            onReviewAndSave={(entry) => reviewDrift(entry.affectedFields[0] ?? '')}
          />

          <Box sx={{ height: 640, border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}>
            <Composer
              pkg={load.package}
              componentSelection={componentSelection}
              packageVersion={load.packageVersion}
              instanceRole={load.role}
              getInstanceValues={draft.getInstanceValues}
              observedState={load.observed}
              onFieldChange={draft.onFieldChange}
              catalog={load.connectionCatalog === undefined ? {} : { connection: load.connectionCatalog }}
              optionLabelsByField={connectionOptionLabels}
              disabled={blocked}
            />
          </Box>

          <PackageActions
            instanceId={instanceId}
            sections={viewModel.sections}
            repository={actionsRepository}
            disabled={blocked}
          />

          <Box sx={{ mt: 2 }}>
            <ApplyOutcomePanel
              outcome={applyOutcome}
              onConfirm={() => setApplyOutcome(confirmActual(applyOutcome, new Date().toISOString()))}
              onRetry={() => setApplyStep('strategy')}
            />
          </Box>

          <Box sx={{ mt: 2 }}>
            <RolloutCard replicas={observedReplicas} reached={reached} diverged={diverged} />
          </Box>

          <Box sx={{ height: 8 }} />

          {blocked ? null : (
            <SaveBar
              instanceId={instanceId}
              repository={configRepository}
              payload={draft.payload}
              baseStateVersion={load.stateVersion}
              componentSelection={componentSelection}
              rendered={draft.rendered}
              dirtyCount={draft.dirtyInstanceCount}
              blockingCount={blocks.length}
              warnings={warns}
              onSaved={handleSaved}
              onSaveError={handleSaveError}
              {...applyAction}
            />
          )}
        </Box>
      ) : (
        <Box
          sx={{
            display: 'flex',
            gap: 3,
            alignItems: 'flex-start',
          }}
        >
          {/* `SectionNav` owns its own responsive behaviour end to end (task
              108): a persistent rail at `lg` and up, a "Jump to section"
              button opening a `Drawer` below it — down to the 320px floor.
              This wrapper only adapts ITS layout to match, rather than
              hiding the component and duplicating (and, at 320px,
              breaking) a responsive decision `SectionNav` already owns. */}
          <Box sx={{ position: { lg: 'sticky' }, top: 16, flex: { xs: '0 0 auto', lg: '0 0 240px' }, minWidth: 0 }}>
            <SectionNav sections={viewModel.sections} onJump={jumpToSection} />
          </Box>

          <Box
            ref={containerRef}
            sx={{ flex: '1 1 auto', minWidth: 0, display: 'flex', flexDirection: 'column' }}
          >
            <BlockedBanner
              status={viewModel.status}
              onAccept={() => setRuntimeModal('accept')}
              onRestore={() => setRuntimeModal('restore')}
            />

            <Box sx={{ mt: connectionDrift.length === 0 ? 0 : 1.5 }}>
              <ConnectionDriftBanner
                entries={connectionDrift}
                fieldLabel={fieldLabel}
                onReviewAndSave={(entry) => reviewDrift(entry.affectedFields[0] ?? '')}
              />
            </Box>

            {diverged ? (
              <Box sx={{ mt: 1.5 }}>
                <DivergedBanner
                  onRestage={() => {
                    if (placementId !== undefined && applyGeneration !== undefined) {
                      void applyRepository.stage(instanceId, placementId, applyGeneration, 'manual');
                    }
                  }}
                  onRestart={() => setApplyStep('restart')}
                  onRollback={() => setRollbackOpen(true)}
                />
              </Box>
            ) : null}

            {blocked ? null : (
              <Box sx={{ mt: blocked ? 0 : 1.5 }}>
                <ValidationSummary sections={viewModel.sections} onJump={jumpToField} />
              </Box>
            )}

            <Box sx={{ mt: 2 }}>
              <FieldSearch
                query={search.query}
                onQueryChange={search.setQuery}
                totalFieldCount={search.totalFieldCount}
                hits={search.hits}
                values={mergedValues}
                onFieldChange={handleFieldChange}
                disabled={blocked}
              />
            </Box>

            {search.isActive ? null : (
              <Box sx={{ mt: 1.5, display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                {viewModel.sections.map((section, index) => (
                  <SectionCard
                    key={section.title}
                    section={section}
                    values={mergedValues}
                    onFieldChange={handleFieldChange}
                    disabled={blocked}
                    expanded={isExpanded(index)}
                    onToggleExpanded={() => toggle(index)}
                    showRenderedPath={showRenderedPath}
                    optionLabelsByField={connectionOptionLabels}
                  />
                ))}
              </Box>
            )}

            <Box sx={{ mt: 2 }}>
              <PackageActions
                instanceId={instanceId}
                sections={viewModel.sections}
                repository={actionsRepository}
                disabled={blocked}
              />
            </Box>

            <Box sx={{ mt: 2 }}>
              <ApplyOutcomePanel
                outcome={applyOutcome}
                onConfirm={() => setApplyOutcome(confirmActual(applyOutcome, new Date().toISOString()))}
                onRetry={() => setApplyStep('strategy')}
              />
            </Box>

            <Box sx={{ mt: 2 }}>
              <RolloutCard replicas={observedReplicas} reached={reached} diverged={diverged} />
            </Box>

            <Box sx={{ height: 8 }} />

            {blocked ? null : (
              <SaveBar
                instanceId={instanceId}
                repository={configRepository}
                payload={draft.payload}
                baseStateVersion={load.stateVersion}
                componentSelection={componentSelection}
                rendered={draft.rendered}
                dirtyCount={draft.dirtyInstanceCount}
                blockingCount={blocks.length}
                warnings={warns}
                onSaved={handleSaved}
                onSaveError={handleSaveError}
                {...applyAction}
              />
            )}
          </Box>
        </Box>
      )}

      {staleProjectionOpen ? (
        <Box sx={{ mt: 1.5 }}>
          <Alert
            severity="err"
            title={t('designerStaleProjectionTitle')}
            fullWidth
            action={
              <Button
                variant="outlined"
                onClick={() => {
                  setStaleProjectionOpen(false);
                  onRefetch();
                }}
              >
                {t('designerStaleProjectionReload')}
              </Button>
            }
          >
            {t('designerStaleProjectionDetail')}
          </Alert>
        </Box>
      ) : null}

      <ConflictDialog
        open={conflictOpen}
        instanceId={instanceId}
        repository={configRepository}
        // No endpoint reports "your draft vs the current record" (§16 Q9) —
        // the dialog still explains the conflict and offers refetch; an
        // empty diff is the honest answer, never a fabricated one.
        rows={[]}
        onClose={() => setConflictOpen(false)}
        onRefetched={(refetched) => {
          setConflictOpen(false);
          onLoadChange(refetched);
        }}
      />

      <AcceptRuntimeDialog
        open={runtimeModal === 'accept'}
        instanceId={instanceId}
        placementId={placementId}
        baseStateVersion={load.stateVersion}
        repository={configRepository}
        onClose={() => setRuntimeModal(null)}
        onAccepted={() => {
          setRuntimeModal(null);
          onRefetch();
        }}
      />

      <RestoreRuntimeDialog
        open={runtimeModal === 'restore'}
        instanceId={instanceId}
        placementId={placementId}
        declaredVersion={load.observed.declaredVersion ?? ''}
        repository={configRepository}
        onCancel={() => setRuntimeModal(null)}
        onRestored={() => {
          setRuntimeModal(null);
          onRefetch();
        }}
      />

      <>
          <Dialog
            open={applyStep === 'strategy'}
            title={t('designerApplyStrategyDialogTitle')}
            onClose={() => setApplyStep(null)}
            actions={
              <>
                <Button variant="outlined" onClick={() => setApplyStep(null)}>
                  {t('cancel')}
                </Button>
                <Button onClick={() => setApplyStep('restart')}>{t('designerApplyDialogContinue')}</Button>
              </>
            }
          >
            <StrategyPicker
              strategies={RESTART_STRATEGIES}
              value={strategy}
              onChange={setStrategy}
              // MI-specific facts with no home in `SolutionPackage`/`ViewModel`
              // (`StrategyPicker.test.tsx`'s own precedent) — the CONSERVATIVE
              // reading: config must be uniform, no health mapping is
              // declared, which withholds the health-gated strategy rather
              // than offering one nothing can actually gate.
              requiresUniformConfig
              healthMappingDeclared={false}
            />
          </Dialog>

          {selectedStrategy && placementId !== undefined && applyGeneration !== undefined ? (
            <RestartConfirm
              open={applyStep === 'restart'}
              instanceId={instanceId}
              placementId={placementId}
              generation={applyGeneration}
              strategy={selectedStrategy}
              replicas={observedReplicas}
              repository={applyRepository}
              onCancel={() => setApplyStep(null)}
              onApplied={(outcome) => {
                setApplyOutcome(outcome);
                setApplyStep(null);
              }}
            />
          ) : null}

          <RollbackDialog
            open={rollbackOpen}
            instanceId={instanceId}
            currentGeneration={load.configGeneration}
            // No rollback endpoint names the target generation (§16 Q9/Q12
            // adjacent — no source, same gap `ConflictDialog`'s own `rows`
            // documents) — left empty rather than fabricated.
            targetGeneration=""
            rows={[]}
            repository={applyRepository}
            onCancel={() => setRollbackOpen(false)}
            onApplied={(outcome) => {
              setApplyOutcome(outcome);
              setRollbackOpen(false);
            }}
          />
      </>
    </Box>
  );
}
