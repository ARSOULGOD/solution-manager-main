import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SectionCard } from './SectionCard';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'namespace',
    label: overrides.label ?? 'Namespace',
    renderedPath: 'cache-engine.namespace',
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
  return {
    title: 'General',
    order: 0,
    fields: [field()],
    actions: [],
    ...overrides,
  };
}

describe('SectionCard', () => {
  it('is collapsed when not expanded, and toggles via the header', () => {
    const onToggleExpanded = vi.fn();
    renderWithProviders(
      <SectionCard
        section={section()}
        values={{}}
        onFieldChange={() => {}}
        expanded={false}
        onToggleExpanded={onToggleExpanded}
      />,
    );

    expect(screen.queryByLabelText('Namespace')).toBeNull();
    fireEvent.click(screen.getByText('General'));
    expect(onToggleExpanded).toHaveBeenCalledTimes(1);
  });

  it('counts only applicable (visible) fields in the header, excluding hidden branches', () => {
    renderWithProviders(
      <SectionCard
        section={section({
          fields: [field({ key: 'a', visible: true }), field({ key: 'b', label: 'Hidden', visible: false })],
        })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );

    expect(screen.getByText('1 field')).not.toBeNull();
    expect(screen.queryByLabelText('Hidden')).toBeNull();
  });

  it('shows the empty-state message when a discriminator hides every field, never an empty card', () => {
    renderWithProviders(
      <SectionCard
        section={section({ fields: [field({ visible: false })] })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );

    expect(screen.getByText('No fields apply here with the current selections.')).not.toBeNull();
  });

  it('renders an Advanced chip only when every applicable field is advanced', () => {
    const { rerender } = renderWithProviders(
      <SectionCard
        section={section({ fields: [field({ advanced: true })] })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );
    expect(screen.getByText('Advanced')).not.toBeNull();
    // An all-advanced section renders its field directly — no extra toggle needed.
    expect(screen.queryByText(/Show \d/)).toBeNull();

    rerender(
      <SectionCard
        section={section({ fields: [field({ key: 'a' }), field({ key: 'b', label: 'B', advanced: true })] })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );
    expect(screen.queryByText('Advanced')).toBeNull();
  });

  it('keeps advanced fields collapsed by default, behind a "Show N advanced fields" toggle', () => {
    renderWithProviders(
      <SectionCard
        section={section({ fields: [field({ key: 'a' }), field({ key: 'b', label: 'B', advanced: true })] })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );

    expect(screen.getByLabelText('Namespace')).not.toBeNull();
    expect(screen.queryByLabelText('B')).toBeNull();
    expect(screen.getByText('Show 1 advanced field')).not.toBeNull();

    fireEvent.click(screen.getByText('Show 1 advanced field'));
    expect(screen.getByLabelText('B')).not.toBeNull();
    expect(screen.getByText('Hide 1 advanced field')).not.toBeNull();
  });

  it('shows a blocking chip when any applicable field has a bind-tier validation, warning chip otherwise', () => {
    const { rerender } = renderWithProviders(
      <SectionCard
        section={section({
          fields: [field({ validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'x' }] })],
        })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );
    expect(screen.getByText('1 warning')).not.toBeNull();

    rerender(
      <SectionCard
        section={section({
          fields: [
            field({
              key: 'a',
              validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'x' }],
            }),
            field({ key: 'b', label: 'B', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'y' }] }),
          ],
        })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );
    expect(screen.getByText('1 blocking issue')).not.toBeNull();
    expect(screen.queryByText(/warning/)).toBeNull();
  });

  it('excludes disabled fields from the block/warn counts', () => {
    renderWithProviders(
      <SectionCard
        section={section({
          fields: [
            field({ enabled: false, validations: [{ tier: 'bind', code: 'REQUIRED', message: 'y' }] }),
          ],
        })}
        values={{}}
        onFieldChange={() => {}}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );
    expect(screen.queryByText(/blocking issue/)).toBeNull();
  });

  it('fires onFieldChange with the field key when a field is edited', () => {
    const onFieldChange = vi.fn();
    renderWithProviders(
      <SectionCard
        section={section()}
        values={{ namespace: 'acme' }}
        onFieldChange={onFieldChange}
        expanded={true}
        onToggleExpanded={() => {}}
      />,
    );

    fireEvent.change(screen.getByLabelText('Namespace'), { target: { value: 'acme-2' } });
    expect(onFieldChange).toHaveBeenCalledWith('namespace', 'acme-2');
  });
});

describe('SectionCard reflow (task 108, FE-D29)', () => {
  it('a long title with both a block chip and an advanced chip does not force horizontal scroll at 320px', () => {
    setViewportWidth(reflowFloorPx);
    const longSection = section({
      title: 'DIRECTORY REPLICATION SETTINGS AND SCHEDULING',
      fields: [
        field({ key: 'a', advanced: true, validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
      ],
    });
    const { container } = renderWithProviders(
      <SectionCard
        section={longSection}
        values={{}}
        onFieldChange={() => {}}
        expanded={false}
        onToggleExpanded={() => {}}
      />,
    );

    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    expect(() => {
      assertReflow(root as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });
});
