/**
 * The `UI-D15` view-model contract (task 046, `REQ-001`). Shared with a
 * future Java implementation — see `../fixtures/README.md` for the JSON
 * fixture format both must pass. This module imports nothing: no React,
 * no DOM, no `@shared/*` (enforced by `eslint.config.js` — the Java side
 * has no notion of this frontend's `shared/vocab`).
 *
 * SPECIFIED (`docs/design/dynamic-ui/design.md` §3, §6.5): the package
 * envelope, component/field declaration shape, the field-type/optionsSource/
 * action-kind/source-kind closed vocabularies, and the `ViewModel` shape
 * itself (`sections[]`/`graphs[]`/`status`).
 *
 * INFERRED (no pinned shape exists yet — called out per field, not silently
 * assumed): `ComponentSelection` (how a specific instance's composed
 * components are named), `DraftValues`/`ObservedState`'s keying by
 * component-instance id, and `ControlKind` (a rendering-layer concept
 * `project()` derives from `type`, not something the package declares).
 */

// ---------------------------------------------------------------------------
// Closed vocabularies (`UI-D14`) — adding a member is an SM release.
// ---------------------------------------------------------------------------

/** `design.md` §3.4. */
export type FieldType =
  'string' | 'int' | 'bool' | 'enum' | 'duration' | 'list' | 'map' | 'table' | 'yaml' | 'secret';

/** `design.md` §3.5. `broker` retired by `UI-D27` — no package or fixture in this tree ever used it. */
export type OptionsSourceEntity = 'environment' | 'connection' | 'instance' | 'metric';

/** `design.md` §3.5 — a field declares static `options` or this, never both. */
export interface OptionsSource {
  kind: 'sm-catalog';
  entity: OptionsSourceEntity;
  /** SPECIFIED by `UI-D27` — narrows which `connection` catalog entries apply. */
  connectionType?: 'solace-broker' | 'remote-server' | undefined;
}

/** SPECIFIED by `UI-D28` — binds a field to an attribute of the entry chosen by the `ref` field (a `sm-catalog` selector). */
export interface ValueFrom {
  ref: string;
  attribute: string;
}

/** `design.md` §3.5. */
export type ActionKind = 'test-connection' | 'manage-catalog-entry';

/** `design.md` §3.4 — transport mechanisms only, never a solution name. */
export type SourceKind = 'http_json' | 'prometheus' | 'solace_event' | 'exec' | 'jdbc_query';

/** `design.md` §3.4, per `SM-D31`. */
export type Mark = 'line' | 'gauge' | 'single-stat';

// Reused across `shared/vocab` (frontend-only, not importable here) and this
// contract — kept in sync by hand since the two must never diverge in
// meaning, only in which module owns the literal.
/**
 * A package version identifier. A number for a curated, monotonically
 * numbered package (`6`, `7`); a string for a build that is not
 * numerically ordered (`'dev-2026.08.17'`). No ordering is implied by the
 * type — `SolutionPackage.packageVersion` declares the order.
 */
export type PackageVersionId = number | string;

export type HealthKey =
  'healthy' | 'not_responding' | 'restart_expected' | 'inactive_replica' | 'not_applicable';
export type VersionState = 'ok' | 'drift' | 'unknown' | 'unverified';
export type ConfigState = 'in_sync' | 'staged' | 'rendered' | 'diverged' | 'drift' | 'na';

// ---------------------------------------------------------------------------
// The package (input) — `design.md` §3.1–§3.5.
// ---------------------------------------------------------------------------

export type Literal = string | number | boolean;

/** `field: literal`, implicitly AND-ed (`UI-D2`) — no OR, no comparison, no negation. */
export type WhenClause = Readonly<Record<string, Literal>>;

export interface FieldUiMeta {
  section?: string | undefined;
  order?: number | undefined;
  /** Canonical `ui.readOnly`; keeps an authored immutable field disabled. */
  readOnly?: boolean | undefined;
  /**
   * SPECIFIED by `UI-D27` (task 9). Marks a field for promotion to the
   * Declare Instance dialog as a connection selector, gated behind the
   * instance's chosen org/sub-org/environment scope. Never one of the three
   * dialect-flattened keys (`label`/`helperText`/`advanced`) — this one
   * stays nested under `ui`, same as `section`/`order`/`readOnly`.
   */
  promoteToDeclare?: boolean | undefined;
}

