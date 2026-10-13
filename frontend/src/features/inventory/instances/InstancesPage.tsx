/**
 * Instance inventory grid (task 041, `REQ-002`, `UI-D9`, `SM-D22`, UI §A.6).
 *
 * `Declare instance` (task 115, superseding 045's stub) opens
 * `DeclareInstanceDialog` and posts through `repository.declare()` against
 * the real `declareInstance` contract (integration plan 007, 2026-10-09)
 * — see `instancesRepository.ts` and `declareForm.ts` for what that
 * contract carries and what the pre-007 field set invented.
 *
 * On success the new row is focused via `?focus=`, not merely appended:
 * a grid that silently grows is a change the operator has to hunt for.
 */
import { Box } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';

import { ApiError } from '@shared/api';
import type { Instance } from '@shared/api/contracts/instances';
import { queryKeys } from '@shared/api/queryKeys';
import type { HierarchyRepository } from '@shared/hierarchy';
import { t } from '@shared/i18n';
import {
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  Skeleton,
  ToggleGroup,
  Tooltip,
} from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { PageWidth, Stack } from '@shared/ui/layout';
import { pushToast } from '@shared/ui/Toaster';

import type { SolutionsRepository } from '../solutions';

import { BulkRestartDialog } from './BulkRestartDialog';
import { buildInstanceColumns } from './columns';
import type { ConnectionPickerRepository } from './connectionPickerRepository';
import { DeclareInstanceDialog } from './DeclareInstanceDialog';
import type { TargetsRepository } from './targetPickerRepository';
import type { DeclareInstanceInput, InstancesRepository } from './instancesRepository';
import { ReplicaDetailPanel } from './ReplicaDetailPanel';
import { UndeclareInstancesDialog } from './UndeclareInstancesDialog';

export interface InstancesPageProps {
  solutionId: string;
  repository: InstancesRepository;
  solutionsRepository: SolutionsRepository;
  /** Backs the declare dialog's Org → Sub-org → Environment cascade (`FE-D46`) and the grid's env column. */
  hierarchyRepository: HierarchyRepository;
  /** Backs the declare dialog's `dp-remote` Connection picker (integration plan 014). */
  connectionsRepository: ConnectionPickerRepository;
  /** New target picker; optional to keep fixture-only consumers compatible. */
  targetsRepository?: Pick<TargetsRepository, 'list'>;
}

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

function InstancesLoadingSkeleton() {
  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <Box>
          <Skeleton width={200} height={32} />
          <Box sx={{ mt: 1 }}>
            <Skeleton width={480} height={16} />
          </Box>
        </Box>
        <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1.5, p: 2 }}>
          {[0, 1, 2, 3, 4].map((i) => (
            <Box key={i} sx={{ display: 'flex', gap: 3, py: 1.5 }}>
              <Skeleton width={180} height={14} />
              <Skeleton width={64} height={14} />
              <Skeleton width={120} height={14} />
              <Skeleton width={160} height={14} />
              <Skeleton width={140} height={14} />
            </Box>
          ))}
        </Box>
      </Stack>
    </PageWidth>
  );
}

type EnvFilter = 'ALL' | (string & {});

/**
 * Disabled ONLY while the solution catalog is still loading, and never
 * silently: an instance is declared against one of the package's versions,
 * so the action cannot be offered before they can be enumerated. The
 * `Tooltip` states that reason — a disabled control with no stated reason
 * is the failure mode this codebase calls out everywhere else.
 *
 * The catalog query stays NON-BLOCKING for the table (the grid is the
 * primary content and renders regardless); it gates only this action.
 */
function DeclareInstanceButton({
  onClick,
  disabled = false,
}: {
  onClick: () => void;
  disabled?: boolean;
}) {
  const button = (
    <Button variant="outlined" size="sm" onClick={onClick} disabled={disabled}>
      {t('instancesDeclareAction')}
    </Button>
  );
  return disabled ? (
    <Tooltip title={t('instancesDeclareDisabledLoadingTip')}>
      <span>{button}</span>
    </Tooltip>
  ) : (
    button
  );
}

