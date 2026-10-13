/**
 * `ConfigRepository` (task 033, §6.0.5, §9.2, UI §A.8).
 *
 * **Plan 008, Tasks 1 & 2 (2026-10-09): `loadDesigner()`/`save()` now call the real
 * `getDesigner`/`stageConfig` contract.** Previously (see git history) this module returned the
 * fixture-era shape `{package, values, observed, configGeneration, …}` straight off a
 * fixture-shaped `/designer` route. It now:
 *
 * 1. Calls the real `getDesigner` (`{stateVersion, generation, viewModel, layout}`) — `viewModel`
 *    is console.yaml's wire `ViewModel` (`components[].sections[].fields[]`, each field's `value`
 *    a `{kind, value?, source?, ref?}` union), a DIFFERENT shape from this codebase's own
 *    `project()`-OUTPUT `ViewModel` (`@contracts/view-model/types`: flat `sections[]`/`graphs[]`,
 *    no per-component grouping) despite the shared name.
 * 2. Fetches the RAW `SolutionPackage` via the injected `getSolutionPackage` (the real
 *    `getSolutionPackage`/D-4 route, reusing `solutionsRepository.ts`'s own client pattern — never
 *    duplicated here, see `createConfigRepository`'s second parameter), keyed off the catalog
 *    solution id supplied by the route and the wire `viewModel.packageVersion`. The wire
 *    `viewModel.solution` is the package name (for example `file-mesh`), not the UUID required
 *    by the catalog package endpoint.
 * 3. Reverse-maps the wire `ViewModel` into this engine's own `DraftValues`/`ObservedState`
 *    (`mapWireDesignerToLocal` below) so the UNCHANGED local `project()` (`useDesignerDraft`,
 *    `Composer`) can run exactly as it did before this task — `OR-34` governs the server's own
 *    authoritative record (staging/diff/history/accept-runtime refusal); `FE-D33`'s rationale
 *    (live editing re-evaluates a `when:` predicate locally, per keystroke) still governs why the
 *    browser keeps running `project()` itself rather than round-tripping every edit.
 *
 * **What the reverse mapping can and cannot carry — see `mapWireDesignerToLocal`'s own header for
 * the itemized list.** Two fields have NO wire source at all and are listed in
 * `DesignerLoad.unsupportedFields` rather than silently dropped: `observed.restartObservedValues`
 * (no raw per-field restart-observed snapshot crosses the wire, only the already-rolled-up
 * five-tier `ViewModelConfigState`) and an approximated `observed.configState` (the wire's five
 * separate tiers collapse to this engine's one-member enum via a documented best-effort rule,
 * never a ratified mapping — none exists). `connectionCatalog`/`appliedConnectionRevisions`
 * (`UI-D27`/`UI-D28`/`UI-D29`) have NO counterpart in `console.yaml`'s `ViewModel` schema at all.
 * The catalog is therefore loaded separately from the real Connections API; applied revisions
 * remain unavailable until the backend exposes them.
 *
 * **KNOWN REGRESSION, out of this task's scope:** `frontend/src/app/dev/devPackageHandlers.ts`'s
 * own `/designer` override still returns the OLD fixture shape (a dev-session package drop has no
 * equivalent of a real backend's `getDesigner` projection to call) — `app/dev/devPackageFlow.test.ts`
 * exercises that override directly and will fail across the board until that dev-tooling path is
 * rewritten to the same wire shape this file now expects. That rewrite is its own task (dev
 * tooling, not named in plan 008's Tasks 1/2, not part of the `designer` feature's own test suite)
 * — flagged here rather than silently left to surprise the next reader.
 *
 * **`save()` / `SaveConfigBody` (Task 2, `stageConfig`).** The real body is
 * `{baseStateVersion, componentSelection, draftValues, rendered}` — `SaveBar` builds it from new
 * props (`baseStateVersion`, `componentSelection`, `rendered`) rather than this module inventing a
 * conversion of its own; see `SaveBar.tsx`'s header for why. `rendered` is computed by
 * `useDesignerDraft.ts`'s new `rendered` field (scoped per-instance `project()` passes — the local
 * engine's flat `sections[]` has no per-instance `renderedPath` list to read directly; see that
 * module's header for why a scoped pass is needed instead of a single global one).
 *
 * **Discovered contract gap, not fabricated:** `stageConfig`'s request schema is
 * `additionalProperties: false` with no `overrideReason` field at all — `UI-D16`'s override-reason
 * dialog has nothing to send it to. `SaveBar` still runs the confirmation dialog (operators still
 * see and accept the warning) but the reason itself is accepted and discarded, not transmitted —
 * see `SaveBar.tsx`'s header.
 */
