import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { Alert, Button } from '@shared/ui';

import type { DriftEntry } from './detectDrift';

export interface ConnectionDriftBannerProps {
  readonly entries: readonly DriftEntry[];
  readonly fieldLabel?: (fieldKey: string) => string;
  readonly onReviewAndSave?: (entry: DriftEntry) => void;
}

export function ConnectionDriftBanner({
  entries,
  fieldLabel = (fieldKey) => fieldKey,
  onReviewAndSave,
}: ConnectionDriftBannerProps): ReactElement | null {
  if (entries.length === 0) return null;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      {entries.map((entry) => (
        <Alert key={entry.connectionId} severity="warn" title={`${entry.name} changed`} fullWidth>
          <Typography variant="body2">
            {`${entry.name} changed (rev ${String(entry.appliedRevision)} → ${String(entry.currentRevision)}). `}
            {`${entry.affectedFields.map(fieldLabel).join(', ')} may differ from what was last applied.`}
          </Typography>
          <Box sx={{ mt: 1.5 }}>
            <Button size="sm" variant="outlined" onClick={() => onReviewAndSave?.(entry)}>
              Review and save
            </Button>
          </Box>
        </Alert>
      ))}
    </Box>
  );
}
