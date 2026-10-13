import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import { allVocabEntries } from '../../vocab';

import { StateChip } from './StateChip';

const modes = ['light', 'dark'] as const;

describe('StateChip a11y', () => {
  it.each(modes)('has no violations in %s mode across every vocabulary entry', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        {allVocabEntries.map((entry, index) => (
          <StateChip key={`${entry.key}-${index}`} entry={entry} />
        ))}
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
