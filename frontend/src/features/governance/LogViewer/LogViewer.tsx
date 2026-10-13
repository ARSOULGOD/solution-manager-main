/**
 * Log viewer (task 066, Dec `UI-D10` `I6` `I1`, `dynamic-ui` §5.2; re-wired to the
 * production `readLogs`/`getLogLevel`/`setLogLevel` contract by integration plan
 * 010). Content is tailed on request and **never persisted** — no `localStorage`,
 * no cache, no log line reaches any persistence API; the buffer below lives only in
 * this component's `useState`, capped at `MAX_LINES`, and is discarded on unmount or
 * component/placement switch.
 *
 * `readLogs` is a bounded page GET (`limit`/`placementId`/`before`/`after`), not a
 * stream — the old `streamLogs()` AsyncIterable abstraction existed because the
 * transport was unspecified (`Q13`); it is now known, so tailing is an ordinary
 * poll here, on an interval, using each page's last line's `at` as the next `after`
 * cursor. A component with no logger support (`getLogLevel` returns `supported:
 * false`) renders a STATED degradation, never an error and never a silently empty
 * tail.
 *
 * Raising to `DEBUG` is privileged and audited (`UI-D10`): it requires an explicit
 * confirm (`HighConsequenceDialog`, task 016) before the repository call, and the
 * server's correlation id from that call backs a toast/outcome badge (task 021).
 * ⚠️ **Only `mode: 'config'` is ever submitted.** `mode: 'live'` answers `501`
 * (`422` on a direct-HTTP placement) — it is not built (`OR-103`, `T9.4`) — so this
 * viewer never offers it as a live write; every level change is staged as
 * configuration and takes effect at the instance's next restart, exactly like a
 * Designer edit.
 */
import { Box, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import {
  Alert,
  Button,
  Chip,
  HighConsequenceDialog,
  Paper,
  Select,
  Skeleton,
  Tooltip,
  pushOutcome,
} from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Cluster } from '@shared/ui/layout';

import type { LogLevel, LogLine } from '../governanceRepository';

import type { LogViewerProps } from './LogViewer.types';

const LEVEL_OPTIONS: readonly LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG'];
const MAX_LINES = 200;
const POLL_INTERVAL_MS = 3000;

// Standard visually-hidden clip pattern — present for screen readers,
// invisible on screen (task 067's start/stop live region).
const visuallyHiddenSx = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
} as const;

const LEVEL_TONE: Record<LogLevel, 'err' | 'warn' | 'info' | 'idle' | 'neutral'> = {
  ERROR: 'err',
  WARN: 'warn',
  INFO: 'info',
  DEBUG: 'idle',
  TRACE: 'neutral',
};

