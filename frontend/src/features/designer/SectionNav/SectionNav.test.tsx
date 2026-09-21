import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewField, ViewSection } from '@contracts/view-model/types';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';

import { SectionNav } from './SectionNav';

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

describe('SectionNav', () => {
  it('renders one entry per section, in order, and calls onJump with the section index', () => {
    const onJump = vi.fn();
    renderWithProviders(
      <SectionNav
        sections={[section({ title: 'General' }), section({ title: 'Broker' })]}
        onJump={onJump}
      />,
    );

    fireEvent.click(screen.getByText('Broker'));
    expect(onJump).toHaveBeenCalledWith(1);
  });

  it('shows the applicable field count when there is nothing to flag', () => {
    renderWithProviders(
      <SectionNav
        sections={[section({ fields: [field({ key: 'a' }), field({ key: 'b', visible: false })] })]}
        onJump={() => {}}
      />,
    );
    // Hidden field excluded from the count (applicable = visible only).
    expect(screen.getByText('1')).not.toBeNull();
  });

  it('flags a blocking validation over a warning one when a section has both', () => {
    renderWithProviders(
      <SectionNav
        sections={[
          section({
            fields: [
              field({ key: 'a', validations: [{ tier: 'semantic', code: 'ABOVE_RECOMMENDED', message: 'x' }] }),
              field({ key: 'b', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'y' }] }),
            ],
          }),
        ]}
        onJump={() => {}}
      />,
    );

    const entry = screen.getByRole('button', { name: /General/ });
    expect(entry.getAttribute('aria-label')).toBe('General — 1 blocking issue');
  });

  it('carries a full accessible label including the section title and status', () => {
    renderWithProviders(
      <SectionNav sections={[section({ title: 'Sizing', fields: [field(), field({ key: 'b' })] })]} onJump={() => {}} />,
    );
    expect(screen.getByRole('button', { name: 'Sizing — 2 fields' })).not.toBeNull();
  });
});

describe('SectionNav rail-vs-drawer (task 108, FE-D29)', () => {
  it('renders the persistent rail directly at lg and up — no trigger button', () => {
    setViewportWidth(1240);
    renderWithProviders(
      <SectionNav sections={[section({ title: 'General' })]} onJump={() => {}} />,
    );

    expect(screen.getByRole('navigation', { name: 'Sections' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Jump to section' })).toBeNull();
  });

  it('below lg, the rail is not rendered until the drawer is opened, and the dots survive the collapse', () => {
    setViewportWidth(905);
    renderWithProviders(
      <SectionNav
        sections={[
          section({
            title: 'Broker',
            fields: [field({ key: 'a', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] })],
          }),
        ]}
        onJump={() => {}}
      />,
    );

    expect(screen.queryByRole('navigation', { name: 'Sections' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Jump to section' }));

    expect(screen.getByRole('navigation', { name: 'Sections' })).not.toBeNull();
    const entry = screen.getByRole('button', { name: /Broker/ });
    expect(entry.getAttribute('aria-label')).toBe('Broker — 1 blocking issue');
  });

  it('selecting a section from the drawer calls onJump and closes the drawer', async () => {
    setViewportWidth(905);
    const onJump = vi.fn();
    renderWithProviders(
      <SectionNav
        sections={[section({ title: 'General' }), section({ title: 'Broker' })]}
        onJump={onJump}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Jump to section' }));
    fireEvent.click(screen.getByText('Broker'));

    expect(onJump).toHaveBeenCalledWith(1);
    await waitFor(() => {
      expect(screen.queryByRole('navigation', { name: 'Sections' })).toBeNull();
    });
  });
});
