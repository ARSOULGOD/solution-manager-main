/**
 * Frontend preflight for a user-selected Solution Package JSON document.
 *
 * Gate 1 is compiled directly from the repository's canonical JSON Schema;
 * there is no hand-written second structural contract. Cross-reference
 * gates mirror `contracts/validation/validate-package.py`. This is an early,
 * readable browser check, not a trust boundary: `cp-core` must run the same
 * schema and semantic gates again when the real byte-upload API exists.
 */
import type { ErrorObject } from 'ajv';

import validateSchema from './generated/solution-package-validator.mjs';
import type {
  CanonicalSolutionPackage,
  PackageComponent,
  PackageField,
  PackageValidationIssue,
  PackageValidationOptions,
  PackageValidationResult,
  PackageValidationSummary,
  PackageVersionId,
} from './types';

function pointerSegment(value: string): string {
  return value.replaceAll('~', '~0').replaceAll('/', '~1');
}

function schemaIssue(error: ErrorObject): PackageValidationIssue {
  let path = error.instancePath;
  if (error.keyword === 'required') {
    const missing = (error.params as { missingProperty: string }).missingProperty;
    path = `${path}/${pointerSegment(missing)}`;
  } else if (error.keyword === 'additionalProperties') {
    const extra = (error.params as { additionalProperty: string }).additionalProperty;
    path = `${path}/${pointerSegment(extra)}`;
  }
  return {
    gate: 1,
    code: `SCHEMA_${error.keyword.toUpperCase()}`,
    path: path === '' ? '/' : path,
    message: error.message ?? 'does not match the Solution Package schema',
  };
}

function issue(
  gate: PackageValidationIssue['gate'],
  code: string,
  path: string,
  message: string,
): PackageValidationIssue {
  return { gate, code, path, message };
}

type VersionBounds = readonly [readonly number[], readonly number[]];

/** Mirrors the deliberately small semver-range reader used by the Python gate. */
function rangeBounds(spec: string): VersionBounds {
  let low: number[] | undefined;
  let high: number[] | undefined;
  for (const token of spec.split(/\s+/)) {
    const version = token
      .replace(/^[><=]+/, '')
      .split('.')
      .filter((part) => /^\d+$/.test(part))
      .map(Number);
    if (token.startsWith('>=')) low = version;
    else if (token.startsWith('<')) high = version;
  }
  return [low ?? [0], high ?? [9999]];
}

