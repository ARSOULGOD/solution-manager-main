import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { Instance } from '@shared/api/contracts/instances';
import type { HierarchyRepository } from '@shared/hierarchy';
import type { Env } from '@shared/testdoubles/data/instances';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import type { RemoteServerConnectionRow } from './connectionPickerRepository';
import { DeclareInstanceDialog } from './DeclareInstanceDialog';

const modes = ['light', 'dark'] as const;

const HIERARCHY: HierarchyRepository = {
  organizations: () => Promise.resolve(['Acme Financial']),
  subOrgs: () => Promise.resolve(['Payments']),
  environments: () => Promise.resolve(['PROD'] as Env[]),
  environmentId: () => Promise.resolve('env-1'),
  environmentPath: () =>
    Promise.resolve({ org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' }),
};

const CONNECTIONS: readonly RemoteServerConnectionRow[] = [
  { connectionId: 'c1', name: 'build-host', connectionType: 'remote-server', attributes: {}, active: true },
];

const EXISTING: readonly Instance[] = [
  {
    stateVersion: 1,
    instanceId: 'cache-prod-01',
    name: 'cache-prod-01',
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [],
  },
];

function renderDialog(mode: (typeof modes)[number]) {
  return renderWithProviders(
    <DeclareInstanceDialog
      open
      solutionId="cache"
      existing={EXISTING}
      packageVersions={[6, 7]}
      hierarchyRepository={HIERARCHY}
      connectionsRepository={{ listRemoteServers: () => Promise.resolve([...CONNECTIONS]) }}
      onClose={() => undefined}
      onSubmit={() => undefined}
    />,
    { mode },
  );
}

describe('DeclareInstanceDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderDialog(mode);
    expect(await axe(container)).toHaveNoViolations();
  });

  // The invalid state is its own a11y surface: MUI wires `aria-invalid`
  // and `aria-describedby` from the error text, and a form that only
  // passes while pristine has not been checked where it matters.
  it('has no violations while showing validation errors', async () => {
    const { container } = renderDialog('light');

    fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

    expect(await axe(container)).toHaveNoViolations();
  });
});
