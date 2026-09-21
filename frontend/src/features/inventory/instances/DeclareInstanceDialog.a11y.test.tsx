import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { HierarchyRepository } from '@shared/hierarchy';
import { CACHE_INSTANCES, type Env } from '@shared/testdoubles/data/instances';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { DeclareInstanceDialog } from './DeclareInstanceDialog';

const modes = ['light', 'dark'] as const;

const HIERARCHY: HierarchyRepository = {
  organizations: () => Promise.resolve(['Acme Financial']),
  subOrgs: () => Promise.resolve(['Payments']),
  environments: () => Promise.resolve(['PROD'] as Env[]),
};

function renderDialog(mode: (typeof modes)[number]) {
  return renderWithProviders(
    <DeclareInstanceDialog
      open
      solutionId="cache"
      existing={CACHE_INSTANCES}
      packageVersions={[6, 7]}
      hierarchyRepository={HIERARCHY}
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
