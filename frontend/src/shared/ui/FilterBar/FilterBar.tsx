/**
 * Toolbar: free-text search (debounced) plus immediate enum/toggle
 * filters. Result count is a polite live region (FE-025).
 */
import { Box } from '@mui/material';

import { t } from '@shared/i18n';

import { Cluster } from '../layout/Cluster';

import type { FilterBarProps } from './FilterBar.types';
import { SearchField } from './SearchField';

export function FilterBar({ children, search, resultCount }: FilterBarProps) {
  return (
    <Box data-filter-bar role="search" aria-label={t('filterBar')} sx={{ minWidth: 0 }}>
      <Cluster>
        {search !== undefined && <SearchField {...search} />}
        {children}
      </Cluster>
      <Box aria-live="polite" aria-atomic="true" data-filter-result-count sx={{ mt: 1 }}>
        {t('resultCount', { count: resultCount })}
      </Box>
    </Box>
  );
}
