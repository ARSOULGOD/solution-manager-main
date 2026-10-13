/**
 * Per-replica table (task 060, Dec `SM-D22` `UI-D9`, Design §10.1, UI §A.8
 * replica table; reference `designer_panels.jsx`'s own `ReplicaTable`).
 * Never a single spinner — replica · rendered config version · restart
 * observed · health, one row per replica, on `DataTable` (task 020, whose
 * own objective names "replicas" as one of the tables it unifies) rather
 * than the reference's raw `<table>`.
 *
 * `generation`/`restartObserved` are **invented column names** — the
 * contract names them in prose with no row schema (§16 Q12) — flagged via
 * `ColumnDef.info`, the same mechanism `columns.tsx` already uses for
 * `driver`/`locus` being data, not a UI branch.
 *
 * The last column reads `ReplicaObservationFixture.health`'s
 * `GREEN`/`RED`/`RESTART_EXPECTED`/`INACTIVE_REPLICA` keys through
 * `applyResultVocab`, its own vocabulary — NOT the shared `healthVocab`
 * Governance uses for a live health probe. This column is what the apply
 * that produced the row observed at the time, never re-checked afterward;
 * labelling it with `healthVocab`'s "Healthy" implied an ongoing live
 * status this table does not have, and still said "Healthy" long after an
 * operator stopped the container by hand. See `applyResult.ts`'s header.
 */
import { Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';
import { Chip, DataTable, StateChip } from '@shared/ui';
import type { ColumnDef } from '@shared/ui';
import { applyResultVocab, type ApplyResultKey } from '@shared/vocab';

import { isOddGeneration } from './majorityGeneration';
import type { ReplicaTableProps } from './ReplicaTable.types';

const REPLICA_HEALTH_TO_VOCAB: Record<ReplicaObservationFixture['health'], ApplyResultKey> = {
  GREEN: 'restart_succeeded',
  RED: 'restart_failed',
  RESTART_EXPECTED: 'restart_pending',
  INACTIVE_REPLICA: 'inactive_replica',
};

export function ReplicaTable({ replicas }: ReplicaTableProps): ReactElement {
  const columns: readonly ColumnDef<ReplicaObservationFixture>[] = [
    {
      field: 'id',
      headerName: t('replicaTableReplicaHeader'),
      valueGetter: (row) => row.id,
      renderCell: (row) => (
        <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
          {row.id}
        </Typography>
      ),
    },
    {
      field: 'generation',
      headerName: t('replicaTableRenderedVersionHeader'),
      info: t('replicaTableRenderedVersionInfo'),
      valueGetter: (row) => row.generation,
      renderCell: (row) => {
        const odd = isOddGeneration(row, replicas);
        return (
          <Typography
            variant="body2"
            sx={{
              fontFamily: 'monospace',
              ...(odd ? { color: 'error.main', fontWeight: 600 } : {}),
            }}
          >
            {row.generation}
          </Typography>
        );
      },
    },
    {
      field: 'restartObserved',
      headerName: t('replicaTableRestartObservedHeader'),
      info: t('replicaTableRestartObservedInfo'),
      valueGetter: (row) =>
        row.restartObserved
          ? t('replicaTableRestartObservedYes')
          : t('replicaTableRestartObservedPending'),
      renderCell: (row) =>
        row.restartObserved ? (
          <Chip label={t('replicaTableRestartObservedYes')} tone="ok" dot />
        ) : (
          <Chip label={t('replicaTableRestartObservedPending')} tone="idle" dot />
        ),
    },
    {
      field: 'health',
      headerName: t('replicaTableHealthHeader'),
      info: t('replicaTableHealthInfo'),
      valueGetter: (row) => applyResultVocab[REPLICA_HEALTH_TO_VOCAB[row.health]].label,
      renderCell: (row) => <StateChip entry={applyResultVocab[REPLICA_HEALTH_TO_VOCAB[row.health]]} />,
    },
  ];

  return (
    <DataTable<ReplicaObservationFixture>
      rows={replicas}
      columns={columns}
      getRowId={(row) => row.id}
      density="compact"
      csvFileName="replicas.csv"
    />
  );
}
