import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { defaultExpandedSections } from './composerSectionExpansion';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: 'field',
    label: 'Field',
    renderedPath: 'component.field',
    discriminator: false,
    controlKind: 'text',
    value: { kind: 'unset' },
    options: [],
    enabled: true,
    visible: true,
    masked: false,
    advanced: false,
    validations: [],
    ...overrides,
  };
}

function section(overrides: Partial<ViewSection> = {}): ViewSection {
  return { title: 'Section', order: 0, fields: [], actions: [], ...overrides };
}

describe('defaultExpandedSections', () => {
  it('starts a normal section expanded', () => {
    const sections = [section({ fields: [field({ advanced: false })] })];

    expect(defaultExpandedSections(sections)).toEqual(new Set([0]));
  });

  it('starts an all-advanced section collapsed', () => {
    const sections = [section({ fields: [field({ advanced: true }), field({ key: 'f2', advanced: true })] })];

    expect(defaultExpandedSections(sections)).toEqual(new Set());
  });

  it('starts a mixed normal/advanced section expanded — allAdvanced requires every applicable field to be advanced', () => {
    const sections = [section({ fields: [field({ advanced: true }), field({ key: 'f2', advanced: false })] })];

    expect(defaultExpandedSections(sections)).toEqual(new Set([0]));
  });

  it('preserves each section\'s index across a mixed list, not just the count', () => {
    const sections = [
      section({ fields: [field({ advanced: false })] }), // 0: normal -> expanded
      section({ fields: [field({ advanced: true })] }), // 1: all-advanced -> collapsed
      section({ fields: [field({ advanced: false })] }), // 2: normal -> expanded
    ];

    expect(defaultExpandedSections(sections)).toEqual(new Set([0, 2]));
  });

  it('starts an empty section (no applicable fields) expanded — allAdvanced is false when there is nothing to be advanced', () => {
    const sections = [section({ fields: [] })];

    expect(defaultExpandedSections(sections)).toEqual(new Set([0]));
  });
});
