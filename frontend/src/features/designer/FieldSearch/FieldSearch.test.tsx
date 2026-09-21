import { act, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { FieldValue, ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { FieldSearch } from './FieldSearch';
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

function Harness({ sections, onFieldChange = () => {} }: { sections: readonly ViewSection[]; onFieldChange?: (key: string, value: FieldValue) => void }) {
  const { query, setQuery, totalFieldCount, hits } = useFieldSearch(sections);
  return (
    <FieldSearch
      query={query}
      onQueryChange={setQuery}
      totalFieldCount={totalFieldCount}
      hits={hits}
      values={{}}
      onFieldChange={onFieldChange}
    />
  );
}

describe('FieldSearch', () => {
  it('labels the box with the total declared field count, including hidden fields', () => {
    renderWithProviders(
      <Harness
        sections={[
          section({ fields: [field({ key: 'a' }), field({ key: 'b', visible: false })] }),
          section({ title: 'Broker', fields: [field({ key: 'c' })] }),
        ]}
      />,
    );
    expect(screen.getByLabelText('Search 3 fields…')).not.toBeNull();
  });

  it('shows no results panel while the query is empty', () => {
    renderWithProviders(<Harness sections={[section()]} />);
    expect(screen.queryByText(/field.*match/i)).toBeNull();
  });

  it('matches label, key, rendered path and section, debounced, and excludes hidden-branch fields', () => {
    vi.useFakeTimers();
    renderWithProviders(
      <Harness
        sections={[
          section({
            title: 'Broker',
            fields: [
              field({ key: 'host-name', label: 'Host', renderedPath: 'broker.host' }),
              field({ key: 'hidden-host', label: 'Hidden host', visible: false }),
            ],
          }),
        ]}
      />,
    );

    fireEvent.change(screen.getByLabelText('Search 2 fields…'), { target: { value: 'host' } });
    // Debounced — no filtering yet.
    expect(screen.queryByText('Hidden host')).toBeNull();
    expect(screen.queryByLabelText('Host')).toBeNull();

    void act(() => vi.advanceTimersByTime(300));

    expect(screen.getByLabelText('Host')).not.toBeNull();
    expect(screen.queryByLabelText('Hidden host')).toBeNull();
    expect(screen.getByText('1 field matches "host"')).not.toBeNull();
    expect(screen.getByText('Broker')).not.toBeNull();

    vi.useRealTimers();
  });

  it('states no fields match when the query has no hits', () => {
    vi.useFakeTimers();
    renderWithProviders(<Harness sections={[section({ fields: [field({ label: 'Namespace' })] })]} />);

    fireEvent.change(screen.getByLabelText('Search 1 field…'), { target: { value: 'zzz' } });
    void act(() => vi.advanceTimersByTime(300));

    expect(screen.getByText('No fields match "zzz".')).not.toBeNull();
    vi.useRealTimers();
  });

  it('announces the result state politely', () => {
    vi.useFakeTimers();
    renderWithProviders(<Harness sections={[section({ fields: [field({ label: 'Namespace' })] })]} />);

    fireEvent.change(screen.getByLabelText('Search 1 field…'), { target: { value: 'name' } });
    void act(() => vi.advanceTimersByTime(300));

    const live = screen.getByText('1 field matches "name"').closest('[aria-live]');
    expect(live?.getAttribute('aria-live')).toBe('polite');
    vi.useRealTimers();
  });

  it('forwards edits on a matched field', () => {
    vi.useFakeTimers();
    const onFieldChange = vi.fn();
    renderWithProviders(
      <Harness sections={[section({ fields: [field({ key: 'namespace', label: 'Namespace' })] })]} onFieldChange={onFieldChange} />,
    );

    fireEvent.change(screen.getByLabelText('Search 1 field…'), { target: { value: 'name' } });
    void act(() => vi.advanceTimersByTime(300));

    fireEvent.change(screen.getByLabelText('Namespace'), { target: { value: 'acme' } });
    expect(onFieldChange).toHaveBeenCalledWith('namespace', 'acme');
    vi.useRealTimers();
  });
});
