/**
 * The composer canvas (task 063, Dec `UI-D20` `UI-D21` (flagged) `D1` 🔒,
 * design §10, UI §A.8.18): three panes for a multi-component solution
 * (File Mesh) — palette, canvas, inspector — plus the flat "All fields"
 * list as the required non-pointer path. Cache (single-component) has
 * nothing for this to add and stays on the plain form; callers gate on
 * `isComposerEligible` (`nodeCanvas.ts`) before rendering this at all —
 * this component also bails defensively for the same reason, since a
 * one-node canvas with no derived link would be a worse experience than
 * the flat form it's a wrapper around.
 *
 * Node positions are local, presentation-only state (`UI-D20`): they never
 * read or write `DraftValues`, and moving a node never calls
 * `onFieldChange`, so it can never mark the form dirty or enter the
 * config hash.
 *
 * **No mobile canvas** (task 108, `FE-D29`): below `md` there is no room
 * for a three-pane grid, and inventing one is explicitly out of scope —
 * the plan's own words are "reuses a required affordance rather than
 * inventing a mobile canvas." `effectiveView` forces `'list'` regardless
 * of the operator's own toggle choice (shrinking the window while parked
 * on the canvas view lands on the list, not a broken grid), and the view
 * toggle itself is replaced by a stated reason — an inert, disabled
 * toggle would invite a tap that does nothing.
 */
import { Box, useMediaQuery, useTheme } from '@mui/material';
import type { ReactElement } from 'react';
import { useMemo, useState } from 'react';

import { project } from '@contracts/view-model/project';
import type { Catalog, ObservedState, PackageVersionId } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Alert, InfoTip, ToggleGroup } from '@shared/ui';

import { groupSectionFields } from '../SectionCard/sectionFieldGroups';

import type { ComposerProps } from './Composer.types';
import { ComposerFieldList } from './ComposerFieldList';
import { ComposerInspector } from './ComposerInspector';
import { ComposerNode } from './ComposerNode';
import { ComposerPalette } from './ComposerPalette';
import { ComposerResizeHandle } from './ComposerResizeHandle';
import {
  defaultNodePositions,
  derivedLinks,
  isComposerEligible,
  type NodePositions,
} from './nodeCanvas';

const CANVAS_LINK_PORT_OFFSET = { fromRight: 220, toLeft: 0, verticalCenter: 34 } as const;

// Resizable palette/inspector panes (Designer width/resize follow-up).
// Defaults match this canvas's original fixed widths, so nothing shifts
// on first load; min/max keep the canvas middle pane usable at either
// extreme. Widths are local presentation state, same as node positions.
const PALETTE_WIDTH_DEFAULT = 210;
const PALETTE_WIDTH_MIN = 160;
const PALETTE_WIDTH_MAX = 360;
const INSPECTOR_WIDTH_DEFAULT = 400;
const INSPECTOR_WIDTH_MIN = 280;
const INSPECTOR_WIDTH_MAX = 560;

type ComposerView = 'canvas' | 'list';

interface NodeStat {
  fieldCount: number;
  requiredEmptyCount: number;
}

function computeNodeStats(
  pkg: ComposerProps['pkg'],
  componentSelection: ComposerProps['componentSelection'],
  getInstanceValues: ComposerProps['getInstanceValues'],
  observedState: ObservedState,
  packageVersion: PackageVersionId,
  catalog: Catalog,
  instanceRole?: string,
): ReadonlyMap<string, NodeStat> {
  const stats = new Map<string, NodeStat>();
  for (const entry of componentSelection) {
    const viewModel = project(
      pkg,
      [entry],
      { [entry.instanceId]: getInstanceValues(entry.instanceId) },
      observedState,
      packageVersion,
      catalog,
      instanceRole,
    );
    let fieldCount = 0;
    let requiredEmptyCount = 0;
    for (const section of viewModel.sections) {
      const groups = groupSectionFields(section);
      fieldCount += groups.applicable.length;
      requiredEmptyCount += groups.blockCount;
    }
    stats.set(entry.instanceId, { fieldCount, requiredEmptyCount });
  }
  return stats;
}

