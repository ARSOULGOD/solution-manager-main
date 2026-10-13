import { describe, expect, it } from 'vitest';

import { DESCRIPTORS } from '@shared/testdoubles/data/connections';

import { deriveColumns } from './columns';

describe('deriveColumns', () => {
  it('takes exactly the attributes the descriptor marks listColumn, in ui.order', () => {
    expect(deriveColumns(DESCRIPTORS['solace-broker'])).toEqual([
      { key: 'host', label: 'Host' },
      { key: 'vpn', label: 'Message VPN' },
    ]);
  });

  it('derives a different column set per type with no per-type code', () => {
    expect(deriveColumns(DESCRIPTORS['remote-server']).map((c) => c.key))
      .toEqual(['host', 'username', 'authMethod']);
  });

  it('never exposes a secret attribute as a column (I6)', () => {
    for (const descriptor of Object.values(DESCRIPTORS)) {
      const secretKeys = descriptor.attributes.filter((a) => a.secret).map((a) => a.key);
      const columnKeys = deriveColumns(descriptor).map((c) => c.key);
      expect(columnKeys.filter((k) => secretKeys.includes(k))).toEqual([]);
    }
  });
});
