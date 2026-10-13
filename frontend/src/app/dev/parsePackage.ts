/**
 * DEV-only: turns a file dropped on the load-a-package dialog into a
 * `SolutionPackage` the real `project()` engine can run over, or into a
 * STATED reason it cannot.
 *
 * A newly dropped document is validated against the canonical root JSON
 * Schema and its cross-reference gates before it reaches projection.
 * `validatePackage()` below remains intentionally narrow
 * for one different input: the already-normalized internal packages
 * rehydrated from DEV sessionStorage. Keeping those paths named prevents
 * rehydrated state separate from the upload boundary.
 *
 * DEV-only: reached exclusively from `devSolutionsRepository.ts`, which
 * `main.tsx` installs inside a literal `if (import.meta.env.DEV)` branch
 * (see `gallery.routes.ts` for why the check must be textually literal).
 */
import { parseAndValidateSolutionPackage, toFrontendPackage } from '@contracts/solution-package';
import type {
  ActionKind,
  FieldType,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';

/**
 * A package as DROPPED. Identical to `SolutionPackage` except that
 * `provenance.signature` may be ABSENT: an unsigned in-development build
 * carries no signature at all. Its absence is exactly what
 * `derivePackageCatalog.ts` reads to set `devMode` (`UI-D19`).
 *
 * This alias also gives the DEV store a stable name for its normalized
 * projection-engine document.
 */
export interface LoadedPackage extends Omit<SolutionPackage, 'provenance'> {
  provenance: { author: string; signature?: string | undefined };
}

/**
 * Why a dropped file did not become a package. A CODE, not a sentence —
 * the caller decides the copy; `message` is the developer-facing detail
 * that names the offending path, which is the whole value of parsing
 * client-side rather than posting bytes at a server that cannot yet
 * receive them.
 */
export type PackageRejectionCode =
  'zip-unsupported' | 'sompkg-unsupported' | 'malformed-json' | 'invalid-package';

export interface PackageRejection {
  code: PackageRejectionCode;
  /** One sentence, safe to show an operator. */
  message: string;
  /** The specific path or parser error behind `message`, when there is one. */
  detail?: string;
}

export type PackageParseResult =
  { ok: true; pkg: LoadedPackage } | { ok: false; rejection: PackageRejection };

// Restated from `types.ts`'s closed vocabularies (`UI-D14`). `project()`
// looks both up in a `Record` keyed by the union, so an unlisted member
// yields `undefined` and renders a control that does not exist — which is
// precisely why these two are checked and the free-form fields are not.
const FIELD_TYPES: readonly FieldType[] = [
  'string',
  'int',
  'bool',
  'enum',
  'duration',
  'list',
  'map',
  'table',
  'yaml',
  'secret',
];

const ACTION_KINDS: readonly ActionKind[] = ['test-connection', 'manage-catalog-entry'];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isVersionList(value: unknown): value is readonly PackageVersionId[] {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.every((member) => typeof member === 'number' || typeof member === 'string')
  );
}

/**
 * A component's or field's optional `version` (FE-118). Three distinct
 * faults, each named:
 *
 * - ABSENT is valid, and means "every version the package supports" — the
 *   rule that keeps a package written before versions existed loadable.
 * - EMPTY is rejected. An empty array belongs to no version and would
 *   therefore never render; the contract admits the shape, but a package
 *   arriving with one has almost certainly lost a field rather than
 *   retired it, and silently rendering nothing is the failure this check
 *   exists to name.
 * - A member the PACKAGE does not itself support is rejected. A field
 *   scoped to v9 in a `[6, 7]` package can never be selected, so it too
 *   would never render — the same silent loss, one step further away from
 *   where an author would look for it.
 */
function versionFault(
  version: unknown,
  path: string,
  supported: readonly PackageVersionId[],
): string | undefined {
  if (version === undefined) {
    return undefined;
  }
  if (!isVersionList(version)) {
    return `${path}.version must be a non-empty array of numbers or strings when present`;
  }
  const unsupported = version.filter((member) => !supported.includes(member));
  if (unsupported.length > 0) {
    return `${path}.version names ${unsupported.map((v) => String(v)).join(', ')}, which packageVersion does not declare`;
  }
  return undefined;
}

