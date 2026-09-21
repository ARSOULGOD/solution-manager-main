/**
 * One Designer field, end to end (task 048; `UI-D4`, `UI-D16`, `UI-D17`,
 * `I6`; design §10, §18; UI §A.8.4). The same component renders every
 * solution's fields — it switches on `field.controlKind` only, never on a
 * solution or field name (`UI-D14`).
 *
 * Adapting `ViewField` into whatever a specific control needs is this
 * task's job (FE-047's own report says so) — `controlRegistry` gives the
 * `controlKind -> component` lookup, this file gives the
 * `controlKind -> props` half.
 *
 * **Below `sm`** (task 108, `FE-D29`): the metadata row (discriminator
 * hint, rendered path, `Default` hint, `Modified` chip) moves under the
 * control instead of above it, and wraps rather than overflowing — every
 * one of those is a correctness signal, so none is hidden, only
 * reordered. The DOM order actually changes (not a CSS `order` trick):
 * flipping only the *visual* order would desync it from focus/reading
 * order, a WCAG 1.3.2 (meaningful sequence) violation `useMediaQuery`-
 * driven branching avoids.
 */
import { Box, Typography, useMediaQuery, useTheme } from '@mui/material';
import type { ComponentType, ReactElement } from 'react';

import type { FieldValue, ViewFieldValue } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Chip, InfoTip, ToneIcon } from '@shared/ui';
import type { SecretFieldProps, SecretFieldState, SelectProps } from '@shared/ui';

import type { DurationControlProps } from '../controls/DurationControl';
import type { ListControlProps } from '../controls/ListControl';
import type { MapControlProps, MapEntry } from '../controls/MapControl';
import { controlRegistry } from '../controls/registry';
import type { TableControlProps, TableRow } from '../controls/TableControl';
import type { YamlControlProps } from '../controls/YamlControl';

import type { FieldRowProps } from './FieldRow.types';

const MONO_FONT = 'JetBrains Mono, ui-monospace, monospace';

/** The label/required/helperText/error/disabled shape every scalar control shares — declared locally, not imported from `@shared/ui/internal/FieldProps` (unbarrelled; FE-047's own ruling on this). */
interface CommonControlProps {
  label: string;
  required: boolean;
  helperText?: string;
  error?: string;
  disabled: boolean;
}

function unwrap(value: ViewFieldValue): FieldValue {
  return value.kind === 'value' ? value.value : null;
}

function secretStateFor(value: ViewFieldValue): SecretFieldState {
  return value.kind === 'secretRef' ? { kind: 'set', ref: value.ref } : { kind: 'unset' };
}

// A field's declared requiredness surfaces through `UI-D16`'s live
// validation set (task 053, `project()`'s own `evaluateValidations`), not
// a separate boolean on `ViewField`.
function fieldIsRequired(field: FieldRowProps['field']): boolean {
  return field.validations.some((validation) => validation.code === 'REQUIRED');
}

function stringValue(value: FieldValue): string {
  return typeof value === 'string' ? value : '';
}

function numberValue(value: FieldValue): number | '' {
  return typeof value === 'number' ? value : '';
}

function booleanValue(value: FieldValue): boolean {
  return value === true;
}

function listValues(value: FieldValue): readonly string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function mapEntries(value: FieldValue): readonly MapEntry[] {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return [];
  }
  return Object.entries(value)
    .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
    .map(([key, entryValue]) => ({ key, value: entryValue }));
}

function tableRows(value: FieldValue): readonly TableRow[] {
  return Array.isArray(value)
    ? value.filter((row): row is TableRow => row !== null && typeof row === 'object' && !Array.isArray(row))
    : [];
}

interface FieldControlProps {
  controlKind: FieldRowProps['field']['controlKind'];
  common: CommonControlProps;
  options: readonly string[];
  value: FieldValue;
  onChange: (value: FieldValue) => void;
}

