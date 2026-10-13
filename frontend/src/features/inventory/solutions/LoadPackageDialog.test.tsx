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
  return {
    list: () => Promise.resolve([]),
    getPackage: () => Promise.reject(new Error('not used in this test')),
    load,
  };
}

const OK_RESULT: PackageLoadResult = {
  solutionId: 'widget-mesh',
  packageVersion: [1],
  signature: 'unsigned',
  stateVersion: 1,
  correlationId: 'corr-1',
};

describe('LoadPackageDialog', () => {
  it('is keyboard-operable — Enter on the drop zone opens the file picker', () => {
    const clickSpy = vi
      .spyOn(HTMLInputElement.prototype, 'click')
      .mockImplementation(() => undefined);
    const repository = stubRepository(() => Promise.resolve(OK_RESULT));

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

  it('loads a valid package and renders the admitted result — loadPackage has no server-side signature check (v1 is always unsigned)', async () => {
    const load = vi.fn<SolutionsRepository['load']>(() => Promise.resolve(OK_RESULT));
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
      expect(screen.getByText('Loaded')).not.toBeNull();
    });
    expect(load).toHaveBeenCalledTimes(1);
    // The parsed document, not file metadata (`loadPackage`'s body is `{ package }`).
    expect(load.mock.calls[0]?.[0]).toMatchObject({ solution: VALID_SOLUTION_PACKAGE.solution });
    expect(screen.getByText('Package format is valid')).not.toBeNull();
    expect(screen.getByText('widget-mesh')).not.toBeNull();
    expect(screen.getByText('Components')).not.toBeNull();
    expect(screen.getByText('Fields')).not.toBeNull();
    expect(screen.getByText('Fields: 1 · Actions: 0 · Metrics: 0')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Confirm and load' }).hasAttribute('disabled')).toBe(
      false,
    );
  });

  it('shows an inline error and keeps Load disabled when the server rejects the load', async () => {
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
    const repository = stubRepository(() => Promise.resolve(OK_RESULT));

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

  it('calls onLoaded with the load result when Load is clicked', async () => {
    const onLoaded = vi.fn();
    const repository = stubRepository(() => Promise.resolve(OK_RESULT));

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

    expect(onLoaded).toHaveBeenCalledWith(OK_RESULT);
  });

  it('shows every validation issue and never calls the repository for an invalid package', async () => {
    const load = vi.fn(() => Promise.resolve(OK_RESULT));
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
    const load = vi.fn(() => Promise.resolve(OK_RESULT));
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
    const repository = stubRepository(() => Promise.resolve(OK_RESULT));

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
