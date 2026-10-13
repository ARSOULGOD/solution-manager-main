import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AcceptedRuntimeConfig, ConfigRepository } from '../configRepository';

import { AcceptRuntimeDialog } from './AcceptRuntimeDialog';

function stubRepository(acceptRuntime: ConfigRepository['acceptRuntime']): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used in these tests')),
    save: () => Promise.reject(new Error('not used in these tests')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in these tests')),
    acceptRuntime,
    restoreRuntime: () => Promise.reject(new Error('not used in these tests')),
  };
}

const ACCEPTED_EVERYTHING: AcceptedRuntimeConfig = {
  stateVersion: 5,
  generation: 5,
  unchanged: false,
  placementId: 'cache-prod-01-placement-0',
  fileBytesDifferFromRendered: false,
  truncated: false,
  notAccepted: [],
};

const ACCEPTED_WITH_GAPS: AcceptedRuntimeConfig = {
  ...ACCEPTED_EVERYTHING,
  notAccepted: [
    { path: 'cache-engine.solace.password', reason: 'secret-not-read', comparison: 'differs' },
    { path: 'cache-engine.notARealKey', reason: 'undeclared-key' },
  ],
};

describe('AcceptRuntimeDialog', () => {
  it('becomes a full-screen sheet below sm (task 108, FE-D29)', () => {
    setViewportWidth(320);
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    expect(document.querySelector('.MuiDialog-paperFullScreen')).not.toBeNull();
  });

  it('confirm is disabled with no placementId', () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId={undefined}
        baseStateVersion={4}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    expect(
      screen.getByRole('button', { name: 'Accept the runtime' }).hasAttribute('disabled'),
    ).toBe(true);
  });

  it('confirming sends {baseStateVersion, placementId} and fires onAccepted', async () => {
    const acceptRuntime = vi.fn<ConfigRepository['acceptRuntime']>(() =>
      Promise.resolve(ACCEPTED_EVERYTHING),
    );
    const onAccepted = vi.fn();

    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(acceptRuntime)}
        onClose={() => undefined}
        onAccepted={onAccepted}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime' }));

    await waitFor(() => {
      expect(acceptRuntime).toHaveBeenCalledWith('cache-prod-01', {
        baseStateVersion: 4,
        placementId: 'cache-prod-01-placement-0',
      });
    });
    await waitFor(() => {
      expect(onAccepted).toHaveBeenCalledWith(ACCEPTED_EVERYTHING);
    });
  });

  it('renders every notAccepted field by its closed reason, never a value', async () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() => Promise.resolve(ACCEPTED_WITH_GAPS))}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime' }));

    expect(
      await screen.findByText('cache-engine.solace.password — a secret value, never read back'),
    ).not.toBeNull();
    expect(
      screen.getByText('cache-engine.notARealKey — not a key this package declares'),
    ).not.toBeNull();
    expect(document.body.textContent).not.toContain('differs');
  });

  it('an empty notAccepted[] reports everything was accepted', async () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() => Promise.resolve(ACCEPTED_EVERYTHING))}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime' }));

    expect(await screen.findByText('Every mappable field on the host was accepted.')).not.toBeNull();
  });

  it('unchanged renders the honest "nothing differs" message', async () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() =>
          Promise.resolve({ ...ACCEPTED_EVERYTHING, unchanged: true }),
        )}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime' }));

    expect(
      await screen.findByText('Nothing on the host differs from the current declaration.'),
    ).not.toBeNull();
  });

  it('a failed accept renders an inline error and does not fire onAccepted', async () => {
    const onAccepted = vi.fn();
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        onClose={() => undefined}
        onAccepted={onAccepted}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Accept the runtime' }));

    expect(
      await screen.findByText('The platform could not accept the runtime configuration. Try again.'),
    ).not.toBeNull();
    expect(onAccepted).not.toHaveBeenCalled();
  });

  it('cancel fires onClose, never acceptRuntime', () => {
    const onClose = vi.fn();
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        baseStateVersion={4}
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onClose={onClose}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
