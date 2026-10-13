import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Chip } from './Chip';
import type { ChipTone } from './Chip.types';

const tones: ChipTone[] = ['ok', 'warn', 'err', 'info', 'idle', 'neutral', 'purple'];
const modes = ['light', 'dark'] as const;

describe('Chip a11y', () => {
  it.each(modes)('has no violations in %s mode across every tone', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        {tones.map((tone) => (
          <Chip key={tone} label={tone} tone={tone} dot />
        ))}
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