/** A first failing path, or `undefined` when every dereferenced field holds. */
function firstStructuralFault(value: unknown): string | undefined {
  if (!isRecord(value)) {
    return 'the package root is not a JSON object';
  }
  if (!isNonEmptyString(value['solution'])) {
    return 'solution must be a non-empty string';
  }
  const packageVersions = value['packageVersion'];
  if (!isVersionList(packageVersions)) {
    return 'packageVersion must be a non-empty array of numbers or strings';
  }
  if (typeof value['appliesTo'] !== 'string') {
    return 'appliesTo must be a string';
  }

  const provenance = value['provenance'];
  if (!isRecord(provenance) || !isNonEmptyString(provenance['author'])) {
    return 'provenance.author must be a non-empty string';
  }
  // `signature` is REQUIRED by `types.ts` but OPTIONAL here on purpose: an
  // unsigned package is the case `UI-D19`'s dev-mode admission exists for,
  // and rejecting it at the parser would make that path unreachable.
  // `derivePackageCatalog.ts` reads its absence as `devMode: true`.
  if (provenance['signature'] !== undefined && typeof provenance['signature'] !== 'string') {
    return 'provenance.signature must be a string when present';
  }

  const authoring = value['authoring'];
  if (
    !isRecord(authoring) ||
    typeof authoring['reviewedRows'] !== 'number' ||
    typeof authoring['totalRows'] !== 'number'
  ) {
    return 'authoring.reviewedRows and authoring.totalRows must both be numbers';
  }

  const components = value['components'];
  if (!Array.isArray(components) || components.length === 0) {
    return 'components must be a non-empty array';
  }

  for (const [index, component] of components.entries()) {
    const at = `components[${String(index)}]`;
    if (!isRecord(component)) {
      return `${at} is not an object`;
    }
    if (!isNonEmptyString(component['type'])) {
      return `${at}.type must be a non-empty string`;
    }
    // `project()` builds every field's `renderedPath` from this.
    if (typeof component['configPath'] !== 'string') {
      return `${at}.configPath must be a string`;
    }
    const fault =
      versionFault(component['version'], at, packageVersions) ??
      componentFieldsFault(component['fields'], at, packageVersions) ??
      componentActionsFault(component['actions'], at) ??
      componentMetricsFault(component['metrics'], at);
    if (fault !== undefined) {
      return fault;
    }
  }

  return undefined;
}

function componentFieldsFault(
  fields: unknown,
  at: string,
  supported: readonly PackageVersionId[],
): string | undefined {
  if (!Array.isArray(fields)) {
    return `${at}.fields must be an array`;
  }
  for (const [index, field] of fields.entries()) {
    const path = `${at}.fields[${String(index)}]`;
    if (!isRecord(field)) {
      return `${path} is not an object`;
    }
    if (!isNonEmptyString(field['key'])) {
      return `${path}.key must be a non-empty string`;
    }
    const version = versionFault(field['version'], path, supported);
    if (version !== undefined) {
      return version;
    }
    if (!FIELD_TYPES.includes(field['type'] as FieldType)) {
      return `${path}.type must be one of ${FIELD_TYPES.join(', ')}`;
    }
    // `project()` spreads this into `ViewField.options`; a non-array would
    // reach the control registry as one.
    if (field['options'] !== undefined && !Array.isArray(field['options'])) {
      return `${path}.options must be an array when present`;
    }
  }
  return undefined;
}

function componentActionsFault(actions: unknown, at: string): string | undefined {
  if (actions === undefined) {
    return undefined;
  }
  if (!Array.isArray(actions)) {
    return `${at}.actions must be an array when present`;
  }
  for (const [index, action] of actions.entries()) {
    const path = `${at}.actions[${String(index)}]`;
    if (!isRecord(action)) {
      return `${path} is not an object`;
    }
    if (!isNonEmptyString(action['key'])) {
      return `${path}.key must be a non-empty string`;
    }
    if (!ACTION_KINDS.includes(action['kind'] as ActionKind)) {
      return `${path}.kind must be one of ${ACTION_KINDS.join(', ')}`;
    }
    if (action['inputs'] !== undefined && !Array.isArray(action['inputs'])) {
      return `${path}.inputs must be an array when present`;
    }
  }
  return undefined;
}

