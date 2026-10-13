/**
 * Catalog list columns, DERIVED from the descriptor (`UI-D27`). A third
 * connection type gets its columns from its own descriptor with no change
 * here — which is the point of the descriptor being a field list.
 */
import type { ConnectionDescriptor } from '@shared/testdoubles/data/connections';

export interface ColumnDef {
  readonly key: string;
  readonly label: string;
}

export function deriveColumns(descriptor: ConnectionDescriptor): ColumnDef[] {
  return descriptor.attributes
    // A secret can never be a column. The guard is here as well as in the
    // descriptor schema because this is the code that would leak it (`I6`).
    .filter((attribute) => attribute.ui?.listColumn === true && attribute.secret !== true)
    .slice()
    .sort((a, b) => (a.ui?.order ?? 0) - (b.ui?.order ?? 0))
    .map((attribute) => ({ key: attribute.key, label: attribute.ui?.label ?? attribute.key }));
}