import type {
  Catalog,
  ConfigState,
  DraftValues,
  HealthKey,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
  VersionState,
} from '@contracts/view-model/types';
import type { PlaneClient } from '@shared/api';

export interface DesignerLoad {
  /** The `Designer` envelope's own watermark (`Versioned`) — the `baseStateVersion` accept/restore-runtime (and now `stageConfig`) need. */
  stateVersion: number;
  package: SolutionPackage;
  /** The instance's package version (`FE-118`) — reaches every `project()` call the Designer makes (`FE-D37`). */
  packageVersion: PackageVersionId;
  /** Persisted package-defined instance role, when applicable. */
  role?: string;
  /** The configuration-of-record, keyed by component instance id — `useDesignerDraft`'s `initialDraft`. */
  values: DraftValues;
  observed: ObservedState;
  /** The config-of-record generation as loaded — task 055's save-bar baseline (`FE-D18` clause A). The real wire `generation` is `number | null` (console.yaml `Designer.generation`); `null` (no generation exists yet) is carried through as `''`. */
  configGeneration: string;
  /** The component types this instance composes (`FE-D55`) — now read directly off the wire `ViewModel.components[]` (which component instances the SERVER actually projected) rather than a separately-authored binding. Absent only when the wire reports zero components. */
  componentTypes?: readonly string[] | undefined;
  /** Current connection catalog entries used to resolve package-declared `valueFrom` bindings. */
  connectionCatalog?: Catalog['connection'];
  /** Revisions recorded by the last apply; absent until the server exposes them. Same NO-wire-counterpart note as `connectionCatalog`. */
  appliedConnectionRevisions?: Readonly<Record<string, number>>;
  /**
   * Plan 008 Task 1: fields the wire `Designer` envelope genuinely has nowhere to carry, listed
   * rather than silently dropped (`I6`-adjacent discipline: never guess, never drop quietly). See
   * this module's header for what each entry means. Optional for the same reason
   * `componentTypes`/`connectionCatalog` are — a literal `DesignerLoad` built by hand (tests,
   * `ConflictDialog`'s own fixture) has nothing to report and omits it rather than padding every
   * call site with an empty array.
   */
  unsupportedFields?: readonly string[] | undefined;
}

/** console.yaml `RenderedEntry` — the save/diff/hash payload (`FE-D17`), emitted by a per-instance-scoped local `project()` pass (`useDesignerDraft.ts`'s `rendered`), never re-derived at the call site. */
export interface RenderedEntry {
  instanceKey: string;
  renderedPath: string;
  value: unknown;
}

/** console.yaml `stageConfig` request body — `{baseStateVersion, componentSelection, draftValues, rendered}`, `additionalProperties: false` (no `overrideReason` — see module header). */
export interface SaveConfigBody {
  baseStateVersion: number;
  componentSelection: {
    instances: readonly {
      instanceKey: string;
      type: string;
      layout?: { x: number; y: number };
    }[];
  };
  draftValues: DraftValues;
  rendered: readonly RenderedEntry[];
}

/** console.yaml `stageConfig`'s 200 — `StagedConfig & {unchanged}` — plus the server's correlation id (`FE-D13`). */
export interface SaveConfigResult {
  stateVersion: number;
  generation: number;
  unchanged: boolean;
  /** The server's correlation id (`FE-D13`) — carried through for the caller's own success toast. */
  correlationId: string;
}