function componentMetricsFault(metrics: unknown, at: string): string | undefined {
  if (metrics === undefined) {
    return undefined;
  }
  if (!Array.isArray(metrics)) {
    return `${at}.metrics must be an array when present`;
  }
  for (const [index, metric] of metrics.entries()) {
    const path = `${at}.metrics[${String(index)}]`;
    // `projectGraphs()` reads `name`; `derivePackageCatalog()` reads
    // `source.kind`. Nothing else in this flow touches a metric.
    if (!isRecord(metric) || !isNonEmptyString(metric['name'])) {
      return `${path}.name must be a non-empty string`;
    }
    const source = metric['source'];
    if (!isRecord(source) || !isNonEmptyString(source['kind'])) {
      return `${path}.source.kind must be a non-empty string`;
    }
  }
  return undefined;
}

/**
 * Validates an already-parsed JSON value. Exported because
 * `devPackageStore.ts` re-runs it over everything it rehydrates from
 * `sessionStorage` — a package that was valid when stored is not
 * necessarily valid after a hand-edit of the storage entry or a contract
 * change between sessions.
 */
export function validatePackage(value: unknown): PackageParseResult {
  const fault = firstStructuralFault(value);
  if (fault !== undefined) {
    return {
      ok: false,
      rejection: {
        code: 'invalid-package',
        message: 'This file is JSON, but not a Solution Package.',
        detail: fault,
      },
    };
  }
  // The single boundary cast, after every dereferenced field is checked
  // above — the standard parse-don't-validate seam, and the same posture
  // the fixture layer takes for a package it reads from the tree.
  return { ok: true, pkg: value as LoadedPackage };
}

/**
 * Parses a multi-version upload document against the canonical root schema
 * and semantic gates, then maps nested UI metadata for the frontend
 * projection engine. The full report is rendered by `LoadPackageDialog`; this
 * DEV wrapper keeps the first issue for its `ApiError` compatibility seam.
 */
export function parsePackageText(text: string): PackageParseResult {
  const validation = parseAndValidateSolutionPackage(text, { devMode: true });
  if (!validation.valid) {
    const first = validation.issues[0];
    return {
      ok: false,
      rejection: {
        code: first?.code === 'MALFORMED_JSON' ? 'malformed-json' : 'invalid-package',
        message:
          first?.code === 'MALFORMED_JSON'
            ? 'This file is not valid JSON.'
            : 'This file is JSON, but not a valid Solution Package.',
        ...(first === undefined ? {} : { detail: `${first.path}: ${first.message}` }),
      },
    };
  }
  return { ok: true, pkg: toFrontendPackage(validation.package) };
}

/**
 * The DEV parser still returns a specific explanation if a legacy caller
 * hands it `.zip` or `.sompkg`. Both are archives: the real loader unpacks
 * one server-side and reads
 * `descriptor.yaml` beside `package.json` (§16 Q10). Nothing in the
 * browser here can, and saying so is more useful than a JSON parse error
 * from a binary file's first bytes.
 */
function archiveRejection(fileName: string): PackageRejection | undefined {
  const name = fileName.toLowerCase();
  if (name.endsWith('.zip')) {
    return {
      code: 'zip-unsupported',
      message: 'Zipped packages cannot be opened in the browser — drop the package JSON itself.',
      detail: 'Unpacking an archive needs the server-side loader (§16 Q10).',
    };
  }
  if (name.endsWith('.sompkg')) {
    return {
      code: 'sompkg-unsupported',
      message: 'A .sompkg bundle cannot be opened in the browser — drop the package JSON itself.',
      detail:
        'A .sompkg carries descriptor.yaml alongside package.json; only the server-side loader reads both (§16 Q10).',
    };
  }
  return undefined;
}

/**
 * `Blob.text()` where it exists, `FileReader` where it does not. Every
 * real browser has the first; jsdom's `File` does not implement it, and
 * the loader's own tests run there — so the fallback is what makes this
 * function testable at all, not a browser-compatibility hedge.
 */
function readText(file: File): Promise<string> {
  if (typeof file.text === 'function') {
    return file.text();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      resolve(typeof reader.result === 'string' ? reader.result : '');
    };
    reader.onerror = () => {
      reject(reader.error ?? new Error('The file could not be read.'));
    };
    reader.readAsText(file);
  });
}

export async function parsePackageFile(file: File): Promise<PackageParseResult> {
  const rejection = archiveRejection(file.name);
  if (rejection !== undefined) {
    return { ok: false, rejection };
  }

  let text: string;
  try {
    text = await readText(file);
  } catch (error) {
    return {
      ok: false,
      rejection: {
        code: 'malformed-json',
        message: 'This file could not be read.',
        detail: error instanceof Error ? error.message : String(error),
      },
    };
  }
  return parsePackageText(text);
}
