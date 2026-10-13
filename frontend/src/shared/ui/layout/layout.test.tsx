import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import { assertReflow } from '../../../test/reflow';
import { renderWithProviders } from '../../../test/renderWithProviders';

import { Cluster } from './Cluster';
import { Grid } from './Grid';
import { PageWidth } from './PageWidth';
import { Sidebar } from './Sidebar';
import { Stack } from './Stack';

const WIDTHS = [320, 600, 905, 1240, 1560] as const;

describe('layout primitives', () => {
  it('Stack is a column flex at every asserted width', () => {
    const { container } = renderWithProviders(
      <Stack>
        <span>one</span>
        <span>two</span>
      </Stack>,
    );
    const root = container.querySelector('[data-layout="stack"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(root).flexDirection).toBe('column');
    for (const width of WIDTHS) {
      expect(() => {
        assertReflow(root, width);
      }).not.toThrow();
    }
  });

  it('Cluster wraps rather than overflowing at the reflow floor', () => {
    const { container } = renderWithProviders(
      <Cluster>
        <button type="button">Save</button>
        <button type="button">Apply</button>
        <button type="button">Restart</button>
        <button type="button">Export</button>
      </Cluster>,
    );
    const root = container.querySelector('[data-layout="cluster"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(root).flexWrap).toBe('wrap');
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });

  it('Grid is a container-query grid and does not overflow at each width', () => {
    const { container } = renderWithProviders(
      <Grid>
        <div>A</div>
        <div>B</div>
        <div>C</div>
        <div>D</div>
      </Grid>,
    );
    const root = container.querySelector('[data-layout="grid"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(root).containerType).toBe('inline-size');
    for (const width of WIDTHS) {
      expect(() => {
        assertReflow(root, width);
      }).not.toThrow();
    }
  });

  it('Sidebar is rail-plus-content and does not overflow at each width', () => {
    const { container } = renderWithProviders(
      <Sidebar rail={<nav>Nav</nav>}>
        <p>Main</p>
      </Sidebar>,
    );
    const root = container.querySelector('[data-layout="sidebar"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(root.querySelector('[data-layout="sidebar-rail"]')).not.toBeNull();
    expect(root.querySelector('[data-layout="sidebar-content"]')).not.toBeNull();
    expect(getComputedStyle(root).containerType).toBe('inline-size');
    for (const width of WIDTHS) {
      expect(() => {
        assertReflow(root, width);
      }).not.toThrow();
    }
  });

  it('PageWidth is a size prop (default vs wide), not a copied class', () => {
    const { rerender, container } = renderWithProviders(
      <PageWidth>
        <p>Body</p>
      </PageWidth>,
    );
    const root = container.querySelector('[data-layout="page-width"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(root.getAttribute('data-page-width')).toBe('default');
    expect(getComputedStyle(root).maxWidth).toBe('1240px');

    rerender(
      <PageWidth size="wide">
        <p>Body</p>
      </PageWidth>,
    );
    const wide = container.querySelector('[data-layout="page-width"]');
    expect(wide).toBeInstanceOf(HTMLElement);
    if (!(wide instanceof HTMLElement)) {
      return;
    }
    expect(wide.getAttribute('data-page-width')).toBe('wide');
    expect(getComputedStyle(wide).maxWidth).toBe('1560px');
    expect(() => {
      assertReflow(wide, reflowFloorPx);
    }).not.toThrow();
  });
});
