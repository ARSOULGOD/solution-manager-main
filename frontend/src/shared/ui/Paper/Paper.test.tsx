import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Paper } from './Paper';

describe('Paper', () => {
  it.each(['flat', 'padded', 'hoverable'] as const)('renders the %s surface', (surface) => {
    renderWithProviders(
      <Paper surface={surface}>
        <p>Body</p>
      </Paper>,
    );
    expect(screen.getByText('Body')).not.toBeNull();
    expect(document.querySelector(`[data-surface="${surface}"]`)).not.toBeNull();
  });

  // Task 129 — both default off, so no existing caller changes shape.
  it('is neither filled nor dashed by default', () => {
    renderWithProviders(<Paper>Body</Paper>);

    expect(document.querySelector('[data-fill]')).toBeNull();
    expect(document.querySelector('[data-dashed]')).toBeNull();
  });

  it('stretches to its container and stacks children when filled', () => {
    renderWithProviders(<Paper fill>Body</Paper>);

    const surface = document.querySelector('[data-fill]');
    expect(surface).not.toBeNull();
    const style = surface === null ? null : getComputedStyle(surface);
    expect(style?.height).toBe('100%');
    expect(style?.display).toBe('flex');
    expect(style?.flexDirection).toBe('column');
  });

  it('draws a dashed border when asked, matching the reference placeholder card', () => {
    renderWithProviders(<Paper dashed>Body</Paper>);

    const surface = document.querySelector('[data-dashed]');
    expect(surface).not.toBeNull();
    expect(surface === null ? null : getComputedStyle(surface).borderStyle).toBe('dashed');
  });
});
