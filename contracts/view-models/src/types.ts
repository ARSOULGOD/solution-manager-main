/**
 * Solution Package input types and view-model output types.
 *
 * Input mirrors `contracts/solution-package.schema.json`, which is authoritative — these
 * types are a convenience for the projector, not a second definition. Output implements
 * `docs/design/dynamic-ui/design.md` §6.5 (`UI-D15`) as corrected against `DM-D*`/`FE-D*`.
 */

// ---------------------------------------------------------------- input

/** UI-D2: an object of field:literal, implicitly AND-ed. No OR, comparison or negation. */
export type Predicate = Readonly<Record<string, string | number | boolean>>;
export type PackageVersionId = number | string;

export type FieldType =
  | 'string' | 'int' | 'bool' | 'enum' | 'duration'
  | 'list' | 'map' | 'table' | 'yaml' | 'secret';

export interface OptionsSource {
  readonly kind: 'sm-catalog';
  readonly entity: 'broker' | 'environment' | 'connection' | 'instance' | 'metric';
}

export interface UiHints {
  readonly label?: string;
  readonly section?: string;
  readonly subSection?: string;
  readonly order?: number;
  readonly advanced?: boolean;
  readonly helperText?: string;
  readonly readOnly?: boolean;
}

export interface PackageField {
  readonly key: string;
  readonly type: FieldType;
  readonly version?: readonly PackageVersionId[];
  readonly virtual?: boolean;
  readonly options?: readonly (string | number | boolean)[];
  readonly optionsSource?: OptionsSource;
  readonly columns?: readonly { readonly key: string; readonly type: string }[];
  readonly default?: string | number | boolean | null;
  readonly required?: boolean;
  readonly secret?: boolean;
  readonly discriminator?: boolean;
  readonly when?: Predicate;
  readonly min?: number;
  readonly max?: number;
  readonly pattern?: string;
  readonly yamlSubtree?: string;
  readonly ui?: UiHints;
}

export interface PackageAction {
  readonly key: string;
  readonly kind: 'test-connection' | 'manage-catalog-entry';
  readonly entity?: OptionsSource['entity'];
  readonly inputs?: readonly string[];
  readonly when?: Predicate;
  readonly ui?: UiHints;
}

export interface PackageMetric {
  readonly name: string;
  readonly metricType: 'counter' | 'gauge' | 'histogram';
  readonly unit: string;
  readonly layer?: 'package-baseline' | 'user-extension';
  readonly when?: Predicate;
  readonly source: Readonly<Record<string, unknown>>;
}

export interface PackageComponent {
  readonly type: string;
  readonly cardinality: 'exactly-one' | 'one-of-many' | 'zero-or-more';
  readonly version?: readonly PackageVersionId[];
  readonly role?: 'source' | 'sink' | 'none';
  readonly configPath: string;
  readonly configFile?: string;
  readonly loggerPrefix?: string;
  readonly fields?: readonly PackageField[];
  readonly actions?: readonly PackageAction[];
  readonly metrics?: readonly PackageMetric[];
  readonly health?: { readonly probe: Readonly<Record<string, unknown>> };
  readonly logs?: Readonly<Record<string, unknown>>;
}

export interface SolutionPackage {
  readonly solution: string;
  readonly packageVersion: readonly PackageVersionId[];
  readonly appliesTo: string;
  readonly provenance?: { readonly author: string; readonly signature?: string };
  readonly components: readonly PackageComponent[];
}

// ---------------------------------------------------------------- input: state

/** UI-D20: `layout` is presentation state and is excluded from `rendered` and the hash. */
export interface ComponentInstance {
  readonly instanceKey: string;
  readonly type: string;
  readonly layout?: { readonly x: number; readonly y: number };
}
export interface ComponentSelection { readonly instances: readonly ComponentInstance[] }

/** Draft values, namespaced per component instance. */
export type DraftValues = Readonly<Record<string, Readonly<Record<string, unknown>>>>;

export type VersionState = 'OK' | 'VERSION_DRIFT' | 'VERSION_UNKNOWN' | 'VERSION_UNVERIFIED';

/** SM-D22 / architecture §6.1 — the five states are carried separately and never collapsed. */
export interface ConfigState {
  readonly desired?: string;
  readonly rendered?: string;
  readonly persisted?: string;
  readonly restartObserved?: string;
  readonly effectiveVerified?: string;
}

export type ReplicaHealth = 'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA';

