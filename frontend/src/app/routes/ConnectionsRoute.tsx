import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { ConnectionDrawer, ConnectionsPage, createConnectionsRepository, DeploymentTargetsPanel } from '@features/connections';
import type { ConnectionDescriptor, ConnectionRow, ConnectionTypeKey } from '@features/connections';
import { createTargetsRepository } from '@features/inventory/instances';
import { createCpClient } from '@shared/api/cpClient';
import { queryKeys } from '@shared/api/queryKeys';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// Repository construction lives here, not in the feature, for the same
// reason `AdminRoute.tsx` documents: `RuntimeConfig` is an app-layer
// concern a feature may not import directly (boundaries). Not in v1 nav —
// reachable via `AccountMenu`'s DEV-only preview link, same as `/admin`.
export default function ConnectionsRoute() {
  const config = useRuntimeConfig();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ConnectionRow | null>(null);
  const [creatingType, setCreatingType] = useState<ConnectionTypeKey | null>(null);
  const [activeType, setActiveType] = useState<ConnectionTypeKey | null>(null);
  const repository = useMemo(
    () => createConnectionsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );
  const targetsRepository = useMemo(
    () => createTargetsRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  // Shares its cache entry with `ConnectionsPage`'s own `getConnectionTypes`
  // query (same `queryKeys.connections.types()` key) — one request either
  // way, never two in-flight for the same question.
  const typesQuery = useQuery({
    queryKey: queryKeys.connections.types(),
    queryFn: ({ signal }) => repository.getConnectionTypes(signal),
  });
  const descriptorByType = new Map<ConnectionTypeKey, ConnectionDescriptor>(
    (typesQuery.data ?? []).map((descriptor) => [descriptor.connectionType, descriptor]),
  );

  const createDraft = (type: ConnectionTypeKey): ConnectionRow => ({
    connectionId: '',
    name: '',
    connectionType: type,
    // Organization-level by default (`ScopeRef`'s empty object, `I5`) — no
    // scope-picker exists on this screen yet; an operator who needs a
    // narrower scope edits `ownershipScope` is not offered here (open
    // follow-up, out of plan 013 Task 5's scope).
    ownershipScope: {},
    attributes: {},
    active: true,
    usedByCount: 0,
  });

  const closeDrawer = () => {
    setEditing(null);
    setCreatingType(null);
  };

  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.connections.list() });

  const openRow = editing ?? (creatingType === null ? null : createDraft(creatingType));
  const openDescriptor = openRow === null ? undefined : descriptorByType.get(openRow.connectionType);
  const resolvedActiveType = activeType ?? typesQuery.data?.[0]?.connectionType ?? null;

  return (
    <>
      <ConnectionsPage
        repository={repository}
        onOpen={(row) => setEditing(row)}
        onCreate={(type) => setCreatingType(type)}
        onActiveTypeChange={setActiveType}
      />
      {resolvedActiveType === 'remote-server' ? (
        <DeploymentTargetsPanel connectionsRepository={repository} targetsRepository={targetsRepository} />
      ) : null}
      {openRow === null || openDescriptor === undefined ? null : (
        <ConnectionDrawer
          connection={openRow}
          descriptor={openDescriptor}
          title={editing === null ? `New ${openDescriptor.label}` : openRow.name}
          onClose={closeDrawer}
          onSave={async (input) => {
            if (editing === null) {
              await repository.create(input);
            } else {
              await repository.update(editing.connectionId, input);
            }
            closeDrawer();
            await invalidate();
          }}
          // Absent while creating (`editing === null`) — there is nothing
          // yet to deactivate or delete (task 10).
          {...(editing === null
            ? {}
            : {
                onDeactivate: () => {
                  void repository.deactivate(editing.connectionId).then(async () => {
                    closeDrawer();
                    await invalidate();
                  });
                },
                onDelete: () => {
                  void repository.remove(editing.connectionId, editing.usedByCount).then(async () => {
                    closeDrawer();
                    await invalidate();
                  });
                },
              })}
        />
      )}
    </>
  );
}
