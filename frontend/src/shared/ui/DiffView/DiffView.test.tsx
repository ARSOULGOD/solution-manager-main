import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { DiffView } from './DiffView';
import { SECRET_MASK, type DiffRow } from './DiffView.types';

describe('DiffView', () => {
  it('renders configurable column labels and rename mapping copy', () => {
    renderWithProviders(
      <DiffView
        labels={['Your draft', 'Current record']}
        rows={[
          { path: 'cache.ttl', kind: 'change', old: '60s', new: '120s' },
          { path: 'cache.host', kind: 'rename', old: 'hostname', new: 'host' },
        ]}
      />,
    );
    expect(screen.getByText('Your draft')).not.toBeNull();
    expect(screen.getByText('Current record')).not.toBeNull();
    expect(screen.getByText(/needs mapping/)).not.toBeNull();
  });

  it('renders a secret row masked on both sides regardless of a cast value', () => {
    const secretRow = {
      path: 'broker.password',
      kind: 'change',
      secret: true,
      old: SECRET_MASK,
      new: SECRET_MASK,
    } satisfies DiffRow;

    const { container } = renderWithProviders(<DiffView rows={[secretRow]} />);
    expect(screen.getByText('secret · masked')).not.toBeNull();
    const sides = container.querySelectorAll('[data-diff-side]');
    expect(sides).toHaveLength(2);
    expect(sides[0]?.textContent).toBe('••••••••');
    expect(sides[1]?.textContent).toBe('••••••••');
    expect(container.textContent).not.toContain('hunter2');
  });
});

describe('DiffView stacked mode (task 108, FE-D29)', () => {
  it('below sm, renders a card per row with each value labelled by its own side, not a table', () => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <DiffView
        labels={['Your draft', 'Current record']}
        rows={[{ path: 'cache.ttl', kind: 'change', old: '60s', new: '120s' }]}
      />,
    );

    expect(container.querySelector('table')).toBeNull();
    expect(container.querySelector('[data-diff-stacked]')).not.toBeNull();
    expect(screen.getByText('Your draft')).not.toBeNull();
    expect(screen.getByText('Current record')).not.toBeNull();
    expect(screen.getByText('60s')).not.toBeNull();
    expect(screen.getByText('120s')).not.toBeNull();
  });

  it('a stacked secret row still masks both sides — G7 does not relax with the viewport', () => {
    setViewportWidth(320);
    const secretRow = {
      path: 'broker.password',
      kind: 'change',
      secret: true,
      old: SECRET_MASK,
      new: SECRET_MASK,
    } satisfies DiffRow;

    const { container } = renderWithProviders(<DiffView rows={[secretRow]} />);
    expect(screen.getByText('secret · masked')).not.toBeNull();
    const sides = container.querySelectorAll('[data-diff-side]');
    expect(sides).toHaveLength(2);
    expect(sides[0]?.textContent).toBe('••••••••');
    expect(sides[1]?.textContent).toBe('••••••••');
    expect(container.textContent).not.toContain('hunter2');

    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    expect(() => {
      assertReflow(root as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });
});
