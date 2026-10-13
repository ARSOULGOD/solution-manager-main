import { describe, expect, it } from 'vitest';

import { migrateWidgetDocument, type MigrationStep, type WidgetDocument } from './migrations';
import { CURRENT_WIDGET_SCHEMA_VERSION } from './widgetConfig';

// A synthetic v1 -> v4 chain — no real migration exists yet (the schema is
// still v1), so this proves the ENGINE runs an ordered, multi-step chain
// before any real step does. Each step adds one marker field so the test
// can tell which steps actually ran, and in what order.
const SYNTHETIC_CHAIN: Readonly<Record<number, MigrationStep>> = {
  1: (doc) => ({ ...doc, schemaVersion: 2, ranStep: [...((doc.ranStep as string[]) ?? []), '1->2'] }),
  2: (doc) => ({ ...doc, schemaVersion: 3, ranStep: [...((doc.ranStep as string[]) ?? []), '2->3'] }),
  3: (doc) => ({ ...doc, schemaVersion: 4, ranStep: [...((doc.ranStep as string[]) ?? []), '3->4'] }),
};

describe('migrateWidgetDocument', () => {
  it('reports `current` and does not touch a document already at the target version', () => {
    const doc: WidgetDocument = { schemaVersion: 1, title: 'x' };
    const result = migrateWidgetDocument(doc, 1, {});
    expect(result).toEqual({ status: 'current', config: doc });
  });

  it('runs the ordered chain v1 -> v4, three steps, in order', () => {
    const doc: WidgetDocument = { schemaVersion: 1, title: 'x' };
    const result = migrateWidgetDocument(doc, 4, SYNTHETIC_CHAIN);
    expect(result.status).toBe('migrated');
    if (result.status !== 'migrated') throw new Error('unreachable');
    expect(result.fromVersion).toBe(1);
    expect(result.config.schemaVersion).toBe(4);
    expect(result.config.ranStep).toEqual(['1->2', '2->3', '3->4']);
  });

  it('preserves an unknown field across every step of the chain', () => {
    const doc: WidgetDocument = { schemaVersion: 1, title: 'x', somethingThisConsoleDoesNotKnow: 'keep-me' };
    const result = migrateWidgetDocument(doc, 4, SYNTHETIC_CHAIN);
    expect(result.status).toBe('migrated');
    if (result.status !== 'migrated') throw new Error('unreachable');
    expect(result.config.somethingThisConsoleDoesNotKnow).toBe('keep-me');
  });

  it('reports `too-new` for a document ahead of the console, and does not touch it', () => {
    const doc: WidgetDocument = { schemaVersion: 4, title: 'x' };
    const result = migrateWidgetDocument(doc, 1, {});
    expect(result).toEqual({ status: 'too-new', declaredVersion: 4 });
  });

  it('stops (rather than looping) when the chain has a registered gap', () => {
    const doc: WidgetDocument = { schemaVersion: 1, title: 'x' };
    // No step registered FROM version 2 — the chain cannot reach 4.
    const gappedChain: Readonly<Record<number, MigrationStep>> = { 1: SYNTHETIC_CHAIN[1] as MigrationStep };
    const result = migrateWidgetDocument(doc, 4, gappedChain);
    expect(result.status).toBe('migrated');
    if (result.status !== 'migrated') throw new Error('unreachable');
    expect(result.config.schemaVersion).toBe(2);
  });

  it('defaults `targetVersion` to the console\'s own current schema version', () => {
    const doc: WidgetDocument = { schemaVersion: CURRENT_WIDGET_SCHEMA_VERSION, title: 'x' };
    const result = migrateWidgetDocument(doc);
    expect(result.status).toBe('current');
  });
});
