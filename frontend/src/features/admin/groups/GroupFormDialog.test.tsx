import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { GroupFormDialog } from './GroupFormDialog';
import { GROUPS, PAYMENTS_OPS, USERS } from './groupsTestData';

const ORGANIZATIONS = ['Acme Financial', 'Globex'];

function createGroupStub(): AdminRepository['createGroup'] {
  return (draft) =>
    Promise.resolve({
      group: {
        ...PAYMENTS_OPS,
        id: 'grp-new',
        name: draft.name,
        organization: draft.organization,
        memberCount: draft.memberIds.length,
      },
      correlationId: 'corr-group-1',
    });
}

function renderCreate(overrides: Partial<AdminRepository> = {}, onClose = vi.fn()) {
  return {
    onClose,
    ...renderWithProviders(
      <GroupFormDialog
        repository={stubAdminRepository({ createGroup: createGroupStub(), ...overrides })}
        organizations={ORGANIZATIONS}
        users={USERS}
        existingGroups={GROUPS}
        onClose={onClose}
      />,
    ),
  };
}

function field(label: string): HTMLElement {
  return screen.getByLabelText(new RegExp(`^${label}\\s*\\*?$`));
}

describe('GroupFormDialog — create', () => {
  it('creates a group with a name, an organization and initial members', async () => {
    const user = userEvent.setup();
    const createGroup = vi.fn<AdminRepository['createGroup']>(createGroupStub());
    const { onClose } = renderCreate({ createGroup });

    await user.type(field('Group name'), 'Payments Reviewers');
    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(await screen.findByRole('checkbox', { name: /Mira Chen/ }));
    await user.click(screen.getByRole('button', { name: 'Create group' }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(createGroup).toHaveBeenCalledWith({
      name: 'Payments Reviewers',
      description: '',
      organization: 'Acme Financial',
      memberIds: ['u-mira'],
    });
    expect(useOutcomeStore.getState().byResource['group:grp-new']?.title).toBe(
      'Group Payments Reviewers created',
    );
  });

  it('refuses a name padded with whitespace rather than trimming it silently', async () => {
    // `FE-D48`: uniqueness compares after trim, but VALIDITY refuses
    // surrounding whitespace, so the audit line always carries the name the
    // operator actually typed.
    const user = userEvent.setup();
    const createGroup = vi.fn<AdminRepository['createGroup']>(createGroupStub());
    renderCreate({ createGroup });

    await user.type(field('Group name'), '  Payments Reviewers  ');
    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(screen.getByRole('button', { name: 'Create group' }));

    expect(
      await screen.findByText('A group name cannot begin or end with a space.'),
    ).not.toBeNull();
    expect(createGroup).not.toHaveBeenCalled();
  });

  it('reports a missing name and a missing organization as field errors', async () => {
    const user = userEvent.setup();
    const createGroup = vi.fn<AdminRepository['createGroup']>(createGroupStub());
    renderCreate({ createGroup });

    await user.click(screen.getByRole('button', { name: 'Create group' }));

    expect(await screen.findByText('A group name is required.')).not.toBeNull();
    expect(screen.getByText('An organization is required.')).not.toBeNull();
    expect(createGroup).not.toHaveBeenCalled();
  });

  it('reports a duplicate name in the same organization, case-insensitively', async () => {
    const user = userEvent.setup();
    renderCreate();

    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.type(field('Group name'), 'payments operations');
    await user.tab();

    expect(
      await screen.findByText('A group called this already exists in Acme Financial.'),
    ).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Create group' })).not.toBeDisabled();
    // Blocked at submit, not by a silently disabled button.
    await user.click(screen.getByRole('button', { name: 'Create group' }));
    expect(
      screen.getByText('A group called this already exists in Acme Financial.'),
    ).not.toBeNull();
  });

  it('rejects characters that would be unreadable in an audit line', async () => {
    const user = userEvent.setup();
    renderCreate();

    await user.type(field('Group name'), 'ops/prod');
    await user.tab();
    expect(await screen.findByText(/Use letters, numbers, spaces/)).not.toBeNull();
  });

  it('offers only active users of the selected organization', async () => {
    const user = userEvent.setup();
    renderCreate();

    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));

    expect(await screen.findByRole('checkbox', { name: /Mira Chen/ })).not.toBeNull();
    // Sam is in the same Organization; Priya is deactivated.
    expect(screen.getByRole('checkbox', { name: /Sam Okoye/ })).not.toBeNull();
    expect(screen.queryByRole('checkbox', { name: /Priya Nair/ })).toBeNull();
  });

  it('clears incompatible members when the organization changes, and says how many', async () => {
    const user = userEvent.setup();
    renderCreate();

    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(await screen.findByRole('checkbox', { name: /Mira Chen/ }));

    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Globex' }));

    expect(
      await screen.findByText(
        '1 selected member is not in Globex and was removed from the selection.',
      ),
    ).not.toBeNull();

    // The selection is empty. `MemberPicker` short-circuits to its empty state
    // before rendering the "N selected" counter, so that counter is not
    // observable here — but the empty state is the stronger assertion anyway:
    // `GroupFormDialog` builds `pickerCandidates` as the union of the eligible
    // users AND anyone still selected, so a member left over from Acme would
    // force a checkbox row and this message could not appear.
    expect(screen.getByText('No active users in this organization yet.')).not.toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('prevents a duplicate submission while the request is in flight', async () => {
    const user = userEvent.setup();
    let resolve: (() => void) | undefined;
    const createGroup = vi.fn<AdminRepository['createGroup']>(
      () =>
        new Promise((r) => {
          resolve = () => {
            r({ group: { ...PAYMENTS_OPS, id: 'grp-new' }, correlationId: 'c' });
          };
        }),
    );
    renderCreate({ createGroup });

    await user.type(field('Group name'), 'Payments Reviewers');
    await user.click(field('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));

    await user.click(screen.getByRole('button', { name: 'Create group' }));
    const pending = await screen.findByRole('button', { name: 'Creating…' });
    expect((pending as HTMLButtonElement).disabled).toBe(true);
    expect(createGroup).toHaveBeenCalledTimes(1);
    resolve?.();
  });
});

describe('GroupFormDialog — edit', () => {
  function renderEdit(overrides: Partial<AdminRepository> = {}, onClose = vi.fn()) {
    return {
      onClose,
      ...renderWithProviders(
        <GroupFormDialog
          repository={stubAdminRepository({
            updateGroup: (_id, draft) =>
              Promise.resolve({
                group: { ...PAYMENTS_OPS, name: draft.name },
                correlationId: 'corr-group-2',
              }),
            ...overrides,
          })}
          group={PAYMENTS_OPS}
          organizations={ORGANIZATIONS}
          users={USERS}
          existingGroups={GROUPS}
          initialMemberIds={['u-mira']}
          onClose={onClose}
        />,
      ),
    };
  }

  it('shows the organization read-only and explains why', () => {
    renderEdit();

    const organization = field('Organization') as HTMLInputElement;
    expect(organization.value).toBe('Acme Financial');
    expect(organization.readOnly).toBe(true);
    expect(screen.getByText(/Set at creation and not editable/)).not.toBeNull();
  });

  it('saves a new name and membership', async () => {
    const user = userEvent.setup();
    const updateGroup = vi.fn<AdminRepository['updateGroup']>((_id, draft) =>
      Promise.resolve({ group: { ...PAYMENTS_OPS, name: draft.name }, correlationId: 'c' }),
    );
    const { onClose } = renderEdit({ updateGroup });

    await user.clear(field('Group name'));
    await user.type(field('Group name'), 'Ledger Ops');
    await user.click(await screen.findByRole('checkbox', { name: /Devon Ramirez/ }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(updateGroup).toHaveBeenCalledWith(PAYMENTS_OPS.id, {
      name: 'Ledger Ops',
      description: 'Day-to-day operators for the Payments ledger.',
      memberIds: ['u-mira', 'u-devon'],
    });
  });

  it('does not flag the group as a duplicate of itself', async () => {
    const user = userEvent.setup();
    renderEdit();

    await user.click(field('Group name'));
    await user.tab();
    expect(screen.queryByText(/already exists in Acme Financial/)).toBeNull();
  });

  it('protects an in-progress edit from a scrim click', async () => {
    const user = userEvent.setup();
    const { onClose } = renderEdit();

    await user.type(field('Group name'), '!');
    // A dirty form refuses a backdrop close; Escape still works.
    await user.click(document.querySelector('.MuiBackdrop-root') as HTMLElement);
    expect(onClose).not.toHaveBeenCalled();
  });
});
