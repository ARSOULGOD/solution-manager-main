import type { PlaneClient } from '@shared/api';

/** The non-secret Connection metadata needed by the dp-remote Declare picker. */
export interface RemoteServerConnectionRow {
  connectionId: string;
  name: string;
  connectionType: string;
  attributes: Readonly<Record<string, unknown>>;
  active: boolean;
}

export interface ConnectionPickerRepository {
  listRemoteServers(signal?: AbortSignal): Promise<RemoteServerConnectionRow[]>;
}

export function createConnectionPickerRepository(
  cpClient: PlaneClient,
): ConnectionPickerRepository {
  return {
    async listRemoteServers(signal) {
      const options = signal === undefined ? undefined : { signal };
      const response = await cpClient.get<{ items: readonly RemoteServerConnectionRow[] }>(
        '/connections',
        options,
      );
      return response.items.filter(
        (connection) => connection.connectionType === 'remote-server' && connection.active,
      );
    },
  };
}