export interface FieldDeclaration {
  key: string;
  type: FieldType;
  /** Optional package-defined role; omitted or `none` means shared. */
  role?: string | null | undefined;
  /**
   * The package versions this declaration belongs to. ABSENT means every
   * version the package supports — which is what keeps a third-party
   * package that has never heard of versioning valid.
   *
   * ⚠️ **Absent is not the same as `[]`.** An empty array belongs to no
   * version and therefore never renders. That is a legitimate way to
   * retire a field without deleting it, and a silent way to lose one by
   * accident — so `parsePackage.ts` rejects it at load time rather than
   * letting it disappear quietly.
   */
  version?: readonly PackageVersionId[] | undefined;
  options?: readonly string[] | undefined;
  optionsSource?: OptionsSource | undefined;
  /** SPECIFIED by `UI-D28`. Absent means an ordinary field; present means `project()` resolves this field's value from the chosen catalog entry under `UI-D28`'s precedence (override -> connection -> declared default -> unset). */
  valueFrom?: ValueFrom | undefined;
  default?: Literal | null | undefined;
  discriminator?: boolean | undefined;
  when?: WhenClause | undefined;
  secret?: boolean | undefined;
  advanced?: boolean | undefined;
  helperText?: string | undefined;
  ui?: FieldUiMeta | undefined;
  required?: boolean | undefined;
  min?: number | undefined;
  max?: number | undefined;
  pattern?: string | undefined;
  /** `type: table` only (`UI-D22`) — each column restricted to scalar types. */
  columns?: readonly FieldDeclaration[] | undefined;
  /**
   * Authored (task 048 gap — not yet in `UI-D17`'s own column enumeration,
   * see `docs/plans/frontend/reports/task-048-report.md`). "Nothing can
   * derive this" applies here exactly as it does to `helperText`: the
   * reference package data (`designer_data.jsx`) hand-picks a label per
   * field (`max-size` -> "Max entries", `ttl` -> "TTL") rather than
   * mechanically casing the key. Falls back to `key` when omitted, same as
   * the reference's own `label: o.label || o.key`.
   */
  label?: string | undefined;
  /**
   * Authored. A field with no MI-backed config path — `project()` maps
   * this to `renderedPath: null` (`UI-D4`'s Appendix A.8.4: "a `virtual`
   * field renders identically to a real one — no badge, no visual tell").
   */
  virtual?: boolean | undefined;
}

export interface ActionDeclaration {
  key: string;
  kind: ActionKind;
  entity?: OptionsSourceEntity | undefined;
  inputs?: readonly string[] | undefined;
  when?: WhenClause | undefined;
}

export interface MetricSource {
  kind: SourceKind;
  path?: string | undefined;
  port?: number | undefined;
  pointer?: string | undefined;
  /** REQUIRED by the package schema (`architecture.md` §10) — not narrowed further here; task 046 does not compute against it. */
  onUnavailable: string;
}

export interface MetricDeclaration {
  name: string;
  /** REQUIRED, never inferred (`SM-D26`). Left as `string`, not a closed union — the three-value DB CHECK isn't enumerated in the design doc's evidence, and task 046's `project()` only passes this through (see `../fixtures/README.md`). */
  metricType: string;
  unit: string;
  source: MetricSource;
  when?: WhenClause | undefined;
  layer?: string | undefined;
}

/** `design.md` §3.2 (`UI-D1`) — `cardinality`'s two observed values; not stated as a closed vocabulary, but no third value appears anywhere in the evidence base. */
export type Cardinality = 'exactly-one' | 'one-of-many' | 'zero-or-more';

export interface ComponentDeclaration {
  type: string;
  cardinality: Cardinality;
  /** The package versions this component belongs to — same rule as `FieldDeclaration.version`. */
  version?: readonly PackageVersionId[] | undefined;
  /** Optional package role; null is an explicit no-role value in the package contract. */
  role?: string | null | undefined;
  configPath: string;
  loggerPrefix: string;
  fields: readonly FieldDeclaration[];
  actions?: readonly ActionDeclaration[] | undefined;
  metrics?: readonly MetricDeclaration[] | undefined;
}

export interface PackageProvenance {
  author: string;
  signature?: string | undefined;
}

export interface PackageAuthoring {
  reviewedRows: number;
  totalRows: number;
}

