/**
 * `<HiddenDefaultWidget>` (task 087, Dec `SM-D31` `DM-D18`, Design §12,
 * Conflict C7 — mirrors the visual reference's own `.hidden-def` card in
 * `docs/design/frontend/reference/app/dashboards.jsx`). What a hidden
 * `product-default` widget renders **in its own grid slot** instead of the
 * live `WidgetFrame` — hiding is an override stored in the layer above, so
 * a later package can still improve the default and the operator's choice
 * survives (design §12). `DashboardGrid` decides which of the two to
 * render per widget; this component only knows how to state the one fact
 * that matters — hidden, not deleted — and offer the way back.
 *
 * Reuses `EmptyState`, same precedent `WidgetFrame.tsx`'s own
 * `TooNewContent` already set for an `h2` inside a widget card — a widget
 * title is a visual label (`component="div"`, never a real heading), but a
 * whole-card placeholder state is exactly what `EmptyState` is for
 * elsewhere in this codebase (`ExploreResult.tsx`, `SingleStatMark.tsx`).
 */
import { Box } from '@mui/material';

import { t } from '@shared/i18n';
import { Button, EmptyState, Paper } from '@shared/ui';

/** One `DashboardGrid` row (150px) — a hidden default is never taller than the smallest real widget. */
const HIDDEN_DEFAULT_MIN_HEIGHT = 150;

export interface HiddenDefaultWidgetProps {
  title: string;
  onRestore: () => void;
  restorePending: boolean;
}

export function HiddenDefaultWidget({
  title,
  onRestore,
  restorePending,
}: HiddenDefaultWidgetProps) {
  return (
    // Task 129 — `dashed` and `fill`. The reference's `.widget.hidden-def`
    // is a dashed card (`theme.css:547`) and this file's header above
    // already said so, but nothing actually drew the dash until now.
    // `fill` makes the placeholder occupy its whole grid slot, so a hidden
    // default sits at the same height as the widget it stands in for
    // rather than collapsing to its text.
    <Paper surface="flat" fill dashed>
      <Box
        sx={{
          flex: 1,
          minHeight: HIDDEN_DEFAULT_MIN_HEIGHT,
          display: 'grid',
          placeItems: 'center',
          padding: '12px 14px',
        }}
      >
        <EmptyState
          title={t('hiddenDefaultWidgetTitle', { title })}
          body={t('hiddenDefaultWidgetBody')}
          action={
            <Button size="sm" variant="outlined" onClick={onRestore} disabled={restorePending}>
              {t('hiddenDefaultWidgetRestore')}
            </Button>
          }
        />
      </Box>
    </Paper>
  );
}
