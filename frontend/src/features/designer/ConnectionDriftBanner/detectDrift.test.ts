import { describe, expect, it } from 'vitest';

import { detectDrift } from './detectDrift';

const APPLIED = { 'conn-7': 3 };
const CURRENT = [
  { id: 'conn-7', name: 'prod-broker-01', revision: 4 },
  { id: 'conn-8', name: 'stage-broker-01', revision: 1 },
];

describe('detectDrift', () => {
  it('reports a connection whose revision advanced since apply', () => {
    expect(detectDrift(APPLIED, CURRENT, { 'conn-7': ['solace.host', 'solace.vpn'] })).toEqual([
      {
        connectionId: 'conn-7',
        name: 'prod-broker-01',
        appliedRevision: 3,
        currentRevision: 4,
        affectedFields: ['solace.host', 'solace.vpn'],
      },
    ]);
  });

  it('reports nothing when revisions match', () => {
    expect(detectDrift({ 'conn-7': 4 }, CURRENT, { 'conn-7': ['solace.host'] })).toEqual([]);
  });

  it('ignores a connection this instance does not reference', () => {
    expect(detectDrift(APPLIED, CURRENT, {})).toEqual([]);
  });

  it('reports nothing when a connection has been deleted rather than guessing', () => {
    expect(detectDrift({ 'conn-99': 1 }, CURRENT, { 'conn-99': ['solace.host'] })).toEqual([]);
  });
});