function FieldControl({ controlKind, common, options, value, onChange }: FieldControlProps): ReactElement {
  switch (controlKind) {
    case 'text': {
      const Control = controlRegistry.text as ComponentType<
        CommonControlProps & { value: string; onChange: (event: { target: { value: string } }) => void }
      >;
      return <Control {...common} value={stringValue(value)} onChange={(event) => onChange(event.target.value)} />;
    }
    case 'number': {
      const Control = controlRegistry.number as ComponentType<
        CommonControlProps & { value: number | ''; onChange: (value: number | '') => void }
      >;
      return (
        <Control
          {...common}
          value={numberValue(value)}
          onChange={(next) => onChange(next === '' ? null : next)}
        />
      );
    }
    case 'toggle': {
      const Control = controlRegistry.toggle as ComponentType<
        Omit<CommonControlProps, 'required'> & { checked: boolean; onChange: (checked: boolean) => void }
      >;
      return (
        <Control
          label={common.label}
          disabled={common.disabled}
          {...(common.helperText !== undefined ? { helperText: common.helperText } : {})}
          {...(common.error !== undefined ? { error: common.error } : {})}
          checked={booleanValue(value)}
          onChange={onChange}
        />
      );
    }
    case 'select': {
      const Control = controlRegistry.select as ComponentType<SelectProps>;
      return (
        <Control
          {...common}
          value={stringValue(value)}
          onChange={onChange}
          options={options.map((option) => ({ value: option, label: option }))}
        />
      );
    }
    case 'duration': {
      const Control = controlRegistry.duration as ComponentType<DurationControlProps>;
      return <Control {...common} value={stringValue(value)} onChange={onChange} />;
    }
    case 'secret': {
      const Control = controlRegistry.secret as ComponentType<SecretFieldProps>;
      return (
        <Control
          disabled={common.disabled ?? false}
          state={secretStateFor(value === null ? { kind: 'secretUnset' } : { kind: 'secretRef', ref: stringValue(value) })}
          onSet={onChange}
          onClear={() => onChange(null)}
        />
      );
    }
    case 'list': {
      const Control = controlRegistry.list as ComponentType<ListControlProps>;
      return <Control {...common} values={listValues(value)} onChange={onChange} />;
    }
    case 'map': {
      const Control = controlRegistry.map as ComponentType<MapControlProps>;
      return (
        <Control
          {...common}
          entries={mapEntries(value)}
          onChange={(entries) => onChange(Object.fromEntries(entries.map((entry) => [entry.key, entry.value])))}
        />
      );
    }
    case 'table': {
      const Control = controlRegistry.table as ComponentType<TableControlProps>;
      // No `columns` on `ViewField` (`FieldDeclaration.columns` isn't
      // threaded through `project()` yet — same gap FE-047's own report
      // notes: "no `table`-typed field example yet"). `TableControl`
      // already renders its package-declares-columns placeholder for this.
      return <Control {...common} rows={tableRows(value)} onChange={onChange} />;
    }
    case 'yaml': {
      const Control = controlRegistry.yaml as ComponentType<YamlControlProps>;
      return <Control label={common.label} {...(common.helperText !== undefined ? { helperText: common.helperText } : {})} />;
    }
  }
}

export function FieldRow({ field, value, onChange, disabled = false, showRenderedPath = false }: FieldRowProps) {
  const theme = useTheme();
  const stackMetadataBelowControl = useMediaQuery(theme.breakpoints.down('sm'));
  const required = fieldIsRequired(field);
  const initialValue = unwrap(field.value);
  const modified = JSON.stringify(value) !== JSON.stringify(initialValue);
  // `Default` and `Modified` are mutually exclusive: a field whose current
  // value originated as the schema default stops being "Default" the
  // moment the operator edits it away from that value.
  const isDefault = field.value.kind === 'value' && field.value.source === 'default' && !modified;
  const blockMessages = field.validations.filter((validation) => validation.tier === 'bind');

  const common: CommonControlProps = {
    label: field.label,
    required,
    disabled: disabled || !field.enabled,
    ...(field.helperText !== undefined ? { helperText: field.helperText } : {}),
    ...(blockMessages.length > 0 ? { error: blockMessages.map((v) => v.message).join(' ') } : {}),
  };

  const metadataRow = (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1 }}>
      {field.discriminator ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, color: 'primary.main' }}>
          <Typography variant="caption">{t('fieldRowDiscriminatorHint')}</Typography>
          <InfoTip tip={t('fieldRowDiscriminatorTip')} />
        </Box>
      ) : null}
      <Box sx={{ flexGrow: 1 }} />
      {showRenderedPath && field.renderedPath !== null ? (
        <Typography variant="caption" sx={{ fontFamily: MONO_FONT, wordBreak: 'break-all' }}>
          {field.renderedPath}
        </Typography>
      ) : null}
      {isDefault ? <Typography variant="caption">{t('fieldRowDefault')}</Typography> : null}
      {modified ? <Chip label={t('fieldRowModified')} tone="info" /> : null}
    </Box>
  );

  return (
    <Box data-field-row data-field-key={field.key} sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {!stackMetadataBelowControl ? metadataRow : null}

      <FieldControl controlKind={field.controlKind} common={common} options={field.options} value={value} onChange={onChange} />

      {stackMetadataBelowControl ? metadataRow : null}

      {field.validations.map((validation, index) => (
        <Box
          key={`${validation.code}-${index}`}
          sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5 }}
        >
          <ToneIcon tone={validation.tier === 'bind' ? 'err' : 'warn'} fontSize="small" />
          <Typography variant="caption">
            {validation.message}
            {validation.tier === 'semantic' ? ` — ${t('fieldRowWarnRecommendation')}` : ''}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}
