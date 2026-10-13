/**
 * Placement detail panel (task 041, UI §A.6) — `DataTable`'s `detailPanel`
 * for an expanded instance row.
 *
 * Rewritten for integration plan 007 (2026-10-09): the real `Instance`
 * carries `placements[]` (`placementId`, `targetId`, `containerName`,
 * `probeStrength`), not the fixture-era `replicas[]` (`name`, `health`).
 * Per-replica health has a real home now too — Governance's placement-scoped
 * `getHealth` (plan 010) — so this panel shows placement identity only,
 * never a fabricated or stale health badge.
 */
import { Box, Typography } from '@mui/material';

import type { Instance } from '@shared/api/contracts/instances';

export interface ReplicaDetailPanelProps {
  row: Instance;
}

export function ReplicaDetailPanel({ row }: ReplicaDetailPanelProps) {
  return (
    <Box data-replica-detail-panel>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
          gap: 1.5,
        }}
      >
        {row.placements.map((placement) => (
          <Box
            key={placement.placementId}
            sx={{ p: 1.5, border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}
          >
            <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
              {placement.containerName}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              {placement.probeStrength}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
