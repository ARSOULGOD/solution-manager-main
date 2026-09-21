import { describe, expect, it } from 'vitest';

import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser } from '@shared/testdoubles/data/rbac';

import {
  hasErrors,
  partitionMembersByOrganization,
  selectableMembers,
  validateGroupForm,
} from './groupValidation';

function group(overrides: Partial<UserGroup> = {}): UserGroup {
  return {
    id: 'grp-1',
    name: 'Payments Operations',
    organization: 'Acme Financial',
    status: 'active',
    source: 'local',
    memberCount: 0,
    assignmentCount: 0,
    createdBy: 'Devon Ramirez',
    createdAt: '2026-06-02T09:14:00Z',
    updatedAt: '2026-06-02T09:14:00Z',
    ...overrides,
  };
}

function user(overrides: Partial<LocalUser> = {}): LocalUser {
  return {
    id: 'u-1',
    name: 'Mira Chen',
    email: 'mira@acme.example',
    status: 'active',
    lastSignIn: '1h ago',
    createdBy: 'Devon Ramirez',
    organization: 'Acme Financial',
    ...overrides,
  };
}

const base = {
  name: 'Payments Ops',
  description: '',
  organization: 'Acme Financial',
  memberIds: [],
};

describe('validateGroupForm', () => {
  it('accepts a well-formed group', () => {
    expect(hasErrors(validateGroupForm(base, { existingGroups: [] }))).toBe(false);
  });

  it('requires a name, and treats whitespace as absent', () => {
    expect(validateGroupForm({ ...base, name: '   ' }, { existingGroups: [] }).name).toBe(
      'A group name is required.',
    );
  });

  it('requires an organization', () => {
    expect(
      validateGroupForm({ ...base, organization: '' }, { existingGroups: [] }).organization,
    ).toBeDefined();
  });

  it('rejects a name with characters that would be unreadable in an audit line', () => {
    expect(validateGroupForm({ ...base, name: 'ops/prod' }, { existingGroups: [] }).name).toMatch(
      /letters, numbers/,
    );
  });

  it('rejects a duplicate name in the same organization, case-insensitively', () => {
    const errors = validateGroupForm(
      { ...base, name: 'payments OPERATIONS' },
      { existingGroups: [group()] },
    );
    expect(errors.name).toBe('A group called this already exists in Acme Financial.');
  });

  it('compares uniqueness AFTER trimming the stored name too', () => {
    const errors = validateGroupForm(
      { ...base, name: 'Payments Operations' },
      { existingGroups: [group({ name: '  Payments Operations  ' })] },
    );
    expect(errors.name).toBe('A group called this already exists in Acme Financial.');
  });

  it('reports the whitespace rule before the duplicate rule — a padded name is invalid first', () => {
    const errors = validateGroupForm(
      { ...base, name: '  payments OPERATIONS ' },
      { existingGroups: [group()] },
    );
    expect(errors.name).toBe('A group name cannot begin or end with a space.');
  });

  it('allows the same name in a DIFFERENT organization — uniqueness is per organization', () => {
    const errors = validateGroupForm(
      { ...base, name: 'Payments Operations', organization: 'Globex' },
      { existingGroups: [group()] },
    );
    expect(errors.name).toBeUndefined();
  });

  it('accepts the ratified punctuation set — & and the apostrophe included', () => {
    for (const name of [
      'Payments & Ledger',
      "O'Brien's Team",
      'Ops (EU).v2_beta-1',
      '\u00dcr\u00fcn Ekibi 3',
    ]) {
      expect(validateGroupForm({ ...base, name }, { existingGroups: [] }).name).toBeUndefined();
    }
  });

  it('rejects the four forbidden characters', () => {
    // `/` collides with the scope-path separator; `\\` and `:` are ambiguous
    // in a log line; a control character is unreadable in one.
    for (const name of ['ops/prod', 'ops\\prod', 'ops:prod', 'ops\u0007prod']) {
      expect(validateGroupForm({ ...base, name }, { existingGroups: [] }).name).toBeDefined();
    }
  });

  it('rejects leading or trailing whitespace rather than silently trimming it', () => {
    // Trimming is what the UNIQUENESS comparison does. Validity refuses, so
    // the operator is told the rule instead of getting a name in the audit
    // line that is not the one they typed (`FE-D48`).
    expect(validateGroupForm({ ...base, name: ' Ledger Ops' }, { existingGroups: [] }).name).toBe(
      'A group name cannot begin or end with a space.',
    );
    expect(validateGroupForm({ ...base, name: 'Ledger Ops ' }, { existingGroups: [] }).name).toBe(
      'A group name cannot begin or end with a space.',
    );
  });

  it('accepts 128 characters and rejects 129', () => {
    expect(
      validateGroupForm({ ...base, name: 'a'.repeat(128) }, { existingGroups: [] }).name,
    ).toBeUndefined();
    expect(
      validateGroupForm({ ...base, name: 'a'.repeat(129) }, { existingGroups: [] }).name,
    ).toBeDefined();
  });

  it('does not flag a group as a duplicate of itself while editing', () => {
    const errors = validateGroupForm(
      { ...base, name: 'Payments Operations' },
      { existingGroups: [group()], editingGroupId: 'grp-1' },
    );
    expect(errors.name).toBeUndefined();
  });
});

describe('selectableMembers', () => {
  const users = [
    user({ id: 'u-mira' }),
    user({ id: 'u-sam', organization: 'Globex' }),
    user({ id: 'u-priya', status: 'deactivated' }),
    user({ id: 'u-devon' }),
  ];

  it('offers only active users of the group’s own organization', () => {
    expect(selectableMembers(users, 'Acme Financial').map((u) => u.id)).toEqual([
      'u-mira',
      'u-devon',
    ]);
  });

  it('never offers someone who is already a member — no duplicate membership is possible', () => {
    expect(selectableMembers(users, 'Acme Financial', ['u-mira']).map((u) => u.id)).toEqual([
      'u-devon',
    ]);
  });
});

describe('partitionMembersByOrganization', () => {
  it('keeps members of the new organization and reports the ones dropped', () => {
    const users = [user({ id: 'u-mira' }), user({ id: 'u-sam', organization: 'Globex' })];
    expect(partitionMembersByOrganization(['u-mira', 'u-sam'], users, 'Acme Financial')).toEqual({
      kept: ['u-mira'],
      dropped: ['u-sam'],
    });
  });
});
