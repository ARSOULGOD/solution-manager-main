import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { ScopeNode } from '@shared/testdoubles/data/rbac';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ScopeTree } from './ScopeTree';

const TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [
    {
      name: 'Acme Financial',
      level: 'Organization',
      children: [
        {
          name: 'Payments',
          level: 'Sub-organization',
          children: [{ name: 'PROD', level: 'Environment' }],
        },
      ],
    },
    { name: 'Globex', level: 'Organization' },
  ],
};

describe('ScopeTree', () => {
  it('renders every node in the tree', () => {
    renderWithProviders(<ScopeTree node={TREE} selected={[]} onSelect={vi.fn()} />);

    for (const name of ['All organizations', 'Acme Financial', 'Payments', 'PROD', 'Globex']) {
      expect(screen.getByRole('button', { name })).not.toBeNull();
    }
  });

  it('selecting a node reports its full path from the root', async () => {
    const onSelect = vi.fn();
    renderWithProviders(<ScopeTree node={TREE} selected={[]} onSelect={onSelect} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'PROD' }));

    // The ROOT contributes no segment (`FE-D46`) — the path starts at the
    // organization.
    expect(onSelect).toHaveBeenCalledWith(['Acme Financial', 'Payments', 'PROD']);
  });

  it('marks the selected node as pressed and no other', () => {
    renderWithProviders(<ScopeTree node={TREE} selected={['Acme Financial']} onSelect={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Acme Financial' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(screen.getByRole('button', { name: 'Globex' }).getAttribute('aria-pressed')).toBe(
      'false',
    );
  });

  it('collapsing a branch hides its descendants', async () => {
    renderWithProviders(<ScopeTree node={TREE} selected={[]} onSelect={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Payments' })).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Collapse Acme Financial' }));

    expect(screen.queryByRole('button', { name: 'Payments' })).toBeNull();
  });
});