/**
 * Plan 008 Task 3: matches console.yaml's `ConfigHistory.entries[]` exactly
 * (`generation` is `int64`, `at`/`by`, an optional `configHash` computed
 * over references only — `I6`). The envelope's own `stateVersion` is
 * dropped by `history()` below; no caller consumes it yet (`FE-D18`'s
 * caller-owned-generation baseline is keyed off `DesignerLoad.configGeneration`,
 * Task 1's unmigrated seam — see `configRepository.ts`'s module header).
 */
export interface ConfigHistoryEntry {
  generation: number;
  at: string;
  by: string;
  configHash?: string;
}

export interface SecretIntent {
  /** Write-only — never stored beyond this call (G7, `I6`). */
  value: string;
  /** Required by console.yaml `setConfigSecret` — the optimistic-concurrency base. */
  baseStateVersion: number;
}

/** console.yaml `StagedConfig` — `setConfigSecret`'s 200. NEVER a resolved value (`I6`): just the new watermark. */
export interface StagedConfig {
  stateVersion: number;
  generation: number;
}

/**
 * console.yaml `acceptRuntimeConfig` request body — `{baseStateVersion, placementId}` only.
 * NEVER a `toVersion`/rename map (that UI had no contract basis — see `AcceptRuntimeDialog`'s header).
 */
export interface AcceptRuntimeIntent {
  baseStateVersion: number;
  placementId: string;
}

/**
 * console.yaml `AcceptedRuntimeConfig` — `StagedConfig` plus what the accept could not map.
 * `notAccepted[]` entries are listed by PATH ONLY (`I6`: a secret's value is never read back);
 * `comparison` is present only when `reason` is `secret-not-read`.
 */
export interface NotAcceptedField {
  path: string;
  fieldKey?: string;
  reason:
    | 'secret-not-read'
    | 'undeclared-key'
    | 'sm-owned-overlay'
    | 'inactive-field'
    | 'yaml-field-not-accepted';
  comparison?: 'same' | 'differs';
}

export interface AcceptedRuntimeConfig {
  stateVersion: number;
  generation: number;
  unchanged: boolean;
  placementId: string;
  fileBytesDifferFromRendered: boolean;
  truncated: boolean;
  notAccepted: readonly NotAcceptedField[];
}

/**
 * console.yaml `restoreRuntimeConfig` request body — `placementId` required, `strategy`
 * defaults to `manual` server-side; `outageConfirmedBy` required only for `strategy:
 * 'restart-all'` (out of scope here — this dialog only ever sends `manual`, task 059's
 * "redeploy the declared version" flow, never a restart strategy picker).
 */
export interface RestoreRuntimeIntent {
  placementId: string;
  strategy?: 'manual';
}

/** console.yaml `restoreRuntimeConfig`'s `202 ApplyAccepted` — observed at `/apply/observe`, not awaited here. */
export interface RestoreRuntimeAccepted {
  applyId: string;
}

export interface ConfigRepository {
  loadDesigner(instanceId: string, signal?: AbortSignal): Promise<DesignerLoad>;
  /** A `409` throws `ApiError` (stale `stateVersion` — refetch and re-stage) and a `422` throws `ApiError` too (a projection mismatch between this client's `rendered` and the server's own — never a generic save error, see `SaveBar.tsx`). */
  save(instanceId: string, body: SaveConfigBody): Promise<SaveConfigResult>;
  history(instanceId: string, signal?: AbortSignal): Promise<ConfigHistoryEntry[]>;
  setSecret(instanceId: string, fieldKey: string, intent: SecretIntent): Promise<StagedConfig>;
  acceptRuntime(instanceId: string, intent: AcceptRuntimeIntent): Promise<AcceptedRuntimeConfig>;
  restoreRuntime(instanceId: string, intent: RestoreRuntimeIntent): Promise<RestoreRuntimeAccepted>;
}

