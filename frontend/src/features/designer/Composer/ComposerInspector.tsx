/**
 * The composer's right pane (task 063, design §10, UI §A.8.18): "the
 * selected component's sections through the same `SectionCard`/`FieldRow`
 * components" — implemented by scoping `project()` to a single-entry
 * `componentSelection`, exactly the pattern `useDesignerDraft.ts`'s own
 * `visibleInstanceValues()` already uses (task 052). That scoping is what
 * lets this pane reuse `FieldSearch`/`SectionCard` unmodified: from their
 * point of view a single selected node is indistinguishable from a
 * single-component solution, which is exactly the case `SectionNav.tsx`'s
 * own module doc says those components "render correctly" for today. No
 * change to `contracts/view-model/types.ts` was needed to build this
 * inspector.
 *
 * Accordion state is a plain local `Set<number>` — `SectionCard`'s own
 * doc says that state is "owned by the caller" — rather than the fuller
 * `useSectionJump` (task 050), which also owns scroll-into-view/focus for
 * a nav rail this pane doesn't have; pulling it in only for its `expanded`
 * bookkeeping would be an unused-feature import for no benefit here.
 */
import { Box, IconButton, Typography } from '@mui/material';
import type { ReactElement } from 'react';
import { useMemo, useState } from 'react';

import { project } from '@contracts/view-model/project';
import type {
  Catalog,
  ComponentSelectionEntry,
  FieldValue,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';
import { t } from '@shared/i18n';

import { FieldSearch, useFieldSearch } from '../FieldSearch';
import { SectionCard } from '../SectionCard';

import { ComponentRoleIcon } from './composerIcons';
import { composerRoleLabel } from './composerRoleLabel';
import { defaultExpandedSections } from './composerSectionExpansion';

export interface ComposerInspectorProps {
  pkg: SolutionPackage;
  entry: ComponentSelectionEntry;
  packageVersion: PackageVersionId;
  values: Readonly<Record<string, FieldValue>>;
  observedState: ObservedState;
  onFieldChange: (key: string, value: FieldValue) => void;
  onClose: () => void;
  catalog?: Catalog;
  optionLabelsByField?: Readonly<Record<string, Readonly<Record<string, string>>>>;
  disabled?: boolean;
}

export function ComposerInspector({
  pkg,
  entry,
  packageVersion,
  values,
  observedState,
  onFieldChange,
  onClose,
  catalog = {},
  optionLabelsByField = {},
  disabled = false,
}: ComposerInspectorProps): ReactElement {
  const viewModel = useMemo(
    () => project(pkg, [entry], { [entry.instanceId]: values }, observedState, packageVersion, catalog, entry.role),
    [pkg, entry, values, observedState, packageVersion, catalog],
  );
  const search = useFieldSearch(viewModel.sections);
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(() =>
    defaultExpandedSections(viewModel.sections),
  );

  // Adjusted during render (not an effect — react-hooks/set-state-in-
  // effect): a new selected node's sections start expanded the same way
  // the first one did, rather than inheriting whatever the previous
  // node's section indices happened to mean.
  const [expandedForInstanceId, setExpandedForInstanceId] = useState(entry.instanceId);
  if (entry.instanceId !== expandedForInstanceId) {
    setExpandedForInstanceId(entry.instanceId);
    setExpanded(defaultExpandedSections(viewModel.sections));
  }

  function toggle(index: number): void {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }

  return (
    <Box
      sx={{
        borderLeft: 1,
        borderColor: 'divider',
        overflowY: 'auto',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <Box
        sx={{
          p: 2,
          borderBottom: 1,
          borderColor: 'divider',
          position: 'sticky',
          top: 0,
          backgroundColor: 'background.paper',
          zIndex: 1,
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box
            aria-hidden
            sx={{
              width: 28,
              height: 28,
              borderRadius: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              color: 'primary.contrastText',
              backgroundColor: entry.role === 'sink' ? 'secondary.main' : 'primary.main',
            }}
          >
            <ComponentRoleIcon role={entry.role} fontSize="small" />
          </Box>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>
              {composerRoleLabel(entry.role)}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace' }}
            >
              {entry.type}
            </Typography>
          </Box>
          <IconButton aria-label={t('close')} onClick={onClose} size="small">
            <span aria-hidden>×</span>
          </IconButton>
        </Box>
      </Box>

      <Box sx={{ p: 2, flex: 1 }}>
        <FieldSearch
          query={search.query}
          onQueryChange={search.setQuery}
          totalFieldCount={search.totalFieldCount}
          hits={search.hits}
          values={values}
          onFieldChange={onFieldChange}
          disabled={disabled}
        />

        {!search.isActive &&
          viewModel.sections.map((section, index) => (
            <Box key={`${entry.instanceId}:${section.title}`} sx={{ mt: 1.5 }}>
              <SectionCard
                section={section}
                values={values}
                onFieldChange={onFieldChange}
                disabled={disabled}
                expanded={expanded.has(index)}
                onToggleExpanded={() => toggle(index)}
                optionLabelsByField={optionLabelsByField}
              />
            </Box>
          ))}

        {viewModel.sections.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('composerInspectorEmpty')}
          </Typography>
        ) : null}
      </Box>
    </Box>
  );
}
