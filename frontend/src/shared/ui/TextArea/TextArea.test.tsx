import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { TextArea } from './TextArea';

describe('TextArea', () => {
  it('renders a multiline field with its label and value', () => {
    renderWithProviders(<TextArea label="Notes" value="First line" onChange={() => {}} />);

    const field = screen.getByLabelText<HTMLTextAreaElement>('Notes');
    expect(field.tagName.toLowerCase()).toBe('textarea');
    expect(field.value).toBe('First line');
  });

  it('fires onChange when the value changes', () => {
    const onChange = vi.fn();
    renderWithProviders(<TextArea label="Notes" value="" onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'x' } });

    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('swaps helper text for the error message', () => {
    renderWithProviders(
      <TextArea
        label="Notes"
        value=""
        onChange={() => {}}
        helperText="Optional context."
        error="Notes are required for this action."
      />,
    );

    const field = screen.getByLabelText<HTMLTextAreaElement>('Notes');
    expect(field.getAttribute('aria-invalid')).toBe('true');
    const describedBy = field.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Notes are required for this action.',
    );
  });
});
