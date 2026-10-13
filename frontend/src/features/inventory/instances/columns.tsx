/**
 * Column definitions for the instance inventory grid (task 041, `UI-D9`,
 * `SM-D22`, UI §A.6).
 *
 * Rewritten for integration plan 007 (2026-10-09, consolidated design
 * session item 1): 10 columns → 5. Dropped `driver`, `locus`, `health`,
 * `version` and `config` — all five required an N+1 fetch per row
 * (`getHealth`/config-ladder per instance) to populate a LIST, a real
 * performance cost for a grid rather than a free display, and all five
 * already have a home: Governance and Designer fetch them once, for the
 * one opened instance, where it's cheap (see `InstanceHeader`'s own
 * rewrite note). `driver`/`locus` additionally don't exist on the real
 * `Instance` at all — they're properties of the TARGET now
 * (`kind`/`transport` on `TargetRow`), implied by which target an
 * instance is placed on, not re-asked or re-shown per instance.
 *
 * What's left is exactly what `listInstances`'s single call provides for
 * free: name, environment (resolved separately — see
 * `InstancesPage.tsx`'s `environmentNames` map), declared package version,
 * and placement count (`placements.length`, no extra call).
 */
import { Typography } from '@mui/material';
import { Link } from 'react-router';

import type { Instance } from '@shared/api/contracts/instances';
import { t } from '@shared/i18n';
import { Chip, EnvChip } from '@shared/ui';
import type { ColumnDef } from '@shared/ui';

// A feature cannot import the app-layer `routes` helper (boundaries,
// task 009) — `SolutionsPage.tsx`'s `instancesHref` documents the same
// constraint for itself.
function instanceHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/designer`;
}

function InstanceCell({ row, solutionId }: { row: Instance; solutionId: string }) {
  return (
    <Typography
      component={Link}
      to={instanceHref(solutionId, row.instanceId)}
      variant="body2"
      sx={{ fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}
    >
      {row.name}
    </Typography>
  );
}

export function buildInstanceColumns(
  solutionId: string,
  environmentNames: ReadonlyMap<string, string>,
): ColumnDef<Instance>[] {
  return [
    {
      field: 'name',
      headerName: t('instancesColInstance'),
      width: 240,
      hideable: false,
      cardEmphasis: 'primary',
      valueGetter: (row) => row.name,
      renderCell: (row) => <InstanceCell row={row} solutionId={solutionId} />,
    },
    {
      field: 'env',
      headerName: t('instancesColEnv'),
      width: 88,
      cardEmphasis: 'chip',
      valueGetter: (row) => environmentNames.get(row.environmentId) ?? '',
      renderCell: (row) => {
        const env = environmentNames.get(row.environmentId);
        return env === undefined ? (
          <Typography color="text.disabled">—</Typography>
        ) : (
          <EnvChip env={env} />
        );
      },
    },
    {
      // IDENTITY (which schema this instance is configured against), so it
      // sits with name/env rather than among observed state.
      field: 'packageVersion',
      headerName: t('instancesColPackageVersion'),
      width: 110,
      cardEmphasis: 'chip',
      valueGetter: (row) => String(row.declaredPackageVersion),
      renderCell: (row) => (
        // `neutral`, deliberately NOT a semantic tone: `ok`/`warn`/`err`
        // mean HEALTH in this vocabulary, and a version is not a health
        // state. `mono` because the label is an identifier, not a word.
        <Chip tone="neutral" mono label={String(row.declaredPackageVersion)} />
      ),
    },
    {
      field: 'placements',
      headerName: t('instancesColReplicas'),
      width: 96,
      valueGetter: (row) => String(row.placements.length),
      renderCell: (row) => (
        <Typography sx={{ fontFamily: 'monospace' }}>{row.placements.length}</Typography>
      ),
    },
  ];
}
