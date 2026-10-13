import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';
import { PageWidth } from '@shared/ui/layout';

import { assertReflow } from '../../test/reflow';
import { renderWithProviders, screen } from '../../test/renderWithProviders';

import type { HomeSummary } from './homeRepository';
import { StatStrip } from './StatStrip';

const BASE_SUMMARY: HomeSummary = {
  solutionsManaged: 2,
  instancesDeclared: 12,
  needAttentionNow: 3,
  configStagedNotApplied: 1,
};

describe('StatStrip', () => {
  it('renders all four tiles with their values and labels', () => {
    renderWithProviders(<StatStrip summary={BASE_SUMMARY} />);

    expect(screen.getByText('2')).not.toBeNull();
    expect(screen.getByText('Solutions managed')).not.toBeNull();
    expect(screen.getByText('12')).not.toBeNull();
    expect(screen.getByText('Instances declared')).not.toBeNull();
    expect(screen.getByText('3')).not.toBeNull();
    expect(screen.getByText('Need attention now')).not.toBeNull();
    expect(screen.getByText('1')).not.toBeNull();
    expect(screen.getByText('Config staged · not applied')).not.toBeNull();
  });

  it('renders an unknown count as "—", never "0"', () => {
    renderWithProviders(
      <StatStrip
        summary={{
          solutionsManaged: null,
          instancesDeclared: null,
          needAttentionNow: null,
          configStagedNotApplied: null,
        }}
      />,
    );

    expect(screen.queryByText('0')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(4);
  });

  it('links the Solutions, Instances and Config-staged tiles, but not "Need attention now"', () => {
    renderWithProviders(<StatStrip summary={BASE_SUMMARY} />);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(3);
    expect(screen.getByText('Need attention now').closest('a')).toBeNull();
  });

  it('renders the instances count with a "+1?" suffix when the summary is partial', () => {
    renderWithProviders(
      <StatStrip summary={{ ...BASE_SUMMARY, partial: { unreportingHost: 'cache-edge-01' } }} />,
    );

    expect(screen.getByText('12 +1?')).not.toBeNull();
  });

  it('does not force horizontal page-body scroll at 320px', () => {
    const { container } = renderWithProviders(
      <PageWidth>
        <StatStrip summary={BASE_SUMMARY} />
      </PageWidth>,
    );

    const root = container.querySelector('[data-layout="page-width"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });
});
