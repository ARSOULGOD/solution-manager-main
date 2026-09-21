import { describe, expect, it } from 'vitest';

import type { AttentionItem } from '@shared/testdoubles/data/instances';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { AttentionFeed } from './AttentionFeed';

const ITEMS: readonly AttentionItem[] = [
  {
    id: 'att-1',
    severity: 'err',
    title: 'cache-stage-01 · replica not responding',
    detail: '1 of 2 replicas stopped answering health checks.',
    instanceId: 'cache-stage-01',
    href: '/solutions/cache/instances?focus=cache-stage-01',
  },
  {
    id: 'att-2',
    severity: 'warn',
    title: 'Version drift · cache-prod-02',
    detail: 'Deployed 2.4.1, declared 2.3.0.',
    instanceId: 'cache-prod-02',
    href: '/solutions/cache/instances?focus=cache-prod-02',
  },
];

describe('AttentionFeed', () => {
  it('renders one deep-linked row per item, newest first, with severity and detail', () => {
    renderWithProviders(<AttentionFeed items={ITEMS} />);

    expect(screen.getByText('Needs attention')).not.toBeNull();
    expect(screen.getByText('2 items · newest first')).not.toBeNull();

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0]?.getAttribute('href')).toBe('/solutions/cache/instances?focus=cache-stage-01');
    expect(links[1]?.getAttribute('href')).toBe('/solutions/cache/instances?focus=cache-prod-02');
    expect(screen.getByText('cache-stage-01 · replica not responding')).not.toBeNull();
    expect(screen.getByText('1 of 2 replicas stopped answering health checks.')).not.toBeNull();
  });

  it('renders an explicit "Nothing needs attention" notice for an empty feed — never a blank panel', () => {
    renderWithProviders(<AttentionFeed items={[]} />);

    expect(screen.getByText('Nothing needs attention.')).not.toBeNull();
    expect(
      screen.getByText('All declared instances are healthy, applied and on a known version.'),
    ).not.toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });
});
