import { describe, expect, it } from 'vitest';

import { Button } from '@shared/ui/Button';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { Can } from './Can';

// Each test supplies an explicit identity. The development administrator now
// holds the whole initial D10 catalog, so relying on its defaults would no
// longer exercise omission for a deliberately ungranted operation.
//
// Identity resolution is async (a resolved Promise), so every test renders
// a KNOWN-granted control alongside the one under test and waits for that
// one to appear — proving resolution has actually happened before asserting
// on the other, rather than racing the still-loading state.

describe('Can — omit mode', () => {
  it('omits the control entirely when the permission is not granted', async () => {
    renderWithProviders(
      <div>
        <Can id="target.use">
          <Button>Sentinel</Button>
        </Can>
        <Can id="metrics.query">
          <Button>Force raw</Button>
        </Can>
      </div>,
      { permissions: ['target.use'] },
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Sentinel' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Force raw' })).not.toBeInTheDocument();
  });

  it('renders the control once the permission IS granted', async () => {
    renderWithProviders(
      <Can id="target.use">
        <Button>Use target</Button>
      </Can>,
      { permissions: ['target.use'] },
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Use target' })).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Use target' })).not.toBeDisabled();
  });
});

describe('Can — disable-with-reason mode', () => {
  it('renders the control DISABLED with the reason when granted but capability-blocked', async () => {
    renderWithProviders(
      <Can id="target.use" disabledReason="No target of this kind is configured yet.">
        <Button>Use target</Button>
      </Can>,
      { permissions: ['target.use'] },
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Use target' })).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Use target' })).toBeDisabled();
  });

  it('never renders disable-with-reason for an UNGRANTED permission — omit wins', async () => {
    renderWithProviders(
      <div>
        <Can id="target.use">
          <Button>Sentinel</Button>
        </Can>
        <Can id="metrics.query" disabledReason="would never be seen">
          <Button>Force raw</Button>
        </Can>
      </div>,
      { permissions: ['target.use'] },
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Sentinel' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Force raw' })).not.toBeInTheDocument();
  });
});
