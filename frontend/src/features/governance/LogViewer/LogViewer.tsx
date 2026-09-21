/**
 * Log viewer (task 066, Dec `UI-D10` `I6` `I1`, `dynamic-ui` §5.2). Content
 * is streamed on request and **never persisted** — no `localStorage`, no
 * cache, no log line reaches any persistence API; the buffer below lives
 * only in this component's `useState`, capped at `MAX_LINES`, and is
 * discarded on unmount or component switch. A component with no log
 * endpoint (`logLevelsQuery.data.supported === false`) renders a STATED
 * degradation, never an error and never a silently empty stream — the
 * same `streamLogs()` already yields nothing for that case (see
 * `governanceRepository.ts`), this is the visible explanation why.
 *
 * Raising to `DEBUG` is privileged and audited (`UI-D10`): it requires an
 * explicit confirm (`HighConsequenceDialog`, task 016) before the
 * repository call, and the server's correlation id from that call backs a
 * toast/outcome badge (task 021) — proof the change and the operator's
 * identity reached the audit log, not just a client-side flag.
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
  pushOutcome,
} from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Cluster } from '@shared/ui/layout';

import type { LogLevel, LogLine } from '../governanceRepository';

import type { LogViewerProps } from './LogViewer.types';

const LEVEL_OPTIONS: readonly LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG'];
const PAGE_SIZE = 200;
const MAX_LINES = 1_000;
const BOTTOM_THRESHOLD_PX = 32;

function lineKey(line: LogLine): string {
  return `${line.ts}\u0000${line.message}`;
}

function mergeUnique(existing: readonly LogLine[], incoming: readonly LogLine[], prepend: boolean): readonly LogLine[] {
  const combined = prepend ? [...incoming, ...existing] : [...existing, ...incoming];
  const seen = new Set<string>();
  const unique = combined.filter((line) => {
    const key = lineKey(line);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return prepend ? unique.slice(0, MAX_LINES) : unique.slice(-MAX_LINES);
}

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

export function LogViewer({ instanceId, components, repository }: LogViewerProps) {
  const [component, setComponent] = useState(components[0]);
  const [streaming, setStreaming] = useState(false);
  // Never written anywhere but here — see the header comment (`I6`).
  const [lines, setLines] = useState<readonly LogLine[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  // Text for the live region below — set on start/stop transitions only,
  // never per line (task 067: "not announced on every line... announce
  // start/stop only").
  const [announcement, setAnnouncement] = useState('');
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [logsPending, setLogsPending] = useState(false);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const pageAbortRef = useRef<AbortController | null>(null);
  const preserveScrollRef = useRef<{ height: number; top: number } | null>(null);
  const autoScrollRef = useRef(true);

  const logLevelsQuery = useQuery({
    queryKey: queryKeys.governance.logLevels(instanceId, component ?? ''),
    queryFn: ({ signal }) => repository.logLevels(instanceId, component ?? '', signal),
    enabled: component !== undefined,
  });

  const setLevel = useMutation({
    mutationFn: (nextLevel: LogLevel) =>
      repository.setLogLevel(instanceId, component ?? '', nextLevel),
    onSuccess: ({ correlationId }, nextLevel) => {
      void logLevelsQuery.refetch();
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
    const wasStreaming = abortRef.current !== null;
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
    if (wasStreaming) {
      setAnnouncement(t('logViewerStreamingStoppedAnnouncement'));
    }
  }

  function startStreaming() {
    if (component === undefined) {
      return;
    }
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setStreaming(true);
    setAnnouncement(t('logViewerStreamingStartedAnnouncement'));
    void (async () => {
      try {
        for await (const line of repository.streamLogs(instanceId, component, controller.signal)) {
          if (controller.signal.aborted) {
            return;
          }
          const viewport = viewportRef.current;
          autoScrollRef.current = viewport === null
            || viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= BOTTOM_THRESHOLD_PX;
          setLines((prev) => mergeUnique(prev, [line], false));
        }
      } finally {
        if (!controller.signal.aborted) {
          abortRef.current = null;
          setStreaming(false);
          setAnnouncement(t('logViewerStreamingStoppedAnnouncement'));
        }
      }
    })();
  }

  async function refreshLogs() {
    if (component === undefined) return;
    pageAbortRef.current?.abort();
    const controller = new AbortController();
    pageAbortRef.current = controller;
    setLogsPending(true);
    try {
      const page = await repository.fetchLogs(instanceId, component, { limit: PAGE_SIZE }, controller.signal);
      if (controller.signal.aborted) return;
      autoScrollRef.current = true;
      setLines(page.lines.slice(-MAX_LINES));
      setNextBefore(page.hasOlder ? page.nextBefore : null);
    } finally {
      if (!controller.signal.aborted) {
        pageAbortRef.current = null;
        setLogsPending(false);
      }
    }
  }

  async function loadOlder() {
    if (component === undefined || nextBefore === null) return;
    pageAbortRef.current?.abort();
    const controller = new AbortController();
    pageAbortRef.current = controller;
    const viewport = viewportRef.current;
    if (viewport !== null) {
      preserveScrollRef.current = { height: viewport.scrollHeight, top: viewport.scrollTop };
    }
    setLogsPending(true);
    try {
      const page = await repository.fetchLogs(
        instanceId,
        component,
        { before: nextBefore, limit: PAGE_SIZE },
        controller.signal,
      );
      if (controller.signal.aborted) return;
      setLines((prev) => mergeUnique(prev, page.lines, true));
      setNextBefore(page.hasOlder ? page.nextBefore : null);
    } finally {
      if (!controller.signal.aborted) {
        pageAbortRef.current = null;
        setLogsPending(false);
      }
    }
  }

  function handleComponentChange(next: string) {
    stopStreaming();
    pageAbortRef.current?.abort();
    pageAbortRef.current = null;
    setLines([]);
    setNextBefore(null);
    setComponent(next);
  }

  function handleLevelChange(next: LogLevel) {
    const current = logLevelsQuery.data?.level;
    if (next === 'DEBUG' && current !== 'DEBUG') {
      setConfirmOpen(true);
      return;
    }
    setLevel.mutate(next);
  }

  useEffect(() => {
    // Unmount, or a component switch mid-stream — never leave a stream
    // running against a selection the viewport no longer shows.
    return () => {
      abortRef.current?.abort();
      pageAbortRef.current?.abort();
    };
  }, [component]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (viewport === null) return;
    const preserved = preserveScrollRef.current;
    if (preserved !== null) {
      viewport.scrollTop = preserved.top + viewport.scrollHeight - preserved.height;
      preserveScrollRef.current = null;
    } else if (autoScrollRef.current) {
      viewport.scrollTop = viewport.scrollHeight;
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

      {logLevelsQuery.isPending ? (
        <Skeleton width="100%" height={44} />
      ) : logLevelsQuery.isError ? (
        <ErrorPanel
          title={t('logViewerLoadErrorTitle')}
          detail={t('errorGeneric')}
          onRetry={() => void logLevelsQuery.refetch()}
        />
      ) : !logLevelsQuery.data.supported ? (
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
            <Box sx={{ minWidth: 140 }}>
              <Select
                label={t('logViewerLevelLabel')}
                value={logLevelsQuery.data.level}
                onChange={(value) => handleLevelChange(value as LogLevel)}
                options={LEVEL_OPTIONS.map((lvl) => ({ value: lvl, label: lvl }))}
              />
            </Box>
            {logLevelsQuery.data.level === 'DEBUG' && (
              <Chip tone="warn" label={t('logViewerDebugAuditedChip')} />
            )}
            <Box sx={{ flex: 1 }} />
            <Button variant="outlined" disabled={logsPending} onClick={() => void refreshLogs()}>
              {t('logViewerRefresh')}
            </Button>
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

          {nextBefore !== null && (
            <Box sx={{ mt: 1.5 }}>
              <Button variant="outlined" disabled={logsPending} onClick={() => void loadOlder()}>
                {t('logViewerLoadOlder')}
              </Button>
            </Box>
          )}

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
              lines.map((line) => (
                <Box key={lineKey(line)} sx={{ display: 'flex', gap: 1, whiteSpace: 'pre-wrap' }}>
                  <Box component="span" sx={{ flex: 'none', color: 'text.secondary' }}>
                    {line.ts}
                  </Box>
                  <Box
                    component="span"
                    sx={(theme) => ({
                      flex: 'none',
                      fontWeight: 700,
                      color: theme.palette.tone[LEVEL_TONE[line.level]].main,
                    })}
                  >
                    {line.level}
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
