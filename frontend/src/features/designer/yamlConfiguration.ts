import { dump, load } from 'js-yaml';

import type { DraftValues, FieldValue, ViewField } from '@contracts/view-model/types';

/** The minimum information needed to project the shared draft into YAML and back. */
export interface YamlFieldRef {
  instanceId: string;
  field: Pick<ViewField, 'key' | 'label' | 'renderedPath' | 'controlKind' | 'value'>;
}

const MISSING = Symbol('yaml-missing');

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isFieldValue(value: unknown): value is FieldValue {
  if (
    value === null ||
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean'
  ) {
    return true;
  }
  if (Array.isArray(value)) {
    return value.every((item) => isFieldValue(item));
  }
  return isRecord(value) && Object.values(value).every((item) => isFieldValue(item));
}

function sameValue(left: FieldValue, right: FieldValue): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function getAtPath(root: unknown, path: string): unknown {
  let current: unknown = root;
  for (const segment of path.split('.')) {
    if (!isRecord(current) || !(segment in current)) {
      return MISSING;
    }
    current = current[segment];
  }
  return current;
}

function setAtPath(root: Record<string, unknown>, path: string, value: FieldValue): void {
  const segments = path.split('.').filter((segment) => segment.length > 0);
  if (segments.length === 0) {
    throw new Error(`Field path '${path}' is empty.`);
  }
  let current = root;
  for (const segment of segments.slice(0, -1)) {
    const existing = current[segment];
    if (existing !== undefined && !isRecord(existing)) {
      throw new Error(`Field paths conflict at '${segment}'.`);
    }
    if (!isRecord(existing)) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  }
  const leaf = segments[segments.length - 1];
  if (leaf !== undefined) {
    current[leaf] = value;
  }
}

function unknownPath(
  root: Record<string, unknown>,
  fields: readonly YamlFieldRef[],
): string | undefined {
  const known = fields
    .map((ref) => ref.field.renderedPath)
    .filter((path): path is string => path !== null);
  const walk = (value: Record<string, unknown>, prefix: string): string | undefined => {
    for (const [key, child] of Object.entries(value)) {
      const path = prefix.length === 0 ? key : `${prefix}.${key}`;
      if (known.includes(path)) continue;
      if (!known.some((candidate) => candidate.startsWith(`${path}.`))) {
        return path;
      }
      if (!isRecord(child)) {
        return path;
      }
      const nested = walk(child, path);
      if (nested !== undefined) return nested;
    }
    return undefined;
  };
  return walk(root, '');
}

function yamlTypeMatches(controlKind: ViewField['controlKind'], value: FieldValue): boolean {
  switch (controlKind) {
    case 'number':
      return typeof value === 'number' && Number.isFinite(value);
    case 'toggle':
      return typeof value === 'boolean';
    case 'list':
    case 'table':
      return Array.isArray(value);
    case 'map':
      return isRecord(value);
    case 'text':
    case 'select':
    case 'duration':
      return typeof value === 'string';
    case 'yaml':
      return true;
    case 'secret':
      return false;
  }
}

/** Serialize the currently projected, non-secret fields using their package paths. */
export function yamlFromFields(fields: readonly YamlFieldRef[]): {
  text: string;
  omittedSecrets: number;
} {
  const root: Record<string, unknown> = {};
  let omittedSecrets = 0;
  for (const ref of fields) {
    if (ref.field.renderedPath === null) {
      continue;
    }
    if (ref.field.value.kind === 'secretUnset' || ref.field.value.kind === 'secretRef') {
      omittedSecrets += 1;
      continue;
    }
    if (ref.field.value.kind === 'unset') {
      setAtPath(root, ref.field.renderedPath, null);
      continue;
    }
    setAtPath(root, ref.field.renderedPath, ref.field.value.value);
  }
  const header =
    omittedSecrets > 0
      ? '# Sensitive fields are intentionally omitted. Edit them in Form View.\n'
      : '';
  return { text: `${header}${dump(root, { noRefs: true, lineWidth: -1 })}`, omittedSecrets };
}

/** Parse YAML and convert only known package fields back into the shared draft shape. */
export function draftValuesFromYaml(
  text: string,
  fields: readonly YamlFieldRef[],
): { values: DraftValues; error?: string | undefined } {
  let document: unknown;
  try {
    document = text.trim().length === 0 ? {} : load(text, { json: false });
  } catch (error) {
    return {
      values: {},
      error: `YAML syntax error: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
  if (!isRecord(document)) {
    return { values: {}, error: 'YAML configuration must have a mapping/object at the root.' };
  }
  const unrecognizedPath = unknownPath(document, fields);
  if (unrecognizedPath !== undefined) {
    return {
      values: {},
      error: `Configuration path '${unrecognizedPath}' is not defined by this package.`,
    };
  }

  const next: Record<string, Record<string, FieldValue>> = {};
  for (const ref of fields) {
    const path = ref.field.renderedPath;
    if (
      path === null ||
      ref.field.value.kind === 'secretUnset' ||
      ref.field.value.kind === 'secretRef'
    ) {
      continue;
    }
    const value = getAtPath(document, path);
    if (value === MISSING) {
      // An omitted field keeps its current form value, which is important for secrets and
      // for editing only a small part of a large connector configuration.
      continue;
    }
    if (value === null && ref.field.value.kind === 'unset') {
      // Unset fields are rendered as `null` so every field is visible in YAML View; leaving
      // that placeholder untouched must not be treated as a type error.
      continue;
    }
    if (!isFieldValue(value)) {
      return { values: {}, error: `Field '${path}' contains a value that cannot be represented.` };
    }
    if (!yamlTypeMatches(ref.field.controlKind, value)) {
      return { values: {}, error: `Field '${path}' has the wrong YAML type for this control.` };
    }
    // A connection-resolved field is displayed in YAML as its effective value, but the
    // draft must retain the connection binding unless the operator deliberately changes
    // that value in YAML. Otherwise merely editing an unrelated field would silently turn
    // every derived host/port/VPN into a literal override.
    if (
      ref.field.value.kind === 'value' &&
      ref.field.value.source === 'connection' &&
      sameValue(ref.field.value.value, value)
    ) {
      continue;
    }
    (next[ref.instanceId] ??= {})[ref.field.key] = value;
  }
  return { values: next };
}
