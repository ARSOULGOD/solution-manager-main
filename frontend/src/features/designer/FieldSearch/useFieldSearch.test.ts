import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { useFieldSearch } from './useFieldSearch';

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

describe('useFieldSearch', () => {
  it('counts every declared field, visible or not, for the label', () => {
    const { result } = renderHook(() =>
      useFieldSearch([section({ fields: [field({ key: 'a' }), field({ key: 'b', visible: false })] })]),
    );
    expect(result.current.totalFieldCount).toBe(2);
  });

  it('is inactive with no hits until a query is set', () => {
    const { result } = renderHook(() => useFieldSearch([section()]));
    expect(result.current.isActive).toBe(false);
    expect(result.current.hits).toEqual([]);
  });

  it('matches label, key, rendered path or section, case-insensitively', () => {
    const sections = [
      section({
        title: 'Broker',
        fields: [
          field({ key: 'host-name', label: 'Host', renderedPath: 'broker.host' }),
          field({ key: 'port', label: 'Port', renderedPath: 'broker.port' }),
        ],
      }),
      section({
        title: 'Sizing',
        fields: [field({ key: 'max-entries', label: 'Max entries', renderedPath: 'sizing.max' })],
      }),
    ];
    const { result } = renderHook(() => useFieldSearch(sections));

    act(() => result.current.setQuery('HOST'));
    expect(result.current.hits.map((hit) => hit.field.key)).toEqual(['host-name']);

    act(() => result.current.setQuery('sizing'));
    expect(result.current.hits.map((hit) => hit.field.key)).toEqual(['max-entries']);

    act(() => result.current.setQuery('broker.port'));
    expect(result.current.hits.map((hit) => hit.field.key)).toEqual(['port']);
  });

  it('excludes a hidden-branch field even when its label matches', () => {
    const { result } = renderHook(() =>
      useFieldSearch([section({ fields: [field({ key: 'a', label: 'Namespace', visible: false })] })]),
    );
    act(() => result.current.setQuery('namespace'));
    expect(result.current.hits).toEqual([]);
  });

  it('clears back to inactive when the query is emptied', () => {
    const { result } = renderHook(() => useFieldSearch([section({ fields: [field({ label: 'Namespace' })] })]));
    act(() => result.current.setQuery('name'));
    expect(result.current.isActive).toBe(true);
    act(() => result.current.setQuery(''));
    expect(result.current.isActive).toBe(false);
    expect(result.current.hits).toEqual([]);
  });

  it('carries the matched section title on each hit', () => {
    const { result } = renderHook(() =>
      useFieldSearch([section({ title: 'Broker', fields: [field({ key: 'host', label: 'Host' })] })]),
    );
    act(() => result.current.setQuery('host'));
    expect(result.current.hits).toHaveLength(1);
    expect(result.current.hits[0]?.field.key).toBe('host');
    expect(result.current.hits[0]?.sectionTitle).toBe('Broker');
  });
});