function compareVersions(left: readonly number[], right: readonly number[]): number {
  const width = Math.max(left.length, right.length);
  for (let index = 0; index < width; index += 1) {
    const difference = (left[index] ?? 0) - (right[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

function rangesOverlap(left: string, right: string): boolean {
  const [leftLow, leftHigh] = rangeBounds(left);
  const [rightLow, rightHigh] = rangeBounds(right);
  return compareVersions(leftLow, rightHigh) < 0 && compareVersions(rightLow, leftHigh) < 0;
}

function predicateIssues(
  component: PackageComponent,
  componentVersions: readonly PackageVersionId[],
  ownerVersions: readonly PackageVersionId[],
  ownerPath: string,
  ownerName: string,
  when: Readonly<Record<string, string | number | boolean>> | undefined,
): PackageValidationIssue[] {
  if (when === undefined) return [];
  const fields = component.fields ?? [];
  const byKey = new Map(fields.map((field) => [field.key, field]));
  const issues: PackageValidationIssue[] = [];

  for (const [key, value] of Object.entries(when)) {
    const target = byKey.get(key);
    const path = `${ownerPath}/when/${pointerSegment(key)}`;
    if (target === undefined) {
      issues.push(
        issue(4, 'WHEN_UNKNOWN_FIELD', path, `${ownerName} references unknown field “${key}”.`),
      );
      continue;
    }

    const targetVersions = effectiveVersions(target.version, componentVersions);
    const missingVersions = ownerVersions.filter((version) => !targetVersions.includes(version));
    if (missingVersions.length > 0) {
      issues.push(
        issue(
          14,
          'WHEN_FIELD_MISSING_IN_VERSION',
          path,
          `${ownerName} is available in package version(s) ${formatVersions(missingVersions)}, but field “${key}” is not.`,
        ),
      );
    }

    if (target.options !== undefined && !target.options.includes(value)) {
      issues.push(
        issue(
          '5b',
          'WHEN_VALUE_NOT_OPTION',
          path,
          `Value ${JSON.stringify(value)} is not an option declared by “${key}”.`,
        ),
      );
    } else if (
      target.optionsSource === undefined &&
      target.type === 'bool' &&
      typeof value !== 'boolean'
    ) {
      issues.push(
        issue(
          '5b',
          'WHEN_VALUE_NOT_BOOLEAN',
          path,
          `Value for boolean field “${key}” must be true or false.`,
        ),
      );
    } else if (
      target.optionsSource === undefined &&
      target.type === 'int' &&
      (!Number.isInteger(value) || typeof value !== 'number')
    ) {
      issues.push(
        issue(
          '5b',
          'WHEN_VALUE_NOT_INTEGER',
          path,
          `Value for integer field “${key}” must be an integer.`,
        ),
      );
    }

    for (const [ancestorKey, ancestorValue] of Object.entries(target.when ?? {})) {
      if (!(ancestorKey in when)) {
        issues.push(
          issue(
            5,
            'WHEN_OMITS_ANCESTOR',
            path,
            `Predicate omits ancestor “${ancestorKey}” required by “${key}”.`,
          ),
        );
      } else if (when[ancestorKey] !== ancestorValue) {
        issues.push(
          issue(
            5,
            'WHEN_CONTRADICTS_ANCESTOR',
            path,
            `Predicate contradicts ancestor “${ancestorKey}” required by “${key}”.`,
          ),
        );
      }
    }
  }
  return issues;
}

function formatVersions(versions: readonly PackageVersionId[]): string {
  return versions.map(String).join(', ');
}

function effectiveVersions(
  declared: readonly PackageVersionId[] | undefined,
  inherited: readonly PackageVersionId[],
): readonly PackageVersionId[] {
  if (declared === undefined) return inherited;
  return declared.filter((version) => inherited.includes(version));
}

function declaredVersionIssues(pkg: CanonicalSolutionPackage): PackageValidationIssue[] {
  const issues: PackageValidationIssue[] = [];

  pkg.components.forEach((component, componentIndex) => {
    const componentPath = `/components/${String(componentIndex)}`;
    for (const [versionIndex, version] of (component.version ?? []).entries()) {
      if (!pkg.packageVersion.includes(version)) {
        issues.push(
          issue(
            14,
            'COMPONENT_VERSION_NOT_DECLARED',
            `${componentPath}/version/${String(versionIndex)}`,
            `Component version ${String(version)} is not listed in packageVersion.`,
          ),
        );
      }
    }

    const componentVersions = effectiveVersions(component.version, pkg.packageVersion);
    (component.fields ?? []).forEach((field, fieldIndex) => {
      for (const [versionIndex, version] of (field.version ?? []).entries()) {
        const path = `${componentPath}/fields/${String(fieldIndex)}/version/${String(versionIndex)}`;
        if (!pkg.packageVersion.includes(version)) {
          issues.push(
            issue(
              14,
              'FIELD_VERSION_NOT_DECLARED',
              path,
              `Field version ${String(version)} is not listed in packageVersion.`,
            ),
          );
        } else if (!componentVersions.includes(version)) {
          issues.push(
            issue(
              14,
              'FIELD_VERSION_OUTSIDE_COMPONENT',
              path,
              `Field version ${String(version)} is not available for this component.`,
            ),
          );
        }
      }
    });
  });

  return issues;
}

function duplicateIssues(
  values: readonly string[],
  basePath: string,
  label: 'field key' | 'action key' | 'metric name',
): PackageValidationIssue[] {
  const seen = new Set<string>();
  const issues: PackageValidationIssue[] = [];
  values.forEach((value, index) => {
    if (seen.has(value)) {
      issues.push(
        issue(
          9,
          'DUPLICATE_DECLARATION',
          `${basePath}/${String(index)}`,
          `Duplicate ${label} “${value}”.`,
        ),
      );
    }
    seen.add(value);
  });
  return issues;
}

function semanticIssues(
  pkg: CanonicalSolutionPackage,
  options: PackageValidationOptions,
): PackageValidationIssue[] {
  const issues: PackageValidationIssue[] = declaredVersionIssues(pkg);
  const provenance = pkg.provenance;

  if (provenance === undefined) {
    issues.push(
      issue(
        2,
        'PROVENANCE_MISSING',
        '/provenance',
        'A provenance block is required for admission.',
      ),
    );
  } else if (provenance.author === 'solace' && !provenance.signature) {
    issues.push(
      issue(
        2,
        'SIGNATURE_MISSING',
        '/provenance/signature',
        'A Solace-authored package must carry a signature.',
      ),
    );
  } else if (provenance.author === 'dev-mode' && !options.devMode) {
    issues.push(
      issue(
        2,
        'DEV_MODE_DISABLED',
        '/provenance/author',
        'Unsigned development packages are disabled in this build.',
      ),
    );
  }

  for (const existing of options.existingPackages ?? []) {
    if (existing.solution === pkg.solution && rangesOverlap(existing.appliesTo, pkg.appliesTo)) {
      issues.push(
        issue(
          3,
          'APPLIES_TO_OVERLAP',
          '/appliesTo',
          `Range “${pkg.appliesTo}” overlaps loaded range “${existing.appliesTo}” for solution “${pkg.solution}”.`,
        ),
      );
    }
  }

  pkg.components.forEach((component, componentIndex) => {
    const componentPath = `/components/${String(componentIndex)}`;
    const componentVersions = effectiveVersions(component.version, pkg.packageVersion);
    const fields = component.fields ?? [];
    const actions = component.actions ?? [];
    const metrics = component.metrics ?? [];

    issues.push(
      ...duplicateIssues(
        fields.map((field) => field.key),
        `${componentPath}/fields`,
        'field key',
      ),
      ...duplicateIssues(
        actions.map((action) => action.key),
        `${componentPath}/actions`,
        'action key',
      ),
      ...duplicateIssues(
        metrics.map((metric) => metric.name),
        `${componentPath}/metrics`,
        'metric name',
      ),
    );

    fields.forEach((field, fieldIndex) => {
      const fieldPath = `${componentPath}/fields/${String(fieldIndex)}`;
      issues.push(
        ...predicateIssues(
          component,
          componentVersions,
          effectiveVersions(field.version, componentVersions),
          fieldPath,
          `Field “${field.key}”`,
          field.when,
        ),
      );

      if (field.type === 'yaml') {
        const subtree = field.yamlSubtree ?? '';
        for (const other of fields) {
          if (
            other.key !== field.key &&
            (other.key === subtree || other.key.startsWith(`${subtree}.`))
          ) {
            issues.push(
              issue(
                10,
                'YAML_SUBTREE_COLLISION',
                `${fieldPath}/yamlSubtree`,
                `YAML subtree “${subtree}” collides with field “${other.key}”.`,
              ),
            );
          }
        }
      }

      if (field.type === 'secret' && field.secret === false) {
        issues.push(
          issue(
            13,
            'CONTRADICTORY_SECRET_DECLARATION',
            `${fieldPath}/secret`,
            'A secret field cannot also declare secret: false.',
          ),
        );
      }
    });

    actions.forEach((action, actionIndex) => {
      const actionPath = `${componentPath}/actions/${String(actionIndex)}`;
      issues.push(
        ...predicateIssues(
          component,
          componentVersions,
          componentVersions,
          actionPath,
          `Action “${action.key}”`,
          action.when,
        ),
      );
      for (const input of action.inputs ?? []) {
        const inputField: PackageField | undefined = fields.find((field) => field.key === input);
        if (inputField === undefined) {
          issues.push(
            issue(
              4,
              'ACTION_INPUT_UNKNOWN_FIELD',
              `${actionPath}/inputs`,
              `Action input “${input}” is not a field in this component.`,
            ),
          );
          continue;
        }
        const inputVersions = effectiveVersions(inputField.version, componentVersions);
        const missingVersions = componentVersions.filter(
          (version) => !inputVersions.includes(version),
        );
        if (missingVersions.length > 0) {
          issues.push(
            issue(
              14,
              'ACTION_INPUT_MISSING_IN_VERSION',
              `${actionPath}/inputs`,
              `Action input “${input}” is unavailable in package version(s) ${formatVersions(missingVersions)}.`,
            ),
          );
        }
      }
    });

    metrics.forEach((metric, metricIndex) => {
      issues.push(
        ...predicateIssues(
          component,
          componentVersions,
          componentVersions,
          `${componentPath}/metrics/${String(metricIndex)}`,
          `Metric “${metric.name}”`,
          metric.when,
        ),
      );
    });

    const predicateKeys = new Set(
      [...fields, ...actions, ...metrics].flatMap((declaration) =>
        Object.keys(declaration.when ?? {}),
      ),
    );
    fields.forEach((field, fieldIndex) => {
      if (
        predicateKeys.has(field.key) &&
        (field.default === undefined || field.default === null) &&
        field.ui?.readOnly === true
      ) {
        issues.push(
          issue(
            12,
            'UNREACHABLE_READ_ONLY_DISCRIMINATOR',
            `${componentPath}/fields/${String(fieldIndex)}`,
            `Field “${field.key}” drives a predicate but has no default and is read-only.`,
          ),
        );
      }
    });
  });

  return issues;
}

function summarize(pkg: CanonicalSolutionPackage): PackageValidationSummary {
  const components = pkg.components.map((component) => ({
    type: component.type,
    cardinality: component.cardinality,
    fieldCount: component.fields?.length ?? 0,
    actionCount: component.actions?.length ?? 0,
    metricCount: component.metrics?.length ?? 0,
  }));
  return {
    solution: pkg.solution,
    packageVersion: pkg.packageVersion,
    appliesTo: pkg.appliesTo,
    componentCount: components.length,
    fieldCount: components.reduce((count, component) => count + component.fieldCount, 0),
    actionCount: components.reduce((count, component) => count + component.actionCount, 0),
    metricCount: components.reduce((count, component) => count + component.metricCount, 0),
    components,
  };
}

export function validateSolutionPackage(
  value: unknown,
  options: PackageValidationOptions,
): PackageValidationResult {
  if (!validateSchema(value)) {
    return { valid: false, issues: (validateSchema.errors ?? []).map(schemaIssue) };
  }

  const issues = semanticIssues(value, options);
  if (issues.length > 0) return { valid: false, issues };
  return { valid: true, package: value, summary: summarize(value) };
}

export function parseAndValidateSolutionPackage(
  text: string,
  options: PackageValidationOptions,
): PackageValidationResult {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    return {
      valid: false,
      issues: [
        issue(
          1,
          'MALFORMED_JSON',
          '/',
          error instanceof Error ? error.message : 'The selected file is not valid JSON.',
        ),
      ],
    };
  }
  return validateSolutionPackage(value, options);
}
