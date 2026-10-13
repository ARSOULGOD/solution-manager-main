import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ConnectionDriftBanner } from './ConnectionDriftBanner';

const ENTRY = {
  connectionId: 'conn-7',
  name: 'prod-broker-01',
  appliedRevision: 3,
  currentRevision: 4,
  affectedFields: ['solace.host', 'solace.vpn'],
} as const;

describe('ConnectionDriftBanner', () => {
  it('names the connection, fields, and one remediation', () => {
    renderWithProviders(
      <ConnectionDriftBanner
        entries={[ENTRY]}
        fieldLabel={(key) => ({ 'solace.host': 'Host', 'solace.vpn': 'Message VPN' })[key] ?? key}
      />,
    );
    expect(screen.getAllByText(/prod-broker-01 changed/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Host, Message VPN/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review and save' })).toBeInTheDocument();
  });

  it('offers no rollout remediation', () => {
    renderWithProviders(<ConnectionDriftBanner entries={[ENTRY]} />);
    for (const name of ['Restart', 'Roll back', 'Re-stage']) {
      expect(screen.queryByRole('button', { name: new RegExp(name, 'i') })).not.toBeInTheDocument();
    }
  });

  it('calls the bare review callback', () => {
    const onReview = vi.fn();
    renderWithProviders(<ConnectionDriftBanner entries={[ENTRY]} onReviewAndSave={onReview} />);
    screen.getByRole('button', { name: 'Review and save' }).click();
    expect(onReview).toHaveBeenCalledWith(ENTRY);
  });

  it('renders nothing when there is no drift', () => {
    const { container } = renderWithProviders(<ConnectionDriftBanner entries={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
