/**
 * Hand-typed instance wire shapes — replaced wholesale when a generated
 * contract lands, never extended into a shadow contract (same rule as
 * `common.ts`).
 *
 * Rewritten for integration plan 007 (2026-10-09) against the real
 * `console.yaml` `Instance`/`Acquisition` schemas and `declareInstance`'s
 * request body. The previous version (task 115) was written against an
 * open `§16 Q10` with no real declare API — see `declareForm.ts`'s header
 * for what that version invented and why none of it survived.
 */

/**
 * How the DP acquires an instance's health and metrics — chosen per
 * instance at declare (M-Q2/M-Q2b), persisted at instance level. No
 * automatic fallback between the two, and the body carries no host
 * (`OR-82`): the address is never browser-supplied.
 */
export type AcquisitionMode = 'direct-http' | 'via-ssh';

/** Deployment binding. Only dp-remote is active until the agent contracts land. */
export type DeploymentMode = 'dp-remote' | 'host-local' | 'federated';

export interface Acquisition {
  mode: AcquisitionMode;
}

export interface DeclareInstancePlacement {
  containerName: string;
  configDirectory: string;
  containerConfigPath: string;
  fileName: string;
  healthPort: number;
  /** `{"<sourcePort>": <hostPort>}` — accepted at declare, not yet acted on (plan 011 P11-4.1). Omitted entirely for v1's declare dialog. */
  metricsPorts?: Readonly<Record<string, number>>;
}

interface DeclareInstanceInputBase {
  /** The catalog id (`OR-41`), never the package's declared `solution` string. */
  solutionId: string;
  name: string;
  /** Absent means `dp-remote` on the wire; only `dp-remote` is accepted today (plan 014). */
  deploymentMode?: DeploymentMode;
  declaredPackageVersion: string | number;
  /** Package-defined instance role; omitted when the package has no roles. */
  role?: string;
  placement: DeclareInstancePlacement;
  acquisition: Acquisition;
}

/** Legacy Target binding (plan 007); retained for existing clients and instances. */
export type TargetBackedDeclareInstanceInput = DeclareInstanceInputBase & {
  targetId: string;
  connectionId?: never;
};

/** The plan 014 binding new declarations use: a remote-server Connection, `dp-remote` only. */
export type ConnectionBackedDeclareInstanceInput = DeclareInstanceInputBase & {
  targetId?: never;
  connectionId: string;
  deploymentMode: 'dp-remote';
};

/**
 * `declareInstance`'s body: exactly one of `targetId` and `connectionId`
 * (`console.yaml`, plan 014) — a union, so a body naming both or neither
 * does not type-check.
 */
export type DeclareInstanceInput = TargetBackedDeclareInstanceInput | ConnectionBackedDeclareInstanceInput;

/** Exactly one of `targetId` (a legacy Target) and `connectionId` (a remote-server Connection, plan 014) is present. */
export interface InstancePlacement {
  placementId: string;
  targetId?: string;
  connectionId?: string;
  containerName: string;
  probeStrength: 'readiness' | 'liveness' | 'none';
}

/** `console.yaml`'s `Instance` — `Versioned` + the fields below. */
export interface Instance {
  stateVersion: number;
  instanceId: string;
  name: string;
  /** The catalog id (`OR-41`). */
  solutionId: string;
  environmentId: string;
  declaredPackageVersion: string | number;
  /** Package-defined instance role, when applicable. */
  role?: string;
  acquisition: Acquisition;
  placements: readonly InstancePlacement[];
}