export interface SolutionPackage {
  solution: string;
  /**
   * Every version this ONE file serves, ascending, non-empty. A package
   * evolves without forking into a file per version: components and
   * fields declare which of these they belong to (`version`), and an
   * instance is configured against exactly one of them.
   */
  packageVersion: readonly PackageVersionId[];
  appliesTo: string;
  provenance: PackageProvenance;
  authoring: PackageAuthoring;
  components: readonly ComponentDeclaration[];
}

// ---------------------------------------------------------------------------
// The other three `project()` inputs — INFERRED, see module header.
// ---------------------------------------------------------------------------

/**
 * Which component instances this specific solution instance composes
 * (`UI-D1`: "a solution declares component types; an instance composes
 * instances of them"). `instanceId` disambiguates `one-of-many` components
 * (File Mesh's multiple sources/sinks) and keys `DraftValues`/`ObservedState`
 * below. Cache — the v1 target — is the degenerate single-entry case.
 */
export interface ComponentSelectionEntry {
  type: string;
  instanceId: string;
  role?: string | null | undefined;
}

export type ComponentSelection = readonly ComponentSelectionEntry[];

export type FieldValue =
  Literal | null | readonly FieldValue[] | { readonly [key: string]: FieldValue };

/** `fieldKey -> value`, for one component instance. */
export type ComponentValues = Readonly<Record<string, FieldValue>>;

/** `componentInstanceId -> ComponentValues` — mirrors File Mesh's own `(component, field) -> value` shape (`UI-D1`). */
export type DraftValues = Readonly<Record<string, ComponentValues>>;

/**
 * SPECIFIED by `UI-D27`/`UI-D29`. One entry in an `sm-catalog` list — a connection, an
 * environment, etc. `revision` is what would move a `UI-D29` fingerprint when the entry
 * changes; this task does not compute one, it only carries the field through.
 *
 * `attributes` NEVER holds a value for an attribute named in `secretAttributes` — a secret
 * attribute contributes only its presence, and `project()` refuses to resolve a `valueFrom`
 * binding against it unless the target field is itself secret (`I6`).
 */
export interface CatalogEntry {
  id: string;
  label: string;
  connectionType?: string | undefined;
  attributes: Readonly<Record<string, unknown>>;
  revision: number;
  /** Attribute keys this entry considers secret. Names only, never values (`I6`). */
  secretAttributes?: readonly string[] | undefined;
}

/** SPECIFIED by `UI-D27`. Options resolved from an SM-owned catalog; the package never supplies a URL. */
export type Catalog = Readonly<Partial<Record<OptionsSourceEntity, readonly CatalogEntry[]>>>;

/**
 * Already-resolved observed state. `project()` *consumes* this — it does
 * not derive health from telemetry or corroborate a version from an image
 * tag; that is a governance-layer concern. The one obligation `project()`
 * has against it is gating edit access on `versionState` (`UI-D12`).
 */
export interface ObservedState {
  health: HealthKey;
  versionState: VersionState;
  configState: ConfigState;
  /**
   * The version bound to this instance at binding time — `design.md` §6.1's
   * declared-first pattern (`UI-D12`). Always present: the operator always
   * declares one, even when nothing corroborates it. `project()` passes it
   * through unchanged into `ViewStatus`; only `VersionPill`/`BlockedBanner`
   * (task 059) read it.
   */
  declaredVersion?: string | undefined;
  /**
   * What corroborates the declaration, when anything does. Absent exactly
   * describes `VERSION_UNVERIFIED` (§6.1: "nothing observable corroborates
   * the declaration") — optional here rather than required so fixtures
   * that don't exercise version-state display need not supply either
   * field (`run.test.ts`'s `toEqual` already tolerates an absent key on
   * both sides).
   */
  observedVersion?: string | undefined;
}

// ---------------------------------------------------------------------------
// The view model (output) — `design.md` §6.5, verbatim shape.
// ---------------------------------------------------------------------------

/**
 * A rendering-layer concept derived from `FieldType` — the package never
 * declares this. One control kind per field type (see `controlKindFor` in
 * `project.ts`).
 */
export type ControlKind =
  | 'text'
  | 'number'
  | 'toggle'
  | 'select'
  | 'duration'
  | 'list'
  | 'map'
  | 'table'
  | 'yaml'
  | 'secret';

export interface Validation {
  tier: 'bind' | 'semantic';
  /** e.g. `REQUIRED`, `ABOVE_RECOMMENDED` (`design.md` §7.5's own examples) — FieldRow's required-asterisk keys off this, not a separate boolean (task 048). */
  code: string;
  message: string;
}

