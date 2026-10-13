/**
 * Descriptor-driven form logic (`UI-D27`) — pure, so every rule is
 * asserted without rendering a drawer, the same split `declareForm.ts`
 * uses.
 *
 * There is NO connection-type knowledge in this file. `authMethod` is an
 * ordinary discriminator; a future type's discriminator works unchanged.
 */
import type { ConnectionDescriptor, ConnectionDescriptorAttribute }
  from '@shared/testdoubles/data/connections';

export type AttributeValues = Readonly<Record<string, unknown>>;

/**
 * `ConnectionDescriptorAttribute` (task 6) has no `min`/`max`/`pattern`
 * fields even though the JSON descriptors declare them (see
 * `contracts/connection-types/remote-server.json`'s `port` attribute) — a
 * type gap in task 6, not touched here to keep this task's edits inside
 * `ConnectionDrawer/`. This local extension reads those JSON-declared
 * fields without widening the shared testdouble type.
 */
type ConstrainedAttribute = ConnectionDescriptorAttribute & {
  readonly min?: number;
  readonly max?: number;
  readonly pattern?: string;
};

/** `when` is an all-must-match equality map, exactly as `project()` evaluates it. */
function matches(when: ConnectionDescriptorAttribute['when'], values: AttributeValues): boolean {
  if (when === undefined) return true;
  return Object.entries(when).every(([key, expected]) => values[key] === expected);
}

export function visibleAttributes(
  descriptor: ConnectionDescriptor,
  values: AttributeValues,
): ConnectionDescriptorAttribute[] {
  return descriptor.attributes
    .filter((attribute) => matches(attribute.when, values))
    .slice()
    .sort((a, b) => (a.ui?.order ?? 0) - (b.ui?.order ?? 0));
}

export function validateConnection(
  descriptor: ConnectionDescriptor,
  values: AttributeValues,
  secretStatus: Readonly<Record<string, 'set' | 'unset'>>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const attribute of visibleAttributes(descriptor, values) as ConstrainedAttribute[]) {
    const isSecret = attribute.secret === true || attribute.type === 'secret';
    const value = values[attribute.key];

    if (attribute.required === true) {
      // A secret satisfies "required" by already being set - the operator
      // must not retype a password to change a port.
      const satisfied = isSecret
        ? secretStatus[attribute.key] === 'set' || (typeof value === 'string' && value !== '')
        : value !== undefined && value !== null && value !== '';
      if (!satisfied) {
        errors[attribute.key] = 'Required.';
        continue;
      }
    }

    if (isSecret) continue; // nothing below inspects a secret's value

    if (typeof value === 'number') {
      if (attribute.min !== undefined && value < attribute.min) {
        errors[attribute.key] = `Must be at least ${String(attribute.min)}.`;
      } else if (attribute.max !== undefined && value > attribute.max) {
        errors[attribute.key] = `Must be at most ${String(attribute.max)}.`;
      }
    }

    if (attribute.pattern !== undefined && typeof value === 'string' && value !== ''
        && !new RegExp(attribute.pattern).test(value)) {
      errors[attribute.key] = 'Invalid format.';
    }
  }
  return errors;
}
