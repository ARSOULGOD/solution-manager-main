import { describe, expect, it } from 'vitest';

import type { ViewStatus } from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { VersionPill } from './VersionPill';

function status(overrides: Partial<ViewStatus>): ViewStatus {
  return { health: 'healthy', versionState: 'ok', configState: 'in_sync', ...overrides };
}

describe('VersionPill', () => {
  it('renders OK distinctly from a fault state', () => {
    renderWithProviders(<VersionPill status={status({ versionState: 'ok' })} />);
    expect(screen.getByText('OK')).not.toBeNull();
  });

  it('renders drift with declared and observed versions', () => {
    renderWithProviders(
      <VersionPill
        status={status({
          versionState: 'drift',
          declaredVersion: '2.4.0',
          observedVersion: '2.3.0',
        })}
      />,
    );
    expect(screen.getByText('Drift')).not.toBeNull();
    expect(screen.getByText('decl 2.4.0 · obs 2.3.0')).not.toBeNull();
  });

  it('renders unknown as its own distinct state, never falling back to OK', () => {
    renderWithProviders(
      <VersionPill status={status({ versionState: 'unknown', declaredVersion: '9.9.0' })} />,
    );
    expect(screen.getByText('Unknown')).not.toBeNull();
    expect(screen.queryByText('OK')).toBeNull();
    expect(screen.getByText('decl 9.9.0')).not.toBeNull();
  });

  it('renders unverified without an observed version, and not as a fault', () => {
    renderWithProviders(
      <VersionPill status={status({ versionState: 'unverified', declaredVersion: '2.4.0' })} />,
    );
    expect(screen.getByText('Unverified')).not.toBeNull();
    expect(screen.getByText('decl 2.4.0')).not.toBeNull();
  });

  it('omits the mono caption entirely when no declared version is supplied', () => {
    const { container } = renderWithProviders(
      <VersionPill status={status({ versionState: 'ok' })} />,
    );
    expect(container.textContent).not.toContain('decl');
  });
});