/**
 * `value(v, source) | unset | secretRef(ref) | secretUnset` — `design.md`
 * §6.5's Appendix A.8.1 shape, and already the exact union
 * `SecretField`'s own `SecretFieldState` (task 023) expects for its
 * `secretRef`/`secretUnset` half. `source` has no `'observed'` member:
 * `ObservedState` carries no field-level values for `project()` to draw
 * one from (see task-048-report) — only `SolutionPackage.default` and a
 * component's own `DraftValues` entry ever produce a field's value.
 *
 * SPECIFIED by `UI-D28`: `source` widens to `'connection'` (resolved from the selected
 * catalog entry) and `'override'` (a literal draft value on a field that also declares
 * `valueFrom` — the operator's explicit override beats the connection).
 */
export type ViewFieldValue =
  | { kind: 'value'; value: FieldValue; source: 'default' | 'draft' | 'connection' | 'override' }
  | { kind: 'unset' }
  | { kind: 'secretRef'; ref: string }
  | { kind: 'secretUnset' };

export interface ViewField {
  key: string;
  /** Authored (`FieldDeclaration.label`) or `key` as a fallback — never humanized (task 048 ruling). */
  label: string;
  /** `null` for a `virtual` field — renders identically to a real one, no visual tell (`UI-D4` Appendix A.8.4). */
  renderedPath: string | null;
  discriminator: boolean;
  controlKind: ControlKind;
  /**
   * `secret: true` always projects `{ kind: 'secretUnset' }` today — the
   * raw value never appears here (`I6`, obligation 7), and distinguishing
   * a real `secretRef` from an accidentally-raw draft string needs a
   * `DraftValues`-level contract this task does not have (see
   * task-048-report's ruling on this). `secretRef` is declared in the
   * union because the shared Java contract needs the same vocabulary, but
   * `project()` cannot yet produce it safely.
   */
  value: ViewFieldValue;
  options: readonly string[];
  enabled: boolean;
  visible: boolean;
  masked: boolean;
  advanced: boolean;
  helperText?: string | undefined;
  /** `required`/`min`/`max`/`pattern`, live-evaluated against the field's current defaults-applied value (`UI-D16`, task 053). Empty when none apply. */
  validations: readonly Validation[];
}

export interface ViewAction {
  key: string;
  kind: ActionKind;
  enabled: boolean;
  /**
   * Field KEYS the action runs server-side against — never a value (`I6`,
   * `UI-D22`). Pass-through of `ActionDeclaration.inputs`; the package
   * declares no action label (§3.5), so the browser names these inputs on
   * screen instead of inventing display copy from `key` (task 058).
   */
  inputs: readonly string[];
}

export interface ViewSection {
  title: string;
  order: number;
  fields: readonly ViewField[];
  actions: readonly ViewAction[];
}

export interface ViewGraph {
  metric: string;
  mark: Mark;
  transform?: string | undefined;
  resolution?: string | undefined;
  thresholds?: readonly number[] | undefined;
}

export interface ViewStatus {
  health: HealthKey;
  versionState: VersionState;
  configState: ConfigState;
  /** Pass-through of `ObservedState.declaredVersion` (task 059) — see that field's doc comment. */
  declaredVersion?: string | undefined;
  /** Pass-through of `ObservedState.observedVersion` (task 059) — see that field's doc comment. */
  observedVersion?: string | undefined;
}

/**
 * SPECIFIED by `UI-D28`. `instanceId` (not `instanceKey`) to match this file's own
 * `ComponentSelectionEntry`/`DraftValues` vocabulary. Codes used today: `CONNECTION_UNRESOLVED`
 * (a selector names a catalog id the catalog does not hold) and `SECRET_BINDING_REFUSED` (`I6`
 * — a non-secret field's `valueFrom` binds an attribute the catalog marks secret).
 */
export interface Diagnostic {
  code: string;
  message: string;
  instanceId?: string | undefined;
  key?: string | undefined;
}

export interface ViewModel {
  sections: readonly ViewSection[];
  graphs: readonly ViewGraph[];
  status: ViewStatus;
  /** SPECIFIED by `UI-D28`. Optional so every pre-existing literal `ViewModel` built before this
   * task (e.g. `overlayRbac.test.ts`) stays valid without adding an empty array everywhere;
   * `project()` itself always populates it. */
  diagnostics?: readonly Diagnostic[] | undefined;
}
