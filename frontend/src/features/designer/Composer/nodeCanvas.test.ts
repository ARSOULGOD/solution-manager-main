import { describe, expect, it } from 'vitest';

import type { ComponentSelection } from '@contracts/view-model/types';

import {
  defaultNodePositions,
  derivedLinks,
  directionForKey,
  isComposerEligible,
  nudgePosition,
} from './nodeCanvas';

const FILE_MESH_SELECTION: ComponentSelection = [
  { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
  { type: 'COMP_SINK_SOLACE', instanceId: 'snk', role: 'sink' },
];

const CACHE_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'cache-0' }];

describe('defaultNodePositions', () => {
  it('places a source strictly left of a sink', () => {
    const positions = defaultNodePositions(FILE_MESH_SELECTION);
    expect(positions['src']?.x).toBeLessThan(positions['snk']?.x ?? 0);
  });

  it('stacks two nodes in the same column instead of overlapping them', () => {
    const selection: ComponentSelection = [
      { type: 'COMP_SOURCE_FTP', instanceId: 'src-1', role: 'source' },
      { type: 'COMP_SOURCE_SFTP', instanceId: 'src-2', role: 'source' },
    ];
    const positions = defaultNodePositions(selection);
    expect(positions['src-1']?.x).toBe(positions['src-2']?.x);
    expect(positions['src-1']?.y).not.toBe(positions['src-2']?.y);
  });

  it('gives an undeclared role its own column, distinct from source and sink', () => {
    const selection: ComponentSelection = [
      { type: 'cache-engine', instanceId: 'cache-0' },
      { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
      { type: 'COMP_SINK_SOLACE', instanceId: 'snk', role: 'sink' },
    ];
    const positions = defaultNodePositions(selection);
    const xs = [positions['cache-0']?.x, positions['src']?.x, positions['snk']?.x];
    expect(new Set(xs).size).toBe(3);
  });
});

describe('derivedLinks', () => {
  it('links every source to every sink (single source, single sink)', () => {
    expect(derivedLinks(FILE_MESH_SELECTION)).toEqual([{ from: 'src', to: 'snk' }]);
  });

  it('fans out a single source to every declared sink', () => {
    const selection: ComponentSelection = [
      { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
      { type: 'COMP_SINK_SOLACE', instanceId: 'snk-1', role: 'sink' },
      { type: 'COMP_SINK_FILE', instanceId: 'snk-2', role: 'sink' },
    ];
    expect(derivedLinks(selection)).toEqual([
      { from: 'src', to: 'snk-1' },
      { from: 'src', to: 'snk-2' },
    ]);
  });

  it('derives no link for a single-component selection with no role at all', () => {
    expect(derivedLinks(CACHE_SELECTION)).toEqual([]);
  });
});

describe('directionForKey', () => {
  it.each([
    ['ArrowUp', 'up'],
    ['ArrowDown', 'down'],
    ['ArrowLeft', 'left'],
    ['ArrowRight', 'right'],
  ] as const)('maps %s to %s', (key, direction) => {
    expect(directionForKey(key)).toBe(direction);
  });

  it('returns undefined for a non-arrow key, so the caller falls through to default handling', () => {
    expect(directionForKey('Enter')).toBeUndefined();
    expect(directionForKey(' ')).toBeUndefined();
    expect(directionForKey('a')).toBeUndefined();
  });
});

describe('nudgePosition', () => {
  const origin: NodePositionForTest = { x: 100, y: 100 };

  it.each([
    ['up', { x: 100, y: 84 }],
    ['down', { x: 100, y: 116 }],
    ['left', { x: 84, y: 100 }],
    ['right', { x: 116, y: 100 }],
  ] as const)('moves %s by the default step', (direction, expected) => {
    expect(nudgePosition(origin, direction)).toEqual(expected);
  });

  it('clamps at zero rather than nudging into negative coordinates', () => {
    expect(nudgePosition({ x: 4, y: 4 }, 'left', 16)).toEqual({ x: 0, y: 4 });
    expect(nudgePosition({ x: 4, y: 4 }, 'up', 16)).toEqual({ x: 4, y: 0 });
  });

  it('accepts a custom step', () => {
    expect(nudgePosition(origin, 'right', 4)).toEqual({ x: 104, y: 100 });
  });
});

describe('isComposerEligible', () => {
  it('is false for a single-component selection (Cache)', () => {
    expect(isComposerEligible(CACHE_SELECTION)).toBe(false);
  });

  it('is true for a multi-component selection (File Mesh)', () => {
    expect(isComposerEligible(FILE_MESH_SELECTION)).toBe(true);
  });

  it('is false for an empty selection', () => {
    expect(isComposerEligible([])).toBe(false);
  });
});

type NodePositionForTest = { x: number; y: number };
