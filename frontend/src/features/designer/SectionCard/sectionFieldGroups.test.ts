import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { groupSectionFields } from './sectionFieldGroups';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'a',
    label: overrides.label ?? 'A',
    renderedPath: 'component.a',
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
  return { title: 'General', order: 0, fields: [field()], actions: [], ...overrides };
}

describe('groupSectionFields', () => {
  it('excludes hidden (non-visible) fields from every count and group', () => {
    const groups = groupSectionFields(
      section({ fields: [field({ key: 'a', visible: true }), field({ key: 'b', visible: false })] }),
    );
    expect(groups.applicable.map((f) => f.key)).toEqual(['a']);
  });

  it('is all-advanced only when there is at least one applicable field and every one is advanced', () => {
    expect(groupSectionFields(section({ fields: [] })).allAdvanced).toBe(false);
    expect(groupSectionFields(section({ fields: [field({ advanced: true })] })).allAdvanced).toBe(true);
    expect(
      groupSectionFields(section({ fields: [field({ key: 'a' }), field({ key: 'b', advanced: true })] })).allAdvanced,
    ).toBe(false);
  });

  it('puts every field in `normal` (none in `advanced`) when the section is all-advanced', () => {
    const groups = groupSectionFields(section({ fields: [field({ advanced: true })] }));
    expect(groups.normal.length).toBe(1);
    expect(groups.advanced.length).toBe(0);
  });

  it('counts a bind-tier validation as blocking and a semantic-tier one as a warning', () => {
    const groups = groupSectionFields(
      section({
        fields: [
          field({ key: 'a', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
          field({ key: 'b', validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' }] }),
        ],
      }),
    );
    expect(groups.blockCount).toBe(1);
    expect(groups.warnCount).toBe(1);
  });

  it('excludes disabled fields from the block/warn counts', () => {
    const groups = groupSectionFields(
      section({ fields: [field({ enabled: false, validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] })] }),
    );
    expect(groups.blockCount).toBe(0);
  });
});
