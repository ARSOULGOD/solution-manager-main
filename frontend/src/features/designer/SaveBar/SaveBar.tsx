/**
 * The unblocked half of `FE-D18`: writing a draft to the DP's own
 * configuration-of-record (task 055, Design §10.1/§13, UI §A.8 save bar).
 * Apply/restart (clause B) is `DECISIONS.md` open item 1 — this component
 * only ever calls `repository.save()`; the `onApply` it exposes is a seam
 * task 061 fills in once that protocol exists, never wired to anything here.
 *
 * **Save vs Apply never collapse** (`FE-D18`): Save writes the platform's
 * own record; Apply reaches the MI and needs a restart. The two disabled
 * rules encode that as *rules*, not copy — Save needs `blockingCount === 0`
 * and `dirtyCount > 0`; Apply needs `dirtyCount === 0`, i.e. what's on
 * screen matches what's on record. That second condition is intentionally
 * *not* "a save happened this session" — a config saved in an earlier
 * session and never applied is exactly as apply-able as one just saved
 * here, and `dirtyCount` (a deep comparison against the caller's own
 * baseline, task 052) is already true whenever those two do differ.
 *
 * **No optimistic update** (`FE-D20`): this component never predicts a
 * save's outcome. `dirtyCount` and `baseStateVersion` are both owned by the
 * caller and only ever change in response to `onSaved` — the same
 * `onLoaded`-callback split `LoadPackageDialog`/`SolutionsPage` (task 040)
 * already use, so a save failure leaves the caller's baseline untouched
 * with no rollback step required.
 *
 * The success toast is pushed from *here*, not deferred to a page-level
 * caller the way `SolutionsPage` pushes `LoadPackageDialog`'s toast —
 * there is no Designer page yet for that caller to be, and task 055's own
 * acceptance criteria pins the exact success copy, so leaving it unpushed
 * until some future task would leave a specified requirement unmet.
 * `onSaveError` exists for the opposite case: a `409` (stale `stateVersion`)
 * needs task 056's own conflict UI, and a `422` (plan 008 Task 2: the
 * server's own projection disagrees with this client's `rendered[]`) needs
 * `DesignerForm`'s distinct "stale base, reload" state — both reach that
 * callback and suppress the inline `Alert` rather than showing a generic
 * error alongside either.
 *
 * **Warning override gate** (task 057, `UI-D16`): Save's disabled rule
 * still never gates on `warnings` (task 055's own scope stays unchanged),
 * but clicking Save with outstanding warnings opens `OverrideReasonDialog`
 * instead of saving immediately. **Plan 008 Task 2 found `stageConfig` has
 * nowhere to carry the confirmed reason** (`additionalProperties: false`,
 * no `overrideReason` field) — the dialog still runs and still gates the
 * click, but the reason itself is accepted by the mutation and discarded,
 * never sent (see `SaveBar.types.ts`'s own note).
 */
import { Box, Typography, useTheme } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState, type ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Tooltip, pushToast } from '@shared/ui';

import { OverrideReasonDialog } from '../OverrideReasonDialog';

import type { SaveBarProps } from './SaveBar.types';

/**
 * `Tooltip` (task 014) wraps its child in a focusable `role="button"` span
 * — correct for explaining a DISABLED control (nothing else there to
 * focus) but a nested-interactive a11y violation over an enabled one,
 * which already has its own accessible name (`SolutionCard`, task 039,
 * makes the same disabled-only choice for its own tooltip-wrapped button).
 * So the explanatory tooltip below only ever wraps the disabled state.
 */
function tooltipIfDisabled(disabled: boolean, title: string, button: ReactElement): ReactElement {
  return disabled ? <Tooltip title={title}>{button}</Tooltip> : button;
}

export function SaveBar({
  instanceId,
  repository,
  payload,
  baseStateVersion,
  componentSelection,
  rendered,
  dirtyCount,
  blockingCount,
  warnings,
  onSaved,
  onSaveError,
  onApply,
}: SaveBarProps): ReactElement {
  const theme = useTheme();
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const recommendationCount = warnings.length;

  const save = useMutation({
    // `_overrideReason` is accepted (the confirmation dialog still runs) and discarded — the real
    // `stageConfig` body has no field for it (`SaveBar.types.ts`'s own note on the gap).
    mutationFn: (_overrideReason?: string) =>
      repository.save(instanceId, {
        baseStateVersion,
        componentSelection: {
          instances: componentSelection.map((selection) => ({
            instanceKey: selection.instanceId,
            type: selection.type,
          })),
        },
        draftValues: payload,
        rendered,
      }),
    onSuccess: (result) => {
      pushToast({
        title: t('saveBarSuccessTitle'),
        detail: t('saveBarSuccessDetail'),
        correlationId: result.correlationId,
        severity: 'ok',
      });
      onSaved(result);
    },
    onError: (error) => onSaveError?.(error),
  });

  const saveDisabled = blockingCount > 0 || dirtyCount === 0 || save.isPending;
  // `onApply` is omitted when the caller cannot identify a live placement and
  // saved generation. Without this clause the button would render enabled at
  // `dirtyCount === 0` but silently do nothing on click.
  const applyDisabled = blockingCount > 0 || dirtyCount > 0 || onApply === undefined;
  const isConflict = save.error instanceof ApiError && save.error.status === 409;

  const handleSaveClick = () => {
    if (recommendationCount > 0) {
      setOverrideDialogOpen(true);
      return;
    }
    save.mutate(undefined);
  };

  const handleOverrideConfirm = (reason: string) => {
    setOverrideDialogOpen(false);
    save.mutate(reason);
  };

  return (
    <Box
      sx={{
        position: 'sticky',
        bottom: 0,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        borderTop: `1px solid ${theme.palette.divider}`,
        backgroundColor: theme.palette.background.paper,
        px: 2,
        py: 1.5,
      }}
    >
      {save.isError && !isConflict ? (
        <Alert severity="err" title={t('saveBarErrorTitle')} fullWidth>
          {t('saveBarErrorDetail')}
        </Alert>
      ) : null}

      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1.5 }}>
        <Box
          sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, flex: '1 1 auto' }}
        >
          <Typography
            variant="body2"
            sx={{ fontWeight: dirtyCount > 0 ? theme.typography.fontWeightMedium : undefined }}
          >
            {dirtyCount === 0
              ? t('saveBarNoChanges')
              : t('saveBarDirtyCount', { count: dirtyCount })}
          </Typography>
          {blockingCount > 0 ? (
            <Chip tone="err" label={t('saveBarBlockingChip', { count: blockingCount })} />
          ) : null}
          {recommendationCount > 0 ? (
            <Chip
              tone="warn"
              label={t('saveBarRecommendationChip', { count: recommendationCount })}
            />
          ) : null}
        </Box>

        {tooltipIfDisabled(
          saveDisabled,
          t('saveBarSaveTooltip'),
          <Button variant="outlined" disabled={saveDisabled} onClick={handleSaveClick}>
            {t('saveBarSaveAction')}
          </Button>,
        )}
        {tooltipIfDisabled(
          applyDisabled,
          t('saveBarApplyDisabledTip'),
          <Button disabled={applyDisabled} {...(onApply !== undefined ? { onClick: onApply } : {})}>
            {t('saveBarApplyAction')}
          </Button>,
        )}
      </Box>

      <OverrideReasonDialog
        open={overrideDialogOpen}
        warnings={warnings}
        onCancel={() => setOverrideDialogOpen(false)}
        onConfirm={handleOverrideConfirm}
      />
    </Box>
  );
}