export function InstancesPage({
  solutionId,
  repository,
  solutionsRepository,
  hierarchyRepository,
  connectionsRepository,
  targetsRepository,
}: InstancesPageProps) {
  const instancesQuery = useQuery({
    queryKey: queryKeys.instances.list(solutionId),
    queryFn: ({ signal }) => repository.list(solutionId, {}, signal),
  });
  // Memoized: `?? []` would otherwise mint a new array identity every
  // render while the query is pending, which would defeat the
  // `environmentIds` memo just below (its one job is to avoid re-walking
  // the hierarchy tree on every render).
  const instances = useMemo(() => instancesQuery.data ?? [], [instancesQuery.data]);

  // Non-blocking: the solution's display name is a nicety for the page
  // title, never a gate on the table itself — a failed/slow lookup here
  // still lets the instance grid (the primary content) render.
  const solutionsQuery = useQuery({
    queryKey: queryKeys.solutions.list(),
    queryFn: ({ signal }) => solutionsRepository.list(signal),
  });
  const solution = solutionsQuery.data?.find((candidate) => candidate.solutionId === solutionId);
  const title = solution?.name ?? solutionId;

  // `Instance` carries `environmentId` only (no name) — resolved once per
  // distinct id across the whole list, not once per row, so a grid of a
  // hundred instances against three environments makes three lookups, not
  // a hundred. `environmentPath` itself caches the walked tree, so a
  // second distinct id in the same render is also free.
  const environmentIds = useMemo(
    () => [...new Set(instances.map((instance) => instance.environmentId))].sort(),
    [instances],
  );
  const environmentNamesQuery = useQuery({
    queryKey: ['instances', 'environment-names', solutionId, environmentIds] as const,
    queryFn: async ({ signal }) => {
      const entries = await Promise.all(
        environmentIds.map(async (id) => {
          const path = await hierarchyRepository.environmentPath(id, signal);
          return [id, path?.env] as const;
        }),
      );
      return new Map(entries.filter((entry): entry is [string, string] => entry[1] !== undefined));
    },
    enabled: environmentIds.length > 0,
  });
  const emptyEnvironmentNames = useMemo(() => new Map<string, string>(), []);
  const environmentNames = environmentNamesQuery.data ?? emptyEnvironmentNames;
  const environmentFilterOptions = useMemo<readonly { value: EnvFilter; label: string }[]>(
    () => [
      { value: 'ALL', label: t('instancesEnvAll') },
      ...[...new Set(environmentNames.values())]
        .sort((left, right) => left.localeCompare(right))
        .map((env) => ({ value: env, label: env })),
    ],
    [environmentNames],
  );

  // Selection and focus are URL state (`FE-D6`) — a link reproduces
  // exactly what the sender saw. Env filter and quick search are
  // component/DataTable state — they reset on reload by design.
  const [searchParams, setSearchParams] = useSearchParams();
  const selection = useMemo(() => {
    const raw = searchParams.get('sel');
    return raw === null ? [] : raw.split(',').filter((id) => id !== '');
  }, [searchParams]);
  const focusRowId = searchParams.get('focus') ?? undefined;
  const [envFilter, setEnvFilter] = useState<EnvFilter>('ALL');
  const [bulkRestartOpen, setBulkRestartOpen] = useState(false);
  const [declareOpen, setDeclareOpen] = useState(false);
  const [undeclareOpen, setUndeclareOpen] = useState(false);

  const queryClient = useQueryClient();
  const declareMutation = useMutation({
    mutationFn: ({ environmentId, input }: { environmentId: string; input: DeclareInstanceInput }) =>
      repository.declare(environmentId, input),
    onSuccess: ({ instance, correlationId }) => {
      pushToast({
        title: t('instancesDeclareSucceededTitle'),
        detail: t('instancesDeclareSucceededDetail', { name: instance.name }),
        correlationId,
      });
      void queryClient.invalidateQueries({ queryKey: queryKeys.instances.list(solutionId) });
      setDeclareOpen(false);
      declareMutation.reset();
      focusRow(instance.instanceId);
    },
    // No `onError`: the dialog stays open and renders the failure in place
    // (design §16) rather than closing over a mutation the operator would
    // have to re-key from memory.
  });

  /**
   * Undeclare removes SM's RECORD of each instance and nothing else — see
   * `UndeclareInstancesDialog` and `instancesRepository.ts` on why no
   * lifecycle verb is offered alongside it.
   *
   * `allSettled`, not `all`: with several instances selected, a failure on
   * one must not hide the ones that succeeded, and the operator needs to
   * know exactly which are still declared. A rejected `all` would leave
   * them guessing.
   */
  const undeclareMutation = useMutation({
    mutationFn: async (selectedInstances: readonly Instance[]) => {
      const results = await Promise.allSettled(
        selectedInstances.map((instance) => repository.undeclare(instance.instanceId)),
      );
      const failed = selectedInstances.filter((_, index) => results[index]?.status === 'rejected');
      const firstOk = results.find((result) => result.status === 'fulfilled');
      return {
        removed: selectedInstances.filter((_, index) => results[index]?.status === 'fulfilled'),
        failed,
        // Every toast quotes a SERVER correlation id (`FE-D13`); the first
        // successful removal's stands for the batch. A batch where nothing
        // succeeded has none, and reports as an error instead of a toast.
        correlationId: firstOk?.status === 'fulfilled' ? firstOk.value.correlationId : undefined,
      };
    },
    onSuccess: ({ removed, failed, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.instances.list(solutionId) });

      if (removed.length > 0 && correlationId !== undefined) {
        pushToast({
          title: t('instancesUndeclareSucceededTitle', { count: removed.length }),
          correlationId,
        });
      }
      // Removed rows must leave `?sel=` — a selection pointing at rows that
      // no longer exist would re-arm the bulk actions against nothing.
      const removedIds = new Set(removed.map((instance) => instance.instanceId));
      handleSelectionChange(selection.filter((id) => !removedIds.has(id)));

      if (failed.length === 0) {
        setUndeclareOpen(false);
        undeclareMutation.reset();
      }
    },
  });

  function describeUndeclareFailure(): string | undefined {
    const data = undeclareMutation.data;
    if (data !== undefined && data.failed.length > 0) {
      return t('instancesUndeclareFailedDetail', {
        names: data.failed.map((instance) => instance.name).join(', '),
      });
    }
    if (undeclareMutation.isError) {
      return describeQueryError(undeclareMutation.error);
    }
    return undefined;
  }

  function focusRow(instanceId: string): void {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.set('focus', instanceId);
        return next;
      },
      { replace: true },
    );
  }

  function describeDeclareError(error: unknown): string {
    if (error instanceof ApiError) {
      return error.message;
    }
    return t('errorGeneric');
  }

  function handleSelectionChange(ids: string[]): void {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (ids.length > 0) {
          next.set('sel', ids.join(','));
        } else {
          next.delete('sel');
        }
        return next;
      },
      { replace: true },
    );
  }

  if (instancesQuery.isPending) {
    return <InstancesLoadingSkeleton />;
  }

  if (instancesQuery.isError) {
    return (
      <PageWidth size="wide">
        <Stack gap={3}>
          <PageHeader title={title} sub={t('instancesSubtitle')} />
          <ErrorPanel
            title={t('instancesErrorTitle')}
            detail={describeQueryError(instancesQuery.error)}
            onRetry={() => void instancesQuery.refetch()}
          />
        </Stack>
      </PageWidth>
    );
  }

  const columns = buildInstanceColumns(solutionId, environmentNames);
  const rows =
    envFilter === 'ALL'
      ? instances
      : instances.filter((row) => environmentNames.get(row.environmentId) === envFilter);
  // Selected instances come from the full (unfiltered-by-env) list — a
  // selection made before narrowing the Env filter still restarts, since
  // the env toggle is display-only and never mutates `?sel=` (FE-042).
  const selectedInstances = instances.filter((row) => selection.includes(row.instanceId));
  // A selection may name a row the env filter is hiding — that is the point
  // of `?sel=` surviving a filter change (FE-042). It may NEVER name a row
  // that does not exist: after an undeclare the grid prunes the removed row
  // and `DataTable` restores its id as "filtered away, not deselected",
  // which is right for a filter and wrong for a removal — the component
  // cannot tell the two apart. Resolving it here, against the authoritative
  // instance list, is what stops a removed instance from staying "selected"
  // and re-arming the bulk actions against a row that is gone.
  const liveSelection = selection.filter((id) => instances.some((row) => row.instanceId === id));

  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <PageHeader
          title={title}
          sub={t('instancesSubtitle')}
          actions={
            <DeclareInstanceButton
              onClick={() => setDeclareOpen(true)}
              disabled={solutionsQuery.isPending}
            />
          }
        />
        {instances.length === 0 ? (
          <EmptyState
            title={t('instancesEmptyTitle')}
            body={t('instancesEmptyBody')}
            action={
              <DeclareInstanceButton
                onClick={() => setDeclareOpen(true)}
                disabled={solutionsQuery.isPending}
              />
            }
          />
        ) : (
          <DataTable<Instance>
            rows={rows}
            columns={columns}
            getRowId={(row) => row.instanceId}
            detailPanel={(row) => <ReplicaDetailPanel row={row} />}
            csvFileName={`${solutionId}-instances.csv`}
            empty={<Box sx={{ py: 5, textAlign: 'center' }}>{t('instancesNoMatch')}</Box>}
            selection={liveSelection}
            onSelectionChange={handleSelectionChange}
            focusRowId={focusRowId}
            toolbarExtras={
              <ToggleGroup
                aria-label={t('instancesEnvFilter')}
                value={envFilter}
                onChange={setEnvFilter}
                options={environmentFilterOptions}
              />
            }
            bulkActions={
              liveSelection.length > 0 && (
                <>
                  <Button
                    variant="outlined"
                    size="sm"
                    onClick={() => {
                      setBulkRestartOpen(true);
                    }}
                  >
                    {t('instancesBulkRestartTrigger', { count: liveSelection.length })}
                  </Button>
                  <Button
                    variant="outlined"
                    size="sm"
                    onClick={() => {
                      setUndeclareOpen(true);
                    }}
                  >
                    {t('instancesUndeclareTrigger', { count: liveSelection.length })}
                  </Button>
                </>
              )
            }
          />
        )}
      </Stack>
      <BulkRestartDialog
        open={bulkRestartOpen}
        instances={selectedInstances}
        onConfirm={() => {
          setBulkRestartOpen(false);
        }}
        onCancel={() => {
          setBulkRestartOpen(false);
        }}
      />
      <UndeclareInstancesDialog
        open={undeclareOpen}
        instances={selectedInstances}
        onConfirm={() => {
          undeclareMutation.mutate(selectedInstances);
        }}
        onCancel={() => {
          setUndeclareOpen(false);
          undeclareMutation.reset();
        }}
        submitting={undeclareMutation.isPending}
        {...(describeUndeclareFailure() !== undefined
          ? { errorMessage: describeUndeclareFailure() as string }
          : {})}
      />
      {/* Mounted only while open, so each opening starts from a fresh
          form — a cancelled draft is never waiting for the operator the
          next time they press Declare. */}
      {declareOpen && (
        <DeclareInstanceDialog
          open
          solutionId={solutionId}
          existing={instances}
          packageVersions={solution?.packageVersions ?? []}
          getPackage={(packageSolutionId, packageVersion, signal) =>
            solutionsRepository.getPackage(packageSolutionId, packageVersion, signal)
          }
          hierarchyRepository={hierarchyRepository}
          connectionsRepository={connectionsRepository}
          {...(targetsRepository === undefined ? {} : { targetsRepository })}
          onClose={() => {
            setDeclareOpen(false);
            declareMutation.reset();
          }}
          onSubmit={(environmentId, input) => declareMutation.mutate({ environmentId, input })}
          submitting={declareMutation.isPending}
          {...(declareMutation.isError
            ? { errorMessage: describeDeclareError(declareMutation.error) }
            : {})}
        />
      )}
    </PageWidth>
  );
}
