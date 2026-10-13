import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { collectValidationEntries } from './collectValidationEntries';

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

describe('collectValidationEntries', () => {
  it('sorts a bind-tier field into blocks and a semantic-tier field into warns, tagged with its section index', () => {
    const { blocks, warns } = collectValidationEntries([
      section({
        title: 'General',
        fields: [
          field({ key: 'a', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
        ],
      }),
      section({
        title: 'Sizing',
        fields: [
          field({
            key: 'b',
            label: 'B',
            validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' }],
          }),
        ],
      }),
    ]);

    expect(blocks).toEqual([{ sectionIndex: 0, fieldKey: 'a', label: 'A', message: 'x' }]);
    expect(warns).toEqual([{ sectionIndex: 1, fieldKey: 'b', label: 'B', message: 'y' }]);
  });

  it('excludes a hidden (non-visible) field even when it carries a validation', () => {
    const { blocks, warns } = collectValidationEntries([
      section({
        fields: [
          field({
            visible: false,
            validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }],
          }),
        ],
      }),
    ]);
    expect(blocks).toEqual([]);
    expect(warns).toEqual([]);
  });

  it('excludes a disabled field even when it carries a validation', () => {
    const { blocks } = collectValidationEntries([
      section({
        fields: [
          field({
            enabled: false,
            validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }],
          }),
        ],
      }),
    ]);
    expect(blocks).toEqual([]);
  });

  it("carries the firing validation's own message on the entry", () => {
    const { blocks, warns } = collectValidationEntries([
      section({
        fields: [
          field({
            key: 'a',
            validations: [{ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' }],
          }),
        ],
      }),
      section({
        fields: [
          field({
            key: 'b',
            validations: [
              {
                tier: 'semantic',
                code: 'ABOVE_RECOMMENDED',
                message: 'Above the range Solace recommends (1–100).',
              },
            ],
          }),
        ],
      }),
    ]);

    expect(blocks[0]?.message).toBe('This field is required.');
    expect(warns[0]?.message).toBe('Above the range Solace recommends (1–100).');
  });

  it('counts a field with both tiers as blocking only, matching `groupSectionFields`', () => {
    const { blocks, warns } = collectValidationEntries([
      section({
        fields: [
          field({
            validations: [
              { tier: 'bind', code: 'REQUIRED', message: 'x' },
              { tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' },
            ],
          }),
        ],
      }),
    ]);
    expect(blocks.length).toBe(1);
    expect(warns.length).toBe(0);
  });
});
