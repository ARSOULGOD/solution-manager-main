import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ViewStatus } from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { BlockedBanner } from './BlockedBanner';

function status(overrides: Partial<ViewStatus>): ViewStatus {
  return { health: 'healthy', versionState: 'drift', configState: 'in_sync', ...overrides };
}

describe('BlockedBanner', () => {
  it('renders nothing for ok', () => {
    const { container } = renderWithProviders(
      <BlockedBanner
        status={status({ versionState: 'ok' })}
        onAccept={() => undefined}
        onRestore={() => undefined}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing for unverified — it must not be styled as a fault (UI-D12)', () => {
    const { container } = renderWithProviders(
      <BlockedBanner
        status={status({ versionState: 'unverified' })}
        onAccept={() => undefined}
        onRestore={() => undefined}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('titles and reasons drift correctly, with both declared and observed', () => {
    renderWithProviders(
      <BlockedBanner
        status={status({
          versionState: 'drift',
          declaredVersion: '2.4.0',
          observedVersion: '2.3.0',
        })}
        onAccept={() => undefined}
        onRestore={() => undefined}
      />,
    );

    expect(screen.getByText('Editing blocked · version drift')).not.toBeNull();
    expect(screen.getByText(/Declared 2\.4\.0 but observed 2\.3\.0/)).not.toBeNull();
    expect(screen.getByText(/read-only, not unavailable/)).not.toBeNull();
  });

  it('titles and reasons unknown correctly, distinct from drift', () => {
    renderWithProviders(
      <BlockedBanner
        status={status({ versionState: 'unknown', declaredVersion: '9.9.0' })}
        onAccept={() => undefined}
        onRestore={() => undefined}
      />,
    );

    expect(screen.getByText('Editing blocked · version unknown')).not.toBeNull();
    expect(screen.getByText(/nothing observed corroborates it/)).not.toBeNull();
  });

  it('fires onAccept and onRestore from their own buttons', () => {
    const onAccept = vi.fn();
    const onRestore = vi.fn();
    renderWithProviders(
      <BlockedBanner
        status={status({
          versionState: 'drift',
          declaredVersion: '2.4.0',
          observedVersion: '2.3.0',
        })}
        onAccept={onAccept}
        onRestore={onRestore}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime…' }));
    fireEvent.click(screen.getByRole('button', { name: 'Restore the runtime…' }));

    expect(onAccept).toHaveBeenCalledTimes(1);
    expect(onRestore).toHaveBeenCalledTimes(1);
  });

  it('does not hide sibling content — it is a banner alongside the config, not a replacement for it', () => {
    renderWithProviders(
      <>
        <BlockedBanner
          status={status({
            versionState: 'drift',
            declaredVersion: '2.4.0',
            observedVersion: '2.3.0',
          })}
          onAccept={() => undefined}
          onRestore={() => undefined}
        />
        <div data-testid="config-below">the config stays fully legible here</div>
      </>,
    );

    expect(screen.getByText('Editing blocked · version drift')).not.toBeNull();
    expect(screen.getByTestId('config-below')).not.toBeNull();
  });
});
