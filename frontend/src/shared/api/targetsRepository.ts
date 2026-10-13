/** Shared deployment-target repository used by Connections and Declare Instance. */
import type { PlaneClient } from './planeClientFactory';

export interface TargetOwnershipScope {
  organizationId?: string;
  subOrganizationId?: string;
  environmentId?: string;
  solutionId?: string;
  instanceId?: string;
}

export interface TargetRow {
  targetId: string;
  name: string;
  kind: 'docker' | 'systemd' | 'kubernetes' | 'vm';
  transport: 'ssh-docker' | 'ssh';
  runtime?: 'docker' | 'systemd';
  connectionId?: string;
  runtimeSettingsJson?: string;
  ownershipScope: TargetOwnershipScope;
  allowedConfigRoot?: string;
  allowedContainerPrefix?: string;
  retiredAt?: string;
}

interface TargetListEnvelope {
  items: readonly TargetRow[];
}

export interface TargetsRepository {
  list(signal?: AbortSignal): Promise<TargetRow[]>;
  registerLinked(input: {
    name: string;
    kind: 'docker' | 'systemd';
    connectionId: string;
    configRoot: string;
    containerPrefix?: string;
    serviceNamePattern?: string;
    ownershipScope: TargetOwnershipScope;
  }, signal?: AbortSignal): Promise<unknown>;
  updateLinked(targetId: string, input: {
    name: string;
    connectionId: string;
    kind: 'docker' | 'systemd';
    configRoot: string;
    containerPrefix?: string;
    serviceNamePattern?: string;
  }, signal?: AbortSignal): Promise<unknown>;
  /** Delete is represented by the platform's safe, auditable retire operation. */
  retire(targetId: string, signal?: AbortSignal): Promise<unknown>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createTargetsRepository(cpClient: PlaneClient): TargetsRepository {
  return {
    async list(signal) {
      const envelope = await cpClient.get<TargetListEnvelope>('/targets', opts(signal));
      return [...envelope.items];
    },
    registerLinked(input, signal) {
      return cpClient.post('/targets', {
        name: input.name,
        kind: input.kind,
        transport: 'ssh',
        ownershipScope: {
          subOrganizationId: input.ownershipScope.subOrganizationId,
          environmentId: input.ownershipScope.environmentId,
        },
        connectionId: input.connectionId,
        runtime: input.kind,
        configRoot: input.configRoot,
        ...(input.containerPrefix === undefined ? {} : { containerPrefix: input.containerPrefix }),
        ...(input.serviceNamePattern === undefined ? {} : { serviceNamePattern: input.serviceNamePattern }),
      }, opts(signal));
    },
    updateLinked(targetId, input, signal) {
      return cpClient.patch(`/targets/${targetId}`, {
        name: input.name,
        connectionId: input.connectionId,
        runtime: input.kind,
        configRoot: input.configRoot,
        ...(input.containerPrefix === undefined ? {} : { containerPrefix: input.containerPrefix }),
        ...(input.serviceNamePattern === undefined ? {} : { serviceNamePattern: input.serviceNamePattern }),
      }, opts(signal));
    },
    retire(targetId, signal) {
      return cpClient.post(`/targets/${targetId}/retire`, {}, opts(signal));
    },
  };
}
