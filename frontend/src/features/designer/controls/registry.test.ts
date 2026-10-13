import { describe, expect, it } from 'vitest';

import type { ControlKind } from '@contracts/view-model/types';

import { controlRegistry } from './registry';

const ALL_KINDS: readonly ControlKind[] = [
  'text',
  'number',
  'toggle',
  'select',
  'duration',
  'list',
  'map',
  'table',
  'yaml',
  'secret',
];

describe('controlRegistry', () => {
  it.each(ALL_KINDS)('has a component registered for "%s"', (kind) => {
    expect(controlRegistry[kind]).toBeDefined();
  });

  it('registers exactly the ten ControlKind members, no more and no fewer', () => {
    expect(Object.keys(controlRegistry).sort()).toEqual([...ALL_KINDS].sort());
  });
});
