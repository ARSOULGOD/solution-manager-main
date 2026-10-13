/**
 * Browser-facing types for the canonical Solution Package JSON document.
 *
 * The authority is `../../../contracts/solution-package.schema.json`; these
 * interfaces only make a schema-validated value convenient to consume in
 * TypeScript. The upload document and projection engine share the same
 * multi-version model; `normalize.ts` only maps canonical nested UI metadata
 * into the renderer's presentation fields.
 */

export type PackageLiteral = string | number | boolean;
export type PackageVersionId = number | string;

export type PackageFieldType =
  'string' | 'int' | 'bool' | 'enum' | 'duration' | 'list' | 'map' | 'table' | 'yaml' | 'secret';

export interface PackageUiMetadata {
  label?: string | undefined;
  section?: string | undefined;
  subSection?: string | undefined;
  order?: number | undefined;
  advanced?: boolean | undefined;
  helperText?: string | undefined;
  readOnly?: boolean | undefined;
  listColumn?: boolean | undefined;
  promoteToDeclare?: boolean | undefined;
}

export interface PackageOptionsSource {
  kind: 'sm-catalog';
  /** `broker` retired by `UI-D27` — a Solace broker is `connection` + `connectionType`. */
  entity: 'connection' | 'environment' | 'instance' | 'metric';
  /** Required when `entity === 'connection'` (schema-enforced). */
  connectionType?: 'solace-broker' | 'remote-server' | undefined;
}

/** `UI-D28` — binds this field to an attribute of the connection chosen in `ref`. */
export interface PackageValueFrom {
  ref: string;
  attribute: string;
}

export interface PackageColumn {
  key: string;
  type: 'string' | 'int' | 'bool' | 'enum' | 'duration' | 'secret';
  options?: readonly PackageLiteral[] | undefined;
  label?: string | undefined;
}

export interface PackageField {
  key: string;
  type: PackageFieldType;
  /** Optional package-defined role; omitted or `none` means shared. */
  role?: string | null | undefined;
  /** Omitted means the field belongs to every version containing its component. */
  version?: readonly PackageVersionId[] | undefined;
  virtual?: boolean | undefined;
  options?: readonly PackageLiteral[] | undefined;
  optionsSource?: PackageOptionsSource | undefined;
  valueFrom?: PackageValueFrom | undefined;
  columns?: readonly PackageColumn[] | undefined;
  default?: PackageLiteral | null | undefined;
  required?: boolean | undefined;
  secret?: boolean | undefined;
  discriminator?: boolean | undefined;
  when?: Readonly<Record<string, PackageLiteral>> | undefined;
  min?: number | undefined;
  max?: number | undefined;
  pattern?: string | undefined;
  yamlSubtree?: string | undefined;
  ui?: PackageUiMetadata | undefined;
  provenance?: Readonly<Record<string, 'derived' | 'suggested' | 'authored'>> | undefined;
}

export interface PackageAction {
  key: string;
  kind: 'test-connection' | 'manage-catalog-entry';
  entity?: PackageOptionsSource['entity'] | undefined;
  inputs?: readonly string[] | undefined;
  when?: Readonly<Record<string, PackageLiteral>> | undefined;
  ui?: PackageUiMetadata | undefined;
}

export interface PackageMetricSource {
  kind: 'http_json' | 'prometheus' | 'solace_event' | 'exec' | 'jdbc_query';
  path?: string | undefined;
  port?: number | undefined;
  pointer?: string | undefined;
  topicPattern?: string | undefined;
  query?: string | undefined;
  command?: string | undefined;
  onUnavailable: 'degrade' | 'error';
}

export interface PackageMetric {
  name: string;
  metricType: 'counter' | 'gauge' | 'histogram';
  unit: string;
  layer?: 'package-baseline' | 'user-extension' | undefined;
  when?: Readonly<Record<string, PackageLiteral>> | undefined;
  source: PackageMetricSource;
}

export interface PackageComponent {
  type: string;
  cardinality: 'exactly-one' | 'one-of-many' | 'zero-or-more';
  /** Omitted means the component belongs to every package version. */
  version?: readonly PackageVersionId[] | undefined;
  /** Package-defined role; omitted/null and the legacy `none` marker mean no role. */
  role?: string | null | undefined;
  configPath: string;
  configFile?: string | undefined;
  loggerPrefix?: string | undefined;
  fields?: readonly PackageField[] | undefined;
  actions?: readonly PackageAction[] | undefined;
  metrics?: readonly PackageMetric[] | undefined;
  health?: Readonly<Record<string, unknown>> | undefined;
  logs?: Readonly<Record<string, unknown>> | undefined;
}

export interface CanonicalSolutionPackage {
  solution: string;
  /** Ordered, non-empty schema versions served by this one package document. */
  packageVersion: readonly PackageVersionId[];
  appliesTo: string;
  provenance?:
    | {
        author: 'solace' | 'dev-mode';
        signature?: string | undefined;
      }
    | undefined;
  authoring?:
    | {
        reviewedRows?: number | undefined;
        totalRows?: number | undefined;
        source?: string | undefined;
      }
    | undefined;
  components: readonly PackageComponent[];
}

export interface PackageValidationIssue {
  /** Stable machine-readable identifier used by tests and future API mapping. */
  code: string;
  /** JSON Pointer into the uploaded document; `/` means the package root. */
  path: string;
  /** Operator-facing explanation. */
  message: string;
  /** The canonical load-time gate that rejected it. */
  gate: number | '5b';
}

export interface PackageComponentSummary {
  type: string;
  cardinality: PackageComponent['cardinality'];
  fieldCount: number;
  actionCount: number;
  metricCount: number;
}

export interface PackageValidationSummary {
  solution: string;
  packageVersion: readonly PackageVersionId[];
  appliesTo: string;
  componentCount: number;
  fieldCount: number;
  actionCount: number;
  metricCount: number;
  components: readonly PackageComponentSummary[];
}

export type PackageValidationResult =
  | {
      valid: true;
      package: CanonicalSolutionPackage;
      summary: PackageValidationSummary;
    }
  | {
      valid: false;
      issues: readonly PackageValidationIssue[];
    };

export interface ExistingPackageRange {
  solution: string;
  appliesTo: string;
}

export interface PackageValidationOptions {
  /** Allows `author: dev-mode`; it never makes a bad structure valid. */
  devMode: boolean;
  /** Catalog entries used by gate 3's same-solution range-overlap check. */
  existingPackages?: readonly ExistingPackageRange[] | undefined;
}
