import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Skeleton } from './Skeleton';

describe('Skeleton', () => {
  it('occupies the replacement content dimensions so layout does not shift', () => {
    const { container, rerender } = renderWithProviders(<Skeleton width={240} height={48} />);
    const skeleton = container.querySelector('[data-skeleton]');
    expect(skeleton).toBeInstanceOf(HTMLElement);
    if (!(skeleton instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(skeleton).width).toBe('240px');
    expect(getComputedStyle(skeleton).height).toBe('48px');

    rerender(
      <div data-loaded style={{ width: 240, height: 48 }}>
        Loaded
      </div>,
    );
    expect(screen.getByText('Loaded')).not.toBeNull();
  });
});