/** Resolves the raw `SolutionPackage` for a solution + (possibly absent) version — `solutionsRepository.ts`'s `getPackage`, injected rather than duplicated here (`eslint-plugin-boundaries`: a feature may only reach another feature through its public barrel, and this keeps `configRepository.ts` from having to import one at all). `undefined` means "no generation yet" (`console.yaml Designer.generation` is nullable) — the real `getSolutionPackage` route treats an omitted `packageVersion` query param as "the entry's latest". */
export type GetSolutionPackage = (
  solutionId: string,
  packageVersion: PackageVersionId | undefined,
  signal?: AbortSignal,
) => Promise<SolutionPackage>;

/** The read-only shape needed to turn the real Connections API into a Designer catalog. */
export interface ConnectionCatalogRow {
  readonly connectionId: string;
  readonly name: string;
  readonly connectionType: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  readonly active: boolean;
  readonly revision?: number | undefined;
  readonly updatedAt?: string | undefined;
}

export type GetConnectionCatalog = (
  signal?: AbortSignal,
) => Promise<readonly ConnectionCatalogRow[]>;

// ---------------------------------------------------------------------------
// Wire shapes (console.yaml) — transcribed by hand, no codegen yet (Task 1's
// own follow-up, same as the fixture-era module's own note). Kept PRIVATE to
// this module: nothing outside the wire boundary should ever see these.
// ---------------------------------------------------------------------------

type WireFieldValue =
  | { kind: 'value'; value: unknown; source: 'draft' | 'default' }
  | { kind: 'unset' }
  | { kind: 'secretRef'; ref: string }
  | { kind: 'secretUnset' };

interface WireFieldView {
  key: string;
  renderedPath: string | null;
  controlKind: string;
  value: WireFieldValue;
  visible: boolean;
  enabled: boolean;
  virtual: boolean;
  advanced: boolean;
  masked: boolean;
  discriminator: boolean;
  validations: readonly { severity: 'block' | 'warn'; code: string; message: string }[];
  hiddenBy: readonly string[];
  label?: string;
  helperText?: string;
  options?: readonly (string | number | boolean)[];
}

interface WireSectionView {
  title: string;
  order: number;
  fields: readonly WireFieldView[];
  actions: readonly unknown[];
  allVisibleFieldsAdvanced: boolean;
}

interface WireComponentView {
  instanceKey: string;
  type: string;
  role?: string | null;
  sections: readonly WireSectionView[];
}

interface WireConfigState {
  desired?: string;
  rendered?: string;
  persisted?: string;
  restartObserved?: string;
  effectiveVerified?: string;
}

type WireVersionState = 'OK' | 'VERSION_DRIFT' | 'VERSION_UNKNOWN' | 'VERSION_UNVERIFIED';
type WireHealthRollup = 'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA' | 'NOT_APPLICABLE';

interface WireStatusView {
  version: {
    state: WireVersionState;
    declared?: string;
    observed?: string;
    editingBlocked: boolean;
    reason?: string;
  };
  config: WireConfigState;
  replicas: readonly { id: string; health: string }[];
  healthRollup: WireHealthRollup;
  healthAvailability: 'declared' | 'not-declared';
}

interface WireViewModel {
  engineVersion: number;
  solution: string;
  packageVersion: PackageVersionId | null;
  components: readonly WireComponentView[];
  rendered: readonly { instanceKey: string; renderedPath: string; value: unknown }[];
  excluded: readonly unknown[];
  metrics: readonly unknown[];
  status: WireStatusView;
  diagnostics: readonly unknown[];
}

interface WireDesigner {
  stateVersion: number;
  generation: number | null;
  viewModel: WireViewModel;
  layout: Readonly<Record<string, { x: number; y: number }>>;
  role?: string | null;
}

const WIRE_HEALTH_TO_LOCAL: Readonly<Record<WireHealthRollup, HealthKey>> = {
  GREEN: 'healthy',
  RED: 'not_responding',
  RESTART_EXPECTED: 'restart_expected',
  INACTIVE_REPLICA: 'inactive_replica',
  NOT_APPLICABLE: 'not_applicable',
};

const WIRE_VERSION_STATE_TO_LOCAL: Readonly<Record<WireVersionState, VersionState>> = {
  OK: 'ok',
  VERSION_DRIFT: 'drift',
  VERSION_UNKNOWN: 'unknown',
  VERSION_UNVERIFIED: 'unverified',
};

