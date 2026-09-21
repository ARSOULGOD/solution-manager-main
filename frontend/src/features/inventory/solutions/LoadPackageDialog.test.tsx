import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import {
  VALID_SOLUTION_PACKAGE,
  validSolutionPackageFile,
} from '../../../test/solutionPackageFile';

import { LoadPackageDialog } from './LoadPackageDialog';
import type { PackageLoadResult, SolutionsRepository } from './solutionsRepository';

function stubRepository(load: SolutionsRepository['load']): SolutionsRepository {
  return { list: () => Promise.resolve([]), load };
}

describe('LoadPackageDialog', () => {
  it('is keyboard-operable — Enter on the drop zone opens the file picker', () => {
    const clickSpy = vi
      .spyOn(HTMLInputElement.prototype, 'click')
      .mockImplementation(() => undefined);
    const repository = stubRepository(() =>
      Promise.resolve({ verified: true, devMode: false, message: 'ok', correlationId: 'c1' }),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    const dropzone = screen.getByRole('button', { name: 'Drop a package file here' });
    dropzone.focus();
    fireEvent.keyDown(dropzone, { key: 'Enter' });

    expect(clickSpy).toHaveBeenCalled();
    clickSpy.mockRestore();
  });

  it('verifies a signed package on selection and renders the signed result', async () => {
    const load = vi.fn(() =>
      Promise.resolve<PackageLoadResult>({
        verified: true,
        devMode: false,
        signatureFingerprint: '3af9…c210',
        message: 'Signature verified · Solace-signed',
        correlationId: 'corr-1',
      }),
    );
    const repository = stubRepository(load);

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    const input = document.querySelector('input[type="file"]');
    expect(input).toBeInstanceOf(HTMLInputElement);
    await userEvent.upload(input as HTMLInputElement, validSolutionPackageFile());

    await waitFor(() => {
      expect(screen.getByText('Signature verified · Solace-signed')).not.toBeNull();
    });
    expect(load).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Package format is valid')).not.toBeNull();
    expect(screen.getByText('widget-mesh')).not.toBeNull();
    expect(screen.getByText('Components')).not.toBeNull();
    expect(screen.getByText('Fields')).not.toBeNull();
    expect(screen.getByText('Fields: 1 · Actions: 0 · Metrics: 0')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled')).toBe(
      false,
    );
  });

  it('admits an unsigned package only under the dev-mode banner, never as "Signed"', async () => {
    const repository = stubRepository(() =>
      Promise.resolve({
        verified: false,
        devMode: true,
        message: 'No Solace signature — admitted only in an explicit development mode.',
        correlationId: 'corr-2',
      }),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      validSolutionPackageFile('nightly.json'),
    );

    await waitFor(() => {
      expect(screen.getByText('Unsigned · admitted in development mode')).not.toBeNull();
    });
    expect(screen.queryByText('Signature verified · Solace-signed')).toBeNull();
    // Vitest always runs with import.meta.env.DEV === true (devMockAdapter.ts's own note), so a
    // devMode:true result is admitted here — Load is enabled, not rejected.
    expect(screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled')).toBe(
      false,
    );
  });

  it('shows an inline error and keeps Load disabled when verification fails', async () => {
    const repository = stubRepository(() =>
      Promise.reject(new ApiError({ status: 500, message: 'verification unavailable' })),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      validSolutionPackageFile(),
    );

    await waitFor(() => {
      expect(screen.getByText('verification unavailable')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled')).toBe(
      true,
    );
  });

  it('"Choose another" resets back to the drop zone', async () => {
    const repository = stubRepository(() =>
      Promise.resolve({ verified: true, devMode: false, message: 'ok', correlationId: 'c3' }),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      validSolutionPackageFile(),
    );
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Choose another' })).not.toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Choose another' }));

    expect(screen.getByRole('button', { name: 'Drop a package file here' })).not.toBeNull();
  });

  it('calls onLoaded with the verification result when Load is clicked', async () => {
    const onLoaded = vi.fn();
    const repository = stubRepository(() =>
      Promise.resolve({ verified: true, devMode: false, message: 'ok', correlationId: 'corr-4' }),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={onLoaded}
        repository={repository}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      validSolutionPackageFile(),
    );
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled'),
      ).toBe(false);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Confirm and load' }));

    expect(onLoaded).toHaveBeenCalledWith({
      verified: true,
      devMode: false,
      message: 'ok',
      correlationId: 'corr-4',
    });
  });

  it('shows every validation issue and never calls the repository for an invalid package', async () => {
    const load = vi.fn(() =>
      Promise.resolve<PackageLoadResult>({
        verified: true,
        devMode: false,
        message: 'ok',
        correlationId: 'c-invalid',
      }),
    );
    const invalidPackage = {
      ...VALID_SOLUTION_PACKAGE,
      components: [
        {
          ...VALID_SOLUTION_PACKAGE.components[0],
          fields: [
            {
              key: 'BROKEN',
              type: 'date',
              label: 'Misplaced label',
            },
          ],
        },
      ],
    };

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={stubRepository(load)}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      new File([JSON.stringify(invalidPackage)], 'invalid.json', { type: 'application/json' }),
    );

    await waitFor(() => {
      expect(screen.getByText('Package format needs correction')).not.toBeNull();
    });
    expect(screen.getByText('/components/0/fields/0/type')).not.toBeNull();
    expect(screen.getByText('/components/0/fields/0/label')).not.toBeNull();
    expect(load).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled')).toBe(
      true,
    );
  });

  it('rejects a package with no component before verification', async () => {
    const load = vi.fn(() =>
      Promise.resolve<PackageLoadResult>({
        verified: true,
        devMode: false,
        message: 'ok',
        correlationId: 'c-empty',
      }),
    );
    const emptyPackage = { ...VALID_SOLUTION_PACKAGE, components: [] };

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={() => undefined}
        onLoaded={() => undefined}
        repository={stubRepository(load)}
      />,
    );

    await userEvent.upload(
      document.querySelector('input[type="file"]') as HTMLInputElement,
      new File([JSON.stringify(emptyPackage)], 'empty.json', { type: 'application/json' }),
    );

    await waitFor(() => {
      expect(screen.getByText('/components')).not.toBeNull();
    });
    expect(load).not.toHaveBeenCalled();
  });

  it('calls onClose when Cancel is clicked', () => {
    const onClose = vi.fn();
    const repository = stubRepository(() =>
      Promise.resolve({ verified: true, devMode: false, message: 'ok', correlationId: 'c5' }),
    );

    renderWithProviders(
      <LoadPackageDialog
        open
        onClose={onClose}
        onLoaded={() => undefined}
        repository={repository}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
  });
});
