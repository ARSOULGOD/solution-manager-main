/**
 * Column definitions for the instance inventory grid (task 041, `UI-D9`,
 * `UI-D12`, `SM-D22`, UI §A.6). `driver`/`locus` cells are pure
 * `vocab[row.driver]`/`vocab[row.locus]` lookups — **never** a UI branch
 * on their value (`REQ-005`, `REQ-008`): the same column renders Docker,
 * Kubernetes and systemd, `dp-remote`, `host-local` and `observe-only`
 * alike.
 *
 * **`width` is a FLOOR** (FE-119) — `DataTable` maps it to `minWidth` plus
 * a proportional `flex` share. Before FE-119 it also set `flex: 1`, which
 * MUI documents as overriding `width` outright, so every number below was
 * discarded and all nine columns rendered identically wide. The values are
 * re-tuned here now that they take effect; their sum exceeds a typical
 * viewport, and the grid scrolls horizontally rather than truncating.
 */
import { Box, Typography } from '@mui/material';
import { Link } from 'react-router';

import { t } from '@shared/i18n';
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import { Chip, EnvChip, InfoTip, StateChip, Tooltip } from '@shared/ui';
import type { ColumnDef } from '@shared/ui';
import {
  configStateVocab,
  driverVocab,
  healthVocab,
  locusVocab,
  notFoundVocab,
  versionVocab,
  type HealthKey,
  type VersionKey,
} from '@shared/vocab';

// `UI-D12`: `drift`/`unknown` block editing with a stated reason;
// `unverified` does not — the permanent-normal state for an MI with no
// version-reporting mechanism. Local to this cell: `shared/vocab`'s own
// `tip` is general commentary, this is the specific editability sentence
// the reference always appends to it.
export const VERSION_EDITABILITY: Record<VersionKey, string> = {
  ok: 'Config editing is allowed.',
  unverified: 'Config editing is allowed.',
  drift: 'Config is read-only until reconciled.',
  unknown: 'Config editing is blocked.',
};

