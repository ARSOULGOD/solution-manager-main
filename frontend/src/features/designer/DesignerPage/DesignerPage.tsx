/**
 * `DesignerPage` (task 123, `REQ-001`/`REQ-006`) — routes between four
 * states and does nothing else: skeleton · error-with-retry · "this
 * package declares nothing for this version" · form. The composer canvas
 * (`FE-124`) is not a fifth state here — it is `DesignerForm`'s own choice
 * between the flat section list and the composer canvas once a package
 * composes more than one component, made with the same `load` this file
 * already resolved. Projection, validation and save logic all live in
 * `DesignerForm` and the ~25 components it assembles; this file owns only
 * the query lifecycle and the generation-keyed baseline `FE-D20` requires.
 *
 * **`deriveComponentSelection` is the single source of truth** for which
 * components exist (`componentSelection.ts`) — computed once here and
 * passed down, never re-derived inside `DesignerForm`.
 *
 * **The generation keys the draft.** `load` starts as the query's own
 * result and can be REPLACED (never merged) by `DesignerForm` after a
 * save, a conflict refetch, or an accepted/restored runtime — each of
 * which carries its own new `configGeneration`. `DesignerForm` is keyed
 * on that generation, so every replacement is a full remount with a fresh
 * `useDesignerDraft`, never an optimistic edit onto the old one (`FE-D20`).
 */
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { EmptyState, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import type { DesignerLoad } from '../configRepository';

import { deriveComponentSelection } from './componentSelection';
import { DesignerForm } from './DesignerForm';
import type { DesignerPageProps } from './DesignerPage.types';

function describeQueryError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

export function DesignerPage({
  instanceId,
  configRepository,
  applyRepository,
  actionsRepository,
}: DesignerPageProps): ReactElement {
  const query = useQuery({
    queryKey: queryKeys.config.designer(instanceId),
    queryFn: ({ signal }) => configRepository.loadDesigner(instanceId, signal),
  });

  // Adjusted during render, not in an effect (react-hooks/set-state-in-
  // effect) — the same pattern `Composer.tsx` uses. `syncedFrom` is what
  // makes this correct rather than a loop: `load` can be locally REPLACED
  // by `DesignerForm` (a save, a conflict refetch) without matching
  // `query.data`, and that replacement must stick until the query itself
  // produces a genuinely new response object.
  const [load, setLoad] = useState<DesignerLoad | undefined>(undefined);
  const [syncedFrom, setSyncedFrom] = useState<DesignerLoad | undefined>(undefined);
  if (query.data !== undefined && query.data !== syncedFrom) {
    setSyncedFrom(query.data);
    setLoad(query.data);
  }

  if (load === undefined) {
    if (query.isError) {
      return (
        <ErrorPanel
          title={t('designerLoadErrorTitle')}
          detail={describeQueryError(query.error)}
          onRetry={() => void query.refetch()}
        />
      );
    }
    return (
      <Stack gap={2}>
        <Skeleton width={220} height={28} />
        <Skeleton width="100%" height={44} />
        {[0, 1, 2, 3].map((row) => (
          <Stack key={row} gap={1}>
            <Skeleton width="30%" height={14} />
            <Skeleton width="55%" height={40} />
          </Stack>
        ))}
      </Stack>
    );
  }

  const componentSelection = deriveComponentSelection(load.package, load.packageVersion);
  if (componentSelection.length === 0) {
    return (
      <EmptyState
        title={t('designerEmptyTitle')}
        body={t('designerEmptyBody', { version: String(load.packageVersion) })}
      />
    );
  }

  return (
    <DesignerForm
      key={load.configGeneration}
      instanceId={instanceId}
      load={load}
      componentSelection={componentSelection}
      configRepository={configRepository}
      applyRepository={applyRepository}
      actionsRepository={actionsRepository}
      onLoadChange={setLoad}
      onRefetch={() => void query.refetch()}
    />
  );
}
