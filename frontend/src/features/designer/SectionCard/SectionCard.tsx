/**
 * One Designer section, as an accordion with its own advanced-field
 * disclosure (task 049; `UI-D15`'s advanced obligation — "all 1,989 File
 * Mesh rows carry the flag; a 132-field FTP component is unusable without
 * it", design §10, UI §A.8.6).
 *
 * "Applicable" throughout this file means `field.visible` — a hidden
 * branch (`when:` false) never counts toward the header's field count or
 * the block/warn chips, even though its value is still retained in the
 * draft (`UI-D4`). `advanced` is an orthogonal disclosure flag (task 046's
 * own ruling): a field can be both visible and advanced at once.
 */
import { Box, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import { useState } from 'react';
import type { ReactElement } from 'react';

import type { FieldValue, ViewField } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Chip } from '@shared/ui';

import { FieldRow } from '../FieldRow';

import type { SectionCardProps } from './SectionCard.types';
import { groupSectionFields } from './sectionFieldGroups';

// Inlined, not `@mui/icons-material` (not a project dependency — same
// ruling as `Button`'s danger marker and `TextField`'s eye icons).
function ExpanderIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8 5l8 7-8 7V5z" />
    </SvgIcon>
  );
}

function AddIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
    </SvgIcon>
  );
}

function RemoveIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M19 13H5v-2h14v2z" />
    </SvgIcon>
  );
}

function unwrap(field: ViewField): FieldValue {
  return field.value.kind === 'value' ? field.value.value : null;
}

export function SectionCard({
  section,
  values,
  onFieldChange,
  disabled = false,
  expanded,
  onToggleExpanded,
  showRenderedPath = false,
}: SectionCardProps): ReactElement {
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const { applicable: applicableFields, allAdvanced, normal: normalFields, advanced: advancedFields, blockCount, warnCount } =
    groupSectionFields(section);

  const renderField = (field: ViewField) => (
    <FieldRow
      key={field.key}
      field={field}
      value={values[field.key] ?? unwrap(field)}
      onChange={(value) => onFieldChange(field.key, value)}
      disabled={disabled}
      showRenderedPath={showRenderedPath}
    />
  );

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}>
      <Box
        component="button"
        type="button"
        onClick={onToggleExpanded}
        aria-expanded={expanded}
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 1,
          width: '100%',
          p: 1.5,
          border: 'none',
          background: 'none',
          cursor: 'pointer',
          font: 'inherit',
          textAlign: 'left',
          color: 'text.primary',
        }}
      >
        <ExpanderIcon fontSize="small" sx={{ transform: expanded ? 'rotate(90deg)' : 'none', flexShrink: 0 }} />
        {/* `minWidth: 0` lets a long title wrap/shrink inside the flex row instead of forcing the header to overflow (task 108, FE-D29) — a flex item's default `min-width: auto` sizes to its longest unbreakable run otherwise. */}
        {/* `component="span"`: this is a toggle button's label, not a document
            heading — MUI's default variant mapping renders `subtitle2` as a
            real `<h6>` otherwise, which skips levels under a page's own `<h1>`
            (surfaced by axe once this card mounts under a real page, task 123). */}
        <Typography component="span" variant="subtitle2" sx={{ minWidth: 0, wordBreak: 'break-word' }}>
          {section.title}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
          {t('sectionCardFieldCount', { count: applicableFields.length })}
        </Typography>
        {allAdvanced ? <Chip tone="neutral" label={t('sectionCardAdvancedChip')} /> : null}
        <Box sx={{ flexGrow: 1 }} />
        {blockCount > 0 ? (
          <Chip tone="err" dot label={t('sectionCardBlockCount', { count: blockCount })} />
        ) : warnCount > 0 ? (
          <Chip tone="warn" dot label={t('sectionCardWarnCount', { count: warnCount })} />
        ) : null}
      </Box>

      {expanded ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, p: 1.5, pt: 0 }}>
          {applicableFields.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('sectionCardEmpty')}
            </Typography>
          ) : null}

          {normalFields.map(renderField)}

          {advancedFields.length > 0 ? (
            <>
              <Box
                component="button"
                type="button"
                onClick={() => setAdvancedOpen((open) => !open)}
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 0.5,
                  alignSelf: 'flex-start',
                  border: 'none',
                  background: 'none',
                  cursor: 'pointer',
                  font: 'inherit',
                  color: 'primary.main',
                  p: 0,
                }}
              >
                {advancedOpen ? <RemoveIcon fontSize="small" /> : <AddIcon fontSize="small" />}
                {advancedOpen
                  ? t('sectionCardHideAdvanced', { count: advancedFields.length })
                  : t('sectionCardShowAdvanced', { count: advancedFields.length })}
              </Box>
              {advancedOpen ? advancedFields.map(renderField) : null}
            </>
          ) : null}
        </Box>
      ) : null}
    </Box>
  );
}