export function LogViewer({ instanceId, components, placementIds, repository }: LogViewerProps) {
  const [component, setComponent] = useState(components[0]);
  const [placementId, setPlacementId] = useState(placementIds[0]);
  const [streaming, setStreaming] = useState(false);
  // Never written anywhere but here — see the header comment (`I6`).
  const [lines, setLines] = useState<readonly LogLine[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  // Text for the live region below — set on start/stop transitions only,
  // never per line (task 067: "not announced on every line... announce
  // start/stop only").
  const [announcement, setAnnouncement] = useState('');
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const afterRef = useRef<string | undefined>(undefined);

  const logLevelQuery = useQuery({
    queryKey: queryKeys.governance.logLevels(instanceId, component ?? ''),
    queryFn: ({ signal }) => repository.getLogLevel(instanceId, component ?? '', placementId, signal),
    enabled: component !== undefined,
  });

  const setLevel = useMutation({
    mutationFn: (nextLevel: LogLevel) =>
      repository.setLogLevel(instanceId, component ?? '', {
        placementId: placementId ?? instanceId,
        level: nextLevel,
        // `mode: 'live'` is never submitted — see the header note (`T9.4`, `OR-103`).
        mode: 'config',
      }),
    onSuccess: ({ correlationId }, nextLevel) => {
      void logLevelQuery.refetch();
      if (nextLevel === 'DEBUG') {
        pushOutcome(instanceId, {
          title: t('logViewerDebugOutcomeTitle'),
          detail: t('logViewerDebugOutcomeDetail', { component: component ?? '' }),
          correlationId,
          severity: 'warn',
        });
        startStreaming();
      }
    },
  });

  function stopStreaming() {
    const wasStreaming = pollRef.current !== null;
    if (pollRef.current !== null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    setStreaming(false);
    if (wasStreaming) {
      setAnnouncement(t('logViewerStreamingStoppedAnnouncement'));
    }
  }

  async function pollOnce() {
    if (component === undefined) {
      return;
    }
    const page = await repository.readLogs(instanceId, component, {
      ...(placementId !== undefined ? { placementId } : {}),
      ...(afterRef.current !== undefined ? { after: afterRef.current } : {}),
    });
    if (page.lines.length === 0) {
      return;
    }
    afterRef.current = page.lines[page.lines.length - 1]?.at;
    setLines((prev) => [...prev, ...page.lines].slice(-MAX_LINES));
  }

  function startStreaming() {
    if (component === undefined) {
      return;
    }
    if (pollRef.current !== null) {
      clearInterval(pollRef.current);
    }
    setLines([]);
    afterRef.current = undefined;
    setStreaming(true);
    setAnnouncement(t('logViewerStreamingStartedAnnouncement'));
    void pollOnce();
    pollRef.current = setInterval(() => void pollOnce(), POLL_INTERVAL_MS);
  }

  function handleComponentChange(next: string) {
    stopStreaming();
    setLines([]);
    setComponent(next);
  }

  function handlePlacementChange(next: string) {
    stopStreaming();
    setLines([]);
    setPlacementId(next);
  }

  function handleLevelChange(next: LogLevel) {
    const current = logLevelQuery.data?.configuredLevel;
    if (next === 'DEBUG' && current !== 'DEBUG') {
      setConfirmOpen(true);
      return;
    }
    setLevel.mutate(next);
  }

  useEffect(() => {
    // Unmount, or a component/placement switch mid-poll — never leave a
    // poll running against a selection the viewport no longer shows.
    return () => {
      if (pollRef.current !== null) {
        clearInterval(pollRef.current);
      }
    };
  }, [component, placementId]);

  useEffect(() => {
    if (viewportRef.current) {
      viewportRef.current.scrollTop = viewportRef.current.scrollHeight;
    }
  }, [lines]);

  if (component === undefined) {
    return null;
  }

  return (
    <Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
        {t('logViewerNeverPersistedNote')}
      </Typography>

      {logLevelQuery.isPending ? (
        <Skeleton width="100%" height={44} />
      ) : logLevelQuery.isError ? (
        <ErrorPanel
          title={t('logViewerLoadErrorTitle')}
          detail={t('errorGeneric')}
          onRetry={() => void logLevelQuery.refetch()}
        />
      ) : !logLevelQuery.data.supported ? (
        <Alert severity="neutral" title={t('logViewerNoEndpointTitle')}>
          {t('logViewerNoEndpointBody', { component })}
        </Alert>
      ) : (
        <>
          <Cluster>
            <Box sx={{ minWidth: 180 }}>
              <Select
                label={t('logViewerComponentLabel')}
                value={component}
                onChange={handleComponentChange}
                options={components.map((c) => ({ value: c, label: c }))}
              />
            </Box>
            {placementIds.length > 1 && (
              <Box sx={{ minWidth: 180 }}>
                <Select
                  label={t('logViewerPlacementLabel')}
                  value={placementId ?? placementIds[0]!}
                  onChange={handlePlacementChange}
                  options={placementIds.map((id) => ({ value: id, label: id }))}
                />
              </Box>
            )}
            <Box sx={{ minWidth: 140 }}>
              <Select
                label={t('logViewerLevelLabel')}
                value={logLevelQuery.data.configuredLevel ?? 'INFO'}
                onChange={(value) => handleLevelChange(value as LogLevel)}
                options={LEVEL_OPTIONS.map((lvl) => ({ value: lvl, label: lvl }))}
              />
            </Box>
            {/* `mode: live` is not built (`T9.4`, `OR-103`) — stated as unavailable,
                never offered as a choice the user could attempt and watch fail. */}
            <Tooltip title={t('logViewerLiveModeUnavailable')}>
              <span>
                <Chip tone="neutral" label="config mode only" />
              </span>
            </Tooltip>
            {logLevelQuery.data.configuredLevel === 'DEBUG' && (
              <Chip tone="warn" label={t('logViewerDebugAuditedChip')} />
            )}
            <Box sx={{ flex: 1 }} />
            <Button
              variant={streaming ? 'outlined' : 'contained'}
              onClick={() => (streaming ? stopStreaming() : startStreaming())}
            >
              {streaming ? t('logViewerPause') : t('logViewerStream')}
            </Button>
          </Cluster>

          <Box aria-live="polite" aria-atomic="true" sx={visuallyHiddenSx}>
            {announcement}
          </Box>

          <Box
            ref={viewportRef}
            role="region"
            aria-label={t('logViewerViewportLabel')}
            sx={{
              mt: 1.5,
              height: 260,
              overflowY: 'auto',
              fontFamily: 'monospace',
              fontSize: 13,
              backgroundColor: 'action.hover',
              borderRadius: 1,
              p: 1.5,
              border: '1px solid',
              borderColor: 'divider',
            }}
          >
            {lines.length === 0 ? (
              <Typography variant="caption" color="text.secondary">
                {streaming ? t('logViewerWaiting') : t('logViewerIdle')}
              </Typography>
            ) : (
              lines.map((line, index) => (
                <Box key={index} sx={{ display: 'flex', gap: 1, whiteSpace: 'pre-wrap' }}>
                  <Box component="span" sx={{ flex: 'none', color: 'text.secondary' }}>
                    {line.at}
                  </Box>
                  <Box
                    component="span"
                    sx={(theme) => ({
                      flex: 'none',
                      fontWeight: 700,
                      color:
                        theme.palette.tone[LEVEL_TONE[(line.level as LogLevel) ?? 'INFO']].main,
                    })}
                  >
                    {line.level ?? ''}
                  </Box>
                  <Box component="span">{line.message}</Box>
                </Box>
              ))
            )}
          </Box>
        </>
      )}

      {confirmOpen && (
        <HighConsequenceDialog
          open={confirmOpen}
          title={t('logViewerDebugDialogTitle')}
          consequence="high"
          confirmLabel={t('logViewerDebugConfirm')}
          dirty={setLevel.isPending}
          onConfirm={() => {
            setLevel.mutate('DEBUG');
            setConfirmOpen(false);
          }}
          onCancel={() => setConfirmOpen(false)}
        >
          <Typography variant="body2">{t('logViewerDebugDialogBody')}</Typography>
        </HighConsequenceDialog>
      )}
    </Paper>
  );
}