/**
 * APPROXIMATED — flagged in `DesignerLoad.unsupportedFields`, never silently trusted as exact.
 * `SM-D22`/architecture.md §6.1 carries five tiers (`desired`/`rendered`/`persisted`/
 * `restartObserved`/`effectiveVerified`) separately and explicitly never collapses them; this
 * engine's own `ConfigState` (`frontend/contracts/view-model/types.ts`) is one closed-vocabulary
 * member. No ratified mapping between the two exists. This rule picks the one local state
 * `DivergedBanner`/`isEditingBlocked`'s callers actually gate on (`'diverged'` — a restart-observed
 * tier that disagrees with what's persisted) and falls back to `'na'`/`'in_sync'` conservatively.
 */
function deriveLocalConfigState(config: WireConfigState): ConfigState {
  if (config.persisted === undefined) {
    return 'na';
  }
  if (config.restartObserved !== undefined && config.restartObserved !== config.persisted) {
    return 'diverged';
  }
  return 'in_sync';
}

/** `true` only for a field this client may safely treat as an explicit draft override — `I6` never lets a secret arm or an unset arm contribute a literal. */
function draftLiteralOf(value: WireFieldValue): { present: true; value: unknown } | { present: false } {
  if (value.kind === 'value' && value.source === 'draft') {
    return { present: true, value: value.value };
  }
  return { present: false };
}

interface MappedWireDesigner {
  draftValues: DraftValues;
  observedState: ObservedState;
  componentTypes: readonly string[];
  unsupportedFields: readonly string[];
}

/**
 * The reverse mapping this module's header describes. Pure and side-effect-free so it can be unit
 * tested on its own (`configRepository.test.ts`) without a network layer.
 */
