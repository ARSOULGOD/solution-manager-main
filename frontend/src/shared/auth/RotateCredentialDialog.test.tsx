import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { RotateCredentialDialog } from './RotateCredentialDialog';

describe('RotateCredentialDialog', () => {
  it('submits current and new credential and calls onRotate on success', async () => {
    const onRotate = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<RotateCredentialDialog open onRotate={onRotate} />);

    await userEvent.type(screen.getByLabelText(/current/i), 'old-secret');
    await userEvent.type(screen.getByLabelText(/new/i), 'new-secret-12chars');
    await userEvent.click(screen.getByRole('button', { name: /update/i }));

    await waitFor(() => expect(onRotate).toHaveBeenCalledWith('old-secret', 'new-secret-12chars'));
  });
});