export interface ObservedState {
  readonly version: {
    readonly declared?: string;
    readonly observed?: string;
    readonly state: VersionState;
  };
  readonly config?: ConfigState;
  readonly replicas?: readonly { readonly id: string; readonly health: ReplicaHealth }[];
  /** UI-D7: restart-observed config UNIONED with persisted virtual values, per instance. */
  readonly restartObservedValues?: Readonly<Record<string, Readonly<Record<string, unknown>>>>;
}

/** Options resolved from an SM-owned catalog (UI-D22). The package never supplies a URL. */
export type Catalog = Readonly<Partial<Record<OptionsSource['entity'], readonly string[]>>>;

// ---------------------------------------------------------------- output

export type ControlKind =
  | 'text' | 'number' | 'switch' | 'select' | 'duration'
  | 'string-list' | 'key-value' | 'table' | 'yaml-editor' | 'secret';

/**
 * FE-D23 / I6: no arm of this union can carry a resolved secret. A third arm appearing
 * here means I6 was breached upstream.
 */
export type FieldValue =
  | { readonly kind: 'value'; readonly value: unknown; readonly source: 'draft' | 'default' }
  | { readonly kind: 'unset' }
  | { readonly kind: 'secretRef'; readonly ref: string }
  | { readonly kind: 'secretUnset' };

export type Severity = 'block' | 'warn';

export interface Validation {
  readonly severity: Severity;
  readonly code: string;
  readonly message: string;
}

export interface FieldView {
  readonly key: string;
  /** null when the field is `virtual` — it reaches no config file (UI-D24). */
  readonly renderedPath: string | null;
  readonly controlKind: ControlKind;
  readonly value: FieldValue;
  readonly options?: readonly (string | number | boolean)[];
  readonly visible: boolean;
  readonly enabled: boolean;
  readonly virtual: boolean;
  readonly advanced: boolean;
  readonly masked: boolean;
  readonly discriminator: boolean;
  readonly label?: string;
  readonly helperText?: string;
  readonly validations: readonly Validation[];
  /** Predicate keys that evaluated false. Empty when visible. */
  readonly hiddenBy: readonly string[];
}

export interface ActionView {
  readonly key: string;
  readonly kind: PackageAction['kind'];
  readonly enabled: boolean;
  readonly visible: boolean;
  readonly inputs: readonly string[];
  readonly label?: string;
}

export interface SectionView {
  readonly title: string;
  readonly order: number;
  readonly fields: readonly FieldView[];
  readonly actions: readonly ActionView[];
  /** True when every visible field in the section is `advanced` — the section collapses by default. */
  readonly allVisibleFieldsAdvanced: boolean;
}

export interface ComponentView {
  readonly instanceKey: string;
  readonly type: string;
  readonly role: NonNullable<PackageComponent['role']>;
  readonly sections: readonly SectionView[];
}

export interface RenderedEntry {
  readonly instanceKey: string;
  readonly renderedPath: string;
  readonly value: unknown;
}

export type ExclusionReason = 'hidden' | 'virtual' | 'action' | 'orphan';

export interface ExcludedEntry {
  readonly instanceKey: string;
  readonly key: string;
  readonly reason: ExclusionReason;
}

export type CollectionState = 'collectable' | 'not-applicable' | 'pending-config-change';

export interface MetricView {
  readonly instanceKey: string;
  readonly name: string;
  readonly metricType: PackageMetric['metricType'];
  readonly unit: string;
  readonly collectionState: CollectionState;
}

export type HealthRollup = 'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA' | 'NOT_APPLICABLE';

export interface StatusView {
  readonly version: { readonly state: VersionState; readonly declared?: string; readonly observed?: string; readonly editingBlocked: boolean; readonly reason?: string };
  readonly config: ConfigState;
  readonly replicas: readonly { readonly id: string; readonly health: ReplicaHealth }[];
  readonly healthRollup: HealthRollup;
  readonly healthAvailability: 'declared' | 'not-declared';
}

export interface Diagnostic {
  readonly code: string;
  readonly message: string;
  readonly instanceKey?: string;
  readonly key?: string;
}

export interface ViewModel {
  /** Bumped when projection semantics change, so a silent behavioural drift is detectable. */
  readonly engineVersion: number;
  readonly solution: string;
  readonly packageVersion: PackageVersionId | null;
  readonly components: readonly ComponentView[];
  /** FE-D17: the save/diff/hash payload. Emitted, never re-derived by consumers. */
  readonly rendered: readonly RenderedEntry[];
  readonly excluded: readonly ExcludedEntry[];
  readonly metrics: readonly MetricView[];
  readonly status: StatusView;
  readonly diagnostics: readonly Diagnostic[];
}
