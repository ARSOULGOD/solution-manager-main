/**
 * The attention feed (task 038, UI-D8, UI §A.4). Each row is a real
 * `Link` (not a `div`+`onKeyDown`) so keyboard activation and "open in
 * new tab" come for free. Severity icon/colour comes from
 * `theme.palette.tone` — the same source `Alert`/`StateChip` use
 * (`FE-D5`) — since `AttentionItem` carries no icon field of its own.
 */
import { Box, ButtonBase, Typography, useTheme } from '@mui/material';
import { Link } from 'react-router';

import { t } from '@shared/i18n';
import { Paper, ToneIcon } from '@shared/ui';

import type { AttentionItem } from './homeRepository';
import { ChevronRightIcon } from './icons';

export interface AttentionFeedProps {
  items: readonly AttentionItem[];
}

const rowSx = {
  display: 'flex',
  alignItems: 'center',
  gap: 1.75,
  width: '100%',
  textAlign: 'left' as const,
  py: 1.75,
  px: 0.5,
};

function AttentionRowContent({ item, severity }: { item: AttentionItem; severity: AttentionItem['severity'] }) {
  const theme = useTheme();
  const { main, bg } = theme.palette.tone[severity];

  return (
    <>
      <Box
        aria-hidden
        sx={{
          width: 38,
          height: 38,
          borderRadius: '9px',
          display: 'grid',
          placeItems: 'center',
          flex: 'none',
          backgroundColor: bg,
          color: main,
        }}
      >
        <ToneIcon tone={severity} sx={{ fontSize: 19 }} />
      </Box>
      <Box sx={{ flex: '1 1 auto', minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {item.title}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
          {item.detail}
        </Typography>
      </Box>
    </>
  );
}

// `severity` here is widened to the five values the real adapter can
// produce (`ok`/`idle` included — a `theme.palette.tone` entry exists for
// every one); `ToneIcon`'s own prop type is the subset the rest of the app
// uses, so the cast stays local to this one read.
function toneSeverity(severity: AttentionItem['severity']): 'err' | 'warn' | 'info' | 'ok' {
  if (severity === 'idle') {
    return 'info';
  }
  return severity;
}

function AttentionRow({ item }: { item: AttentionItem }) {
  const severity = toneSeverity(item.severity);

  // `href` is absent when D-1 option B's per-item `getInstance` lookup
  // failed (deleted instance, lost scope, transient error) — the row still
  // reports what happened, it just isn't a dead or wrong link (never
  // fabricated, per the adapter's own contract note).
  if (item.href === undefined) {
    return (
      <Box data-attention-row sx={rowSx}>
        <AttentionRowContent item={item} severity={severity} />
      </Box>
    );
  }

  return (
    <ButtonBase
      component={Link}
      to={item.href}
      data-attention-row
      sx={(t2) => ({
        ...rowSx,
        borderBottom: `1px solid ${t2.palette.divider}`,
        '&:last-of-type': { borderBottom: 'none' },
        '&:hover': { backgroundColor: t2.palette.action.hover },
      })}
    >
      <AttentionRowContent item={item} severity={severity} />
      <ChevronRightIcon sx={{ color: 'text.disabled', flex: 'none' }} />
    </ButtonBase>
  );
}

function EmptyFeedNotice() {
  const theme = useTheme();
  return (
    <Box sx={{ py: 4, textAlign: 'center' }}>
      <Box
        sx={{
          display: 'flex',
          gap: 1,
          justifyContent: 'center',
          alignItems: 'center',
          color: theme.palette.tone.ok.main,
        }}
      >
        <ToneIcon tone="ok" sx={{ fontSize: 20 }} />
        <Typography sx={{ fontWeight: 600 }}>{t('homeAttentionEmptyTitle')}</Typography>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75 }}>
        {t('homeAttentionEmptyBody')}
      </Typography>
    </Box>
  );
}

export function AttentionFeed({ items }: AttentionFeedProps) {
  return (
    <Paper surface="flat" data-attention-feed>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', px: 2.75, pt: 2.25, pb: 0.75 }}>
        <Typography variant="h6" component="h2">
          {t('homeAttentionHeading')}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {t('homeAttentionCount', { count: items.length })}
        </Typography>
      </Box>
      <Box sx={{ px: 2.75, pb: 1.25 }}>
        {items.length === 0 ? (
          <EmptyFeedNotice />
        ) : (
          items.map((item) => <AttentionRow key={item.id} item={item} />)
        )}
      </Box>
    </Paper>
  );
}