export function mapWireDesignerToLocal(wire: WireDesigner): MappedWireDesigner {
  const draftValues: Record<string, Record<string, unknown>> = {};
  for (const component of wire.viewModel.components) {
    const instanceValues: Record<string, unknown> = {};
    for (const section of component.sections) {
      for (const field of section.fields) {
        const literal = draftLiteralOf(field.value);
        if (literal.present) {
          instanceValues[field.key] = literal.value;
        }
      }
    }
    draftValues[component.instanceKey] = instanceValues;
  }

  const { status } = wire.viewModel;
  const observedState: ObservedState = {
    health: WIRE_HEALTH_TO_LOCAL[status.healthRollup],
    versionState: WIRE_VERSION_STATE_TO_LOCAL[status.version.state],
    configState: deriveLocalConfigState(status.config),
    ...(status.version.declared === undefined ? {} : { declaredVersion: status.version.declared }),
    ...(status.version.observed === undefined ? {} : { observedVersion: status.version.observed }),
  };

  return {
    // Cast at the wire boundary: the local engine's recursive `FieldValue` (`Literal | null |
    // FieldValue[] | {[key]: FieldValue}`) cannot statically absorb the wire's `unknown` literal
    // without this — same trust boundary `solutionsRepository.ts`'s `getPackage()` already takes
    // for the raw package document (no runtime schema validation exists on either side yet).
    draftValues: draftValues as unknown as DraftValues,
    observedState,
    componentTypes: wire.viewModel.components.map((component) => component.type),
    unsupportedFields: [
      // No wire source at all (`UI-D7`'s per-field restart-observed snapshot never crosses the
      // wire — only the rolled-up five-tier `ViewModelConfigState` does). A virtual field whose
      // visibility depends on it renders from the draft/default pass only.
      'observed.restartObservedValues',
      // See `deriveLocalConfigState`'s own header — approximated, not ratified.
      'observed.configState (approximated from the wire five-tier ViewModelConfigState)',
    ],
  };
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createConfigRepository(
  dpClient: PlaneClient,
  getSolutionPackage: GetSolutionPackage,
  /** The catalog solution UUID from the instance route. Older fixture callers may omit it. */
  catalogSolutionId?: string,
  /** Optional real Connections API seam; omitted by fixture-only callers. */
  getConnectionCatalog?: GetConnectionCatalog,
): ConfigRepository {
  return {
    async loadDesigner(instanceId, signal) {
      const wire = await dpClient.get<WireDesigner>(
        `/instances/${encodeURIComponent(instanceId)}/designer`,
        opts(signal),
      );
      const { draftValues, observedState, componentTypes, unsupportedFields } =
        mapWireDesignerToLocal(wire);

      // `ViewModel.solution` is the package's string name, while
      // `/cp/solutions/{solutionId}/package` requires the catalog UUID. The
      // route is the authoritative source for that UUID in the real app.
      const solutionId = catalogSolutionId ?? wire.viewModel.solution;
      const wirePackageVersion = wire.viewModel.packageVersion ?? undefined;
      const pkg = await getSolutionPackage(solutionId, wirePackageVersion, signal);
      const connectionRows = getConnectionCatalog === undefined ? undefined : await getConnectionCatalog(signal);
      const connectionCatalog = connectionRows?.map((row) => {
        const secretAttributes = Object.entries(row.attributes)
          .filter(
            ([, value]) =>
              typeof value === 'object' &&
              value !== null &&
              !Array.isArray(value) &&
              (value as { set?: unknown }).set === true,
          )
          .map(([key]) => key);
        return {
          id: row.connectionId,
          // The package stores the ID and the Designer must submit that ID. Use it
          // as the visible option label too, so the selected value is unambiguous
          // when several connection names are similar.
          label: row.connectionId,
          connectionType: row.connectionType,
          attributes: row.attributes,
          // The current Connections wire omits revision. `updatedAt` is the
          // strongest available change marker until the contract grows one.
          revision: row.revision ?? (row.updatedAt === undefined ? 0 : Date.parse(row.updatedAt)),
          ...(secretAttributes.length > 0 ? { secretAttributes } : {}),
        };
      });
      const packageVersion =
        wirePackageVersion ?? pkg.packageVersion[pkg.packageVersion.length - 1] ?? 1;

      return {
        stateVersion: wire.stateVersion,
        package: pkg,
        packageVersion,
        values: draftValues,
        observed: observedState,
        configGeneration: wire.generation === null ? '' : String(wire.generation),
        ...(wire.role === undefined || wire.role === null || wire.role.trim() === '' ? {} : { role: wire.role }),
        ...(componentTypes.length === 0 ? {} : { componentTypes }),
        ...(connectionCatalog === undefined ? {} : { connectionCatalog }),
        unsupportedFields,
      };
    },
    async save(instanceId, body) {
      const { data, correlationId } = await dpClient.postWithCorrelation<
        Omit<SaveConfigResult, 'correlationId'>
      >(`/instances/${encodeURIComponent(instanceId)}/config`, body);
      return { ...data, correlationId };
    },
    async history(instanceId, signal) {
      // console.yaml's `getConfigHistory` returns the `ConfigHistory` envelope
      // (`Versioned` + `entries`), not a bare array — unwrap it here, the one
      // seam that knows the wire shape, per `frontend/README.md`'s
      // fixture-to-real-client replacement pattern.
      const envelope = await dpClient.get<{ stateVersion: number; entries: ConfigHistoryEntry[] }>(
        `/instances/${encodeURIComponent(instanceId)}/config/history`,
        opts(signal),
      );
      return envelope.entries;
    },
    setSecret(instanceId, fieldKey, intent) {
      return dpClient.post<StagedConfig>(
        `/instances/${encodeURIComponent(instanceId)}/config/secrets/${encodeURIComponent(fieldKey)}`,
        intent,
      );
    },
    acceptRuntime(instanceId, intent) {
      return dpClient.post<AcceptedRuntimeConfig>(
        `/instances/${encodeURIComponent(instanceId)}/config/accept-runtime`,
        intent,
      );
    },
    restoreRuntime(instanceId, intent) {
      return dpClient.post<RestoreRuntimeAccepted>(
        `/instances/${encodeURIComponent(instanceId)}/config/restore-runtime`,
        intent,
      );
    },
  };
}
