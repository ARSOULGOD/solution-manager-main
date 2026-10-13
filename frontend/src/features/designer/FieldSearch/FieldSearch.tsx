/**
 * Flat field search across every Designer section (task 051, design
 * §10/§17, UI §A.8.8): a debounced search box labelled "Search N fields…",
 * matching label, key, rendered path and section, rendering hits as plain
 * `FieldRow`s each carrying a crumb.
 *
 * UI §A.8.8 describes the crumb as `component · section`. `ViewSection`
 * carries no component-instance identity to supply the first half — the
 * same gap `SectionNav`'s module doc documents in detail (proven there via
 * the `multi-component-selection` fixture: two different component
 * instances project `ViewSection`s indistinguishable by anything but their
 * field values). The crumb here is therefore `section` alone, which is
 * exact for any single-component solution (Cache, the v1 target) and
 * disclosed rather than faked for a multi-component one.
 *
 * Debouncing is `SearchField`'s (`FE-025`, design §17) — reused rather
 * than reimplemented so this stays the *only* Designer surface with a
 * debounce, per this task's own acceptance criterion.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import type { FieldValue, ViewField } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { SearchField } from '@shared/ui';

import { FieldRow } from '../FieldRow';

import type { FieldSearchProps } from './FieldSearch.types';

function unwrap(field: ViewField): FieldValue {
  return field.value.kind === 'value' ? field.value.value : null;
}

export function FieldSearch({
  query,
  onQueryChange,
  totalFieldCount,
  hits,
  values,
  onFieldChange,
  disabled = false,
}: FieldSearchProps): ReactElement {
  const isActive = query !== '';

  return (
    <Box>
      <SearchField label={t('fieldSearchLabel', { count: totalFieldCount })} value={query} onChange={onQueryChange} />

      {isActive ? (
        <Box aria-live="polite" aria-atomic="true" sx={{ mt: 1.5, display: 'flex', flexDirection: 'column', gap: 2 }}>
          {hits.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {t('fieldSearchEmpty', { query })}
            </Typography>
          ) : (
            <>
              <Typography variant="caption" color="text.secondary">
                {t('fieldSearchResultCount', { count: hits.length, query })}
              </Typography>
              {hits.map(({ field, sectionTitle }) => (
                <Box key={field.key} sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                  <Typography variant="caption" color="text.secondary">
                    {sectionTitle}
                  </Typography>
                  <FieldRow
                    field={field}
                    value={values[field.key] ?? unwrap(field)}
                    onChange={(value) => onFieldChange(field.key, value)}
                    disabled={disabled}
                    showRenderedPath
                  />
                </Box>
              ))}
            </>
          )}
        </Box>
      ) : null}
    </Box>
  );
}