// A feature cannot import the app-layer `routes` helper (boundaries,
// task 009) — `SolutionsPage.tsx`'s `instancesHref` documents the same
// constraint for itself.
function instanceHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/designer`;
}

function InstanceCell({ row, solutionId }: { row: InstanceRow; solutionId: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography
        component={Link}
        to={instanceHref(solutionId, row.id)}
        variant="body2"
        sx={{ fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}
      >
        {row.name}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {row.org} / {row.subOrg}
      </Typography>
    </Box>
  );
}

function healthCell(row: InstanceRow) {
  if (row.healthRollup === 'notfound') {
    const entry = row.notFoundReason !== undefined ? notFoundVocab[row.notFoundReason] : undefined;
    if (entry === undefined) {
      return null;
    }
    return (
      <Box>
        <StateChip entry={entry} />
        {entry.scope !== undefined && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
            {entry.scope}
          </Typography>
        )}
      </Box>
    );
  }

  const entry = healthVocab[row.healthRollup];
  const counts = new Map<HealthKey, number>();
  for (const replica of row.replicas) {
    counts.set(replica.health, (counts.get(replica.health) ?? 0) + 1);
  }
  const summary = [...counts.entries()]
    .map(([key, count]) => `${String(count)} ${healthVocab[key].label.toLowerCase()}`)
    .join(' · ');

  return (
    <Box>
      <StateChip entry={entry} />
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
        {summary}
      </Typography>
    </Box>
  );
}

function versionCell(row: InstanceRow) {
  const entry = versionVocab[row.version.state];
  const tip = [entry.tip, VERSION_EDITABILITY[row.version.state]].filter(Boolean).join(' ');
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <Chip tone={entry.tone} icon={entry.icon} label={entry.label} />
        <InfoTip tip={tip} />
      </Box>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', mt: 0.25, fontFamily: 'monospace' }}
      >
        {t('instancesVersionObsDecl', {
          observed: row.version.observed,
          declared: row.version.declared,
        })}
      </Typography>
    </Box>
  );
}

function configCell(row: InstanceRow) {
  const entry = configStateVocab[row.configState];
  if (row.configState === 'na') {
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <Typography color="text.disabled">—</Typography>
        {entry.tip !== undefined && <InfoTip tip={entry.tip} />}
      </Box>
    );
  }
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
      <Chip tone={entry.tone} icon={entry.icon} label={entry.label} />
      {row.configState === 'in_sync' ? (
        <Tooltip title={t('instancesConfigEffectiveUnverifiedTip')}>
          <Chip tone="idle" label={t('instancesConfigEffectiveUnverified')} />
        </Tooltip>
      ) : (
        entry.tip !== undefined && <InfoTip tip={entry.tip} />
      )}
    </Box>
  );
}

export function buildInstanceColumns(solutionId: string): ColumnDef<InstanceRow>[] {
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
      valueGetter: (row) => row.env,
      renderCell: (row) => <EnvChip env={row.env} />,
    },
    {
      // Between `env` and `driver`: it is IDENTITY (which schema this
      // instance is configured against), so it belongs with the other
      // identity columns rather than out among the observed state.
      field: 'packageVersion',
      headerName: t('instancesColPackageVersion'),
      width: 110,
      cardEmphasis: 'chip',
      valueGetter: (row) => String(row.packageVersion),
      renderCell: (row) => (
        // `neutral`, deliberately NOT a semantic tone: `ok`/`warn`/`err`
        // mean HEALTH in this vocabulary, and a version is not a health
        // state. `mono` because the label is an identifier, not a word —
        // the reference renders exactly this
        // (`reference/app/instances.jsx:93`).
        <Chip tone="neutral" mono label={String(row.packageVersion)} />
      ),
    },
    {
      field: 'driver',
      headerName: t('instancesColDriver'),
      info: t('instancesColDriverInfo'),
      width: 150,
      cardEmphasis: 'chip',
      valueGetter: (row) => driverVocab[row.driver].label,
      renderCell: (row) => <StateChip entry={driverVocab[row.driver]} />,
    },
    {
      field: 'locus',
      headerName: t('instancesColLocus'),
      info: t('instancesColLocusInfo'),
      width: 170,
      cardEmphasis: 'chip',
      valueGetter: (row) => locusVocab[row.locus].label,
      renderCell: (row) => <StateChip entry={locusVocab[row.locus]} />,
    },
    {
      field: 'health',
      headerName: t('instancesColHealth'),
      width: 210,
      cardEmphasis: 'chip',
      valueGetter: (row) =>
        row.healthRollup === 'notfound' ? (row.notFoundReason ?? 'notfound') : row.healthRollup,
      renderCell: healthCell,
    },
    {
      field: 'version',
      headerName: t('instancesColVersion'),
      width: 190,
      cardEmphasis: 'chip',
      valueGetter: (row) => row.version.state,
      renderCell: versionCell,
    },
    {
      field: 'config',
      headerName: t('instancesColConfig'),
      width: 210,
      cardEmphasis: 'chip',
      valueGetter: (row) => row.configState,
      renderCell: configCell,
    },
    {
      field: 'replicas',
      headerName: t('instancesColReplicas'),
      width: 96,
      valueGetter: (row) => String(row.replicas.length),
      renderCell: (row) => (
        <Typography sx={{ fontFamily: 'monospace' }}>{row.replicas.length}</Typography>
      ),
    },
    {
      field: 'lastSeen',
      headerName: t('instancesColLastSeen'),
      width: 120,
      valueGetter: (row) => row.lastSeen,
      renderCell: (row) =>
        row.lastSeen === 'never' ? (
          <Tooltip title={t('instancesLastSeenNeverTip')}>
            <Typography color="text.disabled">—</Typography>
          </Tooltip>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
            {row.lastSeen}
          </Typography>
        ),
    },
  ];
}
