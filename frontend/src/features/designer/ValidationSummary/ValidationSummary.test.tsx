import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ValidationSummary } from './ValidationSummary';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'a',
    label: overrides.label ?? 'A',
    renderedPath: 'component.a',
    discriminator: false,
    controlKind: 'text',
    value: { kind: 'unset' },
    options: [],
    enabled: true,
    visible: true,
    masked: false,
    advanced: false,
    validations: [],
    ...overrides,
  };
}

function section(overrides: Partial<ViewSection> = {}): ViewSection {
  return { title: 'General', order: 0, fields: [field()], actions: [], ...overrides };
}

describe('ValidationSummary', () => {
  it('renders nothing when no field carries a validation', () => {
    const { container } = renderWithProviders(<ValidationSummary sections={[section()]} onJump={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('states a warning-only summary as recommendations to review, not a blocker', () => {
    renderWithProviders(
      <ValidationSummary
        sections={[
          section({ fields: [field({ validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'x' }] })] }),
        ]}
        onJump={() => {}}
      />,
    );
    expect(screen.getByText('1 recommendation to review')).not.toBeNull();
    expect(screen.getByRole('status')).not.toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('states a blocking summary distinctly, and adds a secondary line when warnings are also present', () => {
    renderWithProviders(
      <ValidationSummary
        sections={[
          section({
            fields: [
              field({ key: 'a', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
              field({ key: 'b', label: 'B', validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'y' }] }),
            ],
          }),
        ]}
        onJump={() => {}}
      />,
    );
    expect(screen.getByText('1 field must be fixed before saving')).not.toBeNull();
    expect(screen.getByText('1 recommendation also needs review')).not.toBeNull();
    expect(screen.getByRole('alert')).not.toBeNull();
  });

  it('renders one chip per offending field and calls onJump with its section index and key', () => {
    const onJump = vi.fn();
    renderWithProviders(
      <ValidationSummary
        sections={[
          section({ title: 'General', fields: [field({ key: 'a', visible: false })] }),
          section({
            title: 'Broker',
            fields: [field({ key: 'host', label: 'Host', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] })],
          }),
        ]}
        onJump={onJump}
      />,
    );

    fireEvent.click(screen.getByText('Host'));
    expect(onJump).toHaveBeenCalledWith(1, 'host');
  });

  it('excludes a hidden or disabled field from both the header count and the chips', () => {
    renderWithProviders(
      <ValidationSummary
        sections={[
          section({
            fields: [
              field({ key: 'a', visible: false, validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
              field({
                key: 'b',
                label: 'B',
                enabled: false,
                validations: [{ tier: 'bind', code: 'REQUIRED', message: 'y' }],
              }),
            ],
          }),
        ]}
        onJump={() => {}}
      />,
    );
    expect(screen.queryByText(/must be fixed/)).toBeNull();
  });
});
