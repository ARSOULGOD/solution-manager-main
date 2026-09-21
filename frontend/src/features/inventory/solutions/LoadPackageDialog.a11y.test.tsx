import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, waitFor } from '../../../test/renderWithProviders';
import { validSolutionPackageFile } from '../../../test/solutionPackageFile';

import { LoadPackageDialog } from './LoadPackageDialog';
import type { SolutionsRepository } from './solutionsRepository';

const modes = ['light', 'dark'] as const;

function stubRepository(): SolutionsRepository {
  return {
    list: () => Promise.resolve([]),
    load: () =>
      Promise.resolve({
        verified: true,
        devMode: false,
        signatureFingerprint: '3af9…c210',
        message: 'Signature verified · Solace-signed',
        correlationId: 'corr-a11y',
      }),
  };
}

describe('LoadPackageDialog a11y', () => {
  it.each(modes)('drop-zone state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={stubRepository()}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('verified-result state has no violations in %s mode', async (mode) => {
    const { container, getByText } = renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={stubRepository()}
      />,
      { mode },
    );

    const input = document.querySelector('input[type="file"]');
    await userEvent.upload(input as HTMLInputElement, validSolutionPackageFile());
    await waitFor(() => {
      expect(getByText('Signature verified · Solace-signed')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
