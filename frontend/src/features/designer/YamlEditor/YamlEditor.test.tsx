import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { YamlEditor } from './YamlEditor';

describe('YamlEditor', () => {
  it('supports direct editing, copy, paste, and visible validation feedback', () => {
    const onChange = vi.fn();
    const onCopy = vi.fn();
    const onPaste = vi.fn();

    renderWithProviders(
      <YamlEditor
        value={'solace:\n  host: broker.example'}
        error="Field 'solace.port' has the wrong YAML type for this control."
        omittedSecrets={1}
        onChange={onChange}
        onCopy={onCopy}
        onPaste={onPaste}
      />,
    );

    const editor = screen.getByRole('textbox', { name: 'YAML configuration editor' });
    fireEvent.change(editor, { target: { value: 'solace:\n  host: changed.example' } });
    fireEvent.click(screen.getByRole('button', { name: 'Copy YAML' }));
    fireEvent.click(screen.getByRole('button', { name: 'Paste YAML' }));

    expect(onChange).toHaveBeenCalledWith('solace:\n  host: changed.example');
    expect(onCopy).toHaveBeenCalledOnce();
    expect(onPaste).toHaveBeenCalledOnce();
    expect(screen.getByText('Invalid YAML configuration')).not.toBeNull();
    expect(screen.getByText('Sensitive fields omitted')).not.toBeNull();
  });
});
