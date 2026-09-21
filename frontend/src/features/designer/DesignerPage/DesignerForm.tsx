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
 * to `INDETERMINATE` and nothing else. Rollout/apply is offered only for
 * a single-component solution — a package composing several components
 * (Composer canvas) has no per-component rollout story any decision here
 * defines, so it is not invented; the same posture `FE-115`'s declare form
 * takes toward one-of-many composition.
 */
import { Box } from '@mui/material';
import { useMemo, useState, type ReactElement } from 'react';

import type { ComponentSelection, DraftValues, FieldValue, SolutionPackage } from '@contracts/view-model/types';
import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Button, Chip, Dialog } from '@shared/ui';
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

import { isEditingBlocked } from './observedState';

export interface DesignerFormProps {
  instanceId: string;
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
  const search = useFieldSearch(viewModel.sections);
  const { blocks, warns } = collectValidationEntries(viewModel.sections);

  const [conflictOpen, setConflictOpen] = useState(false);
  const [runtimeModal, setRuntimeModal] = useState<'accept' | 'restore' | null>(null);

  // ---- Rollout / apply (FE-124, single-component only — see module header) ----
  const [applyStep, setApplyStep] = useState<'strategy' | 'restart' | null>(null);
  const [strategy, setStrategy] = useState(RESTART_STRATEGIES[0]?.key ?? 'manual');
  const [applyOutcome, setApplyOutcome] = useState<ApplyOutcome>({ kind: 'NOT_STARTED' });
  const [rollbackOpen, setRollbackOpen] = useState(false);

  const isMultiComponent = componentSelection.length > 1;
  const diverged = load.observed.configState === 'diverged' || applyOutcome.kind === 'DIVERGED';
  const observedReplicas =
    applyOutcome.kind === 'NOT_STARTED' || applyOutcome.kind === 'IN_FLIGHT' ? [] : applyOutcome.replicas;
  // Never infer a restart from staging alone; it is reached only after this
  // session's separately authenticated restart request returns.
  const reached: ConfigLadderReachedKey =
    (applyOutcome.kind === 'INDETERMINATE' && applyOutcome.restartObserved === true) ||
    applyOutcome.kind === 'CONFIRMED_BY_OPERATOR' ||
    applyOutcome.kind === 'DIVERGED'
      ? 'restart-observed'
      : 'desired';
  const selectedStrategy = RESTART_STRATEGIES.find((candidate) => candidate.key === strategy);

  const handleSaveError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 409) {
      setConflictOpen(true);
    }
  };

  const handleSaved = (result: SaveConfigResult) => {
    const nextValues: Record<string, DraftValues[string]> = {};
    for (const selection of componentSelection) {
      nextValues[selection.instanceId] = draft.getInstanceValues(selection.instanceId);
    }
    onLoadChange({ ...load, values: nextValues, configGeneration: result.generation });
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
      </Box>

      {isMultiComponent ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <BlockedBanner
            status={viewModel.status}
            onAccept={() => setRuntimeModal('accept')}
            onRestore={() => setRuntimeModal('restore')}
          />

          <Box sx={{ height: 640, border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}>
            <Composer
              pkg={load.package}
              componentSelection={componentSelection}
              packageVersion={load.packageVersion}
              getInstanceValues={draft.getInstanceValues}
              observedState={load.observed}
              onFieldChange={draft.onFieldChange}
              disabled={blocked}
            />
          </Box>

          <PackageActions
            instanceId={instanceId}
            sections={viewModel.sections}
            repository={actionsRepository}
            disabled={blocked}
          />

          <Box sx={{ height: 8 }} />

          {blocked ? null : (
            <SaveBar
              instanceId={instanceId}
              repository={configRepository}
              payload={draft.payload}
              generation={load.configGeneration}
              dirtyCount={draft.dirtyInstanceCount}
              blockingCount={blocks.length}
              warnings={warns}
              onSaved={handleSaved}
              onSaveError={handleSaveError}
              // No rollout/apply story for a multi-component solution
              // (module header) — `onApply` stays unset, so Apply renders
              // enabled-looking at `dirtyCount === 0` but does nothing,
              // rather than inventing a composed-instance rollout.
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

            {diverged ? (
              <Box sx={{ mt: 1.5 }}>
                <DivergedBanner
                  onRestage={() => void applyRepository.stage(instanceId, 'manual')}
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
                generation={load.configGeneration}
                dirtyCount={draft.dirtyInstanceCount}
                blockingCount={blocks.length}
                warnings={warns}
                onSaved={handleSaved}
                onSaveError={handleSaveError}
                onApply={() => setApplyStep('strategy')}
              />
            )}
          </Box>
        </Box>
      )}

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
        toVersion={load.observed.observedVersion ?? ''}
        repository={configRepository}
        rows={[]}
        onClose={() => setRuntimeModal(null)}
        onAccepted={() => {
          setRuntimeModal(null);
          onRefetch();
        }}
      />

      <RestoreRuntimeDialog
        open={runtimeModal === 'restore'}
        instanceId={instanceId}
        declaredVersion={load.observed.declaredVersion ?? ''}
        repository={configRepository}
        onCancel={() => setRuntimeModal(null)}
        onRestored={() => {
          setRuntimeModal(null);
          onRefetch();
        }}
      />

      {isMultiComponent ? null : (
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

          {selectedStrategy ? (
            <RestartConfirm
              open={applyStep === 'restart'}
              instanceId={instanceId}
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
      )}
    </Box>
  );
}
