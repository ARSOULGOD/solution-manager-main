/**
 * Connections catalog list (`UI-D27`, spec §8.1). One tab per connection
 * type, generated from `getConnectionTypes` (plan 013 Task 5) — never a
 * hardcoded pair, and never the static `DESCRIPTORS` testdouble import a
 * pre-Task-5 revision used — so a third connection type needs no change to
 * this file, only a new descriptor served by the real API.
 *
 * ⚠️ This layout is spec §8.1, built by analogy to Targets/Admin lists.
 * There is no visual-reference screen for Connections (`FE-D30` only
 * governs screens the reference actually contains) — no brand colours are
 * invented here, only `@shared/ui` tokens the reference-governed screens
 * already use (`I2`, `FE-D5`).
 *
 * **No secret ever renders here.** The page reads `attributes` only — a
 * secret attribute reaches here only as the gateway's `{ set: true }`
 * redaction (`I6`), which `formatAttribute` renders as a literal dash via
 * the same "no plain string/number/boolean" branch any other object value
 * takes — and `deriveColumns` (`columns.ts`'s own filter) never includes a
 * secret-typed key besides.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { Button, Chip, EmptyState, PageHeader, Paper, Skeleton, Switch } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { PageWidth, Stack } from '@shared/ui/layout';

import type { ConnectionDescriptor, ConnectionRow, ConnectionsRepository, ConnectionTypeKey }
  from '../connectionsRepository';

import { deriveColumns } from './columns';
import { ConnectionTypeIcon } from './connectionTypeIcons';

export interface ConnectionsPageProps {
  repository: ConnectionsRepository;
  /** Task 8 opens the detail drawer from a row click. */
  onOpen?: (row: ConnectionRow) => void;
  /** Task 8/9 open the create form, seeded with the active tab's type. */
  onCreate?: (type: ConnectionTypeKey) => void;
  /** Lets the route compose Remote Server-only sections below this catalog. */
  onActiveTypeChange?: (type: ConnectionTypeKey) => void;
}

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

function formatScope(row: ConnectionRow): string {
  const scope = row.ownershipScope;
  const ids = [scope.subOrganizationId, scope.environmentId, scope.solutionId, scope.instanceId].filter(
    (id): id is string => id !== undefined,
  );
  return ids.length === 0 ? t('targetsPreviewScopeOrganizationWide') : ids.join(' / ');
}

function formatAttribute(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return '—';
  }
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : '—';
}

