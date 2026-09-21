/**
 * `ConfigRepository` (task 033, §6.0.5, §9.2, UI §A.8). CONTRACT: TBD on
 * two remaining points (a third, Q19, is resolved below):
 * - **Q8** — the save-side generation field's name (cheap, unblocked).
 * - **Q9** — the `409` conflict body shape; no actor, no timestamp.
 *
 * **Q19 resolved (`FE-D33`, task 122): the browser projects.** The server
 * returns the package, the instance's version, its values and observed
 * state — never a pre-projected view model. A pre-projected snapshot could
 * not re-evaluate a `when:` predicate as the operator edits (`project()`'s
 * own obligation 4), and every Designer component already takes contract
 * types (`SolutionPackage`, `DraftValues`, …), never a bespoke view model.
 * This binds the frontend data flow only — the wire *encoding* of the
 * response is the backend sub-spec's to write.
 */
import type {
  DraftValues,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';
import type { PlaneClient } from '@shared/api';
import type { DiffRowFixture } from '@shared/testdoubles/data/designer';

export interface DesignerLoad {
  package: SolutionPackage;
  /** The instance's package version (`FE-118`) — reaches every `project()` call the Designer makes (`FE-D37`). */
  packageVersion: PackageVersionId;
  /** The configuration-of-record, keyed by component instance id — `useDesignerDraft`'s `initialDraft`. */
  values: DraftValues;
  observed: ObservedState;
  /** The config-of-record generation as loaded — task 055's save-bar baseline (`FE-D18` clause A). CONTRACT: TBD field NAME (§16 Q8). */
  configGeneration: string;
}

export interface SaveConfigBody {
  /** `renderedFields()` output — NEVER RHF's raw `getValues()` (`FE-D17`). */
  fields: Readonly<Record<string, unknown>>;
  /** CONTRACT: TBD — the field NAME only (§16 Q8, cheap/unblocked). */
  generation: string;
  /** Required when a semantic warning was overridden (`UI-D16`). */
  overrideReason?: string;
}

export interface SaveConfigResult {
  generation: string;
  /** The server's correlation id (`FE-D13`) — carried through for the caller's own success toast. */
  correlationId: string;
}

export interface ConfigHistoryEntry {
  generation: string;
  savedAt: string;
  savedBy: string;
  overrideReason?: string;
}

export interface SecretIntent {
  /** Write-only — never stored beyond this call (G7, `I6`). */
  value: string;
}

/** old path -> new path, for a version-reconciliation rename (§A.8.12). */
export type RenameMap = Readonly<Record<string, string>>;

export interface VersionDiff {
  rows: readonly DiffRowFixture[];
}

export interface ConfigRepository {
  loadDesigner(instanceId: string, signal?: AbortSignal): Promise<DesignerLoad>;
  /** A `409` throws `ApiError` — the caller refetches and diffs (`FE-D18` clause A). */
  save(instanceId: string, body: SaveConfigBody): Promise<SaveConfigResult>;
  history(instanceId: string, signal?: AbortSignal): Promise<ConfigHistoryEntry[]>;
  setSecret(instanceId: string, fieldKey: string, intent: SecretIntent): Promise<{ ref: string }>;
  acceptRuntime(instanceId: string, toVersion: string, renames: RenameMap): Promise<VersionDiff>;
  restoreRuntime(instanceId: string): Promise<void>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createConfigRepository(dpClient: PlaneClient): ConfigRepository {
  return {
    loadDesigner(instanceId, signal) {
      return dpClient.get<DesignerLoad>(
        `/instances/${encodeURIComponent(instanceId)}/designer`,
        opts(signal),
      );
    },
    async save(instanceId, body) {
      const { data, correlationId } = await dpClient.postWithCorrelation<
        Omit<SaveConfigResult, 'correlationId'>
      >(`/instances/${encodeURIComponent(instanceId)}/config`, body);
      return { ...data, correlationId };
    },
    history(instanceId, signal) {
      return dpClient.get<ConfigHistoryEntry[]>(
        `/instances/${encodeURIComponent(instanceId)}/config/history`,
        opts(signal),
      );
    },
    setSecret(instanceId, fieldKey, intent) {
      return dpClient.post<{ ref: string }>(
        `/instances/${encodeURIComponent(instanceId)}/config/secrets/${encodeURIComponent(fieldKey)}`,
        intent,
      );
    },
    acceptRuntime(instanceId, toVersion, renames) {
      return dpClient.post<VersionDiff>(
        `/instances/${encodeURIComponent(instanceId)}/config/accept-runtime`,
        { toVersion, renames },
      );
    },
    restoreRuntime(instanceId) {
      return dpClient.post<void>(
        `/instances/${encodeURIComponent(instanceId)}/config/restore-runtime`,
      );
    },
  };
}