export function Composer({
  pkg,
  componentSelection,
  packageVersion,
  instanceRole,
  catalog = {},
  optionLabelsByField = {},
  getInstanceValues,
  observedState,
  onFieldChange,
  disabled = false,
}: ComposerProps): ReactElement | null {
  const theme = useTheme();
  const canvasUnavailable = useMediaQuery(theme.breakpoints.down('md'));
  const [view, setView] = useState<ComposerView>('canvas');
  const effectiveView: ComposerView = canvasUnavailable ? 'list' : view;
  // Nothing selected by default — the inspector only opens once an
  // operator picks a node or a palette type, rather than pre-selecting
  // the first composed instance.
  const [selectedInstanceId, setSelectedInstanceId] = useState<string | undefined>(undefined);
  const [nodePositions, setNodePositions] = useState<NodePositions>(() =>
    defaultNodePositions(componentSelection),
  );
  const [paletteWidth, setPaletteWidth] = useState(PALETTE_WIDTH_DEFAULT);
  const [inspectorWidth, setInspectorWidth] = useState(INSPECTOR_WIDTH_DEFAULT);

  // Adjusted during render, not in an effect (react-hooks/set-state-in-
  // effect) — the documented "you might not need an effect" pattern for
  // keeping state in sync with a changed prop: compute what this render
  // needs right here, and only call the setter when something actually
  // changed, so it settles on the very next pass without an extra
  // commit-then-effect round trip.
  //
  // Adding a position for a newly-composed instance never disturbs one
  // the operator already dragged for an instance still present — this is
  // never a wholesale reset.
  const missingPositions = componentSelection.filter(
    (entry) => !(entry.instanceId in nodePositions),
  );
  const effectiveNodePositions: NodePositions =
    missingPositions.length === 0
      ? nodePositions
      : { ...nodePositions, ...defaultNodePositions(missingPositions) };
  if (missingPositions.length > 0) {
    setNodePositions(effectiveNodePositions);
  }

  const stillSelected =
    selectedInstanceId !== undefined &&
    componentSelection.some((entry) => entry.instanceId === selectedInstanceId);
  const effectiveSelectedInstanceId = stillSelected ? selectedInstanceId : undefined;
  if (selectedInstanceId !== undefined && !stillSelected) {
    setSelectedInstanceId(undefined);
  }

  const nodeStats = useMemo(
    () => computeNodeStats(pkg, componentSelection, getInstanceValues, observedState, packageVersion, catalog, instanceRole),
    [pkg, componentSelection, getInstanceValues, observedState, packageVersion, catalog, instanceRole],
  );
  const links = useMemo(() => derivedLinks(componentSelection), [componentSelection]);
  const selectedEntry = componentSelection.find(
    (entry) => entry.instanceId === effectiveSelectedInstanceId,
  );

  // The palette must respect the same role/binding scope the canvas nodes
  // and inspector already do (`deriveComponentSelection`, `FE-D55`) — never
  // the package's full component list. A package can declare more types
  // than one instance composes (File Mesh: several source variants, one
  // sink), and showing an out-of-role type here would contradict what the
  // canvas itself renders for the very same instance.
  const paletteComponents = useMemo(() => {
    const selected = new Set(componentSelection.map((entry) => entry.type));
    return pkg.components.filter((component) => selected.has(component.type));
  }, [pkg.components, componentSelection]);

  if (!isComposerEligible(componentSelection)) {
    return null;
  }

  function handleSelectType(type: string): void {
    const match = componentSelection.find((entry) => entry.type === type);
    if (match !== undefined) {
      setSelectedInstanceId(match.instanceId);
    }
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <Box
        sx={{
          display: 'flex',
          justifyContent: canvasUnavailable ? 'stretch' : 'flex-end',
          p: 1,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        {canvasUnavailable ? (
          <Alert severity="info" fullWidth>
            {t('composerCanvasUnavailableReason')}
          </Alert>
        ) : (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            {effectiveView === 'canvas' ? <InfoTip tip={t('composerCanvasHint')} /> : null}
            <ToggleGroup
              aria-label={t('composerViewToggleLabel')}
              value={view}
              onChange={setView}
              options={[
                { value: 'canvas', label: t('composerViewCanvas') },
                { value: 'list', label: t('composerViewList') },
              ]}
            />
          </Box>
        )}
      </Box>

      {effectiveView === 'list' ? (
        <ComposerFieldList
          pkg={pkg}
          componentSelection={componentSelection}
          packageVersion={packageVersion}
          getInstanceValues={getInstanceValues}
          observedState={observedState}
          onFieldChange={onFieldChange}
          catalog={catalog}
          optionLabelsByField={optionLabelsByField}
          disabled={disabled}
        />
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns:
              selectedEntry !== undefined
                ? `${String(paletteWidth)}px 6px 1fr 6px ${String(inspectorWidth)}px`
                : `${String(paletteWidth)}px 6px 1fr`,
            flex: 1,
            minHeight: 0,
          }}
        >
          <ComposerPalette
            components={paletteComponents}
            selectedType={selectedEntry?.type}
            onSelect={handleSelectType}
          />

          <ComposerResizeHandle
            label={t('composerPaletteResizeLabel')}
            width={paletteWidth}
            min={PALETTE_WIDTH_MIN}
            max={PALETTE_WIDTH_MAX}
            onResize={setPaletteWidth}
            direction="grow-right"
          />

          <Box
            sx={{
              position: 'relative',
              overflow: 'auto',
              backgroundImage: (theme) =>
                `radial-gradient(${theme.palette.divider} 1px, transparent 1px)`,
              backgroundSize: '22px 22px',
            }}
          >
            <Box
              component="svg"
              sx={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                pointerEvents: 'none',
                color: 'primary.main',
              }}
            >
              {links.map((link) => {
                const from = effectiveNodePositions[link.from];
                const to = effectiveNodePositions[link.to];
                if (from === undefined || to === undefined) {
                  return null;
                }
                const x1 = from.x + CANVAS_LINK_PORT_OFFSET.fromRight;
                const y1 = from.y + CANVAS_LINK_PORT_OFFSET.verticalCenter;
                const x2 = to.x + CANVAS_LINK_PORT_OFFSET.toLeft;
                const y2 = to.y + CANVAS_LINK_PORT_OFFSET.verticalCenter;
                return (
                  <path
                    key={`${link.from}->${link.to}`}
                    d={`M ${x1} ${y1} C ${x1 + 70} ${y1}, ${x2 - 70} ${y2}, ${x2} ${y2}`}
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={2}
                  />
                );
              })}
            </Box>

            {componentSelection.map((entry) => {
              const position = effectiveNodePositions[entry.instanceId];
              if (position === undefined) {
                return null;
              }
              const stat = nodeStats.get(entry.instanceId);
              return (
                <ComposerNode
                  key={entry.instanceId}
                  entry={entry}
                  position={position}
                  selected={entry.instanceId === selectedInstanceId}
                  fieldCount={stat?.fieldCount ?? 0}
                  requiredEmptyCount={stat?.requiredEmptyCount ?? 0}
                  onSelect={() => setSelectedInstanceId(entry.instanceId)}
                  onMove={(next) =>
                    setNodePositions((prev) => ({ ...prev, [entry.instanceId]: next }))
                  }
                />
              );
            })}
          </Box>

          {selectedEntry !== undefined ? (
            <>
              <ComposerResizeHandle
                label={t('composerInspectorResizeLabel')}
                width={inspectorWidth}
                min={INSPECTOR_WIDTH_MIN}
                max={INSPECTOR_WIDTH_MAX}
                onResize={setInspectorWidth}
                direction="grow-left"
              />
              <ComposerInspector
                pkg={pkg}
                entry={selectedEntry}
                packageVersion={packageVersion}
                values={getInstanceValues(selectedEntry.instanceId)}
                observedState={observedState}
                onFieldChange={(key, value) => onFieldChange(selectedEntry.instanceId, key, value)}
                onClose={() => setSelectedInstanceId(undefined)}
                catalog={catalog}
                optionLabelsByField={optionLabelsByField}
                disabled={disabled}
              />
            </>
          ) : null}
        </Box>
      )}
    </Box>
  );
}