export function ConnectionsPage({
  repository,
  onOpen = () => {},
  onCreate = () => {},
  onActiveTypeChange = () => {},
}: ConnectionsPageProps) {
  const [activeType, setActiveType] = useState<ConnectionTypeKey | null>(null);
  const [showInactive, setShowInactive] = useState(false);

  const typesQuery = useQuery({
    queryKey: queryKeys.connections.types(),
    queryFn: ({ signal }) => repository.getConnectionTypes(signal),
  });

  // One fetch, all types and both statuses: tab COUNTS need every type's
  // active rows at once, not just the active tab's — a per-tab fetch would
  // leave the tabs the operator has not clicked showing a stale/empty
  // count. `includeInactive: true` is likewise so the "Show inactive"
  // toggle is instant, client-side state rather than a second round trip.
  const connectionsQuery = useQuery({
    queryKey: queryKeys.connections.list(),
    queryFn: ({ signal }) => repository.list(signal),
  });

  const descriptors = useMemo<ConnectionDescriptor[]>(() => typesQuery.data ?? [], [typesQuery.data]);
  const typeKeys = useMemo(() => descriptors.map((descriptor) => descriptor.connectionType), [descriptors]);
  const descriptorByType = useMemo(() => {
    const map = new Map<ConnectionTypeKey, ConnectionDescriptor>();
    for (const descriptor of descriptors) {
      map.set(descriptor.connectionType, descriptor);
    }
    return map;
  }, [descriptors]);

  const rowsByType = useMemo(() => {
    const grouped = new Map<ConnectionTypeKey, ConnectionRow[]>();
    for (const key of typeKeys) {
      grouped.set(key, []);
    }
    for (const row of connectionsQuery.data ?? []) {
      if (!grouped.has(row.connectionType)) {
        grouped.set(row.connectionType, []);
      }
      grouped.get(row.connectionType)?.push(row);
    }
    return grouped;
  }, [connectionsQuery.data, typeKeys]);

  const resolvedActiveType = activeType ?? typeKeys[0] ?? null;

  if (connectionsQuery.isPending || typesQuery.isPending) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <Skeleton width={200} height={32} />
          <Skeleton width="100%" height={280} />
        </Stack>
      </PageWidth>
    );
  }

  if (connectionsQuery.isError || typesQuery.isError) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <PageHeader title={t('connectionsTitle')} sub={t('connectionsSub')} />
          <ErrorPanel
            title={t('connectionsErrorTitle')}
            detail={describeError(connectionsQuery.error ?? typesQuery.error)}
            onRetry={() => {
              void connectionsQuery.refetch();
              void typesQuery.refetch();
            }}
          />
        </Stack>
      </PageWidth>
    );
  }

  if (resolvedActiveType === null) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <PageHeader title={t('connectionsTitle')} sub={t('connectionsSub')} />
          <EmptyState title={t('connectionsEmptyTitle')} body={t('connectionsEmptyBody')} />
        </Stack>
      </PageWidth>
    );
  }

  const descriptor = descriptorByType.get(resolvedActiveType);
  const columns = descriptor === undefined ? [] : deriveColumns(descriptor);
  const allRowsForType = rowsByType.get(resolvedActiveType) ?? [];
  const visibleRows = showInactive
    ? allRowsForType
    : allRowsForType.filter((row) => row.active);

  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <PageHeader
          title={t('connectionsTitle')}
          sub={t('connectionsSub')}
          actions={
            <Button variant="outlined" size="sm" onClick={() => onCreate(resolvedActiveType)}>
              {t('connectionsNewAction')}
            </Button>
          }
        />

        <Paper surface="flat">
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 2,
              borderBottom: 1,
              borderColor: 'divider',
              px: 1,
            }}
          >
            <Box role="tablist" sx={{ display: 'flex', gap: 0.5, flex: '1 1 auto', minWidth: 0 }}>
              {typeKeys.map((key) => {
                const typeDescriptor = descriptorByType.get(key);
                const activeCount = (rowsByType.get(key) ?? []).filter((row) => row.active).length;
                const isSelected = key === resolvedActiveType;
                return (
                  <Box
                    key={key}
                    component="button"
                    type="button"
                    role="tab"
                    aria-selected={isSelected}
                    tabIndex={isSelected ? 0 : -1}
                    onClick={() => {
                      setActiveType(key);
                      onActiveTypeChange(key);
                    }}
                    sx={(theme) => ({
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1,
                      border: 'none',
                      background: 'none',
                      cursor: 'pointer',
                      font: 'inherit',
                      fontWeight: isSelected ? 600 : 400,
                      px: 2,
                      py: 1.5,
                      color: isSelected ? theme.palette.primary.main : theme.palette.text.secondary,
                      borderBottom: '2px solid',
                      borderColor: isSelected ? 'primary.main' : 'transparent',
                      transition: theme.transitions.create(['color', 'border-color']),
                      '&:hover': {
                        color: theme.palette.primary.main,
                      },
                    })}
                  >
                    <ConnectionTypeIcon connectionType={key} fontSize="small" />
                    {`${typeDescriptor?.label ?? key}s (${String(activeCount)})`}
                  </Box>
                );
              })}
            </Box>

            <Box sx={{ display: 'flex', alignItems: 'center', py: 1 }}>
              <Switch
                label={t('connectionsShowInactive')}
                checked={showInactive}
                onChange={setShowInactive}
              />
            </Box>
          </Box>

          {visibleRows.length === 0 ? (
            <Box sx={{ p: 3 }}>
              <EmptyState
                title={t('connectionsEmptyTitle')}
                body={t('connectionsEmptyBody')}
                action={
                  <Button variant="outlined" size="sm" onClick={() => onCreate(resolvedActiveType)}>
                    {t('connectionsNewAction')}
                  </Button>
                }
              />
            </Box>
          ) : (
            <Box sx={{ overflowX: 'auto' }} data-reflow-exception="data-table">
              <Table size="small">
                <TableHead>
                  <TableRow
                    sx={{
                      '& th': {
                        fontWeight: 600,
                        backgroundColor: 'action.hover',
                        color: 'text.secondary',
                      },
                    }}
                  >
                    <TableCell>{t('connectionsColName')}</TableCell>
                    {columns.map((column) => (
                      <TableCell key={column.key}>{column.label}</TableCell>
                    ))}
                    <TableCell>{t('connectionsColScope')}</TableCell>
                    <TableCell>{t('connectionsColUsed')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {visibleRows.map((row) => (
                    <TableRow
                      key={row.connectionId}
                      hover
                      onClick={() => onOpen(row)}
                      sx={{
                        cursor: 'pointer',
                        '&:last-of-type td': { borderBottom: 'none' },
                      }}
                    >
                      <TableCell>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
                            {row.name}
                          </Typography>
                          {!row.active && (
                            <Chip tone="idle" label={t('connectionsInactiveChip')} />
                          )}
                        </Box>
                      </TableCell>
                      {columns.map((column) => (
                        <TableCell key={column.key}>
                          {formatAttribute(row.attributes[column.key])}
                        </TableCell>
                      ))}
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {formatScope(row)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip tone="neutral" label={String(row.usedByCount)} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
          )}
        </Paper>
      </Stack>
    </PageWidth>
  );
}
