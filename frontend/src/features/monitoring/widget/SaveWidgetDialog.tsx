/**
 * `SaveWidgetDialog` (task 084, Dec `DM-D6` `DM-D12` `DM-D21` `SM-D31`,
 * `dynamic-metrics` §7, Conflict C7, design §11.1 step 4).
 *
 * **`title` is the only editable field.** Everything else in `draft`
 * (query, mark, range, resolution) is what `ExplorePage` already resolved
 * — this dialog renders it as a read-only summary rather than a second set
 * of controls that could disagree with Explore's own. §11.1 step 3
 * ("Customize mark, axes, legend, colors, thresholds, scale") has no
 * `FE-*` task anywhere in this plan, so there is nothing to edit for those
 * fields yet; `draft` already carries `buildWidgetConfig`'s one default
 * for each (flagged there, not here).
 *
 * **Scope is fixed, not offered.** Conflict C7 / design §12: v1 saves
 * `instance` scope, `shared` layer, full stop — `entity`/`organization`/
 * `personal` are not built. The scope/layer rows below are a STATEMENT,
 * never a control, which is what keeps this dialog from rendering a
 * surface for a scope this console cannot actually honour.
 *
 * **No "create alert" anywhere.** `DM-D15`'s seed exists as a decision,
 * not a shipped affordance — architecture §16.1 states the alerting
 * service does not exist and nobody is notified, and this task's own text
 * says the simpler and safer choice is to omit the affordance entirely in
 * v1, not render it with a caveat. This file has no alert-related code at
 * all, which is the point.
 */
import { Box, Typography } from '@mui/material';
import { useState } from 'react';

import { formatDateTime, timeZoneLabel } from '@shared/format';
import { t } from '@shared/i18n';
import { Alert, Button, Dialog, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

import { resolveTimeRange } from '../explore/timeRangeOptions';

import type { WidgetConfigV1 } from './widgetConfig';

export interface SaveWidgetDialogProps {
  open: boolean;
  /** Built by the caller (`buildWidgetConfig`) from Explore's current draft — `title` starts empty. */
  draft: WidgetConfigV1;
  onClose: () => void;
  onSubmit: (config: WidgetConfigV1) => void;
  submitting?: boolean;
  /** A failed save, rendered in place — design §16, same posture `DeclareInstanceDialog` uses for its own. */
  errorMessage?: string;
}

function rangeSummary(draft: WidgetConfigV1): string {
  const resolved = resolveTimeRange(draft.range);
  return `${formatDateTime(resolved.from)} – ${formatDateTime(resolved.to)} ${timeZoneLabel()}`;
}

export function SaveWidgetDialog({
  open,
  draft,
  onClose,
  onSubmit,
  submitting = false,
  errorMessage,
}: SaveWidgetDialogProps) {
  // Initialised once per mount — the caller mounts this only while open
  // (same "declareOpen &&" pattern `InstancesPage.tsx` uses for
  // `DeclareInstanceDialog`), so a cancelled title is never waiting for
  // the operator the next time they press Save.
  const [title, setTitle] = useState(draft.title);
  const [showTitleError, setShowTitleError] = useState(false);

  function handleSubmit(): void {
    if (title.trim().length === 0) {
      setShowTitleError(true);
      return;
    }
    onSubmit({ ...draft, title: title.trim() });
  }

  return (
    <Dialog
      open={open}
      title={t('saveWidgetDialogTitle')}
      onClose={onClose}
      dirty
      maxWidth="sm"
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={submitting}>
            {t('cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={submitting}>
            {submitting ? t('saveWidgetDialogSubmitting') : t('saveWidgetDialogSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2.5}>
        <Typography variant="body2">{t('saveWidgetDialogIntro')}</Typography>
        <Alert severity="info">{t('saveWidgetDialogDevNote')}</Alert>

        {errorMessage !== undefined && (
          <Alert severity="err" title={t('saveWidgetDialogFailedTitle')}>
            {errorMessage}
          </Alert>
        )}

        <TextField
          label={t('saveWidgetDialogTitleFieldLabel')}
          required
          autoFocus
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          {...(showTitleError && title.trim().length === 0
            ? { error: t('saveWidgetDialogTitleRequired') }
            : {})}
        />

        <Box
          sx={{
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1.5,
            p: 1.5,
            display: 'grid',
            gridTemplateColumns: 'auto 1fr',
            columnGap: 2,
            rowGap: 0.75,
          }}
        >
          <SummaryRow label={t('saveWidgetDialogSummaryMetric')} value={draft.query.metric} />
          <SummaryRow label={t('saveWidgetDialogSummaryMark')} value={draft.mark} />
          <SummaryRow
            label={t('saveWidgetDialogSummaryInstances')}
            value={String(draft.query.selector.instanceIds.length)}
          />
          <SummaryRow label={t('saveWidgetDialogSummaryRange')} value={rangeSummary(draft)} />
          <SummaryRow label={t('saveWidgetDialogSummaryResolution')} value={draft.resolution} />
          <SummaryRow label={t('saveWidgetDialogScopeLabel')} value={t('saveWidgetDialogScopeValue')} />
          <SummaryRow label={t('saveWidgetDialogLayerLabel')} value={t('saveWidgetDialogLayerValue')} />
        </Box>

        <Typography variant="caption" color="text.secondary">
          {t('saveWidgetDialogScopeNote')}
        </Typography>
      </Stack>
    </Dialog>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2">{value}</Typography>
    </>
  );
}
