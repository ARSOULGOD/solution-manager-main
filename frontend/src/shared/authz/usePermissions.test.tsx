import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { usePermissions } from './usePermissions';

function Probe() {
  const permissions = usePermissions();
  return (
    <ul>
      <li data-testid="target-use">{permissions.gate('target.use')}</li>
      <li data-testid="metrics-query">{permissions.gate('metrics.query')}</li>
      <li data-testid="target-use-status">{permissions.status('target.use')}</li>
      <li data-testid="metrics-query-status">{permissions.status('metrics.query')}</li>
    </ul>
  );
}

describe('usePermissions', () => {
  it("reflects the resolved identity's granted ids once resolution settles", async () => {
    renderWithProviders(<Probe />, { permissions: ['target.use'] });

    await waitFor(() => expect(screen.getByTestId('target-use').textContent).toBe('render'));
    expect(screen.getByTestId('metrics-query').textContent).toBe('omit');
  });

  it('status() is independent of what is granted — it is the catalog, not the identity', async () => {
    renderWithProviders(<Probe />);

    await waitFor(() =>
      expect(screen.getByTestId('target-use-status').textContent).toBe('grantable'),
    );
    expect(screen.getByTestId('metrics-query-status').textContent).toBe('grantable');
  });
});
