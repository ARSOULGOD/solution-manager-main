import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { AttentionFeed } from './AttentionFeed';
import type { AttentionItem } from './homeRepository';

const ITEMS: readonly AttentionItem[] = [
  {
    id: 'cache-stage-01-health',
    severity: 'err',
    title: 'cache-stage-01 · Health',
    detail: 'State: red.',
    instanceId: 'cache-stage-01',
    href: '/solutions/cache/instances/cache-stage-01/governance',
  },
  {
    id: 'cache-qa-01-config-diverged',
    severity: 'err',
    title: 'cache-qa-01 · Configuration diverged',
    detail: 'Configuration diverged',
    instanceId: 'cache-qa-01',
    href: '/solutions/cache/instances/cache-qa-01/governance',
  },
];

describe('AttentionFeed', () => {
  it('renders one deep-linked row per item, newest first, with severity and detail', () => {
    renderWithProviders(<AttentionFeed items={ITEMS} />);

    expect(screen.getByText('Needs attention')).not.toBeNull();
    expect(screen.getByText('2 items · newest first')).not.toBeNull();

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0]?.getAttribute('href')).toBe('/solutions/cache/instances/cache-stage-01/governance');
    expect(links[1]?.getAttribute('href')).toBe('/solutions/cache/instances/cache-qa-01/governance');
    expect(screen.getByText('cache-stage-01 · Health')).not.toBeNull();
    expect(screen.getByText('State: red.')).not.toBeNull();
  });

  it('renders an explicit "Nothing needs attention" notice for an empty feed — never a blank panel', () => {
    renderWithProviders(<AttentionFeed items={[]} />);

    expect(screen.getByText('Nothing needs attention.')).not.toBeNull();
    expect(
      screen.getByText('All declared instances are healthy, applied and on a known version.'),
    ).not.toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a non-navigable row — never a wrong link — when an item has no href', () => {
    const unresolved: readonly AttentionItem[] = [
      {
        id: 'inst-missing-apply-indeterminate',
        severity: 'warn',
        title: 'inst-missing · Apply status indeterminate',
        detail: 'Apply status indeterminate',
        instanceId: 'inst-missing',
      },
    ];

    renderWithProviders(<AttentionFeed items={unresolved} />);

    expect(screen.getByText('inst-missing · Apply status indeterminate')).not.toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });
});
