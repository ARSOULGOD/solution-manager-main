/**
 * "All fields" — the composer's required non-pointer path (task 063,
 * design §10, UI §A.8.18: "a list view of the same components as the
 * non-pointer path... keep it"). One block per composed instance, each
 * with its own component header and its own `SectionCard` accordion list
 * — the same per-instance `project()` scoping `ComposerInspector` uses, so
 * this needs no `ViewSection` instance-identity contract change either.
 *
 * Every interactive element here (accordion headers, field controls) is
 * already keyboard-operable — `SectionCard`/`FieldRow` are, task 049/048
 * — so this view satisfies "keyboard-complete" by construction rather than
 * by adding anything canvas-specific.
 *
 * Accordion state is a plain local `Set<number>` per group, the same
 * choice `ComposerInspector` makes and for the same reason: `useSectionJump`
 * also owns scroll-into-view/focus for a nav rail neither pane has.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';
import { useMemo, useState } from 'react';

import { project } from '@contracts/view-model/project';
import type {
  Catalog,
  ComponentSelection,
  ComponentSelectionEntry,
  FieldValue,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';

import { SectionCard } from '../SectionCard';

import { ComponentRoleIcon } from './composerIcons';
import { composerRoleLabel } from './composerRoleLabel';
import { defaultExpandedSections } from './composerSectionExpansion';

export interface ComposerFieldListProps {
  pkg: SolutionPackage;
  componentSelection: ComponentSelection;
  packageVersion: PackageVersionId;
  getInstanceValues: (instanceId: string) => Readonly<Record<string, FieldValue>>;
  observedState: ObservedState;
  onFieldChange: (instanceId: string, fieldKey: string, value: FieldValue) => void;
  catalog?: Catalog;
  optionLabelsByField?: Readonly<Record<string, Readonly<Record<string, string>>>>;
  disabled?: boolean;
}

export function ComposerFieldList({
  pkg,
  componentSelection,
  packageVersion,
  getInstanceValues,
  observedState,
  onFieldChange,
  catalog = {},
  optionLabelsByField = {},
  disabled = false,
}: ComposerFieldListProps): ReactElement {
  return (
    <Box sx={{ p: 2, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 3 }}>
      {componentSelection.map((entry) => (
        <ComposerFieldListGroup
          key={entry.instanceId}
          pkg={pkg}
          entry={entry}
          packageVersion={packageVersion}
          values={getInstanceValues(entry.instanceId)}
          observedState={observedState}
          catalog={catalog}
          optionLabelsByField={optionLabelsByField}
          onFieldChange={(key, value) => onFieldChange(entry.instanceId, key, value)}
          disabled={disabled}
        />
      ))}
    </Box>
  );
}

interface ComposerFieldListGroupProps {
  pkg: SolutionPackage;
  entry: ComponentSelectionEntry;
  packageVersion: PackageVersionId;
  values: Readonly<Record<string, FieldValue>>;
  observedState: ObservedState;
  onFieldChange: (key: string, value: FieldValue) => void;
  catalog: Catalog;
  optionLabelsByField?: Readonly<Record<string, Readonly<Record<string, string>>>>;
  disabled: boolean;
}

function ComposerFieldListGroup({
  pkg,
  entry,
  packageVersion,
  values,
  observedState,
  onFieldChange,
  catalog,
  optionLabelsByField = {},
  disabled,
}: ComposerFieldListGroupProps): ReactElement {
  const viewModel = useMemo(
    () => project(pkg, [entry], { [entry.instanceId]: values }, observedState, packageVersion, catalog, entry.role),
    [pkg, entry, values, observedState, packageVersion, catalog],
  );
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(() =>
    defaultExpandedSections(viewModel.sections),
  );

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
    <Box component="section" aria-label={`${composerRoleLabel(entry.role)} · ${entry.type}`}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <ComponentRoleIcon role={entry.role} fontSize="small" />
        <Typography
          variant="caption"
          sx={{ fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em' }}
        >
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

      {viewModel.sections.map((section, index) => (
        <Box key={`${entry.instanceId}:${section.title}`} sx={{ mb: 1.5 }}>
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
    </Box>
  );
}
