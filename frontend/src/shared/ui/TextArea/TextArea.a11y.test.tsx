import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { TextArea } from './TextArea';

const modes = ['light', 'dark'] as const;

describe('TextArea a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <TextArea label="Notes" value="Some notes." onChange={() => {}} />
        <TextArea label="Required" value="" onChange={() => {}} required />
        <TextArea label="Notes" value="" onChange={() => {}} error="Notes are required." />
        <TextArea label="Disabled" value="" onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
