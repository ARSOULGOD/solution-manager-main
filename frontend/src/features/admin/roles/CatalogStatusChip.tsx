/**
 * Renders a permission's catalog status (`grantable | proposed | absent`)
 * as one tooltip-backed chip (task 091, `D10` 🔒, §16 Q4). One mapping,
 * used by every row in `RoleEditor` and every role card — so "proposed
 * can't be granted yet" reads identically everywhere. The status values
 * are `@shared/authz/permissions`' `CatalogStatus`; flipping a verb's
 * status is a catalog data change and this component does not move.
 */
import type { ReactElement } from 'react';

import type { CatalogStatus } from '@shared/authz/permissions';
import { t } from '@shared/i18n';
import { Chip, Tooltip } from '@shared/ui';
import type { ChipTone } from '@shared/ui';

interface StatusMeta {
  tone: ChipTone;
  label: string;
  tip: string;
}

const STATUS_META: Record<CatalogStatus, StatusMeta> = {
  grantable: {
    tone: 'ok',
    label: t('adminRoleStatusGrantableLabel'),
    tip: t('adminRoleStatusGrantableTip'),
  },
  proposed: {
    tone: 'warn',
    label: t('adminRoleStatusProposedLabel'),
    tip: t('adminRoleStatusProposedTip'),
  },
  absent: {
    tone: 'idle',
    label: t('adminRoleStatusAbsentLabel'),
    tip: t('adminRoleStatusAbsentTip'),
  },
};

export function catalogStatusMeta(status: CatalogStatus): StatusMeta {
  return STATUS_META[status];
}

export function CatalogStatusChip({ status }: { status: CatalogStatus }): ReactElement {
  const meta = STATUS_META[status];
  return (
    <Tooltip title={meta.tip}>
      <Chip tone={meta.tone} label={meta.label} />
    </Tooltip>
  );
}
